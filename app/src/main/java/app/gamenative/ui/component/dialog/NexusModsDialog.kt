package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModProfile
import app.gamenative.data.ModProfileInstallState
import app.gamenative.data.ModTargetRoot
import app.gamenative.mods.BethesdaPlacementRecipeExpander
import app.gamenative.mods.BethesdaGame
import app.gamenative.mods.BethesdaPlugin
import app.gamenative.mods.BethesdaPluginAssetIssue
import app.gamenative.mods.BethesdaPluginDependencyIssue
import app.gamenative.mods.BethesdaPluginManager
import app.gamenative.mods.FomodInstaller
import app.gamenative.mods.FomodAutoSelector
import app.gamenative.mods.FomodInstallerDetector
import app.gamenative.mods.FomodParser
import app.gamenative.mods.ModArchiveEntry
import app.gamenative.mods.ModArchiveInstallAssessor
import app.gamenative.mods.ModConflictAnalyzer
import app.gamenative.mods.ModDownloadInfo
import app.gamenative.mods.ModDownloadRegistry
import app.gamenative.mods.ModFileConflictReport
import app.gamenative.mods.ModHealthReport
import app.gamenative.mods.ModHealthSeverity
import app.gamenative.mods.ModImportProgress
import app.gamenative.mods.ModMaterializer
import app.gamenative.mods.ModPathDetector
import app.gamenative.mods.ModPlacementConflict
import app.gamenative.mods.ModPlacementPreset
import app.gamenative.mods.ModPlacementSources
import app.gamenative.mods.ModProfileManager
import app.gamenative.mods.ModStorageBreakdown
import app.gamenative.mods.ModTargetResolver
import app.gamenative.mods.NexusApiClient
import app.gamenative.mods.NexusApiException
import app.gamenative.mods.NexusCollectionFile
import app.gamenative.mods.NexusCollectionInfo
import app.gamenative.mods.NexusCollectionPrioritySuggester
import app.gamenative.mods.NexusCollectionReusePolicy
import app.gamenative.mods.NexusCollectionUrlParser
import app.gamenative.mods.NexusImportState
import app.gamenative.mods.NexusModFile
import app.gamenative.mods.NexusModInfo
import app.gamenative.mods.NexusModManager
import app.gamenative.mods.NexusModReference
import app.gamenative.mods.NexusUrlParser
import app.gamenative.service.NexusModImportService
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
internal data class RecipeDraft(
    val sourceSubpath: String = "",
    val targetRoot: String = ModTargetRoot.GAME_DIR.name,
    val targetRelativePath: String = "",
    val mode: String = ModPlacementMode.SYMLINK.name,
    val stripPrefixSegments: Int = 0,
    val includeSourceDirectory: Boolean = false,
)

internal enum class PlacementChoice {
    AUTOMATIC,
    PRESET,
    LAST_USED,
    CUSTOM,
}

private enum class ManageModsTab {
    IMPORT,
    MODS,
    PLACEMENT,
    ISSUES,
}

private const val MIN_APPLY_FREE_BYTES = 2L * 1024L * 1024L * 1024L

internal data class ApiKeyValidationState(
    val checking: Boolean = false,
    val message: String = "",
    val success: Boolean? = null,
)

internal data class PendingFileSelection(
    val reference: NexusModReference,
    val modInfo: NexusModInfo,
    val files: List<NexusModFile>,
)

internal data class PendingCollectionSelection(
    val collection: NexusCollectionInfo,
    val mods: List<PendingCollectionMod>,
)

internal data class PendingCollectionMod(
    val collectionFile: NexusCollectionFile,
    val modInfo: NexusModInfo?,
    val file: NexusModFile?,
    val error: String? = null,
)

internal enum class CollectionQueueStatus {
    QUEUED,
    IMPORTING,
    IMPORTED,
    FAILED,
    CANCELED,
}

internal data class CollectionQueueItem(
    val key: String,
    val name: String,
    val status: CollectionQueueStatus,
    val progress: Float = 0f,
    val message: String = "",
    val error: String = "",
    val startedAt: Long = 0L,
)

internal data class PendingFomodResult(
    val drafts: List<RecipeDraft>,
    val unsupportedCount: Int,
    val selectedOptions: List<String>,
    val conditionalRuleCount: Int,
)

internal data class PendingApply(
    val install: ModInstall,
    val recipes: List<ModPlacementRecipe>,
    val conflicts: List<ModPlacementConflict>,
)

internal data class PendingProfileApply(
    val conflicts: List<ModPlacementConflict>,
)

internal data class PendingProfileNameEdit(
    val profile: ModProfile?,
    val initialName: String,
)

internal data class ArchiveBrowserItem(
    val name: String,
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long = 0L,
)

internal data class PlacementPresetOption(
    val preset: ModPlacementPreset,
    val drafts: List<RecipeDraft>,
)

private data class ModDiagnosticsSnapshot(
    val conflicts: List<ModFileConflictReport>,
    val placementNeededInstallIds: Set<String>,
    val bethesdaGame: BethesdaGame?,
    val plugins: List<BethesdaPlugin>,
    val pluginIssues: List<BethesdaPluginDependencyIssue>,
    val pluginAssetIssues: List<BethesdaPluginAssetIssue>,
)

private data class ProfileOrderPlan(
    val stateByInstallId: Map<String, ModProfileInstallState>,
    val disabledInstalls: List<ModInstall>,
    val configuredInstalls: List<ModInstall>,
    val installsToApply: List<ModInstall>,
    val missingTargetRepairInstallIds: Set<String>,
    val recipesByInstallId: Map<String, List<ModPlacementRecipe>>,
    val recipesToPersistByInstallId: Map<String, List<ModPlacementRecipe>>,
    val unconfiguredCount: Int,
    val unconfiguredNames: List<String>,
    val bethesdaGame: BethesdaGame?,
    val plugins: List<BethesdaPlugin>,
    val pluginIssues: List<BethesdaPluginDependencyIssue>,
    val pluginAssetIssues: List<BethesdaPluginAssetIssue>,
)

private data class ProfileOrderConflictCheck(
    val rawConflicts: List<ModPlacementConflict>,
    val conflicts: List<ModPlacementConflict>,
    val hasOverwriteRecipe: Boolean,
)

private data class ProfileOrderApplyResult(
    val errors: Int,
    val bethesdaGame: BethesdaGame?,
    val plugins: List<BethesdaPlugin>,
    val pluginIssues: List<BethesdaPluginDependencyIssue>,
    val pluginAssetIssues: List<BethesdaPluginAssetIssue>,
)

@Composable
private fun NexusDialogSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = data.visuals.message,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ManageModsSummaryBar(
    installs: List<ModInstall>,
    enabledByInstallId: Map<String, Boolean>,
    activeProfile: ModProfile?,
    activeDownload: ModDownloadInfo?,
    issueCount: Int,
    diagnosticsLoading: Boolean,
    busyText: String?,
    modifier: Modifier = Modifier,
) {
    val placeable = installs.filter { it.canPlaceFiles() }
    val enabledCount = placeable.count {
        it.status == ModInstallStatus.APPLIED.name && isEnabledInProfile(it, enabledByInstallId)
    }
    val queueText = activeDownload?.status ?: when {
        busyText != null -> busyText
        diagnosticsLoading -> stringResource(R.string.nexus_scanning)
        issueCount > 0 -> stringResource(R.string.nexus_issue_count, issueCount)
        else -> stringResource(R.string.nexus_idle)
    }
    val summary = stringResource(
        R.string.nexus_summary_bar,
        placeable.size,
        enabledCount,
        activeProfile?.name ?: stringResource(R.string.nexus_default_profile),
        queueText,
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ManageModsTabs(
    selectedTab: ManageModsTab,
    onSelect: (ManageModsTab) -> Unit,
) {
    val tabs = ManageModsTab.entries
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 420.dp
        TabRow(
            selectedTabIndex = tabs.indexOf(selectedTab),
            modifier = Modifier.fillMaxWidth(),
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { onSelect(tab) },
                    text = {
                        Text(
                            text = when (tab) {
                                ManageModsTab.IMPORT -> stringResource(R.string.nexus_tab_import)
                                ManageModsTab.MODS -> stringResource(R.string.nexus_tab_mods)
                                ManageModsTab.PLACEMENT -> stringResource(if (compact) R.string.nexus_tab_placement_short else R.string.nexus_tab_placement)
                                ManageModsTab.ISSUES -> stringResource(R.string.nexus_tab_issues)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun NexusSectionCard(verticalSpacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(verticalSpacing), content = content)
    }
}

@Composable
private fun NexusSectionHeader(title: String, loading: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        TextButton(onClick = onAction, enabled = !loading) { Text(actionLabel) }
    }
}

@Composable
private fun OverwriteConfirmDialog(
    title: String,
    message: String,
    conflicts: List<ModPlacementConflict>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(message)
                conflicts.take(12).forEach { conflict ->
                    Text(
                        text = conflict.targetPath.replace(File.separatorChar, '/'),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (conflicts.size > 12) Text(stringResource(R.string.nexus_more_prefixed, conflicts.size - 12))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun applyCollectionPluginOrder(
    plugins: List<BethesdaPlugin>,
    pluginLoadOrder: List<String>,
): List<BethesdaPlugin> {
    if (pluginLoadOrder.isEmpty() || plugins.isEmpty()) return plugins
    val orderByName = pluginLoadOrder
        .mapIndexed { index, name -> name.trim().removePrefix("*").lowercase() to index }
        .toMap()
    return plugins
        .sortedWith(
            compareBy<BethesdaPlugin> { orderByName[it.fileName.lowercase()] ?: Int.MAX_VALUE }
                .thenBy { it.orderIndex }
                .thenBy { it.priority }
                .thenBy { it.fileName.lowercase() },
        )
        .mapIndexed { index, plugin -> plugin.copy(orderIndex = index) }
}

@Composable
private fun EmptyWorkflowSection(
    title: String,
    subtitle: String,
) {
    NexusSectionCard(verticalSpacing = 6.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StorageCleanupSection(
    breakdown: ModStorageBreakdown?,
    loading: Boolean,
    onScan: () -> Unit,
    onCleanTemp: () -> Unit,
    onDeleteFailedArchives: () -> Unit,
    onCleanRedundantBackups: () -> Unit,
) {
    fun size(bytes: Long) = StorageUtils.formatBinarySize(bytes)
    @Composable
    fun Line(label: String, bytes: Long) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(size(bytes), fontWeight = FontWeight.SemiBold)
        }
    }
    val cleanable = breakdown?.cleanableBytes ?: 0L
    val failedArchives = breakdown?.failedArchiveBytes ?: 0L
    val redundantBackupCount = breakdown?.redundantBackupCount ?: 0
    val redundantBackupLabel = if (redundantBackupCount > 0) {
        stringResource(R.string.nexus_backups_safe_to_clean_records, redundantBackupCount)
    } else {
        stringResource(R.string.nexus_backups_safe_to_clean)
    }
    NexusSectionCard {
        NexusSectionHeader(stringResource(R.string.nexus_storage_cleanup_title), loading, stringResource(R.string.nexus_scan), onScan)
        Text(
            stringResource(R.string.nexus_storage_cleanup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.nexus_storage_cleanup_cache_backups_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.nexus_storage_cleanup_actions_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Line(stringResource(R.string.nexus_temp_orphaned_files), cleanable)
        Line(stringResource(R.string.nexus_failed_download_archives), failedArchives)
        breakdown?.let {
            Line(stringResource(R.string.nexus_extracted_mod_cache), it.extractedCacheBytes)
            Line(stringResource(R.string.nexus_rollback_backups), it.backupBytes)
            Line(redundantBackupLabel, it.redundantBackupBytes)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 420.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCleanTemp, enabled = !loading && cleanable > 0L, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nexus_clean_temp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onDeleteFailedArchives, enabled = !loading && failedArchives > 0L, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nexus_delete_failed_archives), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCleanTemp, enabled = !loading && cleanable > 0L, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.nexus_clean_temp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onDeleteFailedArchives, enabled = !loading && failedArchives > 0L, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.nexus_delete_failed_archives), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        TextButton(
            onClick = onCleanRedundantBackups,
            enabled = !loading && redundantBackupCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.nexus_clean_redundant_backups), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InstallHealthSection(
    report: ModHealthReport?,
    loading: Boolean,
    onCheck: () -> Unit,
) {
    NexusSectionCard {
        NexusSectionHeader(stringResource(R.string.nexus_install_health_title), loading, stringResource(R.string.nexus_check), onCheck)
        Text(
            stringResource(R.string.nexus_install_health_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        report?.let { current ->
            if (current.issues.isEmpty()) {
                Text(stringResource(R.string.nexus_no_install_health_issues), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                val summaryColor = if (current.errorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Text(stringResource(R.string.nexus_install_health_summary, current.errorCount, current.warningCount), style = MaterialTheme.typography.bodySmall, color = summaryColor)
                current.issues.take(8).forEach { issue ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val titleColor = if (issue.severity == ModHealthSeverity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        Text(listOf(issue.installName, issue.title).filter(String::isNotBlank).joinToString(": "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = titleColor)
                        Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (current.issues.size > 8) {
                    Text(stringResource(R.string.nexus_more_prefixed, current.issues.size - 8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NexusModsDialog(
    visible: Boolean,
    libraryItem: LibraryItem,
    gameRootDir: File?,
    winePrefix: String,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember(context) { NexusModManager.dao(context) }
    val installs by dao.observeInstallsForApp(libraryItem.appId).collectAsState(initial = emptyList())
    val activeDownloads by ModDownloadRegistry.observeDownloads().collectAsState()
    val activeDownload = activeDownloads.values.firstOrNull { it.appId == libraryItem.appId }
    val activeImportProgress = activeDownload?.toImportProgress()
    val profiles by dao.observeProfilesForApp(libraryItem.appId).collectAsState(initial = emptyList())
    val activeProfile = profiles.firstOrNull { it.active }
    val profileStateFlow = remember(libraryItem.appId, activeProfile?.profileId) {
        activeProfile?.let { dao.observeProfileInstallStates(libraryItem.appId, it.profileId) }
            ?: flowOf(emptyList())
    }
    val profileStates by profileStateFlow.collectAsState(initial = emptyList())
    val priorityByInstallId = remember(profileStates) { profileStates.associate { it.installId to it.priority } }
    val profileEnabledByInstallId = remember(profileStates) { profileStates.associate { it.installId to it.enabled } }
    val apiClient = remember { NexusApiClient() }
    val roots = remember(gameRootDir, winePrefix, context) {
        ModTargetResolver.roots(gameRootDir, winePrefix).ifEmpty {
            listOfNotNull(gameRootDir?.takeIf { it.isDirectory }?.let {
                app.gamenative.mods.ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, context.getString(R.string.nexus_game_directory_root), it)
            })
        }
    }
    val fallbackDefaultDraft = remember(roots) {
        RecipeDraft(targetRoot = roots.firstOrNull()?.type?.name ?: ModTargetRoot.GAME_DIR.name)
    }

    var apiKey by remember { mutableStateOf(PrefManager.nexusApiKey) }
    var apiKeyValidation by remember { mutableStateOf<ApiKeyValidationState?>(null) }
    var nexusUrl by remember { mutableStateOf("") }
    var loadingMessage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var importProgress by remember { mutableStateOf<ModImportProgress?>(null) }
    var selectedInstall by remember { mutableStateOf<ModInstall?>(null) }
    var archiveEntries by remember { mutableStateOf<List<ModArchiveEntry>>(emptyList()) }
    var selectedFomodInstaller by remember { mutableStateOf<FomodInstaller?>(null) }
    var conflictReports by remember { mutableStateOf<List<ModFileConflictReport>>(emptyList()) }
    var placementNeededInstallIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bethesdaGame by remember(libraryItem.name) { mutableStateOf(BethesdaPluginManager.detectGame(libraryItem.name)) }
    var bethesdaPlugins by remember { mutableStateOf<List<BethesdaPlugin>>(emptyList()) }
    var bethesdaPluginIssues by remember { mutableStateOf<List<BethesdaPluginDependencyIssue>>(emptyList()) }
    var bethesdaPluginAssetIssues by remember { mutableStateOf<List<BethesdaPluginAssetIssue>>(emptyList()) }
    var diagnosticsLoading by remember { mutableStateOf(false) }
    var pendingFileSelection by remember { mutableStateOf<PendingFileSelection?>(null) }
    var pendingCollectionSelection by remember { mutableStateOf<PendingCollectionSelection?>(null) }
    var selectedCollectionKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val collectionQueue = remember { mutableStateMapOf<String, CollectionQueueItem>() }
    var collectionPaused by remember { mutableStateOf(false) }
    var collectionCancelRequested by remember { mutableStateOf(false) }
    var activeCollectionInstallId by remember { mutableStateOf<String?>(null) }
    var pendingApply by remember { mutableStateOf<PendingApply?>(null) }
    var pendingProfileApply by remember { mutableStateOf<PendingProfileApply?>(null) }
    var modApplyInProgress by remember { mutableStateOf(false) }
    var profileApplyInProgress by remember { mutableStateOf(false) }
    var placementApplyStatusMessage by remember { mutableStateOf<String?>(null) }
    var pendingProfileNameEdit by remember { mutableStateOf<PendingProfileNameEdit?>(null) }
    var pendingProfileDelete by remember { mutableStateOf<ModProfile?>(null) }
    var placementChoice by remember { mutableStateOf(PlacementChoice.AUTOMATIC) }
    var lastPlacementDrafts by remember(libraryItem.appId) { mutableStateOf<List<RecipeDraft>>(emptyList()) }
    var detectedDefaultDraft by remember(libraryItem.appId) { mutableStateOf<RecipeDraft?>(null) }
    val defaultDraft = detectedDefaultDraft ?: fallbackDefaultDraft
    var selectedTab by remember(libraryItem.appId) { mutableStateOf(ManageModsTab.MODS) }
    val recipeDrafts = remember { mutableStateListOf<RecipeDraft>() }
    var storageBreakdown by remember(libraryItem.appId) { mutableStateOf<ModStorageBreakdown?>(null) }
    var storageLoading by remember(libraryItem.appId) { mutableStateOf(false) }
    var healthReport by remember(libraryItem.appId) { mutableStateOf<ModHealthReport?>(null) }
    var healthLoading by remember(libraryItem.appId) { mutableStateOf(false) }
    var diagnosticsPaused by remember { mutableStateOf(false) }

    fun refreshLastPlacement() {
        lastPlacementDrafts = NexusModManager.lastPlacementRecipesForApp(libraryItem.appId, "")
            .map { it.toDraft() }
    }

    fun refreshStorageBreakdown() {
        scope.launch {
            storageLoading = true
            storageBreakdown = NexusModManager.scanStorageForApp(context, libraryItem.appId)
            storageLoading = false
        }
    }

    fun runStorageCleanup(failedArchives: Boolean) {
        scope.launch {
            storageLoading = true
            try {
                val result = if (failedArchives) {
                    NexusModManager.cleanupFailedArchivesForApp(context, libraryItem.appId)
                } else {
                    NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
                }
                storageBreakdown = NexusModManager.scanStorageForApp(context, libraryItem.appId)
                SnackbarManager.show(context.getString(R.string.nexus_freed_size, StorageUtils.formatBinarySize(result.reclaimedBytes)))
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_storage_cleanup_failed))
            } finally {
                storageLoading = false
            }
        }
    }

    fun cleanRedundantBackups() {
        scope.launch {
            storageLoading = true
            try {
                val result = NexusModManager.cleanupRedundantBackupsForApp(context, libraryItem.appId)
                storageBreakdown = NexusModManager.scanStorageForApp(context, libraryItem.appId)
                SnackbarManager.show(context.getString(R.string.nexus_freed_size, StorageUtils.formatBinarySize(result.reclaimedBytes)))
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_redundant_backup_cleanup_failed))
            } finally {
                storageLoading = false
            }
        }
    }

    fun runInstallHealthCheck() {
        scope.launch {
            healthLoading = true
            try {
                healthReport = NexusModManager.checkInstallHealthForApp(
                    context = context,
                    appId = libraryItem.appId,
                    gameRootDir = gameRootDir,
                    winePrefix = winePrefix,
                )
                val report = healthReport
                SnackbarManager.show(
                    if (report?.issues.isNullOrEmpty()) {
                        context.getString(R.string.nexus_no_install_health_issues)
                    } else {
                        context.getString(R.string.nexus_found_install_health_issues, report?.issues?.size ?: 0)
                    },
                )
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_install_health_check_failed))
            } finally {
                healthLoading = false
            }
        }
    }

    LaunchedEffect(libraryItem.appId) {
        refreshLastPlacement()
        launch(Dispatchers.IO) {
            ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
        }
        launch {
            delay(750)
            NexusModImportService.resumeInterruptedImports(context)
            NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
            storageBreakdown = NexusModManager.scanStorageForApp(context, libraryItem.appId)
        }
    }

    LaunchedEffect(roots, gameRootDir, winePrefix, libraryItem.name) {
        detectedDefaultDraft = null
        detectedDefaultDraft = withContext(Dispatchers.IO) {
            BethesdaPluginManager.detectGame(libraryItem.name)?.let { game ->
                return@withContext RecipeDraft(
                    targetRoot = ModTargetRoot.GAME_DIR.name,
                    targetRelativePath = game.dataDirName,
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                )
            }
            val detectedDir = ModPathDetector.detect(gameRootDir, winePrefix, libraryItem.name)
                ?.targetDirs
                ?.firstOrNull()
                ?.canonicalFile
            val root = detectedDir?.let { dir ->
                roots.firstOrNull { root ->
                    val rootFile = root.dir.canonicalFile
                    dir == rootFile || dir.path.startsWith(rootFile.path + File.separator)
                }
            } ?: roots.firstOrNull()
            val relative = if (detectedDir != null && root != null) {
                detectedDir.relativeToOrNull(root.dir.canonicalFile)?.path ?: ""
            } else {
                ""
            }
            RecipeDraft(
                targetRoot = root?.type?.name ?: ModTargetRoot.GAME_DIR.name,
                targetRelativePath = relative,
            )
        }
    }

    LaunchedEffect(pendingCollectionSelection) {
        val pending = pendingCollectionSelection
        selectedCollectionKeys = pending
            ?.mods
            ?.filter { it.canImport }
            ?.map { it.collectionKey() }
            ?.toSet()
            .orEmpty()
        collectionQueue.clear()
        collectionPaused = false
        collectionCancelRequested = false
        activeCollectionInstallId = null
    }

    LaunchedEffect(installs, profileStates, gameRootDir, winePrefix, libraryItem.appId, libraryItem.name, activeProfile?.profileId, diagnosticsPaused) {
        if (diagnosticsPaused) {
            diagnosticsLoading = false
            return@LaunchedEffect
        }
        diagnosticsLoading = true
        try {
            delay(300)
            val snapshot = withContext(Dispatchers.IO) {
                val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                val states = ModProfileManager.ensureStatesForInstalls(
                    dao = dao,
                    profile = profile,
                    installs = installs.filter { it.canPlaceFiles() },
                )
                val enabledStateByInstallId = states.associate { it.installId to it.enabled }
                val usableInstalls = installs.filter { it.canPlaceFiles() && isEnabledInProfile(it, enabledStateByInstallId) }
                val priorities = states.associate { it.installId to it.priority }
                val recipesByInstallId = usableInstalls.associate { install ->
                    install.installId to dao.getRecipesForInstall(install.installId)
                }
                val conflicts = ModConflictAnalyzer.analyze(
                    installs = usableInstalls,
                    recipesByInstallId = recipesByInstallId,
                    prioritiesByInstallId = priorities,
                    gameRootDir = gameRootDir,
                    winePrefix = winePrefix,
                )
                val game = BethesdaPluginManager.detectGame(libraryItem.name)
                val detectedPlugins = game?.let {
                    BethesdaPluginManager.detectPlugins(
                        installs = usableInstalls,
                        recipesByInstallId = recipesByInstallId,
                        prioritiesByInstallId = priorities,
                        gameRootDir = gameRootDir,
                        winePrefix = winePrefix,
                        pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, it),
                    )
                }.orEmpty()
                ModDiagnosticsSnapshot(
                    conflicts = conflicts,
                    placementNeededInstallIds = usableInstalls
                        .filter { install -> recipesByInstallId[install.installId].orEmpty().isEmpty() }
                        .mapTo(mutableSetOf()) { it.installId },
                    bethesdaGame = game,
                    plugins = detectedPlugins,
                    pluginIssues = game?.let {
                        BethesdaPluginManager.diagnosePluginMasters(
                            managedPlugins = detectedPlugins,
                            game = it,
                            gameRootDir = gameRootDir,
                            pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, it),
                        )
                    }.orEmpty(),
                    pluginAssetIssues = if (game != null) BethesdaPluginManager.diagnosePluginAssets(detectedPlugins) else emptyList(),
                )
            }
            conflictReports = snapshot.conflicts
            placementNeededInstallIds = snapshot.placementNeededInstallIds
            bethesdaGame = snapshot.bethesdaGame
            bethesdaPlugins = snapshot.plugins
            bethesdaPluginIssues = snapshot.pluginIssues
            bethesdaPluginAssetIssues = snapshot.pluginAssetIssues
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            conflictReports = emptyList()
            placementNeededInstallIds = emptySet()
            bethesdaPlugins = emptyList()
            bethesdaPluginIssues = emptyList()
            bethesdaPluginAssetIssues = emptyList()
            SnackbarManager.show(e.message ?: context.getString(R.string.nexus_diagnostics_scan_failed))
        } finally {
            diagnosticsLoading = false
        }
    }

    fun createProfile(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            SnackbarManager.show(context.getString(R.string.nexus_enter_profile_name))
            return
        }
        scope.launch {
            try {
                val active = ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                val profile = ModProfile(
                    profileId = "${libraryItem.appId}:profile:${System.currentTimeMillis()}",
                    appId = libraryItem.appId,
                    name = trimmedName,
                    active = false,
                )
                dao.upsertProfile(profile)
                dao.getProfileInstallStates(libraryItem.appId, active.profileId).forEach { state ->
                    dao.upsertProfileInstallState(
                        state.copy(
                            profileId = profile.profileId,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                dao.activateProfile(libraryItem.appId, profile.profileId)
                pendingProfileNameEdit = null
                SnackbarManager.show(context.getString(R.string.nexus_profile_created, trimmedName))
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_profile_create_failed))
            }
        }
    }

    fun renameProfile(profile: ModProfile, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            SnackbarManager.show(context.getString(R.string.nexus_enter_profile_name))
            return
        }
        scope.launch {
            try {
                dao.renameProfile(profile.profileId, trimmedName)
                pendingProfileNameEdit = null
                SnackbarManager.show(context.getString(R.string.nexus_profile_renamed))
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_profile_rename_failed))
            }
        }
    }

    fun deleteProfile(profile: ModProfile) {
        scope.launch {
            val currentProfiles = dao.getProfilesForApp(libraryItem.appId)
            if (currentProfiles.size <= 1) {
                SnackbarManager.show(context.getString(R.string.nexus_profile_required))
                pendingProfileDelete = null
                return@launch
            }
            val replacement = currentProfiles.firstOrNull { it.profileId != profile.profileId }
            dao.deleteProfile(profile.profileId)
            if (profile.active && replacement != null) {
                dao.activateProfile(libraryItem.appId, replacement.profileId)
            }
            pendingProfileDelete = null
            SnackbarManager.show(context.getString(R.string.nexus_profile_deleted, profile.name))
        }
    }

    fun activateProfile(profile: ModProfile) {
        scope.launch {
            dao.activateProfile(libraryItem.appId, profile.profileId)
            ModProfileManager.ensureStatesForInstalls(
                dao = dao,
                profile = profile.copy(active = true),
                installs = installs.filter { it.canPlaceFiles() },
            )
            SnackbarManager.show(context.getString(R.string.nexus_profile_switched_apply))
        }
    }

    fun setProfileInstallEnabled(install: ModInstall, enabled: Boolean) {
        scope.launch {
            val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
            val state = ModProfileManager.ensureStateForInstall(dao, profile, install.installId, enabled = enabled)
            dao.upsertProfileInstallState(state.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
            if (!enabled) {
                val skipped = NexusModManager.disableInstall(
                    context = context,
                    install = install,
                    restoreBackups = true,
                    gameRootDir = gameRootDir,
                    winePrefix = winePrefix,
                )
                SnackbarManager.show(
                    if (skipped.isEmpty()) {
                        context.getString(R.string.nexus_disabled_in_named_profile, profile.name)
                    } else {
                        context.getString(R.string.nexus_disabled_in_profile_with_skipped, profile.name, skipped.size)
                    },
                )
            } else {
                if (install.status == ModInstallStatus.DISABLED.name) {
                    dao.updateInstallEnabled(install.installId, true, ModInstallStatus.READY.name)
                }
                SnackbarManager.show(context.getString(R.string.nexus_enabled_in_named_profile, profile.name))
            }
        }
    }

    fun moveInstallPriority(installId: String, direction: Int) {
        scope.launch {
            val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
            val states = ModProfileManager.ensureStatesForInstalls(
                dao = dao,
                profile = profile,
                installs = installs.filter { it.canPlaceFiles() },
            )
                .sortedWith(compareByDescending<ModProfileInstallState> { it.priority }.thenBy { it.installId })
            val index = states.indexOfFirst { it.installId == installId }
            val otherIndex = index + direction
            if (index < 0 || otherIndex !in states.indices) return@launch
            val current = states[index]
            val other = states[otherIndex]
            dao.upsertProfileInstallState(current.copy(priority = other.priority, updatedAt = System.currentTimeMillis()))
            dao.upsertProfileInstallState(other.copy(priority = current.priority, updatedAt = System.currentTimeMillis()))
        }
    }

    fun makeInstallHighestPriority(installId: String) {
        scope.launch {
            val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
            val states = ModProfileManager.ensureStatesForInstalls(
                dao = dao,
                profile = profile,
                installs = installs.filter { it.canPlaceFiles() },
            )
            val current = states.firstOrNull { it.installId == installId } ?: return@launch
            val topPriority = states.maxOfOrNull { it.priority } ?: current.priority
            dao.upsertProfileInstallState(
                current.copy(
                    priority = if (current.priority >= topPriority) current.priority else topPriority + 1,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun writePluginState(updated: List<BethesdaPlugin>) {
        val game = bethesdaGame ?: return
        val pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, game) ?: return
        scope.launch {
            val issues = withContext(Dispatchers.IO) {
                BethesdaPluginManager.updateManagedPluginsTxt(
                    file = pluginsFile,
                    managedPlugins = updated,
                    game = game,
                    gameRootDir = gameRootDir,
                )
                BethesdaPluginManager.diagnosePluginMasters(
                    managedPlugins = updated,
                    game = game,
                    gameRootDir = gameRootDir,
                    pluginsFile = pluginsFile,
                )
            }
            bethesdaPlugins = updated
            bethesdaPluginIssues = issues
            bethesdaPluginAssetIssues = BethesdaPluginManager.diagnosePluginAssets(updated)
            SnackbarManager.show(
                if (issues.hasBlockingPluginIssues()) {
                    context.getString(R.string.nexus_plugin_list_saved_with_warnings)
                } else {
                    context.getString(R.string.nexus_plugin_list_saved)
                },
            )
        }
    }

    fun movePluginMastersBefore(plugin: BethesdaPlugin, masterNames: List<String>) {
        val masterKeys = masterNames.map { it.trim().removePrefix("*").lowercase() }.toSet()
        val moving = bethesdaPlugins.filter { it.fileName.lowercase() in masterKeys }
        if (moving.isEmpty()) {
            SnackbarManager.show(context.getString(R.string.nexus_required_plugin_not_managed))
            return
        }
        val reordered = bethesdaPlugins.toMutableList()
        reordered.removeAll(moving.toSet())
        val targetIndex = reordered.indexOfFirst { it.fileName == plugin.fileName }
        if (targetIndex < 0) return
        reordered.addAll(
            targetIndex,
            moving.sortedBy { masterNames.indexOfFirst { master -> master.equals(it.fileName, ignoreCase = true) }.takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
        )
        writePluginState(reordered)
    }

    fun applyProfileOrder(allowOverwrite: Boolean): kotlinx.coroutines.Job? {
        if (profileApplyInProgress) {
            SnackbarManager.show(context.getString(R.string.nexus_mod_order_already_applying))
            return null
        }
        return scope.launch {
            profileApplyInProgress = true
            diagnosticsPaused = true
            try {
                SnackbarManager.show(context.getString(R.string.nexus_applying_order_may_take_time))
                loadingMessage = context.getString(R.string.nexus_checking_mod_order)
                var effectiveAllowOverwrite = allowOverwrite
                val collectionPluginOrder = pendingCollectionSelection?.collection?.manifestInfo?.rules?.pluginLoadOrder.orEmpty()
                val conflictInstallIds = conflictReports
                    .flatMap { report -> report.participants.map { it.installId } }
                    .toSet()
                val plan = withContext(Dispatchers.IO) {
                    val profile = ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                    val currentInstalls = dao.getInstallsForApp(libraryItem.appId)
                        .filter { it.canPlaceFiles() }
                    val stateByInstallId = ModProfileManager.ensureStatesForInstalls(
                        dao = dao,
                        profile = profile,
                        installs = currentInstalls,
                    )
                        .associateBy { it.installId }
                    val disabledInstalls = currentInstalls
                        .filter { stateByInstallId[it.installId]?.enabled != true }
                    val orderedInstalls = currentInstalls
                        .filter { stateByInstallId[it.installId]?.enabled == true }
                        .sortedWith(compareBy<ModInstall> { stateByInstallId[it.installId]?.priority ?: 0 }.thenBy { it.installId })
                    val recipesToPersistByInstallId = mutableMapOf<String, List<ModPlacementRecipe>>()
                    val recipesByInstallId = orderedInstalls.associate { install ->
                        val savedRecipes = dao.getRecipesForInstall(install.installId)
                        val effectiveRecipes = BethesdaPlacementRecipeExpander.expand(libraryItem.name, install, savedRecipes)
                        if (effectiveRecipes != savedRecipes) {
                            recipesToPersistByInstallId[install.installId] = effectiveRecipes
                        }
                        install.installId to effectiveRecipes
                    }
                    val configuredInstalls = orderedInstalls.filter { recipesByInstallId[it.installId].orEmpty().isNotEmpty() }
                    val unconfiguredInstalls = orderedInstalls - configuredInstalls.toSet()
                    val game = BethesdaPluginManager.detectGame(libraryItem.name)
                    val plugins = game?.let {
                        BethesdaPluginManager.detectPlugins(
                            installs = configuredInstalls,
                            recipesByInstallId = recipesByInstallId,
                            prioritiesByInstallId = stateByInstallId.mapValues { state -> state.value.priority },
                            gameRootDir = gameRootDir,
                            winePrefix = winePrefix,
                            pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, it),
                            defaultEnabled = true,
                        )
                    }.orEmpty()
                        .let { applyCollectionPluginOrder(it, collectionPluginOrder) }
                    val pluginIssues = game?.let {
                        BethesdaPluginManager.diagnosePluginMasters(
                            managedPlugins = plugins,
                            game = it,
                            gameRootDir = gameRootDir,
                            pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, it),
                        )
                    }.orEmpty()
                    val pluginAssetIssues = if (game != null) BethesdaPluginManager.diagnosePluginAssets(plugins) else emptyList()
                    val assetRepairInstallIds = pluginAssetIssues.mapNotNull { it.plugin.installId }.toSet()
                    val missingTargetRepairInstallIds = configuredInstalls
                        .filter { install ->
                            NexusModManager.hasMissingAppliedTargets(
                                install = install,
                                recipes = recipesByInstallId[install.installId].orEmpty(),
                                gameRootDir = gameRootDir,
                                winePrefix = winePrefix,
                            )
                        }
                        .mapTo(mutableSetOf()) { it.installId }
                    val installsToApply = configuredInstalls.filter { install ->
                        install.status != ModInstallStatus.APPLIED.name ||
                            install.installId in conflictInstallIds ||
                            install.installId in assetRepairInstallIds ||
                            install.installId in missingTargetRepairInstallIds
                    }
                    ProfileOrderPlan(
                        stateByInstallId = stateByInstallId,
                        disabledInstalls = disabledInstalls,
                        configuredInstalls = configuredInstalls,
                        installsToApply = installsToApply,
                        missingTargetRepairInstallIds = missingTargetRepairInstallIds,
                        recipesByInstallId = recipesByInstallId,
                        recipesToPersistByInstallId = recipesToPersistByInstallId,
                        unconfiguredCount = unconfiguredInstalls.size,
                        unconfiguredNames = unconfiguredInstalls.map { it.modName },
                        bethesdaGame = game,
                        plugins = plugins,
                        pluginIssues = pluginIssues,
                        pluginAssetIssues = pluginAssetIssues,
                    )
                }
                bethesdaGame = plan.bethesdaGame
                bethesdaPlugins = plan.plugins
                bethesdaPluginIssues = plan.pluginIssues
                bethesdaPluginAssetIssues = plan.pluginAssetIssues
                if (plan.pluginIssues.hasBlockingPluginIssues()) {
                    SnackbarManager.show(context.getString(R.string.nexus_fix_plugin_warnings_before_apply))
                    return@launch
                }
                var disabledSkipped = 0

                if (!allowOverwrite) {
                    loadingMessage = context.getString(R.string.nexus_checking_file_conflicts)
                    val check = withContext(Dispatchers.IO) {
                        val targetCheckInstalls = plan.installsToApply.filter { it.status != ModInstallStatus.APPLIED.name }
                        val overwriteManifests = targetCheckInstalls.flatMap { install ->
                            dao.getOverwriteManifests(install.installId)
                        }
                        val rawConflicts = targetCheckInstalls.flatMap { install ->
                            ModMaterializer.scanConflicts(
                                install = install,
                                recipes = plan.recipesByInstallId[install.installId].orEmpty(),
                                gameRootDir = gameRootDir,
                                winePrefix = winePrefix,
                            )
                        }
                        ProfileOrderConflictCheck(
                            rawConflicts = rawConflicts,
                            conflicts = ModMaterializer.filterUnapprovedConflicts(rawConflicts, overwriteManifests),
                            hasOverwriteRecipe = plan.recipesByInstallId.values.flatten().any { it.mode == ModPlacementMode.OVERWRITE_COPY.name },
                        )
                    }
                    if (check.conflicts.isNotEmpty() && check.hasOverwriteRecipe) {
                        pendingProfileApply = PendingProfileApply(check.conflicts)
                        return@launch
                    }
                    if (check.conflicts.isNotEmpty()) {
                        SnackbarManager.show(context.getString(R.string.nexus_profile_order_target_files_exist))
                        return@launch
                    }
                    effectiveAllowOverwrite = check.rawConflicts.isNotEmpty()
                }

                val availableBytes = NexusModManager.cacheRoot(context, libraryItem.appId).usableSpace
                if (availableBytes < MIN_APPLY_FREE_BYTES) {
                    SnackbarManager.show(
                        context.getString(R.string.nexus_storage_low_apply_order, StorageUtils.formatBinarySize(MIN_APPLY_FREE_BYTES - availableBytes)),
                    )
                    return@launch
                }

                if (plan.recipesToPersistByInstallId.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        plan.recipesToPersistByInstallId.forEach { (installId, recipes) ->
                            dao.replaceRecipes(installId, recipes)
                        }
                    }
                }

                if (plan.disabledInstalls.isNotEmpty()) {
                    loadingMessage = context.getString(R.string.nexus_applying_mod_order)
                    disabledSkipped = withContext(Dispatchers.IO) {
                        plan.disabledInstalls.sumOf { install ->
                            NexusModManager.disableInstall(
                                context = context,
                                install = install,
                                restoreBackups = true,
                                gameRootDir = gameRootDir,
                                winePrefix = winePrefix,
                            ).size
                        }
                    }
                }

                loadingMessage = context.getString(R.string.nexus_applying_mod_order)
                val result = withContext(Dispatchers.IO) {
                    var errors = 0
                    plan.installsToApply.forEach { install ->
                        val recipes = plan.recipesByInstallId[install.installId].orEmpty()
                        val result = if (
                            !effectiveAllowOverwrite &&
                            install.status == ModInstallStatus.APPLIED.name &&
                            install.installId in plan.missingTargetRepairInstallIds
                        ) {
                            NexusModManager.repairMissingAppliedTargets(
                                install = install,
                                recipes = recipes,
                                gameRootDir = gameRootDir,
                                winePrefix = winePrefix,
                            )
                        } else {
                            NexusModManager.applyInstall(
                                context = context,
                                install = install,
                                recipes = recipes,
                                gameRootDir = gameRootDir,
                                winePrefix = winePrefix,
                                allowOverwrite = effectiveAllowOverwrite,
                                saveLastPlacement = false,
                                preserveStatusOnError = true,
                            )
                        }
                        errors += result.errors.size
                    }
                    val game = BethesdaPluginManager.detectGame(libraryItem.name)
                    if (errors == 0 && game != null) {
                        val pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, game)
                        if (pluginsFile != null) {
                            val appliedInstalls = plan.configuredInstalls.map { it.copy(status = ModInstallStatus.APPLIED.name) }
                            val detectedPlugins = applyCollectionPluginOrder(
                                BethesdaPluginManager.detectPlugins(
                                    installs = appliedInstalls,
                                    recipesByInstallId = plan.recipesByInstallId,
                                    prioritiesByInstallId = plan.stateByInstallId.mapValues { it.value.priority },
                                    gameRootDir = gameRootDir,
                                    winePrefix = winePrefix,
                                    pluginsFile = pluginsFile,
                                    defaultEnabled = true,
                                ),
                                collectionPluginOrder,
                            )
                            BethesdaPluginManager.updateManagedPluginsTxt(
                                file = pluginsFile,
                                managedPlugins = detectedPlugins,
                                game = game,
                                gameRootDir = gameRootDir,
                            )
                            val issues = BethesdaPluginManager.diagnosePluginMasters(
                                managedPlugins = detectedPlugins,
                                game = game,
                                gameRootDir = gameRootDir,
                                pluginsFile = pluginsFile,
                            )
                            ProfileOrderApplyResult(
                                errors = errors,
                                bethesdaGame = game,
                                plugins = detectedPlugins,
                                pluginIssues = issues,
                                pluginAssetIssues = BethesdaPluginManager.diagnosePluginAssets(detectedPlugins),
                            )
                        } else {
                            ProfileOrderApplyResult(errors, null, emptyList(), emptyList(), emptyList())
                        }
                    } else {
                        ProfileOrderApplyResult(errors, null, emptyList(), emptyList(), emptyList())
                    }
                }
                result.bethesdaGame?.let {
                    bethesdaGame = it
                    bethesdaPlugins = result.plugins
                    bethesdaPluginIssues = result.pluginIssues
                    bethesdaPluginAssetIssues = result.pluginAssetIssues
                }
                val suffix = if (disabledSkipped > 0) {
                    context.getString(R.string.nexus_changed_disabled_files_left_in_place, disabledSkipped)
                } else {
                    ""
                }
                val skippedSuffix = if (plan.unconfiguredCount > 0) {
                    val visibleNames = plan.unconfiguredNames.take(3).joinToString(", ")
                    val remaining = if (plan.unconfiguredCount > 3) ", ..." else ""
                    "; ${context.getString(R.string.nexus_apply_needs_placement, plan.unconfiguredCount, visibleNames, remaining)}"
                } else {
                    ""
                }
                if (healthReport != null) {
                    loadingMessage = context.getString(R.string.nexus_refreshing_install_health)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            NexusModManager.checkInstallHealthForApp(
                                context = context,
                                appId = libraryItem.appId,
                                gameRootDir = gameRootDir,
                                winePrefix = winePrefix,
                            )
                        }
                    }.onSuccess { refreshedHealth ->
                        healthReport = refreshedHealth
                    }
                }
                SnackbarManager.show(if (result.errors == 0) context.getString(R.string.nexus_mod_order_applied, suffix, skippedSuffix) else context.getString(R.string.nexus_mod_order_applied_with_errors, result.errors, suffix, skippedSuffix))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_apply_mod_order_failed))
            } finally {
                profileApplyInProgress = false
                loadingMessage = null
                diagnosticsPaused = false
            }
        }
    }

    fun refreshEntries(install: ModInstall?) {
        if (install == null || !install.canPlaceFiles()) {
            archiveEntries = emptyList()
            selectedFomodInstaller = null
            return
        }
        scope.launch {
            val (entries, fomodInstaller) = withContext(Dispatchers.IO) {
                val extractedRoot = File(install.extractedPath)
                val parsedFomod = FomodInstallerDetector.moduleConfigFile(extractedRoot)
                    ?.let { runCatching { FomodParser.parse(it, extractedRoot) }.getOrNull() }
                NexusModManager.archiveEntries(install) to parsedFomod
            }
            archiveEntries = entries
            selectedFomodInstaller = fomodInstaller
            if (placementChoice == PlacementChoice.AUTOMATIC && install.canPlaceFiles()) {
                recipeDrafts.clear()
                recipeDrafts += automaticDraftsFor(libraryItem.name, entries, defaultDraft)
            }
        }
    }

    fun loadRecipes(install: ModInstall?) {
        recipeDrafts.clear()
        if (install == null || !install.canPlaceFiles()) {
            recipeDrafts += defaultDraft
            return
        }
        scope.launch {
            val recipes = withContext(Dispatchers.IO) { dao.getRecipesForInstall(install.installId) }
            recipeDrafts.clear()
            if (recipes.isEmpty()) {
                placementChoice = PlacementChoice.AUTOMATIC
                recipeDrafts += automaticDraftsFor(libraryItem.name, archiveEntries, defaultDraft)
            } else {
                placementChoice = PlacementChoice.CUSTOM
                recipeDrafts += recipes.map {
                    RecipeDraft(
                        sourceSubpath = it.sourceSubpath,
                        targetRoot = it.targetRoot,
                        targetRelativePath = it.targetRelativePath,
                        mode = it.mode,
                        stripPrefixSegments = it.stripPrefixSegments,
                        includeSourceDirectory = it.includeSourceDirectory,
                    )
                }
            }
        }
    }

    fun importFile(reference: NexusModReference, modInfo: NexusModInfo, file: NexusModFile) {
        PrefManager.nexusApiKey = apiKey.trim()
        scope.launch {
            try {
                val cleanup = NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
                if (cleanup.reclaimedBytes > 0L) {
                    SnackbarManager.show(context.getString(R.string.nexus_cleaned_old_temp_files, StorageUtils.formatBinarySize(cleanup.reclaimedBytes)))
                }
                val storage = NexusModManager.checkImportStorage(context, libraryItem.appId, listOf(file))
                if (!storage.canImport) {
                    SnackbarManager.show(
                        context.getString(
                            R.string.nexus_not_enough_storage_import,
                            StorageUtils.formatBinarySize(storage.estimatedRequiredBytes),
                            StorageUtils.formatBinarySize(storage.availableBytes),
                        ),
                    )
                    return@launch
                }
                loadingMessage = context.getString(R.string.nexus_starting_named_file, file.name.ifBlank { file.fileName })
                progress = 0f
                importProgress = null
                val install = NexusModImportService.enqueueImport(
                    context = context,
                    appId = libraryItem.appId,
                    reference = reference,
                    modInfo = modInfo,
                    file = file,
                    displayName = modInfo.name,
                ).await()
                selectedInstall = install
                pendingFileSelection = null
                selectedTab = ManageModsTab.PLACEMENT
                placementChoice = PlacementChoice.AUTOMATIC
                loadRecipes(install)
                refreshEntries(install)
                SnackbarManager.show(context.getString(R.string.nexus_nexus_mod_imported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SnackbarManager.show(NexusImportState.userMessage(e))
            } finally {
                loadingMessage = null
                importProgress = null
            }
        }
    }

    fun retryInstall(install: ModInstall) {
        importFile(
            reference = NexusModReference(
                gameDomain = install.nexusGameDomain,
                modId = install.nexusModId,
                fileId = install.nexusFileId,
            ),
            modInfo = NexusModInfo(
                modId = install.nexusModId,
                name = install.modName,
                summary = install.metadataSummary(),
                version = install.version,
            ),
            file = NexusModFile(
                fileId = install.nexusFileId,
                name = install.fileName,
                version = install.version,
                fileName = install.fileName,
                sizeBytes = install.sizeBytes,
                uploadedTimestamp = 0L,
            ),
        )
    }

    fun localizedImportStatus(status: String): String = when (status) {
        "Starting" -> context.getString(R.string.nexus_queue_starting)
        "Downloading" -> context.getString(R.string.nexus_import_status_downloading)
        "Unpacking" -> context.getString(R.string.nexus_import_status_unpacking)
        else -> status
    }

    suspend fun resolveCollectionMod(collectionFile: NexusCollectionFile): PendingCollectionMod {
        if (collectionFile.modId <= 0L || collectionFile.fileId <= 0L) {
            return PendingCollectionMod(
                collectionFile = collectionFile,
                modInfo = null,
                file = null,
                error = context.getString(R.string.nexus_collection_manual_external_entry),
            )
        }
        return try {
            val modInfo = apiClient.getModInfo(collectionFile.gameDomain, collectionFile.modId)
            val files = apiClient.getModFiles(collectionFile.gameDomain, collectionFile.modId)
            val file = files.firstOrNull { it.fileId == collectionFile.fileId }
                ?: collectionFile.toFallbackNexusFile()
            PendingCollectionMod(collectionFile, modInfo, file)
        } catch (e: Exception) {
            val fallbackFile = collectionFile.toFallbackNexusFile()
            if (fallbackFile != null) {
                PendingCollectionMod(
                    collectionFile = collectionFile,
                    modInfo = NexusModInfo(
                        modId = collectionFile.modId,
                        name = collectionFile.modName.ifBlank { context.getString(R.string.nexus_collection_mod_fallback_name, collectionFile.modId) },
                        summary = "",
                        version = collectionFile.version,
                    ),
                    file = fallbackFile,
                    error = null,
                )
            } else {
                PendingCollectionMod(
                    collectionFile = collectionFile,
                    modInfo = null,
                    file = null,
                    error = e.message ?: context.getString(R.string.nexus_collection_mod_resolve_failed),
                )
            }
        }
    }

    fun resolveCollection(reference: app.gamenative.mods.NexusCollectionReference) {
        PrefManager.nexusApiKey = apiKey.trim()
        scope.launch {
            try {
                loadingMessage = context.getString(R.string.nexus_resolving_nexus_collection)
                progress = 0f
                importProgress = null
                val collection = apiClient.getCollectionRevision(reference)
                if (collection.files.isEmpty()) {
                    SnackbarManager.show(context.getString(R.string.nexus_collection_no_downloadable_mods))
                    return@launch
                }
                val resolvedMods = mutableListOf<PendingCollectionMod>()
                collection.files.forEachIndexed { index, file ->
                    loadingMessage = context.getString(R.string.nexus_resolving_collection_item, index + 1, collection.files.size)
                    resolvedMods += resolveCollectionMod(file)
                }
                pendingFileSelection = null
                pendingCollectionSelection = PendingCollectionSelection(collection, resolvedMods)
                selectedTab = ManageModsTab.IMPORT
                SnackbarManager.show(context.getString(R.string.nexus_collection_resolve_ready, resolvedMods.count { it.canImport }))
            } catch (e: NexusApiException) {
                SnackbarManager.show(NexusImportState.userMessage(e, context.getString(R.string.nexus_resolve_collection_failed)))
            } catch (e: Exception) {
                SnackbarManager.show(NexusImportState.userMessage(e, context.getString(R.string.nexus_resolve_collection_failed)))
            } finally {
                if (loadingMessage?.startsWith(context.getString(R.string.nexus_resolving_prefix)) == true) loadingMessage = null
            }
        }
    }

    fun importCollection(pending: PendingCollectionSelection, selectedKeys: Set<String>) {
        val collectionMods = pending.mods.filter { it.canImport && it.collectionKey() in selectedKeys }
        if (collectionMods.isEmpty()) {
            SnackbarManager.show(context.getString(R.string.nexus_no_selected_collection_mods_ready))
            return
        }
        PrefManager.nexusApiKey = apiKey.trim()
        scope.launch {
            var imported = 0
            var reused = 0
            var failed = 0
            fun updateQueue(
                pendingMod: PendingCollectionMod,
                status: CollectionQueueStatus,
                progress: Float? = null,
                message: String = "",
                error: String = "",
                startedAt: Long? = null,
            ) {
                val key = pendingMod.collectionKey()
                val current = collectionQueue[key]
                collectionQueue[key] = (current ?: pendingMod.toQueueItem(
                    status = status,
                    fallbackName = context.getString(R.string.nexus_collection_mod_fallback_name, pendingMod.collectionFile.modId),
                )).copy(
                    status = status,
                    progress = progress ?: current?.progress ?: 0f,
                    message = message,
                    error = error,
                    startedAt = startedAt ?: current?.startedAt ?: 0L,
                )
            }
            suspend fun existingReusableInstall(pendingMod: PendingCollectionMod): ModInstall? {
                return withContext(Dispatchers.IO) {
                    val file = pendingMod.file ?: return@withContext null
                    val installId = NexusModManager.installIdFor(
                        appId = libraryItem.appId,
                        gameDomain = pendingMod.collectionFile.gameDomain,
                        modId = pendingMod.collectionFile.modId,
                        fileId = pendingMod.collectionFile.fileId.takeIf { it > 0L } ?: file.fileId,
                    )
                    dao.getInstall(installId)
                        ?.takeIf {
                            NexusCollectionReusePolicy.matchesExactFile(it, pendingMod.collectionFile, file) &&
                                it.canPlaceFiles() &&
                                File(it.extractedPath).isDirectory
                        }
                }
            }
            suspend fun configureCollectionInstall(
                pendingMod: PendingCollectionMod,
                modInfo: NexusModInfo,
                file: NexusModFile,
                reference: NexusModReference,
                install: ModInstall,
                profile: ModProfile,
                suggestedPriority: Int,
                reusedExisting: Boolean,
            ): Pair<String, String> {
                val entries = withContext(Dispatchers.IO) { NexusModManager.archiveEntries(install) }
                val fomodInstaller = withContext(Dispatchers.IO) {
                    val extractedRoot = File(install.extractedPath)
                    FomodInstallerDetector.moduleConfigFile(extractedRoot)
                        ?.let { runCatching { FomodParser.parse(it, extractedRoot) }.getOrNull() }
                }
                val bethesdaGameForMod = BethesdaPluginManager.detectGame(libraryItem.name)
                val fomodAutoSelection = fomodInstaller?.let { installer ->
                    bethesdaGameForMod?.let { game ->
                        FomodAutoSelector.selectDeterministic(
                            installId = install.installId,
                            installer = installer,
                            targetRelativePath = game.dataDirName,
                        )
                    }
                }
                val assessment = ModArchiveInstallAssessor.assess(
                    gameName = libraryItem.name,
                    modName = modInfo.name,
                    fileName = file.fileName.ifBlank { file.name },
                    entries = entries,
                    gameDomain = reference.gameDomain,
                    modId = reference.modId,
                    fileId = reference.fileId ?: file.fileId,
                )
                val hasFomodInstaller = archiveContainsFomodInstaller(entries)
                val drafts = if (hasFomodInstaller || !assessment.allowsAutomaticPlacement) {
                    emptyList()
                } else {
                    automaticDraftsFor(libraryItem.name, entries, defaultDraft)
                }
                val existingRecipes = withContext(Dispatchers.IO) { dao.getRecipesForInstall(install.installId) }
                if (existingRecipes.isEmpty()) {
                    val recipes = fomodAutoSelection?.recipes ?: drafts.map { it.toRecipe(install.installId) }
                    withContext(Dispatchers.IO) {
                        dao.replaceRecipes(
                            install.installId,
                            BethesdaPlacementRecipeExpander.expand(
                                gameName = libraryItem.name,
                                install = install,
                                recipes = recipes,
                            ),
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    val targetProfile = dao.getActiveProfileForApp(libraryItem.appId) ?: profile
                    val state = ModProfileManager.ensureStateForInstall(
                        dao = dao,
                        profile = targetProfile,
                        installId = install.installId,
                        enabled = true,
                        priority = suggestedPriority,
                    )
                    dao.upsertProfileInstallState(
                        state.copy(
                            enabled = true,
                            priority = suggestedPriority,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                if (selectedInstall == null) {
                    selectedInstall = install
                    archiveEntries = entries
                    selectedFomodInstaller = fomodInstaller
                    placementChoice = PlacementChoice.AUTOMATIC
                    recipeDrafts.clear()
                    recipeDrafts += when {
                        existingRecipes.isNotEmpty() -> existingRecipes.map { it.toDraft() }
                        else -> (fomodAutoSelection?.recipes?.map { it.toDraft() } ?: drafts)
                            .ifEmpty { listOf(defaultDraft) }
                    }
                }
                val queueReasons = buildList {
                    if (reusedExisting && install.nexusFileId != (reference.fileId ?: file.fileId)) {
                        add(context.getString(R.string.nexus_collection_different_file_reused))
                    }
                    if (existingRecipes.isNotEmpty() && reusedExisting) add(context.getString(R.string.nexus_collection_existing_placement_kept))
                    addAll(assessment.reasons)
                    addAll(fomodAutoSelection?.reasons.orEmpty())
                }.distinct()
                val queueMessage = when {
                    reusedExisting && install.nexusFileId != (reference.fileId ?: file.fileId) -> context.getString(R.string.nexus_collection_already_imported_using_existing_file)
                    reusedExisting && existingRecipes.isNotEmpty() -> context.getString(R.string.nexus_collection_already_imported_kept_placement)
                    reusedExisting -> context.getString(R.string.nexus_collection_already_imported)
                    fomodAutoSelection != null -> context.getString(R.string.nexus_collection_imported_fomod_auto_selected)
                    else -> assessment.queueMessage
                }
                return queueMessage to queueReasons.joinToString("; ")
            }
            try {
                val cleanup = NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
                if (cleanup.reclaimedBytes > 0L) {
                    SnackbarManager.show(context.getString(R.string.nexus_cleaned_old_temp_files, StorageUtils.formatBinarySize(cleanup.reclaimedBytes)))
                }
                collectionPaused = false
                collectionCancelRequested = false
                collectionMods.forEach { pendingMod ->
                    updateQueue(pendingMod, CollectionQueueStatus.QUEUED)
                }
                val reusableInstalls = mutableMapOf<String, ModInstall>()
                val modsNeedingDownload = mutableListOf<PendingCollectionMod>()
                collectionMods.forEach { pendingMod ->
                    val existing = existingReusableInstall(pendingMod)
                    if (existing != null) {
                        reusableInstalls[pendingMod.collectionKey()] = existing
                    } else {
                        modsNeedingDownload += pendingMod
                    }
                }
                val storage = NexusModManager.checkImportStorage(
                    context = context,
                    appId = libraryItem.appId,
                    files = modsNeedingDownload.mapNotNull { it.file },
                    sequential = true,
                )
                if (!storage.canImport) {
                    SnackbarManager.show(
                        context.getString(
                            R.string.nexus_not_enough_storage_import,
                            StorageUtils.formatBinarySize(storage.estimatedRequiredBytes),
                            StorageUtils.formatBinarySize(storage.availableBytes),
                        ),
                    )
                    return@launch
                }
                val suggestedPriorities = NexusCollectionPrioritySuggester.priorities(collectionMods.map { it.collectionFile })
                val profile = withContext(Dispatchers.IO) {
                    ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                }
                val collectionPriorityBase = withContext(Dispatchers.IO) {
                    (dao.getProfileInstallStates(libraryItem.appId, profile.profileId).maxOfOrNull { it.priority } ?: -1) + 1
                }
                for ((index, pendingMod) in collectionMods.withIndex()) {
                    while (collectionPaused && !collectionCancelRequested) {
                        updateQueue(pendingMod, CollectionQueueStatus.QUEUED, message = context.getString(R.string.nexus_queue_paused))
                        delay(300L)
                    }
                    if (collectionCancelRequested) {
                        updateQueue(pendingMod, CollectionQueueStatus.CANCELED, message = context.getString(R.string.nexus_queue_canceled))
                        break
                    }
                    val modInfo = pendingMod.modInfo ?: continue
                    val file = pendingMod.file ?: continue
                    val reference = NexusModReference(
                        gameDomain = pendingMod.collectionFile.gameDomain,
                        modId = pendingMod.collectionFile.modId,
                        fileId = pendingMod.collectionFile.fileId,
                    )
                    try {
                        val installId = NexusModManager.installIdFor(
                            appId = libraryItem.appId,
                            gameDomain = reference.gameDomain,
                            modId = reference.modId,
                            fileId = reference.fileId ?: file.fileId,
                        )
                        activeCollectionInstallId = installId
                        loadingMessage = context.getString(R.string.nexus_preparing_collection_item, index + 1, collectionMods.size, modInfo.name)
                        progress = 0f
                        importProgress = null
                        val suggestedPriority = collectionPriorityBase + (suggestedPriorities[pendingMod.collectionKey()] ?: index)
                        val reusableInstall = reusableInstalls[pendingMod.collectionKey()] ?: existingReusableInstall(pendingMod)
                        if (reusableInstall != null) {
                            if (reusableInstall.installId != installId) {
                                withContext(Dispatchers.IO) { dao.getInstall(installId) }
                                    ?.takeIf { it.status == ModInstallStatus.ERROR.name }
                                    ?.let { duplicate ->
                                        withContext(Dispatchers.IO) {
                                            dao.deleteOverwriteManifests(duplicate.installId)
                                            dao.deleteInstall(duplicate.installId)
                                            File(duplicate.archivePath).takeIf { it.path.isNotBlank() }?.delete()
                                            File(duplicate.extractedPath).deleteRecursively()
                                        }
                                    }
                            }
                            updateQueue(
                                pendingMod,
                                status = CollectionQueueStatus.IMPORTING,
                                progress = 1f,
                                message = context.getString(R.string.nexus_collection_already_imported),
                                startedAt = System.currentTimeMillis(),
                            )
                            val (queueMessage, queueError) = configureCollectionInstall(
                                pendingMod = pendingMod,
                                modInfo = modInfo,
                                file = file,
                                reference = reference,
                                install = reusableInstall,
                                profile = profile,
                                suggestedPriority = suggestedPriority,
                                reusedExisting = true,
                            )
                            reused++
                            updateQueue(
                                pendingMod,
                                CollectionQueueStatus.IMPORTED,
                                progress = 1f,
                                message = queueMessage,
                                error = queueError,
                            )
                            continue
                        }
                        loadingMessage = context.getString(R.string.nexus_starting_collection_item, index + 1, collectionMods.size, modInfo.name)
                        updateQueue(
                            pendingMod,
                            status = CollectionQueueStatus.IMPORTING,
                            message = context.getString(R.string.nexus_queue_starting),
                            startedAt = System.currentTimeMillis(),
                        )
                        val install = NexusModImportService.enqueueImport(
                            context = context,
                            appId = libraryItem.appId,
                            reference = reference,
                            modInfo = modInfo,
                            file = file,
                            displayName = context.getString(R.string.nexus_collection_display_name, index + 1, collectionMods.size, modInfo.name),
                            onProgress = { detail ->
                                scope.launch(Dispatchers.Main) {
                                    updateQueue(
                                        pendingMod,
                                        CollectionQueueStatus.IMPORTING,
                                        progress = detail.progress,
                                        message = localizedImportStatus(detail.status),
                                    )
                                }
                            },
                        ).await()
                        imported++
                        val (queueMessage, queueError) = configureCollectionInstall(
                            pendingMod = pendingMod,
                            modInfo = modInfo,
                            file = file,
                            reference = reference,
                            install = install,
                            profile = profile,
                            suggestedPriority = suggestedPriority,
                            reusedExisting = false,
                        )
                        updateQueue(
                            pendingMod,
                            CollectionQueueStatus.IMPORTED,
                            progress = 1f,
                            message = queueMessage,
                            error = queueError,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        val canceled = e.message?.contains("canceled", ignoreCase = true) == true || collectionCancelRequested
                        updateQueue(
                            pendingMod,
                            status = if (canceled) CollectionQueueStatus.CANCELED else CollectionQueueStatus.FAILED,
                            message = if (canceled) context.getString(R.string.nexus_queue_canceled) else context.getString(R.string.nexus_queue_failed),
                            error = NexusImportState.userMessage(e),
                        )
                    } finally {
                        activeCollectionInstallId = null
                    }
                }
                val prepared = imported + reused
                val suffix = buildString {
                    if (reused > 0) append(context.getString(R.string.nexus_collection_summary_already_imported, reused))
                    if (failed > 0) append(context.getString(R.string.nexus_collection_summary_failed, failed))
                }
                if (collectionCancelRequested) {
                    SnackbarManager.show(context.getString(R.string.nexus_collection_import_canceled, prepared, suffix))
                } else {
                    SnackbarManager.show(context.getString(R.string.nexus_collection_prepared_applying, prepared, suffix))
                    if (prepared > 0) {
                        applyProfileOrder(allowOverwrite = false)?.join()
                    }
                }
            } finally {
                activeCollectionInstallId = null
                loadingMessage = null
                importProgress = null
            }
        }
    }

    fun resolveUrlAndImport() {
        val collectionReference = NexusCollectionUrlParser.parse(nexusUrl)
        if (collectionReference != null) {
            resolveCollection(collectionReference)
            return
        }
        val reference = NexusUrlParser.parse(nexusUrl)
        if (reference == null) {
            SnackbarManager.show(context.getString(R.string.nexus_enter_valid_nexus_url))
            return
        }
        PrefManager.nexusApiKey = apiKey.trim()
        scope.launch {
            try {
                loadingMessage = context.getString(R.string.nexus_resolving_nexus_mod)
                progress = 0f
                importProgress = null
                val modInfo = apiClient.getModInfo(reference.gameDomain, reference.modId)
                val files = apiClient.getModFiles(reference.gameDomain, reference.modId)
                if (files.isEmpty()) {
                    SnackbarManager.show(context.getString(R.string.nexus_no_downloadable_files_mod))
                    return@launch
                }
                val file = reference.fileId?.let { fileId -> files.firstOrNull { it.fileId == fileId } }
                if (file != null) {
                    importFile(reference, modInfo, file)
                } else {
                    pendingCollectionSelection = null
                    pendingFileSelection = PendingFileSelection(reference, modInfo, files)
                    selectedTab = ManageModsTab.IMPORT
                }
            } catch (e: NexusApiException) {
                SnackbarManager.show(NexusImportState.userMessage(e, context.getString(R.string.nexus_resolve_url_failed)))
            } catch (e: Exception) {
                SnackbarManager.show(NexusImportState.userMessage(e, context.getString(R.string.nexus_resolve_url_failed)))
            } finally {
                if (loadingMessage == context.getString(R.string.nexus_resolving_nexus_mod)) loadingMessage = null
            }
        }
    }

    fun buildRecipes(install: ModInstall): List<ModPlacementRecipe> =
        BethesdaPlacementRecipeExpander.expand(
            gameName = libraryItem.name,
            install = install,
            recipes = recipeDrafts.map { draft -> draft.toRecipe(install.installId) },
        )

    suspend fun applyRecipesInternal(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        allowOverwrite: Boolean,
    ) {
        loadingMessage = context.getString(R.string.nexus_applying_mod_files)
        val (cleanupSkipped, result) = withContext(Dispatchers.IO) {
            val oldRecipes = dao.getRecipesForInstall(install.installId)
            val skipped = NexusModManager.cleanupBeforeRecipeReplacement(
                context = context,
                install = install,
                oldRecipes = oldRecipes,
                newRecipes = recipes,
                gameRootDir = gameRootDir,
                winePrefix = winePrefix,
            )
            dao.replaceRecipes(install.installId, recipes)
            val applied = NexusModManager.applyInstall(
                context = context,
                install = install,
                recipes = recipes,
                gameRootDir = gameRootDir,
                winePrefix = winePrefix,
                allowOverwrite = allowOverwrite,
            )
            if (applied.errors.isEmpty()) {
                val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                val state = ModProfileManager.ensureStateForInstall(dao, profile, install.installId)
                dao.upsertProfileInstallState(state.copy(enabled = true, updatedAt = System.currentTimeMillis()))
            }
            skipped to applied
        }
        val message = if (result.errors.isEmpty()) {
            lastPlacementDrafts = recipes.map { it.toDraft() }
            val cleanupSuffix = if (cleanupSkipped.isNotEmpty()) {
                context.getString(R.string.nexus_old_files_left_in_place_suffix, cleanupSkipped.size)
            } else {
                ""
            }
            context.getString(R.string.nexus_applied_items_backups, result.created, result.backedUp, cleanupSuffix)
        } else {
            context.getString(R.string.nexus_applied_with_errors, result.errors.size)
        }
        placementApplyStatusMessage = message
        SnackbarManager.show(message)
        selectedInstall = install.copy(status = if (result.errors.isEmpty()) ModInstallStatus.APPLIED.name else ModInstallStatus.ERROR.name)
    }

    fun applyRecipes(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        allowOverwrite: Boolean,
    ) {
        if (modApplyInProgress) {
            SnackbarManager.show(context.getString(R.string.nexus_mod_apply_already_running))
            return
        }
        scope.launch {
            modApplyInProgress = true
            try {
                placementApplyStatusMessage = null
                applyRecipesInternal(install, recipes, allowOverwrite)
            } catch (e: Exception) {
                val message = e.message ?: context.getString(R.string.nexus_failed_to_apply_mod)
                placementApplyStatusMessage = message
                SnackbarManager.show(message)
            } finally {
                modApplyInProgress = false
                loadingMessage = null
            }
        }
    }

    fun applyRecipes(install: ModInstall, allowOverwrite: Boolean) =
        applyRecipes(install, buildRecipes(install), allowOverwrite)

    fun saveAndApply() {
        val install = selectedInstall ?: return
        if (!install.canPlaceFiles()) {
            SnackbarManager.show(context.getString(R.string.nexus_mod_not_finished_importing))
            return
        }
        if (!recipeDrafts.all { draft -> roots.any { root -> root.type.name == draft.targetRoot } }) {
            SnackbarManager.show(context.getString(R.string.nexus_choose_destination_inside))
            return
        }
        if (
            selectedFomodInstaller != null &&
            placementChoice != PlacementChoice.CUSTOM &&
            recipeDrafts.any { draft -> ModPlacementSources.decode(draft.sourceSubpath).isEmpty() }
        ) {
            SnackbarManager.show(context.getString(R.string.nexus_fomod_or_custom_required))
            return
        }
        val recipes = buildRecipes(install)
        if (modApplyInProgress) {
            SnackbarManager.show(context.getString(R.string.nexus_mod_apply_already_running))
            return
        }
        scope.launch {
            modApplyInProgress = true
            try {
                placementApplyStatusMessage = null
                loadingMessage = context.getString(R.string.nexus_checking_target_files)
                val (rawConflicts, conflicts) = withContext(Dispatchers.IO) {
                    val raw = ModMaterializer.scanConflicts(
                        install = install,
                        recipes = recipes,
                        gameRootDir = gameRootDir,
                        winePrefix = winePrefix,
                    )
                    raw to ModMaterializer.filterUnapprovedConflicts(
                        conflicts = raw,
                        manifests = dao.getOverwriteManifests(install.installId),
                    )
                }
                val hasOverwriteRecipe = recipes.any { it.mode == ModPlacementMode.OVERWRITE_COPY.name }
                if (conflicts.isNotEmpty() && hasOverwriteRecipe) {
                    pendingApply = PendingApply(install, recipes, conflicts)
                } else if (conflicts.isNotEmpty()) {
                    val message = context.getString(R.string.nexus_target_files_exist_overwrite)
                    placementApplyStatusMessage = message
                    SnackbarManager.show(message)
                } else {
                    applyRecipesInternal(install, recipes, allowOverwrite = rawConflicts.isNotEmpty())
                }
            } catch (e: Exception) {
                val message = e.message ?: context.getString(R.string.nexus_scan_placement_conflicts_failed)
                placementApplyStatusMessage = message
                SnackbarManager.show(message)
            } finally {
                modApplyInProgress = false
                loadingMessage = null
            }
        }
    }

    val issueCount = conflictReports.size + bethesdaPluginIssues.size + bethesdaPluginAssetIssues.size + (healthReport?.issues?.size ?: 0)

    fun selectInstallForPlacement(install: ModInstall) {
        selectedInstall = install
        placementApplyStatusMessage = null
        loadRecipes(install)
        refreshEntries(install)
        selectedTab = ManageModsTab.PLACEMENT
    }

    fun cancelCollectionQueue() {
        collectionCancelRequested = true
        activeCollectionInstallId?.let(ModDownloadRegistry::requestCancel)
        collectionQueue.keys.forEach { key ->
            val current = collectionQueue[key] ?: return@forEach
            if (current.status == CollectionQueueStatus.QUEUED) {
                collectionQueue[key] = current.copy(
                    status = CollectionQueueStatus.CANCELED,
                    message = context.getString(R.string.nexus_queue_canceled),
                )
            }
        }
    }

    @Composable
    fun CollectionSelectionContent(pending: PendingCollectionSelection) {
        val selectedFiles = pending.mods
            .filter { it.canImport && it.collectionKey() in selectedCollectionKeys }
            .mapNotNull { it.file }
        CollectionSelectionSection(
            pending = pending,
            selectedKeys = selectedCollectionKeys,
            queueItems = collectionQueue,
            availableBytes = NexusModManager.cacheRoot(context, libraryItem.appId).usableSpace,
            estimatedRequiredBytes = NexusModManager.estimateSequentialImportScratchBytes(selectedFiles),
            paused = collectionPaused,
            cancelEnabled = activeCollectionInstallId != null || collectionQueue.values.any {
                it.status == CollectionQueueStatus.QUEUED || it.status == CollectionQueueStatus.IMPORTING
            },
            onToggle = { key, selected ->
                selectedCollectionKeys = if (selected) selectedCollectionKeys + key else selectedCollectionKeys - key
            },
            onSelectAll = {
                selectedCollectionKeys = pending.mods.filter { it.canImport }.map { it.collectionKey() }.toSet()
            },
            onClearSelection = { selectedCollectionKeys = emptySet() },
            onImportSelected = { importCollection(pending, selectedCollectionKeys) },
            onRetryFailed = {
                val failedKeys = collectionQueue.values
                    .filter { it.status == CollectionQueueStatus.FAILED }
                    .map { it.key }
                    .toSet()
                selectedCollectionKeys = failedKeys
                importCollection(pending, failedKeys)
            },
            onPauseAll = { collectionPaused = true },
            onResumeAll = { collectionPaused = false },
            onCancelAll = ::cancelCollectionQueue,
        )
    }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val dialogSnackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(dialogSnackbarHostState) {
            SnackbarManager.messages.collect { message ->
                dialogSnackbarHostState.showSnackbar(message)
            }
        }
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.option_manage_mods), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = libraryItem.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.close))
                    }
                }

                HorizontalDivider()

                ManageModsSummaryBar(
                    installs = installs,
                    enabledByInstallId = profileEnabledByInstallId,
                    activeProfile = activeProfile,
                    activeDownload = activeDownload,
                    issueCount = issueCount,
                    diagnosticsLoading = diagnosticsLoading,
                    busyText = loadingMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )

                ManageModsTabs(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                )

                val importScrollState = rememberScrollState()
                val modsScrollState = rememberScrollState()
                val placementScrollState = rememberScrollState()
                val issuesScrollState = rememberScrollState()
                val selectedScrollState = when (selectedTab) {
                    ManageModsTab.IMPORT -> importScrollState
                    ManageModsTab.MODS -> modsScrollState
                    ManageModsTab.PLACEMENT -> placementScrollState
                    ManageModsTab.ISSUES -> issuesScrollState
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(selectedScrollState)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (selectedTab) {
                        ManageModsTab.IMPORT -> {
                            ApiKeySection(
                                apiKey = apiKey,
                                validationState = apiKeyValidation,
                                onApiKeyChange = { apiKey = it },
                                onValidate = {
                                    PrefManager.nexusApiKey = apiKey.trim()
                                    scope.launch {
                                        try {
                                            apiKeyValidation = ApiKeyValidationState(checking = true, message = context.getString(R.string.nexus_validating_api_key))
                                            loadingMessage = context.getString(R.string.nexus_validating_api_key)
                                            val user = apiClient.validateKey()
                                            apiKeyValidation = ApiKeyValidationState(
                                                message = context.getString(R.string.nexus_connected_nexus_user, user.name),
                                                success = true,
                                            )
                                            SnackbarManager.show(context.getString(R.string.nexus_connected_nexus_user, user.name))
                                        } catch (e: Exception) {
                                            apiKeyValidation = ApiKeyValidationState(
                                                message = e.message ?: context.getString(R.string.nexus_api_key_validation_failed),
                                                success = false,
                                            )
                                            SnackbarManager.show(e.message ?: context.getString(R.string.nexus_api_key_validation_failed))
                                        } finally {
                                            loadingMessage = null
                                        }
                                    }
                                },
                            )
                            ImportSection(
                                nexusUrl = nexusUrl,
                                onUrlChange = { nexusUrl = it },
                                onImport = ::resolveUrlAndImport,
                            )
                            pendingFileSelection?.let { pending ->
                                FileSelectionSection(
                                    pending = pending,
                                    onImport = { file -> importFile(pending.reference, pending.modInfo, file) },
                                )
                            }
                            pendingCollectionSelection?.let { pending -> CollectionSelectionContent(pending) }
                        }

                        ManageModsTab.MODS -> {
                            ProfilesSection(
                                profiles = profiles,
                                activeProfile = activeProfile,
                                onActivate = ::activateProfile,
                                onCreate = { pendingProfileNameEdit = PendingProfileNameEdit(null, nextProfileName(profiles, context.getString(R.string.nexus_profile_name_prefix))) },
                                onRename = { profile -> pendingProfileNameEdit = PendingProfileNameEdit(profile, profile.name) },
                                onDelete = { profile -> pendingProfileDelete = profile },
                            )
                            InstalledModsSection(
                                installs = installs,
                                priorityByInstallId = priorityByInstallId,
                                enabledByInstallId = profileEnabledByInstallId,
                                selectedInstall = selectedInstall,
                                placementNeededInstallIds = placementNeededInstallIds,
                                onSelect = ::selectInstallForPlacement,
                                onSetEnabled = ::setProfileInstallEnabled,
                                onDelete = { install ->
                                    scope.launch {
                                        val skipped = NexusModManager.deleteInstall(
                                            context = context,
                                            install = install,
                                            restoreBackups = true,
                                            gameRootDir = gameRootDir,
                                            winePrefix = winePrefix,
                                        )
                                        if (selectedInstall?.installId == install.installId) selectedInstall = null
                                        SnackbarManager.show(
                                            if (skipped.isEmpty()) {
                                                context.getString(R.string.nexus_mod_deleted)
                                            } else {
                                                context.getString(R.string.nexus_mod_deleted_with_skipped, skipped.size)
                                            },
                                        )
                                    }
                                },
                                onRetry = ::retryInstall,
                                onMovePriority = ::moveInstallPriority,
                                onApplyOrder = { applyProfileOrder(allowOverwrite = false) },
                            )
                        }

                        ManageModsTab.PLACEMENT -> {
                            selectedInstall?.let { install ->
                                val presetOptions = placementPresetOptions(libraryItem.name, archiveEntries, defaultDraft)
                                PlacementSection(
                                    install = install,
                                    entries = archiveEntries,
                                    fomodInstaller = selectedFomodInstaller,
                                    roots = roots,
                                    drafts = recipeDrafts,
                                    presetOptions = presetOptions,
                                    placementChoice = placementChoice,
                                    canUseLastPlacement = lastPlacementDrafts.isNotEmpty(),
                                    onPlacementChoiceChange = { choice ->
                                        placementApplyStatusMessage = null
                                        val currentDrafts = recipeDrafts.toList()
                                        placementChoice = choice
                                        recipeDrafts.clear()
                                        recipeDrafts += when (choice) {
                                            PlacementChoice.AUTOMATIC -> automaticDraftsFor(libraryItem.name, archiveEntries, defaultDraft)
                                            PlacementChoice.PRESET -> presetOptions.firstOrNull()?.drafts
                                                ?: automaticDraftsFor(libraryItem.name, archiveEntries, defaultDraft)
                                            PlacementChoice.LAST_USED -> compatibleLastPlacementDrafts(lastPlacementDrafts, archiveEntries, defaultDraft)
                                            PlacementChoice.CUSTOM -> currentDrafts.ifEmpty { automaticDraftsFor(libraryItem.name, archiveEntries, defaultDraft) }
                                        }
                                    },
                                    onUseLastPlacement = {
                                        placementApplyStatusMessage = null
                                        placementChoice = PlacementChoice.LAST_USED
                                        recipeDrafts.clear()
                                        recipeDrafts += compatibleLastPlacementDrafts(lastPlacementDrafts, archiveEntries, defaultDraft)
                                    },
                                    onPresetSelected = { drafts ->
                                        placementApplyStatusMessage = null
                                        placementChoice = PlacementChoice.PRESET
                                        recipeDrafts.clear()
                                        recipeDrafts += drafts
                                    },
                                    onUpdateDraft = { index, draft ->
                                        placementApplyStatusMessage = null
                                        recipeDrafts[index] = draft
                                    },
                                    onAddDraft = {
                                        placementApplyStatusMessage = null
                                        recipeDrafts += defaultDraft
                                    },
                                    onRemoveDraft = { index ->
                                        if (recipeDrafts.size > 1) {
                                            placementApplyStatusMessage = null
                                            recipeDrafts.removeAt(index)
                                        }
                                    },
                                    onFomodRecipes = { drafts, unsupportedCount ->
                                        placementApplyStatusMessage = null
                                        placementChoice = PlacementChoice.CUSTOM
                                        recipeDrafts.clear()
                                        recipeDrafts += drafts
                                        if (unsupportedCount > 0) {
                                            SnackbarManager.show(context.getString(R.string.nexus_fomod_mappings_need_manual_placement, unsupportedCount))
                                        } else {
                                            SnackbarManager.show(context.getString(R.string.nexus_fomod_choices_added))
                                        }
                                    },
                                    applyStatusMessage = placementApplyStatusMessage,
                                    onSaveAndApply = ::saveAndApply,
                                )
                            } ?: EmptyWorkflowSection(stringResource(R.string.nexus_no_mod_selected), stringResource(R.string.nexus_select_mod_from_mods_tab))
                        }

                        ManageModsTab.ISSUES -> {
                            InstallHealthSection(
                                report = healthReport,
                                loading = healthLoading,
                                onCheck = ::runInstallHealthCheck,
                            )
                            StorageCleanupSection(
                                breakdown = storageBreakdown,
                                loading = storageLoading,
                                onScan = ::refreshStorageBreakdown,
                                onCleanTemp = { runStorageCleanup(failedArchives = false) },
                                onDeleteFailedArchives = { runStorageCleanup(failedArchives = true) },
                                onCleanRedundantBackups = ::cleanRedundantBackups,
                            )
                            if (issueCount == 0 && bethesdaGame == null && bethesdaPlugins.isEmpty()) {
                                EmptyWorkflowSection(stringResource(R.string.nexus_no_issues_found), stringResource(R.string.nexus_no_issues_description))
                            }
                            if (conflictReports.isNotEmpty()) {
                                ConflictSummarySection(
                                    conflicts = conflictReports,
                                    onSelectInstall = { installId ->
                                        installs.firstOrNull { it.installId == installId }?.let(::selectInstallForPlacement)
                                    },
                                    onMovePriority = ::moveInstallPriority,
                                    onMakeWinner = ::makeInstallHighestPriority,
                                )
                            }
                            if (bethesdaPluginIssues.isNotEmpty() || bethesdaPluginAssetIssues.isNotEmpty()) {
                                BethesdaPluginDiagnosticsSection(
                                    issues = bethesdaPluginIssues,
                                    assetIssues = bethesdaPluginAssetIssues,
                                )
                            }
                            if (bethesdaGame != null || bethesdaPlugins.isNotEmpty()) {
                                BethesdaPluginsSection(
                                    game = bethesdaGame,
                                    plugins = bethesdaPlugins,
                                    issues = bethesdaPluginIssues,
                                    assetIssues = bethesdaPluginAssetIssues,
                                    onToggle = { plugin ->
                                        writePluginState(
                                            bethesdaPlugins.map {
                                                if (it.fileName == plugin.fileName) it.copy(enabled = !it.enabled) else it
                                            },
                                        )
                                    },
                                    onMove = { plugin, direction ->
                                        val index = bethesdaPlugins.indexOfFirst { it.fileName == plugin.fileName }
                                        val otherIndex = index + direction
                                        if (index >= 0 && otherIndex in bethesdaPlugins.indices) {
                                            writePluginState(bethesdaPlugins.toMutableList().apply {
                                                val moved = removeAt(index)
                                                add(otherIndex, moved)
                                            })
                                        }
                                    },
                                    onFixOrder = ::movePluginMastersBefore,
                                )
                            }
                        }
                    }
                }

                val displayedImportProgress = activeImportProgress ?: importProgress
                val displayedLoadingMessage = activeDownload?.let { "${it.displayName}: ${localizedImportStatus(it.status)}" } ?: loadingMessage
                displayedLoadingMessage?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(message, style = MaterialTheme.typography.bodyMedium)
                            }
                            displayedImportProgress?.let { detail ->
                                if (detail.status == "Downloading" && detail.downloadedBytes > 0L) {
                                    val totalText = if (detail.totalBytes > 0L) {
                                        StorageUtils.formatBinarySize(detail.totalBytes)
                                    } else {
                                        stringResource(R.string.nexus_progress_unknown)
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.nexus_downloaded_progress,
                                            StorageUtils.formatBinarySize(detail.downloadedBytes),
                                            totalText,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (detail.status == "Unpacking") {
                                    Text(
                                        text = when {
                                            detail.totalBytes > 0L && detail.downloadedBytes > 0L ->
                                                stringResource(
                                                    R.string.nexus_unpacked_progress,
                                                    StorageUtils.formatBinarySize(detail.downloadedBytes),
                                                    StorageUtils.formatBinarySize(detail.totalBytes),
                                                )
                                            detail.downloadedBytes > 0L ->
                                                stringResource(R.string.nexus_unpacked_size, StorageUtils.formatBinarySize(detail.downloadedBytes))
                                            else -> stringResource(R.string.nexus_unpacking_archive)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            val displayedProgress = displayedImportProgress?.progress ?: progress
                            if (displayedProgress > 0f && displayedProgress < 1f) {
                                LinearProgressIndicator(progress = { displayedProgress }, modifier = Modifier.fillMaxWidth())
                            } else if (displayedImportProgress?.status == "Unpacking") {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                }
            }
            NexusDialogSnackbarHost(
                hostState = dialogSnackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(bottom = 16.dp),
            )
        }
    }

    pendingApply?.let { pending ->
        OverwriteConfirmDialog(
            title = stringResource(R.string.nexus_overwrite_existing_title),
            message = stringResource(R.string.nexus_overwrite_existing_message),
            conflicts = pending.conflicts,
            confirmLabel = stringResource(R.string.nexus_backup_overwrite),
            onConfirm = {
                pendingApply = null
                applyRecipes(pending.install, pending.recipes, allowOverwrite = true)
            },
            onDismiss = { pendingApply = null },
        )
    }

    pendingProfileApply?.let { pending ->
        OverwriteConfirmDialog(
            title = stringResource(R.string.nexus_apply_mod_order_title),
            message = stringResource(R.string.nexus_apply_mod_order_message),
            conflicts = pending.conflicts,
            confirmLabel = stringResource(R.string.nexus_backup_apply),
            onConfirm = {
                pendingProfileApply = null
                applyProfileOrder(allowOverwrite = true)
            },
            onDismiss = { pendingProfileApply = null },
        )
    }

    pendingProfileNameEdit?.let { edit ->
        ProfileNameDialog(
            title = if (edit.profile == null) stringResource(R.string.nexus_profile_new_title) else stringResource(R.string.nexus_profile_rename_title),
            initialName = edit.initialName,
            onConfirm = { name ->
                val existing = profiles.any { profile ->
                    profile.profileId != edit.profile?.profileId && profile.name.equals(name.trim(), ignoreCase = true)
                }
                if (existing) {
                            SnackbarManager.show(context.getString(R.string.nexus_profile_name_duplicate))
                } else if (edit.profile == null) {
                    createProfile(name)
                } else {
                    renameProfile(edit.profile, name)
                }
            },
            onDismiss = { pendingProfileNameEdit = null },
        )
    }

    pendingProfileDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingProfileDelete = null },
            title = { Text(stringResource(R.string.nexus_profile_delete_title)) },
            text = { Text(stringResource(R.string.nexus_profile_delete_message)) },
            confirmButton = {
                TextButton(onClick = { deleteProfile(profile) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProfileDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
