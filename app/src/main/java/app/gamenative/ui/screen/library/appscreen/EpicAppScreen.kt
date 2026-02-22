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
import androidx.compose.runtime.mutableStateMapOf
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
import app.gamenative.enums.Marker
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ContainerUtils.extractGameIdFromContainerId
import app.gamenative.utils.MarkerUtils
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

        // Shared state for game manager dialog - map of gameId to GameManagerDialogState
        private val gameManagerDialogStates = mutableStateMapOf<Int, app.gamenative.ui.component.dialog.state.GameManagerDialogState>()

        fun showGameManagerDialog(gameId: Int, state: app.gamenative.ui.component.dialog.state.GameManagerDialogState) {
            Timber.tag(TAG).d("showGameManagerDialog: gameId=$gameId")
            gameManagerDialogStates[gameId] = state
        }

        fun hideGameManagerDialog(gameId: Int) {
            Timber.tag(TAG).d("hideGameManagerDialog: gameId=$gameId")
            gameManagerDialogStates.remove(gameId)
        }

        fun getGameManagerDialogState(gameId: Int): app.gamenative.ui.component.dialog.state.GameManagerDialogState? {
            return gameManagerDialogStates[gameId]
        }
    }

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo {
        Timber.tag(TAG).d("getGameDisplayInfo: appId=${libraryItem.appId}, name=${libraryItem.name}")
        // Use gameId to look up the Epic game
        val gameId = libraryItem.gameId

        var epicGame by remember(gameId) { mutableStateOf<EpicGame?>(null) }
        var dlcTitles by remember(gameId) { mutableStateOf<List<EpicGame>>(emptyList()) }

        // Listen for install status changes to refresh game data
        DisposableEffect(gameId) {
            val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.appId == gameId) {
                    Timber.tag(TAG).d("Install status changed, refreshing game data for $gameId")
                    val game = EpicService.getEpicGameOf(gameId)
                    epicGame = game
                    if (game != null) {
                        dlcTitles = EpicService.getDLCForGame(game.id)
                    }
                }
            }
            app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
            onDispose {
                app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
            }
        }

        // Fetch install size from manifest if not already available
        LaunchedEffect(gameId) {
            val game = EpicService.getEpicGameOf(gameId)
            if (
                game != null &&
                !game.isInstalled &&
                (game.installSize == 0L || game.downloadSize == 0L || game.downloadSize > game.installSize)
            ) {
                Timber.tag("Epic").d("Install size not available for ${game.title}, fetching from manifest...")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val sizes = EpicService.fetchManifestSizes(context, game.id)
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
                            // Update state on Main dispatcher to ensure thread safety
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                epicGame = updatedGame
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("Epic").e(e, "Failed to fetch install size for ${game.title}")
                    }
                }
            }
        }

        LaunchedEffect(gameId) {
            val game = EpicService.getEpicGameOf(gameId)
            epicGame = game

            // We should be able to get the developer most of the time (which can populate).
            // The installSize & download Size are empty until we grab the manifest. We can MAYBE do this parallel and then update them.
            // We can also get some of these bits of detail from gamesDB etc. - Release date, developer & publisher.
            // Most import parts are id, title, namespace, appName, catalogId.
            // We will also need to let them know whether cloudsaves are enabled.
            if (game != null) {
                // Log all game details
                Timber.tag(TAG).i("=== Epic Game Details ===")
                Timber.tag(TAG).i("ID: ${game.id}")
                Timber.tag(TAG).i("Title: ${game.title}")
                Timber.tag(TAG).i("App Name: ${game.appName}")
                Timber.tag(TAG).i("Namespace: ${game.namespace}")
                Timber.tag(TAG).i("Catalog Item ID: ${game.catalogId}")
                Timber.tag(TAG).i("Developer: ${game.developer}")
                Timber.tag(TAG).i("Install Size: ${game.installSize} bytes (${StringUtils.formatBytes(game.installSize)})")
                Timber.tag(TAG).i("Download Size: ${game.downloadSize} bytes (${StringUtils.formatBytes(game.downloadSize)})")
                Timber.tag(TAG).i("Cloud Save Enabled: ${game.cloudSaveEnabled}")
                Timber.tag(TAG).i("========================")

                val fetchedDlcTitles = EpicService.getDLCForGame(game.id)
                dlcTitles = fetchedDlcTitles
                if (fetchedDlcTitles.isNotEmpty()) {
                    Timber.tag(TAG).i("DLC Count: ${fetchedDlcTitles.size}")
                    for (title in fetchedDlcTitles) {
                        Timber.tag("Epic").d("DLC Found: ${title.title}")
                    }
                } else {
                    Timber.tag(TAG).i("DLC Count: 0")
                }
                if (fetchedDlcTitles.isNotEmpty()) {
                    val installedDlcs = fetchedDlcTitles.count { it.isInstalled }
                    Timber.tag(TAG).i("DLC Manager: $installedDlcs/${fetchedDlcTitles.size} installed")
                }
            } else {
                Timber.tag(TAG).w("No Epic game found for gameId: $gameId")
            }
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

        return try {
            val epicGame = EpicService.getEpicGameOf(libraryItem.gameId)
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

    override fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean {
        val game = EpicService.getEpicGameOf(libraryItem.gameId) ?: return false
        if (game.isInstalled) return false // Already installed (including old installs with no marker)
        val path = EpicConstants.getGameInstallPath(context, game.appName)
        return File(path).exists() && !MarkerUtils.hasMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
    }

    override fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        Timber.tag(TAG).i("onDownloadInstallClick: appId=${libraryItem.appId}, name=${libraryItem.name}")

        val game = EpicService.getEpicGameOf(libraryItem.gameId)

        if (game == null) {
            Timber.e("No game found with gameId: ${libraryItem.gameId}")
            return
        }

        val gameId = libraryItem.gameId
        val downloadInfo = EpicService.getDownloadInfo(gameId)
        val isDownloading = downloadInfo != null && (downloadInfo.getProgress() ?: 0f) < 1f
        val installed = isInstalled(context, libraryItem)

        Timber.tag(TAG).d("onDownloadInstallClick: appId=${libraryItem.appId}, gameId=$gameId, isDownloading=$isDownloading, installed=$installed")

        if (isDownloading) {
            // Show cancel download dialog
            showInstallDialog(
                libraryItem.appId,
                app.gamenative.ui.component.dialog.state.MessageDialogState(
                    visible = true,
                    type = app.gamenative.ui.enums.DialogType.CANCEL_APP_DOWNLOAD,
                    title = context.getString(R.string.cancel_download_prompt_title),
                    message = context.getString(R.string.epic_cancel_download_message),
                    confirmBtnText = context.getString(R.string.yes),
                    dismissBtnText = context.getString(R.string.no),
                )
            )
        } else if (installed) {
            // Already installed: launch game
            Timber.tag(TAG).i("Epic game already installed, launching: $gameId")
            onClickPlay(false)
        } else {
            // Show game manager dialog with DLC selection
            Timber.tag(TAG).i("Showing game manager dialog for: ${libraryItem.appId}")
            showGameManagerDialog(
                gameId,
                app.gamenative.ui.component.dialog.state.GameManagerDialogState(visible = true)
            )
        }
    }

    /**
     * Perform the actual download after confirmation
     * Delegates to EpicService/EpicManager for proper service layer separation
     * @param scope Lifecycle-aware CoroutineScope from the calling composable
     * @param selectedGameIds List of game IDs to download (base game + selected DLCs)
     */
    private fun performDownload(scope: CoroutineScope, context: Context, libraryItem: LibraryItem, selectedGameIds: List<Int>, onClickPlay: (Boolean) -> Unit) {
        Timber.tag(TAG).i("Starting Epic game download: ${libraryItem.gameId} with ${selectedGameIds.size} items (including DLCs)")
        scope.launch(Dispatchers.IO) {
            try {
                // Get the game to access its title/appName
                val game = EpicService.getEpicGameOf(libraryItem.gameId)
                if (game == null) {
                    Timber.e("Game not found: ${libraryItem.gameId}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.epic_game_not_found), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Get install path
                val installPath = EpicConstants.getGameInstallPath(context, game.appName)
                Timber.tag(TAG).d("Downloading Epic game to: $installPath")

                // Determine if we should download DLCs (if more than just the base game is selected)
                val withDlcs = selectedGameIds.size > 1
                Timber.tag(TAG).d("Download with DLCs: $withDlcs (${selectedGameIds.size} items selected)")

                // Show starting download toast
                withContext(Dispatchers.Main) {
                    val message = if (withDlcs) {
                        context.getString(R.string.epic_starting_download_with_dlcs, game.title)
                    } else {
                        context.getString(R.string.epic_starting_download, game.title)
                    }
                    android.widget.Toast.makeText(
                        context,
                        message,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }

                // Start download - EpicService will handle monitoring, database updates, verification, and events
                // Pass the selected DLC IDs (excluding the base game)
                val dlcIds = selectedGameIds.filter { it != libraryItem.gameId }
                val result = EpicService.downloadGame(context, libraryItem.gameId, dlcIds, installPath)

                if (result.isSuccess) {
                    Timber.tag(TAG).i("Epic game download started successfully: ${libraryItem.gameId}")
                    // Success toast will be shown when download completes (monitored by EpicService)
                } else {
                    Timber.e("Failed to start Epic game download: ${libraryItem.gameId} - ${result.exceptionOrNull()?.message}")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.epic_download_failed, result.exceptionOrNull()?.message ?: ""),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during Epic download")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.epic_download_error, e.message ?: ""),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onPauseResumeClick: appId=${libraryItem.appId}")

        if (isDownloading(context, libraryItem)) {
            val downloadInfo = EpicService.getDownloadInfo(libraryItem.gameId)
            // Cancel/pause download
            Timber.tag(TAG).i("Pausing Epic download: ${libraryItem.gameId}")
            downloadInfo?.cancel()
            CoroutineScope(Dispatchers.Main.immediate).launch {
                EpicService.cleanupDownload(context, libraryItem.gameId)
            }
        } else {
            // Resume download (restart from beginning for now)
            Timber.tag(TAG).i("Resuming Epic download: ${libraryItem.gameId}")
            onDownloadInstallClick(context, libraryItem) {}
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onDeleteDownloadClick: appId=${libraryItem.appId}")

        if (isDownloading(context, libraryItem) || hasPartialDownload(context, libraryItem)) {
            // Show cancel download dialog when downloading
            showInstallDialog(
                libraryItem.appId,
                app.gamenative.ui.component.dialog.state.MessageDialogState(
                    visible = true,
                    type = app.gamenative.ui.enums.DialogType.CANCEL_APP_DOWNLOAD,
                    title = context.getString(R.string.cancel_download_prompt_title),
                    message = context.getString(R.string.epic_delete_download_message),
                    confirmBtnText = context.getString(R.string.yes),
                    dismissBtnText = context.getString(R.string.no)
                )
            )
        } else if (isInstalled(context, libraryItem)) {
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
        Timber.tag(TAG).i("Uninstalling Epic game: ${libraryItem.appId}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = EpicService.deleteGame(context, libraryItem.gameId)

                if (result.isSuccess) {
                    Timber.tag(TAG).i("Epic game uninstalled successfully: ${libraryItem.appId}")
                } else {
                    Timber.e("Failed to uninstall Epic game: ${libraryItem.appId} - ${result.exceptionOrNull()?.message}")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.epic_uninstall_failed, result.exceptionOrNull()?.message ?: ""),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error uninstalling Epic game")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.epic_uninstall_error, e.message ?: ""),
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

    override fun getExportFileExtension(): String = ".epic"

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}")
        return try {
            val path = EpicService.getInstallPath(libraryItem.gameId)
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
        val epicGame = EpicService.getEpicGameOf(libraryItem.gameId)
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
                                    context.getString(R.string.epic_cloud_sync_starting),
                                    Toast.LENGTH_SHORT,
                                ).show()

                                val result = withContext(Dispatchers.IO) {
                                    EpicCloudSavesManager.syncCloudSaves(
                                        context,
                                        libraryItem.gameId,
                                        preferredAction = "download", // Force download for testing
                                    )
                                }

                                Toast.makeText(
                                    context,
                                    if (result) context.getString(R.string.epic_cloud_sync_success) else context.getString(R.string.epic_cloud_sync_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "[Cloud Saves] Sync failed")
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.epic_cloud_sync_error, e.message ?: ""),
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

        // Check if download is already in progress and attach listener immediately
        val existingDownloadInfo = EpicService.getDownloadInfo(libraryItem.gameId)
        if (existingDownloadInfo != null && (existingDownloadInfo.getProgress() ?: 0f) < 1f) {
            Timber.tag(TAG).d("[OBSERVE] Download already in progress for ${libraryItem.gameId}, attaching progress listener immediately")
            val progressListener: (Float) -> Unit = { progress ->
                onProgressChanged(progress)
            }
            existingDownloadInfo.addProgressListener(progressListener)
            currentProgressListener = progressListener

            // Add cleanup for this listener
            disposables += {
                currentProgressListener?.let { listener ->
                    existingDownloadInfo.removeProgressListener(listener)
                    currentProgressListener = null
                }
            }
            // Report current progress immediately
            existingDownloadInfo.getProgress()?.let { currentProgress ->
                onProgressChanged(currentProgress)
            }
        }

        // Listen for download status changes
        val downloadStatusListener: (app.gamenative.events.AndroidEvent.DownloadStatusChanged) -> Unit = { event ->
            Timber.tag(TAG).d("[OBSERVE] DownloadStatusChanged event received: event.appId=${event.appId}, libraryItem.gameId=${libraryItem.gameId}, match=${event.appId == libraryItem.gameId}")
            if (event.appId == libraryItem.gameId) {
                Timber.tag(TAG).d("[OBSERVE] Download status changed for ${libraryItem.gameId}, isDownloading=${event.isDownloading}")
                if (event.isDownloading) {
                    // Download started - attach progress listener
                    val downloadInfo = EpicService.getDownloadInfo(libraryItem.gameId)
                    if (downloadInfo != null) {
                        // Remove previous listener if exists
                        currentProgressListener?.let { listener ->
                            downloadInfo.removeProgressListener(listener)
                        }
                        // Add new listener and track it
                        val progressListener: (Float) -> Unit = { progress ->
                            Timber.tag(TAG).v("[OBSERVE] Progress update received for ${libraryItem.gameId}: $progress")
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
                        Timber.tag(TAG).d("[OBSERVE] Progress listener attached for ${libraryItem.gameId}")
                    }
                } else {
                    // Download stopped/completed - clean up listener
                    currentProgressListener?.let { listener ->
                        val downloadInfo = EpicService.getDownloadInfo(libraryItem.gameId)
                        downloadInfo?.removeProgressListener(listener)
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
        val scope = rememberCoroutineScope()

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
        val gameId = libraryItem.gameId
        var installDialogState by remember(appId) {
            mutableStateOf(BaseAppScreen.getInstallDialogState(appId) ?: app.gamenative.ui.component.dialog.state.MessageDialogState(false))
        }
        LaunchedEffect(appId) {
            snapshotFlow { BaseAppScreen.getInstallDialogState(appId) }
                .collect { state ->
                    installDialogState = state ?: app.gamenative.ui.component.dialog.state.MessageDialogState(false)
                }
        }

        // Game manager dialog state
        var gameManagerDialogState by remember(gameId) {
            mutableStateOf(getGameManagerDialogState(gameId) ?: app.gamenative.ui.component.dialog.state.GameManagerDialogState(false))
        }
        LaunchedEffect(gameId) {
            snapshotFlow { getGameManagerDialogState(gameId) }
                .collect { state ->
                    gameManagerDialogState = state ?: app.gamenative.ui.component.dialog.state.GameManagerDialogState(false)
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
                        performDownload(scope, context, libraryItem, listOf(libraryItem.gameId)) {}
                    }
                }
                app.gamenative.ui.enums.DialogType.CANCEL_APP_DOWNLOAD -> {
                    {
                        Timber.tag(TAG).i("Cancelling/deleting Epic download for: $gameId")
                        val downloadInfo = EpicService.getDownloadInfo(gameId)
                        downloadInfo?.cancel()
                        scope.launch {
                            downloadInfo?.awaitCompletion()
                            EpicService.cleanupDownload(context, gameId)
                            EpicService.deleteGame(context, gameId)
                            withContext(Dispatchers.Main) {
                                BaseAppScreen.hideInstallDialog(appId)
                                app.gamenative.PluviaApp.events.emit(app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, false))
                                app.gamenative.PluviaApp.events.emit(app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId))
                            }
                        }
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

        // Game manager dialog (DLC selection)
        if (gameManagerDialogState.visible) {
            app.gamenative.ui.component.dialog.EpicGameManagerDialog(
                visible = true,
                onGetDisplayInfo = { context ->
                    getGameDisplayInfo(context, libraryItem)
                },
                onInstall = { selectedGameIds ->
                    hideGameManagerDialog(gameId)
                    performDownload(scope, context, libraryItem, selectedGameIds) {}
                },
                onDismissRequest = {
                    hideGameManagerDialog(gameId)
                }
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
