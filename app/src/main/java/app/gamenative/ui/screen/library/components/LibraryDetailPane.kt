package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.data.LibraryItem
import app.gamenative.data.GameSource
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.screen.library.AppScreen
import app.gamenative.ui.theme.PluviaTheme
import java.util.EnumSet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryDetailPane(
    libraryItem: LibraryItem?,
    onClickPlay: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Surface {
        if (libraryItem == null) {
            // Simply use the regular LibraryListPane with empty data
            val listState = rememberLazyGridState()
            val sheetState = rememberModalBottomSheetState()
            val emptyState = remember {
                LibraryState(
                    appInfoList = emptyList(),
                    // Use the same default filter as in PrefManager (GAME)
                    appInfoSortType = EnumSet.of(AppFilter.GAME)
                )
            }

            LibraryListPane(
                state = emptyState,
                listState = listState,
                sheetState = sheetState,
                onFilterChanged = {},
                onPageChange = {},
                onModalBottomSheet = {},
                onIsSearching = {},
                onLogout = {},
                onNavigate = {},
                onSearchQuery = {},
                onNavigateRoute = {},
                onGoOnline = {},
                onSourceToggle = {},
            )
        } else {
            if (libraryItem.gameSource == GameSource.STEAM) {
                AppScreen(
                    libraryItem = libraryItem,
                    onClickPlay = onClickPlay,
                    onBack = onBack,
                )
            } else {
                // Open Container detail: provide Play and Edit Container actions
                val context = LocalContext.current
                var showConfigDialog by remember { mutableStateOf(false) }
                var containerData by remember { mutableStateOf(com.winlator.container.ContainerData()) }

                // Load current container data when opening the dialog
                val loadContainerData: () -> Unit = {
                    val container = app.gamenative.utils.ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
                    containerData = app.gamenative.utils.ContainerUtils.toContainerData(container)
                }

                Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
                    androidx.compose.material3.Text(
                        text = libraryItem.name,
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.Button(onClick = {
                            // Trigger external game launch pipeline with this appId
                            app.gamenative.PluviaApp.events.emit(app.gamenative.events.AndroidEvent.ExternalGameLaunch(libraryItem.appId))
                        }) {
                            androidx.compose.material3.Text(text = "Play")
                        }
                        androidx.compose.material3.OutlinedButton(onClick = {
                            loadContainerData()
                            showConfigDialog = true
                        }) {
                            androidx.compose.material3.Text(text = "Edit Container")
                        }
                        androidx.compose.material3.OutlinedButton(onClick = onBack) {
                            androidx.compose.material3.Text(text = "Back")
                        }
                    }
                }

                if (showConfigDialog) {
                    app.gamenative.ui.component.dialog.ContainerConfigDialog(
                        title = "Edit Container",
                        initialConfig = containerData,
                        onDismissRequest = { showConfigDialog = false },
                        onSave = {
                            app.gamenative.utils.ContainerUtils.applyToContainer(context, libraryItem.appId, it)
                            showConfigDialog = false
                        }
                    )
                }
            }
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
@Preview(device = "spec:width=1920px,height=1080px,dpi=440") // Odin2 Mini
@Composable
private fun Preview_LibraryDetailPane() {
    PrefManager.init(LocalContext.current)
    PluviaTheme {
        LibraryDetailPane(
            libraryItem = LibraryItem(
                appId = "${GameSource.STEAM.name}_${Int.MAX_VALUE}",
                name = "Preview Game",
                iconHash = "",
                gameSource = GameSource.STEAM
            ),
            onClickPlay = { },
            onBack = { },
        )
    }
}
