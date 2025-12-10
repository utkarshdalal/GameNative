package app.gamenative.theme.model

/**
 * A container for fixed (static) UI elements that don't scroll with content.
 * Multiple containers can be defined to group related elements.
 */
data class FixedContainer(
    /** Unique identifier for this container. */
    val id: String,
    /** List of fixed elements in this container. */
    val elements: List<FixedElement> = emptyList(),
    /** Background color (ARGB) for this container, null for transparent. */
    val backgroundColor: Int? = null,
    /** Height of the container in pixels, null for auto-size based on content. */
    val height: Float? = null,
)

/**
 * Base class for fixed UI elements (predefined slots that the app fills with functionality).
 */
sealed class FixedElement {
    /** Position of the element. */
    abstract val position: DimOffset
    /** Anchor point for positioning. */
    abstract val anchor: Anchor

    /**
     * App header showing app name, theme name, and game count.
     */
    data class Header(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        /** Text color (ARGB). */
        val textColor: Int = 0xFFFFFFFF.toInt(),
        /** Whether to show the app name. */
        val showAppName: Boolean = true,
        /** Whether to show the theme name. */
        val showThemeName: Boolean = true,
        /** Whether to show the game count. */
        val showGameCount: Boolean = true,
    ) : FixedElement()

    /**
     * Search bar for filtering games.
     */
    data class SearchBar(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        /** Size of the search bar. */
        val size: DimSize,
        /** Background color (ARGB), null for default. */
        val backgroundColor: Int? = null,
        /** Corner radius in pixels. */
        val borderRadius: Float = 8f,
    ) : FixedElement()

    /**
     * User profile/account button.
     */
    data class ProfileButton(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.TOP_RIGHT,
        /** Size of the button in pixels. */
        val size: Float = 40f,
    ) : FixedElement()

    /**
     * Filter button to open the filter bottom sheet.
     */
    data class FilterButton(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.BOTTOM_RIGHT,
        /** Whether to show expanded text label. */
        val expanded: Boolean = true,
    ) : FixedElement()

    /**
     * Add button to add custom games.
     */
    data class AddButton(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.BOTTOM_RIGHT,
    ) : FixedElement()
}

/**
 * Anchor point for positioning fixed elements.
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
    BOTTOM_RIGHT,
}

