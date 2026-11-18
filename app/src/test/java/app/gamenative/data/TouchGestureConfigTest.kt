package app.gamenative.data

import app.gamenative.data.TouchGestureConfig.Companion.ACTION_RIGHT_CLICK
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGestureConfigTest {
    @Test
    fun `new touch options default off for older configs`() {
        val config = TouchGestureConfig.fromJson("""{"tapEnabled":false}""")

        assertFalse(config.holdMouseButtonWhileTouchingEnabled)
        assertFalse(config.showCursorInTouchscreenMode)
        assertEquals(TouchGestureConfig.ACTION_LEFT_CLICK, config.holdMouseButtonWhileTouchingAction)
    }

    @Test
    fun `new touch options are written to json`() {
        val config = TouchGestureConfig(
            holdMouseButtonWhileTouchingEnabled = true,
            holdMouseButtonWhileTouchingAction = ACTION_RIGHT_CLICK,
            showCursorInTouchscreenMode = true,
        )

        val json = JSONObject(config.toJson())

        assertTrue(json.getBoolean("holdMouseButtonWhileTouchingEnabled"))
        assertEquals(ACTION_RIGHT_CLICK, json.getString("holdMouseButtonWhileTouchingAction"))
        assertTrue(json.getBoolean("showCursorInTouchscreenMode"))
    }

    @Test
    fun `new touch options round trip from json`() {
        val expected = TouchGestureConfig(
            holdMouseButtonWhileTouchingEnabled = true,
            holdMouseButtonWhileTouchingAction = ACTION_RIGHT_CLICK,
            showCursorInTouchscreenMode = true,
        )

        val actual = TouchGestureConfig.fromJson(expected.toJson())

        assertTrue(actual.holdMouseButtonWhileTouchingEnabled)
        assertEquals(ACTION_RIGHT_CLICK, actual.holdMouseButtonWhileTouchingAction)
        assertTrue(actual.showCursorInTouchscreenMode)
    }

    @Test
    fun `action combo encodes and decodes with modifiers first`() {
        val combo = TouchGestureConfig.actionComboOf(listOf("key_1", "key_CTRL_L"))

        assertEquals("combo:key_CTRL_L|key_1", combo)
        assertEquals(listOf("key_CTRL_L", "key_1"), TouchGestureConfig.actionParts(combo))
        assertEquals("key_1", TouchGestureConfig.primaryAction(combo))
    }

    @Test
    fun `action sequence preserves selected order`() {
        val sequence = TouchGestureConfig.actionComboOf(
            listOf("key_E", TouchGestureConfig.ACTION_LEFT_CLICK),
            sequence = true,
            sequenceDelayMs = 220,
        )

        assertEquals("seq:220:key_E|left_click", sequence)
        assertEquals(listOf("key_E", TouchGestureConfig.ACTION_LEFT_CLICK), TouchGestureConfig.actionParts(sequence))
        assertEquals(TouchGestureConfig.ACTION_LEFT_CLICK, TouchGestureConfig.primaryAction(sequence))
        assertEquals(220, TouchGestureConfig.actionSequenceDelayMs(sequence))
        assertTrue(TouchGestureConfig.isActionSequence(sequence))
    }

    @Test
    fun `old action sequence defaults delay`() {
        val sequence = "seq:key_E|left_click"

        assertEquals(listOf("key_E", TouchGestureConfig.ACTION_LEFT_CLICK), TouchGestureConfig.actionParts(sequence))
        assertEquals(TouchGestureConfig.DEFAULT_ACTION_SEQUENCE_DELAY_MS, TouchGestureConfig.actionSequenceDelayMs(sequence))
    }

    @Test
    fun `mouse action detection does not depend on combo order`() {
        val combo = TouchGestureConfig.actionComboOf(
            listOf(TouchGestureConfig.ACTION_LEFT_CLICK, "key_E"),
        )

        assertTrue(TouchGestureConfig.containsMouseButtonAction(combo))
    }
}
