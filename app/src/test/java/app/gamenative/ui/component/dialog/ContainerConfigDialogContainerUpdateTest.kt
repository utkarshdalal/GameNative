package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ConfigInfo
import app.gamenative.data.SteamApp
import app.gamenative.db.PluviaDatabase
import app.gamenative.enums.AppType
import app.gamenative.enums.OS
import app.gamenative.enums.ReleaseState
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.Field
import java.util.EnumSet

@RunWith(RobolectricTestRunner::class)
class ContainerConfigDialogContainerUpdateTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var container: Container

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File.createTempFile("container_test_", null)
        tempDir.delete()
        tempDir.mkdirs()

        // Ensure DownloadService paths point at a writable temp directory so that
        // SteamService companion logic (e.g. getAppDirPath) returns valid paths.
        DownloadService.populateDownloadService(context)
        File(SteamService.internalAppInstallPath).mkdirs()
        SteamService.externalAppInstallPath.takeIf { it.isNotBlank() }?.let { File(it).mkdirs() }

        // Create app directory that SteamService.getAppDirPath will return
        val appDir = File(SteamService.internalAppInstallPath, "123456")
        appDir.mkdirs()

        // Set up in-memory database with SteamApp entry
        val db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Insert test SteamApp so getAppDirPath() can find it
        val testApp = SteamApp(
            id = 123456,
            name = "Test Game",
            config = ConfigInfo(installDir = "123456"),
            type = AppType.game,
            osList = EnumSet.of(OS.windows),
            releaseState = ReleaseState.released,
        )
        runBlocking {
            db.steamAppDao().insert(testApp)
        }

        // Create a mock SteamService instance and set it as SteamService.instance
        val mockSteamService = mock<SteamService>()
        whenever(mockSteamService.appDao).thenReturn(db.steamAppDao())

        // Mock steamClient and steamID for userSteamId property
        val mockSteamClient = mock<`in`.dragonbra.javasteam.steam.steamclient.SteamClient>()
        val mockSteamID = mock<`in`.dragonbra.javasteam.types.SteamID>()
        whenever(mockSteamService.steamClient).thenReturn(mockSteamClient)
        whenever(mockSteamClient.steamID).thenReturn(mockSteamID)

        // Set the mock as SteamService.instance using reflection
        val instanceField = SteamService::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, mockSteamService)

        container = Container("STEAM_123456")
        container.setRootDir(tempDir)

        // Create .wine directory structure for registry file
        val wineDir = File(tempDir, ".wine").apply { mkdirs() }
        File(wineDir, "user.reg").apply {
            if (!exists()) {
                writeText("REGEDIT4\n")
            }
        }
    }

    @Test
    fun bionicScenario_updatesAllContainerProperties() {
        // Create Bionic scenario test data
        val envVars = com.winlator.core.envvars.EnvVars().apply {
            put("DXVK_ASYNC", "1")
            put("VKD3D_FEATURE_LEVEL", "12_1")
            put("WINEDLLOVERRIDES", "d3d11=n,b")
        }.toString()

        val graphicsConfig = com.winlator.core.KeyValueSet()
            .put("version", "turnip25.3.0_R3_Auto")
            .put("presentMode", "immediate")
            .put("resourceType", "dmabuf")
            .put("bcnEmulation", "full")
            .put("bcnEmulationType", "software")
            .put("bcnEmulationCache", "1")
            .put("disablePresentWait", "1")
            .put("syncFrame", "1")
            .put("maxDeviceMemory", "2048")
            .put("adrenotoolsTurnip", "0")
            .put("exposedDeviceExtensions", "VK_KHR_swapchain|VK_KHR_timeline_semaphore")
            .put("blacklistedExtensions", "VK_KHR_maintenance1")
            .toString()

        val dxWrapperConfig = com.winlator.core.KeyValueSet()
            .put("version", "2.14.1")
            .put("async", "1")
            .put("asyncCache", "1")
            .put("vkd3dVersion", "2.14.1")
            .put("vkd3dFeatureLevel", "12_0")
            .toString()

        val winComponents = "direct3d=0,directsound=0,directmusic=1,directshow=1,directplay=1,vcrun2010=0,wmdecoder=0,opengl=1"

        val mutated = ContainerData(
            name = "Test Bionic Container",
            executablePath = "Games\\\\Sample\\\\Run.exe",
            execArgs = "--fullscreen --skip-intro",
            language = "german",
            screenSize = "1920x1080",
            envVars = envVars,
            audioDriver = "alsa",
            showFPS = true,
            forceDlc = true,
            useLegacyDRM = true,
            launchRealSteam = true,
            allowSteamUpdates = true,
            steamType = Container.STEAM_TYPE_LIGHT,
            wincomponents = winComponents,
            graphicsDriverConfig = graphicsConfig,
            dxwrapperConfig = dxWrapperConfig,
            sharpnessEffect = "CAS",
            sharpnessLevel = 64,
            sharpnessDenoise = 12,
            videoPciDeviceID = 1728,
            offScreenRenderingMode = "backbuffer",
            videoMemorySize = "4096",
            mouseWarpOverride = "force",
            csmt = false,
            strictShaderMath = false,
            useDRI3 = false,
            emulator = "box64",
            sdlControllerAPI = false,
            enableXInput = false,
            enableDInput = false,
            dinputMapperType = 2,
            disableMouseInput = true,
            touchscreenMode = true,
            startupSelection = Container.STARTUP_SELECTION_AGGRESSIVE,
            cpuList = "0,2,4,6",
            cpuListWoW64 = "1,3,5,7",
            containerVariant = Container.BIONIC,
            wineVersion = "proton-9.0-arm64ec",
            graphicsDriver = "wrapper-v2",
            dxwrapper = "vkd3d",
            box64Version = "0.3.8",
            box64Preset = com.winlator.box86_64.Box86_64Preset.PERFORMANCE,
            fexcoreVersion = "2511",
        )

        ContainerUtils.applyToContainer(context, container, mutated, saveToDisk = false)

        // Basic properties
        assertEquals(mutated.name, container.name)
        assertEquals(mutated.screenSize, container.screenSize)
        assertEquals(mutated.execArgs, container.execArgs)
        assertEquals(mutated.executablePath, container.executablePath)
        assertEquals(mutated.envVars, container.envVars)
        assertEquals(mutated.audioDriver, container.audioDriver)

        // Graphics driver
        assertEquals(mutated.graphicsDriver, container.graphicsDriver)
        assertEquals(mutated.graphicsDriverConfig, container.graphicsDriverConfig)

        // DX Wrapper
        assertEquals(mutated.dxwrapper, container.dxWrapper)
        assertEquals(mutated.dxwrapperConfig, container.dxWrapperConfig)

        // Boolean flags
        assertEquals(mutated.showFPS, container.isShowFPS)
        assertEquals(mutated.launchRealSteam, container.isLaunchRealSteam)
        assertEquals(mutated.allowSteamUpdates, container.isAllowSteamUpdates)
        assertEquals(mutated.forceDlc, container.isForceDlc)
        assertEquals(mutated.useLegacyDRM, container.isUseLegacyDRM)
        assertEquals(mutated.sdlControllerAPI, container.isSdlControllerAPI)
        assertEquals(mutated.disableMouseInput, container.isDisableMouseInput)
        assertEquals(mutated.touchscreenMode, container.isTouchscreenMode)

        // Steam type
        assertEquals(mutated.steamType, container.getSteamType())

        // Win components
        assertEquals(mutated.wincomponents, container.winComponents)

        // CPU lists
        assertEquals(mutated.cpuList, container.cpuList)
        assertEquals(mutated.cpuListWoW64, container.cpuListWoW64)
        assertEquals(mutated.wow64Mode, container.isWoW64Mode)

        // Startup selection
        assertEquals(mutated.startupSelection, container.startupSelection)

        // Box64
        assertEquals(mutated.box64Version, container.box64Version)
        assertEquals(mutated.box64Preset, container.box64Preset)

        // Bionic-specific
        assertEquals(mutated.containerVariant, container.containerVariant)
        assertEquals(mutated.wineVersion, container.wineVersion)
        assertEquals(mutated.emulator, container.emulator)
        assertEquals(mutated.fexcoreVersion, container.fexCoreVersion)

        // Sharpness (stored in extra)
        assertEquals(mutated.sharpnessEffect, container.getExtra("sharpnessEffect"))
        assertEquals(mutated.sharpnessLevel.toString(), container.getExtra("sharpnessLevel"))
        assertEquals(mutated.sharpnessDenoise.toString(), container.getExtra("sharpnessDenoise"))

        // Language (may be stored in extra if setter fails)
        val actualLanguage = try {
            container.language
        } catch (e: Exception) {
            container.getExtra("language", "english")
        }
        assertEquals(mutated.language, actualLanguage)
    }

    @Test
    fun glibcScenario_updatesAllContainerProperties() {
        // Create Glibc scenario test data
        val envVars = com.winlator.core.envvars.EnvVars().apply {
            put("DXVK_ASYNC", "1")
            put("VKD3D_FEATURE_LEVEL", "12_1")
            put("WINEDLLOVERRIDES", "d3d11=n,b")
        }.toString()

        val graphicsConfig = com.winlator.core.KeyValueSet()
            .put("vkMaxVersion", "1.2")
            .put("imageCacheSize", "512")
            .put("maxDeviceMemory", "4096")
            .put("exposedDeviceExtensions", "VK_KHR_swapchain|VK_KHR_timeline_semaphore")
            .put("blacklistedExtensions", "VK_EXT_sample_locations")
            .toString()

        val dxWrapperConfig = com.winlator.core.KeyValueSet()
            .put("version", "2.4.1-gplasync")
            .put("async", "1")
            .put("asyncCache", "1")
            .put("vkd3dVersion", "2.14.1")
            .put("vkd3dFeatureLevel", "12_1")
            .toString()

        val winComponents = "direct3d=0,directsound=0,directmusic=1,directshow=1,directplay=1,vcrun2010=0,wmdecoder=0,opengl=1"

        val mutated = ContainerData(
            name = "Test Glibc Container",
            executablePath = "Games\\\\Sample\\\\Run.exe",
            execArgs = "--fullscreen --skip-intro",
            language = "german",
            screenSize = "1920x1080",
            envVars = envVars,
            audioDriver = "alsa",
            showFPS = true,
            forceDlc = true,
            useLegacyDRM = true,
            launchRealSteam = true,
            allowSteamUpdates = true,
            steamType = Container.STEAM_TYPE_LIGHT,
            wincomponents = winComponents,
            graphicsDriverConfig = graphicsConfig,
            dxwrapperConfig = dxWrapperConfig,
            sharpnessEffect = "DLS",
            sharpnessLevel = 40,
            sharpnessDenoise = 22,
            videoPciDeviceID = 1728,
            offScreenRenderingMode = "backbuffer",
            videoMemorySize = "4096",
            mouseWarpOverride = "force",
            csmt = false,
            strictShaderMath = false,
            useDRI3 = false,
            emulator = "box64",
            sdlControllerAPI = false,
            enableXInput = false,
            enableDInput = false,
            dinputMapperType = 2,
            disableMouseInput = true,
            touchscreenMode = true,
            startupSelection = Container.STARTUP_SELECTION_AGGRESSIVE,
            cpuList = "0,2,4,6",
            cpuListWoW64 = "1,3,5,7",
            containerVariant = Container.GLIBC,
            wineVersion = "wine-9.2-x86_64",
            graphicsDriver = "sd-8-elite",
            graphicsDriverVersion = "2-842.6",
            dxwrapper = "vkd3d",
            box64Version = "0.3.4",
            box64Preset = com.winlator.box86_64.Box86_64Preset.STABILITY,
        )

        ContainerUtils.applyToContainer(context, container, mutated, saveToDisk = false)

        // Basic properties
        assertEquals(mutated.name, container.name)
        assertEquals(mutated.screenSize, container.screenSize)
        assertEquals(mutated.execArgs, container.execArgs)
        assertEquals(mutated.executablePath, container.executablePath)
        assertEquals(mutated.envVars, container.envVars)
        assertEquals(mutated.audioDriver, container.audioDriver)

        // Graphics driver
        assertEquals(mutated.graphicsDriver, container.graphicsDriver)
        assertEquals(mutated.graphicsDriverVersion, container.graphicsDriverVersion)
        assertEquals(mutated.graphicsDriverConfig, container.graphicsDriverConfig)

        // DX Wrapper
        assertEquals(mutated.dxwrapper, container.dxWrapper)
        assertEquals(mutated.dxwrapperConfig, container.dxWrapperConfig)

        // Boolean flags
        assertEquals(mutated.showFPS, container.isShowFPS)
        assertEquals(mutated.launchRealSteam, container.isLaunchRealSteam)
        assertEquals(mutated.allowSteamUpdates, container.isAllowSteamUpdates)
        assertEquals(mutated.forceDlc, container.isForceDlc)
        assertEquals(mutated.useLegacyDRM, container.isUseLegacyDRM)
        assertEquals(mutated.sdlControllerAPI, container.isSdlControllerAPI)
        assertEquals(mutated.disableMouseInput, container.isDisableMouseInput)
        assertEquals(mutated.touchscreenMode, container.isTouchscreenMode)

        // Steam type
        assertEquals(mutated.steamType, container.getSteamType())

        // Win components
        assertEquals(mutated.wincomponents, container.winComponents)

        // CPU lists
        assertEquals(mutated.cpuList, container.cpuList)
        assertEquals(mutated.cpuListWoW64, container.cpuListWoW64)
        assertEquals(mutated.wow64Mode, container.isWoW64Mode)

        // Startup selection
        assertEquals(mutated.startupSelection, container.startupSelection)

        // Box64
        assertEquals(mutated.box64Version, container.box64Version)
        assertEquals(mutated.box64Preset, container.box64Preset)

        // Glibc-specific
        assertEquals(mutated.containerVariant, container.containerVariant)
        assertEquals(mutated.wineVersion, container.wineVersion)
        assertEquals(mutated.emulator, container.emulator)

        // Sharpness (stored in extra)
        assertEquals(mutated.sharpnessEffect, container.getExtra("sharpnessEffect"))
        assertEquals(mutated.sharpnessLevel.toString(), container.getExtra("sharpnessLevel"))
        assertEquals(mutated.sharpnessDenoise.toString(), container.getExtra("sharpnessDenoise"))

        // Language (may be stored in extra if setter fails)
        val actualLanguage = try {
            container.language
        } catch (e: Exception) {
            container.getExtra("language", "english")
        }
        assertEquals(mutated.language, actualLanguage)
    }

    @Test
    fun basicPropertyUpdates_areAppliedCorrectly() {
        val containerData = ContainerData(
            name = "Test Container",
            screenSize = "1920x1080",
            execArgs = "--fullscreen --no-splash",
            executablePath = "Games\\Test\\game.exe",
            envVars = "TEST_VAR=value",
            audioDriver = "alsa"
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals("Test Container", container.name)
        assertEquals("1920x1080", container.screenSize)
        assertEquals("--fullscreen --no-splash", container.execArgs)
        assertEquals("Games\\Test\\game.exe", container.executablePath)
        assertEquals("TEST_VAR=value", container.envVars)
        assertEquals("alsa", container.audioDriver)
    }

    @Test
    fun graphicsDriverConfig_isAppliedCorrectly() {
        val graphicsConfig = com.winlator.core.KeyValueSet()
            .put("version", "turnip25.3.0")
            .put("presentMode", "immediate")
            .put("maxDeviceMemory", "2048")
            .toString()

        val containerData = ContainerData(
            graphicsDriver = "wrapper-v2",
            graphicsDriverVersion = "25.3.0",
            graphicsDriverConfig = graphicsConfig
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals("wrapper-v2", container.graphicsDriver)
        assertEquals("25.3.0", container.graphicsDriverVersion)
        assertEquals(graphicsConfig, container.graphicsDriverConfig)
    }

    @Test
    fun dxWrapperConfig_isAppliedCorrectly() {
        val dxWrapperConfig = com.winlator.core.KeyValueSet()
            .put("version", "2.14.1")
            .put("async", "1")
            .put("asyncCache", "1")
            .put("vkd3dVersion", "2.14.1")
            .put("vkd3dFeatureLevel", "12_1")
            .toString()

        val containerData = ContainerData(
            dxwrapper = "vkd3d",
            dxwrapperConfig = dxWrapperConfig
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals("vkd3d", container.dxWrapper)
        assertEquals(dxWrapperConfig, container.dxWrapperConfig)
    }

    @Test
    fun booleanFlags_areAppliedCorrectly() {
        val containerData = ContainerData(
            showFPS = true,
            launchRealSteam = true,
            allowSteamUpdates = true,
            forceDlc = true,
            useLegacyDRM = true,
            sdlControllerAPI = true,
            enableXInput = true,
            enableDInput = true,
            disableMouseInput = true,
            touchscreenMode = true,
            wow64Mode = true
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertTrue(container.isShowFPS)
        assertTrue(container.isLaunchRealSteam)
        assertTrue(container.isAllowSteamUpdates)
        assertTrue(container.isForceDlc)
        assertTrue(container.isUseLegacyDRM)
        assertTrue(container.isSdlControllerAPI)
        assertTrue(container.isDisableMouseInput)
        assertTrue(container.isTouchscreenMode)
    }

    @Test
    fun booleanFlags_falseValues_areAppliedCorrectly() {
        // Start with true values
        container.isShowFPS = true
        container.isLaunchRealSteam = true
        container.isForceDlc = true

        val containerData = ContainerData(
            showFPS = false,
            launchRealSteam = false,
            forceDlc = false
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertFalse(container.isShowFPS)
        assertFalse(container.isLaunchRealSteam)
        assertFalse(container.isForceDlc)
    }

    @Test
    fun bionicSpecificProperties_areAppliedCorrectly() {
        val containerData = ContainerData(
            containerVariant = Container.BIONIC,
            wineVersion = "proton-9.0-arm64ec",
            emulator = "box64",
            fexcoreVersion = "2511"
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals(Container.BIONIC, container.containerVariant)
        assertEquals("proton-9.0-arm64ec", container.wineVersion)
        assertEquals("box64", container.emulator)
        assertEquals("2511", container.fexCoreVersion)
        // Note: fexcoreTSOMode, fexcoreX87Mode, and fexcoreMultiBlock are written to
        // a separate config file via FEXCoreManager, not stored as Container properties
    }

    @Test
    fun glibcSpecificProperties_areAppliedCorrectly() {
        val containerData = ContainerData(
            containerVariant = Container.GLIBC,
            wineVersion = "wine-9.2-x86_64",
            graphicsDriverVersion = "2-842.6"
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals(Container.GLIBC, container.containerVariant)
        assertEquals("wine-9.2-x86_64", container.wineVersion)
        assertEquals("2-842.6", container.graphicsDriverVersion)
    }

    @Test
    fun complexConfigs_areAppliedCorrectly() {
        val graphicsConfig = com.winlator.core.KeyValueSet()
            .put("version", "turnip25.3.0_R3_Auto")
            .put("presentMode", "immediate")
            .put("resourceType", "dmabuf")
            .put("bcnEmulation", "full")
            .put("bcnEmulationType", "software")
            .put("bcnEmulationCache", "1")
            .put("disablePresentWait", "1")
            .put("syncFrame", "1")
            .put("maxDeviceMemory", "2048")
            .put("adrenotoolsTurnip", "0")
            .put("exposedDeviceExtensions", "VK_KHR_swapchain|VK_KHR_timeline_semaphore")
            .put("blacklistedExtensions", "VK_KHR_maintenance1")
            .toString()

        val dxWrapperConfig = com.winlator.core.KeyValueSet()
            .put("version", "2.14.1")
            .put("async", "1")
            .put("asyncCache", "1")
            .put("vkd3dVersion", "2.14.1")
            .put("vkd3dFeatureLevel", "12_0")
            .toString()

        val containerData = ContainerData(
            graphicsDriverConfig = graphicsConfig,
            dxwrapperConfig = dxWrapperConfig
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals(graphicsConfig, container.graphicsDriverConfig)
        assertEquals(dxWrapperConfig, container.dxWrapperConfig)
    }

    @Test
    fun winComponents_areAppliedCorrectly() {
        val winComponents = "direct3d=0,directsound=0,directmusic=1,directshow=1,directplay=1,vcrun2010=0,wmdecoder=0,opengl=1"

        val containerData = ContainerData(
            wincomponents = winComponents
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals(winComponents, container.winComponents)
    }

    @Test
    fun cpuLists_areAppliedCorrectly() {
        val containerData = ContainerData(
            cpuList = "0,2,4,6",
            cpuListWoW64 = "1,3,5,7",
            wow64Mode = true
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals("0,2,4,6", container.cpuList)
        assertEquals("1,3,5,7", container.cpuListWoW64)
        assertTrue(container.isWoW64Mode)
    }

    @Test
    fun box64Properties_areAppliedCorrectly() {
        val containerData = ContainerData(
            box64Version = "0.3.8",
            box64Preset = com.winlator.box86_64.Box86_64Preset.PERFORMANCE
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals("0.3.8", container.box64Version)
        assertEquals(com.winlator.box86_64.Box86_64Preset.PERFORMANCE, container.box64Preset)
    }

    @Test
    fun sharpnessProperties_areStoredInExtra() {
        val containerData = ContainerData(
            sharpnessEffect = "CAS",
            sharpnessLevel = 64,
            sharpnessDenoise = 12
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals("CAS", container.getExtra("sharpnessEffect"))
        assertEquals("64", container.getExtra("sharpnessLevel"))
        assertEquals("12", container.getExtra("sharpnessDenoise"))
    }

    @Test
    fun language_isAppliedCorrectly() {
        val containerData = ContainerData(
            language = "german"
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        // Language may be stored in extra if setter fails
        val actualLanguage = try {
            container.language
        } catch (e: Exception) {
            container.getExtra("language", "english")
        }
        assertEquals("german", actualLanguage)
    }

    @Test
    fun steamType_isAppliedCorrectly() {
        val containerData = ContainerData(
            steamType = Container.STEAM_TYPE_LIGHT
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals(Container.STEAM_TYPE_LIGHT, container.getSteamType())
    }

    @Test
    fun startupSelection_isAppliedCorrectly() {
        val containerData = ContainerData(
            startupSelection = Container.STARTUP_SELECTION_AGGRESSIVE
        )

        ContainerUtils.applyToContainer(context, container, containerData, saveToDisk = false)

        assertEquals(Container.STARTUP_SELECTION_AGGRESSIVE, container.startupSelection)
    }
}

