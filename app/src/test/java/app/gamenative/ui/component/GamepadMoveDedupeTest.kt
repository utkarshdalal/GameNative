package app.gamenative.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure move-channel dedupe (spec 2026-08-12, M4 — C4).
 *
 * C4: controllers that emit BOTH hat-axis motion AND KEYCODE_DPAD_* keys for the same
 * physical gesture moved the focus twice (navigator via axes + bridge re-dispatching the
 * raw key into Compose's native DPAD handling). The first channel to move inside the
 * 120 ms gesture window wins; the second is suppressed. Key repeats always pass (the key
 * channel is the continuous-repeat channel while the stick/hat is held).
 */
class GamepadMoveDedupeTest {

    private val t0 = 1_000L

    // ── Key channel ────────────────────────────────────────────────────────

    @Test
    fun `first key press with no recent axis move dispatches`() {
        assertTrue(
            GamepadMoveDedupe.shouldDispatchKeyMove(
                now = t0,
                lastMoveAt = t0 - 5_000L,
                repeatCount = 0,
            )
        )
    }

    @Test
    fun `key press inside the window after an axis move is suppressed`() {
        // Axis moved 50 ms ago — the same gesture already moved the focus.
        assertFalse(
            GamepadMoveDedupe.shouldDispatchKeyMove(
                now = t0,
                lastMoveAt = t0 - 50L,
                repeatCount = 0,
            )
        )
    }

    @Test
    fun `key press exactly at the window boundary dispatches`() {
        assertTrue(
            GamepadMoveDedupe.shouldDispatchKeyMove(
                now = t0,
                lastMoveAt = t0 - GamepadMoveDedupe.WINDOW_MS,
                repeatCount = 0,
            )
        )
    }

    @Test
    fun `key repeat always dispatches even inside the window`() {
        // Holding the D-pad: the axis channel does not repeat while held, so the key
        // channel must never be swallowed by the dedupe.
        assertTrue(
            GamepadMoveDedupe.shouldDispatchKeyMove(
                now = t0,
                lastMoveAt = t0 - 30L,
                repeatCount = 1,
            )
        )
        assertTrue(
            GamepadMoveDedupe.shouldDispatchKeyMove(
                now = t0,
                lastMoveAt = t0 - 30L,
                repeatCount = 3,
            )
        )
    }

    // ── Axis channel ───────────────────────────────────────────────────────

    @Test
    fun `axis move with no recent key move dispatches`() {
        assertTrue(
            GamepadMoveDedupe.shouldDispatchAxisMove(
                now = t0,
                lastMoveAt = t0 - 5_000L,
            )
        )
    }

    @Test
    fun `axis move inside the window after a key move is suppressed`() {
        // The bridge stamped the clock when it dispatched the DPAD key 80 ms ago.
        assertFalse(
            GamepadMoveDedupe.shouldDispatchAxisMove(
                now = t0,
                lastMoveAt = t0 - 80L,
            )
        )
    }

    @Test
    fun `axis move exactly at the window boundary dispatches`() {
        assertTrue(
            GamepadMoveDedupe.shouldDispatchAxisMove(
                now = t0,
                lastMoveAt = t0 - GamepadMoveDedupe.WINDOW_MS,
            )
        )
    }

    @Test
    fun `axis move just outside the window dispatches`() {
        assertTrue(
            GamepadMoveDedupe.shouldDispatchAxisMove(
                now = t0,
                lastMoveAt = t0 - GamepadMoveDedupe.WINDOW_MS - 1L,
            )
        )
    }
}
