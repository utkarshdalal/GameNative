package app.gamenative.ui.component

/**
 * Pure dedupe between the TWO focus-movement channels of a gamepad (spec 2026-08-12, M4 — C4).
 *
 * C4: controllers that emit BOTH hat-axis motion AND KEYCODE_DPAD_* keys for the same
 * physical gesture move the focus twice — once via [BusJoystickFocusNavigator] (axes) and
 * once via [BusGamepadKeyBridge] re-dispatching the raw key into Compose, whose focus
 * system handles DPAD natively. 1 press = 2 moves (skipping rows/tabs).
 *
 * Both channels stamp the shared [GamepadNavigationClock.lastMoveAt] on every REAL move;
 * whichever channel moves first wins the 120 ms gesture window, the second is suppressed.
 * Key repeats (holding the D-pad) always pass — the key channel is the continuous-repeat
 * channel; axis motion does not repeat while held, so a held D-pad must never be swallowed
 * by the dedupe.
 */
object GamepadMoveDedupe {
    const val WINDOW_MS = 120L

    /**
     * Should a DPAD key be re-dispatched to Compose?
     *
     * - `repeatCount > 0` (held D-pad): ALWAYS dispatch — the axis channel does not repeat,
     *   so the key channel is the only source of continuous repeat.
     * - First press: dispatch only when no axis/hat move of the same gesture already moved
     *   the focus inside [WINDOW_MS] (the axis channel won the race).
     */
    fun shouldDispatchKeyMove(now: Long, lastMoveAt: Long, repeatCount: Int): Boolean {
        if (repeatCount > 0) return true
        return now - lastMoveAt >= WINDOW_MS
    }

    /**
     * Should an axis/hat move move the focus?
     *
     * No when a DPAD key of the same gesture already moved the focus inside [WINDOW_MS]
     * (the key channel won the race — the bridge stamps the clock when it dispatches).
     */
    fun shouldDispatchAxisMove(now: Long, lastMoveAt: Long): Boolean =
        now - lastMoveAt >= WINDOW_MS
}
