package app.gamenative.ui.component.dialog

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A `ScrollState`-driven port of GameNative's own scrollbar ([app.gamenative.ui.component.Scrollbar], used by the
 * game-selection grid) so the SC editors match it thematically: an accent-primary thumb with a subtle vertical
 * gradient + grab-handle over a faint track, auto-hiding after inactivity, expanding + draggable while touched.
 *
 * That component is bound to `LazyGridState`; ours use `Column(verticalScroll(ScrollState))`, and editing the
 * upstream file would break our isolation — so this reproduces its look/behavior for a plain scroll container. Wrap
 * the scrollable content: `ScScrollbar(scrollState, Modifier.weight(1f)) { Column(Modifier.verticalScroll(state)) { … } }`.
 */
@Composable
fun ScScrollbar(
    scrollState: ScrollState,
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

    var isVisible by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isTouchScrolling by remember { mutableStateOf(false) }
    var containerHeight by remember { mutableFloatStateOf(0f) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val maxValue = scrollState.maxValue
    val showScrollbar = maxValue > 0
    val isScrollInProgress = scrollState.isScrollInProgress

    // ScrollState makes the math simple: progress = value/maxValue; thumb length = visible fraction of content.
    val scrollProgress = if (maxValue <= 0) 0f else (scrollState.value.toFloat() / maxValue).coerceIn(0f, 1f)
    val thumbHeightRatio = if (maxValue <= 0 || containerHeight <= 0f) {
        1f
    } else {
        (containerHeight / (containerHeight + maxValue)).coerceIn(0.05f, 1f)
    }

    val isExpanded = isDragging || isTouchScrolling
    val thumbWidth by animateDpAsState(
        targetValue = if (isExpanded) thumbWidthExpanded else thumbWidthCollapsed,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "thumbWidth",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isVisible || isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "scrollbarAlpha",
    )
    val grabHandleAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "grabHandleAlpha",
    )

    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress && !isDragging) {
            isTouchScrolling = true
        } else if (!isScrollInProgress) {
            delay(300)
            isTouchScrolling = false
        }
    }

    LaunchedEffect(scrollState.value) {
        if (showScrollbar) {
            isVisible = true
            delay(hideDelay)
            if (!isDragging && !isTouchScrolling) isVisible = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        content()

        if (showScrollbar && alpha > 0f) {
            val density = LocalDensity.current
            val thumbMinHeightPx = with(density) { thumbMinHeightDp.toPx() }
            val thumbHeightPx = (containerHeight * thumbHeightRatio).coerceAtLeast(thumbMinHeightPx)
            val maxOffset = (containerHeight - thumbHeightPx).coerceAtLeast(0f)
            val thumbHeightDp = with(density) { thumbHeightPx.toDp() }

            val effectiveProgress = if (isDragging) dragProgress else scrollProgress
            val thumbOffset = effectiveProgress * maxOffset

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
                            val target = (offset.y / containerHeight).coerceIn(0f, 1f)
                            scope.launch { scrollState.animateScrollTo((target * maxValue).roundToInt()) }
                        }
                    }
                    .pointerInput(maxValue) {
                        detectDragGestures(
                            onDragStart = { dragProgress = scrollProgress; isDragging = true; isVisible = true },
                            onDragEnd = {
                                isDragging = false
                                scope.launch { delay(hideDelay); if (!isTouchScrolling) isVisible = false }
                            },
                            onDragCancel = {
                                isDragging = false
                                scope.launch { delay(hideDelay); if (!isTouchScrolling) isVisible = false }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val deltaProgress = dragAmount.y / maxOffset.coerceAtLeast(1f)
                                dragProgress = (dragProgress + deltaProgress).coerceIn(0f, 1f)
                                // dispatchRawDelta is the non-suspending pointer-scroll API — no coroutine per
                                // drag event (unlike scrollTo). Verify scroll direction/scale on-device.
                                val target = (dragProgress * maxValue).roundToInt()
                                scrollState.dispatchRawDelta((target - scrollState.value).toFloat())
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
                // Thumb
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbOffset.roundToInt()) }
                        .width(thumbWidth)
                        .height(thumbHeightDp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(thumbColor, thumbColor.copy(alpha = thumbColor.alpha * 0.8f)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
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
