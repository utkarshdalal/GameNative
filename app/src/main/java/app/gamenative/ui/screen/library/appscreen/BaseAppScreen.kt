package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.api.isValidCommunityConfig
import app.gamenative.api.prepareCommunityConfigForApply
import app.gamenative.data.GameSource
import app.gamenative.data.FavoritesManager
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.mods.ModContainerResolver
import app.gamenative.mods.NexusModManager
import app.gamenative.ui.component.dialog.CommunityConfigsDialog
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.NexusModsDialog
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.screen.library.components.toggleFavorite
import app.gamenative.ui.util.ContainerConfigTransfer
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DiagnosticsLog
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.createPinnedShortcut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import com.winlator.container.ContainerData
import com.winlator.core.GPUInformation
import java.io.File
import kotlin.text.Charsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Abstract base class for AppScreen implementations.
 * This defines the contract that all game source-specific screens must implement.
 */
data class KnownConfigInstallState(
    val visible: Boolean,
    val progress: Float,
    val label: String,
)

internal suspend fun installMissingComponentsForConfig(
    context: Context,
    gameId: Int,
    configJson: kotlinx.serialization.json.JsonObject,
    matchType: String,
    matchedGpu: String = "",
    preserveConfigValues: Boolean = false,
): Boolean {
    val missingRequests = BestConfigService.resolveMissingManifestInstallRequests(
        context = context,
        configJson = configJson,
        matchType = matchType,
        matchedGpu = matchedGpu,
        preserveConfigValues = preserveConfigValues,
    )
    if (missingRequests.isEmpty()) return true
    val parentContext = currentCoroutineContext()
    val progressJob = SupervisorJob(parentContext[Job])
    val progressScope = CoroutineScope(parentContext + progressJob)

    try {
        withContext(Dispatchers.Main.immediate) {
            BaseAppScreen.showKnownConfigInstallState(
                gameId,
                KnownConfigInstallState(
                    visible = true,
                    progress = -1f,
                    label = missingRequests.first().entry.name,
                ),
            )
        }

        for (request in missingRequests) {
            val label = request.entry.id
            withContext(Dispatchers.Main.immediate) {
                BaseAppScreen.showKnownConfigInstallState(
                    gameId,
                    KnownConfigInstallState(
                        visible = true,
                        progress = -1f,
                        label = label,
                    ),
                )
            }
            val result = ManifestInstaller.installManifestEntry(
                context = context,
                entry = request.entry,
                isDriver = request.isDriver,
                contentType = request.contentType,
                onProgress = { progress ->
                    val clamped = progress.coerceIn(0f, 1f)
                    progressScope.launch(Dispatchers.Main.immediate) {
                        BaseAppScreen.showKnownConfigInstallState(
                            gameId,
                            KnownConfigInstallState(
                                visible = true,
                                progress = clamped,
                                label = label,
                            ),
                        )
                    }
                },
            )
            SnackbarManager.show(result.message)
            if (!result.success) return false
        }
        return true
    } finally {
        progressJob.cancel()
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            BaseAppScreen.hideKnownConfigInstallState(gameId)
        }
    }
}

abstract class BaseAppScreen {
    companion object {
        private val installDialogStates = mutableStateMapOf<String, app.gamenative.ui.component.dialog.state.MessageDialogState>()
        private val exportConfigRequests = mutableStateMapOf<String, Boolean>()
        private val importConfigRequests = mutableStateMapOf<String, Boolean>()
        private val exportSavesRequests = mutableStateMapOf<String, Boolean>()
        private val importSavesRequests = mutableStateMapOf<String, Boolean>()
        private val manageModsRequests = mutableStateMapOf<String, Boolean>()
        private val communityConfigRequests = mutableStateMapOf<String, Boolean>()
        private val knownConfigInstallStates = mutableStateMapOf<Int, KnownConfigInstallState>()

        fun showInstallDialog(appId: String, state: app.gamenative.ui.component.dialog.state.MessageDialogState) {
            installDialogStates[appId] = state
        }

        fun hideInstallDialog(appId: String) {
            installDialogStates.remove(appId)
        }

        fun getInstallDialogState(appId: String): app.gamenative.ui.component.dialog.state.MessageDialogState? {
            return installDialogStates[appId]
        }

        fun requestExportConfig(appId: String) {
            exportConfigRequests[appId] = true
        }

        fun clearExportConfigRequest(appId: String) {
            exportConfigRequests.remove(appId)
        }

        fun shouldExportConfig(appId: String): Boolean {
            return exportConfigRequests[appId] == true
        }

        fun requestImportConfig(appId: String) {
            importConfigRequests[appId] = true
        }

        fun clearImportConfigRequest(appId: String) {
            importConfigRequests.remove(appId)
        }

        fun shouldImportConfig(appId: String): Boolean {
            return importConfigRequests[appId] == true
        }

        fun requestExportSaves(appId: String) {
            exportSavesRequests[appId] = true
        }

        fun clearExportSavesRequest(appId: String) {
            exportSavesRequests.remove(appId)
        }

        fun shouldExportSaves(appId: String): Boolean {
            return exportSavesRequests[appId] == true
        }

        fun requestImportSaves(appId: String) {
            importSavesRequests[appId] = true
        }

        fun clearImportSavesRequest(appId: String) {
            importSavesRequests.remove(appId)
        }

        fun shouldImportSaves(appId: String): Boolean {
            return importSavesRequests[appId] == true
        }

        fun requestManageMods(appId: String) {
            manageModsRequests[appId] = true
        }

        fun clearManageModsRequest(appId: String) {
            manageModsRequests.remove(appId)
        }

        fun shouldManageMods(appId: String): Boolean {
            return manageModsRequests[appId] == true
        }

        fun requestCommunityConfigs(appId: String) {
            communityConfigRequests[appId] = true
        }

        fun clearCommunityConfigsRequest(appId: String) {
            communityConfigRequests.remove(appId)
        }

        fun shouldBrowseCommunityConfigs(appId: String): Boolean {
            return communityConfigRequests[appId] == true
        }

        // missing components that prevent config from being applied
        data class MissingComponentsState(
            val components: List<String>,
            val onApplyAnyway: (() -> Unit)? = null,
        )

        private val missingComponentsDialogStates = mutableStateMapOf<String, MissingComponentsState>()

        fun showMissingComponentsDialog(appId: String, components: List<String>, onApplyAnyway: (() -> Unit)? = null) {
            missingComponentsDialogStates[appId] = MissingComponentsState(components, onApplyAnyway)
        }

        fun hideMissingComponentsDialog(appId: String) {
            missingComponentsDialogStates.remove(appId)
        }

        fun getMissingComponentsState(appId: String): MissingComponentsState? {
            return missingComponentsDialogStates[appId]
        }

        fun showKnownConfigInstallState(gameId: Int, state: KnownConfigInstallState) {
            knownConfigInstallStates[gameId] = state
        }

        fun hideKnownConfigInstallState(gameId: Int) {
            knownConfigInstallStates.remove(gameId)
        }

        fun getKnownConfigInstallState(gameId: Int): KnownConfigInstallState? {
            return knownConfigInstallStates[gameId]
        }
    }

    /**
     * Compatibility info is fetched and cached by [LibraryViewModel]. App screens should only read from cache
     * and render the message if available.
     */
    @Composable
    protected fun rememberCompatibilityInfo(
        context: Context,
        gameName: String,
    ): Pair<String?, ULong?> {
        var compatibilityMessage by remember(gameName) { mutableStateOf<String?>(null) }
        var compatibilityColor by remember(gameName) { mutableStateOf<ULong?>(null) }

        LaunchedEffect(gameName) {
            if (gameName.isBlank()) {
                compatibilityMessage = null
                compatibilityColor = null
                return@LaunchedEffect
            }
            try {
                val cachedResponse = GameCompatibilityCache.getCached(gameName)
                if (cachedResponse != null) {
                    val message = GameCompatibilityService.getCompatibilityMessageFromResponse(context, cachedResponse)
                    compatibilityMessage = message.text
                    compatibilityColor = message.color.value
                } else {
                    compatibilityMessage = null
                    compatibilityColor = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("BaseAppScreen").e(e, "Failed to get compatibility from cache")
                compatibilityMessage = null
                compatibilityColor = null
            }
        }

        return compatibilityMessage to compatibilityColor
    }

    /**
     * Get the game display information for rendering the UI.
     * This is called to get all the data needed for the common UI layout.
     */
    @Composable
    abstract fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo

    /**
     * Check if the game is installed
     */
    abstract fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean

    /**
     * Check if the game can be downloaded/installed
     */
    abstract fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean

    /**
     * Check if the game is currently downloading
     */
    abstract fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean

    /**
     * Get the current download progress (0.0 to 1.0)
     */
    abstract fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float

    /**
     * Check if there's a partial/incomplete download that can be resumed
     * Default implementation checks if progress is > 0 and < 1, but can be overridden
     * for more accurate detection (e.g., checking for marker files)
     */
    open fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean {
        val progress = getDownloadProgress(context, libraryItem)
        return progress > 0f && progress < 1f
    }

    /**
     * Check if a stale install record remains even though the game is not actually installed
     * (e.g. its files went missing after a storage switch). Such a record blocks reinstall
     * until it is cleaned up, so sources that can detect it should expose a delete action.
     */
    open fun hasLeftoverInstall(context: Context, libraryItem: LibraryItem): Boolean {
        return false
    }

    /**
     * Check if an update is pending (synchronous version, returns false by default)
     * Override isUpdatePendingSuspend for async checks
     */
    open fun isUpdatePending(context: Context, libraryItem: LibraryItem): Boolean {
        return false
    }

    /**
     * Check if an update is pending (suspend version for async checks)
     * Override this if you need to call suspend functions
     */
    open suspend fun isUpdatePendingSuspend(context: Context, libraryItem: LibraryItem): Boolean {
        return isUpdatePending(context, libraryItem)
    }

    /**
     * Handle the play/install button click
     */
    abstract fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit)

    /**
     * Handle pause/resume download click
     */
    abstract fun onPauseResumeClick(context: Context, libraryItem: LibraryItem)

    /**
     * Handle delete download click
     */
    abstract fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem)

    /**
     * Handle update click
     */
    abstract fun onUpdateClick(context: Context, libraryItem: LibraryItem)

    /**
     * Get the game name for shortcuts and dialogs
     */
    @Composable
    protected fun getGameName(context: Context, libraryItem: LibraryItem): String {
        // Use display info to get the name
        return getGameDisplayInfo(context, libraryItem).name
    }

    protected fun getGameSource(libraryItem: LibraryItem): GameSource {
        return libraryItem.gameSource
    }

    /**
     * Get the game ID for shortcuts depending on app type
     */
    protected fun getGameId(libraryItem: LibraryItem): Int {
        return libraryItem.gameId
    }

    /**
     * Get the icon URL for shortcuts (can be null)
     */
    @Composable
    protected fun getIconUrl(context: Context, libraryItem: LibraryItem): String? {
        return getGameDisplayInfo(context, libraryItem).iconUrl
    }

    /**
     * Get the file extension for exported frontend files (e.g., ".steam", ".game")
     * Must be overridden by subclasses to provide source-specific extension
     */
    abstract fun getExportFileExtension(): String

    /**
     * Get the game install path (non-composable version).
     * Returns the path to the game's installation directory, or null if not installed.
     * Must be implemented by subclasses to provide source-specific path resolution.
     */
    protected abstract fun getInstallPath(context: Context, libraryItem: LibraryItem): String?

    /**
     * Get Edit Container menu option.
     */
    @Composable
    protected open fun getEditContainerOption(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
    ): AppMenuOption {
        return AppMenuOption(
            optionType = AppOptionMenuType.EditContainer,
            onClick = onEditContainer,
        )
    }

    @Composable
    protected open fun getRunContainerOption(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
    ): AppMenuOption? {
        return AppMenuOption(
            AppOptionMenuType.RunContainer,
            onClick = {
                onRunContainerClick(context, libraryItem, onClickPlay)
            },
        )
    }

    @Composable
    protected open fun getTestGraphicsOption(
        context: Context,
        libraryItem: LibraryItem,
        onTestGraphics: () -> Unit,
    ): AppMenuOption? {
        return AppMenuOption(
            AppOptionMenuType.TestGraphics,
            onClick = {
                onTestGraphicsClick(context, libraryItem, onTestGraphics)
            },
        )
    }

    @Composable
    protected open fun getPlayWithDiagnosticsOption(
        context: Context,
        libraryItem: LibraryItem,
        onPlayWithDiagnostics: () -> Unit,
    ): AppMenuOption? {
        return AppMenuOption(
            AppOptionMenuType.PlayWithDiagnostics,
            onClick = { onPlayWithDiagnostics() },
        )
    }

    @Composable
    protected open fun getShareDiagnosticsOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        if (!DiagnosticsLog.exists(context, libraryItem.appId)) return null
        return AppMenuOption(
            AppOptionMenuType.ShareDiagnostics,
            onClick = { shareDiagnostics(context, libraryItem) },
        )
    }

    private fun shareDiagnostics(context: Context, libraryItem: LibraryItem) {
        val file = DiagnosticsLog.file(context, libraryItem.appId)
        if (!file.exists()) {
            SnackbarManager.show(context.getString(R.string.diagnostics_share_none))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.diagnostics_share_title)),
        )
    }

    @Composable
    protected abstract fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption?

    @Composable
    protected open fun getExportContainerOption(
        context: Context,
        libraryItem: LibraryItem,
        exportFrontendLauncher: ActivityResultLauncher<String>,
    ): AppMenuOption? {
        val gameId = getGameId(libraryItem)
        val gameName = getGameName(context, libraryItem)
        val extension = getExportFileExtension()
        return AppMenuOption(
            optionType = AppOptionMenuType.ExportFrontend,
            onClick = {
                val suggested = "${gameName}$extension"
                exportFrontendLauncher.launch(suggested)
            },
        )
    }

    /**
     * Get "Use known config" menu option. Subclasses can override to customize behavior
     * or disable it entirely by returning null.
     */
    @Composable
    protected open fun getUseKnownConfigOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        val scope = rememberCoroutineScope()
        return AppMenuOption(
            optionType = AppOptionMenuType.UseKnownConfig,
            onClick = {
                scope.launch(Dispatchers.IO) {
                    applyKnownConfigForLibraryItem(context, libraryItem)
                }
            },
        )
    }

    @Composable
    protected open fun getBrowseCommunityConfigsOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        return AppMenuOption(
            optionType = AppOptionMenuType.BrowseCommunityConfigs,
            onClick = { requestCommunityConfigs(libraryItem.appId) },
        )
    }

    /**
     * Get export-config menu option. Subclasses can override to customize behavior
     * or disable export-config entirely by returning null.
     */
    @Composable
    protected open fun getExportConfigOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        return AppMenuOption(
            optionType = AppOptionMenuType.ExportConfig,
            onClick = {
                requestExportConfig(libraryItem.appId)
            },
        )
    }

    @Composable
    protected open fun getImportConfigOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        return AppMenuOption(
            optionType = AppOptionMenuType.ImportConfig,
            onClick = {
                requestImportConfig(libraryItem.appId)
            },
        )
    }

    @Composable
    protected open fun getExportSavesOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        if (!supportsSaveTransfer(libraryItem)) return null
        return AppMenuOption(
            optionType = AppOptionMenuType.ExportSaves,
            onClick = {
                requestExportSaves(libraryItem.appId)
            },
        )
    }

    @Composable
    protected open fun getImportSavesOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        if (!supportsSaveTransfer(libraryItem)) return null
        return AppMenuOption(
            optionType = AppOptionMenuType.ImportSaves,
            onClick = {
                requestImportSaves(libraryItem.appId)
            },
        )
    }

    protected open fun supportsSaveTransfer(libraryItem: LibraryItem): Boolean = false

    protected open suspend fun exportSaves(
        context: Context,
        libraryItem: LibraryItem,
        uri: android.net.Uri,
    ): Boolean = false

    protected open suspend fun importSaves(
        context: Context,
        libraryItem: LibraryItem,
        uri: android.net.Uri,
    ): Boolean = false

    /**
     * Get config-related menu options (e.g. Export config, Import config).
     * By default returns only Export config when supported; sources can override
     * to add Import config or other options so they appear grouped together.
     */
    @Composable
    protected open fun getConfigMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
    ): List<AppMenuOption> {
        val configOptions = if (supportsContainerConfig()) {
            listOfNotNull(
                getUseKnownConfigOption(context, libraryItem),
                getBrowseCommunityConfigsOption(context, libraryItem),
                getExportConfigOption(context, libraryItem),
                getImportConfigOption(context, libraryItem),
            )
        } else {
            emptyList()
        }

        return configOptions + listOfNotNull(
            getExportSavesOption(context, libraryItem),
            getImportSavesOption(context, libraryItem),
        )
    }

    @Composable
    protected open fun getManageModsOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption = AppMenuOption(
        optionType = AppOptionMenuType.ManageMods,
        onClick = {
            requestManageMods(libraryItem.appId)
        },
    )

    protected suspend fun cleanupNexusModsForApp(
        context: Context,
        libraryItem: LibraryItem,
        gameRootDir: File?,
    ) {
        runCatching {
            NexusModManager.deleteInstallsForApp(
                context = context,
                appId = libraryItem.appId,
                gameRootDir = gameRootDir,
            )
        }.onFailure { error ->
            Timber.w(error, "Failed to clean Nexus mods for app %s", libraryItem.appId)
        }
    }

    /**
     * Get Create Shortcut menu option. Subclasses can override to customize behavior.
     */
    @Composable
    protected open fun getCreateShortcutOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        val gameSource = getGameSource(libraryItem)
        val gameId = getGameId(libraryItem)
        val gameName = getGameName(context, libraryItem)
        val iconUrl = getIconUrl(context, libraryItem)

        return AppMenuOption(
            optionType = AppOptionMenuType.CreateShortcut,
            onClick = {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        createPinnedShortcut(
                            context = context,
                            gameId = gameId,
                            label = gameName,
                            gameSource = gameSource,
                            iconUrl = iconUrl,
                        )
                        SnackbarManager.show(context.getString(R.string.base_app_shortcut_created))
                    } catch (e: Exception) {
                        SnackbarManager.show(context.getString(R.string.base_app_shortcut_failed, e.message ?: ""))
                    }
                }
            },
        )
    }

    /**
     * Get source-specific menu options. Subclasses can override to add custom options.
     */
    @Composable
    protected open fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean,
    ): List<AppMenuOption> {
        return emptyList()
    }

    @Composable
    private fun getFavoriteOption(libraryItem: LibraryItem): AppMenuOption {
        val context = LocalContext.current
        val favorites by FavoritesManager.favorites.collectAsStateWithLifecycle()
        val isFavorite = favorites.contains(libraryItem.appId)
        return AppMenuOption(
            optionType = if (isFavorite) {
                AppOptionMenuType.RemoveFromFavorites
            } else {
                AppOptionMenuType.AddToFavorites
            },
            onClick = {
                toggleFavorite(context, libraryItem.appId, libraryItem.name)
            },
        )
    }

    @Composable
    private fun getSubmitFeedbackOption(context: Context, libraryItem: LibraryItem): AppMenuOption {
        return AppMenuOption(
            optionType = AppOptionMenuType.SubmitFeedback,
            onClick = {
                PluviaApp.events.emit(AndroidEvent.ShowGameFeedback(libraryItem.appId))
            },
        )
    }

    @Composable
    private fun getGetSupportOption(context: Context): AppMenuOption {
        return AppMenuOption(
            optionType = AppOptionMenuType.GetSupport,
            onClick = {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    ("https://discord.gg/2hKv4VfZfE").toUri(),
                )
                context.startActivity(browserIntent)
            },
        )
    }

    protected open fun onRunContainerClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
    ) {
        onClickPlay(true)
    }

    protected open fun onTestGraphicsClick(
        context: Context,
        libraryItem: LibraryItem,
        onTestGraphics: () -> Unit,
    ) {
        onTestGraphics()
    }

    /**
     * Get the game folder path for image fetching.
     * Override this in subclasses to provide source-specific path resolution.
     * Default implementation uses getInstallPath() if the game is installed.
     */
    protected open fun getGameFolderPathForImageFetch(context: Context, libraryItem: LibraryItem): String? {
        // Check if installed and get path
        if (isInstalled(context, libraryItem)) {
            return getInstallPath(context, libraryItem)
        }
        return null
    }

    /**
     * Hook called after images are fetched. Override in subclasses for post-processing
     * (e.g., icon extraction for Custom Games).
     */
    protected open fun onAfterFetchImages(context: Context, libraryItem: LibraryItem, gameFolderPath: String) {
        // Default: no post-processing
    }

    /**
     * Reset container to default settings while preserving drive mappings.
     * This is common behavior for all game sources.
     */
    protected fun resetContainerToDefaults(context: Context, libraryItem: LibraryItem) {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        val defaults = ContainerUtils.getDefaultContainerData().copy(drives = container.drives)

        ContainerUtils.applyToContainer(context, libraryItem.appId, defaults)

        SnackbarManager.show("Container reset to defaults")
    }

    /**
     * Shared helper to fetch and apply a "known config" for a given game/library item.
     * Installs any missing manifest components before applying the config.
     */
    protected open suspend fun applyKnownConfigForLibraryItem(
        context: Context,
        libraryItem: LibraryItem,
    ) {
        val gameId = libraryItem.gameId
        val uiScope = CoroutineScope(Dispatchers.Main.immediate)
        try {
            val gameName = ContainerUtils.resolveGameName(libraryItem.appId)
            val gpuName = GPUInformation.getRenderer(context)

            val bestConfig = BestConfigService.fetchBestConfig(
                gameName = gameName,
                gpuName = gpuName,
                gameStore = libraryItem.gameSource.name,
            )
            if (bestConfig == null) {
                SnackbarManager.show(context.getString(R.string.best_config_fetch_failed))
                return
            }
            if (bestConfig.matchType == "no_match") {
                SnackbarManager.show(context.getString(R.string.best_config_no_config_available))
                return
            }

            val installsOk = installMissingComponentsForConfig(
                context = context,
                gameId = gameId,
                configJson = bestConfig.bestConfig,
                matchType = bestConfig.matchType,
                matchedGpu = bestConfig.matchedGpu,
            )
            if (!installsOk) return

            val appId = libraryItem.appId
            val configJson = bestConfig.bestConfig
            val matchType = bestConfig.matchType

            val parsedResult = BestConfigService.parseConfigResult(
                context = context,
                configJson = configJson,
                matchType = matchType,
                applyKnownConfig = true,
                storeMatch = bestConfig.matchedStore.equals(libraryItem.gameSource.name, ignoreCase = true),
                matchedGpu = bestConfig.matchedGpu,
            )
            val parsedConfig = parsedResult.config
            val missingComponents = parsedResult.missingComponents

            if (missingComponents.isNotEmpty()) {
                showMissingComponentsDialog(appId, missingComponents) {
                    // "apply anyway" — re-parse with defaults replacing missing components
                    uiScope.launch(Dispatchers.IO) {
                        try {
                            val forced = BestConfigService.parseConfigToContainerData(
                                context, configJson, matchType, true,
                                storeMatch = bestConfig.matchedStore.equals(libraryItem.gameSource.name, ignoreCase = true),
                                forceApply = true,
                                matchedGpu = bestConfig.matchedGpu,
                            )
                            if (!forced.isNullOrEmpty()) {
                                val c = ContainerUtils.getOrCreateContainer(context, appId)
                                val cd = ContainerUtils.toContainerData(c)
                                val updated = ContainerUtils.applyBestConfigMapToContainerData(cd, forced)
                                ContainerUtils.applyToContainer(context, c, updated)
                                SnackbarManager.show(context.getString(R.string.best_config_applied_with_defaults))
                            } else {
                                SnackbarManager.show(context.getString(R.string.best_config_known_config_invalid))
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to force-apply config: ${e.message}")
                            SnackbarManager.show(context.getString(R.string.best_config_apply_failed, e.message ?: "Unknown error"))
                        }
                    }
                }
            } else if (parsedConfig.isNotEmpty()) {
                val container = ContainerUtils.getOrCreateContainer(context, appId)
                val currentData = ContainerUtils.toContainerData(container)
                val updatedData = ContainerUtils.applyBestConfigMapToContainerData(
                    currentData,
                    parsedConfig,
                )
                ContainerUtils.applyToContainer(context, container, updatedData)
                SnackbarManager.show(context.getString(R.string.best_config_applied_successfully))
            } else {
                SnackbarManager.show(context.getString(R.string.best_config_known_config_invalid))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to apply known config for ${libraryItem.appId}: ${e.message}")
            withContext(Dispatchers.Main) {
                hideKnownConfigInstallState(gameId)
            }
            SnackbarManager.show(
                context.getString(
                    R.string.best_config_apply_failed,
                    e.message ?: "Unknown error",
                ),
            )
        }
    }

    /** Applies a selected community config using the existing validation and dependency installers. */
    protected open suspend fun applyCommunityConfigForLibraryItem(
        context: Context,
        libraryItem: LibraryItem,
        configJson: kotlinx.serialization.json.JsonObject,
        matchType: String,
        matchedGpu: String,
        applyLaunchArguments: Boolean,
        applyEnvironmentVariables: Boolean,
    ): Boolean {
        val appId = libraryItem.appId
        val gameId = libraryItem.gameId
        val uiScope = CoroutineScope(Dispatchers.Main.immediate)
        val safeConfig = prepareCommunityConfigForApply(
            config = configJson,
            applyLaunchArguments = applyLaunchArguments,
            applyEnvironmentVariables = applyEnvironmentVariables,
        )

        if (!isValidCommunityConfig(safeConfig)) {
            SnackbarManager.show(context.getString(R.string.best_config_known_config_invalid))
            return false
        }

        return try {
            val installsOk = installMissingComponentsForConfig(
                context = context,
                gameId = gameId,
                configJson = safeConfig,
                matchType = matchType,
                matchedGpu = matchedGpu,
                preserveConfigValues = true,
            )
            if (!installsOk) return false

            val parsedResult = BestConfigService.parseConfigResult(
                context = context,
                configJson = safeConfig,
                matchType = matchType,
                applyKnownConfig = true,
                storeMatch = false,
                matchedGpu = matchedGpu,
                preserveConfigValues = true,
            )
            val parsedConfig = parsedResult.config
            val missingComponents = parsedResult.missingComponents

            if (missingComponents.isNotEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    showMissingComponentsDialog(appId, missingComponents) {
                        uiScope.launch(Dispatchers.IO) {
                            try {
                                val forced = BestConfigService.parseConfigToContainerData(
                                    context = context,
                                    configJson = safeConfig,
                                    matchType = matchType,
                                    applyKnownConfig = true,
                                    storeMatch = false,
                                    forceApply = true,
                                    matchedGpu = matchedGpu,
                                    preserveConfigValues = true,
                                )
                                if (!forced.isNullOrEmpty()) {
                                    val container = ContainerUtils.getOrCreateContainer(context, appId)
                                    val currentData = ContainerUtils.toContainerData(container)
                                    val updatedData = ContainerUtils.applyBestConfigMapToContainerData(currentData, forced)
                                    ContainerUtils.applyToContainer(context, container, updatedData)
                                    SnackbarManager.show(context.getString(R.string.best_config_applied_with_defaults))
                                } else {
                                    SnackbarManager.show(context.getString(R.string.best_config_known_config_invalid))
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Timber.w(error, "Failed to force-apply community config: ${error.message}")
                                SnackbarManager.show(
                                    context.getString(
                                        R.string.best_config_apply_failed,
                                        error.message ?: "Unknown error",
                                    ),
                                )
                            }
                        }
                    }
                }
                false
            } else if (parsedConfig.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val container = ContainerUtils.getOrCreateContainer(context, appId)
                    val currentData = ContainerUtils.toContainerData(container)
                    val updatedData = ContainerUtils.applyBestConfigMapToContainerData(currentData, parsedConfig)
                    ContainerUtils.applyToContainer(context, container, updatedData)
                }
                SnackbarManager.show(context.getString(R.string.best_config_applied_successfully))
                true
            } else {
                SnackbarManager.show(context.getString(R.string.best_config_known_config_invalid))
                false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            withContext(Dispatchers.Main.immediate) {
                hideKnownConfigInstallState(gameId)
            }
            Timber.w(error, "Failed to apply community config for $appId: ${error.message}")
            SnackbarManager.show(
                context.getString(
                    R.string.best_config_apply_failed,
                    error.message ?: "Unknown error",
                ),
            )
            false
        }
    }

    /**
     * Common reset confirmation dialog for all game sources.
     */
    @Composable
    protected fun ResetConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(context.getString(R.string.base_app_reset_container_title)) },
            text = {
                Text(context.getString(R.string.steam_reset_container_message))
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = context.getString(R.string.base_app_reset_container_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(context.getString(R.string.cancel))
                }
            },
        )
    }

    /**
     * Get the options menu items specific to this game source
     */
    @Composable
    fun getOptionsMenu(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        onTestGraphics: () -> Unit,
        onPlayWithDiagnostics: () -> Unit,
        exportFrontendLauncher: ActivityResultLauncher<String>,
    ): List<AppMenuOption> {
        val isInstalled = isInstalled(context, libraryItem)
        val menuOptions = mutableListOf<AppMenuOption>()

        // Always available: Edit Container
        menuOptions.add(getEditContainerOption(context, libraryItem, onEditContainer))

        if (isInstalled) {
            // Options only available when game is installed
            getRunContainerOption(context, libraryItem, onClickPlay)?.let { menuOptions.add(it) }
            getTestGraphicsOption(context, libraryItem, onTestGraphics)?.let { menuOptions.add(it) }
            getPlayWithDiagnosticsOption(context, libraryItem, onPlayWithDiagnostics)?.let { menuOptions.add(it) }
            getShareDiagnosticsOption(context, libraryItem)?.let { menuOptions.add(it) }
            getResetContainerOption(context, libraryItem)?.let { menuOptions.add(it) }
            getCreateShortcutOption(context, libraryItem)?.let { menuOptions.add(it) }
            getExportContainerOption(context, libraryItem, exportFrontendLauncher)?.let { menuOptions.add(it) }
        }

        // Always available options
        if (!libraryItem.isRecommended) {
            menuOptions.add(getFavoriteOption(libraryItem))
        }
        menuOptions.add(getSubmitFeedbackOption(context, libraryItem))
        menuOptions.add(getGetSupportOption(context))

        // Add any source-specific options
        menuOptions.addAll(getSourceSpecificMenuOptions(context, libraryItem, onEditContainer, onBack, onClickPlay, isInstalled))

        if (isInstalled) {
            menuOptions.add(getManageModsOption(context, libraryItem))
        }

        // Add config-related options (export/import) after source-specific options,
        // so container-related items appear as:
        // Reset Container, Reset DRM, Use Known Config, Export Config, Import Config.
        if (isInstalled) {
            menuOptions.addAll(getConfigMenuOptions(context, libraryItem))
        }

        return menuOptions
    }

    /**
     * Load container data for editing
     */
    abstract fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData

    /**
     * Save container configuration
     */
    abstract fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData)

    /**
     * Get the main content composable for this screen.
     * This uses the common UI layout from AppScreenContent.
     */
    @Composable
    fun Content(
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
        onTestGraphics: () -> Unit,
        onPlayWithDiagnostics: () -> Unit,
        onBack: () -> Unit,
    ) {
        val context = LocalContext.current
        val displayInfoBase = getGameDisplayInfo(context, libraryItem)
        val appId = libraryItem.appId

        // Fetch HLTB stats asynchronously (best-effort)
        var hltbStats by remember(displayInfoBase.name) {
            mutableStateOf<app.gamenative.utils.HltbService.Stats?>(null)
        }
        LaunchedEffect(displayInfoBase.name) {
            if (displayInfoBase.name.isNotBlank())
                hltbStats = try {
                    app.gamenative.utils.HltbService.getStats(displayInfoBase.name)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { null }
        }
        val displayInfo = displayInfoBase.copy(hltbStats = hltbStats)

        // Use composable state for values that change over time
        var isInstalledState by remember(libraryItem.appId) {
            mutableStateOf(isInstalled(context, libraryItem))
        }
        var isValidToDownloadState by remember(libraryItem.appId) {
            mutableStateOf(isValidToDownload(context, libraryItem))
        }
        var isDownloadingState by remember(libraryItem.appId) {
            mutableStateOf(isDownloading(context, libraryItem))
        }
        var downloadProgressState by remember(libraryItem.appId) {
            mutableFloatStateOf(getDownloadProgress(context, libraryItem))
        }
        var isUpdatePendingState by remember(libraryItem.appId) {
            mutableStateOf(false) // Initialize to false, will be updated in LaunchedEffect
        }

        // Calculate hasPartialDownload state
        var hasPartialDownloadState by remember(libraryItem.appId) {
            mutableStateOf(hasPartialDownload(context, libraryItem))
        }
        var hasLeftoverInstallState by remember(libraryItem.appId) {
            mutableStateOf(hasLeftoverInstall(context, libraryItem))
        }

        // Immersive/VR launch mode is only offered on the modernXr build running on Meta Quest.
        val isImmersiveModeSupported = remember(libraryItem.appId) {
            app.gamenative.BuildConfig.MODERN_XR && app.gamenative.MainActivity.isMetaQuest()
        }
        var isImmersiveModeEnabledState by remember(libraryItem.appId) { mutableStateOf<Boolean?>(null) }
        val immersiveModeSaveRequests = remember(libraryItem.appId) { Channel<Boolean>(Channel.CONFLATED) }
        if (isImmersiveModeSupported) {
            LaunchedEffect(libraryItem.appId) {
                val stored = withContext(Dispatchers.IO) {
                    runCatching { ContainerUtils.getContainer(context, libraryItem.appId).isLaunchImmersiveMode() }
                        .getOrDefault(true)
                }
                if (isImmersiveModeEnabledState == null) {
                    isImmersiveModeEnabledState = stored
                }
                for (enabled in immersiveModeSaveRequests) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val container = ContainerUtils.getContainer(context, libraryItem.appId)
                            container.setLaunchImmersiveMode(enabled)
                            container.saveData()
                        }
                    }
                }
            }
        }

        val uiScope = rememberCoroutineScope()

        suspend fun performStateRefresh(includeUpdatePending: Boolean) {
            isInstalledState = isInstalled(context, libraryItem)
            isValidToDownloadState = isValidToDownload(context, libraryItem)
            val currentlyDownloading = isDownloading(context, libraryItem)
            isDownloadingState = currentlyDownloading
            downloadProgressState = getDownloadProgress(context, libraryItem)
            hasPartialDownloadState = hasPartialDownload(context, libraryItem)
            hasLeftoverInstallState = hasLeftoverInstall(context, libraryItem)
            if (includeUpdatePending) {
                isUpdatePendingState = isUpdatePendingSuspend(context, libraryItem)
            }
        }

        fun requestStateRefresh(includeUpdatePending: Boolean) {
            uiScope.launch {
                performStateRefresh(includeUpdatePending)
            }
        }

        LaunchedEffect(libraryItem.appId) {
            performStateRefresh(true)
        }

        var showConfigDialog by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }
        var containerData by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(ContainerData())
        }
        var communityContainerData by remember(appId) {
            mutableStateOf<ContainerData?>(null)
        }

        val onEditContainer: () -> Unit = {
            containerData = loadContainerData(context, libraryItem)
            showConfigDialog = true
        }

        // Export for Frontend launcher
        val exportFrontendLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
            onResult = { uri ->
                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            val content = getGameId(libraryItem).toString()
                            outputStream.write(content.toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                        }
                        SnackbarManager.show(context.getString(R.string.base_app_exported))
                    } catch (e: Exception) {
                        SnackbarManager.show(context.getString(R.string.base_app_export_failed, e.message ?: ""))
                    }
                } else {
                    SnackbarManager.show(context.getString(R.string.base_app_export_cancelled))
                }
            },
        )

        var exportConfigRequested by remember(appId) {
            mutableStateOf(shouldExportConfig(appId))
        }

        LaunchedEffect(appId) {
            snapshotFlow { shouldExportConfig(appId) }
                .collect { shouldRequest ->
                    exportConfigRequested = shouldRequest
                }
        }

        val exportConfigLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                if (uri == null) {
                    clearExportConfigRequest(appId)
                    return@rememberLauncherForActivityResult
                }

                uiScope.launch {
                    try {
                        ContainerConfigTransfer.exportConfig(
                            context = context,
                            appId = appId,
                            uri = uri,
                        )
                    } finally {
                        clearExportConfigRequest(appId)
                    }
                }
            }

        LaunchedEffect(exportConfigRequested) {
            if (exportConfigRequested) {
                val gameName = displayInfo.name.ifBlank { "game" }
                val suggestedFileName = "${gameName}_config.json"
                exportConfigLauncher.launch(suggestedFileName)
            }
        }

        var importConfigRequested by remember(appId) {
            mutableStateOf(shouldImportConfig(appId))
        }

        LaunchedEffect(appId) {
            snapshotFlow { shouldImportConfig(appId) }
                .collect { shouldRequest ->
                    importConfigRequested = shouldRequest
                }
        }

        val importConfigLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri == null) {
                    clearImportConfigRequest(appId)
                    return@rememberLauncherForActivityResult
                }

                uiScope.launch {
                    try {
                        ContainerConfigTransfer.importConfig(
                            context = context,
                            appId = appId,
                            uri = uri,
                            onInstallStateChange = { visible, progress, label ->
                                uiScope.launch(Dispatchers.Main.immediate) {
                                    if (visible) {
                                        showKnownConfigInstallState(
                                            libraryItem.gameId,
                                            KnownConfigInstallState(
                                                visible = true,
                                                progress = progress,
                                                label = label,
                                            ),
                                        )
                                    } else {
                                        hideKnownConfigInstallState(libraryItem.gameId)
                                    }
                                }
                            },
                        )
                    } finally {
                        clearImportConfigRequest(appId)
                    }
                }
            }

        LaunchedEffect(importConfigRequested) {
            if (importConfigRequested) {
                importConfigLauncher.launch(
                    arrayOf("application/json", "text/json", "text/plain"),
                )
            }
        }

        var exportSavesRequested by remember(appId) {
            mutableStateOf(shouldExportSaves(appId))
        }

        LaunchedEffect(appId) {
            snapshotFlow { shouldExportSaves(appId) }
                .collect { shouldRequest ->
                    exportSavesRequested = shouldRequest
                }
        }

        val exportSavesLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                if (uri == null) {
                    clearExportSavesRequest(appId)
                    return@rememberLauncherForActivityResult
                }

                uiScope.launch {
                    try {
                        exportSaves(context, libraryItem, uri)
                    } finally {
                        clearExportSavesRequest(appId)
                    }
                }
            }

        LaunchedEffect(exportSavesRequested) {
            if (exportSavesRequested) {
                val gameName = displayInfo.name.ifBlank { "game" }
                exportSavesLauncher.launch("${gameName}_saves.zip")
            }
        }

        var importSavesRequested by remember(appId) {
            mutableStateOf(shouldImportSaves(appId))
        }

        LaunchedEffect(appId) {
            snapshotFlow { shouldImportSaves(appId) }
                .collect { shouldRequest ->
                    importSavesRequested = shouldRequest
                }
        }

        val importSavesLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri == null) {
                    clearImportSavesRequest(appId)
                    return@rememberLauncherForActivityResult
                }

                uiScope.launch {
                    try {
                        importSaves(context, libraryItem, uri)
                    } finally {
                        clearImportSavesRequest(appId)
                    }
                }
            }

        LaunchedEffect(importSavesRequested) {
            if (importSavesRequested) {
                importSavesLauncher.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                    ),
                )
            }
        }

        var manageModsRequested by remember(appId) {
            mutableStateOf(shouldManageMods(appId))
        }

        LaunchedEffect(appId) {
            snapshotFlow { shouldManageMods(appId) }
                .collect { shouldRequest ->
                    manageModsRequested = shouldRequest
                }
        }

        var communityConfigsRequested by remember(appId) {
            mutableStateOf(shouldBrowseCommunityConfigs(appId))
        }

        LaunchedEffect(appId) {
            snapshotFlow { shouldBrowseCommunityConfigs(appId) }
                .collect { shouldRequest ->
                    communityConfigsRequested = shouldRequest
                }
        }

        val optionsMenu = getOptionsMenu(context, libraryItem, onEditContainer, onBack, onClickPlay, onTestGraphics, onPlayWithDiagnostics, exportFrontendLauncher)

        // Get download info based on game source for progress tracking
        val downloadInfo = when (libraryItem.gameSource) {
            app.gamenative.data.GameSource.STEAM -> app.gamenative.service.SteamService.getAppDownloadInfo(displayInfo.gameId)
            app.gamenative.data.GameSource.EPIC -> app.gamenative.service.epic.EpicService.getDownloadInfo(displayInfo.gameId)
            app.gamenative.data.GameSource.GOG -> app.gamenative.service.gog.GOGService.getDownloadInfo(displayInfo.gameId.toString())
            app.gamenative.data.GameSource.CUSTOM_GAME -> null // Custom games don't support downloads yet
            app.gamenative.data.GameSource.AMAZON -> app.gamenative.service.amazon.AmazonService.getDownloadInfoByAppId(libraryItem.gameId)
        }

        DisposableEffect(libraryItem.appId) {
            val dispose = observeGameState(
                context = context,
                libraryItem = libraryItem,
                onStateChanged = { requestStateRefresh(true) },
                onProgressChanged = { progress ->
                    uiScope.launch {
                        downloadProgressState = progress
                    }
                },
                onHasPartialDownloadChanged = { hasPartial ->
                    hasPartialDownloadState = hasPartial
                },
            )
            onDispose {
                dispose?.invoke()
            }
        }

        val launchActivity = context as? android.app.Activity
        var showReadiness by remember { mutableStateOf(false) }

        // Render the common UI
        app.gamenative.ui.screen.library.AppScreenContent(
            displayInfo = displayInfo,
            isInstalled = isInstalledState,
            isValidToDownload = isValidToDownloadState,
            isDownloading = isDownloadingState,
            downloadProgress = downloadProgressState,
            hasPartialDownload = hasPartialDownloadState,
            hasLeftoverInstall = hasLeftoverInstallState,
            isUpdatePending = isUpdatePendingState,
            downloadInfo = downloadInfo,
            immersiveMode = app.gamenative.ui.screen.library.ImmersiveModeUiState(
                isSupported = isImmersiveModeSupported && isImmersiveModeEnabledState != null,
                isEnabled = isImmersiveModeEnabledState == true,
                onChange = { enabled ->
                    isImmersiveModeEnabledState = enabled
                    immersiveModeSaveRequests.trySend(enabled)
                },
            ),
            onDownloadInstallClick = {
                if (app.gamenative.launch.LaunchReadiness.pending) {
                    showReadiness = true
                } else {
                    onDownloadInstallClick(context, libraryItem, onClickPlay)
                    uiScope.launch {
                        delay(100)
                        performStateRefresh(true)
                    }
                }
            },
            onPauseResumeClick = {
                isDownloadingState = !isDownloadingState
                onPauseResumeClick(context, libraryItem)
            },
            onDeleteDownloadClick = {
                onDeleteDownloadClick(context, libraryItem)
            },
            onUpdateClick = {
                onUpdateClick(context, libraryItem)
                uiScope.launch {
                    performStateRefresh(true)
                }
            },
            onBack = onBack,
            optionsMenu = optionsMenu,
            dialogOpen = showConfigDialog || communityConfigsRequested || manageModsRequested,
        )

        if (showReadiness && launchActivity != null) {
            app.gamenative.launch.LaunchReadiness.Prompt(launchActivity) {
                showReadiness = false
                if (!app.gamenative.launch.LaunchReadiness.pending) {
                    onDownloadInstallClick(context, libraryItem, onClickPlay)
                    uiScope.launch {
                        delay(100)
                        performStateRefresh(true)
                    }
                }
            }
        }

        // Show container config dialog if needed
        if (showConfigDialog) {
            ContainerConfigDialog(
                title = "${displayInfo.name} Config",
                initialConfig = containerData,
                onDismissRequest = { showConfigDialog = false },
                onSave = {
                    saveContainerConfig(context, libraryItem, it)
                    showConfigDialog = false
                },
            )
        }

        LaunchedEffect(appId, communityConfigsRequested) {
            communityContainerData = if (communityConfigsRequested) {
                withContext(Dispatchers.IO) {
                    loadContainerData(context, libraryItem)
                }
            } else {
                null
            }
        }

        if (communityConfigsRequested) {
            val currentContainerData = communityContainerData
            if (currentContainerData == null) {
                LoadingDialog(
                    visible = true,
                    onDismissRequest = { clearCommunityConfigsRequest(appId) },
                    progress = -1f,
                    message = stringResource(R.string.working),
                )
            } else {
                CommunityConfigsDialog(
                    visible = true,
                    gameName = displayInfo.name,
                    currentLaunchArguments = currentContainerData.execArgs,
                    currentEnvironmentVariables = currentContainerData.envVars,
                    onDismissRequest = { clearCommunityConfigsRequest(appId) },
                    onApply = { run, matchType, options ->
                        clearCommunityConfigsRequest(appId)
                        uiScope.launch(Dispatchers.IO) {
                            applyCommunityConfigForLibraryItem(
                                context = context,
                                libraryItem = libraryItem,
                                configJson = run.config,
                                matchType = matchType,
                                matchedGpu = run.device.gpu,
                                applyLaunchArguments = options.applyLaunchArguments,
                                applyEnvironmentVariables = options.applyEnvironmentVariables,
                            )
                        }
                    },
                )
            }
        }

        if (manageModsRequested) {
            NexusModsDialog(
                visible = true,
                libraryItem = libraryItem,
                gameRootDir = getInstallPath(context, libraryItem)?.let { File(it) },
                winePrefix = ModContainerResolver.getWinePrefix(context, libraryItem.appId),
                onDismissRequest = {
                    clearManageModsRequest(appId)
                },
            )
        }

        val gameId = libraryItem.gameId
        var knownConfigInstallState by remember(gameId) {
            mutableStateOf(getKnownConfigInstallState(gameId) ?: KnownConfigInstallState(false, -1f, ""))
        }

        LaunchedEffect(gameId) {
            snapshotFlow { getKnownConfigInstallState(gameId) }
                .collect { state ->
                    knownConfigInstallState = state ?: KnownConfigInstallState(false, -1f, "")
                }
        }

        LoadingDialog(
            visible = knownConfigInstallState.visible,
            progress = knownConfigInstallState.progress,
            message = if (knownConfigInstallState.label.isNotEmpty()) {
                context.getString(R.string.manifest_downloading_item, knownConfigInstallState.label)
            } else {
                context.getString(R.string.working)
            },
        )

        // missing components dialog — shown when config can't be applied
        val missingState = getMissingComponentsState(appId)
        if (missingState != null) {
            AlertDialog(
                onDismissRequest = {
                    hideMissingComponentsDialog(appId)
                },
                title = { Text(stringResource(R.string.best_config_missing_components_title)) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.best_config_missing_components_message,
                            missingState.components.joinToString("\n"),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        hideMissingComponentsDialog(appId)
                    }) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = if (missingState.onApplyAnyway != null) {
                    {
                        TextButton(onClick = {
                            hideMissingComponentsDialog(appId)
                            missingState.onApplyAnyway.invoke()
                        }) {
                            Text(stringResource(R.string.best_config_apply_anyway))
                        }
                    }
                } else {
                    null
                },
            )
        }

        // Render any additional dialogs
        AdditionalDialogs(libraryItem, onDismiss = {}, onEditContainer = onEditContainer, onBack = onBack)
    }

    /**
     * Check if container configuration editing is supported
     */
    abstract fun supportsContainerConfig(): Boolean

    /**
     * Observe download/install state changes for this app.
     * Return a lambda that will be invoked to clean up observers.
     */
    protected open fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)? = null,
    ): (() -> Unit)? {
        return null
    }

    /**
     * Get additional dialogs to show (e.g., loading, message dialogs).
     * Override this to add source-specific dialogs.
     */
    @Composable
    open fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
    ) {
        // Default: no additional dialogs
    }
}
