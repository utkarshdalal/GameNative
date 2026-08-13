package app.gamenative.ui.component

import android.view.KeyEvent
import app.gamenative.ui.component.GamepadKeyLogic.AdjustAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure decision logic behind the gamepad modifier framework
 * (spec 2026-08-09, §3.1 — the Compose modifiers are thin wrappers over these functions).
 *
 * Only compile-time KeyEvent constants are used, so no Android runtime is needed.
 */
class GamepadModifiersTest {

    // ── gamepadSelectable activation ──────────────────────────────────────

    @Test
    fun `raw A activates a focused row on first down`() {
        assertTrue(
            GamepadKeyLogic.selectableActivation(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
            )
        )
    }

    @Test
    fun `DPAD_CENTER activates a focused row (bridged A)`() {
        assertTrue(
            GamepadKeyLogic.selectableActivation(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
            )
        )
    }

    @Test
    fun `ENTER activates a focused row`() {
        assertTrue(
            GamepadKeyLogic.selectableActivation(
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
            )
        )
    }

    @Test
    fun `activation repeats propagate to the parent`() {
        // Holding A must not re-activate; the event is not consumed.
        assertFalse(
            GamepadKeyLogic.selectableActivation(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
                isFocused = true,
            )
        )
    }

    @Test
    fun `unfocused rows never consume activation keys`() {
        assertFalse(
            GamepadKeyLogic.selectableActivation(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = false,
            )
        )
    }

    @Test
    fun `key up never activates`() {
        assertFalse(
            GamepadKeyLogic.selectableActivation(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
                isFocused = true,
            )
        )
    }

    @Test
    fun `navigation keys are never swallowed`() {
        for (key in intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
        )) {
            assertFalse(
                "key $key must propagate",
                GamepadKeyLogic.selectableActivation(
                    keyCode = key,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                    isFocused = true,
                )
            )
        }
    }

    // ── gamepadAdjustableRow lock semantics ───────────────────────────────

    @Test
    fun `A locks an unlocked focused row`() {
        assertEquals(
            AdjustAction.ToggleLock,
            GamepadKeyLogic.adjustableAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isLocked = false,
            )
        )
    }

    @Test
    fun `raw A locks without a bridge`() {
        assertEquals(
            AdjustAction.ToggleLock,
            GamepadKeyLogic.adjustableAction(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isLocked = false,
            )
        )
    }

    @Test
    fun `A unlocks a locked row`() {
        assertEquals(
            AdjustAction.ToggleLock,
            GamepadKeyLogic.adjustableAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isLocked = true,
            )
        )
    }

    @Test
    fun `A repeats do not toggle the lock`() {
        assertEquals(
            AdjustAction.Ignore,
            GamepadKeyLogic.adjustableAction(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 2,
                isFocused = true,
                isLocked = false,
            )
        )
    }

    @Test
    fun `raw B unlocks a locked row`() {
        assertEquals(
            AdjustAction.Unlock,
            GamepadKeyLogic.adjustableAction(
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isLocked = true,
            )
        )
    }

    @Test
    fun `raw B propagates when the row is unlocked (hierarchical back)`() {
        assertEquals(
            AdjustAction.Ignore,
            GamepadKeyLogic.adjustableAction(
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isLocked = false,
            )
        )
    }

    @Test
    fun `left right adjust only while locked`() {
        assertEquals(
            AdjustAction.AdjustLeft,
            GamepadKeyLogic.adjustableAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 3,
                isFocused = true,
                isLocked = true,
            )
        )
        assertEquals(
            AdjustAction.AdjustRight,
            GamepadKeyLogic.adjustableAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isLocked = true,
            )
        )
        // Unlocked: L/R must propagate so the focus system can navigate.
        assertEquals(
            AdjustAction.Ignore,
            GamepadKeyLogic.adjustableAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isLocked = false,
            )
        )
    }

    @Test
    fun `adjustment keys do nothing when the row is not focused`() {
        for (key in intArrayOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )) {
            assertEquals(
                AdjustAction.Ignore,
                GamepadKeyLogic.adjustableAction(
                    keyCode = key,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                    isFocused = false,
                    isLocked = true,
                )
            )
        }
    }

    // ── gamepadBackHandler ────────────────────────────────────────────────

    @Test
    fun `raw B down is a back`() {
        assertTrue(
            GamepadKeyLogic.back(
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                action = KeyEvent.ACTION_DOWN,
            )
        )
    }

    @Test
    fun `B up is not a back (no double-fire)`() {
        assertFalse(
            GamepadKeyLogic.back(
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                action = KeyEvent.ACTION_UP,
            )
        )
    }

    @Test
    fun `physical BACK is not handled here (disjoint path)`() {
        assertFalse(
            GamepadKeyLogic.back(
                keyCode = KeyEvent.KEYCODE_BACK,
                action = KeyEvent.ACTION_DOWN,
            )
        )
    }

    @Test
    fun `other keys are not back`() {
        assertFalse(
            GamepadKeyLogic.back(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                action = KeyEvent.ACTION_DOWN,
            )
        )
    }
}
