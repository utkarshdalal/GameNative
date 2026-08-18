package app.gamenative.ui.screen.xr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.model.MainViewModel
import app.gamenative.ui.screen.xserver.XServerScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.core.AppUtils
import com.winlator.renderer.GLRenderer
import com.winlator.winhandler.WinHandler
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Dedicated entry point for launching a game directly in Meta Quest immersive mode. */
@AndroidEntryPoint
class ImmersiveXrActivity : androidx.activity.ComponentActivity() {

    companion object {
        private const val EXTRA_APP_ID = "app_id"
        private const val EXTRA_IS_OFFLINE = "is_offline"
        private const val POLL_INTERVAL_MS = 11L
        private const val CAPTURE_RETRY_DELAY_MS = 200L
        private const val OVERLAY_REFRESH_INTERVAL_MS = 33L // ~30fps — plenty for a menu/HUD,
        private const val OVERLAY_CONTENT_GRACE_MS = 2500L

        private const val IMMERSIVE_UI_DENSITY = 2.5f

        private const val MENU_DPAD_AXIS_THRESHOLD = 0.5f
        private const val MENU_DPAD_INITIAL_DELAY_MS = 400L
        private const val MENU_DPAD_REPEAT_DELAY_MS = 150L

        private const val POINTER_INDICATOR_GAP_METERS = 0.02f
        private const val POINTER_HANDLE_RADIUS_METERS = 0.035f
        private const val POINTER_HANDLE_STROKE_WIDTH_IDLE_PX = 7.0f
        private const val POINTER_HANDLE_STROKE_WIDTH_ACTIVE_PX = 10.5f
        private const val POINTER_CORNER_RADIUS_METERS = 0.06f
        private const val POINTER_BAR_HALF_WIDTH_METERS = 0.08f
        private const val POINTER_BAR_RADIUS_METERS = 0.06f
        private const val POINTER_GRAB_PRESS_THRESHOLD = 0.65f
        private const val POINTER_GRAB_RELEASE_THRESHOLD = 0.3f
        private const val MIN_CAPTURE_INTERVAL_MS = 11L // ~90fps cap, matches the HMD's own fixed

        private const val EXTRA_QUAD_DISTANCE = "immersiveQuadDistance"
        private const val EXTRA_QUAD_SCALE = "immersiveQuadScale"
        private const val EXTRA_PASSTHROUGH_ENABLED = "immersivePassthroughEnabled"

        fun start(context: Context, appId: String, isOffline: Boolean) {
            val intent = Intent(context, ImmersiveXrActivity::class.java).apply {
                putExtra(EXTRA_APP_ID, appId)
                putExtra(EXTRA_IS_OFFLINE, isOffline)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private val viewModel: MainViewModel by viewModels()

    @Volatile
    private var backAction: (() -> Unit)? = null

    @Volatile
    private var quickMenuToggle: (() -> Unit)? = null

    @Volatile
    private var quickMenuFocusManager: androidx.compose.ui.focus.FocusManager? = null

    @Volatile
    private var quickMenuCycleTab: ((Boolean) -> Unit)? = null

    @Volatile
    private var quickMenuAdjustmentControl: (() -> Pair<() -> Unit, () -> Unit>?)? = null

    @Volatile
    private var quickMenuFocusTabRail: (() -> Unit)? = null

    @Volatile
    private var quickMenuFocusedActivate: (() -> (() -> Unit)?)? = null

    @Volatile
    private var quickMenuSetStartHeld: ((Boolean) -> Unit)? = null
    private var lastPushedStartHeld = false

    private var xrSessionHandle: Long = 0L
    private var currentAppId: String? = null

    private var pollingThread: Thread? = null
    private val pollingActive = AtomicBoolean(false)
    private var cachedWinHandler: WinHandler? = null

    @Volatile
    private var surfaceReady = false
    private var surfaceCallbackAttachedTo: SurfaceView? = null
    private val surfaceReadyCallback = object : android.view.SurfaceHolder.Callback {
        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            surfaceReady = true
        }
        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
            surfaceReady = false
        }
    }
    private var cachedBridge: XrGamepadBridge? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val captureActive = AtomicBoolean(false)
    private var gameCaptureBitmap: Bitmap? = null
    private val overlayLock = Any()
    private var overlayLayerBitmap: Bitmap? = null
    private var finalFrameBitmap: Bitmap? = null
    private var overlayCaptureLogCounter = 0
    @Volatile
    private var bootingSplashVisible = false
    private var overlayContentLastVisibleAt = 0L
    private var overlayClearSubmitted = false
    private var pointerGripHeldLogCounter = 0

    private var directGLBridge: DirectGLBridge? = null
    private var directVulkanBridge: DirectVulkanBridge? = null
    @Volatile
    private var directRenderActive = false
    private var directRenderProbeLogged = false

    private var lastMenuDpadKeyCode: Int? = null
    private var lastMenuDpadHeldSince = 0L
    private var lastMenuDpadEventTime = 0L
    private var lastMenuButtonAPressed = false
    private var lastMenuButtonBPressed = false
    private var lastMenuButtonLBPressed = false
    private var lastMenuButtonRBPressed = false

    private var xrPointerModeActive by mutableStateOf(false)
    private var resizeHandlesEnabled by mutableStateOf(false)
    @Volatile private var lastLeftActionPressed = false
    @Volatile private var lastRightActionPressed = false
    @Volatile private var pointerGrabHand: Int? = null // 0 = left, 1 = right
    private var pointerGrabIsResize = false
    private val pointerGrabStartHandPos = FloatArray(3)
    private var pointerGrabStartDistance = 0f
    private var pointerGrabStartHorizontal = 0f
    private var pointerGrabStartVertical = 0f
    private var pointerGrabStartScale = 0f

    @Volatile private var pointerCursorLeftValid = false
    @Volatile private var pointerCursorLeftX = 0f
    @Volatile private var pointerCursorLeftY = 0f
    @Volatile private var pointerCursorRightValid = false
    @Volatile private var pointerCursorRightX = 0f
    @Volatile private var pointerCursorRightY = 0f

    private var pointerTouchDownTimeLeft = 0L
    private var pointerTouchDownTimeRight = 0L

    @Volatile
    private var quickMenuVisible = false

    private var wasInMenuNavigationMode = false
    private var buttonSuppressMaskUntilRelease = 0

    private var quadDistance by mutableFloatStateOf(ImmersiveControls.DEFAULT_DISTANCE)
    private var quadHorizontal by mutableFloatStateOf(ImmersiveControls.DEFAULT_OFFSET)
    private var quadVertical by mutableFloatStateOf(ImmersiveControls.DEFAULT_OFFSET)
    private var quadScale by mutableFloatStateOf(ImmersiveControls.DEFAULT_SCALE)
    private var passthroughEnabled by mutableStateOf(false)
    private var showControlsOnboarding by mutableStateOf(false)
    private var mappedWindowCount by androidx.compose.runtime.mutableIntStateOf(0)
    private var overlayPausedUi by mutableStateOf(false)
    private var cachedContainer: Container? = null
    private var directRenderBlockedByEffects by mutableStateOf<Boolean?>(null)

    override fun attachBaseContext(newBase: Context) {
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.densityDpi = (IMMERSIVE_UI_DENSITY * android.util.DisplayMetrics.DENSITY_DEFAULT).toInt()
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i(
            "Immersive: density override applied -> %.3f (logical size %dx%d dp)",
            resources.displayMetrics.density,
            (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt(),
            (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt(),
        )

        val appId = intent.getStringExtra(EXTRA_APP_ID)
        val isOffline = intent.getBooleanExtra(EXTRA_IS_OFFLINE, false)
        if (appId == null) {
            finish()
            return
        }
        currentAppId = appId

        PluviaApp.isActivityInForeground = true
        AppUtils.keepScreenOn(this)
        loadImmersiveSettings(appId)

        setContent {
            PluviaTheme {
                val context = LocalContext.current
                val mainState by viewModel.state.collectAsStateWithLifecycle()

                androidx.activity.compose.BackHandler(enabled = backAction != null) {
                    backAction?.invoke()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                XServerScreen(
                    appId = appId,
                    bootToContainer = false,
                    isOffline = isOffline,
                    registerBackAction = { cb -> backAction = cb },
                    navigateBack = { finish() },
                    onExit = { onComplete ->
                        viewModel.exitSteamApp(context, appId) {
                            onComplete?.invoke()
                            finish()
                        }
                    },
                    onWindowMapped = { ctx, window ->
                        mappedWindowCount++
                        viewModel.onWindowMapped(ctx, window, appId)
                        showControlsOnboarding = true
                    },
                    onWindowUnmapped = {
                        mappedWindowCount = (mappedWindowCount - 1).coerceAtLeast(0)
                    },
                    onGameLaunchError = { error ->
                        viewModel.onGameLaunchError(error)
                        finish()
                    },
                    immersiveHooks = ImmersiveSessionHooks(
                    onQuickMenuVisibilityChanged = { visible ->
                        Timber.i("Immersive: quick menu visibility changed to %b", visible)
                        quickMenuVisible = visible
                        if (visible) {
                            directRenderBlockedByEffects = (PluviaApp.xServerView?.renderer as? com.winlator.renderer.VulkanRenderer)
                                ?.isEffectsRequireCompositor()
                        }
                    },
                    registerToggle = { toggle -> quickMenuToggle = toggle },
                    registerStartHeld = { setter -> quickMenuSetStartHeld = setter },
                    registerFocusManager = { fm -> quickMenuFocusManager = fm },
                    registerCycleTab = { cycle -> quickMenuCycleTab = cycle },
                    registerAdjustmentControl = { control -> quickMenuAdjustmentControl = control },
                    registerFocusTabRail = { action -> quickMenuFocusTabRail = action },
                    registerFocusedActivate = { getter -> quickMenuFocusedActivate = getter },
                    controls = ImmersiveControls(
                        passthroughEnabled = passthroughEnabled,
                        onPassthroughToggle = { enabled ->
                            passthroughEnabled = enabled
                            if (xrSessionHandle != 0L) {
                                XrNative.nativeSetPassthroughEnabled(xrSessionHandle, enabled)
                            }
                            persistImmersiveSettings(appId)
                        },
                        directRenderBlockedByEffects = directRenderBlockedByEffects,
                        onResetScreenEffects = {
                            val vulkanRenderer = PluviaApp.xServerView?.renderer as? com.winlator.renderer.VulkanRenderer
                            vulkanRenderer?.resetScreenEffects()
                            directRenderBlockedByEffects = vulkanRenderer?.isEffectsRequireCompositor()
                            Timber.i("Immersive: screen effects reset from quick menu, blocked=%s", directRenderBlockedByEffects)
                        },
                        resizeModeEnabled = resizeHandlesEnabled,
                        onResizeModeToggle = { enabled ->
                            resizeHandlesEnabled = enabled
                            xrPointerModeActive = enabled
                            lastLeftActionPressed = false
                            lastRightActionPressed = false
                            if (!enabled) {
                                pointerGrabHand = null
                                pointerCursorLeftValid = false
                                pointerCursorRightValid = false
                            }
                            Timber.i("Immersive: resize handles toggled %s from quick menu, pointer mode now %s", enabled, enabled)
                        },
                    ),
                    ),
                )

                val splashVisible = mainState.showBootingSplash && mappedWindowCount == 0 && !overlayPausedUi
                LaunchedEffect(splashVisible) { bootingSplashVisible = splashVisible }
                app.gamenative.ui.components.BootingSplash(
                    visible = splashVisible,
                    text = mainState.bootingSplashText,
                    heroImageUrl = mainState.bootingSplashHeroImageUrl,
                )

                ImmersiveControlsOnboarding(
                    visible = showControlsOnboarding,
                    onDismiss = { showControlsOnboarding = false },
                )
                ImmersiveModeChangeIndicator(pointerModeActive = xrPointerModeActive)
                }
            }
        }
    }

    private fun loadImmersiveSettings(appId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val container = try {
                ContainerUtils.getContainer(this@ImmersiveXrActivity, appId)
            } catch (t: Throwable) {
                Timber.w(t, "Could not load container for immersive settings, using defaults")
                return@launch
            }
            cachedContainer = container
            quadDistance = container.getExtra(EXTRA_QUAD_DISTANCE, ImmersiveControls.DEFAULT_DISTANCE.toString())
                .toFloatOrNull() ?: ImmersiveControls.DEFAULT_DISTANCE
            quadHorizontal = ImmersiveControls.DEFAULT_OFFSET
            quadVertical = ImmersiveControls.DEFAULT_OFFSET
            quadScale = container.getExtra(EXTRA_QUAD_SCALE, ImmersiveControls.DEFAULT_SCALE.toString())
                .toFloatOrNull() ?: ImmersiveControls.DEFAULT_SCALE
            passthroughEnabled = container.getExtra(EXTRA_PASSTHROUGH_ENABLED, "false").toBoolean()
            applyQuadTransform()
            if (xrSessionHandle != 0L) {
                XrNative.nativeSetPassthroughEnabled(xrSessionHandle, passthroughEnabled)
            }
        }
    }

    private fun persistImmersiveSettings(appId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val container = cachedContainer ?: try {
                ContainerUtils.getContainer(this@ImmersiveXrActivity, appId).also { cachedContainer = it }
            } catch (t: Throwable) {
                Timber.w(t, "Could not persist immersive settings")
                return@launch
            }
            container.putExtra(EXTRA_QUAD_DISTANCE, quadDistance.toString())
            container.putExtra(EXTRA_QUAD_SCALE, quadScale.toString())
            container.putExtra(EXTRA_PASSTHROUGH_ENABLED, passthroughEnabled.toString())
            container.saveData()
        }
    }

    private fun applyQuadTransform() {
        if (xrSessionHandle == 0L) return
        XrNative.nativeSetQuadTransform(
            xrSessionHandle,
            quadHorizontal,
            quadVertical,
            -quadDistance,
            ImmersiveControls.BASE_WIDTH_METERS * quadScale,
            ImmersiveControls.BASE_HEIGHT_METERS * quadScale,
            1f,
            -1f,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newAppId = intent.getStringExtra(EXTRA_APP_ID) ?: return
        val runningAppId = currentAppId
        if (runningAppId == null || newAppId == runningAppId) return
        viewModel.exitSteamApp(this, runningAppId) { recreate() }
    }

    override fun onResume() {
        super.onResume()
        PluviaApp.isActivityInForeground = true
        if (SteamService.keepAlive && PluviaApp.hasValidSuspendPolicyState() && PluviaApp.xEnvironment != null) {
            when {
                PluviaApp.isNeverSuspendMode() -> Unit
                PluviaApp.isOverlayPaused && PluviaApp.isManualSuspendMode() -> Unit
                else -> PluviaApp.xEnvironment?.onResume()
            }
        }
        startXrSessionIfNeeded()
    }

    override fun onPause() {
        PluviaApp.isActivityInForeground = false
        Timber.i(
            "Immersive: onPause, isFinishing=%b isChangingConfigurations=%b",
            isFinishing,
            isChangingConfigurations,
        )
        if (isFinishing && !isChangingConfigurations) {
            PluviaApp.shutdownEnvironment()
        } else if (SteamService.keepAlive && PluviaApp.hasValidSuspendPolicyState() &&
            PluviaApp.xEnvironment != null && !PluviaApp.isNeverSuspendMode()
        ) {
            PluviaApp.xEnvironment?.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopXrSession()
        PluviaApp.shutdownEnvironment()
        super.onDestroy()
    }

    private fun startXrSessionIfNeeded() {
        if (xrSessionHandle != 0L) return
        xrSessionHandle = try {
            XrNative.nativeCreate(this)
        } catch (t: Throwable) {
            Timber.w(t, "Native OpenXR module unavailable — immersive rendering/controller mapping disabled")
            return
        }

        applyQuadTransform()
        XrNative.nativeSetPassthroughEnabled(xrSessionHandle, passthroughEnabled)

        startControllerPollingLoop()
        startFrameCaptureLoop()
    }

    private fun startControllerPollingLoop() {
        pollingActive.set(true)
        pollingThread = thread(name = "XrGamepadPoll") {
            val buttons = IntArray(1)
            val axes = FloatArray(6)
            val handPoses = FloatArray(12)
            val flags = BooleanArray(3)
            var lastFedGamepad = false
            while (pollingActive.get()) {
                val winHandler = PluviaApp.xServerView?.getxServer()?.winHandler
                if (winHandler != null) {
                    if (winHandler !== cachedWinHandler) {
                        cachedWinHandler = winHandler
                        cachedBridge = XrGamepadBridge(winHandler)
                    }
                    val quickMenuClicked = XrNative.nativePollSnapshot(xrSessionHandle, buttons, axes, handPoses, flags)
                    if (flags[2] != lastPushedStartHeld) {
                        lastPushedStartHeld = flags[2]
                        val setter = quickMenuSetStartHeld
                        if (setter != null) runOnUiThread { setter(lastPushedStartHeld) }
                    }
                    if (flags[1]) {
                        xrPointerModeActive = !xrPointerModeActive
                        Timber.i("Immersive: XR pointer mode %s", if (xrPointerModeActive) "enabled" else "disabled")
                        lastLeftActionPressed = (axes[4] > POINTER_GRAB_PRESS_THRESHOLD) ||
                            ((buttons[0] and (1 shl XrGamepadBridge.BUTTON_LB)) != 0)
                        lastRightActionPressed = (axes[5] > POINTER_GRAB_PRESS_THRESHOLD) ||
                            ((buttons[0] and (1 shl XrGamepadBridge.BUTTON_RB)) != 0)
                        if (!xrPointerModeActive) {
                            pointerGrabHand = null
                            pointerCursorLeftValid = false
                            pointerCursorRightValid = false
                        }
                    }
                    val inMenuMode = quickMenuVisible || PluviaApp.isOverlayPaused
                    overlayPausedUi = PluviaApp.isOverlayPaused
                    if (wasInMenuNavigationMode && !inMenuMode) {
                        buttonSuppressMaskUntilRelease = buttons[0]
                    }
                    wasInMenuNavigationMode = inMenuMode
                    val feedGame = !xrPointerModeActive && !inMenuMode
                    if (!feedGame && lastFedGamepad) cachedBridge?.reset()
                    lastFedGamepad = feedGame
                    when {
                        xrPointerModeActive -> handlePointerMode(buttons[0], axes, handPoses, flags[0])
                        inMenuMode && !flags[2] -> handleMenuNavigation(buttons[0], axes)
                        inMenuMode -> Unit
                        else -> {
                            buttonSuppressMaskUntilRelease = buttonSuppressMaskUntilRelease and buttons[0]
                            cachedBridge?.applySnapshot(buttons[0] and buttonSuppressMaskUntilRelease.inv(), axes)
                        }
                    }
                    if (quickMenuClicked) {
                        Timber.i(
                            "Immersive: quick-menu chord fired, quickMenuToggle registered=%b buttons=0x%03x " +
                                "quickMenuVisible=%b isOverlayPaused=%b xrPointerModeActive=%b",
                            quickMenuToggle != null, buttons[0], quickMenuVisible, PluviaApp.isOverlayPaused, xrPointerModeActive,
                        )
                        runOnUiThread { quickMenuToggle?.invoke() }
                    }
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    /**
     * Direct manipulation of the quad via a hand's aim-pose ray, active only while
     * [xrPointerModeActive]. Grip or trigger both grab and click. Grab a corner (within
     * [POINTER_CORNER_RADIUS_METERS]) to resize, the top/bottom edge (within
     * [POINTER_BAR_RADIUS_METERS]) to reposition. Deltas are relative to grab start, not
     * incremental, so repeated rounding cannot drift.
     */
    private fun handlePointerMode(buttons: Int, axes: FloatArray, handPoses: FloatArray, posesValid: Boolean) {
        val leftGripPressed = (buttons and (1 shl XrGamepadBridge.BUTTON_LB)) != 0
        val rightGripPressed = (buttons and (1 shl XrGamepadBridge.BUTTON_RB)) != 0
        val leftTriggerPressed = axes[4] > (if (lastLeftActionPressed) POINTER_GRAB_RELEASE_THRESHOLD else POINTER_GRAB_PRESS_THRESHOLD)
        val rightTriggerPressed = axes[5] > (if (lastRightActionPressed) POINTER_GRAB_RELEASE_THRESHOLD else POINTER_GRAB_PRESS_THRESHOLD)
        val leftActionPressed = leftGripPressed || leftTriggerPressed
        val rightActionPressed = rightGripPressed || rightTriggerPressed
        val leftReleasedNow = !leftActionPressed && lastLeftActionPressed
        val rightReleasedNow = !rightActionPressed && lastRightActionPressed
        val leftPressedNow = leftActionPressed && !lastLeftActionPressed
        val rightPressedNow = rightActionPressed && !lastRightActionPressed
        if ((leftActionPressed && !leftPressedNow && pointerGrabHand != 0) ||
            (rightActionPressed && !rightPressedNow && pointerGrabHand != 1)
        ) {
            pointerGripHeldLogCounter++
            if (pointerGripHeldLogCounter % 45 == 0) {
                Timber.i(
                    "Immersive: action button held without a fresh edge — left=%b(pressedNow=%b) right=%b(pressedNow=%b) grabbing=%s",
                    leftActionPressed, leftPressedNow, rightActionPressed, rightPressedNow, pointerGrabHand,
                )
            }
        }
        lastLeftActionPressed = leftActionPressed
        lastRightActionPressed = rightActionPressed

        if (!posesValid) {
            pointerCursorLeftValid = false
            pointerCursorRightValid = false
            return
        }

        val leftHit = rayPlaneHit(handPoses, 0, quadHorizontal, quadVertical, quadDistance)
        pointerCursorLeftValid = leftHit != null
        if (leftHit != null) {
            pointerCursorLeftX = leftHit[0]
            pointerCursorLeftY = leftHit[1]
        }
        val rightHit = rayPlaneHit(handPoses, 1, quadHorizontal, quadVertical, quadDistance)
        pointerCursorRightValid = rightHit != null
        if (rightHit != null) {
            pointerCursorRightX = rightHit[0]
            pointerCursorRightY = rightHit[1]
        }

        handHandAction(0, leftPressedNow, leftReleasedNow, leftActionPressed, leftHit, handPoses)
        handHandAction(1, rightPressedNow, rightReleasedNow, rightActionPressed, rightHit, handPoses)
    }

    /** One hand's share of handlePointerMode — a fresh press either starts a grab (if aimed at a
     * handle) OR dispatches a click, never both for the same press (tryStartPointerGrab's return
     * value decides which, so a grab-starting press can't also fire a spurious click underneath
     * it — merging grab and click onto the same button made that a real risk that separate
     * buttons never had). */
    private fun handHandAction(
        hand: Int,
        pressedNow: Boolean,
        releasedNow: Boolean,
        held: Boolean,
        hit: FloatArray?,
        handPoses: FloatArray,
    ) {
        if (pointerGrabHand == hand) {
            if (releasedNow) {
                pointerGrabHand = null
                currentAppId?.let { persistImmersiveSettings(it) }
            } else {
                updateActiveGrab(handPoses)
            }
            return
        }
        when {
            pressedNow -> {
                if (!tryStartPointerGrab(hand, handPoses)) {
                    dispatchPointerTouch(hand, android.view.MotionEvent.ACTION_DOWN, hit)
                }
            }
            releasedNow -> dispatchPointerTouch(hand, android.view.MotionEvent.ACTION_UP, hit)
            held -> dispatchPointerTouch(hand, android.view.MotionEvent.ACTION_MOVE, hit)
        }
    }

    /** Converts a hand's quad-local hit point (meters) into content-view pixel coordinates and
     * dispatches a synthetic touch event there — lets the XR pointer act as a real touch/mouse
     * input for whatever Compose UI is on screen (menu buttons, adjustment +/-, the Resume
     * button...), the same way tapping a Quest system panel with a controller ray does. */
    private fun dispatchPointerTouch(hand: Int, action: Int, hit: FloatArray?) {
        if (hit == null) return
        val halfWidth = (ImmersiveControls.BASE_WIDTH_METERS * quadScale) / 2f
        val halfHeight = (ImmersiveControls.BASE_HEIGHT_METERS * quadScale) / 2f
        if (halfWidth <= 0f || halfHeight <= 0f) return
        val localX = hit[0]
        val localY = hit[1]

        val now = android.os.SystemClock.uptimeMillis()
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            if (hand == 0) pointerTouchDownTimeLeft = now else pointerTouchDownTimeRight = now
        }
        val downTime = (if (hand == 0) pointerTouchDownTimeLeft else pointerTouchDownTimeRight)
            .takeIf { it > 0L } ?: now

        runOnUiThread {
            val contentView = findViewById<android.view.View>(android.R.id.content)
            val width = contentView.width
            val height = contentView.height
            if (width <= 0 || height <= 0) return@runOnUiThread

            val px = ((localX + halfWidth) / (2f * halfWidth)) * width
            val py = ((halfHeight - localY) / (2f * halfHeight)) * height
            if (px < 0f || px > width || py < 0f || py > height) return@runOnUiThread

            val event = android.view.MotionEvent.obtain(downTime, now, action, px, py, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {
                dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private val pointerCursorPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
    }

    /** Draws a simple 2D dot at each hand's ray/quad hit point — see pointerCursor* fields. */
    private fun toPixel(localX: Float, localY: Float, halfWidth: Float, halfHeight: Float, width: Int, height: Int): FloatArray {
        val px = ((localX + halfWidth) / (2f * halfWidth)) * width
        val py = ((halfHeight - localY) / (2f * halfHeight)) * height
        return floatArrayOf(px, py)
    }

    /** Draws the per-hand aiming cursor always (while in pointer mode), and the 6 grab handles (4 corners + top/bottom edge) only while [resizeHandlesEnabled. */
    private fun drawPointerCursors(canvas: android.graphics.Canvas, width: Int, height: Int) {
        val contentHalfWidth = (ImmersiveControls.BASE_WIDTH_METERS * quadScale) / 2f
        val contentHalfHeight = (ImmersiveControls.BASE_HEIGHT_METERS * quadScale) / 2f
        if (contentHalfWidth <= 0f || contentHalfHeight <= 0f) return

        fun toContentPixel(localX: Float, localY: Float) = toPixel(localX, localY, contentHalfWidth, contentHalfHeight, width, height)

        fun drawCursor(valid: Boolean, localX: Float, localY: Float, isGrabbing: Boolean) {
            if (!valid) return
            val (rawPx, rawPy) = toContentPixel(localX, localY)
            val px = rawPx.coerceIn(0f, width.toFloat())
            val py = rawPy.coerceIn(0f, height.toFloat())
            val (nearCorner, nearEdgeBar) = nearGrabZone(localX, localY, contentHalfWidth, contentHalfHeight)
            pointerCursorPaint.style = if (nearCorner || nearEdgeBar || isGrabbing) {
                android.graphics.Paint.Style.FILL
            } else {
                android.graphics.Paint.Style.STROKE
            }
            pointerCursorPaint.strokeWidth = 4f
            pointerCursorPaint.color = android.graphics.Color.WHITE
            canvas.drawCircle(px, py, if (isGrabbing) 16f * quadScale else 11f * quadScale, pointerCursorPaint)
        }
        drawCursor(pointerCursorLeftValid, pointerCursorLeftX, pointerCursorLeftY, pointerGrabHand == 0)
        drawCursor(pointerCursorRightValid, pointerCursorRightX, pointerCursorRightY, pointerGrabHand == 1)

        if (!resizeHandlesEnabled) return

        val handleRadiusPx = POINTER_HANDLE_RADIUS_METERS * width / (2f * contentHalfWidth)

        val hands = buildList {
            if (pointerCursorLeftValid) add(Triple(pointerCursorLeftX, pointerCursorLeftY, 0))
            if (pointerCursorRightValid) add(Triple(pointerCursorRightX, pointerCursorRightY, 1))
        }

        fun zoneStyle(signX: Int, signY: Int, isCorner: Boolean): Pair<Int, Float> {
            var hovering = false
            var grabbing = false
            for ((hLocalX, hLocalY, hand) in hands) {
                val (nearCorner, nearEdgeBar) = nearGrabZone(hLocalX, hLocalY, contentHalfWidth, contentHalfHeight)
                val matchesThisZone = if (isCorner) {
                    nearCorner && kotlin.math.sign(hLocalX).toInt() == signX && kotlin.math.sign(hLocalY).toInt() == signY
                } else {
                    nearEdgeBar && kotlin.math.sign(hLocalY).toInt() == signY
                }
                if (matchesThisZone) {
                    hovering = true
                    if (pointerGrabHand == hand) grabbing = true
                }
            }
            return when {
                grabbing -> 180 to POINTER_HANDLE_STROKE_WIDTH_ACTIVE_PX // ~0.7 opacity, 3x the idle stroke
                hovering -> 90 to POINTER_HANDLE_STROKE_WIDTH_ACTIVE_PX // ~0.35 opacity, 3x the idle stroke
                else -> 20 to POINTER_HANDLE_STROKE_WIDTH_IDLE_PX // ~0.08 opacity, present but barely there
            }
        }

        val handlePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = POINTER_HANDLE_STROKE_WIDTH_IDLE_PX
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = android.graphics.Color.WHITE
        }
        val armPx = handleRadiusPx * 1.6f
        for (signX in intArrayOf(-1, 1)) {
            for (signY in intArrayOf(-1, 1)) {
                val corner = toContentPixel(
                    signX * (contentHalfWidth - POINTER_INDICATOR_GAP_METERS),
                    signY * (contentHalfHeight - POINTER_INDICATOR_GAP_METERS),
                )
                val (alpha, strokeWidth) = zoneStyle(signX, signY, isCorner = true)
                handlePaint.alpha = alpha
                handlePaint.strokeWidth = strokeWidth
                canvas.drawLine(corner[0], corner[1], corner[0] - signX * armPx, corner[1], handlePaint)
                canvas.drawLine(corner[0], corner[1], corner[0], corner[1] + signY * armPx, handlePaint)
            }
        }
        for (signY in intArrayOf(-1, 1)) {
            val center = toContentPixel(0f, signY * (contentHalfHeight - POINTER_INDICATOR_GAP_METERS))
            val (alpha, strokeWidth) = zoneStyle(0, signY, isCorner = false)
            handlePaint.alpha = alpha
            handlePaint.strokeWidth = strokeWidth
            canvas.drawLine(center[0] - armPx, center[1], center[0] + armPx, center[1], handlePaint)
        }
    }

    /** Ray/quad-plane intersection, returning quad-LOCAL coordinates (meters, origin at the quad's own center, +X along the quad's own right edge, +Y up) dir. */
    private fun rayPlaneHit(handPoses: FloatArray, hand: Int, horizontal: Float, vertical: Float, distance: Float): FloatArray? {
        val base = hand * 6
        val originX = handPoses[base]
        val originY = handPoses[base + 1]
        val originZ = handPoses[base + 2]
        val dirX = handPoses[base + 3]
        val dirY = handPoses[base + 4]
        val dirZ = handPoses[base + 5]

        val yaw = if (distance > 0.0001f) kotlin.math.atan2(horizontal, distance) else 0f
        val pitch = if (distance > 0.0001f) kotlin.math.atan2(vertical, distance) else 0f
        val sinYaw = kotlin.math.sin(yaw)
        val cosYaw = kotlin.math.cos(yaw)
        val sinPitch = kotlin.math.sin(pitch)
        val cosPitch = kotlin.math.cos(pitch)

        val centerX = distance * sinYaw * cosPitch
        val centerY = distance * sinPitch
        val centerZ = -distance * cosYaw * cosPitch

        val rightX = cosYaw
        val rightY = 0f
        val rightZ = sinYaw
        val upX = -sinYaw * sinPitch
        val upY = cosPitch
        val upZ = cosYaw * sinPitch
        val normalX = -sinYaw * cosPitch
        val normalY = -sinPitch
        val normalZ = cosYaw * cosPitch

        val denom = dirX * normalX + dirY * normalY + dirZ * normalZ
        if (kotlin.math.abs(denom) < 0.0001f) return null
        val t = ((centerX - originX) * normalX + (centerY - originY) * normalY + (centerZ - originZ) * normalZ) / denom
        if (t <= 0f) return null

        val hitX = originX + t * dirX
        val hitY = originY + t * dirY
        val hitZ = originZ + t * dirZ
        val relX = hitX - centerX
        val relY = hitY - centerY
        val relZ = hitZ - centerZ
        val localX = relX * rightX + relY * rightY + relZ * rightZ
        val localY = relX * upX + relY * upY + relZ * upZ
        return floatArrayOf(localX, localY)
    }

    /** Whether a quad-local point is near a corner (resize handle) or the top/bottom edge (move
     * handle) — shared by the actual grab check and the hover-highlight drawn for the cursor, so
     * the user can see which zone they're in before pulling the grip, not just guess. */
    private fun nearGrabZone(localX: Float, localY: Float, halfWidth: Float, halfHeight: Float): Pair<Boolean, Boolean> {
        if (!resizeHandlesEnabled) return false to false
        val cornerCenterX = if (localX >= 0f) halfWidth else -halfWidth
        val cornerCenterY = if (localY >= 0f) halfHeight else -halfHeight
        val nearCorner = kotlin.math.hypot(localX - cornerCenterX, localY - cornerCenterY) < POINTER_CORNER_RADIUS_METERS
        val barCenterY = if (localY >= 0f) halfHeight else -halfHeight
        val nearEdgeBar = kotlin.math.abs(localX) < POINTER_BAR_HALF_WIDTH_METERS &&
            kotlin.math.abs(localY - barCenterY) < POINTER_BAR_RADIUS_METERS
        return nearCorner to nearEdgeBar
    }

    /** Returns true if a grab actually started — callers use this to skip a simultaneous click
     * dispatch for the same press (see handlePointerMode's kdoc on the unified action button). */
    private fun tryStartPointerGrab(hand: Int, handPoses: FloatArray): Boolean {
        val hit = rayPlaneHit(handPoses, hand, quadHorizontal, quadVertical, quadDistance)
        if (hit == null) {
            Timber.i("Immersive: action pressed hand=%d but ray never crossed the quad plane (behind the hand, or pointing away)", hand)
            return false
        }
        val contentHalfWidth = (ImmersiveControls.BASE_WIDTH_METERS * quadScale) / 2f
        val contentHalfHeight = (ImmersiveControls.BASE_HEIGHT_METERS * quadScale) / 2f
        val localX = hit[0]
        val localY = hit[1]

        val (nearCorner, nearEdgeBar) = nearGrabZone(localX, localY, contentHalfWidth, contentHalfHeight)

        if (!nearCorner && !nearEdgeBar) {
            Timber.i(
                "Immersive: action pressed hand=%d but not in a grab zone — local=(%.2f,%.2f) contentHalf=(%.2f,%.2f) cornerRadius=%.2f barHalfWidth=%.2f barRadius=%.2f",
                hand, localX, localY, contentHalfWidth, contentHalfHeight,
                POINTER_CORNER_RADIUS_METERS, POINTER_BAR_HALF_WIDTH_METERS, POINTER_BAR_RADIUS_METERS,
            )
            return false
        }

        pointerGrabHand = hand
        pointerGrabIsResize = nearCorner
        pointerGrabStartHandPos[0] = hit[0]
        pointerGrabStartHandPos[1] = hit[1]
        pointerGrabStartHandPos[2] = handPoses[hand * 6 + 2]
        pointerGrabStartDistance = quadDistance
        pointerGrabStartHorizontal = quadHorizontal
        pointerGrabStartVertical = quadVertical
        pointerGrabStartScale = quadScale
        Timber.i("Immersive: pointer grab started hand=%d resize=%b", hand, nearCorner)
        return true
    }

    private fun updateActiveGrab(handPoses: FloatArray) {
        val hand = pointerGrabHand ?: return
        val base = hand * 6
        val currentHit = rayPlaneHit(handPoses, hand, pointerGrabStartHorizontal, pointerGrabStartVertical, pointerGrabStartDistance)
        if (currentHit == null) return
        val dx = currentHit[0] - pointerGrabStartHandPos[0]
        val dy = currentHit[1] - pointerGrabStartHandPos[1]
        val dz = handPoses[base + 2] - pointerGrabStartHandPos[2]

        if (pointerGrabIsResize) {
            val startDist = kotlin.math.hypot(pointerGrabStartHandPos[0], pointerGrabStartHandPos[1])
            val newDist = kotlin.math.hypot(currentHit[0], currentHit[1])
            if (startDist > 0.0001f) {
                val newScale = (pointerGrabStartScale * (newDist / startDist))
                    .coerceIn(ImmersiveControls.MIN_SCALE, ImmersiveControls.MAX_SCALE)
                quadScale = newScale
            }
        } else {
            quadHorizontal = (pointerGrabStartHorizontal + dx)
                .coerceIn(ImmersiveControls.MIN_OFFSET, ImmersiveControls.MAX_OFFSET)
            quadVertical = (pointerGrabStartVertical + dy)
                .coerceIn(ImmersiveControls.MIN_OFFSET, ImmersiveControls.MAX_OFFSET)
            quadDistance = (pointerGrabStartDistance - dz)
                .coerceIn(ImmersiveControls.MIN_DISTANCE, ImmersiveControls.MAX_DISTANCE)
        }
        applyQuadTransform()
    }

    /** Translates left-stick + A/B into dpad/select/back KeyEvents so QuickMenu's existing
     * focusable()/FocusRequester navigation (built for a real gamepad's KeyEvents) works, instead
     * of feeding Wine while the menu — which pauses the game anyway — is on screen. */
    private fun handleMenuNavigation(buttons: Int, axes: FloatArray) {
        val leftX = axes[0]
        val leftY = axes[1]
        val direction = when {
            leftY > MENU_DPAD_AXIS_THRESHOLD -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            leftY < -MENU_DPAD_AXIS_THRESHOLD -> android.view.KeyEvent.KEYCODE_DPAD_UP
            leftX < -MENU_DPAD_AXIS_THRESHOLD -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            leftX > MENU_DPAD_AXIS_THRESHOLD -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            else -> null
        }
        val now = System.currentTimeMillis()
        when {
            direction == null -> lastMenuDpadKeyCode = null
            direction != lastMenuDpadKeyCode -> {
                Timber.i(
                    "Immersive: menu dpad direction=%d leftX=%.2f leftY=%.2f quickMenuVisible=%b isOverlayPaused=%b",
                    direction, leftX, leftY, quickMenuVisible, PluviaApp.isOverlayPaused,
                )
                triggerMenuDirection(direction)
                lastMenuDpadKeyCode = direction
                lastMenuDpadHeldSince = now
                lastMenuDpadEventTime = now
            }
            now - lastMenuDpadHeldSince > MENU_DPAD_INITIAL_DELAY_MS &&
                now - lastMenuDpadEventTime > MENU_DPAD_REPEAT_DELAY_MS -> {
                triggerMenuDirection(direction)
                lastMenuDpadEventTime = now
            }
        }

        val buttonAPressed = (buttons and (1 shl XrGamepadBridge.BUTTON_A)) != 0
        if (buttonAPressed && !lastMenuButtonAPressed) {
            val focusedActivate = quickMenuFocusedActivate?.invoke()
            Timber.i("Immersive: A pressed, focusedActivatePresent=%b", focusedActivate != null)
            if (focusedActivate != null) {
                runOnUiThread { focusedActivate.invoke() }
            } else {
                dispatchMenuKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                dispatchMenuKeyEvent(android.view.KeyEvent.KEYCODE_BUTTON_A)
            }
        }
        lastMenuButtonAPressed = buttonAPressed

        val buttonBPressed = (buttons and (1 shl XrGamepadBridge.BUTTON_B)) != 0
        if (buttonBPressed && !lastMenuButtonBPressed) dispatchMenuKeyEvent(android.view.KeyEvent.KEYCODE_BACK)
        lastMenuButtonBPressed = buttonBPressed

        val stickClickHeld = (buttons and ((1 shl XrGamepadBridge.BUTTON_L3) or (1 shl XrGamepadBridge.BUTTON_R3))) != 0
        val buttonLBPressed = (buttons and (1 shl XrGamepadBridge.BUTTON_LB)) != 0
        if (buttonLBPressed && !lastMenuButtonLBPressed && !stickClickHeld && quickMenuVisible) {
            Timber.i("Immersive: LB pressed, cycleTabPresent=%b", quickMenuCycleTab != null)
            runOnUiThread { quickMenuCycleTab?.invoke(false) }
        }
        lastMenuButtonLBPressed = buttonLBPressed

        val buttonRBPressed = (buttons and (1 shl XrGamepadBridge.BUTTON_RB)) != 0
        if (buttonRBPressed && !lastMenuButtonRBPressed && !stickClickHeld && quickMenuVisible) {
            Timber.i("Immersive: RB pressed, cycleTabPresent=%b", quickMenuCycleTab != null)
            runOnUiThread { quickMenuCycleTab?.invoke(true) }
        }
        lastMenuButtonRBPressed = buttonRBPressed
    }

    private fun dispatchMenuKeyEvent(keyCode: Int) {
        runOnUiThread {
            val time = android.os.SystemClock.uptimeMillis()
            dispatchMenuKeyEventInternal(android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, keyCode, 0))
            dispatchMenuKeyEventInternal(android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    /** Routes a dpad direction to a locked slider's decrease/increase if one is active, then to the tab-rail bypass for LEFT specifically, otherwise moves fo. */
    private fun triggerMenuDirection(keyCode: Int) {
        val active = quickMenuAdjustmentControl?.invoke()
        if (active != null &&
            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val (onDecrease, onIncrease) = active
            Timber.i("Immersive: adjustment bypass triggered keyCode=%d (locked row's decrease/increase)", keyCode)
            runOnUiThread { if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) onDecrease() else onIncrease() }
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            runOnUiThread { quickMenuFocusTabRail?.invoke() }
        } else {
            moveMenuFocus(keyCode)
        }
    }

    /** Moves Compose focus directly via QuickMenu's registered FocusManager, instead of dispatching a synthetic dpad KeyEvent through dispatchMenuKeyEvent()/. */
    private fun moveMenuFocus(keyCode: Int) {
        val direction = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> androidx.compose.ui.focus.FocusDirection.Down
            android.view.KeyEvent.KEYCODE_DPAD_UP -> androidx.compose.ui.focus.FocusDirection.Up
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> androidx.compose.ui.focus.FocusDirection.Left
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> androidx.compose.ui.focus.FocusDirection.Right
            else -> return
        }
        runOnUiThread {
            val moved = quickMenuFocusManager?.moveFocus(direction)
            Timber.i("Immersive: moveFocus direction=%s focusManagerPresent=%b result=%s", direction, quickMenuFocusManager != null, moved)
        }
    }

    private fun dispatchMenuKeyEventInternal(event: android.view.KeyEvent) {
        val consumed = PluviaApp.events.emit(app.gamenative.events.AndroidEvent.KeyEvent(event)) { results ->
            results.any { it }
        } == true
        val fallbackHandled = if (!consumed) dispatchKeyEvent(event) else false
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            Timber.i(
                "Immersive: menu key dispatch keyCode=%d consumedByBus=%b dispatchKeyEvent=%b",
                event.keyCode, consumed, fallbackHandled,
            )
        }
    }

    /**
     * Two decoupled loops: game frames capture on a background thread at full rate, while the
     * Compose overlay re-renders on a slower timer ([OVERLAY_REFRESH_INTERVAL_MS]) on the UI
     * thread, which View.draw requires. [overlayLock] guards the shared bitmap.
     */
    private fun startFrameCaptureLoop() {
        captureThread = HandlerThread("XrFrameCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        captureActive.set(true)
        overlayContentLastVisibleAt = android.os.SystemClock.uptimeMillis()
        overlayClearSubmitted = false
        scheduleNextCapture()
        scheduleNextOverlayRefresh()
    }

    /** Attaches a direct-render bridge to the running container's renderer, whichever it turns
     * out to be — called (idempotently, see the guard below) from [scheduleNextCapture]'s retry
     * loop rather than once at session start, since the game's XServerView/renderer isn't created
     * yet at that point for a typical launch. */
    private fun setupDirectRenderBridgeIfSupported() {
        val actualRenderer = PluviaApp.xServerView?.renderer
        val glRenderer = actualRenderer as? GLRenderer

        if (glRenderer != null && glRenderer.isEffectsRequireCompositor()) {
            if (directRenderBlockedByEffects != true) {
                directRenderBlockedByEffects = true
                Timber.i("Immersive: GL screen effects active — direct-render bridge disabled, using PixelCopy")
            }
            if (directGLBridge != null) teardownDirectRenderBridge()
            return
        }

        if (directGLBridge != null || directVulkanBridge != null) return

        if (glRenderer != null) {
            val bridge = DirectGLBridge(
                onBufferReady = { buffer ->
                    if (xrSessionHandle != 0L) XrNative.nativeSetSharedGameBuffer(xrSessionHandle, buffer)
                    directRenderActive = true
                    applyQuadTransform()
                    Timber.i("Immersive: direct GL render path active — game frame now shared via GPU buffer, PixelCopy of the game layer stopped")
                },
            )
            directGLBridge = bridge
            directRenderBlockedByEffects = false
            glRenderer.setXrFrameBridge(bridge)
            Timber.i("Immersive: GLRenderer detected — direct-render bridge attached, buffer import pending first frame")
            return
        }

        val vulkanRenderer = actualRenderer as? com.winlator.renderer.VulkanRenderer
        if (vulkanRenderer != null) {
            val bridge = DirectVulkanBridge(
                onFrame = { ahbPtr, _, _ ->
                    if (xrSessionHandle != 0L) {
                        XrNative.nativeSetSharedGameBufferPtr(xrSessionHandle, ahbPtr)
                        if (!directRenderActive) {
                            directRenderActive = true
                            applyQuadTransform()
                        }
                    }
                },
            )
            directVulkanBridge = bridge
            vulkanRenderer.setVulkanXrFrameBridge(bridge)
            Timber.i("Immersive: VulkanRenderer detected — direct-render bridge attached, waiting for its first AHardwareBuffer (PixelCopy stays as fallback until then)")
            return
        }

        if (actualRenderer != null && !directRenderProbeLogged) {
            directRenderProbeLogged = true
            Timber.i("Immersive: renderer is %s (unrecognized) — direct GPU render path unavailable, staying on PixelCopy", actualRenderer.javaClass.simpleName)
        }
    }

    private fun teardownDirectRenderBridge() {
        directVulkanBridge = null
        (PluviaApp.xServerView?.renderer as? com.winlator.renderer.VulkanRenderer)?.setVulkanXrFrameBridge(null)
        val bridge = directGLBridge
        if (bridge != null) {
            directGLBridge = null
            (PluviaApp.xServerView?.renderer as? GLRenderer)?.setXrFrameBridge(null)
            PluviaApp.xServerView?.queueEvent { bridge.release() }
        }
        directRenderActive = false
    }

    private fun scheduleNextCapture() {
        if (!captureActive.get()) return
        val handler = captureHandler ?: return

        setupDirectRenderBridgeIfSupported()

        val surfaceView = PluviaApp.xServerView as? SurfaceView
        val width = surfaceView?.width ?: 0
        val height = surfaceView?.height ?: 0

        val glBridge = directGLBridge
        if (glBridge != null) {
            if (width > 0 && height > 0) {
                PluviaApp.xServerView?.queueEvent { glBridge.ensureAllocated(width, height) }
            }
            if (directRenderActive) {
                handler.postDelayed({ scheduleNextCapture() }, CAPTURE_RETRY_DELAY_MS)
                return
            }
        }

        if (directVulkanBridge != null && directRenderActive) {
            handler.postDelayed({ scheduleNextCapture() }, CAPTURE_RETRY_DELAY_MS)
            return
        }

        if (surfaceView != null && surfaceView !== surfaceCallbackAttachedTo) {
            surfaceCallbackAttachedTo?.holder?.removeCallback(surfaceReadyCallback)
            surfaceReady = surfaceView.holder?.surface?.isValid == true
            surfaceView.holder.addCallback(surfaceReadyCallback)
            surfaceCallbackAttachedTo = surfaceView
        }

        if (surfaceView == null || width <= 0 || height <= 0 || !surfaceReady) {
            handler.postDelayed({ scheduleNextCapture() }, CAPTURE_RETRY_DELAY_MS)
            return
        }

        val bitmap = gameCaptureBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setHasAlpha(false)
                gameCaptureBitmap = it
            }

        if (!surfaceReady || surfaceView.holder?.surface?.isValid != true) {
            handler.postDelayed({ scheduleNextCapture() }, CAPTURE_RETRY_DELAY_MS)
            return
        }

        val captureStartTime = android.os.SystemClock.uptimeMillis()
        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    compositeGameFrame(width, height)
                }
                val elapsed = android.os.SystemClock.uptimeMillis() - captureStartTime
                val delay = (MIN_CAPTURE_INTERVAL_MS - elapsed).coerceAtLeast(0L)
                if (delay > 0L) handler.postDelayed({ scheduleNextCapture() }, delay) else scheduleNextCapture()
            }, handler)
        } catch (t: Throwable) {
            Timber.w(t, "PixelCopy request failed")
            handler.postDelayed({ scheduleNextCapture() }, CAPTURE_RETRY_DELAY_MS)
        }
    }

    /** Composites the game frame with the cached overlay on the capture thread. Runs only when
     * not direct-rendering (see scheduleNextCapture). */
    private fun compositeGameFrame(width: Int, height: Int) {
        if (!captureActive.get() || xrSessionHandle == 0L) return
        val gameFrame = gameCaptureBitmap ?: return

        val finalBitmap = finalFrameBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { finalFrameBitmap = it }

        val canvas = android.graphics.Canvas(finalBitmap)
        canvas.drawBitmap(gameFrame, 0f, 0f, null)
        synchronized(overlayLock) {
            overlayLayerBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
        if (xrPointerModeActive) {
            drawPointerCursors(canvas, width, height)
        }
        XrNative.nativeSubmitFrame(xrSessionHandle, finalBitmap)
    }

    /** Slow path: re-renders just the Compose overlay (transparent where the game is) into
     * [overlayLayerBitmap], on its own throttled timer, on the UI thread (required for
     * View.draw). Self-chaining like the capture loop, independent of it. */
    private fun scheduleNextOverlayRefresh() {
        if (!captureActive.get()) return
        val handler = captureHandler ?: return
        handler.postDelayed({ refreshOverlayLayer() }, OVERLAY_REFRESH_INTERVAL_MS)
    }

    private fun refreshOverlayLayer() {
        runOnUiThread {
            if (!captureActive.get()) {
                return@runOnUiThread
            }

            val contentView = findViewById<android.view.View>(android.R.id.content)
            val width = contentView.width
            val height = contentView.height
            if (width <= 0 || height <= 0) {
                scheduleNextOverlayRefresh()
                return@runOnUiThread
            }

            val hasOverlayContent = quickMenuVisible || overlayPausedUi || showControlsOnboarding ||
                xrPointerModeActive || bootingSplashVisible
            val now = android.os.SystemClock.uptimeMillis()
            if (hasOverlayContent) {
                overlayContentLastVisibleAt = now
                overlayClearSubmitted = false
            }
            // Fade-outs and the mode indicator outlive their flag, so keep drawing for a grace window.
            if (!hasOverlayContent && now - overlayContentLastVisibleAt > OVERLAY_CONTENT_GRACE_MS) {
                if (overlayClearSubmitted) {
                    scheduleNextOverlayRefresh()
                    return@runOnUiThread
                }
                overlayClearSubmitted = true
                synchronized(overlayLock) {
                    val bitmap = overlayLayerBitmap?.takeIf { it.width == width && it.height == height }
                        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { overlayLayerBitmap = it }
                    android.graphics.Canvas(bitmap)
                        .drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    if (directRenderActive && xrSessionHandle != 0L) {
                        XrNative.nativeSubmitFrame(xrSessionHandle, bitmap)
                    }
                }
                scheduleNextOverlayRefresh()
                return@runOnUiThread
            }

            val surfaceView = PluviaApp.xServerView as? SurfaceView
            val previousAlpha = surfaceView?.alpha ?: 1f

            try {
                synchronized(overlayLock) {
                    val bitmap = overlayLayerBitmap?.takeIf { it.width == width && it.height == height }
                        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { overlayLayerBitmap = it }
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    if (surfaceView != null) surfaceView.alpha = 0f
                    try {
                        contentView.draw(canvas)
                        if (directRenderActive && xrPointerModeActive) {
                            drawPointerCursors(canvas, width, height)
                        }
                    } finally {
                        if (surfaceView != null) surfaceView.alpha = previousAlpha
                    }
                }
                if (directRenderActive && xrSessionHandle != 0L) {
                    synchronized(overlayLock) {
                        overlayLayerBitmap?.let { XrNative.nativeSubmitFrame(xrSessionHandle, it) }
                    }
                }
                overlayCaptureLogCounter++
                if (overlayCaptureLogCounter % 30 == 0) {
                    val centerPx = synchronized(overlayLock) { overlayLayerBitmap?.getPixel(width / 2, height / 2) } ?: 0
                    Timber.i(
                        "Immersive: overlay layer refreshed size=%dx%d centerPx=#%08X sessionHandle=%d",
                        width,
                        height,
                        centerPx,
                        xrSessionHandle,
                    )
                }
            } catch (t: Throwable) {
                Timber.w(t, "Overlay layer refresh failed")
            }
            scheduleNextOverlayRefresh()
        }
    }

    private fun stopFrameCaptureLoop() {
        captureActive.set(false)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        gameCaptureBitmap = null
        finalFrameBitmap = null
        synchronized(overlayLock) { overlayLayerBitmap = null }
        surfaceCallbackAttachedTo?.holder?.removeCallback(surfaceReadyCallback)
        surfaceCallbackAttachedTo = null
        surfaceReady = false
    }

    private fun stopXrSession() {
        pollingActive.set(false)
        // The thread calls into the native session, so it must be joined before the handle dies.
        pollingThread?.interrupt()
        pollingThread?.join(500)
        pollingThread = null
        stopFrameCaptureLoop()
        teardownDirectRenderBridge()
        if (xrSessionHandle != 0L) {
            XrNative.nativeRequestStop(xrSessionHandle)
            XrNative.nativeJoinAndDestroy(xrSessionHandle)
            xrSessionHandle = 0L
        }
    }
}

/** Shown for a few seconds once the game's window actually appears, explaining the two chords
 * that have no other affordance to discover them (quick menu, XR-pointer-mode toggle). */
@Composable
private fun ImmersiveControlsOnboarding(visible: Boolean, onDismiss: () -> Unit) {
    LaunchedEffect(visible) {
        if (visible) {
            delay(7000)
            onDismiss()
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.immersive_onboarding_disable_passthrough), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.immersive_onboarding_quick_menu), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.immersive_onboarding_pointer_toggle), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    }
}

/** Brief centered indicator, fading in/out, whenever the controller interpretation mode changes
 * (Xbox gamepad <-> XR pointer) — the only other feedback for that switch is native log output. */
@Composable
private fun ImmersiveModeChangeIndicator(pointerModeActive: Boolean) {
    var visible by remember { mutableStateOf(false) }
    var shownForPointerMode by remember { mutableStateOf(pointerModeActive) }
    LaunchedEffect(pointerModeActive) {
        shownForPointerMode = pointerModeActive
        visible = true
        delay(1800)
        visible = false
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = if (shownForPointerMode) Icons.Default.TouchApp else Icons.Default.SportsEsports,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = stringResource(if (shownForPointerMode) R.string.immersive_mode_xr_pointer else R.string.immersive_mode_xbox),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
