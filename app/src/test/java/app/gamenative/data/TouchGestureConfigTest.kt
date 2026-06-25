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
}
