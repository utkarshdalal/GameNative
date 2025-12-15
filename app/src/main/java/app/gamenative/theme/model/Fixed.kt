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
    /** 
     * Visibility condition based on orientation. 
     * When set, applies to this container and all its children.
     * Child elements can still override with their own visibility.
     */
    val visibility: Visibility = Visibility.ALWAYS,
)

/**
 * Base class for fixed UI elements (predefined slots that the app fills with functionality).
 */
sealed class FixedElement {
    /** Position of the element. */
    abstract val position: DimOffset
    /** Anchor point for positioning. */
    abstract val anchor: Anchor
    /** Visibility condition based on orientation. */
    abstract val visibility: Visibility

    /**
     * App header showing app name, theme name, and game count.
     */
    data class Header(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
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
        override val visibility: Visibility = Visibility.ALWAYS,
        /** Size of the search bar. */
        val size: DimSize,
        /** Background color (ARGB), null for default. */
        val backgroundColor: Int? = null,
        /** Corner radius in pixels. */
        val borderRadius: Float = 8f,
        /** If true, shows only an icon that expands when focused/has text. */
        val collapsible: Boolean = false,
    ) : FixedElement()

    /**
     * User profile/account button.
     */
    data class ProfileButton(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.TOP_RIGHT,
        override val visibility: Visibility = Visibility.ALWAYS,
        /** Size of the button container in pixels. */
        val size: Float = 48f,
        /** Size of the icon inside the button in pixels. */
        val iconSize: Float = 24f,
        /** Padding inside the button in pixels. */
        val padding: Float = 8f,
        /** Background color (ARGB), null for default. */
        val backgroundColor: Int? = null,
        /** Corner radius in pixels. */
        val cornerRadius: Float = 12f,
    ) : FixedElement()

    /**
     * Filter button to open the filter bottom sheet.
     */
    data class FilterButton(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.BOTTOM_RIGHT,
        override val visibility: Visibility = Visibility.ALWAYS,
        /** Whether to show expanded text label. */
        val expanded: Boolean = true,
    ) : FixedElement()

    /**
     * Add button to add custom games.
     */
    data class AddButton(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.BOTTOM_RIGHT,
        override val visibility: Visibility = Visibility.ALWAYS,
    ) : FixedElement()
}

