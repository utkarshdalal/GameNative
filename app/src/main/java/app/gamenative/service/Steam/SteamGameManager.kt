package app.gamenative.service.Steam

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.gamenative.Constants
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import app.gamenative.data.Game
import app.gamenative.data.GameSource
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamGameWrapper
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.enums.PathType
import app.gamenative.enums.SaveLocation
import app.gamenative.service.DownloadService
import app.gamenative.service.GameManager
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.enums.DialogType
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.StorageUtils
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

class SteamGameManager @Inject constructor(
    private val steamAppDao: SteamAppDao,
) : GameManager {
    override fun downloadGame(context: Context, libraryItem: LibraryItem): Result<DownloadInfo?> {
        try {
            val downloadInfo = SteamService.downloadApp(libraryItem.gameId)
            if (downloadInfo != null) {
                return Result.success(downloadInfo)
            } else {
                return Result.failure(Exception("Failed to start Steam game download"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to install Steam game $libraryItem.gameId")
            return Result.failure(e)
        }
    }

    override fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
        try {
            val success = SteamService.deleteApp(libraryItem.gameId)
            if (success) {
                return Result.success(Unit)
            } else {
                return Result.failure(Exception("Failed to delete Steam game files"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete Steam game ${libraryItem.gameId}")
            return Result.failure(e)
        }
    }

    override fun isGameInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        return try {
            SteamService.isAppInstalled(libraryItem.gameId)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun isUpdatePending(libraryItem: LibraryItem): Boolean {
        val appInfo = getAppInfo(libraryItem)
        if (appInfo == null) {
            return false
        }
        return SteamService.isUpdatePending(appInfo.id)
    }

    override fun getDownloadInfo(libraryItem: LibraryItem): DownloadInfo? {
        return try {
            SteamService.getAppDownloadInfo(libraryItem.gameId)
        } catch (e: Exception) {
            null
        }
    }

    override fun hasPartialDownload(libraryItem: LibraryItem): Boolean {
        return try {
            SteamService.hasPartialDownload(libraryItem.gameId)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun launchGameWithSaveSync(
        context: Context,
        libraryItem: LibraryItem,
        parentScope: CoroutineScope,
        ignorePendingOperations: Boolean,
        preferredSave: Int?,
    ): PostSyncInfo = withContext(Dispatchers.IO) {
        try {
            val gameId = libraryItem.gameId
            Timber.i("Starting Steam game launch with save sync for ${libraryItem.name} (appId: $gameId)")

            // Use existing Steam save sync logic
            val prefixToPath: (String) -> String = { prefix ->
                PathType.from(prefix).toAbsPath(context, gameId, SteamService.userSteamId!!.accountID)
            }

            // Convert Int? to SaveLocation
            val saveLocation = when (preferredSave) {
                0 -> SaveLocation.Local
                1 -> SaveLocation.Remote
                else -> SaveLocation.None
            }

            val postSyncInfo = SteamService.beginLaunchApp(
                appId = gameId,
                prefixToPath = prefixToPath,
                ignorePendingOperations = ignorePendingOperations,
                preferredSave = saveLocation,
                parentScope = parentScope,
            ).await()

            Timber.i("Steam game save sync completed for ${libraryItem.name}")
            postSyncInfo
        } catch (e: Exception) {
            Timber.e(e, "Steam game launch with save sync failed for ${libraryItem.gameId}")
            PostSyncInfo(app.gamenative.enums.SyncResult.UnknownFail)
        }
    }

    override suspend fun getGameDiskSize(context: Context, libraryItem: LibraryItem): String = withContext(Dispatchers.IO) {
        var result = "..."
        DownloadService.getSizeOnDiskDisplay(libraryItem.gameId.toInt()) { result = it }
        result
    }

    override fun getAppDirPath(appId: String): String {
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
        return SteamService.getAppDirPath(gameId)
    }

    override fun getStoreUrl(libraryItem: LibraryItem): Uri {
        return "https://store.steampowered.com/app/${libraryItem.gameId}".toUri()
    }

    override fun getWineStartCommand(
        context: Context,
        libraryItem: LibraryItem,
        container: Container,
        bootToContainer: Boolean,
        appLaunchInfo: LaunchInfo?,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
    ): String {
        val appId = libraryItem.appId // For backward compatibility

        if (appLaunchInfo == null) {
            return "\"wfm.exe\""
        }

        // Check if we should launch through real Steam
        if (container.isLaunchRealSteam()) {
            // Launch Steam with the applaunch parameter to start the game
            return "\"C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe\" -silent -vgui -tcp " +
                "-nobigpicture -nofriendsui -nochatui -nointro -applaunch $appId"
        }

        // Original logic for direct game launch
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
        val appDirPath = SteamService.getAppDirPath(gameId)
        var executablePath = ""
        if (container.executablePath.isNotEmpty()) {
            executablePath = container.executablePath
        } else {
            executablePath = SteamService.getInstalledExe(gameId)
            container.executablePath = executablePath
            container.saveData()
        }
        val executableDir = appDirPath + "/" + executablePath.substringBeforeLast("/", "")
        guestProgramLauncherComponent.workingDir = File(executableDir)
        Timber.i("Working directory is $executableDir")

        Timber.i("Final exe path is " + executablePath)
        val drives = container.drives
        val driveIndex = drives.indexOf(appDirPath)
        // greater than 1 since there is the drive character and the colon before the app dir path
        val drive = if (driveIndex > 1) {
            drives[driveIndex - 2]
        } else {
            Timber.e("Could not locate game drive")
            'D'
        }
        envVars.put("WINEPATH", "$drive:/${appLaunchInfo?.workingDir}")

        return "\"$drive:/${executablePath}\""
    }

    override fun createLibraryItem(appId: String, gameId: String, context: Context): LibraryItem {
        val gameIdInt = gameId.toInt()
        val appInfo = SteamService.getAppInfoOf(gameIdInt)

        return LibraryItem(
            appId = appId,
            name = appInfo?.name ?: "Unknown Game",
            iconHash = appInfo?.iconHash ?: "",
            gameSource = GameSource.STEAM,
        )
    }

    override suspend fun getDownloadSize(libraryItem: LibraryItem): String {
        return withContext(Dispatchers.IO) {
            DownloadService.getSizeFromStoreDisplay(libraryItem.gameId)
        }
    }

    override fun isValidToDownload(libraryItem: LibraryItem): Boolean {
        val appInfo = getAppInfo(libraryItem)
        return appInfo?.branches?.isNotEmpty() == true && appInfo?.depots?.isNotEmpty() == true
    }

    override fun getAppInfo(libraryItem: LibraryItem): SteamApp? {
        return SteamService.getAppInfoOf(libraryItem.gameId)
    }

    override fun getReleaseDate(libraryItem: LibraryItem): String {
        val appInfo = getAppInfo(libraryItem)
        if (appInfo?.releaseDate == null) {
            return "Unknown"
        }
        val date = Date(appInfo.releaseDate * 1000)
        return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(date)
    }

    override fun getHeroImage(libraryItem: LibraryItem): String {
        val appInfo = getAppInfo(libraryItem)
        return appInfo?.getHeroUrl() ?: ""
    }

    override fun getIconImage(libraryItem: LibraryItem): String {
        return Constants.Library.ICON_URL + "${libraryItem.gameId}/${libraryItem.iconHash}.ico"
    }

    override fun getInstallInfoDialog(context: Context, libraryItem: LibraryItem): MessageDialogState {
        val depots = SteamService.getDownloadableDepots(libraryItem.gameId)
        Timber.i("There are ${depots.size} depots belonging to ${libraryItem.gameId}")
        // How much free space is on disk
        val availableBytes = StorageUtils.getAvailableSpace(SteamService.defaultStoragePath)
        val availableSpace = StorageUtils.formatBinarySize(availableBytes)
        // TODO: un-hardcode "public" branch
        val downloadSize = StorageUtils.formatBinarySize(
            depots.values.sumOf {
                it.manifests["public"]?.download ?: 0
            },
        )
        val installBytes = depots.values.sumOf { it.manifests["public"]?.size ?: 0 }
        val installSize = StorageUtils.formatBinarySize(installBytes)
        if (availableBytes < installBytes) {
            return MessageDialogState(
                visible = true,
                type = DialogType.NOT_ENOUGH_SPACE,
                title = context.getString(R.string.not_enough_space),
                message = "The app being installed needs $installSize of space but " +
                    "there is only $availableSpace left on this device",
                confirmBtnText = context.getString(R.string.acknowledge),
            )
        } else {
            return MessageDialogState(
                visible = true,
                type = DialogType.INSTALL_APP,
                title = context.getString(R.string.download_prompt_title),
                message = "The app being installed has the following space requirements. Would you like to proceed?" +
                    "\n\n\tDownload Size: $downloadSize" +
                    "\n\tSize on Disk: $installSize" +
                    "\n\tAvailable Space: $availableSpace",
                confirmBtnText = context.getString(R.string.proceed),
                dismissBtnText = context.getString(R.string.cancel),
            )
        }
    }

    override fun runBeforeLaunch(context: Context, libraryItem: LibraryItem) {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        if (container.isLaunchRealSteam()) {
            SteamUtils.restoreSteamApi(context, libraryItem.gameId)
        } else {
            runBlocking { SteamUtils.replaceSteamApi(context, libraryItem.gameId) }
        }
    }

    override fun getAllGames(): Flow<List<Game>> {
        return steamAppDao.getAllOwnedApps().map { steamApps ->
            steamApps.map { steamApp -> SteamGameWrapper(steamApp) }
        }
    }
}
