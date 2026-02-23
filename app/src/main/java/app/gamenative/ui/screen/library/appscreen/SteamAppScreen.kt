package app.gamenative.ui.screen.library.appscreen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.enums.Marker
import app.gamenative.enums.PathType
import app.gamenative.enums.SyncResult
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.getAppDirPath
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.screen.library.GameMigrationDialog
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.StorageUtils
import app.gamenative.utils.SteamUtils
import com.posthog.PostHog
import com.google.android.play.core.splitcompat.SplitCompat
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.fexcore.FEXCoreManager
import com.winlator.xenvironment.ImageFsInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import app.gamenative.ui.component.dialog.GameManagerDialog
import app.gamenative.ui.component.dialog.state.GameManagerDialogState
import app.gamenative.utils.ContainerUtils.getContainer
import com.winlator.core.GPUInformation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.pathString

private data class InstallSizeInfo(
    val downloadSize: String,
    val installSize: String,
    val availableSpace: String,
    val installBytes: Long,
    val availableBytes: Long,
)

data class KnownConfigInstallState(
    val visible: Boolean,
    val progress: Float,
    val label: String,
)

private suspend fun installMissingComponentsForConfig(
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
        SteamAppScreen.showKnownConfigInstallState(
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
            SteamAppScreen.showKnownConfigInstallState(
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
                    SteamAppScreen.showKnownConfigInstallState(
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
        withContext(Dispatchers.Main) {
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
        if (!result.success) {
            uiScope.launch(Dispatchers.Main.immediate) { SteamAppScreen.hideKnownConfigInstallState(gameId) }
            return false
        }
    }

    uiScope.launch(Dispatchers.Main.immediate) { SteamAppScreen.hideKnownConfigInstallState(gameId) }
    return true
}

private suspend fun applyConfigForContainer(
    context: Context,
    gameId: Int,
    appId: String,
    configJson: kotlinx.serialization.json.JsonObject,
    matchType: String,
    uiScope: CoroutineScope,
): Boolean {
    return try {
        val installsOk = installMissingComponentsForConfig(
            context,
            gameId,
            configJson,
            matchType,
            uiScope,
        )
        if (!installsOk) return false

        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val containerData = ContainerUtils.toContainerData(container)
        val parsedConfig = BestConfigService.parseConfigToContainerData(
            context,
            configJson,
            matchType,
            true,
        )
        val missingContentDescription = BestConfigService.consumeLastMissingContentDescription()
        if (parsedConfig != null && parsedConfig.isNotEmpty()) {
            val updatedContainerData = ContainerUtils.applyBestConfigMapToContainerData(
                containerData,
                parsedConfig,
            )
            ContainerUtils.applyToContainer(context, container, updatedContainerData)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.best_config_applied_successfully),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } else {
            withContext(Dispatchers.Main) {
                val message = if (missingContentDescription != null) {
                    context.getString(R.string.best_config_missing_content, missingContentDescription)
                } else {
                    context.getString(R.string.best_config_known_config_invalid)
                }
                Toast.makeText(
                    context,
                    message,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        true
    } catch (e: Exception) {
        Timber.w(e, "Failed to apply config: ${e.message}")
        withContext(Dispatchers.Main) {
            SteamAppScreen.hideKnownConfigInstallState(gameId)
            Toast.makeText(
                context,
                context.getString(R.string.best_config_apply_failed, e.message ?: "Unknown error"),
                Toast.LENGTH_SHORT,
            ).show()
        }
        false
    }
}

private fun buildInstallPromptState(context: Context, info: InstallSizeInfo): MessageDialogState {
    val message = context.getString(
        R.string.steam_install_space_prompt,
        info.downloadSize,
        info.installSize,
        info.availableSpace
    )
    return MessageDialogState(
        visible = true,
        type = DialogType.INSTALL_APP,
        title = context.getString(R.string.download_prompt_title),
        message = message,
        confirmBtnText = context.getString(R.string.proceed),
        dismissBtnText = context.getString(R.string.cancel),
    )
}

private fun buildNotEnoughSpaceState(context: Context, info: InstallSizeInfo): MessageDialogState {
    val message = context.getString(
        R.string.steam_install_not_enough_space,
        info.installSize,
        info.availableSpace
    )
    return MessageDialogState(
        visible = true,
        type = DialogType.NOT_ENOUGH_SPACE,
        title = context.getString(R.string.not_enough_space),
        message = message,
        confirmBtnText = context.getString(R.string.acknowledge),
    )
}

/**
 * Steam-specific implementation of BaseAppScreen
 */
class SteamAppScreen : BaseAppScreen() {
    companion object {
        // Shared state for uninstall dialog - list of appIds that should show the dialog
        private val uninstallDialogAppIds = mutableStateListOf<String>()

        fun showUninstallDialog(appId: String) {
            if (!uninstallDialogAppIds.contains(appId)) {
                uninstallDialogAppIds.add(appId)
            }
        }

        fun hideUninstallDialog(appId: String) {
            uninstallDialogAppIds.remove(appId)
        }

        fun shouldShowUninstallDialog(appId: String): Boolean {
            return uninstallDialogAppIds.contains(appId)
        }

        // Shared state for install dialog - map of gameId to MessageDialogState
        private val installDialogStates = mutableStateMapOf<Int, MessageDialogState>()

        fun showInstallDialog(gameId: Int, state: MessageDialogState) {
            installDialogStates[gameId] = state
        }

        fun hideInstallDialog(gameId: Int) {
            installDialogStates.remove(gameId)
        }

        fun getInstallDialogState(gameId: Int): MessageDialogState? {
            return installDialogStates[gameId]
        }

        private val knownConfigInstallStates = mutableStateMapOf<Int, KnownConfigInstallState>()

        fun showKnownConfigInstallState(gameId: Int, state: KnownConfigInstallState) {
            knownConfigInstallStates[gameId] = state
        }

        fun hideKnownConfigInstallState(gameId: Int) {
            knownConfigInstallStates.remove(gameId)
        }

        fun getKnownConfigInstallState(gameId: Int): KnownConfigInstallState? {
            return knownConfigInstallStates[gameId]
        }

        private val importConfigRequests = mutableStateMapOf<Int, Boolean>()

        fun requestImportConfig(gameId: Int) {
            importConfigRequests[gameId] = true
        }

        fun clearImportConfigRequest(gameId: Int) {
            importConfigRequests.remove(gameId)
        }

        fun shouldImportConfig(gameId: Int): Boolean {
            return importConfigRequests[gameId] == true
        }

        private val exportConfigRequests = mutableStateMapOf<Int, Boolean>()

        fun requestExportConfig(gameId: Int) {
            exportConfigRequests[gameId] = true
        }

        fun clearExportConfigRequest(gameId: Int) {
            exportConfigRequests.remove(gameId)
        }

        fun shouldExportConfig(gameId: Int): Boolean {
            return exportConfigRequests[gameId] == true
        }

        private val gameManagerDialogStates = mutableStateMapOf<Int, GameManagerDialogState>()

        fun showGameManagerDialog(gameId: Int, state: GameManagerDialogState) {
            gameManagerDialogStates[gameId] = state
        }

        fun hideGameManagerDialog(gameId: Int) {
            gameManagerDialogStates.remove(gameId)
        }

        fun getGameManagerDialogState(gameId: Int): GameManagerDialogState? {
            return gameManagerDialogStates[gameId]
        }

        // Shared state for update/verify operation - map of gameId to AppOptionMenuType
        private val pendingUpdateVerifyOperations = mutableStateMapOf<Int, AppOptionMenuType>()

        fun setPendingUpdateVerifyOperation(gameId: Int, operation: AppOptionMenuType?) {
            if (operation != null) {
                pendingUpdateVerifyOperations[gameId] = operation
            } else {
                pendingUpdateVerifyOperations.remove(gameId)
            }
        }

        fun getPendingUpdateVerifyOperation(gameId: Int): AppOptionMenuType? {
            return pendingUpdateVerifyOperations[gameId]
        }
    }
    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
    ): GameDisplayInfo {
        val gameId = libraryItem.gameId
        val appInfo = remember(libraryItem.appId) {
            SteamService.getAppInfoOf(gameId)
        } ?: return GameDisplayInfo(
            name = libraryItem.name,
            developer = "",
            releaseDate = 0L,
            heroImageUrl = null,
            iconUrl = null,
            gameId = gameId,
            appId = libraryItem.appId,
        )

        var isInstalled by remember(libraryItem.appId) {
            mutableStateOf(SteamService.isAppInstalled(gameId))
        }

        DisposableEffect(gameId) {
            val listener: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.appId == gameId) {
                    isInstalled = SteamService.isAppInstalled(gameId)
                }
            }
            PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(listener)
            onDispose {
                PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(listener)
            }
        }

        // Get hero image URL
        val heroImageUrl = remember(appInfo.id) {
            appInfo.getHeroUrl()
        }

        // Get icon URL
        val iconUrl = remember(appInfo.id) {
            appInfo.iconUrl
        }

        // Get install location
        val installLocation = remember(isInstalled, gameId) {
            if (isInstalled) {
                getAppDirPath(gameId)
            } else null
        }

        // Get size on disk (async, will update via state)
        var sizeOnDisk by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(isInstalled, gameId) {
            if (isInstalled) {
                DownloadService.getSizeOnDiskDisplay(gameId) {
                    sizeOnDisk = it
                }
            } else {
                sizeOnDisk = null
            }
        }

        // Get size from store (async, will update via state)
        var sizeFromStore by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(isInstalled, gameId) {
            if (!isInstalled) {
                // Load size from store asynchronously to avoid blocking UI
                withContext(Dispatchers.IO) {
                    val size = DownloadService.getSizeFromStoreDisplay(gameId)
                    sizeFromStore = size
                }
            } else {
                sizeFromStore = null
            }
        }

        // Get last played text
        val lastPlayedText = remember(isInstalled, gameId) {
            if (isInstalled) {
                val path = getAppDirPath(gameId)
                val file = File(path)
                if (file.exists()) {
                    SteamUtils.fromSteamTime((file.lastModified() / 1000).toInt())
                } else {
                    context.getString(R.string.steam_never)
                }
            } else {
                context.getString(R.string.steam_never)
            }
        }

        // Get playtime text
        var playtimeText by remember { mutableStateOf("0 hrs") }
        LaunchedEffect(gameId) {
            val steamID = SteamService.userSteamId?.accountID?.toLong()
            if (steamID != null) {
                val games = SteamService.getOwnedGames(steamID)
                val game = games.firstOrNull { it.appId == gameId }
                playtimeText = if (game != null) {
                    SteamUtils.formatPlayTime(game.playtimeForever) + " hrs"
                } else "0 hrs"
            }
        }

        // Fetch compatibility info from cache
        var compatibilityMessage by remember { mutableStateOf<String?>(null) }
        var compatibilityColor by remember { mutableStateOf<ULong?>(null) }
        LaunchedEffect(isInstalled, gameId, appInfo.name) {
            try {
                val cachedResponse = GameCompatibilityCache.getCached(appInfo.name)
                if (cachedResponse != null) {
                    val message = GameCompatibilityService.getCompatibilityMessageFromResponse(context, cachedResponse)
                    compatibilityMessage = message.text
                    compatibilityColor = message.color.value
                } else {
                    compatibilityMessage = null
                    compatibilityColor = null
                }
            } catch (e: Exception) {
                Timber.tag("SteamAppScreen").e(e, "Failed to get compatibility from cache")
                compatibilityMessage = null
                compatibilityColor = null
            }
        }

        return GameDisplayInfo(
            name = appInfo.name,
            developer = appInfo.developer,
            releaseDate = appInfo.releaseDate,
            heroImageUrl = heroImageUrl,
            iconUrl = iconUrl,
            gameId = gameId,
            appId = libraryItem.appId,
            installLocation = installLocation,
            sizeOnDisk = sizeOnDisk,
            sizeFromStore = sizeFromStore,
            lastPlayedText = lastPlayedText,
            playtimeText = playtimeText,
            compatibilityMessage = compatibilityMessage,
            compatibilityColor = compatibilityColor,
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        return SteamService.isAppInstalled(libraryItem.gameId)
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        val appInfo = SteamService.getAppInfoOf(libraryItem.gameId) ?: return false
        return appInfo.branches.isNotEmpty() && appInfo.depots.isNotEmpty()
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        val downloadInfo = SteamService.getAppDownloadInfo(libraryItem.gameId)
        return downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        val downloadInfo = SteamService.getAppDownloadInfo(libraryItem.gameId)
        return downloadInfo?.getProgress() ?: 0f
    }

    override fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean {
        // Use Steam's more accurate check that looks for marker files
        return SteamService.hasPartialDownload(libraryItem.gameId)
    }

    override fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)?
    ): (() -> Unit)? {
        val appId = libraryItem.gameId
        val disposables = mutableListOf<() -> Unit>()

        var progressDisposer = attachDownloadProgressListener(appId, onProgressChanged)

        val installListener: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
            if (event.appId == appId) {
                onStateChanged()
            }
        }
        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        disposables += { PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener) }

        val downloadStatusListener: (AndroidEvent.DownloadStatusChanged) -> Unit = { event ->
            if (event.appId == appId) {
                if (event.isDownloading) {
                    progressDisposer?.invoke()
                    progressDisposer = attachDownloadProgressListener(appId, onProgressChanged)
                    onHasPartialDownloadChanged?.invoke(true)
                } else {
                    progressDisposer?.invoke()
                    progressDisposer = null
                    if (SteamService.isAppInstalled(appId)) {
                        onHasPartialDownloadChanged?.invoke(false)
                    }
                }
                onStateChanged()
            }
        }
        PluviaApp.events.on<AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener)
        disposables += { PluviaApp.events.off<AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener) }

        val connectivityListener: (AndroidEvent.DownloadPausedDueToConnectivity) -> Unit = { event ->
            if (event.appId == appId) {
                onStateChanged()
            }
        }
        PluviaApp.events.on<AndroidEvent.DownloadPausedDueToConnectivity, Unit>(connectivityListener)
        disposables += { PluviaApp.events.off<AndroidEvent.DownloadPausedDueToConnectivity, Unit>(connectivityListener) }

        return {
            progressDisposer?.invoke()
            disposables.forEach { it() }
        }
    }

    private fun attachDownloadProgressListener(
        appId: Int,
        onProgressChanged: (Float) -> Unit
    ): (() -> Unit)? {
        val downloadInfo = SteamService.getAppDownloadInfo(appId) ?: return null
        val listener: (Float) -> Unit = { progress ->
            onProgressChanged(progress)
        }
        downloadInfo.addProgressListener(listener)
        onProgressChanged(downloadInfo.getProgress())
        return { downloadInfo.removeProgressListener(listener) }
    }

    override suspend fun isUpdatePendingSuspend(context: Context, libraryItem: LibraryItem): Boolean {
        return SteamService.isUpdatePending(libraryItem.gameId)
    }

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        // Only return path if game is installed
        if (isInstalled(context, libraryItem)) {
            return getAppDirPath(libraryItem.gameId)
        }
        return null
    }

    override fun onRunContainerClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit
    ) {
        val gameId = libraryItem.gameId
        val appInfo = SteamService.getAppInfoOf(gameId)
        PostHog.capture(
            event = "container_opened",
            properties = mapOf("game_name" to (appInfo?.name ?: ""))
        )
        super.onRunContainerClick(context, libraryItem, onClickPlay)
    }

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit
    ) {
        val gameId = libraryItem.gameId
        val downloadInfo = SteamService.getAppDownloadInfo(gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val isInstalled = SteamService.isAppInstalled(gameId)

        if (isDownloading) {
            // Show cancel download dialog
            showInstallDialog(
                gameId,
                MessageDialogState(
                    visible = true,
                    type = DialogType.CANCEL_APP_DOWNLOAD,
                    title = context.getString(R.string.cancel_download_prompt_title),
                    message = context.getString(R.string.steam_cancel_download_message),
                    confirmBtnText = context.getString(R.string.yes),
                    dismissBtnText = context.getString(R.string.no),
                )
            )
        } else if (SteamService.hasPartialDownload(gameId)) {
            // Resume incomplete download
            CoroutineScope(Dispatchers.IO).launch {
                SteamService.downloadApp(gameId)
            }
        } else if (!isInstalled) {
            // Request storage permissions first, then show install dialog
            // This will be handled by the permission launcher in AdditionalDialogs
            showGameManagerDialog(
                gameId,
                GameManagerDialogState(
                    visible = true
                )
            )
        } else {
            // Already installed: launch app
            val appInfo = SteamService.getAppInfoOf(gameId)
            PostHog.capture(
                event = "game_launched",
                properties = mapOf("game_name" to (appInfo?.name ?: ""))
            )
            onClickPlay(false)
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        val gameId = libraryItem.gameId
        val downloadInfo = SteamService.getAppDownloadInfo(gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f

        if (isDownloading) {
            downloadInfo?.cancel()
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                SteamService.downloadApp(gameId)
            }
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        val gameId = libraryItem.gameId
        val isInstalled = SteamService.isAppInstalled(gameId)
        val downloadInfo = SteamService.getAppDownloadInfo(gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f

        if (isDownloading || SteamService.hasPartialDownload(gameId)) {
            // Show cancel download dialog when downloading
            showInstallDialog(
                gameId,
                MessageDialogState(
                    visible = true,
                    type = DialogType.CANCEL_APP_DOWNLOAD,
                    title = context.getString(R.string.cancel_download_prompt_title),
                    message = context.getString(R.string.steam_delete_download_message),
                    confirmBtnText = context.getString(R.string.yes),
                    dismissBtnText = context.getString(R.string.no)
                )
            )
        } else if (isInstalled) {
            // Show uninstall dialog when installed
            showUninstallDialog(libraryItem.appId)
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        CoroutineScope(Dispatchers.IO).launch {
            SteamService.downloadApp(libraryItem.gameId)
        }
    }

    /**
     * Override Edit Container to check for ImageFS installation first
     */
    @Composable
    override fun getEditContainerOption(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit
    ): AppMenuOption {
        val gameId = libraryItem.gameId
        val appId = libraryItem.appId

        return AppMenuOption(
            optionType = AppOptionMenuType.EditContainer,
            onClick = {
                val container = ContainerUtils.getOrCreateContainer(context, appId)
                val variant = container.containerVariant

                if (!SteamService.isImageFsInstalled(context)) {
                    if (!SteamService.isImageFsInstallable(context, variant)) {
                        showInstallDialog(
                            gameId,
                            MessageDialogState(
                                visible = true,
                                type = DialogType.INSTALL_IMAGEFS,
                                title = context.getString(R.string.steam_imagefs_download_install_title),
                                message = context.getString(R.string.steam_imagefs_download_install_message),
                                confirmBtnText = context.getString(R.string.proceed),
                                dismissBtnText = context.getString(R.string.cancel),
                            )
                        )
                    } else {
                        showInstallDialog(
                            gameId,
                            MessageDialogState(
                                visible = true,
                                type = DialogType.INSTALL_IMAGEFS,
                                title = context.getString(R.string.steam_imagefs_install_title),
                                message = context.getString(R.string.steam_imagefs_install_message),
                                confirmBtnText = context.getString(R.string.proceed),
                                dismissBtnText = context.getString(R.string.cancel),
                            )
                        )
                    }
                } else {
                    onEditContainer()
                }
            }
        )
    }

    /**
     * Override Reset Container to show confirmation dialog
     */
    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem
    ): AppMenuOption {
        val gameId = libraryItem.gameId
        var showResetConfirmDialog by remember { mutableStateOf(false) }

        if (showResetConfirmDialog) {
            ResetConfirmDialog(
                onConfirm = {
                    showResetConfirmDialog = false
                    resetContainerToDefaults(context, libraryItem)
                },
                onDismiss = { showResetConfirmDialog = false }
            )
        }

        return AppMenuOption(
            AppOptionMenuType.ResetToDefaults,
            onClick = { showResetConfirmDialog = true }
        )
    }

    /**
     * Add Steam-specific menu options (Reset DRM, Verify Files, Update)
     */
    @Composable
    override fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean
    ): List<AppMenuOption> {
        val gameId = libraryItem.gameId
        val appId = libraryItem.appId
        val appInfo = SteamService.getAppInfoOf(gameId) ?: return emptyList()
        val isDownloadInProgress = SteamService.getDownloadingAppInfoOf(gameId) != null

        if (!isInstalled || isDownloadInProgress) {
            return emptyList()
        }

        val scope = rememberCoroutineScope()

        // Steam-specific options (only when installed)
        return listOf(
            AppMenuOption(
                AppOptionMenuType.ResetDrm,
                onClick = {
                    val container = ContainerUtils.getOrCreateContainer(context, appId)
                    MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_DLL_REPLACED)
                    MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_DLL_RESTORED)
                    MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_COLDCLIENT_USED)
                    container.isNeedsUnpacking = true
                    container.saveData()
                },
            ),
            AppMenuOption(
                AppOptionMenuType.ManageGameContent,
                onClick = {
                    showGameManagerDialog(
                        gameId,
                        GameManagerDialogState(
                            visible = true,
                        )
                    )
                }
            ),
            AppMenuOption(
                AppOptionMenuType.VerifyFiles,
                onClick = {
                    // Show confirmation dialog before verifying
                    setPendingUpdateVerifyOperation(gameId, AppOptionMenuType.VerifyFiles)
                    showInstallDialog(
                        gameId,
                        MessageDialogState(
                            visible = true,
                            type = DialogType.UPDATE_VERIFY_CONFIRM,
                            title = context.getString(R.string.steam_verify_files_title),
                            message = context.getString(R.string.steam_verify_files_message),
                            confirmBtnText = context.getString(R.string.steam_continue),
                            dismissBtnText = context.getString(R.string.cancel),
                        )
                    )
                },
            ),
            AppMenuOption(
                AppOptionMenuType.Update,
                onClick = {
                    // Show confirmation dialog before updating
                    setPendingUpdateVerifyOperation(gameId, AppOptionMenuType.Update)
                    showInstallDialog(
                        gameId,
                        MessageDialogState(
                            visible = true,
                            type = DialogType.UPDATE_VERIFY_CONFIRM,
                            title = context.getString(R.string.steam_update_title),
                            message = context.getString(R.string.steam_update_message),
                            confirmBtnText = context.getString(R.string.steam_continue),
                            dismissBtnText = context.getString(R.string.cancel),
                        )
                    )
                },
            ),
            // Uninstall option removed from menu - now handled by delete button next to play button
            // The button uses onDeleteDownloadClick which shows the uninstall dialog
            AppMenuOption(
                AppOptionMenuType.ForceCloudSync,
                onClick = {
                    PostHog.capture(
                        event = "cloud_sync_forced",
                        properties = mapOf("game_name" to appInfo.name)
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        val steamId = SteamService.userSteamId
                        if (steamId == null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.steam_not_logged_in),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@launch
                        }

                        val containerManager = ContainerManager(context)
                        val container = ContainerUtils.getOrCreateContainer(context, appId)
                        containerManager.activateContainer(container)

                        val prefixToPath: (String) -> String = { prefix ->
                            PathType.from(prefix).toAbsPath(context, gameId, steamId.accountID)
                        }
                        val syncResult = SteamService.forceSyncUserFiles(
                            appId = gameId,
                            prefixToPath = prefixToPath
                        ).await()

                        withContext(Dispatchers.Main) {
                            when (syncResult.syncResult) {
                                SyncResult.Success -> {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.steam_cloud_sync_success),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                SyncResult.UpToDate -> {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.steam_cloud_sync_up_to_date),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                else -> {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.steam_cloud_sync_failed,
                                            syncResult.syncResult
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                }
            ),
            AppMenuOption(
                AppOptionMenuType.UseKnownConfig,
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val gameName = appInfo.name
                            val gpuName = GPUInformation.getRenderer(context)

                            val bestConfig = BestConfigService.fetchBestConfig(gameName, gpuName)
                            if (bestConfig != null && bestConfig.matchType != "no_match") {
                                applyConfigForContainer(
                                    context,
                                    gameId,
                                    appId,
                                    bestConfig.bestConfig,
                                    bestConfig.matchType,
                                    scope,
                                )
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.best_config_no_config_available),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to apply known config: ${e.message}")
                            withContext(Dispatchers.Main) {
                                hideKnownConfigInstallState(gameId)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.best_config_apply_failed, e.message ?: "Unknown error"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            ),
            AppMenuOption(
                AppOptionMenuType.ImportConfig,
                onClick = {
                    requestImportConfig(gameId)
                }
            ),
            AppMenuOption(
                AppOptionMenuType.ExportConfig,
                onClick = {
                    requestExportConfig(gameId)
                }
            )
        )
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        val container = getContainer(context, libraryItem.appId)
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)

        if (container.language != config.language) {
            CoroutineScope(Dispatchers.IO).launch {
                SteamService.downloadApp(libraryItem.gameId)
            }
        }
    }

    override fun supportsContainerConfig(): Boolean = true

    override fun getExportFileExtension(): String = ".steam"

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit
    ) {
        val context = LocalContext.current
        val gameId = libraryItem.gameId
        val appInfo = remember(libraryItem.appId) {
            SteamService.getAppInfoOf(gameId)
        }

        // Track uninstall dialog state
        var showUninstallDialog by remember { mutableStateOf(shouldShowUninstallDialog(libraryItem.appId)) }

        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowUninstallDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    showUninstallDialog = shouldShow
                }
        }

        // Track install dialog state
        var installDialogState by remember(gameId) {
            mutableStateOf(getInstallDialogState(gameId) ?: MessageDialogState(false))
        }

        LaunchedEffect(gameId) {
            snapshotFlow { getInstallDialogState(gameId) }
                .collect { state ->
                    installDialogState = state ?: MessageDialogState(false)
                }
        }

        var knownConfigInstallState by remember(gameId) {
            mutableStateOf(getKnownConfigInstallState(gameId) ?: KnownConfigInstallState(false, -1f, ""))
        }

        LaunchedEffect(gameId) {
            snapshotFlow { getKnownConfigInstallState(gameId) }
                .collect { state ->
                    knownConfigInstallState = state ?: KnownConfigInstallState(false, -1f, "")
                }
        }

        var importConfigRequested by remember(gameId) {
            mutableStateOf(shouldImportConfig(gameId))
        }

        LaunchedEffect(gameId) {
            snapshotFlow { shouldImportConfig(gameId) }
                .collect { shouldRequest ->
                    importConfigRequested = shouldRequest
                }
        }

        var exportConfigRequested by remember(gameId) {
            mutableStateOf(shouldExportConfig(gameId))
        }

        LaunchedEffect(gameId) {
            snapshotFlow { shouldExportConfig(gameId) }
                .collect { shouldRequest ->
                    exportConfigRequested = shouldRequest
                }
        }

        var gameManagerDialogState by remember(gameId) {
            mutableStateOf(getGameManagerDialogState(gameId) ?: GameManagerDialogState(false))
        }

        LaunchedEffect(gameId) {
            snapshotFlow { getGameManagerDialogState(gameId) }
                .collect { state ->
                    gameManagerDialogState = state ?: GameManagerDialogState(false)
                }
        }

        // Migration state
        val scope = rememberCoroutineScope()
        var showMoveDialog by remember { mutableStateOf(false) }
        var current by remember { mutableStateOf("") }
        var progress by remember { mutableFloatStateOf(0f) }
        var moved by remember { mutableIntStateOf(0) }
        var total by remember { mutableIntStateOf(0) }
        val oldGamesDirectory = remember {
            Paths.get(SteamService.defaultAppInstallPath).pathString
        }
        val initialStoragePermissionGranted = remember {
            val writePermissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val readPermissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            writePermissionGranted && readPermissionGranted
        }
        var hasStoragePermission by remember { mutableStateOf(initialStoragePermissionGranted) }
        var installSizeInfo by remember(gameId) { mutableStateOf<InstallSizeInfo?>(null) }

        // Permission launcher for game migration
        val permissionMovingInternalLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { permission ->
                scope.launch {
                    showMoveDialog = true
                    StorageUtils.moveGamesFromOldPath(
                        Paths.get(Environment.getExternalStorageDirectory().absolutePath, "GameNative", "Steam").pathString,
                        oldGamesDirectory,
                        onProgressUpdate = { currentFile, fileProgress, movedFiles, totalFiles ->
                            current = currentFile
                            progress = fileProgress
                            moved = movedFiles
                            total = totalFiles
                        },
                        onComplete = {
                            showMoveDialog = false
                        },
                    )
                }
            },
        )

        // Permission launcher for storage permissions
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val writePermissionGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false
            val readPermissionGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
            val granted = writePermissionGranted && readPermissionGranted
            hasStoragePermission = granted
            if (!granted) {
                // Permissions denied
                Toast.makeText(
                    context,
                    context.getString(R.string.steam_storage_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
                hideInstallDialog(gameId)
                hideGameManagerDialog(gameId)
            }
        }

        val importConfigLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) {
                clearImportConfigRequest(gameId)
                return@rememberLauncherForActivityResult
            }

            scope.launch(Dispatchers.Main) {
                try {
                    SteamService.keepAlive = true
                    val jsonText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.orEmpty()
                    if (jsonText.isBlank()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.best_config_known_config_invalid),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }

                    val configJson = Json.parseToJsonElement(jsonText).jsonObject
                    val matchType = "exact_gpu_match"
                    applyConfigForContainer(
                        context,
                        gameId,
                        libraryItem.appId,
                        configJson,
                        matchType,
                        scope,
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to import config: ${e.message}")
                    Toast.makeText(
                        context,
                        context.getString(R.string.best_config_apply_failed, e.message ?: "Unknown error"),
                        Toast.LENGTH_SHORT
                    ).show()
                } finally {
                    clearImportConfigRequest(gameId)
                    SteamService.keepAlive = false
                }
            }
        }

        LaunchedEffect(importConfigRequested) {
            if (importConfigRequested) {
                importConfigLauncher.launch(
                    arrayOf("application/json", "text/json", "text/plain")
                )
            }
        }

        val exportConfigLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            if (uri == null) {
                clearExportConfigRequest(gameId)
                return@rememberLauncherForActivityResult
            }

            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                try {
                    val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
                    val jsonText = JSONObject(container.containerJson).toString(2)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(jsonText.toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                        }
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.base_app_exported),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to export config: ${e.message}")
                    Toast.makeText(
                        context,
                        context.getString(R.string.base_app_export_failed, e.message ?: "Unknown error"),
                        Toast.LENGTH_SHORT
                    ).show()
                } finally {
                    clearExportConfigRequest(gameId)
                }
            }
        }

        LaunchedEffect(exportConfigRequested) {
            if (exportConfigRequested) {
                val gameName = appInfo?.name ?: "game"
                val suggestedFileName = "${gameName}_config.json"
                exportConfigLauncher.launch(suggestedFileName)
            }
        }

        LaunchedEffect(gameId, hasStoragePermission) {
            if (!hasStoragePermission) {
                installSizeInfo = null
                return@LaunchedEffect
            }
            try {
                val info = withContext(Dispatchers.IO) {
                    val depots = SteamService.getDownloadableDepots(gameId)
                    Timber.i("There are ${depots.size} depots belonging to ${libraryItem.appId}")
                    val availableBytes = StorageUtils.getAvailableSpace(SteamService.defaultStoragePath)
                    val downloadBytes = depots.values.sumOf {
                        it.manifests["public"]?.download ?: 0
                    }
                    val installBytes = depots.values.sumOf { it.manifests["public"]?.size ?: 0 }
                    InstallSizeInfo(
                        downloadSize = StorageUtils.formatBinarySize(downloadBytes),
                        installSize = StorageUtils.formatBinarySize(installBytes),
                        availableSpace = StorageUtils.formatBinarySize(availableBytes),
                        installBytes = installBytes,
                        availableBytes = availableBytes,
                    )
                }
                installSizeInfo = info
            } catch (e: Exception) {
                Timber.e(e, "Failed to calculate install sizes for gameId=$gameId")
                installSizeInfo = null
            }
        }

        LaunchedEffect(installDialogState.visible, installDialogState.type, hasStoragePermission, installSizeInfo) {
            if (!installDialogState.visible) return@LaunchedEffect
            if (installDialogState.type != DialogType.INSTALL_APP_PENDING) return@LaunchedEffect

            if (!hasStoragePermission) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
            } else {
                val info = installSizeInfo ?: return@LaunchedEffect
                val state = if (info.availableBytes < info.installBytes) {
                    buildNotEnoughSpaceState(context, info)
                } else {
                    buildInstallPromptState(context, info)
                }
                showInstallDialog(gameId, state)
            }
        }

        LaunchedEffect(gameManagerDialogState.visible, hasStoragePermission) {
            if (!gameManagerDialogState.visible) return@LaunchedEffect
            if (!hasStoragePermission) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
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

        // Install dialog (INSTALL_APP, NOT_ENOUGH_SPACE, CANCEL_APP_DOWNLOAD)
        if (installDialogState.visible) {
            val onDismissRequest: (() -> Unit)? = {
                hideInstallDialog(gameId)
            }
            val onDismissClick: (() -> Unit)? = {
                hideInstallDialog(gameId)
            }
            val onConfirmClick: (() -> Unit)? = when (installDialogState.type) {
                DialogType.INSTALL_APP_PENDING -> null
                DialogType.INSTALL_APP -> {
                    {
                        PostHog.capture(
                            event = "game_install_started",
                            properties = mapOf("game_name" to (appInfo?.name ?: ""))
                        )
                        hideInstallDialog(gameId)
                        CoroutineScope(Dispatchers.IO).launch {
                            SteamService.downloadApp(gameId)
                        }
                    }
                }
                DialogType.NOT_ENOUGH_SPACE -> {
                    {
                        hideInstallDialog(gameId)
                    }
                }
                DialogType.CANCEL_APP_DOWNLOAD -> {
                    {
                        PostHog.capture(
                            event = "game_install_cancelled",
                            properties = mapOf("game_name" to (appInfo?.name ?: ""))
                        )
                        val downloadInfo = SteamService.getAppDownloadInfo(gameId)
                        downloadInfo?.cancel()
                        CoroutineScope(Dispatchers.IO).launch {
                            SteamService.deleteApp(gameId)
                            PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(gameId))
                            withContext(Dispatchers.Main) {
                                hideInstallDialog(gameId)
                            }
                        }
                    }
                }
                DialogType.UPDATE_VERIFY_CONFIRM -> {
                    {
                        hideInstallDialog(gameId)
                        val operation = getPendingUpdateVerifyOperation(gameId)
                        setPendingUpdateVerifyOperation(gameId, null)

                        if (operation != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
                                val downloadInfo = SteamService.downloadApp(gameId)
                                MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_DLL_REPLACED)
                                MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_DLL_RESTORED)
                                MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_COLDCLIENT_USED)

                                if (operation == AppOptionMenuType.VerifyFiles) {
                                    val steamId = SteamService.userSteamId
                                    if (steamId != null) {
                                        val prefixToPath: (String) -> String = { prefix ->
                                            PathType.from(prefix).toAbsPath(context, gameId, steamId.accountID)
                                        }
                                        SteamService.forceSyncUserFiles(
                                            appId = gameId,
                                            prefixToPath = prefixToPath,
                                            overrideLocalChangeNumber = -1
                                        ).await()
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.steam_not_logged_in),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }

                                container.isNeedsUnpacking = true
                                container.saveData()
                            }
                        }
                    }
                }
                DialogType.INSTALL_IMAGEFS -> {
                    {
                        hideInstallDialog(gameId)
                        // Install ImageFS with loading progress
                        // Note: This should ideally show a loading dialog, but for now we'll do it in the background
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
                                val variant = container.containerVariant

                                if (!SteamService.isImageFsInstallable(context, variant)) {
                                    SteamService.downloadImageFs(
                                        onDownloadProgress = { /* TODO: Update loading dialog progress */ },
                                        this,
                                        variant = variant,
                                        context = context
                                    ).await()
                                }
                                if (!SteamService.isImageFsInstalled(context)) {
                                    withContext(Dispatchers.Main) {
                                        SplitCompat.install(context)
                                    }
                                    ImageFsInstaller.installIfNeededFuture(context, context.assets, container) { progress ->
                                        // TODO: Update loading dialog progress
                                    }.get()
                                }
                                // After installation, trigger container edit
                                withContext(Dispatchers.Main) {
                                    // Trigger the container edit callback
                                    // This will be handled by the menu option's onClick
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.steam_imagefs_installed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.steam_imagefs_install_failed,
                                            e.message ?: ""
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }
                else -> null
            }

            MessageDialog(
                visible = installDialogState.visible,
                onDismissRequest = onDismissRequest,
                onConfirmClick = onConfirmClick,
                onDismissClick = onDismissClick,
                confirmBtnText = installDialogState.confirmBtnText,
                dismissBtnText = installDialogState.dismissBtnText,
                title = installDialogState.title,
                message = installDialogState.message,
            )
        }

        // Uninstall confirmation dialog
        if (showUninstallDialog) {
            AlertDialog(
                onDismissRequest = {
                    hideUninstallDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.steam_uninstall_game_title)) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.steam_uninstall_confirmation_message,
                            appInfo?.name ?: libraryItem.name
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(libraryItem.appId)

                            CoroutineScope(Dispatchers.IO).launch {
                                val success = SteamService.deleteApp(gameId)
                                withContext(Dispatchers.Main) {
                                    ContainerUtils.deleteContainer(context, libraryItem.appId)
                                }
                                withContext(Dispatchers.Main) {
                                    if (success) {
                                        PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(gameId))
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.steam_uninstall_success,
                                                appInfo?.name ?: libraryItem.name
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        PostHog.capture(
                                            event = "game_uninstalled",
                                            properties = mapOf("game_name" to (appInfo?.name ?: ""))
                                        )
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.steam_uninstall_failed),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.uninstall), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        hideUninstallDialog(libraryItem.appId)
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Game migration dialog
        if (showMoveDialog) {
            GameMigrationDialog(
                progress = progress,
                currentFile = current,
                movedFiles = moved,
                totalFiles = total,
            )
        }

        if (gameManagerDialogState.visible) {
            GameManagerDialog(
                visible = true,
                onGetDisplayInfo = { context ->
                    return@GameManagerDialog getGameDisplayInfo(context, libraryItem)
                },
                onInstall = { dlcAppIds ->
                    hideGameManagerDialog(gameId)

                    val installedApp = SteamService.getInstalledApp(gameId)
                    if (installedApp != null) {
                        // Remove markers if the app is already installed
                        MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_DLL_REPLACED)
                        MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_DLL_RESTORED)
                        MarkerUtils.removeMarker(getAppDirPath(gameId), Marker.STEAM_COLDCLIENT_USED)
                    }

                    PostHog.capture(
                        event = "game_install_started",
                        properties = mapOf("game_name" to (appInfo?.name ?: ""))
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        SteamService.downloadApp(gameId, dlcAppIds, isUpdateOrVerify = false)
                    }
                },
                onDismissRequest = {
                    hideGameManagerDialog(gameId)
                }
            )
        }
    }
}

