package app.gamenative.shadercache

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.ApiResult
import app.gamenative.api.ShaderCacheApi
import app.gamenative.api.SupportApi
import app.gamenative.events.AndroidEvent
import com.posthog.PostHog
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
 * Launch: the capture layer is installed per container as an implicit layer and records every
 * pipeline the game creates into a fresh session database, skipping entries already in the
 * downloaded pool. Exit: new session databases are uploaded and deleted. Before the next launch:
 * the merged pool for the game is downloaded and replayed with fossilize-replay so the driver's
 * disk cache is warm.
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
    private const val POOL_LOOKUP_TIMEOUT_MS = 15_000L
    private const val POOL_DOWNLOAD_TIMEOUT_MS = 3 * 60 * 1000L
    private const val REPLAY_WALL_CLOCK_MS = 10 * 60 * 1000L
    private const val REPLAY_STALL_SECONDS = 30
    private const val CANCEL_POLL_MS = 250L
    private const val OUTPUT_TAIL_LINES = 20

    private val FILTERED_APPLICATIONS = listOf("gamescope", "Xwayland")
    private val FILTERED_ENGINES = listOf("mesa zink")
    private val PROGRESS_LINE = Regex("Overall (\\d+) / (\\d+)")

    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadMutex = Mutex()

    @Volatile
    private var activeSessionDir: File? = null

    private data class ReplayOutcome(val exitCode: Int, val timedOut: Boolean, val completed: Int, val total: Int, val tail: List<String>)

    @JvmStatic
    fun isEnabled(container: Container?): Boolean =
        container != null &&
            container.containerVariant.equals(Container.BIONIC, ignoreCase = true) &&
            PrefManager.sharedShaderCache

    private fun cacheDir(container: Container) = File(container.rootDir, CACHE_RELATIVE_DIR)
    private fun recordDir(container: Container) = File(cacheDir(container), RECORD_DIR)
    private fun poolDir(container: Container) = File(cacheDir(container), POOL_DIR)
    private fun poolFile(container: Container, key: ShaderCacheKey) = File(poolDir(container), "${key.fileName}.foz")
    private fun poolMetaFile(container: Container, key: ShaderCacheKey) = File(poolDir(container), "${key.fileName}.json")
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
            if (key != null) FileUtils.writeString(File(sessionDir, SESSION_KEY_FILENAME), key.toJson().toString())
            envVars.remove("DISABLE_FOSSILIZE")
            envVars.put("FOSSILIZE", "1")
            envVars.put("FOSSILIZE_DUMP_PATH", File(sessionDir, "fossilize").absolutePath)
            (key?.let { poolFile(container, it) }?.takeIf { it.isFile }
                ?: localPoolFile(container).takeIf { it.isFile })?.let {
                envVars.put("FOSSILIZE_DUMP_PATH_READ_ONLY", it.absolutePath)
            }
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

    /** Downloads the newest pool for this container and replays it. Blocking; never throws. Stops early once [isCancelled]. */
    @JvmStatic
    fun prepareBeforeLaunch(context: Context, container: Container, launcher: BionicProgramLauncherComponent, isCancelled: () -> Boolean) {
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
            val meta = refreshPool(context, container, key, isCancelled)?.takeIf { poolFile(container, key).isFile }
            val poolFile = meta?.let { poolFile(container, key) } ?: localPoolFile(container).takeIf { it.isFile } ?: return
            val version = meta?.optLong("version")?.toString() ?: "local-${poolFile.length()}-${poolFile.lastModified()}"
            val stamp = "${key.fileName}|$version|${driverIdentity(container)}"
            val replayed = container.getExtra(EXTRA_REPLAYED, "")
            if (replayed == stamp || replayed == "$stamp|failed") {
                Timber.tag(TAG).d("Replay of %s already done for version %s, skipping", key.fileName, version)
                return
            }
            if (isCancelled()) return
            replay(context, container, launcher, key, poolFile, version, meta?.optLong("entries") ?: 0L, replayBin, stamp, isCancelled)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Shader cache preparation failed")
        }
    }

    private fun refreshPool(context: Context, container: Container, key: ShaderCacheKey, isCancelled: () -> Boolean): JSONObject? {
        val metaFile = poolMetaFile(container, key)
        val local = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
            ?.takeIf { poolFile(container, key).isFile }
        val remote = runUnlessCancelled(POOL_LOOKUP_TIMEOUT_MS, isCancelled) { ShaderCacheApi.fetchPool(key) }
        val pool = if (remote is ApiResult.Success) remote.data ?: return local else return local
        if (local != null && local.optLong("version") >= pool.version) return local
        if (pool.size > MAX_TRANSFER_BYTES) {
            Timber.tag(TAG).w("Pool for %s is %d bytes, over the %d byte limit", key, pool.size, MAX_TRANSFER_BYTES)
            return local
        }
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(context.getString(R.string.shader_cache_preparing)))
        val target = poolFile(container, key)
        val partial = File(target.parentFile, target.name + ".part")
        val result = runUnlessCancelled(POOL_DOWNLOAD_TIMEOUT_MS, isCancelled) {
            SupportApi.downloadSigned(pool.url, partial, if (pool.size > 0) pool.size else MAX_TRANSFER_BYTES)
        }
        val hash = if (result is ApiResult.Success) result.data else null
        if (hash == null || !hash.equals(pool.sha256, ignoreCase = true)) {
            partial.delete()
            Timber.tag(TAG).w("Pool download for %s failed (%s)", key, if (hash == null) result else "sha256 mismatch")
            return local
        }
        poolDir(container).listFiles()?.forEach { if (it != partial) it.delete() }
        if (!partial.renameTo(target)) {
            partial.delete()
            return null
        }
        val meta = JSONObject()
            .put("version", pool.version)
            .put("sha256", pool.sha256)
            .put("size", pool.size)
            .put("entries", pool.entries)
        FileUtils.writeString(metaFile, meta.toString())
        Timber.tag(TAG).i("Downloaded pool v%d for %s (%d entries)", pool.version, key, pool.entries)
        return meta
    }

    /** Runs [block] with a timeout, cancelling it once [isCancelled]; null if it timed out or was cancelled. */
    private fun <T> runUnlessCancelled(timeoutMs: Long, isCancelled: () -> Boolean, block: suspend () -> T): T? = runBlocking {
        val work = async(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { block() } }
        while (work.isActive && !isCancelled()) delay(CANCEL_POLL_MS)
        if (work.isActive) {
            work.cancel()
            null
        } else {
            work.await()
        }
    }

    private fun driverIdentity(container: Container): String =
        "${container.graphicsDriver}:${KeyValueSet(container.graphicsDriverConfig).get("version")}"

    private fun replay(
        context: Context,
        container: Container,
        launcher: BionicProgramLauncherComponent,
        key: ShaderCacheKey,
        poolFile: File,
        version: String,
        entries: Long,
        replayBin: File,
        stamp: String,
        isCancelled: () -> Boolean,
    ) {
        val env = launcher.buildShellEnvVars(null, arrayOf("FOSSILIZE", "FOSSILIZE_DUMP_PATH", "FOSSILIZE_DUMP_PATH_READ_ONLY"))
            ?: return
        env.put("DISABLE_FOSSILIZE", "1")
        val icd = env.get("VK_ICD_FILENAMES")
        if (icd.isEmpty() || icd.contains("gamescope")) {
            env.put("VK_ICD_FILENAMES", File(ImageFs.find(context).shareDir, "vulkan/icd.d/wrapper_icd.aarch64.json").absolutePath)
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        val args = listOf(
            poolFile.absolutePath,
            "--num-threads", threads.toString(),
            "--timeout-seconds", REPLAY_STALL_SECONDS.toString(),
            "--disable-rate-limiter",
        )
        val workDir = poolDir(container)
        val started = System.currentTimeMillis()
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(context.getString(R.string.shader_cache_preparing)))

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
        container.putExtra(EXTRA_REPLAYED, if (succeeded) stamp else "$stamp|failed")
        container.saveData()
        if (PrefManager.usageAnalyticsEnabled) {
            runCatching {
                PostHog.capture(
                    event = "shader_cache_replayed",
                    properties = keyProperties(key) + mapOf(
                        "pool_version" to version,
                        "entries" to entries,
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
            val key = readSessionKey(session)
            if (key == null) {
                Timber.tag(TAG).i("Dropping %s: no shader cache key was recorded with it", session.name)
                session.deleteRecursively()
                continue
            }
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

    private fun ShaderCacheKey.toJson(): JSONObject = JSONObject().put("game", game).put("dxvk", dxvk).put("vkd3d", vkd3d)

    private fun readSessionKey(session: File): ShaderCacheKey? = runCatching {
        val json = JSONObject(File(session, SESSION_KEY_FILENAME).readText())
        ShaderCacheKey(json.getString("game"), json.getString("dxvk"), json.getString("vkd3d"))
    }.getOrNull()

    private fun keyProperties(key: ShaderCacheKey): Map<String, Any> =
        mapOf("game" to key.game, "dxvk" to key.dxvk, "vkd3d" to key.vkd3d)

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
