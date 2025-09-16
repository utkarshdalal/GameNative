package app.gamenative.service.Steam

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.gamenative.Constants
import app.gamenative.data.DownloadInfo
import app.gamenative.data.Game
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamGameWrapper
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.enums.SyncResult
import app.gamenative.service.GameManager
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SteamGameManager @Inject constructor(
    private val steamAppDao: SteamAppDao,
): GameManager {
    // Not yet used in actual app
    override fun downloadGame(context: Context, appId: String): Result<DownloadInfo?> = Result.success(null)

    // Not yet used in actual app
    override fun deleteGame(context: Context, appId: String): Result<Unit> = Result.success(Unit)

    // Not yet used in actual app
    override fun isGameInstalled(context: Context, appId: String): Boolean = false

    // Not yet used in actual app
    override suspend fun isUpdatePending(appId: String): Boolean = false

    // Not yet used in actual app
    override fun getDownloadInfo(appId: String): DownloadInfo? = null

    // Not yet used in actual app
    override fun hasPartialDownload(appId: String): Boolean = false

    // Not yet used in actual app
    override suspend fun getGameDiskSize(context: Context, appId: String): String = "2.1 GB"

    // Not yet used in actual app
    override fun getDownloadSize(appId: String): String = "1.5 GB"

    // Not yet used in actual app
    override fun isValidToDownload(appId: String): Boolean = true

    // Not yet used in actual app
    override fun getAppInfo(appId: String): SteamApp? = null

    // Not yet used in actual app
    override fun getAppDirPath(appId: String): String = "/path/to/fake/app/dir"

    // Not yet used in actual app
    override fun getStoreUrl(appId: String): Uri = "https://example.com".toUri()

    // Not yet used in actual app
    override suspend fun launchGameWithSaveSync(
        context: Context,
        appId: String,
        parentScope: CoroutineScope,
        ignorePendingOperations: Boolean,
        preferredSave: Int?,
    ): PostSyncInfo {
        return PostSyncInfo(SyncResult.Success, 0)
    }

    // Not yet used in actual app
    override fun getWineStartCommand(
        context: Context,
        appId: String,
        container: Container,
        bootToContainer: Boolean,
        appLaunchInfo: LaunchInfo?,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
    ): String = ""

    // Not yet used in actual app
    override fun getReleaseDate(appId: String): String = "2024-01-01"

    override fun getHeroImage(appId: String): String {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val appInfo = SteamService.getAppInfoOf(steamAppId)
        return appInfo?.getHeroUrl() ?: ""
    }

    override fun getIconImage(libraryItem: LibraryItem): String {
        return Constants.Library.ICON_URL + "${libraryItem.gameId}/${libraryItem.iconHash}.ico"
    }

    // Not yet used in actual app
    override fun getInstallInfoDialog(context: Context, appId: String): MessageDialogState {
        return MessageDialogState(
            false,
        )
    }

    // Not yet used in actual app
    override fun runBeforeLaunch(context: Context, appId: String) {}

    override fun getAllGames(): Flow<List<Game>> {
        return steamAppDao.getAllOwnedApps().map { steamApps ->
            steamApps.map { steamApp -> SteamGameWrapper(steamApp) }
        }
    }
}
