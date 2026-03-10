package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.util.AdaptivePadding
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private const val CAROUSEL_TILT_ANGLE = 58f
private const val CAROUSEL_SPACING_RATIO = 0.06f
private const val CAROUSEL_CAMERA_DISTANCE_DP = 24f
private const val CAROUSEL_SIDE_OFFSET_RATIO = 0.14f
private const val CAROUSEL_STEP_OFFSET_RATIO = 0.08f
private const val PREF_CAROUSEL_DEBUG_TILT_ANGLE = "library_carousel_debug_tilt_angle"
private const val PREF_CAROUSEL_DEBUG_SPACING_RATIO = "library_carousel_debug_spacing_ratio"
private const val PREF_CAROUSEL_DEBUG_CAMERA_DISTANCE_DP = "library_carousel_debug_camera_distance_dp"
private const val PREF_CAROUSEL_DEBUG_SIDE_OFFSET_RATIO = "library_carousel_debug_side_offset_ratio"
private const val PREF_CAROUSEL_DEBUG_STEP_OFFSET_RATIO = "library_carousel_debug_step_offset_ratio"

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LibraryCarouselPane(
    state: LibraryState,
    listState: LazyGridState,
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
    val isDebugBuild = BuildConfig.DEBUG
    var showDebugTuner by rememberSaveable { mutableStateOf(false) }
    var debugTiltAngle by rememberSaveable {
        mutableFloatStateOf(PrefManager.getFloat(PREF_CAROUSEL_DEBUG_TILT_ANGLE, CAROUSEL_TILT_ANGLE))
    }
    var debugSpacingRatio by rememberSaveable {
        mutableFloatStateOf(PrefManager.getFloat(PREF_CAROUSEL_DEBUG_SPACING_RATIO, CAROUSEL_SPACING_RATIO))
    }
    var debugCameraDistanceDp by rememberSaveable {
        mutableFloatStateOf(PrefManager.getFloat(PREF_CAROUSEL_DEBUG_CAMERA_DISTANCE_DP, CAROUSEL_CAMERA_DISTANCE_DP))
    }
    var debugSideOffsetRatio by rememberSaveable {
        mutableFloatStateOf(PrefManager.getFloat(PREF_CAROUSEL_DEBUG_SIDE_OFFSET_RATIO, CAROUSEL_SIDE_OFFSET_RATIO))
    }
    var debugStepOffsetRatio by rememberSaveable {
        mutableFloatStateOf(PrefManager.getFloat(PREF_CAROUSEL_DEBUG_STEP_OFFSET_RATIO, CAROUSEL_STEP_OFFSET_RATIO))
    }

    val configuration = LocalConfiguration.current
    val horizontalPadding = AdaptivePadding.horizontal()
    val cardWidth = when (configuration.screenWidthDp) {
        in 0..700 -> 180.dp
        in 701..1100 -> 220.dp
        else -> 250.dp
    }
    val cardHeight = cardWidth * 1.5f
    val cardWidthPx = with(density) { cardWidth.toPx() }
    val centeredHorizontalPadding = ((configuration.screenWidthDp.dp - cardWidth) / 2).coerceAtLeast(horizontalPadding)
    val tiltAngleBase = if (isDebugBuild) debugTiltAngle else CAROUSEL_TILT_ANGLE
    val spacingRatio = if (isDebugBuild) debugSpacingRatio else CAROUSEL_SPACING_RATIO
    val cameraDistanceDp = if (isDebugBuild) debugCameraDistanceDp else CAROUSEL_CAMERA_DISTANCE_DP
    val sideOffsetRatio = if (isDebugBuild) debugSideOffsetRatio else CAROUSEL_SIDE_OFFSET_RATIO
    val stepOffsetRatio = if (isDebugBuild) debugStepOffsetRatio else CAROUSEL_STEP_OFFSET_RATIO
    val overlapSpacing = cardWidth * spacingRatio
    val overlapSpacingPx = with(density) { overlapSpacing.toPx() }
    val firstTileOffsetPx = cardWidthPx * 0.08f
    val cameraDistancePx = with(density) { cameraDistanceDp.dp.toPx() }
    val tunerMaxHeight = (configuration.screenHeightDp.dp * 0.72f).coerceAtLeast(320.dp)

    val centeredIndex by remember(state.appInfoList, listState.layoutInfo) {
        derivedStateOf {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf -1

            val viewportCenter =
                (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2f

            visibleItems
                .minByOrNull { itemInfo ->
                    abs((itemInfo.offset.x + itemInfo.size.width / 2f) - viewportCenter)
                }
                ?.index ?: -1
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
                .padding(paddingValues),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (state.appInfoList.isNotEmpty()) {
                    val flingBehavior = rememberSnapFlingBehavior(lazyGridState = listState)

                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(1),
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        flingBehavior = flingBehavior,
                        horizontalArrangement = Arrangement.spacedBy(overlapSpacing),
                        verticalArrangement = Arrangement.Center,
                        contentPadding = PaddingValues(
                            start = centeredHorizontalPadding,
                            end = centeredHorizontalPadding,
                            top = 80.dp,
                            bottom = 72.dp,
                        ),
                    ) {
                        items(
                            count = state.appInfoList.size,
                            key = { listIndex -> state.appInfoList[listIndex].appId },
                        ) { listIndex ->
                            val item = state.appInfoList[listIndex]
                            val itemLayoutInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listIndex }
                            val viewportCenter =
                                (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2f

                            val itemCenter = itemLayoutInfo?.let { info ->
                                info.offset.x + info.size.width / 2f
                            } ?: viewportCenter

                            val distanceFromCenter = itemCenter - viewportCenter
                            val normalizedDistance =
                                (distanceFromCenter / (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toFloat())
                                    .coerceIn(-1f, 1f)
                            val absDistance = abs(normalizedDistance)
                            val itemStepDistancePx = (cardWidthPx + overlapSpacingPx).coerceAtLeast(1f)
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
                            val tiltAngle = (tiltAngleBase * tiltMultiplier).coerceAtMost(79f)

                            val scale = interpolateByDistance(
                                distanceInSteps = distanceInSteps,
                                centerValue = 1.04f,
                                firstStepValue = 0.91f,
                                secondStepValue = 0.86f,
                                farValue = 0.8f,
                            )
                            val alpha = interpolateByDistance(
                                distanceInSteps = distanceInSteps,
                                centerValue = 1f,
                                firstStepValue = 0.88f,
                                secondStepValue = 0.8f,
                                farValue = 0.7f,
                            )
                            val rotationY = direction * tiltAngle
                            val translationX = if (direction == 0f) {
                                0f
                            } else {
                                val tiltInfluence = if (tiltAngleBase > 0.1f) tiltAngle / tiltAngleBase else 1f
                                val baseOffsetRatio = sideOffsetRatio + (distanceInSteps * stepOffsetRatio)
                                val baseShift = direction * cardWidthPx * baseOffsetRatio * tiltInfluence
                                val edgeOffset = if (listIndex == 0 && listState.firstVisibleItemIndex == 0) {
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
                                .alpha(if (isCentered) 1f else 0.96f)
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
                                    .width(cardWidth)
                                    .height(cardHeight)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        this.alpha = appItemAlpha
                                        this.rotationY = rotationY
                                        this.translationX = translationX
                                        cameraDistance = cameraDistancePx
                                        shape = RoundedCornerShape(12.dp)
                                        clip = true
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                AppItem(
                                    modifier = appItemModifier,
                                    appInfo = item,
                                    onClick = { onNavigate(item.appId) },
                                    onFocus = {
                                        onFocusedIndexChanged(listIndex)
                                        scope.launch {
                                            listState.animateScrollToItem(listIndex)
                                        }
                                    },
                                    paneType = PaneType.GRID_CAPSULE,
                                    imageRefreshCounter = state.imageRefreshCounter,
                                    compatibilityStatus = state.compatibilityMap[item.name],
                                )
                            }
                        }

                        if (state.appInfoList.size < state.totalAppsInFilter) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                } else if (state.isLoading) {
                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(1),
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.Center,
                        contentPadding = PaddingValues(
                            start = centeredHorizontalPadding,
                            end = centeredHorizontalPadding,
                            top = 80.dp,
                            bottom = 72.dp,
                        ),
                    ) {
                        items(6) {
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

                if (isDebugBuild) {
                    if (showDebugTuner) {
                        val scrollState = rememberScrollState()
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
                                .widthIn(max = 480.dp)
                                .heightIn(max = tunerMaxHeight),
                            tonalElevation = 6.dp,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(scrollState)
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Carousel Tuner (Debug)",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    IconButton(onClick = { showDebugTuner = false }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close tuner",
                                        )
                                    }
                                }
                                TextButton(
                                    modifier = Modifier.offset(x = (-12).dp),
                                    onClick = {
                                        debugTiltAngle = CAROUSEL_TILT_ANGLE
                                        debugSpacingRatio = CAROUSEL_SPACING_RATIO
                                        debugCameraDistanceDp = CAROUSEL_CAMERA_DISTANCE_DP
                                        debugSideOffsetRatio = CAROUSEL_SIDE_OFFSET_RATIO
                                        debugStepOffsetRatio = CAROUSEL_STEP_OFFSET_RATIO
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_TILT_ANGLE, CAROUSEL_TILT_ANGLE)
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_SPACING_RATIO, CAROUSEL_SPACING_RATIO)
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_CAMERA_DISTANCE_DP, CAROUSEL_CAMERA_DISTANCE_DP)
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_SIDE_OFFSET_RATIO, CAROUSEL_SIDE_OFFSET_RATIO)
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_STEP_OFFSET_RATIO, CAROUSEL_STEP_OFFSET_RATIO)
                                    },
                                ) {
                                    Text("Reset")
                                }
                                Text(
                                    text = "Tilt ${debugTiltAngle.toInt()}°",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = debugTiltAngle,
                                    onValueChange = {
                                        debugTiltAngle = it
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_TILT_ANGLE, it)
                                    },
                                    valueRange = 0f..75f,
                                )
                                Text(
                                    text = "Spacing ${(debugSpacingRatio * 100f).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = debugSpacingRatio,
                                    onValueChange = {
                                        debugSpacingRatio = it
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_SPACING_RATIO, it)
                                    },
                                    valueRange = -0.3f..0.25f,
                                )
                                Text(
                                    text = "Camera Dist ${debugCameraDistanceDp.toInt()}dp",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = debugCameraDistanceDp,
                                    onValueChange = {
                                        debugCameraDistanceDp = it
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_CAMERA_DISTANCE_DP, it)
                                    },
                                    valueRange = 6f..90f,
                                )
                                Text(
                                    text = "Side Offset ${(debugSideOffsetRatio * 100f).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = debugSideOffsetRatio,
                                    onValueChange = {
                                        debugSideOffsetRatio = it
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_SIDE_OFFSET_RATIO, it)
                                    },
                                    valueRange = 0f..0.4f,
                                )
                                Text(
                                    text = "Step Offset ${(debugStepOffsetRatio * 100f).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = debugStepOffsetRatio,
                                    onValueChange = {
                                        debugStepOffsetRatio = it
                                        PrefManager.setFloat(PREF_CAROUSEL_DEBUG_STEP_OFFSET_RATIO, it)
                                    },
                                    valueRange = 0f..0.25f,
                                )
                            }
                        }
                    } else {
                        TextButton(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 12.dp, bottom = 16.dp),
                            onClick = { showDebugTuner = true },
                        ) {
                            Text("Carousel Tuner")
                        }
                    }
                }
            }
        }
    }
}
