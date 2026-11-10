package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.LOADING_PROGRESS_UNKNOWN
import com.winlator.container.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opt-in replacement of EOS SDK DLLs older than 1.13 with a newer build before launch.
 *
 * SDK 1.12 and older crash inside EOS_Auth_Login when the exchange-code login
 * returns EOS_Auth_ScopeConsentRequired (Epic fixed this in 1.13). The SDK is a
 * drop-in binary upgrade that keeps behaving like the version the game was built
 * against, so only the file is swapped. The original is kept next to it.
 *
 * The swap is NOT applied by default: a newer SDK is not a no-op for every title
 * (a game built against 1.10 can fail its own Epic login under 1.19 even though
 * Epic documents the SDK as a drop-in upgrade), so the user enables it per
 * container. Turning the setting off restores the shipped DLL from its `.gn-orig`
 * backup.
 */
object EpicSdkUpdateDependency : LaunchDependency {
    private const val TAG = "EpicSdkUpdate"
    const val SDK_DLL_NAME = "EOSSDK-Win64-Shipping.dll"
    const val BACKUP_SUFFIX = ".gn-orig"
    const val REPLACEMENT_FILE = "eossdk-win64-shipping-1.19.2.1.dll"
    private const val MIN_OK_MAJOR = 1
    private const val MIN_OK_MINOR = 13
    private const val MAX_SEARCH_DEPTH = 6
    private const val MAX_IMPORT_SCAN_BYTES = 256L * 1024 * 1024

    /** Exports present in SDK 1.12 that the replacement no longer provides. */
    private val removedExports = listOf("EOS_RTCAdmin_GetUserRoomTokenCount")

    data class SdkVersion(val major: Int, val minor: Int, val patch: Int, val build: Int) {
        override fun toString() = "$major.$minor.$patch.$build"
    }

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        gameSource == GameSource.EPIC

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        if (container.isUpdateEpicSdk) outdatedSdkDlls(gameId).isEmpty()
        else restorableDlls(gameId).isEmpty()

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        if (container.isUpdateEpicSdk) "Updating Epic Online Services SDK"
        else "Restoring Epic Online Services SDK"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        if (!container.isUpdateEpicSdk) {
            withContext(Dispatchers.IO) { restoreShippedDlls(gameId) }
            return@coroutineScope
        }
        val outdated = withContext(Dispatchers.IO) { outdatedSdkDlls(gameId) }
        if (outdated.isEmpty()) return@coroutineScope
        try {
            val blocker = withContext(Dispatchers.IO) { findRemovedExportImporter(outdated) }
            if (blocker != null) {
                Timber.tag(TAG).w("${blocker.name} imports an export the newer SDK dropped, leaving the SDK alone")
                return@coroutineScope
            }
            val replacement = File(context.filesDir, REPLACEMENT_FILE)
            if (!replacement.exists()) {
                callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
                withContext(Dispatchers.IO) {
                    SteamService.downloadFile(
                        onDownloadProgress = { callbacks.setLoadingProgress(it) },
                        parentScope = this@coroutineScope,
                        context = context,
                        fileName = REPLACEMENT_FILE,
                    ).await()
                }
            }
            val version = withContext(Dispatchers.IO) { readProductVersion(replacement) }
            if (version == null || !isAtLeastMinimum(version)) {
                Timber.tag(TAG).w("$REPLACEMENT_FILE reports version $version, not using it")
                replacement.delete()
                return@coroutineScope
            }
            withContext(Dispatchers.IO) {
                for (dll in outdated) {
                    val backup = File(dll.parentFile, dll.name + BACKUP_SUFFIX)
                    if (!backup.exists()) dll.copyTo(backup)
                    replacement.copyTo(dll, overwrite = true)
                    Timber.tag(TAG).i("Replaced ${dll.absolutePath} (${readProductVersion(backup)}) with SDK $version")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "EOS SDK update failed for gameId=%d, launching anyway", gameId)
        }
    }

    private fun outdatedSdkDlls(gameId: Int): List<File> =
        installedSdkDlls(gameId).filter { dll -> readProductVersion(dll)?.let { !isAtLeastMinimum(it) } ?: false }

    /** SDK DLLs that still have a `.gn-orig` backup next to them. */
    private fun restorableDlls(gameId: Int): List<File> =
        installedSdkDlls(gameId).filter { dll -> backupOf(dll).exists() }

    private fun backupOf(dll: File): File = File(dll.parentFile, dll.name + BACKUP_SUFFIX)

    /** Puts every `.gn-orig` copy back and drops the backup, so the shipped DLL wins again. */
    private fun restoreShippedDlls(gameId: Int) {
        for (dll in restorableDlls(gameId)) {
            val backup = backupOf(dll)
            try {
                if (dll.length() == backup.length()) {
                    // Content was already restored earlier; the stale backup is all that is left.
                    backup.delete()
                    Timber.tag(TAG).i("Dropped stale SDK backup ${backup.absolutePath}")
                    continue
                }
                backup.copyTo(dll, overwrite = true)
                if (dll.length() == backup.length()) {
                    backup.delete()
                    Timber.tag(TAG).i("Restored ${dll.absolutePath} from ${backup.name}")
                } else {
                    Timber.tag(TAG).w("Restore of ${dll.absolutePath} left a short file, keeping the backup")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not restore ${dll.absolutePath}")
            }
        }
    }

    private fun installedSdkDlls(gameId: Int): List<File> {
        val installPath = runBlocking {
            EpicService.getInstance()?.epicManager?.getGameById(gameId)?.installPath
        }
        if (installPath.isNullOrEmpty()) return emptyList()
        val root = File(installPath)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .maxDepth(MAX_SEARCH_DEPTH)
            .filter { it.isFile && it.name.equals(SDK_DLL_NAME, ignoreCase = true) }
            .toList()
    }

    /**
     * A native binary that imports a dropped export by name would fail to load
     * against the newer SDK, so look for the names in the game's PE files.
     */
    private fun findRemovedExportImporter(sdkDlls: List<File>): File? {
        val needles = removedExports.map { it.toByteArray(Charsets.US_ASCII) }
        val roots = sdkDlls.mapNotNull { it.parentFile?.parentFile?.parentFile ?: it.parentFile }.distinct()
        for (root in roots) {
            root.walkTopDown().maxDepth(MAX_SEARCH_DEPTH)
                .filter { it.isFile && it.length() <= MAX_IMPORT_SCAN_BYTES }
                .filter { it.extension.equals("exe", true) || it.extension.equals("dll", true) }
                .filter { f -> sdkDlls.none { it.absolutePath == f.absolutePath } && !f.name.endsWith(BACKUP_SUFFIX) }
                .forEach { f -> if (containsAny(f, needles)) return f }
        }
        return null
    }

    private fun containsAny(file: File, needles: List<ByteArray>): Boolean {
        val longest = needles.maxOf { it.size }
        try {
            RandomAccessFile(file, "r").use { raf ->
                val chunk = ByteArray(1 shl 20)
                var carry = ByteArray(0)
                while (true) {
                    val n = raf.read(chunk)
                    if (n <= 0) break
                    val buf = carry + chunk.copyOf(n)
                    if (needles.any { indexOf(buf, it) >= 0 }) return true
                    carry = buf.copyOfRange(maxOf(0, buf.size - (longest - 1)), buf.size)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not scan ${file.absolutePath}")
        }
        return false
    }

    private fun isAtLeastMinimum(v: SdkVersion): Boolean =
        v.major > MIN_OK_MAJOR || (v.major == MIN_OK_MAJOR && v.minor >= MIN_OK_MINOR)

    /** Reads dwProductVersion from the PE VS_FIXEDFILEINFO block. */
    internal fun readProductVersion(file: File): SdkVersion? {
        val signature = byteArrayOf(0xBD.toByte(), 0x04, 0xEF.toByte(), 0xFE.toByte())
        val fixedInfoSize = 24
        try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val chunk = ByteArray(1 shl 20)
                var carry = ByteArray(0)
                var pos = 0L
                while (pos < length) {
                    raf.seek(pos)
                    val n = raf.read(chunk)
                    if (n <= 0) break
                    val buf = carry + chunk.copyOf(n)
                    val idx = indexOf(buf, signature)
                    if (idx >= 0) {
                        val absolute = pos - carry.size + idx
                        if (absolute + fixedInfoSize > length) return null
                        raf.seek(absolute)
                        val info = ByteArray(fixedInfoSize)
                        raf.readFully(info)
                        val bb = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
                        val ms = bb.getInt(16)
                        val ls = bb.getInt(20)
                        return SdkVersion(ms ushr 16, ms and 0xffff, ls ushr 16, ls and 0xffff)
                    }
                    carry = buf.copyOfRange(maxOf(0, buf.size - (signature.size - 1)), buf.size)
                    pos += n
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read version of ${file.absolutePath}")
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
