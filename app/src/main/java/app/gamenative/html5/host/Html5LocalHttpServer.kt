package app.gamenative.html5.host

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

// loopback HTTP/1.1 server. chromium 113+ (PlzDedicatedWorker) no longer routes worker subresource
// requests through shouldInterceptRequest, and media needs seekable Range responses; serving over real
// HTTP covers both. content comes from the same interceptor used for shouldInterceptRequest.
//
// - Connection: close, so EOF == end of body.
// - bounded pool: every app on the device can reach this port, so connection count isn't ours to trust.
// - Range (206) is honored for media; without it chromium's player stalls after the first chunk.
//
// per-container isolation comes from the URL host (see WebViewOrigin); all containers share this port.
class Html5LocalHttpServer(contentSource: (Uri) -> WebResourceResponse?) {
    val port: Int
    private val serverSocket: ServerSocket

    // past MAX_WORKERS we shed load with a 503 rather than a thread per socket.
    private val pool: ThreadPoolExecutor = ThreadPoolExecutor(
        0, MAX_WORKERS, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "Html5LocalHttpServer-worker").apply { isDaemon = true } },
    )
    private val running = AtomicBoolean(true)

    // swappable so the server can bind before the per-container interceptor exists; 404 until set.
    @Volatile private var source: ((Uri) -> WebResourceResponse?)? = contentSource
    fun setSource(s: ((Uri) -> WebResourceResponse?)?) { source = s }

    init {
        // 127.0.0.1, NOT 0.0.0.0. the port is part of the storage origin and must never change (see WebViewOrigin).
        val target = WebViewOrigin.ensurePortAllocated()
        // SO_REUSEADDR: a quick back-out + relaunch would otherwise fail to bind over TIME_WAIT.
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), target), 50)
        }
        port = serverSocket.localPort
        Timber.tag("Html5LocalHttpServer").i("bound to 127.0.0.1:%d", port)

        Thread({ acceptLoop() }, "Html5LocalHttpServer-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        var consecutiveFailures = 0
        while (running.get()) {
            val socket = try {
                serverSocket.accept().also { consecutiveFailures = 0 }
            } catch (e: IOException) {
                if (!running.get()) continue
                consecutiveFailures++
                Timber.tag("Html5LocalHttpServer").w(e, "accept failed (%d in a row)", consecutiveFailures)
                // a persistently failing accept() returns instantly; back off instead of spinning a core.
                runCatching { Thread.sleep(minOf(1_000L, 20L * consecutiveFailures)) }
                continue
            }
            try {
                pool.submit { handleConnection(socket) }
            } catch (e: RejectedExecutionException) {
                Timber.tag("Html5LocalHttpServer").w("worker pool saturated at %d; shedding connection", MAX_WORKERS)
                runCatching { socket.use { writeError(it.getOutputStream(), 503, "Service Unavailable") } }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 10_000
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                val output = socket.getOutputStream()

                val requestLine = readLineBounded(input, MAX_REQUEST_LINE_CHARS) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    writeError(output, 400, "Bad Request")
                    return
                }
                val method = parts[0]
                val target = parts[1]

                // Host carries the per-container origin, which the interceptor needs.
                var hostHeader: String? = null
                var rangeHeader: String? = null
                var headerCount = 0
                while (true) {
                    val line = readLineBounded(input, MAX_HEADER_LINE_CHARS) ?: break
                    if (line.isEmpty()) break
                    if (++headerCount > MAX_HEADERS) {
                        writeError(output, 431, "Request Header Fields Too Large")
                        return
                    }
                    if (line.length > 5 && line.regionMatches(0, "Host:", 0, 5, ignoreCase = true)) {
                        hostHeader = line.substring(5).trim()
                    } else if (line.length > 6 && line.regionMatches(0, "Range:", 0, 6, ignoreCase = true)) {
                        rangeHeader = line.substring(6).trim()
                    }
                }

                if (method != "GET" && method != "HEAD") {
                    writeError(output, 405, "Method Not Allowed")
                    return
                }

                val authority = hostHeader ?: "127.0.0.1:$port"
                val absUri = Uri.parse("http://$authority$target")

                val activeSource = source
                val response = activeSource?.invoke(absUri)
                if (response == null) {
                    writeError(output, 404, "Not Found")
                    return
                }
                writeResponse(output, response, headOnly = method == "HEAD", rangeHeader = rangeHeader)
            } catch (e: Exception) {
                // debug level: browser-side aborts are routine.
                Timber.tag("Html5LocalHttpServer").d(e, "connection handler exited")
            }
        }
    }

    private fun writeResponse(
        out: OutputStream,
        response: WebResourceResponse,
        headOnly: Boolean,
        rangeHeader: String? = null,
    ) {
        val baseStatus = response.statusCode.takeIf { it in 100..599 } ?: 200
        val baseReason = response.reasonPhrase ?: "OK"
        val mime = response.mimeType ?: "application/octet-stream"
        val charset = response.encoding
        val contentType = if (charset.isNullOrBlank()) mime else "$mime; charset=$charset"

        val hintedLength = response.responseHeaders
            ?.entries?.firstOrNull { it.key.equals(HEADER_CONTENT_LENGTH, ignoreCase = true) }
            ?.value?.toLongOrNull()
        val mode = decideBodyMode(mime, rangeHeader != null, hintedLength)

        val prepared = if (mode == BodyMode.BUFFER) prepareBody(response, rangeHeader, baseStatus, baseReason) else null
        val status = prepared?.status ?: baseStatus
        val reason = prepared?.reason ?: baseReason
        val contentRange = prepared?.contentRange
        val contentLength = prepared?.contentLength ?: hintedLength?.takeIf { mode == BodyMode.STREAM_LARGE }
        val bufferedBytes = prepared?.bytes
        val bufferedOffset = prepared?.offset ?: 0
        val bufferedSliceLen = prepared?.length ?: 0

        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Cache-Control: no-store\r\n")
        if (contentLength != null) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n")
            // only on 206/416: advertising it on a plain 200 makes chromium's demuxer attempt a seek that
            // fails. without it the pipeline stays on the sequential read path.
            if (status == 206 || status == 416) {
                sb.append("Accept-Ranges: bytes\r\n")
            }
        }
        if (contentRange != null) {
            sb.append("Content-Range: ").append(contentRange).append("\r\n")
        }
        response.responseHeaders?.forEach { (k, v) ->
            val lower = k.lowercase()
            if (lower != "content-type" && lower != "connection" && lower != "cache-control" &&
                lower != "content-length" && lower != "accept-ranges" && lower != "content-range"
            ) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()

        if (!headOnly) {
            if (bufferedBytes != null) {
                if (bufferedSliceLen > 0) {
                    out.write(bufferedBytes, bufferedOffset, bufferedSliceLen)
                }
            } else {
                response.data?.use { stream -> copyStream(stream, out) }
            }
        } else if (bufferedBytes == null) {
            // HEAD (existsSync probes) still got an open stream; close it or leak an fd per probe.
            // BUFFER mode already closed it in prepareBody.
            response.data?.close()
        }
        out.flush()
    }

    // null bytes = read failed.
    private data class PreparedBody(
        val status: Int,
        val reason: String,
        val contentRange: String?,
        val contentLength: Long?,
        val bytes: ByteArray?,
        val offset: Int,
        val length: Int,
    )

    // consumes response.data -- the caller must not stream from it afterwards.
    private fun prepareBody(
        response: android.webkit.WebResourceResponse,
        rangeHeader: String?,
        baseStatus: Int,
        baseReason: String,
    ): PreparedBody {
        val stream = response.data ?: return PreparedBody(baseStatus, baseReason, null, null, null, 0, 0)
        val bytes = try {
            stream.use { it.readBytes() }
        } catch (e: Exception) {
            Timber.tag("Html5LocalHttpServer").w(e, "buffer body failed")
            return PreparedBody(baseStatus, baseReason, null, null, null, 0, 0)
        }
        val total = bytes.size
        if (rangeHeader == null) {
            return PreparedBody(baseStatus, baseReason, null, total.toLong(), bytes, 0, total)
        }
        val parsed = parseRange(rangeHeader, total.toLong())
        if (parsed != null) {
            val (s, e) = parsed
            return PreparedBody(
                status = 206,
                reason = "Partial Content",
                contentRange = "bytes $s-$e/$total",
                contentLength = e - s + 1,
                bytes = bytes,
                offset = s.toInt(),
                length = (e - s + 1).toInt(),
            )
        }
        Timber.tag("Html5LocalHttpServer").w("416 unsatisfiable range header=%s total=%d", rangeHeader, total)
        return PreparedBody(
            status = 416,
            reason = "Range Not Satisfiable",
            contentRange = "bytes */$total",
            contentLength = 0L,
            bytes = ByteArray(0),
            offset = 0,
            length = 0,
        )
    }

    private fun writeError(out: OutputStream, status: Int, reason: String) {
        val body = reason
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: text/plain; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        sb.append(body)
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket.close() }
        pool.shutdownNow()
        Timber.tag("Html5LocalHttpServer").i("stopped (port=%d)", port)
    }

    internal enum class BodyMode {
        // no Content-Length, EOF-framed. non-media, non-range responses.
        STREAM_PLAIN,

        // exact Content-Length, no Accept-Ranges. large media stays off the heap; seeking re-requests
        // from 0 (slow but correct).
        STREAM_LARGE,

        // whole body in memory, sliced per Range -- true seek support for short media.
        BUFFER,
    }

    companion object {
        // legitimate traffic stays far below this (chromium opens <= 6 sockets per host); it exists because
        // every app on the device can reach the port.
        private const val MAX_WORKERS = 64

        private const val MAX_REQUEST_LINE_CHARS = 8 * 1024
        private const val MAX_HEADER_LINE_CHARS = 8 * 1024
        private const val MAX_HEADERS = 64

        // readLine() is unbounded, so a peer that never sends a newline could grow the heap until timeout.
        internal fun readLineBounded(input: BufferedReader, maxChars: Int): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return sb.takeIf { it.isNotEmpty() }?.toString()
                if (c == '\n'.code) return sb.toString().removeSuffix("\r")
                if (sb.length >= maxChars) throw IOException("request line exceeds $maxChars chars")
                sb.append(c.toChar())
            }
        }

        internal const val STREAM_THRESHOLD_BYTES = 32L * 1024 * 1024

        // unknown length -> BUFFER for media/range: only a hinted length can stream large bodies.
        internal fun decideBodyMode(mime: String, hasRange: Boolean, hintedLength: Long?): BodyMode {
            val isMedia = mime.startsWith("video/", ignoreCase = true) ||
                mime.startsWith("audio/", ignoreCase = true)
            if (!hasRange && !isMedia) return BodyMode.STREAM_PLAIN
            if (hintedLength != null && hintedLength > STREAM_THRESHOLD_BYTES) return BodyMode.STREAM_LARGE
            return BodyMode.BUFFER
        }

        // `bytes=START-END` / `bytes=START-` / `bytes=-SUFFIX_LEN` -> inclusive range, or null if
        // unsatisfiable. multi-range is unsupported; chromium only sends single ranges for media.
        internal fun parseRange(header: String, total: Long): Pair<Long, Long>? {
            if (total <= 0L) return null
            val spec = header.trim().removePrefix("bytes=").takeIf { !it.contains(',') } ?: return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val startStr = spec.substring(0, dash).trim()
            val endStr = spec.substring(dash + 1).trim()
            val start: Long
            val end: Long
            if (startStr.isEmpty()) {
                val suffix = endStr.toLongOrNull() ?: return null
                if (suffix <= 0L) return null
                start = (total - suffix).coerceAtLeast(0L)
                end = total - 1
            } else {
                start = startStr.toLongOrNull() ?: return null
                end = if (endStr.isEmpty()) total - 1 else endStr.toLongOrNull() ?: return null
            }
            if (start < 0L || start >= total || end < start) return null
            return start to end.coerceAtMost(total - 1)
        }
    }
}
