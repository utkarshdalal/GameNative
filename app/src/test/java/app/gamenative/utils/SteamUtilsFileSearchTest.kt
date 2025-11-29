package app.gamenative.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ConfigInfo
import app.gamenative.data.SteamApp
import app.gamenative.db.PluviaDatabase
import app.gamenative.enums.AppType
import app.gamenative.enums.Marker
import app.gamenative.enums.OS
import app.gamenative.enums.ReleaseState
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.Field
import java.nio.file.Files
import java.nio.file.Paths
import java.util.EnumSet
import kotlin.io.path.exists

@RunWith(RobolectricTestRunner::class)
class SteamUtilsFileSearchTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var appDir: File
    private lateinit var db: PluviaDatabase
    private val testAppId = "STEAM_123456"
    private val steamAppId = 123456

    /**
     * Helper function to load production asset content (same as used by SteamUtils.replaceSteamApi)
     */
    private fun loadTestAsset(context: Context, assetPath: String): String {
        return context.assets.open(assetPath).bufferedReader().use { it.readText() }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File.createTempFile("steam_utils_test_", null)
        tempDir.delete()
        tempDir.mkdirs()

        // Set up DownloadService paths
        DownloadService.populateDownloadService(context)
        File(SteamService.internalAppInstallPath).mkdirs()
        SteamService.externalAppInstallPath.takeIf { it.isNotBlank() }?.let { File(it).mkdirs() }

        // Create app directory that SteamService.getAppDirPath will return
        appDir = File(SteamService.internalAppInstallPath, "123456")
        appDir.mkdirs()

        // Set up ImageFs for restoreOriginalExecutable
        val imageFs = ImageFs.find(context)
        val wineprefix = File(imageFs.wineprefix)
        wineprefix.mkdirs()
        val dosDevices = File(wineprefix, "dosdevices")
        dosDevices.mkdirs()
        File(dosDevices, "a:").mkdirs()

        // Set up container directory so ContainerManager can find it
        // This prevents getOrCreateContainer() from trying to create a new container (which needs zstd-jni)
        val homeDir = File(imageFs.rootDir, "home")
        homeDir.mkdirs()

        val containerDir = File(homeDir, "${ImageFs.USER}-${testAppId}")
        containerDir.mkdirs()

        // Create a minimal container config file
        val container = Container(testAppId)
        container.setRootDir(containerDir)
        container.name = "Test Container"
        container.saveData()  // This creates the config file that ContainerManager will load

        // Set up in-memory database with SteamApp entry
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Insert test SteamApp so getAppDirPath() can find it
        val testApp = SteamApp(
            id = steamAppId,
            name = "Test Game",
            config = ConfigInfo(installDir = "123456"),  // This is what getAppDirName() will use
            type = AppType.game,
            osList = EnumSet.of(OS.windows),
            releaseState = ReleaseState.released,
        )
        runBlocking {
            db.steamAppDao().insert(testApp)
        }

        // Create a mock SteamService instance and set it as SteamService.instance
        // This allows getAppInfoOf() to find the test app
        val mockSteamService = mock<SteamService>()
        whenever(mockSteamService.appDao).thenReturn(db.steamAppDao())

        // Mock steamClient and steamID for userSteamId property
        val mockSteamClient = mock<`in`.dragonbra.javasteam.steam.steamclient.SteamClient>()
        val mockSteamID = mock<`in`.dragonbra.javasteam.types.SteamID>()
        whenever(mockSteamService.steamClient).thenReturn(mockSteamClient)
        whenever(mockSteamClient.steamID).thenReturn(mockSteamID)

        // Set the mock as SteamService.instance using reflection
        try {
            val instanceField = SteamService::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, mockSteamService)  // null because it's a static field
        } catch (e: Exception) {
            fail("Failed to set SteamService.instance: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        // Clean up temp directory
        tempDir.deleteRecursively()
        appDir.deleteRecursively()
        // Close database
        db.close()
    }

    @Test
    fun putBackSteamDlls_findsAndRestoresOrigFiles() {
        // Create .orig backup files
        val orig32File = File(appDir, "steam_api.dll.orig")
        val orig64File = File(appDir, "steam_api64.dll.orig")
        orig32File.writeBytes("backup 32bit dll content".toByteArray())
        orig64File.writeBytes("backup 64bit dll content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify restoration
        val restored32File = File(appDir, "steam_api.dll")
        val restored64File = File(appDir, "steam_api64.dll")
        assertTrue("Should restore 32-bit DLL", restored32File.exists())
        assertTrue("Should restore 64-bit DLL", restored64File.exists())
        assertEquals("32-bit DLL content should match backup",
            "backup 32bit dll content", restored32File.readText())
        assertEquals("64-bit DLL content should match backup",
            "backup 64bit dll content", restored64File.readText())
    }

    @Test
    fun putBackSteamDlls_findsOrigFilesInSubdirectories() {
        // Create .orig file in subdirectory
        val subDir = File(appDir, "bin")
        subDir.mkdirs()
        val origFile = File(subDir, "steam_api.dll.orig")
        origFile.writeBytes("backup dll content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify restoration
        val restoredFile = File(subDir, "steam_api.dll")
        assertTrue("Should restore DLL in subdirectory", restoredFile.exists())
        assertEquals("Restored content should match backup",
            "backup dll content", restoredFile.readText())
    }

    @Test
    fun putBackSteamDlls_respectsMaxDepth() {
        // Create directory structure deeper than max depth (5)
        var currentDir = appDir
        for (i in 1..7) {
            currentDir = File(currentDir, "level$i")
            currentDir.mkdirs()
        }

        // Create .orig file beyond max depth
        val deepOrigFile = File(currentDir, "steam_api.dll.orig")
        deepOrigFile.writeBytes("backup content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify file beyond max depth was NOT restored
        val restoredFile = File(currentDir, "steam_api.dll")
        assertFalse("Should NOT restore DLL beyond max depth", restoredFile.exists())
    }

    @Test
    fun putBackSteamDlls_handlesCaseInsensitiveMatching() {
        // Create .orig file with different case
        val origFile = File(appDir, "STEAM_API64.DLL.ORIG")
        origFile.writeBytes("backup content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify restoration (case-insensitive)
        val restoredFile = File(appDir, "steam_api64.dll")
        assertTrue("Should restore DLL with case-insensitive matching", restoredFile.exists())
        assertEquals("Restored content should match backup",
            "backup content", restoredFile.readText())
    }

    @Test
    fun restoreOriginalExecutable_findsAndRestoresOriginalExe() {
        // Set up dosdevices path
        val imageFs = ImageFs.find(context)
        val dosDevicesPath = File(imageFs.wineprefix, "dosdevices/a:")
        dosDevicesPath.mkdirs()

        // Create .original.exe file
        val origExeFile = File(dosDevicesPath, "game.exe.original.exe")
        origExeFile.writeBytes("original exe content".toByteArray())

        // Call the actual function
        SteamUtils.restoreOriginalExecutable(context, steamAppId)

        // Verify restoration
        val restoredFile = File(dosDevicesPath, "game.exe")
        assertTrue("Should restore exe to original location", restoredFile.exists())
        assertEquals("Restored content should match backup",
            "original exe content", restoredFile.readText())
    }

    @Test
    fun restoreOriginalExecutable_respectsMaxDepth() {
        // Set up dosdevices path
        val imageFs = ImageFs.find(context)
        val dosDevicesPath = File(imageFs.wineprefix, "dosdevices/a:")
        dosDevicesPath.mkdirs()

        // Create directory structure deeper than max depth (5)
        var currentDir = dosDevicesPath
        for (i in 1..7) {
            currentDir = File(currentDir, "level$i")
            currentDir.mkdirs()
        }

        // Create .original.exe file beyond max depth
        val deepOrigExeFile = File(currentDir, "game.exe.original.exe")
        deepOrigExeFile.writeBytes("original exe content".toByteArray())

        // Call the actual function
        SteamUtils.restoreOriginalExecutable(context, steamAppId)

        // Verify file beyond max depth was NOT restored
        val restoredFile = File(currentDir, "game.exe")
        assertFalse("Should NOT restore exe beyond max depth", restoredFile.exists())
    }

    @Test
    fun restoreOriginalExecutable_doesNotFailWhenNoBackupFound() {
        // Set up dosdevices path with no backup files
        val imageFs = ImageFs.find(context)
        val dosDevicesPath = File(imageFs.wineprefix, "dosdevices/a:")
        dosDevicesPath.mkdirs()

        // Call the actual function - should not throw
        try {
            SteamUtils.restoreOriginalExecutable(context, steamAppId)
            // Test passes if no exception is thrown
            assertTrue("Should complete without error when no backup found", true)
        } catch (e: Exception) {
            fail("Should not throw exception when no backup found: ${e.message}")
        }
    }

    @Test
    fun putBackSteamDlls_handlesBoth32And64BitInSingleTraversal() {
        // Create both .orig files
        val orig32File = File(appDir, "steam_api.dll.orig")
        val orig64File = File(appDir, "steam_api64.dll.orig")
        orig32File.writeBytes("backup 32bit".toByteArray())
        orig64File.writeBytes("backup 64bit".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify both are restored in a single traversal
        val restored32File = File(appDir, "steam_api.dll")
        val restored64File = File(appDir, "steam_api64.dll")
        assertTrue("Should restore 32-bit DLL", restored32File.exists())
        assertTrue("Should restore 64-bit DLL", restored64File.exists())
    }

    @Test
    fun putBackSteamDlls_deletesExistingDllBeforeRestoring() {
        // Create .orig backup file
        val origFile = File(appDir, "steam_api.dll.orig")
        origFile.writeBytes("backup content".toByteArray())

        // Create existing DLL with different content
        val existingDll = File(appDir, "steam_api.dll")
        existingDll.writeBytes("old dll content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify old DLL was deleted and replaced with backup
        assertTrue("DLL should exist after restoration", existingDll.exists())
        assertEquals("DLL should contain backup content, not old content",
            "backup content", existingDll.readText())
    }

    @Test
    fun walkTopDown_doesNotLeakFileDescriptors() {
        // Create a deep directory structure with many files
        for (i in 1..10) {
            val dir = File(appDir, "level$i")
            dir.mkdirs()
            for (j in 1..5) {
                File(dir, "file$j.txt").writeText("content")
            }
        }

        // Call putBackSteamDlls multiple times (which uses walkTopDown internally)
        repeat(100) {
            SteamUtils.putBackSteamDlls(appDir.absolutePath)
        }

        // If file descriptors were leaking, we'd hit "Too many open files" error
        // This test passes if no exception is thrown
        assertTrue("Should complete without file descriptor leak", true)
    }

    @Test
    fun replaceSteamApi_findsAndReplacesDllInRootDirectory() = runBlocking {
        // Ensure no marker exists
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)

        // Create test DLL file in root
        val dllFile = File(appDir, "steam_api.dll")
        dllFile.writeBytes("original dll content".toByteArray())

        // Call the actual function - test assets should be available from test resources
        SteamUtils.replaceSteamApi(context, testAppId)

        // Verify backup was created
        val backupFile = File(appDir, "steam_api.dll.orig")
        assertTrue("Should create backup .orig file", backupFile.exists())
        assertEquals("Backup should contain original content",
            "original dll content", backupFile.readText())

        // Verify DLL was replaced with asset content
        assertTrue("DLL file should exist after replacement", dllFile.exists())
        val expectedContent = loadTestAsset(context, "steampipe/steam_api.dll")
        assertEquals("DLL should contain asset content",
            expectedContent, dllFile.readText())

        // Verify marker was added
        assertTrue("Should add STEAM_DLL_REPLACED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED))
    }

    @Test
    fun replaceSteamApi_findsDllInSubdirectory() = runBlocking {
        // Ensure no marker exists
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)

        // Create nested directory structure
        val subDir = File(appDir, "bin")
        subDir.mkdirs()
        val dllFile = File(subDir, "steam_api.dll")
        dllFile.writeBytes("original dll content".toByteArray())

        // Call the actual function
        SteamUtils.replaceSteamApi(context, testAppId)

        // Verify backup was created in subdirectory
        val backupFile = File(subDir, "steam_api.dll.orig")
        assertTrue("Should create backup in subdirectory", backupFile.exists())
        assertEquals("Backup should contain original content",
            "original dll content", backupFile.readText())

        // Verify DLL was replaced with asset content
        val expectedContent = loadTestAsset(context, "steampipe/steam_api.dll")
        assertEquals("DLL should contain asset content",
            expectedContent, dllFile.readText())
    }

    @Test
    fun replaceSteamApi_respectsMaxDepth() = runBlocking {
        // Ensure no marker exists
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)

        // Create directory structure deeper than max depth (5)
        var currentDir = appDir
        for (i in 1..7) {
            currentDir = File(currentDir, "level$i")
            currentDir.mkdirs()
        }

        // Create DLL beyond max depth
        val deepDllFile = File(currentDir, "steam_api.dll")
        deepDllFile.writeBytes("original dll content".toByteArray())

        // Call the actual function
        SteamUtils.replaceSteamApi(context, testAppId)

        // Verify DLL beyond max depth was NOT processed (no backup created)
        val backupFile = File(currentDir, "steam_api.dll.orig")
        assertFalse("Should NOT create backup for DLL beyond max depth", backupFile.exists())
    }

    @Test
    fun replaceSteamApi_handlesBoth32And64BitDlls() = runBlocking {
        // Ensure no marker exists
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)

        // Create both DLL files
        val dll32File = File(appDir, "steam_api.dll")
        val dll64File = File(appDir, "steam_api64.dll")
        dll32File.writeBytes("original 32bit dll".toByteArray())
        dll64File.writeBytes("original 64bit dll".toByteArray())

        // Call the actual function
        SteamUtils.replaceSteamApi(context, testAppId)

        // Verify both backups were created
        val backup32File = File(appDir, "steam_api.dll.orig")
        val backup64File = File(appDir, "steam_api64.dll.orig")
        assertTrue("Should create backup for 32-bit DLL", backup32File.exists())
        assertTrue("Should create backup for 64-bit DLL", backup64File.exists())
        assertTrue("original 32bit dll" == backup32File.readText())
        assertTrue("original 64bit dll" == backup64File.readText())

        // Verify both DLLs were replaced with asset content
        val expected32Content = loadTestAsset(context, "steampipe/steam_api.dll")
        val expected64Content = loadTestAsset(context, "steampipe/steam_api64.dll")
        assertEquals("32-bit DLL should contain asset content",
            expected32Content, dll32File.readText())
        assertEquals("64-bit DLL should contain asset content",
            expected64Content, dll64File.readText())
    }

    @Test
    fun replaceSteamApi_handlesCaseInsensitiveMatching() = runBlocking {
        // Ensure no marker exists
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)

        // Create DLL with different case
        val dllFile = File(appDir, "STEAM_API.DLL")
        dllFile.writeBytes("original dll content".toByteArray())

        // Call the actual function
        SteamUtils.replaceSteamApi(context, testAppId)

        // Verify backup was created (case-insensitive matching)
        val backupFile = File(appDir, "STEAM_API.DLL.orig")
        assertTrue("Should create backup with case-insensitive matching", backupFile.exists())
        assertEquals("Backup should contain original content",
            "original dll content", backupFile.readText())

        // Verify DLL was replaced with asset content
        val expectedContent = loadTestAsset(context, "steampipe/steam_api.dll")
        assertEquals("DLL should contain asset content",
            expectedContent, dllFile.readText())
    }

    @Test
    fun replaceSteamApi_then_restoreSteamApi_restoresOriginalDlls() = runBlocking {
        // Ensure no markers exist
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED)

        // Create original DLL files with known content
        val original32Content = "original 32-bit steam_api.dll content"
        val original64Content = "original 64-bit steam_api64.dll content"
        val dll32File = File(appDir, "steam_api.dll")
        val dll64File = File(appDir, "steam_api64.dll")
        dll32File.writeBytes(original32Content.toByteArray())
        dll64File.writeBytes(original64Content.toByteArray())

        // Step 1: Call replaceSteamApi()
        SteamUtils.replaceSteamApi(context, testAppId)

        // Verify DLLs were replaced with asset content
        val expected32Content = loadTestAsset(context, "steampipe/steam_api.dll")
        val expected64Content = loadTestAsset(context, "steampipe/steam_api64.dll")
        assertEquals("32-bit DLL should contain asset content after replace",
            expected32Content, dll32File.readText())
        assertEquals("64-bit DLL should contain asset content after replace",
            expected64Content, dll64File.readText())

        // Verify backups were created with original content
        val backup32File = File(appDir, "steam_api.dll.orig")
        val backup64File = File(appDir, "steam_api64.dll.orig")
        assertTrue("Should create backup for 32-bit DLL", backup32File.exists())
        assertTrue("Should create backup for 64-bit DLL", backup64File.exists())
        assertEquals("32-bit backup should contain original content",
            original32Content, backup32File.readText())
        assertEquals("64-bit backup should contain original content",
            original64Content, backup64File.readText())

        // Verify marker was added
        assertTrue("Should add STEAM_DLL_REPLACED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED))
        assertFalse("Should NOT have STEAM_DLL_RESTORED marker yet",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED))

        // Step 2: Call restoreSteamApi()
        SteamUtils.restoreSteamApi(context, testAppId)

        // Verify original DLLs were restored from backups
        assertEquals("32-bit DLL should be restored to original content",
            original32Content, dll32File.readText())
        assertEquals("64-bit DLL should be restored to original content",
            original64Content, dll64File.readText())

        // Verify markers were updated
        assertFalse("Should remove STEAM_DLL_REPLACED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED))
        assertTrue("Should add STEAM_DLL_RESTORED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED))
    }
}
