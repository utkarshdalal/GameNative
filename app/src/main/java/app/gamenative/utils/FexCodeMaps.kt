package app.gamenative.utils

import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.api.ApiResult
import app.gamenative.api.CodeMapApi
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.GPUInformation
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * FEX code maps: the list of guest blocks FEX compiled for one process, written with
 * FEX_ENABLECODECACHINGWIP=1 to AppData/Local/fex-emu/codemap/new/ in the prefix. They are the
 * input to the offline code cache compiler and do not depend on the FEX build that wrote them,
 * so the app uploads them after a session when the container's FEXCore pack supports caching (it ships
 * the offline compiler) and the user has not turned the feature off.
 */
object FexCodeMaps {

    private const val TAG = "FexCodeMaps"
    private const val CODEMAP_DIR = ".wine/drive_c/users/xuser/AppData/Local/fex-emu/codemap/new"
    internal const val ENTRY_BYTES = 12
    private const val MAX_MAP_BYTES = 8L * 1024 * 1024
    private const val MAX_FILES_PER_SESSION = 10

    // Reserved file id that introduces a record instead of a block, and the record kinds.
    internal const val RESERVED_FILE_ID = -1L
    internal const val LOAD_EXTERNAL_LIBRARY = -1
    private const val SET_EXECUTABLE_32 = -18
    private const val SET_EXECUTABLE_64 = -17
    internal const val INDIRECT_BRANCH = -35
    internal const val PAIR_BYTES = 2 * ENTRY_BYTES

    private const val SESSIONS_DIR = ".gamenative/fex-codemap-sessions"

    // FILETIME (100 ns since 1601) of the Unix epoch; map names end in the process start as a FILETIME.
    private const val FILETIME_UNIX_EPOCH = 116444736000000000L

    /**
     * A FEXCore pack that can write usable maps and load caches ships its offline compiler next to the
     * DLLs (GameNative series gn-6 and later). Checking for that file is the capability test; no
     * version string is parsed. Bundled upstream packs have no compiler and get nothing.
     */
    @JvmStatic
    fun packSupportsCodeCaches(installDir: File?): Boolean =
        installDir != null && File(installDir, "system32/FEXOfflineCompiler64.exe").isFile

    @JvmStatic
    fun supportsCodeMaps(context: Context, container: Container): Boolean =
        supportsCodeMaps(context, ContentsManager(context), container)

    /** Same check with a caller's [ContentsManager], which has already scanned the installed packs. */
    @JvmStatic
    fun supportsCodeMaps(context: Context, contentsManager: ContentsManager, container: Container): Boolean {
        if (!"fexcore".equals(container.emulator, ignoreCase = true)) return false
        val profile = contentsManager.getProfileByEntryName("fexcore-${container.fexCoreVersion}") ?: return false
        return packSupportsCodeCaches(ContentsManager.getInstallDir(context, profile))
    }

    /** Whether this launch should have FEX write code maps: the user has the upload on and the pack supports it. */
    @JvmStatic
    fun captureEnabled(context: Context, container: Container): Boolean =
        PrefManager.fexCodeMapUploadEnabled && supportsCodeMaps(context, container)

    @JvmStatic
    fun captureEnabled(context: Context, contentsManager: ContentsManager, container: Container): Boolean =
        PrefManager.fexCodeMapUploadEnabled && supportsCodeMaps(context, contentsManager, container)

    fun codeMapDir(container: Container): File = File(container.rootDir, CODEMAP_DIR)

    /** Whether FEX should record indirect branch targets: only once the server has shown it takes them. */
    @JvmStatic
    fun indirectBranchesEnabled(): Boolean = PrefManager.fexCodeMapIndirectAccepted

    data class MapInfo(
        val mainExe: String,
        val mainFileId: String,
        val bitness: Int,
        val entries: Int,
        val libraries: Int,
        val indirectBranches: Int = 0,
    )

    /**
     * Walks the map (FEXCore CodeMap): 12-byte entries {file id u64, block offset u32}, little
     * endian. The reserved file id (all ones) introduces a record instead of a block: offset
     * 0xffffffff = library load (u64 file id, NUL-terminated path padded to 4 bytes), offset
     * 0xffffffee / 0xffffffef = the main executable's file id for a 32-bit / 64-bit process,
     * offset 0xffffffdd = indirect branch: {file id, offset} of the jump or call, then of its target.
     * The file name is "<exe>-<fileid>.<time>.bin" (CodeMap::GetBaseFilename).
     */
    fun readInfo(file: File): MapInfo? {
        if (file.length() < 2L * ENTRY_BYTES || file.length() > MAX_MAP_BYTES) return null
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var blocks = 0
        var libraries = 0
        var indirectBranches = 0
        var mainFileId: Long? = null
        var bitness = 0
        while (buf.remaining() >= ENTRY_BYTES) {
            val fileId = buf.long
            val blockOffset = buf.int
            if (fileId != -1L) {
                blocks++
                continue
            }
            when (blockOffset) {
                -1 -> { // 0xffffffff: library load
                    if (buf.remaining() < 8) return null
                    buf.long
                    var end = buf.position()
                    while (end < buf.limit() && buf.get(end) != 0.toByte()) end++
                    if (end >= buf.limit()) return null
                    val next = (end + 1 + 3) and 3.inv()
                    if (next > buf.limit()) return null // padding cut off: not a complete record
                    buf.position(next)
                    libraries++
                }
                -18, -17 -> { // 0xffffffee / 0xffffffef: main executable
                    if (buf.remaining() < 8) return null
                    mainFileId = buf.long
                    bitness = if (blockOffset == -18) 32 else 64
                }
                INDIRECT_BRANCH -> {
                    if (buf.remaining() < PAIR_BYTES) return null
                    buf.position(buf.position() + PAIR_BYTES)
                    indirectBranches++
                }
                else -> return null
            }
        }
        if (buf.hasRemaining() || mainFileId == null || (blocks == 0 && indirectBranches == 0)) return null
        val base = file.name.removeSuffix(".bin").substringBeforeLast('.')
        val exe = base.substringBeforeLast('-').ifEmpty { base }
        return MapInfo(exe, "%016x".format(mainFileId), bitness, blocks, libraries, indirectBranches)
    }

    /**
     * Uploads every map FEX wrote for this container and deletes the ones the server took.
     * Runs after the game has exited and the environment is down, on an IO thread.
     */
    fun uploadAfterSession(context: Context, container: Container, containerId: String, sessionLengthSec: Int?) {
        if (!captureEnabled(context, container)) return
        val dir = codeMapDir(container)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.isEmpty()) return

        // A custom game's maps only feed its own code caches, which have their copies by now.
        if (ContainerUtils.extractGameSourceFromContainerId(containerId) == GameSource.CUSTOM_GAME) {
            files.forEach { it.delete() }
            return
        }

        // Keep the newest few; a backlog from failed uploads must not grow without bound.
        files.drop(MAX_FILES_PER_SESSION).forEach {
            Timber.tag(TAG).w("Dropping old code map ${it.name}: more than $MAX_FILES_PER_SESSION pending")
            it.delete()
        }

        val sent = readSentBlocks(container)
        val sentPairs = readSentPairs(container)
        val meta = sessionMeta(context, container, containerId, sessionLengthSec)
        val sessions = sessionsDir(container)
        // Cleared for the rest of the batch on a refusal; the saved preference is written asynchronously.
        var pairsAllowed = indirectBranchesEnabled()
        for (file in files.take(MAX_FILES_PER_SESSION)) {
            // The user may turn the feature off while a batch runs; stop before the next request.
            if (!PrefManager.fexCodeMapUploadEnabled) return
            var delta: File? = null
            var gz: File? = null
            try {
                val info = readInfo(file)
                if (info == null) {
                    Timber.tag(TAG).w("Dropping unreadable code map ${file.name} (${file.length()} B)")
                    file.delete()
                    continue
                }
                // Only blocks this device has never sent go up; FEX re-records cached code as nothing, so
                // with a cache in place a session's map is already just the misses.
                delta = File.createTempFile("codemap-", ".delta", context.cacheDir)
                val newBlocks = writeDelta(file, delta, sent, sentPairs, pairsAllowed)
                val deltaInfo = readInfo(delta)
                if (newBlocks == 0 && (deltaInfo?.indirectBranches ?: 0) == 0) {
                    Timber.tag(TAG).i("Code map ${file.name}: ${info.entries} entries, ${info.indirectBranches} indirect branches, none new")
                    file.delete()
                    continue
                }
                if (deltaInfo == null) {
                    Timber.tag(TAG).w("Code map ${file.name}: delta unreadable")
                    file.delete()
                    continue
                }
                gz = File.createTempFile("codemap-", ".gz", context.cacheDir)
                val sha256 = gzipAndHash(delta, gz)
                val fileMeta = JSONObject(meta.toString()).apply {
                    put("mainExe", deltaInfo.mainExe)
                    put("mainFileId", deltaInfo.mainFileId)
                    put("bitness", deltaInfo.bitness)
                    put("entries", deltaInfo.entries)
                    put("libraries", deltaInfo.libraries)
                    put("indirectBranches", deltaInfo.indirectBranches)
                    put("size", delta.length())
                    put("sha256", sha256)
                    putSessionOptions(this, sessionOptionsFor(sessions, file))
                }
                when (val result = CodeMapApi.upload(fileMeta, gz)) {
                    is ApiResult.Success -> {
                        Timber.tag(TAG).i(
                            "Uploaded code map ${file.name}: $newBlocks new of ${info.entries} entries, " +
                                "${deltaInfo.indirectBranches} new of ${info.indirectBranches} indirect branches -> ${result.data.key}",
                        )
                        file.delete()
                        appendSentBlocks(container, delta, sent)
                        appendSentPairs(container, delta, sentPairs)
                        if (result.data.indirectBranches != null && !PrefManager.fexCodeMapIndirectAccepted) {
                            PrefManager.fexCodeMapIndirectAccepted = true
                        }
                    }
                    is ApiResult.HttpError -> {
                        Timber.tag(TAG).w("Code map ${file.name} rejected: HTTP ${result.code} ${result.message}")
                        if (result.code == 400 && deltaInfo.indirectBranches > 0) {
                            // Kept: the next pass sends its blocks without the pairs.
                            pairsAllowed = false
                            PrefManager.fexCodeMapIndirectAccepted = false
                        } else if (result.code == 400 || result.code == 413) {
                            // A map the server validated and refused will be refused again; anything else may be transient.
                            file.delete()
                        }
                    }
                    is ApiResult.NetworkError -> {
                        Timber.tag(TAG).w("Code map ${file.name} not uploaded: ${result.exception.message}")
                        // Unreachable server: the rest would only wait out the same timeout. A bad
                        // response for this one map is not a reason to skip the others.
                        if (result.exception is IOException) return
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Code map ${file.name} failed")
            } finally {
                gz?.delete()
                delta?.delete()
            }
        }
    }

    /** Every (file id, block offset) this container has uploaded, as 12-byte records like the maps themselves. */
    internal fun sentBlocksFile(container: Container) = File(container.rootDir, ".gamenative/fex-codemap-sent.bin")

    private fun readSentBlocks(container: Container): HashSet<Long> {
        val set = HashSet<Long>()
        val file = sentBlocksFile(container)
        if (!file.isFile) return set
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= ENTRY_BYTES) {
            set.add(blockKey(buf.long, buf.int))
        }
        // Duplicates from maps that overlapped only inflate the file; rewrite it with each block once.
        val records = bytes.size / ENTRY_BYTES
        if (records > set.size + set.size / 10) {
            runCatching {
                val out = ByteBuffer.allocate(set.size * ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                val seen = HashSet<Long>(set.size)
                buf.rewind()
                while (buf.remaining() >= ENTRY_BYTES) {
                    val fileId = buf.long
                    val blockOffset = buf.int
                    if (seen.add(blockKey(fileId, blockOffset))) out.putLong(fileId).putInt(blockOffset)
                }
                file.writeBytes(out.array())
                Timber.tag(TAG).i("Compacted the sent-block file: $records -> ${seen.size} records")
            }.onFailure { Timber.tag(TAG).w(it, "Could not compact the sent-block file") }
        }
        return set
    }

    /** Keys of every block entry in a map (its records are skipped). */
    internal fun blockKeysOf(map: File): Set<Long> {
        val keys = HashSet<Long>()
        forEachRecord(map.readBytes()) { fileId, blockOffset, _ ->
            if (fileId != RESERVED_FILE_ID) keys.add(blockKey(fileId, blockOffset))
        }
        return keys
    }

    /**
     * Appends the block entries of a delta map to the sent-block file (its records are skipped) and
     * adds them to [sent], so the same pass does not send them again from another map.
     */
    private fun appendSentBlocks(container: Container, delta: File, sent: MutableSet<Long>) {
        runCatching {
            val out = ByteArrayOutputStream()
            forEachRecord(delta.readBytes()) { fileId, blockOffset, _ ->
                if (fileId != RESERVED_FILE_ID) {
                    sent.add(blockKey(fileId, blockOffset))
                    out.write(ByteBuffer.allocate(ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(fileId).putInt(blockOffset).array())
                }
            }
            sentBlocksFile(container).apply { parentFile?.mkdirs() }.appendBytes(out.toByteArray())
        }.onFailure { Timber.tag(TAG).w(it, "Could not update the sent-block file") }
    }

    /** Every indirect branch pair this container has uploaded, as 24-byte {site, target} records. */
    internal fun sentPairsFile(container: Container) = File(container.rootDir, ".gamenative/fex-codemap-sent-indirect.bin")

    private fun readSentPairs(container: Container): HashSet<Long> {
        val set = HashSet<Long>()
        val file = sentPairsFile(container)
        if (!file.isFile) return set
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= PAIR_BYTES) {
            set.add(pairKey(buf.long, buf.int, buf.long, buf.int))
        }
        return set
    }

    /** Keys of every indirect branch pair in a map. */
    internal fun pairKeysOf(map: File): Set<Long> {
        val keys = HashSet<Long>()
        forEachRecord(map.readBytes()) { fileId, blockOffset, raw ->
            if (fileId == RESERVED_FILE_ID && blockOffset == INDIRECT_BRANCH) keys.add(pairKey(raw))
        }
        return keys
    }

    private fun appendSentPairs(container: Container, delta: File, sent: MutableSet<Long>) {
        runCatching {
            val out = ByteArrayOutputStream()
            forEachRecord(delta.readBytes()) { fileId, blockOffset, raw ->
                if (fileId == RESERVED_FILE_ID && blockOffset == INDIRECT_BRANCH) {
                    sent.add(pairKey(raw))
                    out.write(raw, ENTRY_BYTES, PAIR_BYTES)
                }
            }
            if (out.size() > 0) sentPairsFile(container).apply { parentFile?.mkdirs() }.appendBytes(out.toByteArray())
        }.onFailure { Timber.tag(TAG).w(it, "Could not update the sent indirect branch file") }
    }

    private fun pairKey(record: ByteArray): Long {
        val buf = ByteBuffer.wrap(record, ENTRY_BYTES, PAIR_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        return pairKey(buf.long, buf.int, buf.long, buf.int)
    }

    private fun pairKey(siteFileId: Long, siteOffset: Int, targetFileId: Long, targetOffset: Int): Long {
        var key = siteFileId
        key = key * 1_000_003 + siteOffset.toLong().and(0xffffffffL)
        key = key * 1_000_003 + targetFileId
        return key * 1_000_003 + targetOffset.toLong().and(0xffffffffL)
    }

    /** Mixes a file id and block offset into one set key (file ids are hashes, so the low bits vary). */
    private fun blockKey(fileId: Long, blockOffset: Int): Long = fileId * 31 + blockOffset.toLong().and(0xffffffffL)

    /**
     * Writes [source] to [target] keeping the main executable and library load records but only the
     * blocks not in [sent], and the indirect branch pairs not in [sentPairs] when [keepPairs] is set.
     * Returns the number of blocks written. Library records are kept because the compiler resolves
     * file ids to paths through them.
     */
    fun writeDelta(source: File, target: File, sent: Set<Long>, sentPairs: Set<Long> = emptySet(), keepPairs: Boolean = false): Int {
        val bytes = source.readBytes()
        val out = ByteArrayOutputStream(bytes.size)
        var blocks = 0
        forEachRecord(bytes) { fileId, blockOffset, raw ->
            val keep = when {
                fileId != RESERVED_FILE_ID -> blockKey(fileId, blockOffset) !in sent
                blockOffset == INDIRECT_BRANCH -> keepPairs && pairKey(raw) !in sentPairs
                else -> true
            }
            if (keep) {
                out.write(raw)
                if (fileId != RESERVED_FILE_ID) blocks++
            }
        }
        target.writeBytes(out.toByteArray())
        return blocks
    }

    /** Walks a map and hands each entry or record to [visit] with its raw bytes. */
    internal fun forEachRecord(bytes: ByteArray, visit: (fileId: Long, blockOffset: Int, raw: ByteArray) -> Unit) {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= ENTRY_BYTES) {
            val start = buf.position()
            val fileId = buf.long
            val blockOffset = buf.int
            if (fileId == RESERVED_FILE_ID) {
                when (blockOffset) {
                    LOAD_EXTERNAL_LIBRARY -> {
                        if (buf.remaining() < 8) return
                        buf.long
                        var end = buf.position()
                        while (end < buf.limit() && buf.get(end) != 0.toByte()) end++
                        if (end >= buf.limit()) return
                        val next = (end + 1 + 3) and 3.inv()
                        if (next > buf.limit()) return
                        buf.position(next)
                    }
                    SET_EXECUTABLE_32, SET_EXECUTABLE_64 -> {
                        if (buf.remaining() < 8) return
                        buf.long
                    }
                    INDIRECT_BRANCH -> {
                        if (buf.remaining() < PAIR_BYTES) return
                        buf.position(buf.position() + PAIR_BYTES)
                    }
                    else -> return
                }
            }
            visit(fileId, blockOffset, bytes.copyOfRange(start, buf.position()))
        }
    }

    private fun sessionMeta(context: Context, container: Container, containerId: String, sessionLengthSec: Int?) = JSONObject().apply {
        put("store", ContainerUtils.extractGameSourceFromContainerId(containerId).name)
        put("gameId", runCatching { ContainerUtils.extractGameIdFromContainerId(containerId) }.getOrDefault(0))
        put("gameName", runCatching { ContainerUtils.resolveGameName(containerId) }.getOrDefault("Unknown"))
        put("fexVersion", container.fexCoreVersion)
        put("deviceModel", Build.MODEL)
        put("deviceManufacturer", Build.MANUFACTURER)
        put("socName", HardwareUtils.getSOCName() ?: JSONObject.NULL)
        put("gpuName", GPUInformation.getRenderer(context)?.takeIf { it.isNotBlank() } ?: "unknown")
        put("androidVersion", Build.VERSION.RELEASE)
        put("appVersion", BuildConfig.VERSION_NAME)
        put("sessionLengthSec", sessionLengthSec ?: JSONObject.NULL)
    }

    /** Puts the session's FEX preset and options into upload metadata; null for each one not known. */
    internal fun putSessionOptions(meta: JSONObject, fex: JSONObject?) {
        meta.put("fexPreset", fex?.optString("preset")?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        for ((key, option) in SESSION_OPTIONS) {
            meta.put(key, fex?.optString(option)?.let { if (it == "1") true else if (it == "0") false else null } ?: JSONObject.NULL)
        }
    }

    private fun sessionsDir(container: Container) = File(container.rootDir, SESSIONS_DIR)

    /** Start of the process that wrote [map] in Unix milliseconds, from its name (CodeMap::GetBaseFilename + time). */
    internal fun mapStartMs(map: File): Long? =
        map.name.removeSuffix(".bin").substringAfterLast('.').toLongOrNull()
            ?.let { (it - FILETIME_UNIX_EPOCH) / 10_000 }

    /**
     * The options of the launch that started last before [map]'s process, or null when none is
     * stored (the launch predates this or its file was pruned): never another launch's options.
     */
    internal fun sessionOptionsFor(sessionsDir: File, map: File): JSONObject? {
        val start = mapStartMs(map) ?: return null
        val session = sessionsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".json").toLongOrNull()?.let { it to f } }
            .filter { it.first <= start }
            .maxByOrNull { it.first }
            ?.second ?: return null
        return runCatching { JSONObject(session.readText()) }.getOrNull()
    }

    private val SESSION_OPTIONS = listOf(
        "fexTsoEnabled" to "TSOEnabled",
        "fexVectorTsoEnabled" to "VectorTSOEnabled",
        "fexMemcpySetTsoEnabled" to "MemcpySetTSOEnabled",
        "fexHalfBarrierTsoEnabled" to "HalfBarrierTSOEnabled",
        "fexMultiblock" to "Multiblock",
    )

    /**
     * Stores the FEX preset and memory-ordering options a game launch runs with, so the maps of that
     * session are uploaded with them. [options] are keyed by FEX option name, as in Config.json.
     */
    fun writeSessionConfig(container: Container, preset: String?, options: Map<String, String>) {
        runCatching { writeSessionOptions(sessionsDir(container), System.currentTimeMillis(), preset, options) }
            .onFailure { Timber.tag(TAG).w(it, "Could not store the session's FEX options") }
    }

    /**
     * Writes one launch's options as <startMs>.json. Only the newest few are kept: as many as the
     * maps that can wait for upload, so an older map finds no file rather than a newer launch's.
     */
    internal fun writeSessionOptions(dir: File, startMs: Long, preset: String?, options: Map<String, String>) {
        val json = JSONObject().put("preset", preset ?: "")
        for ((_, option) in SESSION_OPTIONS) options[option]?.let { json.put(option, it) }
        dir.mkdirs()
        File(dir, "$startMs.json").writeText(json.toString())
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .sortedByDescending { it.name.removeSuffix(".json").toLongOrNull() ?: 0L }
            .drop(MAX_FILES_PER_SESSION)
            .forEach { it.delete() }
    }

    private fun gzipAndHash(source: File, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(source).use { input ->
            GZIPOutputStream(FileOutputStream(target)).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
