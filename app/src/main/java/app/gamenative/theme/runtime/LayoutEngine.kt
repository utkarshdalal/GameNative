package app.gamenative.theme.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.gamenative.theme.model.*
import app.gamenative.theme.runtime.layers.RenderLayer

/**
 * Simple binding context to resolve literal-or-binding values at render time.
 * This is a hook; a real BindingEngine can be introduced later.
 */
interface BindingContext {
    fun resolveString(value: StringOrBinding): String?
    fun resolveFloat(value: FloatOrBinding): Float?
    fun resolveInt(value: IntOrBinding): Int?
}

/** A trivial binding context for previews/tests backed by string/number maps. */
class MapBindingContext(
    private val strings: Map<String, String> = emptyMap(),
    private val floats: Map<String, Float> = emptyMap(),
    private val ints: Map<String, Int> = emptyMap(),
) : BindingContext {
    override fun resolveString(value: StringOrBinding): String? = when (value) {
        is StringOrBinding.Literal -> value.value
        is StringOrBinding.Ref -> strings[value.binding.path]
    }
    override fun resolveFloat(value: FloatOrBinding): Float? = when (value) {
        is FloatOrBinding.Literal -> value.value
        is FloatOrBinding.Ref -> floats[value.binding.path]
    }
    override fun resolveInt(value: IntOrBinding): Int? = when (value) {
        is IntOrBinding.Literal -> value.value
        is IntOrBinding.Ref -> ints[value.binding.path]
    }
}

/** Convert Dimension to Dp relative to the given max width/height. */
private fun dimToDp(d: Dimension, maxW: Dp, maxH: Dp): Dp = when (d) {
    is Dimension.Px -> d.value.dp // treat px as dp for simplicity in preview
    is Dimension.RelW -> maxW * d.fraction
    is Dimension.RelH -> maxH * d.fraction
}

/** Compute positioned size and offset for a child within a parent box. */
private data class BoxPlacement(val size: DpSize, val offsetX: Dp, val offsetY: Dp)

@Composable
private fun computePlacement(
    parentSize: DpSize,
    pos: DimOffset,
    size: DimSize?,
    defaultSize: DpSize,
    anchor: Anchor,
): BoxPlacement {
    val w = size?.let { dimToDp(it.width, parentSize.width, parentSize.height) } ?: defaultSize.width
    val h = size?.let { dimToDp(it.height, parentSize.width, parentSize.height) } ?: defaultSize.height
    val x = dimToDp(pos.x, parentSize.width, parentSize.height)
    val y = dimToDp(pos.y, parentSize.width, parentSize.height)
    val offX = when (anchor) {
        Anchor.TOP_LEFT, Anchor.CENTER_LEFT, Anchor.BOTTOM_LEFT -> x
        Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> (parentSize.width - w) / 2 + x
        Anchor.TOP_RIGHT, Anchor.CENTER_RIGHT, Anchor.BOTTOM_RIGHT -> parentSize.width - x - w
    }
    val offY = when (anchor) {
        Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT -> y
        Anchor.CENTER_LEFT, Anchor.CENTER, Anchor.CENTER_RIGHT -> (parentSize.height - h) / 2 + y
        Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT -> parentSize.height - y - h
    }
    return BoxPlacement(size = DpSize(w, h), offsetX = offX, offsetY = offY)
}

/** Render a full layout tree. */
@Composable
fun ThemeLayout(
    layout: LayoutNode,
    cards: Map<String, Card>,
    binding: BindingContext,
    modifier: Modifier = Modifier,
    anchor: Anchor = Anchor.TOP_LEFT,
    viewportSize: DpSize = DpSize(1080.dp, 640.dp),
    itemBindingProvider: ((Int) -> BindingContext)? = null,
) {
    // Use the actual available space from parent so themes scale to screen size.
    BoxWithConstraints(modifier = modifier) {
        val vp = DpSize(maxWidth, maxHeight)
        when (layout) {
            is LayoutNode.Canvas -> CanvasLayout(layout, cards, binding, Modifier.fillMaxSize(), anchor, vp)
            is LayoutNode.Grid -> GridLayout(layout, cards, binding, Modifier.fillMaxSize(), anchor, vp, itemBindingProvider)
            is LayoutNode.Carousel -> CarouselLayout(layout, cards, binding, Modifier.fillMaxSize(), anchor, vp, itemBindingProvider)
        }
    }
}

@Composable
private fun CanvasLayout(
    node: LayoutNode.Canvas,
    cards: Map<String, Card>,
    binding: BindingContext,
    modifier: Modifier,
    anchor: Anchor,
    viewportSize: DpSize,
) {
    val w = dimToDp(node.size.width, viewportSize.width, viewportSize.height)
    val h = dimToDp(node.size.height, viewportSize.width, viewportSize.height)
    val canvasSize = DpSize(w, h)
    Box(modifier = modifier.size(w, h)) {
        node.children.forEach { child ->
            val card = cards[child.cardId] ?: return@forEach
            val place = computePlacement(canvasSize, child.position, child.size, card.canvas.toDpSize(canvasSize), anchor)
            Box(
                modifier = Modifier
                    .offset(x = place.offsetX, y = place.offsetY)
                    .size(place.size.width, place.size.height)
            ) {
                RenderCard(card, binding, anchor, canvasSize)
            }
        }
    }
}

@Composable
private fun GridLayout(
    node: LayoutNode.Grid,
    cards: Map<String, Card>,
    binding: BindingContext,
    modifier: Modifier,
    anchor: Anchor,
    viewportSize: DpSize,
    itemBindingProvider: ((Int) -> BindingContext)? = null,
) {
    val card = cards[node.itemCard] ?: return
    val cellW = dimToDp(node.cellWidth, viewportSize.width, viewportSize.height)
    // If cellHeight not specified, use the card's canvas height
    val cellH = node.cellHeight?.let { dimToDp(it, viewportSize.width, viewportSize.height) }
        ?: dimToDp(card.canvas.height, viewportSize.width, viewportSize.height)
    val hSpace = node.hSpacing.dp
    val vSpace = node.vSpacing.dp
    // Default to 1 column if not specified
    val columns = node.columns ?: 1
    val rows = node.rows ?: 3
    Column(modifier = modifier) {
        repeat(rows) { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(columns) { c ->
                    val index = r * columns + c
                    val itemBinding = itemBindingProvider?.invoke(index) ?: binding
                    Box(modifier = Modifier.size(cellW, cellH)) {
                        RenderCard(card, itemBinding, anchor, DpSize(cellW, cellH))
                    }
                    if (c != columns - 1) Spacer(Modifier.width(hSpace))
                }
            }
            if (r != rows - 1) Spacer(Modifier.height(vSpace))
        }
    }
}

@Composable
private fun CarouselLayout(
    node: LayoutNode.Carousel,
    cards: Map<String, Card>,
    binding: BindingContext,
    modifier: Modifier,
    anchor: Anchor,
    viewportSize: DpSize,
    itemBindingProvider: ((Int) -> BindingContext)? = null,
) {
    val card = cards[node.itemCard] ?: return
    val itemW = dimToDp(node.itemSize.width, viewportSize.width, viewportSize.height)
    val itemH = dimToDp(node.itemSize.height, viewportSize.width, viewportSize.height)
    val space = node.itemSpacing.dp
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val count = node.pageSize ?: 5
        repeat(count) { i ->
            val itemBinding = itemBindingProvider?.invoke(i) ?: binding
            Box(modifier = Modifier.size(itemW, itemH)) {
                RenderCard(card, itemBinding, anchor, DpSize(itemW, itemH))
            }
            if (i != count - 1) Spacer(Modifier.width(space))
        }
    }
}

@Composable
private fun RenderCard(
    card: Card,
    binding: BindingContext,
    anchor: Anchor = Anchor.TOP_LEFT,
    parentSize: DpSize,
) {
    Box(modifier = Modifier.size(parentSize.width, parentSize.height)) {
        card.layers.forEach { layer ->
            RenderLayer(layer, parentSize, binding, anchor)
        }
    }
}

// -- FocusEngine integration helpers --
internal fun gridFocusConfig(node: LayoutNode.Grid): FocusEngine.Config = FocusEngine.Config(
    rows = node.rows ?: 1,
    cols = node.columns ?: 1,
    wrapX = true,
    wrapY = false,
    snapToCell = true,
    pageSize = null,
    selectionMode = node.selectionMode,
    centeredSelection = false,
)

internal fun carouselFocusConfig(node: LayoutNode.Carousel): FocusEngine.Config = FocusEngine.Config(
    rows = 1,
    cols = maxOf(1, node.pageSize ?: 1),
    wrapX = true,
    wrapY = false,
    snapToCell = true,
    pageSize = node.pageSize,
    selectionMode = node.selectionMode,
    centeredSelection = (node.selectionMode == SelectionMode.STATIONARY)
)

@Composable
private fun DimSize.toDpSize(parent: DpSize): DpSize = DpSize(
    width = when (val w = this.width) {
        is Dimension.Px -> with(LocalDensity.current) { w.value.dp }
        is Dimension.RelW -> parent.width * w.fraction
        is Dimension.RelH -> parent.height * w.fraction
    },
    height = when (val h = this.height) {
        is Dimension.Px -> with(LocalDensity.current) { h.value.dp }
        is Dimension.RelW -> parent.width * h.fraction
        is Dimension.RelH -> parent.height * h.fraction
    }
)

// --- Preview ---

@Preview(widthDp = 1080, heightDp = 640)
@Composable
fun ThemeLayoutPreview_Canvas() {
    val card = Card(
        id = "gameCard",
        canvas = DimSize(Dimension.Px(320f), Dimension.Px(180f)),
        layers = listOf(
            Layer.RectLayer(
                position = DimOffset(Dimension.Px(0f), Dimension.Px(0f)),
                size = DimSize(Dimension.RelW(1f), Dimension.RelH(1f)),
                opacity = FloatOrBinding.Literal(1f),
                color = IntOrBinding.Literal(0xFF2E7D32.toInt()),
                cornerRadius = "12"
            ),
            Layer.TextLayer(
                position = DimOffset(Dimension.Px(12f), Dimension.Px(12f)),
                size = null,
                opacity = null,
                text = StringOrBinding.Literal("Game Title"),
                color = IntOrBinding.Literal(0xFFFFFFFF.toInt()),
                textSize = FloatOrBinding.Literal(20f),
                maxLines = 1
            )
        )
    )
    val layout = LayoutNode.Canvas(
        size = DimSize(Dimension.Px(800f), Dimension.Px(480f)),
        children = listOf(
            CanvasChild("gameCard", DimOffset(Dimension.Px(40f), Dimension.Px(40f))),
            CanvasChild("gameCard", DimOffset(Dimension.Px(360f), Dimension.Px(40f)))
        )
    )
    val binding = remember { MapBindingContext() }
    ThemeLayout(layout, mapOf(card.id to card), binding, modifier = Modifier.background(Color(0xFF111111)))
}
