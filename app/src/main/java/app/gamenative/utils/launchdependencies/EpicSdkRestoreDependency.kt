package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.epic.EpicService
import com.winlator.container.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Puts back the game's own EOS SDK DLL where an earlier app version replaced it,
 * and deletes the replacement DLLs that version cached.
 */
object EpicSdkRestoreDependency : LaunchDependency {
    private const val TAG = "EpicSdkRestore"
    const val SDK_DLL_NAME = "EOSSDK-Win64-Shipping.dll"
    const val BACKUP_SUFFIX = ".gn-orig"
    private const val TEMP_SUFFIX = ".gn-tmp"
    private const val MAX_SEARCH_DEPTH = 6

    internal val cachedReplacementFiles = listOf("eossdk-win64-shipping-1.19.2.1.dll", "eossdk-win64-shipping-1.13.1.dll")

    /** Major, minor and patch of the builds the app put in place of the game's DLL. */
    private val replacementVersions = listOf(Triple(1, 19, 2), Triple(1, 13, 1))

    data class SdkVersion(val major: Int, val minor: Int, val patch: Int, val build: Int) {
        override fun toString() = "$major.$minor.$patch.$build"
    }

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        gameSource == GameSource.EPIC

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        cachedFiles(context).none { it.exists() } && sdkBackups(installRoot(gameId)).isEmpty()

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Restoring Epic Online Services SDK"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        try {
            withContext(Dispatchers.IO) {
                cachedFiles(context).forEach { it.delete() }
                sdkBackups(installRoot(gameId)).forEach { restore(File(it.parentFile, it.name.removeSuffix(BACKUP_SUFFIX))) }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "EOS SDK restore failed for gameId=%d, launching anyway", gameId)
        }
    }

    /** Puts the original back, unless a game update already replaced the swapped DLL, then drops the backup. */
    internal fun restore(dll: File) {
        val backup = File(dll.parentFile, dll.name + BACKUP_SUFFIX)
        val version = if (dll.exists()) readProductVersion(dll) else null
        if (version == null || isReplacementVersion(version)) {
            replaceFile(backup, dll)
            Timber.tag(TAG).i("Restored ${dll.absolutePath} (${readProductVersion(dll)}), was $version")
        }
        backup.delete()
    }

    private fun isReplacementVersion(v: SdkVersion): Boolean =
        replacementVersions.any { (major, minor, patch) -> v.major == major && v.minor == minor && v.patch == patch }

    private fun cachedFiles(context: Context): List<File> =
        cachedReplacementFiles.flatMap { listOf(File(context.filesDir, it), File(context.filesDir, it + TEMP_SUFFIX)) }

    private fun replaceFile(source: File, target: File) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        source.copyTo(temp, overwrite = true)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not replace ${target.absolutePath}")
        }
    }

    private fun installRoot(gameId: Int): File? {
        val installPath = runBlocking {
            EpicService.getInstance()?.epicManager?.getGameById(gameId)?.installPath
        }
        if (installPath.isNullOrEmpty()) return null
        return File(installPath).takeIf { it.isDirectory }
    }

    private fun sdkBackups(root: File?): List<File> {
        if (root == null) return emptyList()
        return root.walkTopDown()
            .maxDepth(MAX_SEARCH_DEPTH)
            .filter { it.isFile && it.name.equals(SDK_DLL_NAME + BACKUP_SUFFIX, ignoreCase = true) }
            .toList()
    }

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
