package app.gamenative.service.epic

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.gamenative.data.DownloadInfo
import app.gamenative.data.EpicCredentials
import app.gamenative.data.EpicGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.enums.Marker
import app.gamenative.service.NotificationHelper
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Epic Games Service - thin coordinator that delegates to specialized managers.
 *
 * Architecture:
 * - EpicAuthManager: Authentication and account management
 * - EpicManager: Game library, downloads, and installation
 *
 * This service maintains backward compatibility through static accessors
 * while delegating all operations to the appropriate managers.
 */

/**
 * TODO: Test Pausing and Cancelling Downloads
 * TODO: DLC Support
 * TODO: Clean up all the code in the Epic files
 * TODO: Ensure games install over multiple manifest types
 * TODO: Remove all the Python code and put in the basic information regarding Cloud Saves.
 */
@AndroidEntryPoint
class EpicService : Service() {

    companion object {
        private var instance: EpicService? = null

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            Timber.tag("Epic").i("[EpicService.start] Called. isRunning=$isRunning")
            if (!isRunning) {
                Timber.tag("Epic").i("[EpicService.start] Starting foreground service...")
                val intent = Intent(context, EpicService::class.java)
                context.startForegroundService(intent)
                Timber.tag("Epic").i("[EpicService.start] startForegroundService called")
            } else {
                Timber.tag("Epic").i("[EpicService.start] Service already running, skipping")
            }
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        fun initialize(context: Context): Boolean {
            // No initialization needed - EpicDownloadManager uses native Kotlin implementation
            return true
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to EpicAuthManager
        // ==========================================================================

        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<EpicCredentials> {
            return EpicAuthManager.authenticateWithCode(context, authorizationCode)
        }

        fun hasStoredCredentials(context: Context): Boolean {
            return EpicAuthManager.hasStoredCredentials(context)
        }

        suspend fun getStoredCredentials(context: Context): Result<EpicCredentials> {
            return EpicAuthManager.getStoredCredentials(context)
        }

        suspend fun logout(context: Context): Result<Unit> {
            return EpicAuthManager.logout(context)
        }

        // ==========================================================================
        // SYNC & OPERATIONS
        // ==========================================================================

        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true
        }

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): EpicService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance EpicManager
        // ==========================================================================

        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }

        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }

        fun getDownloadInfo(appName: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(appName)
        }

        suspend fun deleteGame(context: Context, appName: String): Result<Unit> {
            val instance = getInstance()
            if (instance == null) {
                return Result.failure(Exception("Service not available"))
            }

            return try {
                // Get the game to find its install path
                val game = instance.epicManager.getGameByAppName(appName)
                if (game == null) {
                    return Result.failure(Exception("Game not found: $appName"))
                }

                // Delete the installation folder if it exists
                if (game.isInstalled && game.installPath.isNotEmpty()) {
                    val installDir = File(game.installPath)
                    if (installDir.exists()) {
                        Timber.tag("Epic").i("Deleting installation folder: ${game.installPath}")
                        val deleted = installDir.deleteRecursively()
                        if (deleted) {
                            Timber.tag("Epic").i("Successfully deleted installation folder")
                        } else {
                            Timber.tag("Epic").w("Failed to delete some files in installation folder")
                        }
                    }
                }

                // Remove all markers
                val appDirPath = game.installPath
                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                // Uninstall from database (keeps the entry but marks as not installed)
                instance.epicManager.uninstall(game.id)

                // Delete container (must run on Main thread)
                withContext(Dispatchers.Main) {
                    val containerId = "EPIC_${game.appName}"
                    ContainerUtils.deleteContainer(context, containerId)
                    Timber.i("Deleted container for Epic app ${game.appName}")
                }

                // Trigger library refresh event
                app.gamenative.PluviaApp.events.emitJava(
                    app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged("EPIC_${game.appName}"),
                )

                Timber.tag("Epic").i("Game uninstalled: $appName")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to uninstall game: $appName")
                Result.failure(e)
            }
        }

        fun cleanupDownload(appName: String) {
            getInstance()?.activeDownloads?.remove(appName)
        }

        fun cancelDownload(appName: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(appName)

            return if (downloadInfo != null) {
                Timber.i("Cancelling download for Epic game: $appName")
                downloadInfo.cancel()
                instance.activeDownloads.remove(appName)
                Timber.d("Download cancelled for Epic game: $appName")
                true
            } else {
                Timber.w("No active download found for Epic game: $appName")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS - Delegate to instance EpicManager (Stubs)
        // ==========================================================================

        fun getEpicGameOf(appName: String): EpicGame? {
            return runBlocking {
                getInstance()?.epicManager?.getGameByAppName(appName)
            }
        }

        fun getDLCForGame(appId: String): List<EpicGame> {
            return runBlocking {
                getInstance()?.epicManager?.getDLCForTitle(appId) ?: emptyList()
            }
        }

        suspend fun updateEpicGame(game: EpicGame) {
            getInstance()?.epicManager?.updateGame(game)
        }

        suspend fun getDLCForTitle(appName: String): List<EpicGame> {
            return getInstance()?.epicManager?.getDLCForTitle(appName) ?: emptyList()
        }

        fun isGameInstalled(appName: String): Boolean {
            val game = getEpicGameOf(appName)
            return game?.isInstalled == true
        }

        fun getInstallPath(appName: String): String? {
            val game = getEpicGameOf(appName)
            return if (game?.isInstalled == true && game.installPath.isNotEmpty()) {
                game.installPath
            } else {
                null
            }
        }

        fun verifyInstallation(appName: String): Pair<Boolean, String?> {
            // TODO: Implement when EpicManager is ready
            return Pair(false, "Not implemented")
        }

        suspend fun getInstalledExe(context: Context, libraryItem: LibraryItem): String {
            // Strip EPIC_ prefix to get the raw Epic app name
            val epicAppName = libraryItem.appId.removePrefix("EPIC_")
            val game = getInstance()?.epicManager?.getGameByAppName(epicAppName)
            if (game == null || !game.isInstalled || game.installPath.isEmpty()) {
                Timber.tag("Epic").e("Game not installed: ${libraryItem.appId}")
                return ""
            }

            // For now, return the install path - actual executable detection would require
            // parsing the game's launch manifest or config files
            // Most Epic games have a .exe in the root or Binaries folder
            val installDir = File(game.installPath)
            if (!installDir.exists()) {
                Timber.tag("Epic").e("Install directory does not exist: ${game.installPath}")
                return ""
            }

            // Try to find the main executable
            // Common patterns: Game.exe, GameName.exe, or in Binaries/Win64/
            val exeFiles = installDir.walk()
                .filter { it.extension.equals("exe", ignoreCase = true) }
                .filter { !it.name.contains("UnityCrashHandler", ignoreCase = true) }
                .filter { !it.name.contains("UnrealCEFSubProcess", ignoreCase = true) }
                .sortedBy { it.absolutePath.length } // Prefer shorter paths (usually main exe)
                .toList()

            val mainExe = exeFiles.firstOrNull()
            if (mainExe != null) {
                Timber.tag("Epic").i("Found executable: ${mainExe.absolutePath}")
                return mainExe.absolutePath
            }

            Timber.tag("Epic").w("No executable found in ${game.installPath}")
            return ""
        }

        fun getWineStartCommand(
            context: Context,
            libraryItem: LibraryItem,
            container: com.winlator.container.Container,
            bootToContainer: Boolean,
            appLaunchInfo: LaunchInfo?,
            envVars: com.winlator.core.envvars.EnvVars,
            guestProgramLauncherComponent: com.winlator.xenvironment.components.GuestProgramLauncherComponent,
        ): String {
            // Strip EPIC_ prefix to get the raw Epic app name
            val epicAppName = libraryItem.appId.removePrefix("EPIC_")

            val game = runBlocking {
                getInstance()?.epicManager?.getGameByAppName(epicAppName)
            }

            if (game == null || !game.isInstalled || game.installPath.isEmpty()) {
                Timber.tag("Epic").e("Cannot launch: game not installed")
                return "\"explorer.exe\""
            }

            // Get the executable path
            val exePath = runBlocking {
                getInstalledExe(context, libraryItem)
            }

            if (exePath.isEmpty()) {
                Timber.tag("Epic").e("Cannot launch: executable not found")
                return "\"explorer.exe\""
            }

            // Convert to relative path from install directory
            val relativePath = exePath.removePrefix(game.installPath).removePrefix("/")

            // Use A: drive (or the mapped drive letter) instead of Z:
            // The container setup in ContainerUtils maps the game install path to A: drive
            val winePath = "A:\\$relativePath".replace("/", "\\")

            Timber.tag("Epic").i("Launching Epic game with exe: $winePath")

            // Fetch credentials and exchange code for launch arguments
            var launchArgs = ""
            val credentialsResult = runBlocking { EpicAuthManager.getStoredCredentials(context) }
            val credentials = credentialsResult.getOrNull()

            if (credentials != null) {
                try {
                    val codeResult = runBlocking { EpicAuthClient.getExchangeCode(credentials.accessToken) }
                    val exchangeCode = codeResult.getOrNull()

                    if (exchangeCode != null) {
                        launchArgs = " -AUTH_LOGIN=unused -AUTH_PASSWORD=$exchangeCode -AUTH_TYPE=exchangecode " +
                            "-epicapp=${game.appName} -epicenv=Prod -EpicPortal " +
                            "-epicusername=\"${credentials.displayName}\" -epicuserid=${credentials.accountId} " +
                            "-epiclocale=en-US"
                        Timber.tag("Epic").i("Generated Epic launch arguments with exchange code")
                    } else {
                        Timber.tag("Epic").w("Failed to get exchange code, game may fail to launch or require login")
                    }
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Error generating launch arguments")
                }
            } else {
                Timber.tag("Epic").w("No stored credentials, launching without Epic arguments")
            }

            // Build Wine command with proper escaping
            return "\"$winePath\"$launchArgs"
        }

        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.epicManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }

        suspend fun fetchManifestSizes(context: Context, appName: String): EpicManager.ManifestSizes {
            return getInstance()?.epicManager?.fetchManifestSizes(context, appName)
                ?: EpicManager.ManifestSizes(installSize = 0L, downloadSize = 0L)
        }

        fun downloadGame(context: Context, appName: String, installPath: String): Result<DownloadInfo> {
            val instance = getInstance()
            if (instance == null) {
                Timber.tag("Epic").e("Service not running")
                return Result.failure(Exception("Service not running"))
            }

            // Check if already downloading
            if (instance.activeDownloads.containsKey(appName)) {
                Timber.tag("Epic").w("Download already in progress for $appName")
                return Result.success(instance.activeDownloads[appName]!!)
            }

            // Start download in background
            instance.scope.launch {
                try {
                    val game = instance.epicManager.getGameByAppName(appName)
                    if (game == null) {
                        Timber.tag("Epic").e("Game not found: $appName")
                        return@launch
                    }

                    val downloadInfo = DownloadInfo()
                    downloadInfo.setActive(true)
                    instance.activeDownloads[appName] = downloadInfo

                    Timber.tag("Epic").i("Starting download for ${game.title}")

                    // Emit event for UI to start tracking progress
                    app.gamenative.PluviaApp.events.emitJava(
                        app.gamenative.events.AndroidEvent.DownloadStatusChanged("EPIC_${game.appName}", true),
                    )

                    val result = instance.epicDownloadManager.downloadGame(
                        context = context,
                        game = game,
                        installPath = installPath,
                        downloadInfo = downloadInfo,
                    )

                    Timber.tag("Epic").d("Download result: ${if (result.isSuccess) "SUCCESS" else "FAILURE: ${result.exceptionOrNull()?.message}"}")

                    if (result.isSuccess) {
                        Timber.tag("Epic").i("Download completed successfully for ${game.title}")

                        // Update game as installed
                        val updatedGame = game.copy(
                            isInstalled = true,
                            installPath = installPath,
                        )
                        instance.epicManager.updateGame(updatedGame)

                        // Emit events for UI update
                        app.gamenative.PluviaApp.events.emitJava(
                            app.gamenative.events.AndroidEvent.DownloadStatusChanged("EPIC_${game.appName}", false),
                        )
                        app.gamenative.PluviaApp.events.emitJava(
                            app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged("EPIC_${game.appName}"),
                        )
                    } else {
                        Timber.tag("Epic").e("Download failed: ${result.exceptionOrNull()?.message}")

                        // Emit event for UI update on failure
                        app.gamenative.PluviaApp.events.emitJava(
                            app.gamenative.events.AndroidEvent.DownloadStatusChanged("EPIC_${game.appName}", false),
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Download exception for $appName")

                    // Emit event for UI update on exception
                    val game = instance.epicManager.getGameByAppName(appName)
                    if (game != null) {
                        app.gamenative.PluviaApp.events.emitJava(
                            app.gamenative.events.AndroidEvent.DownloadStatusChanged("EPIC_${game.appName}", false),
                        )
                    }
                } finally {
                    instance.activeDownloads.remove(appName)
                }
            }

            // Return the DownloadInfo immediately so caller can track progress
            return Result.success(instance.activeDownloads[appName]!!)
        }

        suspend fun refreshSingleGame(appName: String, context: Context): Result<EpicGame?> {
            // For now, just get from database
            val game = getInstance()?.epicManager?.getGameByAppName(appName)
            return if (game != null) {
                Result.success(game)
            } else {
                Result.failure(Exception("Game not found: $appName"))
            }
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var epicManager: EpicManager

    @Inject
    lateinit var epicDownloadManager: EpicDownloadManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by Epic app name
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.tag("Epic").i("[EpicService] Service created")

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
        val notification = notificationHelper.createForegroundNotification("Epic Games Service running...")
        startForeground(3, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag("Epic").i("[EpicService] onStartCommand() called")

        // Ensure foreground state is maintained
        val notification = notificationHelper.createForegroundNotification("Epic Games Service active")
        startForeground(3, notification)
        Timber.tag("Epic").i("[EpicService] Foreground state ensured")

        // Start background library sync automatically when service starts
        backgroundSyncJob = scope.launch {
            try {
                setSyncInProgress(true)
                Timber.tag("Epic").i("[EpicService] Starting background library sync...")

                val syncResult = epicManager.startBackgroundSync(applicationContext)
                if (syncResult.isFailure) {
                    Timber.tag("Epic").w("[EpicService] Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                } else {
                    Timber.tag("Epic").i("[EpicService] Background library sync completed successfully")
                }
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "[EpicService] Exception starting background sync")
            } finally {
                setSyncInProgress(false)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag("Epic").i("[EpicService] Service destroyed")

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel() // Cancel any ongoing operations
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
