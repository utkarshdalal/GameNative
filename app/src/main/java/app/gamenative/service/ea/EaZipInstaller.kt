package app.gamenative.service.ea

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import timber.log.Timber

/**
 * Installs an EA game build straight from its zip on the CDN with HTTP range requests, one entry at a time.
 * Port of Maxima's content/downloader.rs and content/manager.rs (ArmchairDevelopers/Maxima, GPL-3.0).
 */
object EaZipInstaller {
    /** [partialBytes] are pending entries' bytes already on disk from an earlier run. */
    class Plan(val entries: List<EaZip.Entry>, val pending: List<EaZip.Entry>, val partialBytes: Long = 0) {
        val pendingBytes: Long = pending.sumOf { it.compressedSize } - partialBytes
        val installBytes: Long = entries.filter { !it.isDirectory }.sumOf { it.uncompressedSize }
    }

    private const val STATE_FILE = ".gn_ea_install_state"
    private const val STATE_WRITE_INTERVAL_MS = 2_000L
    private const val PART_SUFFIX = ".gnpart"
    private const val OUT_SUFFIX = ".gnout"
    private const val LARGE_ENTRY = 64L * 1024 * 1024
    private const val INITIAL_TAIL = 64L * 1024
    private const val CONCURRENCY = 6
    private const val MAX_TRIES = 5
    private const val BUFFER_SIZE = 256 * 1024

    private class UrlExpiredException(code: Int) : IOException("download URL rejected with HTTP $code")

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Reads the central directory (HEAD for length, then range GETs of the tail, growing as NeedFrom asks) and works out which entries still need fetching. */
    suspend fun plan(url: String, installDir: File): Plan = withContext(Dispatchers.IO) {
        val totalSize = contentLength(url)
        var tail = rangeBytes(url, maxOf(0L, totalSize - INITIAL_TAIL), totalSize)
        var result = EaZip.parseTail(tail, totalSize)
        while (result is EaZip.TailResult.NeedFrom) {
            tail = rangeBytes(url, result.offset, totalSize - tail.size) + tail
            result = EaZip.parseTail(tail, totalSize)
        }
        val entries = (result as EaZip.TailResult.Entries).entries

        val state = readState(installDir)
        val pending = entries.filter { entry ->
            if (entry.isDirectory) return@filter false
            if (!EaZip.isSafeName(entry.name)) {
                Timber.w("EA install: skipping unsafe zip entry %s", entry.name)
                return@filter false
            }
            val file = target(installDir, entry)
            val done = state != null && state[entry.name] == entry.crc32 && file.isFile && file.length() == entry.uncompressedSize
            !done
        }
        val partial = pending.sumOf { minOf(partFile(target(installDir, it)).length(), it.compressedSize) }
        Plan(entries, pending, partial)
    }

    /** Fetches [plan].pending into [installDir]; [refreshUrl] is called for a fresh signed URL when the CDN answers 401/403/410. [onBytes] gets compressed-byte deltas as they arrive. */
    suspend fun install(plan: Plan, installDir: File, url: String, refreshUrl: suspend () -> String, onBytes: (Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        ensureDir(installDir)
        for (entry in plan.entries) {
            if (entry.isDirectory && EaZip.isSafeName(entry.name)) ensureDir(target(installDir, entry))
        }

        val state = readState(installDir) ?: mutableMapOf()
        val stateLock = Any()
        val urlMutex = Mutex()
        var currentUrl = url

        suspend fun freshUrl(failed: String): String = urlMutex.withLock {
            if (currentUrl == failed) currentUrl = refreshUrl()
            currentUrl
        }

        suspend fun usableUrl(): String = urlMutex.withLock { currentUrl }

        val semaphore = Semaphore(CONCURRENCY)
        var stateWrittenAt = 0L
        try {
        coroutineScope {
            plan.pending.map { entry ->
                async {
                    semaphore.withPermit {
                        if (!EaZip.isSafeName(entry.name)) {
                            Timber.w("EA install: skipping unsafe zip entry %s", entry.name)
                            return@withPermit
                        }
                        val file = target(installDir, entry)
                        if (entry.isDirectory) {
                            ensureDir(file)
                            return@withPermit
                        }
                        fetchEntry(entry, file, { usableUrl() }, { freshUrl(it) }, onBytes)
                        synchronized(stateLock) {
                            state[entry.name] = entry.crc32
                            val now = System.currentTimeMillis()
                            if (now - stateWrittenAt >= STATE_WRITE_INTERVAL_MS || entry.compressedSize >= LARGE_ENTRY) {
                                writeState(installDir, state)
                                stateWrittenAt = now
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        } finally {
            synchronized(stateLock) { writeState(installDir, state) }
        }
    }

    /**
     * Brings one entry into place. The entry's bytes as stored in the zip go to a part file that a
     * later run continues with a range request; only a complete part is inflated (or, when stored,
     * moved) into place and checked. A part that fails the check is fetched again once.
     */
    private suspend fun fetchEntry(
        entry: EaZip.Entry,
        file: File,
        currentUrl: suspend () -> String,
        refresh: suspend (String) -> String,
        onBytes: (Long) -> Unit,
    ) {
        file.parentFile?.let { ensureDir(it) }
        if (entry.uncompressedSize == 0L) {
            FileOutputStream(file).close()
            return
        }
        if (entry.method != 0 && entry.method != 8) throw IOException("zip entry ${entry.name} uses unsupported compression method ${entry.method}")
        if (entry.dataOffset < 0) throw IOException("zip entry ${entry.name} has no known data offset")

        val part = partFile(file)
        if (part.length() > entry.compressedSize) part.delete()
        var lastError: IOException? = null
        var refetched = false
        for (attempt in 1..MAX_TRIES) {
            currentCoroutineContext().ensureActive()
            val url = currentUrl()
            try {
                fetchRaw(entry, url, part, onBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UrlExpiredException) {
                lastError = e
                refresh(url)
                continue
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                lastError = e
                Timber.w(e, "EA install: %s failed (try %d of %d)", entry.name, attempt, MAX_TRIES)
                if (attempt < MAX_TRIES) delay(1000L * attempt)
                continue
            }
            try {
                extract(entry, part, file)
                return
            } catch (e: IOException) {
                lastError = e
                Timber.w(e, "EA install: %s did not check out, fetching it again", entry.name)
                onBytes(-part.length())
                part.delete()
                if (refetched) break
                refetched = true
            }
        }
        throw IOException("zip entry ${entry.name} failed after $MAX_TRIES tries", lastError)
    }

    /** Appends the entry's missing stored bytes to [part], continuing from what is already there. */
    private suspend fun fetchRaw(entry: EaZip.Entry, url: String, part: File, onBytes: (Long) -> Unit) {
        val have = part.length()
        if (have >= entry.compressedSize) return
        val start = entry.dataOffset + have
        val end = entry.dataOffset + entry.compressedSize
        val call = client.newCall(rangeRequest(url, start, end))
        withCancellableCall(call) {
            call.execute().use { response ->
                checkRange(response, start, end)
                var received = have
                FileOutputStream(part, true).use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (received + n > entry.compressedSize) throw IOException("zip entry ${entry.name} got more bytes than requested")
                            out.write(buffer, 0, n)
                            received += n
                            onBytes(n.toLong())
                        }
                    }
                }
                if (received != entry.compressedSize) throw IOException("zip entry ${entry.name} got $received of ${entry.compressedSize} bytes")
            }
        }
    }

    /** Turns a complete part into [file]; throws when its size or CRC32 is wrong. */
    internal fun extract(entry: EaZip.Entry, part: File, file: File) {
        val crc = CRC32()
        var written = 0L
        fun count(b: ByteArray, n: Int) {
            written += n
            if (written > entry.uncompressedSize) throw IOException("zip entry ${entry.name} inflates past its size")
            crc.update(b, 0, n)
        }
        fun verify() {
            if (written != entry.uncompressedSize) throw IOException("zip entry ${entry.name} is $written bytes, expected ${entry.uncompressedSize}")
            if (crc.value != entry.crc32) throw IOException("zip entry ${entry.name} failed its CRC32 check")
        }
        fun replaceWith(source: File) {
            if (file.exists() && !file.delete()) throw IOException("cannot replace ${file.path}")
            if (!source.renameTo(file)) throw IOException("cannot move ${source.path} to ${file.path}")
        }

        if (entry.method == 0) {
            val buffer = ByteArray(BUFFER_SIZE)
            FileInputStream(part).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    count(buffer, n)
                }
            }
            verify()
            replaceWith(part)
            return
        }

        val out = File(file.path + OUT_SUFFIX)
        val inBuffer = ByteArray(BUFFER_SIZE)
        val outBuffer = ByteArray(BUFFER_SIZE)
        val inflater = Inflater(true)
        try {
            BufferedOutputStream(FileOutputStream(out), BUFFER_SIZE).use { sink ->
                fun drain() {
                    while (!inflater.finished()) {
                        val n = inflater.inflate(outBuffer)
                        if (n > 0) {
                            count(outBuffer, n)
                            sink.write(outBuffer, 0, n)
                        } else if (inflater.needsInput()) {
                            return
                        } else if (inflater.needsDictionary()) {
                            throw IOException("zip entry ${entry.name} needs a preset dictionary")
                        }
                    }
                }
                FileInputStream(part).use { input ->
                    while (!inflater.finished()) {
                        val n = input.read(inBuffer)
                        if (n < 0) break
                        inflater.setInput(inBuffer, 0, n)
                        drain()
                    }
                }
                if (!inflater.finished()) {
                    inflater.setInput(byteArrayOf(0))
                    drain()
                }
                if (!inflater.finished()) throw IOException("zip entry ${entry.name} has a truncated deflate stream")
            }
            verify()
            replaceWith(out)
            part.delete()
        } catch (e: java.util.zip.DataFormatException) {
            throw IOException("zip entry ${entry.name} has corrupt deflate data", e)
        } finally {
            inflater.end()
            if (out.exists()) out.delete()
        }
    }

    private suspend fun <T> withCancellableCall(call: Call, block: suspend () -> T): T = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    private fun contentLength(url: String): Long {
        val request = Request.Builder().url(url).head().header("Accept-Encoding", "identity").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403 || response.code == 410) throw UrlExpiredException(response.code)
            if (!response.isSuccessful) throw IOException("HEAD of the game archive failed with HTTP ${response.code}")
            val length = response.header("Content-Length")?.toLongOrNull()
            if (length == null || length <= 0) throw IOException("game archive has no content length")
            return length
        }
    }

    private fun rangeBytes(url: String, start: Long, end: Long): ByteArray {
        client.newCall(rangeRequest(url, start, end)).execute().use { response ->
            checkRange(response, start, end)
            val bytes = response.body.bytes()
            if (bytes.size.toLong() != end - start) throw IOException("range $start-$end returned ${bytes.size} bytes")
            return bytes
        }
    }

    private fun rangeRequest(url: String, start: Long, end: Long): Request =
        Request.Builder().url(url)
            .header("Range", "bytes=$start-${end - 1}")
            .header("Accept-Encoding", "identity")
            .build()

    private fun checkRange(response: Response, start: Long, end: Long) {
        val code = response.code
        if (code == 401 || code == 403 || code == 410) throw UrlExpiredException(code)
        if (code != 206) throw IOException("range $start-${end - 1} answered HTTP $code instead of 206")
        val contentRange = response.header("Content-Range")
        if (contentRange != null) {
            val first = Regex("""bytes\s+(\d+)-""").find(contentRange)?.groupValues?.get(1)?.toLongOrNull()
            if (first != null && first != start) throw IOException("range $start-${end - 1} answered $contentRange")
        }
    }

    private fun partFile(file: File): File = File(file.path + PART_SUFFIX)

    internal fun target(installDir: File, entry: EaZip.Entry): File =
        File(installDir, entry.name.replace('\\', '/').trimEnd('/'))

    private fun ensureDir(dir: File) {
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create ${dir.path}")
    }

    private fun readState(installDir: File): MutableMap<String, Long>? {
        val file = File(installDir, STATE_FILE)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, Long>()
            for (key in json.keys()) map[key] = json.getLong(key)
            map
        } catch (e: Exception) {
            Timber.w(e, "EA install: unreadable install state, starting over")
            null
        }
    }

    private fun writeState(installDir: File, state: Map<String, Long>) {
        val json = JSONObject()
        for ((name, crc) in state) json.put(name, crc)
        val tmp = File(installDir, "$STATE_FILE.tmp")
        tmp.writeText(json.toString())
        val file = File(installDir, STATE_FILE)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot write ${file.path}")
        }
    }
}
