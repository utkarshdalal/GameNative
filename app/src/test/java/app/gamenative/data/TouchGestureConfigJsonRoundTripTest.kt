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
        // pre-overhaul stored gestureConfig -- only legacy keys present.
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

    // the one field where html5Defaults differs from compatibilityDefaults. a fresh container
    // has gestureConfig = "", so the whole defaults object is handed back.
    @Test
    fun a_blank_config_returns_the_html5_defaults_whole() {
        val parsed = TouchGestureConfig.fromJson("", TouchGestureConfig.html5Defaults())

        assertEquals(TouchGestureConfig.ACTION_OPEN_QUICK_MENU, parsed.threeFingerTapAction)
        assertEquals(TouchGestureConfig.html5Defaults(), parsed)
    }

    @Test
    fun a_blank_config_returns_wine_defaults_when_none_are_passed() {
        assertEquals(TouchGestureConfig.compatibilityDefaults(), TouchGestureConfig.fromJson(""))
        assertEquals(TouchGestureConfig.compatibilityDefaults(), TouchGestureConfig.fromJson(null))
    }

    // invalid JSON must hand back the caller's defaults, not the wine ones
    @Test
    fun invalid_json_returns_the_passed_defaults() {
        val parsed = TouchGestureConfig.fromJson("{not json", TouchGestureConfig.html5Defaults())

        assertEquals(TouchGestureConfig.html5Defaults(), parsed)
    }

    // toJson writes every key fromJson reads, so a stored config never consults a per-field
    // fallback -- passing html5Defaults must not override anything the user actually saved.
    @Test
    fun a_saved_config_is_unaffected_by_which_defaults_are_passed() {
        val saved = TouchGestureConfig.compatibilityDefaults()
            .copy(threeFingerTapAction = TouchGestureConfig.ACTION_SHOW_KEYBOARD)
            .toJson()

        val asWine = TouchGestureConfig.fromJson(saved)
        val asHtml5 = TouchGestureConfig.fromJson(saved, TouchGestureConfig.html5Defaults())

        assertEquals("a deliberate show_keyboard choice must survive", asWine, asHtml5)
        assertEquals(TouchGestureConfig.ACTION_SHOW_KEYBOARD, asHtml5.threeFingerTapAction)
    }

    // a config written before the key existed: the field falls back to the caller's defaults,
    // which is why threeFingerTapAction stays threaded rather than reverting to a literal
    @Test
    fun a_config_predating_the_key_takes_the_passed_default_for_it() {
        val legacy = org.json.JSONObject(TouchGestureConfig.compatibilityDefaults().toJson())
            .apply { remove("threeFingerTapAction") }
            .toString()

        assertEquals(
            TouchGestureConfig.ACTION_OPEN_QUICK_MENU,
            TouchGestureConfig.fromJson(legacy, TouchGestureConfig.html5Defaults()).threeFingerTapAction,
        )
        assertEquals(
            TouchGestureConfig.ACTION_SHOW_KEYBOARD,
            TouchGestureConfig.fromJson(legacy).threeFingerTapAction,
        )
    }

    // for every other field, a partial config parses the same whether wine or html5 defaults
    // are passed
    @Test
    fun every_other_field_ignores_which_defaults_are_passed() {
        val partial = "{}"

        val asWine = TouchGestureConfig.fromJson(partial)
        val asHtml5 = TouchGestureConfig.fromJson(partial, TouchGestureConfig.html5Defaults())

        assertEquals(asWine, asHtml5.copy(threeFingerTapAction = asWine.threeFingerTapAction))
    }
}
