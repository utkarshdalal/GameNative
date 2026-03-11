package app.gamenative.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
    var pendingUninstall by remember { mutableStateOf<ContainerStorageManager.Entry?>(null) }

    suspend fun reloadEntries() {
        isLoading = true
        entries = ContainerStorageManager.loadEntries(context)
        isLoading = false
    }

    LaunchedEffect(Unit) {
        reloadEntries()
    }

    pendingRemoval?.let { entry ->
        val entryName = entry.displayName.ifBlank { context.getString(R.string.container_storage_unknown_container) }
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.container_storage_remove_title)) },
            text = { Text(stringResource(R.string.container_storage_remove_message, entryName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        scope.launch {
                            val removed = ContainerStorageManager.removeContainer(context, entry.containerId)
                            if (removed) {
                                SnackbarManager.show(
                                    context.getString(R.string.container_storage_remove_success, entryName),
                                )
                                reloadEntries()
                            } else {
                                SnackbarManager.show(context.getString(R.string.container_storage_remove_failed))
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

    pendingUninstall?.let { entry ->
        val entryName = entry.displayName.ifBlank { context.getString(R.string.container_storage_unknown_container) }
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.container_storage_uninstall_title)) },
            text = { Text(stringResource(R.string.container_storage_uninstall_message, entryName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        scope.launch {
                            val result = ContainerStorageManager.uninstallGameAndContainer(context, entry)
                            if (result.isSuccess) {
                                SnackbarManager.show(
                                    context.getString(R.string.container_storage_uninstall_success, entryName),
                                )
                                reloadEntries()
                            } else {
                                SnackbarManager.show(
                                    context.getString(
                                        R.string.container_storage_uninstall_failed,
                                        result.exceptionOrNull()?.message ?: "Unknown error",
                                    ),
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.container_storage_uninstall_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .statusBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.96f)
                    .widthIn(max = 1100.dp),
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
                                text = if (isLoading) {
                                    stringResource(R.string.container_storage_loading)
                                } else {
                                    stringResource(
                                        R.string.container_storage_summary,
                                        entries.size,
                                        StorageUtils.formatBinarySize(entries.sumOf { it.combinedSizeBytes ?: it.containerSizeBytes }),
                                    )
                                },
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
                    Spacer(modifier = Modifier.height(10.dp))

                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = stringResource(R.string.container_storage_loading),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(entries, key = { it.containerId }) { entry ->
                                    ContainerStorageRow(
                                        entry = entry,
                                        onRemove = { pendingRemoval = entry },
                                        onUninstall = { pendingUninstall = entry },
                                    )
                                }
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
    onUninstall: () -> Unit,
) {
    val displayName = entry.displayName.ifBlank { stringResource(R.string.container_storage_unknown_container) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.containerId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                entry.combinedSizeBytes?.let {
                    MetadataChip(
                        text = StorageUtils.formatBinarySize(it),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetadataChip(
                        text = gameSourceLabel(entry.gameSource),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    MetadataChip(
                        text = statusLabel(entry.status),
                        containerColor = statusContainerColor(entry.status),
                        contentColor = statusContentColor(entry.status),
                    )
                }

                Text(
                    text = sizeBreakdown(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (entry.canUninstallGame) {
                    SquircleActionButton(
                        text = stringResource(R.string.container_storage_uninstall_button),
                        icon = Icons.Default.DeleteForever,
                        onClick = onUninstall,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                SquircleActionButton(
                    text = stringResource(R.string.container_storage_remove_button),
                    icon = Icons.Default.Delete,
                    onClick = onRemove,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SquircleActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(text = text)
    }
}

@Composable
private fun MetadataChip(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun sizeBreakdown(entry: ContainerStorageManager.Entry): String {
    val container = "Container ${StorageUtils.formatBinarySize(entry.containerSizeBytes)}"
    val game = entry.gameInstallSizeBytes?.let { "Game ${StorageUtils.formatBinarySize(it)}" }
    val total = entry.combinedSizeBytes?.let { "Total ${StorageUtils.formatBinarySize(it)}" }
    return listOfNotNull(game, container, total).joinToString(" • ")
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

@Composable
private fun statusContainerColor(status: ContainerStorageManager.Status) = when (status) {
    ContainerStorageManager.Status.READY -> MaterialTheme.colorScheme.secondaryContainer
    ContainerStorageManager.Status.GAME_FILES_MISSING -> MaterialTheme.colorScheme.tertiaryContainer
    ContainerStorageManager.Status.ORPHANED -> MaterialTheme.colorScheme.errorContainer
    ContainerStorageManager.Status.UNREADABLE -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun statusContentColor(status: ContainerStorageManager.Status) = when (status) {
    ContainerStorageManager.Status.READY -> MaterialTheme.colorScheme.onSecondaryContainer
    ContainerStorageManager.Status.GAME_FILES_MISSING -> MaterialTheme.colorScheme.onTertiaryContainer
    ContainerStorageManager.Status.ORPHANED -> MaterialTheme.colorScheme.onErrorContainer
    ContainerStorageManager.Status.UNREADABLE -> MaterialTheme.colorScheme.onSurface
}
