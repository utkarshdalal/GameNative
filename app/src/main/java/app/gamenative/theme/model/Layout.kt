package app.gamenative.theme.model

/**
 * Root theme definition aggregating manifest, variables, cards, and layout elements.
 * This is a pure data model; no parsing or rendering logic is included here.
 */
data class ThemeDefinition(
    /** Theme manifest with identity and compatibility fields. */
    val manifest: Manifest,
    /** Declared variables that can be referenced by bindings. */
    val variables: List<Variable> = emptyList(),
    /** Responsive breakpoints for orientation/size-based variable overrides. */
    val breakpoints: List<Breakpoint> = emptyList(),
    /** Card definitions for rendering individual items (games). */
    val cards: List<Card> = emptyList(),
    /** 
     * Ordered list of layout elements (fixed containers and content).
     * Rendered in declaration order unless zIndex overrides it.
     */
    val layoutElements: List<LayoutElement> = emptyList(),
) {
    // Convenience accessors for backwards compatibility
    /** All fixed containers from layout elements. */
    val fixedContainers: List<FixedContainer>
        get() = layoutElements.filterIsInstance<LayoutElement.Fixed>().map { it.container }
    
    /** The main layout node (Grid/Carousel), or null if not present. */
    val layout: LayoutNode?
        get() = layoutElements.filterIsInstance<LayoutElement.Content>().firstOrNull()?.node
}

/**
 * A single element in the layout, either a fixed container or content (grid/carousel).
 * Elements are rendered in declaration order unless zIndex overrides it.
 */
sealed class LayoutElement {
    /** Optional z-index for explicit z-ordering. Null = use declaration order. */
    abstract val zIndex: Int?
    /** Declaration order index (set during parsing). Used for stable sorting. */
    abstract val declarationOrder: Int
    
    /**
     * A fixed container with UI elements (backgrounds, buttons, etc.).
     */
    data class Fixed(
        val container: FixedContainer,
        override val zIndex: Int? = null,
        override val declarationOrder: Int = 0,
    ) : LayoutElement()
    
    /**
     * The main content area (Grid or Carousel).
     */
    data class Content(
        val node: LayoutNode,
        override val zIndex: Int? = null,
        override val declarationOrder: Int = 0,
    ) : LayoutElement()
}

/**
 * A node in the layout tree.
 */
sealed class LayoutNode {

    /**
     * Absolute positioning canvas. Children are placed at explicit positions within [size].
     */
    data class Canvas(
        /** Size of the canvas box. */
        val size: DimSize,
        /** Children positioned absolutely within the canvas. */
        val children: List<CanvasChild> = emptyList(),
    ) : LayoutNode()

    /**
     * Uniform grid layout.
     */
    data class Grid(
        /** Number of columns. Null = adaptive based on cellWidth (recommended). */
        val columns: Int? = null,
        /** Number of rows (optional if content is dynamic). */
        val rows: Int? = null,
        /** Minimum width of each grid cell. Used for adaptive column calculation. */
        val cellWidth: Dimension,
        /** Height of each grid cell. If null, uses aspectRatio or card's canvas height. */
        val cellHeight: Dimension? = null,
        /** Aspect ratio (width/height) for automatic cell height calculation. e.g., 2.14 for hero (460:215), 0.67 for capsule (2:3). */
        val aspectRatio: Float? = null,
        /** Horizontal spacing between cells. */
        val hSpacing: Float = 0f,
        /** Vertical spacing between cells. */
        val vSpacing: Float = 0f,
        /** Selection behavior (stationary vs moving). */
        val selectionMode: SelectionMode = SelectionMode.MOVING,
        /** Card to use for grid items. */
        val itemCard: String,
        /** Content padding from top. */
        val contentPaddingTop: Float = 0f,
        /** Content padding from bottom. */
        val contentPaddingBottom: Float = 0f,
        /** Content padding from start (left in LTR). */
        val contentPaddingStart: Float = 0f,
        /** Content padding from end (right in LTR). */
        val contentPaddingEnd: Float = 0f,
        /** Optional separator rendered between items. Contains static layers (no game bindings). */
        val separator: GridSeparator? = null,
        /** Vertical alignment of items within each cell. */
        val verticalAlign: VerticalAlign = VerticalAlign.TOP,
    ) : LayoutNode()

    /**
     * Carousel (row or column) layout.
     */
    data class Carousel(
        /** Scroll direction for the carousel. */
        val direction: Direction = Direction.RIGHT,
        /** Scroll orientation: horizontal or vertical. */
        val orientation: CarouselOrientation = CarouselOrientation.HORIZONTAL,
        /** Size of each item in the carousel. */
        val itemSize: DimSize,
        /** Spacing between items. */
        val itemSpacing: Float = 0f,
        /** Selection behavior (stationary vs moving). */
        val selectionMode: SelectionMode = SelectionMode.STATIONARY,
        /** Card to use for carousel items. */
        val itemCard: String,
        /** Optional number of items visible at once (page size). */
        val pageSize: Int? = null,
        /** Whether the carousel centers on the focused item with snap behavior. */
        val centerFocus: Boolean = false,
        /** Scale factor for the focused item (1.0 = no scaling). */
        val focusedScale: Float = 1.0f,
        /** Vertical alignment within parent container (for horizontal carousels). */
        val verticalAlign: VerticalAlign = VerticalAlign.TOP,
        /** Vertical offset from the aligned position (positive = down, for horizontal carousels). */
        val verticalOffset: Dimension = Dimension.Px(0f),
        /** Horizontal alignment within parent container (for vertical carousels). */
        val horizontalAlign: HorizontalAlign = HorizontalAlign.START,
        /** Horizontal offset from the aligned position (positive = right, for vertical carousels). */
        val horizontalOffset: Dimension = Dimension.Px(0f),
        /** X offset applied to the focused item (positive = right). */
        val focusedOffsetX: Float = 0f,
        /** Y offset applied to the focused item (positive = down). */
        val focusedOffsetY: Float = 0f,
        /** Extra spacing around the focused item to account for scaling (added to itemSpacing). */
        val focusedSpacing: Float = 0f,
        /** Background image binding for focused item (e.g., "@{game.hero}"). Null = no background. */
        val focusedBackground: StringOrBinding? = null,
        /** Opacity for the focused background image (0.0-1.0). */
        val backgroundOpacity: Float = 0.3f,
        /** Duration in ms for background crossfade transition. */
        val backgroundTransitionSpeed: Int = 400,
    ) : LayoutNode()
}

/**
 * Vertical alignment options for layout elements.
 */
enum class VerticalAlign {
    TOP,
    CENTER,
    BOTTOM;

    companion object {
        fun fromString(value: String?): VerticalAlign = when (value?.lowercase()) {
            "center" -> CENTER
            "bottom" -> BOTTOM
            else -> TOP
        }
    }
}

/**
 * Horizontal alignment options for layout elements.
 */
enum class HorizontalAlign {
    START,
    CENTER,
    END;

    companion object {
        fun fromString(value: String?): HorizontalAlign = when (value?.lowercase()) {
            "center" -> CENTER
            "end", "right" -> END
            else -> START
        }
    }
}

/**
 * Carousel scroll orientation.
 */
enum class CarouselOrientation {
    HORIZONTAL,
    VERTICAL;

    companion object {
        fun fromString(value: String?): CarouselOrientation = when (value?.lowercase()) {
            "vertical" -> VERTICAL
            else -> HORIZONTAL
        }
    }
}

/**
 * A child placed on a [LayoutNode.Canvas].
 */
data class CanvasChild(
    /** Card to render at this position. */
    val cardId: String,
    /** Absolute position within the parent canvas. */
    val position: DimOffset,
    /** Optional explicit size; otherwise the card's canvas size is used. */
    val size: DimSize? = null,
)

/**
 * A separator rendered between grid items.
 * Contains static layers (rect, image, text) without game bindings.
 */
data class GridSeparator(
    /** Height of the separator content area. */
    val height: Dimension,
    /** Layers to render in the separator (rect, image, text only - no game bindings). */
    val layers: List<Layer> = emptyList(),
    /** Margin from top of separator. */
    val marginTop: Float = 0f,
    /** Margin from bottom of separator. */
    val marginBottom: Float = 0f,
    /** Margin from start (left in LTR). */
    val marginStart: Float = 0f,
    /** Margin from end (right in LTR). */
    val marginEnd: Float = 0f,
)
