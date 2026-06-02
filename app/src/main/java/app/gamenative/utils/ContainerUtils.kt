package app.gamenative.utils

import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.html5.Html5OptInService
import app.gamenative.html5.Html5SlugUtil
import app.gamenative.html5.savesync.Html5SaveSyncService
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.LsfgVkManager
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
import com.winlator.core.KeyValueSet
import com.winlator.core.GPUInformation
import com.winlator.core.envvars.EnvVars
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineThemeManager
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.winhandler.WinHandler.PreferredInputApi
import com.winlator.xenvironment.ImageFs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    const val WRAPPER_TURNIP_CAPABLE = "Turnip v26.2.0 R4"
    const val WRAPPER_ADRENO_8ELITE_GEN5 = "Turnip Adreno Driver T26 (@Mr_Purple_666)"
    const val WRAPPER_ADRENO_8ELITE = "Turnip Gen8 V30"
    const val WRAPPER_ADRENO_A12 = "Turnip v26.1.0 A12 Fix"

    val wrapperDriverDefaults: List<String> =
        listOf(WRAPPER_TURNIP_CAPABLE, WRAPPER_ADRENO_8ELITE_GEN5, WRAPPER_ADRENO_8ELITE, WRAPPER_ADRENO_A12)

    fun setContainerDefaults(context: Context) {
        // Override default driver and DXVK version based on Turnip capability
        if (GPUInformation.isTurnipCapable(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = if (GPUInformation.isAdreno6xx(context)) "1.11.1-sarek" else "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = WRAPPER_TURNIP_CAPABLE
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else if (GPUInformation.isAdrenoA12(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = WRAPPER_ADRENO_A12
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else if (GPUInformation.isAdreno8EliteGen5(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = WRAPPER_ADRENO_8ELITE_GEN5
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else if (GPUInformation.isAdreno8Elite(context)) {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER = "Wrapper"
            DefaultVersion.DXVK = "2.4.1-gplasync"
            DefaultVersion.VKD3D = "2.14.1"
            DefaultVersion.WRAPPER = WRAPPER_ADRENO_8ELITE
            DefaultVersion.STEAM_TYPE = Container.STEAM_TYPE_NORMAL
            DefaultVersion.ASYNC_CACHE = "1"
        } else {
            DefaultVersion.VARIANT = Container.BIONIC
            DefaultVersion.WINE_VERSION = "proton-10.0-arm64ec-2"
            DefaultVersion.DEFAULT_GRAPHICS_DRIVER =
                if (GPUInformation.isAdrenoGPU(context)) "Wrapper" else "Wrapper-gamenative"
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
            rendererPresentMode = PrefManager.rendererPresentMode,
            displayRenderer = PrefManager.displayRendererMode,
            sfCompatMode = PrefManager.sfCompatMode,
            dxwrapper = PrefManager.dxWrapper,
            dxwrapperConfig = PrefManager.dxWrapperConfig,
            audioDriver = PrefManager.audioDriver,
            pulseaudioLowLatency = PrefManager.pulseaudioLowLatency,
            wincomponents = PrefManager.winComponents,
            drives = PrefManager.drives,
            execArgs = PrefManager.execArgs,
            showFPS = false,
            launchRealSteam = PrefManager.launchRealSteam,
            launchBionicSteam = PrefManager.launchBionicSteam,
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
            localSavesOnly = PrefManager.localSavesOnly,
            steamOfflineMode = PrefManager.steamOfflineMode,
            epicOfflineMode = PrefManager.epicOfflineMode,
            useLegacyDRM = PrefManager.useLegacyDRM,
            unpackFiles = PrefManager.unpackFiles,
            suspendPolicy = PrefManager.suspendPolicy,
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
            portraitMode = PrefManager.portraitMode,
            externalDisplayMode = PrefManager.externalDisplayInputMode,
            externalDisplaySwap = PrefManager.externalDisplaySwap,
            sharpnessEffect = PrefManager.sharpnessEffect,
            sharpnessLevel = PrefManager.sharpnessLevel,
            sharpnessDenoise = PrefManager.sharpnessDenoise,
            // global default-config dialog edits this slot DIRECTLY (no -1 follow-global sentinel
            // here -- this IS the global). per-container dialog uses -1 to mean "follow this".
            renderScale = PrefManager.html5RenderScale,
        )
    }

    fun setDefaultContainerData(containerData: ContainerData) {
        PrefManager.screenSize = containerData.screenSize
        PrefManager.envVars = containerData.envVars
        PrefManager.graphicsDriver = containerData.graphicsDriver
        PrefManager.graphicsDriverVersion = containerData.graphicsDriverVersion
        PrefManager.graphicsDriverConfig = containerData.graphicsDriverConfig
        PrefManager.rendererPresentMode = containerData.rendererPresentMode
        PrefManager.displayRendererMode = containerData.displayRenderer
        PrefManager.sfCompatMode = containerData.sfCompatMode
        PrefManager.dxWrapper = containerData.dxwrapper
        PrefManager.dxWrapperConfig = containerData.dxwrapperConfig
        PrefManager.audioDriver = containerData.audioDriver
        PrefManager.pulseaudioLowLatency = containerData.pulseaudioLowLatency
        PrefManager.winComponents = containerData.wincomponents
        PrefManager.drives = containerData.drives
        PrefManager.execArgs = containerData.execArgs
        PrefManager.launchRealSteam = containerData.launchRealSteam
        PrefManager.launchBionicSteam = containerData.launchBionicSteam
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
        PrefManager.localSavesOnly = containerData.localSavesOnly
        PrefManager.steamOfflineMode = containerData.steamOfflineMode
        PrefManager.epicOfflineMode = containerData.epicOfflineMode
        PrefManager.useLegacyDRM = containerData.useLegacyDRM
        PrefManager.unpackFiles = containerData.unpackFiles
        PrefManager.suspendPolicy = containerData.suspendPolicy
        PrefManager.portraitMode = containerData.portraitMode
        PrefManager.sharpnessEffect = containerData.sharpnessEffect
        PrefManager.sharpnessLevel = containerData.sharpnessLevel
        PrefManager.sharpnessDenoise = containerData.sharpnessDenoise
        // clamp negative (follow-global sentinel) to 0 -- global slot can never follow itself.
        PrefManager.html5RenderScale = containerData.renderScale.coerceAtLeast(0f)
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
        // Read shooter-mode flag from container
        val shooterMode = container.isShooterMode()
        // Read gesture configuration JSON
        val gestureConfig = container.getGestureConfig()
        // Read shooter mode configuration JSON
        val shooterConfig = container.getShooterConfig()
        val externalDisplayMode = container.getExternalDisplayMode()
        val externalDisplaySwap = container.isExternalDisplaySwap()

        return ContainerData(
            name = container.name,
            screenSize = container.screenSize,
            envVars = container.envVars,
            graphicsDriver = container.graphicsDriver,
            graphicsDriverVersion = container.graphicsDriverVersion,
            graphicsDriverConfig = container.graphicsDriverConfig,
            rendererPresentMode = container.rendererPresentMode,
            displayRenderer = container.displayRenderer,
            sfCompatMode = container.sfCompatMode,
            dxwrapper = container.dxWrapper,
            dxwrapperConfig = container.dxWrapperConfig,
            audioDriver = container.audioDriver,
            pulseaudioLowLatency = container.getPulseaudioLowLatency(),
            wincomponents = container.winComponents,
            drives = container.drives,
            execArgs = container.execArgs,
            executablePath = container.executablePath,
            showFPS = false,
            launchRealSteam = container.isLaunchRealSteam,
            launchBionicSteam = container.isLaunchBionicSteam,
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
            localSavesOnly = container.isLocalSavesOnly,
            steamOfflineMode = container.isSteamOfflineMode(),
            epicOfflineMode = container.isEpicOfflineMode(),
            useLegacyDRM = container.isUseLegacyDRM(),
            unpackFiles = container.isUnpackFiles(),
            suspendPolicy = container.suspendPolicy,
            portraitMode = container.isPortraitMode,
            enableXInput = enableX,
            enableDInput = enableD,
            dinputMapperType = mapperType,
            disableMouseInput = disableMouse,
            touchscreenMode = touchscreenMode,
            shooterMode = shooterMode,
            gestureConfig = gestureConfig,
            shooterConfig = shooterConfig,
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
            // LSFG Vulkan frame generation
            lsfgEnabled = container.getExtra(LsfgVkManager.EXTRA_ARMED, "false").toBoolean(),
            bionicFgEnabled = container.getExtra(BionicFgManager.EXTRA_ARMED, "false").toBoolean(),
        )
    }

    fun applyToContainer(context: Context, appId: String, containerData: ContainerData) {
        val container = getContainer(context, appId)
        applyToContainer(context, container, containerData)
    }

    // single save choke point. variant=html5 runs Html5OptInService.optIn
    // BEFORE applyToContainer so the Container flip only happens when fingerprint matches.
    // without this gate, saveContainerConfig overrides bypass optIn and silently flip
    // runtime=webview for games that have no html5 payload (#2.1 hylics regression).
    // returns true when Container mutated (apply ran); false when variant=html5 was rejected
    // by the opt-in gate -- in that case this helper emits the snackbar itself so callers
    // just check the Boolean and decide whether to close the dialog.
    suspend fun applyToContainerGated(
        context: Context,
        appId: String,
        containerData: ContainerData,
    ): Boolean {
        // variant-flip mirror-sync. compute pre-flip direction BEFORE any
        // Container mutation so we capture the TRUE prior runtime (applyToContainer overwrites
        // containerVariant mid-call). only runs when variant actually flips webview↔wine; no-op
        // for non-html5 variant changes, first-time creation, or same-variant saves.
        
        // using EntryPointAccessors to reach the Hilt singleton from this object utility --
        // ContainerUtils can't @Inject its own deps. sync failures surface via the service's
        // own snackbar path so we swallow here and proceed with the flip regardless.
        val flipDirection: Html5SaveSyncService.FlipDirection? = computeHtml5FlipDirection(context, appId, containerData)
        if (flipDirection != null) {
            val svc = runCatching {
                EntryPointAccessors
                    .fromApplication(context.applicationContext, Html5SaveSyncEntryPoint::class.java)
                    .html5SaveSyncService()
            }.onFailure { Timber.tag("ContainerUtils").w(it, "Html5SaveSyncService EntryPoint lookup failed") }
                .getOrNull()
            svc?.mirrorOnFlip(appId, flipDirection)
            // a webview↔wine flip changes Container.runtime, which LibraryViewModel caches keyed
            // by appId. that cache invalidates only on LibraryInstallStatusChanged, so emit it here
            // or the library RuntimeBadge shows the pre-flip runtime until an unrelated install event.
            appId.toIntOrNull()?.let {
                app.gamenative.PluviaApp.events.emit(
                    app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(
                        it,
                        GameSource.fromContainerId(appId) ?: GameSource.STEAM,
                    ),
                )
            }
        }

        if (containerData.containerVariant.equals(Container.CONTAINER_VARIANT_HTML5, ignoreCase = true)) {
            // optIn is FIRST-TIME-ONLY. it constructs a fresh WebViewContainer
            // (defaults) and overwrites disk -- wiping gestureConfig, controlsProfileId, overlay*,
            // language. those fields flow through dedicated paths
            // (Html5ControllerTabContent / WebViewScreen QuickMenu) and the outer Save has no
            // dirty-tracking for them, so re-running optIn on subsequent saves clobbers user
            // edits. only fingerprint when JSON doesn't exist yet; on re-saves go straight to
            // applyToContainer + persistHtml5ContainerOverrides (which propagates inputMap, the
            // sole ContainerData→WebViewContainer field).
            val alreadyOptedIn = loadWebViewContainerForAppId(appId) != null
            if (!alreadyOptedIn) {
                return when (val r = Html5OptInService.optIn(context, appId, containerData)) {
                    Html5OptInService.Result.Matched -> {
                        withContext(Dispatchers.IO) {
                            applyToContainer(context, appId, containerData)
                            persistHtml5ContainerOverrides(appId, containerData)
                        }
                        true
                    }
                    is Html5OptInService.Result.NoMatch -> {
                        SnackbarManager.show(r.message)
                        false
                    }
                    is Html5OptInService.Result.CannotResolveInstallPath -> {
                        SnackbarManager.show(r.message)
                        false
                    }
                    is Html5OptInService.Result.PackLoadFailure -> {
                        SnackbarManager.show(
                            "HTML5 pack '${r.engineId}' failed to load — see logs",
                        )
                        false
                    }
                }
            }
            withContext(Dispatchers.IO) {
                applyToContainer(context, appId, containerData)
                persistHtml5ContainerOverrides(appId, containerData)
            }
            return true
        }
        withContext(Dispatchers.IO) { applyToContainer(context, appId, containerData) }
        return true
    }

    // computes the save-sync mirror direction for a variant flip. null when no flip is occurring
    // (either container doesn't exist yet, variant is unchanged, or the flip is wine↔wine-variant
    // and has no html5-side data to move). keeps the gated method lean.
    
    // WINE_TO_WEBVIEW additionally requires an existing WebViewContainer JSON. on first-time
    // opt-in there's no engineProfile yet and resolveSetup throws PathMissing ("no pack profile")
    // before optIn even runs -- fires "Save path not found" RIGHT BEFORE the game is detected as
    // html5. the wine→webview population happens at WebViewScreen launch via syncInbound anyway.
    private fun computeHtml5FlipDirection(
        context: Context,
        appId: String,
        containerData: ContainerData,
    ): Html5SaveSyncService.FlipDirection? {
        if (!hasContainer(context, appId)) return null
        val currentRuntime = runCatching { getContainer(context, appId).runtime }.getOrNull()
            ?: return null
        val newIsHtml5 = containerData.containerVariant.equals(Container.CONTAINER_VARIANT_HTML5, ignoreCase = true)
        val currentIsWebview = currentRuntime == Container.RUNTIME_WEBVIEW
        return when {
            !currentIsWebview && newIsHtml5 -> {
                if (loadWebViewContainerForAppId(appId) == null) null
                else Html5SaveSyncService.FlipDirection.WINE_TO_WEBVIEW
            }
            currentIsWebview && !newIsHtml5 -> Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE
            else -> null
        }
    }

    // Hilt accessor for Html5SaveSyncService -- consumed inline inside applyToContainerGated.
    // object-utility pattern can't take @Inject deps, so we reach via EntryPointAccessors.
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Html5SaveSyncEntryPoint {
        fun html5SaveSyncService(): Html5SaveSyncService
    }

    // round-trip dialog-edited fields into WebViewContainer.json. WebViewScreen reads inputMap
    // from WebViewContainer at launch, so GeneralTab edits to inputMap need this propagation.
    // NOTE: suspendPolicy is INTENTIONALLY NOT here -- it's a single per-container preference
    // owned by the wine Container (Container.java:165, getSuspendPolicy/setSuspendPolicy).
    // both wine and html5 runtimes read it from the same place; html5 fetches via
    // ContainerUtils.getContainer(context, appId).suspendPolicy at launch.
    // overlayOpacity / overlayVisible / controlsProfileId / gestureConfig flow
    // through dedicated paths (QuickMenu and Html5ControllerTabContent) and persist directly.
    // NO-OP when the slug can't be resolved (first-opt-in race: optIn handles initial write,
    // this runs AFTER optIn so the file exists if optIn succeeded).
    // runs on Dispatchers.IO -- caller (applyToContainerGated) already switches context.
    @androidx.annotation.VisibleForTesting
    // the html5-eligible appId shapes: CUSTOM_GAME_<int> and STEAM_<int> (matches Html5OptInService.optIn).
    private fun html5IdPart(appId: String): Int? = when {
        GameSource.CUSTOM_GAME.matches(appId) -> GameSource.CUSTOM_GAME.idOf(appId).toIntOrNull()
        GameSource.STEAM.matches(appId) -> GameSource.STEAM.idOf(appId).toIntOrNull()
        else -> null
    }

    internal fun persistHtml5ContainerOverrides(appId: String, containerData: ContainerData) {
        val root = Html5OptInService.resolveFingerprintPath(appId) ?: run {
            Timber.tag("ContainerUtils").w("no fingerprint path for $appId — html5 overrides dropped")
            return
        }
        val idPart = html5IdPart(appId) ?: run {
            Timber.tag("ContainerUtils").w("unrecognized appId prefix for $appId — html5 overrides dropped")
            return
        }
        val slug = Html5SlugUtil.slug(root.name, idPart)
        val existing = WebViewContainer.load(slug) ?: run {
            Timber.tag("ContainerUtils").w("no WebViewContainer JSON at slug=$slug — html5 overrides dropped")
            return
        }
        val updated = existing.copy(
            inputMap = containerData.inputMap,
            renderScale = containerData.renderScale,
        )
        if (updated == existing) return // no-op, avoid spurious disk writes
        WebViewContainer.save(slug, updated)
        Timber.tag("ContainerUtils").i(
            "persisted html5 overrides for slug=$slug: inputMap='%s' renderScale=%.2f",
            updated.inputMap,
            updated.renderScale,
        )
    }

    // load + merge html5 sidecar fields into a wine-derived ContainerData. without this,
    // opening the container config dialog for an html5 container shows defaults for fields that
    // live in WebViewContainer.json (inputMap, renderScale) -- and saving without changing them
    // CLOBBERS the persisted values back to defaults. callers: SteamAppScreen + CustomGameAppScreen
    // loadContainerData. no-op for non-html5 containers (sidecar absent).
    fun mergeHtml5SidecarFields(data: ContainerData, appId: String): ContainerData {
        if (!data.containerVariant.equals(Container.CONTAINER_VARIANT_HTML5, ignoreCase = true)) {
            return data
        }
        val sidecar = loadWebViewContainerForAppId(appId) ?: return data
        return data.copy(
            inputMap = sidecar.inputMap,
            renderScale = sidecar.renderScale,
        )
    }

    // shared seam for ContainerConfigTransfer / Reset paths so they can address
    // WebViewContainer.json without reaching into file-private jsonDirSlug. mirrors the
    // resolve dance inside persistHtml5ContainerOverrides but does NOT mutate disk.
    // returns null when appId is non-CUSTOM_GAME_ / non-STEAM_ OR install path missing --
    // callers treat null as "no html5 sidecar; skip html5 work".
    fun webViewContainerSlugForAppId(appId: String): String? {
        val root = Html5OptInService.resolveFingerprintPath(appId) ?: return null
        val idPart = html5IdPart(appId) ?: return null
        return Html5SlugUtil.slug(root.name, idPart)
    }

    fun loadWebViewContainerForAppId(appId: String): WebViewContainer? {
        val slug = webViewContainerSlugForAppId(appId) ?: return null
        return WebViewContainer.load(slug)
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
                "steamOfflineMode" -> value?.let { updatedData.copy(steamOfflineMode = it as? Boolean ?: updatedData.steamOfflineMode) } ?: updatedData
                "epicOfflineMode" -> value?.let { updatedData.copy(epicOfflineMode = it as? Boolean ?: updatedData.epicOfflineMode) } ?: updatedData
                "unpackFiles" -> value?.let { updatedData.copy(unpackFiles = it as? Boolean ?: updatedData.unpackFiles) } ?: updatedData
                "suspendPolicy" -> value?.let { updatedData.copy(suspendPolicy = it as? String ?: updatedData.suspendPolicy) } ?: updatedData
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

        // Variant flip backstop: html5 → wine. Containers born as html5 skip prefix extraction
        // at creation (see createNewContainer + ContainerManager.createContainer). When the user
        // flips variant to a wine variant later, the prefix has to materialize NOW -- the
        // WineRegistryEditor call below expects .wine/user.reg to exist, and downstream wine
        // boot expects windows/, Program Files/, system32/, etc. extract the prefix here using
        // the NEW wineVersion in containerData so the variant flip completes atomically.
        val newVariantIsHtml5 = containerData.containerVariant
            .equals(Container.CONTAINER_VARIANT_HTML5, ignoreCase = true)
        val isFlipHtml5ToWine = container.runtime == Container.RUNTIME_WEBVIEW && !newVariantIsHtml5
        if (isFlipHtml5ToWine) {
            val driveCExists = File(container.rootDir, ".wine/drive_c/windows").isDirectory
            if (!driveCExists) {
                Timber.tag("ContainerUtils").i(
                    "Variant flip html5→wine: extracting prefix for appId=%s wineVersion=%s",
                    container.id,
                    containerData.wineVersion,
                )
                val containerManager = ContainerManager(context)
                val contentsManager = com.winlator.contents.ContentsManager(context)
                val ok = containerManager.extractContainerPatternFile(
                    containerData.wineVersion,
                    contentsManager,
                    container.rootDir,
                    null,
                )
                if (!ok) {
                    Timber.tag("ContainerUtils").e(
                        "Prefix extraction failed for variant flip html5→wine on appId=%s; container will be unbootable in wine until reset",
                        container.id,
                    )
                    // fall through -- caller is committed to the flip via the dialog Save. let
                    // the rest of applyToContainer run so the .container JSON gets the new
                    // variant, and surface the failure as a wine-boot error on next launch.
                }
            }
        }

        // Detect language change before mutating container
        val previousLanguage: String = try {
            container.language
        } catch (e: Exception) {
            container.getExtra("language", "english")
        }
        val previousForceDlc: Boolean = container.isForceDlc
        val previousSteamOfflineMode: Boolean = container.isSteamOfflineMode()

        val previousUnpackFiles: Boolean = container.isUnpackFiles
        val previousLaunchBionicSteam: Boolean = container.isLaunchBionicSteam
        val previousLaunchRealSteam: Boolean = container.isLaunchRealSteam
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
        container.rendererPresentMode = containerData.rendererPresentMode
        container.displayRenderer = containerData.displayRenderer
        container.sfCompatMode = containerData.sfCompatMode
        container.dxWrapper = containerData.dxwrapper
        container.dxWrapperConfig = containerData.dxwrapperConfig
        container.audioDriver = containerData.audioDriver
        container.setPulseaudioLowLatency(containerData.pulseaudioLowLatency)
        container.winComponents = containerData.wincomponents
        container.drives = containerData.drives
        container.execArgs = containerData.execArgs
        if (container.executablePath != containerData.executablePath && container.executablePath != "") {
            container.setNeedsUnpacking(true)
        }
        container.executablePath = containerData.executablePath
        container.isShowFPS = false
        container.isLaunchRealSteam = containerData.launchRealSteam
        container.isLaunchBionicSteam = containerData.launchBionicSteam
        if (previousLaunchBionicSteam != containerData.launchBionicSteam ||
            previousLaunchRealSteam != containerData.launchRealSteam) {
            container.setNeedsUnpacking(true)
        }
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
        container.setShooterMode(containerData.shooterMode)
        container.setGestureConfig(containerData.gestureConfig)
        container.setShooterConfig(containerData.shooterConfig)
        container.setExternalDisplayMode(containerData.externalDisplayMode)
        container.setExternalDisplaySwap(containerData.externalDisplaySwap)
        container.setForceDlc(containerData.forceDlc)
        container.setLocalSavesOnly(containerData.localSavesOnly)
        container.setSteamOfflineMode(containerData.steamOfflineMode)
        container.setEpicOfflineMode(containerData.epicOfflineMode)
        container.setUseLegacyDRM(containerData.useLegacyDRM)
        container.setUnpackFiles(containerData.unpackFiles)
        container.setSuspendPolicy(containerData.suspendPolicy)
        container.setPortraitMode(containerData.portraitMode)
        if (previousUnpackFiles != containerData.unpackFiles && containerData.unpackFiles) {
            container.setNeedsUnpacking(true)
        }
        container.putExtra("sharpnessEffect", containerData.sharpnessEffect)
        container.putExtra("sharpnessLevel", containerData.sharpnessLevel.toString())
        container.putExtra("sharpnessDenoise", containerData.sharpnessDenoise.toString())
        // LSFG Vulkan frame generation
        container.putExtra(LsfgVkManager.EXTRA_ARMED, containerData.lsfgEnabled.toString())
        container.putExtra(BionicFgManager.EXTRA_ARMED, containerData.bionicFgEnabled.toString())
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
        if (previousForceDlc != containerData.forceDlc) {
            val steamAppId = extractGameIdFromContainerId(container.id)
            val appDirPath = SteamService.getAppDirPath(steamAppId)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
            Timber.i("forceDlc changed from '$previousForceDlc' to '${containerData.forceDlc}'. Cleared STEAM_DLL_REPLACED marker for container ${container.id}.")
        }
        if (previousSteamOfflineMode != containerData.steamOfflineMode) {
            val steamAppId = extractGameIdFromContainerId(container.id)
            val appDirPath = SteamService.getAppDirPath(steamAppId)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
            Timber.i("steamOfflineMode changed from '$previousSteamOfflineMode' to '${containerData.steamOfflineMode}'. Cleared STEAM_DLL_REPLACED marker for container ${container.id}.")
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

    // single source of truth for "what runtime is this appId on?". used by
    // LibraryViewModel build pipeline + every per-source AppScreen's GameDisplayInfo.
    // not-yet-installed entries default to RUNTIME_WINE (matches Container.runtime field default).
    //
    // runCatching tolerates a TOCTOU between hasContainer and getContainer's internal recheck:
    // each call constructs a fresh ContainerManager that rescans the filesystem, so a
    // concurrent uninstall can flip the answer between the two checks. the missing-container
    // case is semantically equivalent to "not installed" → fall back to RUNTIME_WINE.
    fun resolveRuntime(context: Context, appId: String): String =
        runCatching { getContainer(context, appId).runtime }
            .getOrDefault(Container.RUNTIME_WINE)

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

            GameSource.AMAZON -> {
                // For Amazon games, map the specific game directory to A: drive
                val appIdInt = runCatching { extractGameIdFromContainerId(appId) }.getOrNull()
                val installPath = if (appIdInt != null) {
                    AmazonService.getInstallPathByAppId(appIdInt)
                } else null

                if (installPath != null && installPath.isNotEmpty()) {
                    val drive: Char = if (defaultDrives.contains("A:")) {
                        Container.getNextAvailableDriveLetter(defaultDrives)
                    } else {
                        'A'
                    }
                    "$defaultDrives$drive:$installPath"
                } else {
                    Timber.w("Could not find Amazon game install path for appId: $appIdInt, using default drives")
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

        // gate prefix extraction at container creation. Html5OptInService.optIn writes the
        // WebViewContainer sidecar JSON BEFORE the wine Container is created in the dialog Save
        // flow; sidecar presence at this point => caller intends an html5 container, so we
        // plumb variant=html5 + runtime=webview into the data and ContainerManager.createContainer
        // skips the ~60MB prefixPack extraction (windows/, Program Files/, system.reg) that
        // html5 never uses. callers without a sidecar (Wine-runtime path, or html5 containers
        // created BEFORE this gate landed) hit the standard prefix-extract branch.
        if (loadWebViewContainerForAppId(appId) != null) {
            data.put("containerVariant", Container.CONTAINER_VARIANT_HTML5)
            data.put("runtime", Container.RUNTIME_WEBVIEW)
        }

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

        // Check for cached best config (store-backed games only, only if no custom config provided)
        var bestConfigMap: Map<String, Any?>? = null

        if (supportsKnownConfigAutoApply(gameSource) && customConfig == null && PrefManager.autoApplyKnownConfig) {
            try {
                val gameName = resolveGameName(appId)
                if (gameName != "Unknown" && gameName.isNotBlank()) {
                    val gpuName = GPUInformation.getRenderer(context)

                    // Check cache first (synchronous, fast)
                    // If not cached, make request on background thread (not UI thread)
                    runBlocking(Dispatchers.IO) {
                        try {
                            val bestConfig = BestConfigService.fetchBestConfig(
                                gameName = gameName,
                                gpuName = gpuName,
                                gameStore = gameSource.name,
                            )
                            if (bestConfig != null && bestConfig.matchType != "no_match") {
                                Timber.i("Applying best config for $gameName (matchType: ${bestConfig.matchType})")
                                val parsedConfig = BestConfigService.parseConfigToContainerData(
                                    context,
                                    bestConfig.bestConfig,
                                    bestConfig.matchType,
                                    true,
                                    bestConfig.matchedStore.equals(gameSource.name, ignoreCase = true),
                                    matchedGpu = bestConfig.matchedGpu,
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
                rendererPresentMode = PrefManager.rendererPresentMode,
                displayRenderer = PrefManager.displayRendererMode,
                sfCompatMode = PrefManager.sfCompatMode,
                dxwrapper = initialDxWrapper,
                dxwrapperConfig = PrefManager.dxWrapperConfig,
                audioDriver = PrefManager.audioDriver,
                pulseaudioLowLatency = PrefManager.pulseaudioLowLatency,
                wincomponents = PrefManager.winComponents,
                drives = drives,
                execArgs = PrefManager.execArgs,
                showFPS = false,
                launchRealSteam = PrefManager.launchRealSteam,
                launchBionicSteam = PrefManager.launchBionicSteam,
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
                steamOfflineMode = PrefManager.steamOfflineMode,
                epicOfflineMode = PrefManager.epicOfflineMode,
                useLegacyDRM = PrefManager.useLegacyDRM,
                unpackFiles = PrefManager.unpackFiles,
                suspendPolicy = PrefManager.suspendPolicy,
                portraitMode = PrefManager.portraitMode,
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

        if (BuildConfig.XR_BUILD) {
            val kvs = KeyValueSet(containerData.graphicsDriverConfig)
            kvs.put("adrenotoolsTurnip", "0")
            containerData = containerData.copy(graphicsDriverConfig = kvs.toString())
        }

        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true) && GPUInformation.isAdreno740(context)) {
            val ev = EnvVars(containerData.envVars)
            if (!ev.has("FD_DEV_FEATURES")) {
                ev.put("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1")
                containerData = containerData.copy(envVars = ev.toString())
            }
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

            GameSource.AMAZON -> {
                val appIdInt = runCatching { extractGameIdFromContainerId(appId) }.getOrNull()
                if (appIdInt != null) AmazonService.getInstallPathByAppId(appIdInt) else null
            }
        }

        val resolvedGameFolderPath = if (gameSource == GameSource.CUSTOM_GAME) {
            gameFolderPath
        } else {
            StorageUtils.resolveLegacyGameDir(gameFolderPath)
        }

        if (resolvedGameFolderPath != null && resolvedGameFolderPath != gameFolderPath) {
            when (gameSource) {
                GameSource.GOG ->
                    GOGService.updateInstallPath(extractGameIdFromContainerId(appId).toString(), resolvedGameFolderPath)
                GameSource.EPIC ->
                    EpicService.updateInstallPath(extractGameIdFromContainerId(appId), resolvedGameFolderPath)
                GameSource.AMAZON ->
                    runCatching { extractGameIdFromContainerId(appId) }.getOrNull()
                        ?.let { AmazonService.updateInstallPath(it, resolvedGameFolderPath) }
                else -> {}
            }
        }

        if (resolvedGameFolderPath != null) {
            // Check if A: drive is already mapped to the correct path
            var hasCorrectADrive = false
            for (drive in Container.drivesIterator(container.drives)) {
                if (drive[0] == "A" && drive[1] == resolvedGameFolderPath) {
                    hasCorrectADrive = true
                    break
                }
            }

            // If A: drive is not mapped correctly, update it
            if (!hasCorrectADrive) {
                val currentDrives = container.drives
                // Rebuild drives string, excluding existing A: drive and adding new one
                val drivesBuilder = StringBuilder()
                drivesBuilder.append("A:$resolvedGameFolderPath")

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
        Timber.i("[ContainerDeletion] Attempting to delete container for appId=$appId")
        val manager = ContainerManager(context)
        val hasContainer = manager.hasContainer(appId)
        Timber.i("[ContainerDeletion] hasContainer($appId) = $hasContainer")
        if (hasContainer) {
            // Remove the container directory asynchronously
            manager.removeContainerAsync(
                manager.getContainerById(appId),
            ) {
                Timber.i("[ContainerDeletion] Successfully deleted container for appId=$appId")
            }
        } else {
            Timber.w("[ContainerDeletion] No container found for appId=$appId — deletion aborted.")

            // Containers successfully parsed by ContainerManager (config file was readable)
            val loadedIds = manager.containers.map { it.id }
            Timber.w("[ContainerDeletion] Loaded containers (${loadedIds.size}): $loadedIds")

            // Raw filesystem scan — catches directories whose config file was empty/corrupt and
            // were silently skipped by ContainerManager. These are potential orphans.
            // Directory layout: <filesDir>/imagefs/home/xuser-<containerId>
            val homeDir = java.io.File(context.filesDir, "imagefs/home")
            val prefix = "${com.winlator.xenvironment.ImageFs.USER}-"
            val rawIds = homeDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith(prefix) }
                ?.map { it.name.removePrefix(prefix) }
                ?: emptyList()
            val unloadedIds = rawIds - loadedIds.toSet()
            Timber.w("[ContainerDeletion] Raw filesystem dirs (${rawIds.size}): $rawIds")
            if (unloadedIds.isNotEmpty()) {
                Timber.w("[ContainerDeletion] Dirs present on disk but NOT loaded by ContainerManager (corrupt/empty config): $unloadedIds")
            }
        }

        // origin-scoped storage cleanup. all html5 containers share the Default profile dir;
        // chromium partitions LS / IDB by origin, so wiping origin = https://game-<appId>
        // removes that container's data without touching siblings.
        runCatching {
            deleteHtml5OriginStorage(context, appId)
        }.onFailure {
            Timber.tag("ContainerUtils").w(it, "html5 origin storage cleanup failed appId=%s", appId)
        }

        // clean up the per-container ControlsProfile owned by this
        // html5 container. without this, profiles accumulate in InputControlsManager's
        // global pool indefinitely (picker UI clutter, orphan files on disk).
        // safety: skip if any other html5 container references the same profileId -- covers
        // the lazy-fork migration window where two containers may briefly share an id.
        runCatching {
            deleteHtml5ControlsProfileIfOrphan(context, appId)
        }.onFailure {
            Timber.tag("ContainerUtils").w(it, "html5 controls profile cleanup failed appId=%s", appId)
        }

        // delete the html5-containers/<slug>/ JSON dir last -- has to run AFTER
        // deleteHtml5ControlsProfileIfOrphan (which scans config.json files to resolve the
        // controlsProfileId). after this the slug is gone and any further scan won't see it.
        runCatching {
            deleteHtml5JsonDir(context, appId)
        }.onFailure {
            Timber.tag("ContainerUtils").w(it, "html5 json dir cleanup failed appId=%s", appId)
        }

        // per-container diagnostic logs. written to BOTH <filesDir>/html5-logs/<appId>
        // (Html5DiagnosticBridge save-trace.jsonl) and <externalAppDir>/html5-logs/<appId>
        // (SteamworksJsBridge steamworks.jsonl). neither is origin-scoped, so the storage
        // cleanup above misses them -- delete both so uninstall leaves nothing behind.
        runCatching {
            File(context.filesDir, "html5-logs/$appId").deleteRecursively()
            File(DownloadService.baseExternalAppDirPath, "html5-logs/$appId")
                .deleteRecursively()
        }.onFailure {
            Timber.tag("ContainerUtils").w(it, "html5 logs cleanup failed appId=%s", appId)
        }
    }

    // origin-scoped LS + IDB scrub for an html5 container. shared Default profile means
    // sibling containers' data lives next door -- match by chromium origin filename
    // (https_game-<id>_0) so we only touch THIS container's bytes.
    //
    // WebStorage.deleteOrigin alone does NOT reliably evict the LS leveldb keys for
    // the origin (observed deleted=0 even with substantial state present), so we follow
    // up with an explicit iq80 purge of the shared LS leveldb. best-effort: if chromium
    // currently holds the LS dir's LOCK, the purge throws and we surface a warning.
    internal fun deleteHtml5OriginStorage(context: Context, appId: String) {
        val origin = app.gamenative.html5.host.WebViewOrigin.originUrl(appId)
        runCatching {
            android.webkit.WebStorage.getInstance().deleteOrigin(origin)
        }.onFailure {
            Timber.tag("ContainerUtils").w(it, "WebStorage.deleteOrigin failed origin=%s", origin)
        }
        val defaultDir = File(context.dataDir, "app_webview/Default")
        val lsDir = File(defaultDir, "Local Storage/leveldb")
        runCatching {
            app.gamenative.html5.savesync.LevelDbRewriter.purgeLsOrigin(lsDir, origin)
        }.onFailure {
            Timber.tag("ContainerUtils").w(it, "LS leveldb purge failed origin=%s", origin)
        }
        val idbDir = File(defaultDir, "IndexedDB")
        val prefix = app.gamenative.html5.host.WebViewOrigin.levelDbPrefix(appId)
        for (suffix in listOf("indexeddb.leveldb", "indexeddb.blob")) {
            val target = File(idbDir, "$prefix.$suffix")
            runCatching {
                if (target.exists()) target.deleteRecursively()
            }.onFailure {
                Timber.tag("ContainerUtils").w(it, "IDB delete failed path=%s", target)
            }
        }
        // OPFS (origin private file system). chromium stores it under File System/<bucket>/,
        // keyed to the origin via the shared File System/Origins index -- NOT covered by
        // WebStorage.deleteOrigin or the LS/IDB deletes above. worker-shim titles (pack:c3)
        // keep their live saves here; leaving it behind means a reinstall reads stale OPFS
        // instead of the cloud-restored copy. leave the Origins index entry dangling -- chromium
        // recreates the bucket on next access (verified on device).
        runCatching {
            val fsDir = File(defaultDir, "File System")
            val bucket = app.gamenative.html5.savesync.LevelDbRewriter
                .resolveOpfsBucketDir(File(fsDir, "Origins"), origin)
            if (bucket != null) {
                val bucketDir = File(fsDir, bucket)
                if (bucketDir.isDirectory && bucketDir.deleteRecursively()) {
                    Timber.tag("ContainerUtils").i("deleted OPFS bucket %s for origin=%s", bucket, origin)
                }
            }
        }.onFailure {
            Timber.tag("ContainerUtils").w(it, "OPFS bucket cleanup failed origin=%s", origin)
        }
        // inbound-gate marker. survives uninstall otherwise; a reinstall's cloud-restore writes
        // backdated mtimes that read as "older" than the stale marker, so launch-sync skips and
        // the stale OPFS wins. clearing it forces the next launch to re-run inbound (OVERWRITE).
        runCatching {
            app.gamenative.html5.savesync.Html5SaveSyncService.clearSyncState(context, appId)
        }.onFailure {
            Timber.tag("ContainerUtils").w(it, "sync-state marker cleanup failed appId=%s", appId)
        }
    }

    // deletes the html5-containers/<slug>/ JSON dir for this appId on container uninstall.
    // post-uninstall the original install folder is gone, so we can't reconstruct the slug
    // via Html5OptInService.slugFor(). instead: scan html5-containers/, parse each
    // config.json, match config.id == appId, delete the matching dir.
    //
    // wine-only containers: no config.json scan match → no-op. leaked empty dirs (from the
    // pre-fix configFile mkdir bug): not deleted here either (can't be attributed to any
    // appId) -- user can clean those manually via adb if desired.
    internal fun deleteHtml5JsonDir(context: Context, appId: String) {
        val rootDir = File(DownloadService.baseExternalAppDirPath, "html5-containers")
        if (!rootDir.isDirectory) return
        val dirs = rootDir.listFiles { f -> f.isDirectory } ?: return
        for (dir in dirs) {
            val sibling = WebViewContainer.load(dir.name) ?: continue
            if (sibling.id != appId) continue
            val deleted = dir.deleteRecursively()
            Timber.tag("ContainerUtils").i(
                "deleteHtml5JsonDir: appId=%s slug=%s deleted=%s",
                appId, dir.name, deleted,
            )
            // drop cached appId→slug entry so the next isHtml5App probe re-walks and lands
            // on a fresh negative hit (SENTINEL_NONE).
            app.gamenative.html5.host.WebViewScreenViewModel.invalidateSlugCache(appId)
            return // slug → appId is 1:1; first match is the only match
        }
    }

    // helper. resolves THIS container's slug + profileId by scanning
    // html5-containers/<slug>/config.json files, checks siblings for shared id, and removes
    // the profile via InputControlsManager IF unshared. logs WHY-not when shared so a future
    // debugger can trace back to the lazy-fork migration window.

    // wine containers: scan returns null (no matching slug), no-op. runtime-agnostic -- single
    // hook regardless of html5 vs wine.
    // bumped from `private` to `internal` so ContainerStorageManager
    // (Settings → Storage → Remove + uninstallGameAndContainer) can fire the same cleanup.
    // that path deletes the dir directly via FileUtils.delete and would otherwise bypass
    // ContainerUtils.deleteContainer entirely, leaving orphan ControlsProfiles for every
    // Steam/GOG/Epic/Amazon library uninstall routed through the storage manager UI.
    internal fun deleteHtml5ControlsProfileIfOrphan(context: Context, appId: String) {
        val rootDir = File(DownloadService.baseExternalAppDirPath, "html5-containers")
        if (!rootDir.exists()) return
        val dirs = rootDir.listFiles { f -> f.isDirectory } ?: return

        // resolve THIS container's profileId + scan siblings in one pass.
        var thisProfileId: Long = 0L
        val siblingProfileIds = mutableListOf<Long>()
        var foundSelf = false
        for (dir in dirs) {
            val sibling = WebViewContainer.load(dir.name) ?: continue
            if (sibling.id == appId) {
                thisProfileId = sibling.controlsProfileId
                foundSelf = true
            } else if (sibling.controlsProfileId > 0L) {
                siblingProfileIds.add(sibling.controlsProfileId)
            }
        }
        if (!foundSelf) return // wine container OR html5 JSON dir already gone -- no-op
        if (thisProfileId <= 0L) {
            Timber.tag("ContainerUtils").d(
                "html5 container delete: no controlsProfileId for appId=%s (bootstrap never ran)",
                appId,
            )
            return
        }
        if (thisProfileId in siblingProfileIds) {
            Timber.tag("ContainerUtils").i(
                "html5 container delete: skipping profile cleanup for appId=%s id=%d — sibling references same id (lazy-fork window)",
                appId, thisProfileId,
            )
            return
        }
        val manager = InputControlsManager(context)
        val profile = manager.getProfiles(false).firstOrNull { it.id.toLong() == thisProfileId }
        if (profile == null) {
            Timber.tag("ContainerUtils").d(
                "html5 container delete: profile id=%d already gone for appId=%s",
                thisProfileId, appId,
            )
            return
        }
        manager.removeProfile(profile)
        Timber.tag("ContainerUtils").d(
            "html5 container delete: removing orphan profile id=%d for deleted container=%s",
            thisProfileId, appId,
        )
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
    fun extractGameSourceFromContainerId(containerId: String): GameSource =
        GameSource.fromContainerId(containerId) ?: GameSource.STEAM // default fallback

    fun isLocalSavesOnly(context: Context, appId: String): Boolean {
        if (!hasContainer(context, appId)) return false
        val container = getContainer(context, appId)
        return container.isLocalSavesOnly
    }

    fun supportsKnownConfigAutoApply(gameSource: GameSource): Boolean = when (gameSource) {
        GameSource.STEAM,
        GameSource.GOG,
        GameSource.EPIC,
        GameSource.AMAZON,
        -> true

        GameSource.CUSTOM_GAME,
        -> false
    }

    /**
     * Resolves the display name for a game from its container ID,
     * looking up the appropriate store service.
     */
    fun resolveGameName(containerId: String): String {
        val gameSource = extractGameSourceFromContainerId(containerId)
        val gameId = extractGameIdFromContainerId(containerId)
        return when (gameSource) {
            GameSource.STEAM -> SteamService.getAppInfoOf(gameId)?.name
            GameSource.GOG -> GOGService.getGOGGameOf(gameId.toString())?.title
            GameSource.EPIC -> EpicService.getEpicGameOf(gameId)?.title
            GameSource.AMAZON -> AmazonService.getAmazonGameByAppId(gameId)?.title
            GameSource.CUSTOM_GAME -> {
                val customAppId = "${GameSource.CUSTOM_GAME.name}_$gameId"
                CustomGameScanner.getFolderPathFromAppId(customAppId)
                    ?.let { File(it).name }
            }
        } ?: "Unknown"
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

            // Recursively scan for .exe files using listFiles with depth limit.
            // Symlinked directories are skipped to avoid cycles (e.g. GOG ISI rootdir -> game root).
            fun scanRecursive(dir: File, baseDir: File, depth: Int = 0, maxDepth: Int = 10) {
                if (depth > maxDepth) return

                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        if (FileUtils.isSymlink(file)) return@forEach
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
        val baseName = fileName.removeSuffix(".exe")
        val strongPrefixes = listOf(
            "unins",
            "uninstall",
            "setup",
            "install",
            "redist",
            "vcredist",
            "vc_redist",
            "dxsetup",
            "directx",
            "crashhandler",
            "crashreporter",
        )

        if (strongPrefixes.any { baseName.startsWith(it) }) {
            return true
        }

        val denylistTokens = setOf(
            "unins",
            "uninstall",
            "setup",
            "installer",
            "redist",
            "vcredist",
            "directx",
            "dxsetup",
            "crashhandler",
            "crashreporter",
        )
        val tokens = baseName.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        return tokens.any { it in denylistTokens }
    }
}
