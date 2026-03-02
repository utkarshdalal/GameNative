package app.gamenative.theme.model

/**
 * Represents a responsive breakpoint that can override variable values
 * based on screen orientation or width.
 *
 * Breakpoints are evaluated at render time and applied in order (later breakpoints
 * override earlier ones, similar to CSS cascade).
 *
 * Simple usage (recommended):
 * ```xml
 * <breakpoint orientation="portrait">
 *     <var name="searchBarX" value="16"/>
 * </breakpoint>
 * ```
 *
 * Advanced usage (pixel-based):
 * ```xml
 * <breakpoint minWidth="600" maxWidth="900">
 *     <var name="columns" value="3"/>
 * </breakpoint>
 * ```
 */
data class Breakpoint(
    /** Simple orientation-based breakpoint. Takes precedence over minWidth/maxWidth if set. */
    val orientation: Orientation? = null,
    /** Minimum screen width in dp for this breakpoint to apply. */
    val minWidth: Int? = null,
    /** Maximum screen width in dp for this breakpoint to apply. */
    val maxWidth: Int? = null,
    /** Variable overrides when this breakpoint matches. */
    val variables: Map<String, String> = emptyMap()
) {
    /**
     * Check if this breakpoint matches the current screen configuration.
     *
     * @param isPortrait True if screen height > screen width
     * @param screenWidthDp Current screen width in dp
     */
    fun matches(isPortrait: Boolean, screenWidthDp: Int): Boolean {
        // If orientation is specified, use simple orientation matching
        if (orientation != null) {
            return when (orientation) {
                Orientation.PORTRAIT -> isPortrait
                Orientation.LANDSCAPE -> !isPortrait
            }
        }

        // Otherwise use pixel-based breakpoints
        val minMatches = minWidth == null || screenWidthDp >= minWidth
        val maxMatches = maxWidth == null || screenWidthDp <= maxWidth
        return minMatches && maxMatches
    }
}

/**
 * Screen orientation for breakpoint matching.
 */
enum class Orientation {
    /** Portrait mode: screen height > screen width */
    PORTRAIT,
    /** Landscape mode: screen width >= screen height */
    LANDSCAPE;

    companion object {
        fun fromString(value: String?): Orientation? = when (value?.lowercase()) {
            "portrait" -> PORTRAIT
            "landscape" -> LANDSCAPE
            else -> null
        }
    }
}

/**
 * Visibility condition for UI elements.
 * Controls when an element should be displayed based on screen orientation.
 */
enum class Visibility {
    /** Always visible regardless of orientation (default) */
    ALWAYS,
    /** Only visible in portrait mode */
    PORTRAIT,
    /** Only visible in landscape mode */
    LANDSCAPE;

    companion object {
        fun fromString(value: String?): Visibility = when (value?.lowercase()) {
            "portrait" -> PORTRAIT
            "landscape" -> LANDSCAPE
            else -> ALWAYS
        }
    }

    /**
     * Check if element should be visible given the current orientation.
     */
    fun isVisible(isPortrait: Boolean): Boolean = when (this) {
        ALWAYS -> true
        PORTRAIT -> isPortrait
        LANDSCAPE -> !isPortrait
    }
}

