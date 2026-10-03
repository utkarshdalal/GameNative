package app.gamenative.ui.screen.xserver

import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadInputRoutingTest {
    @Test
    fun `assigned second controller routes straight to WinHandler`() {
        assertEquals(
            listOf(GamepadInputRoute.WIN_HANDLER),
            gamepadInputRouteForSlot(1),
        )
    }

    @Test
    fun `assigned third and fourth controllers route straight to WinHandler`() {
        assertEquals(listOf(GamepadInputRoute.WIN_HANDLER), gamepadInputRouteForSlot(2))
        assertEquals(listOf(GamepadInputRoute.WIN_HANDLER), gamepadInputRouteForSlot(3))
    }

    @Test
    fun `player one keeps the profile pipeline and WinHandler fallback`() {
        assertEquals(
            listOf(
                GamepadInputRoute.SET_CURRENT_CONTROLLER,
                GamepadInputRoute.PHYSICAL_CONTROLLER,
                GamepadInputRoute.INPUT_CONTROLS,
                GamepadInputRoute.WIN_HANDLER,
            ),
            gamepadInputRouteForSlot(0),
        )
    }

    @Test
    fun `unassigned controller skips the standalone physical handler`() {
        // Regression: an unassigned (slot < 0) controller must not be hijacked by
        // PhysicalControllerHandler into Player 1's virtual gamepad state.
        assertEquals(
            listOf(
                GamepadInputRoute.INPUT_CONTROLS,
                GamepadInputRoute.WIN_HANDLER,
            ),
            gamepadInputRouteForSlot(-1),
        )
    }
}
