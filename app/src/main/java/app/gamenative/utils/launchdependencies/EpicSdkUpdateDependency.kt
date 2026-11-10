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
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replaces EOS SDK DLLs older than 1.13 with SDK 1.13.1, only for a game that crashed inside the SDK.
 *
 * SDK 1.12 and older crash inside EOS_Auth_Login when the exchange-code login
 * returns EOS_Auth_ScopeConsentRequired (Epic fixed this in 1.13). A newer SDK
 * is not a safe replacement for every game, so a game keeps its own SDK until an
 * online session shows a crash in it. If the crash is still there after the
 * swap, the original comes back and the game is left alone.
 */
object EpicSdkUpdateDependency : LaunchDependency {
    private const val TAG = "EpicSdkUpdate"
    const val SDK_DLL_NAME = "EOSSDK-Win64-Shipping.dll"
    const val BACKUP_SUFFIX = ".gn-orig"
    const val REPLACEMENT_FILE = "eossdk-win64-shipping-1.13.1.dll"
    private const val TEMP_SUFFIX = ".gn-tmp"
    private const val LEGACY_REPLACEMENT_FILE = "eossdk-win64-shipping-1.19.2.1.dll"
    private const val MIN_OK_MAJOR = 1
    private const val MIN_OK_MINOR = 13
    private const val MAX_REPLACEMENT_MINOR = 14
    private const val MAX_SEARCH_DEPTH = 6
    private const val MAX_IMPORT_SCAN_BYTES = 256L * 1024 * 1024

    const val EXTRA_STATE = "epicSdkSwap"
    const val STATE_PENDING = "pending"
    const val STATE_SWAPPED = "swapped"
    const val STATE_FAILED = "failed"

    /** Exports present in SDK 1.12 and older that the replacement does not provide. */
    private val removedExports = listOf("EOS_RTCAdmin_GetUserRoomTokenCount")

    /** One of the first frames of a winedbg backtrace, inside the SDK. */
    private val sdkCrashFrame = Regex(
        """(?:=>|\s)\s*[0-3]\s+0x[0-9a-f]+\s.*?\bin\s+eossdk-win64-shipping\b""",
        RegexOption.IGNORE_CASE,
    )

    @Volatile private var sessionOnline = false
    @Volatile private var sawSdkCrash = false

    data class SdkVersion(val major: Int, val minor: Int, val patch: Int, val build: Int) {
        override fun toString() = "$major.$minor.$patch.$build"
    }

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        gameSource == GameSource.EPIC

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean {
        val state = container.getExtra(EXTRA_STATE)
        return sdkDlls(installRoot(gameId)).none { needsRestore(it, state) || needsSwap(it, state) }
    }

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Updating Epic Online Services SDK"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        try {
            val state = container.getExtra(EXTRA_STATE)
            val root = withContext(Dispatchers.IO) { installRoot(gameId) } ?: return@coroutineScope
            val outdated = withContext(Dispatchers.IO) {
                File(context.filesDir, LEGACY_REPLACEMENT_FILE).delete()
                val dlls = sdkDlls(root)
                dlls.filter { needsRestore(it, state) }.forEach { restore(it) }
                dlls.filter { needsSwap(it, state) }
            }
            if (outdated.isEmpty()) return@coroutineScope
            val blocker = withContext(Dispatchers.IO) { findRemovedExportImporter(root, outdated) }
            if (blocker != null) {
                Timber.tag(TAG).w("${blocker.name} imports an export the newer SDK dropped, leaving the SDK alone")
                setState(container, STATE_FAILED)
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
            if (version == null || !isReplacementVersion(version)) {
                Timber.tag(TAG).w("$REPLACEMENT_FILE reports version $version, not using it")
                replacement.delete()
                return@coroutineScope
            }
            withContext(Dispatchers.IO) {
                for (dll in outdated) {
                    val original = readProductVersion(dll)
                    dll.copyTo(backupOf(dll), overwrite = true)
                    replaceFile(replacement, dll)
                    Timber.tag(TAG).i("Replaced ${dll.absolutePath} ($original) with SDK $version")
                }
            }
            setState(container, STATE_SWAPPED)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "EOS SDK update failed for gameId=%d, launching anyway", gameId)
        }
    }

    /** Starts watching the guest output of an Epic session; [online] is false when the game got no exchange code. */
    fun beginSession(online: Boolean) {
        sessionOnline = online
        sawSdkCrash = false
    }

    fun onGuestOutput(line: String) {
        if (sessionOnline && !sawSdkCrash && isSdkCrashFrame(line)) sawSdkCrash = true
    }

    fun endSession(container: Container) {
        val crashed = sessionOnline && sawSdkCrash
        sessionOnline = false
        sawSdkCrash = false
        if (!crashed) return
        val current = container.getExtra(EXTRA_STATE)
        val next = stateAfterSdkCrash(current)
        Timber.tag(TAG).i("Crash inside the EOS SDK, swap state '$current' -> '$next'")
        if (next != current) setState(container, next)
    }

    internal fun isSdkCrashFrame(line: String): Boolean =
        line.contains("eossdk", ignoreCase = true) && sdkCrashFrame.containsMatchIn(line)

    internal fun stateAfterSdkCrash(current: String): String = when (current) {
        "" -> STATE_PENDING
        STATE_SWAPPED -> STATE_FAILED
        else -> current
    }

    /** A backup next to a DLL that is not this game's wanted replacement means the original has to come back. */
    internal fun needsRestore(dll: File, state: String): Boolean {
        if (!backupOf(dll).exists()) return false
        val version = readProductVersion(dll)
        return !wantsSwap(state) || version == null || !isReplacementVersion(version)
    }

    internal fun needsSwap(dll: File, state: String): Boolean {
        if (!wantsSwap(state)) return false
        val version = readProductVersion(dll) ?: return false
        return !isAtLeastMinimum(version) || (backupOf(dll).exists() && !isReplacementVersion(version))
    }

    /** Puts the original back, unless a game update already did, then drops the backup. */
    internal fun restore(dll: File) {
        val backup = backupOf(dll)
        val version = readProductVersion(dll)
        if (version == null || isAtLeastMinimum(version)) {
            replaceFile(backup, dll)
            Timber.tag(TAG).i("Restored ${dll.absolutePath} (${readProductVersion(dll)}), was $version")
        }
        backup.delete()
    }

    private fun wantsSwap(state: String) = state == STATE_PENDING || state == STATE_SWAPPED

    private fun backupOf(dll: File) = File(dll.parentFile, dll.name + BACKUP_SUFFIX)

    private fun replaceFile(source: File, target: File) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        source.copyTo(temp, overwrite = true)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not replace ${target.absolutePath}")
        }
    }

    private fun setState(container: Container, state: String) {
        container.putExtra(EXTRA_STATE, state)
        container.saveData()
    }

    private fun installRoot(gameId: Int): File? {
        val installPath = runBlocking {
            EpicService.getInstance()?.epicManager?.getGameById(gameId)?.installPath
        }
        if (installPath.isNullOrEmpty()) return null
        return File(installPath).takeIf { it.isDirectory }
    }

    private fun sdkDlls(root: File?): List<File> {
        if (root == null) return emptyList()
        return root.walkTopDown()
            .maxDepth(MAX_SEARCH_DEPTH)
            .filter { it.isFile && it.name.equals(SDK_DLL_NAME, ignoreCase = true) }
            .toList()
    }

    /**
     * A native binary that imports a dropped export by name would fail to load
     * against the newer SDK, so look for the names in the game's PE files.
     */
    private fun findRemovedExportImporter(root: File, sdkDlls: List<File>): File? {
        val needles = removedExports.map { it.toByteArray(Charsets.US_ASCII) }
        root.walkTopDown().maxDepth(MAX_SEARCH_DEPTH)
            .filter { it.isFile && it.length() <= MAX_IMPORT_SCAN_BYTES }
            .filter { it.extension.equals("exe", true) || it.extension.equals("dll", true) }
            .filter { f -> sdkDlls.none { it.absolutePath == f.absolutePath } }
            .forEach { f -> if (containsAny(f, needles)) return f }
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

    /** The replacement has the 1.13 fix and comes before the 1.15 changes to login on Windows. */
    private fun isReplacementVersion(v: SdkVersion): Boolean =
        v.major == MIN_OK_MAJOR && v.minor in MIN_OK_MINOR..MAX_REPLACEMENT_MINOR

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
