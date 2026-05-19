package app.gamenative.data

import org.json.JSONObject

/**
 * Per-game touch gesture configuration.
 *
 * Serialised as a JSON string and stored in the container's `gestureConfig` field so that
 * every game can have its own gesture mapping.
 */
data class TouchGestureConfig(
    // 1. Tap — customizable action
    val tapEnabled: Boolean = true,
    val tapAction: String = ACTION_LEFT_CLICK,

    // 2. Drag (box selection) — customizable pan/drag action
    val dragEnabled: Boolean = true,
    val dragAction: String = PAN_LEFT_CLICK_DRAG,

    // 3. Long Press — customizable action, disabled by default
    val longPressEnabled: Boolean = false,
    val longPressAction: String = ACTION_RIGHT_CLICK,
    val longPressDelay: Int = DEFAULT_DELAY_MS,

    // 4. Double-Tap — fixed action: Double Left Click
    val doubleTapEnabled: Boolean = true,
    val doubleTapDelay: Int = DEFAULT_DELAY_MS,

    // 5. Two-Finger Drag (camera pan) — customizable action
    val twoFingerDragEnabled: Boolean = true,
    val twoFingerDragAction: String = PAN_ARROW_KEYS,

    // 6. Pinch In/Out (zoom) — customizable action
    val pinchEnabled: Boolean = true,
    val pinchAction: String = ZOOM_SCROLL_WHEEL,

    // 7. Two-Finger Tap — customizable action
    val twoFingerTapEnabled: Boolean = true,
    val twoFingerTapAction: String = ACTION_RIGHT_CLICK,

    // 8. Two-Finger Hold — customizable action + delay
    val twoFingerHoldEnabled: Boolean = true,
    val twoFingerHoldAction: String = ACTION_MIDDLE_CLICK,
    val twoFingerHoldDelay: Int = DEFAULT_DELAY_MS,

    // 9. Three-Finger Tap — customizable action
    val threeFingerTapEnabled: Boolean = true,
    val threeFingerTapAction: String = ACTION_SHOW_KEYBOARD,

    // 10. Three-Finger Drag — customizable action
    val threeFingerDragEnabled: Boolean = false,
    val threeFingerDragAction: String = PAN_ARROW_KEYS,

    // 11. Three-Finger Hold — customizable action + delay
    val threeFingerHoldEnabled: Boolean = true,
    val threeFingerHoldAction: String = ACTION_KEY_ESC,
    val threeFingerHoldDelay: Int = DEFAULT_DELAY_MS,

    // 12. Click highlight circle
    val showClickHighlight: Boolean = false,

    // 13. Gesture debug overlay text (Tap, 2F Drag, etc.)
    val showGestureDebugOverlay: Boolean = false,

    // 14. Gesture threshold (px) for two-finger gesture lock-in
    val gestureThreshold: Int = DEFAULT_GESTURE_THRESHOLD,
) {

    // ── Serialisation ────────────────────────────────────────────────────

    fun toJson(): String {
        return JSONObject().apply {
            put(KEY_TAP_ENABLED, tapEnabled)
            put(KEY_TAP_ACTION, tapAction)
            put(KEY_DRAG_ENABLED, dragEnabled)
            put(KEY_DRAG_ACTION, dragAction)
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
            put(KEY_TWO_FINGER_HOLD_ENABLED, twoFingerHoldEnabled)
            put(KEY_TWO_FINGER_HOLD_ACTION, twoFingerHoldAction)
            put(KEY_TWO_FINGER_HOLD_DELAY, twoFingerHoldDelay)
            put(KEY_THREE_FINGER_TAP_ENABLED, threeFingerTapEnabled)
            put(KEY_THREE_FINGER_TAP_ACTION, threeFingerTapAction)
            put(KEY_THREE_FINGER_DRAG_ENABLED, threeFingerDragEnabled)
            put(KEY_THREE_FINGER_DRAG_ACTION, threeFingerDragAction)
            put(KEY_THREE_FINGER_HOLD_ENABLED, threeFingerHoldEnabled)
            put(KEY_THREE_FINGER_HOLD_ACTION, threeFingerHoldAction)
            put(KEY_THREE_FINGER_HOLD_DELAY, threeFingerHoldDelay)
            put(KEY_SHOW_CLICK_HIGHLIGHT, showClickHighlight)
            put(KEY_SHOW_GESTURE_DEBUG_OVERLAY, showGestureDebugOverlay)
            put(KEY_GESTURE_THRESHOLD, gestureThreshold)
        }.toString()
    }

    companion object {
        // ── Delay constants ──────────────────────────────────────────────
        const val DEFAULT_DELAY_MS = 300
        const val DEFAULT_GESTURE_THRESHOLD = 40
        const val DOUBLE_TAP_DISTANCE_PX = 100

        // ── Action identifiers: common mouse actions ─────────────────────
        const val ACTION_LEFT_CLICK = "left_click"
        const val ACTION_RIGHT_CLICK = "right_click"
        const val ACTION_MIDDLE_CLICK = "middle_click"

        // ── Special actions ──────────────────────────────────────────────
        const val ACTION_SHOW_KEYBOARD = "show_keyboard"
        const val ACTION_KEY_ESC = "key_ESC"

        // ── Action identifiers: two-finger drag (pan) ───────────────────
        const val PAN_WASD = "wasd"
        const val PAN_ARROW_KEYS = "arrow_keys"
        const val PAN_MIDDLE_MOUSE = "middle_mouse_pan"
        const val PAN_LEFT_CLICK_DRAG = "left_click_drag"
        const val PAN_RIGHT_CLICK_DRAG = "right_click_drag"

        // ── Action identifiers: pinch (zoom) ─────────────────────────────
        const val ZOOM_SCROLL_WHEEL = "scroll_wheel"
        const val ZOOM_PLUS_MINUS = "plus_minus"
        const val ZOOM_PAGE_UP_DOWN = "page_up_down"

        // ── JSON keys ────────────────────────────────────────────────────
        private const val KEY_TAP_ENABLED = "tapEnabled"
        private const val KEY_TAP_ACTION = "tapAction"
        private const val KEY_DRAG_ENABLED = "dragEnabled"
        private const val KEY_DRAG_ACTION = "dragAction"
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
        private const val KEY_TWO_FINGER_HOLD_ENABLED = "twoFingerHoldEnabled"
        private const val KEY_TWO_FINGER_HOLD_ACTION = "twoFingerHoldAction"
        private const val KEY_TWO_FINGER_HOLD_DELAY = "twoFingerHoldDelay"
        private const val KEY_THREE_FINGER_TAP_ENABLED = "threeFingerTapEnabled"
        private const val KEY_THREE_FINGER_TAP_ACTION = "threeFingerTapAction"
        private const val KEY_THREE_FINGER_DRAG_ENABLED = "threeFingerDragEnabled"
        private const val KEY_THREE_FINGER_DRAG_ACTION = "threeFingerDragAction"
        private const val KEY_THREE_FINGER_HOLD_ENABLED = "threeFingerHoldEnabled"
        private const val KEY_THREE_FINGER_HOLD_ACTION = "threeFingerHoldAction"
        private const val KEY_THREE_FINGER_HOLD_DELAY = "threeFingerHoldDelay"
        private const val KEY_SHOW_CLICK_HIGHLIGHT = "showClickHighlight"
        private const val KEY_SHOW_GESTURE_DEBUG_OVERLAY = "showGestureDebugOverlay"
        private const val KEY_GESTURE_THRESHOLD = "gestureThreshold"

        /**
         * Compatibility-safe defaults for existing (pre-overhaul) configs.
         *
         * When a container has no saved gesture JSON, or its JSON is corrupt,
         * or the JSON simply predates the new two-/three-finger gestures, we
         * don't want the new gestures silently enabled on upgrade. Return a
         * config with only the legacy gestures on.
         */
        fun compatibilityDefaults(): TouchGestureConfig = TouchGestureConfig(
            twoFingerHoldEnabled = false,
            threeFingerTapEnabled = false,
            threeFingerHoldEnabled = false,
        )

        /** Parse from a JSON string. Returns compatibility-safe defaults when the string is null, blank or invalid. */
        fun fromJson(json: String?): TouchGestureConfig {
            if (json.isNullOrBlank()) return compatibilityDefaults()
            return try {
                val obj = JSONObject(json)
                TouchGestureConfig(
                    tapEnabled = obj.optBoolean(KEY_TAP_ENABLED, true),
                    tapAction = obj.optString(KEY_TAP_ACTION, ACTION_LEFT_CLICK),
                    dragEnabled = obj.optBoolean(KEY_DRAG_ENABLED, true),
                    dragAction = obj.optString(KEY_DRAG_ACTION, PAN_LEFT_CLICK_DRAG),
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
                    twoFingerHoldEnabled = obj.optBoolean(KEY_TWO_FINGER_HOLD_ENABLED, false),
                    twoFingerHoldAction = obj.optString(KEY_TWO_FINGER_HOLD_ACTION, ACTION_MIDDLE_CLICK),
                    twoFingerHoldDelay = obj.optInt(KEY_TWO_FINGER_HOLD_DELAY, DEFAULT_DELAY_MS),
                    threeFingerTapEnabled = obj.optBoolean(KEY_THREE_FINGER_TAP_ENABLED, false),
                    threeFingerTapAction = obj.optString(KEY_THREE_FINGER_TAP_ACTION, ACTION_SHOW_KEYBOARD),
                    threeFingerDragEnabled = obj.optBoolean(KEY_THREE_FINGER_DRAG_ENABLED, false),
                    threeFingerDragAction = obj.optString(KEY_THREE_FINGER_DRAG_ACTION, PAN_ARROW_KEYS),
                    threeFingerHoldEnabled = obj.optBoolean(KEY_THREE_FINGER_HOLD_ENABLED, false),
                    threeFingerHoldAction = obj.optString(KEY_THREE_FINGER_HOLD_ACTION, ACTION_KEY_ESC),
                    threeFingerHoldDelay = obj.optInt(KEY_THREE_FINGER_HOLD_DELAY, DEFAULT_DELAY_MS),
                    showClickHighlight = obj.optBoolean(KEY_SHOW_CLICK_HIGHLIGHT, false),
                    showGestureDebugOverlay = obj.optBoolean(KEY_SHOW_GESTURE_DEBUG_OVERLAY, false),
                    gestureThreshold = obj.optInt(KEY_GESTURE_THRESHOLD, DEFAULT_GESTURE_THRESHOLD),
                )
            } catch (_: Exception) {
                compatibilityDefaults()
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
            PAN_ARROW_KEYS,
            PAN_WASD,
            PAN_MIDDLE_MOUSE,
            PAN_LEFT_CLICK_DRAG,
            PAN_RIGHT_CLICK_DRAG,
        )

        /** Ordered list of zoom/pinch actions. */
        val ZOOM_ACTIONS = listOf(
            ZOOM_SCROLL_WHEEL,
            ZOOM_PLUS_MINUS,
            ZOOM_PAGE_UP_DOWN,
        )
    }
}
