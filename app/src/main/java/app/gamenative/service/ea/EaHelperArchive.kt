package app.gamenative.service.ea

import android.content.Context
import app.gamenative.service.SteamService
import java.io.File
import java.io.InputStream
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/** Downloaded separately from Steam and installed after the Steam tree is refreshed. */
object EaHelperArchive {
    const val ARCHIVE = "eahost-20260915.3.tzst"
    internal const val PREFIX_PATH = "Program Files (x86)/Steam/eastub.exe"
    internal const val EXECUTABLE = "eastub.exe"
    internal const val CONFIG = "ea-helper.properties"

    fun configFile(filesDir: File) = File(filesDir, "ea/$CONFIG")

    suspend fun download(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val archive = File(context.filesDir, ARCHIVE)
        val fetched = !archive.isFile
        if (fetched) SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
        if (fetched || !configFile(context.filesDir).isFile) {
            extractConfig(archive, context.filesDir)
            EaHelperConfig.reset()
        }
    }

    fun install(context: Context, prefixDriveC: File) = install(File(context.filesDir, ARCHIVE), prefixDriveC)

    /** Writes eastub.exe from the archive into the prefix on every launch. */
    internal fun install(archive: File, prefixDriveC: File) {
        val target = File(prefixDriveC, PREFIX_PATH)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create EA helper directory" }
        copyEntry(archive, EXECUTABLE, target)
        target.setExecutable(true, true)
    }

    /** The helper's configuration lives beside the archive, read by the app when it talks to EA. */
    internal fun extractConfig(archive: File, filesDir: File): File {
        val target = configFile(filesDir)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create EA helper directory" }
        copyEntry(archive, CONFIG, target)
        return target
    }

    internal fun loadConfig(filesDir: File): Properties {
        val file = configFile(filesDir)
        check(file.isFile) { "EA helper archive is not installed" }
        return Properties().apply { file.inputStream().use { load(it) } }
    }

    private fun copyEntry(archive: File, name: String, target: File) {
        check(archive.isFile) { "EA helper archive is missing" }
        archive.inputStream().use { raw ->
            ZstdCompressorInputStream(raw).use { zstd ->
                TarArchiveInputStream(zstd).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (entry.isFile && File(entry.name).name == name) {
                            target.outputStream().use { tar.copyTo(it) }
                            return
                        }
                    }
                }
            }
        }
        error("EA helper archive has no $name")
    }
}
