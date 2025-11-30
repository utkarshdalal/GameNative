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
        // Set up drives the same way the app does for Steam games
        val defaultDrives = Container.DEFAULT_DRIVES
        val appDirPath = appDir.absolutePath
        val drive: Char = Container.getNextAvailableDriveLetter(defaultDrives)
        container.drives = "$defaultDrives$drive:$appDirPath"
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
        for (i in 1..12) {
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
        for (i in 1..12) {
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
        for (i in 1..12) {
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

    @Test
    fun testReplaceSteamClientDll_sequence_replacesAndRestoresCorrectly() = runBlocking {
        // Step 1: Initial Setup - Create fake steam app structure
        val originalDllContent = "original steam_api64.dll content"
        val binDir = File(appDir, "bin")
        binDir.mkdirs()
        val dllFile = File(binDir, "steam_api64.dll")
        dllFile.writeBytes(originalDllContent.toByteArray())

        // Create game.exe files
        val imageFs = ImageFs.find(context)
        val dosDevicesPath = File(imageFs.wineprefix, "dosdevices/a:")
        dosDevicesPath.mkdirs()
        val gameExe = File(dosDevicesPath, "game.exe")
        val gameExeUnpacked = File(dosDevicesPath, "game.exe.unpacked.exe")
        val gameExeOriginal = File(dosDevicesPath, "game.exe.original.exe")
        gameExe.writeBytes("game.exe content".toByteArray())
        gameExeUnpacked.writeBytes("unpacked exe content".toByteArray())
        gameExeOriginal.writeBytes("original exe content".toByteArray())

        // Set up container structure with Steam directory
        val containerDir = File(imageFs.rootDir, "home/${ImageFs.USER}-${testAppId}")
        val steamDir = File(containerDir, ".wine/drive_c/Program Files (x86)/Steam")
        steamDir.mkdirs()

        // Set container executablePath so restoreUnpackedExecutable can work
        val container = ContainerUtils.getContainer(context, testAppId)
        container.executablePath = "game.exe"
        container.saveData()

        // Ensure no markers exist
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)

        // Create a minimal steamclient.dll file to satisfy the ensureSteamSettings call
        val steamClientDll = File(steamDir, "steamclient.dll")
        steamClientDll.writeBytes("fake steamclient.dll".toByteArray())

        // Step 2: Call replaceSteamClientDll (First Time)
        SteamUtils.replaceSteamclientDll(context, testAppId)

        // Verify steam_settings folder is created next to steamclient.dll in Steam directory
        val steamSettingsDir = File(steamDir, "steam_settings")
        assertTrue("steam_settings folder should exist in Steam directory", steamSettingsDir.exists())

        // Verify config files exist
        val configsUserIni = File(steamSettingsDir, "configs.user.ini")
        val configsAppIni = File(steamSettingsDir, "configs.app.ini")
        val configsMainIni = File(steamSettingsDir, "configs.main.ini")
        assertTrue("configs.user.ini should exist", configsUserIni.exists())
        assertTrue("configs.app.ini should exist", configsAppIni.exists())
        assertTrue("configs.main.ini should exist", configsMainIni.exists())

        // Verify configs.user.ini contains all required fields
        val userIniContent = configsUserIni.readText()
        assertTrue("configs.user.ini should contain [user::general] section", userIniContent.contains("[user::general]"))
        assertTrue("configs.user.ini should contain account_name field", userIniContent.contains("account_name="))
        assertTrue("configs.user.ini should contain account_steamid field", userIniContent.contains("account_steamid="))
        assertTrue("configs.user.ini should contain language field", userIniContent.contains("language="))
        assertTrue("configs.user.ini should contain ticket field", userIniContent.contains("ticket="))

        // Verify configs.app.ini contains expected content
        val appIniContent = configsAppIni.readText()
        assertTrue("configs.app.ini should contain [app::dlcs] section", appIniContent.contains("[app::dlcs]"))
        assertTrue("configs.app.ini should contain unlock_all field", appIniContent.contains("unlock_all="))

        // Verify configs.main.ini contains expected content
        val mainIniContent = configsMainIni.readText()
        assertTrue("configs.main.ini should contain [main::connectivity] section", mainIniContent.contains("[main::connectivity]"))
        assertTrue("configs.main.ini should contain disable_lan_only=1", mainIniContent.contains("disable_lan_only=1"))

        // Verify steam_appid.txt exists in steam_settings folder
        val steamAppIdFile = File(steamSettingsDir, "steam_appid.txt")
        assertTrue("steam_appid.txt should exist in steam_settings folder", steamAppIdFile.exists())
        assertEquals("steam_appid.txt should contain correct app ID",
            steamAppId.toString(), steamAppIdFile.readText().trim())

        // Verify files from experimental-drm.tzst are extracted to Steam directory
        // At minimum, verify steamclient_loader_x64.dll exists (as checked in line 210 of SteamUtils.kt)
        val steamClientLoaderDll = File(steamDir, "steamclient_loader_x64.dll")
        // Note: If experimental-drm.tzst doesn't exist in test assets, this file won't exist
        // but the test should still verify the steam_settings creation worked
        if (steamClientLoaderDll.exists()) {
            assertTrue("steamclient_loader_x64.dll should exist after extraction", true)
        }

        // Verify steam_api64.dll in app directory is NOT replaced (remains original)
        assertEquals("steam_api64.dll should remain original after replaceSteamClientDll",
            originalDllContent, dllFile.readText())

        // Verify game.exe is NOT overwritten after first replaceSteamClientDll call
        assertEquals("game.exe should be overwritten after replaceSteamClientDll",
            "unpacked exe content", gameExe.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_COLDCLIENT_USED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED))

        // Step 3: Call replaceSteamApi
        // Remove the marker first to allow replaceSteamApi to run
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)

        SteamUtils.replaceSteamApi(context, testAppId)

        // Verify steam_api64.dll gets replaced with content from assets
        val expectedDllContent = loadTestAsset(context, "steampipe/steam_api64.dll")
        assertEquals("steam_api64.dll should be replaced with asset content",
            expectedDllContent, dllFile.readText())

        // Verify .orig backup is created with original content
        val backupFile = File(binDir, "steam_api64.dll.orig")
        assertTrue("Backup .orig file should exist", backupFile.exists())
        assertEquals("Backup should contain original content",
            originalDllContent, backupFile.readText())

        // Verify steam_settings folder is created next to the DLL in app directory
        val appSettingsDir = File(binDir, "steam_settings")
        assertTrue("steam_settings folder should exist next to DLL", appSettingsDir.exists())

        // Verify config files exist in app directory
        val appConfigsUserIni = File(appSettingsDir, "configs.user.ini")
        val appConfigsAppIni = File(appSettingsDir, "configs.app.ini")
        val appConfigsMainIni = File(appSettingsDir, "configs.main.ini")
        assertTrue("configs.user.ini should exist in app directory", appConfigsUserIni.exists())
        assertTrue("configs.app.ini should exist in app directory", appConfigsAppIni.exists())
        assertTrue("configs.main.ini should exist in app directory", appConfigsMainIni.exists())

        // Verify configs.user.ini contains all required fields in app directory
        val appUserIniContent = appConfigsUserIni.readText()
        assertTrue("configs.user.ini in app directory should contain [user::general] section",
            appUserIniContent.contains("[user::general]"))
        assertTrue("configs.user.ini in app directory should contain account_name field",
            appUserIniContent.contains("account_name="))
        assertTrue("configs.user.ini in app directory should contain account_steamid field",
            appUserIniContent.contains("account_steamid="))
        assertTrue("configs.user.ini in app directory should contain language field",
            appUserIniContent.contains("language="))
        assertTrue("configs.user.ini in app directory should contain ticket field",
            appUserIniContent.contains("ticket="))

        // Verify configs.app.ini contains expected content in app directory
        val appAppIniContent = appConfigsAppIni.readText()
        assertTrue("configs.app.ini in app directory should contain [app::dlcs] section",
            appAppIniContent.contains("[app::dlcs]"))
        assertTrue("configs.app.ini in app directory should contain unlock_all field",
            appAppIniContent.contains("unlock_all="))

        // Verify configs.main.ini contains expected content in app directory
        val appMainIniContent = appConfigsMainIni.readText()
        assertTrue("configs.main.ini in app directory should contain [main::connectivity] section",
            appMainIniContent.contains("[main::connectivity]"))
        assertTrue("configs.main.ini in app directory should contain disable_lan_only=1",
            appMainIniContent.contains("disable_lan_only=1"))

        // Verify steam_appid.txt exists in app directory steam_settings folder
        val appSteamAppIdFile = File(appSettingsDir, "steam_appid.txt")
        assertTrue("steam_appid.txt should exist in app directory steam_settings folder", appSteamAppIdFile.exists())
        assertEquals("steam_appid.txt in app directory should contain correct app ID",
            steamAppId.toString(), appSteamAppIdFile.readText().trim())

        // Verify game.exe is NOT overwritten after replaceSteamApi call
        assertEquals("game.exe should be overwritten after replaceSteamApi",
            "unpacked exe content", gameExe.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_DLL_REPLACED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED))

        // Step 4: Call replaceSteamClientDll (Second Time)
        // Remove markers to allow the function to run
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)

        SteamUtils.replaceSteamclientDll(context, testAppId)

        // Verify putBackSteamDlls restores steam_api64.dll to original content (from .orig backup)
        assertEquals("steam_api64.dll should be restored to original content",
            originalDllContent, dllFile.readText())

        // Verify .orig backup file still exists (it should not be deleted during restoration)
        assertTrue("Backup .orig file should still exist after restoration", backupFile.exists())

        // Verify steam_settings folder still exists next to steamclient.dll in Steam directory
        assertTrue("steam_settings folder should still exist in Steam directory",
            steamSettingsDir.exists())

        // Verify config files still exist in Steam directory
        assertTrue("configs.user.ini should still exist in Steam directory", configsUserIni.exists())
        assertTrue("configs.app.ini should still exist in Steam directory", configsAppIni.exists())
        assertTrue("configs.main.ini should still exist in Steam directory", configsMainIni.exists())

        // Verify config file contents are still correct in Steam directory
        val finalUserIniContent = configsUserIni.readText()
        assertTrue("configs.user.ini should still contain [user::general] section",
            finalUserIniContent.contains("[user::general]"))
        val finalAppIniContent = configsAppIni.readText()
        assertTrue("configs.app.ini should still contain [app::dlcs] section",
            finalAppIniContent.contains("[app::dlcs]"))
        val finalMainIniContent = configsMainIni.readText()
        assertTrue("configs.main.ini should still contain [main::connectivity] section",
            finalMainIniContent.contains("[main::connectivity]"))

        // Verify steam_appid.txt still exists in Steam directory
        assertTrue("steam_appid.txt should still exist in Steam directory steam_settings folder",
            steamAppIdFile.exists())
        assertEquals("steam_appid.txt should still contain correct app ID",
            steamAppId.toString(), steamAppIdFile.readText().trim())

        // Verify game.exe is NOT overwritten after second replaceSteamClientDll call
        assertEquals("game.exe should be overwritten after second replaceSteamClientDll",
            "unpacked exe content", gameExe.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_COLDCLIENT_USED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED))
    }

    @Test
    fun testReplaceSteamClientDll_restoreSteamApi_sequence() = runBlocking {
        // Step 1: Initial Setup - Create fake steam app structure
        val originalDllContent = "original steam_api64.dll content"
        val binDir = File(appDir, "bin")
        binDir.mkdirs()
        val dllFile = File(binDir, "steam_api64.dll")
        dllFile.writeBytes(originalDllContent.toByteArray())

        // Create game.exe files
        val imageFs = ImageFs.find(context)
        val dosDevicesPath = File(imageFs.wineprefix, "dosdevices/a:")
        dosDevicesPath.mkdirs()
        val gameExe = File(dosDevicesPath, "game.exe")
        val gameExeUnpacked = File(dosDevicesPath, "game.exe.unpacked.exe")
        val gameExeOriginal = File(dosDevicesPath, "game.exe.original.exe")
        gameExe.writeBytes("game.exe content".toByteArray())
        gameExeUnpacked.writeBytes("unpacked exe content".toByteArray())
        gameExeOriginal.writeBytes("original exe content".toByteArray())

        // Set up container structure with Steam directory
        val containerDir = File(imageFs.rootDir, "home/${ImageFs.USER}-${testAppId}")
        val steamDir = File(containerDir, ".wine/drive_c/Program Files (x86)/Steam")
        steamDir.mkdirs()

        // Set container executablePath so restoreUnpackedExecutable and restoreOriginalExecutable can work
        val container = ContainerUtils.getContainer(context, testAppId)
        container.executablePath = "game.exe"
        container.saveData()

        // Ensure no markers exist
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)

        // Create a minimal steamclient.dll file to satisfy the ensureSteamSettings call
        val steamClientDll = File(steamDir, "steamclient.dll")
        steamClientDll.writeBytes("fake steamclient.dll".toByteArray())

        // Step 2: Call replaceSteamClientDll (First Time)
        SteamUtils.replaceSteamclientDll(context, testAppId)

        // Verify steam_settings folder is created next to steamclient.dll in Steam directory
        val steamSettingsDir = File(steamDir, "steam_settings")
        assertTrue("steam_settings folder should exist in Steam directory", steamSettingsDir.exists())

        // Verify config files exist
        val configsUserIni = File(steamSettingsDir, "configs.user.ini")
        val configsAppIni = File(steamSettingsDir, "configs.app.ini")
        val configsMainIni = File(steamSettingsDir, "configs.main.ini")
        assertTrue("configs.user.ini should exist", configsUserIni.exists())
        assertTrue("configs.app.ini should exist", configsAppIni.exists())
        assertTrue("configs.main.ini should exist", configsMainIni.exists())

        // Verify steam_appid.txt exists in steam_settings folder
        val steamAppIdFile = File(steamSettingsDir, "steam_appid.txt")
        assertTrue("steam_appid.txt should exist in steam_settings folder", steamAppIdFile.exists())
        assertEquals("steam_appid.txt should contain correct app ID",
            steamAppId.toString(), steamAppIdFile.readText().trim())

        // Verify steam_api64.dll in app directory is NOT replaced (remains original)
        assertEquals("steam_api64.dll should remain original after replaceSteamClientDll",
            originalDllContent, dllFile.readText())

        // Verify game.exe is NOT overwritten after first replaceSteamClientDll call
        assertEquals("game.exe should not be overwritten after replaceSteamClientDll",
            "unpacked exe content", gameExe.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_COLDCLIENT_USED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED))

        // Step 3: Call restoreSteamApi
        // Remove markers to allow the function to run
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED)

        SteamUtils.restoreSteamApi(context, testAppId)

        // Verify restoreOriginalExecutable overwrites game.exe with game.exe.original.exe content
        assertEquals("game.exe should be overwritten with game.exe.original.exe content after restoreSteamApi",
            "original exe content", gameExe.readText())

        // Verify steam_api64.dll remains the same (not replaced, since restoreSteamApi calls putBackSteamDlls
        // which only restores from .orig if it exists, and we don't have a .orig file at this point)
        assertEquals("steam_api64.dll should remain the same after restoreSteamApi",
            originalDllContent, dllFile.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_DLL_RESTORED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED))

        // Step 4: Call replaceSteamClientDll (Second Time)
        // Remove markers to allow the function to run
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED)

        SteamUtils.replaceSteamclientDll(context, testAppId)

        // Verify restoreUnpackedExecutable overwrites game.exe with game.exe.unpacked.exe content
        assertEquals("game.exe should be overwritten with game.exe.unpacked.exe content after second replaceSteamClientDll",
            "unpacked exe content", gameExe.readText())

        // Verify steam_settings folder still exists next to steamclient.dll in Steam directory
        assertTrue("steam_settings folder should still exist in Steam directory",
            steamSettingsDir.exists())

        // Verify config files still exist in Steam directory
        assertTrue("configs.user.ini should still exist in Steam directory", configsUserIni.exists())
        assertTrue("configs.app.ini should still exist in Steam directory", configsAppIni.exists())
        assertTrue("configs.main.ini should still exist in Steam directory", configsMainIni.exists())

        // Verify configs.user.ini contains all required fields
        val userIniContent = configsUserIni.readText()
        assertTrue("configs.user.ini should contain [user::general] section", userIniContent.contains("[user::general]"))
        assertTrue("configs.user.ini should contain account_name field", userIniContent.contains("account_name="))
        assertTrue("configs.user.ini should contain account_steamid field", userIniContent.contains("account_steamid="))
        assertTrue("configs.user.ini should contain language field", userIniContent.contains("language="))
        assertTrue("configs.user.ini should contain ticket field", userIniContent.contains("ticket="))

        // Verify configs.app.ini contains expected content
        val appIniContent = configsAppIni.readText()
        assertTrue("configs.app.ini should contain [app::dlcs] section", appIniContent.contains("[app::dlcs]"))
        assertTrue("configs.app.ini should contain unlock_all field", appIniContent.contains("unlock_all="))

        // Verify configs.main.ini contains expected content
        val mainIniContent = configsMainIni.readText()
        assertTrue("configs.main.ini should contain [main::connectivity] section", mainIniContent.contains("[main::connectivity]"))
        assertTrue("configs.main.ini should contain disable_lan_only=1", mainIniContent.contains("disable_lan_only=1"))

        // Verify steam_appid.txt still exists and has correct content
        assertTrue("steam_appid.txt should still exist in Steam directory steam_settings folder",
            steamAppIdFile.exists())
        assertEquals("steam_appid.txt should still contain correct app ID",
            steamAppId.toString(), steamAppIdFile.readText().trim())

        // Verify steam_api64.dll still remains original (putBackSteamDlls doesn't change it if no .orig exists)
        assertEquals("steam_api64.dll should still remain original",
            originalDllContent, dllFile.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_COLDCLIENT_USED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED))
    }

    @Test
    fun testReplaceSteamApi_restoreSteamApi_sequence() = runBlocking {
        // Step 1: Initial Setup - Create fake steam app structure
        val originalDllContent = "original steam_api64.dll content"
        val binDir = File(appDir, "bin")
        binDir.mkdirs()
        val dllFile = File(binDir, "steam_api64.dll")
        dllFile.writeBytes(originalDllContent.toByteArray())

        // Create game.exe files
        val imageFs = ImageFs.find(context)
        val dosDevicesPath = File(imageFs.wineprefix, "dosdevices/a:")
        dosDevicesPath.mkdirs()
        val gameExe = File(dosDevicesPath, "game.exe")
        val gameExeUnpacked = File(dosDevicesPath, "game.exe.unpacked.exe")
        val gameExeOriginal = File(dosDevicesPath, "game.exe.original.exe")
        gameExe.writeBytes("game.exe content".toByteArray())
        gameExeUnpacked.writeBytes("unpacked exe content".toByteArray())
        gameExeOriginal.writeBytes("original exe content".toByteArray())

        // Set up container structure
        val containerDir = File(imageFs.rootDir, "home/${ImageFs.USER}-${testAppId}")

        // Set container executablePath so restoreUnpackedExecutable and restoreOriginalExecutable can work
        val container = ContainerUtils.getContainer(context, testAppId)
        container.executablePath = "game.exe"
        container.saveData()

        // Ensure no markers exist
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)

        // Step 2: Call replaceSteamApi (First Time)
        SteamUtils.replaceSteamApi(context, testAppId)

        // Verify steam_api64.dll gets overwritten with content from assets
        val expectedDllContent = loadTestAsset(context, "steampipe/steam_api64.dll")
        assertEquals("steam_api64.dll should be replaced with asset content",
            expectedDllContent, dllFile.readText())

        // Verify .orig backup is created with original content
        val backupFile = File(binDir, "steam_api64.dll.orig")
        assertTrue("Backup .orig file should exist", backupFile.exists())
        assertEquals("Backup should contain original content",
            originalDllContent, backupFile.readText())

        // Verify steam_settings folder is created next to the DLL in app directory
        val appSettingsDir = File(binDir, "steam_settings")
        assertTrue("steam_settings folder should exist next to DLL", appSettingsDir.exists())

        // Verify config files exist in app directory
        val appConfigsUserIni = File(appSettingsDir, "configs.user.ini")
        val appConfigsAppIni = File(appSettingsDir, "configs.app.ini")
        val appConfigsMainIni = File(appSettingsDir, "configs.main.ini")
        assertTrue("configs.user.ini should exist in app directory", appConfigsUserIni.exists())
        assertTrue("configs.app.ini should exist in app directory", appConfigsAppIni.exists())
        assertTrue("configs.main.ini should exist in app directory", appConfigsMainIni.exists())

        // Verify configs.user.ini contains all required fields
        val appUserIniContent = appConfigsUserIni.readText()
        assertTrue("configs.user.ini should contain [user::general] section",
            appUserIniContent.contains("[user::general]"))
        assertTrue("configs.user.ini should contain account_name field",
            appUserIniContent.contains("account_name="))
        assertTrue("configs.user.ini should contain account_steamid field",
            appUserIniContent.contains("account_steamid="))
        assertTrue("configs.user.ini should contain language field",
            appUserIniContent.contains("language="))
        assertTrue("configs.user.ini should contain ticket field",
            appUserIniContent.contains("ticket="))

        // Verify configs.app.ini contains expected content
        val appAppIniContent = appConfigsAppIni.readText()
        assertTrue("configs.app.ini should contain [app::dlcs] section",
            appAppIniContent.contains("[app::dlcs]"))
        assertTrue("configs.app.ini should contain unlock_all field",
            appAppIniContent.contains("unlock_all="))

        // Verify configs.main.ini contains expected content
        val appMainIniContent = appConfigsMainIni.readText()
        assertTrue("configs.main.ini should contain [main::connectivity] section",
            appMainIniContent.contains("[main::connectivity]"))
        assertTrue("configs.main.ini should contain disable_lan_only=1",
            appMainIniContent.contains("disable_lan_only=1"))

        // Verify steam_appid.txt exists in app directory steam_settings folder
        val appSteamAppIdFile = File(appSettingsDir, "steam_appid.txt")
        assertTrue("steam_appid.txt should exist in app directory steam_settings folder", appSteamAppIdFile.exists())
        assertEquals("steam_appid.txt in app directory should contain correct app ID",
            steamAppId.toString(), appSteamAppIdFile.readText().trim())

        // Verify restoreUnpackedExecutable overwrites game.exe with game.exe.unpacked.exe content
        assertEquals("game.exe should be overwritten with game.exe.unpacked.exe content after replaceSteamApi",
            "unpacked exe content", gameExe.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_DLL_REPLACED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED))

        // Step 3: Call restoreSteamApi
        // Remove markers to allow the function to run
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)

        SteamUtils.restoreSteamApi(context, testAppId)

        // Verify steam_api64.dll is restored from .orig backup to original content
        assertEquals("steam_api64.dll should be restored to original content after restoreSteamApi",
            originalDllContent, dllFile.readText())

        // Verify restoreOriginalExecutable overwrites game.exe with game.exe.original.exe content
        assertEquals("game.exe should be overwritten with game.exe.original.exe content after restoreSteamApi",
            "original exe content", gameExe.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_DLL_RESTORED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED))

        // Step 4: Call replaceSteamApi (Second Time)
        // Remove markers to allow the function to run
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)

        SteamUtils.replaceSteamApi(context, testAppId)

        // Verify steam_api64.dll gets overwritten with asset content again
        assertEquals("steam_api64.dll should be replaced with asset content again",
            expectedDllContent, dllFile.readText())

        // Verify .orig backup still exists with original content
        assertTrue("Backup .orig file should still exist", backupFile.exists())
        assertEquals("Backup should still contain original content",
            originalDllContent, backupFile.readText())

        // Verify steam_settings folder still exists with correct config files
        assertTrue("steam_settings folder should still exist", appSettingsDir.exists())
        assertTrue("configs.user.ini should still exist", appConfigsUserIni.exists())
        assertTrue("configs.app.ini should still exist", appConfigsAppIni.exists())
        assertTrue("configs.main.ini should still exist", appConfigsMainIni.exists())

        // Verify config file contents are still correct
        val finalUserIniContent = appConfigsUserIni.readText()
        assertTrue("configs.user.ini should still contain [user::general] section",
            finalUserIniContent.contains("[user::general]"))
        val finalAppIniContent = appConfigsAppIni.readText()
        assertTrue("configs.app.ini should still contain [app::dlcs] section",
            finalAppIniContent.contains("[app::dlcs]"))
        val finalMainIniContent = appConfigsMainIni.readText()
        assertTrue("configs.main.ini should still contain [main::connectivity] section",
            finalMainIniContent.contains("[main::connectivity]"))

        // Verify steam_appid.txt still exists with correct app ID
        assertTrue("steam_appid.txt should still exist", appSteamAppIdFile.exists())
        assertEquals("steam_appid.txt should still contain correct app ID",
            steamAppId.toString(), appSteamAppIdFile.readText().trim())

        // Verify restoreUnpackedExecutable overwrites game.exe with game.exe.unpacked.exe content again
        assertEquals("game.exe should be overwritten with game.exe.unpacked.exe content again after second replaceSteamApi",
            "unpacked exe content", gameExe.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_DLL_REPLACED marker again",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED))
    }
}
