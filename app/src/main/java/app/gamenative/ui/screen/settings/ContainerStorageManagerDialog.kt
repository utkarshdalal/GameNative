package app.gamenative.ui.screen.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import app.gamenative.data.GameSource
import app.gamenative.ui.screen.library.GameMigrationDialog
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerStorageManager
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

@Stable
class ContainerStorageManagerUiState internal constructor(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    var entries by mutableStateOf<List<ContainerStorageManager.Entry>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var hasLoaded by mutableStateOf(false)
        private set

    var pendingRemoval by mutableStateOf<ContainerStorageManager.Entry?>(null)
        private set

    var pendingUninstall by mutableStateOf<ContainerStorageManager.Entry?>(null)
        private set

    var movingEntryName by mutableStateOf<String?>(null)
        private set

    var moveProgress by mutableFloatStateOf(0f)
        private set

    var moveCurrentFile by mutableStateOf("")
        private set

    var moveMovedFiles by mutableIntStateOf(0)
        private set

    var moveTotalFiles by mutableIntStateOf(0)
        private set

    val isMoving: Boolean
        get() = movingEntryName != null

    fun ensureLoaded() {
        if (!hasLoaded && !isLoading) {
            refresh()
        }
    }

    fun refresh() {
        if (isLoading) return

        scope.launch {
            isLoading = true
            runCatching {
                ContainerStorageManager.loadEntries(appContext)
            }.onSuccess {
                entries = it
                hasLoaded = true
            }.onFailure { error ->
                hasLoaded = false
                Timber.e(error, "Failed to load storage inventory")
                SnackbarManager.show(
                    error.message ?: appContext.getString(R.string.container_storage_unknown_error),
                )
            }
            isLoading = false
        }
    }

    fun requestRemove(entry: ContainerStorageManager.Entry) {
        if (isMoving) return
        pendingRemoval = entry
    }

    fun dismissRemove() {
        pendingRemoval = null
    }

    fun confirmRemove() {
        val entry = pendingRemoval ?: return
        pendingRemoval = null
        val entryName = entry.displayName.ifBlank {
            appContext.getString(R.string.container_storage_unknown_container)
        }

        scope.launch {
            val removed = ContainerStorageManager.removeContainer(appContext, entry.containerId)
            if (removed) {
                SnackbarManager.show(
                    appContext.getString(R.string.container_storage_remove_success, entryName),
                )
                refresh()
            } else {
                SnackbarManager.show(appContext.getString(R.string.container_storage_remove_failed))
            }
        }
    }

    fun requestUninstall(entry: ContainerStorageManager.Entry) {
        if (isMoving) return
        pendingUninstall = entry
    }

    fun dismissUninstall() {
        pendingUninstall = null
    }

    fun confirmUninstall() {
        val entry = pendingUninstall ?: return
        pendingUninstall = null
        val entryName = entry.displayName.ifBlank {
            appContext.getString(R.string.container_storage_unknown_container)
        }

        scope.launch {
            val result = ContainerStorageManager.uninstallGameAndContainer(appContext, entry)
            if (result.isSuccess) {
                SnackbarManager.show(
                    appContext.getString(R.string.container_storage_uninstall_success, entryName),
                )
                refresh()
            } else {
                SnackbarManager.show(
                    appContext.getString(
                        R.string.container_storage_uninstall_failed,
                        result.exceptionOrNull()?.message
                            ?: appContext.getString(R.string.container_storage_unknown_error),
                    ),
                )
            }
        }
    }

    fun startMove(
        entry: ContainerStorageManager.Entry,
        target: ContainerStorageManager.MoveTarget,
    ) {
        if (isMoving) return

        if (target == ContainerStorageManager.MoveTarget.EXTERNAL && !ContainerStorageManager.isExternalStorageConfigured()) {
            SnackbarManager.show(appContext.getString(R.string.container_storage_move_external_disabled))
            return
        }

        val entryName = entry.displayName.ifBlank {
            appContext.getString(R.string.container_storage_unknown_container)
        }

        movingEntryName = entryName
        moveProgress = 0f
        moveCurrentFile = entryName
        moveMovedFiles = 0
        moveTotalFiles = 1

        scope.launch {
            val result = try {
                ContainerStorageManager.moveGame(
                    context = appContext,
                    entry = entry,
                    target = target,
                    onProgressUpdate = { currentFile, fileProgress, movedFiles, totalFiles ->
                        moveCurrentFile = currentFile
                        moveProgress = fileProgress
                        moveMovedFiles = movedFiles
                        moveTotalFiles = totalFiles
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                movingEntryName = null
            }

            if (result.isSuccess) {
                SnackbarManager.show(
                    appContext.getString(
                        R.string.container_storage_move_success,
                        entryName,
                        appContext.getString(
                            if (target == ContainerStorageManager.MoveTarget.EXTERNAL) {
                                R.string.container_storage_location_external
                            } else {
                                R.string.container_storage_location_internal
                            },
                        ),
                    ),
                )
                refresh()
            } else {
                SnackbarManager.show(
                    appContext.getString(
                        R.string.container_storage_move_failed,
                        entryName,
                        result.exceptionOrNull()?.message
                            ?: appContext.getString(R.string.container_storage_unknown_error),
                    ),
                )
            }
        }
    }
}

@Composable
fun rememberContainerStorageManagerUiState(): ContainerStorageManagerUiState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        ContainerStorageManagerUiState(
            appContext = context,
            scope = scope,
        )
    }
}

@Composable
fun ContainerStorageManagerTransientUi(
    state: ContainerStorageManagerUiState,
) {
    state.pendingRemoval?.let { entry ->
        val entryName = entry.displayName.ifBlank {
            stringResource(R.string.container_storage_unknown_container)
        }
        AlertDialog(
            onDismissRequest = state::dismissRemove,
            title = { Text(stringResource(R.string.container_storage_remove_title)) },
            text = { Text(stringResource(R.string.container_storage_remove_message, entryName)) },
            confirmButton = {
                TextButton(onClick = state::confirmRemove) {
                    Text(
                        text = stringResource(R.string.container_storage_remove_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissRemove) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    state.pendingUninstall?.let { entry ->
        val entryName = entry.displayName.ifBlank {
            stringResource(R.string.container_storage_unknown_container)
        }
        AlertDialog(
            onDismissRequest = state::dismissUninstall,
            title = {
                Text(
                    stringResource(
                        if (entry.hasContainer) {
                            R.string.container_storage_uninstall_title
                        } else {
                            R.string.container_storage_uninstall_game_only_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (entry.hasContainer) {
                            R.string.container_storage_uninstall_message
                        } else {
                            R.string.container_storage_uninstall_game_only_message
                        },
                        entryName,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = state::confirmUninstall) {
                    Text(
                        text = stringResource(R.string.container_storage_uninstall_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissUninstall) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (state.isMoving) {
        GameMigrationDialog(
            progress = state.moveProgress,
            currentFile = state.moveCurrentFile,
            movedFiles = state.moveMovedFiles,
            totalFiles = state.moveTotalFiles,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContainerStorageManagerContent(
    state: ContainerStorageManagerUiState,
    modifier: Modifier = Modifier,
    onDismissRequest: (() -> Unit)? = null,
    onOpenGame: ((GameSource, String, String, String) -> Unit)? = null,
) {
    LaunchedEffect(state) {
        state.ensureLoaded()
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.isLoading && !state.hasLoaded) {
                    stringResource(R.string.container_storage_loading)
                } else {
                    stringResource(
                        R.string.container_storage_summary,
                        state.entries.size,
                        StorageUtils.formatBinarySize(inventorySummaryBytes(state.entries)),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            if (onDismissRequest != null) {
                IconButton(
                    onClick = onDismissRequest,
                    enabled = !state.isMoving,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))

        when {
            state.isLoading && state.entries.isEmpty() -> {
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

            state.entries.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp),
                        )
                        Text(
                            text = stringResource(R.string.container_storage_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(state.entries, key = { it.containerId }) { entry ->
                        StorageEntryCard(
                            entry = entry,
                            actionsEnabled = !state.isMoving,
                            onOpenGame = onOpenGame,
                            onMoveToExternal = {
                                state.startMove(entry, ContainerStorageManager.MoveTarget.EXTERNAL)
                            },
                            onMoveToInternal = {
                                state.startMove(entry, ContainerStorageManager.MoveTarget.INTERNAL)
                            },
                            onRemove = { state.requestRemove(entry) },
                            onUninstall = { state.requestUninstall(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContainerStorageManagerDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    state: ContainerStorageManagerUiState = rememberContainerStorageManagerUiState(),
) {
    if (!visible) return

    ContainerStorageManagerTransientUi(state)

    Dialog(
        onDismissRequest = {
            if (!state.isMoving) {
                onDismissRequest()
            }
        },
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
                ContainerStorageManagerContent(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PluviaTheme.colors.surfacePanel)
                        .padding(20.dp),
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageEntryCard(
    entry: ContainerStorageManager.Entry,
    actionsEnabled: Boolean,
    onOpenGame: ((GameSource, String, String, String) -> Unit)?,
    onMoveToExternal: () -> Unit,
    onMoveToInternal: () -> Unit,
    onRemove: () -> Unit,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current
    val displayName = entry.displayName.ifBlank {
        stringResource(R.string.container_storage_unknown_container)
    }
    val storageLocation = ContainerStorageManager.getStorageLocation(context, entry)
    val canMoveToExternal = ContainerStorageManager.canMoveToExternal(context, entry)
    val canMoveToInternal = ContainerStorageManager.canMoveToInternal(context, entry)
    val canOpenGame = onOpenGame != null && entry.gameSource != null && !entry.appId.isNullOrBlank()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StorageArtworkButton(
                    imageUrl = entry.iconUrl,
                    contentDescription = displayName,
                    enabled = canOpenGame,
                    onClick = {
                        val gameSource = entry.gameSource
                        val appId = entry.appId
                        if (gameSource != null && !appId.isNullOrBlank()) {
                            onOpenGame?.invoke(gameSource, appId, displayName, entry.iconUrl)
                        }
                    },
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
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

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                if (storageLocation != ContainerStorageManager.StorageLocation.UNKNOWN) {
                    MetadataChip(
                        text = storageLocationLabel(storageLocation),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = sizeBreakdown(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (canMoveToExternal || canMoveToInternal || entry.canUninstallGame || entry.hasContainer) {
                Spacer(modifier = Modifier.height(14.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canMoveToExternal) {
                        StorageActionButton(
                            text = stringResource(R.string.container_storage_move_to_external_button),
                            icon = Icons.Default.ArrowDownward,
                            onClick = onMoveToExternal,
                            enabled = actionsEnabled,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (canMoveToInternal) {
                        StorageActionButton(
                            text = stringResource(R.string.container_storage_move_to_internal_button),
                            icon = Icons.Default.ArrowUpward,
                            onClick = onMoveToInternal,
                            enabled = actionsEnabled,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (entry.canUninstallGame) {
                        StorageActionButton(
                            text = stringResource(R.string.container_storage_uninstall_button),
                            icon = Icons.Default.DeleteForever,
                            onClick = onUninstall,
                            enabled = actionsEnabled,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (entry.hasContainer) {
                        StorageActionButton(
                            text = stringResource(R.string.container_storage_remove_button),
                            icon = Icons.Default.Delete,
                            onClick = onRemove,
                            enabled = actionsEnabled,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageArtworkButton(
    imageUrl: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple

    Box(
        modifier = Modifier
            .size(52.dp)
            .background(
                color = if (isFocused) {
                    accentColor.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) {
                    accentColor.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                },
                shape = RoundedCornerShape(10.dp),
            )
            .selectable(
                selected = isFocused,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNotBlank()) {
            CoilImage(
                imageModel = { imageUrl },
                imageOptions = ImageOptions(
                    contentScale = ContentScale.Crop,
                    contentDescription = contentDescription,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = contentDescription,
                tint = if (enabled && isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun StorageActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple

    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        interactionSource = interactionSource,
        border = BorderStroke(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) {
                accentColor.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            },
        ),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isFocused) accentColor.copy(alpha = 0.18f) else containerColor,
            contentColor = if (isFocused) accentColor else contentColor,
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

private fun inventorySummaryBytes(entries: List<ContainerStorageManager.Entry>): Long {
    val containerBytes = entries
        .filter { it.hasContainer }
        .sumOf { it.containerSizeBytes }
    val gameBytes = entries
        .mapNotNull { entry ->
            val installPath = entry.installPath ?: return@mapNotNull null
            val gameSize = entry.gameInstallSizeBytes ?: return@mapNotNull null
            installPath to gameSize
        }
        .distinctBy { it.first }
        .sumOf { it.second }
    return containerBytes + gameBytes
}

private fun sizeBreakdown(entry: ContainerStorageManager.Entry): String {
    val parts = mutableListOf<String>()

    entry.gameInstallSizeBytes?.let {
        parts += "Game ${StorageUtils.formatBinarySize(it)}"
    }

    if (entry.hasContainer) {
        parts += "Container ${StorageUtils.formatBinarySize(entry.containerSizeBytes)}"
    }

    if (entry.hasContainer && entry.gameInstallSizeBytes != null) {
        entry.combinedSizeBytes?.let {
            parts += "Total ${StorageUtils.formatBinarySize(it)}"
        }
    }

    return parts.joinToString(" • ")
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
private fun storageLocationLabel(location: ContainerStorageManager.StorageLocation): String = when (location) {
    ContainerStorageManager.StorageLocation.INTERNAL -> stringResource(R.string.container_storage_location_internal)
    ContainerStorageManager.StorageLocation.EXTERNAL -> stringResource(R.string.container_storage_location_external)
    ContainerStorageManager.StorageLocation.UNKNOWN -> stringResource(R.string.container_storage_location_unknown)
}

@Composable
private fun statusLabel(status: ContainerStorageManager.Status): String = when (status) {
    ContainerStorageManager.Status.READY -> stringResource(R.string.container_storage_status_ready)
    ContainerStorageManager.Status.NO_CONTAINER -> stringResource(R.string.container_storage_status_no_container)
    ContainerStorageManager.Status.GAME_FILES_MISSING -> stringResource(R.string.container_storage_status_game_files_missing)
    ContainerStorageManager.Status.ORPHANED -> stringResource(R.string.container_storage_status_orphaned)
    ContainerStorageManager.Status.UNREADABLE -> stringResource(R.string.container_storage_status_unreadable)
}

@Composable
private fun statusContainerColor(status: ContainerStorageManager.Status) = when (status) {
    ContainerStorageManager.Status.READY -> MaterialTheme.colorScheme.secondaryContainer
    ContainerStorageManager.Status.NO_CONTAINER -> MaterialTheme.colorScheme.primaryContainer
    ContainerStorageManager.Status.GAME_FILES_MISSING -> MaterialTheme.colorScheme.tertiaryContainer
    ContainerStorageManager.Status.ORPHANED -> MaterialTheme.colorScheme.errorContainer
    ContainerStorageManager.Status.UNREADABLE -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun statusContentColor(status: ContainerStorageManager.Status) = when (status) {
    ContainerStorageManager.Status.READY -> MaterialTheme.colorScheme.onSecondaryContainer
    ContainerStorageManager.Status.NO_CONTAINER -> MaterialTheme.colorScheme.onPrimaryContainer
    ContainerStorageManager.Status.GAME_FILES_MISSING -> MaterialTheme.colorScheme.onTertiaryContainer
    ContainerStorageManager.Status.ORPHANED -> MaterialTheme.colorScheme.onErrorContainer
    ContainerStorageManager.Status.UNREADABLE -> MaterialTheme.colorScheme.onSurface
}
