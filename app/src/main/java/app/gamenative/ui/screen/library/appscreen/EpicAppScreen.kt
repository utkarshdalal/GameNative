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
import app.gamenative.data.LibraryItem
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.epic.EpicService
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerData
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

// TODO: Understand how we should download the DLC and show that in the UI
// TODO: Wait until GameManager PR has been finished and merged so we can utilise it.

class EpicAppScreen : BaseAppScreen() {

    companion object {
        private const val TAG = "EpicAppScreen"

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
        // For Epic games, appId has EPIC_ prefix, strip it to get the raw Epic app name
        val appId = libraryItem.appId
        val appName = appId.removePrefix("EPIC_")

        // Add a refresh trigger to re-fetch game data when install status changes
        var refreshTrigger by remember { mutableStateOf(0) }

        // Listen for install status changes to refresh game data
        LaunchedEffect(appId) {
            val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.appId == libraryItem.appId) {
                    Timber.tag(TAG).d("Install status changed, refreshing game data for $appId")
                    refreshTrigger++
                }
            }
            app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        }

        // Fetch install size from manifest if not already available
        LaunchedEffect(appId) {
            val game = EpicService.getEpicGameOf(appName)
            if (
                game != null &&
                !game.isInstalled &&
                (game.installSize == 0L || game.downloadSize == 0L || game.downloadSize > game.installSize)
            ) {
                Timber.tag("Epic").d("Install size not available for ${game.title}, fetching from manifest...")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val sizes = EpicService.fetchManifestSizes(context, game.appName)
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
            val game = EpicService.getEpicGameOf(appName)

            if (game != null) {
                val dlcTitles = EpicService.getDLCForGame(game.id)
                if (dlcTitles.isNotEmpty()) {
                    for (title in dlcTitles) {
                        Timber.tag("Epic").i("DLC Found: ${title.title}")
                    }
                }

                Timber.tag("Epic").i(
                    """
                    |╔═══════════════════════════════════════════════════════════════════════════════
                    |║ EPIC GAME DATABASE ENTRY
                    |╠═══════════════════════════════════════════════════════════════════════════════
                    |║ IDENTIFIERS
                    |║   ID (Primary Key): ${game.id}
                    |║   App Name: ${game.appName}
                    |║   Namespace: ${game.namespace}
                    |║   Title: ${game.title}
                    |║
                    |║ METADATA
                    |║   Developer: ${game.developer}
                    |║   Publisher: ${game.publisher}
                    |║   Platform: ${game.platform}
                    |║   Release Date: ${game.releaseDate}
                    |║   Version: ${game.version}
                    |║
                    |║ INSTALLATION
                    |║   Is Installed: ${game.isInstalled}
                    |║   Install Path: ${game.installPath.ifEmpty { "N/A" }}
                    |║   Executable: ${game.executable.ifEmpty { "N/A" }}
                    |║   Download Size: ${game.downloadSize} bytes (${game.downloadSize / 1_000_000_000.0} GB)
                    |║   Install Size: ${game.installSize} bytes (${game.installSize / 1_000_000_000.0} GB)
                    |║   Base Game App Name: ${game.baseGameAppName.ifEmpty { "N/A" }}
                    |║
                    |║ ARTWORK
                    |║   Cover (Tall): ${game.artCover.ifEmpty { "N/A" }}
                    |║   Square: ${game.artSquare.ifEmpty { "N/A" }}
                    |║   Logo: ${game.artLogo.ifEmpty { "N/A" }}
                    |║   Portrait (Wide): ${game.artPortrait.ifEmpty { "N/A" }}
                    |║
                    |║ FEATURES
                    |║   Can Run Offline: ${game.canRunOffline}
                    |║   Requires OT: ${game.requiresOT}
                    |║   Cloud Save Enabled: ${game.cloudSaveEnabled}
                    |║   Save Folder: ${game.saveFolder.ifEmpty { "N/A" }}
                    |║   Is DLC: ${game.isDLC}
                    |║   Base Game App Name: ${game.baseGameAppName.ifEmpty { "N/A" }}
                    |║
                    |║ THIRD PARTY
                    |║   Third Party App: ${game.thirdPartyManagedApp.ifEmpty { "N/A" }}
                    |║   Is EA Managed: ${game.isEAManaged}
                    |║
                    |║ DESCRIPTION
                    |║   ${game.description.take(200)}${if (game.description.length > 200) "..." else ""}
                    |║
                    |║ GENRES (${game.genres.size})
                    |║   ${game.genres.joinToString(", ").ifEmpty { "None" }}
                    |║
                    |║ TAGS (${game.tags.size})
                    |║   ${game.tags.joinToString(", ").ifEmpty { "None" }}
                    |║
                    |║ PLAYTIME
                    |║   Last Played: ${if (game.lastPlayed > 0) java.util.Date(game.lastPlayed) else "Never"}
                    |║   Total Playtime: ${game.playTime} seconds (${game.playTime / 3600.0} hours)
                    |╚═══════════════════════════════════════════════════════════════════════════════
                    """.trimMargin(),
                )
            } else {
                Timber.tag("Epic").w(
                    """
                    |╔═══════════════════════════════════════════════════════════════════════════════
                    |║ EPIC GAME NOT FOUND IN DATABASE
                    |╠═══════════════════════════════════════════════════════════════════════════════
                    |║ App ID: $appId
                    |║ App Name: $appName
                    |║
                    |║ The game will use fallback data from the LibraryItem until Epic library
                    |║ is refreshed. Try opening Settings > Epic Games > Sync Library.
                    |╚═══════════════════════════════════════════════════════════════════════════════
                    """.trimMargin(),
                )
            }
            game
        }

        val game = epicGame

        // Format sizes for display
        val sizeOnDisk = if (game != null && game.isInstalled && game.installSize > 0) {
            formatBytes(game.installSize)
        } else {
            null
        }

        val sizeFromStore = if (game != null) {
            when {
                game.installSize > 0 -> formatBytes(game.installSize)
                game.downloadSize > 0 -> formatBytes(game.downloadSize)
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
        return try {
            // Strip EPIC_ prefix to get raw Epic app name for Legendary CLI operations
            val appName = libraryItem.appId.removePrefix("EPIC_")
            val epicGame = EpicService.getEpicGameOf(appName)
            val installed = epicGame?.isInstalled ?: false
            Timber.tag(TAG).d("isInstalled: appId=${libraryItem.appId}, appName=$appName, result=$installed")
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
        Timber.tag(TAG).d("isDownloading: checking appId=${libraryItem.appId}")
        // Check if there's an active download for this Epic game
        val epicGame = EpicService.getEpicGameOf(libraryItem.appId.removePrefix("EPIC_"))
        val appName = epicGame?.appName ?: return false
        val downloadInfo = EpicService.getDownloadInfo(appName)
        val progress = downloadInfo?.getProgress() ?: 0f
        val isActive = downloadInfo?.isActive() ?: false
        val downloading = downloadInfo != null && isActive && progress < 1f
        Timber.tag(TAG).d("isDownloading: appId=${libraryItem.appId}, appName=$appName, hasDownloadInfo=${downloadInfo != null}, active=$isActive, progress=$progress, result=$downloading")
        return downloading
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        val epicGame = EpicService.getEpicGameOf(libraryItem.appId.removePrefix("EPIC_"))
        val appName = epicGame?.appName ?: return 0f
        val downloadInfo = EpicService.getDownloadInfo(appName)
        val progress = downloadInfo?.getProgress() ?: 0f
        Timber.tag(TAG).d("getDownloadProgress: appId=${libraryItem.appId}, appName=$appName, progress=$progress")
        return progress
    }

    override fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        Timber.tag(TAG).i("onDownloadInstallClick: appId=${libraryItem.appId}, name=${libraryItem.name}")
        val epicGame = EpicService.getEpicGameOf(libraryItem.appId.removePrefix("EPIC_"))
        val appName = epicGame?.appName ?: run {
            Timber.tag(TAG).e("Cannot download: appName not found for ${libraryItem.appId}")
            return
        }

        val downloadInfo = EpicService.getDownloadInfo(appName)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val installed = isInstalled(context, libraryItem)

        Timber.tag(TAG).d("onDownloadInstallClick: appId=${libraryItem.appId}, appName=$appName, isDownloading=$isDownloading, installed=$installed")

        if (isDownloading) {
            // Cancel ongoing download
            Timber.tag(TAG).i("Cancelling Epic download for: $appName")
            downloadInfo.cancel()
            EpicService.cleanupDownload(appName)
        } else if (installed) {
            // Already installed: launch game
            Timber.tag(TAG).i("Epic game already installed, launching: $appName")
            onClickPlay(false)
        } else {
            // Show install confirmation dialog
            Timber.tag(TAG).i("Showing install confirmation dialog for: ${libraryItem.appId}")
            showInstallDialog(libraryItem.appId)
        }
    }

    /**
     * Perform the actual download after confirmation
     * Delegates to EpicService/EpicManager for proper service layer separation
     */
    private fun performDownload(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        val epicGame = EpicService.getEpicGameOf(libraryItem.appId.removePrefix("EPIC_"))
        val appName = epicGame?.appName ?: run {
            Timber.tag(TAG).e("Cannot download: appName not found for ${libraryItem.appId}")
            return
        }

        Timber.i("Starting Epic game download: $appName")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get install path
                val installPath = EpicConstants.getGameInstallPathByAppName(appName)
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
                val result = EpicService.downloadGame(context, appName, installPath)

                if (result.isSuccess) {
                    Timber.i("Epic game download started successfully: $appName")
                    // Success toast will be shown when download completes (monitored by EpicService)
                } else {
                    Timber.e("Failed to start Epic game download: $appName - ${result.exceptionOrNull()?.message}")
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
        val epicGame = EpicService.getEpicGameOf(libraryItem.appId)
        val appName = epicGame?.appName ?: return
        val downloadInfo = EpicService.getDownloadInfo(appName)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        Timber.tag(TAG).d("onPauseResumeClick: appId=${libraryItem.appId}, appName=$appName, isDownloading=$isDownloading")

        if (isDownloading) {
            // Cancel/pause download
            Timber.tag(TAG).i("Pausing Epic download: $appName")
            EpicService.cleanupDownload(appName)
            downloadInfo.cancel()
        } else {
            // Resume download (restart from beginning for now)
            Timber.tag(TAG).i("Resuming Epic download: $appName")
            onDownloadInstallClick(context, libraryItem) {}
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onDeleteDownloadClick: appId=${libraryItem.appId}")
        val epicGame = EpicService.getEpicGameOf(libraryItem.appId.removePrefix("EPIC_"))
        val appName = epicGame?.appName ?: return
        val downloadInfo = EpicService.getDownloadInfo(appName)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val isInstalled = isInstalled(context, libraryItem)
        Timber.tag(TAG).d("onDeleteDownloadClick: appId=${libraryItem.appId}, appName=$appName, isDownloading=$isDownloading, isInstalled=$isInstalled")

        if (isDownloading) {
            // Cancel download immediately if currently downloading
            Timber.tag(TAG).i("Cancelling active download for Epic game: $appName")
            EpicService.cleanupDownload(appName)
            downloadInfo.cancel()
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
                val result = EpicService.deleteGame(context, libraryItem.appId.removePrefix("EPIC_"))

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

    override fun getExportFileExtension(): String = ".pcgame"

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}")
        return try {
            val epicGame = EpicService.getEpicGameOf(libraryItem.appId.removePrefix("EPIC_"))
            val appName = epicGame?.appName ?: return null
            val path = EpicService.getInstallPath(appName)
            Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}, appName=$appName, path=$path")
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
                    val epicGame = EpicService.getEpicGameOf(libraryItem.appId.removePrefix("EPIC_"))
                    val appName = epicGame?.appName
                    if (appName != null) {
                        val downloadInfo = EpicService.getDownloadInfo(appName)
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
                            Timber.tag(TAG).d("[OBSERVE] Progress listener attached for $appName")
                        }
                    }
                } else {
                    // Download stopped/completed - clean up listener
                    currentProgressListener?.let { listener ->
                        val epicGame = EpicService.getEpicGameOf(libraryItem.appId.removePrefix("EPIC_"))
                        val appName = epicGame?.appName
                        if (appName != null) {
                            val downloadInfo = EpicService.getDownloadInfo(appName)
                            downloadInfo?.removeProgressListener(listener)
                        }
                        currentProgressListener = null
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
            Timber.tag(TAG).d("[OBSERVE] LibraryInstallStatusChanged event received: event.appId=${event.appId}, libraryItem.appId=${libraryItem.appId}, match=${event.appId == libraryItem.appId}")
            if (event.appId == libraryItem.appId) {
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

        // Monitor install dialog state
        var showInstallDialog by remember { mutableStateOf(shouldShowInstallDialog(libraryItem.appId)) }

        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowInstallDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    Timber.tag(TAG).d("Install dialog state changed: $shouldShow")
                    showInstallDialog = shouldShow
                }
        }

        // Show install confirmation dialog
        if (showInstallDialog) {
            val appId = libraryItem.appId
            val epicGame = remember(appId) {
                EpicService.getEpicGameOf(appId.removePrefix("EPIC_"))
            }

            val downloadBytes = epicGame?.downloadSize ?: 0L
            val installBytes = epicGame?.installSize ?: 0L
            val downloadText = if (downloadBytes > 0L) formatBytes(downloadBytes) else "Unknown"
            val installText = if (installBytes > 0L) formatBytes(installBytes) else "Unknown"

            AlertDialog(
                onDismissRequest = {
                    hideInstallDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.epic_install_game_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.epic_install_game_message,
                            libraryItem.name,
                            downloadText,
                            installText,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideInstallDialog(libraryItem.appId)
                            performDownload(context, libraryItem) {}
                        },
                    ) {
                        Text(stringResource(R.string.install))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            hideInstallDialog(libraryItem.appId)
                        },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // Show uninstall confirmation dialog
        if (showUninstallDialog) {
            val appId = libraryItem.appId
            val epicGame = remember(appId) {
                EpicService.getEpicGameOf(appId.removePrefix("EPIC_"))
            }

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
