package app.gamenative.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.Constants
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.LaunchInfo
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.ui.data.XServerState
import app.gamenative.utils.ContainerUtils
import com.posthog.PostHog
import com.winlator.alsaserver.ALSAClient
import com.winlator.box86_64.rc.RCFile
import com.winlator.box86_64.rc.RCManager
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contentdialog.NavigationDialog
import com.winlator.contents.ContentsManager
import com.winlator.core.AppUtils
import com.winlator.core.AppUtils.showKeyboard
import com.winlator.core.Callback
import com.winlator.core.DXVKHelper
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
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
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.inputcontrols.TouchMouse
import com.winlator.renderer.GLRenderer
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
import kotlin.io.path.name
import com.winlator.PrefManager as WinlatorPrefManager

// TODO logs in composables are 'unstable' which can cause recomposition (performance issues)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun XServerScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    appId: Int,
    bootToContainer: Boolean,
    navigateBack: () -> Unit,
    onExit: () -> Unit,
    onWindowMapped: ((Context, Window) -> Unit)? = null,
    onWindowUnmapped: ((Window) -> Unit)? = null,
    onGameLaunchError: ((String) -> Unit)? = null,
) {
    Timber.i("Starting up XServerScreen")
    val context = LocalContext.current

    // PluviaApp.events.emit(AndroidEvent.SetAppBarVisibility(false))
    PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(false))
    PluviaApp.events.emit(
        AndroidEvent.SetAllowedOrientation(PrefManager.allowedOrientation),
    )

    // seems to be used to indicate when a custom wine is being installed (intent extra "generate_wineprefix")
    // val generateWinePrefix = false
    var firstTimeBoot = false
    var needsUnpacking = false
    var frameRating by remember { mutableStateOf<FrameRating?>(null) }
    var frameRatingWindowId = -1
    var taskAffinityMask = 0
    var taskAffinityMaskWoW64 = 0

    val xServerState = rememberSaveable(stateSaver = XServerState.Saver) {
        if (ContainerUtils.hasContainer(context, appId)) {
            val container = ContainerUtils.getContainer(context, appId)
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

    val appLaunchInfo = SteamService.getAppInfoOf(appId)?.let { appInfo ->
        SteamService.getWindowsLaunchInfos(appId).firstOrNull()
    }

    var currentAppInfo = SteamService.getAppInfoOf(appId)

    var xServerView: XServerView? by remember {
        val result = mutableStateOf<XServerView?>(null)
        Timber.i("Remembering xServerView as $result")
        result
    }

    var win32AppWorkarounds: Win32AppWorkarounds? by remember { mutableStateOf(null) }

    var isKeyboardVisible = false
    var areControlsVisible = false

    BackHandler {
        Timber.i("BackHandler")
        NavigationDialog(
            context,
            object : NavigationDialog.NavigationListener {
                override fun onNavigationItemSelected(itemId: Int) {
                    when (itemId) {
                        NavigationDialog.ACTION_KEYBOARD -> {
                            // Toggle keyboard using InputMethodManager
                            PostHog.capture(event = "onscreen_keyboard_enabled")
                            showKeyboard(context);
                        }

                        NavigationDialog.ACTION_INPUT_CONTROLS -> {
                            if (areControlsVisible){
                                PostHog.capture(event = "onscreen_controller_disabled")
                                hideInputControls();
                            } else {
                                PostHog.capture(event = "onscreen_controller_enabled")
                                val profiles = PluviaApp.inputControlsManager?.getProfiles(false) ?: listOf()
                                if (profiles.isNotEmpty()) {
                                    showInputControls(profiles[2])
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
                handled = xServerView!!.getxServer().winHandler.onKeyEvent(it.event)
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
                handled = xServerView!!.getxServer().winHandler.onGenericMotionEvent(it.event)
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
                    touchMouse?.onTouchEvent(it)
                }

                true
            },
        factory = { context ->
            // Creates view
            // if (PluviaApp.xServer == null) {
            Timber.i("Creating XServerView and XServer")
            // if (PluviaApp.xServerState == null) {
            //     PluviaApp.xServerState = XServerState()
            // }
            // if (PluviaApp.xServer == null) {
            //     PluviaApp.xServer = XServer(ScreenInfo(xServerState.value.screenSize))
            // }
            val frameLayout = FrameLayout(context)
            val xServerView = XServerView(
                context,
                XServer(ScreenInfo(xServerState.value.screenSize)),
            ).apply {
                xServerView = this
                // pointerEventListener = object: Callback<MotionEvent> {
                //     override fun call(event: MotionEvent) {
                //         Log.d("XServerScreen", "onMotionEvent:\n\t$event")
                //         if (TouchMouse.isMouseDevice(event.device)) {
                //             touchMouse?.onExternalMouseEvent(event) == true
                //         }
                //     }
                // }
                // this.addPointerEventListener(pointerEventListener)
                // this.requestFocus()
                // this.setOnCapturedPointerListener(object: View.OnCapturedPointerListener {
                //     override fun onCapturedPointer(
                //         view: View,
                //         event: MotionEvent
                //     ): Boolean {
                //         Log.d("XServerScreen", "onMotionEvent:\n\t$event")
                //         return if (TouchMouse.isMouseDevice(event.device)) {
                //             touchMouse?.onExternalMouseEvent(event) == true
                //         } else false
                //     }
                //
                // })
                // this.requestPointerCapture()
                // this.background = ColorDrawable(Color.Green.toArgb())
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
                    taskAffinityMaskWoW64
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
                                renderer?.setCursorVisible(true)
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

                if (PluviaApp.xEnvironment != null) {
                    PluviaApp.xEnvironment = shiftXEnvironmentToContext(
                        context,
                        xEnvironment = PluviaApp.xEnvironment!!,
                        getxServer(),
                    )
                } else {
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
                    val renderer: GLRenderer = xServerView!!.getRenderer()
                    if (container.isDisableMouseInput()) {
                        renderer.setCursorVisible(false)
                        PluviaApp.touchpadView?.setEnabled(false)
                    } else {
                        renderer.setCursorVisible(true)
                        PluviaApp.touchpadView?.setEnabled(true)
                    }
                    Timber.d("WinHandler configured: preferredInputApi=%s, dinputMapperType=0x%02x", PreferredInputApi.values()[container.inputType], container.dinputMapperType)
                    // Timber.d("1 Container drives: ${container.drives}")
                    containerManager.activateContainer(container)
                    // Timber.d("2 Container drives: ${container.drives}")

                    taskAffinityMask = ProcessHelper.getAffinityMask(container.getCPUList(true)).toShort().toInt()
                    taskAffinityMaskWoW64 = ProcessHelper.getAffinityMask(container.getCPUListWoW64(true)).toShort().toInt()
                    firstTimeBoot = container.getExtra("appVersion").isEmpty()
                    needsUnpacking = container.isNeedsUnpacking
                    Timber.i("First time boot: $firstTimeBoot")

                    val wineVersion = container.wineVersion
                    Timber.i("Wine version is: $wineVersion")
                    Timber.i("Wine info is: " + WineInfo.fromIdentifier(context, wineVersion))
                    xServerState.value = xServerState.value.copy(
                        wineInfo = WineInfo.fromIdentifier(context, wineVersion),
                    )
                    Timber.i("xServerState.value.wineInfo is: " + xServerState.value.wineInfo)
                    Timber.i("WineInfo.MAIN_WINE_VERSION is: " + WineInfo.MAIN_WINE_VERSION)
                    Timber.i("Wine path for wineinfo is " + xServerState.value.wineInfo.path)

                    if (xServerState.value.wineInfo != WineInfo.MAIN_WINE_VERSION) {
                        Timber.i("Settings wine path to: $xServerState.value.wineInfo.path")
                        ImageFs.find(context).setWinePath(xServerState.value.wineInfo.path)
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
                    extractGraphicsDriverFiles(
                        context,
                        xServerState.value.graphicsDriver,
                        xServerState.value.dxwrapper,
                        xServerState.value.dxwrapperConfig!!,
                        container,
                        envVars,
                    )
                    changeWineAudioDriver(xServerState.value.audioDriver, container, ImageFs.find(context))
                    PluviaApp.xEnvironment = setupXEnvironment(
                        context,
                        appId,
                        bootToContainer,
                        xServerState,
                        envVars,
                        container,
                        appLaunchInfo,
                        xServerView!!.getxServer(),
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

                // Load a default controls profile
                val profiles = PluviaApp.inputControlsManager?.getProfiles(false) ?: listOf()
                if (profiles.isNotEmpty()) {
                    setProfile(profiles[2])
                }

                // Set overlay opacity from preferences if needed
                PrefManager.init(context)
                val opacity = PrefManager.getFloat("controls_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY)
                setOverlayOpacity(opacity)
            }
            PluviaApp.inputControlsView = icView

            xServerView.getxServer().winHandler.setInputControlsView(PluviaApp.inputControlsView)

            // Add InputControlsView on top of XServerView
            frameLayout.addView(icView)
            hideInputControls()
            // Show on-screen controls if no physical controller is connected
            if (ExternalController.getController(0) == null) {
                val profiles = PluviaApp.inputControlsManager?.getProfiles(false) ?: listOf()
                if (profiles.size > 2) {
                    showInputControls(profiles[2])
                    areControlsVisible = true
                }
            }
            val container = ContainerUtils.getContainer(context, appId)

            if (container.isShowFPS()) {
                Timber.i("Attempting to show FPS")
                frameRating = FrameRating(context)
                frameRating?.setVisibility(View.GONE)
                frameRating?.let { frameLayout.addView(it) }
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

private fun showInputControls(profile: ControlsProfile) {
    PluviaApp.inputControlsView?.setVisibility(View.VISIBLE)
    PluviaApp.inputControlsView?.requestFocus()
    PluviaApp.inputControlsView?.setProfile(profile)

    PluviaApp.touchpadView?.setSensitivity(profile.getCursorSpeed() * 1.0f)
    PluviaApp.touchpadView?.setPointerButtonRightEnabled(false)

    PluviaApp.inputControlsView?.invalidate()
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
    appId: Int,
    bootToContainer: Boolean,
    xServerState: MutableState<XServerState>,
    // xServerViewModel: XServerViewModel,
    envVars: EnvVars,
    // generateWinePrefix: Boolean,
    container: Container?,
    appLaunchInfo: LaunchInfo?,
    // shortcut: Shortcut?,
    xServer: XServer,
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
    envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1")
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
            "-all"
    )
    // capture debug output to file if either Wine or Box86/64 logging is enabled
    if (enableWineDebug || enableBox86Logs) {
        val wineLogDir = File(context.getExternalFilesDir(null), "wine_logs")
        wineLogDir.mkdirs()
        val logFile = File(wineLogDir, "wine_debug.log")
        if (logFile.exists()) logFile.delete()
        ProcessHelper.addDebugCallback { line ->
            logFile.appendText(line + "\n")
        }
    }

    val rootPath = imageFs.getRootDir().getPath()
    FileUtils.clear(imageFs.getTmpDir())

    val usrGlibc: Boolean = PrefManager.getBoolean("use_glibc", true)
    val guestProgramLauncherComponent = if (usrGlibc) {
        Timber.i("Setting guestProgramLauncherComponent to GlibcProgarmLauncherComponent")
        GlibcProgramLauncherComponent(contentsManager, contentsManager.getProfileByEntryName(container.wineVersion))
    }
    else {
        Timber.i("Setting guestProgramLauncherComponent to GuestProgramLauncherComponent")
        GuestProgramLauncherComponent()
    }

    if (container != null) {
        if (container.startupSelection == Container.STARTUP_SELECTION_AGGRESSIVE) xServer.winHandler.killProcess("services.exe")

        val wow64Mode = container.isWoW64Mode
        //            String guestExecutable = wineInfo.getExecutable(this, wow64Mode)+" explorer /desktop=shell,"+xServer.screenInfo+" "+getWineStartCommand();
        val guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " +
            getWineStartCommand(appId, container, bootToContainer, appLaunchInfo, envVars, guestProgramLauncherComponent) +
            (if (container.execArgs.isNotEmpty()) " " + container.execArgs else "")
        guestProgramLauncherComponent.isWoW64Mode = wow64Mode
        guestProgramLauncherComponent.guestExecutable = guestExecutable

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
            unpackExecutableFile(context, container.isNeedsUnpacking, container, appId, appLaunchInfo, onGameLaunchError)
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
        environment.addComponent(PulseAudioComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH)))
    }

    if (xServerState.value.graphicsDriver == "virgl") {
        environment.addComponent(
            VirGLRendererComponent(
                xServer,
                UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH),
            ),
        )
    } else if (xServerState.value.graphicsDriver == "vortek") {
        Timber.i("Adding VortekRendererComponent to Environment")
        val options2: VortekRendererComponent.Options? = VortekRendererComponent.Options.fromKeyValueSet(KeyValueSet(container.getGraphicsDriverConfig()))
        val vortekRendererComponent: VortekRendererComponent =
            VortekRendererComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options2)
        environment.addComponent(vortekRendererComponent)
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

    // if (generateWinePrefix) {
    //     generateWineprefix(
    //         context,
    //         imageFs,
    //         xServerState.value.wineInfo,
    //         envVars,
    //         environment,
    //     )
    // }
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
    appId: Int,
    container: Container,
    bootToContainer: Boolean,
    appLaunchInfo: LaunchInfo?,
    envVars: EnvVars,
    guestProgramLauncherComponent: GuestProgramLauncherComponent
): String {
    val tempDir = File(container.getRootDir(), ".wine/drive_c/windows/temp")
    FileUtils.clear(tempDir)

    Timber.tag("XServerScreen").d("appLaunchInfo is $appLaunchInfo")
    val args = if (bootToContainer || appLaunchInfo == null) {
        "\"wfm.exe\""
    } else {
        // Check if we should launch through real Steam
        if (container.isLaunchRealSteam()) {
            // Launch Steam with the applaunch parameter to start the game
            "\"C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe\" -silent -vgui -no-browser -tcp " +
                    "-nobigpicture -nobootstrapupdate -skipinitialbootstrap -nofriendsui -nochatui -nointro -applaunch $appId"
        } else {
            // Original logic for direct game launch
            val appDirPath = SteamService.getAppDirPath(appId)
            var executablePath = ""
            if (container.executablePath.isNotEmpty()) {
                executablePath = container.executablePath
            } else {
                executablePath = SteamService.getInstalledExe(appId)
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
    }

    return "winhandler.exe $args"
}
private fun getSteamlessTarget(
    appId: Int,
    container: Container,
    appLaunchInfo: LaunchInfo?,
): String {
    val appDirPath = SteamService.getAppDirPath(appId)
    val executablePath = SteamService.getInstalledExe(appId)
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
    appId: Int,
    appLaunchInfo: LaunchInfo?,
    onError: ((String) -> Unit)? = null,
) {
    if (!needsUnpacking){
        return
    }
    val shellCommandEnvVars = EnvVars()
    try {
        val imageFs = ImageFs.find(context)
        shellCommandEnvVars.put("HOME", imageFs.home_path)
        shellCommandEnvVars.put("USER", ImageFs.USER)
        shellCommandEnvVars.put("TMPDIR", imageFs.getRootDir().getPath() + "/tmp")
        shellCommandEnvVars.put("DISPLAY", ":99")

        val winePath = imageFs.getWinePath() + "/bin"
        shellCommandEnvVars.put(
            "PATH",
            winePath + ":" +
                    imageFs.getRootDir().getPath() + "/usr/bin:" +
                    imageFs.getRootDir().getPath() + "/usr/local/bin",
        )

        shellCommandEnvVars.put("LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib")
        shellCommandEnvVars.put("BOX64_LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib/x86_64-linux-gnu")
        shellCommandEnvVars.put("ANDROID_SYSVSHM_SERVER", imageFs.getRootDir().getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH)
        shellCommandEnvVars.put("FONTCONFIG_PATH", imageFs.getRootDir().getPath() + "/usr/etc/fonts")
        shellCommandEnvVars.put("WINEDLLOVERRIDES", "winex11.drv=b");

        if ((File(imageFs.getGlibc64Dir(), "libandroid-sysvshm.so")).exists() ||
            (File(imageFs.getGlibc32Dir(), "libandroid-sysvshm.so")).exists()
        ) shellCommandEnvVars.put("LD_PRELOAD", "libredirect.so libandroid-sysvshm.so")
        if (!shellCommandEnvVars.has("WINEESYNC")) shellCommandEnvVars.put("WINEESYNC", "1")
        shellCommandEnvVars.put("WINEESYNC_WINLATOR", "1")
        val rootDir: File = imageFs.getRootDir()
        val executableFile = getSteamlessTarget(appId, container, appLaunchInfo)
        var line: String?
        var output = StringBuilder()

        try {
            // a:/.../GameDir/orig_dll_path.txt  (same dir as the EXE inside A:)
            val origTxtFile  = File("${imageFs.wineprefix}/dosdevices/a:/orig_dll_path.txt")

            if (origTxtFile.exists()) {
                val relDllPath = origTxtFile.readText().trim()
                if (relDllPath.isNotBlank()) {
                    val origDll = File("${imageFs.wineprefix}/dosdevices/a:/$relDllPath")
                    if (origDll.exists()) {
                        val shellCommandArray = arrayOf(
                            rootDir.path + "/usr/local/bin/box64",
                            "wine",
                            "z:\\\\generate_interfaces_file.exe",
                            "A:\\" + relDllPath.replace('/', '\\')
                        )
                        Timber.i("Running generate_interfaces_file " + Arrays.toString(shellCommandArray))

                        var line: String?
                        val genOutput = StringBuilder()
                        val genProc = Runtime.getRuntime().exec(
                            shellCommandArray,
                            shellCommandEnvVars.toStringArray(),
                            imageFs.getRootDir()
                        )
                        val genReader     = BufferedReader(InputStreamReader(genProc.inputStream))
                        val genErrReader  = BufferedReader(InputStreamReader(genProc.errorStream))
                        while (genReader.readLine().also { line = it } != null)     genOutput.append(line).append('\n')
                        while (genErrReader.readLine().also { line = it } != null)  genOutput.append(line).append('\n')
                        genProc.waitFor()

                        val origSteamInterfaces = File("${imageFs.wineprefix}/dosdevices/z:/steam_interfaces.txt")
                        if (origSteamInterfaces.exists()) {
                            val finalSteamInterfaces = File(origDll.parent, "steam_interfaces.txt")
                            try {
                                Files.copy(
                                    origSteamInterfaces.toPath(),
                                    finalSteamInterfaces.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING
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

        try {
            val installMonoArray = arrayOf(
                rootDir.getPath() + "/usr/local/bin/box64", "wine", "msiexec", "/i",
                "Z:\\opt\\mono-gecko-offline\\wine-mono-9.0.0-x86.msi",
            )
            Timber.i("Install mono command " + Arrays.toString(installMonoArray))
            val monoProcess = Runtime.getRuntime().exec(installMonoArray, shellCommandEnvVars.toStringArray(), imageFs.getRootDir())
            var reader = BufferedReader(InputStreamReader(monoProcess.getInputStream()))
            var errorReader = BufferedReader(InputStreamReader(monoProcess.getErrorStream()))
            while ((reader.readLine().also { line = it }) != null) {
                output.append(line).append("\n")
            }
            while ((errorReader.readLine().also { line = it }) != null) {
                output.append(line).append("\n")
            }
            monoProcess.waitFor()
            Timber.i("Result of mono command " + output)
        } catch (e: Exception) {
            Timber.e("Error during mono installation: $e")
        }

        output = StringBuilder()
        try {
            val shellCommandArray = arrayOf(
                rootDir.getPath() + "/usr/local/bin/box64", "wine",
                "z:\\\\Steamless\\\\Steamless.CLI.exe", executableFile,
            )
            Timber.i("Running shell command " + Arrays.toString(shellCommandArray))
            val process: Process = Runtime.getRuntime().exec(shellCommandArray, shellCommandEnvVars.toStringArray(), imageFs.getRootDir())
            val reader = BufferedReader(InputStreamReader(process.getInputStream()))
            val errorReader = BufferedReader(InputStreamReader(process.getErrorStream()))
            while ((reader.readLine().also { line = it }) != null) {
                output.append(line).append("\n")
            }
            while ((errorReader.readLine().also { line = it }) != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
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
            val wineserverProcess = Runtime.getRuntime().exec(
                arrayOf(
                    rootDir.getPath() + "/usr/local/bin/box64", "wineserver",
                    "-w",
                ),
                shellCommandEnvVars.toStringArray(), imageFs.getRootDir(),
            )
            val reader = BufferedReader(InputStreamReader(wineserverProcess.getInputStream()))
            val errorReader = BufferedReader(InputStreamReader(wineserverProcess.getErrorStream()))
            while ((reader.readLine().also { line = it }) != null) {
                output.append(line).append("\n")
            }
            while ((errorReader.readLine().also { line = it }) != null) {
                output.append(line).append("\n")
            }
            wineserverProcess.waitFor()
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
        shellCommandEnvVars.put("DISPLAY", ":0")
    }
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
    var containerDataChanged = false

    if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("imgVersion").equals(imgVersion)) {
        applyGeneralPatches(context, container, imageFs, xServerState.value.wineInfo, onExtractFileListener)
        container.putExtra("appVersion", appVersion)
        container.putExtra("imgVersion", imgVersion)
        containerDataChanged = true
    }

    // val dxwrapper = this.dxwrapper
    if (xServerState.value.dxwrapper == "dxvk") {
        xServerState.value = xServerState.value.copy(
            dxwrapper = "dxvk-" + xServerState.value.dxwrapperConfig?.get("version"),
        )
    }

    if (xServerState.value.dxwrapper != container.getExtra("dxwrapper")) {
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
        // extractWinComponentFiles(context, firstTimeBoot, imageFs, container, containerManager, shortcut, onExtractFileListener)
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
    onExtractFileListener: OnExtractFileListener?,
) {
    Timber.i("Applying general patches")
    val rootDir = imageFs.getRootDir()
    FileUtils.delete(File(rootDir, "/opt/apps"))
    Timber.i("Applying imagefs_patches_gamenative.tzst")
    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "imagefs_patches_gamenative.tzst", rootDir, onExtractFileListener)
    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "pulseaudio.tzst", File(context.filesDir, "pulseaudio"))
    WineUtils.applySystemTweaks(context, wineInfo)
    container.putExtra("graphicsDriver", null)
    container.putExtra("desktopTheme", null)
    // SettingsFragment.resetBox86_64Version(context)
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
    if (firstTimeBoot && dxwrapper != "vkd3d") cloneOriginalDllFiles(imageFs, *dlls)
    val rootDir = imageFs.getRootDir()
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")

    when (dxwrapper) {
        "wined3d" -> {
            restoreOriginalDllFiles(container, containerManager, imageFs, *dlls)
        }
        "cnc-ddraw" -> {
            restoreOriginalDllFiles(container, containerManager, imageFs, *dlls)
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
            val dxvkVersions = context.resources.getStringArray(R.array.dxvk_version_entries)
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context.assets,
                "dxwrapper/dxvk-" + (dxvkVersions[dxvkVersions.size - 1]) + ".tzst", windowsDir, onExtractFileListener,
            )
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context.assets,
                "dxwrapper/vkd3d-" + DefaultVersion.VKD3D + ".tzst",
                windowsDir,
                onExtractFileListener,
            )
        }
        else -> {
            // This block handles dxvk-VERSION strings
            Timber.i("Extracting DXVK/D8VK DLLs for dxwrapper: $dxwrapper")
            restoreOriginalDllFiles(container, containerManager, imageFs, "d3d12.dll", "d3d12core.dll", "ddraw.dll")
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
    container: Container,
    containerManager: ContainerManager,
    imageFs: ImageFs,
    vararg dlls: String,
) {
    val rootDir = imageFs.rootDir
    val cacheDir = File(rootDir, ImageFs.CACHE_PATH + "/original_dlls")
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
        container.wineVersion, container.rootDir,
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
            if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue
            val identifier = wincomponent[0]
            val useNative = wincomponent[1].equals("1")

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

            WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative)
        }

        if (!dlls.isEmpty()) restoreOriginalDllFiles(container, containerManager, imageFs, *dlls.toTypedArray())
        WineUtils.overrideWinComponentDlls(context, container, wincomponents)
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
) {
    // Get the configured driver version or use default
    val turnipVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "turnip" } ?: DefaultVersion.TURNIP
    val virglVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "virgl" } ?: DefaultVersion.VIRGL
    val zinkVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "zink" } ?: DefaultVersion.ZINK

    var cacheId = graphicsDriver
    if (graphicsDriver == "turnip") {
        cacheId += "-" + turnipVersion + "-" + zinkVersion
        if (turnipVersion == "25.2.0"){
            if (GPUInformation.isAdreno710_720_732(context)) {
                envVars.put("TU_DEBUG", "gmem");
            } else {
                envVars.put("TU_DEBUG", "sysmem");
            }
        }
    } else if (graphicsDriver == "virgl") {
        cacheId += "-" + DefaultVersion.VIRGL
    } else if (graphicsDriver == "vortek") {
        cacheId += "-" + DefaultVersion.VORTEK
    }

    val changed = cacheId != container.getExtra("graphicsDriver")
    val imageFs = ImageFs.find(context)
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
    }

    if (graphicsDriver == "turnip") {
        if (dxwrapper.contains("dxvk")) {
            DXVKHelper.setEnvVars(context, dxwrapperConfig, envVars)
        } else if (dxwrapper.contains("vkd3d")) {
            envVars.put("VKD3D_FEATURE_LEVEL", "12_2")
        }

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

        val useDRI3 = PrefManager.getBoolean("use_dri3", true)
        if (!useDRI3) {
            envVars.put("MESA_VK_WSI_PRESENT_MODE", "immediate")
            envVars.put("MESA_VK_WSI_DEBUG", "sw")
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
            dxwrapperConfig.put("constantBufferRangeCheck", "1")
            DXVKHelper.setEnvVars(context, dxwrapperConfig, envVars)
        }
        if (changed) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "graphics_driver/vortek-1.0.tzst", rootDir)
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, "graphics_driver/zink-22.2.5.tzst", rootDir)
        }
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
