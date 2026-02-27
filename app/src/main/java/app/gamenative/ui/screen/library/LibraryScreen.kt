package app.gamenative.ui.screen.library

import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.gamenative.PrefManager
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.LibraryActions
import app.gamenative.ui.components.rememberCustomGameFolderPicker
import app.gamenative.ui.components.requestPermissionsForPath
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.model.LibraryViewModel
import app.gamenative.ui.screen.library.components.LibraryDetailPane
import app.gamenative.ui.screen.library.components.LibraryListPane
import app.gamenative.ui.screen.library.components.LibraryOptionsPanel
import app.gamenative.ui.screen.library.components.LibrarySearchBar
import app.gamenative.ui.screen.library.components.LibraryTabBar
import app.gamenative.ui.screen.auth.AmazonOAuthActivity
import app.gamenative.ui.screen.auth.EpicOAuthActivity
import app.gamenative.ui.screen.auth.GOGOAuthActivity
import app.gamenative.ui.screen.library.components.SystemMenu
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.PlatformAuthUiHelpers
import app.gamenative.ui.util.PlatformLogoutCallbacks
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.PlatformOAuthHandlers
import kotlinx.coroutines.launch
import android.os.SystemClock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onGoOnline: () -> Unit,
    isOffline: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LibraryScreenContent(
        state = state,
        listState = viewModel.listState,
        sheetState = sheetState,
        onFilterChanged = viewModel::onFilterChanged,
        onPageChange = viewModel::onPageChange,
        onModalBottomSheet = viewModel::onModalBottomSheet,
        onIsSearching = viewModel::onIsSearching,
        onSearchQuery = viewModel::onSearchQuery,
        onRefresh = viewModel::onRefresh,
        onClickPlay = onClickPlay,
        onTestGraphics = onTestGraphics,
        onNavigateRoute = onNavigateRoute,
        onLogout = onLogout,
        onGoOnline = onGoOnline,
        onSourceToggle = viewModel::onSourceToggle,
        onAddCustomGameFolder = viewModel::addCustomGameFolder,
        onSortOptionChanged = viewModel::onSortOptionChanged,
        onOptionsPanelToggle = viewModel::onOptionsPanelToggle,
        onTabChanged = viewModel::onTabChanged,
        onPreviousTab = viewModel::onPreviousTab,
        onNextTab = viewModel::onNextTab,
        isOffline = isOffline,
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenContent(
    state: LibraryState,
    listState: LazyGridState,
    sheetState: SheetState,
    onFilterChanged: (AppFilter) -> Unit,
    onPageChange: (Int) -> Unit,
    onModalBottomSheet: (Boolean) -> Unit,
    onIsSearching: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onGoOnline: () -> Unit,
    onSourceToggle: (GameSource) -> Unit,
    onAddCustomGameFolder: (String) -> Unit,
    onSortOptionChanged: (SortOption) -> Unit,
    onOptionsPanelToggle: (Boolean) -> Unit,
    onTabChanged: (LibraryTab) -> Unit,
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
    isOffline: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleScope = LocalLifecycleOwner.current.lifecycleScope

    val gogOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.gog_login_cancel)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.gog_login_cancel)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleGogAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                onSuccess = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.gog_login_success_title),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onDialogClose = { },
            )
        }
    }

    val epicOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.epic_login_cancel)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.epic_login_cancel)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleEpicAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                onSuccess = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.epic_login_success_title),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onDialogClose = { },
            )
        }
    }

    val amazonOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.amazon_login_cancel)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.amazon_login_cancel)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleAmazonAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                onSuccess = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.amazon_login_success_title),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onDialogClose = { },
            )
        }
    }

    var selectedAppId by remember { mutableStateOf<String?>(null) }
    val isViewWide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var currentPaneType by remember { mutableStateOf(PrefManager.libraryLayout) }

    // Initialize layout if undecided
    LaunchedEffect(Unit) {
        if (currentPaneType == PaneType.UNDECIDED) {
            currentPaneType = if (isViewWide) PaneType.GRID_HERO else PaneType.GRID_CAPSULE
            PrefManager.libraryLayout = currentPaneType
        }
    }

    val rootFocusRequester = remember { FocusRequester() }
    val gridFirstItemFocusRequester = remember { FocusRequester() }
    var gridFocusTargetListIndex by remember { mutableIntStateOf(0) }
    var pendingGridFocusRequest by remember { mutableStateOf(false) }

    var isSystemMenuOpen by remember { mutableStateOf(false) }
    // Track previous overlay states to detect when they close
    var wasSystemMenuOpen by remember { mutableStateOf(false) }
    var wasOptionsPanelOpen by remember { mutableStateOf(false) }
    // Keep a stable reference to the selected item so detail view doesn't disappear during list refresh/pagination.
    var selectedLibraryItem by remember { mutableStateOf<LibraryItem?>(null) }
    val filterFabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    // Dialog state for add custom game prompt
    var showAddCustomGameDialog by remember { mutableStateOf(false) }
    var dontShowAgain by remember { mutableStateOf(false) }
    var previousAppCount by remember { mutableIntStateOf(state.appInfoList.size) }
    var controllerBootstrapNeeded by remember { mutableStateOf(true) }
    var rootHasFocus by remember { mutableStateOf(false) }
    var lastBootstrapAtMs by remember { mutableLongStateOf(0L) }

    fun requestGridFocusOrDefer() {
        if (state.appInfoList.isEmpty()) return
        try {
            gridFirstItemFocusRequester.requestFocus()
            pendingGridFocusRequest = false
            lastBootstrapAtMs = SystemClock.uptimeMillis()
        } catch (_: IllegalStateException) {
            pendingGridFocusRequest = true
        }
    }

    fun requestRootFocusSafe() {
        try {
            rootFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {}
    }

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

    BackHandler(enabled = isSystemMenuOpen) {
        isSystemMenuOpen = false
    }

    BackHandler(enabled = state.isOptionsPanelOpen) {
        onOptionsPanelToggle(false)
    }

    BackHandler(enabled = state.isSearching && selectedAppId == null) {
        onIsSearching(false)
        onSearchQuery("")
    }

    BackHandler(selectedLibraryItem != null) {
        selectedAppId = null
        selectedLibraryItem = null
    }

    // Restore focus when returning from game detail (without reloading list)
    LaunchedEffect(selectedAppId) {
        if (selectedAppId != null) {
            controllerBootstrapNeeded = true
        }
        if (selectedAppId == null) {
            // Brief delay to let the UI settle after transition
            kotlinx.coroutines.delay(100)
            // Restore focus to content area
            if (state.appInfoList.isNotEmpty()) {
                requestGridFocusOrDefer()
            } else {
                requestRootFocusSafe()
            }
        }
    }


    // Apply top padding differently for list vs game detail pages.
    // On the game page we want to hide the top padding when the status bar is hidden.
    val safePaddingModifier = if (selectedLibraryItem != null) {
        // Detail (game) page: use actual status bar height when status bar is visible,
        // or 0.dp when status bar is hidden
        val topPadding = if (PrefManager.hideStatusBarWhenNotInGame) {
            0.dp
        } else {
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        }
        Modifier.padding(top = topPadding)
    } else {
        Modifier
    }

    // Restore focus after tab change - handles both empty and populated tabs
    LaunchedEffect(state.currentTab) {
        // Brief delay to let list populate after tab change
        kotlinx.coroutines.delay(150)

        if (state.appInfoList.isEmpty()) {
            // Empty tab - focus root so bumpers still work
            requestRootFocusSafe()
        } else {
            // Tab has content - focus the first grid item
            gridFocusTargetListIndex = 0
            requestGridFocusOrDefer()
        }
    }

    LaunchedEffect(
        pendingGridFocusRequest,
        gridFocusTargetListIndex,
        state.appInfoList.size,
        selectedAppId,
        isSystemMenuOpen,
        state.isOptionsPanelOpen,
        state.isSearching,
    ) {
        if (pendingGridFocusRequest && state.appInfoList.isNotEmpty()) {
            if (selectedAppId == null && !isSystemMenuOpen && !state.isOptionsPanelOpen && !state.isSearching) {
                var retries = 0
                while (pendingGridFocusRequest && retries < 8) {
                    try {
                        gridFirstItemFocusRequester.requestFocus()
                        pendingGridFocusRequest = false
                    } catch (_: IllegalStateException) {
                        retries++
                        // FocusRequester can be temporarily detached during recomposition.
                        kotlinx.coroutines.delay(32)
                    }
                }
            }
        }
    }

    // If the app list starts empty and populates later, bootstrap controller focus once content is ready.
    LaunchedEffect(
        state.appInfoList.size,
        selectedAppId,
        isSystemMenuOpen,
        state.isOptionsPanelOpen,
        state.isSearching,
    ) {
        val currentCount = state.appInfoList.size
        val listBecameNonEmpty = previousAppCount == 0 && currentCount > 0
        val listBecameEmpty = previousAppCount > 0 && currentCount == 0

        if (listBecameNonEmpty && selectedAppId == null && !isSystemMenuOpen && !state.isOptionsPanelOpen && !state.isSearching) {
            gridFocusTargetListIndex = listState.firstVisibleItemIndex.coerceIn(0, state.appInfoList.lastIndex)
            requestGridFocusOrDefer()
        }
        if (listBecameEmpty && selectedAppId == null && !isSystemMenuOpen && !state.isOptionsPanelOpen && !state.isSearching) {
            // Empty tabs can drop focused children; re-anchor focus at the root so bumper nav keeps working.
            requestRootFocusSafe()
        }

        previousAppCount = currentCount
    }

    // Restore focus when System Menu or Options Panel closes
    LaunchedEffect(isSystemMenuOpen, state.isOptionsPanelOpen) {
        val systemMenuJustClosed = wasSystemMenuOpen && !isSystemMenuOpen
        val optionsPanelJustClosed = wasOptionsPanelOpen && !state.isOptionsPanelOpen

        if ((systemMenuJustClosed || optionsPanelJustClosed) && !state.isSearching) {
            // Give a brief moment for the overlay to animate out
            kotlinx.coroutines.delay(50)
            // Restore focus to grid
            if (state.appInfoList.isNotEmpty()) {
                try {
                    gridFirstItemFocusRequester.requestFocus()
                } catch (_: IllegalStateException) {}
            } else {
                // Empty list - focus root so bumpers still work
                try {
                    rootFocusRequester.requestFocus()
                } catch (_: IllegalStateException) {}
            }
        }

        // Update previous state trackers
        wasSystemMenuOpen = isSystemMenuOpen
        wasOptionsPanelOpen = state.isOptionsPanelOpen
    }

    // Global key/motion bootstrap path for cases where Compose focus was lost by touch mode.
    // This runs at the app event bus layer, independent of current Compose focus target.
    DisposableEffect(
        selectedAppId,
        isSystemMenuOpen,
        state.isOptionsPanelOpen,
        state.isSearching,
        state.appInfoList.size,
        state.currentTab,
    ) {
        val canBootstrapGridFocus: () -> Boolean = {
            val now = SystemClock.uptimeMillis()
            selectedAppId == null &&
                !isSystemMenuOpen &&
                !state.isOptionsPanelOpen &&
                !state.isSearching &&
                state.appInfoList.isNotEmpty() &&
                controllerBootstrapNeeded &&
                !rootHasFocus &&
                (now - lastBootstrapAtMs) > 250L
        }
        val canNavigateTabsWithoutFocus: () -> Boolean = {
            selectedAppId == null &&
                !isSystemMenuOpen &&
                !state.isOptionsPanelOpen &&
                !state.isSearching &&
                !rootHasFocus
        }

        val onGlobalKeyEvent: (AndroidEvent.KeyEvent) -> Boolean = { androidEvent ->
            val event = androidEvent.event
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        if (canNavigateTabsWithoutFocus()) {
                            onPreviousTab()
                            requestRootFocusSafe()
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        if (canNavigateTabsWithoutFocus()) {
                            onNextTab()
                            requestRootFocusSafe()
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_BUTTON_L2,
                    KeyEvent.KEYCODE_BUTTON_R2,
                    KeyEvent.KEYCODE_BUTTON_THUMBL,
                    KeyEvent.KEYCODE_BUTTON_THUMBR,
                    -> {
                        if (canBootstrapGridFocus()) {
                            gridFocusTargetListIndex = listState.firstVisibleItemIndex
                                .coerceIn(0, state.appInfoList.lastIndex)
                            requestGridFocusOrDefer()
                            // Do not consume: let normal key routing continue after bootstrap.
                            false
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
        }

        val onGlobalMotionEvent: (AndroidEvent.MotionEvent) -> Boolean = { androidEvent ->
            val event = androidEvent.event
            if (event == null || !canBootstrapGridFocus()) {
                false
            } else {
                val isMoveLike = event.actionMasked == MotionEvent.ACTION_MOVE
                val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                val leftX = event.getAxisValue(MotionEvent.AXIS_X)
                val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
                val hasDirectionalAxis = kotlin.math.abs(hatX) >= 0.5f ||
                    kotlin.math.abs(hatY) >= 0.5f ||
                    kotlin.math.abs(leftX) >= 0.6f ||
                    kotlin.math.abs(leftY) >= 0.6f

                if (isMoveLike && hasDirectionalAxis) {
                    gridFocusTargetListIndex = listState.firstVisibleItemIndex
                        .coerceIn(0, state.appInfoList.lastIndex)
                    requestGridFocusOrDefer()
                    // Do not consume: allow normal movement handling after bootstrap.
                    false
                } else {
                    false
                }
            }
        }

        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onGlobalKeyEvent)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(onGlobalMotionEvent)

        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onGlobalKeyEvent)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(onGlobalMotionEvent)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(safePaddingModifier)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onFocusChanged { focusState ->
                rootHasFocus = focusState.hasFocus
                if (focusState.hasFocus) {
                    controllerBootstrapNeeded = false
                } else {
                    controllerBootstrapNeeded = true
                }
            }
            .focusGroup()
            .onPreviewKeyEvent { keyEvent ->
                // TODO: consider abstracting this
                // Handle gamepad buttons
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    val canBootstrapGridFocus = selectedAppId == null &&
                        !state.isOptionsPanelOpen &&
                        !isSystemMenuOpen &&
                        !state.isSearching &&
                        state.appInfoList.isNotEmpty() &&
                        controllerBootstrapNeeded

                    when (keyCode) {
                        // Navigation keys should bootstrap focus even before any item is selected.
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_BUTTON_L2,
                        KeyEvent.KEYCODE_BUTTON_R2,
                        KeyEvent.KEYCODE_BUTTON_THUMBL,
                        KeyEvent.KEYCODE_BUTTON_THUMBR,
                        -> {
                            if (canBootstrapGridFocus) {
                                gridFocusTargetListIndex = listState.firstVisibleItemIndex
                                    .coerceIn(0, state.appInfoList.lastIndex)
                                requestGridFocusOrDefer()
                                false
                            } else {
                                false
                            }
                        }

                        // L1 button - previous tab
                        KeyEvent.KEYCODE_BUTTON_L1 -> {
                            if (selectedAppId == null && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
                                if (canBootstrapGridFocus) {
                                    gridFocusTargetListIndex = listState.firstVisibleItemIndex
                                        .coerceIn(0, state.appInfoList.lastIndex)
                                    requestGridFocusOrDefer()
                                }
                                onPreviousTab()
                                true
                            } else {
                                false
                            }
                        }

                        // R1 button - next tab
                        KeyEvent.KEYCODE_BUTTON_R1 -> {
                            if (selectedAppId == null && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
                                if (canBootstrapGridFocus) {
                                    gridFocusTargetListIndex = listState.firstVisibleItemIndex
                                        .coerceIn(0, state.appInfoList.lastIndex)
                                    requestGridFocusOrDefer()
                                }
                                onNextTab()
                                true
                            } else {
                                false
                            }
                        }

                        // SELECT button - toggle options panel (library filters/sort)
                        KeyEvent.KEYCODE_BUTTON_SELECT -> {
                            if (selectedAppId == null && !isSystemMenuOpen) {
                                onOptionsPanelToggle(!state.isOptionsPanelOpen)
                                true
                            } else {
                                false
                            }
                        }

                        // START button - toggle system menu (profile/settings)
                        KeyEvent.KEYCODE_BUTTON_START,
                        KeyEvent.KEYCODE_MENU,
                        -> {
                            if (selectedAppId == null && !state.isOptionsPanelOpen) {
                                isSystemMenuOpen = !isSystemMenuOpen
                                true
                            } else {
                                false
                            }
                        }

                        // Y button - toggle search
                        KeyEvent.KEYCODE_BUTTON_Y -> {
                            if (selectedAppId == null && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
                                onIsSearching(!state.isSearching)
                                true
                            } else {
                                false
                            }
                        }

                        // X button - add custom game
                        KeyEvent.KEYCODE_BUTTON_X -> {
                            if (selectedAppId == null && !state.isSearching && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
                                onAddCustomGameClick()
                                true
                            } else {
                                false
                            }
                        }

                        // B button - contextual back / open system menu
                        KeyEvent.KEYCODE_BUTTON_B -> {
                            if (selectedAppId != null) {
                                // Let LibraryAppScreen handle its own B-button
                                false
                            } else if (isSystemMenuOpen) {
                                isSystemMenuOpen = false
                                true
                            } else if (state.isOptionsPanelOpen) {
                                onOptionsPanelToggle(false)
                                true
                            } else if (state.isSearching) {
                                onIsSearching(false)
                                onSearchQuery("")
                                true
                            } else {
                                // Root library view: open system menu
                                isSystemMenuOpen = true
                                true
                            }
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        if (selectedAppId == null) {
            // Use Box to allow content to scroll behind the tab bar
            Box(modifier = Modifier.fillMaxSize()) {
                // Library list (content scrolls behind tab bar)
                LibraryListPane(
                    state = state,
                    listState = listState,
                    currentLayout = currentPaneType,
                    firstGridItemFocusRequester = gridFirstItemFocusRequester,
                    focusTargetListIndex = gridFocusTargetListIndex,
                    onPageChange = onPageChange,
                    onNavigate = { appId ->
                        selectedAppId = appId
                        selectedLibraryItem = state.appInfoList.find { it.appId == appId }
                    },
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                )

                // Top overlay: Tab bar OR Search bar
                if (state.isSearching) {
                    // Search overlay replaces tab bar when searching
                    // TODO: Gamepad focus is a bit wonky whenever we show the search bar
                    LibrarySearchBar(
                        isVisible = true,
                        searchQuery = state.searchQuery,
                        resultCount = state.totalAppsInFilter,
                        listState = listState,
                        onSearchQuery = onSearchQuery,
                        onDismiss = { onIsSearching(false) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    )
                } else {
                    // Tab bar when not searching
                    LibraryTabBar(
                        currentTab = state.currentTab,
                        tabCounts = mapOf(
                            LibraryTab.ALL to state.allCount,
                            LibraryTab.STEAM to state.steamCount,
                            LibraryTab.GOG to state.gogCount,
                            LibraryTab.EPIC to state.epicCount,
                            LibraryTab.AMAZON to state.amazonCount,
                            LibraryTab.LOCAL to state.localCount,
                        ),
                        onTabSelected = onTabChanged,
                        onOptionsClick = { onOptionsPanelToggle(true) },
                        onSearchClick = { onIsSearching(true) },
                        onAddGameClick = onAddCustomGameClick,
                        onMenuClick = { isSystemMenuOpen = true },
                        onNavigateDownToGrid = {
                            if (state.appInfoList.isNotEmpty()) {
                                gridFocusTargetListIndex = listState.firstVisibleItemIndex
                                    .coerceIn(0, state.appInfoList.lastIndex)
                                pendingGridFocusRequest = true
                            }
                        },
                        onPreviousTab = onPreviousTab,
                        onNextTab = onNextTab,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    )
                }
            }
        } else {
            LibraryDetailPane(
                libraryItem = selectedLibraryItem,
                onBack = {
                    selectedAppId = null
                    selectedLibraryItem = null
                },
                onClickPlay = {
                    selectedLibraryItem?.let { libraryItem ->
                        onClickPlay(libraryItem.appId, it)
                    }
                },
                onTestGraphics = {
                    selectedLibraryItem?.let { libraryItem ->
                        onTestGraphics(libraryItem.appId)
                    }
                },
            )
        }

        // Bottom action bar
        if (selectedAppId == null && !state.isOptionsPanelOpen && !isSystemMenuOpen) {
            val libraryActions = if (state.isSearching) {
                listOf(
                    LibraryActions.select,
                    GamepadAction(
                        button = GamepadButton.B,
                        labelResId = R.string.back,
                        onClick = {
                            onIsSearching(false)
                            onSearchQuery("")
                        },
                    ),
                )
            } else {
                listOf(
                    LibraryActions.select,
                    GamepadAction(
                        button = GamepadButton.SELECT,
                        labelResId = R.string.options,
                        onClick = { onOptionsPanelToggle(true) },
                    ),
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.action_system,
                        onClick = { isSystemMenuOpen = true },
                    ),
                    GamepadAction(
                        button = GamepadButton.B,
                        labelResId = R.string.menu,
                        onClick = { isSystemMenuOpen = true },
                    ),
                    GamepadAction(
                        button = GamepadButton.Y,
                        labelResId = R.string.search,
                        onClick = { onIsSearching(true) },
                    ),
                    GamepadAction(
                        button = GamepadButton.X,
                        labelResId = R.string.action_add_game,
                        onClick = onAddCustomGameClick,
                    ),
                )
            }

            GamepadActionBar(
                actions = libraryActions,
                modifier = Modifier.align(Alignment.BottomCenter),
                visible = true,
            )
        }

        // Options panel (SELECT) - renders on top of everything
        if (selectedAppId == null) {
            LibraryOptionsPanel(
                isOpen = state.isOptionsPanelOpen,
                onDismiss = { onOptionsPanelToggle(false) },
                selectedFilters = state.appInfoSortType,
                onFilterChanged = onFilterChanged,
                currentSortOption = state.currentSortOption,
                onSortOptionChanged = onSortOptionChanged,
                currentView = currentPaneType,
                onViewChanged = { newPaneType ->
                    PrefManager.libraryLayout = newPaneType
                    currentPaneType = newPaneType
                },
            )

            // System menu (START) - renders on top of everything
            val context = LocalContext.current
            val gogLoggedIn = app.gamenative.service.gog.GOGAuthManager.hasStoredCredentials(context)
            val epicLoggedIn = app.gamenative.service.epic.EpicAuthManager.hasStoredCredentials(context)
            val amazonLoggedIn = app.gamenative.service.amazon.AmazonAuthManager.hasStoredCredentials(context)

            SystemMenu(
                isOpen = isSystemMenuOpen,
                onDismiss = { isSystemMenuOpen = false },
                onNavigateRoute = onNavigateRoute,
                onLogout = onLogout,
                onGoOnline = onGoOnline,
                isOffline = isOffline,
                gogLoggedIn = gogLoggedIn,
                epicLoggedIn = epicLoggedIn,
                amazonLoggedIn = amazonLoggedIn,
                onGogLoginClick = {
                    gogOAuthLauncher.launch(Intent(context, GOGOAuthActivity::class.java))
                },
                onGogLogoutClick = {
                    PlatformAuthUiHelpers.logoutGog(
                        context = context,
                        scope = lifecycleScope,
                        callbacks = PlatformLogoutCallbacks(),
                    )
                },
                onEpicLoginClick = {
                    epicOAuthLauncher.launch(Intent(context, EpicOAuthActivity::class.java))
                },
                onEpicLogoutClick = {
                    PlatformAuthUiHelpers.logoutEpic(
                        context = context,
                        scope = lifecycleScope,
                        callbacks = PlatformLogoutCallbacks(),
                    )
                },
                onAmazonLoginClick = {
                    amazonOAuthLauncher.launch(Intent(context, AmazonOAuthActivity::class.java))
                },
                onAmazonLogoutClick = {
                    PlatformAuthUiHelpers.logoutAmazon(
                        context = context,
                        scope = lifecycleScope,
                        callbacks = PlatformLogoutCallbacks(),
                    )
                },
            )
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
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = dontShowAgain,
                                onCheckedChange = { dontShowAgain = it },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.add_custom_game_dont_show_again),
                                modifier = Modifier.weight(1f),
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
                        },
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showAddCustomGameDialog = false },
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
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
            onTestGraphics = { },
            onRefresh = { },
            onNavigateRoute = {},
            onLogout = {},
            onGoOnline = {},
            onSourceToggle = {},
            onAddCustomGameFolder = {},
            onSortOptionChanged = {},
            onOptionsPanelToggle = { isOpen ->
                state = state.copy(isOptionsPanelOpen = isOpen)
            },
            onTabChanged = { tab ->
                state = state.copy(currentTab = tab)
            },
            onPreviousTab = {},
            onNextTab = {},
        )
    }
}
