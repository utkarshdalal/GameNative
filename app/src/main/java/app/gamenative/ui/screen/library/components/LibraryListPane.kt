package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.service.DownloadService
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.component.Scrollbar
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.AdaptivePadding
import app.gamenative.ui.util.WindowWidthClass
import app.gamenative.ui.util.rememberWindowWidthClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import timber.log.Timber

/**
 * Calculates the installed games count based on the current filter state.
 *
 * @param state The current library state containing filters and visibility settings
 * @return The number of installed games, respecting current filters and source visibility
 */
private fun calculateInstalledCount(state: LibraryState): Int {
    // If INSTALLED filter is active, all items in the filtered list are installed
    if (state.appInfoSortType.contains(AppFilter.INSTALLED)) {
        return state.totalAppsInFilter
    }

    // Otherwise, count all installed games (respecting source visibility)
    val downloadDirectoryApps = DownloadService.getDownloadDirectoryApps()

    // Count installed Steam games
    val steamCount = if (state.showSteamInLibrary) {
        downloadDirectoryApps.count()
    } else {
        0
    }

    // Count Custom Games (always considered "installed")
    val customGameCount = if (state.showCustomGamesInLibrary) {
        PrefManager.customGamesCount
    } else {
        0
    }

    // Count GOG games that are installed (from PrefManager)
    val gogCount = if (state.showGOGInLibrary) {
        PrefManager.gogInstalledGamesCount
    } else {
        0
    }

    // Count Epic games that are installed (from PrefManager)
    val epicCount = if (state.showEpicInLibrary) {
        PrefManager.epicInstalledGamesCount
    } else {
        0
    }

    // Count Amazon games that are installed (from PrefManager)
    val amazonCount = if (state.showAmazonInLibrary) {
        PrefManager.amazonInstalledGamesCount
    } else {
        0
    }

    return steamCount + customGameCount + gogCount + epicCount + amazonCount
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryListPane(
    state: LibraryState,
    listState: LazyGridState,
    currentLayout: PaneType,
    firstGridItemFocusRequester: FocusRequester? = null,
    focusTargetListIndex: Int? = null,
    onPageChange: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackBarHost = remember { SnackbarHostState() }

    // Calculate installed count based on current filter state
    val installedCount = remember(
        state.appInfoSortType,
        state.showSteamInLibrary,
        state.showCustomGamesInLibrary,
        state.showGOGInLibrary,
        state.showEpicInLibrary,
        state.showAmazonInLibrary,
        state.totalAppsInFilter,
    ) {
        calculateInstalledCount(state)
    }

    val pullToRefreshState = rememberPullToRefreshState()
    val windowWidthClass = rememberWindowWidthClass()

    val columnType = remember(currentLayout, windowWidthClass) {
        when (currentLayout) {
            PaneType.GRID_HERO -> {
                val minSize = when (windowWidthClass) {
                    WindowWidthClass.COMPACT -> 160.dp
                    WindowWidthClass.MEDIUM -> 180.dp
                    WindowWidthClass.EXPANDED -> 200.dp
                }
                GridCells.Adaptive(minSize = minSize)
            }

            PaneType.GRID_CAPSULE -> {
                val minSize = when (windowWidthClass) {
                    WindowWidthClass.COMPACT -> 110.dp
                    WindowWidthClass.MEDIUM -> 130.dp
                    WindowWidthClass.EXPANDED -> 150.dp
                }
                GridCells.Adaptive(minSize = minSize)
            }

            else -> GridCells.Fixed(1)
        }
    }

    val horizontalPadding = AdaptivePadding.horizontal()
    val gridSpacing = AdaptivePadding.gridSpacing()

    LaunchedEffect(listState, state.appInfoList.size) {
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

    var targetOfScroll by remember { mutableIntStateOf(-1) }
    LaunchedEffect(targetOfScroll) {
        if (targetOfScroll != -1) {
            listState.animateScrollToItem(targetOfScroll, -100)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackBarHost) },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            var shouldShowSkeletonOverlay by remember { mutableStateOf(true) }

            val skeletonAlpha by animateFloatAsState(
                targetValue = if (shouldShowSkeletonOverlay) 1f else 0f,
                animationSpec = tween(durationMillis = 300),
                label = "skeletonFadeOut",
            )

            LaunchedEffect(state.isLoading, state.appInfoList.size, state.totalAppsInFilter) {
                shouldShowSkeletonOverlay = when {
                    state.totalAppsInFilter == 0 -> false
                    state.isLoading && state.appInfoList.isEmpty() -> true
                    state.appInfoList.isNotEmpty() && !state.isLoading -> {
                        delay(100)
                        false
                    }
                    else -> false
                }
            }

            val totalSkeletonCount = remember(state.showSteamInLibrary, state.showCustomGamesInLibrary, state.showGOGInLibrary, state.showEpicInLibrary, state.showAmazonInLibrary) {
                val customCount = if (state.showCustomGamesInLibrary) PrefManager.customGamesCount else 0
                val steamCount = if (state.showSteamInLibrary) PrefManager.steamGamesCount else 0
                val gogInstalledCount = if (state.showGOGInLibrary) PrefManager.gogInstalledGamesCount else 0
                val epicInstalledCount = if (state.showEpicInLibrary) PrefManager.epicInstalledGamesCount else 0
                val amazonInstalledCount = if (state.showAmazonInLibrary) PrefManager.amazonInstalledGamesCount else 0
                val total = customCount + steamCount + gogInstalledCount + epicInstalledCount + amazonInstalledCount
                Timber.tag("LibraryListPane").d("Skeleton calculation - Custom: $customCount, Steam: $steamCount, GOG installed: $gogInstalledCount, Epic installed: $epicInstalledCount, Amazon installed: $amazonInstalledCount, Total: $total")
                // Show at least a few skeletons, but not more than a reasonable amount
                if (total == 0) 6 else minOf(total, 20)
            }

            if (state.appInfoList.isNotEmpty()) {
                Scrollbar(
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = onRefresh,
                        state = pullToRefreshState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyVerticalGrid(
                            columns = columnType,
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                            contentPadding = PaddingValues(
                                top = 80.dp,
                                start = horizontalPadding,
                                end = horizontalPadding + 4.dp,
                                bottom = 72.dp,
                            ),
                        ) {
                            items(
                                count = state.appInfoList.size,
                                key = { listIndex -> state.appInfoList[listIndex].appId },
                            ) { listIndex ->
                                val item = state.appInfoList[listIndex]
                                var isVisible by remember(item.index) { mutableStateOf(false) }
                                val alpha by animateFloatAsState(
                                    targetValue = if (isVisible) 1f else 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessLow,
                                    ),
                                    label = "fadeIn",
                                )

                                LaunchedEffect(item.index) {
                                    delay((item.index % 8) * 30L)
                                    isVisible = true
                                }

                                Box(modifier = Modifier.alpha(alpha)) {
                                    val appItemModifier = if (firstGridItemFocusRequester != null &&
                                        focusTargetListIndex != null &&
                                        listIndex == focusTargetListIndex
                                    ) {
                                        Modifier.focusRequester(firstGridItemFocusRequester)
                                    } else {
                                        Modifier
                                    }

                                    if (item.index > 0 && currentLayout == PaneType.LIST) {
                                        HorizontalDivider()
                                    }
                                    AppItem(
                                        modifier = appItemModifier,
                                        appInfo = item,
                                        onClick = { onNavigate(item.appId) },
                                        paneType = currentLayout,
                                        onFocus = { targetOfScroll = item.index },
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
                    }
                }
            }

            val skeletonListState = remember { LazyGridState() }
            if (skeletonAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(skeletonAlpha)
                        .pointerInteropFilter { false },
                ) {
                    LazyVerticalGrid(
                        columns = columnType,
                        state = skeletonListState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                        contentPadding = PaddingValues(
                            top = 80.dp,
                            start = horizontalPadding,
                            end = horizontalPadding,
                            bottom = 72.dp,
                        ),
                    ) {
                        items(totalSkeletonCount) { index ->
                            if (index > 0 && currentLayout == PaneType.LIST) {
                                HorizontalDivider()
                            }
                            GameSkeletonLoader(
                                paneType = currentLayout,
                            )
                        }
                    }
                }
            }
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(device = "spec:width=1920px,height=1080px,dpi=440") // Odin2 Mini
@Composable
private fun Preview_LibraryListPane() {
    val context = LocalContext.current
    PrefManager.init(context)
    val state = remember {
        LibraryState(
            appInfoList = List(15) { idx ->
                val item = fakeAppInfo(idx)
                LibraryItem(
                    index = idx,
                    appId = "${GameSource.STEAM.name}_${item.id}",
                    name = item.name,
                    iconHash = item.iconHash,
                    isShared = idx % 2 == 0,
                )
            },
        )
    }
    PluviaTheme {
        Surface {
            LibraryListPane(
                listState = LazyGridState(2),
                state = state,
                currentLayout = PaneType.GRID_HERO,
                onPageChange = { },
                onNavigate = { },
                onRefresh = { },
            )
        }
    }
}
