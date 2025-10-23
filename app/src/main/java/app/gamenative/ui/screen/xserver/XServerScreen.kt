package app.gamenative.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.LaunchInfo
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.ui.data.XServerState
import app.gamenative.utils.ContainerUtils
import com.posthog.PostHog
import com.winlator.alsaserver.ALSAClient
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contentdialog.NavigationDialog
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentsManager
import com.winlator.core.AppUtils
import com.winlator.core.Callback
import com.winlator.core.DXVKHelper
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
import com.winlator.core.GPUHelper
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.OnExtractFileListener
import com.winlator.core.ProcessHelper
import com.winlator.core.TarCompressorUtils
import com.winlator.core.Win32AppWorkarounds
import com.winlator.core.WineInfo
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineStartMenuCreator
import com.winlator.core.WineThemeManager
import com.winlator.core.WineUtils
import com.winlator.core.envvars.EnvVars
import com.winlator.fexcore.FEXCoreManager
import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.inputcontrols.TouchMouse
import com.winlator.widget.FrameRating
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerView
import com.winlator.winhandler.WinHandler
import com.winlator.winhandler.WinHandler.PreferredInputApi
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.ALSAServerComponent
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import com.winlator.xenvironment.components.GlibcProgramLauncherComponent
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent
import com.winlator.xenvironment.components.PulseAudioComponent
import com.winlator.xenvironment.components.SteamClientComponent
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.VirGLRendererComponent
import com.winlator.xenvironment.components.VortekRendererComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.Keyboard
import com.winlator.xserver.Property
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Arrays
import java.util.Locale
import kotlin.io.path.name
import com.winlator.PrefManager as WinlatorPrefManager

// TODO logs in composables are 'unstable' which can cause recomposition (performance issues)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun XServerScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    appId: String,
    bootToContainer: Boolean,
    registerBackAction: ( ( ) -> Unit ) -> Unit,
    navigateBack: () -> Unit,
    onExit: () -> Unit,
    onWindowMapped: ((Context, Window) -> Unit)? = null,
    onWindowUnmapped: ((Window) -> Unit)? = null,
    onGameLaunchError: ((String) -> Unit)? = null,
) {
    Timber.i("Starting up XServerScreen")
    val context = LocalContext.current
    val view = LocalView.current
    val imm = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    // PluviaApp.events.emit(AndroidEvent.SetAppBarVisibility(false))
    PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(false))
    PluviaApp.events.emit(
        AndroidEvent.SetAllowedOrientation(PrefManager.allowedOrientation),
    )

    // seems to be used to indicate when a custom wine is being installed (intent extra "generate_wineprefix")
    // val generateWinePrefix = false
    var firstTimeBoot = false
    var needsUnpacking = false
    var containerVariantChanged = false
    var frameRating by remember { mutableStateOf<FrameRating?>(null) }
    var frameRatingWindowId = -1
    var taskAffinityMask = 0
    var taskAffinityMaskWoW64 = 0

    val xServerState = rememberSaveable(stateSaver = XServerState.Saver) {
        if (ContainerUtils.hasContainer(context, appId)) {
            val container = ContainerUtils.getContainer(context, appId)
            // Emulation wiring moved to InputControlsView init block
            mutableStateOf(
                XServerState(
                    graphicsDriver = container.graphicsDriver,
                    graphicsDriverVersion = container.graphicsDriverVersion,
                    audioDriver = container.audioDriver,
                    dxwrapper = container.dxWrapper,
                    dxwrapperConfig = DXVKHelper.parseConfig(container.dxWrapperConfig),
                    screenSize = container.screenSize,
                ),
            )
        } else {
            mutableStateOf(XServerState())
        }
    }

    // val xServer by remember {
    //     val result = mutableStateOf(XServer(ScreenInfo(xServerState.value.screenSize)))
    //     Log.d("XServerScreen", "Remembering xServer as $result")
    //     result
    // }
    // var xEnvironment: XEnvironment? by remember {
    //     val result = mutableStateOf<XEnvironment?>(null)
    //     Log.d("XServerScreen", "Remembering xEnvironment as $result")
    //     result
    // }
    var touchMouse by remember {
        val result = mutableStateOf<TouchMouse?>(null)
        Timber.i("Remembering touchMouse as $result")
        result
    }
    var keyboard by remember { mutableStateOf<Keyboard?>(null) }
    // var pointerEventListener by remember { mutableStateOf<Callback<MotionEvent>?>(null) }

    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
    val appLaunchInfo = SteamService.getAppInfoOf(gameId)?.let { appInfo ->
        SteamService.getWindowsLaunchInfos(gameId).firstOrNull()
    }

    var currentAppInfo = SteamService.getAppInfoOf(gameId)

    var xServerView: XServerView? by remember {
        val result = mutableStateOf<XServerView?>(null)
        Timber.i("Remembering xServerView as $result")
        result
    }

    var win32AppWorkarounds: Win32AppWorkarounds? by remember { mutableStateOf(null) }

    var isKeyboardVisible = false
    var areControlsVisible = false

    val emulateKeyboardMouse = ContainerUtils.getContainer(context, appId).isEmulateKeyboardMouse()

    val gameBack: () -> Unit = gameBack@{
        val imeVisible = ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

        if (imeVisible) {
            PostHog.capture(event = "onscreen_keyboard_disabled")
            view.post {
                if (Build.VERSION.SDK_INT >= 30) {
                    view.windowInsetsController?.hide(WindowInsets.Type.ime())
                } else {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    if (view.windowToken != null) imm.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
            return@gameBack
        }

        Timber.i("BackHandler")
        NavigationDialog(
            context,
            object : NavigationDialog.NavigationListener {
                override fun onNavigationItemSelected(itemId: Int) {
                    when (itemId) {
                        NavigationDialog.ACTION_KEYBOARD -> {
                            val anchor = view // use the same composable root view
                            val c = if (Build.VERSION.SDK_INT >= 30)
                                anchor.windowInsetsController else null

                            anchor.post {
                                if (anchor.windowToken == null) return@post
                                val show = {
                                    PostHog.capture(event = "onscreen_keyboard_enabled")
                                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                                }
                                if (Build.VERSION.SDK_INT > 29 && c != null) {
                                    anchor.postDelayed({ show() }, 500)  // Pixel/Android-12+ quirk
                                } else {
                                    show()
                                }
                            }
                        }

                        NavigationDialog.ACTION_INPUT_CONTROLS -> {
                            if (areControlsVisible){
                                PostHog.capture(event = "onscreen_controller_disabled")
                                hideInputControls();
                            } else {
                                PostHog.capture(event = "onscreen_controller_enabled")
                                val profiles = PluviaApp.inputControlsManager?.getProfiles(false) ?: listOf()
                                if (profiles.isNotEmpty()) {
                                    val container = ContainerUtils.getContainer(context, appId)
                                    val targetProfile = if (container.isEmulateKeyboardMouse()) {
                                        val profileName = container.id.toString()
                                        profiles.firstOrNull { it.name == profileName }
                                            ?: ContainerUtils.generateOrUpdateEmulationProfile(context, container)
                                    } else {
                                        profiles[2]
                                    }
                                    showInputControls(targetProfile, xServerView!!.getxServer().winHandler, container)
                                }
                            }
                            areControlsVisible = !areControlsVisible
                            Timber.d("Controls visibility toggled to: $areControlsVisible")
                        }

                        NavigationDialog.ACTION_EXIT_GAME -> {
                            if (currentAppInfo != null) {
                                PostHog.capture(
                                    event = "game_closed",
                                    properties = mapOf(
                                        "game_name" to currentAppInfo.name,
                                    ),
                                )
                            } else {
                                PostHog.capture(event = "game_closed")
                            }
                            exit(xServerView!!.getxServer().winHandler, PluviaApp.xEnvironment, onExit)
                        }
                    }
                }
            },
        ).show()
    }

    DisposableEffect(Unit) {
        registerBackAction(gameBack)
        onDispose {
            Timber.d("XServerScreen leaving, clearing back action")
            registerBackAction { }
        }   // reset when screen leaves
    }

    DisposableEffect(lifecycleOwner) {
        val onActivityDestroyed: (AndroidEvent.ActivityDestroyed) -> Unit = {
            Timber.i("onActivityDestroyed")
            exit(xServerView!!.getxServer().winHandler, PluviaApp.xEnvironment, onExit)
        }
        val onKeyEvent: (AndroidEvent.KeyEvent) -> Boolean = {
            val isKeyboard = Keyboard.isKeyboardDevice(it.event.device)
            val isGamepad = ExternalController.isGameController(it.event.device)
            // logD("onKeyEvent(${it.event.device.sources})\n\tisGamepad: $isGamepad\n\tisKeyboard: $isKeyboard\n\t${it.event}")

            var handled = false
            if (isGamepad) {
                if (emulateKeyboardMouse) {
                    handled = PluviaApp.inputControlsView?.onKeyEvent(it.event) == true
                    if (!handled) handled = xServerView!!.getxServer().winHandler.onKeyEvent(it.event)
                } else {
                    handled = xServerView!!.getxServer().winHandler.onKeyEvent(it.event)
                }
                // handled = ExternalController.onKeyEvent(xServer.winHandler, it.event)
            }
            if (!handled && isKeyboard) {
                handled = keyboard?.onKeyEvent(it.event) == true
            }
            handled
        }
        val onMotionEvent: (AndroidEvent.MotionEvent) -> Boolean = {
            val isGamepad = ExternalController.isGameController(it.event?.device)

            var handled = false
            if (isGamepad) {
                if (emulateKeyboardMouse) {
                    handled = PluviaApp.inputControlsView?.onGenericMotionEvent(it.event) == true
                    if (!handled) handled = xServerView!!.getxServer().winHandler.onGenericMotionEvent(it.event)
                } else {
                    handled = xServerView!!.getxServer().winHandler.onGenericMotionEvent(it.event)
                }
            }
            if (!handled) {
                handled = PluviaApp.touchpadView?.onExternalMouseEvent(it.event) == true
            }
            handled
        }
        val onGuestProgramTerminated: (AndroidEvent.GuestProgramTerminated) -> Unit = {
            Timber.i("onGuestProgramTerminated")
            exit(xServerView!!.getxServer().winHandler, PluviaApp.xEnvironment, onExit)
            navigateBack()
        }
        val onForceCloseApp: (SteamEvent.ForceCloseApp) -> Unit = {
            Timber.i("onForceCloseApp")
            exit(xServerView!!.getxServer().winHandler, PluviaApp.xEnvironment, onExit)
            navigateBack()
        }
        val debugCallback = Callback<String> { outputLine ->
            Timber.i(outputLine ?: "")
        }

        PluviaApp.events.on<AndroidEvent.ActivityDestroyed, Unit>(onActivityDestroyed)
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onKeyEvent)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(onMotionEvent)
        PluviaApp.events.on<AndroidEvent.GuestProgramTerminated, Unit>(onGuestProgramTerminated)
        PluviaApp.events.on<SteamEvent.ForceCloseApp, Unit>(onForceCloseApp)
        ProcessHelper.addDebugCallback(debugCallback)

        onDispose {
            PluviaApp.events.off<AndroidEvent.ActivityDestroyed, Unit>(onActivityDestroyed)
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onKeyEvent)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(onMotionEvent)
            PluviaApp.events.off<AndroidEvent.GuestProgramTerminated, Unit>(onGuestProgramTerminated)
            PluviaApp.events.off<SteamEvent.ForceCloseApp, Unit>(onForceCloseApp)
            ProcessHelper.removeDebugCallback(debugCallback)
        }
    }

    // var launchedView by rememberSaveable { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerHoverIcon(PointerIcon(0))
            .pointerInteropFilter {
                // If controls are visible, let them handle it first
                val controlsHandled = if (areControlsVisible) {
                    PluviaApp.inputControlsView?.onTouchEvent(it) ?: false
                } else {
                    false
                }

                // If controls didn't handle it or aren't visible, send to touchMouse
                if (!controlsHandled) {
                    PluviaApp.touchpadView?.onTouchEvent(it)
                }

                true
            },
        factory = { context ->
            Timber.i("Creating XServerView and XServer")
            val frameLayout = FrameLayout(context)
            val appId = appId
            val existingXServer =
                PluviaApp.xEnvironment
                    ?.getComponent<XServerComponent>(XServerComponent::class.java)
                    ?.xServer
            val xServerToUse = existingXServer ?: XServer(ScreenInfo(xServerState.value.screenSize))
            val xServerView = XServerView(
                context,
                xServerToUse,
            ).apply {
                xServerView = this
                val renderer = this.renderer
                renderer.isCursorVisible = false
                getxServer().renderer = renderer
                PluviaApp.touchpadView = TouchpadView(context, getxServer(), PrefManager.getBoolean("capture_pointer_on_external_mouse", true))
                frameLayout.addView(PluviaApp.touchpadView)
                PluviaApp.touchpadView?.setMoveCursorToTouchpoint(PrefManager.getBoolean("move_cursor_to_touchpoint", false))
                getxServer().winHandler = WinHandler(getxServer(), this)
                win32AppWorkarounds = Win32AppWorkarounds(
                    getxServer(),
                    taskAffinityMask,
                    taskAffinityMaskWoW64,
                )
                touchMouse = TouchMouse(getxServer())
                keyboard = Keyboard(getxServer())
                if (!bootToContainer) {
                    renderer.setUnviewableWMClasses("explorer.exe")
                    // TODO: make 'force fullscreen' be an option of the app being launched
                    appLaunchInfo?.let { renderer.forceFullscreenWMClass = Paths.get(it.executable).name }
                }

                getxServer().windowManager.addOnWindowModificationListener(
                    object : WindowManager.OnWindowModificationListener {
                        private fun changeFrameRatingVisibility(window: Window, property: Property?) {
                            if (frameRating == null) return
                            if (property != null) {
                                if (frameRatingWindowId == -1 && window.attributes.isMapped() && property.nameAsString() == "_MESA_DRV") {
                                    frameRatingWindowId = window.id
                                    (context as? Activity)?.runOnUiThread {
                                        frameRating?.visibility = View.VISIBLE
                                    }
                                }
                            } else if (window.id == frameRatingWindowId) {
                                frameRatingWindowId = -1
                                (context as? Activity)?.runOnUiThread {
                                    frameRating?.visibility = View.GONE
                                }
                            }
                        }
                        override fun onUpdateWindowContent(window: Window) {
                            if (!xServerState.value.winStarted && window.isApplicationWindow()) {
                                val container = ContainerUtils.getContainer(context, appId)
                                if (!container.isDisableMouseInput && !container.isTouchscreenMode) renderer?.setCursorVisible(true)
                                xServerState.value.winStarted = true
                            }
                            if (window.id == frameRatingWindowId) {
                                (context as? Activity)?.runOnUiThread {
                                    frameRating?.update()
                                }
                            }
                        }

                        override fun onModifyWindowProperty(window: Window, property: Property) {
                            changeFrameRatingVisibility(window, property)
                        }

                        override fun onMapWindow(window: Window) {
                            Timber.i(
                                "onMapWindow:" +
                                        "\n\twindowName: ${window.name}" +
                                        "\n\twindowClassName: ${window.className}" +
                                        "\n\tprocessId: ${window.processId}" +
                                        "\n\thasParent: ${window.parent != null}" +
                                        "\n\tchildrenSize: ${window.children.size}",
                            )
                            win32AppWorkarounds?.applyWindowWorkarounds(window)
                            onWindowMapped?.invoke(context, window)
                        }

                        override fun onUnmapWindow(window: Window) {
                            Timber.i(
                                "onUnmapWindow:" +
                                        "\n\twindowName: ${window.name}" +
                                        "\n\twindowClassName: ${window.className}" +
                                        "\n\tprocessId: ${window.processId}" +
                                        "\n\thasParent: ${window.parent != null}" +
                                        "\n\tchildrenSize: ${window.children.size}",
                            )
                            changeFrameRatingVisibility(window, null)
                            onWindowUnmapped?.invoke(window)
                        }
                    },
                )

                if (PluviaApp.xEnvironment == null) {
                    val containerManager = ContainerManager(context)
                    val container = ContainerUtils.getContainer(context, appId)
                    // Configure WinHandler with container's input API settings
                    val handler = getxServer().winHandler
                    if (container.inputType !in 0..3) {
                        container.inputType = PreferredInputApi.BOTH.ordinal
                        container.saveData()
                    }
                    handler.setPreferredInputApi(PreferredInputApi.values()[container.inputType])
                    handler.setDInputMapperType(container.dinputMapperType)
                    if (container.isDisableMouseInput()) {
                        PluviaApp.touchpadView?.setTouchscreenMouseDisabled(true)
                    } else if (container.isTouchscreenMode()) {
                        PluviaApp.touchpadView?.setTouchscreenMode(true)
                    }
                    Timber.d("WinHandler configured: preferredInputApi=%s, dinputMapperType=0x%02x", PreferredInputApi.values()[container.inputType], container.dinputMapperType)
                    // Timber.d("1 Container drives: ${container.drives}")
                    containerManager.activateContainer(container)
                    // Timber.d("2 Container drives: ${container.drives}")
                    val imageFs = ImageFs.find(context)

                    taskAffinityMask = ProcessHelper.getAffinityMask(container.getCPUList(true)).toShort().toInt()
                    taskAffinityMaskWoW64 = ProcessHelper.getAffinityMask(container.getCPUListWoW64(true)).toShort().toInt()
                    containerVariantChanged = container.containerVariant != imageFs.variant
                    firstTimeBoot = container.getExtra("appVersion").isEmpty() || containerVariantChanged
                    needsUnpacking = container.isNeedsUnpacking
                    Timber.i("First time boot: $firstTimeBoot")

                    val wineVersion = container.wineVersion
                    Timber.i("Wine version is: $wineVersion")
                    val contentsManager = ContentsManager(context)
                    contentsManager.syncContents()
                    Timber.i("Wine info is: " + WineInfo.fromIdentifier(context, contentsManager, wineVersion))
                    xServerState.value = xServerState.value.copy(
                        wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion),
                    )
                    Timber.i("xServerState.value.wineInfo is: " + xServerState.value.wineInfo)
                    Timber.i("WineInfo.MAIN_WINE_VERSION is: " + WineInfo.MAIN_WINE_VERSION)
                    Timber.i("Wine path for wineinfo is " + xServerState.value.wineInfo.path)

                    if (!xServerState.value.wineInfo.isMainWineVersion()) {
                        Timber.i("Settings wine path to: ${xServerState.value.wineInfo.path}")
                        imageFs.setWinePath(xServerState.value.wineInfo.path)
                    }

                    val onExtractFileListener = if (!xServerState.value.wineInfo.isWin64) {
                        object : OnExtractFileListener {
                            override fun onExtractFile(destination: File?, size: Long): File? {
                                return destination?.path?.let {
                                    if (it.contains("system32/")) {
                                        null
                                    } else {
                                        File(it.replace("syswow64/", "system32/"))
                                    }
                                }
                            }
                        }
                    } else {
                        null
                    }

                    Timber.i("Doing things once")
                    val envVars = EnvVars()

                    setupWineSystemFiles(
                        context,
                        firstTimeBoot,
                        xServerView!!.getxServer().screenInfo,
                        xServerState,
                        container,
                        containerManager,
                        envVars,
                        onExtractFileListener,
                    )
                    extractArm64ecInputDLLs(context, container) // REQUIRED: Uses updated xinput1_3 main.c from x86_64 build, prevents crashes with 3+ players, avoids need for input shim dlls.
                    extractx86_64InputDlls(context, container)
                    extractGraphicsDriverFiles(
                        context,
                        xServerState.value.graphicsDriver,
                        xServerState.value.dxwrapper,
                        xServerState.value.dxwrapperConfig!!,
                        container,
                        envVars,
                        firstTimeBoot,
                    )
                    changeWineAudioDriver(xServerState.value.audioDriver, container, ImageFs.find(context))
                    setImagefsContainerVariant(context, container)
                    PluviaApp.xEnvironment = setupXEnvironment(
                        context,
                        appId,
                        bootToContainer,
                        xServerState,
                        envVars,
                        container,
                        appLaunchInfo,
                        xServerView!!.getxServer(),
                        containerVariantChanged,
                        onGameLaunchError,
                    )
                }
            }
            PluviaApp.xServerView = xServerView;

            frameLayout.addView(xServerView)

            PluviaApp.inputControlsManager = InputControlsManager(context)

            // Create InputControlsView and add to FrameLayout
            val icView = InputControlsView(context).apply {
                // Configure InputControlsView
                setXServer(xServerView.getxServer())
                setTouchpadView(PluviaApp.touchpadView)

                // Load default profile for now; may be overridden by container settings below
                val profiles = PluviaApp.inputControlsManager?.getProfiles(false) ?: listOf()
                PrefManager.init(context)
                if (profiles.isNotEmpty()) {
                    val container = ContainerUtils.getContainer(context, appId)
                    val targetProfile = if (container.isEmulateKeyboardMouse()) {
                        val profileName = container.id.toString()
                        profiles.firstOrNull { it.name == profileName }
                            ?: ContainerUtils.generateOrUpdateEmulationProfile(context, container)
                    } else {
                        profiles[2]
                    }
                    setProfile(targetProfile)
                }

                // Set overlay opacity from preferences if needed
                val opacity = PrefManager.getFloat("controls_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY)
                setOverlayOpacity(opacity)
            }
            PluviaApp.inputControlsView = icView

            xServerView.getxServer().winHandler.setInputControlsView(PluviaApp.inputControlsView)

            // Add InputControlsView on top of XServerView
            frameLayout.addView(icView)
            hideInputControls()
            val container = ContainerUtils.getContainer(context, appId)

            // If emulation is enabled, select the per-container profile (named by container id)
            if (container.isEmulateKeyboardMouse()) {
                val profiles2 = PluviaApp.inputControlsManager?.getProfiles(false) ?: listOf()
                val profileName = container.id.toString()
                var target = profiles2.firstOrNull { it.name == profileName }
                if (target == null) {
                    target = ContainerUtils.generateOrUpdateEmulationProfile(context, container)
                }
                PluviaApp.inputControlsView?.setProfile(target)
                PluviaApp.inputControlsView?.invalidate()
            } else {
                // Show on-screen controls if no physical controller is connected (respect current profile)
                if (ExternalController.getController(0) == null) {
                    val profiles2 = PluviaApp.inputControlsManager?.getProfiles(false) ?: listOf()
                    if (profiles2.size > 2) {
                        showInputControls(profiles2[2], xServerView.getxServer().winHandler, container)
                        areControlsVisible = true
                    }
                }
            }

            if (container.isShowFPS()) {
                Timber.i("Attempting to show FPS")
                frameRating = FrameRating(context)
                frameRating?.setVisibility(View.GONE)
                frameRating?.let { frameLayout.addView(it) }
            }

            if (container.isDisableMouseInput){
                PluviaApp.touchpadView?.setTouchscreenMouseDisabled(true);
            }

            frameLayout

            // } else {
            //     Log.d("XServerScreen", "Creating XServerView without creating XServer")
            //     xServerView = XServerView(context, PluviaApp.xServer)
            // }
            // xServerView
        },
        update = { view ->
            // View's been inflated or state read in this block has been updated
            // Add logic here if necessary
            // view.requestFocus()
        },
        onRelease = { view ->
            // view.releasePointerCapture()
            // pointerEventListener?.let {
            //     view.removePointerEventListener(pointerEventListener)
            //     view.onRelease()
            // }
        },
    )

    // var ranSetup by rememberSaveable { mutableStateOf(false) }
    // LaunchedEffect(lifecycleOwner) {
    //     if (!ranSetup) {
    //         ranSetup = true
    //
    //
    //     }
    // }
}

private fun emulateKeyboardMouseOnscreen(
    container: Container,
    profiles: List<ControlsProfile>,
    context: Context,
): ControlsProfile? {
    val bindingsJson = container.controllerEmulationBindings
    val emuJson = if (bindingsJson != null) JSONObject(bindingsJson.toString()) else null
    val baseProfile = profiles.firstOrNull { it.id == 3 || it.name.contains("Virtual Gamepad", true) }
        ?: profiles.getOrNull(2)
        ?: profiles.first()
    val baseFile = ControlsProfile.getProfileFile(context, baseProfile.id)
    val profileJSONObject = JSONObject(FileUtils.readString(baseFile))
    val elementsJSONArray = profileJSONObject.getJSONArray("elements")

    fun optBinding(key: String, fallback: String): String {
        return emuJson?.optString(key, fallback) ?: fallback
    }

    for (i in 0 until elementsJSONArray.length()) {
        val e = elementsJSONArray.getJSONObject(i)
        val type = e.getString("type")
        val bindings = e.getJSONArray("bindings")
        if (type == "D_PAD") {
            bindings.put(0, optBinding("DPAD_UP", bindings.getString(0)))
            bindings.put(1, optBinding("DPAD_RIGHT", bindings.getString(1)))
            bindings.put(2, optBinding("DPAD_DOWN", bindings.getString(2)))
            bindings.put(3, optBinding("DPAD_LEFT", bindings.getString(3)))
        } else if (type == "STICK") {
            val b0 = bindings.getString(0)
            if (b0.startsWith("GAMEPAD_LEFT_THUMB")) {
                bindings.put(0, "KEY_W")
                bindings.put(1, "KEY_D")
                bindings.put(2, "KEY_S")
                bindings.put(3, "KEY_A")
            } else if (b0.startsWith("GAMEPAD_RIGHT_THUMB")) {
                bindings.put(0, "MOUSE_MOVE_UP")
                bindings.put(1, "MOUSE_MOVE_RIGHT")
                bindings.put(2, "MOUSE_MOVE_DOWN")
                bindings.put(3, "MOUSE_MOVE_LEFT")
            }
        } else if (type == "BUTTON") {
            val b0 = bindings.getString(0)
            val logical = when (b0) {
                "GAMEPAD_BUTTON_A" -> "A"
                "GAMEPAD_BUTTON_B" -> "B"
                "GAMEPAD_BUTTON_X" -> "X"
                "GAMEPAD_BUTTON_Y" -> "Y"
                "GAMEPAD_BUTTON_L1" -> "L1"
                "GAMEPAD_BUTTON_L2" -> "L2"
                "GAMEPAD_BUTTON_L3" -> "L3"
                "GAMEPAD_BUTTON_R1" -> "R1"
                "GAMEPAD_BUTTON_R2" -> "R2"
                "GAMEPAD_BUTTON_R3" -> "R3"
                "GAMEPAD_BUTTON_START" -> "START"
                "GAMEPAD_BUTTON_SELECT" -> "SELECT"
                else -> null
            }
            if (logical != null) {
                val mapped = optBinding(logical, "NONE")
                bindings.put(0, mapped)
                bindings.put(1, "NONE")
                bindings.put(2, "NONE")
                bindings.put(3, "NONE")
            }
        }
    }

    val targetProfile = profiles.firstOrNull { it.name == "Keyboard & Mouse Gamepad" }
        ?: PluviaApp.inputControlsManager?.duplicateProfile(baseProfile)?.apply {
            setName("Keyboard & Mouse Gamepad")
            save()
        }
        ?: baseProfile
    // Log final JSON and persist to target profile file
    Timber.d("Final emulated profile JSON: %s", profileJSONObject.toString())
    val targetFile = ControlsProfile.getProfileFile(context, targetProfile.id)
    FileUtils.writeString(targetFile, profileJSONObject.toString())
    return targetProfile
}

private fun showInputControls(profile: ControlsProfile, winHandler: WinHandler, container: Container) {
    profile.setVirtualGamepad(true)
    PluviaApp.inputControlsView?.setVisibility(View.VISIBLE)
    PluviaApp.inputControlsView?.requestFocus()
    PluviaApp.inputControlsView?.setProfile(profile)

    PluviaApp.touchpadView?.setSensitivity(profile.getCursorSpeed() * 1.0f)
    PluviaApp.touchpadView?.setPointerButtonRightEnabled(false)

    PluviaApp.inputControlsView?.invalidate()


    // If the selected profile is a virtual gamepad, we must enable the P1 slot.
    if (container.containerVariant.equals(Container.BIONIC) && profile.isVirtualGamepad()) {
        val controllerManager: ControllerManager = ControllerManager.getInstance()


        // Ensure Player 1 slot is enabled so a vjoy device is created for it.
        controllerManager.setSlotEnabled(0, true)


        // Clear any physical device from P1 to prevent conflicts.
        controllerManager.unassignSlot(0)


        // Tell WinHandler to update its internal state.
        if (winHandler != null) {
            winHandler.refreshControllerMappings()
        }
    }
}

private fun hideInputControls() {
    PluviaApp.inputControlsView?.setShowTouchscreenControls(true)
    PluviaApp.inputControlsView?.setVisibility(View.GONE)
    PluviaApp.inputControlsView?.setProfile(null)

    PluviaApp.touchpadView?.setSensitivity(1.0f)
    PluviaApp.touchpadView?.setPointerButtonLeftEnabled(true)
    PluviaApp.touchpadView?.setPointerButtonRightEnabled(true)
    PluviaApp.touchpadView?.isEnabled()?.let {
        if (!it) {
            PluviaApp.touchpadView?.setEnabled(true)
            PluviaApp.xServerView?.getRenderer()?.setCursorVisible(true)
        }
    }
    PluviaApp.inputControlsView?.invalidate()
}

/**
 * Shows or hides the onscreen controls
 */
fun showInputControls(context: Context, show: Boolean) {
    PluviaApp.inputControlsView?.let { icView ->
        icView.setShowTouchscreenControls(show)
        icView.invalidate()
    }
}

/**
 * Changes the currently active controls profile
 */
fun selectControlsProfile(context: Context, profileId: Int) {
    PluviaApp.inputControlsManager?.getProfile(profileId)?.let { profile ->
        PluviaApp.inputControlsView?.setProfile(profile)
        PluviaApp.inputControlsView?.invalidate()
    }
}

/**
 * Sets the opacity of the onscreen controls
 */
fun setControlsOpacity(context: Context, opacity: Float) {
    PluviaApp.inputControlsView?.let { icView ->
        icView.setOverlayOpacity(opacity)
        icView.invalidate()

        // Save the preference for future sessions
        PrefManager.init(context)
        PrefManager.setFloat("controls_opacity", opacity)
    }
}

/**
 * Toggles edit mode for controls
 */
fun toggleControlsEditMode(context: Context, editMode: Boolean) {
    PluviaApp.inputControlsView?.let { icView ->
        icView.setEditMode(editMode)
        icView.invalidate()
    }
}

/**
 * Add a new control element at the current position
 */
fun addControlElement(context: Context): Boolean {
    return PluviaApp.inputControlsView?.addElement() ?: false
}

/**
 * Remove the selected control element
 */
fun removeControlElement(context: Context): Boolean {
    return PluviaApp.inputControlsView?.removeElement() ?: false
}

/**
 * Get available control profiles
 */
fun getAvailableControlProfiles(context: Context): List<String> {
    return PluviaApp.inputControlsManager?.getProfiles(false)?.map { it.getName() } ?: emptyList()
}

private fun assignTaskAffinity(
    window: Window,
    winHandler: WinHandler,
    taskAffinityMask: Int,
    taskAffinityMaskWoW64: Int,
) {
    if (taskAffinityMask == 0) return
    val processId = window.getProcessId()
    val className = window.getClassName()
    val processAffinity = if (window.isWoW64()) taskAffinityMaskWoW64 else taskAffinityMask

    if (className.equals("steam.exe")) {
        return;
    }
    if (processId > 0) {
        winHandler.setProcessAffinity(processId, processAffinity)
    } else if (!className.isEmpty()) {
        winHandler.setProcessAffinity(window.getClassName(), processAffinity)
    }
}

private fun shiftXEnvironmentToContext(
    context: Context,
    xEnvironment: XEnvironment,
    xServer: XServer,
): XEnvironment {
    val environment = XEnvironment(context, xEnvironment.imageFs)
    val rootPath = xEnvironment.imageFs.rootDir.path
    xEnvironment.getComponent<SysVSharedMemoryComponent>(SysVSharedMemoryComponent::class.java).stop()
    val sysVSharedMemoryComponent = SysVSharedMemoryComponent(
        xServer,
        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH),
    )
    // val sysVSharedMemoryComponent = xEnvironment.getComponent<SysVSharedMemoryComponent>(SysVSharedMemoryComponent::class.java)
    // sysVSharedMemoryComponent.connectToXServer(xServer)
    environment.addComponent(sysVSharedMemoryComponent)
    xEnvironment.getComponent<XServerComponent>(XServerComponent::class.java).stop()
    val xServerComponent = XServerComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH))
    // val xServerComponent = xEnvironment.getComponent<XServerComponent>(XServerComponent::class.java)
    // xServerComponent.connectToXServer(xServer)
    environment.addComponent(xServerComponent)
    xEnvironment.getComponent<NetworkInfoUpdateComponent>(NetworkInfoUpdateComponent::class.java).stop()
    val networkInfoComponent = NetworkInfoUpdateComponent()
    environment.addComponent(networkInfoComponent)
    // environment.addComponent(xEnvironment.getComponent<NetworkInfoUpdateComponent>(NetworkInfoUpdateComponent::class.java))
    environment.addComponent(xEnvironment.getComponent<SteamClientComponent>(SteamClientComponent::class.java))
    val alsaComponent = xEnvironment.getComponent<ALSAServerComponent>(ALSAServerComponent::class.java)
    if (alsaComponent != null) {
        environment.addComponent(alsaComponent)
    }
    val pulseComponent = xEnvironment.getComponent<PulseAudioComponent>(PulseAudioComponent::class.java)
    if (pulseComponent != null) {
        environment.addComponent(pulseComponent)
    }
    var virglComponent: VirGLRendererComponent? =
        xEnvironment.getComponent<VirGLRendererComponent>(VirGLRendererComponent::class.java)
    if (virglComponent != null) {
        virglComponent.stop()
        virglComponent = VirGLRendererComponent(
            xServer,
            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH),
        )
        environment.addComponent(virglComponent)
    }
    environment.addComponent(xEnvironment.getComponent<GlibcProgramLauncherComponent>(GlibcProgramLauncherComponent::class.java))

    FileUtils.clear(XEnvironment.getTmpDir(context))
    sysVSharedMemoryComponent.start()
    xServerComponent.start()
    networkInfoComponent.start()
    virglComponent?.start()
    // environment.startEnvironmentComponents()

    return environment
}
private fun setupXEnvironment(
    context: Context,
    appId: String,
    bootToContainer: Boolean,
    xServerState: MutableState<XServerState>,
    // xServerViewModel: XServerViewModel,
    envVars: EnvVars,
    // generateWinePrefix: Boolean,
    container: Container?,
    appLaunchInfo: LaunchInfo?,
    // shortcut: Shortcut?,
    xServer: XServer,
    containerVariantChanged: Boolean,
    onGameLaunchError: ((String) -> Unit)? = null,
): XEnvironment {
    val lc_all = container!!.lC_ALL
    val imageFs = ImageFs.find(context)
    Timber.i("ImageFs paths:")
    Timber.i("- rootDir: ${imageFs.getRootDir().absolutePath}")
    Timber.i("- winePath: ${imageFs.winePath}")
    Timber.i("- home_path: ${imageFs.home_path}")
    Timber.i("- wineprefix: ${imageFs.wineprefix}")

    val contentsManager = ContentsManager(context)
    contentsManager.syncContents()
    envVars.put("LC_ALL", lc_all)
    envVars.put("MESA_DEBUG", "silent")
    envVars.put("MESA_NO_ERROR", "1")
    envVars.put("WINEPREFIX", imageFs.wineprefix)
    if (container.isShowFPS){
        envVars.put("DXVK_HUD", "fps,frametimes")
        envVars.put("VK_INSTANCE_LAYERS", "VK_LAYER_MESA_overlay")
        envVars.put("MESA_OVERLAY_SHOW_FPS", 1)
    }
    if (container.isSdlControllerAPI){
        if (container.inputType == PreferredInputApi.XINPUT.ordinal || container.inputType == PreferredInputApi.AUTO.ordinal){
            envVars.put("SDL_XINPUT_ENABLED", "1")
            envVars.put("SDL_DIRECTINPUT_ENABLED", "0")
            envVars.put("SDL_JOYSTICK_HIDAPI", "1")
        } else if (container.inputType == PreferredInputApi.DINPUT.ordinal) {
            envVars.put("SDL_XINPUT_ENABLED", "0")
            envVars.put("SDL_DIRECTINPUT_ENABLED", "1")
            envVars.put("SDL_JOYSTICK_HIDAPI", "0")
        } else if (container.inputType == PreferredInputApi.BOTH.ordinal) {
            envVars.put("SDL_XINPUT_ENABLED", "1")
            envVars.put("SDL_DIRECTINPUT_ENABLED", "1")
            envVars.put("SDL_JOYSTICK_HIDAPI", "1")
        }
        envVars.put("SDL_JOYSTICK_WGI", "0")
        envVars.put("SDL_JOYSTICK_RAWINPUT", "0")
        envVars.put("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1")
        envVars.put("SDL_HINT_FORCE_RAISEWINDOW", "0")
        envVars.put("SDL_ALLOW_TOPMOST", "0")
        envVars.put("SDL_MOUSE_FOCUS_CLICKTHROUGH", "1")
    }

    ProcessHelper.removeAllDebugCallbacks()
    // read user preferences
    val enableWineDebug = PrefManager.enableWineDebug
    val enableBox86Logs = WinlatorPrefManager.getBoolean("enable_box86_64_logs", false)
    val wineDebugChannels = PrefManager.wineDebugChannels
    // explicitly enable or disable Wine debug channels
    envVars.put(
        "WINEDEBUG",
        if (enableWineDebug && wineDebugChannels.isNotEmpty())
            "+" + wineDebugChannels.replace(",", ",+")
        else
            "-all",
    )
    // capture debug output to file if either Wine or Box86/64 logging is enabled
    var logFile: File? = null
    val captureLogs = enableWineDebug || enableBox86Logs
    if (captureLogs) {
        val wineLogDir = File(context.getExternalFilesDir(null), "wine_logs")
        wineLogDir.mkdirs()
        logFile = File(wineLogDir, "wine_debug.log")
        if (logFile.exists()) logFile.delete()
    }

    ProcessHelper.addDebugCallback { line ->
        if (captureLogs) {
            logFile?.appendText(line + "\n")
        }
    }

    val rootPath = imageFs.getRootDir().getPath()
    FileUtils.clear(imageFs.getTmpDir())

    val usrGlibc: Boolean = container.getContainerVariant().equals(Container.GLIBC, ignoreCase = true)
    val guestProgramLauncherComponent = if (usrGlibc) {
        Timber.i("Setting guestProgramLauncherComponent to GlibcProgramLauncherComponent")
        GlibcProgramLauncherComponent(
            contentsManager,
            contentsManager.getProfileByEntryName(container.wineVersion),
        )
    }
    else {
        Timber.i("Setting guestProgramLauncherComponent to BionicProgramLauncherComponent")
        BionicProgramLauncherComponent(
            contentsManager,
            contentsManager.getProfileByEntryName(container.wineVersion),
        )
    }

    if (container != null) {
        if (container.startupSelection == Container.STARTUP_SELECTION_AGGRESSIVE) xServer.winHandler.killProcess("services.exe")

        val wow64Mode = container.isWoW64Mode
        guestProgramLauncherComponent.setContainer(container);
        guestProgramLauncherComponent.setWineInfo(xServerState.value.wineInfo);
        val guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " +
            getWineStartCommand(appId, container, bootToContainer, appLaunchInfo, envVars, guestProgramLauncherComponent) +
            (if (container.execArgs.isNotEmpty()) " " + container.execArgs else "")
        guestProgramLauncherComponent.isWoW64Mode = wow64Mode
        guestProgramLauncherComponent.guestExecutable = guestExecutable
        // Set steam type for selecting appropriate box64rc
        guestProgramLauncherComponent.setSteamType(container.getSteamType())

        envVars.putAll(container.envVars)
        if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1")

        // Timber.d("3 Container drives: ${container.drives}")
        val bindingPaths = mutableListOf<String>()
        for (drive in container.drivesIterator()) {
            Timber.i("Binding drive ${drive[0]} with path of ${drive[1]}")
            bindingPaths.add(drive[1])
        }
        guestProgramLauncherComponent.bindingPaths = bindingPaths.toTypedArray()
        guestProgramLauncherComponent.box64Version = container.box64Version
        guestProgramLauncherComponent.box86Version = container.box86Version
        guestProgramLauncherComponent.box86Preset = container.box86Preset
        guestProgramLauncherComponent.box64Preset = container.box64Preset
        guestProgramLauncherComponent.setPreUnpack {
            unpackExecutableFile(context, container.isNeedsUnpacking, container, appId, appLaunchInfo, guestProgramLauncherComponent, containerVariantChanged, onGameLaunchError)
        }

        val enableGstreamer = container.isGstreamerWorkaround()

        if (enableGstreamer) {
            for (envVar in Container.MEDIACONV_ENV_VARS) {
                val parts: Array<String?> = envVar.split("=".toRegex(), limit = 2).toTypedArray()
                if (parts.size == 2) {
                    envVars.put(parts[0], parts[1])
                }
            }
        }
    }

    val environment = XEnvironment(context, imageFs)
    environment.addComponent(
        SysVSharedMemoryComponent(
            xServer,
            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH),
        ),
    )
    environment.addComponent(XServerComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)))
    environment.addComponent(NetworkInfoUpdateComponent())
    environment.addComponent(SteamClientComponent())

    // environment.addComponent(SteamClientComponent(UnixSocketConfig.createSocket(
    //     rootPath,
    //     Paths.get(ImageFs.WINEPREFIX, "drive_c", UnixSocketConfig.STEAM_PIPE_PATH).toString()
    // )))
    // environment.addComponent(SteamClientComponent(UnixSocketConfig.createSocket(SteamService.getAppDirPath(appId), "/steam_pipe")))
    // environment.addComponent(SteamClientComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.STEAM_PIPE_PATH)))

    if (xServerState.value.audioDriver == "alsa") {
        envVars.put("ANDROID_ALSA_SERVER", imageFs.getRootDir().getPath() + UnixSocketConfig.ALSA_SERVER_PATH)
        envVars.put("ANDROID_ASERVER_USE_SHM", "true")
        val options = ALSAClient.Options.fromKeyValueSet(null)
        environment.addComponent(ALSAServerComponent(UnixSocketConfig.createSocket(imageFs.getRootDir().getPath(), UnixSocketConfig.ALSA_SERVER_PATH), options))
    } else if (xServerState.value.audioDriver == "pulseaudio") {
        envVars.put("PULSE_SERVER", imageFs.getRootDir().getPath() + UnixSocketConfig.PULSE_SERVER_PATH)
        environment.addComponent(PulseAudioComponent(UnixSocketConfig.createSocket(imageFs.getRootDir().getPath(), UnixSocketConfig.PULSE_SERVER_PATH)))
    }

    if (xServerState.value.graphicsDriver == "virgl") {
        environment.addComponent(
            VirGLRendererComponent(
                xServer,
                UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH),
            ),
        )
    } else if (xServerState.value.graphicsDriver == "vortek" || xServerState.value.graphicsDriver == "adreno" || xServerState.value.graphicsDriver == "sd-8-elite") {
        Timber.i("Adding VortekRendererComponent to Environment")
        val gcfg = KeyValueSet(container.getGraphicsDriverConfig())
        val graphicsDriver = xServerState.value.graphicsDriver
        if (graphicsDriver == "sd-8-elite" || graphicsDriver == "adreno") {
            gcfg.put("adrenotoolsDriver", "vulkan.adreno.so")
            container.setGraphicsDriverConfig(gcfg.toString())
        }
        val options2: VortekRendererComponent.Options? = VortekRendererComponent.Options.fromKeyValueSet(context, gcfg)
        environment.addComponent(VortekRendererComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options2, context))
    }

    guestProgramLauncherComponent.envVars = envVars
    guestProgramLauncherComponent.setTerminationCallback { status ->
        if (status != 0) {
            Timber.e("Guest program terminated with status: $status")
            onGameLaunchError?.invoke("Game terminated with error status: $status")
        }
        PluviaApp.events.emit(AndroidEvent.GuestProgramTerminated)
    }
    environment.addComponent(guestProgramLauncherComponent)


    // Generate fexcore per app settings
    FEXCoreManager.createAppConfigFiles(context)

    // Log container settings before starting
    if (container != null) {
        Timber.i("---- Launching Container ----")
        Timber.i("ID: ${container.id}")
        Timber.i("Name: ${container.name}")
        Timber.i("Screen Size: ${container.screenSize}")
        Timber.i("Graphics Driver: ${container.graphicsDriver}")
        Timber.i("DX Wrapper: ${container.dxWrapper} (Config: '${container.dxWrapperConfig}')")
        Timber.i("Audio Driver: ${container.audioDriver}")
        Timber.i("WoW64 Mode: ${container.isWoW64Mode}")
        Timber.i("Box64 Version: ${container.box64Version}")
        Timber.i("Box64 Preset: ${container.box64Preset}")
        Timber.i("Box86 Version: ${container.box86Version}")
        Timber.i("Box86 Preset: ${container.box86Preset}")
        Timber.i("CPU List: ${container.cpuList}")
        Timber.i("CPU List WoW64: ${container.cpuListWoW64}")
        Timber.i("Env Vars (Container Base): ${container.envVars}") // Log base container vars
        Timber.i("Env Vars (Final Guest): ${envVars.toString()}")   // Log the actual env vars being passed
        Timber.i("Guest Executable: ${guestProgramLauncherComponent.guestExecutable}") // Log the command
        Timber.i("---------------------------")
    }

    environment.startEnvironmentComponents()

    // put in separate scope since winhandler start method does some network stuff
    CoroutineScope(Dispatchers.IO).launch {
        xServer.winHandler.start()
    }
    envVars.clear()
    xServerState.value = xServerState.value.copy(
        dxwrapperConfig = null,
    )
    return environment
}
private fun getWineStartCommand(
    appId: String,
    container: Container,
    bootToContainer: Boolean,
    appLaunchInfo: LaunchInfo?,
    envVars: EnvVars,
    guestProgramLauncherComponent: GuestProgramLauncherComponent,
): String {
    val tempDir = File(container.getRootDir(), ".wine/drive_c/windows/temp")
    FileUtils.clear(tempDir)

    Timber.tag("XServerScreen").d("appLaunchInfo is $appLaunchInfo")
    val args = if (bootToContainer || appLaunchInfo == null) {
        "\"wfm.exe\""
    } else {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        var executablePath = ""
        if (container.executablePath.isNotEmpty()) {
            executablePath = container.executablePath
        } else {
            executablePath = SteamService.getInstalledExe(steamAppId)
            container.executablePath = executablePath
            container.saveData()
        }
        val executableDir = appDirPath + "/" + executablePath.substringBeforeLast("/", "")
        guestProgramLauncherComponent.workingDir = File(executableDir);
        Timber.i("Working directory is ${executableDir}")

        Timber.i("Final exe path is " + executablePath)
        val drives = container.drives
        val driveIndex = drives.indexOf(appDirPath)
        // greater than 1 since there is the drive character and the colon before the app dir path
        val drive = if (driveIndex > 1) {
            drives[driveIndex - 2]
        } else {
            Timber.e("Could not locate game drive")
            'D'
        }
        envVars.put("WINEPATH", "$drive:/${appLaunchInfo.workingDir}")
        "\"$drive:/${executablePath}\""
    }

    return "winhandler.exe $args"
}
private fun getSteamlessTarget(
    appId: String,
    container: Container,
    appLaunchInfo: LaunchInfo?,
): String {
    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
    val appDirPath = SteamService.getAppDirPath(gameId)
    val executablePath = SteamService.getInstalledExe(gameId)
    val drives = container.drives
    val driveIndex = drives.indexOf(appDirPath)
    // greater than 1 since there is the drive character and the colon before the app dir path
    val drive = if (driveIndex > 1) {
        drives[driveIndex - 2]
    } else {
        Timber.e("Could not locate game drive")
        'D'
    }
    return "$drive:\\${executablePath}"
}
private fun exit(winHandler: WinHandler?, environment: XEnvironment?, onExit: () -> Unit) {
    Timber.i("Exit called")
    PostHog.capture(event = "game_exited")
    winHandler?.stop()
    environment?.stopEnvironmentComponents()
    // AppUtils.restartApplication(this)
    // PluviaApp.xServerState = null
    // PluviaApp.xServer = null
    // PluviaApp.xServerView = null
    PluviaApp.xEnvironment = null
    PluviaApp.inputControlsView = null
    PluviaApp.inputControlsManager = null
    PluviaApp.touchpadView = null
    // PluviaApp.touchMouse = null
    // PluviaApp.keyboard = null
    onExit()
}

private fun unpackExecutableFile(
    context: Context,
    needsUnpacking: Boolean,
    container: Container,
    appId: String,
    appLaunchInfo: LaunchInfo?,
    guestProgramLauncherComponent: GuestProgramLauncherComponent,
    containerVariantChanged: Boolean,
    onError: ((String) -> Unit)? = null,
) {
    val imageFs = ImageFs.find(context)
    var output = StringBuilder()
    if (needsUnpacking || containerVariantChanged){
        try {
            val monoCmd = "wine msiexec /i Z:\\opt\\mono-gecko-offline\\wine-mono-9.0.0-x86.msi"
            Timber.i("Install mono command $monoCmd")
            val monoOutput = guestProgramLauncherComponent.execShellCommand(monoCmd)
            output.append(monoOutput)
            Timber.i("Result of mono command " + output)
        } catch (e: Exception) {
            Timber.e("Error during mono installation: $e")
        }
    }
    if (!needsUnpacking){
        return
    }
    try {
        val rootDir: File = imageFs.getRootDir()
        val executableFile = getSteamlessTarget(appId, container, appLaunchInfo)

        try {
            // a:/.../GameDir/orig_dll_path.txt  (same dir as the EXE inside A:)
            val origTxtFile  = File("${imageFs.wineprefix}/dosdevices/a:/orig_dll_path.txt")

            if (origTxtFile.exists()) {
                val relDllPath = origTxtFile.readText().trim()
                if (relDllPath.isNotBlank()) {
                    val origDll = File("${imageFs.wineprefix}/dosdevices/a:/$relDllPath")
                    if (origDll.exists()) {
                        val genCmd = "wine z:\\generate_interfaces_file.exe A:\\" + relDllPath.replace('/', '\\')
                        Timber.i("Running generate_interfaces_file $genCmd")
                        val genOutput = guestProgramLauncherComponent.execShellCommand(genCmd)

                        val origSteamInterfaces = File("${imageFs.wineprefix}/dosdevices/z:/steam_interfaces.txt")
                        if (origSteamInterfaces.exists()) {
                            val finalSteamInterfaces = File(origDll.parent, "steam_interfaces.txt")
                            try {
                                Files.copy(
                                    origSteamInterfaces.toPath(),
                                    finalSteamInterfaces.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING,
                                )
                                Timber.i("Copied steam_interfaces.txt to ${finalSteamInterfaces.absolutePath}")
                            } catch (ioe: IOException) {
                                Timber.w(ioe, "Failed to copy steam_interfaces.txt")
                            }
                        } else {
                            Timber.w("steam_interfaces.txt not found at $origSteamInterfaces")
                        }

                        Timber.i("Result of generate_interfaces_file command $genOutput")
                    } else {
                        Timber.w("DLL specified in orig_dll_path.txt not found: $origDll")
                    }
                }
            } else {
                Timber.i("orig_dll_path.txt not present; skipping interface generation")
            }
        } catch (e: Exception) {
            Timber.e("Error running generate_interfaces_file: $e")
        }

        output = StringBuilder()
        try {
            val slCmd = "wine z:\\Steamless\\Steamless.CLI.exe $executableFile"
            Timber.i("Running shell command $slCmd")
            val slOutput = guestProgramLauncherComponent.execShellCommand(slCmd)
            output.append(slOutput)
            Timber.i("Result of Steamless command " + output)
        } catch (e: Exception) {
            Timber.e("Error running Steamless: $e")
        }

        val exe = File(imageFs.wineprefix + "/dosdevices/" + executableFile.replace("A:", "a:").replace('\\', '/'))
        val unpackedExe = File(
            imageFs.wineprefix + "/dosdevices/" + executableFile.replace("A:", "a:")
                .replace('\\', '/') + ".unpacked.exe",
        )
        val originalExe = File(
            imageFs.wineprefix + "/dosdevices/" + executableFile.replace("A:", "a:")
                .replace('\\', '/') + ".original.exe",
        )
        Timber.i("Moving " + unpackedExe + " to " + exe)
        try {
            if (exe.exists() && unpackedExe.exists()) {
                Files.copy(exe.toPath(), originalExe.toPath())
                Files.copy(unpackedExe.toPath(), exe.toPath(), REPLACE_EXISTING)
            } else {
                val errorMsg = "Either original exe or unpacked exe does not exist. Original: ${exe.exists()}, Unpacked: ${unpackedExe.exists()}"
                Timber.w(errorMsg)
            }
        } catch (e: IOException) {
            Timber.e("Could not move files: $e")
        }

        output = StringBuilder()
        try {
            val wsOutput = guestProgramLauncherComponent.execShellCommand("wineserver -w")
            output.append(wsOutput)
            Timber.i("Result of wineserver -w command " + output)
        } catch (e: Exception) {
            Timber.e("Error running wineserver: $e")
        }
        container.setNeedsUnpacking(false)
        Timber.d("Setting needs unpacking to false")
        container.saveData()
    } catch (e: Exception) {
        Timber.e("Error during unpacking: $e")
        onError?.invoke("Error during unpacking: ${e.message}")
    } finally {
        // no-op
    }
}

private fun extractArm64ecInputDLLs(context: Context, container: Container) {
    val inputAsset = "arm64ec_input_dlls.tzst"
    val imageFs = ImageFs.find(context)
    val wineVersion: String? = container.getWineVersion()
    Log.d("XServerDisplayActivity", "arm64ec Input DLL Extraction Verification: Container Wine version: " + wineVersion)

    // Check if the wineVersion string is not null and contains "arm64ec"
    if (wineVersion != null && wineVersion.contains("proton-9.0-arm64ec")) {
        val wineFolder: File = File(imageFs.getWinePath() + "/lib/wine/")
        Log.d("XServerDisplayActivity", "Wine version contains arm64ec. Extracting input dlls to " + wineFolder.getPath())
        val success: Boolean = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, inputAsset, wineFolder)
        if (!success) {
            Log.d("XServerDisplayActivity", "Failed to extract input dlls")
        }
    } else {
        // Updated log message for clarity
        Log.d("XServerDisplayActivity", "Wine version is not arm64ec, skipping input dlls extraction.")
    }
}

private fun extractx86_64InputDlls(context: Context, container: Container) {
    val inputAsset = "x86_64_input_dlls.tzst"
    val imageFs = ImageFs.find(context)
    val wineVersion: String? = container.getWineVersion()
    Log.d("XServerDisplayActivity", "x86_64 Input DLL Extraction Verification: Container Wine version: " + wineVersion)
    if ("proton-9.0-x86_64" == wineVersion) {
        val wineFolder: File = File(imageFs.getWinePath() + "/lib/wine/")
        Log.d("XServerDisplayActivity", "Extracting input dlls to " + wineFolder.getPath())
    } else Log.d("XServerDisplayActivity", "Wine version is not proton-9.0-x86_64, skipping input dlls extraction")
}

private fun setupWineSystemFiles(
    context: Context,
    firstTimeBoot: Boolean,
    screenInfo: ScreenInfo,
    xServerState: MutableState<XServerState>,
    // xServerViewModel: XServerViewModel,
    container: Container,
    containerManager: ContainerManager,
    // shortcut: Shortcut?,
    envVars: EnvVars,
    onExtractFileListener: OnExtractFileListener?,
) {
    val imageFs = ImageFs.find(context)
    val appVersion = AppUtils.getVersionCode(context).toString()
    val imgVersion = imageFs.getVersion().toString()
    val wineVersion = imageFs.getArch()
    val variant = imageFs.getVariant()
    var containerDataChanged = false

    if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("imgVersion").equals(imgVersion) ||
        container.containerVariant != variant || (container.containerVariant == variant && container.wineVersion != wineVersion)) {
        applyGeneralPatches(context, container, imageFs, xServerState.value.wineInfo, containerManager, onExtractFileListener)
        container.putExtra("appVersion", appVersion)
        container.putExtra("imgVersion", imgVersion)
        containerDataChanged = true
    }

    // Normalize dxwrapper for state (dxvk includes version for extraction switch)
    if (xServerState.value.dxwrapper == "dxvk") {
        xServerState.value = xServerState.value.copy(
            dxwrapper = "dxvk-" + xServerState.value.dxwrapperConfig?.get("version"),
        )
    }

    // Also normalize VKD3D to include version like vkd3d-<version>
    if (xServerState.value.dxwrapper == "vkd3d") {
        xServerState.value = xServerState.value.copy(
            dxwrapper = "vkd3d-" + xServerState.value.dxwrapperConfig?.get("vkd3dVersion"),
        )
    }

    val needReextract = xServerState.value.dxwrapper != container.getExtra("dxwrapper") || container.wineVersion != wineVersion

    Timber.i("needReextract is " + needReextract)
    Timber.i("xServerState.value.dxwrapper is " + xServerState.value.dxwrapper)
    Timber.i("container.getExtra(\"dxwrapper\") is " + container.getExtra("dxwrapper"))

    if (needReextract) {
        extractDXWrapperFiles(
            context,
            firstTimeBoot,
            container,
            containerManager,
            xServerState.value.dxwrapper,
            imageFs,
            onExtractFileListener,
        )
        container.putExtra("dxwrapper", xServerState.value.dxwrapper)
        containerDataChanged = true
    }

    if (xServerState.value.dxwrapper == "cnc-ddraw") envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini")

    // val wincomponents = if (shortcut != null) shortcut.getExtra("wincomponents", container.winComponents) else container.winComponents
    val wincomponents = container.winComponents
    if (!wincomponents.equals(container.getExtra("wincomponents"))) {
        extractWinComponentFiles(context, firstTimeBoot, imageFs, container, containerManager, onExtractFileListener)
        container.putExtra("wincomponents", wincomponents)
        containerDataChanged = true
    }

    val desktopTheme = container.desktopTheme
    if ((desktopTheme + "," + screenInfo) != container.getExtra("desktopTheme")) {
        WineThemeManager.apply(context, WineThemeManager.ThemeInfo(desktopTheme), screenInfo)
        container.putExtra("desktopTheme", desktopTheme + "," + screenInfo)
        containerDataChanged = true
    }

    WineStartMenuCreator.create(context, container)
    WineUtils.createDosdevicesSymlinks(container)

    val startupSelection = container.startupSelection.toString()
    if (startupSelection != container.getExtra("startupSelection")) {
        WineUtils.changeServicesStatus(container, container.startupSelection != Container.STARTUP_SELECTION_NORMAL)
        container.putExtra("startupSelection", startupSelection)
        containerDataChanged = true
    }

    if (containerDataChanged) container.saveData()
}

private fun applyGeneralPatches(
    context: Context,
    container: Container,
    imageFs: ImageFs,
    wineInfo: WineInfo,
    containerManager: ContainerManager,
    onExtractFileListener: OnExtractFileListener?,
) {
    Timber.i("Applying general patches")
    val rootDir = imageFs.getRootDir()
    val contentsManager = ContentsManager(context)
    if (container.containerVariant.equals(Container.GLIBC)) {
        FileUtils.delete(File(rootDir, "/opt/apps"))
        Timber.i("Extracting imagefs_patches_gamenative.tzst")
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD,
            context.assets,
            "imagefs_patches_gamenative.tzst",
            rootDir,
            onExtractFileListener,
        )
    } else {
        Timber.i("Extracting container_pattern_common.tzst")
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "container_pattern_common.tzst", rootDir);
        Timber.i("Attempting to extract _container_pattern.tzst with wine version " + container.wineVersion)
    }
    containerManager.extractContainerPatternFile(container.getWineVersion(), contentsManager, container.rootDir, null)
    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "pulseaudio.tzst", File(context.filesDir, "pulseaudio"))
    WineUtils.applySystemTweaks(context, wineInfo)
    container.putExtra("graphicsDriver", null)
    container.putExtra("desktopTheme", null)
    WinlatorPrefManager.init(context)
    WinlatorPrefManager.putString("current_box64_version", "")
}
private fun extractDXWrapperFiles(
    context: Context,
    firstTimeBoot: Boolean,
    container: Container,
    containerManager: ContainerManager,
    dxwrapper: String,
    imageFs: ImageFs,
    onExtractFileListener: OnExtractFileListener?,
) {
    val dlls = arrayOf(
        "d3d10.dll",
        "d3d10_1.dll",
        "d3d10core.dll",
        "d3d11.dll",
        "d3d12.dll",
        "d3d12core.dll",
        "d3d8.dll",
        "d3d9.dll",
        "dxgi.dll",
        "ddraw.dll",
    )
    val splitDxWrapper = dxwrapper.split("-")[0]
    if (firstTimeBoot && splitDxWrapper != "vkd3d") cloneOriginalDllFiles(imageFs, *dlls)
    val rootDir = imageFs.getRootDir()
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")

    when (splitDxWrapper) {
        "wined3d" -> {
            restoreOriginalDllFiles(context, container, containerManager, imageFs, *dlls)
        }
        "cnc-ddraw" -> {
            restoreOriginalDllFiles(context, container, containerManager, imageFs, *dlls)
            val assetDir = "dxwrapper/cnc-ddraw-" + DefaultVersion.CNC_DDRAW
            val configFile = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/ProgramData/cnc-ddraw/ddraw.ini")
            if (!configFile.isFile) FileUtils.copy(context, "$assetDir/ddraw.ini", configFile)
            val shadersDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/ProgramData/cnc-ddraw/Shaders")
            FileUtils.delete(shadersDir)
            FileUtils.copy(context, "$assetDir/Shaders", shadersDir)
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context.assets,
                "$assetDir/ddraw.tzst", windowsDir, onExtractFileListener,
            )
        }
        "vkd3d" -> {
            Timber.i("Extracting VKD3D D3D12 DLLs for dxwrapper: $dxwrapper")
            // Determine graphics driver to choose DXVK version
            val vortekLike = container.graphicsDriver == "vortek" || container.graphicsDriver == "adreno" || container.graphicsDriver == "sd-8-elite"
            val dxvkVersionForVkd3d = if (vortekLike && GPUHelper.vkGetApiVersion() < GPUHelper.vkMakeVersion(1, 3, 0)) "1.10.3" else "2.4.1"
            Timber.i("Extracting VKD3D DX version for dxwrapper: $dxvkVersionForVkd3d")
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context.assets,
                "dxwrapper/dxvk-${dxvkVersionForVkd3d}.tzst", windowsDir, onExtractFileListener,
            )
            // Determine VKD3D version from state config
            Timber.i("Extracting VKD3D D3D12 DLLs version: $dxwrapper")

            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context.assets,
                "dxwrapper/$dxwrapper.tzst",
                windowsDir,
                onExtractFileListener,
            )
        }
        else -> {
            // This block handles dxvk-VERSION strings
            Timber.i("Extracting DXVK/D8VK DLLs for dxwrapper: $dxwrapper")
            restoreOriginalDllFiles(context, container, containerManager, imageFs, "d3d12.dll", "d3d12core.dll", "ddraw.dll")
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context.assets,
                "dxwrapper/$dxwrapper.tzst", windowsDir, onExtractFileListener,
            )
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context.assets,
                "dxwrapper/d8vk-${DefaultVersion.D8VK}.tzst",
                windowsDir,
                onExtractFileListener,
            )
        }
    }
}
private fun cloneOriginalDllFiles(imageFs: ImageFs, vararg dlls: String) {
    val rootDir = imageFs.rootDir
    val cacheDir = File(rootDir, ImageFs.CACHE_PATH + "/original_dlls")
    if (!cacheDir.isDirectory) cacheDir.mkdirs()
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
    val dirnames = arrayOf("system32", "syswow64")

    for (dll in dlls) {
        for (dirname in dirnames) {
            val dllFile = File(windowsDir, "$dirname/$dll")
            if (dllFile.isFile) FileUtils.copy(dllFile, File(cacheDir, "$dirname/$dll"))
        }
    }
}
private fun restoreOriginalDllFiles(
    context: Context,
    container: Container,
    containerManager: ContainerManager,
    imageFs: ImageFs,
    vararg dlls: String,
) {
    val rootDir = imageFs.rootDir
    if (container.containerVariant.equals(Container.GLIBC)) {
        val cacheDir = File(rootDir, ImageFs.CACHE_PATH + "/original_dlls")
        val contentsManager = ContentsManager(context)
        if (cacheDir.isDirectory) {
            val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
            val dirnames = cacheDir.list()
            var filesCopied = 0

            for (dll in dlls) {
                var success = false
                for (dirname in dirnames!!) {
                    val srcFile = File(cacheDir, "$dirname/$dll")
                    val dstFile = File(windowsDir, "$dirname/$dll")
                    if (FileUtils.copy(srcFile, dstFile)) success = true
                }
                if (success) filesCopied++
            }

            if (filesCopied == dlls.size) return
        }

        containerManager.extractContainerPatternFile(
            container.wineVersion, contentsManager, container.rootDir,
            object : OnExtractFileListener {
                override fun onExtractFile(file: File, size: Long): File? {
                    val path = file.path
                    if (path.contains("system32/") || path.contains("syswow64/")) {
                        for (dll in dlls) {
                            if (path.endsWith("system32/$dll") || path.endsWith("syswow64/$dll")) return file
                        }
                    }
                    return null
                }
            },
        )

        cloneOriginalDllFiles(imageFs, *dlls)
    } else {
        val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        var system32dlls: File? = null
        var syswow64dlls: File? = null

        if (container.wineVersion.contains("arm64ec")) system32dlls = File(imageFs.getWinePath() + "/lib/wine/aarch64-windows")
        else system32dlls = File(imageFs.getWinePath() + "/lib/wine/x86_64-windows")

        syswow64dlls = File(imageFs.getWinePath() + "/lib/wine/i386-windows")


        for (dll in dlls) {
            var srcFile = File(system32dlls, dll)
            var dstFile = File(windowsDir, "system32/" + dll)
            FileUtils.copy(srcFile, dstFile)
            srcFile = File(syswow64dlls, dll)
            dstFile = File(windowsDir, "syswow64/" + dll)
            FileUtils.copy(srcFile, dstFile)
        }
    }
}
private fun extractWinComponentFiles(
    context: Context,
    firstTimeBoot: Boolean,
    imageFs: ImageFs,
    container: Container,
    containerManager: ContainerManager,
    // shortcut: Shortcut?,
    onExtractFileListener: OnExtractFileListener?,
) {
    val rootDir = imageFs.rootDir
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
    val systemRegFile = File(rootDir, ImageFs.WINEPREFIX + "/system.reg")

    try {
        val wincomponentsJSONObject = JSONObject(FileUtils.readString(context, "wincomponents/wincomponents.json"))
        val dlls = mutableListOf<String>()
        // val wincomponents = if (shortcut != null) shortcut.getExtra("wincomponents", container.winComponents) else container.winComponents
        val wincomponents = container.winComponents

        if (firstTimeBoot) {
            for (wincomponent in KeyValueSet(wincomponents)) {
                val dlnames = wincomponentsJSONObject.getJSONArray(wincomponent[0])
                for (i in 0 until dlnames.length()) {
                    val dlname = dlnames.getString(i)
                    dlls.add(if (!dlname.endsWith(".exe")) "$dlname.dll" else dlname)
                }
            }

            cloneOriginalDllFiles(imageFs, *dlls.toTypedArray())
            dlls.clear()
        }

        val oldWinComponentsIter = KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator()

        for (wincomponent in KeyValueSet(wincomponents)) {
            try {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue
            } catch (e: StringIndexOutOfBoundsException) {
                Timber.d("Wincomponent ${wincomponent[0]} does not exist in oldwincomponents, skipping")
            }
            val identifier = wincomponent[0]
            val useNative = wincomponent[1].equals("1")

            if (!container.wineVersion.contains("proton-9.0-arm64ec") && identifier.contains("opengl") && useNative) continue

            if (useNative) {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, context.assets,
                    "wincomponents/$identifier.tzst", windowsDir, onExtractFileListener,
                )
            } else {
                val dlnames = wincomponentsJSONObject.getJSONArray(identifier)
                for (i in 0 until dlnames.length()) {
                    val dlname = dlnames.getString(i)
                    dlls.add(if (!dlname.endsWith(".exe")) "$dlname.dll" else dlname)
                }
            }
            WineUtils.overrideWinComponentDlls(context, container, identifier, useNative)
            WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative)
        }

        if (!dlls.isEmpty()) restoreOriginalDllFiles(context, container, containerManager, imageFs, *dlls.toTypedArray())
    } catch (e: JSONException) {
        Timber.e("Failed to read JSON: $e")
    }
}

private fun extractGraphicsDriverFiles(
    context: Context,
    graphicsDriver: String,
    dxwrapper: String,
    dxwrapperConfig: KeyValueSet,
    container: Container,
    envVars: EnvVars,
    firstTimeBoot: Boolean,
) {
    if (container.containerVariant.equals(Container.GLIBC)) {
        // Get the configured driver version or use default
        val turnipVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "turnip" } ?: DefaultVersion.TURNIP
        val virglVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "virgl" } ?: DefaultVersion.VIRGL
        val zinkVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "zink" } ?: DefaultVersion.ZINK
        val adrenoVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "adreno" } ?: DefaultVersion.ADRENO
        val sd8EliteVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "sd-8-elite" } ?: DefaultVersion.SD8ELITE

        var cacheId = graphicsDriver
        if (graphicsDriver == "turnip") {
            cacheId += "-" + turnipVersion + "-" + zinkVersion
            if (turnipVersion == "25.2.0" || turnipVersion == "25.3.0") {
                if (GPUInformation.isAdreno710_720_732(context)) {
                    envVars.put("TU_DEBUG", "gmem");
                } else {
                    envVars.put("TU_DEBUG", "sysmem");
                }
            }
        } else if (graphicsDriver == "virgl") {
            cacheId += "-" + DefaultVersion.VIRGL
        } else if (graphicsDriver == "vortek" || graphicsDriver == "adreno" || graphicsDriver == "sd-8-elite") {
            cacheId += "-" + DefaultVersion.VORTEK
        }

        val imageFs = ImageFs.find(context)
        val configDir = imageFs.configDir
        val sentinel = File(configDir, ".current_graphics_driver")   // lives in shared tree
        val onDiskId = sentinel.takeIf { it.exists() }?.readText() ?: ""
        val changed = cacheId != container.getExtra("graphicsDriver") || cacheId != onDiskId
        Timber.i("Changed is " + changed + " will re-extract drivers accordingly.")
        val rootDir = imageFs.rootDir
        envVars.put("vblank_mode", "0")

        if (changed) {
            FileUtils.delete(File(imageFs.lib32Dir, "libvulkan_freedreno.so"))
            FileUtils.delete(File(imageFs.lib64Dir, "libvulkan_freedreno.so"))
            FileUtils.delete(File(imageFs.lib64Dir, "libvulkan_vortek.so"))
            FileUtils.delete(File(imageFs.lib32Dir, "libvulkan_vortek.so"))
            FileUtils.delete(File(imageFs.lib32Dir, "libGL.so.1.7.0"))
            FileUtils.delete(File(imageFs.lib64Dir, "libGL.so.1.7.0"))
            val vulkanICDDir = File(rootDir, "/usr/share/vulkan/icd.d")
            FileUtils.delete(vulkanICDDir)
            vulkanICDDir.mkdirs()
            container.putExtra("graphicsDriver", cacheId)
            container.saveData()
            if (!sentinel.exists()) {
                sentinel.parentFile?.mkdirs()
                sentinel.createNewFile()
            }
            sentinel.writeText(cacheId)
        }
        if (dxwrapper.contains("dxvk")) {
            DXVKHelper.setEnvVars(context, dxwrapperConfig, envVars)
        } else if (dxwrapper.contains("vkd3d")) {
            DXVKHelper.setVKD3DEnvVars(context, dxwrapperConfig, envVars)
        }

        if (graphicsDriver == "turnip") {
            envVars.put("GALLIUM_DRIVER", "zink")
            envVars.put("TU_OVERRIDE_HEAP_SIZE", "4096")
            if (!envVars.has("MESA_VK_WSI_PRESENT_MODE")) envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox")
            envVars.put("vblank_mode", "0")

            if (!GPUInformation.isAdreno6xx(context) && !GPUInformation.isAdreno710_720_732(context)) {
                val userEnvVars = EnvVars(container.envVars)
                val tuDebug = userEnvVars.get("TU_DEBUG")
                if (!tuDebug.contains("sysmem")) userEnvVars.put("TU_DEBUG", (if (!tuDebug.isEmpty()) "$tuDebug," else "") + "sysmem")
                container.envVars = userEnvVars.toString()
            }

            if (changed) {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context.assets,
                    "graphics_driver/turnip-${turnipVersion}.tzst",
                    rootDir,
                )
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    context.assets,
                    "graphics_driver/zink-${zinkVersion}.tzst",
                    rootDir,
                )
            }
        } else if (graphicsDriver == "virgl") {
            envVars.put("GALLIUM_DRIVER", "virpipe")
            envVars.put("VIRGL_NO_READBACK", "true")
            envVars.put("VIRGL_SERVER_PATH", UnixSocketConfig.VIRGL_SERVER_PATH)
            envVars.put("MESA_EXTENSION_OVERRIDE", "-GL_EXT_vertex_array_bgra")
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.1")
            envVars.put("vblank_mode", "0")
            if (changed) {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, context.assets,
                    "graphics_driver/virgl-${virglVersion}.tzst", rootDir,
                )
            }
        } else if (graphicsDriver == "vortek") {
            Timber.i("Setting Vortek env vars")
            envVars.put("GALLIUM_DRIVER", "zink")
            envVars.put("ZINK_CONTEXT_THREADED", "1")
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3")
            envVars.put("WINEVKUSEPLACEDADDR", "1")
            envVars.put("VORTEK_SERVER_PATH", imageFs.getRootDir().getPath() + UnixSocketConfig.VORTEK_SERVER_PATH)
            Timber.i("dxwrapper is " + dxwrapper)
            if (dxwrapper.contains("dxvk")) {
                envVars.put("WINE_D3D_CONFIG", "renderer=gdi")
            }
            if (changed) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "graphics_driver/vortek-2.1.tzst", rootDir)
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "graphics_driver/zink-22.2.5.tzst", rootDir)
            }
        } else if (graphicsDriver == "adreno" || graphicsDriver == "sd-8-elite") {
            val assetZip = if (graphicsDriver == "adreno") "Adreno_${adrenoVersion}_adpkg.zip" else "SD8Elite_${sd8EliteVersion}.zip"

            val componentRoot = com.winlator.core.GeneralComponents.getComponentDir(
                com.winlator.core.GeneralComponents.Type.ADRENOTOOLS_DRIVER,
                context,
            )

            // Read manifest name from zip to determine folder name
            val identifier = readZipManifestNameFromAssets(context, assetZip) ?: assetZip.substringBeforeLast('.')

            // Only (re)extract if changed
            val adrenoCacheId = "${graphicsDriver}-${identifier}"
            val needsExtract = changed || adrenoCacheId != container.getExtra("graphicsDriverAdreno")

            if (needsExtract) {
                val destinationDir = File(componentRoot.toString())
                if (destinationDir.isDirectory) {
                    FileUtils.delete(destinationDir)
                }
                destinationDir.mkdirs()
                com.winlator.core.FileUtils.extractZipFromAssets(context, assetZip, destinationDir)

                val targetLibName = "vulkan.adreno.so"

                // Update cache and only the adrenotoolsDriver key within graphics driver config
                container.putExtra("graphicsDriverAdreno", adrenoCacheId)
                container.saveData()
            }
            envVars.put("GALLIUM_DRIVER", "zink")
            envVars.put("ZINK_CONTEXT_THREADED", "1")
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3")
            envVars.put("WINEVKUSEPLACEDADDR", "1")
            envVars.put("VORTEK_SERVER_PATH", imageFs.getRootDir().getPath() + UnixSocketConfig.VORTEK_SERVER_PATH)
            Timber.i("dxwrapper is " + dxwrapper)
            if (dxwrapper.contains("dxvk")) {
                envVars.put("WINE_D3D_CONFIG", "renderer=gdi")
            }
            if (changed) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "graphics_driver/vortek-2.1.tzst", rootDir)
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "graphics_driver/zink-22.2.5.tzst", rootDir)
            }
        }
    } else {
        var adrenoToolsDriverId: String? = ""
        val selectedDriverVersion: String?
        val graphicsDriverConfig = KeyValueSet(container.getGraphicsDriverConfig())
        val imageFs = ImageFs.find(context)

        val currentWrapperVersion: String? = graphicsDriverConfig.get("version", DefaultVersion.WRAPPER)
        val isAdrenotoolsTurnip: String? = graphicsDriverConfig.get("adrenotoolsTurnip", "1") // Default to "1"

        selectedDriverVersion = currentWrapperVersion

        adrenoToolsDriverId =
            if (selectedDriverVersion!!.contains(DefaultVersion.WRAPPER)) DefaultVersion.WRAPPER else selectedDriverVersion
        Log.d("GraphicsDriverExtraction", "Adrenotools DriverID: " + adrenoToolsDriverId)

        val rootDir: File? = imageFs.getRootDir()

        if (dxwrapper.contains("dxvk")) {
            DXVKHelper.setEnvVars(context, dxwrapperConfig, envVars)
        } else if (dxwrapper.contains("vkd3d")) {
            DXVKHelper.setVKD3DEnvVars(context, dxwrapperConfig, envVars)
        }


        val useDRI3: Boolean = container.isUseDRI3
        if (!useDRI3) {
            envVars.put("MESA_VK_WSI_DEBUG", "sw")
        }

        if (currentWrapperVersion.lowercase(Locale.getDefault())
                .contains("turnip") && isAdrenotoolsTurnip == "0"
        ) envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir().path + "/vulkan/icd.d/freedreno_icd.aarch64.json")
        else envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir().path + "/vulkan/icd.d/wrapper_icd.aarch64.json")
        envVars.put("GALLIUM_DRIVER", "zink")
        envVars.put("LIBGL_KOPPER_DISABLE", "true")


        //        if (firstTimeBoot) {
//            Log.d("XServerDisplayActivity", "First time container boot, re-extracting wrapper");
//            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/wrapper" + ".tzst", rootDir);
//            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/extra_libs" + ".tzst", rootDir);
//        }

        // 1. Get the main WRAPPER selection (e.g., "Wrapper-v2") from the class field.
        val mainWrapperSelection: String = graphicsDriver


        // 2. Get the WRAPPER that was last saved to the container's settings.
        val lastInstalledMainWrapper = container.getExtra("lastInstalledMainWrapper")


        // 3. Check if we need to extract a new wrapper file.
        if (firstTimeBoot || mainWrapperSelection != lastInstalledMainWrapper) {
            // We only extract if the selection is actually a wrapper file.
            if (mainWrapperSelection.lowercase(Locale.getDefault()).startsWith("wrapper")) {
                val assetPath = "graphics_driver/" + mainWrapperSelection.lowercase(Locale.getDefault()) + ".tzst"
                Log.d("GraphicsDriverExtraction", "WRAPPER selection changed or first boot. Extracting: " + assetPath)
                val success: Boolean = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), assetPath, rootDir)
                if (success) {
                    // After success, save the new version so we don't re-extract next time.
                    container.putExtra("lastInstalledMainWrapper", mainWrapperSelection)
                    container.saveData()
                }
            }

            // 4. Extract common libraries, but only when the container is first created.
            if (firstTimeBoot) {
                Log.d("XServerDisplayActivity", "First time container boot, extracting extra_libs.tzst")
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "graphics_driver/extra_libs.tzst", rootDir)
            }
        }

        if (adrenoToolsDriverId !== "System") {
            val adrenotoolsManager: AdrenotoolsManager = AdrenotoolsManager(context)
            adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId)
        }
        val blacklistedExtensions: String? = graphicsDriverConfig.get("blacklistedExtensions")
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions)

        val maxDeviceMemory: String? = graphicsDriverConfig.get("maxDeviceMemory", "0")
        if (maxDeviceMemory != null && maxDeviceMemory.toInt() > 0) envVars.put("UTIL_LAYER_VMEM_MAX_SIZE", maxDeviceMemory)

        val frameSync: String? = graphicsDriverConfig.get("frameSync", "Normal")
        if (frameSync == "Always" && useDRI3) {
            envVars.put("MESA_VK_WSI_DEBUG", "forcesync")
        } else if (frameSync == "Never") {
            envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", "1")
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox")
//        if (!vkbasaltConfig.isEmpty()) {
//            envVars.put("ENABLE_VKBASALT", "1")
//            envVars.put("VKBASALT_CONFIG", vkbasaltConfig)
//        }
    }
}

private fun readZipManifestNameFromAssets(context: Context, assetName: String): String? {
    return com.winlator.core.FileUtils.readZipManifestNameFromAssets(context, assetName)
}

private fun readLibraryNameFromExtractedDir(destinationDir: File): String? {
    return try {
        val manifests = destinationDir.listFiles { _, name -> name.endsWith(".json") }
        if (manifests != null && manifests.isNotEmpty()) {
            val manifest = manifests[0]
            val content = com.winlator.core.FileUtils.readString(manifest)
            val json = org.json.JSONObject(content)
            val libraryName = json.optString("libraryName", "").trim()
            if (libraryName.isNotEmpty()) libraryName else null
        } else null
    } catch (_: Exception) {
        null
    }
}
private fun changeWineAudioDriver(audioDriver: String, container: Container, imageFs: ImageFs) {
    if (audioDriver != container.getExtra("audioDriver")) {
        val rootDir = imageFs.rootDir
        val userRegFile = File(rootDir, ImageFs.WINEPREFIX + "/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
            if (audioDriver == "alsa") {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa")
            } else if (audioDriver == "pulseaudio") {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse")
            }
        }
        container.putExtra("audioDriver", audioDriver)
        container.saveData()
    }
}
private fun setImagefsContainerVariant(context: Context, container: Container) {
    val imageFs = ImageFs.find(context)
    val containerVariant = container.containerVariant
    imageFs.createVariantFile(containerVariant)
    imageFs.createArchFile(container.wineVersion)
}
