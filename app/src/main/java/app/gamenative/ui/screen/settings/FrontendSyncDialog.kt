package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.sync.FrontendSyncManager
import app.gamenative.ui.components.rememberCustomGameFolderPicker

/**
 * Dialog for configuring per-source export directories used by frontend launchers such as ES-DE.
 * Changes are buffered until the user confirms with OK.
 */
@Composable
fun FrontendSyncDialog(onDismiss: () -> Unit) {
    val sources = listOf(
        GameSource.STEAM to stringResource(R.string.frontend_sync_source_steam),
        GameSource.EPIC to stringResource(R.string.frontend_sync_source_epic),
        GameSource.GOG to stringResource(R.string.frontend_sync_source_gog),
        GameSource.AMAZON to stringResource(R.string.frontend_sync_source_amazon),
        GameSource.CUSTOM_GAME to stringResource(R.string.frontend_sync_source_custom),
    )
    // Buffered changes: source → (newPath, deleteOldFiles). Applied only on OK.
    val pendingChanges = remember { mutableStateMapOf<GameSource, Pair<String, Boolean>>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.frontend_sync_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                sources.forEach { (source, label) ->
                    FrontendSyncSourceRow(
                        source = source,
                        label = label,
                        onChangeQueued = { newPath, deleteOld ->
                            pendingChanges[source] = newPath to deleteOld
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                pendingChanges.forEach { (source, change) ->
                    FrontendSyncManager.changeDirectory(source, change.first, change.second)
                }
                if (pendingChanges.isNotEmpty()) {
                    FrontendSyncManager.resyncAll()
                }
                onDismiss()
            }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/**
 * Row showing the configured export directory for [source] with folder-picker and clear actions.
 * Reports changes via [onChangeQueued] rather than applying them immediately.
 */
@Composable
private fun FrontendSyncSourceRow(
    source: GameSource,
    label: String,
    onChangeQueued: (newPath: String, deleteOldFiles: Boolean) -> Unit,
) {
    var displayPath by remember(source) {
        mutableStateOf(PrefManager.getFrontendSyncDir(source))
    }
    var showConfirm by remember { mutableStateOf(false) }
    var pendingPath by remember { mutableStateOf("") }

    val picker = rememberCustomGameFolderPicker(
        onPathSelected = { newPath ->
            if (displayPath.isNotEmpty()) {
                pendingPath = newPath
                showConfirm = true
            } else {
                displayPath = newPath
                onChangeQueued(newPath, false)
            }
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (displayPath.isEmpty()) {
                    stringResource(R.string.frontend_sync_not_set)
                } else {
                    displayPath
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { picker.launchPicker() }) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = stringResource(R.string.frontend_sync_pick_directory_cd, label),
            )
        }
        if (displayPath.isNotEmpty()) {
            IconButton(onClick = {
                pendingPath = ""
                showConfirm = true
            }) {
                Icon(
                    Icons.Default.Clear,
                    contentDescription = stringResource(R.string.frontend_sync_clear_directory_cd, label),
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.frontend_sync_clear_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.frontend_sync_clear_confirm_message,
                        FrontendSyncManager.extensionFor(source).trimStart('.'),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    displayPath = pendingPath
                    onChangeQueued(pendingPath, true)
                    showConfirm = false
                }) {
                    Text(stringResource(R.string.frontend_sync_confirm_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    displayPath = pendingPath
                    onChangeQueued(pendingPath, false)
                    showConfirm = false
                }) {
                    Text(stringResource(R.string.frontend_sync_confirm_keep))
                }
            },
        )
    }
}
