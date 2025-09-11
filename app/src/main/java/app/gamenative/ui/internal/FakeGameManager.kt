package app.gamenative.service

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.gamenative.data.DownloadInfo
import app.gamenative.data.Game
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.enums.SyncResult
import app.gamenative.ui.component.dialog.state.MessageDialogState
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fake GameManager implementation for previews and testing
 */
object FakeGameManager : GameManager {

    override fun downloadGame(context: Context, libraryItem: LibraryItem): Result<DownloadInfo?> {
        return Result.success(null)
    }

    override fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
        return Result.success(Unit)
    }

    override fun isGameInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        return libraryItem.index % 3 == 0
    }

    override suspend fun isUpdatePending(libraryItem: LibraryItem): Boolean = false

    override fun getDownloadInfo(libraryItem: LibraryItem): DownloadInfo? {
        return when (libraryItem.index % 5) {
            1 -> DownloadInfo().apply { setProgress(0.3f) }
            2 -> DownloadInfo().apply { setProgress(0.7f) }
            else -> null
        }
    }

    override fun hasPartialDownload(libraryItem: LibraryItem): Boolean = false

    override suspend fun getGameDiskSize(context: Context, libraryItem: LibraryItem): String {
        return when (libraryItem.index % 4) {
            0 -> "2.1 GB"
            1 -> "15.3 GB"
            2 -> "847 MB"
            else -> "4.7 GB"
        }
    }

    override fun createLibraryItem(appId: String, gameId: String, context: Context): LibraryItem {
        return LibraryItem(
            index = 0,
            appId = appId,
            name = "Fake Game",
            iconHash = "",
            isShared = false,
        )
    }

    override fun getDownloadSize(libraryItem: LibraryItem): String = "1.5 GB"
    override fun isValidToDownload(library: LibraryItem): Boolean = true
    override fun getAppInfo(libraryItem: LibraryItem): SteamApp? = null
    override fun getAppDirPath(appId: String): String = "/path/to/fake/app/dir"
    override fun getStoreUrl(libraryItem: LibraryItem): Uri = "https://example.com".toUri()

    override suspend fun launchGameWithSaveSync(
        context: Context,
        libraryItem: LibraryItem,
        parentScope: CoroutineScope,
        ignorePendingOperations: Boolean,
        preferredSave: Int?,
    ): PostSyncInfo {
        return PostSyncInfo(SyncResult.Success, 0)
    }

    override fun getWineStartCommand(
        context: Context,
        libraryItem: LibraryItem,
        container: Container,
        bootToContainer: Boolean,
        appLaunchInfo: LaunchInfo?,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
    ): String = ""

    override fun getReleaseDate(libraryItem: LibraryItem): String = "2024-01-01"

    override fun getHeroImage(libraryItem: LibraryItem): String = ""
    override fun getIconImage(libraryItem: LibraryItem): String = ""

    override fun getInstallInfoDialog(context: Context, libraryItem: LibraryItem): MessageDialogState {
        return MessageDialogState(
            false,
        )
    }

    override fun runBeforeLaunch(context: Context, libraryItem: LibraryItem) {
        // No-op for fake implementation
    }

    override fun getAllGames(): Flow<List<Game>> = flowOf(emptyList())
}
