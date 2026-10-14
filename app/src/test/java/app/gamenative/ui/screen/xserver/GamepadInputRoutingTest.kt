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
    fun `unassigned controller keeps the profile pipeline and WinHandler fallback`() {
        // Regression: an unassigned (slot < 0) controller must reach WinHandler's raw
        // passthrough so buttons without a profile binding (L1/R1) are not dropped. Bound
        // buttons keep working through the standalone physical handler, which is also what
        // sends the translated gamepad state.
        assertEquals(
            listOf(
                GamepadInputRoute.SET_CURRENT_CONTROLLER,
                GamepadInputRoute.PHYSICAL_CONTROLLER,
                GamepadInputRoute.INPUT_CONTROLS,
                GamepadInputRoute.WIN_HANDLER,
            ),
            gamepadInputRouteForSlot(-1),
        )
    }
}
