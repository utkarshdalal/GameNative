package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PrefManager
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

private enum class ManageModsTab(val label: String) {
    IMPORT("Import"),
    MODS("Mods"),
    PLACEMENT("Placement"),
    ISSUES("Issues"),
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
        diagnosticsLoading -> "Scanning"
        issueCount > 0 -> "$issueCount issue(s)"
        else -> "Idle"
    }
    val summary = "${placeable.size} mods | $enabledCount enabled | ${activeProfile?.name ?: "Default"} | $queueText"
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
                        text = tab.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
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
                if (conflicts.size > 12) Text("+ ${conflicts.size - 12} more")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
        "Backups safe to clean ($redundantBackupCount records)"
    } else {
        "Backups safe to clean"
    }
    NexusSectionCard {
        NexusSectionHeader("Storage cleanup", loading, "Scan", onScan)
        Text(
            "Shows storage used by Nexus mod downloads, extracted cache, and backups for this game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Extracted cache and rollback backups are kept so mods can be reapplied, disabled, or restored safely.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Clean temp removes abandoned files. Delete failed archives removes failed downloads, so retry will download again. Clean redundant backups removes backups that are no longer needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Line("Temp/orphaned files", cleanable)
        Line("Failed download archives", failedArchives)
        breakdown?.let {
            Line("Extracted mod cache", it.extractedCacheBytes)
            Line("Rollback backups", it.backupBytes)
            Line(redundantBackupLabel, it.redundantBackupBytes)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCleanTemp, enabled = !loading && cleanable > 0L, modifier = Modifier.weight(1f)) {
                Text("Clean temp", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onDeleteFailedArchives, enabled = !loading && failedArchives > 0L, modifier = Modifier.weight(1f)) {
                Text("Delete failed archives", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(
            onClick = onCleanRedundantBackups,
            enabled = !loading && redundantBackupCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Clean redundant backups", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        NexusSectionHeader("Install health", loading, "Check", onCheck)
        Text(
            "Checks for missing mod files, broken install records, unsafe backups, and files that need to be restored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        report?.let { current ->
            if (current.issues.isEmpty()) {
                Text("No install health issues found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                val summaryColor = if (current.errorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Text("${current.errorCount} problem(s), ${current.warningCount} warning(s)", style = MaterialTheme.typography.bodySmall, color = summaryColor)
                current.issues.take(8).forEach { issue ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val titleColor = if (issue.severity == ModHealthSeverity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        Text(listOf(issue.installName, issue.title).filter(String::isNotBlank).joinToString(": "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = titleColor)
                        Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (current.issues.size > 8) {
                    Text("+ ${current.issues.size - 8} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val roots = remember(gameRootDir, winePrefix) {
        ModTargetResolver.roots(gameRootDir, winePrefix).ifEmpty {
            listOfNotNull(gameRootDir?.takeIf { it.isDirectory }?.let {
                app.gamenative.mods.ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, "Game Directory", it)
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
                SnackbarManager.show("Freed ${StorageUtils.formatBinarySize(result.reclaimedBytes)}")
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: "Storage cleanup failed")
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
                SnackbarManager.show("Freed ${StorageUtils.formatBinarySize(result.reclaimedBytes)}")
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: "Redundant backup cleanup failed")
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
                        "No install health issues found"
                    } else {
                        "Found ${report?.issues?.size ?: 0} install health issue(s)"
                    },
                )
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: "Install health check failed")
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
                installs.filter { it.canPlaceFiles() }.forEach { install ->
                    val state = ModProfileManager.ensureStateForInstall(dao, profile, install.installId)
                    if (install.status == ModInstallStatus.DISABLED.name && state.enabled) {
                        dao.upsertProfileInstallState(state.copy(enabled = false, updatedAt = System.currentTimeMillis()))
                    }
                }
                val states = dao.getProfileInstallStates(libraryItem.appId, profile.profileId)
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
            bethesdaGame = snapshot.bethesdaGame
            bethesdaPlugins = snapshot.plugins
            bethesdaPluginIssues = snapshot.pluginIssues
            bethesdaPluginAssetIssues = snapshot.pluginAssetIssues
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            conflictReports = emptyList()
            bethesdaPlugins = emptyList()
            bethesdaPluginIssues = emptyList()
            bethesdaPluginAssetIssues = emptyList()
            SnackbarManager.show(e.message ?: "Failed to scan Nexus mod diagnostics")
        } finally {
            diagnosticsLoading = false
        }
    }

    fun createProfile(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            SnackbarManager.show("Enter a profile name")
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
                SnackbarManager.show("Created $trimmedName")
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: "Failed to create profile")
            }
        }
    }

    fun renameProfile(profile: ModProfile, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            SnackbarManager.show("Enter a profile name")
            return
        }
        scope.launch {
            try {
                dao.renameProfile(profile.profileId, trimmedName)
                pendingProfileNameEdit = null
                SnackbarManager.show("Renamed profile")
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: "Failed to rename profile")
            }
        }
    }

    fun deleteProfile(profile: ModProfile) {
        scope.launch {
            val currentProfiles = dao.getProfilesForApp(libraryItem.appId)
            if (currentProfiles.size <= 1) {
                SnackbarManager.show("At least one profile is required")
                pendingProfileDelete = null
                return@launch
            }
            val replacement = currentProfiles.firstOrNull { it.profileId != profile.profileId }
            dao.deleteProfile(profile.profileId)
            if (profile.active && replacement != null) {
                dao.activateProfile(libraryItem.appId, replacement.profileId)
            }
            pendingProfileDelete = null
            SnackbarManager.show("Deleted ${profile.name}")
        }
    }

    fun activateProfile(profile: ModProfile) {
        scope.launch {
            dao.activateProfile(libraryItem.appId, profile.profileId)
            installs.filter { it.canPlaceFiles() }.forEach { install ->
                ModProfileManager.ensureStateForInstall(dao, profile.copy(active = true), install.installId)
            }
            SnackbarManager.show("Profile switched. Use Apply order to update game files.")
        }
    }

    fun setProfileInstallEnabled(install: ModInstall, enabled: Boolean) {
        scope.launch {
            val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
            val state = ModProfileManager.ensureStateForInstall(dao, profile, install.installId)
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
                        "Disabled in ${profile.name}"
                    } else {
                        "Disabled in ${profile.name}; ${skipped.size} changed file(s) were left in place"
                    },
                )
            } else {
                if (install.status == ModInstallStatus.DISABLED.name) {
                    dao.updateInstallEnabled(install.installId, true, ModInstallStatus.READY.name)
                }
                SnackbarManager.show("Enabled in ${profile.name}. Use Apply order to place files.")
            }
        }
    }

    fun moveInstallPriority(installId: String, direction: Int) {
        scope.launch {
            val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
            installs.filter { it.canPlaceFiles() }.forEach { install ->
                ModProfileManager.ensureStateForInstall(dao, profile, install.installId)
            }
            val states = dao.getProfileInstallStates(libraryItem.appId, profile.profileId)
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
            installs.filter { it.canPlaceFiles() }.forEach { install ->
                ModProfileManager.ensureStateForInstall(dao, profile, install.installId)
            }
            val states = dao.getProfileInstallStates(libraryItem.appId, profile.profileId)
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
                    "Plugin list saved, but missing or disabled masters need attention"
                } else {
                    "Plugin list saved"
                },
            )
        }
    }

    fun movePluginMastersBefore(plugin: BethesdaPlugin, masterNames: List<String>) {
        val masterKeys = masterNames.map { it.trim().removePrefix("*").lowercase() }.toSet()
        val moving = bethesdaPlugins.filter { it.fileName.lowercase() in masterKeys }
        if (moving.isEmpty()) {
            SnackbarManager.show("The required plugin is not managed by this mod list.")
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

    fun applyProfileOrder(allowOverwrite: Boolean) {
        scope.launch {
            diagnosticsPaused = true
            try {
                SnackbarManager.show("Applying mod order. Large mod lists may take a while.")
                loadingMessage = "Checking mod order"
                var effectiveAllowOverwrite = allowOverwrite
                val collectionPluginOrder = pendingCollectionSelection?.collection?.manifestInfo?.rules?.pluginLoadOrder.orEmpty()
                val conflictInstallIds = conflictReports
                    .flatMap { report -> report.participants.map { it.installId } }
                    .toSet()
                val plan = withContext(Dispatchers.IO) {
                    val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                    val stateByInstallId = dao.getProfileInstallStates(libraryItem.appId, profile.profileId)
                        .associateBy { it.installId }
                    val disabledInstalls = installs
                        .filter { it.canPlaceFiles() && !(stateByInstallId[it.installId]?.enabled ?: (it.status != ModInstallStatus.DISABLED.name)) }
                    val orderedInstalls = installs
                        .filter { it.canPlaceFiles() && (stateByInstallId[it.installId]?.enabled ?: (it.status != ModInstallStatus.DISABLED.name)) }
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
                        unconfiguredCount = orderedInstalls.size - configuredInstalls.size,
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
                    SnackbarManager.show("Fix plugin master warnings before applying order.")
                    return@launch
                }
                var disabledSkipped = 0

                if (!allowOverwrite) {
                    loadingMessage = "Checking file conflicts"
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
                        SnackbarManager.show("Profile order has existing target files. Use Overwrite files and create backups for mods that should win conflicts.")
                        return@launch
                    }
                    effectiveAllowOverwrite = check.rawConflicts.isNotEmpty()
                }

                val availableBytes = NexusModManager.cacheRoot(context, libraryItem.appId).usableSpace
                if (availableBytes < MIN_APPLY_FREE_BYTES) {
                    SnackbarManager.show(
                        "Storage is too low to apply mod order. Free at least ${StorageUtils.formatBinarySize(MIN_APPLY_FREE_BYTES - availableBytes)} more.",
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
                    loadingMessage = "Applying mod order"
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

                loadingMessage = "Applying mod order"
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
                val suffix = if (disabledSkipped > 0) "; $disabledSkipped changed disabled-file(s) left in place" else ""
                val skippedSuffix = if (plan.unconfiguredCount > 0) "; ${plan.unconfiguredCount} mod(s) need placement setup" else ""
                if (healthReport != null) {
                    loadingMessage = "Refreshing install health"
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
                SnackbarManager.show(if (result.errors == 0) "Mod order applied$suffix$skippedSuffix" else "Mod order applied with ${result.errors} error(s)$suffix$skippedSuffix")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: "Failed to apply mod order")
            } finally {
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
                    SnackbarManager.show("Cleaned ${StorageUtils.formatBinarySize(cleanup.reclaimedBytes)} from old mod temp files")
                }
                val storage = NexusModManager.checkImportStorage(context, libraryItem.appId, listOf(file))
                if (!storage.canImport) {
                    SnackbarManager.show(
                        "Not enough storage. Need about ${StorageUtils.formatBinarySize(storage.estimatedRequiredBytes)}, available ${StorageUtils.formatBinarySize(storage.availableBytes)}.",
                    )
                    return@launch
                }
                loadingMessage = "Starting ${file.name.ifBlank { file.fileName }}"
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
                SnackbarManager.show("Nexus mod imported")
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

    suspend fun resolveCollectionMod(collectionFile: NexusCollectionFile): PendingCollectionMod {
        if (collectionFile.modId <= 0L || collectionFile.fileId <= 0L) {
            return PendingCollectionMod(
                collectionFile = collectionFile,
                modInfo = null,
                file = null,
                error = "Manual or external collection entry",
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
                        name = collectionFile.modName.ifBlank { "Nexus mod ${collectionFile.modId}" },
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
                    error = e.message ?: "Could not resolve this collection mod",
                )
            }
        }
    }

    fun resolveCollection(reference: app.gamenative.mods.NexusCollectionReference) {
        PrefManager.nexusApiKey = apiKey.trim()
        scope.launch {
            try {
                loadingMessage = "Resolving Nexus collection"
                progress = 0f
                importProgress = null
                val collection = apiClient.getCollectionRevision(reference)
                if (collection.files.isEmpty()) {
                    SnackbarManager.show("No downloadable mods were returned for this collection")
                    return@launch
                }
                val resolvedMods = mutableListOf<PendingCollectionMod>()
                collection.files.forEachIndexed { index, file ->
                    loadingMessage = "Resolving collection ${index + 1}/${collection.files.size}"
                    resolvedMods += resolveCollectionMod(file)
                }
                pendingFileSelection = null
                pendingCollectionSelection = PendingCollectionSelection(collection, resolvedMods)
                selectedTab = ManageModsTab.IMPORT
                SnackbarManager.show("Collection ready: ${resolvedMods.count { it.canImport }} mod(s)")
            } catch (e: NexusApiException) {
                SnackbarManager.show(NexusImportState.userMessage(e, "Failed to resolve Nexus collection"))
            } catch (e: Exception) {
                SnackbarManager.show(NexusImportState.userMessage(e, "Failed to resolve Nexus collection"))
            } finally {
                if (loadingMessage?.startsWith("Resolving") == true) loadingMessage = null
            }
        }
    }

    fun importCollection(pending: PendingCollectionSelection, selectedKeys: Set<String>) {
        val collectionMods = pending.mods.filter { it.canImport && it.collectionKey() in selectedKeys }
        if (collectionMods.isEmpty()) {
            SnackbarManager.show("No selected collection mods are ready to download")
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
                collectionQueue[key] = (current ?: pendingMod.toQueueItem(status)).copy(
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
                    val state = ModProfileManager.ensureStateForInstall(
                        dao = dao,
                        profile = profile,
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
                        add("Collection requested a different file; existing installed file reused")
                    }
                    if (existingRecipes.isNotEmpty() && reusedExisting) add("Existing placement kept")
                    addAll(assessment.reasons)
                    addAll(fomodAutoSelection?.reasons.orEmpty())
                }.distinct()
                val queueMessage = when {
                    reusedExisting && install.nexusFileId != (reference.fileId ?: file.fileId) -> "Already imported; using existing file"
                    reusedExisting && existingRecipes.isNotEmpty() -> "Already imported; kept placement"
                    reusedExisting -> "Already imported"
                    fomodAutoSelection != null -> "Imported; FOMOD auto-selected"
                    else -> assessment.queueMessage
                }
                return queueMessage to queueReasons.joinToString("; ")
            }
            try {
                val cleanup = NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
                if (cleanup.reclaimedBytes > 0L) {
                    SnackbarManager.show("Cleaned ${StorageUtils.formatBinarySize(cleanup.reclaimedBytes)} from old mod temp files")
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
                        "Not enough storage. Need about ${StorageUtils.formatBinarySize(storage.estimatedRequiredBytes)}, available ${StorageUtils.formatBinarySize(storage.availableBytes)}.",
                    )
                    return@launch
                }
                val suggestedPriorities = NexusCollectionPrioritySuggester.priorities(collectionMods.map { it.collectionFile })
                val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                for ((index, pendingMod) in collectionMods.withIndex()) {
                    while (collectionPaused && !collectionCancelRequested) {
                        updateQueue(pendingMod, CollectionQueueStatus.QUEUED, message = "Paused")
                        delay(300L)
                    }
                    if (collectionCancelRequested) {
                        updateQueue(pendingMod, CollectionQueueStatus.CANCELED, message = "Canceled")
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
                        loadingMessage = "Preparing collection ${index + 1}/${collectionMods.size}: ${modInfo.name}"
                        progress = 0f
                        importProgress = null
                        val suggestedPriority = suggestedPriorities[pendingMod.collectionKey()] ?: index
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
                                message = "Already imported",
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
                        loadingMessage = "Starting collection ${index + 1}/${collectionMods.size}: ${modInfo.name}"
                        updateQueue(
                            pendingMod,
                            status = CollectionQueueStatus.IMPORTING,
                            message = "Starting",
                            startedAt = System.currentTimeMillis(),
                        )
                        val install = NexusModImportService.enqueueImport(
                            context = context,
                            appId = libraryItem.appId,
                            reference = reference,
                            modInfo = modInfo,
                            file = file,
                            displayName = "Collection ${index + 1}/${collectionMods.size}: ${modInfo.name}",
                            onProgress = { detail ->
                                scope.launch(Dispatchers.Main) {
                                    updateQueue(
                                        pendingMod,
                                        CollectionQueueStatus.IMPORTING,
                                        progress = detail.progress,
                                        message = detail.status,
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
                            message = if (canceled) "Canceled" else "Failed",
                            error = NexusImportState.userMessage(e),
                        )
                    } finally {
                        activeCollectionInstallId = null
                    }
                }
                val prepared = imported + reused
                val suffix = buildString {
                    if (reused > 0) append("; $reused already imported")
                    if (failed > 0) append("; $failed failed")
                }
                if (collectionCancelRequested) {
                    SnackbarManager.show("Collection import canceled after $prepared prepared mod(s)$suffix.")
                } else {
                    SnackbarManager.show("Prepared $prepared collection mod(s)$suffix. Applying configured mods.")
                    if (prepared > 0) applyProfileOrder(allowOverwrite = false)
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
            SnackbarManager.show("Enter a valid Nexus mod or collection URL")
            return
        }
        PrefManager.nexusApiKey = apiKey.trim()
        scope.launch {
            try {
                loadingMessage = "Resolving Nexus mod"
                progress = 0f
                importProgress = null
                val modInfo = apiClient.getModInfo(reference.gameDomain, reference.modId)
                val files = apiClient.getModFiles(reference.gameDomain, reference.modId)
                if (files.isEmpty()) {
                    SnackbarManager.show("No downloadable files were returned for this mod")
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
                SnackbarManager.show(NexusImportState.userMessage(e, "Failed to resolve Nexus URL"))
            } catch (e: Exception) {
                SnackbarManager.show(NexusImportState.userMessage(e, "Failed to resolve Nexus URL"))
            } finally {
                if (loadingMessage == "Resolving Nexus mod") loadingMessage = null
            }
        }
    }

    fun buildRecipes(install: ModInstall): List<ModPlacementRecipe> =
        BethesdaPlacementRecipeExpander.expand(
            gameName = libraryItem.name,
            install = install,
            recipes = recipeDrafts.map { draft -> draft.toRecipe(install.installId) },
        )

    fun applyRecipes(install: ModInstall, allowOverwrite: Boolean) {
        val recipes = buildRecipes(install)
        scope.launch {
            try {
                loadingMessage = "Applying mod files"
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
                    val cleanupSuffix = if (cleanupSkipped.isNotEmpty()) "; ${cleanupSkipped.size} old file(s) left in place" else ""
                    "Applied ${result.created} item(s), backed up ${result.backedUp}$cleanupSuffix"
                } else {
                    "Applied with ${result.errors.size} error(s)"
                }
                SnackbarManager.show(message)
                selectedInstall = install.copy(status = if (result.errors.isEmpty()) ModInstallStatus.APPLIED.name else ModInstallStatus.ERROR.name)
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: "Failed to apply mod")
            } finally {
                loadingMessage = null
            }
        }
    }

    fun saveAndApply() {
        val install = selectedInstall ?: return
        if (!install.canPlaceFiles()) {
            SnackbarManager.show("This mod did not finish importing.")
            return
        }
        if (!recipeDrafts.all { draft -> roots.any { root -> root.type.name == draft.targetRoot } }) {
            SnackbarManager.show("Choose a destination inside this game or container.")
            return
        }
        if (
            selectedFomodInstaller != null &&
            placementChoice != PlacementChoice.CUSTOM &&
            recipeDrafts.any { draft -> ModPlacementSources.decode(draft.sourceSubpath).isEmpty() }
        ) {
            SnackbarManager.show("Configure the FOMOD installer or choose Custom placement before applying.")
            return
        }
        val recipes = buildRecipes(install)
        scope.launch {
            try {
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
                    SnackbarManager.show("Target files already exist. Choose Overwrite files and create backups to replace them.")
                } else {
                    applyRecipes(install, allowOverwrite = rawConflicts.isNotEmpty())
                }
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: "Failed to scan placement conflicts")
            }
        }
    }

    val issueCount = conflictReports.size + bethesdaPluginIssues.size + bethesdaPluginAssetIssues.size + (healthReport?.issues?.size ?: 0)

    fun selectInstallForPlacement(install: ModInstall) {
        selectedInstall = install
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
                    message = "Canceled",
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
                        Text("Manage Nexus Mods", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = libraryItem.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text("Close")
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
                                            apiKeyValidation = ApiKeyValidationState(checking = true, message = "Validating Nexus API key")
                                            loadingMessage = "Validating Nexus API key"
                                            val user = apiClient.validateKey()
                                            apiKeyValidation = ApiKeyValidationState(
                                                message = "Connected to Nexus as ${user.name}",
                                                success = true,
                                            )
                                            SnackbarManager.show("Connected to Nexus as ${user.name}")
                                        } catch (e: Exception) {
                                            apiKeyValidation = ApiKeyValidationState(
                                                message = e.message ?: "Nexus API key validation failed",
                                                success = false,
                                            )
                                            SnackbarManager.show(e.message ?: "Nexus API key validation failed")
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
                                onCreate = { pendingProfileNameEdit = PendingProfileNameEdit(null, nextProfileName(profiles)) },
                                onRename = { profile -> pendingProfileNameEdit = PendingProfileNameEdit(profile, profile.name) },
                                onDelete = { profile -> pendingProfileDelete = profile },
                            )
                            InstalledModsSection(
                                installs = installs,
                                priorityByInstallId = priorityByInstallId,
                                enabledByInstallId = profileEnabledByInstallId,
                                selectedInstall = selectedInstall,
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
                                            if (skipped.isEmpty()) "Mod deleted" else "Mod deleted; ${skipped.size} changed file(s) were left in place",
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
                                        placementChoice = PlacementChoice.LAST_USED
                                        recipeDrafts.clear()
                                        recipeDrafts += compatibleLastPlacementDrafts(lastPlacementDrafts, archiveEntries, defaultDraft)
                                    },
                                    onPresetSelected = { drafts ->
                                        placementChoice = PlacementChoice.PRESET
                                        recipeDrafts.clear()
                                        recipeDrafts += drafts
                                    },
                                    onUpdateDraft = { index, draft -> recipeDrafts[index] = draft },
                                    onAddDraft = { recipeDrafts += defaultDraft },
                                    onRemoveDraft = { index ->
                                        if (recipeDrafts.size > 1) recipeDrafts.removeAt(index)
                                    },
                                    onFomodRecipes = { drafts, unsupportedCount ->
                                        placementChoice = PlacementChoice.CUSTOM
                                        recipeDrafts.clear()
                                        recipeDrafts += drafts
                                        if (unsupportedCount > 0) {
                                            SnackbarManager.show("$unsupportedCount FOMOD file mapping(s) need manual placement")
                                        } else {
                                            SnackbarManager.show("FOMOD choices added")
                                        }
                                    },
                                    onSaveAndApply = ::saveAndApply,
                                )
                            } ?: EmptyWorkflowSection("No mod selected", "Select a mod from the Mods tab.")
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
                                EmptyWorkflowSection("No issues found", "Conflicts and plugin warnings will appear here.")
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
                val displayedLoadingMessage = activeDownload?.let { "${it.displayName}: ${it.status}" } ?: loadingMessage
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
                                        "unknown"
                                    }
                                    Text(
                                        text = "${StorageUtils.formatBinarySize(detail.downloadedBytes)} / $totalText downloaded",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (detail.status == "Unpacking") {
                                    Text(
                                        text = when {
                                            detail.totalBytes > 0L && detail.downloadedBytes > 0L ->
                                                "${StorageUtils.formatBinarySize(detail.downloadedBytes)} / ${StorageUtils.formatBinarySize(detail.totalBytes)} unpacked"
                                            detail.downloadedBytes > 0L ->
                                                "${StorageUtils.formatBinarySize(detail.downloadedBytes)} unpacked"
                                            else -> "Unpacking archive"
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
            title = "Overwrite existing files?",
            message = "GameNative will back up existing files before replacing them.",
            conflicts = pending.conflicts,
            confirmLabel = "Back Up & Overwrite",
            onConfirm = {
                pendingApply = null
                applyRecipes(pending.install, allowOverwrite = true)
            },
            onDismiss = { pendingApply = null },
        )
    }

    pendingProfileApply?.let { pending ->
        OverwriteConfirmDialog(
            title = "Apply mod order?",
            message = "GameNative will apply enabled mods from lowest to highest priority and back up replaced files.",
            conflicts = pending.conflicts,
            confirmLabel = "Back Up & Apply",
            onConfirm = {
                pendingProfileApply = null
                applyProfileOrder(allowOverwrite = true)
            },
            onDismiss = { pendingProfileApply = null },
        )
    }

    pendingProfileNameEdit?.let { edit ->
        ProfileNameDialog(
            title = if (edit.profile == null) "New profile" else "Rename profile",
            initialName = edit.initialName,
            onConfirm = { name ->
                val existing = profiles.any { profile ->
                    profile.profileId != edit.profile?.profileId && profile.name.equals(name.trim(), ignoreCase = true)
                }
                if (existing) {
                    SnackbarManager.show("A profile with that name already exists")
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
            title = { Text("Delete profile?") },
            text = { Text("This removes the profile and its mod order. Installed mods are not deleted.") },
            confirmButton = {
                TextButton(onClick = { deleteProfile(profile) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProfileDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
