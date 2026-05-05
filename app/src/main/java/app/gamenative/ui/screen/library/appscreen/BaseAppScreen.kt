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
import androidx.core.net.toUri
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.util.ContainerConfigTransfer
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.createPinnedShortcut
import kotlinx.coroutines.CancellationException
import com.winlator.container.ContainerData
import com.winlator.core.GPUInformation
import java.io.File
import kotlin.text.Charsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    uiScope: CoroutineScope,
): Boolean {
    val missingRequests = BestConfigService.resolveMissingManifestInstallRequests(
        context,
        configJson,
        matchType,
    )
    if (missingRequests.isEmpty()) return true

    uiScope.launch(Dispatchers.Main.immediate) {
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
        uiScope.launch(Dispatchers.Main.immediate) {
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
                uiScope.launch(Dispatchers.Main.immediate) {
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
        if (!result.success) {
            uiScope.launch(Dispatchers.Main.immediate) { BaseAppScreen.hideKnownConfigInstallState(gameId) }
            return false
        }
    }

    uiScope.launch(Dispatchers.Main.immediate) { BaseAppScreen.hideKnownConfigInstallState(gameId) }
    return true
}

abstract class BaseAppScreen {
    companion object {
        private val installDialogStates = mutableStateMapOf<String, app.gamenative.ui.component.dialog.state.MessageDialogState>()
        private val exportConfigRequests = mutableStateMapOf<String, Boolean>()
        private val importConfigRequests = mutableStateMapOf<String, Boolean>()
        private val exportSavesRequests = mutableStateMapOf<String, Boolean>()
        private val importSavesRequests = mutableStateMapOf<String, Boolean>()
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
                uiScope = uiScope,
            )
            if (!installsOk) return

            val appId = libraryItem.appId
            val configJson = bestConfig.bestConfig
            val matchType = bestConfig.matchType

            val parsedConfig = BestConfigService.parseConfigToContainerData(
                context = context,
                configJson = configJson,
                matchType = matchType,
                applyKnownConfig = true,
                storeMatch = bestConfig.matchedStore.equals(libraryItem.gameSource.name, ignoreCase = true),
            )
            val missingComponents = BestConfigService.consumeLastMissingComponents()

            if (missingComponents.isNotEmpty()) {
                showMissingComponentsDialog(appId, missingComponents) {
                    // "apply anyway" — re-parse with defaults replacing missing components
                    uiScope.launch(Dispatchers.IO) {
                        try {
                            val forced = BestConfigService.parseConfigToContainerData(
                                context, configJson, matchType, true,
                                storeMatch = bestConfig.matchedStore.equals(libraryItem.gameSource.name, ignoreCase = true),
                                forceApply = true,
                            )
                            if (forced != null && forced.isNotEmpty()) {
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
            } else if (parsedConfig != null && parsedConfig.isNotEmpty()) {
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
            getResetContainerOption(context, libraryItem)?.let { menuOptions.add(it) }
            getCreateShortcutOption(context, libraryItem)?.let { menuOptions.add(it) }
            getExportContainerOption(context, libraryItem, exportFrontendLauncher)?.let { menuOptions.add(it) }
        }

        // Always available options
        menuOptions.add(getSubmitFeedbackOption(context, libraryItem))
        menuOptions.add(getGetSupportOption(context))

        // Add any source-specific options
        menuOptions.addAll(getSourceSpecificMenuOptions(context, libraryItem, onEditContainer, onBack, onClickPlay, isInstalled))

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
        onBack: () -> Unit,
    ) {
        val context = LocalContext.current
        val displayInfo = getGameDisplayInfo(context, libraryItem)
        val appId = libraryItem.appId

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

        val uiScope = rememberCoroutineScope()

        suspend fun performStateRefresh(includeUpdatePending: Boolean) {
            isInstalledState = isInstalled(context, libraryItem)
            isValidToDownloadState = isValidToDownload(context, libraryItem)
            val currentlyDownloading = isDownloading(context, libraryItem)
            isDownloadingState = currentlyDownloading
            downloadProgressState = getDownloadProgress(context, libraryItem)
            hasPartialDownloadState = hasPartialDownload(context, libraryItem)
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

        val optionsMenu = getOptionsMenu(context, libraryItem, onEditContainer, onBack, onClickPlay, onTestGraphics, exportFrontendLauncher)

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

        // Render the common UI
        app.gamenative.ui.screen.library.AppScreenContent(
            displayInfo = displayInfo,
            isInstalled = isInstalledState,
            isValidToDownload = isValidToDownloadState,
            isDownloading = isDownloadingState,
            downloadProgress = downloadProgressState,
            hasPartialDownload = hasPartialDownloadState,
            isUpdatePending = isUpdatePendingState,
            downloadInfo = downloadInfo,
            onDownloadInstallClick = {
                onDownloadInstallClick(context, libraryItem, onClickPlay)
                uiScope.launch {
                    delay(100)
                    performStateRefresh(true)
                }
            },
            onPauseResumeClick = {
                onPauseResumeClick(context, libraryItem)
                uiScope.launch {
                    delay(100)
                    performStateRefresh(false)
                }
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
            optionsMenu = optionsMenu.toTypedArray(),
        )

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
