package app.gamenative.service.GOG

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGGame
import app.gamenative.data.GOGGameWrapper
import app.gamenative.data.Game
import app.gamenative.data.GameSource
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.enums.AppType
import app.gamenative.enums.ControllerSupport
import app.gamenative.enums.OS
import app.gamenative.enums.ReleaseState
import app.gamenative.enums.SyncResult
import app.gamenative.service.GameManager
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.enums.DialogType
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.StorageUtils
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class GOGGameManager @Inject constructor(
    private val gogGameDao: GOGGameDao,
) : GameManager {

    // Track active downloads by game ID
    private val downloadJobs = ConcurrentHashMap<String, DownloadInfo>()

    override fun downloadGame(context: Context, libraryItem: LibraryItem): Result<DownloadInfo?> {
        try {
            // Check authentication first
            if (!GOGService.hasStoredCredentials(context)) {
                return Result.failure(Exception("GOG authentication required. Please log in to your GOG account first."))
            }

            // Validate credentials and refresh if needed
            val validationResult = runBlocking { GOGService.validateCredentials(context) }
            if (!validationResult.isSuccess || !validationResult.getOrDefault(false)) {
                return Result.failure(Exception("GOG authentication is invalid. Please re-authenticate."))
            }

            val installPath = getGameInstallPath(context, libraryItem.appId, libraryItem.name)
            val authConfigPath = "${context.filesDir}/gog_auth.json"

            Timber.i("Starting GOG game installation: ${libraryItem.name} to $installPath")

            // Use the new download method that returns DownloadInfo
            val result = runBlocking { GOGService.downloadGame(libraryItem.appId, installPath, authConfigPath) }

            if (result.isSuccess) {
                val downloadInfo = result.getOrNull()
                if (downloadInfo != null) {
                    // Store the download info for progress tracking
                    downloadJobs[libraryItem.appId] = downloadInfo
                    Timber.i("GOG game installation started successfully: ${libraryItem.name}")
                }
                return Result.success(downloadInfo)
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown download error")
                Timber.e(error, "Failed to install GOG game: ${libraryItem.name}")
                return Result.failure(error)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to install GOG game: ${libraryItem.name}")
            return Result.failure(e)
        }
    }

    override fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
        try {
            val gameId = libraryItem.gameId.toString()
            val installPath = getGameInstallPath(context, gameId, libraryItem.name)
            val installDir = File(installPath)

            // Delete the manifest file to ensure fresh downloads on reinstall
            val manifestPath = File(context.filesDir, "manifests/$gameId")
            if (manifestPath.exists()) {
                val manifestDeleted = manifestPath.delete()
                if (manifestDeleted) {
                    Timber.i("Deleted manifest file for game $gameId")
                } else {
                    Timber.w("Failed to delete manifest file for game $gameId")
                }
            }

            if (installDir.exists()) {
                val success = installDir.deleteRecursively()
                if (success) {
                    // Update database to mark as not installed
                    val game = runBlocking { getGameById(gameId) }
                    if (game != null) {
                        val updatedGame = game.copy(
                            isInstalled = false,
                            installPath = "",
                        )
                        runBlocking { gogGameDao.update(updatedGame) }
                    }

                    Timber.i("GOG game ${libraryItem.name} deleted successfully")
                    return Result.success(Unit)
                } else {
                    return Result.failure(Exception("Failed to delete GOG game directory"))
                }
            } else {
                Timber.w("GOG game directory doesn't exist: $installPath")
                // Update database anyway to ensure consistency
                val game = runBlocking { getGameById(gameId) }
                if (game != null) {
                    val updatedGame = game.copy(
                        isInstalled = false,
                        installPath = "",
                    )
                    runBlocking { gogGameDao.update(updatedGame) }
                }

                return Result.success(Unit) // Consider it already deleted
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete GOG game ${libraryItem.gameId}")
            return Result.failure(e)
        } finally {
            // Always remove from active downloads regardless of success/failure
            downloadJobs.remove(libraryItem.gameId.toString())
        }
    }

    override fun isGameInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        val gameId = libraryItem.gameId.toString()
        val gameName = libraryItem.name
        try {
            val installPath = getGameInstallPath(context, gameId, gameName)
            val installDir = File(installPath)
            val isInstalled = installDir.exists() && installDir.listFiles()?.isNotEmpty() == true

            // Update database if the install status has changed
            val game = runBlocking { getGameById(gameId) }
            if (game != null && isInstalled != game.isInstalled) {
                val updatedGame = game.copy(
                    isInstalled = isInstalled,
                    installPath = if (isInstalled) installPath else "",
                )
                runBlocking { gogGameDao.update(updatedGame) }
            }

            return isInstalled
        } catch (e: Exception) {
            Timber.e(e, "Error checking if GOG game is installed")
            return false
        }
    }

    override suspend fun isUpdatePending(libraryItem: LibraryItem): Boolean {
        return false // Not implemented yet.
    }

    override fun getDownloadInfo(libraryItem: LibraryItem): DownloadInfo? {
        return downloadJobs[libraryItem.gameId.toString()]
    }

    override fun hasPartialDownload(libraryItem: LibraryItem): Boolean {
        return false // GOG doesn't support partial downloads yet
    }

    override suspend fun getGameDiskSize(context: Context, libraryItem: LibraryItem): String = withContext(Dispatchers.IO) {
        // Calculate size from install directory
        val installPath = getGameInstallPath(context, libraryItem.appId, libraryItem.name)
        val folderSize = StorageUtils.getFolderSize(installPath)

        StorageUtils.formatBinarySize(folderSize)
    }

    override fun getAppDirPath(appId: String): String {
        return GOGConstants.GOG_GAMES_BASE_PATH
    }

    override suspend fun launchGameWithSaveSync(
        context: Context,
        libraryItem: LibraryItem,
        parentScope: CoroutineScope,
        ignorePendingOperations: Boolean,
        preferredSave: Int?,
    ): PostSyncInfo = withContext(Dispatchers.IO) {
        try {
            Timber.i("Starting GOG game launch with save sync for ${libraryItem.name}")

            // Check if GOG credentials exist
            if (!GOGService.hasStoredCredentials(context)) {
                Timber.w("No GOG credentials found, skipping cloud save sync")
                return@withContext PostSyncInfo(SyncResult.Success) // Continue without sync
            }

            // Determine save path for GOG game
            val savePath = "${getGameInstallPath(context, libraryItem.appId, libraryItem.name)}/saves"
            val authConfigPath = "${context.filesDir}/gog_auth.json"

            Timber.i("Starting GOG cloud save sync for game ${libraryItem.gameId}")

            // Perform GOG cloud save sync
            val syncResult = GOGService.syncCloudSaves(
                gameId = libraryItem.gameId.toString(),
                savePath = savePath,
                authConfigPath = authConfigPath,
                timestamp = 0.0f,
            )

            if (syncResult.isSuccess) {
                Timber.i("GOG cloud save sync completed successfully")
                PostSyncInfo(SyncResult.Success)
            } else {
                val error = syncResult.exceptionOrNull()
                Timber.e(error, "GOG cloud save sync failed")
                PostSyncInfo(SyncResult.UnknownFail)
            }
        } catch (e: Exception) {
            Timber.e(e, "GOG cloud save sync exception for game ${libraryItem.gameId}")
            PostSyncInfo(SyncResult.UnknownFail)
        }
    }

    override fun getStoreUrl(libraryItem: LibraryItem): Uri {
        val gogGame = runBlocking { getGameById(libraryItem.gameId.toString()) }
        val slug = gogGame?.slug ?: ""
        return "https://www.gog.com/en/game/$slug".toUri()
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
        // For GOG games, we always want to launch the actual game
        // because GOG doesn't have appLaunchInfo like Steam does

        // Extract the numeric game ID from appId using the existing utility function
        val gameId = ContainerUtils.extractGameIdFromContainerId(libraryItem.appId)

        // Get the game details to find the correct title
        val game = runBlocking { getGameById(gameId.toString()) }
        if (game == null) {
            Timber.e("Game not found for ID: $gameId")
            return "\"explorer.exe\""
        }

        Timber.i("Looking for GOG game '${game.title}' with ID: $gameId")

        // Get the specific game installation directory using the existing function
        val gameInstallPath = getGameInstallPath(context, gameId.toString(), game.title)
        val gameDir = File(gameInstallPath)

        if (!gameDir.exists()) {
            Timber.e("Game installation directory does not exist: $gameInstallPath")
            return "\"explorer.exe\""
        }

        Timber.i("Found game directory: ${gameDir.absolutePath}")

        // Use GOGGameManager to get the correct executable
        val executablePath = runBlocking { getInstalledExe(context, libraryItem) }

        if (executablePath.isEmpty()) {
            Timber.w("No executable found for GOG game ${libraryItem.name}, opening file manager")
            return "\"explorer.exe\""
        }

        // Calculate the Windows path for the game subdirectory
        val gameSubDirRelativePath = gameDir.relativeTo(File(GOGConstants.GOG_GAMES_BASE_PATH)).path.replace('\\', '/')
        val windowsGamePath = "E:/gog_games/$gameSubDirRelativePath"

        // Set WINEPATH to the game subdirectory on E: drive
        envVars.put("WINEPATH", windowsGamePath)

        // Set the working directory to the game directory
        val gameWorkingDir = File(GOGConstants.GOG_GAMES_BASE_PATH, gameSubDirRelativePath)
        guestProgramLauncherComponent.workingDir = gameWorkingDir
        Timber.i("Setting working directory to: ${gameWorkingDir.absolutePath}")

        val executableName = File(executablePath).name
        Timber.i("GOG game executable name: $executableName")
        Timber.i("GOG game Windows path: $windowsGamePath")
        Timber.i("GOG game subdirectory relative path: $gameSubDirRelativePath")

        // Determine structure type by checking if game_* subdirectory exists
        val isV2Structure = gameDir.listFiles()?.any {
            it.isDirectory && it.name.startsWith("game_$gameId")
        } ?: false
        Timber.i("Game structure type: ${if (isV2Structure) "V2" else "V1"}")

        val fullCommand = "\"$windowsGamePath/$executablePath\""

        Timber.i("Full Wine command will be: $fullCommand")
        return fullCommand
    }

    override fun createLibraryItem(appId: String, gameId: String, context: Context): LibraryItem {
        val gogGame = runBlocking { getGameById(gameId) }

        return LibraryItem(
            appId = appId,
            name = gogGame?.title ?: "Unknown GOG Game",
            iconHash = "", // GOG games don't have icon hashes like Steam
            gameSource = GameSource.GOG,
        )
    }

    override fun getDownloadSize(libraryItem: LibraryItem): String {
        return "Unknown" // TODO: Add size info to GOG games
    }

    override fun isValidToDownload(library: LibraryItem): Boolean {
        return true // GOG games are always downloadable if owned
    }

    override fun getAppInfo(libraryItem: LibraryItem): SteamApp? {
        val gogGame = runBlocking { getGameById(libraryItem.gameId.toString()) }
        return if (gogGame != null) {
            convertGOGGameToSteamApp(gogGame)
        } else {
            null
        }
    }

    override fun getReleaseDate(libraryItem: LibraryItem): String {
        val appInfo = getAppInfo(libraryItem)
        if (appInfo?.releaseDate == null || appInfo.releaseDate == 0L) {
            return "Unknown"
        }
        val date = Date(appInfo.releaseDate)
        return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(date)
    }

    override fun getHeroImage(libraryItem: LibraryItem): String {
        val gogGame = runBlocking { getGameById(libraryItem.gameId.toString()) }
        val imageUrl = gogGame?.imageUrl ?: ""

        // Fix GOG URLs that are missing the protocol
        return if (imageUrl.startsWith("//")) {
            "https:$imageUrl"
        } else {
            imageUrl
        }
    }

    override fun getIconImage(libraryItem: LibraryItem): String {
        return libraryItem.iconHash
    }

    override fun getInstallInfoDialog(context: Context, libraryItem: LibraryItem): MessageDialogState {
        // GOG install logic
        val gogInstallPath = "${context.dataDir.path}/gog_games"
        val availableBytes = StorageUtils.getAvailableSpace(context.dataDir.path)
        val availableSpace = StorageUtils.formatBinarySize(availableBytes)

        // For now, show a basic install dialog for GOG games
        // TODO: Get actual size information from GOG API
        return MessageDialogState(
            visible = true,
            type = DialogType.INSTALL_APP,
            title = context.getString(R.string.download_prompt_title),
            message = "Install ${libraryItem.name} from GOG?" +
                "\n\nInstall Path: $gogInstallPath/${libraryItem.name}" +
                "\nAvailable Space: $availableSpace",
            confirmBtnText = context.getString(R.string.proceed),
            dismissBtnText = context.getString(R.string.cancel),
        )
    }

    override fun runBeforeLaunch(context: Context, libraryItem: LibraryItem) {
        // Don't run anything before launch for GOG games
    }

    override fun getAllGames(): Flow<List<Game>> {
        return gogGameDao.getAll().map { gogGames ->
            gogGames.map { gogGame -> GOGGameWrapper(gogGame) }
        }
    }

    /**
     * Get install path for a specific GOG game
     */
    fun getGameInstallPath(context: Context, gameId: String, gameTitle: String): String {
        return GOGConstants.getGameInstallPath(gameTitle)
    }

    /**
     * Get GOG game by ID from database
     */
    suspend fun getGameById(gameId: String): GOGGame? = withContext(Dispatchers.IO) {
        try {
            gogGameDao.getById(gameId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get GOG game by ID: $gameId")
            null
        }
    }

    /**
     * Get the executable path for an installed GOG game.
     * Handles both V1 and V2 game directory structures.
     */
    suspend fun getInstalledExe(context: Context, libraryItem: LibraryItem): String = withContext(Dispatchers.IO) {
        val gameId = libraryItem.gameId
        try {
            val game = runBlocking { getGameById(gameId.toString()) } ?: return@withContext ""
            val installPath = getGameInstallPath(context, game.id, game.title)

            // Try V2 structure first (game_$gameId subdirectory)
            val v2GameDir = File(installPath, "game_$gameId")
            if (v2GameDir.exists()) {
                Timber.i("Found V2 game structure: ${v2GameDir.absolutePath}")
                return@withContext getGameExecutable(installPath, v2GameDir)
            } else {
                // Try V1 structure (look for any subdirectory in the install path)
                val installDirFile = File(installPath)
                val subdirs = installDirFile.listFiles()?.filter {
                    it.isDirectory && it.name != "saves"
                } ?: emptyList()

                if (subdirs.isNotEmpty()) {
                    // For V1 games, find the subdirectory with .exe files
                    val v1GameDir = subdirs.find { subdir ->
                        val exeFiles = subdir.listFiles()?.filter {
                            it.isFile &&
                                it.name.endsWith(".exe", ignoreCase = true) &&
                                !isGOGUtilityExecutable(it.name)
                        } ?: emptyList()
                        exeFiles.isNotEmpty()
                    }

                    if (v1GameDir != null) {
                        Timber.i("Found V1 game structure: ${v1GameDir.absolutePath}")
                        return@withContext getGameExecutable(installPath, v1GameDir)
                    } else {
                        Timber.w("No V1 game subdirectories with executables found in: $installPath")
                        return@withContext ""
                    }
                } else {
                    Timber.w("No game directories found in: $installPath")
                    return@withContext ""
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get executable for GOG game $gameId")
            ""
        }
    }

    /**
     * Check if an executable is a GOG utility (should be skipped)
     */
    private fun isGOGUtilityExecutable(filename: String): Boolean {
        return filename.equals("unins000.exe", ignoreCase = true) ||
            filename.equals("CheckApplication.exe", ignoreCase = true) ||
            filename.equals("SettingsApplication.exe", ignoreCase = true)
    }

    private fun getGameExecutable(installPath: String, gameDir: File): String {
        // Get the main executable from GOG game info file
        val mainExe = getMainExecutableFromGOGInfo(gameDir, installPath)

        if (mainExe.isNotEmpty()) {
            Timber.i("Found GOG game executable from info file: $mainExe")
            return mainExe
        }

        Timber.e("Failed to find executable from GOG info file in: ${gameDir.absolutePath}")
        return ""
    }

    private fun getMainExecutableFromGOGInfo(gameDir: File, installPath: String): String {
        // Look for goggame-*.info file
        val infoFile = gameDir.listFiles()?.find {
            it.isFile && it.name.startsWith("goggame-") && it.name.endsWith(".info")
        }

        if (infoFile == null) {
            throw Exception("GOG info file not found in: ${gameDir.absolutePath}")
        }

        val content = infoFile.readText()
        Timber.d("GOG info file content: $content")

        // Parse JSON to find the primary task
        val jsonObject = org.json.JSONObject(content)

        // Look for playTasks array
        if (!jsonObject.has("playTasks")) {
            throw Exception("GOG info file does not contain playTasks array")
        }

        val playTasks = jsonObject.getJSONArray("playTasks")

        // Find the primary task
        for (i in 0 until playTasks.length()) {
            val task = playTasks.getJSONObject(i)
            if (task.has("isPrimary") && task.getBoolean("isPrimary")) {
                val executablePath = task.getString("path")

                Timber.i("Found primary task executable path: $executablePath")

                // Check if the executable actually exists (case-insensitive)
                val actualExeFile = gameDir.listFiles()?.find {
                    it.name.equals(executablePath, ignoreCase = true)
                }
                if (actualExeFile != null && actualExeFile.exists()) {
                    return "${gameDir.name}/${actualExeFile.name}"
                } else {
                    Timber.w("Primary task executable '$executablePath' not found in game directory")
                }
                break
            }
        }

        return ""
    }

    /**
     * Clean up download info when download is cancelled or fails (unused, might be necessary later?)
     */
    fun cleanupDownload(libraryItem: LibraryItem) {
        downloadJobs.remove(libraryItem.gameId.toString())
        Timber.d("Cleaned up download info for GOG game: ${libraryItem.gameId}")
    }

    /**
     * Convert GOGGame to SteamApp format for compatibility with existing UI components.
     * This allows GOG games to be displayed using the same UI components as Steam games.
     */
    private fun convertGOGGameToSteamApp(gogGame: GOGGame): SteamApp {
        // Convert release date string (ISO format like "2021-06-17T15:55:+0300") to timestamp
        val releaseTimestamp = try {
            if (gogGame.releaseDate.isNotEmpty()) {
                // Try different date formats that GOG might use
                val formats = arrayOf(
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ZZZZZ", Locale.US), // 2021-06-17T15:55:+0300
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ", Locale.US), // 2021-06-17T15:55+0300
                    SimpleDateFormat("yyyy-MM-dd", Locale.US), // 2021-06-17
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US), // 2021-06-17T15:55:30
                )

                var parsedDate: Date? = null
                for (format in formats) {
                    try {
                        parsedDate = format.parse(gogGame.releaseDate)
                        break
                    } catch (e: Exception) {
                        // Try next format
                    }
                }

                parsedDate?.time ?: 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse release date: ${gogGame.releaseDate}")
            0L
        }

        // Convert GOG game ID (string) to integer for SteamApp compatibility
        val appId = try {
            gogGame.id.toIntOrNull() ?: gogGame.id.hashCode()
        } catch (e: Exception) {
            gogGame.id.hashCode()
        }

        return SteamApp(
            id = appId,
            name = gogGame.title,
            type = AppType.game,
            osList = EnumSet.of(OS.windows),
            releaseState = ReleaseState.released,
            releaseDate = releaseTimestamp,
            developer = gogGame.developer.takeIf { it.isNotEmpty() } ?: "Unknown Developer",
            publisher = gogGame.publisher.takeIf { it.isNotEmpty() } ?: "Unknown Publisher",
            controllerSupport = ControllerSupport.none,
            logoHash = "",
            iconHash = "",
            clientIconHash = "",
            installDir = gogGame.title.replace(Regex("[^a-zA-Z0-9 ]"), "").trim(),
        )
    }

    private suspend fun ensureValidCredentials(context: Context): Boolean {
        val validationResult = GOGService.validateCredentials(context)
        return validationResult.isSuccess && validationResult.getOrDefault(false)
    }
}
