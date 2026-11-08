package app.gamenative.utils

import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.api.ApiResult
import app.gamenative.api.CodeMapApi
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
    private const val ENTRY_BYTES = 12
    private const val MAX_MAP_BYTES = 8L * 1024 * 1024
    private const val MAX_FILES_PER_SESSION = 10

    // Reserved file id that introduces a record instead of a block, and the record kinds.
    private const val RESERVED_FILE_ID = -1L
    private const val LOAD_EXTERNAL_LIBRARY = -1
    private const val SET_EXECUTABLE_32 = -18
    private const val SET_EXECUTABLE_64 = -17

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

    /** Whether this launch should have FEX write code maps: the user has the feature on and the pack supports it. */
    @JvmStatic
    fun captureEnabled(context: Context, container: Container): Boolean =
        PrefManager.fexCodeCacheEnabled && supportsCodeMaps(context, container)

    @JvmStatic
    fun captureEnabled(context: Context, contentsManager: ContentsManager, container: Container): Boolean =
        PrefManager.fexCodeCacheEnabled && supportsCodeMaps(context, contentsManager, container)

    fun codeMapDir(container: Container): File = File(container.rootDir, CODEMAP_DIR)

    data class MapInfo(val mainExe: String, val mainFileId: String, val bitness: Int, val entries: Int, val libraries: Int)

    /**
     * Walks the map (FEXCore CodeMap): 12-byte entries {file id u64, block offset u32}, little
     * endian. The reserved file id (all ones) introduces a record instead of a block: offset
     * 0xffffffff = library load (u64 file id, NUL-terminated path padded to 4 bytes), offset
     * 0xffffffee / 0xffffffef = the main executable's file id for a 32-bit / 64-bit process.
     * The file name is "<exe>-<fileid>.<time>.bin" (CodeMap::GetBaseFilename).
     */
    fun readInfo(file: File): MapInfo? {
        if (file.length() < 2L * ENTRY_BYTES || file.length() > MAX_MAP_BYTES) return null
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var blocks = 0
        var libraries = 0
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
                else -> return null
            }
        }
        if (buf.hasRemaining() || mainFileId == null || blocks == 0) return null
        val base = file.name.removeSuffix(".bin").substringBeforeLast('.')
        val exe = base.substringBeforeLast('-').ifEmpty { base }
        return MapInfo(exe, "%016x".format(mainFileId), bitness, blocks, libraries)
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

        // Keep the newest few; a backlog from failed uploads must not grow without bound.
        files.drop(MAX_FILES_PER_SESSION).forEach {
            Timber.tag(TAG).w("Dropping old code map ${it.name}: more than $MAX_FILES_PER_SESSION pending")
            it.delete()
        }

        val sent = readSentBlocks(container)
        val meta = sessionMeta(context, container, containerId, sessionLengthSec)
        for (file in files.take(MAX_FILES_PER_SESSION)) {
            // The user may turn the feature off while a batch runs; stop before the next request.
            if (!PrefManager.fexCodeCacheEnabled) return
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
                // with a hosted cache in place a session's map is already just the misses.
                delta = File.createTempFile("codemap-", ".delta", context.cacheDir)
                val newBlocks = writeDelta(file, delta, sent)
                if (newBlocks == 0) {
                    Timber.tag(TAG).i("Code map ${file.name}: ${info.entries} entries, none new")
                    file.delete()
                    continue
                }
                val deltaInfo = readInfo(delta)
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
                    put("size", delta.length())
                    put("sha256", sha256)
                }
                when (val result = CodeMapApi.upload(fileMeta, gz)) {
                    is ApiResult.Success -> {
                        Timber.tag(TAG).i("Uploaded code map ${file.name}: $newBlocks new of ${info.entries} entries -> ${result.data.key}")
                        file.delete()
                        appendSentBlocks(container, delta, sent)
                    }
                    is ApiResult.HttpError -> {
                        Timber.tag(TAG).w("Code map ${file.name} rejected: HTTP ${result.code} ${result.message}")
                        // A map the server validated and refused will be refused again; anything else may be transient.
                        if (result.code == 400 || result.code == 413) file.delete()
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
    private fun sentBlocksFile(container: Container) = File(container.rootDir, ".gamenative/fex-codemap-sent.bin")

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

    /** Mixes a file id and block offset into one set key (file ids are hashes, so the low bits vary). */
    private fun blockKey(fileId: Long, blockOffset: Int): Long = fileId * 31 + blockOffset.toLong().and(0xffffffffL)

    /**
     * Writes [source] to [target] keeping every record (main executable, library loads) but only the
     * blocks not in [sent]. Returns the number of blocks written. Records are kept because the
     * compiler resolves file ids to paths through them.
     */
    fun writeDelta(source: File, target: File, sent: Set<Long>): Int {
        val bytes = source.readBytes()
        val out = ByteArrayOutputStream(bytes.size)
        var blocks = 0
        forEachRecord(bytes) { fileId, blockOffset, raw ->
            if (fileId == RESERVED_FILE_ID || blockKey(fileId, blockOffset) !in sent) {
                out.write(raw)
                if (fileId != RESERVED_FILE_ID) blocks++
            }
        }
        target.writeBytes(out.toByteArray())
        return blocks
    }

    /** Walks a map and hands each entry or record to [visit] with its raw bytes. */
    private fun forEachRecord(bytes: ByteArray, visit: (fileId: Long, blockOffset: Int, raw: ByteArray) -> Unit) {
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
