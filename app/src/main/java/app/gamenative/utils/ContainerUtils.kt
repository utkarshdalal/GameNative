package app.gamenative.utils

import android.content.Context
import app.gamenative.enums.Marker
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.core.FileUtils
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineThemeManager
import java.io.File
import kotlin.Boolean
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import com.winlator.winhandler.WinHandler.PreferredInputApi

object ContainerUtils {
    data class GpuInfo(
        val deviceId: Int,
        val vendorId: Int,
        val name: String,
    )

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

            csmt = PrefManager.csmt,
            videoPciDeviceID = PrefManager.videoPciDeviceID,
            offScreenRenderingMode = PrefManager.offScreenRenderingMode,
            strictShaderMath = PrefManager.strictShaderMath,
            videoMemorySize = PrefManager.videoMemorySize,
            mouseWarpOverride = PrefManager.mouseWarpOverride,
            disableMouseInput = PrefManager.disableMouseInput,
        )
    }

    fun setDefaultContainerData(containerData: ContainerData) {
        PrefManager.screenSize = containerData.screenSize
        PrefManager.envVars = containerData.envVars
        PrefManager.graphicsDriver = containerData.graphicsDriver
        PrefManager.graphicsDriverVersion = containerData.graphicsDriverVersion
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
        PrefManager.disableMouseInput = containerData.disableMouseInput
        PrefManager.containerLanguage = containerData.language
    }

    fun toContainerData(container: Container): ContainerData {
        val csmt: Boolean
        val videoPciDeviceID: Int
        val offScreenRenderingMode: String
        val strictShaderMath: Boolean
        val videoMemorySize: String
        val mouseWarpOverride: String

        val userRegFile = File(container.rootDir, ".wine/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
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
        // Read disable-mouse flag from container
        val disableMouse = container.isDisableMouseInput()

        return ContainerData(
            name = container.name,
            screenSize = container.screenSize,
            envVars = container.envVars,
            graphicsDriver = container.graphicsDriver,
            graphicsDriverVersion = container.graphicsDriverVersion,
            dxwrapper = container.dxWrapper,
            dxwrapperConfig = container.dxWrapperConfig,
            audioDriver = container.audioDriver,
            wincomponents = container.winComponents,
            drives = container.drives,
            execArgs = container.execArgs,
            executablePath = container.executablePath,
            showFPS = container.isShowFPS,
            launchRealSteam = container.isLaunchRealSteam,
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
            language = try { container.language } catch (e: Exception) { container.getExtra("language", "english") },
            sdlControllerAPI = container.isSdlControllerAPI,
            enableXInput = enableX,
            enableDInput = enableD,
            dinputMapperType = mapperType,
            disableMouseInput = disableMouse,
            emulateKeyboardMouse = try { container.isEmulateKeyboardMouse() } catch (e: Exception) { false },
            controllerEmulationBindings = try { container.getControllerEmulationBindings()?.toString() ?: "" } catch (e: Exception) { "" },
            csmt = csmt,
            videoPciDeviceID = videoPciDeviceID,
            offScreenRenderingMode = offScreenRenderingMode,
            strictShaderMath = strictShaderMath,
            videoMemorySize = videoMemorySize,
            mouseWarpOverride = mouseWarpOverride,
        )
    }

    fun applyToContainer(context: Context, appId: Int, containerData: ContainerData) {
        val containerManager = ContainerManager(context)
        if (containerManager.hasContainer(appId)) {
            val container = containerManager.getContainerById(appId)
            applyToContainer(context, container, containerData)
        } else {
            throw Exception("Container does not exist for $appId")
        }
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
        val userRegFile = File(container.rootDir, ".wine/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
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
        container.desktopTheme = containerData.desktopTheme
        container.graphicsDriverVersion = containerData.graphicsDriverVersion
        container.setDisableMouseInput(containerData.disableMouseInput)
        container.setEmulateKeyboardMouse(containerData.emulateKeyboardMouse)
        try {
            val bindingsStr = containerData.controllerEmulationBindings
            if (bindingsStr.isNotEmpty()) {
                container.setControllerEmulationBindings(org.json.JSONObject(bindingsStr))
            }
        } catch (_: Exception) {}
        try { container.language = containerData.language } catch (e: Exception) { container.putExtra("language", containerData.language) }
        // Set container LC_ALL according to selected language
        val lcAll = mapLanguageToLocale(containerData.language)
        container.setLC_ALL(lcAll)
        // If language changed, remove the STEAM_DLL_REPLACED marker so settings regenerate
        if (previousLanguage.lowercase() != containerData.language.lowercase()) {
            val appDirPath = SteamService.getAppDirPath(container.id)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
            Timber.i("Language changed from '$previousLanguage' to '${containerData.language}'. Cleared STEAM_DLL_REPLACED marker for container ${container.id}.")
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
        Timber.d("Container set: preferredInputApi=%s, dinputMapperType=0x%02x", api, containerData.dinputMapperType)

        if (saveToDisk) {
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

    fun getContainerId(appId: Int): Int {
        // TODO: set up containers for each appId+depotId combo (intent extra "container_id")
        return appId
    }

    fun hasContainer(context: Context, appId: Int): Boolean {
        val containerId = getContainerId(appId)
        val containerManager = ContainerManager(context)
        return containerManager.hasContainer(containerId)
    }

    fun getContainer(context: Context, appId: Int): Container {
        val containerId = getContainerId(appId)

        val containerManager = ContainerManager(context)
        return if (containerManager.hasContainer(containerId)) {
            containerManager.getContainerById(containerId)
        } else {
            throw Exception("Container does not exist for game $appId")
        }
    }

    private fun createNewContainer(
        context: Context,
        appId: Int,
        containerId: Int,
        containerManager: ContainerManager,
        customConfig: ContainerData? = null,
    ): Container {
        // set up container drives to include app
        val defaultDrives = PrefManager.drives
        val appDirPath = SteamService.getAppDirPath(appId)
        val drive: Char = Container.getNextAvailableDriveLetter(defaultDrives)
        val drives = "$defaultDrives$drive:$appDirPath"
        Timber.d("Prepared container drives: $drives")

        val data = JSONObject()
        data.put("name", "container_$containerId")
        val container = containerManager.createContainerFuture(containerId, data).get()

        val containerData = if (customConfig != null) {
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
                dxwrapper = PrefManager.dxWrapper,
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

                csmt = PrefManager.csmt,
                videoPciDeviceID = PrefManager.videoPciDeviceID,
                offScreenRenderingMode = PrefManager.offScreenRenderingMode,
                strictShaderMath = PrefManager.strictShaderMath,
                videoMemorySize = PrefManager.videoMemorySize,
                mouseWarpOverride = PrefManager.mouseWarpOverride,
                disableMouseInput = PrefManager.disableMouseInput,
            )
        }

        applyToContainer(context, container, containerData)
        return container
    }

    fun getOrCreateContainer(context: Context, appId: Int): Container {
        val containerId = getContainerId(appId)
        val containerManager = ContainerManager(context)

        return if (containerManager.hasContainer(containerId)) {
            containerManager.getContainerById(containerId)
        } else {
            createNewContainer(context, appId, containerId, containerManager)
        }
    }

    fun getOrCreateContainerWithOverride(context: Context, appId: Int): Container {
        val containerId = getContainerId(appId)
        val containerManager = ContainerManager(context)

        return if (containerManager.hasContainer(containerId)) {
            val container = containerManager.getContainerById(containerId)

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

            createNewContainer(context, appId, containerId, containerManager, overrideConfig)
        }
    }

    /**
     * Deletes the container associated with the given appId, if it exists.
     */
    fun deleteContainer(context: Context, appId: Int) {
        val containerId = getContainerId(appId)
        val manager = ContainerManager(context)
        if (manager.hasContainer(containerId)) {
            // Remove the container directory asynchronously
            manager.removeContainerAsync(
                manager.getContainerById(containerId),
            ) {
                Timber.i("Deleted container for appId=$appId (containerId=$containerId)")
            }
        }
    }
}
