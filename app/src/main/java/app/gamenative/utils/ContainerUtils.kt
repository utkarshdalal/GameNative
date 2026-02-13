package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
import com.winlator.core.GPUInformation
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineThemeManager
import com.winlator.fexcore.FEXCoreManager
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.winhandler.WinHandler.PreferredInputApi
import com.winlator.xenvironment.ImageFs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object ContainerUtils {
    data class GpuInfo(
        val deviceId: Int,
        val vendorId: Int,
        val name: String,
    )

    fun setContainerDefaults(context: Context) {
        // Override default driver and DXVK version based on Turnip capability
        if (GPUInformation.isTurnipCapable(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-9.0-arm64ec"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = if (GPUInformation.isAdreno6xx(context)) "1.11.1-sarek" else "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = "turnip26.0.0_R8"
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else if (GPUInformation.isAdreno8Elite(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-9.0-arm64ec"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = "Turnip_Gen8_V23"
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-9.0-arm64ec"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = "async-1.10.3"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_LIGHT
            DefaultVersion.ASYNC_CACHE = "0"
        }
    }

    fun getGPUCards(context: Context): Map<Int, GpuInfo> {
        val gpuNames = JSONArray(FileUtils.readString(context, "gpu_cards.json"))
        return List(gpuNames.length()) {
            val deviceId = gpuNames.getJSONObject(it).getInt("deviceID")
            Pair(
                deviceId,
                GpuInfo(
                    deviceId = deviceId,
                    vendorId = gpuNames.getJSONObject(it).getInt("vendorID"),
                    name = gpuNames.getJSONObject(it).getString("name"),
                ),
            )
        }.toMap()
    }

    fun getDefaultContainerData(): ContainerData {
        return ContainerData(
            screenSize = PrefManager.screenSize,
            envVars = PrefManager.envVars,
            graphicsDriver = PrefManager.graphicsDriver,
            graphicsDriverVersion = PrefManager.graphicsDriverVersion,
            graphicsDriverConfig = PrefManager.graphicsDriverConfig,
            dxwrapper = PrefManager.dxWrapper,
            dxwrapperConfig = PrefManager.dxWrapperConfig,
            audioDriver = PrefManager.audioDriver,
            wincomponents = PrefManager.winComponents,
            drives = PrefManager.drives,
            execArgs = PrefManager.execArgs,
            showFPS = PrefManager.showFps,
            launchRealSteam = PrefManager.launchRealSteam,
            cpuList = PrefManager.cpuList,
            cpuListWoW64 = PrefManager.cpuListWoW64,
            wow64Mode = PrefManager.wow64Mode,
            startupSelection = PrefManager.startupSelection.toByte(),
            box86Version = PrefManager.box86Version,
            box64Version = PrefManager.box64Version,
            box86Preset = PrefManager.box86Preset,
            box64Preset = PrefManager.box64Preset,
            desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME,
            language = PrefManager.containerLanguage,
            containerVariant = PrefManager.containerVariant,
            forceDlc = PrefManager.forceDlc,
            useLegacyDRM = PrefManager.useLegacyDRM,
            unpackFiles = PrefManager.unpackFiles,
            wineVersion = PrefManager.wineVersion,
            emulator = PrefManager.emulator,
            fexcoreVersion = PrefManager.fexcoreVersion,
            fexcoreTSOMode = PrefManager.fexcoreTSOMode,
            fexcoreX87Mode = PrefManager.fexcoreX87Mode,
            fexcoreMultiBlock = PrefManager.fexcoreMultiBlock,
            fexcorePreset = PrefManager.fexcorePreset,
            renderer = PrefManager.renderer,
            csmt = PrefManager.csmt,
            videoPciDeviceID = PrefManager.videoPciDeviceID,
            offScreenRenderingMode = PrefManager.offScreenRenderingMode,
            strictShaderMath = PrefManager.strictShaderMath,
            videoMemorySize = PrefManager.videoMemorySize,
            mouseWarpOverride = PrefManager.mouseWarpOverride,
            useDRI3 = PrefManager.useDRI3,
            useSteamInput = PrefManager.useSteamInput,
            enableXInput = PrefManager.xinputEnabled,
			enableDInput = PrefManager.dinputEnabled,
			dinputMapperType = PrefManager.dinputMapperType.toByte(),
            disableMouseInput = PrefManager.disableMouseInput,
            externalDisplayMode = PrefManager.externalDisplayInputMode,
            externalDisplaySwap = PrefManager.externalDisplaySwap,
            sharpnessEffect = PrefManager.sharpnessEffect,
            sharpnessLevel = PrefManager.sharpnessLevel,
            sharpnessDenoise = PrefManager.sharpnessDenoise,
        )
    }

    fun setDefaultContainerData(containerData: ContainerData) {
        PrefManager.screenSize = containerData.screenSize
        PrefManager.envVars = containerData.envVars
        PrefManager.graphicsDriver = containerData.graphicsDriver
        PrefManager.graphicsDriverVersion = containerData.graphicsDriverVersion
        PrefManager.graphicsDriverConfig = containerData.graphicsDriverConfig
        PrefManager.dxWrapper = containerData.dxwrapper
        PrefManager.dxWrapperConfig = containerData.dxwrapperConfig
        PrefManager.audioDriver = containerData.audioDriver
        PrefManager.winComponents = containerData.wincomponents
        PrefManager.drives = containerData.drives
        PrefManager.execArgs = containerData.execArgs
        PrefManager.showFps = containerData.showFPS
        PrefManager.launchRealSteam = containerData.launchRealSteam
        PrefManager.cpuList = containerData.cpuList
        PrefManager.cpuListWoW64 = containerData.cpuListWoW64
        PrefManager.wow64Mode = containerData.wow64Mode
        PrefManager.startupSelection = containerData.startupSelection.toInt()
        PrefManager.box86Version = containerData.box86Version
        PrefManager.box64Version = containerData.box64Version
        PrefManager.box86Preset = containerData.box86Preset
        PrefManager.box64Preset = containerData.box64Preset

        PrefManager.csmt = containerData.csmt
        PrefManager.videoPciDeviceID = containerData.videoPciDeviceID
        PrefManager.offScreenRenderingMode = containerData.offScreenRenderingMode
        PrefManager.strictShaderMath = containerData.strictShaderMath
        PrefManager.videoMemorySize = containerData.videoMemorySize
        PrefManager.mouseWarpOverride = containerData.mouseWarpOverride
        PrefManager.useDRI3 = containerData.useDRI3
        PrefManager.disableMouseInput = containerData.disableMouseInput
        PrefManager.externalDisplayInputMode = containerData.externalDisplayMode
        PrefManager.externalDisplaySwap = containerData.externalDisplaySwap
        PrefManager.containerLanguage = containerData.language
        PrefManager.containerVariant = containerData.containerVariant
        PrefManager.wineVersion = containerData.wineVersion
        // Persist emulator/fexcore defaults for future containers
        PrefManager.emulator = containerData.emulator
        PrefManager.fexcoreVersion = containerData.fexcoreVersion
        PrefManager.fexcoreTSOMode = containerData.fexcoreTSOMode
        PrefManager.fexcoreX87Mode = containerData.fexcoreX87Mode
        PrefManager.fexcoreMultiBlock = containerData.fexcoreMultiBlock
        PrefManager.fexcorePreset = containerData.fexcorePreset
		// Persist renderer and controller defaults
		PrefManager.renderer = containerData.renderer
        PrefManager.useSteamInput = containerData.useSteamInput
        PrefManager.xinputEnabled = containerData.enableXInput
		PrefManager.dinputEnabled = containerData.enableDInput
		PrefManager.dinputMapperType = containerData.dinputMapperType.toInt()
        PrefManager.forceDlc = containerData.forceDlc
        PrefManager.useLegacyDRM = containerData.useLegacyDRM
        PrefManager.unpackFiles = containerData.unpackFiles
        PrefManager.sharpnessEffect = containerData.sharpnessEffect
        PrefManager.sharpnessLevel = containerData.sharpnessLevel
        PrefManager.sharpnessDenoise = containerData.sharpnessDenoise
    }

    fun toContainerData(container: Container): ContainerData {
        val renderer: String
        val csmt: Boolean
        val videoPciDeviceID: Int
        val offScreenRenderingMode: String
        val strictShaderMath: Boolean
        val videoMemorySize: String
        val mouseWarpOverride: String

        val userRegFile = File(container.rootDir, ".wine/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
            renderer =
                registryEditor.getStringValue("Software\\Wine\\Direct3D", "renderer", PrefManager.renderer)
            csmt =
                registryEditor.getDwordValue("Software\\Wine\\Direct3D", "csmt", if (PrefManager.csmt) 3 else 0) != 0

            videoPciDeviceID =
                registryEditor.getDwordValue("Software\\Wine\\Direct3D", "VideoPciDeviceID", PrefManager.videoPciDeviceID)

            offScreenRenderingMode =
                registryEditor.getStringValue("Software\\Wine\\Direct3D", "OffScreenRenderingMode", PrefManager.offScreenRenderingMode)

            val strictShader = if (PrefManager.strictShaderMath) 1 else 0
            strictShaderMath =
                registryEditor.getDwordValue("Software\\Wine\\Direct3D", "strict_shader_math", strictShader) != 0

            videoMemorySize =
                registryEditor.getStringValue("Software\\Wine\\Direct3D", "VideoMemorySize", PrefManager.videoMemorySize)

            mouseWarpOverride =
                registryEditor.getStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", PrefManager.mouseWarpOverride)
        }

        // Read controller API settings from container
        val apiOrdinal = container.getInputType()
        val enableX = apiOrdinal == PreferredInputApi.XINPUT.ordinal || apiOrdinal == PreferredInputApi.BOTH.ordinal
        val enableD = apiOrdinal == PreferredInputApi.DINPUT.ordinal || apiOrdinal == PreferredInputApi.BOTH.ordinal
        val mapperType = container.getDinputMapperType()
        val useSteamInput = container.getExtra("useSteamInput", "false").toBoolean()
        // Read disable-mouse flag from container
        val disableMouse = container.isDisableMouseInput()
        // Read touchscreen-mode flag from container
        val touchscreenMode = container.isTouchscreenMode()
        val externalDisplayMode = container.getExternalDisplayMode()
        val externalDisplaySwap = container.isExternalDisplaySwap()

        return ContainerData(
            name = container.name,
            screenSize = container.screenSize,
            envVars = container.envVars,
            graphicsDriver = container.graphicsDriver,
            graphicsDriverVersion = container.graphicsDriverVersion,
            graphicsDriverConfig = container.graphicsDriverConfig,
            dxwrapper = container.dxWrapper,
            dxwrapperConfig = container.dxWrapperConfig,
            audioDriver = container.audioDriver,
            wincomponents = container.winComponents,
            drives = container.drives,
            execArgs = container.execArgs,
            executablePath = container.executablePath,
            showFPS = container.isShowFPS,
            launchRealSteam = container.isLaunchRealSteam,
            allowSteamUpdates = container.isAllowSteamUpdates,
            steamType = container.getSteamType(),
            cpuList = container.cpuList,
            cpuListWoW64 = container.cpuListWoW64,
            wow64Mode = container.isWoW64Mode,
            startupSelection = container.startupSelection.toByte(),
            box86Version = container.box86Version,
            box64Version = container.box64Version,
            box86Preset = container.box86Preset,
            box64Preset = container.box64Preset,
            desktopTheme = container.desktopTheme,
            containerVariant = container.containerVariant,
            wineVersion = container.wineVersion,
            emulator = container.emulator,
            fexcoreVersion = container.fexCoreVersion,
            fexcorePreset = container.getFEXCorePreset(),
            language = container.language,
            sdlControllerAPI = container.isSdlControllerAPI,
            useSteamInput = useSteamInput,
            forceDlc = container.isForceDlc,
            useLegacyDRM = container.isUseLegacyDRM(),
            unpackFiles = container.isUnpackFiles(),
            enableXInput = enableX,
            enableDInput = enableD,
            dinputMapperType = mapperType,
            disableMouseInput = disableMouse,
            touchscreenMode = touchscreenMode,
            externalDisplayMode = externalDisplayMode,
            externalDisplaySwap = externalDisplaySwap,
            csmt = csmt,
            videoPciDeviceID = videoPciDeviceID,
            offScreenRenderingMode = offScreenRenderingMode,
            strictShaderMath = strictShaderMath,
            useDRI3 = container.isUseDRI3(),
            videoMemorySize = videoMemorySize,
            mouseWarpOverride = mouseWarpOverride,
            sharpnessEffect = container.getExtra("sharpnessEffect", "None"),
            sharpnessLevel = container.getExtra("sharpnessLevel", "100").toIntOrNull() ?: 100,
            sharpnessDenoise = container.getExtra("sharpnessDenoise", "100").toIntOrNull() ?: 100,
        )
    }

    fun applyToContainer(context: Context, appId: String, containerData: ContainerData) {
        val container = getContainer(context, appId)
        applyToContainer(context, container, containerData)
    }

    /**
     * Applies best config map to containerData, handling all possible fields.
     * Used when applyKnownConfig=true returns all validated fields.
     */
    fun applyBestConfigMapToContainerData(containerData: ContainerData, bestConfigMap: Map<String, Any?>): ContainerData {
        var updatedData = containerData
        bestConfigMap.forEach { (key, value) ->
            updatedData = when (key) {
                "executablePath" -> value?.let { updatedData.copy(executablePath = it as? String ?: updatedData.executablePath) }
                    ?: updatedData
                "graphicsDriver" -> value?.let { updatedData.copy(graphicsDriver = it as? String ?: updatedData.graphicsDriver) }
                    ?: updatedData
                "graphicsDriverVersion" -> value?.let {
                    updatedData.copy(
                        graphicsDriverVersion =
                            it as? String ?: updatedData.graphicsDriverVersion,
                    )
                }
                    ?: updatedData
                "graphicsDriverConfig" -> value?.let {
                    updatedData.copy(
                        graphicsDriverConfig =
                            it as? String ?: updatedData.graphicsDriverConfig,
                    )
                }
                    ?: updatedData
                "dxwrapper" -> value?.let { updatedData.copy(dxwrapper = it as? String ?: updatedData.dxwrapper) } ?: updatedData
                "dxwrapperConfig" -> value?.let { updatedData.copy(dxwrapperConfig = it as? String ?: updatedData.dxwrapperConfig) }
                    ?: updatedData
                "execArgs" -> value?.let { updatedData.copy(execArgs = it as? String ?: updatedData.execArgs) } ?: updatedData
                "startupSelection" -> value?.let {
                    updatedData.copy(
                        startupSelection =
                            (it as? Int)?.toByte() ?: updatedData.startupSelection,
                    )
                }
                    ?: updatedData
                "box64Version" -> value?.let { updatedData.copy(box64Version = it as? String ?: updatedData.box64Version) } ?: updatedData
                "box64Preset" -> value?.let { updatedData.copy(box64Preset = it as? String ?: updatedData.box64Preset) } ?: updatedData
                "containerVariant" -> value?.let { updatedData.copy(containerVariant = it as? String ?: updatedData.containerVariant) }
                    ?: updatedData
                "wineVersion" -> value?.let { updatedData.copy(wineVersion = it as? String ?: updatedData.wineVersion) } ?: updatedData
                "emulator" -> value?.let { updatedData.copy(emulator = it as? String ?: updatedData.emulator) } ?: updatedData
                "fexcoreVersion" -> value?.let { updatedData.copy(fexcoreVersion = it as? String ?: updatedData.fexcoreVersion) }
                    ?: updatedData
                "fexcoreTSOMode" -> value?.let { updatedData.copy(fexcoreTSOMode = it as? String ?: updatedData.fexcoreTSOMode) }
                    ?: updatedData
                "fexcoreX87Mode" -> value?.let { updatedData.copy(fexcoreX87Mode = it as? String ?: updatedData.fexcoreX87Mode) }
                    ?: updatedData
                "fexcoreMultiBlock" -> value?.let { updatedData.copy(fexcoreMultiBlock = it as? String ?: updatedData.fexcoreMultiBlock) }
                    ?: updatedData
                "fexcorePreset" -> value?.let { updatedData.copy(fexcorePreset = it as? String ?: updatedData.fexcorePreset) }
                    ?: updatedData
                "useLegacyDRM" -> value?.let { updatedData.copy(useLegacyDRM = it as? Boolean ?: updatedData.useLegacyDRM) } ?: updatedData
                "unpackFiles" -> value?.let { updatedData.copy(unpackFiles = it as? Boolean ?: updatedData.unpackFiles) } ?: updatedData
                "envVars" -> value?.let { updatedData.copy(envVars = it as? String ?: updatedData.envVars) } ?: updatedData
                "cpuList" -> value?.let { updatedData.copy(cpuList = it as? String ?: updatedData.cpuList) } ?: updatedData
                "cpuListWoW64" -> value?.let { updatedData.copy(cpuListWoW64 = it as? String ?: updatedData.cpuListWoW64) } ?: updatedData
                "audioDriver" -> value?.let { updatedData.copy(audioDriver = it as? String ?: updatedData.audioDriver) } ?: updatedData
                "wincomponents" -> value?.let { updatedData.copy(wincomponents = it as? String ?: updatedData.wincomponents) } ?: updatedData
                "videoMemorySize" -> value?.let { updatedData.copy(videoMemorySize = it as? String ?: updatedData.videoMemorySize) } ?: updatedData
                else -> updatedData
            }
        }
        return updatedData
    }

    fun applyToContainer(context: Context, container: Container, containerData: ContainerData) {
        applyToContainer(context, container, containerData, saveToDisk = true)
    }

    fun applyToContainer(context: Context, container: Container, containerData: ContainerData, saveToDisk: Boolean) {
        Timber.d("Applying containerData to container. execArgs: '${containerData.execArgs}', saveToDisk: $saveToDisk")
        // Detect language change before mutating container
        val previousLanguage: String = try {
            container.language
        } catch (e: Exception) {
            container.getExtra("language", "english")
        }
        val previousForceDlc: Boolean = container.isForceDlc
        val previousUnpackFiles: Boolean = container.isUnpackFiles
        val userRegFile = File(container.rootDir, ".wine/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "renderer", containerData.renderer)
            registryEditor.setDwordValue("Software\\Wine\\Direct3D", "csmt", if (containerData.csmt) 3 else 0)
            registryEditor.setDwordValue("Software\\Wine\\Direct3D", "VideoPciDeviceID", containerData.videoPciDeviceID)
            registryEditor.setDwordValue(
                "Software\\Wine\\Direct3D",
                "VideoPciVendorID",
                getGPUCards(context)[containerData.videoPciDeviceID]!!.vendorId,
            )
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "OffScreenRenderingMode", containerData.offScreenRenderingMode)
            registryEditor.setDwordValue("Software\\Wine\\Direct3D", "strict_shader_math", if (containerData.strictShaderMath) 1 else 0)
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "VideoMemorySize", containerData.videoMemorySize)
            registryEditor.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", containerData.mouseWarpOverride)
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "shader_backend", "glsl")
            registryEditor.setStringValue("Software\\Wine\\Direct3D", "UseGLSL", "enabled")
        }

        container.name = containerData.name
        container.screenSize = containerData.screenSize
        container.envVars = containerData.envVars
        container.graphicsDriver = containerData.graphicsDriver
        // Save driver config through to container
        container.graphicsDriverConfig = containerData.graphicsDriverConfig
        container.dxWrapper = containerData.dxwrapper
        container.dxWrapperConfig = containerData.dxwrapperConfig
        container.audioDriver = containerData.audioDriver
        container.winComponents = containerData.wincomponents
        container.drives = containerData.drives
        container.execArgs = containerData.execArgs
        if (container.executablePath != containerData.executablePath && container.executablePath != "") {
            container.setNeedsUnpacking(true)
        }
        container.executablePath = containerData.executablePath
        container.isShowFPS = containerData.showFPS
        container.isLaunchRealSteam = containerData.launchRealSteam
        container.isAllowSteamUpdates = containerData.allowSteamUpdates
        container.setSteamType(containerData.steamType)
        container.cpuList = containerData.cpuList
        container.cpuListWoW64 = containerData.cpuListWoW64
        container.isWoW64Mode = containerData.wow64Mode
        container.startupSelection = containerData.startupSelection
        container.box86Version = containerData.box86Version
        container.box64Version = containerData.box64Version
        container.box86Preset = containerData.box86Preset
        container.box64Preset = containerData.box64Preset
        container.isSdlControllerAPI = containerData.sdlControllerAPI
        container.putExtra("useSteamInput", containerData.useSteamInput)
        container.desktopTheme = containerData.desktopTheme
        container.graphicsDriverVersion = containerData.graphicsDriverVersion
        container.containerVariant = containerData.containerVariant
        container.wineVersion = containerData.wineVersion
        container.emulator = containerData.emulator
        container.fexCoreVersion = containerData.fexcoreVersion
        container.setFEXCorePreset(containerData.fexcorePreset)
        container.setDisableMouseInput(containerData.disableMouseInput)
        container.setTouchscreenMode(containerData.touchscreenMode)
        container.setExternalDisplayMode(containerData.externalDisplayMode)
        container.setExternalDisplaySwap(containerData.externalDisplaySwap)
        container.setForceDlc(containerData.forceDlc)
        container.setUseLegacyDRM(containerData.useLegacyDRM)
        container.setUnpackFiles(containerData.unpackFiles)
        if (previousUnpackFiles != containerData.unpackFiles && containerData.unpackFiles) {
            container.setNeedsUnpacking(true)
        }
        container.putExtra("sharpnessEffect", containerData.sharpnessEffect)
        container.putExtra("sharpnessLevel", containerData.sharpnessLevel.toString())
        container.putExtra("sharpnessDenoise", containerData.sharpnessDenoise.toString())
        try {
            container.language = containerData.language
        } catch (e: Exception) {
            container.putExtra("language", containerData.language)
        }
        // Set container LC_ALL according to selected language
        val lcAll = mapLanguageToLocale(containerData.language)
        container.setLC_ALL(lcAll)
        // If language changed, remove the STEAM_DLL_REPLACED marker so settings regenerate
        if (previousLanguage.lowercase() != containerData.language.lowercase()) {
            val steamAppId = extractGameIdFromContainerId(container.id)
            val appDirPath = SteamService.getAppDirPath(steamAppId)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
            Timber.i("Language changed from '$previousLanguage' to '${containerData.language}'. Cleared STEAM_DLL_REPLACED marker for container ${container.id}.")
        }
        if (previousLanguage.lowercase() != containerData.language.lowercase()) {
            val steamAppId = extractGameIdFromContainerId(container.id)
            val appDirPath = SteamService.getAppDirPath(steamAppId)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
            Timber.i("Language changed from '$previousLanguage' to '${containerData.language}'. Cleared STEAM_DLL_REPLACED marker for container ${container.id}.")
        }
        if (previousForceDlc != containerData.forceDlc) {
            val steamAppId = extractGameIdFromContainerId(container.id)
            val appDirPath = SteamService.getAppDirPath(steamAppId)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
            Timber.i("forceDlc changed from '$previousForceDlc' to '${containerData.forceDlc}'. Cleared STEAM_DLL_REPLACED marker for container ${container.id}.")
        }

        // Apply controller settings to container
        val api = when {
            containerData.enableXInput && containerData.enableDInput -> PreferredInputApi.BOTH
            containerData.enableXInput -> PreferredInputApi.XINPUT
            containerData.enableDInput -> PreferredInputApi.DINPUT
            else -> PreferredInputApi.AUTO
        }
        container.setInputType(api.ordinal)
        container.setDinputMapperType(containerData.dinputMapperType)
        container.setUseDRI3(containerData.useDRI3)
        Timber.d("Container set: preferredInputApi=%s, dinputMapperType=0x%02x", api, containerData.dinputMapperType)

        if (saveToDisk) {
            // Mark that config has been changed, so we can show feedback dialog after next game run
            container.putExtra("config_changed", "true")
            container.saveData()
        }
        Timber.d("Set container.execArgs to '${containerData.execArgs}'")
    }

    private fun mapLanguageToLocale(language: String): String {
        return when (language.lowercase()) {
            "arabic" -> "ar_SA.utf8"
            "bulgarian" -> "bg_BG.utf8"
            "schinese" -> "zh_CN.utf8"
            "tchinese" -> "zh_TW.utf8"
            "czech" -> "cs_CZ.utf8"
            "danish" -> "da_DK.utf8"
            "dutch" -> "nl_NL.utf8"
            "english" -> "en_US.utf8"
            "finnish" -> "fi_FI.utf8"
            "french" -> "fr_FR.utf8"
            "german" -> "de_DE.utf8"
            "greek" -> "el_GR.utf8"
            "hungarian" -> "hu_HU.utf8"
            "italian" -> "it_IT.utf8"
            "japanese" -> "ja_JP.utf8"
            "koreana" -> "ko_KR.utf8"
            "norwegian" -> "nb_NO.utf8"
            "polish" -> "pl_PL.utf8"
            "portuguese" -> "pt_PT.utf8"
            "brazilian" -> "pt_BR.utf8"
            "romanian" -> "ro_RO.utf8"
            "russian" -> "ru_RU.utf8"
            "spanish" -> "es_ES.utf8"
            "latam" -> "es_MX.utf8"
            "swedish" -> "sv_SE.utf8"
            "thai" -> "th_TH.utf8"
            "turkish" -> "tr_TR.utf8"
            "ukrainian" -> "uk_UA.utf8"
            "vietnamese" -> "vi_VN.utf8"
            else -> "en_US.utf8"
        }
    }

    fun getContainerId(appId: String): String {
        return appId
    }

    fun hasContainer(context: Context, appId: String): Boolean {
        val containerManager = ContainerManager(context)
        return containerManager.hasContainer(appId)
    }

    fun getContainer(context: Context, appId: String): Container {
        val containerManager = ContainerManager(context)
        return if (containerManager.hasContainer(appId)) {
            containerManager.getContainerById(appId)
        } else {
            throw Exception("Container does not exist for game $appId")
        }
    }

    private fun createNewContainer(
        context: Context,
        appId: String,
        containerId: String,
        containerManager: ContainerManager,
        customConfig: ContainerData? = null,
    ): Container {
         // Determine game source
        val gameSource = extractGameSourceFromContainerId(appId)

        // Set up container drives to include app
        val defaultDrives = PrefManager.drives
        val drives = when (gameSource) {
            GameSource.STEAM -> {
                // For Steam games, set up the app directory path
                val gameId = extractGameIdFromContainerId(appId)
                val appDirPath = SteamService.getAppDirPath(gameId)
                val drive: Char = Container.getNextAvailableDriveLetter(defaultDrives)
                "$defaultDrives$drive:$appDirPath"
            }

            GameSource.CUSTOM_GAME -> {
                // For Custom Games, find the game folder and map it to A: drive
                val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(appId)
                if (gameFolderPath != null) {
                    // Check if A: is already in defaultDrives, if not use it, otherwise use next available
                    val drive: Char = if (defaultDrives.contains("A:")) {
                        Container.getNextAvailableDriveLetter(defaultDrives)
                    } else {
                        'A'
                    }
                    "$defaultDrives$drive:$gameFolderPath"
                } else {
                    Timber.w("Could not find folder path for Custom Game: $appId")
                    defaultDrives
                }
            }

            GameSource.GOG -> {
                // For GOG games, map the specific game directory to A: drive
                val gameId = extractGameIdFromContainerId(appId)
                val game = GOGService.getGOGGameOf(gameId.toString())
                if (game != null && game.installPath.isNotEmpty()) {
                    val gameInstallPath = game.installPath
                    val drive: Char = if (defaultDrives.contains("A:")) {
                        Container.getNextAvailableDriveLetter(defaultDrives)
                    } else {
                        'A'
                    }
                    "$defaultDrives$drive:$gameInstallPath"
                } else {
                    Timber.w("Could not find GOG game info for: $gameId, using default drives")
                    defaultDrives
                }
            }

            GameSource.EPIC -> {
                // For Epic games, map the specific game directory to A: drive
                val gameId = extractGameIdFromContainerId(appId)
                val game = EpicService.getEpicGameOf(gameId)

                if (game != null && game.installPath.isNotEmpty()) {
                    val gameInstallPath = game.installPath
                    Timber.tag("Epic").d("EPIC GAME FOUND FOR DRIVE: $gameId")
                    Timber.tag("Epic").d("EPIC INSTALL PATH FOUND FOR DRIVE: $gameInstallPath")

                    val drive: Char = if (defaultDrives.contains("A:")) {
                        Container.getNextAvailableDriveLetter(defaultDrives)
                    } else {
                        'A'
                    }
                    "$defaultDrives$drive:$gameInstallPath"
                } else {
                    if (game == null) {
                        Timber.tag("Epic").w("Could not find Epic game info for: $gameId, using default drives")
                    } else {
                        Timber.tag("Epic").w("Epic game $gameId has empty install path, using default drives")
                    }
                    defaultDrives
                }
            }
        }
        Timber.d("Prepared container drives: $drives")

        // Prepare container data with default DX wrapper to start
        val initialDxWrapper = if (customConfig?.dxwrapper != null) {
            customConfig.dxwrapper
        } else {
            PrefManager.dxWrapper // Use default until we get the real version
        }

        // Set up data for container creation
        val data = JSONObject()
        data.put("name", "container_$containerId")

        // Create the actual container
        var container = containerManager.createContainerFuture(containerId, data).get()

        // If container creation failed, it might be because directory already exists but is corrupted
        // Try to clean it up and retry once
        if (container == null) {
            Timber.w("Container creation failed for $containerId, checking for corrupted directory...")
            // Get the container directory path
            val rootDir = ImageFs.find(context).getRootDir()
            val homeDir = File(rootDir, "home")
            val containerDir = File(homeDir, ImageFs.USER + "-" + containerId)

            if (containerDir.exists() && !containerManager.hasContainer(containerId)) {
                Timber.w("Found orphaned/corrupted container directory, deleting and retrying: $containerId")
                try {
                    FileUtils.delete(containerDir)
                    // Retry container creation after cleanup
                    container = containerManager.createContainerFuture(containerId, data).get()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to clean up corrupted container directory: $containerId")
                }
            }

            // If still null after retry, throw exception
            if (container == null) {
                Timber.e("Failed to create container for $containerId after cleanup attempt")
                throw IllegalStateException("Failed to create container: $containerId")
            }
        }

        // For Custom Games, pre-populate executablePath if there's exactly one valid .exe
        if (gameSource == GameSource.CUSTOM_GAME) {
            try {
                val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(appId)
                if (!gameFolderPath.isNullOrEmpty() && container.executablePath.isEmpty()) {
                    val auto = CustomGameScanner.findUniqueExeRelativeToFolder(gameFolderPath)
                    if (auto != null) {
                        Timber.i("Auto-selected Custom Game exe during container creation: $auto")
                        container.executablePath = auto
                        container.saveData()
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to auto-select exe during Custom Game creation for $appId")
            }
        }

        // Check for cached best config (only for Steam games, only if no custom config provided)
        var bestConfigMap: Map<String, Any?>? = null
        if (gameSource == GameSource.STEAM && customConfig == null) {
            try {
                val gameId = extractGameIdFromContainerId(appId)
                val appInfo = SteamService.getAppInfoOf(gameId)
                if (appInfo != null) {
                    val gameName = appInfo.name
                    val gpuName = GPUInformation.getRenderer(context)

                    // Check cache first (synchronous, fast)
                    // If not cached, make request on background thread (not UI thread)
                    runBlocking(Dispatchers.IO) {
                        try {
                            val bestConfig = BestConfigService.fetchBestConfig(gameName, gpuName)
                            if (bestConfig != null && bestConfig.matchType != "no_match") {
                                Timber.i("Applying best config for $gameName (matchType: ${bestConfig.matchType})")
                                val parsedConfig = BestConfigService.parseConfigToContainerData(
                                    context,
                                    bestConfig.bestConfig,
                                    bestConfig.matchType,
                                    true,
                                )
                                if (parsedConfig != null && parsedConfig.isNotEmpty()) {
                                    bestConfigMap = parsedConfig
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to get best config for container creation: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error checking for best config: ${e.message}")
            }
        }

        // Initialize container with default/custom config or best config
        var containerData = if (customConfig != null) {
            // Use custom config, but ensure drives are set if not specified
            if (customConfig.drives == Container.DEFAULT_DRIVES) {
                customConfig.copy(drives = drives)
            } else {
                customConfig
            }
        } else {
            // Use default config with drives
            ContainerData(
                screenSize = PrefManager.screenSize,
                envVars = PrefManager.envVars,
                cpuList = PrefManager.cpuList,
                cpuListWoW64 = PrefManager.cpuListWoW64,
                graphicsDriver = PrefManager.graphicsDriver,
                graphicsDriverVersion = PrefManager.graphicsDriverVersion,
                graphicsDriverConfig = PrefManager.graphicsDriverConfig,
                dxwrapper = initialDxWrapper,
                dxwrapperConfig = PrefManager.dxWrapperConfig,
                audioDriver = PrefManager.audioDriver,
                wincomponents = PrefManager.winComponents,
                drives = drives,
                execArgs = PrefManager.execArgs,
                showFPS = PrefManager.showFps,
                launchRealSteam = PrefManager.launchRealSteam,
                wow64Mode = PrefManager.wow64Mode,
                startupSelection = PrefManager.startupSelection.toByte(),
                box86Version = PrefManager.box86Version,
                box64Version = PrefManager.box64Version,
                box86Preset = PrefManager.box86Preset,
                box64Preset = PrefManager.box64Preset,
                desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME,
                language = PrefManager.containerLanguage,
                containerVariant = PrefManager.containerVariant,
                wineVersion = PrefManager.wineVersion,
                emulator = PrefManager.emulator,
                fexcoreVersion = PrefManager.fexcoreVersion,
                fexcoreTSOMode = PrefManager.fexcoreTSOMode,
                fexcoreX87Mode = PrefManager.fexcoreX87Mode,
                fexcoreMultiBlock = PrefManager.fexcoreMultiBlock,
                fexcorePreset = PrefManager.fexcorePreset,
                renderer = PrefManager.renderer,
                csmt = PrefManager.csmt,
                videoPciDeviceID = PrefManager.videoPciDeviceID,
                offScreenRenderingMode = PrefManager.offScreenRenderingMode,
                strictShaderMath = PrefManager.strictShaderMath,
                useDRI3 = PrefManager.useDRI3,
                videoMemorySize = PrefManager.videoMemorySize,
                mouseWarpOverride = PrefManager.mouseWarpOverride,
                enableXInput = PrefManager.xinputEnabled,
                enableDInput = PrefManager.dinputEnabled,
                dinputMapperType = PrefManager.dinputMapperType.toByte(),
                disableMouseInput = PrefManager.disableMouseInput,
                forceDlc = PrefManager.forceDlc,
                useLegacyDRM = PrefManager.useLegacyDRM,
                unpackFiles = PrefManager.unpackFiles,
                externalDisplayMode = PrefManager.externalDisplayInputMode,
                externalDisplaySwap = PrefManager.externalDisplaySwap,
            )
        }

        // Apply best config map to containerData if available (full validated config on first run when components exist)
        containerData = if (bestConfigMap != null && bestConfigMap.isNotEmpty()) {
            applyBestConfigMapToContainerData(containerData, bestConfigMap)
        } else {
            containerData
        }

        // If custom config is provided, just apply it and return
        if (customConfig?.dxwrapper != null) {
            applyToContainer(context, container, containerData)
            return container
        }

        // No custom config, so determine the DX wrapper synchronously (only for Steam games)
        // For GOG and Custom Games, use the default DX wrapper from preferences
        if (gameSource == GameSource.STEAM) {
            runBlocking {
                try {
                    Timber.i("Fetching DirectX version synchronously for app $appId")

                    val gameId = extractGameIdFromContainerId(appId)
                    // Create CompletableDeferred to wait for result
                    val deferred = kotlinx.coroutines.CompletableDeferred<Int>()

                    // Start the async fetch but wait for it to complete
                    SteamUtils.fetchDirect3DMajor(gameId) { dxVersion ->
                        deferred.complete(dxVersion)
                    }

                    // Wait for the result with a timeout
                    val dxVersion = try {
                        withTimeout(10000) { deferred.await() }
                    } catch (e: Exception) {
                        Timber.w(e, "Timeout waiting for DirectX version")
                        -1 // Default on timeout
                    }

                    // Set wrapper based on DirectX version
                    val newDxWrapper = when {
                        dxVersion == 12 -> "vkd3d"
                        dxVersion in 1..8 -> "wined3d"
                        else -> containerData.dxwrapper // Keep existing for DX10/11 or errors
                    }

                    // Update the wrapper if needed
                    if (newDxWrapper != containerData.dxwrapper) {
                        Timber.i("Setting DX wrapper for app $appId to $newDxWrapper (DirectX version: $dxVersion)")
                        containerData.dxwrapper = newDxWrapper
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Error determining DirectX version: ${e.message}")
                    // Continue with default wrapper on error
                }
            }
        }

        // Apply container data with the determined DX wrapper
        applyToContainer(context, container, containerData)
        return container
    }

    fun getOrCreateContainer(context: Context, appId: String): Container {
        val containerManager = ContainerManager(context)

        val container = if (containerManager.hasContainer(appId)) {
            containerManager.getContainerById(appId)
        } else {
            createNewContainer(context, appId, appId, containerManager)
        }

        // Ensure Custom Games have the A: drive mapped to the game folder
        // and GOG games have a drive mapped to the GOG games directory
        // and Epic games have a drive mapped to the Epic game directory
        val gameSource = extractGameSourceFromContainerId(appId)
        val gameFolderPath: String? = when (gameSource) {
            GameSource.STEAM -> {
                val gameId = extractGameIdFromContainerId(appId)
                SteamService.getAppDirPath(gameId)
            }

            GameSource.GOG -> {
                val gameId = extractGameIdFromContainerId(appId)
                GOGService.getInstallPath(gameId.toString())
            }

            GameSource.EPIC -> {
                val gameId = extractGameIdFromContainerId(appId)
                EpicService.getInstallPath(gameId)
            }

            GameSource.CUSTOM_GAME -> {
                CustomGameScanner.getFolderPathFromAppId(appId)
            }
            else -> null
        }

        if (gameFolderPath != null) {
            // Check if A: drive is already mapped to the correct path
            var hasCorrectADrive = false
            for (drive in Container.drivesIterator(container.drives)) {
                if (drive[0] == "A" && drive[1] == gameFolderPath) {
                    hasCorrectADrive = true
                    break
                }
            }

            // If A: drive is not mapped correctly, update it
            if (!hasCorrectADrive) {
                val currentDrives = container.drives
                // Rebuild drives string, excluding existing A: drive and adding new one
                val drivesBuilder = StringBuilder()
                drivesBuilder.append("A:$gameFolderPath")

                // Add all other drives (excluding A:)
                for (drive in Container.drivesIterator(currentDrives)) {
                    if (drive[0] != "A") {
                        drivesBuilder.append("${drive[0]}:${drive[1]}")
                    }
                }

                val updatedDrives = drivesBuilder.toString()
                container.drives = updatedDrives
                container.saveData()
                Timber.d("Updated container drives to include A: drive mapping: $updatedDrives")
            }
        } else {
            Timber.w("Could not find gameFolderPath for game $appId, skipping drive mapping update")
        }
        return container
    }

    fun getOrCreateContainerWithOverride(context: Context, appId: String): Container {
        val containerManager = ContainerManager(context)

        return if (containerManager.hasContainer(appId)) {
            val container = containerManager.getContainerById(appId)

            // Apply temporary override if present (without saving to disk)
            if (IntentLaunchManager.hasTemporaryOverride(appId)) {
                val overrideConfig = IntentLaunchManager.getTemporaryOverride(appId)
                if (overrideConfig != null) {
                    // Backup original config before applying override (if not already backed up)
                    if (IntentLaunchManager.getOriginalConfig(appId) == null) {
                        val originalConfig = toContainerData(container)
                        IntentLaunchManager.setOriginalConfig(appId, originalConfig)
                    }

                    // Get the effective config (merge base with override)
                    val effectiveConfig = IntentLaunchManager.getEffectiveContainerConfig(context, appId)
                    if (effectiveConfig != null) {
                        applyToContainer(context, container, effectiveConfig, saveToDisk = false)
                        Timber.i("Applied temporary config override to existing container for app $appId (in-memory only)")
                    }
                }
            }

            container
        } else {
            // Create new container with override config if present
            val overrideConfig = if (IntentLaunchManager.hasTemporaryOverride(appId)) {
                IntentLaunchManager.getTemporaryOverride(appId)
            } else {
                null
            }

            createNewContainer(context, appId, appId, containerManager, overrideConfig)
        }
    }

    /**
     * Deletes the container associated with the given appId, if it exists.
     */
    fun deleteContainer(context: Context, appId: String) {
        val manager = ContainerManager(context)
        if (manager.hasContainer(appId)) {
            // Remove the container directory asynchronously
            manager.removeContainerAsync(
                manager.getContainerById(appId),
            ) {
                Timber.i("Deleted container for appId=$appId")
            }
        }
    }

    /**
     * Extracts the game ID from a container ID string
     * Handles formats like:
     * - STEAM_123456 -> 123456
     * - EPIC_2938123
     * - CUSTOM_GAME_571969840 -> 571969840
     * - GOG_19283103 -> 19283103
     * - STEAM_123456(1) -> 123456
     * - 19283103 -> 19283103 (legacy GOG format)
     */
    fun extractGameIdFromContainerId(containerId: String): Int {
        // Remove duplicate suffix like (1), (2) if present
        val idWithoutSuffix = if (containerId.contains("(")) {
            containerId.substringBefore("(")
        } else {
            containerId
        }

        // Split by underscores and find the last numeric part
        val parts = idWithoutSuffix.split("_")
        // The last part should be the numeric ID
        val lastPart = parts.lastOrNull() ?: throw IllegalArgumentException("Invalid container ID format: $containerId")

        return try {
            lastPart.toInt()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Could not extract game ID from container ID: $containerId", e)
        }
    }

    /**
     * Extracts the game source from a container ID string
     */
    fun extractGameSourceFromContainerId(containerId: String): GameSource {
        return when {
            containerId.startsWith("STEAM_") -> GameSource.STEAM
            containerId.startsWith("CUSTOM_GAME_") -> GameSource.CUSTOM_GAME
            containerId.startsWith("GOG_") -> GameSource.GOG
            containerId.startsWith("EPIC_") -> GameSource.EPIC
            // Add other platforms here..
            else -> GameSource.STEAM // default fallback
        }
    }

    /**
     * Gets the file system path for the container's A: drive
     */
    fun getADrivePath(drives: String): String? {
        // Use the existing Container.drivesIterator logic
        for (drive in Container.drivesIterator(drives)) {
            if (drive[0] == "A") {
                return drive[1]
            }
        }
        return null
    }

    /**
     * Scans the container's A: drive for all .exe files
     */
    fun scanExecutablesInADrive(drives: String): List<String> {
        val executables = mutableListOf<String>()

        try {
            // Find the A: drive path from container drives
            val aDrivePath = getADrivePath(drives)
            if (aDrivePath == null) {
                Timber.w("No A: drive found in container drives")
                return emptyList()
            }

            val aDir = File(aDrivePath)
            if (!aDir.exists() || !aDir.isDirectory) {
                Timber.w("A: drive path does not exist or is not a directory: $aDrivePath")
                return emptyList()
            }

            Timber.d("Scanning for executables in A: drive: $aDrivePath")

            // Recursively scan for .exe files using listFiles with depth limit
            fun scanRecursive(dir: File, baseDir: File, depth: Int = 0, maxDepth: Int = 10) {
                if (depth > maxDepth) return

                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        scanRecursive(file, baseDir, depth + 1, maxDepth)
                    } else if (file.isFile && file.name.lowercase().endsWith(".exe")) {
                        // Convert to relative Windows path format
                        val relativePath = baseDir.toURI().relativize(file.toURI()).path
                        executables.add(relativePath)
                    }
                }
            }

            scanRecursive(aDir, aDir)

            // Sort alphabetically and prioritize common game executables
            executables.sortWith { a, b ->
                val aScore = getExecutablePriority(a)
                val bScore = getExecutablePriority(b)

                if (aScore != bScore) {
                    bScore.compareTo(aScore) // Higher priority first
                } else {
                    a.compareTo(b, ignoreCase = true) // Alphabetical
                }
            }

            Timber.d("Found ${executables.size} executables in A: drive")
        } catch (e: Exception) {
            Timber.e(e, "Error scanning A: drive for executables")
        }

        return executables
    }

    /**
     * Filters a list of exe paths to exclude system/utility executables (e.g. uninstallers, setup, crash handlers).
     * Used when unpackFiles is enabled to determine which exes to run Steamless on.
     */
    fun filterExesForUnpacking(exePaths: List<String>): List<String> = exePaths.filter { path ->
        val fileName = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        !isSystemExecutable(fileName)
    }

    /**
     * Assigns priority scores to executables for better sorting
     */
    private fun getExecutablePriority(exePath: String): Int {
        val fileName = exePath.substringAfterLast('\\').lowercase()
        val baseName = fileName.substringBeforeLast('.')

        return when {
            // Highest priority: common game executable patterns
            fileName.contains("game") -> 100

            fileName.contains("start") -> 85

            fileName.contains("main") -> 80

            fileName.contains("launcher") && !fileName.contains("unins") -> 75

            // High priority: probable main executables
            baseName.length >= 4 && !isSystemExecutable(fileName) -> 70

            // Medium priority: any non-system executable
            !isSystemExecutable(fileName) -> 50

            // Low priority: system/utility executables
            else -> 10
        }
    }

    /**
     * Checks if an executable is likely a system/utility file
     */
    private fun isSystemExecutable(fileName: String): Boolean {
        val systemKeywords = listOf(
            "unins", "setup", "install", "config", "crash", "handler",
            "viewer", "compiler", "tool", "redist", "vcredist", "directx",
            "steam", "origin", "uplay", "epic", "battlenet",
        )

        return systemKeywords.any { fileName.contains(it) }
    }
}
