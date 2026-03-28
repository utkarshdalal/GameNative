package app.gamenative.ui.screen.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

// TODO: gate behind expert mode when that exists
private const val ENABLE_BREAKDOWN_DELETE = false

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

    var breakdownEntry by mutableStateOf<ContainerStorageManager.Entry?>(null)
        private set

    var breakdownItems by mutableStateOf<List<ContainerStorageManager.DirSize>>(emptyList())
        private set

    var isLoadingBreakdown by mutableStateOf(false)
        private set

    var expandedPaths by mutableStateOf<Set<String>>(emptySet())
        private set

    var loadingPaths by mutableStateOf<Set<String>>(emptySet())
        private set

    // guards against stale async completions when switching breakdown targets
    private var breakdownSession = 0

    val isMoving: Boolean
        get() = movingEntryName != null

    fun requestBreakdown(entry: ContainerStorageManager.Entry) {
        val session = ++breakdownSession
        breakdownEntry = entry
        breakdownItems = emptyList()
        expandedPaths = emptySet()
        loadingPaths = emptySet()
        isLoadingBreakdown = true
        scope.launch {
            try {
                val result = ContainerStorageManager.getStorageBreakdown(appContext, entry)
                if (breakdownSession == session) breakdownItems = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load storage breakdown")
                SnackbarManager.show(e.message ?: appContext.getString(R.string.container_storage_breakdown_load_failed))
            }
            if (breakdownSession == session) isLoadingBreakdown = false
        }
    }

    fun toggleExpand(item: ContainerStorageManager.DirSize) {
        if (!item.isDirectory) return
        val path = item.path
        if (loadingPaths.contains(path)) return

        val pathPrefix = "$path/"
        if (expandedPaths.contains(path)) {
            expandedPaths = expandedPaths - path - expandedPaths.filter { it.startsWith(pathPrefix) }.toSet()
            loadingPaths = loadingPaths - path
            breakdownItems = breakdownItems.filter { entry ->
                !(entry.depth > item.depth && (entry.path.startsWith(pathPrefix) || entry.path.isEmpty()))
            }
        } else {
            expandedPaths = expandedPaths + path
            loadingPaths = loadingPaths + path
            val session = breakdownSession
            scope.launch {
                try {
                    val children = ContainerStorageManager.expandDirectory(path, item.depth + 1)
                    if (breakdownSession != session) return@launch
                    if (expandedPaths.contains(path) && children.isNotEmpty()) {
                        val idx = breakdownItems.indexOf(item)
                        if (idx >= 0) {
                            val mutable = breakdownItems.toMutableList()
                            mutable.addAll(idx + 1, children)
                            breakdownItems = mutable
                        } else {
                            // parent vanished from list, clean up
                            expandedPaths = expandedPaths - path
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to expand directory")
                    if (breakdownSession == session) expandedPaths = expandedPaths - path
                }
                if (breakdownSession == session) loadingPaths = loadingPaths - path
            }
        }
    }

    var trashSizes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    var pendingEmptyTrash by mutableStateOf<ContainerStorageManager.Entry?>(null)
        private set

    var pendingDelete by mutableStateOf<ContainerStorageManager.DirSize?>(null)
        private set

    fun isExpanded(item: ContainerStorageManager.DirSize): Boolean = expandedPaths.contains(item.path)

    fun requestDeleteDir(item: ContainerStorageManager.DirSize) {
        pendingDelete = item
    }

    fun dismissDeleteDir() {
        pendingDelete = null
    }

    fun confirmDeleteDir() {
        val item = pendingDelete ?: return
        pendingDelete = null
        scope.launch {
            try {
                val deleted = ContainerStorageManager.deleteDirectory(appContext, item.path)
                if (deleted) {
                    val prefix = "${item.path}/"
                    expandedPaths = expandedPaths.filter { it != item.path && !it.startsWith(prefix) }.toSet()
                    breakdownItems = breakdownItems.filter { entry ->
                        entry.path != item.path &&
                            !(entry.depth > item.depth && (entry.path.startsWith(prefix) || entry.path.isEmpty()))
                    }
                    SnackbarManager.show(appContext.getString(R.string.container_storage_deleted, item.name))
                    refresh()
                } else {
                    SnackbarManager.show(appContext.getString(R.string.container_storage_delete_failed, item.name))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete directory")
                SnackbarManager.show(e.message ?: appContext.getString(R.string.container_storage_delete_failed, item.name))
            }
        }
    }

    fun requestEmptyTrash(entry: ContainerStorageManager.Entry) {
        pendingEmptyTrash = entry
    }

    fun dismissEmptyTrash() {
        pendingEmptyTrash = null
    }

    fun confirmEmptyTrash() {
        val entry = pendingEmptyTrash ?: return
        pendingEmptyTrash = null
        scope.launch {
            try {
                val success = ContainerStorageManager.emptyTrash(appContext, entry.containerId)
                if (success) {
                    trashSizes = trashSizes - entry.containerId
                    SnackbarManager.show(appContext.getString(R.string.container_storage_trash_emptied))
                    refresh()
                } else {
                    SnackbarManager.show(appContext.getString(R.string.container_storage_trash_empty_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to empty trash")
                SnackbarManager.show(e.message ?: appContext.getString(R.string.container_storage_trash_empty_failed))
            }
        }
    }

    fun dismissBreakdown() {
        breakdownEntry = null
        breakdownItems = emptyList()
        expandedPaths = emptySet()
        isLoadingBreakdown = false
    }

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
            }.onSuccess { loaded ->
                entries = loaded
                hasLoaded = true
                trashSizes = coroutineScope {
                    loaded.filter { it.hasContainer }.map { entry ->
                        async {
                            runCatching {
                                entry.containerId to ContainerStorageManager.getTrashSize(appContext, entry.containerId)
                            }.getOrNull()
                        }
                    }.awaitAll()
                        .filterNotNull()
                        .filter { it.second > 0L }
                        .toMap()
                }
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

    state.pendingEmptyTrash?.let { entry ->
        val trashSize = state.trashSizes[entry.containerId] ?: 0L
        AlertDialog(
            onDismissRequest = state::dismissEmptyTrash,
            title = { Text(stringResource(R.string.container_storage_empty_trash_title)) },
            text = {
                Text(stringResource(R.string.container_storage_empty_trash_message, StorageUtils.formatBinarySize(trashSize), entry.displayName))
            },
            confirmButton = {
                TextButton(onClick = state::confirmEmptyTrash) {
                    Text(
                        text = stringResource(R.string.container_storage_empty_trash_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissEmptyTrash) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    state.breakdownEntry?.let { entry ->
        StorageBreakdownDialog(
            entryName = entry.displayName,
            state = state,
        )
    }
}

@Composable
private fun StorageBreakdownDialog(
    entryName: String,
    state: ContainerStorageManagerUiState,
) {
    Dialog(
        onDismissRequest = state::dismissBreakdown,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.container_storage_breakdown_title, entryName),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (state.isLoadingBreakdown && state.breakdownItems.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (state.breakdownItems.isEmpty()) {
                    Text(stringResource(R.string.container_storage_breakdown_no_data))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(
                            state.breakdownItems,
                            key = { index, it -> it.path.ifEmpty { "${it.name}:${it.depth}:$index" } },
                        ) { _, dir ->
                            val indent = (dir.depth * 16).dp
                            val isExpanded = state.isExpanded(dir)
                            val dirInteractionSource = remember { MutableInteractionSource() }
                            val isDirFocused by dirInteractionSource.collectIsFocusedAsState()
                            val accentColor = PluviaTheme.colors.accentPurple
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (dir.isDirectory) {
                                            Modifier
                                                .border(
                                                    width = if (isDirFocused) 2.dp else 0.dp,
                                                    color = if (isDirFocused) accentColor.copy(alpha = 0.7f) else Color.Transparent,
                                                    shape = RoundedCornerShape(6.dp),
                                                )
                                                .selectable(
                                                    selected = isExpanded,
                                                    interactionSource = dirInteractionSource,
                                                    indication = null, // focus border above handles feedback
                                                    onClick = { state.toggleExpand(dir) },
                                                )
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(start = indent, top = 2.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (dir.isDirectory) {
                                        Icon(
                                            imageVector = if (isExpanded) {
                                                Icons.Default.ArrowDownward
                                            } else {
                                                Icons.Default.ArrowForward
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(modifier = Modifier.size(4.dp))
                                    }
                                    Text(
                                        text = dir.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = StorageUtils.formatBinarySize(dir.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (ENABLE_BREAKDOWN_DELETE && dir.isDirectory && dir.depth > 0) {
                                    IconButton(
                                        onClick = { state.requestDeleteDir(dir) },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.container_storage_delete_cd),
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = state::dismissBreakdown) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }

    state.pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = state::dismissDeleteDir,
            title = { Text(stringResource(R.string.container_storage_delete_dir_title)) },
            text = {
                Text(stringResource(R.string.container_storage_delete_dir_message, item.name, StorageUtils.formatBinarySize(item.sizeBytes)))
            },
            confirmButton = {
                TextButton(onClick = state::confirmDeleteDir) {
                    Text(
                        text = stringResource(R.string.container_storage_delete_dir_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissDeleteDir) {
                    Text(stringResource(R.string.cancel))
                }
            },
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
                    modifier = Modifier.fillMaxSize().focusGroup(),
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
                            onBreakdown = { state.requestBreakdown(entry) },
                            onEmptyTrash = { state.requestEmptyTrash(entry) },
                            trashSizeBytes = state.trashSizes[entry.containerId] ?: 0L,
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
    onBreakdown: () -> Unit,
    onEmptyTrash: () -> Unit,
    trashSizeBytes: Long,
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
                    modifier = Modifier.fillMaxWidth().focusGroup(),
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
                    // TODO: "Open Container" button — launch Wine desktop mode for manual cleanup
                    StorageActionButton(
                        text = stringResource(R.string.container_storage_breakdown_button),
                        icon = Icons.Default.Info,
                        onClick = onBreakdown,
                        enabled = actionsEnabled,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    if (trashSizeBytes > 0L) {
                        StorageActionButton(
                            text = stringResource(R.string.container_storage_empty_trash_sized_button, StorageUtils.formatBinarySize(trashSizeBytes)),
                            icon = Icons.Default.Delete,
                            onClick = onEmptyTrash,
                            enabled = actionsEnabled,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
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
