package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.gamenative.data.LibraryItem
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import com.winlator.container.ContainerData
import kotlinx.coroutines.delay

/**
 * Abstract base class for AppScreen implementations.
 * This defines the contract that all game source-specific screens must implement.
 */
abstract class BaseAppScreen {
    /**
     * Get the game display information for rendering the UI.
     * This is called to get all the data needed for the common UI layout.
     */
    @Composable
    abstract fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
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
     * Get the options menu items specific to this game source
     */
    abstract fun getOptionsMenu(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit
    ): List<app.gamenative.ui.data.AppMenuOption>

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
        onBack: () -> Unit,
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val displayInfo = getGameDisplayInfo(context, libraryItem)
        
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
        
        // Update download progress periodically if downloading
        LaunchedEffect(isDownloadingState, libraryItem.appId) {
            while (isDownloadingState) {
                downloadProgressState = getDownloadProgress(context, libraryItem)
                isDownloadingState = isDownloading(context, libraryItem)
                delay(500) // Update every 500ms
            }
        }
        
        // Update other states periodically
        LaunchedEffect(libraryItem.appId) {
            while (true) {
                isInstalledState = isInstalled(context, libraryItem)
                isValidToDownloadState = isValidToDownload(context, libraryItem)
                // Use suspend version if available, otherwise use regular method
                isUpdatePendingState = isUpdatePendingSuspend(context, libraryItem)
                delay(2000) // Update every 2 seconds
            }
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

        val optionsMenu = getOptionsMenu(context, libraryItem, onEditContainer, onBack, onClickPlay)

        // Render the common UI
        app.gamenative.ui.screen.library.AppScreenContent(
            displayInfo = displayInfo,
            isInstalled = isInstalledState,
            isValidToDownload = isValidToDownloadState,
            isDownloading = isDownloadingState,
            downloadProgress = downloadProgressState,
            isUpdatePending = isUpdatePendingState,
            onDownloadInstallClick = { 
                onDownloadInstallClick(context, libraryItem, onClickPlay)
                // Refresh state after action
                isInstalledState = isInstalled(context, libraryItem)
                isDownloadingState = isDownloading(context, libraryItem)
            },
            onPauseResumeClick = { 
                onPauseResumeClick(context, libraryItem)
                isDownloadingState = isDownloading(context, libraryItem)
            },
            onDeleteDownloadClick = { onDeleteDownloadClick(context, libraryItem) },
            onUpdateClick = { 
                onUpdateClick(context, libraryItem)
                isDownloadingState = isDownloading(context, libraryItem)
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

        // Render any additional dialogs
        AdditionalDialogs(libraryItem, onDismiss = {})
    }

    /**
     * Check if container configuration editing is supported
     */
    abstract fun supportsContainerConfig(): Boolean

    /**
     * Get additional dialogs to show (e.g., loading, message dialogs).
     * Override this to add source-specific dialogs.
     */
    @Composable
    open fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit
    ) {
        // Default: no additional dialogs
    }
}

