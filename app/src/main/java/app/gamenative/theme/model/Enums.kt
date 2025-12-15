package app.gamenative.theme.model

/**
 * Selection behavior for containers.
 */
enum class SelectionMode {
    /** Focus remains fixed; items move under a stationary selection cursor. */
    STATIONARY,
    /** Focus moves with selection; typical list/grid behavior. */
    MOVING
}

/**
 * Primary axis directions used by carousels and navigation.
 */
enum class Direction {
    LEFT,
    RIGHT,
    UP,
    DOWN
}

/**
 * Supported media kinds in the theme system.
 */
enum class MediaKind {
    IMAGE,
    VIDEO
}

/**
 * Standard UI state names used by the state/transition system.
 */
enum class StandardState {
    NORMAL,
    FOCUSED,
    SELECTED,
    PRESSED,
    DISABLED
}

/**
 * Preload policies for video media.
 */
enum class VideoPreloadPolicy {
    NONE,
    METADATA,
    AUTO
}

/** Animation easing options for transitions. */
enum class Easing {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT
}

/**
 * Value types for variables.
 */
enum class ValueType {
    STRING,
    INT,
    FLOAT,
    BOOL,
    COLOR
}

/**
 * Anchor point for positioning elements (both fixed elements and card layers).
 * Determines which point of the element the x,y coordinates refer to.
 */
enum class Anchor {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT;

    companion object {
        /**
         * Parse anchor from string value (case-insensitive, underscores optional).
         * Returns TOP_LEFT as default if value is null or unrecognized.
         */
        fun fromString(value: String?): Anchor = when (value?.lowercase()?.replace("_", "")) {
            "topleft" -> TOP_LEFT
            "topcenter" -> TOP_CENTER
            "topright" -> TOP_RIGHT
            "centerleft" -> CENTER_LEFT
            "center" -> CENTER
            "centerright" -> CENTER_RIGHT
            "bottomleft" -> BOTTOM_LEFT
            "bottomcenter" -> BOTTOM_CENTER
            "bottomright" -> BOTTOM_RIGHT
            else -> TOP_LEFT
        }
    }
}