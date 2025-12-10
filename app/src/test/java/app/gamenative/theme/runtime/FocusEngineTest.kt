package app.gamenative.theme.runtime

import app.gamenative.theme.model.SelectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusEngineTest {

    @Test
    fun grid_horizontal_wrap_within_row() {
        val cfg = FocusEngine.Config(rows = 2, cols = 3, wrapX = true, wrapY = false, selectionMode = SelectionMode.MOVING)
        var s = FocusEngine.State(totalItems = 10, config = cfg, selectedIndex = 0, firstVisibleIndex = 0)
        // left from index 0 wraps to end of first row (index 2)
        s = FocusEngine.moveLeft(s)
        assertEquals(2, s.selectedIndex)
        // right from end of row wraps to start of row (index 0)
        s = FocusEngine.moveRight(s)
        assertEquals(0, s.selectedIndex)
    }

    @Test
    fun grid_vertical_movement_clamps_last_row() {
        val cfg = FocusEngine.Config(rows = 2, cols = 4, wrapX = false, wrapY = false, selectionMode = SelectionMode.MOVING)
        var s = FocusEngine.State(totalItems = 6, config = cfg, selectedIndex = 4, firstVisibleIndex = 0)
        // moving down from last row stays in last row
        s = FocusEngine.moveDown(s)
        assertEquals(4, s.selectedIndex)
        // moving up goes to same column in previous row
        s = FocusEngine.moveUp(s)
        assertEquals(0, s.selectedIndex)
    }

    @Test
    fun carousel_stationary_centers_selection() {
        val cfg = FocusEngine.Config(rows = 1, cols = 5, pageSize = 5, selectionMode = SelectionMode.STATIONARY, centeredSelection = true)
        var s = FocusEngine.State(totalItems = 10, config = cfg, selectedIndex = 0, firstVisibleIndex = 0)
        repeat(7) { s = FocusEngine.moveRight(s) }
        // selected = 7; with centered selection and page=5 -> firstVisible should be 5
        assertEquals(7, s.selectedIndex)
        assertEquals(5, s.firstVisibleIndex)
    }
}
