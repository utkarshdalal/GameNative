package app.gamenative.ui.component

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import timber.log.Timber

/**
 * Bus-level joystick navigator for in-game overlays (spec 2026-08-09, §2.1 + lessons).
 *
 * WHY bus-level: inside the game window, the XServerRendererView (GL) is a child of the
 * ComposeView, and the app's proven gamepad navigation (LibraryScreen.kt:734-764) consumes
 * AndroidEvent.MotionEvent directly from the PluviaApp.events bus — never relying on the
 * Android view hierarchy. The QuickMenu lives in the SAME window as the GL surface, where
 * view-level listeners are unreliable; the bus path is the one that demonstrably works in
 * this app, so the QuickMenu uses it too.
 *
 * While [enabled], ALL gamepad motion is consumed (the game must not receive the stick while
 * the overlay is up) and axis/hat movement is translated into Compose focus moves through
 * [GamepadStickLogic] — the same pure decision logic as [JoystickFocusNavigator] (which stays
 * for dialog windows, where events never reach this bus). Spec 2026-08-10, §3.1 (RC1):
 * re-arming below the dead zone (not a separate release zone) fixes the dead-navigation
 * bug caused by stick drift resting at 0.30–0.44.
 */
@Composable
fun BusJoystickFocusNavigator(
    enabled: Boolean,
    deadZone: Float = 0.45f,
    cooldownMs: Long = 180L,
) {
    val focusManager = LocalFocusManager.current
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        var stickState = GamepadStickState()
        fun handleMotion(androidEvent: AndroidEvent.MotionEvent): Boolean {
            val ev = androidEvent.event ?: return false
            val isGamepad = (ev.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (ev.source and InputDevice.SOURCE_DPAD) != 0
            if (!isGamepad) return false
            // Consumed: the overlay owns the stick, even when not moving focus.
            if (ev.actionMasked != MotionEvent.ACTION_MOVE) return true
            // The DS4 touchpad reports a MIXED source (JOYSTICK|GAMEPAD|TOUCH_NAVIGATION|
            // CLASS_POINTER): its absolute finger position arrives on AXIS_X/AXIS_Y and reads
            // as a full-deflection stick (mag 1.0). Driving focus from it poisoned the menu
            // (evidence, logcat 2026-08-13): it raced every focus bootstrap so focus never
            // landed, and kept stamping GamepadNavigationClock, so the focus guardian
            // skipped its restores forever ("user navigating, skipping cycle") — a dead
            // menu that only a fresh surface (shader browser) could heal. Touchpad motion
            // is still consumed here (the overlay owns the device), but never translated
            // into focus moves. CLASS_POINTER is unset on real sticks (SOURCE_JOYSTICK/
            // SOURCE_DPAD), so this only filters touch-class sources.
            if ((ev.source and InputDevice.SOURCE_CLASS_POINTER) != 0) return true
            val stickX = ev.getAxisValue(MotionEvent.AXIS_X)
            val stickY = ev.getAxisValue(MotionEvent.AXIS_Y)
            val hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val magnitude = maxOf(
                maxOf(kotlin.math.abs(stickX), kotlin.math.abs(stickY)),
                maxOf(kotlin.math.abs(hatX), kotlin.math.abs(hatY)),
            )
            val now = SystemClock.uptimeMillis()
            val direction = when {
                hatY < -0.5f -> GamepadStickDirection.Up
                hatY > 0.5f -> GamepadStickDirection.Down
                hatX < -0.5f -> GamepadStickDirection.Left
                hatX > 0.5f -> GamepadStickDirection.Right
                stickY < -deadZone -> GamepadStickDirection.Up
                stickY > deadZone -> GamepadStickDirection.Down
                stickX < -deadZone -> GamepadStickDirection.Left
                stickX > deadZone -> GamepadStickDirection.Right
                else -> null
            }
            val decision = GamepadStickLogic.decide(
                previous = stickState,
                now = now,
                magnitude = magnitude,
                direction = direction,
                deadZone = deadZone,
                cooldownMs = cooldownMs,
            )
            stickState = decision.state
            decision.direction?.let { dir ->
                // M4 (spec 2026-08-12): the axis channel must not double-move when a DPAD
                // key of the same gesture already moved the focus inside the window.
                if (GamepadMoveDedupe.shouldDispatchAxisMove(now, GamepadNavigationClock.lastMoveAt)) {
                    Timber.d("BusJoystick: moveFocus(%s) mag=%.2f", dir, magnitude)
                    GamepadNavigationClock.lastMoveAt = now
                    focusManager.moveFocus(dir.focusDirection)
                } else {
                    Timber.d("BusJoystick: axis move suppressed (dedupe window)")
                }
            }
            return true
        }
        val handler: (AndroidEvent.MotionEvent) -> Boolean = ::handleMotion
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(handler)
        Timber.d("BusJoystick: listening")
        onDispose {
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(handler)
            val remaining = PluviaApp.events.listenerCount()[AndroidEvent.MotionEvent::class.simpleName] ?: 0
            Timber.d("BusJoystick: stopped remaining=%d", remaining)
        }
    }
}

/**
 * Shared clock stamped by every gamepad focus move (bus + view navigators). Lets focus
 * targets tell "focus arrived via the stick/hat" from "focus arrived via touch/API"
 * (UX practice: a soft keyboard must only appear on explicit user intent — tapping the
 * field or pressing A — never because stick navigation landed on a text field).
 */
object GamepadNavigationClock {
    @Volatile
    var lastMoveAt: Long = 0L
}

/**
 * What the [BusGamepadKeyBridge] does with the controller's Home/PS key
 * (KEYCODE_BUTTON_MODE) while an overlay is open (spec 2026-08-10, §3.5 — G6).
 *
 * PS opens the QuickMenu through PhysicalControllerHandler (menu closed); with the menu
 * open the bridge must close it — toggle behavior, no ambiguous Boolean.
 */
enum class ModeKeyBehavior {
    /** KEYCODE_BUTTON_MODE closes the overlay via [BusGamepadKeyBridge.onCloseOverlay]. */
    CloseOverlay,

    /** KEYCODE_BUTTON_MODE is consumed and ignored (current behavior for other users). */
    None,
}

/**
 * Bus-level gamepad key bridge for in-game overlays (QuickMenu).
 *
 * Keys dispatched through the window go to the focused Android view; inside the game window
 * that is not guaranteed to be the ComposeView. The bus handler below delivers the gamepad
 * keys Compose understands directly to the ComposeView (bypassing window focus routing) and
 * consumes them so the game never sees them while the overlay is open.
 *
 * - BUTTON_A -> synthetic DPAD_CENTER (with haptics), same translation as [GamepadKeyBridge].
 * - BUTTON_B / L1 / R1 / L2 / R2 / DPAD_* / ENTER -> re-dispatched raw into the ComposeView
 *   (the surface handlers consume them: hierarchical back, tab switching, page scroll).
 * - KEYCODE_BUTTON_MODE (Home/PS) -> always consumed; with [ModeKeyBehavior.CloseOverlay]
 *   the first ACTION_DOWN invokes [onCloseOverlay] (spec 2026-08-10, §3.5 — G6: PS toggles
 *   the QuickMenu open/closed).
 * - BUTTON_START (Options/≡) -> always consumed; with [ModeKeyBehavior.CloseOverlay] it
 *   CLOSES the overlay like PS (P1, spec 2026-08-12: START mirrors HOME as the second
 *   system toggle; the open half is handled by XServerScreen when the menu is closed).
 * - BUTTON_SELECT (View/Share) -> always consumed while an overlay is open (P1): neither
 *   START nor SELECT may ever reach the game behind the open menu (unexpected pause / in-
 *   game UI opening).
 *
 * [GamepadKeyBridge] (view-level) stays for dialog windows, whose events never hit this bus.
 */
@Composable
fun BusGamepadKeyBridge(
    enabled: Boolean,
    modeKeyBehavior: ModeKeyBehavior = ModeKeyBehavior.None,
    onCloseOverlay: () -> Unit = {},
) {
    val view = LocalView.current
    // M3 (spec 2026-08-12 — C3): onCloseOverlay is re-created by the caller on every
    // recomposition; using it as a DisposableEffect key would off/on the bus listener in
    // bursts (each process poll / HUD update). rememberUpdatedState keeps the handler on
    // the LATEST callback without re-registering the listener.
    val currentOnClose by rememberUpdatedState(onCloseOverlay)
    DisposableEffect(enabled, view, modeKeyBehavior) {
        if (!enabled) return@DisposableEffect onDispose {}
        val handledKeys = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
        )
        // M4 (spec 2026-08-12 — C4): DPAD moves race the axis channel. A key press wins
        // the gesture window by stamping the shared clock when it dispatches; a key press
        // that lost (axis already moved < WINDOW_MS ago) is consumed WITHOUT re-dispatch.
        val moveKeys = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        fun handleKey(androidEvent: AndroidEvent.KeyEvent): Boolean {
            val event = androidEvent.event
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (modeKeyBehavior == ModeKeyBehavior.CloseOverlay &&
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
                ) {
                    currentOnClose()
                }
                // DOWN/UP always consumed: the game never sees the Mode key with an overlay up.
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                // P1 (spec 2026-08-12): START mirrors HOME while an overlay is open —
                // first ACTION_DOWN closes it; DOWN/UP always consumed.
                if (modeKeyBehavior == ModeKeyBehavior.CloseOverlay &&
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
                ) {
                    currentOnClose()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
                // P1: consumed so the game never reacts behind the open overlay.
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    GamepadHaptics.vibrate(view.context)
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                }
                return true
            }
            if (event.keyCode in handledKeys) {
                if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in moveKeys) {
                        val now = SystemClock.uptimeMillis()
                        if (GamepadMoveDedupe.shouldDispatchKeyMove(
                                now = now,
                                lastMoveAt = GamepadNavigationClock.lastMoveAt,
                                repeatCount = event.repeatCount,
                            )
                        ) {
                            if (event.repeatCount == 0) {
                                // First press that wins the gesture stamps the shared clock
                                // so the axis channel of the same gesture is suppressed.
                                GamepadNavigationClock.lastMoveAt = now
                            }
                            view.dispatchKeyEvent(event)
                        } else {
                            Timber.d("BusGamepadKeyBridge: DPAD=%d suppressed (dedupe window)", event.keyCode)
                        }
                    } else {
                        view.dispatchKeyEvent(event)
                    }
                }
                return true
            }
            return false
        }
        val handler: (AndroidEvent.KeyEvent) -> Boolean = ::handleKey
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(handler)
        Timber.d("BusGamepadKeyBridge: listening")
        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(handler)
            val remaining = PluviaApp.events.listenerCount()[AndroidEvent.KeyEvent::class.simpleName] ?: 0
            Timber.d("BusGamepadKeyBridge: stopped remaining=%d", remaining)
        }
    }
}
