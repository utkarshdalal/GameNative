package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.runtime.Composable
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerData

/**
 * Open Container-specific implementation of BaseAppScreen
 */
class OpenContainerAppScreen : BaseAppScreen() {
    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem
    ): GameDisplayInfo {
        // Open Container games don't have Steam metadata, so we use basic info
        return GameDisplayInfo(
            name = libraryItem.name,
            developer = "Unknown", // Open Container games don't have developer info
            releaseDate = 0L, // No release date available
            heroImageUrl = null, // No hero image for Open Container games
            iconUrl = null, // No icon URL for Open Container games
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            installLocation = null, // Open Container games are external
            sizeOnDisk = null, // Size not tracked for Open Container games
            sizeFromStore = null, // No store size info
            lastPlayedText = null, // Not tracked
            playtimeText = null, // Not tracked
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
        // For Open Container games, this just launches the game
        PluviaApp.events.emit(AndroidEvent.ExternalGameLaunch(libraryItem.appId))
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        // Not applicable for Open Container games
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        // Not applicable for Open Container games
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        // Not applicable for Open Container games
    }

    override fun getOptionsMenu(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit
    ): List<AppMenuOption> {
        val menuOptions = mutableListOf<AppMenuOption>()
        
        // Edit Container option (always available)
        menuOptions.add(
            AppMenuOption(
                optionType = AppOptionMenuType.EditContainer,
                onClick = onEditContainer
            )
        )
        
        // Since Open Container games are always "installed", show play/run options
        val isInstalled = isInstalled(context, libraryItem)
        if (isInstalled) {
            menuOptions.add(
                AppMenuOption(
                    AppOptionMenuType.RunContainer,
                    onClick = {
                        onClickPlay(true)
                    },
                )
            )
        }
        
        menuOptions.add(
            AppMenuOption(
                optionType = AppOptionMenuType.SubmitFeedback,
                onClick = {
                    PluviaApp.events.emit(AndroidEvent.ShowGameFeedback(libraryItem.appId))
                },
            )
        )
        
        menuOptions.add(
            AppMenuOption(
                optionType = AppOptionMenuType.GetSupport,
                onClick = {
                    // This would open support link - handled by base class if needed
                },
            )
        )
        
        return menuOptions
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)
    }

    override fun supportsContainerConfig(): Boolean = true

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit
    ) {
        // No additional dialogs for Open Container games
    }
}

