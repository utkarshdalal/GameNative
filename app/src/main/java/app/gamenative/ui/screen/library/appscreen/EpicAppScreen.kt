package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.data.EpicGame
import app.gamenative.data.LibraryItem
import app.gamenative.service.epic.EpicCloudSavesManager
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.epic.EpicService
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ContainerUtils.extractGameIdFromContainerId
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.core.StringUtils
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

// TODO: Verify all tests and do DLC auto-install with base game.
class EpicAppScreen : BaseAppScreen() {

    companion object {
        private const val TAG = "EpicAppScreen"

        private val uninstallDialogAppIds = mutableStateListOf<String>()

        fun showUninstallDialog(appId: String) {
            Timber.tag(TAG).d("showUninstallDialog: appId=$appId")
            if (!uninstallDialogAppIds.contains(appId)) {
                uninstallDialogAppIds.add(appId)
                Timber.tag(TAG).d("Added to uninstall dialog list: $appId")
            }
        }

        fun hideUninstallDialog(appId: String) {
            Timber.tag(TAG).d("hideUninstallDialog: appId=$appId")
            uninstallDialogAppIds.remove(appId)
        }

        fun shouldShowUninstallDialog(appId: String): Boolean {
            val result = uninstallDialogAppIds.contains(appId)
            Timber.tag(TAG).d("shouldShowUninstallDialog: appId=$appId, result=$result")
            return result
        }

        // Shared state for install dialog - list of appIds that should show the dialog
        private val installDialogAppIds = mutableStateListOf<String>()

        fun showInstallDialog(appId: String) {
            Timber.tag(TAG).d("showInstallDialog: appId=$appId")
            if (!installDialogAppIds.contains(appId)) {
                installDialogAppIds.add(appId)
                Timber.tag(TAG).d("Added to install dialog list: $appId")
            }
        }

        fun hideInstallDialog(appId: String) {
            Timber.tag(TAG).d("hideInstallDialog: appId=$appId")
            installDialogAppIds.remove(appId)
        }

        fun shouldShowInstallDialog(appId: String): Boolean {
            val result = installDialogAppIds.contains(appId)
            Timber.tag(TAG).d("shouldShowInstallDialog: appId=$appId, result=$result")
            return result
        }
    }

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo {
        Timber.tag(TAG).d("getGameDisplayInfo: appId=${libraryItem.appId}, name=${libraryItem.name}")
        // For Epic games, appId has EPIC_ prefix, strip it to get the raw Epic app name
        val appId = extractGameIdFromContainerId(libraryItem.appId)

        // Add a refresh trigger to re-fetch game data when install status changes
        var refreshTrigger by remember { mutableStateOf(0) }

        // Listen for install status changes to refresh game data
        DisposableEffect(appId) {
            val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.appId == appId) {
                    Timber.tag(TAG).d("Install status changed, refreshing game data for $appId")
                    refreshTrigger++
                }
            }
            app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
            onDispose {
                app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
            }
        }

        // Fetch install size from manifest if not already available
        LaunchedEffect(appId) {
            val game = EpicService.getEpicGameOf(appId)
            if (
                game != null &&
                !game.isInstalled &&
                (game.installSize == 0L || game.downloadSize == 0L || game.downloadSize > game.installSize)
            ) {
                Timber.tag("Epic").d("Install size not available for ${game.title}, fetching from manifest...")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val sizes = EpicService.fetchManifestSizes(context, game.appId)
                        if (sizes.installSize > 0L || sizes.downloadSize > 0L) {
                            Timber.tag("Epic").i(
                                "Fetched sizes for ${game.title}: install=${sizes.installSize} download=${sizes.downloadSize}",
                            )
                            // Update database with fetched size
                            val updatedGame = game.copy(
                                installSize = sizes.installSize,
                                downloadSize = sizes.downloadSize,
                            )
                            EpicService.updateEpicGame(updatedGame)
                            // Trigger refresh to show updated size
                            refreshTrigger++
                        }
                    } catch (e: Exception) {
                        Timber.tag("Epic").e(e, "Failed to fetch install size for ${game.title}")
                    }
                }
            }
        }

        val epicGame = remember(appId, refreshTrigger) {
            val game = EpicService.getEpicGameOf(appId)

            if (game != null) {
                val dlcTitles = EpicService.getDLCForGame(game.id)
                if (dlcTitles.isNotEmpty()) {
                    for (title in dlcTitles) {
                        Timber.tag("Epic").d("DLC Found: ${title.title}")
                    }
                }
                // TODO: Implement DLC Management
                // TODO: Give them a list of DLC and allow them to pick which ones to download
                // gameDlc = dlcTitles
            }
            game
        }

        val game = epicGame

        // Format sizes for display
        val sizeOnDisk = if (game != null && game.isInstalled && game.installSize > 0) {
            StringUtils.formatBytes(game.installSize)
        } else {
            null
        }

        val sizeFromStore = if (game != null) {
            when {
                game.installSize > 0 -> StringUtils.formatBytes(game.installSize)
                game.downloadSize > 0 -> StringUtils.formatBytes(game.downloadSize)
                else -> null
            }
        } else {
            null
        }

        // Parse Epic's ISO 8601 release date string to Unix timestamp
        // GameDisplayInfo expects Unix timestamp in SECONDS, not milliseconds
        val releaseDateTimestamp = if (game?.releaseDate?.isNotEmpty() == true) {
            try {
                val formatter = java.time.format.DateTimeFormatter.ISO_DATE_TIME
                val timestampMillis = java.time.ZonedDateTime.parse(game.releaseDate, formatter).toInstant().toEpochMilli()
                val timestampSeconds = timestampMillis / 1000
                Timber.tag(TAG).d("Parsed release date '${game.releaseDate}' -> $timestampSeconds seconds (${java.util.Date(timestampMillis)})")
                timestampSeconds
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse release date: ${game.releaseDate}")
                0L
            }
        } else {
            0L
        }

        val displayInfo = GameDisplayInfo(
            name = game?.title ?: libraryItem.name,
            iconUrl = game?.iconUrl ?: libraryItem.iconHash,
            heroImageUrl = game?.artCover ?: game?.artSquare ?: libraryItem.iconHash,
            gameId = libraryItem.gameId, // Use gameId property which handles conversion
            appId = libraryItem.appId,
            releaseDate = releaseDateTimestamp,
            developer = game?.developer?.takeIf { it.isNotEmpty() } ?: "",
            installLocation = game?.installPath?.takeIf { it.isNotEmpty() },
            sizeOnDisk = sizeOnDisk,
            sizeFromStore = sizeFromStore,
        )
        Timber.tag(TAG).d("Returning GameDisplayInfo: name=${displayInfo.name}, iconUrl=${displayInfo.iconUrl}, heroImageUrl=${displayInfo.heroImageUrl}, developer=${displayInfo.developer}, installLocation=${displayInfo.installLocation}")
        return displayInfo
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isInstalled: checking appId=${libraryItem.appId}")

        val appId = libraryItem.appId.get()
        return try {
            // Strip EPIC_ prefix to get raw Epic app name for Legendary CLI operations
            val epicGame = EpicService.getEpicGameOf(libraryItem.appId)
            val installed = epicGame?.isInstalled ?: false
            Timber.tag(TAG).d("isInstalled: appId=${libraryItem.appId}, result=$installed")
            installed
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check install status for ${libraryItem.appId}")
            false
        }
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isValidToDownload: checking appId=${libraryItem.appId}")
        // Epic games can be downloaded if not already installed or downloading
        val installed = isInstalled(context, libraryItem)
        val downloading = isDownloading(context, libraryItem)
        val valid = !installed && !downloading
        Timber.tag(TAG).d("isValidToDownload: appId=${libraryItem.appId}, installed=$installed, downloading=$downloading, valid=$valid")
        return valid
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        val downloadInfo = EpicService.getDownloadInfo(libraryItem.gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        return isDownloading
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        val downloadInfo = EpicService.getDownloadInfo(libraryItem.gameId)
        val progress = downloadInfo?.getProgress() ?: 0f
        Timber.tag(TAG).d("getDownloadProgress: appId=${libraryItem.appId}, progress=$progress")
        return progress
    }

    override fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        Timber.tag(TAG).i("onDownloadInstallClick: appId=${libraryItem.appId}, name=${libraryItem.name}")
        val game = EpicService.getEpicGameOf(libraryItem.gameId)

        if (game == null) {
            Timber.e("No game found with id: ${libraryItem.gameId}")
            return
        }

        val appId = game.appId
        val downloadInfo = EpicService.getDownloadInfo(appId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val installed = isInstalled(context, libraryItem)

        Timber.tag(TAG).d("onDownloadInstallClick: appId=${libraryItem.appId}, appId=$appId, isDownloading=$isDownloading, installed=$installed")

        if (isDownloading) {
            // Cancel ongoing download
            Timber.tag(TAG).i("Cancelling Epic download for: $appId")
            EpicService.cleanupDownload(appId)
            downloadInfo.cancel()
        } else if (installed) {
            // Already installed: launch game
            Timber.tag(TAG).i("Epic game already installed, launching: $appId")
            onClickPlay(false)
        } else {
            // Show install confirmation dialog
            Timber.tag(TAG).i("Showing install confirmation dialog for: ${libraryItem.appId}")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Calculate sizes
                    val downloadSize = StringUtils.formatBytes(game?.downloadSize ?: 0L)
                    val installSize = StringUtils.formatBytes(game?.installSize ?: 0L)
                    val availableSpace = try {
                        StringUtils.formatBytes(app.gamenative.utils.StorageUtils.getAvailableSpace(EpicConstants.defaultEpicGamesPath(context)))
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to get available storage space")
                        "Unknown"
                    }

                    val message = context.getString(
                        R.string.epic_install_game_message,
                        downloadSize,
                        installSize,
                        availableSpace,
                    )
                    val state = app.gamenative.ui.component.dialog.state.MessageDialogState(
                        visible = true,
                        type = app.gamenative.ui.enums.DialogType.INSTALL_APP,
                        title = context.getString(R.string.epic_install_game_title),
                        message = message,
                        confirmBtnText = context.getString(R.string.install),
                        dismissBtnText = context.getString(R.string.cancel),
                    )
                    BaseAppScreen.showInstallDialog(libraryItem.appId, state)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to show install dialog for: ${libraryItem.appId}")
                }
            }
        }
    }

    /**
     * Perform the actual download after confirmation
     * Delegates to EpicService/EpicManager for proper service layer separation
     */
    private fun performDownload(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        Timber.i("Starting Epic game download: $appId")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get install path
                val installPath = EpicConstants.getGameInstallPath(context, appId)
                Timber.d("Downloading Epic game to: $installPath")

                // Show starting download toast
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Starting download: ${libraryItem.name}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }

                // Start download - EpicService will handle monitoring, database updates, verification, and events
                val result = EpicService.downloadGame(context, appId, installPath)

                if (result.isSuccess) {
                    Timber.i("Epic game download started successfully: $appId")
                    // Success toast will be shown when download completes (monitored by EpicService)
                } else {
                    Timber.e("Failed to start Epic game download: $appId - ${result.exceptionOrNull()?.message}")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Failed to start download: ${result.exceptionOrNull()?.message}",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during Epic download")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Download error: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onPauseResumeClick: appId=${libraryItem.appId}")

        if (isDownloading(libraryItem.appId)) {
            // Cancel/pause download
            Timber.tag(TAG).i("Pausing Epic download: $appId")
            downloadInfo.cancel()
            EpicService.cleanupDownload(libraryItem.appId)
        } else {
            // Resume download (restart from beginning for now)
            Timber.tag(TAG).i("Resuming Epic download: $appId")
            onDownloadInstallClick(context, libraryItem) {}
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onDeleteDownloadClick: appId=${libraryItem.appId}")

        if (isDownloading(libraryItem.appId)) {
            // Cancel download immediately if currently downloading
            Timber.tag(TAG).i("Cancelling active download for Epic game: $appId")
            downloadInfo.cancel()
            EpicService.cleanupDownload(appId)

            android.widget.Toast.makeText(
                context,
                "Download cancelled",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        } else if (isInstalled) {
            // Show uninstall confirmation dialog
            Timber.tag(TAG).i("Showing uninstall dialog for: ${libraryItem.appId}")
            showUninstallDialog(libraryItem.appId)
        }
    }

    /**
     * Perform the actual uninstall of an Epic game
     * Delegates to EpicService/EpicManager for proper service layer separation
     */
    private fun performUninstall(context: Context, libraryItem: LibraryItem) {
        Timber.i("Uninstalling Epic game: ${libraryItem.appId}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Delegate to EpicService which calls EpicManager.deleteGame
                val result = EpicService.deleteGame(context, appId)

                if (result.isSuccess) {
                    Timber.i("Epic game uninstalled successfully: ${libraryItem.appId}")
                } else {
                    Timber.e("Failed to uninstall Epic game: ${libraryItem.appId} - ${result.exceptionOrNull()?.message}")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Uninstall failed: ${result.exceptionOrNull()?.message}",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error uninstalling Epic game")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Uninstall error: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onUpdateClick: appId=${libraryItem.appId}")
        // TODO: Implement update for Epic games
        // Check Epic for newer version and download if available
        Timber.tag(TAG).d("Update clicked for Epic game: ${libraryItem.appId}")
    }

    override fun getExportFileExtension(): String = ".epicgame"

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}")
        return try {
            val path = EpicService.getInstallPath(libraryItem.appId)
            Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId} path=$path")
            path
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get install path for ${libraryItem.appId}")
            null
        }
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        Timber.tag(TAG).d("loadContainerData: appId=${libraryItem.appId}")
        // Load Epic-specific container data using ContainerUtils
        val container = app.gamenative.utils.ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        val containerData = app.gamenative.utils.ContainerUtils.toContainerData(container)
        Timber.tag(TAG).d("loadContainerData: loaded container for ${libraryItem.appId}")
        return containerData
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        Timber.tag(TAG).i("saveContainerConfig: appId=${libraryItem.appId}")
        // Save Epic-specific container configuration using ContainerUtils
        app.gamenative.utils.ContainerUtils.applyToContainer(context, libraryItem.appId, config)
        Timber.tag(TAG).d("saveContainerConfig: saved container config for ${libraryItem.appId}")
    }

    override fun supportsContainerConfig(): Boolean {
        Timber.tag(TAG).d("supportsContainerConfig: returning true")
        // Epic games support container configuration like other Wine games
        return true
    }

    /**
     * Epic-specific menu options
     */
    @Composable
    override fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean,
    ): List<AppMenuOption> {
        val options = mutableListOf<AppMenuOption>()

        // Add cloud sync option if game supports cloud saves
        val epicGame = EpicService.getEpicGameOf(libraryItem.appId)
        if (epicGame?.cloudSaveEnabled == true) {
            options.add(
                AppMenuOption(
                    optionType = AppOptionMenuType.ForceCloudSync,
                    onClick = {
                        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
                        scope.launch {
                            try {
                                Toast.makeText(
                                    context,
                                    "Starting cloud save sync...",
                                    Toast.LENGTH_SHORT,
                                ).show()

                                val result = withContext(Dispatchers.IO) {
                                    EpicCloudSavesManager.syncCloudSaves(
                                        context,
                                        libraryItem.appId,
                                        preferredAction = "download", // Force download for testing
                                    )
                                }

                                Toast.makeText(
                                    context,
                                    if (result) "Cloud saves synced successfully" else "Cloud save sync failed",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "[Cloud Saves] Sync failed")
                                Toast.makeText(
                                    context,
                                    "Cloud save sync error: ${e.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ),
            )
        }

        return options
    }

    /**
     * Epic games support standard container reset
     */
    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption {
        return AppMenuOption(
            optionType = AppOptionMenuType.ResetToDefaults,
            onClick = {
                resetContainerToDefaults(context, libraryItem)
            },
        )
    }

    /**
     * Epic games don't need special image fetching logic like Custom Games
     * Images come from Epic CDN
     */
    override fun getGameFolderPathForImageFetch(context: Context, libraryItem: LibraryItem): String? {
        return null // Epic uses CDN images, not local files
    }

    override fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)?,
    ): (() -> Unit)? {
        Timber.tag(TAG).d("[OBSERVE] Setting up observeGameState for appId=${libraryItem.appId}, gameId=${libraryItem.gameId}")
        val disposables = mutableListOf<() -> Unit>()
        var currentProgressListener: ((Float) -> Unit)? = null

        // Listen for download status changes
        val downloadStatusListener: (app.gamenative.events.AndroidEvent.DownloadStatusChanged) -> Unit = { event ->
            Timber.tag(TAG).d("[OBSERVE] DownloadStatusChanged event received: event.appId=${event.appId}, libraryItem.appId=${libraryItem.appId}, match=${event.appId == libraryItem.appId}")
            if (event.appId == libraryItem.appId) {
                Timber.tag(TAG).d("[OBSERVE] Download status changed for ${libraryItem.appId}, isDownloading=${event.isDownloading}")
                if (event.isDownloading) {
                    // Download started - attach progress listener
                    val downloadInfo = EpicService.getDownloadInfo(appId)
                    if (downloadInfo != null) {
                        // Remove previous listener if exists
                        currentProgressListener?.let { listener ->
                            downloadInfo.removeProgressListener(listener)
                        }
                        // Add new listener and track it
                        val progressListener: (Float) -> Unit = { progress ->
                            onProgressChanged(progress)
                        }
                        downloadInfo.addProgressListener(progressListener)
                        currentProgressListener = progressListener

                        // Add cleanup for this listener
                        disposables += {
                            currentProgressListener?.let { listener ->
                                downloadInfo.removeProgressListener(listener)
                                currentProgressListener = null
                            }
                        }
                        Timber.tag(TAG).d("[OBSERVE] Progress listener attached for $appId")
                    }
                } else {
                    // Download stopped/completed - clean up listener
                    currentProgressListener?.let { listener ->
                        val downloadInfo = EpicService.getDownloadInfo(appId)
                        downloadInfo?.removeProgressListener(listener)
                    }
                    onHasPartialDownloadChanged?.invoke(false)
                    Timber.tag(TAG).d("[OBSERVE] Download stopped/completed, listener cleaned up")
                }
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener)
        disposables +=
            { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener) }

        // Listen for install status changes
        val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
            Timber.tag(TAG).d("[OBSERVE] LibraryInstallStatusChanged event received: event.appId=${event.appId}, libraryItem.appId=${libraryItem.appId}, match=${event.appId == libraryItem.gameId}")
            if (event.appId == libraryItem.gameId) {
                Timber.tag(TAG).d("[OBSERVE] Install status changed for ${libraryItem.appId}, calling onStateChanged()")
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        disposables +=
            { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener) }

        // Return cleanup function
        return {
            disposables.forEach { it() }
        }
    }

    /**
     * Epic-specific dialogs (install confirmation, uninstall confirmation)
     */
    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
    ) {
        Timber.tag(TAG).d("AdditionalDialogs: composing for appId=${libraryItem.appId}")
        val context = LocalContext.current

        // Monitor uninstall dialog state
        var showUninstallDialog by remember { mutableStateOf(shouldShowUninstallDialog(libraryItem.appId)) }

        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowUninstallDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    Timber.tag(TAG).d("Uninstall dialog state changed: $shouldShow")
                    showUninstallDialog = shouldShow
                }
        }

        // Shared install dialog state (from BaseAppScreen)
        val appId = libraryItem.appId
        var installDialogState by remember(appId) {
            mutableStateOf(BaseAppScreen.getInstallDialogState(appId) ?: app.gamenative.ui.component.dialog.state.MessageDialogState(false))
        }
        LaunchedEffect(appId) {
            snapshotFlow { BaseAppScreen.getInstallDialogState(appId) }
                .collect { state ->
                    installDialogState = state ?: app.gamenative.ui.component.dialog.state.MessageDialogState(false)
                }
        }

        // Show install dialog if visible
        if (installDialogState.visible) {
            val onDismissRequest: (() -> Unit)? = {
                BaseAppScreen.hideInstallDialog(appId)
            }
            val onDismissClick: (() -> Unit)? = {
                BaseAppScreen.hideInstallDialog(appId)
            }
            val onConfirmClick: (() -> Unit)? = when (installDialogState.type) {
                app.gamenative.ui.enums.DialogType.INSTALL_APP -> {
                    {
                        BaseAppScreen.hideInstallDialog(appId)
                        performDownload(context, libraryItem) {}
                    }
                }

                else -> null
            }
            app.gamenative.ui.component.dialog.MessageDialog(
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

        // Show uninstall confirmation dialog
        if (showUninstallDialog) {
            AlertDialog(
                onDismissRequest = {
                    hideUninstallDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.epic_uninstall_game_title)) },
                text = {
                    Text(stringResource(R.string.epic_uninstall_game_message, libraryItem.name))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(libraryItem.appId)
                            performUninstall(context, libraryItem)
                        },
                    ) {
                        Text(stringResource(R.string.uninstall))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(libraryItem.appId)
                        },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}
