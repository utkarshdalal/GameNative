package app.gamenative.service.ea

import android.content.Context
import app.gamenative.service.SteamService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/** Downloaded separately from Steam and installed after the Steam tree is refreshed. */
object EaHelperArchive {
    const val ARCHIVE = "eahost-20260915.tzst"
    internal const val PREFIX_PATH = "Program Files (x86)/Steam/eastub.exe"

    suspend fun download(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val archive = File(context.filesDir, ARCHIVE)
        if (!archive.isFile) SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
    }

    fun install(context: Context, prefixDriveC: File) = install(File(context.filesDir, ARCHIVE), prefixDriveC)

    /** Writes eastub.exe from the archive into the prefix on every launch. */
    internal fun install(archive: File, prefixDriveC: File) {
        check(archive.isFile) { "EA helper archive is missing" }
        val target = File(prefixDriveC, PREFIX_PATH)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create EA helper directory" }
        archive.inputStream().use { raw ->
            ZstdCompressorInputStream(raw).use { zstd ->
                TarArchiveInputStream(zstd).use { tar ->
                    val entry = checkNotNull(tar.nextTarEntry) { "Empty EA helper archive" }
                    check(entry.isFile && File(entry.name).name == "eastub.exe") { "Unexpected EA helper archive entry" }
                    target.outputStream().use { tar.copyTo(it) }
                }
            }
        }
        target.setExecutable(true, true)
    }
}
