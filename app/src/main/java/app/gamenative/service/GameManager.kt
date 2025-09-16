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
    fun downloadGame(context: Context, appId: String): Result<DownloadInfo?>

    /**
     * Delete a game
     */
    fun deleteGame(context: Context, appId: String): Result<Unit>

    /**
     * Check if a game is installed
     */
    fun isGameInstalled(context: Context, appId: String): Boolean
    suspend fun isUpdatePending(appId: String): Boolean

    /**
     * Get the download info for a game
     */
    fun getDownloadInfo(appId: String): DownloadInfo?

    /**
     * Check if a game has a partial download
     */
    fun hasPartialDownload(appId: String): Boolean

    /**
     * Get the game disk size for a game
     */
    suspend fun getGameDiskSize(context: Context, appId: String): String

    /**
     * Get the download size for a game
     */
    fun getDownloadSize(appId: String): String

    /**
     * Check if a game is valid to download
     */
    fun isValidToDownload(appId: String): Boolean

    /**
     * Returns the app info for the given game (steam only, should be refactored)
     */
    fun getAppInfo(appId: String): SteamApp?

    /**
     * Returns the app dir path for the given game
     */
    fun getAppDirPath(appId: String): String

    /**
     * Get the platform-specific store URL for a game
     */
    fun getStoreUrl(appId: String): Uri

    /**
     * Launch a game with cloud save sync
     */
    suspend fun launchGameWithSaveSync(
        context: Context,
        appId: String,
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
        appId: String,
        container: Container,
        bootToContainer: Boolean,
        appLaunchInfo: LaunchInfo?,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
    ): String

    /**
     * Returns the release date for the given game
     */
    fun getReleaseDate(appId: String): String

    /**
     * Get the hero image for the given game
     */
    fun getHeroImage(appId: String): String

    /**
     * Returns the install info dialog for the given game
     */
    fun getInstallInfoDialog(context: Context, appId: String): MessageDialogState

    /**
     * Run code before launching the given game
     */
    fun runBeforeLaunch(context: Context, appId: String)

    /**
     * Get all games from this manager's source with wrappers pre-applied
     */
    fun getAllGames(): Flow<List<Game>>
}
