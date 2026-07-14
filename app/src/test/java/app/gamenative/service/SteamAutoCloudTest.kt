package app.gamenative.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ConfigInfo
import app.gamenative.data.FileChangeLists
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamFileHashCache
import app.gamenative.data.UFS
import app.gamenative.db.PluviaDatabase
import app.gamenative.enums.AppType
import app.gamenative.enums.OS
import app.gamenative.enums.PathType
import app.gamenative.enums.ReleaseState
import app.gamenative.enums.SaveLocation
import app.gamenative.utils.Net
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
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.future.await
import okhttp3.ResponseBody.Companion.toResponseBody
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.nio.file.Path
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
    private lateinit var mockParallelHttpClient: OkHttpClient
    private val testAppId = "STEAM_123456"
    private val steamAppId = 123456
    private val clientId = 1L

    @Before
    fun setUp() {
        mockkObject(Net)
        mockParallelHttpClient = mockk(relaxed = true)
        // Safe default: relaxed client returns null for newCall(), causing downloads to no-op.
        // Tests that exercise the download path override this stub with a test-specific mock.
        every { Net.httpForParallelDownloads(any()) } returns mockParallelHttpClient

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
        unmockkObject(Net)

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
        // Insert a non-empty stale cache so cacheIsAbsentOrEmpty=false and the upload path is taken.
        runBlocking {
            db.appFileChangeListsDao().insert(steamAppId, listOf(
                app.gamenative.data.UserFileInfo(
                    root = PathType.WinMyDocuments,
                    path = "__stale__",
                    filename = "__placeholder__",
                    timestamp = 0L,
                    sha = ByteArray(20) { 0 },
                )
            ))
        }

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

    @Test
    fun getCachedShaOrHash_reusesCachedShaWhenMetadataMatches() = runBlocking {
        val hashFile = File(saveFilesDir, "cached_hash_test.sav")
        hashFile.writeBytes("cache me".toByteArray())
        val path = hashFile.toPath()
        val sizeBytes = Files.size(path)
        val mtimeMillis = Files.getLastModifiedTime(path).toMillis()
        val cachedSha = ByteArray(20) { 7 }

        db.steamFileHashCacheDao().insert(
            SteamFileHashCache(
                appId = steamAppId,
                absPath = path.toString(),
                sizeBytes = sizeBytes,
                mtimeMillis = mtimeMillis,
                sha = cachedSha,
            ),
        )

        val sha = SteamAutoCloud.getCachedShaOrHash(
            appId = steamAppId,
            path = path,
            hashCacheDao = db.steamFileHashCacheDao(),
        )

        assertTrue("Should report cache hit", sha.wasCacheHit)
        assertArrayEquals("Should reuse cached SHA when size and mtime match", cachedSha, sha.sha)
    }

    @Test
    fun getCachedShaOrHash_rehashesWhenMetadataChanges() = runBlocking {
        val hashFile = File(saveFilesDir, "rehash_test.sav")
        hashFile.writeBytes("old-data".toByteArray())
        val path = hashFile.toPath()
        val originalSizeBytes = Files.size(path)
        val originalMtimeMillis = Files.getLastModifiedTime(path).toMillis()
        val cachedSha = ByteArray(20) { 3 }

        db.steamFileHashCacheDao().insert(
            SteamFileHashCache(
                appId = steamAppId,
                absPath = path.toString(),
                sizeBytes = originalSizeBytes,
                mtimeMillis = originalMtimeMillis,
                sha = cachedSha,
            ),
        )

        hashFile.writeBytes("new-data-with-different-size".toByteArray())

        val sha = SteamAutoCloud.getCachedShaOrHash(
            appId = steamAppId,
            path = path,
            hashCacheDao = db.steamFileHashCacheDao(),
        )

        assertFalse("Should report cache miss when metadata changes", sha.wasCacheHit)
        assertFalse("Should not reuse cached SHA when metadata changes", cachedSha.contentEquals(sha.sha))
        val cachedEntry = db.steamFileHashCacheDao().getByAppIdAndPath(steamAppId, path.toString())
        assertNotNull("Cache entry should be updated", cachedEntry)
        assertArrayEquals("Updated cache should store new SHA", sha.sha, cachedEntry!!.sha)
    }

    @Test(expected = java.nio.file.NoSuchFileException::class)
    fun getCachedShaOrHash_throwsWhenFileDoesNotExist() = runBlocking {
        val nonExistent = File(saveFilesDir, "does_not_exist.sav").toPath()
        SteamAutoCloud.getCachedShaOrHash(
            appId = steamAppId,
            path = nonExistent,
            hashCacheDao = db.steamFileHashCacheDao(),
        )
        Unit
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
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
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

        val cacheEntry1 = db.steamFileHashCacheDao().getByAppIdAndPath(steamAppId, expectedFile1.toPath().toString())
        val cacheEntry2 = db.steamFileHashCacheDao().getByAppIdAndPath(steamAppId, expectedFile2.toPath().toString())
        val cacheEntry3 = db.steamFileHashCacheDao().getByAppIdAndPath(steamAppId, expectedFile3.toPath().toString())

        assertNotNull("File 1 cache entry should exist", cacheEntry1)
        assertNotNull("File 2 cache entry should exist", cacheEntry2)
        assertNotNull("File 3 cache entry should exist", cacheEntry3)
        assertArrayEquals("File 1 cache SHA should match cloud SHA", cloudFile1Sha, cacheEntry1!!.sha)
        assertArrayEquals("File 2 cache SHA should match cloud SHA", cloudFile2Sha, cacheEntry2!!.sha)
        assertArrayEquals("File 3 cache SHA should match cloud SHA", cloudFile3Sha, cacheEntry3!!.sha)

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

        // Clear existing database state and insert a non-empty stale cache so
        // cacheIsAbsentOrEmpty=false and the upload path is taken.
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, 0))
            db.appFileChangeListsDao().insert(steamAppId, listOf(
                app.gamenative.data.UserFileInfo(
                    root = PathType.WinMyDocuments,
                    path = "__stale__",
                    filename = "__placeholder__",
                    timestamp = 0L,
                    sha = ByteArray(20) { 0 },
                )
            ))
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

        // Clear existing database state and insert a non-empty stale cache so
        // cacheIsAbsentOrEmpty=false and the upload path is taken.
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, 0))
            db.appFileChangeListsDao().insert(steamAppId, listOf(
                app.gamenative.data.UserFileInfo(
                    root = PathType.WinMyDocuments,
                    path = "__stale__",
                    filename = "__placeholder__",
                    timestamp = 0L,
                    sha = ByteArray(20) { 0 },
                )
            ))
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

        // Verify result - should find files at depths 0-4 (5 files); depth 5 (subdir5) is
        // the last directory walked but maxDepth=5 does not recurse into its subdirectories.
        assertNotNull("Result should not be null", result)
        assertEquals("Should upload 5 files (depths 0-4, maxDepth=5)", 5, result!!.filesUploaded)
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
                whenever(downloadInfo.useHttps).thenReturn(true)
                whenever(downloadInfo.requestHeaders).thenReturn(emptyList())
                whenever(downloadInfo.fileSize).thenReturn(contents[it].size)
                whenever(downloadInfo.rawFileSize).thenReturn(contents[it].size)
                downloadInfo
            }

        val downloadInfoByFilename = (0..<count).associate { index ->
            "%GameInstall%/save$index.dat" to mockDownloadFiles[index]
        }

        every { mockSteamCloud.clientFileDownload(any(), any()) } answers {
            val filename = secondArg<String>()
            CompletableFuture.completedFuture(
                downloadInfoByFilename[filename] ?: error("Unexpected filename: $filename"),
            )
        }

        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } answers {
            val filename = secondArg<String>()
            CompletableFuture.completedFuture(
                downloadInfoByFilename[filename] ?: error("Unexpected filename: $filename"),
            )
        }

        // Mock HTTP client to return file content
        val mockHttpClient = mock<OkHttpClient>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        val callsByPath = (0..<count).associate { index ->
            val call = mock<Call>()
            whenever(call.execute()).thenReturn(
                Response.Builder()
                    .request(okhttp3.Request.Builder().url("https://test.example.com/download/file$index").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(contents[index].toResponseBody(null))
                    .build(),
            )
            "/download/file$index" to call
        }
        whenever(mockHttpClient.newCall(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<okhttp3.Request>(0)
            callsByPath[request.url.encodedPath] ?: error("Unexpected URL: ${request.url}")
        }

        // Set up HTTP client on the existing mock steam client
        val mockSteamClient = mockSteamService.steamClient!!
        val mockConfig = mock<SteamConfiguration>()
        whenever(mockSteamClient.configuration).thenReturn(mockConfig)
        whenever(mockConfig.httpClient).thenReturn(mockHttpClient)

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

    /**
     * Danganronpa 2 sends cloud files with the GameInstall placeholder embedded in the filename
     * (e.g. filename="%GameInstall%savedata.vfs", pathPrefixes=[]) because its PICS UFS entry has
     * path="/". A Windows rootoverride maps GameInstall → WinMyDocuments + My Games/Danganronpa2/.
     * The downloaded file must land in the WinMyDocuments subdirectory, NOT the game install dir.
     */
    @Test
    fun downloadWithEmbeddedGameInstallPrefixUsesRootoverrideLocalPath() = runBlocking {
        saveFilesDir.listFiles()?.forEach { it.delete() }
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, 0))
            db.appFileChangeListsDao().insert(steamAppId, emptyList())
        }

        val docsRoot = File(tempDir, "Documents")
        val gameInstallRoot = File(tempDir, "GameInstall")
        val expectedSaveDir = File(docsRoot, "My Games/Danganronpa2")
        docsRoot.mkdirs()
        gameInstallRoot.mkdirs()
        expectedSaveDir.mkdirs()

        // Danganronpa 2 SaveFilePattern after KeyValueUtils fix: "/" path normalised to ""
        val saveFilePatterns = listOf(
            SaveFilePattern(
                root = PathType.WinMyDocuments,
                path = "My Games/Danganronpa2",
                pattern = "savedata.vfs",
                uploadRoot = PathType.GameInstall,
                uploadPath = "",
            ),
        )
        val danganApp = db.steamAppDao().findApp(steamAppId)!!
            .copy(ufs = UFS(saveFilePatterns = saveFilePatterns))

        val fileContent = "save file content".toByteArray()
        val fileHash = CryptoHelper.shaHash(fileContent)

        // Steam sends filename with embedded %GameInstall% prefix (no separate pathPrefixes entry)
        val mockFile = mock<AppFileInfo>()
        whenever(mockFile.filename).thenReturn("%GameInstall%savedata.vfs")
        whenever(mockFile.shaFile).thenReturn(fileHash)
        whenever(mockFile.pathPrefixIndex).thenReturn(0)
        whenever(mockFile.timestamp).thenReturn(Date())
        whenever(mockFile.rawFileSize).thenReturn(fileContent.size)

        val cloudChangeNumber = 5L
        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(cloudChangeNumber)
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(listOf())
        whenever(mockAppFileChangeList.machineNames).thenReturn(listOf())
        whenever(mockAppFileChangeList.files).thenReturn(listOf(mockFile))

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockAppFileChangeList)

        val downloadInfo = mock<FileDownloadInfo>()
        whenever(downloadInfo.urlHost).thenReturn("test.example.com")
        whenever(downloadInfo.urlPath).thenReturn("/download/savedata.vfs")
        whenever(downloadInfo.useHttps).thenReturn(true)
        whenever(downloadInfo.requestHeaders).thenReturn(emptyList())
        whenever(downloadInfo.fileSize).thenReturn(fileContent.size)
        whenever(downloadInfo.rawFileSize).thenReturn(fileContent.size)

        every { mockSteamCloud.clientFileDownload(any(), any()) } returns
            CompletableFuture.completedFuture(downloadInfo)
        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(downloadInfo)

        val mockHttpClient = mock<OkHttpClient>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        val call = mock<Call>()
        whenever(call.execute()).thenReturn(
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://test.example.com/download/savedata.vfs").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(fileContent.toResponseBody(null))
                .build(),
        )
        whenever(mockHttpClient.newCall(any())).thenReturn(call)

        val prefixToPath: (String) -> String = { prefix ->
            when (prefix) {
                "WinMyDocuments" -> docsRoot.absolutePath
                "GameInstall" -> gameInstallRoot.absolutePath
                "SteamUserData" -> File(tempDir, "userdata").absolutePath
                else -> tempDir.absolutePath
            }
        }

        val result = SteamAutoCloud.syncUserFiles(
            appInfo = danganApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        assertNotNull("Result should not be null", result)
        assertEquals("Should download 1 file", 1, result!!.filesDownloaded)

        // File must land in WinMyDocuments/My Games/Danganronpa2/, NOT the game install dir
        val expectedFile = File(expectedSaveDir, "savedata.vfs")
        assertTrue("savedata.vfs must be in WinMyDocuments/My Games/Danganronpa2/, not game install dir: ${expectedFile.absolutePath}", expectedFile.exists())
        assertFalse("savedata.vfs must NOT be in game install dir", File(gameInstallRoot, "savedata.vfs").exists())
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

    /**
     * When a Windows rootoverride remaps GameInstall → WinAppDataRoaming, the SaveFilePattern
     * has root=WinAppDataRoaming (used for local file lookup) and uploadRoot=GameInstall (used
     * for the cloud prefix). Uploads must use %GameInstall% as the prefix, not %WinAppDataRoaming%.
     */
    /**
     * When a Windows rootoverride has a non-empty addpath (e.g. addpath="MyGame"), the
     * SaveFilePattern has:
     *   root=WinAppDataRoaming  path=MyGame/saves  (local scan dir)
     *   uploadRoot=GameInstall  uploadPath=saves   (cloud key prefix)
     *
     * The local scan must find files in <WinAppDataRoaming>/MyGame/saves and upload them with
     * the cloud prefix %GameInstall%saves — not %WinAppDataRoaming%MyGame/saves.
     */
    @Test
    fun uploadUsesCloudPrefixAndScansAddPathSubdirWhenRootoverrideHasNonEmptyAddPath() = runBlocking {
        val matchingChangeNumber = 5
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)
        db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, matchingChangeNumber.toLong()))
        db.appFileChangeListsDao().insert(steamAppId, listOf(
            app.gamenative.data.UserFileInfo(
                root = PathType.WinMyDocuments,
                path = "__stale__",
                filename = "__placeholder__",
                timestamp = 0L,
                sha = ByteArray(20) { 0 },
            )
        ))

        val roamingRoot = File(tempDir, "roaming")
        val userdataRoot = File(tempDir, "userdata")
        // Files live in the addPath subdirectory: <roamingRoot>/MyGame/saves/
        val saveDir = File(roamingRoot, "MyGame/saves")
        saveDir.mkdirs()
        File(saveDir, "save.sav").writeBytes("save content".toByteArray())

        // SaveFilePattern as produced by KeyValueUtils for a game with addPath="MyGame":
        //   root=WinAppDataRoaming  path=MyGame/saves  (local)
        //   uploadRoot=GameInstall  uploadPath=saves   (cloud key)
        val saveFilePatterns = listOf(
            SaveFilePattern(
                root = PathType.WinAppDataRoaming,
                path = "MyGame/saves",
                pattern = "*.sav",
                uploadRoot = PathType.GameInstall,
                uploadPath = "saves",
            ),
        )
        val appUnderTest = db.steamAppDao().findApp(steamAppId)!!
            .copy(ufs = UFS(saveFilePatterns = saveFilePatterns))

        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(matchingChangeNumber.toLong())
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(emptyList())
        whenever(mockAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(mockAppFileChangeList.files).thenReturn(emptyList())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockAppFileChangeList)

        val mockUploadBatchResponse = mock<`in`.dragonbra.javasteam.steam.handlers.steamcloud.AppUploadBatchResponse>()
        whenever(mockUploadBatchResponse.batchID).thenReturn(1)
        whenever(mockUploadBatchResponse.appChangeNumber).thenReturn((matchingChangeNumber + 1).toLong())

        val capturedFilesToUpload = mutableListOf<List<String>>()
        every {
            mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
        } answers {
            for (i in args.indices) {
                val a = args[i]
                if (a is List<*> && a.all { it is String } && capturedFilesToUpload.isEmpty()) {
                    capturedFilesToUpload.add(a as List<String>)
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

        val prefixToPath: (String) -> String = { prefix ->
            when (prefix) {
                "WinAppDataRoaming" -> roamingRoot.absolutePath
                "SteamUserData" -> userdataRoot.absolutePath
                else -> tempDir.absolutePath
            }
        }

        val result = SteamAutoCloud.syncUserFiles(
            appInfo = appUnderTest,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        assertNotNull("Result should not be null", result)
        assertEquals("Should upload 1 file from MyGame/saves", 1, result!!.filesUploaded)
        assertTrue("Uploads should be completed", result.uploadsCompleted)

        val filesToUpload = capturedFilesToUpload.singleOrNull() ?: emptyList()
        assertTrue(
            "Upload prefix must use cloud key %GameInstall%saves, not remapped root. Got: $filesToUpload",
            filesToUpload.any { it.startsWith("%GameInstall%saves") }
        )
        assertFalse(
            "Upload prefix must NOT use local WinAppDataRoaming root. Got: $filesToUpload",
            filesToUpload.any { it.startsWith("%WinAppDataRoaming%") }
        )
    }

    @Test
    fun twoPointMuseumSaveFolderUsesAccountIdAsChildOfCloudDirectory() = runBlocking {
        val matchingChangeNumber = 5
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)
        db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, matchingChangeNumber.toLong()))
        db.appFileChangeListsDao().insert(steamAppId, listOf(
            app.gamenative.data.UserFileInfo(
                root = PathType.WinMyDocuments,
                path = "__stale__",
                filename = "__placeholder__",
                timestamp = 0L,
                sha = ByteArray(20) { 0 },
            )
        ))

        val localLowRoot = File(tempDir, "local-low")
        val userdataRoot = File(tempDir, "userdata")
        val saveDir = File(localLowRoot, "Two Point Studios/Two Point Museum/Cloud/75264032/Saves")
        saveDir.mkdirs()
        File(saveDir, "1003.sav").writeBytes("museum save content".toByteArray())

        val saveFilePatterns = listOf(
            SaveFilePattern(
                root = PathType.WinAppDataLocalLow,
                path = "Two Point Studios/Two Point Museum/Cloud/75264032",
                pattern = "*",
                recursive = 1,
                uploadRoot = PathType.WinAppDataLocalLow,
                uploadPath = "75264032",
            ),
        )
        val appUnderTest = db.steamAppDao().findApp(steamAppId)!!
            .copy(ufs = UFS(saveFilePatterns = saveFilePatterns))

        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(matchingChangeNumber.toLong())
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(emptyList())
        whenever(mockAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(mockAppFileChangeList.files).thenReturn(emptyList())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockAppFileChangeList)

        val mockUploadBatchResponse = mock<`in`.dragonbra.javasteam.steam.handlers.steamcloud.AppUploadBatchResponse>()
        whenever(mockUploadBatchResponse.batchID).thenReturn(1)
        whenever(mockUploadBatchResponse.appChangeNumber).thenReturn((matchingChangeNumber + 1).toLong())

        val capturedFilesToUpload = mutableListOf<List<String>>()
        every {
            mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
        } answers {
            for (i in args.indices) {
                val a = args[i]
                if (a is List<*> && a.all { it is String } && capturedFilesToUpload.isEmpty()) {
                    capturedFilesToUpload.add(a as List<String>)
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

        val prefixToPath: (String) -> String = { prefix ->
            when (prefix) {
                "WinAppDataLocalLow" -> localLowRoot.absolutePath
                "SteamUserData" -> userdataRoot.absolutePath
                else -> tempDir.absolutePath
            }
        }

        val result = SteamAutoCloud.syncUserFiles(
            appInfo = appUnderTest,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        assertNotNull("Result should not be null", result)
        assertEquals("Should upload 1 file from the account ID child folder", 1, result!!.filesUploaded)
        assertTrue("Uploads should be completed", result.uploadsCompleted)

        val filesToUpload = capturedFilesToUpload.singleOrNull() ?: emptyList()
        assertTrue(
            "Upload should use the account ID cloud prefix and nested Saves file. Got: $filesToUpload",
            filesToUpload.contains("%WinAppDataLocalLow%75264032/Saves/1003.sav")
        )
    }

    @Test
    fun twoPointMuseumDownloadWithSlashAfterRootLandsInCloudDirectory() = runBlocking {
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)
        db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, 0))
        db.appFileChangeListsDao().insert(steamAppId, emptyList())

        val localLowRoot = File(tempDir, "local-low-download")
        val userdataRoot = File(tempDir, "userdata-download")
        val cloudContent = "museum cloud save content".toByteArray()
        val cloudSha = CryptoHelper.shaHash(cloudContent)
        val expectedSave = File(
            localLowRoot,
            "Two Point Studios/Two Point Museum/Cloud/75264032/Saves/Slot1/1003.sav",
        )
        val wrongSave = File(localLowRoot, "75264032/Saves/Slot1/1003.sav")

        val appUnderTest = db.steamAppDao().findApp(steamAppId)!!
            .copy(
                ufs = UFS(
                    saveFilePatterns = listOf(
                        SaveFilePattern(
                            root = PathType.WinAppDataLocalLow,
                            path = "Two Point Studios/Two Point Museum/Cloud/75264032",
                            pattern = "*",
                            recursive = 1,
                            uploadRoot = PathType.WinAppDataLocalLow,
                            uploadPath = "75264032",
                        ),
                    ),
                ),
            )

        val mockFile = mock<AppFileInfo>()
        whenever(mockFile.filename).thenReturn("1003.sav")
        whenever(mockFile.shaFile).thenReturn(cloudSha)
        whenever(mockFile.pathPrefixIndex).thenReturn(0)
        whenever(mockFile.timestamp).thenReturn(Date())
        whenever(mockFile.rawFileSize).thenReturn(cloudContent.size)

        val cloudChangeNumber = 5L
        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(cloudChangeNumber)
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(listOf("%WinAppDataLocalLow%75264032/Saves/Slot1/"))
        whenever(mockAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(mockAppFileChangeList.files).thenReturn(listOf(mockFile))

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockAppFileChangeList)

        val mockDownloadInfo = mock<FileDownloadInfo>()
        whenever(mockDownloadInfo.urlHost).thenReturn("test.example.com")
        whenever(mockDownloadInfo.urlPath).thenReturn("/download/two-point-museum")
        whenever(mockDownloadInfo.useHttps).thenReturn(true)
        whenever(mockDownloadInfo.requestHeaders).thenReturn(emptyList())
        whenever(mockDownloadInfo.fileSize).thenReturn(cloudContent.size)
        whenever(mockDownloadInfo.rawFileSize).thenReturn(cloudContent.size)
        whenever(mockDownloadInfo.timestamp).thenReturn(Date())

        every { mockSteamCloud.clientFileDownload(any(), any()) } answers {
            assertEquals("%WinAppDataLocalLow%75264032/Saves/Slot1/1003.sav", secondArg<String>())
            CompletableFuture.completedFuture(mockDownloadInfo)
        }
        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } answers {
            assertEquals("%WinAppDataLocalLow%75264032/Saves/Slot1/1003.sav", secondArg<String>())
            CompletableFuture.completedFuture(mockDownloadInfo)
        }

        val mockHttpClient = mock<OkHttpClient>()
        val mockCall = mock<Call>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        whenever(mockHttpClient.newCall(any())).thenReturn(mockCall)
        whenever(mockCall.execute()).thenReturn(
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://test.example.com/download/two-point-museum").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(cloudContent.toResponseBody(null))
                .build(),
        )

        val prefixToPath: (String) -> String = { prefix ->
            when (prefix) {
                "WinAppDataLocalLow" -> localLowRoot.absolutePath
                "SteamUserData" -> userdataRoot.absolutePath
                else -> tempDir.absolutePath
            }
        }

        val result = SteamAutoCloud.syncUserFiles(
            appInfo = appUnderTest,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        assertNotNull("Result should not be null", result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertEquals(1, result.filesDownloaded)
        assertTrue("Downloaded save should land in the game's Cloud directory", expectedSave.exists())
        assertFalse("Downloaded save must not land directly under LocalLow/accountId", wrongSave.exists())
        assertEquals(cloudContent.contentToString(), expectedSave.readBytes().contentToString())
    }

    @Test
    fun uploadUsesOriginalRootPrefixWhenRootoverrideApplied() = runBlocking {
        val matchingChangeNumber = 5
        runBlocking {
            db.appChangeNumbersDao().deleteByAppId(steamAppId)
            db.appFileChangeListsDao().deleteByAppId(steamAppId)
            db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, matchingChangeNumber.toLong()))
            // Insert a non-empty stale cache so cacheIsAbsentOrEmpty=false and the upload path is taken.
            db.appFileChangeListsDao().insert(steamAppId, listOf(
                app.gamenative.data.UserFileInfo(
                    root = PathType.WinMyDocuments,
                    path = "__stale__",
                    filename = "__placeholder__",
                    timestamp = 0L,
                    sha = ByteArray(20) { 0 },
                )
            ))
        }

        // Create a temp directory to act as the WinAppDataRoaming root
        val roamingRoot = File(tempDir, "roaming")
        val userdataRoot = File(tempDir, "userdata")
        val saveSubdir = File(roamingRoot, "TheGame")
        saveSubdir.mkdirs()
        val saveFile = File(saveSubdir, "save.sav")
        saveFile.writeBytes("rootoverride save content".toByteArray())

        // SaveFilePattern with rootoverride applied: root remapped to WinAppDataRoaming,
        // uploadRoot preserved as GameInstall (the original manifest root).
        val saveFilePatterns = listOf(
            SaveFilePattern(
                root = PathType.WinAppDataRoaming,
                path = "TheGame",
                pattern = "save.sav",
                uploadRoot = PathType.GameInstall,
            ),
        )
        val updatedApp = db.steamAppDao().findApp(steamAppId)!!.copy(ufs = UFS(saveFilePatterns = saveFilePatterns))

        val mockAppFileChangeList = mock<AppFileChangeList>()
        whenever(mockAppFileChangeList.currentChangeNumber).thenReturn(matchingChangeNumber.toLong())
        whenever(mockAppFileChangeList.isOnlyDelta).thenReturn(false)
        whenever(mockAppFileChangeList.appBuildIDHwm).thenReturn(0)
        whenever(mockAppFileChangeList.pathPrefixes).thenReturn(emptyList())
        whenever(mockAppFileChangeList.machineNames).thenReturn(emptyList())
        whenever(mockAppFileChangeList.files).thenReturn(emptyList())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockAppFileChangeList)

        val mockUploadBatchResponse = mock<`in`.dragonbra.javasteam.steam.handlers.steamcloud.AppUploadBatchResponse>()
        whenever(mockUploadBatchResponse.batchID).thenReturn(1)
        whenever(mockUploadBatchResponse.appChangeNumber).thenReturn((matchingChangeNumber + 1).toLong())

        val capturedFilesToUpload = mutableListOf<List<String>>()
        every {
            mockSteamCloud.beginAppUploadBatch(any(), any(), any(), any(), any(), any(), any())
        } answers {
            for (i in args.indices) {
                val a = args[i]
                if (a is List<*> && a.all { it is String } && capturedFilesToUpload.isEmpty()) {
                    capturedFilesToUpload.add(a as List<String>)
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

        val prefixToPath: (String) -> String = { prefix ->
            when (prefix) {
                "WinAppDataRoaming" -> roamingRoot.absolutePath
                "SteamUserData" -> userdataRoot.absolutePath
                else -> tempDir.absolutePath
            }
        }

        val result = SteamAutoCloud.syncUserFiles(
            appInfo = updatedApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = prefixToPath,
        ).await()

        assertNotNull("Result should not be null", result)
        assertEquals("Should upload 1 file", 1, result!!.filesUploaded)
        assertTrue("Uploads should be completed", result.uploadsCompleted)

        val filesToUpload = capturedFilesToUpload.singleOrNull() ?: emptyList()
        assertTrue(
            "Upload prefix must use original GameInstall root, not remapped WinAppDataRoaming. Got: $filesToUpload",
            filesToUpload.any { it.startsWith("%GameInstall%") }
        )
        assertFalse(
            "Upload prefix must NOT use remapped WinAppDataRoaming root. Got: $filesToUpload",
            filesToUpload.any { it.startsWith("%WinAppDataRoaming%") }
        )
    }
    // ── Sync decision tests ──────────────────────────────────────────────
    // These test what happens when a user launches a game under various
    // combinations of local save state and cloud save state.
    //
    // Key concepts:
    //   "synced"     = app was previously synced; the DB has a change number
    //                  and a cached snapshot of what files looked like last time
    //   "DB cleared" = an app update wiped the sync DB; no record of prior syncs
    //   "never synced" = fresh install, user has never gone online with this game
    //   "cache cleared" = the file snapshot is gone but the change number survived
    //   "cloud ahead"   = another device (or a prior session) uploaded newer saves
    //   "preferredSave" = user already chose Keep Local / Keep Remote in a dialog

    private fun makePrefixToPath(): (String) -> String = { prefix ->
        // getPathTypePairs passes placeholders with % signs, production uses PathType.from()
        val stripped = prefix.removePrefix("%").removeSuffix("%")
        when (stripped) {
            "WinMyDocuments" -> {
                val imageFs = ImageFs.find(context)
                val wineprefix = File(imageFs.wineprefix)
                File(wineprefix, "dosdevices/c:/users/xuser/Documents").absolutePath
            }
            else -> tempDir.absolutePath
        }
    }

    private fun makeCloudFileChangeList(
        cloudChangeNumber: Long,
        files: List<AppFileInfo> = emptyList(),
        pathPrefixes: List<String> = emptyList(),
    ): AppFileChangeList {
        val mock = mock<AppFileChangeList>()
        whenever(mock.currentChangeNumber).thenReturn(cloudChangeNumber)
        whenever(mock.isOnlyDelta).thenReturn(false)
        whenever(mock.appBuildIDHwm).thenReturn(0)
        whenever(mock.pathPrefixes).thenReturn(pathPrefixes)
        whenever(mock.machineNames).thenReturn(emptyList())
        whenever(mock.files).thenReturn(files)
        return mock
    }

    /** Compute SHA-1 of raw bytes — same algorithm as SteamAutoCloud.streamingShaHash */
    private fun sha1(content: ByteArray): ByteArray {
        return java.security.MessageDigest.getInstance("SHA-1").digest(content)
    }

    /** Insert a cached file list that matches the current local files (simulating a prior sync).
     *  Must replicate exactly what getLocalUserFilesAsPrefixMap produces: path = substitutedPath,
     *  cloudPath = uploadPath (raw, may have backslashes), filename = relativePath from basePath. */
    private fun cacheCurrentLocalFiles(changeNumber: Long) = runBlocking {
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)
        db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, changeNumber))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val prefixToPath = makePrefixToPath()
        val cachedFiles = mutableListOf<app.gamenative.data.UserFileInfo>()

        testApp.ufs.saveFilePatterns.filter { it.root.isWindows }.forEach { pattern ->
            val basePath = java.nio.file.Paths.get(prefixToPath(pattern.root.toString()), pattern.substitutedPath)
            if (!java.nio.file.Files.exists(basePath)) return@forEach

            app.gamenative.utils.FileUtils.findFilesRecursive(basePath, pattern.pattern, 5).forEach { filePath ->
                val relativePath = basePath.relativize(filePath).toString()
                cachedFiles.add(
                    app.gamenative.data.UserFileInfo(
                        root = pattern.root,
                        path = pattern.substitutedPath,
                        filename = relativePath,
                        timestamp = java.nio.file.Files.getLastModifiedTime(filePath).toMillis(),
                        sha = sha1(java.nio.file.Files.readAllBytes(filePath)),
                        cloudRoot = pattern.uploadRoot,
                        cloudPath = pattern.uploadPath,
                    )
                )
            }
        }

        db.appFileChangeListsDao().insert(steamAppId, cachedFiles)
    }

    // ── Scenario 1: App update wipes sync DB, user has local saves, cloud has different saves ──
    // Must ask the user which saves to keep — never silently overwrite.
    @Test
    fun dbCleared_localFilesExist_cloudAhead_returnsConflict() = runBlocking {
        // DB cleared: no change number, no cached file list
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)

        // local files exist (from setUp)
        assertTrue("Precondition: local save files exist", saveFilesDir.listFiles()!!.isNotEmpty())

        // cloud is ahead
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = 5))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(
            "Must show conflict dialog, not silently download",
            SyncResult.Conflict,
            result!!.syncResult,
        )
    }

    // ── Scenario 2: App update wipes sync DB, no local saves, cloud has saves ──
    // Nothing local to lose — safe to download cloud saves.
    @Test
    fun dbCleared_noLocalFiles_cloudAhead_downloads() = runBlocking {
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)

        // delete all local files
        saveFilesDir.listFiles()?.forEach { it.delete() }

        // cloud is ahead — but we need download mocks for this to succeed
        val cloudContent = "cloud save".toByteArray()
        val cloudSha = sha1(cloudContent)
        val mockFile = mock<AppFileInfo>()
        whenever(mockFile.filename).thenReturn("SaveData_0.sav")
        whenever(mockFile.shaFile).thenReturn(cloudSha)
        whenever(mockFile.pathPrefixIndex).thenReturn(0)
        whenever(mockFile.timestamp).thenReturn(Date())
        whenever(mockFile.rawFileSize).thenReturn(cloudContent.size)

        val cloudFileChangeList = makeCloudFileChangeList(
            cloudChangeNumber = 5,
            files = listOf(mockFile),
            pathPrefixes = listOf("%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569/SaveGames"),
        )
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(cloudFileChangeList)

        // mock download pipeline
        val mockDownloadInfo = mock<FileDownloadInfo>()
        whenever(mockDownloadInfo.urlHost).thenReturn("test.example.com")
        whenever(mockDownloadInfo.urlPath).thenReturn("/download/file1")
        whenever(mockDownloadInfo.useHttps).thenReturn(true)
        whenever(mockDownloadInfo.requestHeaders).thenReturn(emptyList())
        whenever(mockDownloadInfo.fileSize).thenReturn(cloudContent.size)
        whenever(mockDownloadInfo.rawFileSize).thenReturn(cloudContent.size)

        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockDownloadInfo)

        val mockHttpClient = mock<OkHttpClient>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        val mockCall = mock<Call>()
        whenever(mockHttpClient.newCall(any())).thenReturn(mockCall)
        val response = Response.Builder()
            .request(okhttp3.Request.Builder().url("https://test.example.com/download/file1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(cloudContent.toResponseBody())
            .build()
        whenever(mockCall.execute()).thenReturn(response)

        val mockSteamClient = mockSteamService.steamClient!!
        val mockConfig = mock<SteamConfiguration>()
        whenever(mockSteamClient.configuration).thenReturn(mockConfig)
        whenever(mockConfig.httpClient).thenReturn(mockHttpClient)

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(
            "No local files to lose — should download",
            SyncResult.Success,
            result!!.syncResult,
        )
        assertTrue("Should have downloaded files", result.filesDownloaded > 0)
    }

    // ── Scenario 2b: First boot, cloud has multiple files → download all, verify on disk ──
    // Replaces the disabled testDownloadCloudSavesOnFirstBoot which had several mock bugs.
    @Test
    fun firstBoot_multipleCloudFiles_downloadsAll() = runBlocking {
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)
        saveFilesDir.listFiles()?.forEach { it.delete() }

        val file1Content = "save file alpha".toByteArray()
        val file2Content = "save file beta".toByteArray()
        val file3Content = "system data".toByteArray()

        fun makeFileInfo(name: String, content: ByteArray): AppFileInfo {
            val m = mock<AppFileInfo>()
            whenever(m.filename).thenReturn(name)
            whenever(m.shaFile).thenReturn(sha1(content))
            whenever(m.pathPrefixIndex).thenReturn(0)
            whenever(m.timestamp).thenReturn(Date())
            whenever(m.rawFileSize).thenReturn(content.size)
            return m
        }

        // filenames must match UFS save patterns so post-download re-scan finds them
        val cloudFiles = listOf(
            makeFileInfo("AutoSaveData.sav", file1Content),
            makeFileInfo("SaveData_0.sav", file2Content),
            makeFileInfo("SystemData_0.sav", file3Content),
        )
        val contents = listOf(file1Content, file2Content, file3Content)

        val pathPrefix = "%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569/SaveGames"
        val cloudFileChangeList = makeCloudFileChangeList(
            cloudChangeNumber = 5,
            files = cloudFiles,
            pathPrefixes = listOf(pathPrefix),
        )
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(cloudFileChangeList)

        // mock download info per file
        val downloadInfos = contents.mapIndexed { i, content ->
            mock<FileDownloadInfo>().also { d ->
                whenever(d.urlHost).thenReturn("test.example.com")
                whenever(d.urlPath).thenReturn("/download/file${i + 1}")
                whenever(d.useHttps).thenReturn(true)
                whenever(d.requestHeaders).thenReturn(emptyList())
                whenever(d.fileSize).thenReturn(content.size)
                whenever(d.rawFileSize).thenReturn(content.size)
            }
        }

        val downloadInfoByPath = mapOf(
            "$pathPrefix/${cloudFiles[0].filename}" to downloadInfos[0],
            "$pathPrefix/${cloudFiles[1].filename}" to downloadInfos[1],
            "$pathPrefix/${cloudFiles[2].filename}" to downloadInfos[2],
        )

        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } answers {
            val path = secondArg<String>()
            CompletableFuture.completedFuture(
                downloadInfoByPath[path] ?: error("Unexpected path: $path"),
            )
        }

        // mock HTTP responses
        val mockHttpClient = mock<OkHttpClient>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        val callsByPath = contents.mapIndexed { index, content ->
            val path = "/download/file${index + 1}"
            val call = mock<Call>()
            whenever(call.execute()).thenReturn(
                Response.Builder()
                    .request(okhttp3.Request.Builder().url("https://test.example.com$path").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(content.toResponseBody())
                    .build(),
            )
            path to call
        }.toMap()
        whenever(mockHttpClient.newCall(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<okhttp3.Request>(0)
            callsByPath[request.url.encodedPath] ?: error("Unexpected URL: ${request.url}")
        }

        val mockSteamClient = mockSteamService.steamClient!!
        val mockConfig = mock<SteamConfiguration>()
        whenever(mockSteamClient.configuration).thenReturn(mockConfig)
        whenever(mockConfig.httpClient).thenReturn(mockHttpClient)

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertEquals("Should download 3 files", 3, result.filesDownloaded)
        assertTrue("Bytes downloaded should be > 0", result.bytesDownloaded > 0)

        // verify files on disk
        val expected1 = File(saveFilesDir, "AutoSaveData.sav")
        val expected2 = File(saveFilesDir, "SaveData_0.sav")
        val expected3 = File(saveFilesDir, "SystemData_0.sav")

        assertTrue("File 1 should exist", expected1.exists())
        assertTrue("File 2 should exist", expected2.exists())
        assertTrue("File 3 should exist", expected3.exists())

        assertEquals("File 1 content", file1Content.contentToString(), expected1.readBytes().contentToString())
        assertEquals("File 2 content", file2Content.contentToString(), expected2.readBytes().contentToString())
        assertEquals("File 3 content", file3Content.contentToString(), expected3.readBytes().contentToString())

        // verify downloads seeded the hash cache with the correct SHA
        val cache1 = db.steamFileHashCacheDao().getByAppIdAndPath(steamAppId, expected1.toPath().toString())
        val cache2 = db.steamFileHashCacheDao().getByAppIdAndPath(steamAppId, expected2.toPath().toString())
        val cache3 = db.steamFileHashCacheDao().getByAppIdAndPath(steamAppId, expected3.toPath().toString())
        assertNotNull("Cache entry for file 1 should exist", cache1)
        assertNotNull("Cache entry for file 2 should exist", cache2)
        assertNotNull("Cache entry for file 3 should exist", cache3)
        assertArrayEquals("File 1 cache SHA should match file content", sha1(file1Content), cache1!!.sha)
        assertArrayEquals("File 2 cache SHA should match file content", sha1(file2Content), cache2!!.sha)
        assertArrayEquals("File 3 cache SHA should match file content", sha1(file3Content), cache3!!.sha)

        // verify DB change number updated
        val cn = db.appChangeNumbersDao().getByAppId(steamAppId)
        assertNotNull("Change number should exist", cn)
        assertEquals("Change number should match cloud", 5L, cn!!.changeNumber)
    }

    // ── Scenario 3: User plays offline on a train, comes home — cloud untouched ──
    // Local saves are newer, cloud hasn't changed → upload local saves to cloud.
    @Test
    fun synced_offlinePlay_cloudUnchanged_uploads() = runBlocking {
        val cn = 5L
        cacheCurrentLocalFiles(cn)

        // modify a local file (simulating offline play)
        File(saveFilesDir, "SaveData_0.sav").writeBytes("modified after offline play".toByteArray())

        // cloud hasn't changed
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = cn))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertTrue("Should upload modified files", result.uploadsCompleted)
        assertTrue("Should have uploaded at least 1 file", result.filesUploaded > 0)
    }

    // ── Scenario 4: User plays offline, meanwhile another device also played and uploaded ──
    // Both sides changed — must ask the user which saves to keep.
    @Test
    fun synced_offlinePlay_cloudAdvanced_returnsConflict() = runBlocking {
        val localCn = 5L
        cacheCurrentLocalFiles(localCn)

        // modify a local file (simulating offline play)
        File(saveFilesDir, "SaveData_0.sav").writeBytes("modified after offline play".toByteArray())

        // cloud advanced (another device played)
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = localCn + 1))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(
            "Both local and remote changed — must show conflict",
            SyncResult.Conflict,
            result!!.syncResult,
        )
    }

    // ── Scenario 5: User didn't play locally, but another device uploaded new saves ──
    // No local changes to lose — safe to download.
    @Test
    fun synced_noLocalChanges_cloudAdvanced_downloads() = runBlocking {
        val localCn = 5L
        cacheCurrentLocalFiles(localCn)

        // local files are NOT modified — cache SHAs match disk

        // cloud advanced — but we need download mocks
        val cloudContent = "new cloud save".toByteArray()
        val cloudSha = sha1(cloudContent)
        val mockFile = mock<AppFileInfo>()
        whenever(mockFile.filename).thenReturn("SaveData_0.sav")
        whenever(mockFile.shaFile).thenReturn(cloudSha)
        whenever(mockFile.pathPrefixIndex).thenReturn(0)
        whenever(mockFile.timestamp).thenReturn(Date())
        whenever(mockFile.rawFileSize).thenReturn(cloudContent.size)

        val cloudFileChangeList = makeCloudFileChangeList(
            cloudChangeNumber = localCn + 1,
            files = listOf(mockFile),
            pathPrefixes = listOf("%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569/SaveGames"),
        )
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(cloudFileChangeList)

        val mockDownloadInfo = mock<FileDownloadInfo>()
        whenever(mockDownloadInfo.urlHost).thenReturn("test.example.com")
        whenever(mockDownloadInfo.urlPath).thenReturn("/download/file1")
        whenever(mockDownloadInfo.useHttps).thenReturn(true)
        whenever(mockDownloadInfo.requestHeaders).thenReturn(emptyList())
        whenever(mockDownloadInfo.fileSize).thenReturn(cloudContent.size)
        whenever(mockDownloadInfo.rawFileSize).thenReturn(cloudContent.size)

        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockDownloadInfo)

        val mockHttpClient = mock<OkHttpClient>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        val mockCall = mock<Call>()
        whenever(mockHttpClient.newCall(any())).thenReturn(mockCall)
        val response = Response.Builder()
            .request(okhttp3.Request.Builder().url("https://test.example.com/download/file1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(cloudContent.toResponseBody())
            .build()
        whenever(mockCall.execute()).thenReturn(response)

        val mockSteamClient = mockSteamService.steamClient!!
        val mockConfig = mock<SteamConfiguration>()
        whenever(mockSteamClient.configuration).thenReturn(mockConfig)
        whenever(mockConfig.httpClient).thenReturn(mockHttpClient)

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(
            "No local changes, cloud ahead — safe to download",
            SyncResult.Success,
            result!!.syncResult,
        )
        assertTrue("Should have downloaded files", result.filesDownloaded > 0)
    }

    // ── Scenario 6: Nothing changed anywhere — no sync needed ──
    @Test
    fun synced_noChangesAnywhere_returnsUpToDate() = runBlocking {
        val cn = 5L
        cacheCurrentLocalFiles(cn)

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = cn))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.UpToDate, result!!.syncResult)
    }

    // ── Scenario 7: Fresh install, user played offline first, cloud has saves from another device ──
    // Same risk as scenario 1 — must ask, not silently overwrite.
    @Test
    fun neverSynced_localFilesExist_cloudAhead_returnsConflict() = runBlocking {
        // CN = -1 (never synced), no cached file list
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)

        // local files exist
        assertTrue("Precondition: local save files exist", saveFilesDir.listFiles()!!.isNotEmpty())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = 3))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(
            "Never-synced user with local files must get conflict dialog",
            SyncResult.Conflict,
            result!!.syncResult,
        )
    }

    // ── Scenario 8: App update + conflict — user picks "Keep Local" → upload local saves ──
    @Test
    fun dbCleared_localFiles_preferLocal_uploads() = runBlocking {
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)

        assertTrue("Precondition: local save files exist", saveFilesDir.listFiles()!!.isNotEmpty())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = 5))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.Local,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertTrue("Should upload local files", result.uploadsCompleted)
        assertTrue("Should have uploaded files", result.filesUploaded > 0)
    }

    // ── Scenario 9: Partial DB issue — file snapshot lost but sync counter survived ──
    // Can't tell if local files changed since last sync — must ask.
    @Test
    fun cacheCleared_cnPreserved_cloudSameCn_returnsConflict() = runBlocking {
        val cn = 5L
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)
        // CN preserved, but cache cleared
        db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, cn))

        assertTrue("Precondition: local save files exist", saveFilesDir.listFiles()!!.isNotEmpty())

        // cloud same CN — enters == branch, but cacheIsAbsentOrEmpty forces effectiveLocalChangeNumber = -1
        // which makes -1 < cn → enters < branch → isCacheCleared fires
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = cn))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(
            "Cache cleared with local files must show conflict even when CN matches",
            SyncResult.Conflict,
            result!!.syncResult,
        )
    }

    // ── Scenario 10: Same as 9, but cloud also has newer saves — still must ask ──
    @Test
    fun cacheCleared_cnPreserved_cloudAhead_returnsConflict() = runBlocking {
        val localCn = 5L
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)
        db.appChangeNumbersDao().insert(app.gamenative.data.ChangeNumbers(steamAppId, localCn))

        assertTrue("Precondition: local save files exist", saveFilesDir.listFiles()!!.isNotEmpty())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = localCn + 1))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(
            "Cache cleared with local files and cloud ahead must show conflict",
            SyncResult.Conflict,
            result!!.syncResult,
        )
    }

    @Test
    fun ufsRefreshPreservesSnapshot_emptyCloudDoesNotDeleteLocalFiles() = runBlocking {
        cacheCurrentLocalFiles(0)
        val localSave = File(saveFilesDir, "SaveData_0.sav")
        assertTrue("Precondition: local save file exists", localSave.exists())

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = 0))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.UpToDate, result!!.syncResult)
        assertEquals("No local files should be deleted", 0, result.filesDeleted)
        assertTrue("Local save should be preserved when cloud is empty", localSave.exists())
    }

    // ── Scenario 11: Brand new game, never played anywhere — nothing to sync ──
    @Test
    fun neverSynced_noLocalFiles_noCloud_succeeds() = runBlocking {
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)
        saveFilesDir.listFiles()?.forEach { it.delete() }

        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(makeCloudFileChangeList(cloudChangeNumber = 0))

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertEquals("No files to download", 0, result.filesDownloaded)
    }

    // ── Scenario 12: Offline play + another device played — user picks "Keep Remote" ──
    @Test
    fun synced_offlinePlay_cloudAdvanced_preferRemote_downloads() = runBlocking {
        val localCn = 5L
        cacheCurrentLocalFiles(localCn)

        File(saveFilesDir, "SaveData_0.sav").writeBytes("modified after offline play".toByteArray())

        val cloudContent = "remote save data".toByteArray()
        val cloudSha = sha1(cloudContent)
        val mockFile = mock<AppFileInfo>()
        whenever(mockFile.filename).thenReturn("SaveData_0.sav")
        whenever(mockFile.shaFile).thenReturn(cloudSha)
        whenever(mockFile.pathPrefixIndex).thenReturn(0)
        whenever(mockFile.timestamp).thenReturn(Date())
        whenever(mockFile.rawFileSize).thenReturn(cloudContent.size)

        val cloudFileChangeList = makeCloudFileChangeList(
            cloudChangeNumber = localCn + 1,
            files = listOf(mockFile),
            pathPrefixes = listOf("%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569"),
        )
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(cloudFileChangeList)

        val mockDownloadInfo = mock<FileDownloadInfo>()
        whenever(mockDownloadInfo.urlHost).thenReturn("test.example.com")
        whenever(mockDownloadInfo.urlPath).thenReturn("/download/file1")
        whenever(mockDownloadInfo.useHttps).thenReturn(true)
        whenever(mockDownloadInfo.requestHeaders).thenReturn(emptyList())
        whenever(mockDownloadInfo.fileSize).thenReturn(cloudContent.size)
        whenever(mockDownloadInfo.rawFileSize).thenReturn(cloudContent.size)

        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockDownloadInfo)

        val mockHttpClient = mock<OkHttpClient>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        val mockCall = mock<Call>()
        whenever(mockHttpClient.newCall(any())).thenReturn(mockCall)
        val response = Response.Builder()
            .request(okhttp3.Request.Builder().url("https://test.example.com/download/file1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(cloudContent.toResponseBody())
            .build()
        whenever(mockCall.execute()).thenReturn(response)

        val mockSteamClient = mockSteamService.steamClient!!
        val mockConfig = mock<SteamConfiguration>()
        whenever(mockSteamClient.configuration).thenReturn(mockConfig)
        whenever(mockConfig.httpClient).thenReturn(mockHttpClient)

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.Remote,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertTrue("Should have downloaded files", result.filesDownloaded > 0)
    }

    // ── Scenario 13: App update + conflict — user picks "Keep Remote" → download cloud saves ──
    @Test
    fun dbCleared_localFiles_preferRemote_downloads() = runBlocking {
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)

        assertTrue("Precondition: local save files exist", saveFilesDir.listFiles()!!.isNotEmpty())

        val cloudContent = "remote save after update".toByteArray()
        val cloudSha = sha1(cloudContent)
        val mockFile = mock<AppFileInfo>()
        whenever(mockFile.filename).thenReturn("SaveData_0.sav")
        whenever(mockFile.shaFile).thenReturn(cloudSha)
        whenever(mockFile.pathPrefixIndex).thenReturn(0)
        whenever(mockFile.timestamp).thenReturn(Date())
        whenever(mockFile.rawFileSize).thenReturn(cloudContent.size)

        val cloudFileChangeList = makeCloudFileChangeList(
            cloudChangeNumber = 5,
            files = listOf(mockFile),
            pathPrefixes = listOf("%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569"),
        )
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(cloudFileChangeList)

        val mockDownloadInfo = mock<FileDownloadInfo>()
        whenever(mockDownloadInfo.urlHost).thenReturn("test.example.com")
        whenever(mockDownloadInfo.urlPath).thenReturn("/download/file1")
        whenever(mockDownloadInfo.useHttps).thenReturn(true)
        whenever(mockDownloadInfo.requestHeaders).thenReturn(emptyList())
        whenever(mockDownloadInfo.fileSize).thenReturn(cloudContent.size)
        whenever(mockDownloadInfo.rawFileSize).thenReturn(cloudContent.size)

        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockDownloadInfo)

        val mockHttpClient = mock<OkHttpClient>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        val mockCall = mock<Call>()
        whenever(mockHttpClient.newCall(any())).thenReturn(mockCall)
        val response = Response.Builder()
            .request(okhttp3.Request.Builder().url("https://test.example.com/download/file1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(cloudContent.toResponseBody())
            .build()
        whenever(mockCall.execute()).thenReturn(response)

        val mockSteamClient = mockSteamService.steamClient!!
        val mockConfig = mock<SteamConfiguration>()
        whenever(mockSteamClient.configuration).thenReturn(mockConfig)
        whenever(mockConfig.httpClient).thenReturn(mockHttpClient)

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.Remote,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertTrue("Should have downloaded files", result.filesDownloaded > 0)
    }
    @Test
    fun synced_cloudAdvanced_metadataFailure_doesNotCancelSiblingDownloads() = runBlocking {
        val localCn = 5L
        cacheCurrentLocalFiles(localCn)

        val successfulCloudContent = "fresh autosave from cloud".toByteArray()
        val successfulFile = mock<AppFileInfo>()
        whenever(successfulFile.filename).thenReturn("SaveGames/AutoSaveData.sav")
        whenever(successfulFile.shaFile).thenReturn(sha1(successfulCloudContent))
        whenever(successfulFile.pathPrefixIndex).thenReturn(0)
        whenever(successfulFile.timestamp).thenReturn(Date())
        whenever(successfulFile.rawFileSize).thenReturn(successfulCloudContent.size)

        val metadataFailureFile = mock<AppFileInfo>()
        whenever(metadataFailureFile.filename).thenReturn("SaveGames/SaveData_0.sav")
        whenever(metadataFailureFile.shaFile).thenReturn(sha1("savedata0 content".toByteArray()))
        whenever(metadataFailureFile.pathPrefixIndex).thenReturn(0)
        whenever(metadataFailureFile.timestamp).thenReturn(Date())
        whenever(metadataFailureFile.rawFileSize).thenReturn("savedata0 content".toByteArray().size)

        val pathPrefix = "%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569"
        val cloudFileChangeList = makeCloudFileChangeList(
            cloudChangeNumber = localCn + 1,
            files = listOf(successfulFile, metadataFailureFile),
            pathPrefixes = listOf(pathPrefix),
        )
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(cloudFileChangeList)

        val successfulDownloadInfo = mock<FileDownloadInfo>()
        whenever(successfulDownloadInfo.urlHost).thenReturn("test.example.com")
        whenever(successfulDownloadInfo.urlPath).thenReturn("/download/autosave")
        whenever(successfulDownloadInfo.useHttps).thenReturn(true)
        whenever(successfulDownloadInfo.requestHeaders).thenReturn(emptyList())
        whenever(successfulDownloadInfo.fileSize).thenReturn(successfulCloudContent.size)
        whenever(successfulDownloadInfo.rawFileSize).thenReturn(successfulCloudContent.size)

        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } answers {
            when (val path = secondArg<String>()) {
                "$pathPrefix/SaveGames/AutoSaveData.sav" -> CompletableFuture.completedFuture(successfulDownloadInfo)
                "$pathPrefix/SaveGames/SaveData_0.sav" -> CompletableFuture<FileDownloadInfo>().also {
                    it.completeExceptionally(IOException("metadata lookup failed"))
                }
                else -> error("Unexpected path: $path")
            }
        }

        val mockHttpClient = mock<OkHttpClient>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        val successfulCall = mock<Call>()
        whenever(mockHttpClient.newCall(any())).thenReturn(successfulCall)
        whenever(successfulCall.execute()).thenReturn(
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://test.example.com/download/autosave").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(successfulCloudContent.toResponseBody())
                .build(),
        )

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertEquals("Only the healthy file should count as downloaded", 1, result.filesDownloaded)
        assertEquals(
            "Successful sibling download should still update local file",
            successfulCloudContent.contentToString(),
            File(saveFilesDir, "AutoSaveData.sav").readBytes().contentToString(),
        )
    }

    // ── Scenario 16: DB cache wiped by destructive migration, local == remote ──
    // Repro of the "every steam game reports conflict post-update" bug. When
    // fallbackToDestructiveMigration wipes file_change_lists but local save files
    // are byte-identical to the cloud manifest, we must rehydrate the cache
    // silently and NOT show a conflict dialog.
    @Test
    fun dbCleared_localMatchesRemote_rehydratesSilently_noConflict() = runBlocking {
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)

        // the 5 files created in setUp() — cloud manifest must match exactly.
        // local scan basePath = %WinMyDocuments%/My Games/TestGame/Steam/{id},
        // files live under SaveGames/ → filename (relativized) includes that prefix.
        val localFiles = mapOf(
            "SaveGames/AutoSaveData.sav" to "autosave content".toByteArray(),
            "SaveGames/SaveData_0.sav" to "savedata0 content".toByteArray(),
            "SaveGames/ContinueSaveData.sav" to "continue content".toByteArray(),
            "SaveGames/SaveData_1.sav" to "savedata1 content".toByteArray(),
            "SaveGames/SystemData_0.sav" to "systemdata content".toByteArray(),
        )

        assertTrue("Precondition: local save files exist", saveFilesDir.listFiles()!!.isNotEmpty())

        val cloudFiles = localFiles.map { (name, content) ->
            val m = mock<AppFileInfo>()
            whenever(m.filename).thenReturn(name)
            whenever(m.shaFile).thenReturn(sha1(content))
            whenever(m.pathPrefixIndex).thenReturn(0)
            whenever(m.timestamp).thenReturn(Date())
            whenever(m.rawFileSize).thenReturn(content.size)
            m
        }

        val cloudFileChangeList = makeCloudFileChangeList(
            cloudChangeNumber = 5,
            files = cloudFiles,
            pathPrefixes = listOf("%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569"),
        )
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(cloudFileChangeList)

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(
            "local matches remote exactly — should be UpToDate, not Conflict",
            SyncResult.UpToDate,
            result!!.syncResult,
        )
        assertNull(
            "no conflict dialog — conflictUfsVersion must be null",
            result.conflictUfsVersion,
        )
        assertEquals("no downloads", 0, result.filesDownloaded)
        assertEquals("no uploads", 0, result.filesUploaded)

        // cache must be rehydrated so next launch doesn't trip the same path
        val rehydrated = db.appFileChangeListsDao().getByAppId(steamAppId)
        assertNotNull("cache should be rehydrated", rehydrated)
        assertEquals(
            "rehydrated cache should contain all 5 local files",
            5,
            rehydrated!!.userFileInfo.size,
        )
        val rehydratedCn = db.appChangeNumbersDao().getByAppId(steamAppId)
        assertEquals(
            "rehydrated change number should match cloud",
            5L,
            rehydratedCn!!.changeNumber,
        )
    }

    // ── Reconciliation of never-synced cloud files ───────────────────────
    // The offline-first-boot bug: an upload-only sync stamps the local change
    // number to match the cloud's without downloading, so cloud-only files are
    // orphaned. With equal change numbers we must still pull down any cloud file
    // that's absent from both disk and the cache.

    /** Wire up the download pipeline (Cloud metadata + HTTP) for a single cloud file's content. */
    private fun stubSingleFileDownload(content: ByteArray) {
        val mockDownloadInfo = mock<FileDownloadInfo>()
        whenever(mockDownloadInfo.urlHost).thenReturn("test.example.com")
        whenever(mockDownloadInfo.urlPath).thenReturn("/download/file1")
        whenever(mockDownloadInfo.useHttps).thenReturn(true)
        whenever(mockDownloadInfo.requestHeaders).thenReturn(emptyList())
        whenever(mockDownloadInfo.fileSize).thenReturn(content.size)
        whenever(mockDownloadInfo.rawFileSize).thenReturn(content.size)
        whenever(mockDownloadInfo.timestamp).thenReturn(Date())

        every { mockSteamCloud.clientFileDownload(any(), any()) } returns
            CompletableFuture.completedFuture(mockDownloadInfo)
        every { mockSteamCloud.clientFileDownload(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(mockDownloadInfo)

        val mockHttpClient = mock<OkHttpClient>()
        every { Net.httpForParallelDownloads(any()) } returns mockHttpClient
        val mockCall = mock<Call>()
        whenever(mockHttpClient.newCall(any())).thenReturn(mockCall)
        whenever(mockCall.execute()).thenReturn(
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://test.example.com/download/file1").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(content.toResponseBody())
                .build(),
        )

        val mockSteamClient = mockSteamService.steamClient!!
        val mockConfig = mock<SteamConfiguration>()
        whenever(mockSteamClient.configuration).thenReturn(mockConfig)
        whenever(mockConfig.httpClient).thenReturn(mockHttpClient)
    }

    private fun cloudFile(filename: String, content: ByteArray): AppFileInfo {
        val m = mock<AppFileInfo>()
        whenever(m.filename).thenReturn(filename)
        whenever(m.shaFile).thenReturn(sha1(content))
        whenever(m.pathPrefixIndex).thenReturn(0)
        whenever(m.timestamp).thenReturn(Date())
        whenever(m.rawFileSize).thenReturn(content.size)
        return m
    }

    private val saveGamesPrefix = "%WinMyDocuments%/My Games/TestGame/Steam/76561198025127569/SaveGames"

    // ── Equal CN, cloud has a file this device never synced → download it ──
    @Test
    fun equalCn_cloudHasNeverSyncedFile_downloadsAndCaches() = runBlocking {
        val cn = 5L
        cacheCurrentLocalFiles(cn)

        val orphanContent = "cloud-only save this device never had".toByteArray()
        val orphan = cloudFile("OrphanSaveData.sav", orphanContent)

        val changeList = makeCloudFileChangeList(cn, files = listOf(orphan), pathPrefixes = listOf(saveGamesPrefix))
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(changeList)
        stubSingleFileDownload(orphanContent)

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertEquals("orphaned cloud file should be downloaded", 1, result.filesDownloaded)

        val downloaded = File(saveFilesDir, "OrphanSaveData.sav")
        assertTrue("never-synced cloud file must land on disk", downloaded.exists())
        assertEquals(orphanContent.contentToString(), downloaded.readBytes().contentToString())

        // change number is unchanged (cloud didn't advance), and the file is now cached
        val cnAfter = db.appChangeNumbersDao().getByAppId(steamAppId)
        assertEquals("change number must stay equal", cn, cnAfter!!.changeNumber)
        val cache = db.appFileChangeListsDao().getByAppId(steamAppId)!!.userFileInfo
        assertTrue(
            "downloaded file must be added to the cache so it isn't re-fetched next launch",
            cache.any { it.filename.endsWith("OrphanSaveData.sav") },
        )
        assertEquals("cache should now hold the 5 original files plus the downloaded one", 6, cache.size)
    }

    // ── Equal CN, cache-known file deleted from disk → stays a delete, not resurrected ──
    @Test
    fun equalCn_cacheKnownFileMissingFromDisk_notReDownloaded() = runBlocking {
        val cn = 5L
        cacheCurrentLocalFiles(cn)

        // user deleted this save locally; it's still in the cache
        val deleted = File(saveFilesDir, "SaveData_0.sav")
        assertTrue("precondition: file exists before delete", deleted.exists())
        deleted.delete()

        // cloud still lists it. A working download stub is wired up so that if the never-synced
        // check mis-keys the path, reconcile downloads it and the filesDownloaded assertion fails
        // fast — rather than blocking forever on an unstubbed download future.
        val stillInCloud = cloudFile("SaveData_0.sav", "savedata0 content".toByteArray())
        val changeList = makeCloudFileChangeList(cn, files = listOf(stillInCloud), pathPrefixes = listOf(saveGamesPrefix))
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(changeList)
        stubSingleFileDownload("savedata0 content".toByteArray())

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals("locally-deleted file must not be re-downloaded", 0, result!!.filesDownloaded)
        assertFalse("locally-deleted file must not be resurrected on disk", deleted.exists())
    }

    // ── Equal CN, cloud files already present on disk → nothing to do ──
    @Test
    fun equalCn_cloudFilesAlreadyOnDisk_upToDate() = runBlocking {
        val cn = 5L
        cacheCurrentLocalFiles(cn)

        // cloud lists exactly the files already on disk. Correct path-keying means none are
        // "never synced". A working download stub is wired up so a mis-key fails the download
        // assertion fast instead of blocking forever on an unstubbed download future.
        val onDisk = listOf(
            cloudFile("AutoSaveData.sav", "autosave content".toByteArray()),
            cloudFile("SaveData_0.sav", "savedata0 content".toByteArray()),
            cloudFile("ContinueSaveData.sav", "continue content".toByteArray()),
            cloudFile("SaveData_1.sav", "savedata1 content".toByteArray()),
            cloudFile("SystemData_0.sav", "systemdata content".toByteArray()),
        )
        val changeList = makeCloudFileChangeList(cn, files = onDisk, pathPrefixes = listOf(saveGamesPrefix))
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(changeList)
        stubSingleFileDownload("autosave content".toByteArray())

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.UpToDate, result!!.syncResult)
        assertEquals("nothing to download", 0, result.filesDownloaded)
    }

    // ── Equal CN, mixed manifest: only the never-synced file is fetched (strict subset) ──
    // Manifest has two files already on disk plus one orphan. Reconcile must download exactly
    // the orphan and leave the on-disk files untouched — this exercises downloadFiles fetching
    // a strict subset of the manifest, not all of it.
    @Test
    fun equalCn_mixedManifest_downloadsOnlyTheOrphan() = runBlocking {
        val cn = 5L
        cacheCurrentLocalFiles(cn)

        val orphanContent = "the only cloud-only file".toByteArray()
        // Two files that already exist on disk (from setUp) + one orphan that does not.
        val manifest = listOf(
            cloudFile("AutoSaveData.sav", "autosave content".toByteArray()),
            cloudFile("SaveData_0.sav", "savedata0 content".toByteArray()),
            cloudFile("OrphanSaveData.sav", orphanContent),
        )
        val changeList = makeCloudFileChangeList(cn, files = manifest, pathPrefixes = listOf(saveGamesPrefix))
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(changeList)
        // The download stub serves orphanContent for ANY request, so if reconcile wrongly fetched
        // an on-disk file it would both bump filesDownloaded and overwrite that file's content.
        stubSingleFileDownload(orphanContent)

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.None,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertEquals("exactly one (the orphan) should be downloaded", 1, result.filesDownloaded)

        // orphan pulled down with its own content
        val orphan = File(saveFilesDir, "OrphanSaveData.sav")
        assertTrue("orphan must be downloaded", orphan.exists())
        assertEquals(orphanContent.contentToString(), orphan.readBytes().contentToString())

        // the two already-present files must be left exactly as they were (not re-fetched/overwritten)
        assertEquals(
            "AutoSaveData.sav must not be re-fetched",
            "autosave content",
            File(saveFilesDir, "AutoSaveData.sav").readText(),
        )
        assertEquals(
            "SaveData_0.sav must not be re-fetched",
            "savedata0 content",
            File(saveFilesDir, "SaveData_0.sav").readText(),
        )
    }

    // ── Root cause: Keep Local must still pull down cloud-only files, not orphan them ──
    @Test
    fun keepLocal_downloadsNeverSyncedCloudFiles() = runBlocking {
        // db cleared (fresh) + local files exist + cloud ahead → conflict; user picks Keep Local
        db.appChangeNumbersDao().deleteByAppId(steamAppId)
        db.appFileChangeListsDao().deleteByAppId(steamAppId)
        assertTrue("precondition: local save files exist", saveFilesDir.listFiles()!!.isNotEmpty())

        val orphanContent = "PC-only save the phone never had".toByteArray()
        val orphan = cloudFile("CloudOnlySaveData.sav", orphanContent)
        val changeList = makeCloudFileChangeList(5, files = listOf(orphan), pathPrefixes = listOf(saveGamesPrefix))
        every { mockSteamCloud.getAppFileListChange(any(), any(), any()) } returns
            CompletableFuture.completedFuture(changeList)
        stubSingleFileDownload(orphanContent)

        val testApp = db.steamAppDao().findApp(steamAppId)!!
        val result = SteamAutoCloud.syncUserFiles(
            appInfo = testApp,
            clientId = clientId,
            steamInstance = mockSteamService,
            steamCloud = mockSteamCloud,
            preferredSave = SaveLocation.Local,
            prefixToPath = makePrefixToPath(),
        ).await()

        assertNotNull(result)
        assertEquals(SyncResult.Success, result!!.syncResult)
        assertTrue("local saves should upload", result.uploadsCompleted)
        assertTrue("local saves should upload", result.filesUploaded > 0)
        assertEquals("cloud-only file must be pulled down, not orphaned", 1, result.filesDownloaded)
        assertTrue(
            "cloud-only file must land on disk",
            File(saveFilesDir, "CloudOnlySaveData.sav").exists(),
        )
    }

}
