package app.gamenative.service.rockstar

import android.content.Context
import app.gamenative.NetworkMonitor
import app.gamenative.service.SteamService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/** Versioned helper cache. Does not overwrite game executables, mods, INIs or credentials. */
object RockstarHelperArchive {
    const val VERSION = "20261126.2"
    const val ARCHIVE = "rgschost-$VERSION.tzst"
    const val SIGNIN_SHIM = "rockstar-signin-shim.js"
    private val required = setOf(
        "rgscstub.exe", "scpatch.dll", "bink2w64.dll",
        "rgscstub32.exe", "scpatch32.dll", "binkw32.dll",
        "SocialClubD3D12Renderer.dll", "SocialClubVulkanLayer.dll", SIGNIN_SHIM,
    )

    fun directory(filesDir: File) = File(filesDir, "rockstar/rgschost-$VERSION")

    /** The store-neutral account link in the sign-in script; archives without it serve Steam copies only. */
    const val EPIC_CAPABILITY_MARKER = "window.gnLinkExternal"

    private val epicCapable = ConcurrentHashMap<String, Boolean>()

    /** Whether the extracted helper can sign in Epic copies; read from its sign-in script once per extracted folder. */
    fun supportsEpicCopies(filesDir: File): Boolean {
        val dir = installedDirectory(filesDir) ?: return false
        return epicCapable.getOrPut(dir.path) {
            runCatching { File(dir, SIGNIN_SHIM).readText().contains(EPIC_CAPABILITY_MARKER) }.getOrDefault(false)
        }
    }

    /** Detect the installed Rockstar SDK/metadata, not the publisher or a guessed app-ID list. */
    fun usesRockstar(gameDir: File): Boolean = candidates(gameDir).any(::holdsTitle)

    fun titleDir(installDir: File): File = candidates(installDir).firstOrNull(::holdsTitle) ?: installDir

    private fun candidates(installDir: File): List<File> {
        if (!installDir.isDirectory) return emptyList()
        return listOf(installDir) + installDir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name.lowercase() }
    }

    private fun holdsTitle(dir: File): Boolean {
        val names = dir.listFiles().orEmpty().associateBy { it.name.lowercase() }
        val metadata = names["title.rgl"]
        if (metadata != null && runCatching {
                metadata.inputStream().use { input ->
                    val magic = ByteArray(4)
                    input.read(magic) == 4 && magic.contentEquals(byteArrayOf(82, 71, 76, 77))
                }
            }.getOrDefault(false)
        ) return true
        return listOf("socialclub.dll", "socialclub64.dll").any { names[it]?.isFile == true }
    }

    fun isReady(filesDir: File): Boolean = isComplete(directory(filesDir))

    private fun isComplete(dir: File): Boolean = required.all { File(dir, it).isFile }

    /**
     * The helper to launch with: this version's cache, or, when an app update moved to a version
     * that could not be downloaded yet, the newest complete one already extracted.
     */
    fun installedDirectory(filesDir: File): File? =
        directory(filesDir).takeIf(::isComplete)
            ?: File(filesDir, "rockstar").listFiles().orEmpty()
                .filter { it.isDirectory && isComplete(it) }
                .mapNotNull { dir -> versionOrder(dir.name)?.let { dir to it } }
                .maxWithOrNull { a, b -> compareVersions(a.second, b.second) }
                ?.first

    private val versionFolder = Regex("""rgschost-(\d{8})(?:\.(\d+))?""")

    /** Folder versions are a date with an optional same-day build number: 20261122 < 20261122.1 < 20261122.10. */
    internal fun versionOrder(folderName: String): Pair<Long, Long>? =
        versionFolder.matchEntire(folderName)?.let { match ->
            match.groupValues[1].toLong() to (match.groupValues[2].toLongOrNull() ?: 0L)
        }

    private fun compareVersions(a: Pair<Long, Long>, b: Pair<Long, Long>): Int =
        compareValuesBy(a, b, { it.first }, { it.second })

    suspend fun downloadAndExtract(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (isReady(context.filesDir)) return@withContext directory(context.filesDir)
        val archive = File(context.filesDir, ARCHIVE)
        val previous = installedDirectory(context.filesDir)
        if (previous != null && !archive.isFile && !NetworkMonitor.hasInternet.value) {
            Timber.i("Rockstar helper %s not downloaded and no internet, using %s", VERSION, previous.name)
            return@withContext previous
        }
        try {
            if (!archive.isFile) SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
            install(archive, context.filesDir)
        } catch (e: Exception) {
            if (e is CancellationException || previous == null) throw e
            Timber.w(e, "Rockstar helper %s unavailable, using %s", VERSION, previous.name)
            previous
        }
    }

    internal fun install(archive: File, filesDir: File): File {
        check(archive.isFile) { "Rockstar helper archive is missing" }
        return ensureExtracted(filesDir) { archive.inputStream() }
    }

    /** Extract once per version; a cache with every required file present is used as is. */
    @Synchronized
    fun ensureExtracted(filesDir: File, openArchive: () -> InputStream): File {
        val target = directory(filesDir)
        if (isReady(filesDir)) return target
        check(target.isDirectory || target.mkdirs()) { "Cannot create Rockstar helper cache" }
        openArchive().use { raw ->
            ZstdCompressorInputStream(raw).use { zstd ->
                TarArchiveInputStream(zstd).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (!entry.isFile) continue
                        val name = File(entry.name).name
                        if (name.isEmpty() || name == "." || name == "..") continue
                        File(target, name).outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        }
        check(isReady(filesDir)) { "Rockstar helper archive is incomplete" }
        return target
    }
}
