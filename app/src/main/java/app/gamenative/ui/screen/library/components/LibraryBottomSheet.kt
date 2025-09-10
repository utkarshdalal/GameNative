package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.ui.component.FlowFilterChip
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.theme.PluviaTheme
import java.util.EnumSet

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LibraryBottomSheet(
    selectedFilters: EnumSet<AppFilter>,
    onFilterChanged: (AppFilter) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
    ) {
        Text(text = "App Type", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
           verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppFilter.entries.forEach { appFilter ->
                // App Type filters: exclude status and platform filters
                if (appFilter.code !in listOf(0x01, 0x20, 0x40, 0x80)) {
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

        Text(text = "App Status", style = MaterialTheme.typography.titleLarge)
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

        Text(text = "Platform", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow {
            AppFilter.entries.forEach { appFilter ->
                if (appFilter.code in listOf(0x40, 0x80)) { // Steam and GOG
                    FlowFilterChip(
                        onClick = { onFilterChanged(appFilter) },
                        label = { Text(text = appFilter.displayText) },
                        selected = selectedFilters.contains(appFilter),
                        leadingIcon = { Icon(imageVector = appFilter.icon, contentDescription = null) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp)) // A little extra padding.
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_LibraryBottomSheet() {
    PluviaTheme {
        Surface {
            LibraryBottomSheet(
                selectedFilters = EnumSet.of(AppFilter.GAME, AppFilter.DEMO),
                onFilterChanged = { },
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
