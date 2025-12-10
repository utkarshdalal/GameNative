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
