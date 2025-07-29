package app.gamenative.ui.screen.library

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PrefManager
import app.gamenative.data.LibraryItem
import app.gamenative.service.SteamService
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.Orientation
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.model.LibraryViewModel
import app.gamenative.ui.screen.library.components.LibraryDetailPane
import app.gamenative.ui.screen.library.components.LibraryListPane
import app.gamenative.ui.theme.PluviaTheme
import java.util.EnumSet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onClickPlay: (Int, Boolean) -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
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
        onClickPlay = onClickPlay,
        onNavigateRoute = onNavigateRoute,
        onLogout = onLogout,
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenContent(
    state: LibraryState,
    listState: LazyListState,
    sheetState: SheetState,
    onFilterChanged: (AppFilter) -> Unit,
    onPageChange: (Int) -> Unit,
    onModalBottomSheet: (Boolean) -> Unit,
    onIsSearching: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onClickPlay: (Int, Boolean) -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var selectedAppId by remember { mutableStateOf<Int?>(null) }

    BackHandler(selectedAppId != null) { selectedAppId = null }
    val safePaddingModifier =
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT)
            Modifier.displayCutoutPadding()
        else
            Modifier

    Box(
        Modifier.background(MaterialTheme.colorScheme.background)
        .then(safePaddingModifier)) {
        if (selectedAppId == null) {
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
                onNavigate = { appId -> selectedAppId = appId }
            )
        } else {
            LibraryDetailPane(
                appId = selectedAppId ?: SteamService.INVALID_APP_ID,
                onBack = { selectedAppId = null },
                onClickPlay = { onClickPlay(selectedAppId!!, it) },
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
                        appId = item.id,
                        name = item.name,
                        iconHash = item.iconHash,
                    )
                },
            ),
        )
    }
    PluviaTheme {
        LibraryScreenContent(
            listState = rememberLazyListState(),
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
            onNavigateRoute = {},
            onLogout = {},
        )
    }
}
