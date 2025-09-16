package app.gamenative.service

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.gamenative.data.DownloadInfo
import app.gamenative.data.LaunchInfo
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.enums.SyncResult
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.data.LibraryItem
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

    override fun downloadGame(context: Context, appId: String): Result<DownloadInfo?> {
        return Result.success(null)
    }

    override fun deleteGame(context: Context, appId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override fun isGameInstalled(context: Context, appId: String): Boolean {
        return false
    }

    override suspend fun isUpdatePending(appId: String): Boolean = false

    override fun getDownloadInfo(appId: String): DownloadInfo? {
        return null
    }

    override fun hasPartialDownload(appId: String): Boolean = false

    override suspend fun getGameDiskSize(context: Context, appId: String): String {
        return "2.1 GB"
    }

    override fun getDownloadSize(appId: String): String = "1.5 GB"
    override fun isValidToDownload(appId: String): Boolean = true
    override fun getAppInfo(appId: String): SteamApp? = null
    override fun getAppDirPath(appId: String): String = "/path/to/fake/app/dir"
    override fun getStoreUrl(appId: String): Uri = "https://example.com".toUri()

    override suspend fun launchGameWithSaveSync(
        context: Context,
        appId: String,
        parentScope: CoroutineScope,
        ignorePendingOperations: Boolean,
        preferredSave: Int?,
    ): PostSyncInfo {
        return PostSyncInfo(SyncResult.Success, 0)
    }

    override fun getWineStartCommand(
        context: Context,
        appId: String,
        container: Container,
        bootToContainer: Boolean,
        appLaunchInfo: LaunchInfo?,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
    ): String = ""

    override fun getReleaseDate(appId: String): String = "2024-01-01"

    override fun getHeroImage(appId: String): String = ""

    override fun getInstallInfoDialog(context: Context, appId: String): MessageDialogState {
        return MessageDialogState(
            false,
        )
    }

    override fun runBeforeLaunch(context: Context, appId: String) {
        // No-op for fake implementation
    }

    override fun getAllGames(): Flow<List<LibraryItem>> = flowOf(emptyList())
}
