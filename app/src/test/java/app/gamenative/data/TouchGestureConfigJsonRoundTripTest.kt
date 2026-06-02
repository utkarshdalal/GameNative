package app.gamenative.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// robolectric needed because TouchGestureConfig uses org.json.JSONObject which is an
// android stub on the host classpath. pure-jvm @Test fails at linkage in unit tests.
@RunWith(RobolectricTestRunner::class)
class TouchGestureConfigJsonRoundTripTest {

    @Test fun default_round_trips() {
        val original = TouchGestureConfig()
        val parsed = TouchGestureConfig.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    @Test fun non_default_round_trips() {
        val original = TouchGestureConfig(
            tapAction = TouchGestureConfig.ACTION_RIGHT_CLICK,
            dragAction = TouchGestureConfig.PAN_RIGHT_CLICK_DRAG,
            longPressEnabled = true,
            longPressAction = TouchGestureConfig.ACTION_MIDDLE_CLICK,
            longPressDelay = 500,
            twoFingerDragAction = TouchGestureConfig.PAN_WASD,
            pinchAction = TouchGestureConfig.ZOOM_PLUS_MINUS,
            twoFingerHoldEnabled = false,
            threeFingerTapAction = TouchGestureConfig.ACTION_OPEN_QUICK_MENU,
            threeFingerHoldAction = "key_TAB",
            showClickHighlight = true,
            gestureThreshold = 60,
            cursorMode = TouchGestureConfig.CURSOR_MODE_RELATIVE,
        )
        val parsed = TouchGestureConfig.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    @Test fun blank_uses_compatibility_defaults_by_default() {
        val parsed = TouchGestureConfig.fromJson("")
        assertEquals(TouchGestureConfig.compatibilityDefaults(), parsed)
    }

    @Test fun blank_with_html5_defaults_uses_open_quick_menu() {
        val parsed = TouchGestureConfig.fromJson("", TouchGestureConfig.html5Defaults())
        assertEquals(TouchGestureConfig.ACTION_OPEN_QUICK_MENU, parsed.threeFingerTapAction)
    }

    @Test fun partial_json_picks_up_defaults_per_caller() {
        // mimics a pre-overhaul container's stored gestureConfig — only legacy keys present.
        val legacy = JSONObject().apply {
            put("tapEnabled", true)
            put("dragEnabled", true)
            put("longPressEnabled", false)
            put("doubleTapEnabled", true)
            put("twoFingerDragEnabled", true)
            put("twoFingerTapEnabled", true)
        }.toString()

        val wineParsed = TouchGestureConfig.fromJson(legacy)
        assertEquals(TouchGestureConfig.ACTION_SHOW_KEYBOARD, wineParsed.threeFingerTapAction)

        val html5Parsed = TouchGestureConfig.fromJson(legacy, TouchGestureConfig.html5Defaults())
        assertEquals(TouchGestureConfig.ACTION_OPEN_QUICK_MENU, html5Parsed.threeFingerTapAction)
        assertEquals(TouchGestureConfig.CURSOR_MODE_ABSOLUTE, html5Parsed.cursorMode)
    }

    @Test fun invalid_json_returns_supplied_defaults() {
        assertEquals(
            TouchGestureConfig.compatibilityDefaults(),
            TouchGestureConfig.fromJson("not json {{{"),
        )
        assertEquals(
            TouchGestureConfig.html5Defaults(),
            TouchGestureConfig.fromJson("not json {{{", TouchGestureConfig.html5Defaults()),
        )
    }

    @Test fun null_returns_supplied_defaults() {
        assertEquals(TouchGestureConfig.compatibilityDefaults(), TouchGestureConfig.fromJson(null))
    }
}
