package app.gamenative.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ConfigInfo
import app.gamenative.data.FileChangeLists
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.db.PluviaDatabase
import app.gamenative.enums.AppType
import app.gamenative.enums.OS
import app.gamenative.enums.PathType
import app.gamenative.enums.ReleaseState
import app.gamenative.enums.SaveLocation
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import app.gamenative.enums.SyncResult
import `in`.dragonbra.javasteam.util.crypto.CryptoHelper
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import java.util.Date
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runBlockingTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.Field
import java.util.EnumSet
import java.util.concurrent.CompletableFuture

@RunWith(RobolectricTestRunner::class)
class SteamAutoCloudTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var saveFilesDir: File
    private lateinit var db: PluviaDatabase
    private lateinit var mockSteamService: SteamService
    private lateinit var mockSteamCloud: SteamCloud
    private val testAppId = "STEAM_123456"
    private val steamAppId = 123456
    private val clientId = 1L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File.createTempFile("steam_autocloud_test_", null)
        tempDir.delete()
        tempDir.mkdirs()

        // Set up DownloadService paths
        DownloadService.populateDownloadService(context)
        File(SteamService.internalAppInstallPath).mkdirs()
        SteamService.externalAppInstallPath.takeIf { it.isNotBlank() }?.let { File(it).mkdirs() }

        // Set up ImageFs
        val imageFs = ImageFs.find(context)
        val homeDir = File(imageFs.rootDir, "home")
        homeDir.mkdirs()

        val containerDir = File(homeDir, "${ImageFs.USER}-${testAppId}")
        containerDir.mkdirs()

        // Create container
        val container = Container(testAppId)
        container.setRootDir(containerDir)
        container.name = "Test Container"
        container.saveData()

        // Create save files directory structure matching Windows path
        // %WinMyDocuments%My Games/TestGame/Steam/76561198025127569
        val wineprefix = File(imageFs.wineprefix)
        wineprefix.mkdirs()
        val dosDevices = File(wineprefix, "dosdevices")
        dosDevices.mkdirs()
        val cDrive = File(dosDevices, "c:")
        cDrive.mkdirs()
        val users = File(cDrive, "users")
        users.mkdirs()
        val xuser = File(users, "xuser")
        xuser.mkdirs()
        val documents = File(xuser, "Documents")
        documents.mkdirs()
        val myGames = File(documents, "My Games")
        myGames.mkdirs()
        val testGame = File(myGames, "TestGame")
        testGame.mkdirs()
        val steam = File(testGame, "Steam")
        steam.mkdirs()
        val steamId = File(steam, "76561198025127569")
        steamId.mkdirs()
        val saveGames = File(steamId, "SaveGames")
        saveGames.mkdirs()
        saveFilesDir = saveGames

        // Set up in-memory database
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Create test SteamApp with 3 patterns sharing the same prefix
        val saveFilePatterns = listOf(
            SaveFilePattern(
                root = PathType.WinMyDocuments,
                path = "My Games/TestGame/Steam/76561198025127569",
                pattern = "Capture*.sav",
            ),
            SaveFilePattern(
                root = PathType.WinMyDocuments,
                path = "My Games/TestGame/Steam/76561198025127569",
                pattern = "*SaveData*.sav",
            ),
            SaveFilePattern(
                root = PathType.WinMyDocuments,
                path = "My Games\\TestGame\\Steam\\76561198025127569",
                pattern = "SystemData_0.sav",
            ),
        )

        val testApp = SteamApp(
            id = steamAppId,
            name = "Test Game",
            config = ConfigInfo(installDir = "123456"),
            type = AppType.game,
            osList = EnumSet.of(OS.windows),
            releaseState = ReleaseState.released,
            ufs = UFS(saveFilePatterns = saveFilePatterns),
        )

        runBlocking {
            db.steamAppDao().insert(testApp)
        }

        // Create test files
        // Pattern 2: *SaveData*.sav should match 4 files
        File(saveGames, "AutoSaveData.sav").writeBytes("autosave content".toByteArray())
        File(saveGames, "SaveData_0.sav").writeBytes("savedata0 content".toByteArray())
        File(saveGames, "ContinueSaveData.sav").writeBytes("continue content".toByteArray())
        File(saveGames, "SaveData_1.sav").writeBytes("savedata1 content".toByteArray())

        // Pattern 3: SystemData_0.sav should match 1 file
        File(saveGames, "SystemData_0.sav").writeBytes("systemdata content".toByteArray())

        // Pattern 1: Capture*.sav should match 0 files (none created)

        // Mock SteamService
        mockSteamService = mock<SteamService>()
        whenever(mockSteamService.appDao).thenReturn(db.steamAppDao())
        whenever(mockSteamService.fileChangeListsDao).thenReturn(db.appFileChangeListsDao())
        whenever(mockSteamService.changeNumbersDao).thenReturn(db.appChangeNumbersDao())
        whenever(mockSteamService.db).thenReturn(db)

        val mockSteamClient = mock<`in`.dragonbra.javasteam.steam.steamclient.SteamClient>()
        val mockSteamID = mock<`in`.dragonbra.javasteam.types.SteamID>()
        whenever(mockSteamService.steamClient).thenReturn(mockSteamClient)
        whenever(mockSteamClient.steamID).thenReturn(mockSteamID)

        // Set SteamService.instance using reflection
        try {
            val instanceField = SteamService::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, mockSteamService)
        } catch (e: Exception) {
            fail("Failed to set SteamService.instance: ${e.message}")
        }

        // Use MockK for SteamCloud - handles Kotlin default parameters properly
        mockSteamCloud = mockk<SteamCloud>(relaxed = true)

        // Mock empty AppFileChangeList (no cloud files)
        val emptyAppFileChangeList = mock<AppFileChangeList>()
        whenever(emptyAppFileChangeList.currentChangeNumber).thenReturn(0)
        whenever(emptyAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(emptyAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(emptyAppFileChangeList.pathPrefixes).thenReturn(emptyList())
        whenever(emptyAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(emptyAppFileChangeList.files).thenReturn(emptyList())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(emptyAppFileChangeList)

        // Mock upload batch methods
        val mockUploadBatchResponse = mock<`in`.dragonbra.javasteam.steam.handlers.steamcloud.AppUploadBatchResponse>()
        whenever(mockUploadBatchResponse.batchID).thenReturn(1)
        whenever(mockUploadBatchResponse.appChangeNumber).thenReturn(1)

        every { mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockUploadBatchResponse)

        val mockFileUploadInfo = mock<`in`.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadInfo>()
        whenever(mockFileUploadInfo.blockRequests).thenReturn(emptyList())

        every { mockSteamCloud.beginFileUpload(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockFileUploadInfo)

        every { mockSteamCloud.commitFileUpload(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(true)

        every { mockSteamCloud.completeAppUploadBatch(any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(Unit)

        // Initialize database: set change number to 0 to match cloud (so we test upload path)
        // Insert an empty file-change-list row so getByAppId() is non-null and diff detects local changes
        runBlocking {
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, 0))
            db.appFileChangeListsDao().insert(steamAppId, emptyList())
        }
    }

    @After
    fun tearDown() {
        // Clean up ImageFs directory first (files created in wineprefix)
        // This is critical because ImageFs uses context.getFilesDir() which is inside Robolectric's temp directory
        try {
            val imageFs = ImageFs.find(context)
            val imageFsRoot = imageFs.rootDir
            if (imageFsRoot.exists()) {
                imageFsRoot.deleteRecursively()
            }

            // Reset ImageFs singleton to prevent issues across tests
            val instanceField = ImageFs::class.java.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            instanceField.set(null, null)
        } catch (e: Exception) {
            // Ignore cleanup errors - files might be locked, but Robolectric will handle it
        }

        // Clean up temp directory
        try {
            tempDir.deleteRecursively()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }

        // Close database
        db.close()

        // Give file system a moment to release locks (especially important in CI)
        Thread.sleep(50)
    }

    @Test
    fun testMultiplePatternsSamePrefix_returnsAllFiles() = runBlocking {
        // Get the test app
        val testApp = db.steamAppDao().findApp(steamAppId)!!

        // Create prefixToPath function that maps to our test directory structure
        val prefixToPath: (String) -> String = { prefix ->
            when {
                prefix == "WinMyDocuments" -> {
                    val imageFs = ImageFs.find(context)
                    val wineprefix = File(imageFs.wineprefix)
                    val dosDevices = File(wineprefix, "dosdevices")
                    val cDrive = File(dosDevices, "c:")
                    val users = File(cDrive, "users")
                    val xuser = File(users, "xuser")
                    val documents = File(xuser, "Documents")
                    documents.absolutePath
                }
                else -> tempDir.absolutePath
            }
        }

        // Call syncUserFiles
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        // Verify result
        assertNotNull("Result should not be null", result)
        assertEquals("Should upload 5 files (4 from pattern 2 + 1 from pattern 3)", 5, result!!.filesUploaded)
        assertTrue("Uploads should be completed", result.uploadsCompleted)
        assertEquals("Should have 5 files managed", 5, result.filesManaged)
    }

//    @Test
    fun testDownloadCloudSavesOnFirstBoot() = runBlocking {
        // Clear existing files and database state
        saveFilesDir.listFiles()?.forEach { it.delete() }
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
        }

        // Set local change number to 0 (first boot scenario)
        runBlocking {
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, 0))
            db.appFileChangeListsDao().insert(steamAppId, emptyList())
        }

        val testApp = db.steamAppDao().findApp(steamAppId)!!

        // Create cloud files to download
        val cloudFile1Content = "cloud save file 1 content".toByteArray()
        val cloudFile2Content = "cloud save file 2 content".toByteArray()
        val cloudFile3Content = "cloud save file 3 content".toByteArray()

        val cloudFile1Sha = CryptoHelper.shaHash(cloudFile1Content)
        val cloudFile2Sha = CryptoHelper.shaHash(cloudFile2Content)
        val cloudFile3Sha = CryptoHelper.shaHash(cloudFile3Content)

        // Create mock AppFileInfo instances
        val mockFile1 = mock<AppFileInfo>()
        whenever(mockFile1.filename).thenReturn("cloud_save_1.sav")
        whenever(mockFile1.shaFile).thenReturn(cloudFile1Sha)
        whenever(mockFile1.pathPrefixIndex).thenReturn(0)
        whenever(mockFile1.timestamp).thenReturn(Date())
        whenever(mockFile1.rawFileSize).thenReturn(cloudFile1Content.size)

        val mockFile2 = mock<AppFileInfo>()
        whenever(mockFile2.filename).thenReturn("cloud_save_2.sav")
        whenever(mockFile2.shaFile).thenReturn(cloudFile2Sha)
        whenever(mockFile2.pathPrefixIndex).thenReturn(0)
        whenever(mockFile2.timestamp).thenReturn(Date())
        whenever(mockFile2.rawFileSize).thenReturn(cloudFile2Content.size)

        val mockFile3 = mock<AppFileInfo>()
        whenever(mockFile3.filename).thenReturn("cloud_save_3.sav")
        whenever(mockFile3.shaFile).thenReturn(cloudFile3Sha)
        whenever(mockFile3.pathPrefixIndex).thenReturn(0)
        whenever(mockFile3.timestamp).thenReturn(Date())
        whenever(mockFile3.rawFileSize).thenReturn(cloudFile3Content.size)

        // Create mock AppFileChangeList with cloud files
        val cloudChangeNumber = 5
        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(cloudChangeNumber.toLong())
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(listOf("%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569"))
        whenever(mockAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(mockAppFileChangeList.files).thenReturn(listOf(mockFile1, mockFile2, mockFile3))

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockAppFileChangeList)

        // Mock FileDownloadInfo for each file
        val mockDownloadInfo1 = mock<FileDownloadInfo>()
        whenever(mockDownloadInfo1.urlHost).thenReturn("test.example.com")
        whenever(mockDownloadInfo1.urlPath).thenReturn("/download/file1")
        whenever(mockDownloadInfo1.useHttps).thenReturn(true)
        whenever(mockDownloadInfo1.requestHeaders).thenReturn(emptyList())
        whenever(mockDownloadInfo1.fileSize).thenReturn(cloudFile1Content.size)
        whenever(mockDownloadInfo1.rawFileSize).thenReturn(cloudFile1Content.size)

        val mockDownloadInfo2 = mock<FileDownloadInfo>()
        whenever(mockDownloadInfo2.urlHost).thenReturn("test.example.com")
        whenever(mockDownloadInfo2.urlPath).thenReturn("/download/file2")
        whenever(mockDownloadInfo2.useHttps).thenReturn(true)
        whenever(mockDownloadInfo2.requestHeaders).thenReturn(emptyList())
        whenever(mockDownloadInfo2.fileSize).thenReturn(cloudFile2Content.size)
        whenever(mockDownloadInfo2.rawFileSize).thenReturn(cloudFile2Content.size)

        val mockDownloadInfo3 = mock<FileDownloadInfo>()
        whenever(mockDownloadInfo3.urlHost).thenReturn("test.example.com")
        whenever(mockDownloadInfo3.urlPath).thenReturn("/download/file3")
        whenever(mockDownloadInfo3.useHttps).thenReturn(true)
        whenever(mockDownloadInfo3.requestHeaders).thenReturn(emptyList())
        whenever(mockDownloadInfo3.fileSize).thenReturn(cloudFile3Content.size)
        whenever(mockDownloadInfo3.rawFileSize).thenReturn(cloudFile3Content.size)

        // Mock clientFileDownload to return appropriate download info based on filename in the path
        var downloadCallCount = 0
        every { mockSteamCloud.clientFileDownload(any(), any()) } answers {
            downloadCallCount++
            when (downloadCallCount) {
                1 -> CompletableFuture.completedFuture(mockDownloadInfo1)
                2 -> CompletableFuture.completedFuture(mockDownloadInfo2)
                3 -> CompletableFuture.completedFuture(mockDownloadInfo3)
                else -> CompletableFuture.completedFuture(mockDownloadInfo1) // fallback
            }
        }

        // Mock HTTP client to return file content
        val mockHttpClient = mock<OkHttpClient>()
        val mockCall = mock<Call>()
        whenever(mockHttpClient.newCall(any())).thenReturn(mockCall)

        // Create mock responses with file content
        val responseBody1 = ResponseBody.create(null, cloudFile1Content)
        val response1 = Response.Builder()
            .request(okhttp3.Request.Builder().url("https://test.example.com/download/file1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody1)
            .build()

        val responseBody2 = ResponseBody.create(null, cloudFile2Content)
        val response2 = Response.Builder()
            .request(okhttp3.Request.Builder().url("https://test.example.com/download/file2").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody2)
            .build()

        val responseBody3 = ResponseBody.create(null, cloudFile3Content)
        val response3 = Response.Builder()
            .request(okhttp3.Request.Builder().url("https://test.example.com/download/file3").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody3)
            .build()

        // Return responses in order
        var callCount = 0
        whenever(mockCall.execute()).thenAnswer {
            callCount++
            when (callCount) {
                1 -> response1
                2 -> response2
                3 -> response3
                else -> response3
            }
        }

        // Set up HTTP client on the existing mock steam client
        val mockSteamClient = mockSteamService.steamClient!!
        val mockConfig = mock<SteamConfiguration>()
        whenever(mockSteamClient.configuration).thenReturn(mockConfig)
        whenever(mockConfig.httpClient).thenReturn(mockHttpClient)

        // Create prefixToPath function
        val prefixToPath: (String) -> String = { prefix ->
            when {
                prefix == "WinMyDocuments" -> {
                    val imageFs = ImageFs.find(context)
                    val wineprefix = File(imageFs.wineprefix)
                    val dosDevices = File(wineprefix, "dosdevices")
                    val cDrive = File(dosDevices, "c:")
                    val users = File(cDrive, "users")
                    val xuser = File(users, "xuser")
                    val documents = File(xuser, "Documents")
                    documents.absolutePath
                }
                else -> tempDir.absolutePath
            }
        }

        // Call syncUserFiles
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        // Verify result
        assertNotNull("Result should not be null", result)
        assertEquals("Should download 3 files", 3, result!!.filesDownloaded)
        assertEquals("Sync result should be Success", SyncResult.Success, result.syncResult)
        assertTrue("Bytes downloaded should be > 0", result.bytesDownloaded > 0)

        // Verify files were written to disk
        val expectedFile1 = File(saveFilesDir, "cloud_save_1.sav")
        val expectedFile2 = File(saveFilesDir, "cloud_save_2.sav")
        val expectedFile3 = File(saveFilesDir, "cloud_save_3.sav")

        assertTrue("File 1 should exist", expectedFile1.exists())
        assertTrue("File 2 should exist", expectedFile2.exists())
        assertTrue("File 3 should exist", expectedFile3.exists())

        assertEquals("File 1 content should match", cloudFile1Content.contentToString(), expectedFile1.readBytes().contentToString())
        assertEquals("File 2 content should match", cloudFile2Content.contentToString(), expectedFile2.readBytes().contentToString())
        assertEquals("File 3 content should match", cloudFile3Content.contentToString(), expectedFile3.readBytes().contentToString())

        // Verify database change number was updated
        val changeNumber = db.appChangeNumbersDao().getByAppId(steamAppId)
        assertNotNull("Change number should exist", changeNumber)
        assertEquals("Change number should match cloud", cloudChangeNumber, changeNumber!!.changeNumber)
    }

    @Test
    fun testUploadOnSubsequentBoots() = runBlocking {
        val testApp = db.steamAppDao().findApp(steamAppId)!!

        // Set local change number to match cloud (e.g., both 5)
        val matchingChangeNumber = 5
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, matchingChangeNumber.toLong()))

            // Insert old file state into database (different from current local files)
            val oldFileContent = "old file content".toByteArray()
            val oldFileSha = CryptoHelper.shaHash(oldFileContent)
            val oldUserFile = app.gamenative.data.UserFileInfo(
                root = PathType.WinMyDocuments,
                path = "My Games/TestGame/Steam/76561198025127569",
                filename = "SaveData_0.sav",
                timestamp = System.currentTimeMillis() - 10000,
                sha = oldFileSha
            )
            db.appFileChangeListsDao().insert(steamAppId, listOf(oldUserFile))
        }

        // Create new local files that differ from database state
        saveFilesDir.listFiles()?.forEach { it.delete() }
        val newFile1Content = "new save data 1".toByteArray()
        val newFile2Content = "new save data 2".toByteArray()
        File(saveFilesDir, "SaveData_0.sav").writeBytes(newFile1Content)
        File(saveFilesDir, "SaveData_New.sav").writeBytes(newFile2Content)

        // Mock AppFileChangeList with matching change number (no new cloud files)
        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(matchingChangeNumber.toLong())
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(listOf("%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569"))
        whenever(mockAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(mockAppFileChangeList.files).thenReturn(emptyList())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockAppFileChangeList)

        // Mock upload batch response
        val mockUploadBatchResponse = mock<`in`.dragonbra.javasteam.steam.handlers.steamcloud.AppUploadBatchResponse>()
        whenever(mockUploadBatchResponse.batchID).thenReturn(1)
        whenever(mockUploadBatchResponse.appChangeNumber).thenReturn((matchingChangeNumber + 1).toLong())

        val capturedFilesToDelete = mutableListOf<List<String>>()
        val capturedFilesToUpload = mutableListOf<List<String>>()
        every {
            mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
        } answers {
            for (i in args.indices) {
                val a = args[i]
                if (a is List<*> && a.all { it is String }) {
                    val list = a as List<String>
                    if (capturedFilesToUpload.isEmpty()) capturedFilesToUpload.add(list)
                    else capturedFilesToDelete.add(list)
                }
            }
            CompletableFuture.completedFuture(mockUploadBatchResponse)
        }

        val mockFileUploadInfo = mock<`in`.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadInfo>()
        whenever(mockFileUploadInfo.blockRequests).thenReturn(emptyList())

        every { mockSteamCloud.beginFileUpload(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockFileUploadInfo)

        every { mockSteamCloud.commitFileUpload(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(true)

        every { mockSteamCloud.completeAppUploadBatch(any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(Unit)

        // Create prefixToPath function
        val prefixToPath: (String) -> String = { prefix ->
            when {
                prefix == "WinMyDocuments" -> {
                    val imageFs = ImageFs.find(context)
                    val wineprefix = File(imageFs.wineprefix)
                    val dosDevices = File(wineprefix, "dosdevices")
                    val cDrive = File(dosDevices, "c:")
                    val users = File(cDrive, "users")
                    val xuser = File(users, "xuser")
                    val documents = File(xuser, "Documents")
                    documents.absolutePath
                }
                else -> tempDir.absolutePath
            }
        }

        // Call syncUserFiles
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        // Verify result
        assertNotNull("Result should not be null", result)
        assertTrue("Uploads should be required", result!!.uploadsRequired)
        assertTrue("Uploads should be completed", result.uploadsCompleted)
        assertEquals("Should upload 2 files (1 modified + 1 new)", 2, result.filesUploaded)
        assertEquals("Sync result should be Success", SyncResult.Success, result.syncResult)

        // Verify prefixPath: path with folder uses Paths.get (slash before filename)
        val filesToUpload = capturedFilesToUpload.singleOrNull() ?: emptyList()
        val expectedPrefix = "%WinMyDocuments%My Games/TestGame/Steam/76561198025127569"
        assertTrue("prefixPath for SaveData_0.sav should include path", filesToUpload.any { it.endsWith("/SaveData_0.sav") && it.contains(expectedPrefix) })
        assertTrue("prefixPath for SaveData_New.sav should include path", filesToUpload.any { it.endsWith("/SaveData_New.sav") && it.contains(expectedPrefix) })

        // Verify database was updated with new change number
        val changeNumber = db.appChangeNumbersDao().getByAppId(steamAppId)
        assertNotNull("Change number should exist", changeNumber)
        assertEquals("Change number should be updated", (matchingChangeNumber + 1).toLong(), changeNumber!!.changeNumber)
    }

    @Test
    fun testPrefixResolution() = runBlocking {
        val testApp = db.steamAppDao().findApp(steamAppId)!!

        // Create test files in multiple path types
        val imageFs = ImageFs.find(context)
        val wineprefix = File(imageFs.wineprefix)
        val dosDevices = File(wineprefix, "dosdevices")
        val cDrive = File(dosDevices, "c:")
        val users = File(cDrive, "users")
        val xuser = File(users, "xuser")

        // WinMyDocuments: Documents/My Games/TestGame/save1.sav
        val documents = File(xuser, "Documents")
        val myGames = File(documents, "My Games")
        val testGameDocs = File(myGames, "TestGame")
        testGameDocs.mkdirs()
        val docSaveFile = File(testGameDocs, "save1.sav")
        val docSaveContent = "documents save".toByteArray()
        docSaveFile.writeBytes(docSaveContent)

        // WinAppDataLocal: AppData/Local/TestGame/save2.sav
        val appData = File(xuser, "AppData")
        val local = File(appData, "Local")
        val testGameLocal = File(local, "TestGame")
        testGameLocal.mkdirs()
        val localSaveFile = File(testGameLocal, "save2.sav")
        val localSaveContent = "local save".toByteArray()
        localSaveFile.writeBytes(localSaveContent)

        // SteamUserData: Create structure for Steam userdata
        val programFiles = File(cDrive, "Program Files (x86)")
        val steam = File(programFiles, "Steam")
        val userdata = File(steam, "userdata")
        val accountId = File(userdata, "76561198025127569")
        val appIdDir = File(accountId, steamAppId.toString())
        val remote = File(appIdDir, "remote")
        remote.mkdirs()
        val steamSaveFile = File(remote, "save3.sav")
        val steamSaveContent = "steam save".toByteArray()
        steamSaveFile.writeBytes(steamSaveContent)

        // Update test app with patterns for all three path types
        val saveFilePatterns = listOf(
            SaveFilePattern(
                root = PathType.WinMyDocuments,
                path = "My Games/TestGame",
                pattern = "save1.sav",
            ),
            SaveFilePattern(
                root = PathType.WinAppDataLocal,
                path = "TestGame",
                pattern = "save2.sav",
            ),
            SaveFilePattern(
                root = PathType.SteamUserData,
                path = "",
                pattern = "save3.sav",
            ),
        )

        val updatedApp = testApp.copy(ufs = UFS(saveFilePatterns = saveFilePatterns))
        runBlocking {
            db.steamAppDao().update(updatedApp)
        }

        // Clear existing database state
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, 0))
            db.appFileChangeListsDao().insert(steamAppId, emptyList())
        }

        // Mock empty cloud (no cloud files)
        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(0)
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(emptyList())
        whenever(mockAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(mockAppFileChangeList.files).thenReturn(emptyList())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockAppFileChangeList)

        // Create prefixToPath function that maps all path types
        val prefixToPath: (String) -> String = { prefix ->
            when (prefix) {
                "WinMyDocuments" -> documents.absolutePath
                "WinAppDataLocal" -> local.absolutePath
                "SteamUserData" -> remote.absolutePath
                else -> tempDir.absolutePath
            }
        }

        // Call syncUserFiles
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = updatedApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        // Verify result
        assertNotNull("Result should not be null", result)
        assertEquals("Should upload 3 files (one from each path type)", 3, result!!.filesUploaded)
        assertTrue("Uploads should be completed", result.uploadsCompleted)
        assertEquals("Should have 3 files managed", 3, result.filesManaged)

        // Verify files were found in correct locations
        assertTrue("Documents save file should exist", docSaveFile.exists())
        assertTrue("Local save file should exist", localSaveFile.exists())
        assertTrue("Steam save file should exist", steamSaveFile.exists())
    }

    @Test
    fun testSaveFileDepthDiscovery() = runBlocking {
        val testApp = db.steamAppDao().findApp(steamAppId)!!

        // Create nested directory structure up to depth 7
        val basePath = saveFilesDir
        basePath.listFiles()?.forEach { it.deleteRecursively() }

        // Depth 0
        File(basePath, "level0.sav").writeBytes("level0".toByteArray())

        // Depth 1
        val subdir1 = File(basePath, "subdir1")
        subdir1.mkdirs()
        File(subdir1, "level1.sav").writeBytes("level1".toByteArray())

        // Depth 2
        val subdir2 = File(subdir1, "subdir2")
        subdir2.mkdirs()
        File(subdir2, "level2.sav").writeBytes("level2".toByteArray())

        // Depth 3
        val subdir3 = File(subdir2, "subdir3")
        subdir3.mkdirs()
        File(subdir3, "level3.sav").writeBytes("level3".toByteArray())

        // Depth 4
        val subdir4 = File(subdir3, "subdir4")
        subdir4.mkdirs()
        File(subdir4, "level4.sav").writeBytes("level4".toByteArray())

        // Depth 5
        val subdir5 = File(subdir4, "subdir5")
        subdir5.mkdirs()
        File(subdir5, "level5.sav").writeBytes("level5".toByteArray())

        // Depth 6 (should NOT be found - beyond maxDepth=5)
        val subdir6 = File(subdir5, "subdir6")
        subdir6.mkdirs()
        File(subdir6, "level6.sav").writeBytes("level6".toByteArray())

        // Depth 7 (should NOT be found - beyond maxDepth=5)
        val subdir7 = File(subdir6, "subdir7")
        subdir7.mkdirs()
        File(subdir7, "level7.sav").writeBytes("level7".toByteArray())

        // Update test app with pattern that matches all .sav files
        val saveFilePatterns = listOf(
            SaveFilePattern(
                root = PathType.WinMyDocuments,
                path = "My Games/TestGame/Steam/76561198025127569",
                pattern = "*.sav",
            ),
        )

        val updatedApp = testApp.copy(ufs = UFS(saveFilePatterns = saveFilePatterns))
        runBlocking {
            db.steamAppDao().update(updatedApp)
        }

        // Clear existing database state
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, 0))
            db.appFileChangeListsDao().insert(steamAppId, emptyList())
        }

        // Mock empty cloud (no cloud files)
        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(0)
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(emptyList())
        whenever(mockAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(mockAppFileChangeList.files).thenReturn(emptyList())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockAppFileChangeList)

        // Create prefixToPath function
        val prefixToPath: (String) -> String = { prefix ->
            when {
                prefix == "WinMyDocuments" -> {
                    val imageFs = ImageFs.find(context)
                    val wineprefix = File(imageFs.wineprefix)
                    val dosDevices = File(wineprefix, "dosdevices")
                    val cDrive = File(dosDevices, "c:")
                    val users = File(cDrive, "users")
                    val xuser = File(users, "xuser")
                    val documents = File(xuser, "Documents")
                    documents.absolutePath
                }
                else -> tempDir.absolutePath
            }
        }

        // Call syncUserFiles
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = updatedApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        // Verify result - should find files at depths 0-5 (6 files), but not depths 6-7
        assertNotNull("Result should not be null", result)
        assertEquals("Should upload 5 files (depths 0-5, maxDepth=5)", 5, result!!.filesUploaded)
        assertTrue("Uploads should be completed", result.uploadsCompleted)
        assertEquals("Should have 5 files managed", 5, result.filesManaged)

        // Verify files at depths 0-5 exist
        assertTrue("Level 0 file should exist", File(basePath, "level0.sav").exists())
        assertTrue("Level 1 file should exist", File(subdir1, "level1.sav").exists())
        assertTrue("Level 2 file should exist", File(subdir2, "level2.sav").exists())
        assertTrue("Level 3 file should exist", File(subdir3, "level3.sav").exists())
        assertTrue("Level 4 file should exist", File(subdir4, "level4.sav").exists())

        // Verify files at depths 6-7 exist on disk but were NOT included in upload
        assertTrue("Level 6 file should exist on disk", File(subdir6, "level6.sav").exists())
        assertTrue("Level 7 file should exist on disk", File(subdir7, "level7.sav").exists())
        // But they should not be in the managed files count (verified by filesManaged == 6)
    }

    @Test
    fun testNoPrefixDownload() = runBlocking {
        // Clear existing files and database state
        saveFilesDir.listFiles()?.forEach { it.delete() }
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
        }

        // Set local change number to 0 (first boot scenario)
        runBlocking {
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, 0))
            db.appFileChangeListsDao().insert(steamAppId, emptyList())
        }

        val testApp = db.steamAppDao().findApp(steamAppId)!!

        // Setup the mocks
        val count = 3
        val contents = (0..<count).toList()
            .map { "Save file $it content".toByteArray() }
        val hashes = contents.map { CryptoHelper.shaHash(it) }
        val mockFiles = (0..<count).toList()
            .map {
                val file = mock<AppFileInfo>()
                // Bug is that the prefix is in the file.filename
                whenever(file.filename).thenReturn("%GameInstall%/save$it.dat")
                whenever(file.shaFile).thenReturn(hashes[it])
                whenever(file.pathPrefixIndex).thenReturn(0)
                whenever(file.timestamp).thenReturn(Date())
                whenever(file.rawFileSize).thenReturn(contents[it].size)
                file
            }

        val cloudChangeNumber = 5L
        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(cloudChangeNumber)
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        // Does return an empty list of prefix
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(listOf())
        whenever(mockAppFileChangeList.machineNames).thenReturn(listOf())
        whenever(mockAppFileChangeList.files).thenReturn(mockFiles)

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
                CompletableFuture.completedFuture(mockAppFileChangeList)

        val mockDownloadFiles = (0..<count).toList()
            .map {
                val downloadInfo = mock<FileDownloadInfo>()
                whenever(downloadInfo.urlHost).thenReturn("test.example.com")
                whenever(downloadInfo.urlPath).thenReturn("/download/file$it")
                whenever(downloadInfo.requestHeaders).thenReturn(emptyList())
                whenever(downloadInfo.fileSize).thenReturn(contents[it].size)
                whenever(downloadInfo.rawFileSize).thenReturn(contents[it].size)
                downloadInfo
            }

        // Mock clientFileDownload to return appropriate download info based on filename in the path
        var downloadCallCount = -1
        every { mockSteamCloud.clientFileDownload(any(), any()) } answers {
            ++downloadCallCount
            CompletableFuture.completedFuture(mockDownloadFiles[downloadCallCount])
        }

        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } answers {
            ++downloadCallCount
            CompletableFuture.completedFuture(mockDownloadFiles[downloadCallCount])
        }

        // Mock HTTP client to return file content
        val mockHttpClient = mock<OkHttpClient>()
        val mockCall = mock<Call>()
        whenever(mockHttpClient.newCall(any())).thenReturn(mockCall)

        // Set up HTTP client on the existing mock steam client
        val mockSteamClient = mockSteamService.steamClient!!
        val mockConfig = mock<SteamConfiguration>()
        whenever(mockSteamClient.configuration).thenReturn(mockConfig)
        whenever(mockConfig.httpClient).thenReturn(mockHttpClient)

        val responseBodies = (0..<count).toList()
            .map { contents[it].toResponseBody(null) }
        val responses = (0..<count).toList()
            .map {
                Response.Builder()
                    .request(okhttp3.Request.Builder().url("https://test.example.com/download/file$it").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBodies[it])
                    .build()
            }

        // Return responses in order
        var callCount = 0
        whenever(mockCall.execute()).thenAnswer {
            callCount++
            responses[callCount - 1]
        }

        // Create prefixToPath function
        val prefixToPath: (String) -> String = { prefix ->
            when {
                prefix == "GameInstall" -> {
                    saveFilesDir.absolutePath
                }
                else -> tempDir.absolutePath
            }
        }

        // Call syncUserFiles
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        // Verify result
        assertNotNull("Result should not be null", result)
        assertEquals("Should download 3 files", 3, result!!.filesDownloaded)
        assertEquals("Sync result should be Success", SyncResult.Success, result.syncResult)
        assertTrue("Bytes downloaded should be > 0", result.bytesDownloaded > 0)

        for (i in 0..<count) {
            val expectedFile = File(saveFilesDir, "save$i.dat")
            assertTrue("File $i should exist", expectedFile.exists())
            assertEquals(
                "File $i content should match",
                contents[i].contentToString(),
                expectedFile.readBytes().contentToString()
            )

            val changeNumber = db.appChangeNumbersDao().getByAppId(steamAppId)
            assertNotNull("Change number should exist", changeNumber)
            assertEquals("Change number should match cloud", cloudChangeNumber, changeNumber!!.changeNumber)
        }
    }

    @Test
    fun testNoPrefixUpload() = runBlocking {
        val testApp = db.steamAppDao().findApp(steamAppId)!!

        // Set local change number to match cloud (e.g., both 5)
        val matchingChangeNumber = 5
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, matchingChangeNumber.toLong()))

            // Insert old file state into database (different from current local files)
            val oldFileContent = "old file content".toByteArray()
            val oldFileSha = CryptoHelper.shaHash(oldFileContent)
            val oldUserFile1 = app.gamenative.data.UserFileInfo(
                root = PathType.GameInstall,
                path = "",
                filename = "save1.sav",
                timestamp = System.currentTimeMillis() - 10000,
                sha = oldFileSha
            )
            val oldUserFile2 = app.gamenative.data.UserFileInfo(
                root = PathType.GameInstall,
                path = ".",
                filename = "save2.sav",
                timestamp = System.currentTimeMillis() - 10000,
                sha = oldFileSha
            )
            db.appFileChangeListsDao()
                .insert(steamAppId, listOf(oldUserFile1, oldUserFile2))
        }

        val saveFilePatterns = listOf(
            SaveFilePattern(
                root = PathType.GameInstall,
                path = ".",
                pattern = "save0.sav",
            ),
            SaveFilePattern(
                root = PathType.GameInstall,
                path = "",
                pattern = "save1.sav",
            ),
            SaveFilePattern(
                root = PathType.GameInstall,
                path = ".",
                pattern = "save2.sav",
            ),
        )
        val updatedApp = testApp.copy(ufs = UFS(saveFilePatterns = saveFilePatterns))

        // Update the files
        File(saveFilesDir, "save0.sav").writeBytes("New Content 0".toByteArray())
        File(saveFilesDir, "save1.sav").writeBytes("New Content 1".toByteArray())
        File(saveFilesDir, "save2.sav").delete()

        // Setup the mocks
        // Mock AppFileChangeList with matching change number (no new cloud files)
        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(matchingChangeNumber.toLong())
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(listOf())
        whenever(mockAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(mockAppFileChangeList.files).thenReturn(emptyList())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
                CompletableFuture.completedFuture(mockAppFileChangeList)

        // Mock upload batch response
        val mockUploadBatchResponse = mock<`in`.dragonbra.javasteam.steam.handlers.steamcloud.AppUploadBatchResponse>()
        whenever(mockUploadBatchResponse.batchID).thenReturn(1)
        whenever(mockUploadBatchResponse.appChangeNumber).thenReturn((matchingChangeNumber + 1).toLong())

        val capturedFilesToDelete = mutableListOf<List<String>>()
        val capturedFilesToUpload = mutableListOf<List<String>>()
        every {
            mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
        } answers {
            for (i in args.indices) {
                val a = args[i]
                if (a is List<*> && a.all { it is String }) {
                    val list = a as List<String>
                    if (capturedFilesToUpload.isEmpty()) capturedFilesToUpload.add(list)
                    else capturedFilesToDelete.add(list)
                }
            }
            CompletableFuture.completedFuture(mockUploadBatchResponse)
        }

        val mockFileUploadInfo = mock<`in`.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadInfo>()
        whenever(mockFileUploadInfo.blockRequests).thenReturn(emptyList())

        every { mockSteamCloud.beginFileUpload(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(mockFileUploadInfo)

        every { mockSteamCloud.commitFileUpload(any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(true)

        every { mockSteamCloud.completeAppUploadBatch(any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(Unit)

        // Create prefixToPath function
        val prefixToPath: (String) -> String = { prefix ->
            when {
                prefix == "GameInstall" -> {
                    saveFilesDir.absolutePath
                }
                else -> tempDir.absolutePath
            }
        }

        // Call syncUserFiles
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = updatedApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        // Verify result
        assertNotNull("Result should not be null", result)
        assertTrue("Uploads should be required", result!!.uploadsRequired)
        assertTrue("Uploads should be completed", result.uploadsCompleted)
        assertEquals("Should upload 2 files (1 new + 1 modify)", 2, result.filesUploaded)
        assertEquals("Sync result should be Success", SyncResult.Success, result.syncResult)

        // Verify prefixPath: bare placeholder (%GameInstall%) must have no slash before filename
        val filesToUpload = capturedFilesToUpload.singleOrNull() ?: emptyList()
        assertTrue("prefixPath for save0.sav should be %GameInstall%save0.sav (no slash)", filesToUpload.contains("%GameInstall%save0.sav"))
        assertTrue("prefixPath for save1.sav should be %GameInstall%save1.sav (no slash)", filesToUpload.contains("%GameInstall%save1.sav"))
        val filesToDelete = capturedFilesToDelete.singleOrNull() ?: emptyList()
        assertTrue("prefixPath for deleted save2.sav should be %GameInstall%save2.sav (no slash)", filesToDelete.contains("%GameInstall%save2.sav"))

        // Verify database was updated with new change number
        val changeNumber = db.appChangeNumbersDao().getByAppId(steamAppId)
        assertNotNull("Change number should exist", changeNumber)
        assertEquals("Change number should be updated", (matchingChangeNumber + 1).toLong(), changeNumber!!.changeNumber)
    }
}

