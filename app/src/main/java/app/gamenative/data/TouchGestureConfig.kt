package app.gamenative.data

import org.json.JSONObject

/**
 * Per-game touch gesture configuration.
 *
 * Serialised as a JSON string and stored in the container's `gestureConfig` field so that
 * every game can have its own gesture mapping.
 */
data class TouchGestureConfig(
    // 1. Tap — fixed action: Left Click
    val tapEnabled: Boolean = true,

    // 2. Drag (box selection) — fixed action: Drag Left Click
    val dragEnabled: Boolean = true,

    // 3. Long Press — customizable action, disabled by default
    val longPressEnabled: Boolean = false,
    val longPressAction: String = ACTION_RIGHT_CLICK,
    val longPressDelay: Int = DEFAULT_DELAY_MS,

    // 4. Double-Tap — fixed action: Double Left Click
    val doubleTapEnabled: Boolean = true,
    val doubleTapDelay: Int = DEFAULT_DELAY_MS,

    // 5. Two-Finger Drag (camera pan) — customizable action
    val twoFingerDragEnabled: Boolean = true,
    val twoFingerDragAction: String = PAN_MIDDLE_MOUSE,

    // 6. Pinch In/Out (zoom) — customizable action
    val pinchEnabled: Boolean = true,
    val pinchAction: String = ZOOM_SCROLL_WHEEL,

    // 7. Two-Finger Tap — customizable action
    val twoFingerTapEnabled: Boolean = true,
    val twoFingerTapAction: String = ACTION_RIGHT_CLICK,
) {

    // ── Serialisation ────────────────────────────────────────────────────

    fun toJson(): String {
        return JSONObject().apply {
            put(KEY_TAP_ENABLED, tapEnabled)
            put(KEY_DRAG_ENABLED, dragEnabled)
            put(KEY_LONG_PRESS_ENABLED, longPressEnabled)
            put(KEY_LONG_PRESS_ACTION, longPressAction)
            put(KEY_LONG_PRESS_DELAY, longPressDelay)
            put(KEY_DOUBLE_TAP_ENABLED, doubleTapEnabled)
            put(KEY_DOUBLE_TAP_DELAY, doubleTapDelay)
            put(KEY_TWO_FINGER_DRAG_ENABLED, twoFingerDragEnabled)
            put(KEY_TWO_FINGER_DRAG_ACTION, twoFingerDragAction)
            put(KEY_PINCH_ENABLED, pinchEnabled)
            put(KEY_PINCH_ACTION, pinchAction)
            put(KEY_TWO_FINGER_TAP_ENABLED, twoFingerTapEnabled)
            put(KEY_TWO_FINGER_TAP_ACTION, twoFingerTapAction)
        }.toString()
    }

    companion object {
        // ── Delay constants ──────────────────────────────────────────────
        const val DEFAULT_DELAY_MS = 300
        const val DOUBLE_TAP_DISTANCE_PX = 100

        // ── Action identifiers: common mouse actions ─────────────────────
        const val ACTION_LEFT_CLICK = "left_click"
        const val ACTION_RIGHT_CLICK = "right_click"
        const val ACTION_MIDDLE_CLICK = "middle_click"

        // ── Action identifiers: two-finger drag (pan) ───────────────────
        const val PAN_WASD = "wasd"
        const val PAN_ARROW_KEYS = "arrow_keys"
        const val PAN_MIDDLE_MOUSE = "middle_mouse_pan"

        // ── Action identifiers: pinch (zoom) ─────────────────────────────
        const val ZOOM_SCROLL_WHEEL = "scroll_wheel"
        const val ZOOM_PLUS_MINUS = "plus_minus"
        const val ZOOM_PAGE_UP_DOWN = "page_up_down"

        // ── JSON keys ────────────────────────────────────────────────────
        private const val KEY_TAP_ENABLED = "tapEnabled"
        private const val KEY_DRAG_ENABLED = "dragEnabled"
        private const val KEY_LONG_PRESS_ENABLED = "longPressEnabled"
        private const val KEY_LONG_PRESS_ACTION = "longPressAction"
        private const val KEY_LONG_PRESS_DELAY = "longPressDelay"
        private const val KEY_DOUBLE_TAP_ENABLED = "doubleTapEnabled"
        private const val KEY_DOUBLE_TAP_DELAY = "doubleTapDelay"
        private const val KEY_TWO_FINGER_DRAG_ENABLED = "twoFingerDragEnabled"
        private const val KEY_TWO_FINGER_DRAG_ACTION = "twoFingerDragAction"
        private const val KEY_PINCH_ENABLED = "pinchEnabled"
        private const val KEY_PINCH_ACTION = "pinchAction"
        private const val KEY_TWO_FINGER_TAP_ENABLED = "twoFingerTapEnabled"
        private const val KEY_TWO_FINGER_TAP_ACTION = "twoFingerTapAction"

        /** Parse from a JSON string. Returns defaults when the string is null, blank or invalid. */
        fun fromJson(json: String?): TouchGestureConfig {
            if (json.isNullOrBlank()) return TouchGestureConfig()
            return try {
                val obj = JSONObject(json)
                TouchGestureConfig(
                    tapEnabled = obj.optBoolean(KEY_TAP_ENABLED, true),
                    dragEnabled = obj.optBoolean(KEY_DRAG_ENABLED, true),
                    longPressEnabled = obj.optBoolean(KEY_LONG_PRESS_ENABLED, false),
                    longPressAction = obj.optString(KEY_LONG_PRESS_ACTION, ACTION_RIGHT_CLICK),
                    longPressDelay = obj.optInt(KEY_LONG_PRESS_DELAY, DEFAULT_DELAY_MS),
                    doubleTapEnabled = obj.optBoolean(KEY_DOUBLE_TAP_ENABLED, true),
                    doubleTapDelay = obj.optInt(KEY_DOUBLE_TAP_DELAY, DEFAULT_DELAY_MS),
                    twoFingerDragEnabled = obj.optBoolean(KEY_TWO_FINGER_DRAG_ENABLED, true),
                    twoFingerDragAction = obj.optString(KEY_TWO_FINGER_DRAG_ACTION, PAN_ARROW_KEYS),
                    pinchEnabled = obj.optBoolean(KEY_PINCH_ENABLED, true),
                    pinchAction = obj.optString(KEY_PINCH_ACTION, ZOOM_SCROLL_WHEEL),
                    twoFingerTapEnabled = obj.optBoolean(KEY_TWO_FINGER_TAP_ENABLED, true),
                    twoFingerTapAction = obj.optString(KEY_TWO_FINGER_TAP_ACTION, ACTION_RIGHT_CLICK),
                )
            } catch (_: Exception) {
                TouchGestureConfig()
            }
        }

        /** Ordered list of common mouse actions (used by Long Press and Two-Finger Tap dropdowns). */
        val COMMON_MOUSE_ACTIONS = listOf(
            ACTION_LEFT_CLICK,
            ACTION_RIGHT_CLICK,
            ACTION_MIDDLE_CLICK,
        )

        /** Ordered list of pan/camera-drag actions. */
        val PAN_ACTIONS = listOf(
            PAN_MIDDLE_MOUSE,
            PAN_WASD,
            PAN_ARROW_KEYS,
        )

        /** Ordered list of zoom/pinch actions. */
        val ZOOM_ACTIONS = listOf(
            ZOOM_SCROLL_WHEEL,
            ZOOM_PLUS_MINUS,
            ZOOM_PAGE_UP_DOWN,
        )
    }
}
