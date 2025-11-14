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
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.OpenContainerScanner
import app.gamenative.utils.StorageUtils
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.net.Uri

/**
 * Open Container-specific implementation of BaseAppScreen
 */
class OpenContainerAppScreen : BaseAppScreen() {
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
            OpenContainerScanner.getFolderPathFromAppId(libraryItem.appId)
        }
        
        // Helper function to find SteamGridDB images in the game folder
        fun findSteamGridDBImage(folder: File, imageType: String): String? {
            return folder.listFiles()?.firstOrNull { file ->
                file.name.startsWith("steamgriddb_$imageType") && 
                (file.name.endsWith(".png", ignoreCase = true) || 
                 file.name.endsWith(".jpg", ignoreCase = true) || 
                 file.name.endsWith(".webp", ignoreCase = true))
            }?.let { Uri.fromFile(it).toString() }
        }
        
        // Check for all SteamGridDB images in the game folder
        // Hero view uses horizontal grid (grid_hero)
        val heroImageUrl = remember(gameFolderPath) {
            gameFolderPath?.let { path ->
                val folder = File(path)
                findSteamGridDBImage(folder, "grid_hero")
            }
        }
        
        // Capsule view uses vertical grid (grid_capsule)
        val capsuleUrl = remember(gameFolderPath) {
            gameFolderPath?.let { path ->
                val folder = File(path)
                findSteamGridDBImage(folder, "grid_capsule")
            }
        }
        
        // Header view uses heroes endpoint (hero, but not grid_hero)
        val headerUrl = remember(gameFolderPath) {
            gameFolderPath?.let { path ->
                val folder = File(path)
                // Find hero image but exclude grid_hero
                folder.listFiles()?.firstOrNull { file ->
                    file.name.startsWith("steamgriddb_hero") && 
                    !file.name.contains("grid") &&
                    (file.name.endsWith(".png", ignoreCase = true) || 
                     file.name.endsWith(".jpg", ignoreCase = true) || 
                     file.name.endsWith(".webp", ignoreCase = true))
                }?.let { Uri.fromFile(it).toString() }
            }
        }
        
        val logoUrl = remember(gameFolderPath) {
            gameFolderPath?.let { path ->
                val folder = File(path)
                findSteamGridDBImage(folder, "logo")
            }
        }
        
        // Note: iconUrl is intentionally null - we extract icons from exe files
        // and don't use SteamGridDB icons
        
        // Try to get release date from SteamGridDB metadata if available
        var releaseDate by remember { mutableStateOf(0L) }
        LaunchedEffect(gameFolderPath) {
            gameFolderPath?.let { path ->
                val folder = File(path)
                // Check for release date file (created when images are fetched)
                val releaseDateFile = File(folder, ".steamgriddb_release_date")
                if (releaseDateFile.exists()) {
                    try {
                        val dateStr = releaseDateFile.readText().trim()
                        if (dateStr.isNotEmpty()) {
                            releaseDate = dateStr.toLongOrNull() ?: 0L
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
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
        
        // Open Container games don't have Steam metadata, so we use basic info
        return GameDisplayInfo(
            name = libraryItem.name,
            developer = "Unknown", // Open Container games don't have developer info
            releaseDate = releaseDate,
            heroImageUrl = heroImageUrl,
            iconUrl = null, // Icons are extracted from exe files, not from SteamGridDB
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
        // Open Container games are always considered "installed" since they're external
        return true
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        // Open Container games cannot be downloaded through the app
        return false
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        // Open Container games don't have downloads
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
        // Check if there are multiple valid exe files and none is selected
        val gameFolderPath = OpenContainerScanner.getFolderPathFromAppId(libraryItem.appId)
        if (gameFolderPath != null) {
            val allExes = OpenContainerScanner.findAllValidExeFiles(gameFolderPath)
            if (allExes.size > 1) {
                // Check if container has an executable selected
                val containerManager = ContainerManager(context)
                val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
                if (container.executablePath.isEmpty()) {
                    // Multiple exes found but none selected - show dialog
                    showExeSelectionDialog(libraryItem.appId)
                    return
                }
            }
        }
        
        // Launch the game
        PluviaApp.events.emit(AndroidEvent.ExternalGameLaunch(libraryItem.appId))
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        // Not applicable for Open Container games
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        // Show delete confirmation dialog for Open Container games
        showDeleteDialog(libraryItem.appId)
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        // Not applicable for Open Container games
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
        // Open Container games don't have source-specific menu options
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

    override fun getExportFileExtension(): String = ".game"

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
                title = { Text("Executable Selection Required") },
                text = {
                    Text(
                        text = "This game has multiple executable files. Please select which one to use in the container settings before launching."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideExeSelectionDialog(libraryItem.appId)
                            // Open container settings dialog
                            onEditContainer()
                        }
                    ) {
                        Text("Open Container Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        hideExeSelectionDialog(libraryItem.appId)
                    }) {
                        Text("Close")
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
                title = { Text("Delete Game") },
                text = {
                    Text(
                        text = "Are you sure you want to delete \"${libraryItem.name}\"? " +
                                "This will permanently delete the game folder and cannot be undone."
                    )
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
                                    
                                    // Delete the game folder on background thread
                                    withContext(Dispatchers.IO) {
                                        val gameFolderPath = OpenContainerScanner.getFolderPathFromAppId(libraryItem.appId)
                                        if (gameFolderPath != null) {
                                            val gameFolder = File(gameFolderPath)
                                            if (gameFolder.exists()) {
                                                gameFolder.deleteRecursively()
                                            }
                                        }
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


