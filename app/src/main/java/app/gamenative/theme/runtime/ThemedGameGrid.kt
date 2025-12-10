package app.gamenative.theme.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.gamenative.theme.model.Card as ThemeCard
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.data.LibraryItem
import app.gamenative.theme.model.*
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

/**
 * A themed game grid that renders library items using the theme engine's cards.
 * Unlike the basic ThemeLayout, this component:
 * - Supports scrolling via LazyVerticalGrid
 * - Handles click events for navigation
 * - Uses all items from the data source
 * - Supports focus for controller navigation
 */
@Composable
fun ThemedGameGrid(
    items: List<LibraryItem>,
    gridConfig: LayoutNode.Grid,
    card: ThemeCard,
    listState: LazyGridState,
    modifier: Modifier = Modifier,
    onItemClick: (LibraryItem) -> Unit = {},
    onItemFocus: (LibraryItem) -> Unit = {},
    bindingProvider: (LibraryItem) -> Map<String, String> = { emptyMap() },
) {
    val cellWidthDp = (gridConfig.cellSize.width as? Dimension.Px)?.value?.dp ?: 150.dp
    val cellHeightDp = (gridConfig.cellSize.height as? Dimension.Px)?.value?.dp ?: 225.dp
    val hSpacing = gridConfig.hSpacing.dp
    val vSpacing = gridConfig.vSpacing.dp
    
    // Use content padding from grid config, with defaults
    val paddingTop = if (gridConfig.contentPaddingTop > 0) gridConfig.contentPaddingTop.dp else 80.dp
    val paddingBottom = if (gridConfig.contentPaddingBottom > 0) gridConfig.contentPaddingBottom.dp else 72.dp
    val paddingStart = if (gridConfig.contentPaddingStart > 0) gridConfig.contentPaddingStart.dp else 16.dp
    val paddingEnd = if (gridConfig.contentPaddingEnd > 0) gridConfig.contentPaddingEnd.dp else 16.dp

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = cellWidthDp),
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = paddingStart,
            end = paddingEnd,
            top = paddingTop,
            bottom = paddingBottom,
        ),
        horizontalArrangement = Arrangement.spacedBy(hSpacing),
        verticalArrangement = Arrangement.spacedBy(vSpacing),
    ) {
        items(
            items = items,
            key = { it.appId }
        ) { item ->
            ThemedGameTile(
                item = item,
                card = card,
                bindings = bindingProvider(item),
                cellSize = DpSize(cellWidthDp, cellHeightDp),
                onClick = { onItemClick(item) },
                onFocus = { onItemFocus(item) },
            )
        }
    }
}

/**
 * A single game tile rendered using theme card layers.
 */
@Composable
private fun ThemedGameTile(
    item: LibraryItem,
    card: ThemeCard,
    bindings: Map<String, String>,
    cellSize: DpSize,
    onClick: () -> Unit,
    onFocus: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    var imageLoadFailed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(cellSize.width, cellSize.height)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (isFocused) onFocus()
            }
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 3.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            )
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                } else Modifier
            )
    ) {
        // Render each layer from the card
        card.layers.forEach { layer ->
            RenderThemedLayer(
                layer = layer,
                bindings = bindings,
                parentSize = cellSize,
                onImageLoadFailed = { imageLoadFailed = true },
            )
        }

        // Fallback: Show title prominently if image failed
        if (imageLoadFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

/**
 * Render a single theme layer with proper binding resolution.
 */
@Composable
private fun BoxScope.RenderThemedLayer(
    layer: Layer,
    bindings: Map<String, String>,
    parentSize: DpSize,
    onImageLoadFailed: () -> Unit = {},
) {
    when (layer) {
        is Layer.ImageLayer -> {
            val src = resolveBinding(layer.source.src, bindings)
            val shape = parseCornerRadius(layer.cornerRadius)
            val x = (layer.position.x as? Dimension.Px)?.value?.dp ?: 0.dp
            val y = (layer.position.y as? Dimension.Px)?.value?.dp ?: 0.dp
            val w = (layer.size?.width as? Dimension.Px)?.value?.dp ?: parentSize.width
            val h = (layer.size?.height as? Dimension.Px)?.value?.dp ?: parentSize.height
            val alpha = resolveFloatBinding(layer.opacity, 1f)

            if (src.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .size(w, h)
                        .clip(shape)
                        .alpha(alpha)
                ) {
                    CoilImage(
                        imageModel = { src },
                        imageOptions = ImageOptions(
                            contentScale = ContentScale.Crop,
                            contentDescription = null,
                        ),
                        failure = {
                            onImageLoadFailed()
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF444444))
                            )
                        },
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF333333))
                            )
                        }
                    )
                }
            } else {
                // No image URL - show placeholder
                Box(
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .size(w, h)
                        .clip(shape)
                        .background(Color(0xFF555555))
                )
            }
        }

        is Layer.OverlayLayer -> {
            val x = (layer.position.x as? Dimension.Px)?.value?.dp ?: 0.dp
            val y = (layer.position.y as? Dimension.Px)?.value?.dp ?: 0.dp
            val w = (layer.size?.width as? Dimension.Px)?.value?.dp ?: parentSize.width
            val h = (layer.size?.height as? Dimension.Px)?.value?.dp ?: parentSize.height
            val shape = parseCornerRadius(layer.cornerRadius)
            val color = resolveIntBinding(layer.color, 0x66000000, bindings)
            val alpha = resolveFloatBinding(layer.opacity, 1f)

            Box(
                modifier = Modifier
                    .offset(x = x, y = y)
                    .size(w, h)
                    .clip(shape)
                    .alpha(alpha)
                    .background(Color(color))
            )
        }

        is Layer.TextLayer -> {
            val text = resolveBinding(layer.text, bindings)
            val x = (layer.position.x as? Dimension.Px)?.value?.dp ?: 0.dp
            val y = (layer.position.y as? Dimension.Px)?.value?.dp ?: 0.dp
            val w = (layer.size?.width as? Dimension.Px)?.value?.dp ?: parentSize.width
            val h = (layer.size?.height as? Dimension.Px)?.value?.dp ?: 24.dp
            val textSize = resolveFloatBinding(layer.textSize, 14f)
            val color = resolveIntBinding(layer.color, 0xFFFFFFFF.toInt(), bindings)
            val alpha = resolveFloatBinding(layer.opacity, 1f)

            // Treat textSize as sp directly (theme authors specify logical size)
            val textSizeSp = textSize.sp

            val textAlignment = when (layer.textAlign.lowercase()) {
                "center" -> androidx.compose.ui.text.style.TextAlign.Center
                "right" -> androidx.compose.ui.text.style.TextAlign.End
                else -> androidx.compose.ui.text.style.TextAlign.Start
            }
            val boxAlignment = when (layer.textAlign.lowercase()) {
                "center" -> Alignment.Center
                "right" -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }

            Box(
                modifier = Modifier
                    .offset(x = x, y = y)
                    .size(w, h)
                    .alpha(alpha),
                contentAlignment = boxAlignment
            ) {
                Text(
                    text = text,
                    color = Color(color),
                    fontSize = textSizeSp,
                    fontWeight = FontWeight.Medium,
                    maxLines = layer.maxLines ?: 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlignment,
                )
            }
        }

        is Layer.BorderLayer -> {
            val x = (layer.position.x as? Dimension.Px)?.value?.dp ?: 0.dp
            val y = (layer.position.y as? Dimension.Px)?.value?.dp ?: 0.dp
            val w = (layer.size?.width as? Dimension.Px)?.value?.dp ?: parentSize.width
            val h = (layer.size?.height as? Dimension.Px)?.value?.dp ?: parentSize.height
            val shape = parseCornerRadius(layer.cornerRadius)
            val borderWidth = resolveFloatBinding(layer.strokeWidth, 1f).dp
            val color = resolveIntBinding(layer.color, 0xFFFFFFFF.toInt(), bindings)
            val alpha = resolveFloatBinding(layer.opacity, 1f)

            Box(
                modifier = Modifier
                    .offset(x = x, y = y)
                    .size(w, h)
                    .alpha(alpha)
                    .border(width = borderWidth, color = Color(color), shape = shape)
            )
        }

        is Layer.ShadowLayer -> {
            // Shadows are complex in Compose - simplified implementation
            val x = (layer.position.x as? Dimension.Px)?.value?.dp ?: 0.dp
            val y = (layer.position.y as? Dimension.Px)?.value?.dp ?: 0.dp
            val w = (layer.size?.width as? Dimension.Px)?.value?.dp ?: parentSize.width
            val h = (layer.size?.height as? Dimension.Px)?.value?.dp ?: parentSize.height
            // Shadow layers are primarily decorative - skip for now in this implementation
        }

        is Layer.VideoLayer -> {
            // Video not supported in grid tiles - show poster or placeholder
            val x = (layer.position.x as? Dimension.Px)?.value?.dp ?: 0.dp
            val y = (layer.position.y as? Dimension.Px)?.value?.dp ?: 0.dp
            val w = (layer.size?.width as? Dimension.Px)?.value?.dp ?: parentSize.width
            val h = (layer.size?.height as? Dimension.Px)?.value?.dp ?: parentSize.height

            Box(
                modifier = Modifier
                    .offset(x = x, y = y)
                    .size(w, h)
                    .background(Color(0xFF303030)),
                contentAlignment = Alignment.Center
            ) {
                Text("VIDEO", color = Color.White, fontSize = 10.sp)
            }
        }

        is Layer.BackdropLayer -> {
            // Backdrop blur effects - simplified
            val x = (layer.position.x as? Dimension.Px)?.value?.dp ?: 0.dp
            val y = (layer.position.y as? Dimension.Px)?.value?.dp ?: 0.dp
            val w = (layer.size?.width as? Dimension.Px)?.value?.dp ?: parentSize.width
            val h = (layer.size?.height as? Dimension.Px)?.value?.dp ?: parentSize.height
            val tint = resolveIntBinding(layer.tintColor, 0, bindings)

            if (tint != 0) {
                Box(
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .size(w, h)
                        .background(Color(tint).copy(alpha = 0.5f))
                )
            }
        }
    }
}

// region Binding Helpers

private fun resolveBinding(value: StringOrBinding, bindings: Map<String, String>): String {
    return when (value) {
        is StringOrBinding.Literal -> value.value
        is StringOrBinding.Ref -> bindings[value.binding.path] ?: ""
    }
}

private fun resolveFloatBinding(value: FloatOrBinding?, default: Float): Float {
    return when (value) {
        null -> default
        is FloatOrBinding.Literal -> value.value
        is FloatOrBinding.Ref -> default // Runtime bindings not supported yet
    }
}

private fun resolveIntBinding(value: IntOrBinding?, default: Int, bindings: Map<String, String> = emptyMap()): Int {
    return when (value) {
        null -> default
        is IntOrBinding.Literal -> value.value
        is IntOrBinding.Ref -> bindings[value.binding.path]?.let { parseColorString(it) } ?: default
    }
}

private fun parseColorString(s: String): Int? {
    return try {
        android.graphics.Color.parseColor(s)
    } catch (e: Exception) {
        null
    }
}

/**
 * Parse CSS-like corner radius string into a RoundedCornerShape.
 * - "8" = all corners 8dp
 * - "8 4" = top-left/bottom-right 8dp, top-right/bottom-left 4dp
 * - "8 4 2" = top-left 8dp, top-right/bottom-left 4dp, bottom-right 2dp
 * - "8 4 2 1" = top-left 8dp, top-right 4dp, bottom-right 2dp, bottom-left 1dp
 */
private fun parseCornerRadius(value: String?): RoundedCornerShape {
    if (value.isNullOrBlank()) return RoundedCornerShape(0.dp)
    
    val parts = value.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
    
    return when (parts.size) {
        0 -> RoundedCornerShape(0.dp)
        1 -> RoundedCornerShape(parts[0].dp)
        2 -> RoundedCornerShape(
            topStart = parts[0].dp,
            topEnd = parts[1].dp,
            bottomEnd = parts[0].dp,
            bottomStart = parts[1].dp
        )
        3 -> RoundedCornerShape(
            topStart = parts[0].dp,
            topEnd = parts[1].dp,
            bottomEnd = parts[2].dp,
            bottomStart = parts[1].dp
        )
        else -> RoundedCornerShape(
            topStart = parts[0].dp,
            topEnd = parts[1].dp,
            bottomEnd = parts[2].dp,
            bottomStart = parts[3].dp
        )
    }
}

// endregion

