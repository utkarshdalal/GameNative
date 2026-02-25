package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.data.GOGGame
import app.gamenative.data.LibraryItem
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils.getContainer
import com.winlator.container.ContainerData
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * GOG-specific implementation of BaseAppScreen
 * Handles GOG games with integration to the Python gogdl backend
 */
class GOGAppScreen : BaseAppScreen() {
    companion object {
        private const val TAG = "GOGAppScreen"

        // Shared state for uninstall dialog - list of appIds that should show the dialog
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

        /**
         * Formats bytes into a human-readable string (KB, MB, GB).
         * Uses binary units (1024 base).
         */
        private fun formatBytes(bytes: Long): String {
            val kb = 1024.0
            val mb = kb * 1024
            val gb = mb * 1024
            return when {
                bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
                bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
                bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
                else -> "$bytes B"
            }
        }
    }

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo {
        Timber.tag(TAG).d("getGameDisplayInfo: appId=${libraryItem.appId}, name=${libraryItem.name}")
        // Extract numeric gameId for GOGService calls
        val gameId = libraryItem.gameId.toString()

        // Add a refresh trigger to re-fetch game data when install status changes
        var refreshTrigger by remember { mutableStateOf(0) }

        // Listen for install status changes to refresh game data
        LaunchedEffect(gameId) {
            val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.appId == libraryItem.gameId) {
                    Timber.tag(TAG).d("Install status changed, refreshing game data for $gameId")
                    refreshTrigger++
                }
            }
            app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        }

        var gogGame by remember(gameId, refreshTrigger) { mutableStateOf<GOGGame?>(null) }

        LaunchedEffect(gameId, refreshTrigger) {
            gogGame = GOGService.getGOGGameOf(gameId)
        }

        val game = gogGame

        // Format sizes for display
        val sizeOnDisk = if (game != null && game.isInstalled && game.installSize > 0) {
            formatBytes(game.installSize)
        } else {
            null
        }

        val sizeFromStore = if (game != null && game.downloadSize > 0) {
            formatBytes(game.downloadSize)
        } else {
            null
        }

        // Parse GOG's ISO 8601 release date string to Unix timestamp
        // GOG returns dates like "2022-08-18T17:50:00+0300" (without colon in timezone)
        // GameDisplayInfo expects Unix timestamp in SECONDS, not milliseconds
        val rawReleaseDate = game?.releaseDate
        val releaseDateTimestamp = if (!rawReleaseDate.isNullOrEmpty() && rawReleaseDate != "null") {
            try {
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
                val timestampMillis = java.time.ZonedDateTime.parse(rawReleaseDate, formatter).toInstant().toEpochMilli()
                val timestampSeconds = timestampMillis / 1000
                Timber.tag(TAG).d("Parsed release date '$rawReleaseDate' -> $timestampSeconds seconds (${java.util.Date(timestampMillis)})")
                timestampSeconds
            } catch (e: Exception) {
                Timber.tag(TAG).d("Release date not parseable (ignored): $rawReleaseDate")
                0L
            }
        } else {
            0L
        }

        val displayInfo = GameDisplayInfo(
            name = game?.title ?: libraryItem.name,
            iconUrl = game?.iconUrl ?: libraryItem.iconHash,
            heroImageUrl = game?.imageUrl ?: game?.iconUrl ?: libraryItem.iconHash,
            gameId = libraryItem.gameId, // Use gameId property which handles conversion
            appId = libraryItem.appId,
            releaseDate = releaseDateTimestamp,
            developer = game?.developer?.takeIf { it.isNotEmpty() } ?: "", // GOG API doesn't provide this
            installLocation = game?.installPath?.takeIf { it.isNotEmpty() },
            sizeOnDisk = sizeOnDisk,
            sizeFromStore = sizeFromStore,
        )
        Timber.tag(TAG).d("Returning GameDisplayInfo: name=${displayInfo.name}, iconUrl=${displayInfo.iconUrl}, heroImageUrl=${displayInfo.heroImageUrl}, developer=${displayInfo.developer}, installLocation=${displayInfo.installLocation}")
        return displayInfo
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isInstalled: checking appId=${libraryItem.appId}")
        return try {
            // GOGService expects numeric gameId
            val installed = GOGService.isGameInstalled(libraryItem.gameId.toString())
            Timber.tag(TAG).d("isInstalled: appId=${libraryItem.appId}, result=$installed")
            installed
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check install status for ${libraryItem.appId}")
            false
        }
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isValidToDownload: checking appId=${libraryItem.appId}")
        // GOG games can be downloaded if not already installed or downloading
        val installed = isInstalled(context, libraryItem)
        val downloading = isDownloading(context, libraryItem)
        val valid = !installed && !downloading
        Timber.tag(TAG).d("isValidToDownload: appId=${libraryItem.appId}, installed=$installed, downloading=$downloading, valid=$valid")
        return valid
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isDownloading: checking appId=${libraryItem.appId}")
        // Check if there's an active download for this GOG game
        // GOGService expects numeric gameId
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.gameId.toString())
        val progress = downloadInfo?.getProgress() ?: 0f
        val isActive = downloadInfo?.isActive() ?: false
        val downloading = downloadInfo != null && isActive && progress < 1f
        Timber.tag(TAG).d("isDownloading: appId=${libraryItem.appId}, hasDownloadInfo=${downloadInfo != null}, active=$isActive, progress=$progress, result=$downloading")
        return downloading
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        // GOGService expects numeric gameId
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.gameId.toString())
        val progress = downloadInfo?.getProgress() ?: 0f
        Timber.tag(TAG).d("getDownloadProgress: appId=${libraryItem.appId}, progress=$progress")
        return progress
    }

    override fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean {
        // GOG downloads cannot be paused/resumed, so never show as having partial download
        // This prevents the UI from showing a resume button
        return false
    }

    override fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        Timber.tag(TAG).i("onDownloadInstallClick: appId=${libraryItem.appId}, name=${libraryItem.name}")
        // GOGService expects numeric gameId
        val gameId = libraryItem.gameId.toString()
        val downloadInfo = GOGService.getDownloadInfo(gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val installed = isInstalled(context, libraryItem)

        Timber.tag(TAG).d("onDownloadInstallClick: appId=${libraryItem.appId}, isDownloading=$isDownloading, installed=$installed")

        if (isDownloading) {
            // Cancel ongoing download
            Timber.tag(TAG).i("Cancelling GOG download for: ${libraryItem.appId}")
            downloadInfo.cancel()
            GOGService.cleanupDownload(gameId)
        } else if (installed) {
            // Already installed: launch game
            Timber.tag(TAG).i("GOG game already installed, launching: ${libraryItem.appId}")
            onClickPlay(false)
        } else {
            // Show install confirmation dialog
            Timber.tag(TAG).i("Showing install confirmation dialog for: ${libraryItem.appId}")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val game = GOGService.getGOGGameOf(gameId)

                    // Calculate sizes
                    val downloadSize = app.gamenative.utils.StorageUtils.formatBinarySize(game?.downloadSize ?: 0L)
                    val availableSpace = app.gamenative.utils.StorageUtils.formatBinarySize(
                        app.gamenative.utils.StorageUtils.getAvailableSpace(app.gamenative.service.gog.GOGConstants.defaultGOGGamesPath)
                    )

                    val message = context.getString(
                        R.string.gog_install_confirmation_message,
                        downloadSize,
                        availableSpace
                    )
                    val state = app.gamenative.ui.component.dialog.state.MessageDialogState(
                        visible = true,
                        type = app.gamenative.ui.enums.DialogType.INSTALL_APP,
                        title = context.getString(R.string.gog_install_game_title),
                        message = message,
                        confirmBtnText = context.getString(R.string.download),
                        dismissBtnText = context.getString(R.string.cancel)
                    )
                    BaseAppScreen.showInstallDialog(libraryItem.appId, state)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to show install dialog for: ${libraryItem.appId}")
                }
            }
        }
    }

    private fun performDownload(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        val gameId = libraryItem.gameId.toString()
        Timber.i("Starting GOG game download: ${libraryItem.appId}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get install path
                val installPath = GOGConstants.getGameInstallPath(libraryItem.name)
                val containerData = loadContainerData(context, libraryItem)

                Timber.d("Downloading GOG game to: $installPath")

                // Show starting download toast
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Starting download for ${libraryItem.name}...",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }

                // Start download - GOGService will handle monitoring, database updates, verification, and events
                val result = GOGService.downloadGame(context, gameId, installPath, containerData.language)

                if (result.isSuccess) {
                    Timber.i("GOG download started successfully for: $gameId")
                    // Success toast will be shown when download completes (monitored by GOGService)
                } else {
                    val error = result.exceptionOrNull()
                    Timber.e(error, "Failed to start GOG download")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Failed to start download: ${error?.message}",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during GOG download")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Download error: ${e.message}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onPauseResumeClick: appId=${libraryItem.appId}")
        // GOG downloads cannot be paused - only canceled
        // This method should not be called for GOG since hasPartialDownload returns false,
        // but if it is called, just cancel the download
        val gameId = libraryItem.gameId.toString()
        val downloadInfo = GOGService.getDownloadInfo(gameId)

        if (downloadInfo != null) {
            Timber.tag(TAG).i("Cancelling GOG download: ${libraryItem.appId}")
            downloadInfo.cancel()
            GOGService.cleanupDownload(gameId)
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onDeleteDownloadClick: appId=${libraryItem.appId}")
        // GOGService expects numeric gameId
        val gameId = libraryItem.gameId.toString()
        val downloadInfo = GOGService.getDownloadInfo(gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val isInstalled = isInstalled(context, libraryItem)
        Timber.tag(TAG).d("onDeleteDownloadClick: appId=${libraryItem.appId}, isDownloading=$isDownloading, isInstalled=$isInstalled")

        if (isDownloading) {
            // Cancel download immediately if currently downloading
            Timber.tag(TAG).i("Cancelling active download for GOG game: ${libraryItem.appId}")
            downloadInfo.cancel()
            GOGService.cleanupDownload(gameId)
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

    private fun performUninstall(context: Context, libraryItem: LibraryItem) {
        Timber.i("Uninstalling GOG game: ${libraryItem.appId}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Delegate to GOGService which calls GOGManager.deleteGame
                val result = GOGService.deleteGame(context, libraryItem)

                if (result.isSuccess) {
                    Timber.i("Successfully uninstalled GOG game: ${libraryItem.appId}")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Game uninstalled successfully",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    val error = result.exceptionOrNull()
                    Timber.e(error, "Failed to uninstall GOG game: ${libraryItem.appId}")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "Failed to uninstall game: ${error?.message}",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error uninstalling GOG game")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Failed to uninstall game: ${e.message}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onUpdateClick: appId=${libraryItem.appId}")
        // TODO: Implement update for GOG games
        // Check GOG for newer version and download if available
        Timber.tag(TAG).d("Update clicked for GOG game: ${libraryItem.appId}")
    }

    override fun getExportFileExtension(): String {
        return ".gog"
    }

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}")
        return try {
            // GOGService expects numeric gameId
            val path = GOGService.getInstallPath(libraryItem.gameId.toString())
            Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}, path=$path")
            path
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get install path for ${libraryItem.appId}")
            null
        }
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        Timber.tag(TAG).d("loadContainerData: appId=${libraryItem.appId}")
        // Load GOG-specific container data using ContainerUtils
        val container = app.gamenative.utils.ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        val containerData = app.gamenative.utils.ContainerUtils.toContainerData(container)
        Timber.tag(TAG).d("loadContainerData: loaded container for ${libraryItem.appId}")
        return containerData
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        Timber.tag(TAG).i("saveContainerConfig: appId=${libraryItem.appId}")
        val container = getContainer(context, libraryItem.appId)
        val previousLanguage = container.language
        app.gamenative.utils.ContainerUtils.applyToContainer(context, libraryItem.appId, config)
        Timber.tag(TAG).d("saveContainerConfig: saved container config for ${libraryItem.appId}")

        if (previousLanguage != config.language) {
            CoroutineScope(Dispatchers.IO).launch {
                val gameId = libraryItem.gameId.toString()
                if (!GOGService.isGameInstalled(gameId)) return@launch
                if (GOGService.getDownloadInfo(gameId)?.isActive() == true) return@launch

                val installPath = GOGService.getInstallPath(gameId)
                    ?: GOGConstants.getGameInstallPath(libraryItem.name)

                GOGService.downloadGame(context, gameId, installPath, config.language)
            }
        }
    }

    override fun supportsContainerConfig(): Boolean {
        Timber.tag(TAG).d("supportsContainerConfig: returning true")
        // GOG games support container configuration like other Wine games
        return true
    }

    /**
     * GOG games support standard container reset
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
    override fun getGameFolderPathForImageFetch(context: Context, libraryItem: LibraryItem): String? {
        return null // GOG Stores full URLs in their database entry.
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
            Timber.tag(TAG).d("[OBSERVE] DownloadStatusChanged event received: event.appId=${event.appId}, libraryItem.gameId=${libraryItem.gameId}, match=${event.appId == libraryItem.gameId}")
            if (event.appId == libraryItem.gameId) {
                Timber.tag(TAG).d("[OBSERVE] Download status changed for ${libraryItem.appId}, isDownloading=${event.isDownloading}")
                if (event.isDownloading) {
                    // Download started - attach progress listener
                    // GOGService expects numeric gameId
                    val downloadInfo = GOGService.getDownloadInfo(libraryItem.gameId.toString())
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
                    }
                } else {
                    // Download stopped/completed - clean up listener
                    currentProgressListener?.let { listener ->
                        val downloadInfo = GOGService.getDownloadInfo(libraryItem.gameId.toString())
                        downloadInfo?.removeProgressListener(listener)
                        currentProgressListener = null
                    }
                    onHasPartialDownloadChanged?.invoke(false)
                }
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener)
        disposables +=
            { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener) }

        // Listen for install status changes
        val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
            Timber.tag(TAG).d("[OBSERVE] LibraryInstallStatusChanged event received: event.appId=${event.appId}, libraryItem.gameId=${libraryItem.gameId}, match=${event.appId == libraryItem.gameId}")
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
     * GOG-specific dialogs (install confirmation, uninstall confirmation)
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
                title = { Text(stringResource(R.string.gog_uninstall_game_title)) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.gog_uninstall_confirmation_message,
                            libraryItem.name,
                        ),
                    )
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
