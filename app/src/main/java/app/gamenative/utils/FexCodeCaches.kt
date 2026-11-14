package app.gamenative.utils

import android.content.Context
import android.os.SystemClock
import android.system.Os
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import com.winlator.container.Container
import com.winlator.container.ContainerOverlay
import com.winlator.contents.ContentsManager
import com.winlator.core.ProcessHelper
import com.winlator.core.envvars.EnvVars
import com.winlator.fexcore.FEXCoreManager
import com.winlator.fexcore.FEXCorePresetManager
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FEX code caches compiled on the device from its own code maps (FexCodeMaps). After a session the
 * maps FEX wrote are hard-linked into an inbox before the upload deletes them. The inbox is merged into
 * one cumulative map per image (a session that loaded a cache records only the misses, so the union is
 * what the cache must cover) and the pack's FEXOfflineCompiler runs under the session's own launcher
 * environment, through `wine cmd /c` on a batch file in the prefix. That build runs in two places: in
 * the background right after a session, and on the loading screen before the next guest starts, which
 * covers whatever the first one did not finish (the app closed, a launch stopped it). Each image is
 * recompiled only when its cumulative map grew by [REBUILD_GROWTH_PERCENT]; a new FEX build or FEX
 * setting deletes every cache, because FEX does not check that a cache matches the build that loads it.
 */
object FexCodeCaches {

    private const val TAG = "FexCodeCache"
    private const val FEX_DIR = ".wine/drive_c/users/xuser/AppData/Local/fex-emu"
    private const val WIN_WORK_DIR = "C:\\users\\xuser\\AppData\\Local\\fex-emu\\gamenative"
    private const val STATE_FILE = ".gamenative/fex-codecache.json"
    private val HOSTED_LEFTOVERS = listOf(".gamenative/fex-cache-installed.json", ".gamenative/fex-image-hashes.json")

    // FEXOfflineCompiler names caches <image>-<file id>-<config id> and always writes config id 0.
    private const val CACHE_SUFFIX = "-0000000000000000"

    // About 1,250 blocks a second on a Snapdragon 8 Gen 2, so this covers maps far larger than the
    // biggest seen (327k blocks for one exe).
    private const val COMPILE_TIMEOUT_MS = 10 * 60 * 1000L
    private const val STOP_WAIT_MS = 5_000L
    private const val WINE_EXIT_WAIT_MS = 15_000L
    private const val POLL_MS = 250L

    // One large game's exe is about 240 MB of cache; this keeps it plus its own DLLs and the Steam
    // client's. Every cache can be rebuilt from the maps, so eviction only costs compile time.
    private const val STORAGE_CAP_BYTES = 512L * 1024 * 1024

    // A cache is rebuilt once its image recorded this many percent more blocks than it was built from.
    private const val REBUILD_GROWTH_PERCENT = 10
    private const val MAX_INBOX_FILES = 20
    private const val MAX_MAP_BYTES = 8L * 1024 * 1024
    private const val STALE_IMAGE_MS = 60L * 24 * 60 * 60 * 1000
    private const val LOG_TAIL_LINES = 12

    // FEX options that change generated code, by the name of their environment variable.
    private val CODEGEN_OPTIONS = listOf(
        "Multiblock", "MaxInst", "HostFeatures", "SmallTSCScale", "HideHybrid", "SoftwareRNG", "CPUFeatureRegisters",
        "DisableL2Cache", "DynamicL1Cache", "SMCChecks", "TSOEnabled", "VectorTSOEnabled", "MemcpySetTSOEnabled",
        "HalfBarrierTSOEnabled", "StrictInProcessSplitLocks", "KernelUnalignedAtomicBackpatching", "VolatileMetadata",
        "ExtendedVolatileMetadata", "X87ReducedPrecision", "HideHypervisorBit", "MonoHacks", "ForceSVEWidth",
    ).associateBy { "FEX_" + it.uppercase(Locale.ROOT) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    private var jobBeforeLaunch = false

    @Volatile
    private var stopReason: String? = null

    @Volatile
    private var sessionLauncher: BionicProgramLauncherComponent? = null

    // The launcher of the starting session when its container records code maps.
    @Volatile
    private var captureLauncher: BionicProgramLauncherComponent? = null

    private class Mode(val beforeLaunch: Boolean, val stopRequested: () -> String?, val onProgress: (done: Int, total: Int) -> Unit)

    private class Outcome(val images: Int, val produced: Int, val ended: String?)

    private val afterSessionMode = Mode(false, { stopReason }, { _, _ -> })

    private fun fexDir(container: Container) = File(container.rootDir, FEX_DIR)
    private fun cacheDir(container: Container) = File(fexDir(container), "cache")
    private fun workDir(container: Container) = File(fexDir(container), "gamenative")
    private fun inboxDir(container: Container) = File(workDir(container), "inbox")
    private fun mapsDir(container: Container) = File(workDir(container), "maps")
    private fun stagingDir(container: Container) = File(workDir(container), "staging")
    private fun logsDir(container: Container) = File(workDir(container), "logs")

    /**
     * Called by the launcher when it starts, before the pre-launch steps: stops a compile still
     * running from the last session, remembers the launcher for the builds before and after this
     * session, and deletes caches this launch must not load: those built for another FEX build or
     * other FEX settings, or all of them while code caches are off. Never throws.
     */
    @JvmStatic
    fun onGuestStarting(context: Context, contentsManager: ContentsManager, launcher: BionicProgramLauncherComponent) {
        try {
            stopForLaunch("a game is starting")
            sessionLauncher = launcher
            captureLauncher = null
            val container = launcher.container ?: return
            if (!FexCodeMaps.supportsCodeMaps(context, contentsManager, container)) return
            val state = readState(container)
            val changed = if (PrefManager.fexCodeCacheUseEnabled) {
                invalidateIfBuildChanged(container, state, buildId(context, container, launcher))
            } else {
                deleteCachesWhileOff(container, state)
            }
            if (changed) writeState(container, state)
            if (PrefManager.fexCodeMapUploadEnabled) captureLauncher = launcher
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Code cache check before launch failed")
        }
    }

    /** Called by the launcher right before the guest process starts: logs the caches this launch loads. Never throws. */
    @JvmStatic
    fun onGuestReady(launcher: BionicProgramLauncherComponent) {
        try {
            if (launcher !== captureLauncher) return
            val container = launcher.container ?: return
            val caches = cacheDir(container).listFiles { f -> f.isFile }.orEmpty()
            if (caches.isEmpty()) {
                Timber.tag(TAG).i("Launch with no code caches for ${container.id}")
            } else {
                Timber.tag(TAG).i(
                    "Launch with ${caches.size} code caches (${mb(caches.sumOf { it.length() })}) for ${container.id}: " +
                        caches.joinToString { "${it.name} (${it.length()} B)" },
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Code cache listing before launch failed")
        }
    }

    /**
     * Stops a compile left running from the last session and waits for it for at most [STOP_WAIT_MS];
     * its unfinished images stay pending for the build before this launch. Never throws.
     */
    @JvmStatic
    fun stopForLaunch(reason: String) {
        try {
            val running = synchronized(lock) { job?.takeIf { it.isActive && !jobBeforeLaunch } } ?: return
            stopReason = reason
            Timber.tag(TAG).i("Stopping the code cache compile: $reason")
            val ended = runBlocking { withTimeoutOrNull(STOP_WAIT_MS) { running.join() } }
            if (ended == null) Timber.tag(TAG).w("Code cache compile still running $STOP_WAIT_MS ms after the stop")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not stop the code cache compile")
        }
    }

    /**
     * Called once the session's environment is down. Links this session's maps into the inbox (the
     * upload deletes the originals) and starts the background compile; returns at once.
     */
    fun afterSession(context: Context, container: Container) {
        val launcher = sessionLauncher?.takeIf { it.container?.id == container.id }
        sessionLauncher = null
        try {
            if (!FexCodeMaps.captureEnabled(context, container)) {
                Timber.tag(TAG).i("Skip compile for ${container.id}: FEX code map recording is off or the FEX pack has no offline compiler")
                return
            }
            linkSessionMaps(container)
            synchronized(lock) {
                if (job?.isActive == true) {
                    Timber.tag(TAG).i("Skip compile for ${container.id}: another compile is running; the maps wait in the inbox")
                    return
                }
                stopReason = null
                jobBeforeLaunch = false
                job = scope.launch {
                    try {
                        compileAfterSession(context, container, launcher)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Code cache compile for ${container.id} failed")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Code cache step after the session failed")
        }
    }

    private fun linkSessionMaps(container: Container) {
        val maps = FexCodeMaps.codeMapDir(container).listFiles { f -> f.isFile && f.name.endsWith(".bin") }.orEmpty()
        if (maps.isEmpty()) {
            Timber.tag(TAG).i("No code maps from this session")
            return
        }
        val inbox = inboxDir(container).apply { mkdirs() }
        var linked = 0
        for (map in maps) {
            val target = File(inbox, map.name)
            if (target.exists()) continue
            try {
                try {
                    Os.link(map.path, target.path)
                } catch (e: Exception) {
                    map.copyTo(target, overwrite = true)
                }
                linked++
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not keep code map ${map.name}")
            }
        }
        inbox.listFiles { f -> f.isFile }.orEmpty().sortedByDescending { it.lastModified() }.drop(MAX_INBOX_FILES).forEach {
            Timber.tag(TAG).w("Dropping code map ${it.name} from the inbox: more than $MAX_INBOX_FILES waiting")
            it.delete()
        }
        Timber.tag(TAG).i("Kept $linked of ${maps.size} code maps from this session for the compile")
    }

    private suspend fun compileAfterSession(context: Context, container: Container, launcher: BionicProgramLauncherComponent?) {
        val state = readState(container)
        mergeAndPrune(container, state)
        if (!PrefManager.fexCodeCacheUseEnabled) return skip(container, "FEX code caches are off; the maps are kept for a later build")
        if (launcher == null) return skip(container, "no launcher from this session")
        compile(context, container, launcher, state, afterSessionMode)
    }

    /**
     * Pre-launch step: merges the inbox and compiles every block no cache covers yet before the guest
     * starts, so this launch already loads the result. Blocking; shows its progress on the booting
     * splash and offers Skip. Skip or [isCancelled] stops the compile at once; finished caches are kept
     * and the rest stays pending. Decides that there is nothing to do from the state file and directory
     * listings alone. Never throws.
     */
    @JvmStatic
    fun buildBeforeLaunch(context: Context, launcher: BionicProgramLauncherComponent, isCancelled: () -> Boolean) {
        if (launcher !== captureLauncher || !PrefManager.fexCodeCacheUseEnabled) return
        val container = launcher.container ?: return
        val skipped = AtomicBoolean(false)
        val started = SystemClock.elapsedRealtime()
        try {
            val inbox = inboxDir(container).listFiles { f -> f.isFile && f.name.endsWith(".bin") }.orEmpty()
            if (inbox.isEmpty()) {
                val selection = selectImages(container, readState(container), log = false)
                if (withCompiler(container, selection.images, log = false).isEmpty()) return nothingToBuild(container, selection)
            }
            val mode = Mode(
                beforeLaunch = true,
                stopRequested = {
                    when {
                        skipped.get() -> "skipped by the player"
                        isCancelled() -> "the launch was cancelled"
                        else -> null
                    }
                },
                onProgress = { done, total -> showProgress(context, (done + 1).coerceAtMost(total), total) },
            )
            val work = synchronized(lock) {
                if (job?.isActive == true) return@synchronized null
                jobBeforeLaunch = true
                scope.launch {
                    try {
                        buildForLaunch(context, container, launcher, mode, skipped, started)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Pre-launch build for ${container.id} failed")
                    }
                }.also { job = it }
            }
            if (work == null) {
                Timber.tag(TAG).i("Skip pre-launch build for ${container.id}: the compile from the last session is still running")
                return
            }
            LaunchSkip.offer { skipped.set(true) }
            runBlocking { work.join() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Pre-launch build for ${container.id} failed")
        } finally {
            LaunchSkip.withdraw()
        }
    }

    private suspend fun buildForLaunch(
        context: Context,
        container: Container,
        launcher: BionicProgramLauncherComponent,
        mode: Mode,
        skipped: AtomicBoolean,
        started: Long,
    ) {
        val state = readState(container)
        mergeAndPrune(container, state)
        val selection = selectImages(container, state, log = false)
        val pending = withCompiler(container, selection.images, log = false)
        if (pending.isEmpty()) return nothingToBuild(container, selection)
        val newBlocks = pending.sumOf { (_, entry) ->
            val built = entry.optInt("builtBlocks", 0).takeIf { File(cacheDir(container), entry.optString("cacheName")).isFile } ?: 0
            (entry.optInt("blocks") - built).coerceAtLeast(0)
        }
        Timber.tag(TAG).i("Pre-launch build for ${container.id}: ${pending.size} images, $newBlocks new blocks")
        showProgress(context, 1, pending.size)
        val outcome = compile(context, container, launcher, state, mode)
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        val done = outcome?.produced ?: 0
        val total = outcome?.images ?: pending.size
        when {
            outcome != null && outcome.ended == null -> Timber.tag(TAG).i("Pre-launch build finished in %.1f s".format(Locale.ROOT, seconds))
            skipped.get() -> Timber.tag(TAG).i("Pre-launch build skipped by the player after %.1f s: %d of %d images done".format(Locale.ROOT, seconds, done, total))
            else -> Timber.tag(TAG).i(
                "Pre-launch build ended early after %.1f s (%s): %d of %d images done"
                    .format(Locale.ROOT, seconds, outcome?.ended ?: mode.stopRequested() ?: "not started", done, total),
            )
        }
    }

    private fun nothingToBuild(container: Container, selection: Selection) {
        val waiting = if (selection.waitingBlocks > 0) " (${selection.waitingBlocks} new blocks below the rebuild threshold)" else ""
        Timber.tag(TAG).i("Nothing to build for ${container.id}$waiting")
    }

    private fun showProgress(context: Context, current: Int, total: Int) {
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(context.getString(R.string.fex_code_cache_building, current, total)))
    }

    private fun mergeAndPrune(container: Container, state: JSONObject) {
        mergeInbox(container, state)
        pruneStaleImages(container, state)
        writeState(container, state)
    }

    private fun skip(container: Container, reason: String) {
        Timber.tag(TAG).i("Skip compile for ${container.id}: $reason")
    }

    private class Offsets {
        var values = IntArray(1024)
        var size = 0

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun sortedDistinct(): IntArray {
            val sorted = values.copyOf(size).also { it.sort() }
            if (sorted.isEmpty()) return sorted
            var n = 1
            for (i in 1 until sorted.size) if (sorted[i] != sorted[n - 1]) sorted[n++] = sorted[i]
            return sorted.copyOf(n)
        }
    }

    private class SessionImage(val record: ByteArray, val path: String, val bits: Int) {
        val offsets = Offsets()
    }

    /**
     * Folds every inbox map into the cumulative map of each image it touched, adding the blocks the
     * upload ledger holds for those images (maps uploaded before this existed were deleted after
     * upload). Builtin Wine images and processes FEX runs with their own app config are left out.
     */
    private fun mergeInbox(container: Container, state: JSONObject) {
        val files = inboxDir(container).listFiles { f -> f.isFile && f.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) return
        val images = HashMap<Long, SessionImage>()
        var builtins = 0
        for (file in files) {
            try {
                val info = if (file.length() <= MAX_MAP_BYTES) FexCodeMaps.readInfo(file) else null
                if (info == null) {
                    Timber.tag(TAG).w("Ignoring unreadable code map ${file.name} (${file.length()} B)")
                    continue
                }
                if (FEXCoreManager.findAppConfigExeName(info.mainExe) != null) {
                    Timber.tag(TAG).i("Ignoring code map ${file.name}: ${info.mainExe} runs with its own FEX config")
                    continue
                }
                val libraries = HashMap<Long, ByteArray>()
                val blocks = HashMap<Long, Offsets>()
                FexCodeMaps.forEachRecord(file.readBytes()) { fileId, blockOffset, raw ->
                    when {
                        fileId != FexCodeMaps.RESERVED_FILE_ID -> blocks.getOrPut(fileId) { Offsets() }.add(blockOffset)
                        blockOffset == FexCodeMaps.LOAD_EXTERNAL_LIBRARY -> {
                            libraries[ByteBuffer.wrap(raw, FexCodeMaps.ENTRY_BYTES, 8).order(ByteOrder.LITTLE_ENDIAN).long] = raw
                        }
                    }
                }
                for ((fileId, record) in libraries) {
                    val offsets = blocks[fileId] ?: continue
                    val path = recordPath(record)
                    if (isWineBuiltin(path)) {
                        builtins++
                        continue
                    }
                    val image = images.getOrPut(fileId) { SessionImage(record, path, info.bitness) }
                    for (i in 0 until offsets.size) image.offsets.add(offsets.values[i])
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not read code map ${file.name}")
            }
        }
        addSentBlocks(container, images)

        val entries = imagesOf(state)
        val now = System.currentTimeMillis()
        mapsDir(container).mkdirs()
        for ((fileId, image) in images) {
            val key = fileIdHex(fileId)
            val mapFile = File(mapsDir(container), "$key.bin")
            val before = readMapBlocks(mapFile, fileId)
            val merged = Offsets()
            before.forEach { merged.add(it) }
            for (i in 0 until image.offsets.size) merged.add(image.offsets.values[i])
            val all = merged.sortedDistinct()
            val entry = entries.optJSONObject(key) ?: JSONObject().also { entries.put(key, it) }
            entry.put("path", image.path).put("name", imageName(image.path)).put("bits", image.bits).put("lastSeen", now)
            if (all.size > before.size || !mapFile.isFile) {
                writeMap(mapFile, fileId, image.record, all)
                Timber.tag(TAG).i("Merged ${imageName(image.path)} ($key, ${image.bits}-bit): +${all.size - before.size} new blocks, ${all.size} total")
            }
            entry.put("blocks", all.size)
        }
        files.forEach { it.delete() }
        Timber.tag(TAG).i("Merged ${files.size} code maps: ${images.size} images, $builtins Wine builtin images left out")
    }

    private fun addSentBlocks(container: Container, images: Map<Long, SessionImage>) {
        val sent = FexCodeMaps.sentBlocksFile(container)
        if (images.isEmpty() || !sent.isFile) return
        runCatching {
            val buf = ByteBuffer.wrap(sent.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            while (buf.remaining() >= FexCodeMaps.ENTRY_BYTES) {
                val fileId = buf.long
                val blockOffset = buf.int
                images[fileId]?.offsets?.add(blockOffset)
            }
        }.onFailure { Timber.tag(TAG).w(it, "Could not read the uploaded-block ledger") }
    }

    /** The path in a library-load record: after the 12-byte entry and the 8-byte file id, up to the NUL. */
    private fun recordPath(record: ByteArray): String {
        val start = FexCodeMaps.ENTRY_BYTES + 8
        var end = start
        while (end < record.size && record[end] != 0.toByte()) end++
        return String(record, start, end - start, Charsets.UTF_8)
    }

    // Wine substitutes its own builtins for these at load time, so a cache compiled from the file is invalid.
    private fun isWineBuiltin(path: String): Boolean = path.lowercase(Locale.ROOT).contains(":\\windows\\")

    private fun imageName(path: String): String = path.substringAfterLast('\\').substringAfterLast('/')

    private fun fileIdHex(fileId: Long) = "%016x".format(fileId)

    private fun readMapBlocks(file: File, fileId: Long): IntArray {
        if (!file.isFile) return IntArray(0)
        val offsets = Offsets()
        FexCodeMaps.forEachRecord(file.readBytes()) { id, blockOffset, _ -> if (id == fileId) offsets.add(blockOffset) }
        return offsets.sortedDistinct()
    }

    /** A map in the layout FEXOfflineCompiler writes for one library: its load record, then its blocks. */
    private fun writeMap(file: File, fileId: Long, record: ByteArray, offsets: IntArray) {
        val buf = ByteBuffer.allocate(record.size + offsets.size * FexCodeMaps.ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(record)
        offsets.forEach { buf.putLong(fileId).putInt(it) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(buf.array())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("cannot move ${tmp.path} into place")
        }
    }

    private fun pruneStaleImages(container: Container, state: JSONObject) {
        val entries = imagesOf(state)
        val cutoff = System.currentTimeMillis() - STALE_IMAGE_MS
        for (key in entries.keys().asSequence().toList()) {
            val entry = entries.getJSONObject(key)
            if (entry.optLong("lastSeen") >= cutoff) continue
            File(mapsDir(container), "$key.bin").delete()
            entry.optString("cacheName").takeIf { it.isNotEmpty() }?.let { File(cacheDir(container), it).delete() }
            entries.remove(key)
            Timber.tag(TAG).i("Dropped ${entry.optString("name")} ($key): not loaded for 60 days")
        }
    }

    private suspend fun compile(
        context: Context,
        container: Container,
        launcher: BionicProgramLauncherComponent,
        state: JSONObject,
        mode: Mode,
    ): Outcome? {
        fun skipped(reason: String): Outcome? {
            skip(container, reason)
            return null
        }
        mode.stopRequested()?.let { return skipped(it) }
        if (!PrefManager.fexCodeCacheUseEnabled) return skipped("FEX code caches are off")
        if (!mode.beforeLaunch && PluviaApp.xEnvironment != null) return skipped("a game session is active")
        if (!waitForWineToExit(container, mode.stopRequested)) return null
        mode.stopRequested()?.let { return skipped(it) }

        val (build, config) = buildOf(context, container, launcher)
        invalidateIfBuildChanged(container, state, build)
        HOSTED_LEFTOVERS.forEach { File(container.rootDir, it).delete() }

        val images = withCompiler(container, selectImages(container, state).images, log = true)
        writeState(container, state)
        if (images.isEmpty()) return skipped("nothing new to compile")

        val work = workDir(container)
        val staging = stagingDir(container)
        val logs = logsDir(container)
        listOf(staging, logs).forEach { dir ->
            dir.mkdirs()
            dir.listFiles().orEmpty().forEach { it.delete() }
        }
        File(work, "Config.json").writeText(
            JSONObject().put(
                "Config",
                JSONObject().apply {
                    config.forEach { (name, value) -> put(name, value) }
                    put("EnableCodeCachingWIP", "1")
                    put("SilentLog", "0")
                },
            ).toString(),
        )
        val lines = mutableListOf("@echo off")
        for ((key, entry) in images) {
            val compiler = "C:\\windows\\system32\\FEXOfflineCompiler${entry.optInt("bits")}.exe"
            lines += "$compiler generate --fileid $key --outdir $WIN_WORK_DIR\\staging/ $WIN_WORK_DIR\\maps\\$key.bin" +
                " > $WIN_WORK_DIR\\logs\\$key.out.txt 2> $WIN_WORK_DIR\\logs\\$key.err.txt"
            lines += "echo %ERRORLEVEL% > $WIN_WORK_DIR\\logs\\$key.exit.txt"
        }
        File(work, "compile.bat").writeText(lines.joinToString("\r\n", postfix = "\r\n"))

        Timber.tag(TAG).i(
            "Compile started for ${container.id}, FEX ${container.fexCoreVersion}, config $config: ${images.size} images: " +
                images.joinToString { (key, entry) -> "${entry.optString("name")} ($key, ${entry.optInt("blocks")} blocks, ${entry.optInt("bits")}-bit)" },
        )
        val started = SystemClock.elapsedRealtime()
        val env = EnvVars().apply {
            // The real prefix path, not the home symlink, which a launch of another container repoints. It must
            // be the canonical spelling the overlay uses as its upper dir: any other spelling of a thin prefix
            // is not overlaid, so Wine sees no base prefix and cannot load kernel32.
            put("WINEPREFIX", ContainerOverlay.canonicalHostPath(File(container.rootDir, ".wine")))
            put("FEX_APP_CONFIG_LOCATION", "$WIN_WORK_DIR\\")
            put("WINEDEBUG", "+err")
        }
        val unset = arrayOf("FEX_ENABLECODECACHINGWIP")
        val run = scope.async { launcher.execShellCommand("wine cmd /c $WIN_WORK_DIR\\compile.bat", true, env, unset) }
        var ended: String? = null
        var shown = 0
        while (run.isActive) {
            mode.stopRequested()?.let { ended = "stopped: $it" }
            if (ended == null && SystemClock.elapsedRealtime() - started > COMPILE_TIMEOUT_MS) ended = "hit the ${COMPILE_TIMEOUT_MS / 60_000}-minute limit"
            if (ended != null) break
            if (mode.beforeLaunch) {
                val done = logs.list().orEmpty().count { it.endsWith(".exit.txt") }
                if (done != shown && done < images.size) {
                    shown = done
                    mode.onProgress(done, images.size)
                }
            }
            withTimeoutOrNull(POLL_MS) { run.join() }
        }
        ended?.let { reason -> Timber.tag(TAG).w("Compile for ${container.id} $reason; killing its Wine processes") }
        // Before a launch the compile's wineserver must not linger: the guest would attach to it.
        if (ended != null || mode.beforeLaunch) {
            runCatching { launcher.execShellCommand("wineserver -k", true, env, unset) }
                .onFailure { Timber.tag(TAG).w(it, "wineserver -k after the compile failed") }
            withTimeoutOrNull(STOP_WAIT_MS) { run.join() }
        }
        if (run.isCompleted && !run.isCancelled) {
            val output = runCatching { run.await() }.getOrDefault("")
            if (output.isNotBlank()) Timber.tag(TAG).i("Wine output:\n${tail(output)}")
        }

        val cacheDir = cacheDir(container).apply { mkdirs() }
        var produced = 0
        var producedBytes = 0L
        var failed = 0
        for ((key, entry) in images) {
            val name = entry.optString("name")
            val blocks = entry.optInt("blocks")
            val output = staging.listFiles { f -> f.isFile && f.name.endsWith("-$key$CACHE_SUFFIX") }?.firstOrNull()
            val exit = File(logs, "$key.exit.txt").takeIf { it.isFile }?.readText()?.trim()
            if (output != null && output.length() > 0 && (exit == null || exit == "0")) {
                // The staging directory is the temp name: the compiler wrote and renamed the file there, and
                // this rename puts the finished file where FEX looks in one step.
                val target = File(cacheDir, output.name)
                if (output.renameTo(target)) {
                    entry.put("builtBlocks", blocks).put("cacheName", target.name).put("cacheSize", target.length())
                    entry.remove("failedBlocks")
                    entry.remove("evictedAtBlocks")
                    produced++
                    producedBytes += target.length()
                    Timber.tag(TAG).i("Cache ${target.name}: ${target.length()} B from $blocks blocks")
                } else {
                    Timber.tag(TAG).w("Could not move ${output.name} into ${cacheDir.path}")
                }
            } else if (exit != null) {
                failed++
                entry.put("failedBlocks", blocks)
                Timber.tag(TAG).w(
                    "Compile of $name ($key) failed (exit $exit):\n" +
                        tail(listOf("out", "err").joinToString("\n") { File(logs, "$key.$it.txt").takeIf { f -> f.isFile }?.readText().orEmpty() }),
                )
            } else {
                Timber.tag(TAG).i("Not compiled: $name ($key); the run ended before it")
            }
        }
        staging.listFiles().orEmpty().forEach { it.delete() }
        val total = enforceCap(container, state)
        writeState(container, state)
        Timber.tag(TAG).i(
            "Compile finished for ${container.id} in %.1f s%s: %d caches written (%s), %d failed; container caches now %s"
                .format(Locale.ROOT, (SystemClock.elapsedRealtime() - started) / 1000.0, ended?.let { " ($it)" }.orEmpty(), produced, mb(producedBytes), failed, mb(total)),
        )
        return Outcome(images.size, produced, ended)
    }

    private suspend fun waitForWineToExit(container: Container, stopRequested: () -> String?): Boolean {
        val deadline = SystemClock.elapsedRealtime() + WINE_EXIT_WAIT_MS
        while (true) {
            val running = ProcessHelper.listRunningWineProcesses()
            if (running.isEmpty()) return true
            if (stopRequested() != null || SystemClock.elapsedRealtime() > deadline) {
                skip(container, "Wine processes still running: ${running.joinToString()}")
                return false
            }
            delay(500)
        }
    }

    /** [images] whose bitness has an offline compiler in the prefix. */
    private fun withCompiler(container: Container, images: List<Pair<String, JSONObject>>, log: Boolean): List<Pair<String, JSONObject>> {
        val system32 = File(container.rootDir, ".wine/drive_c/windows/system32")
        return images.filter { (key, entry) ->
            val bits = entry.optInt("bits")
            val ok = File(system32, "FEXOfflineCompiler$bits.exe").isFile
            if (!ok && log) Timber.tag(TAG).i("Skip ${entry.optString("name")} ($key): no FEXOfflineCompiler$bits.exe in system32")
            ok
        }
    }

    /** Images to build, most blocks first, and the recorded blocks that wait below the rebuild threshold. */
    private class Selection(val images: List<Pair<String, JSONObject>>, val waitingBlocks: Int)

    /**
     * Images with no cache, or whose cumulative map grew by [REBUILD_GROWTH_PERCENT] of the blocks their
     * cache was built from. A compile rebuilds the whole cache, so a few misses wait for the next rebuild.
     */
    private fun selectImages(container: Container, state: JSONObject, log: Boolean = true): Selection {
        val entries = imagesOf(state)
        val selected = mutableListOf<Pair<String, JSONObject>>()
        var upToDate = 0
        var waiting = 0
        for (key in entries.keys()) {
            val entry = entries.getJSONObject(key)
            val name = entry.optString("name")
            val blocks = entry.optInt("blocks")
            val cacheName = entry.optString("cacheName")
            if (blocks == 0 || !File(mapsDir(container), "$key.bin").isFile) continue
            val built = entry.optInt("builtBlocks", -1)
            if (built > 0 && cacheName.isNotEmpty() && File(cacheDir(container), cacheName).isFile) {
                val added = (blocks - built).coerceAtLeast(0)
                if (added * 100L < built * REBUILD_GROWTH_PERCENT.toLong()) {
                    upToDate++
                    waiting += added
                    continue
                }
            }
            if (entry.optInt("failedBlocks", -1) == blocks) {
                if (log) Timber.tag(TAG).i("Skip $name ($key): its compile failed at $blocks blocks and the map has not grown")
                continue
            }
            val evictedAt = entry.optInt("evictedAtBlocks")
            if (evictedAt > 0 && blocks < evictedAt * 2) {
                if (log) Timber.tag(TAG).i("Skip $name ($key): evicted for the storage cap at $evictedAt blocks; waiting for ${evictedAt * 2}")
                continue
            }
            selected += key to entry
        }
        if (upToDate > 0 && log) Timber.tag(TAG).i("$upToDate images have a current cache; $waiting new blocks below the rebuild threshold")
        return Selection(selected.sortedByDescending { it.second.optInt("blocks") }, waiting)
    }

    /** Deletes caches until the container's total is under [STORAGE_CAP_BYTES]; returns the total left. */
    private fun enforceCap(container: Container, state: JSONObject): Long {
        val files = cacheDir(container).listFiles { f -> f.isFile }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= STORAGE_CAP_BYTES) return total
        val entries = imagesOf(state)
        val byCache = entries.keys().asSequence().map { entries.getJSONObject(it) }.associateBy { it.optString("cacheName") }
        // Least valuable first: files this does not track, then images not loaded for longest, then fewest blocks.
        val order = files.sortedWith(
            compareBy<File>(
                { if (byCache.containsKey(it.name)) 1 else 0 },
                { byCache[it.name]?.optLong("lastSeen") ?: 0L },
                { byCache[it.name]?.optInt("builtBlocks") ?: 0 },
            ),
        )
        for (file in order) {
            if (total <= STORAGE_CAP_BYTES) break
            val size = file.length()
            if (!file.delete()) continue
            total -= size
            byCache[file.name]?.let { entry ->
                entry.put("evictedAtBlocks", entry.optInt("builtBlocks"))
                entry.remove("builtBlocks")
                entry.remove("cacheName")
                entry.remove("cacheSize")
            }
            Timber.tag(TAG).i("Evicted ${file.name} ($size B): caches over the ${mb(STORAGE_CAP_BYTES)} cap")
        }
        return total
    }

    private fun buildOf(context: Context, container: Container, launcher: BionicProgramLauncherComponent): Pair<String, Map<String, String>> {
        val config = sortedMapOf<String, String>()
        val env = FEXCorePresetManager.getEnvVars(context, launcher.fexCorePreset)
        launcher.envVars?.let { env.putAll(it) }
        for (name in env) CODEGEN_OPTIONS[name.uppercase(Locale.ROOT)]?.let { config[it] = env.get(name) }
        val digest = MessageDigest.getInstance("SHA-256").digest(config.entries.joinToString(";").toByteArray())
        return "${container.fexCoreVersion}/${digest.take(8).joinToString("") { "%02x".format(it) }}" to config
    }

    private fun buildId(context: Context, container: Container, launcher: BionicProgramLauncherComponent) =
        buildOf(context, container, launcher).first

    /**
     * Deletes every cache in the prefix when [build] differs from the one the caches were compiled
     * for, including caches from before this state existed. The maps stay: they do not depend on the
     * FEX build. Returns whether [state] changed.
     */
    private fun invalidateIfBuildChanged(container: Container, state: JSONObject, build: String): Boolean {
        val old = state.optString("build")
        if (old == build) return false
        val caches = cacheDir(container).listFiles { f -> f.isFile }.orEmpty()
        val bytes = caches.sumOf { it.length() }
        val deleted = caches.count { it.delete() }
        val entries = imagesOf(state)
        entries.keys().forEach { key ->
            entries.getJSONObject(key).apply {
                remove("builtBlocks")
                remove("cacheName")
                remove("cacheSize")
                remove("failedBlocks")
                remove("evictedAtBlocks")
            }
        }
        state.put("build", build)
        Timber.tag(TAG).i("FEX build or settings changed (${old.ifEmpty { "none" }} -> $build): deleted $deleted caches (${mb(bytes)})")
        return true
    }

    /**
     * While code caches are off, deletes every cache in the prefix and the marks of what was built:
     * the FEX switch that records the maps also loads caches. The maps and the upload ledger stay.
     * Returns whether [state] changed.
     */
    private fun deleteCachesWhileOff(container: Container, state: JSONObject): Boolean {
        val caches = cacheDir(container).listFiles { f -> f.isFile }.orEmpty()
        val entries = state.optJSONObject("images")
        val marked = entries?.keys()?.asSequence()?.map { entries.getJSONObject(it) }
            ?.filter { it.has("builtBlocks") || it.has("cacheName") || it.has("cacheSize") }?.toList().orEmpty()
        if (caches.isEmpty() && marked.isEmpty()) return false
        val bytes = caches.sumOf { it.length() }
        val deleted = caches.count { it.delete() }
        marked.forEach { entry ->
            entry.remove("builtBlocks")
            entry.remove("cacheName")
            entry.remove("cacheSize")
        }
        Timber.tag(TAG).i("Code caches are off: deleted $deleted caches (${mb(bytes)}) for ${container.id}")
        return marked.isNotEmpty()
    }

    private fun imagesOf(state: JSONObject): JSONObject =
        state.optJSONObject("images") ?: JSONObject().also { state.put("images", it) }

    private fun readState(container: Container): JSONObject =
        runCatching { JSONObject(File(container.rootDir, STATE_FILE).readText()) }.getOrElse { JSONObject() }

    private fun writeState(container: Container, state: JSONObject) {
        runCatching {
            val file = File(container.rootDir, STATE_FILE)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(state.toString())
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { Timber.tag(TAG).w(it, "Could not save the code cache state") }
    }

    private fun tail(text: String): String = text.trim().lines().takeLast(LOG_TAIL_LINES).joinToString("\n")

    private fun mb(bytes: Long): String = "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
}
