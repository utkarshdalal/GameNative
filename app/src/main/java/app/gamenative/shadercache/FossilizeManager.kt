package app.gamenative.shadercache

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.ApiResult
import app.gamenative.api.ShaderCacheApi
import app.gamenative.api.SupportApi
import app.gamenative.data.DownloadInfo
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.events.AndroidEvent
import app.gamenative.utils.LaunchSkip
import app.gamenative.utils.Net
import com.posthog.PostHog
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.FileUtils
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Shared shader cache built on Valve's Fossilize.
 *
 * A game has one pool per translation layer version (see [ShaderCacheKey]); a container uses the pools of the
 * versions it loads.
 * Install: as the last step of a game install the game's pools are downloaded into app storage; the first launch
 * moves them into the container.
 * App start: one request asks which pools of the installed games are newer; nothing is downloaded.
 * Launch: the capture layer is installed per container as an implicit layer and records every
 * pipeline the game creates into a fresh session database, skipping entries already in the
 * downloaded pools. Exit: new session databases are uploaded and deleted. Before the next launch:
 * the merged pools are downloaded if they are newer and replayed with fossilize-replay so
 * the driver's disk cache is warm.
 */
object FossilizeManager {
    private const val TAG = "Fossilize"

    private const val BUNDLED_LAYER_LIB = "libfossilize_layer.so"
    private const val LAYER_LIB = "libVkLayer_fossilize.so"
    private const val REPLAY_BIN = "libfossilize_replay.so"
    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"
    private const val MANIFEST_FILENAME = "VkLayer_fossilize.json"
    private const val VERSION_FILENAME = ".fossilize_runtime_version"
    private const val MANIFEST_LIBRARY_PATH = "../../../lib/$LAYER_LIB"

    private const val CACHE_RELATIVE_DIR = ".cache/fossilize"
    private const val RECORD_DIR = "record"
    private const val POOL_DIR = "pool"
    private const val FILTER_FILENAME = "application_filter.json"
    private const val LOCAL_POOL_FILENAME = "local.foz"
    private const val SESSION_KEY_FILENAME = "key.json"

    private const val EXTRA_REPLAYED = "fossilizeReplayed"

    private const val FOZ_HEADER_BYTES = 16L
    private const val MAX_RETAINED_BYTES = 200L * 1024 * 1024
    private const val MAX_TRANSFER_BYTES = 256L * 1024 * 1024
    private const val START_CHECK_DELAY_MS = 10_000L
    private const val POOL_LOOKUP_TIMEOUT_MS = 6_000L
    private const val POOL_DOWNLOAD_TIMEOUT_MS = 3 * 60 * 1000L
    private const val REPLAY_WALL_CLOCK_MS = 10 * 60 * 1000L
    private const val REPLAY_STALL_SECONDS = 30
    private const val CANCEL_POLL_MS = 250L
    private const val OUTPUT_TAIL_LINES = 20

    private val FILTERED_APPLICATIONS = listOf("gamescope", "Xwayland")
    private val FILTERED_ENGINES = listOf("mesa zink")
    private val PROGRESS_LINE = Regex("Overall (\\d+) / (\\d+)")

    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val launchWorkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadMutex = Mutex()

    // What the server last said about each pool this process asked about. A pool in checkedPools but not in
    // remotePools does not exist.
    private val remotePools = ConcurrentHashMap<ShaderPool, ShaderCacheApi.PoolVersion>()
    private val checkedPools: MutableSet<ShaderPool> = ConcurrentHashMap.newKeySet()
    private val startCheckRan = AtomicBoolean(false)
    private val stagingJobs = ConcurrentHashMap<String, Job>()

    // Counts the uninstalls of each game; an install-time download that started before one does not keep its pool.
    private val stagedPoolDrops = ConcurrentHashMap<String, Int>()
    private val stagingLock = Any()

    // Pools an install staged in this process; their game's installed state may be written after the step.
    private val stagedThisProcess: MutableSet<ShaderPool> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var activeSessionDir: File? = null

    private data class ReplayOutcome(val exitCode: Int, val timedOut: Boolean, val completed: Int, val total: Int, val tail: List<String>)

    private data class RecordedSession(val key: ShaderCacheKey, val wrapper: String)

    private class ReplayDatabase(val id: String, val file: File, val entries: Long)

    @JvmStatic
    fun isEnabled(container: Container?): Boolean =
        container != null &&
            container.containerVariant.equals(Container.BIONIC, ignoreCase = true) &&
            PrefManager.sharedShaderCache

    private fun cacheDir(container: Container) = File(container.rootDir, CACHE_RELATIVE_DIR)
    private fun recordDir(container: Container) = File(cacheDir(container), RECORD_DIR)
    private fun poolDir(container: Container) = File(cacheDir(container), POOL_DIR)
    private fun poolFile(container: Container, pool: ShaderPool) = File(poolDir(container), "${pool.fileName}.foz")
    private fun poolMetaFile(container: Container, pool: ShaderPool) = File(poolDir(container), "${pool.fileName}.json")
    private fun localPoolFile(container: Container) = File(poolDir(container), LOCAL_POOL_FILENAME)

    @JvmStatic
    fun ensureRuntimeInstalled(context: Context, container: Container): Boolean {
        val source = File(context.applicationInfo.nativeLibraryDir, BUNDLED_LAYER_LIB)
        if (!source.isFile) {
            Timber.tag(TAG).i("%s not bundled; shader cache recording is off", BUNDLED_LAYER_LIB)
            return false
        }
        val libFile = File(File(container.rootDir, LIB_RELATIVE_DIR), LAYER_LIB)
        val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val manifestFile = File(layerDir, MANIFEST_FILENAME)
        val versionFile = File(layerDir, VERSION_FILENAME)
        val runtimeVersion = "${source.length()}-${source.lastModified()}"
        val installed = versionFile.takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }
        if (installed == runtimeVersion && libFile.isFile && manifestFile.isFile) return true
        return try {
            libFile.parentFile?.mkdirs()
            layerDir.mkdirs()
            source.inputStream().use { input -> libFile.outputStream().use { input.copyTo(it) } }
            FileUtils.chmod(libFile, 0b111101101)
            FileUtils.writeString(manifestFile, manifestJson())
            FileUtils.writeString(versionFile, runtimeVersion)
            Timber.tag(TAG).i("Installed Fossilize layer %s into %s", runtimeVersion, container.rootDir)
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to install Fossilize layer")
            false
        }
    }

    private fun manifestJson(): String = JSONObject()
        .put("file_format_version", "1.0.0")
        .put(
            "layer",
            JSONObject()
                .put("name", "VK_LAYER_fossilize")
                .put("type", "GLOBAL")
                .put("library_path", MANIFEST_LIBRARY_PATH)
                .put("api_version", "1.4.303")
                .put("implementation_version", "1")
                .put("description", "Fossilize capture layer")
                .put(
                    "functions",
                    JSONObject()
                        .put("vkGetInstanceProcAddr", "VK_LAYER_fossilize_GetInstanceProcAddr")
                        .put("vkGetDeviceProcAddr", "VK_LAYER_fossilize_GetDeviceProcAddr"),
                )
                .put("enable_environment", JSONObject().put("FOSSILIZE", "1"))
                .put("disable_environment", JSONObject().put("DISABLE_FOSSILIZE", "1")),
        )
        .toString(2)

    @JvmStatic
    fun disableLayer(container: Container) {
        val manifest = File(File(container.rootDir, LAYER_RELATIVE_DIR), MANIFEST_FILENAME)
        if (manifest.exists()) manifest.delete()
    }

    @JvmStatic
    fun applyLaunchEnv(context: Context, container: Container?, envVars: EnvVars): Boolean {
        envVars.remove("FOSSILIZE")
        if (container == null) return false
        if (!isEnabled(container) || !ensureRuntimeInstalled(context, container)) {
            disableLayer(container)
            envVars.put("DISABLE_FOSSILIZE", "1")
            return false
        }
        return try {
            val sessionDir = File(recordDir(container), "session-${System.currentTimeMillis()}")
            sessionDir.mkdirs()
            activeSessionDir = sessionDir
            val key = ShaderCacheKey.of(container)
            if (key != null) FileUtils.writeString(File(sessionDir, SESSION_KEY_FILENAME), sessionJson(key, container).toString())
            envVars.remove("DISABLE_FOSSILIZE")
            envVars.put("FOSSILIZE", "1")
            envVars.put("FOSSILIZE_DUMP_PATH", File(sessionDir, "fossilize").absolutePath)
            val readOnly = key?.pools.orEmpty().map { poolFile(container, it) }.filter { it.isFile }
                .ifEmpty { listOf(localPoolFile(container)).filter { it.isFile } }
            if (readOnly.isNotEmpty()) envVars.put("FOSSILIZE_DUMP_PATH_READ_ONLY", readOnly.joinToString(":") { it.absolutePath })
            envVars.put("FOSSILIZE_APPLICATION_INFO_FILTER_PATH", writeApplicationFilter(container).absolutePath)
            val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR).absolutePath
            val layerPath = envVars.get("VK_LAYER_PATH")
            if (layerPath.split(':').none { it == layerDir }) {
                envVars.put("VK_LAYER_PATH", if (layerPath.isEmpty()) layerDir else "$layerPath:$layerDir")
            }
            Timber.tag(TAG).i("Recording into %s", sessionDir)
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to arm Fossilize recording")
            disableLayer(container)
            envVars.remove("FOSSILIZE")
            envVars.put("DISABLE_FOSSILIZE", "1")
            false
        }
    }

    private fun writeApplicationFilter(container: Container): File {
        val file = File(cacheDir(container), FILTER_FILENAME)
        val json = JSONObject()
            .put("asset", "FossilizeApplicationInfoFilter")
            .put("version", 1)
            .put("blacklistedApplicationNames", JSONArray(FILTERED_APPLICATIONS))
            .put("blacklistedEngineNames", JSONArray(FILTERED_ENGINES))
            .toString(2)
        if (!file.isFile || file.readText() != json) {
            file.parentFile?.mkdirs()
            FileUtils.writeString(file, json)
        }
        return file
    }

    /**
     * Downloads the newest pool for this container and replays it. Blocking; never throws. Stops early once [isCancelled]
     * or once the player presses Skip.
     */
    @JvmStatic
    fun prepareBeforeLaunch(context: Context, container: Container, launcher: BionicProgramLauncherComponent, isCancelled: () -> Boolean) {
        val skipped = AtomicBoolean(false)
        val shouldStop = { isCancelled() || skipped.get() }
        try {
            if (!isEnabled(container)) return
            val replayBin = File(context.applicationInfo.nativeLibraryDir, REPLAY_BIN)
            if (!replayBin.isFile) {
                Timber.tag(TAG).i("%s not bundled; skipping shader replay", REPLAY_BIN)
                return
            }
            val key = ShaderCacheKey.of(container) ?: run {
                Timber.tag(TAG).i("No shader cache key for %s", container.id)
                return
            }
            val pools = refreshPools(context, container, key, shouldStop, skipped).filterKeys { poolFile(container, it).isFile }
            val databases = pools.map { (pool, meta) ->
                ReplayDatabase("${pool.game}/${pool.layer}|${meta.optLong("version")}", poolFile(container, pool), meta.optLong("entries"))
            }.ifEmpty {
                val local = localPoolFile(container).takeIf { it.isFile } ?: return
                listOf(ReplayDatabase("local|${local.length()}-${local.lastModified()}", local, 0L))
            }
            // The stamp lists the driver and each pool version replayed for it; only pools missing from it are replayed.
            val driver = driverIdentity(container)
            val replayed = container.getExtra(EXTRA_REPLAYED, "").split(';')
            val done = if (replayed.first() == driver) replayed.drop(1).toSet() else emptySet()
            val todo = databases.filter { it.id !in done }
            if (todo.isEmpty()) {
                Timber.tag(TAG).d("Replay of %s already done for %s, skipping", key, databases.joinToString { it.id })
                return
            }
            if (shouldStop()) return
            val stamp = (listOf(driver) + databases.map { it.id }).joinToString(";")
            replay(context, container, launcher, key, todo, replayBin, stamp, shouldStop, skipped)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Shader cache preparation failed")
        } finally {
            LaunchSkip.withdraw(skipped)
        }
    }

    /** Brings the container's pools up to date; returns the metadata of each pool it has. */
    private fun refreshPools(
        context: Context,
        container: Container,
        key: ShaderCacheKey,
        shouldStop: () -> Boolean,
        skipped: AtomicBoolean,
    ): Map<ShaderPool, JSONObject> {
        dropStalePoolFiles(container, key)
        val local = key.pools.mapNotNull { pool ->
            runCatching { JSONObject(poolMetaFile(container, pool).readText()) }.getOrNull()
                ?.takeIf { poolFile(container, pool).isFile }?.let { pool to it }
        }.toMap(HashMap())
        val staging = stagingJobs[key.game]
        // A registered step may not have started yet; join() starts it.
        if (staging != null && !staging.isCompleted) {
            // The game's install is still downloading its pools; a second download would race it.
            PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(context.getString(R.string.shader_cache_preparing)))
            LaunchSkip.offer(skipped) { skipped.set(true) }
            awaitWork(POOL_DOWNLOAD_TIMEOUT_MS, shouldStop, cancelOnStop = false) { staging.join() }
            if (!staging.isCompleted) {
                Timber.tag(TAG).i("Install-time pool download for %s still running; launching without it", key)
                return local
            }
        }
        val stagedDir = StagedPools.dir(context.filesDir)
        StagedPools.dropGame(stagedDir, key.game, key.pools)
        for (pool in key.pools) {
            val adopted = StagedPools.adopt(
                stagedDir,
                pool,
                poolDir(container),
                poolFile(container, pool),
                poolMetaFile(container, pool),
                local[pool]?.optLong("version"),
            ) ?: continue
            local[pool] = adopted
            localPoolFile(container).delete()
            val version = adopted.optLong("version")
            if (pool in checkedPools) {
                remotePools.merge(pool, ShaderCacheApi.PoolVersion(version, adopted.optLong("size"), adopted.optLong("entries"))) { old, new ->
                    if (new.version >= old.version) new else old
                }
            }
            Timber.tag(TAG).i("Adopted staged pool v%d for %s", version, pool)
        }
        val unknown = key.pools.any { pool ->
            if (pool !in checkedPools) return@any true
            val known = remotePools[pool] ?: return@any false
            (local[pool]?.optLong("version") ?: -1L) < known.version && known.size <= MAX_TRANSFER_BYTES
        }
        if (!unknown) return local
        // Abandoned rather than cancelled: the lookup can include an account token refresh that has to finish.
        val remote = awaitWork(POOL_LOOKUP_TIMEOUT_MS, shouldStop, cancelOnStop = false) { ShaderCacheApi.fetchPools(key) }
        if (remote !is ApiResult.Success) {
            Timber.tag(TAG).w("Pool lookup for %s gave no answer in time or failed (%s); using the local pools", key.game, remote)
            return local
        }
        rememberPools(key, remote.data)
        val wanted = key.pools.mapNotNull { pool ->
            val served = remote.data[pool.layer] ?: return@mapNotNull null
            if ((local[pool]?.optLong("version") ?: -1L) >= served.version) return@mapNotNull null
            if (served.size > MAX_TRANSFER_BYTES) {
                Timber.tag(TAG).w("Pool %s is %d bytes, over the %d byte limit", pool, served.size, MAX_TRANSFER_BYTES)
                return@mapNotNull null
            }
            pool to served
        }
        if (wanted.isEmpty()) return local
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(context.getString(R.string.shader_cache_preparing)))
        LaunchSkip.offer(skipped) { skipped.set(true) }
        val deadline = System.currentTimeMillis() + POOL_DOWNLOAD_TIMEOUT_MS
        for ((pool, served) in wanted) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0 || shouldStop()) break
            downloadPool(container, pool, served, remaining, shouldStop)?.let {
                local[pool] = it
                localPoolFile(container).delete()
            }
        }
        return local
    }

    private fun downloadPool(
        container: Container,
        pool: ShaderPool,
        served: ShaderCacheApi.Pool,
        timeoutMs: Long,
        shouldStop: () -> Boolean,
    ): JSONObject? {
        val target = poolFile(container, pool)
        val metaFile = poolMetaFile(container, pool)
        val partial = File(target.parentFile, target.name + ".part")
        // Cancelled and joined so the .part file is no longer written once this returns.
        val result = awaitWork(timeoutMs, shouldStop, cancelOnStop = true) {
            SupportApi.downloadSigned(
                served.url,
                partial,
                if (served.size > 0) served.size else MAX_TRANSFER_BYTES,
                client = Net.launchDownload,
            )
        }
        val hash = if (result is ApiResult.Success) result.data else null
        if (hash == null || !hash.equals(served.sha256, ignoreCase = true)) {
            partial.delete()
            if (hash == null && shouldStop()) {
                Timber.tag(TAG).i("Download of pool %s skipped or cancelled", pool)
            } else {
                Timber.tag(TAG).w("Download of pool %s failed (%s)", pool, if (hash == null) result else "sha256 mismatch")
            }
            return null
        }
        metaFile.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            return null
        }
        val meta = StagedPools.meta(served.version, served.sha256, served.size, served.entries)
        FileUtils.writeString(metaFile, meta.toString())
        Timber.tag(TAG).i("Downloaded pool v%d for %s (%d entries)", served.version, pool, served.entries)
        return meta
    }

    /**
     * Install step: downloads the pools of the game being installed, for the layer versions its container loads or a new
     * container would load, into app storage, where its first launch picks them up. Shows on the install as a
     * post-install step. Ends quietly on any failure; cancelling the install cancels it.
     */
    suspend fun downloadPoolDuringInstall(
        context: Context,
        downloadInfo: DownloadInfo,
        containerId: String,
        store: String,
        gameId: String,
        eventGameId: Int = downloadInfo.gameId,
    ) {
        if (!PrefManager.sharedShaderCache) return
        val game = ShaderCacheKey.storeGame(store, gameId) ?: return
        // Everything below is inside the try: no part of this step may fail the install.
        try {
            val generation = stagedPoolDrops[game] ?: 0
            val appContext = context.applicationContext ?: context
            downloadInfo.setPostInstallSyncing(true)
            downloadInfo.updateStatusMessage(appContext.getString(R.string.shader_cache_preparing))
            PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(eventGameId, true))
            coroutineScope {
                val step = async(start = CoroutineStart.LAZY) { stagePools(appContext, downloadInfo, containerId, game, generation) }
                val running = stagingJobs.putIfAbsent(game, step)
                if (running != null) {
                    step.cancel()
                    running.join()
                    return@coroutineScope
                }
                try {
                    step.await()
                } finally {
                    stagingJobs.remove(game, step)
                }
            }
        } catch (e: CancellationException) {
            // Only the install's own cancellation propagates; an uninstall cancelling the step just ends it.
            currentCoroutineContext().ensureActive()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Install-time pool download for %s failed", game)
        } finally {
            runCatching {
                downloadInfo.setPostInstallSyncing(false)
                downloadInfo.updateStatusMessage(null)
                PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(eventGameId, false))
            }.onFailure { Timber.tag(TAG).w(it, "Could not clear the install status for %s", game) }
        }
    }

    private suspend fun stagePools(context: Context, downloadInfo: DownloadInfo, containerId: String, game: String, generation: Int) {
        val dir = StagedPools.dir(context.filesDir)
        val container = runCatching { ContainerManager(context).getContainerById(containerId) }.getOrNull()
        val key = ShaderCacheKey.of(game, container)
        if (key.pools.isEmpty()) return
        stagedThisProcess.addAll(key.pools)
        // Abandoned rather than cancelled on timeout, as at launch: the lookup can include a token refresh.
        val lookup = launchWorkScope.async { ShaderCacheApi.fetchPools(key) }
        val remote = withTimeoutOrNull(POOL_LOOKUP_TIMEOUT_MS) { lookup.await() }
        if (remote !is ApiResult.Success) {
            Timber.tag(TAG).i("Install-time pool lookup for %s failed (%s)", key, remote ?: "timed out")
            return
        }
        rememberPools(key, remote.data)
        val deadline = System.currentTimeMillis() + POOL_DOWNLOAD_TIMEOUT_MS
        for (pool in key.pools) {
            val served = remote.data[pool.layer] ?: run {
                Timber.tag(TAG).i("No pool %s at install", pool)
                null
            } ?: continue
            val local = container?.let { c ->
                StagedPools.readMeta(poolMetaFile(c, pool))?.takeIf { poolFile(c, pool).isFile }?.optLong("version")
            }
            val have = maxOf(local ?: -1L, StagedPools.stagedVersion(dir, pool) ?: -1L)
            if (have >= served.version) {
                Timber.tag(TAG).i("Pool %s v%d is already on the device", pool, have)
                continue
            }
            if (served.size > MAX_TRANSFER_BYTES) {
                Timber.tag(TAG).w("Pool %s is %d bytes, over the %d byte limit", pool, served.size, MAX_TRANSFER_BYTES)
                continue
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            stagePool(context, downloadInfo, dir, pool, served, remaining, generation)
        }
    }

    private suspend fun stagePool(
        context: Context,
        downloadInfo: DownloadInfo,
        dir: File,
        pool: ShaderPool,
        served: ShaderCacheApi.Pool,
        timeoutMs: Long,
        generation: Int,
    ) {
        val target = StagedPools.poolFile(dir, pool)
        val partial = File(dir, target.name + ".part")
        var shown = -1
        val result = withTimeoutOrNull(timeoutMs) {
            SupportApi.downloadSigned(
                served.url,
                partial,
                if (served.size > 0) served.size else MAX_TRANSFER_BYTES,
                client = Net.launchDownload,
            ) { progress ->
                val percent = (progress * 100).toInt()
                if (percent != shown) {
                    shown = percent
                    downloadInfo.updateStatusMessage(context.getString(R.string.shader_cache_preparing_progress, percent))
                }
            }
        }
        val hash = (result as? ApiResult.Success)?.data
        if (hash == null || !hash.equals(served.sha256, ignoreCase = true)) {
            partial.delete()
            Timber.tag(TAG).w("Install-time download of pool %s failed (%s)", pool, if (hash == null) result ?: "timed out" else "sha256 mismatch")
            return
        }
        synchronized(stagingLock) {
            if ((stagedPoolDrops[pool.game] ?: 0) != generation) {
                partial.delete()
                Timber.tag(TAG).i("%s was uninstalled during its install-time pool download", pool.game)
                return
            }
            StagedPools.metaFile(dir, pool).delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                return
            }
            FileUtils.writeString(StagedPools.metaFile(dir, pool), StagedPools.meta(served.version, served.sha256, target.length(), served.entries).toString())
        }
        Timber.tag(TAG).i("Staged pool v%d for %s (%d entries, %d bytes)", served.version, pool, served.entries, target.length())
    }

    /** Deletes the staged pools of an uninstalled game. Never throws. */
    fun dropStagedPool(context: Context, store: String, gameId: String) {
        val game = ShaderCacheKey.storeGame(store, gameId) ?: return
        runCatching {
            synchronized(stagingLock) {
                stagedPoolDrops.merge(game, 1, Int::plus)
                stagingJobs[game]?.cancel()
                StagedPools.dropGame(StagedPools.dir(context.filesDir), game)
            }
        }.onFailure { Timber.tag(TAG).w(it, "Could not delete the staged pools of %s", game) }
    }

    private fun rememberPools(key: ShaderCacheKey, served: Map<String, ShaderCacheApi.Pool>) {
        for (pool in key.pools) {
            val found = served[pool.layer]
            if (found == null) {
                remotePools.remove(pool)
            } else {
                remotePools[pool] = ShaderCacheApi.PoolVersion(found.version, found.size, found.entries)
            }
            checkedPools.add(pool)
        }
    }

    /** Removes pool files under any other name: other layer versions and earlier key formats. */
    private fun dropStalePoolFiles(container: Container, key: ShaderCacheKey) {
        val keep = key.pools.flatMap { listOf(poolFile(container, it).name, poolMetaFile(container, it).name) } + LOCAL_POOL_FILENAME
        poolDir(container).listFiles()?.forEach { if (it.isFile && it.name !in keep) it.delete() }
    }

    /**
     * Asks the server once per process which pools the installed games have and at which version, so a launch whose
     * local pools are current makes no request. Downloads nothing; never throws.
     */
    suspend fun checkPoolsAtStart(
        context: Context,
        appInfoDao: AppInfoDao,
        gogGameDao: GOGGameDao,
        epicGameDao: EpicGameDao,
        amazonGameDao: AmazonGameDao,
    ) {
        if (!PrefManager.sharedShaderCache || !startCheckRan.compareAndSet(false, true)) return
        try {
            delay(START_CHECK_DELAY_MS)
            val keys = installedGames(context, appInfoDao, gogGameDao, epicGameDao, amazonGameDao).filter { it.pools.isNotEmpty() }
            runCatching {
                StagedPools.dropAllExcept(StagedPools.dir(context.filesDir), keys.flatMap { it.pools } + stagedThisProcess)
            }
            for (chunk in keys.chunked(ShaderCacheApi.MAX_CHECK_GAMES)) {
                val result = ShaderCacheApi.checkPools(chunk)
                if (result !is ApiResult.Success) {
                    Timber.tag(TAG).i("Pool check failed (%s)", result)
                    return
                }
                for ((pool, version) in result.data) {
                    remotePools.merge(pool, version) { old, new -> if (new.version >= old.version) new else old }
                }
                checkedPools.addAll(chunk.flatMap { it.pools })
            }
            Timber.tag(TAG).i(
                "Checked %d games, %d pools available",
                keys.size,
                keys.sumOf { key -> key.pools.count { remotePools.containsKey(it) } },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Pool check failed")
        }
    }

    private suspend fun installedGames(
        context: Context,
        appInfoDao: AppInfoDao,
        gogGameDao: GOGGameDao,
        epicGameDao: EpicGameDao,
        amazonGameDao: AmazonGameDao,
    ): List<ShaderCacheKey> {
        // Custom games are keyed by an exe hash, so only those with a shader cache container are asked about.
        val containerKeys = runCatching { ContainerManager(context).containers }.getOrNull().orEmpty()
            .filter { !ShaderCacheKey.isCustomGame(it) || isEnabled(it) }
            .mapNotNull { container -> runCatching { ShaderCacheKey.of(container) }.getOrNull() }
            .associateBy { it.game }
        val keys = linkedMapOf<String, ShaderCacheKey>()
        suspend fun add(store: String, ids: suspend () -> List<String>) {
            val found = try {
                ids()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            found?.forEach { id ->
                val game = ShaderCacheKey.storeGame(store, id) ?: return@forEach
                keys[game] = containerKeys[game] ?: ShaderCacheKey.of(game, null)
            }
        }
        add("steam") { appInfoDao.getAll().filter { it.isDownloaded }.map { it.id.toString() } }
        add("gog") { gogGameDao.getInstalledGames().map { it.id } }
        add("epic") { epicGameDao.getInstalledGames().map { it.appName } }
        add("amazon") { amazonGameDao.getInstalledGames().map { ShaderCacheKey.amazonGameId(it.productId) } }
        containerKeys.values.filter { it.game.startsWith("custom:") }.forEach { keys[it.game] = it }
        return keys.values.toList()
    }

    /**
     * Runs [block] off the calling thread and waits for it for at most [timeoutMs], or until [shouldStop]. Null if the
     * wait ended without a result or [block] failed; never throws. When the wait ends early, [block] is cancelled and
     * joined if [cancelOnStop], and otherwise left to finish on its own with its result dropped.
     */
    private fun <T> awaitWork(timeoutMs: Long, shouldStop: () -> Boolean, cancelOnStop: Boolean, block: suspend () -> T): T? = try {
        val work = launchWorkScope.async { block() }
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                while (work.isActive && !shouldStop()) withTimeoutOrNull(CANCEL_POLL_MS) { work.join() }
            }
            when {
                work.isActive -> {
                    if (cancelOnStop) work.cancelAndJoin()
                    null
                }
                else -> work.await()
            }
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "Shader cache network step failed")
        null
    }

    private fun driverIdentity(container: Container): String =
        "${container.graphicsDriver}:${KeyValueSet(container.graphicsDriverConfig).get("version")}"

    private fun replay(
        context: Context,
        container: Container,
        launcher: BionicProgramLauncherComponent,
        key: ShaderCacheKey,
        databases: List<ReplayDatabase>,
        replayBin: File,
        stamp: String,
        isCancelled: () -> Boolean,
        skipped: AtomicBoolean,
    ) {
        val env = launcher.buildShellEnvVars(null, arrayOf("FOSSILIZE", "FOSSILIZE_DUMP_PATH", "FOSSILIZE_DUMP_PATH_READ_ONLY"))
            ?: return
        env.put("DISABLE_FOSSILIZE", "1")
        val icd = env.get("VK_ICD_FILENAMES")
        if (icd.isEmpty() || icd.contains("gamescope")) {
            env.put("VK_ICD_FILENAMES", File(ImageFs.find(context).shareDir, "vulkan/icd.d/wrapper_icd.aarch64.json").absolutePath)
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        // fossilize-replay reads every database given to it as one.
        val args = databases.map { it.file.absolutePath } + listOf(
            "--num-threads", threads.toString(),
            "--timeout-seconds", REPLAY_STALL_SECONDS.toString(),
            "--disable-rate-limiter",
        )
        val workDir = poolDir(container)
        val started = System.currentTimeMillis()
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(context.getString(R.string.shader_cache_preparing)))
        LaunchSkip.offer(skipped) { skipped.set(true) }

        var command = listOf(replayBin.absolutePath)
        var outcome = runReplay(command + args + "--progress", env, workDir, started, context, isCancelled)
        if (outcome == null) {
            command = listOf("/system/bin/linker64", replayBin.absolutePath)
            outcome = runReplay(command + args + "--progress", env, workDir, started, context, isCancelled)
        }
        if (outcome == null) {
            Timber.tag(TAG).w("Could not start %s", REPLAY_BIN)
            return
        }
        if (outcome.exitCode != 0 && !outcome.timedOut && !isCancelled()) {
            Timber.tag(TAG).i("Multi-process replay failed (exit=%d); retrying in a single process", outcome.exitCode)
            outcome = runReplay(command + args, env, workDir, started, context, isCancelled) ?: outcome
        }
        if (isCancelled()) {
            Timber.tag(TAG).i("Replay of %s cancelled at %d/%d", key, outcome.completed, outcome.total)
            return
        }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        val succeeded = outcome.exitCode == 0
        Timber.tag(TAG).i(
            "Replay of %s finished: exit=%d timedOut=%s progress=%d/%d in %.1fs",
            key, outcome.exitCode, outcome.timedOut, outcome.completed, outcome.total, seconds,
        )
        if (!succeeded) Timber.tag(TAG).w("Replay of %s failed:\n%s", key, outcome.tail.joinToString("\n"))
        container.putExtra(EXTRA_REPLAYED, if (succeeded) stamp else "$stamp;failed")
        container.saveData()
        if (PrefManager.usageAnalyticsEnabled) {
            runCatching {
                PostHog.capture(
                    event = "shader_cache_replayed",
                    properties = keyProperties(key) + mapOf(
                        "pool_version" to databases.joinToString(",") { it.id },
                        "entries" to databases.sumOf { it.entries },
                        "seconds" to seconds,
                        "exit_code" to outcome.exitCode,
                        "timed_out" to outcome.timedOut,
                        "completed" to outcome.completed,
                        "total" to outcome.total,
                    ),
                )
            }
        }
    }

    private fun runReplay(
        command: List<String>,
        env: EnvVars,
        workDir: File,
        started: Long,
        context: Context,
        isCancelled: () -> Boolean,
    ): ReplayOutcome? {
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            for (entry in env.toStringArray()) {
                val split = entry.indexOf('=')
                if (split > 0) put(entry.substring(0, split), entry.substring(split + 1))
            }
        }
        val process = try {
            builder.start()
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Spawn failed: %s", command.first())
            return null
        }
        var completed = 0
        var total = 0
        val tail = ArrayDeque<String>()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val match = PROGRESS_LINE.find(line)
                    if (match != null) {
                        val current = match.groupValues[1].toInt()
                        val all = match.groupValues[2].toInt()
                        if (current != completed || all != total) {
                            completed = current
                            total = all
                            if (all > 0) {
                                val percent = (current * 100 / all).coerceIn(0, 100)
                                PluviaApp.events.emit(
                                    AndroidEvent.SetBootingSplashText(
                                        context.getString(R.string.shader_cache_preparing_progress, percent),
                                    ),
                                )
                            }
                        }
                    } else if (!line.startsWith("Fossilize INFO")) {
                        Timber.tag(TAG).d("replay: %s", line)
                        synchronized(tail) {
                            if (tail.size == OUTPUT_TAIL_LINES) tail.removeFirst()
                            tail.addLast(line)
                        }
                    }
                }
            }
        }, "fossilize-replay-output")
        reader.isDaemon = true
        reader.start()
        val deadline = started + REPLAY_WALL_CLOCK_MS
        var finished = false
        while (!finished && !isCancelled()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            finished = process.waitFor(minOf(remaining, CANCEL_POLL_MS), TimeUnit.MILLISECONDS)
        }
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(2_000)
        return ReplayOutcome(
            exitCode = if (finished) process.exitValue() else -1,
            timedOut = !finished,
            completed = completed,
            total = total,
            tail = synchronized(tail) { tail.toList() },
        )
    }

    /** Uploads recorded session databases for [container] in the background. Never throws. */
    @JvmStatic
    fun uploadPendingAsync(context: Context, container: Container): Job? {
        activeSessionDir = null
        if (!isEnabled(container)) return null
        val appContext = context.applicationContext
        return uploadScope.launch {
            try {
                uploadMutex.withLock { uploadPending(appContext, container) }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Shader cache upload failed")
            }
        }
    }

    private suspend fun uploadPending(context: Context, container: Container) {
        val root = recordDir(container)
        val active = activeSessionDir
        val sessions = root.listFiles()?.filter { it.isDirectory && it != active }?.sortedBy { it.name }.orEmpty()
        val uploaded = mutableMapOf<ShaderCacheKey, Pair<Int, Long>>()
        upload@ for (session in sessions) {
            val files = session.walkTopDown().filter { it.isFile && it.name.endsWith(".foz") }.sortedBy { it.lastModified() }.toList()
            if (files.isEmpty()) continue
            val recorded = readSession(session)
            if (recorded == null) {
                Timber.tag(TAG).i("Dropping %s: no shader cache key was recorded with it", session.name)
                session.deleteRecursively()
                continue
            }
            val key = recorded.key
            runCatching {
                val (dxvk, vkd3d) = RecordedLayers.read(files)
                ShaderCacheKey.recordObserved(container, recorded.wrapper, dxvk, vkd3d)
            }.onFailure { Timber.tag(TAG).w(it, "Could not read the layer versions of %s", session.name) }
            for (file in files) {
                val size = file.length()
                if (size <= FOZ_HEADER_BYTES || size > MAX_TRANSFER_BYTES) {
                    if (size > MAX_TRANSFER_BYTES) Timber.tag(TAG).w("Dropping %s: %d bytes is over the upload limit", file.name, size)
                    file.delete()
                    continue
                }
                val sha256 = sha256(file)
                val meta = ShaderCacheApi.UploadMeta(
                    sha256 = sha256,
                    size = size,
                    appVersion = BuildConfig.VERSION_NAME,
                    gpu = runCatching { GPUInformation.getRenderer(context) }.getOrDefault(""),
                    driver = driverIdentity(container),
                    displayServer = container.displayServer.orEmpty(),
                )
                when (val slot = ShaderCacheApi.beginUpload(key, meta)) {
                    is ApiResult.Success -> {
                        val put = SupportApi.uploadToSignedUrl(slot.data.putUrl, file, size, sha256)
                        if (put !is ApiResult.Success) break@upload
                        if (ShaderCacheApi.finishUpload(slot.data.key) !is ApiResult.Success) break@upload
                        file.delete()
                        val (count, bytes) = uploaded[key] ?: (0 to 0L)
                        uploaded[key] = (count + 1) to (bytes + size)
                    }
                    is ApiResult.HttpError -> {
                        if (slot.code == 413) {
                            Timber.tag(TAG).w("Server refused %s (%d bytes) as too large", file.name, size)
                            file.delete()
                            continue
                        }
                        break@upload
                    }
                    is ApiResult.NetworkError -> break@upload
                }
            }
        }
        pruneEmptyDirs(root)
        enforceRetentionCap(root)
        for ((key, totals) in uploaded) {
            Timber.tag(TAG).i("Uploaded %d shader databases (%d bytes) for %s", totals.first, totals.second, key)
            if (PrefManager.usageAnalyticsEnabled) {
                runCatching {
                    PostHog.capture(
                        event = "shader_cache_uploaded",
                        properties = keyProperties(key) + mapOf("files" to totals.first, "bytes" to totals.second),
                    )
                }
            }
        }
    }

    private fun sessionJson(key: ShaderCacheKey, container: Container): JSONObject = JSONObject()
        .put("game", key.game)
        .put("dxvk", key.dxvk)
        .put("vkd3d", key.vkd3d)
        .put("wrapper", ShaderCacheKey.wrapperSignature(container))

    private fun readSession(session: File): RecordedSession? = runCatching {
        val json = JSONObject(File(session, SESSION_KEY_FILENAME).readText())
        RecordedSession(
            key = ShaderCacheKey(
                game = json.getString("game"),
                dxvk = json.optString("dxvk").ifEmpty { null },
                vkd3d = json.optString("vkd3d").ifEmpty { null },
            ),
            wrapper = json.optString("wrapper"),
        )
    }.getOrNull()

    private fun keyProperties(key: ShaderCacheKey): Map<String, Any> =
        mapOf("game" to key.game, "dxvk" to key.dxvk.orEmpty(), "vkd3d" to key.vkd3d.orEmpty())

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pruneEmptyDirs(root: File) {
        val active = activeSessionDir
        root.listFiles()
            ?.filter { it.isDirectory && it != active && it.walkTopDown().none { f -> f.isFile && f.name.endsWith(".foz") } }
            ?.forEach { it.deleteRecursively() }
    }

    private fun enforceRetentionCap(root: File) {
        val active = activeSessionDir?.let { it.path + File.separator }
        val files = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".foz") && (active == null || !it.path.startsWith(active)) }
            .sortedBy { it.lastModified() }
            .toMutableList()
        var total = files.sumOf { it.length() }
        while (total > MAX_RETAINED_BYTES && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
        pruneEmptyDirs(root)
    }
}
