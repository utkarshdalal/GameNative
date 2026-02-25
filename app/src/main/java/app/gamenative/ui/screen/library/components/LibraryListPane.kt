package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.data.LibraryItem
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.component.topbar.AccountButton
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInteropFilter
import kotlinx.coroutines.delay
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import app.gamenative.PrefManager
import app.gamenative.utils.DeviceUtils
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import app.gamenative.data.GameSource
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.utils.PaddingUtils
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
    sheetState: SheetState,
    onFilterChanged: (AppFilter) -> Unit,
    onModalBottomSheet: (Boolean) -> Unit,
    onPageChange: (Int) -> Unit,
    onIsSearching: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onNavigate: (String) -> Unit,
    onSearchQuery: (String) -> Unit,
    onNavigateRoute: (String) -> Unit,
    onGoOnline: () -> Unit,
    onRefresh: () -> Unit,
    onSourceToggle: (GameSource) -> Unit,
    isOffline: Boolean = false,
) {
    val context = LocalContext.current
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




    // Responsive width for better layouts
    val isViewWide = DeviceUtils.isViewWide(currentWindowAdaptiveInfo())

    var paneType: PaneType by remember { mutableStateOf(PrefManager.libraryLayout) }
    val columnType = remember(paneType) {
        when (paneType) {
            PaneType.GRID_HERO -> GridCells.Adaptive(minSize = 200.dp)
            PaneType.GRID_CAPSULE -> GridCells.Adaptive(minSize = 150.dp)
            else -> GridCells.Fixed(1)
        }
    }

    // Infinite scroll: load next page when scrolled to bottom
    LaunchedEffect(listState, state.appInfoList.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex >= state.appInfoList.lastIndex
                    && state.appInfoList.size < state.totalAppsInFilter) {
                    onPageChange(1)
                }
            }
    }

    LaunchedEffect(isViewWide, paneType) {
        // Set initial paneType at first launch depending on orientation
        if (paneType == PaneType.UNDECIDED) {
            // Default hero for landscape/tablets, or list for portrait phones
            if (isViewWide) {
                paneType = PaneType.GRID_HERO
            } else {
                paneType = PaneType.GRID_CAPSULE
            }
            PrefManager.libraryLayout = paneType
        }

    }

    var targetOfScroll by remember { mutableIntStateOf(-1) }
    LaunchedEffect(targetOfScroll) {
        if (targetOfScroll != -1) {
            listState.animateScrollToItem(targetOfScroll, -100)
        }
    }

    val headerTopPadding = PaddingUtils.statusBarAwarePadding().calculateTopPadding()

    Scaffold(
        snackbarHost = { SnackbarHost(snackBarHost) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            // Modern Header with gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(vertical = headerTopPadding)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "GameNative",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            )
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                app.gamenative.R.string.library_game_count,
                                state.totalAppsInFilter,
                                installedCount
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isViewWide) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 30.dp)
                        ) {
                            LibrarySearchBar(
                                state = state,
                                listState = listState,
                                onSearchQuery = onSearchQuery,
                            )
                        }
                    }

                    // User profile button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(8.dp)
                    ) {
                        AccountButton(
                            onNavigateRoute = onNavigateRoute,
                            onLogout = onLogout,
                            onGoOnline = onGoOnline,
                            isOffline = isOffline,
                        )
                    }
                }
            }

            if (! isViewWide) {
                // Search bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    LibrarySearchBar(
                        state = state,
                        listState = listState,
                        onSearchQuery = onSearchQuery,
                    )
                }
            }

            // Game list
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Track skeleton overlay alpha (fade out when games are loaded)
                // Show skeleton overlay when loading OR when list is empty (initial state)
                // But hide if final count is 0 (no games match filters)
                var shouldShowSkeletonOverlay by remember {
                    mutableStateOf(true) // Start visible
                }

                // Fade out skeleton overlay when games appear
                val skeletonAlpha by animateFloatAsState(
                    targetValue = if (shouldShowSkeletonOverlay) 1f else 0f,
                    animationSpec = tween(durationMillis = 300),
                    label = "skeletonFadeOut"
                )

                // Update skeleton overlay visibility based on loading state and games
                LaunchedEffect(state.isLoading, state.appInfoList.size, state.totalAppsInFilter) {
                    // Hide skeleton loaders if final count is 0 (no games match filters)
                    if (state.totalAppsInFilter == 0 && !state.isLoading) {
                        shouldShowSkeletonOverlay = false
                    } else if (state.isLoading && state.appInfoList.isEmpty() && state.totalAppsInFilter > 0) {
                        // Still loading and we expect games, show skeleton overlay
                        shouldShowSkeletonOverlay = true
                    } else if (state.appInfoList.isNotEmpty() && !state.isLoading) {
                        // Games are loaded and loading is complete, start fading out skeleton overlay
                        delay(100) // Small delay to let games render and fade in
                        shouldShowSkeletonOverlay = false
                    } else if (!state.isLoading && state.appInfoList.isEmpty() && state.totalAppsInFilter == 0) {
                        // Loading complete but no games (filters exclude everything), hide skeletons
                        shouldShowSkeletonOverlay = false
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

                // Show actual games (base layer)
                if (state.appInfoList.isNotEmpty()) {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = onRefresh,
                        state = pullToRefreshState
                    ) {
                        LazyVerticalGrid(
                            columns = columnType,
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                bottom = 72.dp
                            ),
                        ) {
                            items(items = state.appInfoList, key = { it.index }) { item ->
                                // Fade-in animation for items
                                var isVisible by remember(item.index) { mutableStateOf(false) }
                                val alpha by animateFloatAsState(
                                    targetValue = if (isVisible) 1f else 0f,
                                    animationSpec = tween(durationMillis = 300),
                                    label = "fadeIn"
                                )

                                LaunchedEffect(item.index) {
                                    isVisible = true
                                }

                                Box(modifier = Modifier.alpha(alpha)) {
                                    if (item.index > 0 && paneType == PaneType.LIST) {
                                        // Dividers in list view
                                        HorizontalDivider()
                                    }
                                    AppItem(
                                        appInfo = item,
                                        onClick = { onNavigate(item.appId) },
                                        paneType = paneType,
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
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }
                }

                // Skeleton loaders as overlay (fades out when games are loaded)
                // Use a separate non-interactive state so it doesn't interfere with scrolling
                val skeletonListState = remember { LazyGridState() }
                if (skeletonAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(skeletonAlpha)
                            .pointerInteropFilter { false } // Non-interactive - allows touch events to pass through
                    ) {
                        LazyVerticalGrid(
                            columns = columnType,
                            state = skeletonListState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                bottom = 72.dp
                            ),
                        ) {
                            items(totalSkeletonCount) { index ->
                                if (index > 0 && paneType == PaneType.LIST) {
                                    HorizontalDivider()
                                }
                                GameSkeletonLoader(
                                    paneType = paneType,
                                )
                            }
                        }
                    }
                }

                if (state.modalBottomSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { onModalBottomSheet(false) },
                        sheetState = sheetState,
                        content = {
                            LibraryBottomSheet(
                                selectedFilters = state.appInfoSortType,
                                onFilterChanged = onFilterChanged,
                                currentView = paneType,
                                onViewChanged = { newPaneType ->
                                    PrefManager.libraryLayout = newPaneType
                                    paneType = newPaneType
                                },
                                showSteam = state.showSteamInLibrary,
                                showCustomGames = state.showCustomGamesInLibrary,
                                showGOG = state.showGOGInLibrary,
                                showEpic = state.showEpicInLibrary,
                                showAmazon = state.showAmazonInLibrary,
                                onSourceToggle = onSourceToggle,
                            )
                        },
                    )
                }
            }
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@OptIn(ExperimentalMaterial3Api::class)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(device = "spec:width=1920px,height=1080px,dpi=440") // Odin2 Mini
@Composable
private fun Preview_LibraryListPane() {
    val context = LocalContext.current
    PrefManager.init(context)
    val sheetState = rememberModalBottomSheetState()
    var state by remember {
        mutableStateOf(
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
            ),
        )
    }
    PluviaTheme {
        Surface {
            LibraryListPane(
                listState = LazyGridState(2),
                state = state,
                sheetState = sheetState,
                onFilterChanged = { },
                onPageChange = { },
                onModalBottomSheet = {
                    val currentState = state.modalBottomSheet
                    println("State: $currentState")
                    state = state.copy(modalBottomSheet = !currentState)
                },
                onIsSearching = { },
                onSearchQuery = { },
                onNavigateRoute = { },
                onLogout = { },
                onNavigate = { },
                onGoOnline = { },
                onRefresh = { },
                onSourceToggle = { },
            )
        }
    }
}
