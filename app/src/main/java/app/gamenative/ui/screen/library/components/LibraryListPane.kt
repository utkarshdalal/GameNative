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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.derivedStateOf
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
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.component.topbar.AccountButton
import app.gamenative.utils.CustomGameScanner
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import app.gamenative.PrefManager
import app.gamenative.utils.DeviceUtils
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import app.gamenative.data.GameSource
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.screen.PluviaScreen
import timber.log.Timber

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
    onSourceToggle: (GameSource) -> Unit,
    isOffline: Boolean = false,
) {
    val context = LocalContext.current
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val snackBarHost = remember { SnackbarHostState() }
    val installedCount = remember {
        val steamCount = DownloadService.getDownloadDirectoryApps().count()
        
        // Custom Games are always considered "installed" since they're external folders
        val customGameCount = CustomGameScanner.scanAsLibraryItems(query = "").count()
        steamCount + customGameCount
    }

    // Responsive width for better layouts
    val isViewWide = DeviceUtils.isViewWide(currentWindowAdaptiveInfo())

    // List view is always 1 column
    var columnType: GridCells by remember { mutableStateOf(GridCells.Fixed(1)) }
    var paneType: PaneType by remember { mutableStateOf(PrefManager.libraryLayout) }

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

        // How many columns does this view need? 1 for list, or adaptive which can handle rotating the device
        columnType = GridCells.Fixed(1)
        if (paneType == PaneType.GRID_HERO) {
            columnType = GridCells.Adaptive(minSize = 200.dp)
        } else if (paneType == PaneType.GRID_CAPSULE) {
            columnType = GridCells.Adaptive(minSize = 150.dp)
        }
    }

    var targetOfScroll by remember { mutableIntStateOf(-1) }
    LaunchedEffect(targetOfScroll) {
        if (targetOfScroll != -1) {
            listState.animateScrollToItem(targetOfScroll, -100)
        }
    }

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
                    .padding(16.dp)
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
                            text = "${state.totalAppsInFilter} games • $installedCount installed",
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
                        if (item.index > 0 && paneType == PaneType.LIST) {
                            // Dividers in list view
                            HorizontalDivider()
                        }
                        AppItem(
                            appInfo = item,
                            onClick = { onNavigate(item.appId) },
                            paneType = paneType,
                            onFocus = { targetOfScroll = item.index },
                        )
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

                // Filter FAB - always show
                if (!state.isSearching) {
                    ExtendedFloatingActionButton(
                        text = { Text(text = "Filters") },
                        expanded = expandedFab,
                        icon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null) },
                        onClick = { onModalBottomSheet(true) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(24.dp)
                    )
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
                onSourceToggle = { },
            )
        }
    }
}
