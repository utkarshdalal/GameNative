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
 * - `slot == 0`: Player 1. Let the profile-based handlers run first so a bound profile can
 *   translate the controller into the virtual gamepad, then fall back to WinHandler.
 * - `slot < 0`: an unassigned controller. Skip the standalone physical handler because it
 *   resolves controllers through the profile's wildcard entry and would route this input into
 *   Player 1's virtual gamepad state. The on-screen controls handler remains as the legacy
 *   virtual-gamepad path, and WinHandler is the final fallback.
 */
internal fun gamepadInputRouteForSlot(assignedSlot: Int): List<GamepadInputRoute> = when {
    assignedSlot > 0 -> listOf(GamepadInputRoute.WIN_HANDLER)
    assignedSlot == 0 -> listOf(
        GamepadInputRoute.SET_CURRENT_CONTROLLER,
        GamepadInputRoute.PHYSICAL_CONTROLLER,
        GamepadInputRoute.INPUT_CONTROLS,
        GamepadInputRoute.WIN_HANDLER,
    )
    else -> listOf(
        GamepadInputRoute.INPUT_CONTROLS,
        GamepadInputRoute.WIN_HANDLER,
    )
}
