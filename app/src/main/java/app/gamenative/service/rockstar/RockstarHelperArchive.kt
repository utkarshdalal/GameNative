package app.gamenative.service.rockstar

import android.content.Context
import app.gamenative.service.SteamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.File
import java.io.InputStream

/** Versioned helper cache. Does not overwrite game executables, mods, INIs or credentials. */
object RockstarHelperArchive {
    const val VERSION = "20260916"
    const val ARCHIVE = "rgschost-$VERSION.tzst"
    const val SIGNIN_SHIM = "rockstar-signin-shim.js"
    private val required = setOf(
        "rgscstub.exe", "scpatch.dll", "bink2w64.dll",
        "SocialClubD3D12Renderer.dll", "SocialClubVulkanLayer.dll", SIGNIN_SHIM,
    )

    fun directory(filesDir: File) = File(filesDir, "rockstar/rgschost-$VERSION")

    /** Detect the installed Rockstar SDK/metadata, not the publisher or a guessed app-ID list. */
    fun usesRockstar(gameDir: File): Boolean {
        if (!gameDir.isDirectory) return false
        val names = gameDir.listFiles().orEmpty().associateBy { it.name.lowercase() }
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

    fun isReady(filesDir: File): Boolean = required.all { File(directory(filesDir), it).isFile }

    suspend fun downloadAndExtract(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (isReady(context.filesDir)) return@withContext directory(context.filesDir)
        val archive = File(context.filesDir, ARCHIVE)
        if (!archive.isFile) SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
        install(archive, context.filesDir)
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
