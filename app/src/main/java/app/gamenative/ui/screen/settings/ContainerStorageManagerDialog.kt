package app.gamenative.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerStorageManager
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.launch

@Composable
fun ContainerStorageManagerDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ContainerStorageManager.Entry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var pendingRemoval by remember { mutableStateOf<ContainerStorageManager.Entry?>(null) }

    suspend fun reloadEntries() {
        isLoading = true
        entries = ContainerStorageManager.loadEntries(context)
        isLoading = false
    }

    LaunchedEffect(Unit) {
        reloadEntries()
    }

    pendingRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.container_storage_remove_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.container_storage_remove_message,
                        entry.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        scope.launch {
                            val removed = ContainerStorageManager.removeContainer(context, entry.containerId)
                            if (removed) {
                                SnackbarManager.show(
                                    context.getString(
                                        R.string.container_storage_remove_success,
                                        entry.displayName,
                                    ),
                                )
                                reloadEntries()
                            } else {
                                SnackbarManager.show(
                                    context.getString(R.string.container_storage_remove_failed),
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.container_storage_remove_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .statusBarsPadding(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PluviaTheme.colors.surfacePanel)
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.container_storage_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.container_storage_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                if (!isLoading) {
                    Text(
                        text = stringResource(
                            R.string.container_storage_summary,
                            entries.size,
                            StorageUtils.formatBinarySize(entries.sumOf { it.sizeBytes }),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.container_storage_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    entries.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.container_storage_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            entries.forEach { entry ->
                                ContainerStorageRow(
                                    entry = entry,
                                    onRemove = { pendingRemoval = entry },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerStorageRow(
    entry: ContainerStorageManager.Entry,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.containerId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = StorageUtils.formatBinarySize(entry.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(
                    R.string.container_storage_source_and_status,
                    gameSourceLabel(entry.gameSource),
                    statusLabel(entry.status),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            entry.installPath?.takeIf { it.isNotBlank() }?.let { path ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.container_storage_remove_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun gameSourceLabel(gameSource: GameSource?): String = when (gameSource) {
    GameSource.STEAM -> stringResource(R.string.library_source_steam)
    GameSource.CUSTOM_GAME -> stringResource(R.string.library_source_custom)
    GameSource.GOG -> stringResource(R.string.tab_gog)
    GameSource.EPIC -> stringResource(R.string.tab_epic)
    GameSource.AMAZON -> stringResource(R.string.tab_amazon)
    null -> stringResource(R.string.container_storage_source_unknown)
}

@Composable
private fun statusLabel(status: ContainerStorageManager.Status): String = when (status) {
    ContainerStorageManager.Status.READY -> stringResource(R.string.container_storage_status_ready)
    ContainerStorageManager.Status.GAME_FILES_MISSING -> stringResource(R.string.container_storage_status_game_files_missing)
    ContainerStorageManager.Status.ORPHANED -> stringResource(R.string.container_storage_status_orphaned)
    ContainerStorageManager.Status.UNREADABLE -> stringResource(R.string.container_storage_status_unreadable)
}
