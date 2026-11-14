package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Constraints
import app.gamenative.theme.ThemeReflection
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.OwnedGames
import app.gamenative.service.SteamService
import app.gamenative.theme.ThemeHero
import app.gamenative.ui.component.GameStatsRow
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.data.statsFor
import app.gamenative.ui.data.communityCompatibilityFor
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedSubtitleText
import app.gamenative.ui.theme.ThemedTitleText
import app.gamenative.ui.util.AdaptivePadding
import app.gamenative.ui.util.shouldShowGamepadUI
import app.gamenative.utils.SteamUtils
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CAROUSEL_TILT_ANGLE = 30.061367f
private const val CAROUSEL_SPACING_RATIO = -0.11f
private const val CAROUSEL_CAMERA_DISTANCE_DP = 6f
private const val CAROUSEL_SIDE_OFFSET_RATIO = 0.028464798f
private const val CAROUSEL_STEP_OFFSET_RATIO = 0.08f
private const val CAROUSEL_ITEM_OVERSCAN_RATIO = 0.4f
private const val CAROUSEL_CARD_ASPECT_RATIO = 2f / 3f
private const val CAROUSEL_MOUSE_WHEEL_SCROLL_MULTIPLIER = 72f
private const val CAROUSEL_MOUSE_DRAG_SLOP_PX = 8f

/** Shelf tile height as a share of a landscape screen's height, as Steam's home measures on its store screenshots. */
private const val SHELF_TILE_HEIGHT_RATIO = 0.39f
private val SHELF_MIN_TILE_HEIGHT = 72.dp
/** Height kept free next to the shelf for the title and the line under it. */
private val SHELF_LABEL_ROOM = 64.dp
/** Height kept free next to the shelf for the title alone. */
private val SHELF_TITLE_ROOM = 36.dp
private val SHELF_ROW_VERTICAL_PADDING = 8.dp
/** Space between the top tab bar's controls and a shelf placed under it. */
private val SHELF_TOP_GAP = 16.dp
/** The vertical padding a grid card keeps around its cover. */
private val SHELF_CARD_VERTICAL_PADDING = 4.dp
private const val SHELF_WIDE_RESIZE_MS = 220
private const val SHELF_COVER_POSE_MS = 400
/** Steam's focus pop: translateZ(15px) under perspective(300px) for a cover, translateZ(7px) for the wide one. */
private const val SHELF_FOCUS_POP = 300f / 285f
private const val SHELF_FOCUS_POP_WIDE = 300f / 293f
private val SHELF_FOCUS_IN = tween<Float>(durationMillis = 300, easing = CubicBezierEasing(0.16f, 0.86f, 0.43f, 0.99f))
private val SHELF_FOCUS_OUT = tween<Float>(durationMillis = 400, easing = CubicBezierEasing(0f, 0.73f, 0.48f, 1f))
/** A layer's camera distance is in Skia's camera unit, an inch of 72 pixels. */
private const val CAMERA_DISTANCE_UNIT_PX = 72f
/** Eye distance for a turn without a perspective: far enough that the covers show no foreshortening. */
private const val FLAT_PERSPECTIVE_PX = 100_000f

/**
 * The shelf's artwork where the theme sets none, as on Steam's home: 70 % opacity at half brightness (the app's
 * overlay 1), sharp, full color, fading out over its lower half.
 */
private val SHELF_HERO = ThemeHero(opacity = 0.7f, blurDp = 0f, saturation = 1f, overlay = 1f, fadeBottom = 0.5f)
/** Steam's landscape capsule (header) shape, used for the shelf's wide first tile. */
private const val SHELF_WIDE_ASPECT_RATIO = 460f / 215f

private fun Modifier.carouselMouseInput(listState: LazyListState): Modifier =
    pointerInput(listState) {
        coroutineScope {
            awaitPointerEventScope {
                var isDragging = false
                var lastDragX = 0f
                var pressedStartX: Float? = null
                while (true) {
                    val event = awaitPointerEvent()
                    val mouseChange = event.changes.firstOrNull { it.type == PointerType.Mouse }
                    when (event.type) {
                        PointerEventType.Scroll -> {
                            val scrollDelta = mouseChange?.scrollDelta
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

                        PointerEventType.Press -> {
                            if (mouseChange?.pressed == true) {
                                pressedStartX = mouseChange.position.x
                                isDragging = false
                            }
                        }

                        PointerEventType.Move -> {
                            if (mouseChange != null) {
                                val currentX = mouseChange.position.x
                                if (isDragging) {
                                    val delta = currentX - lastDragX
                                    lastDragX = currentX
                                    if (delta != 0f) {
                                        listState.dispatchRawDelta(-delta)
                                    }
                                } else {
                                    val startX = pressedStartX
                                    if (startX != null && abs(currentX - startX) > CAROUSEL_MOUSE_DRAG_SLOP_PX) {
                                        isDragging = true
                                        lastDragX = currentX
                                    }
                                }
                            }
                        }

                        PointerEventType.Release, PointerEventType.Exit -> {
                            isDragging = false
                            pressedStartX = null
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

/**
 * The layout-derived inputs a card's tilt transform depends on. Held behind a [derivedStateOf] so that
 * a measure pass which doesn't actually move this card (e.g. the centered card's marquee re-measuring
 * its text every frame) produces an equal value and does not invalidate the card's graphicsLayer.
 */
private data class CarouselTiltInput(
    val distanceFromCenterPx: Float,
    val viewportSpanPx: Float,
    val isFirstItemAtStart: Boolean,
)

/**
 * Wraps a carousel card and applies its draw-order [zIndex] (centered card on top). This is the only
 * place that reacts to the live [centeredIndex]; keeping it in a thin wrapper means a center change
 * recomposes just this box, while [content] — which captures only stable values — is skipped. Do not
 * read scroll-driven state inside [content], or that isolation breaks.
 */
@Composable
private fun CarouselItemSlot(
    listIndex: Int,
    centeredIndex: State<Int>,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val center = centeredIndex.value
    val steps = if (center >= 0) abs(listIndex - center) else 0
    val zOrder = if (steps == 0) 20f else (10f - steps).coerceAtLeast(0f)
    Box(modifier = modifier.zIndex(zOrder), content = content)
}

@Composable
private fun CarouselEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp),
            shape = PluviaTheme.shape(16.dp),
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
    shelf: Boolean = false,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val configuration = LocalConfiguration.current
    val horizontalPadding = AdaptivePadding.horizontal()
    val showGamepadHints = shouldShowGamepadUI() && !PluviaTheme.hideFooterHints
    val hideStatusBar = PrefManager.hideStatusBarWhenNotInGame

    // Fine-Tuned Values by JT, working on both landscape and portrait screens
    val hintBarHeight = if (showGamepadHints) 56.dp else 0.dp
    val extraTopPadding = if (showGamepadHints) 0.dp else 8.dp
    val extraBottomPadding = if (hideStatusBar) 0.dp else 24.dp
    val carouselTopMargin = (if (state.isSearching) 64.dp else 56.dp) + extraTopPadding
    val carouselBottomMargin = 8.dp + hintBarHeight + extraBottomPadding
    val cardTopPadding = 16.dp
    val cardBottomPadding = 16.dp

    // Correct calculation, no more messy calculations
    val landscapeScale = if (configuration.screenWidthDp > configuration.screenHeightDp) 1.0f else 0.5f
    val availableCarouselHeight = (configuration.screenHeightDp.dp - carouselTopMargin - carouselBottomMargin) * landscapeScale
    // The carousel is bound by the screen height, so the theme tile scale can only shrink it.
    val cardHeight = (availableCarouselHeight - cardTopPadding - cardBottomPadding) * PluviaTheme.cardScale.coerceAtMost(1f)
    val cardWidth = cardHeight * CAROUSEL_CARD_ASPECT_RATIO
    val itemContainerHeight = cardHeight + cardTopPadding + cardBottomPadding

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
    val shelfLayout = if (shelf) {
        shelfLayout(
            topBarHeight = carouselTopMargin,
            extraBottomPadding = extraBottomPadding,
            showGamepadHints = showGamepadHints,
        )
    } else {
        null
    }
    // A recommendation leading the shelf stays in view at its left while the game after it has focus.
    val shelfLeadsWithRec = shelf && shelfLeadsWithRecommendation(state.appInfoList.size) { state.appInfoList[it].isRecommended }
    val currentFocusTargetIndex by rememberUpdatedState(focusTargetListIndex)
    // The shelf's wide tile is the first game of the library, after a recommendation shown ahead of it, and is wide
    // only while it is the focused game: 1 = wide, 0 = a cover like the rest.
    val shelfWideIndex = if (shelf && PluviaTheme.shelfWideFirst) {
        state.appInfoList.indexOfFirst { !it.isRecommended }
    } else {
        -1
    }
    val shelfWideProgress = if (shelf) {
        animateFloatAsState(
            targetValue = if (shelfWideIndex >= 0 && focusTargetListIndex == shelfWideIndex) 1f else 0f,
            animationSpec = tween(durationMillis = SHELF_WIDE_RESIZE_MS),
            label = "shelfWideTile",
        )
    } else {
        null
    }
    val shelfNavigation = remember { mutableIntStateOf(0) }
    // The cover the last D-pad move went to; that move scrolls the row itself.
    val shelfNavigationTarget = remember { mutableIntStateOf(-1) }

    // The list scroll offset that brings the cover at [index] to the middle of the row, at its width once focused.
    fun shelfCenterOffset(index: Int): Int {
        val info = listState.layoutInfo
        val itemWidth = with(density) {
            if (index == shelfWideIndex) shelfLayout?.wideTileWidth?.roundToPx() else shelfLayout?.tileWidth?.roundToPx()
        } ?: 0
        return shelfCenterScrollOffset(info.viewportSize.width, itemWidth, info.beforeContentPadding)
    }

    // The shelf's current game is the focused one while it is in view, else (after a touch scroll) the one nearest the
    // middle, as is the carousel's.
    val centeredIndexState = remember(shelf) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val focus = currentFocusTargetIndex
            if (shelf && focus != null && layoutInfo.visibleItemsInfo.any { it.index == focus }) {
                focus
            } else if (shelf) {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - viewportCenter) }?.index
                    ?: listState.firstVisibleItemIndex
            } else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                layoutInfo.visibleItemsInfo.minByOrNull { itemInfo ->
                    abs(itemInfo.offset + itemInfo.size / 2f - viewportCenter)
                }?.index ?: -1
            }
        }
    }
    val centeredIndex by centeredIndexState

    fun currentTargetIndex(): Int {
        val lastIndex = state.appInfoList.lastIndex
        if (lastIndex < 0) return 0
        val preferredIndex = focusTargetListIndex ?: centeredIndex.takeIf { it >= 0 } ?: listState.firstVisibleItemIndex
        return preferredIndex.coerceIn(0, lastIndex)
    }

    fun navigateCarousel(delta: Int) {
        if (state.appInfoList.isEmpty()) return

        val targetIndex = (currentTargetIndex() + delta).coerceIn(0, state.appInfoList.lastIndex)
        if (targetIndex == currentTargetIndex()) return

        val navigation = ++shelfNavigation.intValue
        shelfNavigationTarget.intValue = targetIndex
        scope.launch {
            onFocusedIndexChanged(targetIndex)
            kotlinx.coroutines.delay(16)
            try {
                firstCarouselItemFocusRequester?.requestFocus()
            } catch (_: IllegalStateException) {
            }
            // A scroll measures its distance once, so a wide tile narrowing ahead of the target does so before the
            // row moves; one widening never shifts the target's start.
            if (shelfWideProgress != null && targetIndex != shelfWideIndex) {
                snapshotFlow { shelfWideProgress.value <= 0f || shelfNavigation.intValue != navigation }.first { it }
                if (shelfNavigation.intValue != navigation) return@launch
            }
            if (shelf) {
                listState.animateScrollToItem(targetIndex, shelfCenterOffset(targetIndex))
            } else {
                listState.animateScrollToItem(targetIndex)
            }
            kotlinx.coroutines.delay(16)
            try {
                firstCarouselItemFocusRequester?.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }

    // Focus that lands without a D-pad move (the shelf opening, a return from a game page, a recommendation arriving
    // ahead of the list) brings its cover to the middle as well, once any scroll asked for alongside it is done.
    if (shelf) {
        LaunchedEffect(focusTargetListIndex, state.appInfoList.isNotEmpty(), shelfLeadsWithRec) {
            val target = focusTargetListIndex ?: return@LaunchedEffect
            if (state.appInfoList.isEmpty() || shelfNavigationTarget.intValue == target) return@LaunchedEffect
            withFrameNanos { }
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            if (shelfNavigationTarget.intValue == target) return@LaunchedEffect
            listState.scrollToItem(target.coerceIn(0, state.appInfoList.lastIndex), shelfCenterOffset(target))
        }
    }

    LaunchedEffect(listState, state.appInfoList.size, state.totalAppsInFilter) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
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

    var settledBackdropItem by remember { mutableStateOf<LibraryItem?>(null) }
    val currentAppInfoList by rememberUpdatedState(state.appInfoList)
    // With theme artwork the backdrop follows the centered game itself, so a list that loads (or changes) under an
    // unchanged index still shows its artwork at once.
    val followItem by rememberUpdatedState(shelf || PluviaTheme.hero != null)
    LaunchedEffect(listState) {
        var pendingUpdate: Job? = null
        var lastSettledIndex = -1
        snapshotFlow {
            val centered = centeredIndex
            Triple(
                listState.isScrollInProgress,
                centered,
                if (followItem) currentAppInfoList.getOrNull(centered.coerceAtLeast(0))?.appId else null,
            )
        }
            .collect { (isScrolling, currentCentered, _) ->
                pendingUpdate?.cancel()
                if (!isScrolling) {
                    pendingUpdate = launch {
                        delay(if (followItem && settledBackdropItem == null) 0L else 200L)
                        val list = currentAppInfoList
                        val idx = currentCentered.takeIf { it in list.indices }
                            ?: listState.firstVisibleItemIndex.coerceIn(0, list.lastIndex.coerceAtLeast(0))
                        if (idx != lastSettledIndex || (followItem && list.getOrNull(idx)?.appId != settledBackdropItem?.appId)) {
                            lastSettledIndex = idx
                            settledBackdropItem = list.getOrNull(idx)
                        }
                    }
                }
            }
    }

    Scaffold(
        modifier = modifier,
        containerColor = PluviaTheme.screenBackground,
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
            Box(modifier = Modifier.fillMaxSize()) {
                LibraryDynamicBackdrop(
                    appInfo = settledBackdropItem,
                    imageRefreshCounter = state.imageRefreshCounter,
                    modifier = Modifier.fillMaxSize(),
                    defaultHero = if (shelfLayout != null) SHELF_HERO else null,
                    artHeight = shelfLayout?.artHeight,
                    fullResolution = shelfLayout != null,
                )

                if (shelfLayout != null) {
                    LibraryShelf(
                        state = state,
                        listState = listState,
                        layout = shelfLayout,
                        currentIndex = centeredIndexState,
                        leadsWithRecommendation = shelfLeadsWithRec,
                        wideIndex = shelfWideIndex,
                        wideProgress = shelfWideProgress,
                        settledAppId = settledBackdropItem?.appId,
                        onNavigate = onNavigate,
                        focusRequester = firstCarouselItemFocusRequester,
                        focusTargetListIndex = focusTargetListIndex,
                        onFocusedIndexChanged = onFocusedIndexChanged,
                    )
                } else if (state.appInfoList.isNotEmpty()) {
                    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

                    LazyRow(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .carouselMouseInput(listState),
                        flingBehavior = flingBehavior,
                        horizontalArrangement = Arrangement.spacedBy(carouselItemSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(
                            start = centeredSlotHorizontalPadding,
                            end = centeredSlotHorizontalPadding,
                            top = carouselTopMargin,
                            bottom = carouselBottomMargin,
                        ),
                    ) {
                        items(
                            count = state.appInfoList.size,
                            key = { listIndex ->
                                val item = state.appInfoList[listIndex]
                                if (item.recSource == "hero") "HERO_SLOT" else item.appId
                            },
                        ) { listIndex ->
                            val item = state.appInfoList[listIndex]

                            val tiltInputState = remember(listIndex) {
                                derivedStateOf {
                                    val layoutInfo = listState.layoutInfo
                                    val vc = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                                    val span = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                                        .toFloat().coerceAtLeast(1f)
                                    val vi = layoutInfo.visibleItemsInfo.firstOrNull { it.index == listIndex }
                                    val ic = if (vi != null) vi.offset + vi.size / 2f else vc
                                    CarouselTiltInput(
                                        distanceFromCenterPx = ic - vc,
                                        viewportSpanPx = span,
                                        isFirstItemAtStart = listIndex == 0 &&
                                            layoutInfo.visibleItemsInfo.firstOrNull()?.index == 0,
                                    )
                                }
                            }

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

                            CarouselItemSlot(
                                listIndex = listIndex,
                                centeredIndex = centeredIndexState,
                                modifier = Modifier
                                    .width(carouselItemSlotWidth)
                                    .height(itemContainerHeight),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                        .width(carouselItemSlotWidth)
                                        .height(itemContainerHeight)
                                        .graphicsLayer {
                                            val tiltInput = tiltInputState.value
                                            val distanceFromCenter = tiltInput.distanceFromCenterPx
                                            val span = tiltInput.viewportSpanPx
                                            val normalizedDistance = (distanceFromCenter / span).coerceIn(-1f, 1f)
                                            val itemStepDistancePx = (carouselItemSlotWidthPx + carouselItemSpacingPx).coerceAtLeast(1f)
                                            val distanceInSteps = abs(distanceFromCenter) / itemStepDistancePx

                                            val direction = when {
                                                normalizedDistance < -0.03f -> 1f
                                                normalizedDistance > 0.03f -> -1f
                                                else -> 0f
                                            }

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

                                            val computedRotationY = direction * tiltAngle
                                            val computedTranslationX = if (direction == 0f) {
                                                0f
                                            } else {
                                                val tiltInfluence = if (CAROUSEL_TILT_ANGLE > 0.1f) tiltAngle / CAROUSEL_TILT_ANGLE else 1f
                                                val baseOffsetRatio = CAROUSEL_SIDE_OFFSET_RATIO + (distanceInSteps * CAROUSEL_STEP_OFFSET_RATIO)
                                                val baseShift = direction * cardWidthPx * baseOffsetRatio * tiltInfluence
                                                val edgeOffset = if (tiltInput.isFirstItemAtStart) {
                                                    firstTileOffsetPx
                                                } else {
                                                    0f
                                                }
                                                baseShift + edgeOffset
                                            }

                                            scaleX = scale
                                            scaleY = scale
                                            this.rotationY = computedRotationY
                                            this.translationX = computedTranslationX
                                            cameraDistance = cameraDistancePx
                                            clip = false
                                            compositingStrategy = CompositingStrategy.Offscreen
                                        },
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = cardTopPadding)
                                            .width(cardWidth)
                                            .height(cardHeight),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AppItem(
                                            modifier = appItemModifier,
                                            appInfo = item,
                                            onClick = {
                                                onFocusedIndexChanged(listIndex)
                                                onNavigate(item.appId)
                                            },
                                            onFocus = {
                                                onFocusedIndexChanged(listIndex)
                                            },
                                            paneType = PaneType.GRID_CAPSULE,
                                            imageRefreshCounter = state.imageRefreshCounter,
                                            compatibilityStatus = state.compatibilityMap[item.name],
                                            communityCompatibility = state.communityCompatibilityFor(item),
                                            gameStats = state.statsFor(item),
                                            showFocusGlow = false,
                                            enableFocusScale = false,
                                            animateStats = item.appId == settledBackdropItem?.appId,
                                            themedCover = false,
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
                                    CircularProgressIndicator(color = PluviaTheme.progressColor)
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
                            top = carouselTopMargin,
                            bottom = carouselBottomMargin,
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

/** Where the shelf's row of covers sits between the top tab bar and the hint bar. */
private enum class ShelfRowPosition { TOP, CENTER, BOTTOM }

/** Sizes of the shelf layout; the artwork covers [artHeight] from the top and fades into the page under it. */
private data class ShelfLayout(
    val tileWidth: Dp,
    val tileHeight: Dp,
    val startPadding: Dp,
    val endPadding: Dp,
    val spacing: Dp,
    val topMargin: Dp,
    val bottomMargin: Dp,
    val artHeight: Dp,
    val rowPosition: ShelfRowPosition,
    val titleAbove: Boolean,
    val showTitle: Boolean,
    val showSubtitle: Boolean,
    /** Height of the title block, 0 without one. */
    val labelRoom: Dp,
    /** Room under the covers the reflections draw into, kept out of the row's height. */
    val reflectionRoom: Dp,
) {
    val wideTileWidth: Dp get() = tileHeight * SHELF_WIDE_ASPECT_RATIO
}

@Composable
private fun shelfLayout(
    topBarHeight: Dp,
    extraBottomPadding: Dp,
    showGamepadHints: Boolean,
): ShelfLayout {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val themeLayout = PluviaTheme.layout
    val scale = PluviaTheme.cardScale
    val hintBarHeight = if (showGamepadHints) 56.dp * PluviaTheme.footerStyle.heightScale else 0.dp
    val bottomMargin = 8.dp + hintBarHeight + extraBottomPadding
    val showTitle = !PluviaTheme.shelfHideTitle
    val showSubtitle = showTitle && !PluviaTheme.shelfHideSubtitle
    val labelRoom = when {
        !showTitle -> 0.dp
        showSubtitle -> SHELF_LABEL_ROOM
        else -> SHELF_TITLE_ROOM
    }
    val baseHeight = if (screenWidth > screenHeight) {
        screenHeight * SHELF_TILE_HEIGHT_RATIO
    } else {
        minOf(screenHeight * 0.3f, screenWidth * 0.6f)
    }
    val wantedHeight = baseHeight * scale
    val fixedRoom = bottomMargin + labelRoom + SHELF_ROW_VERTICAL_PADDING * 2
    // A large tile scale gives up the gap under the tab bar before the covers are made smaller.
    val topGap = if (screenHeight - topBarHeight - SHELF_TOP_GAP - fixedRoom < wantedHeight) 0.dp else SHELF_TOP_GAP
    val topMargin = topBarHeight + topGap
    val maxHeight = (screenHeight - topMargin - fixedRoom).coerceAtLeast(SHELF_MIN_TILE_HEIGHT)
    val tileHeight = wantedHeight.coerceIn(SHELF_MIN_TILE_HEIGHT, maxHeight)
    val tileWidth = tileHeight * CAROUSEL_CARD_ASPECT_RATIO
    val startPadding = AdaptivePadding.horizontal()
    val reflection = themeLayout.coverReflection
    return ShelfLayout(
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        startPadding = startPadding,
        // lets every game, the last one too, scroll to the middle of the row
        endPadding = ((screenWidth - tileWidth) / 2).coerceAtLeast(startPadding),
        spacing = 12.dp * scale.coerceAtMost(1f),
        topMargin = topMargin,
        bottomMargin = bottomMargin,
        // behind everything down to the bottom of the row, as Steam's artwork fills the home area
        artHeight = (screenHeight - bottomMargin).coerceIn(screenHeight * 0.55f, screenHeight),
        rowPosition = when (PluviaTheme.shelfRowPosition) {
            "center" -> ShelfRowPosition.CENTER
            "bottom" -> ShelfRowPosition.BOTTOM
            else -> ShelfRowPosition.TOP
        },
        titleAbove = PluviaTheme.shelfTitleAbove,
        showTitle = showTitle,
        showSubtitle = showSubtitle,
        labelRoom = labelRoom,
        reflectionRoom = if (reflection != null) {
            tileHeight * reflection.fadeStop.coerceIn(0f, 1f) + reflection.gapDp.coerceAtLeast(0f).dp +
                SHELF_CARD_VERTICAL_PADDING * 2
        } else {
            0.dp
        },
    )
}

/** A width from [narrow] to [wide] by [progress], read when measuring so a resize does not recompose the tile. */
private fun Modifier.shelfTileWidth(narrow: Dp, wide: Dp, progress: State<Float>): Modifier =
    layout { measurable, constraints ->
        val narrowPx = narrow.toPx()
        val width = (narrowPx + (wide.toPx() - narrowPx) * progress.value).roundToInt()
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** Reports [room] less height than the content takes, so what it draws there overlaps whatever follows. */
private fun Modifier.overhangBottom(room: Dp): Modifier = layout { measurable, constraints ->
    val roomPx = room.roundToPx()
    val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + roomPx else Constraints.Infinity
    val placeable = measurable.measure(constraints.copy(maxHeight = maxHeight))
    layout(placeable.width, (placeable.height - roomPx).coerceAtLeast(0)) { placeable.place(0, 0) }
}

/**
 * Shear ([tilt]) and turn about the vertical axis ([turn]) in degrees, read when drawing so animating them does not
 * recompose. A positive turn moves the right edge away, as CSS rotateY() does.
 */
private fun Modifier.coverPose(tilt: State<Float>, turn: State<Float>, cameraDistance: Float): Modifier =
    graphicsLayer {
        rotationY = turn.value
        this.cameraDistance = cameraDistance
    }.drawWithContent {
        val shear = tan(Math.toRadians(tilt.value.toDouble())).toFloat()
        if (shear == 0f) {
            drawContent()
        } else {
            val canvas = drawContext.canvas
            canvas.save()
            canvas.translate(center.x, center.y)
            canvas.skew(shear, 0f)
            canvas.translate(-center.x, -center.y)
            drawContent()
            canvas.restore()
        }
    }

/**
 * A mirrored copy of the cover under it, recorded into [layer]: [alpha] opacity, faded out over [ThemeReflection.fadeStop]
 * of the cover's height, blurred by [blur], [ThemeReflection.gapDp] below the cover.
 */
private fun Modifier.coverReflection(
    reflection: ThemeReflection,
    layer: GraphicsLayer,
    alpha: State<Float>,
    blur: RenderEffect?,
): Modifier = drawWithContent {
    val fade = reflection.fadeStop.coerceIn(0.01f, 1f)
    layer.alpha = alpha.value
    layer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
    layer.renderEffect = blur
    layer.record {
        scale(1f, -1f, pivot = center) { this@drawWithContent.drawContent() }
        drawRect(Brush.verticalGradient(0f to Color.Black, fade to Color.Transparent), blendMode = BlendMode.DstIn)
    }
    translate(top = size.height + reflection.gapDp.dp.toPx()) { drawLayer(layer) }
    drawContent()
}

/**
 * Steam-home style row: a flat row of covers with the current game's title and status under its cover. As on Steam's
 * home the focused cover scrolls to the middle of the row as far as the row can scroll, so the first covers sit at the
 * left with the row at its start. The row sits right under the top tab bar unless the theme centers it between the
 * bars or puts it along the bottom; the theme can also move the title above the row or leave it out.
 * The first game of the library is a wide landscape tile while it has focus ([wideIndex], [wideProgress]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryShelf(
    state: LibraryState,
    listState: LazyListState,
    layout: ShelfLayout,
    currentIndex: State<Int>,
    leadsWithRecommendation: Boolean,
    wideIndex: Int,
    wideProgress: State<Float>?,
    settledAppId: String?,
    onNavigate: (String) -> Unit,
    focusRequester: FocusRequester?,
    focusTargetListIndex: Int?,
    onFocusedIndexChanged: (Int) -> Unit,
) {
    LaunchedEffect(Unit) {
        // Steam may still be signing in when the shelf first shows; try again until the play times arrive.
        repeat(PLAYTIME_LOAD_TRIES) {
            ShelfPlaytime.load()
            if (ShelfPlaytime.byAppId.value != null) return@LaunchedEffect
            delay(PLAYTIME_RETRY_MS)
        }
    }
    val density = LocalDensity.current
    // A theme can lean or turn each cover by its place against the focused one; otherwise the card leans all alike.
    val posePerSlot = PluviaTheme.cardPosePerSlot
    val tilt = PluviaTheme.cardTilt
    val tiltAfter = PluviaTheme.cardTiltAfter
    val turn = PluviaTheme.cardTurn
    val turnAfter = PluviaTheme.cardTurnAfter
    val focusedFlat = PluviaTheme.cardFocusedFlat
    val perspective = PluviaTheme.cardTurnPerspective
    // CSS perspective(d) puts the eye d away from the cover; a layer's camera sits cameraDistance * 72 px away.
    val turnCameraDistance = with(density) { perspective?.toPx() ?: FLAT_PERSPECTIVE_PX } / CAMERA_DISTANCE_UNIT_PX
    val themeLayout = PluviaTheme.layout
    // Steam's focused cover comes forward by translateZ under perspective(300px): 15 px for a cover, 7 px for the
    // wide one; a theme can replace that pop, or scale the focused cover on top of it.
    val focusPop = themeLayout.cardFocusPop?.takeIf { it > 0f } ?: SHELF_FOCUS_POP
    val focusPopWide = themeLayout.cardFocusPopWide?.takeIf { it > 0f } ?: SHELF_FOCUS_POP_WIDE
    val focusedScale = themeLayout.cardFocusedScale?.takeIf { it > 0f } ?: 1f
    val reflection = themeLayout.coverReflection
    val reflectionBlur = remember(reflection?.blurDp, density) {
        val blurPx = with(density) { (reflection?.blurDp ?: 0f).dp.toPx() }
        if (blurPx > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BlurEffect(blurPx, blurPx, TileMode.Decal) else null
    }
    val focusIndex = focusTargetListIndex ?: currentIndex.value
    // Changes only when the wide tile starts or stops being wider than a cover, which swaps its artwork.
    val wideShown by remember(wideProgress) { derivedStateOf { (wideProgress?.value ?: 0f) > 0f } }
    // A fling settles with a cover at the row's middle; the list stops it at the row's start and end.
    val snapPosition = remember {
        object : SnapPosition {
            override fun position(
                layoutSize: Int,
                itemSize: Int,
                beforeContentPadding: Int,
                afterContentPadding: Int,
                itemIndex: Int,
                itemCount: Int,
            ): Int = shelfCenterSnapPosition(layoutSize, itemSize, beforeContentPadding)
        }
    }
    // A recommendation that lands ahead of the focused game (or a shelf shown again on it) brings the row back to
    // its start, where the list's scroll would otherwise keep the game at the left edge and hide the recommendation.
    LaunchedEffect(leadsWithRecommendation) {
        if (leadsWithRecommendation && focusTargetListIndex == 1) listState.scrollToItem(0)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = layout.topMargin, bottom = layout.bottomMargin),
        contentAlignment = when (layout.rowPosition) {
            ShelfRowPosition.TOP -> Alignment.TopStart
            ShelfRowPosition.CENTER -> Alignment.CenterStart
            ShelfRowPosition.BOTTOM -> Alignment.BottomStart
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (state.appInfoList.isNotEmpty()) {
                val label: @Composable () -> Unit = {
                    ShelfLabel(
                        state = state,
                        currentIndex = currentIndex,
                        shelf = layout,
                        wideIndex = wideIndex,
                        wideProgress = wideProgress,
                        listState = listState,
                    )
                }
                if (layout.showTitle && layout.titleAbove) {
                    label()
                    Spacer(modifier = Modifier.height(4.dp))
                }
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (reflection != null) Modifier.overhangBottom(layout.reflectionRoom) else Modifier)
                        .carouselMouseInput(listState),
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = snapPosition),
                    horizontalArrangement = Arrangement.spacedBy(layout.spacing),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(
                        start = layout.startPadding,
                        end = layout.endPadding,
                        top = SHELF_ROW_VERTICAL_PADDING,
                        bottom = SHELF_ROW_VERTICAL_PADDING + layout.reflectionRoom,
                    ),
                ) {
                    items(
                        count = state.appInfoList.size,
                        key = { listIndex ->
                            val item = state.appInfoList[listIndex]
                            if (item.recSource == "hero") "HERO_SLOT" else item.appId
                        },
                    ) { listIndex ->
                        val item = state.appInfoList[listIndex]
                        val focused = listIndex == focusIndex
                        val resizes = listIndex == wideIndex && wideProgress != null
                        val wide = resizes && wideShown
                        val poseModifier = if (posePerSlot) {
                            val pose = shelfCoverPose(
                                index = listIndex,
                                focusIndex = focusIndex,
                                tilt = tilt,
                                tiltAfter = tiltAfter,
                                turn = turn,
                                turnAfter = turnAfter,
                                focusedFlat = focusedFlat,
                            )
                            val tiltState = animateFloatAsState(pose.tiltDeg, tween(SHELF_COVER_POSE_MS), label = "shelfCoverTilt")
                            val turnState = animateFloatAsState(pose.turnDeg, tween(SHELF_COVER_POSE_MS), label = "shelfCoverTurn")
                            remember(tiltState, turnState, turnCameraDistance) {
                                Modifier.coverPose(tiltState, turnState, turnCameraDistance)
                            }
                        } else {
                            null
                        }
                        val popProgress = animateFloatAsState(
                            targetValue = if (focused) 1f else 0f,
                            animationSpec = if (focused) SHELF_FOCUS_IN else SHELF_FOCUS_OUT,
                            label = "shelfFocusPop",
                        )
                        val reflectionModifier = if (reflection != null) {
                            val reflectionLayer = rememberGraphicsLayer()
                            val reflectionAlpha = animateFloatAsState(
                                targetValue = if (focused) reflection.focusedOpacity ?: reflection.opacity else reflection.opacity,
                                animationSpec = tween(SHELF_COVER_POSE_MS),
                                label = "shelfReflection",
                            )
                            remember(reflection, reflectionLayer, reflectionAlpha, reflectionBlur) {
                                Modifier.coverReflection(reflection, reflectionLayer, reflectionAlpha, reflectionBlur)
                            }
                        } else {
                            null
                        }
                        AppItem(
                            modifier = Modifier
                                .then(if (focused) Modifier.zIndex(1f) else Modifier)
                                .shelfFocusPop(
                                    progress = popProgress,
                                    pop = focusPop,
                                    popWide = focusPopWide,
                                    wideProgress = if (resizes) wideProgress else null,
                                    extraScale = focusedScale,
                                )
                                .then(reflectionModifier ?: Modifier)
                                .then(
                                    if (resizes && wideProgress != null) {
                                        Modifier
                                            .shelfTileWidth(layout.tileWidth, layout.wideTileWidth, wideProgress)
                                            .height(layout.tileHeight + SHELF_CARD_VERTICAL_PADDING * 2)
                                    } else {
                                        Modifier.width(layout.tileWidth)
                                    },
                                )
                                .then(
                                    if (focusRequester != null && listIndex == focusTargetListIndex) {
                                        Modifier.focusRequester(focusRequester)
                                    } else {
                                        Modifier
                                    },
                                ),
                            appInfo = item,
                            onClick = {
                                onFocusedIndexChanged(listIndex)
                                onNavigate(item.appId)
                            },
                            onFocus = { onFocusedIndexChanged(listIndex) },
                            paneType = if (wide) PaneType.GRID_HERO else PaneType.GRID_CAPSULE,
                            imageRefreshCounter = state.imageRefreshCounter,
                            compatibilityStatus = state.compatibilityMap[item.name],
                            communityCompatibility = state.communityCompatibilityFor(item),
                            gameStats = state.statsFor(item),
                            showFocusGlow = false,
                            enableFocusScale = false,
                            animateStats = item.appId == settledAppId,
                            showTitle = false,
                            skewTilt = true,
                            fillSize = resizes,
                            poseModifier = poseModifier,
                            coverIndex = listIndex,
                            focusedOverride = focused,
                        )
                    }

                    if (state.appInfoList.size < state.totalAppsInFilter) {
                        item {
                            Box(
                                modifier = Modifier
                                    .width(layout.tileWidth)
                                    .height(layout.tileHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = PluviaTheme.progressColor)
                            }
                        }
                    }
                }
                if (layout.showTitle && !layout.titleAbove) label()
            } else if (state.isLoading) {
                val wideFirst = PluviaTheme.shelfWideFirst
                if (layout.titleAbove) Spacer(modifier = Modifier.height(layout.labelRoom))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    userScrollEnabled = false,
                    horizontalArrangement = Arrangement.spacedBy(layout.spacing),
                    contentPadding = PaddingValues(
                        start = layout.startPadding,
                        top = SHELF_ROW_VERTICAL_PADDING,
                        bottom = SHELF_ROW_VERTICAL_PADDING,
                    ),
                ) {
                    items(6) { index ->
                        GameSkeletonLoader(
                            modifier = Modifier
                                .width(if (wideFirst && index == 0) layout.wideTileWidth else layout.tileWidth)
                                .height(layout.tileHeight)
                                .alpha(0.85f),
                            paneType = if (wideFirst && index == 0) PaneType.GRID_HERO else PaneType.GRID_CAPSULE,
                        )
                    }
                }
                if (!layout.titleAbove) Spacer(modifier = Modifier.height(layout.labelRoom))
            }
        }
    }
    if (state.appInfoList.isEmpty() && !state.isLoading) {
        CarouselEmptyState()
    }
}

/**
 * The focused cover's pop about its center: [pop] for a cover, [popWide] as it widens ([wideProgress]), times
 * [extraScale], reached as [progress] goes to 1; read in the layer so the animation does not recompose.
 */
private fun Modifier.shelfFocusPop(
    progress: State<Float>,
    pop: Float,
    popWide: Float,
    wideProgress: State<Float>?,
    extraScale: Float,
): Modifier = graphicsLayer {
    val w = wideProgress?.value ?: 0f
    val full = (pop + (popWide - pop) * w) * extraScale
    val scale = 1f + (full - 1f) * progress.value
    scaleX = scale
    scaleY = scale
}

private const val PLAYTIME_LOAD_TRIES = 20
private const val PLAYTIME_RETRY_MS = 3_000L

/** Steam playtime by app id for the shelf's status line, fetched when a shelf is shown; null until it arrives. */
private object ShelfPlaytime {
    val byAppId = mutableStateOf<Map<Int, OwnedGames>?>(null)
    private var loading = false

    suspend fun load() {
        if (loading) return
        loading = true
        try {
            val steamId = SteamService.userSteamId?.convertToUInt64() ?: return
            val games = runCatching { SteamService.getOwnedGames(steamId) }.getOrNull()
            if (!games.isNullOrEmpty()) byAppId.value = games.associateBy { it.appId }
        } finally {
            loading = false
        }
    }
}

/**
 * The current game's title and, under it, its Steam playtime or install state and its play stats, placed at the
 * current game's cover: under the row, or above it when the theme asks. A theme that centers titles centers them on
 * that cover, kept on screen.
 */
@Composable
private fun ShelfLabel(
    state: LibraryState,
    currentIndex: State<Int>,
    shelf: ShelfLayout,
    wideIndex: Int,
    wideProgress: State<Float>?,
    listState: LazyListState,
) {
    val index = currentIndex.value.coerceAtLeast(0)
    val item = state.appInfoList.getOrNull(index)
    val startPadding = shelf.startPadding
    val tileWidth = shelf.tileWidth
    val wideTileWidth = shelf.wideTileWidth
    val anchorWide = if (index == wideIndex) wideProgress else null
    val gameStats = item?.let { state.statsFor(it) }
    val center = PluviaTheme.centerTitles
    val textColor = MaterialTheme.colorScheme.onBackground
    val titleColor = PluviaTheme.headingColor(textColor)
    val shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 2f), blurRadius = 8f)
    val title = when {
        item == null -> ""
        item.isRecTeaser -> stringResource(R.string.rec_teaser_title)
        else -> item.name
    }
    val playtimes = ShelfPlaytime.byAppId.value
    val owned = if (item?.gameSource == GameSource.STEAM) playtimes?.get(item.gameId) else null
    val noPlaytime = stringResource(R.string.library_no_playtime)
    // As Steam's home: the play time, else "No playtime yet", for every game, non-Steam and recommended ones too.
    val status = when {
        item == null || item.isRecTeaser -> null
        item.isRecommended -> listOfNotNull(noPlaytime, item.recPrice?.takeIf { item.recStoreCard }).joinToString("  ·  ")
        owned != null && owned.playtimeTwoWeeks > 0 ->
            stringResource(R.string.playtime_last_two_weeks, SteamUtils.formatPlayTime(owned.playtimeTwoWeeks))
        owned != null && owned.playtimeForever > 0 ->
            stringResource(R.string.total_playtime, SteamUtils.formatPlayTime(owned.playtimeForever))
        // Steam play times not fetched yet (or offline): the install state until they arrive
        item.gameSource == GameSource.STEAM && playtimes == null ->
            stringResource(if (item.isInstalled) R.string.library_installed else R.string.library_not_installed)
        else -> noPlaytime
    }
    val showStats = item != null && !item.isRecommended && gameStats != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(shelf.labelRoom)
            .layout { measurable, constraints ->
                // the label sits at its cover wherever the row holds it
                val side = startPadding.roundToPx()
                val shift = (listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: 0)
                    .coerceIn(0, (constraints.maxWidth - side * 2).coerceAtLeast(0))
                val start = side + shift
                val room = if (center) constraints.maxWidth - side * 2 else constraints.maxWidth - side - start
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = room.coerceAtLeast(0)))
                val x = if (center) {
                    val anchorWidth = if (anchorWide != null) {
                        val narrowPx = tileWidth.toPx()
                        narrowPx + (wideTileWidth.toPx() - narrowPx) * anchorWide.value
                    } else {
                        tileWidth.toPx()
                    }
                    (start + anchorWidth.roundToInt() / 2 - placeable.width / 2)
                        .coerceAtMost(constraints.maxWidth - side - placeable.width)
                        .coerceAtLeast(side)
                } else {
                    start
                }
                layout(constraints.maxWidth, placeable.height) { placeable.place(x, 0) }
            },
        verticalArrangement = if (shelf.titleAbove) Arrangement.Bottom else Arrangement.Top,
        horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        ThemedTitleText(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal, shadow = shadow),
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (shelf.showSubtitle && (status != null || showStats)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val subtitleColor = PluviaTheme.subtitleColor(textColor.copy(alpha = 0.75f))
                if (status != null) {
                    ThemedSubtitleText(
                        text = status.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                            shadow = shadow,
                        ),
                        color = subtitleColor,
                        maxLines = 1,
                    )
                }
                if (showStats) {
                    if (status != null) Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.width(IntrinsicSize.Max)) {
                        GameStatsRow(stats = gameStats, tint = subtitleColor, animate = false)
                    }
                }
            }
        }
    }
}
