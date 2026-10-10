package app.gamenative.ui.screen.xserver

/**
 * Ordered dispatch stages for a physical gamepad input event.
 *
 * The stage list returned by [gamepadInputRouteForSlot] decides which handlers
 * see a key or motion event, and in what order.
 */
internal enum class GamepadInputRoute {
    /** Record the device as the current physical controller (rumble targeting). */
    SET_CURRENT_CONTROLLER,

    /** Standalone profile-based handler that can translate input into the virtual gamepad. */
    PHYSICAL_CONTROLLER,

    /** Legacy on-screen controls handler; active only while touch controls are shown. */
    INPUT_CONTROLS,

    /** Slot-aware raw passthrough into the running Wine game. */
    WIN_HANDLER,
}

/**
 * Returns the dispatch stages for a physical gamepad event based on its assigned player slot.
 *
 * - `slot > 0`: an assigned Player 2-4 controller. Route straight to WinHandler, which owns
 *   per-slot raw passthrough.
 * - `slot <= 0`: Player 1 or an unassigned controller. Run the profile pipeline first so a
 *   bound profile can translate the controller into the virtual gamepad, then fall back to
 *   WinHandler's raw passthrough for buttons that have no profile binding (e.g. L1/R1).
 */
internal fun gamepadInputRouteForSlot(assignedSlot: Int): List<GamepadInputRoute> = when {
    assignedSlot > 0 -> listOf(GamepadInputRoute.WIN_HANDLER)
    else -> listOf(
        GamepadInputRoute.SET_CURRENT_CONTROLLER,
        GamepadInputRoute.PHYSICAL_CONTROLLER,
        GamepadInputRoute.INPUT_CONTROLS,
        GamepadInputRoute.WIN_HANDLER,
    )
}
