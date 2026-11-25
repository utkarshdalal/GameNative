package app.gamenative.service.xbox

import android.content.Context
import app.gamenative.service.SteamService
import java.io.File
import java.io.InputStream
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/**
 * Versioned helper cache: the game runtime stand-in (xgameruntime.dll, 64-bit), the early-load proxy
 * template and the configuration.
 * Holds no Microsoft binary.
 */
object XboxHelperArchive {
    const val VERSION = "20261126"
    const val ARCHIVE = "xboxhost-$VERSION.tzst"
    const val RUNTIME_DLL = "xgameruntime.dll"
    const val CONFIG = "xboxhost.properties"
    const val NOTICE = "NOTICE.txt"
    const val PROXY_TEMPLATE = "xboxproxy.dll"
    private val required = setOf(RUNTIME_DLL, PROXY_TEMPLATE, CONFIG)

    fun directory(filesDir: File) = File(filesDir, "xbox/xboxhost-$VERSION")

    fun runtimeDll(filesDir: File) = File(directory(filesDir), RUNTIME_DLL)

    fun proxyTemplate(filesDir: File) = File(directory(filesDir), PROXY_TEMPLATE)

    fun configFile(filesDir: File) = File(directory(filesDir), CONFIG)

    fun isReady(filesDir: File): Boolean = required.all { File(directory(filesDir), it).isFile }

    /** Fetched only when the user installs or launches an Xbox game. */
    suspend fun downloadAndExtract(context: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (isReady(context.filesDir)) return@withContext directory(context.filesDir)
        val archive = File(context.filesDir, ARCHIVE)
        try {
            if (!archive.isFile) SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
            check(archive.isFile) { "Xbox helper archive is missing" }
            ensureExtracted(context.filesDir) { archive.inputStream() }.also { XboxHelperConfig.reset() }
        } finally {
            archive.delete()
        }
    }

    @Synchronized
    fun ensureExtracted(filesDir: File, openArchive: () -> InputStream): File {
        val target = directory(filesDir)
        if (isReady(filesDir)) return target
        check(target.isDirectory || target.mkdirs()) { "Cannot create Xbox helper cache" }
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
        check(isReady(filesDir)) { "Xbox helper archive is incomplete" }
        return target
    }

    internal fun loadConfig(filesDir: File): Properties {
        val file = configFile(filesDir)
        check(file.isFile) { "Xbox helper archive is not installed" }
        return Properties().apply { file.inputStream().use { load(it) } }
    }
}

/**
 * Values the helper archive supplies in xboxhost.properties: client ids, endpoints, client versions.
 * None of them are part of the app. Each package keeps its own key names next to the code that reads them.
 */
object XboxHelperConfig {
    @Volatile private var cached: Properties? = null

    private fun properties(filesDir: File): Properties =
        cached ?: XboxHelperArchive.loadConfig(filesDir).also { cached = it }

    fun value(filesDir: File, name: String): String =
        optional(filesDir, name) ?: error("Xbox helper configuration is missing $name")

    fun optional(filesDir: File, name: String): String? =
        properties(filesDir).getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

    /** Replaces each `{key}` in [template]; unknown placeholders are left alone. */
    fun fill(template: String, values: Map<String, String>): String =
        values.entries.fold(template) { acc, (key, value) -> acc.replace("{$key}", value) }

    internal fun reset() { cached = null }
}
