package app.gamenative.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.service.epic.EpicService
import app.gamenative.service.GameSessionManager
import app.gamenative.service.gog.GOGService
import app.gamenative.service.SteamService
import app.gamenative.ui.data.XServerState
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.SteamTokenLogin
import app.gamenative.utils.SteamUtils
import com.posthog.PostHog
import com.winlator.PrefManager as WinlatorPrefManager
import com.winlator.alsaserver.ALSAClient
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contentdialog.NavigationDialog
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
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
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.name
import kotlin.text.lowercase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

// Always re-extract drivers and DXVK on every launch to handle cases of container corruption
// where games randomly stop working. Set to false once corruption issues are resolved.
private const val ALWAYS_REEXTRACT = true

// Guard to prevent duplicate game_exited events when multiple exit triggers fire simultaneously
private val isExiting = AtomicBoolean(false)

// TODO logs in composables are 'unstable' which can cause recomposition (performance issues)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun XServerScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    appId: String,
    bootToContainer: Boolean,
    testGraphics: Boolean = false,
    registerBackAction: (() -> Unit) -> Unit,
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

    PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(false))
    PluviaApp.events.emit(
        AndroidEvent.SetAllowedOrientation(PrefManager.allowedOrientation),
    )

    var firstTimeBoot = false
    var needsUnpacking = false
    var containerVariantChanged = false
    var frameRating by remember { mutableStateOf<FrameRating?>(null) }
    var frameRatingWindowId = -1
    var vkbasaltConfig = ""
    var taskAffinityMask = 0
    var taskAffinityMaskWoW64 = 0

    val container = remember(appId) {
        ContainerUtils.getContainer(context, appId)
    }

    val xServerState = rememberSaveable(stateSaver = XServerState.Saver) {
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
    }

    var touchMouse by remember {
        val result = mutableStateOf<TouchMouse?>(null)
        Timber.i("Remembering touchMouse as $result")
        result
    }
    var keyboard by remember { mutableStateOf<Keyboard?>(null) }

    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
    val appLaunchInfo = SteamService.getAppInfoOf(gameId)?.let {
        SteamService.getWindowsLaunchInfos(gameId).firstOrNull()
    }

    var currentAppInfo = SteamService.getAppInfoOf(gameId)

    var xServerView: XServerView? by remember {
        val result = mutableStateOf<XServerView?>(null)
        Timber.i("Remembering xServerView as $result")
        result
    }

    var win32AppWorkarounds: Win32AppWorkarounds? by remember { mutableStateOf(null) }
    var physicalControllerHandler: PhysicalControllerHandler? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        onDispose {
            physicalControllerHandler?.cleanup()
            physicalControllerHandler = null
            GameSessionManager.clearSession()
        }
    }
    var isKeyboardVisible = false
    var areControlsVisible by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }
    var elementPositionsSnapshot by remember { mutableStateOf<Map<com.winlator.inputcontrols.ControlElement, Pair<Int, Int>>>(emptyMap()) }
    var showElementEditor by remember { mutableStateOf(false) }
    var elementToEdit by remember { mutableStateOf<com.winlator.inputcontrols.ControlElement?>(null) }
    var showPhysicalControllerDialog by remember { mutableStateOf(false) }

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
                            val anchor = view 
                            val c = if (Build.VERSION.SDK_INT >= 30) {
                                anchor.windowInsetsController
                            } else {
                                null
                            }

                            anchor.post {
                                if (anchor.windowToken == null) return@post
                                val show = { 
                                    PostHog.capture(event = "onscreen_keyboard_enabled")
                                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                                }
                                if (Build.VERSION.SDK_INT > 29 && c != null) {
                                    anchor.postDelayed({ show() }, 500)
                                } else {
                                    show()
                                }
                            }
                        }

                        NavigationDialog.ACTION_INPUT_CONTROLS -> {
                            if (areControlsVisible) {
                                PostHog.capture(event = "onscreen_controller_disabled")
                                hideInputControls()
                            } else {
                                PostHog.capture(event = "onscreen_controller_enabled")
                                val manager = PluviaApp.inputControlsManager
                                val profiles = manager?.getProfiles(false) ?: listOf()
                                if (profiles.isNotEmpty()) {
                                    val profileIdStr = container.getExtra("profileId", "0")
                                    val profileId = profileIdStr.toIntOrNull() ?: 0
                                    val targetProfile = if (profileId != 0) {
                                        manager?.getProfile(profileId)
                                    } else {
                                        null
                                    } ?: manager?.getProfile(0) ?: profiles.getOrNull(2) ?: profiles.first()

                                    showInputControls(targetProfile, xServerView!!.getxServer().winHandler, container)
                                }
                            }
                            areControlsVisible = !areControlsVisible
                        }

                        NavigationDialog.ACTION_EDIT_CONTROLS -> {
                            PostHog.capture(event = "edit_controls_in_game")

                            val manager = PluviaApp.inputControlsManager ?: InputControlsManager(context)
                            val allProfiles = manager.getProfiles(false)

                            val profileIdStr = container.getExtra("profileId", "0")
                            val profileId = profileIdStr.toIntOrNull() ?: 0

                            var activeProfile = if (profileId != 0) {
                                manager.getProfile(profileId)
                            } else {
                                null
                            }

                            if (activeProfile == null) {
                                val sourceProfile = manager.getProfile(0)
                                    ?: allProfiles.firstOrNull { it.id == 2 }
                                    ?: allProfiles.firstOrNull()

                                if (sourceProfile != null) {
                                    try {
                                        activeProfile = manager.duplicateProfile(sourceProfile)
                                        val gameName = currentAppInfo?.name ?: container.name
                                        activeProfile.setName("$gameName - Controls")
                                        activeProfile.save()
                                        container.putExtra("profileId", activeProfile.id.toString())
                                        container.saveData()
                                        PluviaApp.inputControlsView?.setProfile(activeProfile)
                                    } catch (e: Exception) {
                                        Timber.e(e, "Failed to auto-create profile for container %s", container.name)
                                        activeProfile = sourceProfile
                                    }
                                }
                            }

                            if (activeProfile != null) {
                                val profile = PluviaApp.inputControlsView?.profile
                                if (profile != null) {
                                    val snapshot = mutableMapOf<com.winlator.inputcontrols.ControlElement, Pair<Int, Int>>()
                                    profile.elements.forEach { element ->
                                        snapshot[element] = Pair(element.x.toInt(), element.y.toInt())
                                    }
                                    elementPositionsSnapshot = snapshot
                                }

                                isEditMode = true
                                PluviaApp.inputControlsView?.setEditMode(true)
                                PluviaApp.inputControlsView?.let { icView ->
                                    icView.post {
                                        activeProfile.loadElements(icView)
                                    }
                                }

                                if (!areControlsVisible) {
                                    showInputControls(activeProfile, xServerView!!.getxServer().winHandler, container)
                                    areControlsVisible = true
                                }
                            }
                        }

                        NavigationDialog.ACTION_EDIT_PHYSICAL_CONTROLLER -> {
                            PostHog.capture(event = "edit_physical_controller_from_menu")
                            showPhysicalControllerDialog = true
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
                            exit(xServerView!!.getxServer().winHandler, PluviaApp.xEnvironment, frameRating, currentAppInfo, container, onExit, navigateBack)
                        }
                    }
                }
            },
        ).show()
    }

    DisposableEffect(container) {
        registerBackAction(gameBack)
        onDispose {
            Timber.d("XServerScreen leaving, clearing back action")
            registerBackAction { }
        }
    }

    DisposableEffect(lifecycleOwner, container) {
        val onActivityDestroyed: (AndroidEvent.ActivityDestroyed) -> Unit = {
            Timber.i("onActivityDestroyed")
            exit(xServerView!!.getxServer().winHandler, PluviaApp.xEnvironment, frameRating, currentAppInfo, container, onExit, navigateBack)
        }
        val onKeyEvent: (AndroidEvent.KeyEvent) -> Boolean = {
            val isKeyboard = Keyboard.isKeyboardDevice(it.event.device)
            val isGamepad = ExternalController.isGameController(it.event.device)

            var handled = false
            if (isGamepad) {
                handled = physicalControllerHandler?.onKeyEvent(it.event) == true
                if (!handled) handled = PluviaApp.inputControlsView?.onKeyEvent(it.event) == true
                if (!handled) handled = xServerView!!.getxServer().winHandler.onKeyEvent(it.event)
            }
            if (!handled && isKeyboard) {
                handled = keyboard?.onKeyEvent(it.event) == true
            }
            handled
        }
        val onMotionEvent: (AndroidEvent.MotionEvent) -> Boolean = {
            val isGamepad = ExternalController.isGameController(it.event?.device)

            var handled = false
            if (isGamepad && it.event != null) {
                handled = physicalControllerHandler?.onGenericMotionEvent(it.event!!) == true
                if (!handled) handled = PluviaApp.inputControlsView?.onGenericMotionEvent(it.event) == true
                if (!handled) handled = xServerView!!.getxServer().winHandler.onGenericMotionEvent(it.event)
            }
            if (!handled) {
                handled = PluviaApp.touchpadView?.onExternalMouseEvent(it.event) == true
            }
            handled
        }
        val onGuestProgramTerminated: (AndroidEvent.GuestProgramTerminated) -> Unit = {
            Timber.i("onGuestProgramTerminated")
            exit(xServerView!!.getxServer().winHandler, PluviaApp.xEnvironment, frameRating, currentAppInfo, container, onExit, navigateBack)
        }
        val onForceCloseApp: (SteamEvent.ForceCloseApp) -> Unit = {
            Timber.i("onForceCloseApp")
            exit(xServerView!!.getxServer().winHandler, PluviaApp.xEnvironment, frameRating, currentAppInfo, container, onExit, navigateBack)
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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerHoverIcon(PointerIcon(0))
                .pointerInteropFilter {
                    val controlsHandled = if (areControlsVisible) {
                        PluviaApp.inputControlsView?.onTouchEvent(it) ?: false
                    } else {
                        false
                    }

                    if (!controlsHandled) {
                        PluviaApp.touchpadView?.onTouchEvent(it)
                    }

                    true
                },
            factory = { context ->
                Timber.i("Creating XServerView and XServer")
                val frameLayout = FrameLayout(context)
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
                    PluviaApp.touchpadView = 
                        TouchpadView(context, getxServer(), PrefManager.getBoolean("capture_pointer_on_external_mouse", true))
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
                        appLaunchInfo?.let { renderer.forceFullscreenWMClass = Paths.get(it.executable).name }
                    }
                    getxServer().windowManager.addOnWindowModificationListener(
                        object : WindowManager.OnWindowModificationListener {
                            private fun changeFrameRatingVisibility(window: Window, property: Property?) {
                                if (frameRating == null) return
                                if (property != null) {
                                    if (frameRatingWindowId == -1 &&
                                        (
                                            property.nameAsString().contains("_UTIL_LAYER") ||
                                                property.nameAsString().contains("_MESA_DRV") ||
                                                container.containerVariant.equals(Container.GLIBC) &&
                                                property.nameAsString().contains("_NET_WM_SURFACE")
                                            )
                                    ) {
                                        frameRatingWindowId = window.id
                                        (context as? Activity)?.runOnUiThread {
                                            frameRating?.visibility = View.VISIBLE
                                        }
                                        frameRating?.update()
                                    }
                                } else if (frameRatingWindowId != -1) {
                                    frameRatingWindowId = -1
                                    (context as? Activity)?.runOnUiThread {
                                        frameRating?.visibility = View.GONE
                                    }
                                }
                            }
                            override fun onUpdateWindowContent(window: Window) {
                                if (!xServerState.value.winStarted && window.isApplicationWindow()) {
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
                                        "\nhasParent: ${window.parent != null}" +
                                        "\nchildrenSize: ${window.children.size}",
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
                                        "\nhasParent: ${window.parent != null}" +
                                        "\nchildrenSize: ${window.children.size}",
                                )
                                changeFrameRatingVisibility(window, null)
                                onWindowUnmapped?.invoke(window)
                            }
                        },
                    )

                    if (PluviaApp.xEnvironment == null) {
                        val setupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                            Thread(r, "WineSetup-Thread").apply { isDaemon = false }
                        }

                        setupExecutor.submit {
                            try {
                                val containerManager = ContainerManager(context)
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
                                containerManager.activateContainer(container)
                                val imageFs = ImageFs.find(context)

                                taskAffinityMask = ProcessHelper.getAffinityMask(container.getCPUList(true)).toShort().toInt()
                                taskAffinityMaskWoW64 = ProcessHelper.getAffinityMask(container.getCPUListWoW64(true)).toShort().toInt()
                                containerVariantChanged = container.containerVariant != imageFs.variant
                                firstTimeBoot = container.getExtra("appVersion").isEmpty() || containerVariantChanged
                                needsUnpacking = container.isNeedsUnpacking

                                val wineVersion = container.wineVersion
                                val contentsManager = ContentsManager(context)
                                contentsManager.syncContents()
                                xServerState.value = xServerState.value.copy(
                                    wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion),
                                )

                                if (!xServerState.value.wineInfo.isMainWineVersion()) {
                                    imageFs.setWinePath(xServerState.value.wineInfo.path)
                                } else {
                                    imageFs.setWinePath(imageFs.rootDir.path + "/opt/wine")
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

                                val sharpnessEffect: String = container.getExtra("sharpnessEffect", "None")
                                if (sharpnessEffect != "None") {
                                    val sharpnessLevel = container.getExtra("sharpnessLevel", "100").toDouble()
                                    val sharpnessDenoise = container.getExtra("sharpnessDenoise", "100").toDouble()
                                    vkbasaltConfig =
                                        "effects=" + sharpnessEffect.lowercase(Locale.getDefault()) + ";" + "casSharpness=" +
                                        sharpnessLevel / 100 +
                                        ";" +
                                        "dlsSharpness=" +
                                        sharpnessLevel / 100 +
                                        ";" +
                                        "dlsDenoise=" +
                                        sharpnessDenoise / 100 +
                                        ";" +
                                        "enableOnLaunch=True"
                                }

                                val envVars = EnvVars()

                                setupWineSystemFiles(
                                    context,
                                    firstTimeBoot,
                                    xServerView!!.getxServer().screenInfo,
                                    xServerState,
                                    container,
                                    containerManager,
                                    envVars,
                                    contentsManager,
                                    onExtractFileListener,
                                )
                                extractArm64ecInputDLLs(context, container)
                                extractx86_64InputDlls(context, container)
                                extractGraphicsDriverFiles(
                                    context,
                                    xServerState.value.graphicsDriver,
                                    xServerState.value.dxwrapper,
                                    xServerState.value.dxwrapperConfig!!,
                                    container,
                                    envVars,
                                    firstTimeBoot,
                                    vkbasaltConfig,
                                )
                                changeWineAudioDriver(xServerState.value.audioDriver, container, ImageFs.find(context))
                                setImagefsContainerVariant(context, container)
                                PluviaApp.xEnvironment = setupXEnvironment(
                                    context,
                                    appId,
                                    bootToContainer,
                                    testGraphics,
                                    xServerState,
                                    envVars,
                                    container,
                                    appLaunchInfo,
                                    xServerView!!.getxServer(),
                                    containerVariantChanged,
                                    onGameLaunchError,
                                    navigateBack,
                                )
                            } catch (e: Exception) {
                                Timber.e(e, "Error during wine setup operations")
                                onGameLaunchError?.invoke("Failed to setup wine: ${e.message}")
                            } finally {
                                setupExecutor.shutdown()
                            }
                        }
                    }
                }
                PluviaApp.xServerView = xServerView

                frameLayout.addView(xServerView)

                PluviaApp.inputControlsManager = InputControlsManager(context)

                var loadedProfile: ControlsProfile? = null

                val icView = InputControlsView(context).apply {
                    setXServer(xServerView.getxServer())
                    setTouchpadView(PluviaApp.touchpadView)

                    val manager = PluviaApp.inputControlsManager
                    val profiles = manager?.getProfiles(false) ?: listOf()
                    PrefManager.init(context)

                    if (profiles.isNotEmpty()) {
                        val profileIdStr = container.getExtra("profileId", "0")
                        val profileId = profileIdStr.toIntOrNull() ?: 0
                        val customProfile = if (profileId != 0) manager?.getProfile(profileId) else null

                        val targetProfile = if (customProfile != null) {
                            customProfile
                        } else {
                            val fallback = manager?.getProfile(0) ?: profiles.getOrNull(2) ?: profiles.first()
                            fallback
                        }
                        setProfile(targetProfile)

                        physicalControllerHandler = PhysicalControllerHandler(targetProfile, xServerView.getxServer(), gameBack)
                        loadedProfile = targetProfile
                    }

                    val opacity = PrefManager.getFloat("controls_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY)
                    setOverlayOpacity(opacity)
                }
                PluviaApp.inputControlsView = icView

                xServerView.getxServer().winHandler.setInputControlsView(PluviaApp.inputControlsView)

                frameLayout.addView(icView)

                icView.post {
                    loadedProfile?.let { profile ->
                        if (!profile.isElementsLoaded) {
                            profile.loadElements(icView)
                        }

                        if (profile.elements.isNotEmpty()) {
                            val controllerManager = ControllerManager.getInstance()
                            controllerManager.scanForDevices()
                            val hasPhysicalController = controllerManager.getDetectedDevices().isNotEmpty()

                            val shouldShowControls = when {
                                container.isTouchscreenMode -> false
                                hasPhysicalController -> false
                                else -> true
                            }

                            if (shouldShowControls) {
                                showInputControls(profile, xServerView.getxServer().winHandler, container)
                                areControlsVisible = true
                            } else {
                                hideInputControls()
                                areControlsVisible = false
                            }
                        }
                    }
                }
                frameRating = FrameRating(context)
                frameRating?.setVisibility(View.GONE)

                if (container.isShowFPS()) {
                    frameRating?.let { frameLayout.addView(it) }
                }

                if (container.isDisableMouseInput) {
                    PluviaApp.touchpadView?.setTouchscreenMouseDisabled(true)
                }

                frameLayout
            },
            update = { view ->
            },
            onRelease = { view ->
            },
        )

        if (isEditMode && areControlsVisible) {
            EditModeToolbar(
                onAdd = {
                    if (PluviaApp.inputControlsView?.addElement() == true) {
                        PluviaApp.inputControlsView?.invalidate()
                    }
                },
                onEdit = {
                    val selectedElement = PluviaApp.inputControlsView?.getSelectedElement()
                    if (selectedElement != null) {
                        elementToEdit = selectedElement
                        showElementEditor = true
                    }
                },
                onDelete = {
                    PluviaApp.inputControlsView?.removeElement()
                },
                onSave = {
                    PluviaApp.inputControlsView?.profile?.save()
                    elementPositionsSnapshot = emptyMap()
                    isEditMode = false
                    PluviaApp.inputControlsView?.setEditMode(false)
                    PluviaApp.inputControlsView?.post {
                        PluviaApp.inputControlsView?.invalidate()
                    }
                },
                onClose = {
                    if (elementPositionsSnapshot.isNotEmpty()) {
                        elementPositionsSnapshot.forEach { (element, position) ->
                            element.setX(position.first)
                            element.setY(position.second)
                        }
                        elementPositionsSnapshot = emptyMap()
                    }

                    isEditMode = false
                    PluviaApp.inputControlsView?.setEditMode(false)
                    PluviaApp.inputControlsView?.post {
                        PluviaApp.inputControlsView?.profile?.loadElements(PluviaApp.inputControlsView)
                        PluviaApp.inputControlsView?.profile?.save()
                        PluviaApp.inputControlsView?.invalidate()
                    }
                },
                onDuplicate = { id ->
                    val manager = PluviaApp.inputControlsManager
                    val profile = manager?.getProfile(id)
                    val currentProfile = PluviaApp.inputControlsView?.profile
                    if (profile != null && currentProfile != null) {
                        PluviaApp.inputControlsView?.let { icView ->
                            icView.post {
                                profile.loadElements(icView)
                                val elementsToRemove = currentProfile.elements.toList()
                                elementsToRemove.forEach { currentProfile.removeElement(it) }

                                profile.elements.forEach { element ->
                                    val newElement = com.winlator.inputcontrols.ControlElement(icView)
                                    newElement.setType(element.type)
                                    newElement.setShape(element.shape)
                                    newElement.setX(element.x.toInt())
                                    newElement.setY(element.y.toInt())
                                    newElement.setScale(element.scale)
                                    newElement.setText(element.text)
                                    newElement.setIconId(element.iconId.toInt())
                                    newElement.setToggleSwitch(element.isToggleSwitch)
                                    for (i in 0 until 4) {
                                        newElement.setBindingAt(i, element.getBindingAt(i))
                                    }
                                    currentProfile.addElement(newElement)
                                }

                                icView.invalidate()
                                android.widget.Toast.makeText(context, context.getString(R.string.toast_controls_reset), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            )
        }
    }

    if (showElementEditor && elementToEdit != null && PluviaApp.inputControlsView != null) {
        app.gamenative.ui.component.dialog.ElementEditorDialog(
            element = elementToEdit!!,
            view = PluviaApp.inputControlsView!!,
            onDismiss = {
                showElementEditor = false
            },
            onSave = {
                showElementEditor = false
            },
        )
    }

    if (showPhysicalControllerDialog) {
        val manager = PluviaApp.inputControlsManager ?: InputControlsManager(context)
        val profileIdStr = container.getExtra("profileId", "0")
        val profileId = profileIdStr.toIntOrNull() ?: 0

        // Get profile, but don't load profile 0 directly (will duplicate if needed)
        var profile = if (profileId != 0) {
            manager.getProfile(profileId)
        } else {
            null  // Will create new profile below
        }

        // Auto-create profile if using default (profile 0)
        if (profile == null) {
            val allProfiles = manager.getProfiles(false)
            val sourceProfile = manager.getProfile(0)
                ?: allProfiles.firstOrNull { it.id == 2 }
                ?: allProfiles.firstOrNull()

            if (sourceProfile != null) {
                try {
                    // Duplicate profile 0 to create game-specific profile
                    profile = manager.duplicateProfile(sourceProfile)

                    // Rename to game name
                    val gameName = currentAppInfo?.name ?: container.name
                    profile.setName("$gameName - Physical Controller")
                    profile.save()

                    // Associate with container
                    container.putExtra("profileId", profile.id.toString())
                    container.saveData()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to auto-create profile for container ${container.name}")
                    profile = sourceProfile  // Fallback
                }
            }
        }

        if (profile != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showPhysicalControllerDialog = false },
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.95f)),
                ) {
                    app.gamenative.ui.component.dialog.PhysicalControllerConfigSection(
                        profile = profile,
                        onDismiss = { showPhysicalControllerDialog = false },
                        onSave = {
                            // Ensure controllersLoaded is true before saving
                            // (addController sets the flag even if controller already exists)
                            profile.addController("*")

                            // Save profileId to container so it persists across launches
                            container.putExtra("profileId", profile.id.toString())
                            container.saveData()

                            // Save profile (will now write controllers since controllersLoaded = true)
                            profile.save()
                            profile.loadControllers()

                            if (PluviaApp.inputControlsView?.profile != null) {
                                PluviaApp.inputControlsView?.setProfile(profile)
                            }
                            physicalControllerHandler?.setProfile(profile)
                            showPhysicalControllerDialog = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditModeToolbar(
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onDuplicate: (Int) -> Unit,
) {
    var duplicateProfileOpen by remember { mutableStateOf(false) }
    var toolbarOffsetX by remember { mutableStateOf(0f) }
    var toolbarOffsetY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    Box(
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        modifier = Modifier
            .offset(x = toolbarOffsetX.dp, y = toolbarOffsetY.dp)
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(top = 16.dp)
            .pointerInput(density) {
                detectDragGestures {
                    change, dragAmount ->
                    change.consume()
                    toolbarOffsetX += dragAmount.x / density.density
                    toolbarOffsetY += dragAmount.y / density.density
                }
            },
    ) {
        Row(
            modifier = Modifier
                .wrapContentSize()
                .background(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Drag to move",
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(end = 4.dp),
            )

            TextButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.add), color = androidx.compose.ui.graphics.Color.White)
            }

            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.edit), color = androidx.compose.ui.graphics.Color.White)
            }

            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.delete), color = androidx.compose.ui.graphics.Color.White)
            }

            Box {
                TextButton(onClick = { duplicateProfileOpen = !duplicateProfileOpen }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy From", tint = androidx.compose.ui.graphics.Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.copy_from), color = androidx.compose.ui.graphics.Color.White)
                }

                val knownProfiles = PluviaApp.inputControlsManager?.getProfiles(false) ?: emptyList()
                if (knownProfiles.isNotEmpty()) {
                    DropdownMenu(
                        expanded = duplicateProfileOpen,
                        onDismissRequest = { duplicateProfileOpen = false },
                    ) {
                        for (knownProfile in knownProfiles) {
                            DropdownMenuItem(
                                text = { Text(knownProfile.name) },
                                onClick = {
                                    onDuplicate(knownProfile.id)
                                    duplicateProfileOpen = false
                                },
                            )
                        }
                    }
                }
            }

            TextButton(onClick = onSave) {
                Icon(Icons.Default.Check, contentDescription = "Save", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.save), color = androidx.compose.ui.graphics.Color.White)
            }

            TextButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = androidx.compose.ui.graphics.Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.close), color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

private fun showInputControls(profile: ControlsProfile, winHandler: WinHandler, container: Container) {
    profile.setVirtualGamepad(true)

    PluviaApp.inputControlsView?.let { icView ->
        if (!profile.isElementsLoaded || icView.width == 0 || icView.height == 0) {
            if (icView.width == 0 || icView.height == 0) {
                icView.post {
                    profile.loadElements(icView)
                    icView.setProfile(profile)
                    icView.setShowTouchscreenControls(true)
                    icView.setVisibility(View.VISIBLE)
                    icView.requestFocus()
                    icView.invalidate()
                }
            } else {
                profile.loadElements(icView)
                icView.setProfile(profile)
                icView.setShowTouchscreenControls(true)
                icView.setVisibility(View.VISIBLE)
                icView.requestFocus()
                icView.invalidate()
            }
        } else {
            icView.setProfile(profile)
            icView.setShowTouchscreenControls(true)
            icView.setVisibility(View.VISIBLE)
            icView.requestFocus()
            icView.invalidate()
        }
    }

    PluviaApp.touchpadView?.setSensitivity(profile.getCursorSpeed() * 1.0f)
    PluviaApp.touchpadView?.setPointerButtonRightEnabled(false)

    if (container.containerVariant.equals(Container.BIONIC) && profile.isVirtualGamepad()) {
        val controllerManager: ControllerManager = ControllerManager.getInstance()
        controllerManager.setSlotEnabled(0, true)
        controllerManager.unassignSlot(0)
        if (winHandler != null) {
            winHandler.refreshControllerMappings()
        }
    }
}

private fun hideInputControls() {
    PluviaApp.inputControlsView?.setShowTouchscreenControls(false)
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

fun showInputControls(context: Context, show: Boolean) {
    PluviaApp.inputControlsView?.let { icView ->
        if (show) {
            icView.profile?.let { profile ->
                profile.loadElements(icView)
            }
        }
        icView.setShowTouchscreenControls(show)
        icView.invalidate()
    }
}

fun selectControlsProfile(context: Context, profileId: Int) {
    PluviaApp.inputControlsManager?.getProfile(profileId)?.let { profile ->
        PluviaApp.inputControlsView?.setProfile(profile)
        PluviaApp.inputControlsView?.invalidate()
    }
}

fun setControlsOpacity(context: Context, opacity: Float) {
    PluviaApp.inputControlsView?.let { icView ->
        icView.setOverlayOpacity(opacity)
        icView.invalidate()
        PrefManager.init(context)
        PrefManager.setFloat("controls_opacity", opacity)
    }
}

fun toggleControlsEditMode(context: Context, editMode: Boolean) {
    PluviaApp.inputControlsView?.let { icView ->
        icView.setEditMode(editMode)
        icView.invalidate()
    }
}

fun addControlElement(context: Context): Boolean {
    return PluviaApp.inputControlsView?.addElement() ?: false
}

fun removeControlElement(context: Context): Boolean {
    return PluviaApp.inputControlsView?.removeElement() ?: false
}

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
        return
    }
    if (processId > 0) {
        winHandler.setProcessAffinity(processId, processAffinity)
    } else if (!className.isEmpty()) {
        winHandler.setProcessAffinity(window.getClassName(), processAffinity)
    }
}

private fun setupXEnvironment(
    context: Context,
    appId: String,
    bootToContainer: Boolean,
    testGraphics: Boolean,
    xServerState: MutableState<XServerState>,
    envVars: EnvVars,
    container: Container?,
    appLaunchInfo: LaunchInfo?,
    xServer: XServer,
    containerVariantChanged: Boolean,
    onGameLaunchError: ((String) -> Unit)? = null,
    navigateBack: () -> Unit,
): XEnvironment {
    val lc_all = container!!.lC_ALL
    val imageFs = ImageFs.find(context)

    val contentsManager = ContentsManager(context)
    contentsManager.syncContents()
    envVars.put("LC_ALL", lc_all)
    envVars.put("MESA_DEBUG", "silent")
    envVars.put("MESA_NO_ERROR", "1")
    envVars.put("WINEPREFIX", imageFs.wineprefix)
    if (container.isShowFPS) {
        envVars.put("DXVK_HUD", "fps,frametimes")
        envVars.put("VK_INSTANCE_LAYERS", "VK_LAYER_MESA_overlay")
        envVars.put("MESA_OVERLAY_SHOW_FPS", 1)
    }
    if (container.isSdlControllerAPI) {
        if (container.inputType == PreferredInputApi.XINPUT.ordinal || container.inputType == PreferredInputApi.AUTO.ordinal) {
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
    val enableWineDebug = PrefManager.enableWineDebug
    val enableBox86Logs = WinlatorPrefManager.getBoolean("enable_box86_64_logs", false)
    val wineDebugChannels = PrefManager.wineDebugChannels
    envVars.put(
        "WINEDEBUG",
        if (enableWineDebug && wineDebugChannels.isNotEmpty()) {
            "+" + wineDebugChannels.replace(",", ",+")
        } else {
            "-all"
        },
    )
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
        GlibcProgramLauncherComponent(
            contentsManager,
            contentsManager.getProfileByEntryName(container.wineVersion),
        )
    } else {
        BionicProgramLauncherComponent(
            contentsManager,
            contentsManager.getProfileByEntryName(container.wineVersion),
        )
    }

    if (container != null) {
        if (container.startupSelection == Container.STARTUP_SELECTION_AGGRESSIVE) {
            if (container.containerVariant.equals(Container.BIONIC)) {
                container.startupSelection = Container.STARTUP_SELECTION_ESSENTIAL
                container.putExtra("startupSelection", java.lang.String.valueOf(Container.STARTUP_SELECTION_ESSENTIAL))
                container.saveData()
            } else {
                xServer.winHandler.killProcess("services.exe")
            }
        }

        val wow64Mode = container.isWoW64Mode
        guestProgramLauncherComponent.setContainer(container)
        guestProgramLauncherComponent.setWineInfo(xServerState.value.wineInfo)

        val wineStartCommand =
            getWineStartCommand(context, appId, container, bootToContainer, testGraphics, appLaunchInfo, envVars, guestProgramLauncherComponent)
        
        if (wineStartCommand == null) {
            val guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " \"explorer.exe\""
            guestProgramLauncherComponent.isWoW64Mode = wow64Mode
            guestProgramLauncherComponent.guestExecutable = guestExecutable
            guestProgramLauncherComponent.setSteamType(container.getSteamType())
        } else {
            val guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " +
                wineStartCommand +
                (if (container.execArgs.isNotEmpty()) " " + container.execArgs else "")
            guestProgramLauncherComponent.isWoW64Mode = wow64Mode
            guestProgramLauncherComponent.guestExecutable = guestExecutable
            guestProgramLauncherComponent.setSteamType(container.getSteamType())
        }

        envVars.putAll(container.envVars)
        if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1")

        val bindingPaths = mutableListOf<String>()
        for (drive in container.drivesIterator()) {
            bindingPaths.add(drive[1])
        }
        guestProgramLauncherComponent.bindingPaths = bindingPaths.toTypedArray()
        guestProgramLauncherComponent.box64Version = container.box64Version
        guestProgramLauncherComponent.box86Version = container.box86Version
        guestProgramLauncherComponent.box86Preset = container.box86Preset
        guestProgramLauncherComponent.box64Preset = container.box64Preset
        guestProgramLauncherComponent.setPreUnpack {
            unpackExecutableFile(
                context = context,
                needsUnpacking = container.isNeedsUnpacking,
                container = container,
                appId = appId,
                appLaunchInfo = appLaunchInfo,
                guestProgramLauncherComponent = guestProgramLauncherComponent,
                containerVariantChanged = containerVariantChanged,
                onError = onGameLaunchError
            )
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
    } else if (xServerState.value.graphicsDriver == "vortek" ||
        xServerState.value.graphicsDriver == "adreno"
    ) {
        val gcfg = KeyValueSet(container.getGraphicsDriverConfig())
        val graphicsDriver = xServerState.value.graphicsDriver
        if (graphicsDriver == "adreno") {
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
            navigateBack()
        }
        PluviaApp.events.emit(AndroidEvent.GuestProgramTerminated)
    }
    environment.addComponent(guestProgramLauncherComponent)

    // Moved here, as guestProgramLauncherComponent.environment is setup after addComponent()
    if (container != null) {
        if (container.isLaunchRealSteam) {
            SteamTokenLogin(
                steamId = PrefManager.steamUserSteamId64.toString(),
                login = PrefManager.username,
                token = PrefManager.refreshToken,
                imageFs = imageFs,
                guestProgramLauncherComponent = guestProgramLauncherComponent,
            ).setupSteamFiles()
        }
    }

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

    // Request encrypted app ticket for Steam games at launch time
    val isCustomGame = ContainerUtils.extractGameSourceFromContainerId(appId) == GameSource.CUSTOM_GAME
    val gameIdForTicket = ContainerUtils.extractGameIdFromContainerId(appId)
    if (!bootToContainer && !isCustomGame && gameIdForTicket != null && !container.isLaunchRealSteam) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ticket = SteamService.instance?.getEncryptedAppTicket(gameIdForTicket)
                if (ticket != null) {
                    Timber.i("Successfully retrieved encrypted app ticket for app $gameIdForTicket")
                } else {
                    Timber.w("Failed to retrieve encrypted app ticket for app $gameIdForTicket")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error requesting encrypted app ticket for app $gameIdForTicket")
            }
        }
    }

    environment.startEnvironmentComponents()

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
    context: Context,
    appId: String,
    container: Container,
    bootToContainer: Boolean,
    testGraphics: Boolean,
    appLaunchInfo: LaunchInfo?,
    envVars: EnvVars,
    guestProgramLauncherComponent: GuestProgramLauncherComponent,
): String? {
    val tempDir = File(container.getRootDir(), ".wine/drive_c/windows/temp")
    FileUtils.clear(tempDir)

    val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
    val isCustomGame = gameSource == GameSource.CUSTOM_GAME
    val isGOGGame = gameSource == GameSource.GOG
    val isEpicGame = gameSource == GameSource.EPIC
    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)

    if (!isCustomGame && !isGOGGame && !isEpicGame) {
        if (container.executablePath.isEmpty()) {
            container.executablePath = SteamService.getInstalledExe(gameId)
            container.saveData()
        }
        if (!container.isUseLegacyDRM) {
            SteamUtils.writeColdClientIni(gameId, container)
        }
    }

    if (isGOGGame) {
        val libraryItem = LibraryItem(
            appId = appId,
            name = "",
            gameSource = GameSource.GOG,
        )

        val gogCommand = GOGService.getWineStartCommand(
            context = context,
            libraryItem = libraryItem,
            container = container,
            bootToContainer = bootToContainer,
            appLaunchInfo = appLaunchInfo,
            envVars = envVars,
            guestProgramLauncherComponent = guestProgramLauncherComponent,
        )

        return "winhandler.exe $gogCommand"
    } else if (isEpicGame) {
        val libraryItem = LibraryItem(
            appId = appId,
            name = "",
            gameSource = GameSource.EPIC,
        )

        val epicCommand = EpicService.getWineStartCommand(
            context = context,
            libraryItem = libraryItem,
            container = container,
            bootToContainer = bootToContainer,
            appLaunchInfo = appLaunchInfo,
            envVars = envVars,
            guestProgramLauncherComponent = guestProgramLauncherComponent,
        )

        return "winhandler.exe $epicCommand"
    }

    val args = if (testGraphics) {
        "\"Z:/opt/apps/TestD3D.exe\""
    } else if (bootToContainer) {
        "\"wfm.exe\""
    } else if (isCustomGame) {
        var executablePath = container.executablePath

        var gameFolderPath: String? = null
        for (drive in Container.drivesIterator(container.drives)) {
            if (drive[0] == "A") {
                gameFolderPath = drive[1]
                break
            }
        }

        if (executablePath.isEmpty()) {
            if (gameFolderPath == null) {
                return "winhandler.exe \"wfm.exe\""
            }
            val auto = CustomGameScanner.findUniqueExeRelativeToFolder(gameFolderPath!!)
            if (auto != null) {
                executablePath = auto
                container.executablePath = auto
                container.saveData()
            } else {
                return "winhandler.exe \"wfm.exe\""
            }
        }

        if (gameFolderPath == null) {
            return "winhandler.exe \"wfm.exe\""
        }

        val executableDir = gameFolderPath + "/" + executablePath.substringBeforeLast("/", "")
        guestProgramLauncherComponent.workingDir = File(executableDir)

        val normalizedPath = executablePath.replace('/', '\\')
        envVars.put("WINEPATH", "A:\\")
        "\"A:\\${normalizedPath}\""
    } else if (appLaunchInfo == null) {
        "\"wfm.exe\""
    } else {
        if (container.isLaunchRealSteam) {
            // Launch Steam with the applaunch parameter to start the game
            "\"C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe\" -silent -vgui -tcp " +
                    "-nobigpicture -nofriendsui -nochatui -nointro -applaunch $gameId"
        } else {
            var executablePath = ""
            if (container.executablePath.isNotEmpty()) {
                executablePath = container.executablePath
            } else {
                executablePath = SteamService.getInstalledExe(gameId)
                container.executablePath = executablePath
                container.saveData()
            }
            if (container.isUseLegacyDRM) {
                val appDirPath = SteamService.getAppDirPath(gameId)
                val executableDir = appDirPath + "/" + executablePath.substringBeforeLast("/", "")
                guestProgramLauncherComponent.workingDir = File(executableDir)

                val drives = container.drives
                val driveIndex = drives.indexOf(appDirPath)
                val drive = if (driveIndex > 1) {
                    drives[driveIndex - 2]
                } else {
                    'D'
                }
                envVars.put("WINEPATH", "$drive:/${appLaunchInfo.workingDir}")
                "\"$drive:/${executablePath}\""
            } else {
                "\"C:\\Program Files (x86)\\Steam\\steamclient_loader_x64.exe\""
            }
        }
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
    val executablePath = if (container.executablePath.isNotEmpty()) {
        container.executablePath
    } else {
        SteamService.getInstalledExe(gameId)
    }
    val drives = container.drives
    val driveIndex = drives.indexOf(appDirPath)
    val drive = if (driveIndex > 1) {
        drives[driveIndex - 2]
    } else {
        'D'
    }
    return "$drive:\\$executablePath"
}

/**
 * Filters executables to exclude _CommonRedist folder and files ending in original.exe or unpacked.exe
 */
private fun filterExecutablesForSteamless(executables: List<String>): List<String> {
    return executables.filter { exePath ->
        val lowerPath = exePath.lowercase()
        // Exclude _CommonRedist folder
        !lowerPath.contains("_commonredist") &&
        // Exclude files ending in original.exe or unpacked.exe
        !lowerPath.endsWith("original.exe") &&
        !lowerPath.endsWith("unpacked.exe")
    }
}

private fun exit(
    winHandler: WinHandler?,
    environment: XEnvironment?,
    frameRating: FrameRating?,
    appInfo: SteamApp?,
    container: Container,
    onExit: () -> Unit,
    navigateBack: () -> Unit,
) {
private fun exit(winHandler: WinHandler?, environment: XEnvironment?, frameRating: FrameRating?, appInfo: SteamApp?, container: Container, onExit: () -> Unit, navigateBack: () -> Unit) {
    Timber.i("Exit called")

    // Prevent duplicate PostHog events when multiple exit triggers fire simultaneously
    if (isExiting.compareAndSet(false, true)) {
        PostHog.capture(
            event = "game_exited",
            properties = mapOf(
                "game_name" to appInfo?.name.toString(),
                "session_length" to (frameRating?.sessionLengthSec ?: 0),
                "avg_fps" to (frameRating?.avgFPS ?: 0.0),
                "container_config" to container.containerJson,
            ),
        )
    }

    frameRating?.let { rating ->
        container.putSessionMetadata("avg_fps", rating.avgFPS)
        container.putSessionMetadata("session_length_sec", rating.sessionLengthSec.toInt())
        container.saveData()
    }

    winHandler?.stop()
    environment?.stopEnvironmentComponents()
    SteamService.isGameRunning = false
    GameSessionManager.clearSession()
    SteamService.keepAlive = false
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
    frameRating?.writeSessionSummary()
    onExit()
    navigateBack()
}

/**
 * Helper data class to hold redistributable installation context
 */
private data class RedistContext(
    val commonRedistDir: File,
    val driveLetter: Char,
    val guestProgramLauncherComponent: GuestProgramLauncherComponent
)

/**
 * Gets the _CommonRedist directory and drive letter for the game
 * @return RedistContext if valid, null otherwise
 */
private fun getRedistDirectory(
    appId: String,
    container: Container,
    guestProgramLauncherComponent: GuestProgramLauncherComponent
): RedistContext? {
    val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
    val gameDirPath = SteamService.getAppDirPath(steamAppId)
    val commonRedistDir = File(gameDirPath, "_CommonRedist")

    if (!commonRedistDir.exists() || !commonRedistDir.isDirectory()) {
        Timber.tag("installRedist").i("_CommonRedist directory not found at ${commonRedistDir.absolutePath}")
        return null
    }

    // Get the drive letter for the game directory
    val drives = container.drives
    val driveIndex = drives.indexOf(gameDirPath)
    val driveLetter = if (driveIndex > 1) {
        drives[driveIndex - 2]
    } else {
        Timber.tag("installRedist").e("Could not locate game drive for redistributables")
        return null
    }

    return RedistContext(commonRedistDir, driveLetter, guestProgramLauncherComponent)
}

private fun installVcRedist(context: RedistContext) {
        val vcredistDir = File(context.commonRedistDir, "vcredist")
        if (vcredistDir.exists() && vcredistDir.isDirectory()) {
            vcredistDir.walkTopDown()
                .filter { it.isFile && it.name.equals("VC_redist.x64.exe", ignoreCase = true) }
                .forEach { exeFile ->
                    try {
                        val relativePath = exeFile.relativeTo(context.commonRedistDir).path.replace('/', '\\')
                        val drive = context.driveLetter
                        val winePath = "$drive:\\_CommonRedist\\$relativePath"
                        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Installing Visual C++ Redistributables..."))
                        Timber.i("Installing vcredist: $winePath")
                        val cmd = "wine $winePath /quiet /norestart && wineserver -k"
                        val output = context.guestProgramLauncherComponent.execShellCommand(cmd)
                        Timber.i("vcredist installation output: $output")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to install vcredist ${exeFile.name}")
                    }
                }
        }
}

private fun installDotNetFramework(context: RedistContext) {
    val dotnetDirs = listOf("dotNetFx", "dotnet", "DotNet")

    for (dirName in dotnetDirs) {
        val dotnetDir = File(context.commonRedistDir, dirName)
        if (!dotnetDir.exists() || !dotnetDir.isDirectory()) continue

        dotnetDir.walkTopDown()
            .filter { it.isFile &&
                (it.name.startsWith("dotNetFx", ignoreCase = true) ||
                 it.name.contains(".NET Framework", ignoreCase = true) ||
                 it.name.startsWith("NDP", ignoreCase = true)) &&
                it.name.endsWith(".exe", ignoreCase = true) }
            .forEach { exeFile ->
                try {
                    val relativePath = exeFile.relativeTo(context.commonRedistDir).path.replace('/', '\\')
                    val winePath = "${context.driveLetter}:\\_CommonRedist\\$relativePath"
                    PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Installing .NET Framework..."))
                    Timber.i("Installing .NET Framework: $winePath")
                    val cmd = "wine $winePath /q /norestart && wineserver -k"
                    val output = context.guestProgramLauncherComponent.execShellCommand(cmd)
                    Timber.i(".NET Framework installation output: $output")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to install .NET Framework ${exeFile.name}")
                }
            }
    }
}

/**
 * Installs OpenAL redistributables (oalinst.exe) (https://www.openal.org/)
 * Helps with 3D audio implementations between 2001-2010
 */
private fun installOpenAL(context: RedistContext) {
    val openalDir = File(context.commonRedistDir, "OpenAL")
    if (!openalDir.exists() || !openalDir.isDirectory()) return

    val openalInstaller = openalDir.walkTopDown()
        .filter { it.isFile &&
            (it.name.equals("oalinst.exe", ignoreCase = true) ||
             it.name.startsWith("OpenAL", ignoreCase = true)) &&
            it.name.endsWith(".exe", ignoreCase = true) }
        .firstOrNull()

    openalInstaller?.let { exeFile ->
        try {
            val relativePath = exeFile.relativeTo(context.commonRedistDir).path.replace('/', '\\')
            val winePath = "${context.driveLetter}:\\_CommonRedist\\$relativePath"
            PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Installing OpenAL..."))
            Timber.i("Installing OpenAL: $winePath")
            val cmd = "wine $winePath /s && wineserver -k"
            val output = context.guestProgramLauncherComponent.execShellCommand(cmd)
            Timber.i("OpenAL installation output: $output")
        } catch (e: Exception) {
            Timber.e(e, "Failed to install OpenAL ${exeFile.name}")
        }
    }
}

private fun installPhysX(context: RedistContext) {
    val physxDir = File(context.commonRedistDir, "PhysX")
    if (physxDir.exists() && physxDir.isDirectory()) {
        physxDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("PhysX", ignoreCase = true) &&
                        it.name.endsWith(".msi", ignoreCase = true) }
            .forEach { msiFile ->
                try {
                    val relativePath = msiFile.relativeTo(context.commonRedistDir).path.replace('/', '\\')
                    val drive = context.driveLetter
                    val winePath = "$drive:\\_CommonRedist\\$relativePath"
                    PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Installing PhysX..."))
                    Timber.i("Installing PhysX: $winePath")
                    val cmd = "wine msiexec /i $winePath /quiet /norestart && wineserver -k"
                    val output = context.guestProgramLauncherComponent.execShellCommand(cmd)
                    Timber.i("PhysX installation output: $output")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to install PhysX ${msiFile.name}")
                }
            }
    }
}

private fun installXNAFramework(context: RedistContext) {
    val xnaDir = File(context.commonRedistDir, "xnafx")
    if (xnaDir.exists() && xnaDir.isDirectory()) {
        xnaDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("xna", ignoreCase = true) &&
                        it.name.endsWith(".msi", ignoreCase = true) }
            .forEach { msiFile ->
                try {
                    val relativePath = msiFile.relativeTo(context.commonRedistDir).path.replace('/', '\\')
                    val drive = context.driveLetter
                    val winePath = "$drive:\\_CommonRedist\\$relativePath"
                    PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Installing XNA Framework..."))
                    Timber.i("Installing XNA: $winePath")
                    val cmd = "wine msiexec /i $winePath /quiet /norestart && wineserver -k"
                    val output = context.guestProgramLauncherComponent.execShellCommand(cmd)
                    Timber.i("XNA installation output: $output")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to install XNA ${msiFile.name}")
                }
            }
    }
}

/**
 * Installs redistributables from _CommonRedist folder
 * if shared depots are present and the redistributable executables exist.
 */
private fun installRedistributables(
    context: Context,
    container: Container,
    appId: String,
    guestProgramLauncherComponent: GuestProgramLauncherComponent,
    imageFs: ImageFs,
) {
    try {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)

        val downloadableDepots = SteamService.getDownloadableDepots(steamAppId)
        val sharedDepots = downloadableDepots.filter { (_, depotInfo) ->
            val manifest = depotInfo.manifests["public"]
            manifest == null || manifest.gid == 0L
        }

        if (sharedDepots.isEmpty()) {
            Timber.tag("installRedist").i("No shared depots found, skipping redistributable installation")
            return
        }

        Timber.tag("installRedist").i("Found ${sharedDepots.size} shared depot(s), checking for redistributables")

        // Get redistributable directory context
        val redistContext = getRedistDirectory(appId, container, guestProgramLauncherComponent) ?: run {
            Timber.tag("installRedist").i("Could not set up redistributable context, skipping installation")
            return
        }

        installVcRedist(redistContext)
        installDotNetFramework(redistContext)
        installOpenAL(redistContext)
        installPhysX(redistContext)
        installXNAFramework(redistContext)

        Timber.tag("installRedist").i("Finished checking for redistributables")
    } catch (e: Exception) {
        Timber.tag("installRedist").e(e, "Error in installRedistributables: ${e.message}")
    }
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
    if (needsUnpacking || containerVariantChanged) {
        try {
            PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Installing Mono..."))
            val monoCmd = "wine msiexec /i Z:\\opt\\mono-gecko-offline\\wine-mono-9.0.0-x86.msi && wineserver -k"
            guestProgramLauncherComponent.execShellCommand(monoCmd)
        } catch (_: Exception) {}

        try {
            installRedistributables(context, container, appId, guestProgramLauncherComponent, imageFs)
        } catch (e: Exception) {
            Timber.tag("installRedist").e(e, "Error installing redistributables: ${e.message}")
        }
    }
    if (!needsUnpacking){
        return
    }
    try {
        val rootDir: File = imageFs.getRootDir()

        try {
            PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Handling DRM..."))
            val origTxtFile = File("${imageFs.wineprefix}/dosdevices/a:/orig_dll_path.txt")

            if (origTxtFile.exists()) {
                val relDllPaths = origTxtFile.readLines().map { it.trim() }.filter { it.isNotBlank() }
                for (relDllPath in relDllPaths) {
                    try {
                        val originalDll = File("${imageFs.wineprefix}/dosdevices/a:/$relDllPath")
                        if (originalDll.exists()) {
                            val genCmd = "wine z:\\generate_interfaces_file.exe A:\\" + relDllPath.replace('/', '\\')
                            guestProgramLauncherComponent.execShellCommand(genCmd)

                            val origSteamInterfaces = File("${imageFs.wineprefix}/dosdevices/z:/steam_interfaces.txt")
                            if (origSteamInterfaces.exists()) {
                                val finalSteamInterfaces = File(originalDll.parent, "steam_interfaces.txt")
                                Files.copy(
                                    origSteamInterfaces.toPath(),
                                    finalSteamInterfaces.toPath(),
                                    REPLACE_EXISTING,
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        output = StringBuilder()

        if (!container.isLaunchRealSteam && !container.isUnpackFiles) {
            val executablePath = container.executablePath
            if (executablePath.isEmpty()) {
                Timber.w("No executable path set, skipping Steamless")
            } else {
                PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Handling DRM..."))

                var batchFile: File? = null
                try {
                    // Normalize path: container.executablePath uses forward slashes, convert to Windows format
                    val normalizedPath = executablePath.replace('/', '\\')
                    val windowsPath = "A:\\$normalizedPath"

                    // Create a batch file that Wine can execute, to handle paths with spaces in them
                    batchFile = File(imageFs.getRootDir(), "tmp/steamless_wrapper.bat")
                    batchFile.parentFile?.mkdirs()
                    batchFile.writeText("@echo off\r\nz:\\Steamless\\Steamless.CLI.exe \"$windowsPath\"\r\n")

                    val slCmd = "wine z:\\tmp\\steamless_wrapper.bat"
                    val slOutput = guestProgramLauncherComponent.execShellCommand(slCmd)
                    output.append(slOutput)
                    Timber.i("Finished processing executable. Result: $output")
                } catch (e: Exception) {
                    Timber.e(e, "Error running Steamless on $executablePath")
                    output.append("Error processing $executablePath: ${e.message}\n")
                } finally {
                    // Clean up batch file
                    batchFile?.delete()
                }

                // Process file moving for the executable
                try {
                    // container.executablePath uses forward slashes (Unix format)
                    // Use as-is for File operations (forward slashes work on Unix/Android)
                    val unixPath = executablePath.replace('\\', '/')
                    val exe = File(imageFs.wineprefix + "/dosdevices/a:/" + unixPath)
                    val unpackedExe = File(
                        imageFs.wineprefix + "/dosdevices/a:/" + unixPath + ".unpacked.exe",
                    )
                    val originalExe = File(
                        imageFs.wineprefix + "/dosdevices/a:/" + unixPath + ".original.exe",
                    )

                    // For logging, show Windows format
                    val windowsPath = "A:\\${executablePath.replace('/', '\\')}"

                    Timber.i("Moving files for $windowsPath")
                    if (exe.exists() && unpackedExe.exists()) {
                        if (originalExe.exists()) {
                            Timber.i("Original backup exists for $windowsPath; skipping overwrite")
                        } else {
                            Files.copy(exe.toPath(), originalExe.toPath(), REPLACE_EXISTING)
                        }
                        Files.copy(unpackedExe.toPath(), exe.toPath(), REPLACE_EXISTING)
                        Timber.i("Successfully moved files for $windowsPath")
                    } else {
                        val errorMsg =
                            "Either exe or unpacked exe does not exist for $windowsPath. Exe: ${exe.exists()}, Unpacked: ${unpackedExe.exists()}"
                        Timber.w(errorMsg)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error moving files for $executablePath")
                }
            }
        } else {
            Timber.i("Skipping Steamless (launchRealSteam=${container.isLaunchRealSteam}, useLegacyDRM=${container.isUseLegacyDRM}, unpackFiles=${container.isUnpackFiles})")
        }

        output = StringBuilder()
        try {
            guestProgramLauncherComponent.execShellCommand("wineserver -k")
        } catch (_: Exception) {}
        container.setNeedsUnpacking(false)
        container.saveData()
    } catch (e: Exception) {
        onError?.invoke("Error during unpacking: ${e.message}")
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
    contentsManager: ContentsManager,
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

    val needReextract = ALWAYS_REEXTRACT || xServerState.value.dxwrapper != container.getExtra("dxwrapper") || container.wineVersion != wineVersion

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
            contentsManager,
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

    if (container.isLaunchRealSteam){
        extractSteamFiles(context, container, onExtractFileListener)
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
        val downloaded = File(imageFs.getFilesDir(), "imagefs_patches_gamenative.tzst")
        Timber.i("Extracting imagefs_patches_gamenative.tzst")
        if (Arrays.asList<String?>(*context.getAssets().list("")).contains("imagefs_patches_gamenative.tzst") == true) {
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context.assets,
                "imagefs_patches_gamenative.tzst",
                rootDir,
                onExtractFileListener,
            )
        } else if (downloaded.exists()){
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                downloaded,
                rootDir,
                onExtractFileListener,
            );
        }
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
    contentsManager: ContentsManager,
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
            val profile: ContentProfile? = contentsManager.getProfileByEntryName(dxwrapper)
            // Determine graphics driver to choose DXVK version
            val vortekLike = container.graphicsDriver == "vortek" || container.graphicsDriver == "adreno"
            val dxvkVersionForVkd3d = if (vortekLike && GPUHelper.vkGetApiVersionSafe() < GPUHelper.vkMakeVersion(1, 3, 0)) "1.10.3" else "2.4.1"
            Timber.i("Extracting VKD3D DX version for dxwrapper: $dxvkVersionForVkd3d")
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context.assets,
                "dxwrapper/dxvk-${dxvkVersionForVkd3d}.tzst", windowsDir, onExtractFileListener,
            )
            if (profile != null) {
                Timber.d("Applying user-defined VKD3D content profile: " + dxwrapper)
                contentsManager.applyContent(profile);
            } else {
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
        }
        else -> {
            val profile: ContentProfile? = contentsManager.getProfileByEntryName(dxwrapper)
            // This block handles dxvk-VERSION strings
            Timber.i("Extracting DXVK/D8VK DLLs for dxwrapper: $dxwrapper")
            restoreOriginalDllFiles(context, container, containerManager, imageFs, "d3d12.dll", "d3d12core.dll", "ddraw.dll")
            if (profile != null) {
                Timber.d("Applying user-defined DXVK content profile: " + dxwrapper)
                contentsManager.applyContent(profile);
            } else {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, context.assets,
                    "dxwrapper/$dxwrapper.tzst", windowsDir, onExtractFileListener,
                )
            }
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
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1]) && !firstTimeBoot) continue
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
    vkbasaltConfig: String,
) {
    if (container.containerVariant.equals(Container.GLIBC)) {
        // Get the configured driver version or use default
        val turnipVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "turnip" } ?: DefaultVersion.TURNIP
        val virglVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "virgl" } ?: DefaultVersion.VIRGL
        val zinkVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "zink" } ?: DefaultVersion.ZINK
        val adrenoVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "adreno" } ?: DefaultVersion.ADRENO

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
        } else if (graphicsDriver == "vortek" || graphicsDriver == "adreno") {
            cacheId += "-" + DefaultVersion.VORTEK
        }

        val imageFs = ImageFs.find(context)
        val configDir = imageFs.configDir
        val sentinel = File(configDir, ".current_graphics_driver")   // lives in shared tree
        val onDiskId = sentinel.takeIf { it.exists() }?.readText() ?: ""
        val changed = ALWAYS_REEXTRACT || cacheId != container.getExtra("graphicsDriver") || cacheId != onDiskId
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
        } else if (graphicsDriver == "adreno") {
            val assetZip = "Adreno_${adrenoVersion}_adpkg.zip"

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
        if (ALWAYS_REEXTRACT || firstTimeBoot || mainWrapperSelection != lastInstalledMainWrapper) {
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

        var vulkanVersion = graphicsDriverConfig.get("vulkanVersion") ?: "1.0"
        val vulkanVersionPatch = GPUHelper.vkVersionPatch()

        vulkanVersion = "$vulkanVersion.$vulkanVersionPatch"
        envVars.put("WRAPPER_VK_VERSION", vulkanVersion)

        val blacklistedExtensions: String? = graphicsDriverConfig.get("blacklistedExtensions")
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions)

        val maxDeviceMemory: String? = graphicsDriverConfig.get("maxDeviceMemory", "0")
        if (maxDeviceMemory != null && maxDeviceMemory.toInt() > 0)
            envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory)

        val presentMode = graphicsDriverConfig.get("presentMode")
        if (presentMode.contains("immediate")) {
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1")
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode)

        val resourceType = graphicsDriverConfig.get("resourceType")
        envVars.put("WRAPPER_RESOURCE_TYPE", resourceType)

        val syncFrame = graphicsDriverConfig.get("syncFrame")
        if (syncFrame == "1") envVars.put("MESA_VK_WSI_DEBUG", "forcesync")

        val disablePresentWait = graphicsDriverConfig.get("disablePresentWait")
        envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait)

        val bcnEmulation = graphicsDriverConfig.get("bcnEmulation")
        when (bcnEmulation) {
            "auto" -> envVars.put("WRAPPER_EMULATE_BCN", "3")
            "full" -> envVars.put("WRAPPER_EMULATE_BCN", "2")
            "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0")
            else -> envVars.put("WRAPPER_EMULATE_BCN", "1")
        }

        val bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache")
        envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache)

        if (!vkbasaltConfig.isEmpty()) {
            envVars.put("ENABLE_VKBASALT", "1")
            envVars.put("VKBASALT_CONFIG", vkbasaltConfig)
        }
    }
}

private fun extractSteamFiles(
    context: Context,
    container: Container,
    onExtractFileListener: OnExtractFileListener?,
) {
    val imageFs = ImageFs.find(context)
    if (File(ImageFs.find(context).rootDir.absolutePath, ImageFs.WINEPREFIX + "/drive_c/Program Files (x86)/Steam/steam.exe").exists()) return
    val downloaded = File(imageFs.getFilesDir(), "steam.tzst")
    Timber.i("Extracting steam.tzst")
    TarCompressorUtils.extract(
        TarCompressorUtils.Type.ZSTD,
        downloaded,
        imageFs.getRootDir(),
        onExtractFileListener,
    );
}

private fun readZipManifestNameFromAssets(context: Context, assetName: String): String? {
    return com.winlator.core.FileUtils.readZipManifestNameFromAssets(context, assetName)
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
