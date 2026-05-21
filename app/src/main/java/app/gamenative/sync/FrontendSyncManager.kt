package app.gamenative.sync

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.ui.util.SnackbarManager
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Collections
import java.util.EnumMap

/**
 * Manages per-source export files that frontend launchers (e.g. ES-DE) use to
 * discover installed games. Each installed game is represented by a small file
 * named `<sanitized-title>.<ext>` placed in the user-configured directory for
 * its source. Files are created on install and removed on uninstall via
 * [AndroidEvent.LibraryInstallStatusChanged].
 */
object FrontendSyncManager {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FrontendSyncEntryPoint {
        fun steamAppDao(): SteamAppDao
        fun epicGameDao(): EpicGameDao
        fun gogGameDao(): GOGGameDao
        fun amazonGameDao(): AmazonGameDao
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var appContext: Context
    private lateinit var steamAppDao: SteamAppDao
    private lateinit var epicGameDao: EpicGameDao
    private lateinit var gogGameDao: GOGGameDao
    private lateinit var amazonGameDao: AmazonGameDao

    /** True while a [resyncAll] job is in progress. */
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /** True when at least one source has a configured export directory. */
    private val _anyConfigured = MutableStateFlow(false)
    val anyConfigured: StateFlow<Boolean> = _anyConfigured.asStateFlow()

    // In-memory mirror of configured dirs so _anyConfigured can be updated synchronously.
    private val configuredDirs: MutableMap<GameSource, String> =
        Collections.synchronizedMap(EnumMap(GameSource::class.java))

    private var resyncJob: Job? = null

    private val onInstallStatusChanged: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
        scope.launch {
            syncGame(event.appId, event.source)
        }
    }

    /**
     * Initialises the manager, loads stored export directories from preferences,
     * and runs an initial sync for every configured source.
     * Must be called once from [app.gamenative.PluviaApp.onCreate].
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        val ep = EntryPointAccessors.fromApplication(context, FrontendSyncEntryPoint::class.java)
        steamAppDao = ep.steamAppDao()
        epicGameDao = ep.epicGameDao()
        gogGameDao = ep.gogGameDao()
        amazonGameDao = ep.amazonGameDao()

        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)

        scope.launch {
            GameSource.entries.forEach { source ->
                val dir = PrefManager.getFrontendSyncDir(source)
                if (dir.isNotEmpty()) {
                    configuredDirs[source] = dir
                    syncAllInstalledGames(source, dir)
                }
            }
            _anyConfigured.value = configuredDirs.isNotEmpty()
        }
    }

    /** Returns the export file extension for [source] (e.g. `.steam`, `.epic`). */
    fun extensionFor(source: GameSource): String = when (source) {
        GameSource.STEAM -> ".steam"
        GameSource.EPIC -> ".epic"
        GameSource.GOG -> ".gog"
        GameSource.AMAZON -> ".amazon"
        GameSource.CUSTOM_GAME -> ".pcgame"
    }

    /**
     * Triggers a full resync of all configured sources, rewriting export files
     * to match the current installed-game state. Calling this while a resync is
     * already running cancels the in-progress job instead.
     */
    fun resyncAll() {
        if (resyncJob?.isActive == true) {
            resyncJob?.cancel()
            _isSyncing.value = false
            return
        }
        _isSyncing.value = true
        resyncJob = scope.launch {
            try {
                val snapshot = synchronized(configuredDirs) { configuredDirs.toMap() }
                snapshot.forEach { (source, dir) ->
                    ensureActive()
                    syncAllInstalledGames(source, dir)
                }
                SnackbarManager.show(appContext.getString(R.string.frontend_sync_resync_complete))
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Updates the export directory for [source].
     *
     * @param newPath New directory path, or an empty string to remove the configuration.
     * @param deleteOldFiles When `true`, existing export files in the previous directory
     *   are deleted before the new path is activated.
     */
    fun changeDirectory(source: GameSource, newPath: String, deleteOldFiles: Boolean) {
        // Capture oldPath and update in-memory state synchronously so UI reflects the change immediately.
        val oldPath = synchronized(configuredDirs) {
            val prev = configuredDirs[source].orEmpty()
            if (newPath.isNotEmpty()) configuredDirs[source] = newPath else configuredDirs.remove(source)
            _anyConfigured.value = configuredDirs.isNotEmpty()
            prev
        }

        scope.launch {
            if (oldPath.isNotEmpty() && oldPath != newPath && deleteOldFiles) {
                deleteAllFilesWithExtension(oldPath, extensionFor(source))
            }
            PrefManager.setFrontendSyncDir(source, newPath)
        }
    }

    private suspend fun syncGame(appId: Int, source: GameSource) {
        val dir = PrefManager.getFrontendSyncDir(source)
        if (dir.isEmpty()) return

        val gameName = lookupGameName(appId, source) ?: run {
            Timber.w("FrontendSyncManager: no game name for appId=%d source=%s", appId, source)
            return
        }

        val isInstalled = isGameInstalled(appId, source)
        val file = File(dir, "${sanitizeFileName(gameName)}${extensionFor(source)}")

        try {
            if (isInstalled) {
                file.parentFile?.mkdirs()
                file.writeText(appId.toString(), Charsets.UTF_8)
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "FrontendSyncManager: failed to sync file for appId=%d", appId)
        }
    }

    private suspend fun syncAllInstalledGames(source: GameSource, dir: String) {
        try {
            val games: List<Pair<Int, String>> = when (source) {
                GameSource.STEAM, GameSource.CUSTOM_GAME -> {
                    steamAppDao.getInstalledGames().map { it.id to it.name }
                }
                GameSource.EPIC -> {
                    epicGameDao.getInstalledGames().map { it.id to it.title }
                }
                GameSource.GOG -> {
                    gogGameDao.getInstalledGames()
                        .map { (it.id.toIntOrNull() ?: 0) to it.title }
                }
                GameSource.AMAZON -> {
                    amazonGameDao.getInstalledGames().map { it.appId to it.title }
                }
            }

            val targetDir = File(dir).also { it.mkdirs() }
            val ext = extensionFor(source)
            deleteAllFilesWithExtension(targetDir.absolutePath, ext)
            games.forEach { (appId, name) ->
                try {
                    File(targetDir, "${sanitizeFileName(name)}$ext").writeText(appId.toString(), Charsets.UTF_8)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "FrontendSyncManager: failed to write export file for appId=%d", appId)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "FrontendSyncManager: failed full sync for source=%s dir=%s", source, dir)
        }
    }

    private suspend fun lookupGameName(appId: Int, source: GameSource): String? = when (source) {
        GameSource.STEAM, GameSource.CUSTOM_GAME -> steamAppDao.findApp(appId)?.name
        GameSource.EPIC -> epicGameDao.getById(appId)?.title
        GameSource.GOG -> gogGameDao.getById(appId.toString())?.title
        GameSource.AMAZON -> amazonGameDao.getByAppId(appId)?.title
    }

    private suspend fun isGameInstalled(appId: Int, source: GameSource): Boolean = when (source) {
        GameSource.STEAM, GameSource.CUSTOM_GAME -> SteamService.isAppInstalled(appId)
        GameSource.EPIC -> epicGameDao.getById(appId)?.isInstalled ?: false
        GameSource.GOG -> gogGameDao.getById(appId.toString())?.isInstalled ?: false
        GameSource.AMAZON -> amazonGameDao.getByAppId(appId)?.isInstalled ?: false
    }

    private val invalidFileChars = Regex("""[\\/:*?"<>|]""")

    private fun sanitizeFileName(name: String): String =
        name.replace(invalidFileChars, "_").trim().ifEmpty { "unknown" }

    /** Deletes all files with [extension] directly inside [dir] (non-recursive, depth = 1). */
    internal fun deleteAllFilesWithExtension(dir: String, extension: String) {
        try {
            val directory = File(dir)
            if (!directory.isDirectory) {
                Timber.w("FrontendSyncManager: not a directory: %s", dir)
                return
            }
            directory.walkTopDown().maxDepth(1).filter { it.isFile && it.name.endsWith(extension) }.forEach { file ->
                if (!file.delete()) {
                    Timber.w("FrontendSyncManager: could not delete %s", file.absolutePath)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "FrontendSyncManager: failed to delete files in %s", dir)
        }
    }
}
