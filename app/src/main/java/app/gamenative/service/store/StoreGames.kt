package app.gamenative.service.store

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.data.StoreGame
import app.gamenative.data.gameSource
import app.gamenative.data.isUpdatePending
import app.gamenative.db.dao.StoreGameDao
import app.gamenative.enums.Marker
import app.gamenative.events.AndroidEvent
import app.gamenative.service.download.GameDownloadService
import app.gamenative.service.download.NativeTreeDelete
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.LocaleHelper
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.StorageUtils
import com.winlator.container.Container
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The shared half of every [StoreAdapter] store: library rows, install folders, the download
 * queue, update checks, uninstall and the lookups the launch path needs.
 */
object StoreGames {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun storeGameDao(): StoreGameDao
    }

    private const val TAG = "StoreGames"
    private const val UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = ConcurrentHashMap<Int, DownloadInfo>()
    private val updateCheckedAt = ConcurrentHashMap<Int, Long>()
    private val _all = MutableStateFlow<List<StoreGame>>(emptyList())

    @Volatile private var appContext: Context? = null
    @Volatile private var byAppId: Map<Int, StoreGame> = emptyMap()

    /** Every store game, kept in step with the table. */
    val all: StateFlow<List<StoreGame>> = _all.asStateFlow()

    /** Reads the table into memory and follows it; local only, no network. */
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            dao().observeAll().collect { games ->
                byAppId = games.associateBy { it.appId }
                _all.value = games
            }
        }
    }

    private fun dao(): StoreGameDao =
        EntryPointAccessors.fromApplication(checkNotNull(appContext) { "StoreGames not attached" }, Deps::class.java).storeGameDao()

    fun isStore(source: GameSource): Boolean = StoreAdapters.of(source) != null

    fun adapter(game: StoreGame): StoreAdapter? = StoreAdapters.of(game.gameSource())

    fun game(appId: Int): StoreGame? =
        byAppId[appId] ?: appContext?.let { runBlocking(Dispatchers.IO) { dao().getByAppId(appId) } }

    fun isSignedIn(context: Context, source: GameSource): Boolean = StoreAdapters.of(source)?.isSignedIn(context) == true

    enum class Availability { AVAILABLE, UNAVAILABLE, NOT_CHECKED }

    /** Raised when the pinned helper archive cannot serve a store; the library shows the store as unavailable. */
    class StoreUnavailableException(source: GameSource) : Exception("${StoreAdapters.displayName(source)} is not available with this helper")

    private val _helperFailures = MutableStateFlow<Set<GameSource>>(emptySet())

    /** Stores whose helper archive could not be fetched or turned out unable to serve them in this run. */
    val helperFailures: StateFlow<Set<GameSource>> = _helperFailures.asStateFlow()

    /** Local only: what the extracted helper says, or [Availability.NOT_CHECKED] before it is extracted. */
    fun availability(context: Context, source: GameSource): Availability {
        val adapter = StoreAdapters.of(source) ?: return Availability.UNAVAILABLE
        if (source in _helperFailures.value) return Availability.UNAVAILABLE
        return when (runCatching { adapter.helperServesStore(context) }.getOrDefault(false)) {
            true -> Availability.AVAILABLE
            false -> Availability.UNAVAILABLE
            null -> Availability.NOT_CHECKED
        }
    }

    /** A signed-in account on a store the helper does not rule out. */
    fun anySignedIn(context: Context): Boolean =
        StoreAdapters.all.any { it.isSignedIn(context) && availability(context, it.source) != Availability.UNAVAILABLE }

    private suspend fun ensureHelper(context: Context, adapter: StoreAdapter): Result<Unit> {
        when (availability(context, adapter.source)) {
            Availability.AVAILABLE -> return Result.success(Unit)
            Availability.UNAVAILABLE -> return Result.failure(StoreUnavailableException(adapter.source))
            Availability.NOT_CHECKED -> Unit
        }
        val fetched = runCatching { adapter.prepareHelper(context) }
            .onFailure { if (it is CancellationException) throw it; Timber.tag(TAG).w(it, "${adapter.displayName} helper unavailable") }
        if (fetched.isSuccess && runCatching { adapter.helperServesStore(context) }.getOrNull() == true) return Result.success(Unit)
        Timber.tag(TAG).i("${adapter.displayName} is not served by the pinned helper")
        _helperFailures.update { it + adapter.source }
        return Result.failure(StoreUnavailableException(adapter.source))
    }

    fun isInstalled(appId: Int): Boolean = game(appId)?.isInstalled == true

    /** Install folder of an installed game, or null. */
    fun installPath(appId: Int): String? = game(appId)?.takeIf { it.isInstalled && it.installPath.isNotEmpty() }?.installPath

    fun title(appId: Int): String? = game(appId)?.title

    fun executable(appId: Int): String? {
        val game = game(appId)?.takeIf { it.isInstalled } ?: return null
        return adapter(game)?.executable(game, File(game.installPath))
    }

    suspend fun updateInstallPath(appId: Int, path: String) {
        val game = game(appId) ?: return
        dao().update(game.copy(installPath = path))
    }

    // ── Account and library ─────────────────────────────────────────────────

    suspend fun signIn(context: Context, source: GameSource): Result<Int> {
        val adapter = StoreAdapters.of(source) ?: return Result.failure(IllegalArgumentException("No store for $source"))
        ensureHelper(context, adapter).onFailure { return Result.failure(it) }
        adapter.signIn(context).onFailure { return Result.failure(it) }
        return refreshLibrary(context, source)
    }

    /** Signs in and loads the library without tying the work to a screen; failures show a snackbar. */
    fun startSignIn(context: Context, source: GameSource, onSignedIn: () -> Unit = {}) {
        val localized = LocaleHelper.applyLanguage(context, PrefManager.appLanguage)
        scope.launch {
            signIn(context, source).onSuccess { withContext(Dispatchers.Main) { onSignedIn() } }.onFailure {
                if (it is CancellationException || it is StoreUnavailableException) return@onFailure
                SnackbarManager.show(localized.getString(R.string.store_sign_in_failed, StoreAdapters.displayName(source), it.message.orEmpty()))
            }
        }
    }

    suspend fun signOut(context: Context, source: GameSource) = withContext(Dispatchers.IO) {
        StoreAdapters.of(source)?.signOut(context)
        dao().deleteNotInstalled(source.name)
    }

    suspend fun refreshLibrary(context: Context, source: GameSource): Result<Int> = withContext(Dispatchers.IO) {
        val adapter = StoreAdapters.of(source) ?: return@withContext Result.failure(IllegalArgumentException("No store for $source"))
        ensureHelper(context, adapter).onFailure { return@withContext Result.failure(it) }
        runCatching {
            val owned = adapter.fetchLibrary(context)
            dao().replaceLibrary(source.name, owned)
            Timber.tag(TAG).i("${adapter.displayName} library: ${owned.size} game(s)")
            owned.size
        }.onFailure { if (it is CancellationException) throw it; Timber.tag(TAG).e(it, "${adapter.displayName} library refresh failed") }
    }

    /** Refreshes every signed-in store; used by the library's pull to refresh. */
    suspend fun refreshSignedIn(context: Context) {
        for (adapter in StoreAdapters.all) {
            if (availability(context, adapter.source) != Availability.UNAVAILABLE && adapter.isSignedIn(context)) refreshLibrary(context, adapter.source)
        }
    }

    // ── Install folders ─────────────────────────────────────────────────────

    fun internalRoot(context: Context, source: GameSource): File = File(context.dataDir, source.name)

    fun externalRoot(source: GameSource): File = File(File(PrefManager.externalStoragePath, source.name), "games")

    private fun defaultRoot(context: Context, source: GameSource): File =
        if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) externalRoot(source) else internalRoot(context, source)

    fun installDir(context: Context, game: StoreGame): File {
        if (game.installPath.isNotEmpty()) return File(game.installPath)
        val name = game.title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim().ifEmpty { "game_${game.appId}" }
        return File(defaultRoot(context, game.gameSource()), name)
    }

    /** Drive path of [installPath] inside [container], e.g. "A:\\". */
    fun windowsDir(container: Container, installPath: String): String {
        for (drive in Container.drivesIterator(container.drives)) {
            if (drive[1] == installPath) return "${drive[0]}:\\"
        }
        return "A:\\"
    }

    // ── Downloads ───────────────────────────────────────────────────────────

    fun downloadInfo(appId: Int): DownloadInfo? = downloads[appId]

    val activeDownloads: Map<Int, DownloadInfo> get() = downloads

    fun hasPartialDownload(appId: Int): Boolean {
        if (downloads.containsKey(appId)) return false
        val game = game(appId) ?: return false
        val path = game.installPath.ifEmpty { return false }
        return MarkerUtils.hasMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
    }

    /** Games with a download that stopped before finishing. */
    fun partialDownloads(): List<StoreGame> = _all.value.filter { hasPartialDownload(it.appId) }

    /** Starts or resumes installing [appId]; an installed game is updated in place. */
    fun download(context: Context, appId: Int): Result<DownloadInfo> {
        val previous = downloads[appId]
        if (previous != null && previous.isActive()) return Result.success(previous)
        val game = game(appId) ?: return Result.failure(IllegalArgumentException("Unknown store game $appId"))
        val adapter = adapter(game) ?: return Result.failure(IllegalArgumentException("No store for ${game.source}"))
        if (game.unsupportedReason.isNotEmpty()) return Result.failure(UnsupportedOperationException(game.unsupportedReason))
        val localized = LocaleHelper.applyLanguage(context, PrefManager.appLanguage)
        val dir = installDir(context, game)
        if (!dir.isDirectory && !dir.mkdirs()) return Result.failure(IllegalStateException("Cannot create ${dir.path}"))
        val source = game.gameSource()

        val info = DownloadInfo(jobCount = 1, gameId = appId, downloadingAppIds = CopyOnWriteArrayList())
        info.setActive(true)
        info.updateStatusMessage(localized.getString(R.string.download_preparing))
        downloads[appId] = info
        MarkerUtils.removeMarker(dir.path, Marker.DOWNLOAD_COMPLETE_MARKER)
        MarkerUtils.addMarker(dir.path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
        GameDownloadService.registerDownload(gameSource = source, gameId = appId.toString(), downloadInfo = info, installPath = dir.path)
        PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(appId, true))
        StoreDownloadService.start(context)

        val job = scope.launch {
            try {
                previous?.awaitCompletion(60_000)
                if (game.installPath != dir.path) dao().update(game.copy(installPath = dir.path))
                val version = adapter.install(context, game, dir, info)
                MarkerUtils.removeMarker(dir.path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                MarkerUtils.addMarker(dir.path, Marker.DOWNLOAD_COMPLETE_MARKER)
                val size = StorageUtils.getFolderSize(dir.path)
                val current = game(appId) ?: game
                dao().update(
                    current.copy(
                        isInstalled = true,
                        installPath = dir.path,
                        installSize = size,
                        installedVersion = version,
                        latestVersion = version,
                    ),
                )
                info.setProgress(1f)
                info.setActive(false)
                info.emitProgressChange()
                SnackbarManager.show(localized.getString(R.string.library_download_completed, game.title))
                PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(appId, source))
                GameDownloadService.unregisterDownload(context, source, appId.toString())
            } catch (e: CancellationException) {
                Timber.tag(TAG).i("Download of ${game.title} stopped")
                info.setActive(false)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Download of ${game.title} failed")
                info.setActive(false)
                GameDownloadService.removeDownload(context, source, appId.toString())
                if (e is UnsupportedOperationException) {
                    MarkerUtils.removeMarker(dir.path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    markUnsupported(appId, e.message ?: "unsupported")
                    SnackbarManager.show(localized.getString(R.string.store_install_unsupported, adapter.displayName))
                } else {
                    SnackbarManager.show(localized.getString(R.string.manifest_download_failed, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                downloads.remove(appId, info)
                PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(appId, false))
                StoreDownloadService.stopIfIdle(context)
            }
        }
        info.setDownloadJob(job)
        return Result.success(info)
    }

    suspend fun markUnsupported(appId: Int, reason: String) {
        val game = game(appId) ?: return
        if (game.unsupportedReason != reason) dao().update(game.copy(unsupportedReason = reason))
    }

    fun cancelDownload(appId: Int): Boolean {
        val info = downloads[appId] ?: return false
        info.cancel()
        return true
    }

    /** Asks the store whether a newer build exists; at most once every ten minutes per game. */
    suspend fun checkForUpdate(context: Context, appId: Int): Boolean = withContext(Dispatchers.IO) {
        val game = game(appId)?.takeIf { it.isInstalled } ?: return@withContext false
        val now = System.currentTimeMillis()
        val last = updateCheckedAt[appId]
        if (last != null && now - last < UPDATE_CHECK_INTERVAL_MS) return@withContext game.isUpdatePending()
        updateCheckedAt[appId] = now
        val latest = runCatching { adapter(game)?.latestVersion(context, game) }
            .onFailure { if (it is CancellationException) throw it; Timber.tag(TAG).w(it, "Update check failed for ${game.title}") }
            .getOrNull() ?: return@withContext game.isUpdatePending()
        val updated = game.copy(latestVersion = latest)
        if (latest != game.latestVersion) dao().update(updated)
        updated.isUpdatePending()
    }

    suspend fun uninstall(context: Context, appId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val game = game(appId) ?: return@withContext Result.failure(IllegalArgumentException("Unknown store game $appId"))
        runCatching<Unit> {
            downloads[appId]?.let { it.cancel(); it.awaitCompletion() }
            GameDownloadService.removeDownload(context, game.gameSource(), appId.toString())
            adapter(game)?.onUninstall(context, game)
            if (game.installPath.isNotEmpty() && File(game.installPath).exists()) {
                val dir = File(game.installPath).canonicalFile
                val roots = listOf(internalRoot(context, game.gameSource()), externalRoot(game.gameSource())).map { it.canonicalFile }
                if (roots.any { dir.path.startsWith(it.path + File.separator) }) {
                    NativeTreeDelete.deleteTreeFast(dir)
                } else {
                    Timber.tag(TAG).w("Not deleting ${dir.path}: outside the ${game.source} folders")
                }
            }
            dao().update(game.copy(isInstalled = false, installPath = "", installSize = 0, installedVersion = "", latestVersion = ""))
            updateCheckedAt.remove(appId)
            PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(appId, false))
            PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(appId, game.gameSource()))
        }
    }
}
