package app.gamenative.service

import android.content.Context
import android.net.Uri
import app.gamenative.data.DownloadInfo
import app.gamenative.data.Game
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.ui.component.dialog.state.MessageDialogState
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

interface GameManager {
    /**
     * Download a game
     */
    fun downloadGame(context: Context, libraryItem: LibraryItem): Result<DownloadInfo?>

    /**
     * Delete a game
     */
    fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit>

    /**
     * Check if a game is installed
     */
    fun isGameInstalled(context: Context, libraryItem: LibraryItem): Boolean

    /**
     * Check if an update is pending for a game
     */
    suspend fun isUpdatePending(libraryItem: LibraryItem): Boolean

    /**
     * Get the download info for a game
     */
    fun getDownloadInfo(libraryItem: LibraryItem): DownloadInfo?

    /**
     * Check if a game has a partial download
     */
    fun hasPartialDownload(libraryItem: LibraryItem): Boolean

    /**
     * Get the game disk size for a game
     */
    suspend fun getGameDiskSize(context: Context, libraryItem: LibraryItem): String

    /**
     * Create a FAKE libraryItem object for a game
     */
    fun createLibraryItem(appId: String, gameId: String, context: Context): LibraryItem

    /**
     * Get the download size for a game
     */
    fun getDownloadSize(libraryItem: LibraryItem): String

    /**
     * Check if a game is valid to download
     */
    fun isValidToDownload(library: LibraryItem): Boolean

    /**
     * Returns the app info for the given game (steam only, should be refactored)
     */
    fun getAppInfo(libraryItem: LibraryItem): SteamApp?

    /**
     * Returns the app dir path for the given game
     */
    fun getAppDirPath(appId: String): String

    /**
     * Get the platform-specific store URL for a game
     */
    fun getStoreUrl(libraryItem: LibraryItem): Uri

    /**
     * Launch a game with cloud save sync
     */
    suspend fun launchGameWithSaveSync(
        context: Context,
        libraryItem: LibraryItem,
        parentScope: CoroutineScope,
        ignorePendingOperations: Boolean = false,
        preferredSave: Int? = null,
    ): PostSyncInfo

    /**
     * Get the wine start command for platform-specific game launching
     * This handles the platform-specific logic for launching games
     */
    fun getWineStartCommand(
        context: Context,
        libraryItem: LibraryItem,
        container: Container,
        bootToContainer: Boolean,
        appLaunchInfo: LaunchInfo?,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
    ): String

    /**
     * Returns the release date for the given game
     */
    fun getReleaseDate(libraryItem: LibraryItem): String

    /**
     * Get the hero image for the given game
     */
    fun getHeroImage(libraryItem: LibraryItem): String


    /**
     * Get the icon image for the given game
     */
    fun getIconImage(libraryItem: LibraryItem): String

    /**
     * Returns the install info dialog for the given game
     */
    fun getInstallInfoDialog(context: Context, libraryItem: LibraryItem): MessageDialogState

    /**
     * Run code before launching the given game
     */
    fun runBeforeLaunch(context: Context, libraryItem: LibraryItem)

    /**
     * Get all games from this manager's source with wrappers pre-applied
     */
    fun getAllGames(): Flow<List<Game>>
}
