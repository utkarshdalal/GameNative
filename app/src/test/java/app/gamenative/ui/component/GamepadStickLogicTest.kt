package app.gamenative.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure stick decision logic (spec 2026-08-10, §3.1 — RC1).
 *
 * RC1 regression: the old logic re-armed only below a 0.30 release zone, so a stick
 * resting in drift at 0.30–0.44 swallowed every following movement until it physically
 * returned to center. [GamepadStickLogic] re-arms below the 0.45 dead zone instead.
 */
class GamepadStickLogicTest {

    private val t0 = 1_000L
    private val defaultState = GamepadStickState()

    // ── Core semantics ─────────────────────────────────────────────────────

    @Test
    fun `first push beyond the dead zone moves immediately`() {
        val decision = GamepadStickLogic.decide(
            previous = defaultState,
            now = t0,
            magnitude = 0.6f,
            direction = GamepadStickDirection.Down,
        )
        assertEquals(GamepadStickDirection.Down, decision.direction)
        assertFalse(decision.state.armed)
        assertEquals(t0, decision.state.lastMoveAt)
    }

    @Test
    fun `regression - rest at 0_40 re-arms after a move and the next push moves`() {
        // Move once (disarmed).
        val moved = GamepadStickLogic.decide(
            previous = defaultState,
            now = t0,
            magnitude = 0.6f,
            direction = GamepadStickDirection.Down,
        )
        assertFalse(moved.state.armed)

        // Stick returns to rest at 0.40: >= old releaseZone (0.30) but < deadZone (0.45).
        val rest = GamepadStickLogic.decide(
            previous = moved.state,
            now = t0 + 200,
            magnitude = 0.40f,
            direction = null,
        )
        assertTrue("0.40 rest must re-arm (RC1 regression)", rest.state.armed)

        // The following push moves again.
        val push = GamepadStickLogic.decide(
            previous = rest.state,
            now = t0 + 400,
            magnitude = 0.6f,
            direction = GamepadStickDirection.Down,
        )
        assertEquals(GamepadStickDirection.Down, push.direction)
        assertFalse(push.state.armed)
    }

    @Test
    fun `stick held above the dead zone never re-arms`() {
        var state = GamepadStickLogic.decide(
            previous = defaultState,
            now = t0,
            magnitude = 0.6f,
            direction = GamepadStickDirection.Down,
        ).state
        // Repeated samples while held: never moves, never re-arms.
        repeat(5) { i ->
            val decision = GamepadStickLogic.decide(
                previous = state,
                now = t0 + 200L * (i + 1),
                magnitude = 0.6f,
                direction = GamepadStickDirection.Down,
            )
            assertNull(decision.direction)
            assertFalse(decision.state.armed)
            state = decision.state
        }
    }

    @Test
    fun `cooldown drops the move but preserves the armed state`() {
        // Move at t0 (disarmed).
        val moved = GamepadStickLogic.decide(
            previous = defaultState,
            now = t0,
            magnitude = 0.6f,
            direction = GamepadStickDirection.Right,
        )
        // Back to center at t0+100: re-arms.
        val centered = GamepadStickLogic.decide(
            previous = moved.state,
            now = t0 + 100,
            magnitude = 0.1f,
            direction = null,
        )
        assertTrue(centered.state.armed)

        // Push at t0+150: inside the 180 ms cooldown -> no move, but armed is preserved.
        val early = GamepadStickLogic.decide(
            previous = centered.state,
            now = t0 + 150,
            magnitude = 0.6f,
            direction = GamepadStickDirection.Right,
        )
        assertNull(early.direction)
        assertTrue("cooldown must preserve the armed state", early.state.armed)

        // Push at t0+250: cooldown elapsed -> moves.
        val late = GamepadStickLogic.decide(
            previous = early.state,
            now = t0 + 250,
            magnitude = 0.6f,
            direction = GamepadStickDirection.Right,
        )
        assertEquals(GamepadStickDirection.Right, late.direction)
    }

    @Test
    fun `hat with its own magnitude moves independent of the stick`() {
        val decision = GamepadStickLogic.decide(
            previous = defaultState,
            now = t0,
            magnitude = 0.6f,
            direction = GamepadStickDirection.Left,
        )
        assertEquals(GamepadStickDirection.Left, decision.direction)
        assertFalse(decision.state.armed)
        assertEquals(t0, decision.state.lastMoveAt)
    }

    @Test
    fun `neutral stick neither moves nor disarms`() {
        val decision = GamepadStickLogic.decide(
            previous = defaultState,
            now = t0,
            magnitude = 0.1f,
            direction = null,
        )
        assertNull(decision.direction)
        assertTrue(decision.state.armed)
        assertEquals(0L, decision.state.lastMoveAt)
    }

    // ── Direction mapping (spec §3.8 extension) ────────────────────────────

    @Test
    fun `stick directions map 1 to 1 to focus directions`() {
        assertEquals(
            androidx.compose.ui.focus.FocusDirection.Up,
            GamepadStickDirection.Up.focusDirection,
        )
        assertEquals(
            androidx.compose.ui.focus.FocusDirection.Down,
            GamepadStickDirection.Down.focusDirection,
        )
        assertEquals(
            androidx.compose.ui.focus.FocusDirection.Left,
            GamepadStickDirection.Left.focusDirection,
        )
        assertEquals(
            androidx.compose.ui.focus.FocusDirection.Right,
            GamepadStickDirection.Right.focusDirection,
        )
    }
}
