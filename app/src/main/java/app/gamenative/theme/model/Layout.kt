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
        /** Number of columns. */
        val columns: Int,
        /** Number of rows (optional if content is dynamic). */
        val rows: Int? = null,
        /** Size of each grid cell. */
        val cellSize: DimSize,
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
    ) : LayoutNode()
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
