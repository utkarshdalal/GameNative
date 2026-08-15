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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

/**
 * Dedicated entry point for launching a game directly in Meta Quest immersive mode.
 *
 * Unlike the normal Play flow (which navigates within [app.gamenative.MainActivity]'s
 * nav-graph), this always boots straight to the configured executable
 * (bootToContainer = false) — there is no "open the Wine desktop" variant here.
 *
 * Hosts the same [XServerScreen] composable used by the normal flow (bootstrap, Wine/Box64
 * launch, on-screen controls, quick menu, etc. are all reused as-is) and layers a native
 * OpenXR session on top for the actual immersive presentation + Touch controller input —
 * see app/src/main/cpp/xrimmersive. The game's actual rendered frame is captured off the
 * existing on-screen [PluviaApp.xServerView] via [PixelCopy] (works for both the Vulkan and
 * legacy GL renderer, no changes to GLRenderer/XServerView needed) and handed to the native
 * module, which uploads it into the OpenXR quad layer every frame — this is not a placeholder,
 * it shows the actual running game.
 */
@AndroidEntryPoint
class ImmersiveXrActivity : androidx.activity.ComponentActivity() {

    companion object {
        private const val EXTRA_APP_ID = "app_id"
        private const val EXTRA_IS_OFFLINE = "is_offline"
        private const val POLL_INTERVAL_MS = 11L // ~90Hz, matches the Quest's native refresh rate
        // While the game window isn't up yet. Kept well above a tight spin: PixelCopy on a
        // Surface that's mid-attach can crash *natively* (confirmed via tombstone — see
        // scheduleNextCapture's isValid() comment) in a way no try/catch here can stop, so
        // fewer attempts during that startup window means less exposure to the race, not just
        // less log spam.
        private const val CAPTURE_RETRY_DELAY_MS = 200L
        private const val OVERLAY_REFRESH_INTERVAL_MS = 33L // ~30fps — plenty for a menu/HUD,
        // and walking the whole Compose tree at full game-frame rate is what caused the crash.

        // Horizon OS hands this Activity a volumetric panel surface at ~4128x2208 physical px but
        // only reports density=1.25 (adb: `wm density` -> 200), i.e. a ~3300x1770dp logical canvas.
        // QuickMenu/XServerScreen were laid out assuming a normal phone/tablet-sized dp canvas, so
        // at that density every fixed-dp element (rail icons, text) renders as a tiny sliver pinned
        // to one corner. Overriding density here forces a sane logical resolution instead.
        private const val IMMERSIVE_UI_DENSITY = 2.5f

        // Left-stick-as-dpad tuning for menu navigation (see lastMenuDpadKeyCode).
        private const val MENU_DPAD_AXIS_THRESHOLD = 0.5f
        private const val MENU_DPAD_INITIAL_DELAY_MS = 400L
        private const val MENU_DPAD_REPEAT_DELAY_MS = 150L

        // XR pointer-mode grab handles, in meters (same space as the quad transform).
        //
        // No margin: the game's own displayed size is IDENTICAL in every controller mode, and
        // never grows for a margin band either — an earlier version grew the quad by a fixed
        // margin on every side to give handles somewhere to live outside the game, but that
        // margin came out of the direct-render path's fixed 1280x720 swapchain pixel budget
        // (kSwapchainWidth/Height in xr_immersive.cpp) — a real, measured ~45% pixel-area loss at
        // default scale, visible as pixelation. Handles now live INSIDE the content instead (see
        // drawPointerCursors/nearGrabZone), only while resizeHandlesEnabled is on.
        //
        // Small visible gap between the game content's own edge and the drawn handle — sitting
        // flush against the game's pixels reads as "stuck to the screen" rather than a floating
        // system-style indicator.
        private const val POINTER_INDICATOR_GAP_METERS = 0.02f
        // Small round handles (corners + top/bottom edge midpoints), not long bars or crosses —
        // closer to how actual VR headset system UI marks a grabbable edge: a small, elegant,
        // consistent marker, not a shape that dominates the edge it's next to.
        private const val POINTER_HANDLE_RADIUS_METERS = 0.035f
        // Idle stroke is 2x the original 3.5f base, hover/grab is 3x — a thin line staying thin
        // was easy to lose track of exactly when a hand was actually aiming at it.
        private const val POINTER_HANDLE_STROKE_WIDTH_IDLE_PX = 7.0f
        private const val POINTER_HANDLE_STROKE_WIDTH_ACTIVE_PX = 10.5f
        // Shrunk from an earlier 0.16f — that radius, sitting right next to the quick menu's own
        // buttons now that handles are drawn inside the content, was catching the pointer well
        // before it was actually on a handle and blocking clicks on whatever's underneath. Small
        // enough now that it takes deliberately aiming at the handle itself, not just its general
        // neighborhood.
        private const val POINTER_CORNER_RADIUS_METERS = 0.06f
        private const val POINTER_BAR_HALF_WIDTH_METERS = 0.08f
        private const val POINTER_BAR_RADIUS_METERS = 0.06f
        // Hysteresis, not a single threshold: without it, an analog trigger resting near the
        // threshold (hand tremor holding it "about half pulled") flaps press/release dozens of
        // times a second — logs showed a grab restarting every ~250-300ms while the user held
        // still, which is exactly that. Needs a firm pull past PRESS to start, only lets go past
        // the much lower RELEASE.
        private const val POINTER_GRAB_PRESS_THRESHOLD = 0.65f
        private const val POINTER_GRAB_RELEASE_THRESHOLD = 0.3f
        private const val MIN_CAPTURE_INTERVAL_MS = 11L // ~90fps cap, matches the HMD's own fixed
        // compositor rate — capturing/uploading faster than the display can show is pure waste,
        // competing with the game's own rendering for the same GPU for no visible benefit.

        // Container extras — persist the "Immersif" quick-menu tab's settings per game.
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

    // Dedicated open/close toggle for the long-press-Start gesture — see XServerScreen's
    // toggleQuickMenu kdoc for why this is used instead of backAction/gameBack.
    @Volatile
    private var quickMenuToggle: (() -> Unit)? = null

    // See QuickMenu's registerFocusManager kdoc — driving Compose focus directly, bypassing
    // Activity.dispatchKeyEvent() for arrow-key navigation (confirmed via logging that dispatched
    // synthetic dpad KeyEvents never reach Compose's key dispatch in this Activity at all).
    @Volatile
    private var quickMenuFocusManager: androidx.compose.ui.focus.FocusManager? = null

    // Same bypass as quickMenuFocusManager, for LB/RB tab-cycling — see QuickMenu's
    // registerCycleTab kdoc.
    @Volatile
    private var quickMenuCycleTab: ((Boolean) -> Unit)? = null

    // Same bypass again, for a locked QuickMenuAdjustmentRow's left/right drag — see QuickMenu's
    // registerAdjustmentControl kdoc. Invoking this returns (onDecrease, onIncrease) for whichever
    // row is currently focused+lock-toggled, or null if none is.
    @Volatile
    private var quickMenuAdjustmentControl: (() -> Pair<() -> Unit, () -> Unit>?)? = null

    // Same bypass again, for LEFT specifically — see QuickMenu's registerFocusTabRail kdoc.
    // FocusManager.moveFocus(Left) reaching the tab rail from content turned out to depend on the
    // focused row's vertical position lining up with a rail icon (confirmed by comparing two
    // sessions' logs: identical code, one where Left worked and one where it didn't, differing
    // only in which row happened to be focused) — this calls requestFocus() on the CURRENTLY
    // SELECTED tab's own rail button directly instead, which doesn't depend on that.
    @Volatile
    private var quickMenuFocusTabRail: (() -> Unit)? = null

    // Same bypass again, for a plain radio/toggle row (e.g. ScreenEffectsPanel's scaling-mode
    // rows) whose default DPAD_CENTER/A click doesn't reliably fire here — see QuickMenu's
    // registerFocusedActivate kdoc. Invoking this returns whichever row's onSelect is currently
    // focused, or null if none is.
    @Volatile
    private var quickMenuFocusedActivate: (() -> (() -> Unit)?)? = null

    // Pushes the Menu/Start button's physical-hold state (native flags[2]) into QuickMenu, which
    // uses it to suppress the real Android KeyEvents (DPAD_CENTER/BUTTON_A/BUTTON_START) that
    // Horizon OS's own gamepad input dispatch generates while that button is held — a completely
    // separate path from this Activity's native OpenXR polling, immune to every other suppression
    // here.
    @Volatile
    private var quickMenuSetStartHeld: ((Boolean) -> Unit)? = null
    private var lastPushedStartHeld = false

    private var xrSessionHandle: Long = 0L
    private var currentAppId: String? = null

    private var pollingThread: Thread? = null
    private val pollingActive = AtomicBoolean(false)
    private var cachedWinHandler: WinHandler? = null

    // Tracks Surface readiness via the actual SurfaceHolder lifecycle callbacks rather than
    // polling isValid() — confirmed by testing (two native tombstones so far, same signature:
    // SIGSEGV inside PixelCopy.request()'s own ANativeWindow_acquire) that isValid() can still
    // race against the Surface being attached, since it's a point-in-time check with no ordering
    // guarantee relative to the native call that follows it. surfaceCreated/surfaceDestroyed are
    // real lifecycle events for this exact Surface, so gating on them is a much narrower window,
    // even if not a mathematically airtight guarantee against every possible interleaving.
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
    // Three bitmaps for the two decoupled loops (see startFrameCaptureLoop's kdoc):
    //  - gameCaptureBitmap: written by PixelCopy, at full game-frame rate.
    //  - overlayLayerBitmap: written by a real View.draw(), at a much slower throttled rate —
    //    guarded by overlayLock since it's read by the other (faster, background-thread) loop.
    //  - finalFrameBitmap: the actual composite of the two, uploaded to the native module.
    private var gameCaptureBitmap: Bitmap? = null
    private val overlayLock = Any()
    private var overlayLayerBitmap: Bitmap? = null
    private var finalFrameBitmap: Bitmap? = null
    private var overlayCaptureLogCounter = 0
    private var pointerGripHeldLogCounter = 0

    // Direct GPU-render path — GLRenderer via DirectGLBridge/GLHardwareBufferImporter, or
    // VulkanRenderer (the default for most real games/dxvk) via DirectVulkanBridge reusing its
    // own per-frame AHardwareBuffer (see VulkanXrFrameBridge's kdoc for why this isn't actually
    // tied to VulkanRenderer's own "zero-copy scanout" path, despite the name). Both are attached
    // opportunistically and are no-ops
    // until (and unless) their renderer type is actually found. directRenderActive only flips
    // true once a real frame has actually flowed through — for GLRenderer that's guaranteed on
    // the next frame once attached, but VulkanRenderer's scanout is conditional (disabled while
    // screen-effect filters force the compositor path), so scheduleNextCapture keeps PixelCopy
    // running as a fallback until this is confirmed true, not just whenever a bridge is attached.
    // Known limitation: if scanout later deactivates mid-session (e.g. an effect toggled on),
    // this does not currently fall back to PixelCopy again — out of scope for this pass.
    private var directGLBridge: DirectGLBridge? = null
    private var directVulkanBridge: DirectVulkanBridge? = null
    @Volatile
    private var directRenderActive = false
    private var directRenderProbeLogged = false

    // Menu navigation state: while quickMenuVisible, the left stick + A/B are translated into
    // Android KeyEvents (dispatchKeyEvent) instead of being fed to Wine, since QuickMenu is
    // regular Compose UI navigated via focusable()/FocusRequester like any Android TV-style
    // screen — it never sees anything from XrGamepadBridge (that only writes to Wine's shared
    // memory gamepad buffer), which is why the controller couldn't drive it at all before.
    private var lastMenuDpadKeyCode: Int? = null
    private var lastMenuDpadHeldSince = 0L
    private var lastMenuDpadEventTime = 0L
    private var lastMenuButtonAPressed = false
    private var lastMenuButtonBPressed = false
    private var lastMenuButtonLBPressed = false
    private var lastMenuButtonRBPressed = false

    // XR pointer mode: toggled by double-clicking either thumbstick (see native pointerModeToggled).
    // Direct manipulation of the quad's transform via the controllers, like grabbing a flat
    // panel window's corner/edge on Horizon OS — grab a corner to resize, grab the top/bottom
    // edge to reposition. Independent of quickMenuVisible/isOverlayPaused: usable any time,
    // same as a system window.
    // mutableStateOf rather than a plain @Volatile var so the mode-change indicator (Compose)
    // can react to it — writes from the polling thread are safe, Compose's snapshot system
    // handles cross-thread visibility on its own. Reading it from plain (non-@Composable) logic
    // code elsewhere in this class works exactly the same as a normal property.
    private var xrPointerModeActive by mutableStateOf(false)
    // Toggled by a dedicated quick-menu button (Immersive tab), NOT the pointer-mode chord — an
    // earlier version made resize/move handles always available (given a margin band to live in)
    // in pointer mode, which cost a real, measured chunk of the direct-render path's fixed
    // swapchain pixel budget (~45% of the pixel area at default scale) just to have somewhere for
    // handles to sit outside the game. Handles now live INSIDE the content instead (see
    // drawPointerCursors/nearGrabZone) and only exist at all — visible or grabbable — while this
    // is on, so the game gets the full pixel budget the rest of the time. Same mutableStateOf
    // cross-thread pattern as xrPointerModeActive above.
    private var resizeHandlesEnabled by mutableStateOf(false)
    // Either the grip or the index trigger — see handlePointerMode's kdoc for why these were
    // unified into one "action" signal per hand instead of assigning grab and click to specific
    // buttons. @Volatile since the quick menu's resize-handles toggle (UI thread) now also writes
    // these directly, alongside the polling thread — previously only the polling thread ever
    // touched them, so plain vars were safe; a raw cross-thread write without this is the exact
    // class of visibility bug already found and fixed once this session for xrFrameBridge fields.
    @Volatile private var lastLeftActionPressed = false
    @Volatile private var lastRightActionPressed = false
    @Volatile private var pointerGrabHand: Int? = null // 0 = left, 1 = right
    private var pointerGrabIsResize = false
    private val pointerGrabStartHandPos = FloatArray(3)
    private var pointerGrabStartDistance = 0f
    private var pointerGrabStartHorizontal = 0f
    private var pointerGrabStartVertical = 0f
    private var pointerGrabStartScale = 0f

    // Where each hand's ray currently hits the quad plane, in quad-local meters — read by
    // compositeAndSubmitFrame (a different thread) to draw a visible cursor dot, since without
    // ANY feedback the user has no way to tell where they're pointing (there's no true 3D laser
    // — that needs native GL line rendering, a bigger lift; a 2D dot drawn directly onto the
    // composited frame is a much cheaper approximation that still answers "where am I aiming").
    @Volatile private var pointerCursorLeftValid = false
    @Volatile private var pointerCursorLeftX = 0f
    @Volatile private var pointerCursorLeftY = 0f
    @Volatile private var pointerCursorRightValid = false
    @Volatile private var pointerCursorRightX = 0f
    @Volatile private var pointerCursorRightY = 0f

    private var pointerTouchDownTimeLeft = 0L
    private var pointerTouchDownTimeRight = 0L

    // Set by XServerScreen's onQuickMenuVisibilityChanged. SurfaceView content is composited on
    // its own layer by the system — PixelCopy(Window) straight up cannot see it (shows a blank/
    // gray hole instead), so the game must be captured via PixelCopy(SurfaceView) directly. The
    // quick menu (and other overlay UI) is regular Compose content in the *other* layer, which
    // PixelCopy(SurfaceView) can't see either — so this flag switches capture source instead of
    // trying to composite both, since the game is paused behind the menu anyway.
    @Volatile
    private var quickMenuVisible = false

    // Both only ever read/written from the polling thread (startControllerPollingLoop) — see its
    // own kdoc for why these exist: suppressing a button that's still held right as menu/pause
    // handling hands control back to the game, so the same physical press that dismissed our
    // menu doesn't also reach the game as a fresh press.
    private var wasInMenuNavigationMode = false
    private var buttonSuppressMaskUntilRelease = 0

    // "Immersif" quick-menu tab state — loaded from the container's extras once available,
    // applied to the native quad transform on every change, and persisted back.
    private var quadDistance by mutableFloatStateOf(ImmersiveControls.DEFAULT_DISTANCE)
    private var quadHorizontal by mutableFloatStateOf(ImmersiveControls.DEFAULT_OFFSET)
    private var quadVertical by mutableFloatStateOf(ImmersiveControls.DEFAULT_OFFSET)
    private var quadScale by mutableFloatStateOf(ImmersiveControls.DEFAULT_SCALE)
    private var passthroughEnabled by mutableStateOf(false)
    private var showControlsOnboarding by mutableStateOf(false)
    private var cachedContainer: Container? = null
    // Refreshed whenever the quick menu opens (see onQuickMenuVisibilityChanged) and right after
    // the reset button runs — null when the renderer isn't VulkanRenderer at all (the "Immersif"
    // tab's reset button/description only shows when this is non-null).
    private var directRenderBlockedByEffects by mutableStateOf<Boolean?>(null)

    override fun attachBaseContext(newBase: Context) {
        // Horizon OS hands this Activity a volumetric panel surface at ~4128x2208 physical px but
        // only reports density=1.25 (adb: `wm density` -> 200), i.e. a ~3300x1770dp logical
        // canvas. QuickMenu/XServerScreen's HUD (a plain View added via addView, not Compose —
        // LocalDensity overrides in setContent wouldn't reach it) were both built assuming a
        // normal phone/tablet-sized dp canvas, so at that density every fixed-dp element renders
        // as a tiny sliver. Overriding density on the base Context fixes it for Compose *and*
        // legacy Views uniformly, since everything in this Activity inherits from it.
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

        AppUtils.keepScreenOn(this)
        loadImmersiveSettings(appId)

        setContent {
            PluviaTheme {
                val context = LocalContext.current

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
                        viewModel.onWindowMapped(ctx, window, appId)
                        showControlsOnboarding = true
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
                            // Handles are only usable via the XR laser pointer (grabbing a
                            // corner/edge), not Xbox mode's stick+dpad navigation — switch modes
                            // together with the toggle instead of leaving the user to find the
                            // pointer-mode chord themselves, and always land back on Xbox mode
                            // when turning handles off (not whichever mode was active before).
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
            // Always start facing the user, regardless of whatever orbit position was persisted
            // from the previous session — only distance/scale carry over.
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
        // No margin — the quad is always exactly the game's own content size, identical in every
        // controller mode. See compositeGameFrame's kdoc for why: an earlier version grew the
        // quad by a fixed margin band to give resize/move handles a place to live outside the
        // game, which cost a real, measured chunk of the direct-render path's fixed swapchain
        // pixel budget. contentScaleX/Y are always 1.0 now (no remap needed) — kept as real JNI
        // parameters rather than removed so this stays a plain revert-to-1.0, not a signature
        // change, should a margin ever come back.
        XrNative.nativeSetQuadTransform(
            xrSessionHandle,
            quadHorizontal,
            quadVertical,
            -quadDistance,
            ImmersiveControls.BASE_WIDTH_METERS * quadScale,
            ImmersiveControls.BASE_HEIGHT_METERS * quadScale,
            1f,
            1f,
        )
    }

    override fun onResume() {
        super.onResume()
        // MainActivity is the only thing that normally toggles this flag (see its own
        // onResume/onPause). Since this is a separate Activity, MainActivity.onPause() fires
        // when we're launched (setting it false) and nothing ever set it back — XServerScreen's
        // post-setup check (`if (!PluviaApp.isActivityInForeground && !neverSuspend)`) then
        // immediately re-paused the freshly-launched game, which is why immersive launches
        // showed a black screen: the game process was running but paused right away.
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
            // Android guarantees the outgoing activity's onPause() completes before the
            // incoming one's onResume() runs, but does NOT guarantee onDestroy() runs that
            // early — it can (and did, per testing) land after MainActivity.onResume() has
            // already read PluviaApp.xEnvironment/SteamService.keepAlive and decided to show
            // "Launching..." again for a game that no longer has a window. Tearing down here
            // instead — while we know for sure this pause means we're really exiting, not just
            // briefly backgrounding for a system dialog — closes that race.
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
        // Safety net for onPause() not having caught it (e.g. the process/Activity was torn
        // down some other way): every step here no-ops if shutdownEnvironment() already ran.
        PluviaApp.shutdownEnvironment()
        super.onDestroy()
    }

    private fun startXrSessionIfNeeded() {
        if (xrSessionHandle != 0L) return
        xrSessionHandle = try {
            XrNative.nativeCreate(this)
        } catch (t: Throwable) {
            // libxrimmersive.so hasn't been built/bundled yet (see XrNative's kdoc) — degrade to
            // a plain direct launch rather than crashing the whole immersive entry point.
            Timber.w(t, "Native OpenXR module unavailable — immersive rendering/controller mapping disabled")
            return
        }

        // Apply whatever settings are already loaded (may still be the defaults if
        // loadImmersiveSettings()'s IO hasn't finished yet — it re-applies once it has).
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
                        // The activation chord itself holds down the buttons driving BOTH grab
                        // and click — seeding these from this SAME poll's snapshot (still held
                        // from the chord) rather than forcing false avoids a spurious same-poll
                        // rising edge: an earlier version forced both to false here, which made
                        // handlePointerMode see a brand-new press on this exact poll and
                        // immediately act on whatever the hand happened to be aiming at mid-chord
                        // — logs confirmed this both fired phantom grab attempts right after every
                        // mode-enable, AND (for the click/trigger case) could dispatch a synthetic
                        // touch that landed on the quick menu's dismiss backdrop, closing it. The
                        // user now needs one clean release+press to start a grab/click, same as
                        // any other button — slightly less "instant" than the original intent,
                        // but no more phantom presses eating the user's first real one.
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
                    if (wasInMenuNavigationMode && !inMenuMode) {
                        // Just exited quick-menu/pause handling — e.g. the same A press that just
                        // dismissed our own "paused" screen and resumed the game is very likely
                        // still physically held on THIS exact poll. Forwarding it immediately as
                        // a fresh gamepad press let the GAME's own in-game pause menu (a separate
                        // thing from our shell's pause state) read that same held button as a
                        // second, unwanted "confirm" — e.g. pressing A to unpause also activating
                        // whatever the game's own menu had focused (its Resume item). Snapshot
                        // whatever's held right now and mask it out of forwarding until released.
                        buttonSuppressMaskUntilRelease = buttons[0]
                    }
                    wasInMenuNavigationMode = inMenuMode
                    when {
                        xrPointerModeActive -> handlePointerMode(buttons[0], axes, handPoses, flags[0])
                        // While the Menu/Start button is physically held (flags[2]), skip menu
                        // navigation entirely — same hand that's holding it may still rest on/near
                        // the thumbstick, and residual leftX/leftY was being read as a genuine dpad
                        // direction the instant the menu opened (or closed), landing on whatever
                        // was focused — e.g. dragging an already-locked slider. Start should only
                        // open/close the menu, nothing else; this makes that true regardless of
                        // stray input during the hold, and applies symmetrically to both the
                        // opening AND the closing hold.
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
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Direct manipulation of the quad transform via a hand's aim-pose ray, active only while
     * [xrPointerModeActive]. Either the grip OR the index trigger works for BOTH grabbing a
     * corner/edge handle and clicking a UI element — whichever the user presses is treated the
     * same way. This used to require a SPECIFIC button for each action, and repeated,
     * contradictory feedback about which physical button is "top" vs "bottom" on the controller
     * never converged on a mapping that felt right — unifying them removes the ambiguity
     * entirely instead of guessing a mapping again. Grab a corner (within
     * [POINTER_CORNER_RADIUS_METERS]) to resize; grab the top/bottom edge (within
     * [POINTER_BAR_RADIUS_METERS]) to reposition. Deltas are computed relative to the hand's
     * position when the grab started, not incrementally, so there's no drift from small
     * per-frame rounding.
     */
    private fun handlePointerMode(buttons: Int, axes: FloatArray, handPoses: FloatArray, posesValid: Boolean) {
        val leftGripPressed = (buttons and (1 shl XrGamepadBridge.BUTTON_LB)) != 0
        val rightGripPressed = (buttons and (1 shl XrGamepadBridge.BUTTON_RB)) != 0
        // Trigger uses hysteresis (two thresholds, see their own kdoc) since it's an analog value
        // with no clean digital edge; the grip is already a plain digital button.
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
            // The action button is physically down but we didn't see a rising edge for it — means
            // it was already held (e.g. still down from the activation chord) when this hand
            // started being read as pressed. Logged (rate-limited) so this is distinguishable
            // from "we tried and missed the zone".
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
        // hit is already quad-LOCAL meters (rayPlaneHit projects onto the quad's own basis) — no
        // further conversion needed.
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
            // obtain() leaves source as SOURCE_UNKNOWN — Compose's pointer input system
            // classifies events by source (touch/mouse/stylus) and silently ignores ones it
            // can't classify, which is almost certainly why this dispatch had no visible effect.
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

    /** Draws the per-hand aiming cursor always (while in pointer mode), and the 6 grab handles
     * (4 corners + top/bottom edge) only while [resizeHandlesEnabled] is on — that toggle, and
     * drawing handles INSIDE the content instead of in an outside margin band, replaced an
     * earlier design that grew the quad by a fixed margin on every side purely to give handles
     * somewhere to live outside the game: that cost a real, measured chunk of the direct-render
     * path's fixed swapchain pixel budget (~45% of the pixel area at default scale), which
     * showed up as pixelation the user explicitly rejected as a tradeoff. [width]/[height] are
     * now simply the content's own canvas — no separate "outer" canvas exists anymore. */
    private fun drawPointerCursors(canvas: android.graphics.Canvas, width: Int, height: Int) {
        val contentHalfWidth = (ImmersiveControls.BASE_WIDTH_METERS * quadScale) / 2f
        val contentHalfHeight = (ImmersiveControls.BASE_HEIGHT_METERS * quadScale) / 2f
        if (contentHalfWidth <= 0f || contentHalfHeight <= 0f) return

        fun toContentPixel(localX: Float, localY: Float) = toPixel(localX, localY, contentHalfWidth, contentHalfHeight, width, height)

        // Cursor dot per hand — still a 2D approximation baked into this same texture, not a true
        // 3D laser (that needs native geometry rendering — a bigger follow-up, see Nightfall's
        // own capsule-mesh laser for the target design). Drawn regardless of resizeHandlesEnabled
        // — this is the aiming feedback for clicking the quick menu, independent of whether
        // resize/move handles are also on right now.
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

        // Each valid hand's current quad-local hit point, paired with which hand it is (needed to
        // check pointerGrabHand for the "grabbed" tier specifically, not just "hovering").
        val hands = buildList {
            if (pointerCursorLeftValid) add(Triple(pointerCursorLeftX, pointerCursorLeftY, 0))
            if (pointerCursorRightValid) add(Triple(pointerCursorRightX, pointerCursorRightY, 1))
        }

        // (alpha, strokeWidth) together — a hand aiming AT a handle needs to be unmistakable, both
        // more opaque AND visibly thicker, not just brighter (a thin line staying thin was easy to
        // lose track of exactly when it mattered most: right as you're lining up a grab).
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

        // Thin corner brackets (an "L" hugging each corner, arms pointing inward) and a thin
        // pill for each edge handle — NOT filled circles. A filled disc covers far more pixels
        // than a thin line at the same opacity, which is what made the circle version look like
        // a solid "ball" even at low alpha; a thin stroke stays genuinely subtle at the same tier.
        // Anchored INSIDE the content edge by POINTER_INDICATOR_GAP_METERS (the same gap that
        // used to sit between the content edge and a handle drawn OUTSIDE it, in the margin band
        // that no longer exists) instead of outside it.
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
                // toPixel's Y axis is flipped relative to world-space localY (py DECREASES as
                // localY increases), so "inward" (toward center) is corner.y + signY*armPx, not
                // corner.y - signY*armPx.
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

    /** Ray/quad-plane intersection, returning quad-LOCAL coordinates (meters, origin at the
     * quad's own center, +X along the quad's own right edge, +Y up) directly — not world
     * coordinates. The quad orbits the player rather than sliding on a flat, world-axis-aligned
     * plane once [horizontal] != 0 (see xr_immersive.cpp's submitQuadLayer for why and the exact
     * derivation this mirrors), so "local" is no longer a plain world-coordinate subtraction: this
     * recomputes the quad's actual position + rotated right/normal axes from the same
     * (horizontal, vertical, distance) triple passed in, then projects the hit point onto them.
     * Callers pass either the LIVE quad transform (for hover/click) or a snapshot frozen at
     * grab-start (for a drag's fixed reference plane — see updateActiveGrab). */
    private fun rayPlaneHit(handPoses: FloatArray, hand: Int, horizontal: Float, vertical: Float, distance: Float): FloatArray? {
        val base = hand * 6
        val originX = handPoses[base]
        val originY = handPoses[base + 1]
        val originZ = handPoses[base + 2]
        val dirX = handPoses[base + 3]
        val dirY = handPoses[base + 4]
        val dirZ = handPoses[base + 5]

        // Same yaw/pitch/position/basis derivation as xr_immersive.cpp's submitQuadLayer — keep
        // the two in sync, since this must describe the SAME quad the player actually sees. The
        // quad orbits the player on both axes (see that function's kdoc for the full derivation),
        // so "local" here means projected onto the quad's own right/up axes at its orbited
        // position, not a plain world-coordinate subtraction.
        val yaw = if (distance > 0.0001f) kotlin.math.atan2(horizontal, distance) else 0f
        val pitch = if (distance > 0.0001f) kotlin.math.atan2(vertical, distance) else 0f
        val sinYaw = kotlin.math.sin(yaw)
        val cosYaw = kotlin.math.cos(yaw)
        val sinPitch = kotlin.math.sin(pitch)
        val cosPitch = kotlin.math.cos(pitch)

        val centerX = distance * sinYaw * cosPitch
        val centerY = distance * sinPitch
        val centerZ = -distance * cosYaw * cosPitch

        // Right axis is unaffected by pitch (pitch rotates about the quad's own local X, i.e.
        // the right axis itself) — only yaw moves it.
        val rightX = cosYaw
        val rightY = 0f
        val rightZ = sinYaw
        // Up and normal both pick up the pitch tilt.
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
        // Gated entirely on the quick-menu toggle now — see its kdoc for why (no margin band left
        // to require "outside" of; handles/grab-zones only exist at all when explicitly asked
        // for). Proximity to a corner/edge decides it from here, with the corner/bar "center"
        // pinned to the actual corner/edge (±halfWidth/halfHeight, never 0) rather than
        // kotlin.math.sign(x)*half (which collapses to 0 exactly at x=0, i.e. dead center) — the
        // margin-era version never hit that edge case because it additionally required being
        // outside the content, which excluded the center by construction; without that gate, a
        // sign()-based center needs this instead to avoid a false match at the exact center.
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
        // BASE_WIDTH/HEIGHT*scale IS the game content's own true half-extent in this "margin is
        // ADDED, never shrinks the game" model. hit is already quad-local (rayPlaneHit projects
        // onto the quad's own basis) — no further conversion needed.
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
        // Grab-start reference is the RAY'S HIT POINT on the quad plane, not the hand's raw 3D
        // position — a small wrist rotation moves the hit point far more than it moves the hand
        // itself (the further the quad, the bigger that amplification), so tracking the hit point
        // is what makes move/resize actually feel like "the window follows the controller" instead
        // of a sluggish 1:1 hand-translation mapping that barely responds to aiming/rotation at
        // all. Z is the exception — there's no "hit point" analog for push/pull distance, so that
        // axis still tracks the hand's raw forward/back position.
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
        // Fixed reference plane frozen at grab-start (position AND orientation, via the frozen
        // pointerGrabStart{Horizontal,Vertical,Distance} triple) — recomputing against a plane
        // that itself moves/rotates mid-drag would fight the very delta it's trying to measure.
        // rayPlaneHit returns coordinates already local to THIS fixed plane, matching
        // pointerGrabStartHandPos (captured the same way in tryStartPointerGrab), so both are
        // directly comparable without any extra offset math.
        val currentHit = rayPlaneHit(handPoses, hand, pointerGrabStartHorizontal, pointerGrabStartVertical, pointerGrabStartDistance)
        if (currentHit == null) return // ray no longer crosses the reference plane — hold, don't jump
        val dx = currentHit[0] - pointerGrabStartHandPos[0]
        val dy = currentHit[1] - pointerGrabStartHandPos[1]
        val dz = handPoses[base + 2] - pointerGrabStartHandPos[2]

        if (pointerGrabIsResize) {
            // Scale by how far the hit point's distance from the quad's center (fixed during a
            // resize — only scale changes, not position) has changed since grab-start — a single
            // uniform ratio applied to both width and height via quadScale, so the aspect ratio
            // can never change no matter which direction the hand drags. Both hit points are
            // already local (center-relative), so distance-from-center is just their own hypot.
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
            // leftY is already sign-flipped upstream (syncControllerInputs) to match XInput's
            // "positive = up" convention for Wine — Compose/Android's own dpad convention wants
            // the opposite here, so UP/DOWN are swapped back relative to the Wine-facing mapping.
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
            // Radio/toggle rows (ScreenEffectRadioRow, upscale-mode choices) rely on Android's
            // default DPAD_CENTER/A-click-on-real-focused-view behavior, which is unreliable here
            // since some other View — not Compose's own focus state — holds real view focus.
            // quickMenuFocusedActivate reports whichever row is currently focused; invoke it
            // directly when present instead of relying on that default click path.
            val focusedActivate = quickMenuFocusedActivate?.invoke()
            Timber.i("Immersive: A pressed, focusedActivatePresent=%b", focusedActivate != null)
            if (focusedActivate != null) {
                runOnUiThread { focusedActivate.invoke() }
            } else {
                // DPAD_CENTER activates plain Modifier.clickable() targets (toggles, the Resume
                // button); BUTTON_A specifically is what QuickMenuAdjustmentRow's slider-lock and
                // XServerScreen's manualResumeMode handler check for (see their onPreviewKeyEvent /
                // onKeyEvent) — send both so either kind of target responds to the same press.
                dispatchMenuKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                dispatchMenuKeyEvent(android.view.KeyEvent.KEYCODE_BUTTON_A)
            }
        }
        lastMenuButtonAPressed = buttonAPressed

        val buttonBPressed = (buttons and (1 shl XrGamepadBridge.BUTTON_B)) != 0
        if (buttonBPressed && !lastMenuButtonBPressed) dispatchMenuKeyEvent(android.view.KeyEvent.KEYCODE_BACK)
        lastMenuButtonBPressed = buttonBPressed

        // LB/RB cycle the quick-menu's tab rail directly via quickMenuCycleTab — reported as
        // unreachable via dpad-left from inside a tab's content, and dispatchMenuKeyEvent's
        // KEYCODE_BUTTON_L1/R1 goes through the same broken KeyEvent path as arrow-key movement
        // did (confirmed via logging), so this bypasses it the same way moveMenuFocus does.
        // stickClickHeld dates back to when XR-pointer-mode activation was a grip+trigger chord,
        // which could briefly co-occur with a real LB/RB press and get misread as a deliberate
        // tab-cycle pair. That chord is gone — XR pointer mode now toggles via a thumbstick
        // double-click (native pointerModeToggled), which never touches the grips — but the gate
        // is left in place as a harmless safeguard against any future chord that reintroduces
        // the same overlap.
        val stickClickHeld = (buttons and ((1 shl XrGamepadBridge.BUTTON_L3) or (1 shl XrGamepadBridge.BUTTON_R3))) != 0
        // Also gated on quickMenuVisible specifically (not just this function running, which
        // ALSO happens whenever isOverlayPaused alone is true — e.g. a fullscreen "press A to
        // resume" prompt with no tab rail at all) — logs showed LB/RB still "cycling" a tab
        // nobody could see in that state, silently leaving the quick menu on a different tab
        // than expected the next time it actually opened.
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

    /** Routes a dpad direction to a locked slider's decrease/increase if one is active, then to
     * the tab-rail bypass for LEFT specifically, otherwise moves focus as normal.
     *
     * A locked QuickMenuAdjustmentRow's own onPreviewKeyEvent (listening for DPAD_LEFT/RIGHT)
     * never sees these presses at all — moveMenuFocus's direct FocusManager bypass intercepts
     * arrow keys before they'd ever reach Compose's key dispatch, so without this, a locked
     * slider's LEFT/RIGHT just moved focus away mid-adjustment instead of changing its value.
     * quickMenuAdjustmentControl (see its own kdoc) is invoked fresh on every call — it's a live
     * getter, not a snapshot — so it always reflects whichever row (if any) is currently locked.
     *
     * LEFT (when no slider is locked) goes through quickMenuFocusTabRail instead of
     * moveMenuFocus — moveFocus(Left) reaching the tab rail from content depends on the focused
     * row's vertical position lining up with a rail icon in Compose's spatial search, confirmed
     * unreliable by comparing two otherwise-identical sessions' logs, so this always jumps
     * straight to the current tab's own rail button instead of gambling on that heuristic. */
    private fun triggerMenuDirection(keyCode: Int) {
        val active = quickMenuAdjustmentControl?.invoke()
        if (active != null &&
            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val (onDecrease, onIncrease) = active
            // Logged specifically to distinguish this branch from the moveFocus one — with no
            // log here, a press silently landing in this branch vs. silently landing in
            // moveMenuFocus (e.g. because the lock had already dropped) look IDENTICAL from the
            // outside, which is exactly the ambiguity that made the "one tick works, then nothing"
            // reports hard to pin down from logs alone.
            Timber.i("Immersive: adjustment bypass triggered keyCode=%d (locked row's decrease/increase)", keyCode)
            // Must run on the UI thread — this drives real Compose/ViewModel state (fps limiter,
            // HUD config, etc.) whose recomposition is tied to the UI thread's frame clock. Calling
            // it straight from this polling thread was a real bug: the resulting recomposition
            // could race with Compose's own focus-commit step, occasionally unfocusing the row
            // right as it recomposed — which this row's own .onFocusChanged then read as "lost
            // focus" and force-unlocked (isAdjustmentLocked = false), matching exactly what showed
            // up as "one adjustment works, then LEFT/RIGHT do nothing until B then A again."
            runOnUiThread { if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) onDecrease() else onIncrease() }
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            runOnUiThread { quickMenuFocusTabRail?.invoke() }
        } else {
            moveMenuFocus(keyCode)
        }
    }

    /** Moves Compose focus directly via QuickMenu's registered FocusManager, instead of
     * dispatching a synthetic dpad KeyEvent through dispatchMenuKeyEvent()/Activity.dispatchKeyEvent()
     * — logging confirmed those never reach Compose's key dispatch at all in this Activity (some
     * other Android View holds real view-level focus throughout), so DPAD_CENTER/BUTTON_A/BACK
     * still go through dispatchMenuKeyEvent (confirmed working), but arrow-key focus movement
     * needs this direct bypass instead. */
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

    // XServerScreen's key handling (manualResumeMode's BUTTON_A/ENTER check, WinHandler gamepad
    // forwarding, the ESC-menu handler) all subscribe to PluviaApp.events' AndroidEvent.KeyEvent
    // bus, which only MainActivity's dispatchKeyEvent() feeds — ImmersiveXrActivity has no
    // equivalent override, so without this, none of that logic ever sees our synthetic events.
    // Mirror MainActivity's dispatchKeyEvent: emit on the bus first, fall back to the normal
    // Android/Compose focus dispatch (dpad nav, plain clickable()) if nothing consumed it.
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
     * Two decoupled rates, not one unconditional per-frame path:
     *
     *  - The GAME frame: PixelCopy self-chains as fast as it completes (same as the original
     *    gameplay-only capture) on a background thread. Compositing it with the cached overlay
     *    layer below it is plain bitmap-to-bitmap drawing — cheap, no View access needed — so it
     *    stays on that same background thread and nativeSubmitFrame() runs at full game-frame
     *    rate, same as before the overlay was ever unified in.
     *  - The OVERLAY layer (quick menu, Resume button, performance HUD): re-rendered separately
     *    on a timer (~[OVERLAY_REFRESH_INTERVAL_MS]) via a real View.draw(Canvas) on the UI
     *    thread into its own cached bitmap — transparent everywhere except whatever overlay UI is
     *    actually showing.
     *
     * An earlier version ran the View.draw() step on *every* game frame, which (confirmed by
     * testing) tanked performance badly enough to crash — walking the whole Compose tree at
     * native-game-frame-rate is far more than a menu/HUD actually needs. Splitting the rates
     * keeps the HUD/menu always visible (the thing that motivated unifying the two capture modes
     * in the first place) without paying a per-frame Compose-tree cost on the game's own rate.
     *
     * [overlayLock] guards [overlayLayerBitmap] since the fast (background) and slow (UI) loops
     * touch it from different threads.
     */
    private fun startFrameCaptureLoop() {
        captureThread = HandlerThread("XrFrameCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        captureActive.set(true)
        scheduleNextCapture()
        scheduleNextOverlayRefresh()
    }

    /** Attaches a direct-render bridge to the running container's renderer, whichever it turns
     * out to be — called (idempotently, see the guard below) from [scheduleNextCapture]'s retry
     * loop rather than once at session start, since the game's XServerView/renderer isn't created
     * yet at that point for a typical launch. */
    private fun setupDirectRenderBridgeIfSupported() {
        if (directGLBridge != null || directVulkanBridge != null) return
        val actualRenderer = PluviaApp.xServerView?.renderer

        val glRenderer = actualRenderer as? GLRenderer
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

        if (directGLBridge == null && directVulkanBridge == null) setupDirectRenderBridgeIfSupported()

        val surfaceView = PluviaApp.xServerView as? SurfaceView
        val width = surfaceView?.width ?: 0
        val height = surfaceView?.height ?: 0

        val glBridge = directGLBridge
        if (glBridge != null) {
            // Direct-render path: GLRenderer writes the game frame straight into the shared GPU
            // buffer from its own thread — no PixelCopy/CPU readback needed for the game layer
            // at all. Just keep the shared buffer sized to the surface (ensureAllocated no-ops
            // once already the right size) and skip everything below.
            if (width > 0 && height > 0) {
                PluviaApp.xServerView?.queueEvent { glBridge.ensureAllocated(width, height) }
            }
            handler.postDelayed({ scheduleNextCapture() }, CAPTURE_RETRY_DELAY_MS)
            return
        }

        if (directVulkanBridge != null && directRenderActive) {
            // Confirmed: VulkanRenderer is already feeding frames straight to the
            // native session on its own cadence (see onScanoutBuffer) — nothing to poll here.
            // Until directRenderActive is actually confirmed true, fall through to PixelCopy
            // below instead (unexpected if it never fires, but PixelCopy staying up is a safe fallback).
            handler.postDelayed({ scheduleNextCapture() }, CAPTURE_RETRY_DELAY_MS)
            return
        }

        if (surfaceView != null && surfaceView !== surfaceCallbackAttachedTo) {
            surfaceCallbackAttachedTo?.holder?.removeCallback(surfaceReadyCallback)
            // addCallback() does NOT retroactively fire surfaceCreated() if the Surface already
            // existed at attach time — seed from isValid() once, then let the callback maintain
            // it from here on (see the surfaceReady kdoc for why the callback, not polling
            // isValid() on every attempt, is what actually narrows the crash window).
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
                // PixelCopy from this (opaque) SurfaceView leaves every pixel's alpha byte at 0
                // (confirmed via a diagnostic pixel sample during development) even though the
                // RGB content is real — Canvas.drawBitmap's default SRC_OVER blend then treats
                // alpha=0 as "draw nothing", silently no-op'ing any attempt to use this as a
                // background (see compositeGameFrame). setHasAlpha(false) tells the renderer to
                // ignore that channel and treat every pixel as fully opaque.
                it.setHasAlpha(false)
                gameCaptureBitmap = it
            }

        // Re-check right before the call too — belt and suspenders, cheap, and narrows the
        // window a little further given the bitmap allocation above can take a moment.
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
                // No cap here previously — this self-chained as fast as PixelCopy completed,
                // which can be much faster than the game itself is actually rendering new frames
                // (PixelCopy will happily hand back the same unchanged buffer instantly). Every
                // one of those "wasted" captures is still a real GPU readback competing with the
                // game's own rendering for the same GPU, on top of the XR compositor being fixed
                // at the headset's own refresh rate regardless (so capturing faster than that is
                // pure waste on that end too). Capping to that rate removes the wasted work
                // without capping anything the display could actually show.
                val elapsed = android.os.SystemClock.uptimeMillis() - captureStartTime
                val delay = (MIN_CAPTURE_INTERVAL_MS - elapsed).coerceAtLeast(0L)
                if (delay > 0L) handler.postDelayed({ scheduleNextCapture() }, delay) else scheduleNextCapture()
            }, handler)
        } catch (t: Throwable) {
            Timber.w(t, "PixelCopy request failed")
            handler.postDelayed({ scheduleNextCapture() }, CAPTURE_RETRY_DELAY_MS)
        }
    }

    /** Fast path: cheap bitmap composite (game frame + cached overlay layer), runs on the capture
     * thread at full game-frame rate. No View access here — see the capture-loop kdoc above.
     * This path only runs when NOT direct-rendering (see scheduleNextCapture).
     *
     * No pointer-border margin here (or anywhere else in this class) — an earlier version grew
     * the quad by a fixed margin band on every side, purely to give resize/move handles a place
     * to live outside the game content. That cost a real, measured chunk of the direct-render
     * path's fixed swapchain pixel budget (~45% of the pixel area at default scale, since the
     * margin was a FIXED size taken out of the SAME fixed-resolution swapchain the game itself
     * has to share) — visible as pixelation the user explicitly did not accept as a tradeoff.
     * Handles now live INSIDE the game content instead (see drawPointerCursors/nearGrabZone),
     * gated behind [resizeHandlesEnabled] so they're only ever drawn/grabbable when the user has
     * explicitly asked for them via the quick menu's toggle — the game's own displayed size is
     * therefore identical in every controller mode AND never grows for a margin that no longer
     * exists, at full sharpness always. */
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

            // SurfaceView reserves a "hole" wherever it sits in the view tree — drawing an
            // ancestor via View.draw(Canvas) (not the real windowed rendering pipeline) paints
            // that hole as solid BLACK, since there's no separate compositor layer for it to show
            // through in an offscreen bitmap.
            //
            // A previous version toggled the SurfaceView's *visibility* around this draw call,
            // which turned out to be the actual cause of two native crashes (SIGSEGV inside
            // PixelCopy's own ANativeWindow_acquire, both starting right at game launch):
            // visibility changes are a well-known trigger for Android to tear down and recreate
            // a SurfaceView's underlying Surface, and this call runs every ~33ms for the entire
            // session — so that "fix" was destroying and recreating the game's real rendering
            // surface ~30 times a second, directly underneath the capture thread's PixelCopy
            // calls. The next attempt, clipOutRect(), avoided touching the Surface at all but
            // over-corrected: it excludes that whole screen-sized rect from the canvas, which
            // also excludes the menu/HUD, since they're drawn at the same screen coordinates
            // (clipping works in coordinates, not "this specific child view").
            //
            // Alpha instead of visibility: unlike visibility, alpha is a pure compositing
            // property — it doesn't go through updateSurface()/the attach-detach path that
            // destroys the Surface — so it should make this View's own contribution (the hole)
            // draw as fully transparent without ever affecting the real Surface, while the
            // menu/HUD (their own alpha untouched) still draw normally on top.
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
                        // No margin anymore (see compositeGameFrame's kdoc) — screen == the whole
                        // visible quad, in both render paths, so this raw capture can be submitted
                        // as-is with no inset/scaling step.
                        contentView.draw(canvas)
                        if (directRenderActive && xrPointerModeActive) {
                            drawPointerCursors(canvas, width, height)
                        }
                    } finally {
                        if (surfaceView != null) surfaceView.alpha = previousAlpha
                    }
                }
                if (directRenderActive && xrSessionHandle != 0L) {
                    // The shared GPU buffer already carries the game frame straight from
                    // GLRenderer/VulkanRenderer — this overlay-only bitmap (transparent everywhere
                    // except menu/HUD/cursors) is all nativeSubmitFrame needs to feed the native
                    // dual-sampler blend (see xr_immersive.cpp's directQuadProgram_ branch).
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
    // contentAlignment on the Box itself, not .align() on the child — same pattern as
    // ImmersiveModeChangeIndicator below; this was pinned to the top instead of centered.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.65f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.immersive_onboarding_disable_passthrough), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.Menu, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.immersive_onboarding_quick_menu), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.immersive_onboarding_pointer_toggle), color = Color.White, style = MaterialTheme.typography.bodyLarge)
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
    // contentAlignment on the Box itself, not .align() on the child — more foolproof against
    // whatever was pinning this to the top-left before (reported after testing).
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.65f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = if (shownForPointerMode) Icons.Default.TouchApp else Icons.Default.SportsEsports,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = stringResource(if (shownForPointerMode) R.string.immersive_mode_xr_pointer else R.string.immersive_mode_xbox),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
