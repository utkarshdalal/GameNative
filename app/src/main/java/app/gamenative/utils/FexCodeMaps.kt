package app.gamenative.utils

import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.api.ApiResult
import app.gamenative.api.CodeMapApi
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import org.json.JSONObject
import timber.log.Timber
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
 * so the app uploads them after a session when the container runs a FEX series build that has
 * the cache fixes (2611-gn-6 or newer) and usage analytics are on.
 */
object FexCodeMaps {

    private const val TAG = "FexCodeMaps"
    private const val CODEMAP_DIR = ".wine/drive_c/users/xuser/AppData/Local/fex-emu/codemap/new"
    private const val ENTRY_BYTES = 12
    private const val MAX_MAP_BYTES = 8L * 1024 * 1024
    private const val MAX_FILES_PER_SESSION = 10

    // First GameNative series release whose FEX writes usable maps (GameNative/FEX gn-6, FEX-2611 base).
    private const val MIN_RELEASE = 2611
    private const val MIN_SERIES = 6

    private val versionPattern = Regex("""^(\d{4})-gn-(\d+)$""")

    /** True for FEXCore content ids like "2611-gn-6" at or above the first release with the cache fixes. */
    @JvmStatic
    fun supportsCodeMaps(fexcoreVersion: String?): Boolean {
        val match = versionPattern.matchEntire(fexcoreVersion?.trim().orEmpty()) ?: return false
        val release = match.groupValues[1].toInt()
        val series = match.groupValues[2].toInt()
        return release > MIN_RELEASE || (release == MIN_RELEASE && series >= MIN_SERIES)
    }

    /** Whether this launch should have FEX write code maps: supported FEX build and analytics opted in. */
    @JvmStatic
    fun captureEnabled(container: Container): Boolean {
        if (!PrefManager.usageAnalyticsEnabled) return false
        if (!"fexcore".equals(container.emulator, ignoreCase = true)) return false
        return supportsCodeMaps(container.fexCoreVersion)
    }

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
                    buf.position(minOf(buf.limit(), (end + 1 + 3) and 3.inv()))
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
        if (!PrefManager.usageAnalyticsEnabled || !supportsCodeMaps(container.fexCoreVersion)) return
        val dir = codeMapDir(container)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.isEmpty()) return

        // Keep the newest few; a backlog from failed uploads must not grow without bound.
        files.drop(MAX_FILES_PER_SESSION).forEach { it.delete() }

        val meta = sessionMeta(context, container, containerId, sessionLengthSec)
        for (file in files.take(MAX_FILES_PER_SESSION)) {
            val info = readInfo(file)
            if (info == null) {
                Timber.tag(TAG).w("Dropping unreadable code map ${file.name} (${file.length()} B)")
                file.delete()
                continue
            }
            val gz = File(context.cacheDir, "${file.name}.gz")
            try {
                val sha256 = gzipAndHash(file, gz)
                val fileMeta = JSONObject(meta.toString()).apply {
                    put("mainExe", info.mainExe)
                    put("mainFileId", info.mainFileId)
                    put("bitness", info.bitness)
                    put("entries", info.entries)
                    put("libraries", info.libraries)
                    put("size", file.length())
                    put("sha256", sha256)
                }
                when (val result = CodeMapApi.upload(fileMeta, gz)) {
                    is ApiResult.Success -> {
                        Timber.tag(TAG).i("Uploaded code map ${file.name}: ${info.entries} entries -> ${result.data.key}")
                        file.delete()
                    }
                    is ApiResult.HttpError -> {
                        Timber.tag(TAG).w("Code map ${file.name} rejected: HTTP ${result.code} ${result.message}")
                        // A map the server validated and refused will be refused again; anything else may be transient.
                        if (result.code == 400 || result.code == 413) file.delete()
                    }
                    is ApiResult.NetworkError -> Timber.tag(TAG).w("Code map ${file.name} not uploaded: ${result.exception.message}")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Code map ${file.name} failed")
            } finally {
                gz.delete()
            }
        }
    }

    private fun sessionMeta(context: Context, container: Container, containerId: String, sessionLengthSec: Int?) = JSONObject().apply {
        put("store", ContainerUtils.extractGameSourceFromContainerId(containerId).name)
        put("gameId", runCatching { ContainerUtils.extractGameIdFromContainerId(containerId) }.getOrDefault(0))
        put("gameName", ContainerUtils.resolveGameName(containerId))
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
