package app.gamenative.service.ubisoft

import android.content.Context
import app.gamenative.service.SteamService
import java.io.File
import java.io.InputStream
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/** Versioned helper cache: the stub and its configuration. Holds no Ubisoft binary. */
object UbisoftHelperArchive {
    const val VERSION = "20261115.1"
    const val ARCHIVE = "ubihost-$VERSION.tzst"
    const val STUB = "ubihost.exe"
    const val CONFIG = "ubihost.properties"
    private val required = setOf(STUB, CONFIG)

    fun directory(filesDir: File) = File(filesDir, "ubisoft/ubihost-$VERSION")

    fun stub(filesDir: File) = File(directory(filesDir), STUB)

    fun configFile(filesDir: File) = File(directory(filesDir), CONFIG)

    fun isReady(filesDir: File): Boolean = required.all { File(directory(filesDir), it).isFile }

    suspend fun downloadAndExtract(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (isReady(context.filesDir)) return@withContext directory(context.filesDir)
        val archive = File(context.filesDir, ARCHIVE)
        if (!archive.isFile) SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
        check(archive.isFile) { "Ubisoft helper archive is missing" }
        ensureExtracted(context.filesDir) { archive.inputStream() }.also { UbisoftHelperConfig.reset() }
    }

    /** Extract once per version; a cache with every required file present is used as is. */
    @Synchronized
    fun ensureExtracted(filesDir: File, openArchive: () -> InputStream): File {
        val target = directory(filesDir)
        if (isReady(filesDir)) return target
        check(target.isDirectory || target.mkdirs()) { "Cannot create Ubisoft helper cache" }
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
        check(isReady(filesDir)) { "Ubisoft helper archive is incomplete" }
        return target
    }

    internal fun loadConfig(filesDir: File): Properties {
        val file = configFile(filesDir)
        check(file.isFile) { "Ubisoft helper archive is not installed" }
        return Properties().apply { file.inputStream().use { load(it) } }
    }
}
