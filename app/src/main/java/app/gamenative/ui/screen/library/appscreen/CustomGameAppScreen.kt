package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.GameImageUtils
import app.gamenative.utils.StorageUtils
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.net.Uri
import timber.log.Timber

/**
 * Custom Game-specific implementation of BaseAppScreen
 */
class CustomGameAppScreen : BaseAppScreen() {
    companion object {
        // Shared state for exe selection dialog - list of appIds that should show the dialog
        private val exeSelectionDialogAppIds = mutableStateListOf<String>()

        fun showExeSelectionDialog(appId: String) {
            if (!exeSelectionDialogAppIds.contains(appId)) {
                exeSelectionDialogAppIds.add(appId)
            }
        }

        fun hideExeSelectionDialog(appId: String) {
            exeSelectionDialogAppIds.remove(appId)
        }

        fun shouldShowExeSelectionDialog(appId: String): Boolean {
            return exeSelectionDialogAppIds.contains(appId)
        }

        // Shared state for delete dialog - list of appIds that should show the dialog
        private val deleteDialogAppIds = mutableStateListOf<String>()

        fun showDeleteDialog(appId: String) {
            if (!deleteDialogAppIds.contains(appId)) {
                deleteDialogAppIds.add(appId)
            }
        }

        fun hideDeleteDialog(appId: String) {
            deleteDialogAppIds.remove(appId)
        }

        fun shouldShowDeleteDialog(appId: String): Boolean {
            return deleteDialogAppIds.contains(appId)
        }
    }
    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
    ): GameDisplayInfo {
        val gameFolderPath = remember(libraryItem.appId) {
            CustomGameScanner.getFolderPathFromAppId(libraryItem.appId)
        }

        // Observe media changes to refresh images when custom media is updated
        val mediaVersion by app.gamenative.utils.CustomMediaUtils.mediaVersionFlow.collectAsState(initial = 0)

        // Use centralized function to get images with proper priority: Custom media -> SteamGridDB -> Steam URLs
        val heroImageUrl = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(
                libraryItem = libraryItem,
                imageType = "grid_hero"
            )
        }

        val capsuleUrl = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(
                libraryItem = libraryItem,
                imageType = "grid_capsule"
            )
        }

        val headerUrl = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(
                libraryItem = libraryItem,
                imageType = "header"
            )
        }

        val logoUrl = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(
                libraryItem = libraryItem,
                imageType = "logo"
            )
        }

        val iconUrl = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(
                libraryItem = libraryItem,
                imageType = "icon"
            )
        }

        // Try to get release date from .gamenative metadata if available
        var releaseDate by remember { mutableStateOf(0L) }
        LaunchedEffect(gameFolderPath) {
            gameFolderPath?.let { path ->
                val folder = File(path)
                // Get release date from metadata
                val metadata = app.gamenative.utils.GameMetadataManager.read(folder)
                releaseDate = metadata?.releaseDate ?: 0L
            }
        }

        // Calculate folder size on disk (async, will update via state)
        var sizeOnDisk by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(gameFolderPath) {
            gameFolderPath?.let { path ->
                withContext(Dispatchers.IO) {
                    try {
                        val folderSize = StorageUtils.getFolderSize(path)
                        val formattedSize = StorageUtils.formatBinarySize(folderSize)
                        sizeOnDisk = formattedSize
                    } catch (e: Exception) {
                        // Ignore errors, sizeOnDisk will remain null
                    }
                }
            }
        }

        // Custom Games don't have Steam metadata, so we use basic info
        return GameDisplayInfo(
            name = libraryItem.name,
            developer = context.getString(R.string.custom_game_unknown_developer), // Custom Games don't have developer info
            releaseDate = releaseDate,
            heroImageUrl = heroImageUrl,
            iconUrl = iconUrl, // Icons are extracted from exe files, not from SteamGridDB
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            installLocation = gameFolderPath,
            sizeOnDisk = sizeOnDisk, // Calculated folder size
            sizeFromStore = null, // No store size info
            lastPlayedText = null, // Not tracked
            playtimeText = null, // Not tracked
            logoUrl = logoUrl,
            capsuleUrl = capsuleUrl,
            headerUrl = headerUrl,
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        // Custom Games are always considered "installed" since they're external
        return true
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        // Custom Games cannot be downloaded through the app
        return false
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        // Custom Games don't have downloads
        return false
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        return 0f
    }

    override fun isUpdatePending(context: Context, libraryItem: LibraryItem): Boolean {
        return false
    }

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit
    ) {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        if (container.executablePath.isEmpty()) {
            // Multiple exes found but none selected - show dialog
            showExeSelectionDialog(libraryItem.appId)
            return
        }
        // Launch the game - executable check is now done in preLaunchApp
        PluviaApp.events.emit(AndroidEvent.ExternalGameLaunch(libraryItem.appId))
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        // Not applicable for Custom Games
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        // Show delete confirmation dialog for Custom Games
        showDeleteDialog(libraryItem.appId)
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        // Not applicable for Custom Games
    }

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        // Custom Games are always "installed" (they're external folders)
        return CustomGameScanner.getFolderPathFromAppId(libraryItem.appId)
    }

    override fun getGameFolderPathForImageFetch(context: Context, libraryItem: LibraryItem): String? {
        // For Custom Games, the install path is the same as the folder path
        val path = getInstallPath(context, libraryItem)
        Timber.tag("CustomGameAppScreen").d("getGameFolderPathForImageFetch - appId: ${libraryItem.appId}, path: ${path ?: "null"}")
        return path
    }

    override fun onAfterFetchImages(context: Context, libraryItem: LibraryItem, gameFolderPath: String) {
        // Extract icon from executable after fetching images
        Timber.tag("CustomGameAppScreen").d("onAfterFetchImages called - appId: ${libraryItem.appId}, gameFolderPath: $gameFolderPath")
        
        // Use the centralized icon extraction function
        CustomGameScanner.extractIconFromExecutable(context, libraryItem.appId)
    }

    @Composable
    override fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean
    ): List<AppMenuOption> {
        // Custom Games don't have source-specific menu options
        // Delete button is handled via onDeleteDownloadClick and shown next to play button
        return emptyList()
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)
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
        val scope = rememberCoroutineScope()

        // Track exe selection dialog state
        var showExeDialog by remember { mutableStateOf(shouldShowExeSelectionDialog(libraryItem.appId)) }

        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowExeSelectionDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    showExeDialog = shouldShow
                }
        }

        // Track delete dialog state
        var showDeleteDialog by remember { mutableStateOf(shouldShowDeleteDialog(libraryItem.appId)) }

        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowDeleteDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    showDeleteDialog = shouldShow
                }
        }

        // Exe selection required dialog
        if (showExeDialog) {
            AlertDialog(
                onDismissRequest = {
                    hideExeSelectionDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.custom_game_exe_selection_title)) },
                text = {
                    Text(text = stringResource(R.string.custom_game_exe_selection_message))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideExeSelectionDialog(libraryItem.appId)
                            // Open container settings dialog
                            onEditContainer()
                        }
                    ) {
                        Text(stringResource(R.string.custom_game_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        hideExeSelectionDialog(libraryItem.appId)
                    }) {
                        Text(stringResource(R.string.close))
                    }
                }
            )
        }

        // Delete confirmation dialog
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = {
                    hideDeleteDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.custom_game_delete_title)) },
                text = {
                    Text(text = stringResource(R.string.custom_game_delete_message, libraryItem.name))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideDeleteDialog(libraryItem.appId)

                            // Delete the game folder and container
                            scope.launch {
                                try {
                                    // Delete the container first (needs to be on main thread)
                                    withContext(Dispatchers.Main) {
                                        ContainerUtils.deleteContainer(context, libraryItem.appId)
                                    }

                                    // Remove from manual folders list and invalidate cache
                                    withContext(Dispatchers.IO) {
                                        val folderPath = CustomGameScanner.getFolderPathFromAppId(libraryItem.appId)
                                        if (folderPath != null) {
                                            val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
                                            manualFolders.remove(folderPath)
                                            PrefManager.customGameManualFolders = manualFolders
                                        }
                                        CustomGameScanner.invalidateCache()
                                    }

                                    // Navigate back and show notification
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "\"${libraryItem.name}\" has been deleted",
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        // Small delay to ensure file system updates are complete
                                        // before navigating back (list will auto-refresh when displayed)
                                        delay(100)

                                        // Navigate back to game list
                                        onBack()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "Failed to delete game: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        hideDeleteDialog(libraryItem.appId)
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}


