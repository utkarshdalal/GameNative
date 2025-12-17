package app.gamenative.theme.model

/**
 * Root theme definition aggregating manifest, variables, cards, fixed containers, and the layout tree.
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
    /** Fixed UI containers with app elements (header, search, etc.). */
    val fixedContainers: List<FixedContainer> = emptyList(),
    /** Root layout node describing the arrangement of cards on screen. */
    val layout: LayoutNode,
)

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
        /** Scale factor for the highlighted/focused item (1.0 = no scaling). */
        val highlightScale: Float = 1.0f,
        /** Vertical alignment within parent container. */
        val verticalAlign: VerticalAlign = VerticalAlign.TOP,
        /** Vertical offset from the aligned position (positive = down). */
        val verticalOffset: Dimension = Dimension.Px(0f),
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
