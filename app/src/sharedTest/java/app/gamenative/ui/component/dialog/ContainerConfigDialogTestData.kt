package app.gamenative.ui.component.dialog

import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import org.json.JSONObject

internal data class ContainerScenario(
    val initial: ContainerData,
    val mutated: ContainerData,
    val assertions: List<FieldAssertion>,
)

internal data class FieldAssertion(
    val name: String,
    val selector: (ContainerData) -> Any?,
)

internal fun createBionicScenario(): ContainerScenario {
    val base = ContainerData(
        containerVariant = Container.BIONIC,
        wineVersion = "proton-9.0-x86_64",
    )
    val mutated = base.commonOverrides().copy(
        containerVariant = Container.BIONIC,
        wineVersion = "proton-9.0-arm64ec",
        graphicsDriver = "wrapper-v2",
        graphicsDriverConfig = buildBionicGraphicsConfig(),
        dxwrapper = "vkd3d",
        dxwrapperConfig = buildDxWrapperConfig(
            version = "2.14.1",
            async = true,
            asyncCache = true,
            vkd3dVersion = "2.14.1",
            featureLevel = "12_0",
        ),
        emulator = "box64",
        box64Version = "0.3.8",
        box64Preset = Box86_64Preset.PERFORMANCE,
        fexcoreVersion = "2511",
        fexcoreTSOMode = "Slow",
        fexcoreX87Mode = "Slow",
        fexcoreMultiBlock = "Enabled",
        sharpnessEffect = "CAS",
        sharpnessLevel = 64,
        sharpnessDenoise = 12,
    )
    return ContainerScenario(initial = base, mutated = mutated, assertions = BIONIC_ASSERTIONS)
}

internal fun createGlibcScenario(): ContainerScenario {
    val base = ContainerData(
        containerVariant = Container.GLIBC,
        wineVersion = "wine-9.2-x86_64",
    )
    val mutated = base.commonOverrides().copy(
        containerVariant = Container.GLIBC,
        wineVersion = "wine-9.2-x86_64",
        graphicsDriver = "sd-8-elite",
        graphicsDriverVersion = "2-842.6",
        graphicsDriverConfig = buildGlibcGraphicsConfig(),
        dxwrapper = "vkd3d",
        dxwrapperConfig = buildDxWrapperConfig(
            version = "2.4.1-gplasync",
            async = true,
            asyncCache = true,
            vkd3dVersion = "2.14.1",
            featureLevel = "12_1",
        ),
        emulator = "box64",
        box64Version = "0.3.4",
        box64Preset = Box86_64Preset.STABILITY,
        sharpnessEffect = "DLS",
        sharpnessLevel = 40,
        sharpnessDenoise = 22,
    )
    return ContainerScenario(initial = base, mutated = mutated, assertions = GLIBC_ASSERTIONS)
}

internal fun assertFieldsChanged(scenario: ContainerScenario) {
    scenario.assertions.forEach { assertion ->
        val initialValue = assertion.selector(scenario.initial)
        val mutatedValue = assertion.selector(scenario.mutated)
        check(initialValue != mutatedValue) {
            "Expected ${assertion.name} to change, but both values were '$initialValue'"
        }
    }
}

private fun ContainerData.commonOverrides(): ContainerData {
    val envVars = EnvVars().apply {
        put("DXVK_ASYNC", "1")
        put("VKD3D_FEATURE_LEVEL", "12_1")
        put("WINEDLLOVERRIDES", "d3d11=n,b")
    }.toString()
    return copy(
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
        wincomponents = buildWinComponents(),
        graphicsDriverConfig = KeyValueSet().toString(),
        dxwrapperConfig = KeyValueSet().toString(),
        sharpnessEffect = "CAS",
        sharpnessLevel = 60,
        sharpnessDenoise = 20,
        videoPciDeviceID = 4242,
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
        shooterMode = false,
        gestureConfig = "{\"longPressEnabled\":true,\"twoFingerTapAction\":\"middle_click\"}",
        emulateKeyboardMouse = true,
        controllerEmulationBindings = buildControllerBindings(),
        startupSelection = Container.STARTUP_SELECTION_AGGRESSIVE,
        cpuList = "0,2,4,6",
        cpuListWoW64 = "1,3,5,7",
        box64Preset = Box86_64Preset.INTERMEDIATE,
    )
}

private fun buildBionicGraphicsConfig(): String {
    return KeyValueSet()
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
}

private fun buildGlibcGraphicsConfig(): String {
    return KeyValueSet()
        .put("vkMaxVersion", "1.2")
        .put("imageCacheSize", "512")
        .put("maxDeviceMemory", "4096")
        .put("exposedDeviceExtensions", "VK_KHR_swapchain|VK_KHR_timeline_semaphore")
        .put("blacklistedExtensions", "VK_EXT_sample_locations")
        .toString()
}

private fun buildDxWrapperConfig(
    version: String,
    async: Boolean,
    asyncCache: Boolean,
    vkd3dVersion: String,
    featureLevel: String,
): String {
    return KeyValueSet()
        .put("version", version)
        .put("async", if (async) "1" else "0")
        .put("asyncCache", if (asyncCache) "1" else "0")
        .put("vkd3dVersion", vkd3dVersion)
        .put("vkd3dFeatureLevel", featureLevel)
        .toString()
}

private fun buildWinComponents(): String {
    return listOf(
        "direct3d" to 0,
        "directsound" to 0,
        "directmusic" to 1,
        "directshow" to 1,
        "directplay" to 1,
        "vcrun2010" to 0,
        "wmdecoder" to 0,
        "opengl" to 1,
    ).joinToString(",") { "${it.first}=${it.second}" }
}

private fun buildControllerBindings(): String {
    val mapping = mapOf(
        "A" to "KEY_SPACE",
        "B" to "KEY_Q",
        "X" to "KEY_E",
        "Y" to "KEY_TAB",
        "L1" to "KEY_SHIFT_L",
        "L2" to "MOUSE_LEFT_BUTTON",
        "L3" to "KEY_CTRL_L",
        "R1" to "KEY_CTRL_R",
        "R2" to "MOUSE_RIGHT_BUTTON",
        "R3" to "KEY_ALT_R",
        "DPAD_UP" to "KEY_UP",
        "DPAD_DOWN" to "KEY_DOWN",
        "DPAD_LEFT" to "KEY_LEFT",
        "DPAD_RIGHT" to "KEY_RIGHT",
        "START" to "KEY_ENTER",
        "SELECT" to "KEY_ESC",
    )
    val json = JSONObject()
    mapping.forEach { (key, value) -> json.put(key, value) }
    return json.toString()
}

private val COMMON_ASSERTIONS = listOf(
    FieldAssertion("executablePath") { it.executablePath },
    FieldAssertion("execArgs") { it.execArgs },
    FieldAssertion("language") { it.language },
    FieldAssertion("screenSize") { it.screenSize },
    FieldAssertion("envVars") { it.envVars },
    FieldAssertion("audioDriver") { it.audioDriver },
    FieldAssertion("showFPS") { it.showFPS },
    FieldAssertion("forceDlc") { it.forceDlc },
    FieldAssertion("useLegacyDRM") { it.useLegacyDRM },
    FieldAssertion("launchRealSteam") { it.launchRealSteam },
    FieldAssertion("allowSteamUpdates") { it.allowSteamUpdates },
    FieldAssertion("steamType") { it.steamType },
    FieldAssertion("wincomponents") { it.wincomponents },
    FieldAssertion("graphicsDriver") { it.graphicsDriver },
    FieldAssertion("graphicsDriverConfig") { it.graphicsDriverConfig },
    FieldAssertion("dxwrapper") { it.dxwrapper },
    FieldAssertion("dxwrapperConfig") { it.dxwrapperConfig },
    FieldAssertion("box64Version") { it.box64Version },
    FieldAssertion("box64Preset") { it.box64Preset },
    FieldAssertion("sharpnessEffect") { it.sharpnessEffect },
    FieldAssertion("sharpnessLevel") { it.sharpnessLevel },
    FieldAssertion("sharpnessDenoise") { it.sharpnessDenoise },
    FieldAssertion("videoPciDeviceID") { it.videoPciDeviceID },
    FieldAssertion("offScreenRenderingMode") { it.offScreenRenderingMode },
    FieldAssertion("videoMemorySize") { it.videoMemorySize },
    FieldAssertion("mouseWarpOverride") { it.mouseWarpOverride },
    FieldAssertion("csmt") { it.csmt },
    FieldAssertion("strictShaderMath") { it.strictShaderMath },
    FieldAssertion("useDRI3") { it.useDRI3 },
    FieldAssertion("emulator") { it.emulator },
    FieldAssertion("sdlControllerAPI") { it.sdlControllerAPI },
    FieldAssertion("enableXInput") { it.enableXInput },
    FieldAssertion("enableDInput") { it.enableDInput },
    FieldAssertion("dinputMapperType") { it.dinputMapperType },
    FieldAssertion("disableMouseInput") { it.disableMouseInput },
    FieldAssertion("touchscreenMode") { it.touchscreenMode },
    FieldAssertion("shooterMode") { it.shooterMode },
    FieldAssertion("gestureConfig") { it.gestureConfig },
    FieldAssertion("emulateKeyboardMouse") { it.emulateKeyboardMouse },
    FieldAssertion("controllerEmulationBindings") { it.controllerEmulationBindings },
    FieldAssertion("startupSelection") { it.startupSelection },
    FieldAssertion("cpuList") { it.cpuList },
    FieldAssertion("cpuListWoW64") { it.cpuListWoW64 },
)

private val BIONIC_ASSERTIONS = COMMON_ASSERTIONS + listOf(
    FieldAssertion("fexcoreVersion") { it.fexcoreVersion },
    FieldAssertion("fexcoreTSOMode") { it.fexcoreTSOMode },
    FieldAssertion("fexcoreX87Mode") { it.fexcoreX87Mode },
    FieldAssertion("fexcoreMultiBlock") { it.fexcoreMultiBlock },
    FieldAssertion("wineVersion") { it.wineVersion },
)

private val GLIBC_ASSERTIONS = COMMON_ASSERTIONS + listOf(
    FieldAssertion("graphicsDriverVersion") { it.graphicsDriverVersion },
)

