package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoSizeSelectActual
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.ui.component.FlowFilterChip
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.icons.CustomGame
import app.gamenative.ui.icons.Steam
import app.gamenative.ui.theme.PluviaTheme
import java.util.EnumSet

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LibraryBottomSheet(
    selectedFilters: EnumSet<AppFilter>,
    onFilterChanged: (AppFilter) -> Unit,
    currentView: PaneType,
    onViewChanged: (PaneType) -> Unit,
    showSteam: Boolean,
    showCustomGames: Boolean,
    showGOG: Boolean,
    showEpic: Boolean,
    onSourceToggle: (app.gamenative.data.GameSource) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
    ) {
        Text(text = stringResource(R.string.library_app_type), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppFilter.entries.forEach { appFilter ->
                // TODO properly fix this (and the one below)
                if (appFilter.code !in listOf(0x01, 0x20)) {
                    FlowFilterChip(
                        onClick = { onFilterChanged(appFilter) },
                        label = { Text(text = appFilter.displayText) },
                        selected = selectedFilters.contains(appFilter),
                        leadingIcon = { Icon(imageVector = appFilter.icon, contentDescription = null) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = stringResource(R.string.library_app_status), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow {
            AppFilter.entries.forEach { appFilter ->
                if (appFilter.code in listOf(0x01, 0x20)) {
                    FlowFilterChip(
                        onClick = { onFilterChanged(appFilter) },
                        label = { Text(text = appFilter.displayText) },
                        selected = selectedFilters.contains(appFilter),
                        leadingIcon = { Icon(imageVector = appFilter.icon, contentDescription = null) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = stringResource(R.string.library_layout), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowFilterChip(
                onClick = { onSourceToggle(GameSource.STEAM) },
                label = { Text(text = stringResource(R.string.library_source_steam)) },
                selected = showSteam,
                leadingIcon = { Icon(imageVector = Icons.Filled.Steam, contentDescription = null) },
            )
            FlowFilterChip(
                onClick = { onSourceToggle(GameSource.CUSTOM_GAME) },
                label = { Text(text = stringResource(R.string.library_source_custom)) },
                selected = showCustomGames,
                leadingIcon = { Icon(imageVector = Icons.Filled.CustomGame, contentDescription = null) },
            )
            FlowFilterChip(
                onClick = { onSourceToggle(GameSource.GOG) },
                label = { Text(text = "GOG") },
                selected = showGOG,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_gog),
                        contentDescription = "GOG",
                        modifier = Modifier.size(28.dp),
                    )
                },
            )
             FlowFilterChip(
                onClick = { onSourceToggle(GameSource.EPIC) },
                label = { Text(text = "Epic") },
                selected = showEpic,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_epic),
                        contentDescription = "Epic",
                        modifier = Modifier.size(24.dp)
                    )
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = stringResource(R.string.library_layout_title), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowFilterChip(
                onClick = { onViewChanged(PaneType.LIST) },
                label = { Text(text = stringResource(R.string.library_layout_list)) },
                selected = (currentView == PaneType.LIST),
                leadingIcon = { Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null) },
            )
            FlowFilterChip(
                onClick = { onViewChanged(PaneType.GRID_CAPSULE) },
                label = { Text(text = stringResource(R.string.library_layout_capsule)) },
                selected = (currentView == PaneType.GRID_CAPSULE),
                leadingIcon = { Icon(imageVector = Icons.Default.PhotoAlbum, contentDescription = null) },
            )
            FlowFilterChip(
                onClick = { onViewChanged(PaneType.GRID_HERO) },
                label = { Text(text = stringResource(R.string.library_layout_hero)) },
                selected = (currentView == PaneType.GRID_HERO),
                leadingIcon = { Icon(imageVector = Icons.Default.PhotoSizeSelectActual, contentDescription = null) },
            )
        }

        Spacer(modifier = Modifier.height(16.dp)) // A little extra padding.
    }
}

/***********
 * PREVIEW *
 ***********/

// small screen
@Preview(device = "spec:width=320dp,height=480dp,dpi=320")
// landscape
@Preview(device = "spec:width=800dp,height=360dp,dpi=320")
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_LibraryBottomSheet() {
    PluviaTheme {
        Surface {
            LibraryBottomSheet(
                selectedFilters = EnumSet.of(AppFilter.GAME, AppFilter.DEMO),
                onFilterChanged = { },
                currentView = PaneType.LIST,
                onViewChanged = { },
                showSteam = true,
                showCustomGames = true,
                showGOG = true,
                showEpic = true,
                onSourceToggle = { },
            )
        }
    }
}

// Note: Previews seem to be broken for this, run it manually

// @OptIn(ExperimentalMaterial3Api::class)
// @Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
// @Preview
// @Composable
// private fun Preview_LibraryBottomSheet_AsSheet() {
//    PluviaTheme {
//        Scaffold { paddingValues ->
//            Box(
//                modifier = Modifier
//                    .fillMaxSize()
//                    .padding(paddingValues),
//            ) {
//                Text(text = "Hello World")
//
//
//                ModalBottomSheet(
//                    onDismissRequest = { },
//                    content = { LibraryBottomSheet() },
//                )
//            }
//        }
//    }
// }
