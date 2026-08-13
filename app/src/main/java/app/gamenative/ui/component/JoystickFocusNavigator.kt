package app.gamenative.ui.component

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import app.gamenative.PrefManager

/**
 * Converts gamepad analog-stick (AXIS_X/AXIS_Y) and hat-switch D-pad (AXIS_HAT_X/AXIS_HAT_Y)
 * axis motion into Compose focus navigation.
 *
 * Compose's focus system only reacts to key events (KEYCODE_DPAD_*). Android gamepads report
 * the left stick and the D-pad hat as *axis motion* in onGenericMotionEvent, which Compose
 * ignores — so joystick users cannot move focus in menus. This composable installs an
 * [View.OnGenericMotionListener] on the host view while [enabled] and moves focus once per
 * [cooldownMs] once an axis crosses [deadZone] (holding the stick scrolls steadily, never
 * free-runs). The event is consumed only when a movement is actually issued.
 *
 * The dead-zone/hysteresis/cooldown decision lives in the pure [GamepadStickLogic]
 * (spec 2026-08-10, §3.1 — RC1): re-arming below the dead zone replaces the old 0.30
 * release zone, so a drifting stick resting at 0.30–0.44 can no longer kill navigation.
 *
 * Spec: docs/superpowers/specs/2026-08-08-dpad-shader-navigation-design.md
 */
@Composable
fun JoystickFocusNavigator(
    enabled: Boolean,
    deadZone: Float = 0.45f,
    cooldownMs: Long = 180L,
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        if (!enabled) return@DisposableEffect onDispose {}
        var stickState = GamepadStickState()
        val listener = View.OnGenericMotionListener { _, ev ->
            if (ev.actionMasked != MotionEvent.ACTION_MOVE) return@OnGenericMotionListener false
            // Ghost gate (spec 2026-08-13): dialog windows are separate surfaces that never
            // pass through MainActivity's dispatcher, so the rule is repeated here — the
            // controller touchpad (CLASS_POINTER on a JOYSTICK device) never navigates dialogs.
            if ((ev.source and InputDevice.SOURCE_CLASS_POINTER) != 0 &&
                PrefManager.ignoreControllerTouchpad
            ) {
                return@OnGenericMotionListener false
            }
            val isGamepad = (ev.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (ev.source and InputDevice.SOURCE_DPAD) != 0
            if (!isGamepad) return@OnGenericMotionListener false
            val stickX = ev.getAxisValue(MotionEvent.AXIS_X)
            val stickY = ev.getAxisValue(MotionEvent.AXIS_Y)
            val hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val magnitude = kotlin.math.max(
                kotlin.math.max(kotlin.math.abs(stickX), kotlin.math.abs(stickY)),
                kotlin.math.max(kotlin.math.abs(hatX), kotlin.math.abs(hatY)),
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
            if (decision.direction == null) {
                // Neutral samples are not consumed; disarmed/cooldown pushes are.
                return@OnGenericMotionListener direction != null
            }
            // M4 (spec 2026-08-12): a DPAD key of the same gesture that already moved the
            // focus inside the window wins — the axis move is dropped (dialog windows can
            // receive both channels from the same controller, exactly like the bus path).
            if (GamepadMoveDedupe.shouldDispatchAxisMove(now, GamepadNavigationClock.lastMoveAt)) {
                GamepadNavigationClock.lastMoveAt = now
                focusManager.moveFocus(decision.direction.focusDirection)
            }
            true
        }
        view.setOnGenericMotionListener(listener)
        onDispose {
            view.setOnGenericMotionListener(null)
        }
    }
}
