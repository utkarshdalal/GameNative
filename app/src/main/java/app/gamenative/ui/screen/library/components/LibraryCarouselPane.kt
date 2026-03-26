package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.util.AdaptivePadding
import app.gamenative.ui.util.shouldShowGamepadUI
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CAROUSEL_TILT_ANGLE = 30.061367f
private const val CAROUSEL_SPACING_RATIO = -0.11f
private const val CAROUSEL_CAMERA_DISTANCE_DP = 6f
private const val CAROUSEL_SIDE_OFFSET_RATIO = 0.028464798f
private const val CAROUSEL_STEP_OFFSET_RATIO = 0.08f
private const val CAROUSEL_ITEM_OVERSCAN_RATIO = 0.4f
private const val CAROUSEL_CARD_ASPECT_RATIO = 2f / 3f
private const val CAROUSEL_CARD_SIZE_MULTIPLIER = 1.22f
private const val CAROUSEL_CARD_VERTICAL_OVERFLOW = 32f
private const val CAROUSEL_BADGE_RESERVED_HEIGHT = 0f
private const val CAROUSEL_MOUSE_WHEEL_SCROLL_MULTIPLIER = 72f

private data class CarouselLayoutSnapshot(
    val viewportCenter: Float = 0f,
    val viewportSpan: Float = 1f,
    val visibleItemCenters: Map<Int, Float> = emptyMap(),
    val visibleCount: Int = 0,
    val lastVisibleIndex: Int? = null,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

private fun Modifier.carouselMouseInput(listState: LazyListState): Modifier =
    pointerInput(listState) {
        coroutineScope {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Scroll -> {
                            val scrollDelta = event.changes.firstOrNull()?.scrollDelta
                            if (scrollDelta != null) {
                                val dominantDelta =
                                    if (abs(scrollDelta.x) > abs(scrollDelta.y)) scrollDelta.x else scrollDelta.y
                                if (dominantDelta != 0f) {
                                    launch {
                                        listState.scrollBy(
                                            dominantDelta * CAROUSEL_MOUSE_WHEEL_SCROLL_MULTIPLIER,
                                        )
                                    }
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

private fun interpolateByDistance(
    distanceInSteps: Float,
    centerValue: Float,
    firstStepValue: Float,
    secondStepValue: Float,
    farValue: Float,
): Float {
    val clampedDistance = distanceInSteps.coerceAtLeast(0f)
    return when {
        clampedDistance <= 1f -> {
            centerValue + (firstStepValue - centerValue) * clampedDistance
        }

        clampedDistance <= 2f -> {
            firstStepValue + (secondStepValue - firstStepValue) * (clampedDistance - 1f)
        }

        else -> {
            val farProgress = (clampedDistance - 2f).coerceIn(0f, 1f)
            secondStepValue + (farValue - secondStepValue) * farProgress
        }
    }
}

@Composable
private fun CarouselEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp,
        ) {
            Text(
                modifier = Modifier.padding(24.dp),
                text = stringResource(R.string.library_no_items),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LibraryCarouselPane(
    state: LibraryState,
    listState: LazyListState,
    onPageChange: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    firstCarouselItemFocusRequester: FocusRequester? = null,
    focusTargetListIndex: Int? = null,
    onFocusedIndexChanged: (Int) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val configuration = LocalConfiguration.current
    val horizontalPadding = AdaptivePadding.horizontal()
    val showGamepadHints = shouldShowGamepadUI()
    val topOverlayClearance = if (state.isSearching) 116.dp else 104.dp
    val bottomOverlayClearance = if (showGamepadHints) 156.dp else 32.dp
    val baseCardWidth = when (configuration.screenWidthDp) {
        in 0..700 -> 200.dp
        in 701..1100 -> 240.dp
        else -> 270.dp
    }
    val baseCardHeight = baseCardWidth / CAROUSEL_CARD_ASPECT_RATIO
    val cardVerticalOverflow = CAROUSEL_CARD_VERTICAL_OVERFLOW.dp
    val badgeReservedHeight = CAROUSEL_BADGE_RESERVED_HEIGHT.dp
    val cardTopOverflow = cardVerticalOverflow
    val cardBottomOverflow = cardVerticalOverflow + badgeReservedHeight
    val availableCarouselHeight =
        (configuration.screenHeightDp.dp - topOverlayClearance - bottomOverlayClearance)
            .coerceAtLeast(220.dp)
    val maxCardHeight =
        (availableCarouselHeight - cardTopOverflow - cardBottomOverflow)
            .coerceAtLeast(180.dp)
    val cardHeight = minOf(baseCardHeight, maxCardHeight * CAROUSEL_CARD_SIZE_MULTIPLIER)
    val cardWidth = cardHeight * CAROUSEL_CARD_ASPECT_RATIO
    val itemContainerHeight = cardHeight + cardTopOverflow + cardBottomOverflow
    val cardWidthPx = with(density) { cardWidth.toPx() }
    // Keep lazy items composed slightly beyond the viewport because rotation/translation can leave
    // transformed pixels visible after the raw item slot has technically moved offscreen.
    val cardHorizontalOverscan = cardWidth * CAROUSEL_ITEM_OVERSCAN_RATIO
    val carouselItemSlotWidth = cardWidth + (cardHorizontalOverscan * 2)
    val centeredHorizontalPadding = ((configuration.screenWidthDp.dp - cardWidth) / 2).coerceAtLeast(horizontalPadding)
    val centeredSlotHorizontalPadding =
        ((configuration.screenWidthDp.dp - carouselItemSlotWidth) / 2)
            .coerceAtLeast((horizontalPadding - cardHorizontalOverscan).coerceAtLeast(0.dp))
    val overlapSpacing = cardWidth * CAROUSEL_SPACING_RATIO
    val carouselItemSpacing = overlapSpacing - (cardHorizontalOverscan * 2)
    val carouselItemSlotWidthPx = with(density) { carouselItemSlotWidth.toPx() }
    val carouselItemSpacingPx = with(density) { carouselItemSpacing.toPx() }
    val firstTileOffsetPx = cardWidthPx * 0.08f
    val cameraDistancePx = with(density) { CAROUSEL_CAMERA_DISTANCE_DP.dp.toPx() }

    val layoutSnapshot by produceState(initialValue = CarouselLayoutSnapshot(), listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
            CarouselLayoutSnapshot(
                viewportCenter = viewportCenter,
                viewportSpan = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                    .toFloat()
                    .coerceAtLeast(1f),
                visibleItemCenters = layoutInfo.visibleItemsInfo.associate { itemInfo ->
                    itemInfo.index to (itemInfo.offset + itemInfo.size / 2f)
                },
                visibleCount = layoutInfo.visibleItemsInfo.size,
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }
            .distinctUntilChanged()
            .collect { value = it }
    }

    val centeredIndex by remember(layoutSnapshot) {
        derivedStateOf {
            layoutSnapshot.visibleItemCenters.minByOrNull { (_, itemCenter) ->
                abs(itemCenter - layoutSnapshot.viewportCenter)
            }?.key ?: -1
        }
    }

    fun currentTargetIndex(): Int {
        val lastIndex = state.appInfoList.lastIndex
        if (lastIndex < 0) return 0
        val preferredIndex = focusTargetListIndex ?: centeredIndex.takeIf { it >= 0 } ?: layoutSnapshot.firstVisibleItemIndex
        return preferredIndex.coerceIn(0, lastIndex)
    }

    fun navigateCarousel(delta: Int) {
        if (state.appInfoList.isEmpty()) return

        val targetIndex = (currentTargetIndex() + delta).coerceIn(0, state.appInfoList.lastIndex)
        if (targetIndex == currentTargetIndex()) return

        scope.launch {
            onFocusedIndexChanged(targetIndex)
            kotlinx.coroutines.delay(16)
            listState.animateScrollToItem(targetIndex)
            kotlinx.coroutines.delay(16)
            try {
                firstCarouselItemFocusRequester?.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }

    LaunchedEffect(listState, state.appInfoList.size, state.totalAppsInFilter) {
        snapshotFlow { layoutSnapshot.lastVisibleIndex }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex >= state.appInfoList.lastIndex &&
                    state.appInfoList.size < state.totalAppsInFilter
                ) {
                    onPageChange(1)
                }
            }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        false
                    } else {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                navigateCarousel(-1)
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                navigateCarousel(1)
                                true
                            }

                            else -> false
                        }
                    }
                },
        ) {
            val selectedBackdropItem = if (state.appInfoList.isEmpty()) {
                null
            } else {
                val fallbackIndex = layoutSnapshot.firstVisibleItemIndex.coerceIn(0, state.appInfoList.lastIndex)
                val backdropIndex = centeredIndex.takeIf { it in state.appInfoList.indices } ?: fallbackIndex
                state.appInfoList.getOrNull(backdropIndex)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LibraryDynamicBackdrop(
                    appInfo = selectedBackdropItem,
                    imageRefreshCounter = state.imageRefreshCounter,
                    modifier = Modifier.fillMaxSize(),
                )

                if (state.appInfoList.isNotEmpty()) {
                    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

                    val mouseDragState = rememberDraggableState { delta ->
                        listState.dispatchRawDelta(-delta)
                    }

                    LazyRow(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .carouselMouseInput(listState)
                            .draggable(
                                state = mouseDragState,
                                orientation = Orientation.Horizontal,
                            ),
                        flingBehavior = flingBehavior,
                        horizontalArrangement = Arrangement.spacedBy(carouselItemSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(
                            start = centeredSlotHorizontalPadding,
                            end = centeredSlotHorizontalPadding,
                            top = topOverlayClearance,
                            bottom = bottomOverlayClearance,
                        ),
                    ) {
                        items(
                            count = state.appInfoList.size,
                            key = { listIndex -> state.appInfoList[listIndex].appId },
                        ) { listIndex ->
                            val item = state.appInfoList[listIndex]
                            val viewportCenter = layoutSnapshot.viewportCenter
                            val itemCenter = layoutSnapshot.visibleItemCenters[listIndex] ?: viewportCenter

                            val distanceFromCenter = itemCenter - viewportCenter
                            val normalizedDistance =
                                (distanceFromCenter / layoutSnapshot.viewportSpan)
                                    .coerceIn(-1f, 1f)
                            val absDistance = abs(normalizedDistance)
                            val itemStepDistancePx = (carouselItemSlotWidthPx + carouselItemSpacingPx).coerceAtLeast(1f)
                            val distanceInSteps = abs(distanceFromCenter) / itemStepDistancePx
                            val relativeToCenter = if (centeredIndex >= 0) {
                                listIndex - centeredIndex
                            } else {
                                0
                            }
                            val stepsFromCenter = abs(relativeToCenter)
                            val direction = when {
                                relativeToCenter < 0 -> 1f
                                relativeToCenter > 0 -> -1f
                                normalizedDistance < -0.03f -> 1f
                                normalizedDistance > 0.03f -> -1f
                                else -> 0f
                            }
                            val isCentered = stepsFromCenter == 0 || (centeredIndex < 0 && absDistance < 0.08f)
                            val tiltMultiplier = when {
                                distanceInSteps <= 1f -> distanceInSteps
                                distanceInSteps <= 2f -> 1f + (distanceInSteps - 1f) * 0.2f
                                else -> 1.2f + (distanceInSteps - 2f) * 0.15f
                            }.coerceAtMost(79f)
                            val tiltAngle = (CAROUSEL_TILT_ANGLE * tiltMultiplier).coerceAtMost(79f)

                            val scale = interpolateByDistance(
                                distanceInSteps = distanceInSteps,
                                centerValue = 1.04f,
                                firstStepValue = 0.91f,
                                secondStepValue = 0.86f,
                                farValue = 0.8f,
                            )
                            val alpha = 1f
                            val rotationY = direction * tiltAngle
                            val translationX = if (direction == 0f) {
                                0f
                            } else {
                                val tiltInfluence = if (CAROUSEL_TILT_ANGLE > 0.1f) tiltAngle / CAROUSEL_TILT_ANGLE else 1f
                                val baseOffsetRatio = CAROUSEL_SIDE_OFFSET_RATIO + (distanceInSteps * CAROUSEL_STEP_OFFSET_RATIO)
                                val baseShift = direction * cardWidthPx * baseOffsetRatio * tiltInfluence
                                val edgeOffset = if (listIndex == 0 && layoutSnapshot.firstVisibleItemIndex == 0) {
                                    firstTileOffsetPx
                                } else {
                                    0f
                                }
                                baseShift + edgeOffset
                            }
                            val zOrder = if (isCentered) {
                                20f
                            } else {
                                (10f - stepsFromCenter).coerceAtLeast(0f)
                            }
                            var isVisible by remember(item.appId) { mutableStateOf(false) }

                            LaunchedEffect(item.appId) {
                                isVisible = true
                            }

                            val appItemAlpha = if (isVisible) alpha else 0f
                            val appItemModifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (firstCarouselItemFocusRequester != null &&
                                        focusTargetListIndex != null &&
                                        listIndex == focusTargetListIndex
                                    ) {
                                        Modifier.focusRequester(firstCarouselItemFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )

                            Box(
                                modifier = Modifier
                                    .zIndex(zOrder)
                                    .width(carouselItemSlotWidth)
                                    .height(itemContainerHeight),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = cardTopOverflow)
                                        .width(cardWidth)
                                        .height(cardHeight + badgeReservedHeight)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            this.alpha = appItemAlpha
                                            this.rotationY = rotationY
                                            this.translationX = translationX
                                            cameraDistance = cameraDistancePx
                                            clip = false
                                        },
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .width(cardWidth)
                                            .height(cardHeight),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AppItem(
                                            modifier = appItemModifier,
                                            appInfo = item,
                                            onClick = { onNavigate(item.appId) },
                                            onFocus = {
                                                onFocusedIndexChanged(listIndex)
                                            },
                                            paneType = PaneType.GRID_CAPSULE,
                                            imageRefreshCounter = state.imageRefreshCounter,
                                            compatibilityStatus = state.compatibilityMap[item.name],
                                            showFocusGlow = false,
                                            enableFocusScale = false,
                                        )
                                    }
                                }
                            }
                        }

                        if (state.appInfoList.size < state.totalAppsInFilter) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .width(carouselItemSlotWidth)
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                } else if (state.isLoading) {
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(
                            start = centeredHorizontalPadding,
                            end = centeredHorizontalPadding,
                            top = topOverlayClearance,
                            bottom = bottomOverlayClearance,
                        ),
                    ) {
                        items(6) {
                            Box(
                                modifier = Modifier
                                    .width(cardWidth)
                                    .height(itemContainerHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                GameSkeletonLoader(
                                    modifier = Modifier
                                        .width(cardWidth)
                                        .height(cardHeight)
                                        .alpha(0.85f),
                                    paneType = PaneType.GRID_CAPSULE,
                                )
                            }
                        }
                    }
                } else {
                    CarouselEmptyState()
                }
            }
        }
    }
}
