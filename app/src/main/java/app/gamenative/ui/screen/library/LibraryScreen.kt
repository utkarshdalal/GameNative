package app.gamenative.ui.screen.library

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import app.gamenative.ui.screen.library.components.LibraryBottomSheet
import app.gamenative.ui.enums.PaneType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.Orientation
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.model.LibraryViewModel
import app.gamenative.ui.screen.library.components.LibraryDetailPane
import app.gamenative.ui.screen.library.components.LibraryListPane
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.components.rememberCustomGameFolderPicker
import app.gamenative.ui.components.requestPermissionsForPath
import app.gamenative.utils.CustomGameScanner
import app.gamenative.theme.runtime.FixedElementCallbacks
import app.gamenative.theme.runtime.LocalSpatialFocusManager
import app.gamenative.theme.runtime.RenderFixedElements
import app.gamenative.theme.runtime.SpatialFocusManager
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onClickPlay: (String, Boolean) -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onGoOnline: () -> Unit,
    isOffline: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Read configuration from MainActivity state (not LocalConfiguration)
    // This is needed because android:configChanges prevents LocalConfiguration from updating
    val currentOrientation = app.gamenative.MainActivity.currentOrientation.value
    val currentScreenWidthDp = app.gamenative.MainActivity.currentScreenWidthDp.value
    val configChangeCount = app.gamenative.MainActivity.configurationChangeCounter.value
    val orientationTrigger = "$currentOrientation-$currentScreenWidthDp-$configChangeCount"
    
    // Key on orientation to force full recomposition when configuration changes
    key(orientationTrigger) {
    LibraryScreenContent(
        state = state,
        listState = viewModel.listState,
        carouselPageIndex = viewModel.carouselPageIndex,
        onCarouselPageChanged = { viewModel.carouselPageIndex = it },
        sheetState = sheetState,
        onFilterChanged = viewModel::onFilterChanged,
        onPageChange = viewModel::onPageChange,
        onModalBottomSheet = viewModel::onModalBottomSheet,
        onIsSearching = viewModel::onIsSearching,
        onSearchQuery = viewModel::onSearchQuery,
        onRefresh = viewModel::onRefresh,
        onClickPlay = onClickPlay,
        onNavigateRoute = onNavigateRoute,
        onLogout = onLogout,
        onGoOnline = onGoOnline,
        onSourceToggle = viewModel::onSourceToggle,
        onAddCustomGameFolder = viewModel::addCustomGameFolder,
        isOffline = isOffline,
    )
    } // end key(orientationTrigger)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenContent(
    state: LibraryState,
    listState: LazyGridState,
    carouselPageIndex: Int,
    onCarouselPageChanged: (Int) -> Unit,
    sheetState: SheetState,
    onFilterChanged: (AppFilter) -> Unit,
    onPageChange: (Int) -> Unit,
    onModalBottomSheet: (Boolean) -> Unit,
    onIsSearching: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onClickPlay: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onGoOnline: () -> Unit,
    onSourceToggle: (GameSource) -> Unit,
    onAddCustomGameFolder: (String) -> Unit,
    isOffline: Boolean = false,
) {
    val context = LocalContext.current
    var selectedAppId by remember { mutableStateOf<String?>(null) }
    val filterFabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    // Dialog state for add custom game prompt
    var showAddCustomGameDialog by remember { mutableStateOf(false) }
    var dontShowAgain by remember { mutableStateOf(false) }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val folderPicker = rememberCustomGameFolderPicker(
        onPathSelected = { path ->
            // When a folder is selected via OpenDocumentTree, the user has already granted
            // URI permissions for that specific folder. We should verify we can access it
            // rather than checking for broad storage permissions.
            val folder = java.io.File(path)
            val canAccess = try {
                folder.exists() && (folder.isDirectory && folder.canRead())
            } catch (e: Exception) {
                false
            }

            // Only request permissions if we can't access the folder AND it's outside the sandbox
            // (folders selected via OpenDocumentTree should already be accessible)
            if (!canAccess && !CustomGameScanner.hasStoragePermission(context, path)) {
                requestPermissionsForPath(context, path, storagePermissionLauncher)
            }
            onAddCustomGameFolder(path)
        },
        onFailure = { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        },
    )

    // Handle opening folder picker (with dialog check)
    val onAddCustomGameClick = {
        if (PrefManager.showAddCustomGameDialog) {
            showAddCustomGameDialog = true
        } else {
            folderPicker.launchPicker()
        }
    }

    BackHandler(selectedAppId != null) { selectedAppId = null }

    // Refresh list when navigating back from detail view
    LaunchedEffect(selectedAppId) {
        if (selectedAppId == null) {
            // Trigger refresh by calling onSearchQuery with current query
            // This will call onFilterApps() which re-scans Custom Games
            val currentQuery = state.searchQuery
            onSearchQuery(currentQuery)
        }
    }

    // Apply top padding differently for list vs game detail pages.
    // On the game page we want to hide the top padding when the status bar is hidden.
    val safePaddingModifier = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
        if (selectedAppId != null) {
            // Detail (game) page: use actual status bar height when status bar is visible,
            // or 0.dp when status bar is hidden
            val topPadding = if (PrefManager.hideStatusBarWhenNotInGame) {
                0.dp
            } else {
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            }
            Modifier.padding(top = topPadding)
        } else {
            // List page keeps safe cutout padding (for notches)
            Modifier.displayCutoutPadding()
        }
    } else Modifier

    // Collect the active ThemeDefinition and dev reload tick (DEBUG only)
    val activeTheme by app.gamenative.theme.ThemeManager.activeTheme.collectAsStateWithLifecycle()
    val reloadTick by app.gamenative.theme.ThemeManager.reloadTick.collectAsStateWithLifecycle()
    val themeRootDir by app.gamenative.theme.ThemeManager.activeThemeRootDir.collectAsStateWithLifecycle()

    // Trigger theme remapping when orientation changes (for breakpoint-aware variables)
    app.gamenative.theme.runtime.OrientationAwareThemeEffect()
    
    // Get orientation key for forcing recomposition when orientation changes
    val orientationKey = app.gamenative.theme.runtime.rememberOrientationKey()

    // Focus manager for clearing search bar focus when tapping elsewhere
    val focusManager = LocalFocusManager.current

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(safePaddingModifier)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                })
            }
    ) {
        if (selectedAppId == null) {
            val def = activeTheme
            val useThemeUi = PrefManager.useThemeEngineUi
            if (useThemeUi && def != null) {
                // Key on orientation to force full recomposition when orientation changes
                // This ensures all positioning from theme breakpoints is properly applied
                key(orientationKey, reloadTick) {
                // Create spatial focus manager for position-based controller navigation
                val spatialFocusManager = remember { SpatialFocusManager() }
                
                // Provide spatial focus manager to all themed components
                CompositionLocalProvider(LocalSpatialFocusManager provides spatialFocusManager) {
                // Render themed layout using the Theme Engine (experimental)
                val cards = remember(def.cards, reloadTick) { def.cards.associateBy { it.id } }

                // Helper function to resolve Steam/custom game images
                fun findSteamGridDBImage(item: LibraryItem, imageType: String): String? {
                    if (item.gameSource == GameSource.CUSTOM_GAME) {
                        val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(item.appId)
                        gameFolderPath?.let { path ->
                            val folder = java.io.File(path)
                            val imageFile = folder.listFiles()?.firstOrNull { file ->
                                file.name.startsWith("steamgriddb_$imageType") &&
                                        (file.name.endsWith(".png", true) || file.name.endsWith(".jpg", true) || file.name.endsWith(".webp", true))
                            }
                            return imageFile?.let { android.net.Uri.fromFile(it).toString() }
                        }
                    }
                    return null
                }
                
                // Helper function to find the full hero image (not grid_hero) for custom games
                fun findSteamGridDBHeroImage(item: LibraryItem): String? {
                    if (item.gameSource == GameSource.CUSTOM_GAME) {
                        val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(item.appId)
                        gameFolderPath?.let { path ->
                            val folder = java.io.File(path)
                            val imageFile = folder.listFiles()?.firstOrNull { file ->
                                file.name.startsWith("steamgriddb_hero") &&
                                        !file.name.contains("grid") &&
                                        (file.name.endsWith(".png", true) || file.name.endsWith(".jpg", true) || file.name.endsWith(".webp", true))
                            }
                            return imageFile?.let { android.net.Uri.fromFile(it).toString() }
                        }
                    }
                    return null
                }

                // Create binding provider that maps LibraryItem to binding values
                val bindingProvider: (LibraryItem) -> Map<String, String> = remember(state.compatibilityMap, reloadTick) {
                    { item: LibraryItem ->
                        val title = item.name
                        val capsuleUrl = when (item.gameSource) {
                            GameSource.CUSTOM_GAME ->
                                findSteamGridDBImage(item, "grid_capsule")
                                    ?: (if (item.iconHash.isNotEmpty()) "https://shared.steamstatic.com/store_item_assets/steam/apps/${item.gameId}/library_600x900.jpg" else "")
                            GameSource.STEAM -> "https://shared.steamstatic.com/store_item_assets/steam/apps/${item.gameId}/library_600x900.jpg"
                            else -> ""
                        }
                        val heroUrl = when (item.gameSource) {
                            GameSource.CUSTOM_GAME ->
                                findSteamGridDBImage(item, "grid_hero")
                                    ?: (if (item.iconHash.isNotEmpty()) "https://shared.steamstatic.com/store_item_assets/steam/apps/${item.gameId}/header.jpg" else "")
                            GameSource.STEAM -> "https://shared.steamstatic.com/store_item_assets/steam/apps/${item.gameId}/header.jpg"
                            else -> ""
                        }
                        // Library hero - the large 1920x620 banner used on game info screens
                        val libraryHeroUrl = when (item.gameSource) {
                            GameSource.CUSTOM_GAME ->
                                findSteamGridDBHeroImage(item)
                                    ?: findSteamGridDBImage(item, "grid_hero")
                                    ?: (if (item.iconHash.isNotEmpty()) "https://shared.steamstatic.com/store_item_assets/steam/apps/${item.gameId}/library_hero.jpg" else "")
                            GameSource.STEAM -> "https://shared.steamstatic.com/store_item_assets/steam/apps/${item.gameId}/library_hero.jpg"
                            else -> ""
                        }
                        val coverUrl = item.clientIconUrl

                        // Compatibility status bindings
                        val compatStatus = state.compatibilityMap[item.name]
                        val (compatLabel, compatColor) = when (compatStatus) {
                            GameCompatibilityStatus.COMPATIBLE -> context.getString(R.string.library_compatible) to "#FF00C853"
                            GameCompatibilityStatus.GPU_COMPATIBLE -> context.getString(R.string.library_compatible) to "#FF00C853"
                            GameCompatibilityStatus.NOT_COMPATIBLE -> context.getString(R.string.library_not_compatible) to "#FFFF1744"
                            GameCompatibilityStatus.UNKNOWN -> context.getString(R.string.library_compatibility_unknown) to "#FF888888"
                            null -> "" to "#00000000"
                        }

                        // Check if game is installed
                        val isInstalled = when (item.gameSource) {
                            GameSource.STEAM -> SteamService.isAppInstalled(item.gameId)
                            GameSource.CUSTOM_GAME -> true // Custom games are always "installed"
                            else -> false
                        }
                        val installStatusLabel = if (isInstalled) {
                            context.getString(R.string.library_installed)
                        } else {
                            context.getString(R.string.library_not_installed)
                        }
                        val installStatusColor = if (isInstalled) "#FF00C853" else "#FF888888"

                        mapOf(
                            "game.title" to title,
                            "game.cover" to coverUrl,
                            "game.capsule" to capsuleUrl,
                            "game.hero" to heroUrl,
                            "game.libraryHero" to libraryHeroUrl,
                            "game.appId" to item.appId,
                            "game.compatibility.label" to compatLabel,
                            "game.compatibility.color" to compatColor,
                            "game.compatibility.visible" to if (compatStatus != null) "true" else "false",
                            "game.isInstalled" to isInstalled.toString(),
                            "game.installStatus" to installStatusLabel,
                            "game.installStatus.color" to installStatusColor,
                        )
                    }
                }

                // Fixed element callbacks (shared by top and bottom)
                val fixedCallbacks = FixedElementCallbacks(
                    onNavigateRoute = onNavigateRoute,
                    onLogout = onLogout,
                    onGoOnline = onGoOnline,
                    onFilterClick = { onModalBottomSheet(true) },
                    onAddClick = onAddCustomGameClick,
                    onSearchQuery = onSearchQuery,
                    isOffline = isOffline,
                    filterExpanded = filterFabExpanded,
                    isSearching = state.isSearching,
                )
                
                val accountButtonContent: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { iconSize ->
                    app.gamenative.ui.component.topbar.AccountButton(
                        onNavigateRoute = onNavigateRoute,
                        onLogout = onLogout,
                        onGoOnline = onGoOnline,
                        isOffline = isOffline,
                        iconSize = iconSize,
                    )
                }
                
                val searchBarContent: @Composable (app.gamenative.ui.screen.library.components.SearchBarStyle) -> Unit = { style ->
                    app.gamenative.ui.screen.library.components.LibrarySearchBar(
                        state = state,
                        listState = listState,
                        onSearchQuery = onSearchQuery,
                        style = style,
                    )
                }
                
                // Render themed layout FIRST (at the back, so fixed elements are on top)
                val layout = def.layout
                when (layout) {
                    is app.gamenative.theme.model.LayoutNode.Grid -> {
                    val card = cards[layout.itemCard]
                    if (card != null) {
                        app.gamenative.theme.runtime.ThemedGameGrid(
                            items = state.appInfoList,
                            gridConfig = layout,
                            card = card,
                            listState = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(safePaddingModifier),
                            onItemClick = { item -> selectedAppId = item.appId },
                            onItemFocus = { /* Can be used for preview pane later */ },
                            bindingProvider = bindingProvider,
                            themePath = app.gamenative.theme.ThemeManager.getActiveThemeAssetPath(),
                        )
                    }
                    }
                    is app.gamenative.theme.model.LayoutNode.Carousel -> {
                        val card = cards[layout.itemCard]
                        if (card != null) {
                            app.gamenative.theme.runtime.ThemedGameCarousel(
                                items = state.appInfoList,
                                carouselConfig = layout,
                                card = card,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(safePaddingModifier),
                                initialPage = carouselPageIndex,
                                onPageChanged = onCarouselPageChanged,
                                onItemClick = { item -> selectedAppId = item.appId },
                                onItemFocus = { /* Can be used for preview pane later */ },
                                bindingProvider = bindingProvider,
                                themePath = app.gamenative.theme.ThemeManager.getActiveThemeAssetPath(),
                            )
                        }
                    }
                    else -> {
                        // Fallback to basic ThemeLayout for Canvas layouts
                    val baseBinding = remember(reloadTick) {
                        app.gamenative.theme.runtime.MapBindingContext()
                    }
                    val itemBindingProvider = remember(state.appInfoList, reloadTick) {
                        { index: Int ->
                            val item = state.appInfoList.getOrNull(index)
                            val bindings = item?.let { bindingProvider(it) } ?: emptyMap()
                            app.gamenative.theme.runtime.MapBindingContext(strings = bindings)
                        }
                    }
                    app.gamenative.theme.runtime.ThemeLayout(
                        layout = layout,
                        cards = cards,
                        binding = baseBinding,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(safePaddingModifier)
                            .padding(8.dp),
                        itemBindingProvider = itemBindingProvider,
                    )
                }
                }

                // Render fixed elements ON TOP of the content (later in Box = higher z-order)
                // All containers are rendered in declaration order
                RenderFixedElements(
                    fixedContainers = def.fixedContainers,
                    state = state,
                    listState = listState,
                    themeName = def.manifest.id,
                    callbacks = fixedCallbacks,
                    accountButtonContent = accountButtonContent,
                    searchBarContent = searchBarContent,
                    position = app.gamenative.theme.runtime.FixedContainerPosition.ALL,
                    themeRootDir = themeRootDir,
                )
                } // end CompositionLocalProvider
                } // end key(orientationKey)
            } else {
                // Legacy interactive Library list
                LibraryListPane(
                    state = state,
                    listState = listState,
                    sheetState = sheetState,
                    onFilterChanged = onFilterChanged,
                    onPageChange = onPageChange,
                    onModalBottomSheet = onModalBottomSheet,
                    onIsSearching = onIsSearching,
                    onSearchQuery = onSearchQuery,
                    onNavigateRoute = onNavigateRoute,
                    onLogout = onLogout,
                    onNavigate = { appId -> selectedAppId = appId },
                    onGoOnline = onGoOnline,
                    onRefresh = onRefresh,
                    onSourceToggle = onSourceToggle,
                    isOffline = isOffline,
                )
            }
        } else {
            // Find the LibraryItem from the state based on selectedAppId
            val selectedLibraryItem = selectedAppId?.let { appId ->
                state.appInfoList.find { it.appId == appId }
            }

            LibraryDetailPane(
                libraryItem = selectedLibraryItem,
                onBack = { selectedAppId = null },
                onClickPlay = {
                    selectedLibraryItem?.let { libraryItem ->
                        onClickPlay(libraryItem.appId, it)
                    }
                },
            )
        }

        // FABs for legacy view (themed view handles these via FixedElementRenderer)
        val useThemeUiForFabs = PrefManager.useThemeEngineUi && app.gamenative.theme.ThemeManager.activeTheme.value != null
        if (selectedAppId == null && !useThemeUiForFabs) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!state.isSearching) {
                    ExtendedFloatingActionButton(
                        text = { Text(text = stringResource(R.string.library_filters)) },
                        icon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null) },
                        expanded = filterFabExpanded,
                        onClick = { onModalBottomSheet(true) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }

                FloatingActionButton(
                    onClick = onAddCustomGameClick,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_custom_game_content_desc),
                    )
                }
            }
        }

        // Add custom game dialog
        if (showAddCustomGameDialog) {
            AlertDialog(
                onDismissRequest = { showAddCustomGameDialog = false },
                title = { Text(stringResource(R.string.add_custom_game_dialog_title)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.add_custom_game_dialog_message),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = dontShowAgain,
                                onCheckedChange = { dontShowAgain = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.add_custom_game_dont_show_again),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (dontShowAgain) {
                                PrefManager.showAddCustomGameDialog = false
                            }
                            showAddCustomGameDialog = false
                            folderPicker.launchPicker()
                        }
                    ) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showAddCustomGameDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Filter bottom sheet - shown for themed views (LibraryListPane handles its own)
        val useThemeUi = PrefManager.useThemeEngineUi
        if (useThemeUi && state.modalBottomSheet && selectedAppId == null) {
            ModalBottomSheet(
                onDismissRequest = { onModalBottomSheet(false) },
                sheetState = sheetState,
            ) {
                LibraryBottomSheet(
                    selectedFilters = state.appInfoSortType,
                    onFilterChanged = onFilterChanged,
                    currentView = PaneType.GRID_CAPSULE, // Theme controls layout, not this
                    onViewChanged = { /* No-op when using themes */ },
                    showSteam = state.showSteamInLibrary,
                    showCustomGames = state.showCustomGamesInLibrary,
                    onSourceToggle = onSourceToggle,
                )
            }
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@OptIn(ExperimentalMaterial3Api::class)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1080px,height=1920px,dpi=440,orientation=landscape",
)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "id:pixel_tablet",
)
@Composable
private fun Preview_LibraryScreenContent() {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    PrefManager.init(context)
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
                    )
                },
                // Add compatibility map for preview
                compatibilityMap = mapOf(
                    "Game 0" to GameCompatibilityStatus.COMPATIBLE,
                    "Game 1" to GameCompatibilityStatus.GPU_COMPATIBLE,
                    "Game 2" to GameCompatibilityStatus.NOT_COMPATIBLE,
                    "Game 3" to GameCompatibilityStatus.UNKNOWN,
                ),
            ),
        )
    }
    PluviaTheme {
        LibraryScreenContent(
            listState = rememberLazyGridState(),
            carouselPageIndex = 0,
            onCarouselPageChanged = {},
            state = state,
            sheetState = sheetState,
            onIsSearching = {},
            onSearchQuery = {},
            onFilterChanged = { },
            onPageChange = { },
            onModalBottomSheet = {
                val currentState = state.modalBottomSheet
                println("State: $currentState")
                state = state.copy(modalBottomSheet = !currentState)
            },
            onClickPlay = { _, _ -> },
            onRefresh = { },
            onNavigateRoute = {},
            onLogout = {},
            onGoOnline = {},
            onSourceToggle = {},
            onAddCustomGameFolder = {},
        )
    }
}
