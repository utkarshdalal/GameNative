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
    /** 
     * Padding in CSS-style shorthand: "all" or "top right bottom left" (1-4 values).
     * - "8" = 8px all sides
     * - "8 16" = 8px top/bottom, 16px left/right
     * - "8 16 8 16" = top, right, bottom, left
     */
    val padding: String? = null,
    /** Corner radius in pixels for the container background. */
    val cornerRadius: Float = 0f,
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
    
    // Highlight styling properties for controller navigation (theme-only feature)
    /** Highlight border color (ARGB), null = use system primary. */
    abstract val highlightColor: Int?
    /** Highlight border opacity (0.0 - 1.0). */
    abstract val highlightOpacity: Float
    /** Highlight border width in pixels. */
    abstract val highlightBorderWidth: Float
    /** Highlight transition animation duration in milliseconds. */
    abstract val highlightTransitionSpeed: Int
    
    // Navigation configuration for controller navigation
    /** 
     * Custom navigation ID for this element. Used for navigation references (navigateUp/Down/Left/Right).
     * If null, an auto-generated ID is used internally, but cannot be referenced by other elements.
     */
    abstract val navigationId: String?
    /** Element navigationId to navigate to when pressing UP, null = use spatial navigation. */
    abstract val navigateUp: String?
    /** Element navigationId to navigate to when pressing DOWN, null = use spatial navigation. */
    abstract val navigateDown: String?
    /** Element navigationId to navigate to when pressing LEFT, null = use spatial navigation. */
    abstract val navigateLeft: String?
    /** Element navigationId to navigate to when pressing RIGHT, null = use spatial navigation. */
    abstract val navigateRight: String?

    /**
     * App header showing app name, theme name, and game count.
     */
    data class Header(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
        override val highlightColor: Int? = null,
        override val highlightOpacity: Float = 0.8f,
        override val highlightBorderWidth: Float = 2f,
        override val highlightTransitionSpeed: Int = 200,
        override val navigationId: String? = null,
        override val navigateUp: String? = null,
        override val navigateDown: String? = null,
        override val navigateLeft: String? = null,
        override val navigateRight: String? = null,
        /** Text color (ARGB). */
        val textColor: Int = 0xFFFFFFFF.toInt(),
        /** Whether to show the app name. */
        val showAppName: Boolean = true,
        /** Whether to show the theme name. */
        val showThemeName: Boolean = true,
        /** Whether to show the game count. */
        val showGameCount: Boolean = true,
        /** Optional explicit size for the header area. */
        val size: DimSize? = null,
        /** Background color (ARGB), null for transparent. */
        val backgroundColor: Int? = null,
        /** Corner radius in pixels for background. */
        val cornerRadius: Float = 0f,
        /** Internal padding in pixels. */
        val padding: Float = 8f,
        /** Text size for header text elements in pixels. */
        val textSize: Float = 14f,
        /** Font weight: "normal", "bold", "medium", "semibold", etc. */
        val fontWeight: String = "bold",
    ) : FixedElement()

    /**
     * Search bar for filtering games.
     */
    data class SearchBar(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
        override val highlightColor: Int? = null,
        override val highlightOpacity: Float = 0.8f,
        override val highlightBorderWidth: Float = 2f,
        override val highlightTransitionSpeed: Int = 200,
        override val navigationId: String? = null,
        override val navigateUp: String? = null,
        override val navigateDown: String? = null,
        override val navigateLeft: String? = null,
        override val navigateRight: String? = null,
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
        override val highlightColor: Int? = null,
        override val highlightOpacity: Float = 0.8f,
        override val highlightBorderWidth: Float = 2f,
        override val highlightTransitionSpeed: Int = 200,
        override val navigationId: String? = null,
        override val navigateUp: String? = null,
        override val navigateDown: String? = null,
        override val navigateLeft: String? = null,
        override val navigateRight: String? = null,
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
        override val highlightColor: Int? = null,
        override val highlightOpacity: Float = 0.8f,
        override val highlightBorderWidth: Float = 2f,
        override val highlightTransitionSpeed: Int = 200,
        override val navigationId: String? = null,
        override val navigateUp: String? = null,
        override val navigateDown: String? = null,
        override val navigateLeft: String? = null,
        override val navigateRight: String? = null,
        /** Whether to show expanded text label. */
        val expanded: Boolean = true,
        /** Button size in pixels (diameter for circular, side for square). */
        val size: Float = 56f,
        /** Icon size in pixels inside the button. */
        val iconSize: Float = 24f,
        /** Background color (ARGB), null uses Material theme primary. */
        val backgroundColor: Int? = null,
        /** Icon tint color (ARGB), null uses Material theme onPrimary. */
        val iconColor: Int? = null,
        /** Corner radius in pixels, 0 = square, size/2 = circular. */
        val cornerRadius: Float = 16f,
    ) : FixedElement()

    /**
     * Add button to add custom games.
     */
    data class AddButton(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.BOTTOM_RIGHT,
        override val visibility: Visibility = Visibility.ALWAYS,
        override val highlightColor: Int? = null,
        override val highlightOpacity: Float = 0.8f,
        override val highlightBorderWidth: Float = 2f,
        override val highlightTransitionSpeed: Int = 200,
        override val navigationId: String? = null,
        override val navigateUp: String? = null,
        override val navigateDown: String? = null,
        override val navigateLeft: String? = null,
        override val navigateRight: String? = null,
        /** Button size in pixels (diameter for circular, side for square). */
        val size: Float = 56f,
        /** Icon size in pixels inside the button. */
        val iconSize: Float = 24f,
        /** Background color (ARGB), null uses Material theme primary. */
        val backgroundColor: Int? = null,
        /** Icon tint color (ARGB), null uses Material theme onPrimary. */
        val iconColor: Int? = null,
        /** Corner radius in pixels, 0 = square, size/2 = circular. */
        val cornerRadius: Float = 16f,
    ) : FixedElement()

    /**
     * Static image element (not bound to game data).
     * Use for decorative images, logos, backgrounds, etc.
     */
    data class Image(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
        override val highlightColor: Int? = null,
        override val highlightOpacity: Float = 0.8f,
        override val highlightBorderWidth: Float = 2f,
        override val highlightTransitionSpeed: Int = 200,
        override val navigationId: String? = null,
        override val navigateUp: String? = null,
        override val navigateDown: String? = null,
        override val navigateLeft: String? = null,
        override val navigateRight: String? = null,
        /** Size of the image (required). */
        val size: DimSize,
        /** Image source URL or asset path. */
        val src: String,
        /** How to scale the image: "cover", "contain", "fill", "none". */
        val scaleType: String = "cover",
        /** CSS-style corner radius (e.g., "8" or "8 8 0 0"). */
        val cornerRadius: String? = null,
        /** Opacity (0.0 - 1.0). */
        val opacity: Float = 1f,
    ) : FixedElement()

    /**
     * Static video element (not bound to game data).
     * Use for background videos, promotional content, etc.
     */
    data class Video(
        override val position: DimOffset,
        override val anchor: Anchor = Anchor.TOP_LEFT,
        override val visibility: Visibility = Visibility.ALWAYS,
        override val highlightColor: Int? = null,
        override val highlightOpacity: Float = 0.8f,
        override val highlightBorderWidth: Float = 2f,
        override val highlightTransitionSpeed: Int = 200,
        override val navigationId: String? = null,
        override val navigateUp: String? = null,
        override val navigateDown: String? = null,
        override val navigateLeft: String? = null,
        override val navigateRight: String? = null,
        /** Size of the video (required). */
        val size: DimSize,
        /** Video source URL or asset path. */
        val src: String,
        /** Poster/thumbnail image to show while loading. */
        val poster: String? = null,
        /** Whether to autoplay the video. */
        val autoplay: Boolean = false,
        /** Whether to loop the video. */
        val loop: Boolean = true,
        /** Whether to mute the video. */
        val muted: Boolean = true,
        /** CSS-style corner radius (e.g., "8" or "8 8 0 0"). */
        val cornerRadius: String? = null,
        /** Opacity (0.0 - 1.0). */
        val opacity: Float = 1f,
    ) : FixedElement()
}

