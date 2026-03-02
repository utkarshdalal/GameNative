package app.gamenative.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fallback item height in pixels when no visible items available for measurement */
private const val FALLBACK_ITEM_HEIGHT_PX = 100f

/**
 * Draggable scrollbar for LazyVerticalGrid
 */
@Composable
fun Scrollbar(
    listState: LazyGridState,
    modifier: Modifier = Modifier,
    thumbColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    thumbWidthCollapsed: Dp = 4.dp,
    thumbWidthExpanded: Dp = 10.dp,
    thumbMinHeightDp: Dp = 48.dp,
    hideDelay: Long = 1500L,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Track visibility and interaction state
    var isVisible by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isTouchScrolling by remember { mutableStateOf(false) }
    var containerHeight by remember { mutableFloatStateOf(0f) }

    // Drag state - when dragging, thumb follows gesture directly instead of list state
    var dragProgress by remember { mutableFloatStateOf(0f) }
    // Cache grid parameters at drag start to prevent recalculation during drag
    var dragColumnsCount by remember { mutableStateOf(1) }
    var dragTotalRows by remember { mutableStateOf(1) }
    var dragTotalItems by remember { mutableStateOf(0) }

    // Read layout info directly
    val layoutInfo = listState.layoutInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    val visibleItemsInfo = layoutInfo.visibleItemsInfo
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val isScrollInProgress = listState.isScrollInProgress

    // Calculate smooth scroll progress using pixel-level precision
    // For grids, account for multiple columns per row
    val scrollProgress = remember(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        totalItemsCount,
        viewportHeight,
    ) {
        if (totalItemsCount == 0 || visibleItemsInfo.isEmpty()) {
            0f
        } else {
            // Estimate column count by counting items sharing the same Y offset
            val firstRowY = visibleItemsInfo.first().offset.y
            val columnsCount = visibleItemsInfo.count { it.offset.y == firstRowY }.coerceAtLeast(1)

            // Calculate average row height from visible items
            val avgRowHeight = if (visibleItemsInfo.isNotEmpty()) {
                visibleItemsInfo.sumOf { it.size.height } / visibleItemsInfo.size.toFloat()
            } else {
                FALLBACK_ITEM_HEIGHT_PX
            }

            // Calculate total rows and current row
            val totalRows = (totalItemsCount + columnsCount - 1) / columnsCount
            val currentRow = listState.firstVisibleItemIndex / columnsCount

            val estimatedTotalHeight = totalRows * avgRowHeight
            val estimatedScrollableHeight = (estimatedTotalHeight - viewportHeight).coerceAtLeast(1f)

            // Calculate current scroll position in pixels (row-based)
            val currentScrollOffset = currentRow * avgRowHeight + listState.firstVisibleItemScrollOffset

            (currentScrollOffset / estimatedScrollableHeight).coerceIn(0f, 1f)
        }
    }

    // Calculate thumb height ratio based on viewport vs estimated total content height
    // For grids, we need to account for multiple columns per row
    val thumbHeightRatio = if (totalItemsCount == 0 || visibleItemsInfo.isEmpty() || viewportHeight <= 0) {
        1f
    } else {
        // Estimate column count by counting items sharing the same Y offset (first row)
        val firstRowY = visibleItemsInfo.first().offset.y
        val columnsCount = visibleItemsInfo.count { it.offset.y == firstRowY }.coerceAtLeast(1)

        // Calculate average row height from visible items
        val avgItemHeight = visibleItemsInfo.sumOf { it.size.height } / visibleItemsInfo.size.toFloat()

        // Calculate total rows (ceiling division)
        val totalRows = (totalItemsCount + columnsCount - 1) / columnsCount
        val estimatedTotalHeight = totalRows * avgItemHeight

        if (estimatedTotalHeight <= 0f) {
            1f
        } else {
            (viewportHeight.toFloat() / estimatedTotalHeight).coerceIn(0.05f, 1f)
        }
    }

    val isExpanded = isDragging || isTouchScrolling

    // Animate width
    val thumbWidth by animateDpAsState(
        targetValue = if (isExpanded) thumbWidthExpanded else thumbWidthCollapsed,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "thumbWidth",
    )

    // Animate visibility
    val alpha by animateFloatAsState(
        targetValue = if (isVisible || isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "scrollbarAlpha",
    )

    // Animate grab handle opacity
    val grabHandleAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "grabHandleAlpha",
    )

    // Track touch scrolling state
    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress && !isDragging) {
            isTouchScrolling = true
        } else if (!isScrollInProgress) {
            delay(300)
            isTouchScrolling = false
        }
    }

    // Show scrollbar when scrolling
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (totalItemsCount > visibleItemsInfo.size) {
            isVisible = true
            delay(hideDelay)
            if (!isDragging && !isTouchScrolling) {
                isVisible = false
            }
        }
    }

    val showScrollbar = totalItemsCount > visibleItemsInfo.size

    Box(modifier = modifier.fillMaxSize()) {
        content()

        if (showScrollbar && alpha > 0f) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val thumbMinHeightPx = with(density) { thumbMinHeightDp.toPx() }
            val thumbHeightPx = (containerHeight * thumbHeightRatio).coerceAtLeast(thumbMinHeightPx)
            val maxOffset = (containerHeight - thumbHeightPx).coerceAtLeast(0f)
            val thumbHeightDp = with(density) { thumbHeightPx.toDp() }

            // When dragging, thumb follows gesture directly; otherwise follows list state
            val effectiveProgress = if (isDragging) dragProgress else scrollProgress
            val thumbOffset = effectiveProgress * maxOffset

            // Pre-calculate grid info for scroll operations
            val columnsCount = if (visibleItemsInfo.isNotEmpty()) {
                val firstRowY = visibleItemsInfo.first().offset.y
                visibleItemsInfo.count { it.offset.y == firstRowY }.coerceAtLeast(1)
            } else {
                1
            }
            val totalRows = (totalItemsCount + columnsCount - 1) / columnsCount

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(24.dp)
                    .padding(end = 4.dp)
                    .alpha(alpha)
                    .onSizeChanged { containerHeight = it.height.toFloat() }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val targetProgress = (offset.y / containerHeight).coerceIn(0f, 1f)
                            val targetRow = (targetProgress * (totalRows - 1)).roundToInt()
                            val targetIndex = (targetRow * columnsCount).coerceIn(0, totalItemsCount - 1)
                            scope.launch {
                                listState.animateScrollToItem(targetIndex)
                            }
                        }
                    }
                    .pointerInput(totalItemsCount, columnsCount, totalRows) {
                        detectDragGestures(
                            onDragStart = {
                                // Cache grid parameters at drag start
                                dragColumnsCount = columnsCount
                                dragTotalRows = totalRows
                                dragTotalItems = totalItemsCount
                                dragProgress = scrollProgress
                                isDragging = true
                                isVisible = true
                            },
                            onDragEnd = {
                                isDragging = false
                                scope.launch {
                                    delay(hideDelay)
                                    if (!isTouchScrolling) {
                                        isVisible = false
                                    }
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                scope.launch {
                                    delay(hideDelay)
                                    if (!isTouchScrolling) {
                                        isVisible = false
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                // Update drag progress directly from gesture
                                val deltaProgress = dragAmount.y / maxOffset.coerceAtLeast(1f)
                                dragProgress = (dragProgress + deltaProgress).coerceIn(0f, 1f)

                                // Use cached grid parameters for stable scroll calculations
                                val maxRow = (dragTotalRows - 1).coerceAtLeast(0)
                                val targetRow = (dragProgress * maxRow).roundToInt()
                                val targetIndex = (targetRow * dragColumnsCount).coerceIn(0, dragTotalItems - 1)

                                // Scroll synchronously to avoid race conditions
                                scope.launch {
                                    listState.scrollToItem(targetIndex.coerceAtLeast(0))
                                }
                            },
                        )
                    },
            ) {
                // Track
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(thumbWidth)
                        .clip(RoundedCornerShape(50))
                        .background(trackColor),
                )

                // Scrollbar thumb
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbOffset.roundToInt()) }
                        .width(thumbWidth)
                        .height(thumbHeightDp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    thumbColor,
                                    thumbColor.copy(alpha = thumbColor.alpha * 0.8f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Grab handle lines (only visible when expanded)
                    if (grabHandleAlpha > 0f) {
                        Column(
                            modifier = Modifier.alpha(grabHandleAlpha),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .width(6.dp)
                                        .height(1.5.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
