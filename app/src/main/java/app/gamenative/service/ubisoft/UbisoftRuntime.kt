package app.gamenative.service.ubisoft

import android.content.Context
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * The launcher's game-side files: API DLLs, the game starter and their imports. They are Ubisoft's
 * and are never hosted by us, so the device takes them from Ubisoft's own installer, reading only
 * these entries (ranged requests when the server allows them, a full download otherwise). The
 * result is cached once per installer build and copied into each prefix at launch.
 */
object UbisoftRuntime {
    /** Root-level installer files and the machine each one must be built for. */
    internal val FILES: Map<String, Int> = linkedMapOf(
        "uplay_r1.dll" to PeImage.MACHINE_X86,
        "uplay_r164.dll" to PeImage.MACHINE_X64,
        "uplay_r2.dll" to PeImage.MACHINE_X86,
        "uplay_r264.dll" to PeImage.MACHINE_X64,
        "ubiorbitapi_r2.dll" to PeImage.MACHINE_X86,
        "ubiorbitapi_r264.dll" to PeImage.MACHINE_X64,
        "uplay_aux_r1.dll" to PeImage.MACHINE_X86,
        "uplay_aux_r164.dll" to PeImage.MACHINE_X64,
        "UbisoftGameLauncher.exe" to PeImage.MACHINE_X86,
        "UbisoftGameLauncher64.exe" to PeImage.MACHINE_X64,
        "discord-rpc.x86.dll" to PeImage.MACHINE_X86,
        "discord-rpc.x64.dll" to PeImage.MACHINE_X64,
        "steam_api.dll" to PeImage.MACHINE_X86,
        "steam_api64.dll" to PeImage.MACHINE_X64,
        "overlay.dll" to PeImage.MACHINE_X86,
        "overlay64.dll" to PeImage.MACHINE_X64,
    )
    private const val VERSION_FILE = "version.txt"
    private const val COMPLETE_MARKER = ".complete"
    private const val DOWNLOAD_NAME = "ubisoft-installer.part"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun root(filesDir: File) = File(filesDir, "ubisoft")

    /** The newest complete runtime cache, or null when none has been extracted yet. */
    fun cachedRuntime(filesDir: File): File? =
        root(filesDir).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("runtime-") && isComplete(it) }
            .maxByOrNull { it.name.removePrefix("runtime-").toLongOrNull() ?: 0L }

    fun isCached(filesDir: File): Boolean = cachedRuntime(filesDir) != null

    internal fun isComplete(dir: File): Boolean =
        File(dir, COMPLETE_MARKER).isFile && FILES.keys.all { File(dir, it).isFile }

    suspend fun ensureCached(context: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        cachedRuntime(context.filesDir)?.let { return@withContext it }
        val url = UbisoftHelperConfig.value(context, UbisoftHelperConfig.INSTALLER_URL)
        val ranged = runCatching { HttpRangeSource(http, url) }
            .onFailure { Timber.i("Ubisoft runtime: ranged reads unavailable (%s), downloading the installer", it.message) }
            .getOrNull()
        if (ranged != null) {
            return@withContext ranged.use { extract(it, context.filesDir, onProgress) }
        }
        val installer = File(root(context.filesDir), DOWNLOAD_NAME)
        try {
            download(url, installer) { onProgress(it * 0.9f) }
            FileByteSource(installer).use { source -> extract(source, context.filesDir) { onProgress(0.9f + it * 0.1f) } }
        } finally {
            installer.delete()
        }
    }

    /** Reads the wanted entries, checks each one, and publishes the cache only when all are there. */
    @Synchronized
    internal fun extract(source: ByteSource, filesDir: File, onProgress: (Float) -> Unit = {}): File {
        val reader = NsisReader(source)
        val archive = reader.open()
        val wanted = FILES.keys.map { it.lowercase() }.toSet() + VERSION_FILE
        val entries = archive.entries.filter { it.isRootLevel && it.name.lowercase() in wanted }
            .groupBy { it.name.lowercase() }.mapValues { it.value.first() }
        val missing = FILES.keys.filter { it.lowercase() !in entries }
        check(missing.isEmpty()) { "The Ubisoft installer has no ${missing.joinToString()}" }

        val build = entries[VERSION_FILE]?.let { entry ->
            String(reader.extract(archive, entry, 64), Charsets.US_ASCII).trim().takeIf { it.matches(Regex("\\d{1,9}")) }
        } ?: "0"
        val target = File(root(filesDir), "runtime-$build")
        if (isComplete(target)) return target
        val stage = File(root(filesDir), "runtime-$build.partial")
        stage.deleteRecursively()
        check(stage.mkdirs()) { "Cannot create the Ubisoft runtime cache" }
        FILES.keys.forEachIndexed { index, name ->
            val bytes = reader.extract(archive, entries.getValue(name.lowercase()))
            verify(name, bytes)
            File(stage, name).writeBytes(bytes)
            onProgress((index + 1).toFloat() / FILES.size)
        }
        File(stage, COMPLETE_MARKER).writeText(build)
        target.deleteRecursively()
        check(stage.renameTo(target)) { "Cannot publish the Ubisoft runtime cache" }
        Timber.i("Ubisoft runtime: build %s cached (%d files)", build, FILES.size)
        return target
    }

    /** A layout change in a later installer has to fail here, not install the wrong file. */
    internal fun verify(name: String, bytes: ByteArray) {
        val pe = runCatching { PeImage.parse(bytes) }.getOrElse { error("$name from the Ubisoft installer is not a PE file: ${it.message}") }
        val expected = FILES[name] ?: error("$name is not a runtime file")
        check(pe.machine == expected) { "$name from the Ubisoft installer has machine 0x${pe.machine.toString(16)}" }
        if (name.endsWith(".dll", ignoreCase = true)) {
            check(pe.isDll && pe.exportName.equals(name, ignoreCase = true)) {
                "$name from the Ubisoft installer exports as ${pe.exportName ?: "nothing"}"
            }
        } else {
            check(!pe.isDll) { "$name from the Ubisoft installer is a DLL" }
        }
    }

    /** Copies the cached runtime into the prefix's launcher folder, skipping files already in place. */
    fun installInto(runtime: File, prefixDriveC: File): File {
        val target = File(prefixDriveC, UbisoftConstants.RUNTIME_DIR)
        check(target.isDirectory || target.mkdirs()) { "Cannot create the Ubisoft launcher folder" }
        for (name in FILES.keys) {
            val source = File(runtime, name)
            val dest = File(target, name)
            if (!dest.isFile || dest.length() != source.length()) source.copyTo(dest, overwrite = true)
        }
        return target
    }

    private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        target.parentFile?.mkdirs()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Ubisoft installer download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Ubisoft installer download returned no body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
    }
}

/**
 * Reads byte ranges over HTTP and keeps the last window, so the header scan and the small reads
 * around each entry cost one request each. Construction fails when the server ignores Range.
 */
class HttpRangeSource(private val client: OkHttpClient, private val url: String) : ByteSource {
    private var windowStart = 0L
    private var window: ByteArray
    override val size: Long

    init {
        val (bytes, total) = fetch(0, INITIAL_WINDOW)
        window = bytes
        size = total
    }

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position + length > size) throw EOFException("read past the end of the installer")
        if (position >= windowStart && position + length <= windowStart + window.size) {
            val from = (position - windowStart).toInt()
            return window.copyOfRange(from, from + length)
        }
        val want = minOf(maxOf(length.toLong(), MIN_WINDOW), size - position)
        val (bytes, _) = fetch(position, want)
        windowStart = position
        window = bytes
        return bytes.copyOfRange(0, length)
    }

    private fun fetch(start: Long, length: Long): Pair<ByteArray, Long> {
        val end = start + length - 1
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        client.newCall(request).execute().use { resp ->
            if (resp.code != 206) throw IOException("range request answered ${resp.code}")
            val total = resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: throw IOException("range answer has no total size")
            val bytes = resp.body?.bytes() ?: throw IOException("range answer has no body")
            if (bytes.size.toLong() != minOf(length, total - start)) throw IOException("short range answer")
            return bytes to total
        }
    }

    override fun close() {}

    private companion object {
        const val INITIAL_WINDOW = 1L shl 20
        const val MIN_WINDOW = 64L * 1024
    }
}
