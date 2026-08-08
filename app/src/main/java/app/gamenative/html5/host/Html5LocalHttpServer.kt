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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

// minimal HTTP/1.1 server bound to 127.0.0.1. exists because WebView's
// WebViewClient.shouldInterceptRequest stopped firing for dedicated-worker subresource
// requests on Chromium ≥ 113 (PlzDedicatedWorker browser-process worker loading) -- and the
// inline-orig stub trick covers only the worker's ENTRY url, not its subsequent dynamic
// imports. serving the game over real HTTP makes those subresource fetches reach us via
// the network stack.
//
// design notes:
// - blocks on accept() in one thread; dispatches each connection to a fixed thread pool.
// - HTTP/1.1 with Connection: close (no keep-alive -- simpler, and worker subresource
//   batches are small).
// - parses just enough of the request to extract method + path + query. body of GETs is
//   ignored (no POSTs in our serving model).
// - source is a lambda: identical inputs/outputs as the per-page interceptor's
//   shouldInterceptRequest path, so index.html injection / /_shims/ / /_worker_stub /
//   /_opfs_ready_marker / case-insensitive disk lookups / asar/zip dispatch all flow
//   through one source of truth.
// - status code + reason phrase pulled from WebResourceResponse when present (API 21+).
//   Connection: close so EOF == end-of-body. Range requests honored for video/audio bodies
//   (HTTP 206 Partial Content with Content-Range + Content-Length + Accept-Ranges). chromium's
//   <video> element issues Range probes for metadata + seek; without range support the player
//   buffers the first chunk then bails ("MediaCodec discarded an unknown buffer" cascade
//   observed empirically against Tyrano webm playback). buffered only for media MIMEs or when
//   the client sent a Range header -- other responses still stream via copyStream + EOF.
//   EXCEPTION: when the interceptor attached a Content-Length hint (withContentLength) and the
//   body exceeds STREAM_THRESHOLD_BYTES, the body streams sequentially with that exact length
//   and no Accept-Ranges instead of being buffered whole -- avoids pinning a long cutscene in
//   memory. seeking on such bodies re-requests from 0 (correct, just slow); short media stays
//   under the threshold and keeps full Range/206 seek support.
//
// per-container origin isolation comes from the URL scheme -- chromium routes any host
// matching `*.localhost` to loopback (RFC 6761) without DNS, so each container can use a
// distinct host like `steam-2738490.localhost` and chromium partitions IDB/LS/cookies by
// origin without needing the multi-profile API. all containers share THIS single port.
class Html5LocalHttpServer(contentSource: (Uri) -> WebResourceResponse?) {
    val port: Int
    private val serverSocket: ServerSocket
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "Html5LocalHttpServer-worker").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(true)

    // hot-swappable source so the server can start (and bind a port) BEFORE the
    // per-container interceptor exists. WebViewScreen sets the source once the
    // interceptor is built; until then, all requests get 404. requests that would race
    // ahead of source registration (none in practice -- webView.loadUrl is gated on
    // saveSyncInboundComplete which fires after the interceptor) just retry on 404.
    @Volatile private var source: ((Uri) -> WebResourceResponse?)? = contentSource
    fun setSource(s: ((Uri) -> WebResourceResponse?)?) { source = s }

    init {
        // bind to 127.0.0.1 explicitly (NOT 0.0.0.0). bind to the WebViewOrigin-allocated
        // persistent port so the leveldb origin (`http_<host>_<port>`) is stable across
        // launches -- chromium would otherwise orphan saves on a port change. WebViewOrigin
        // owns the lifecycle: first call allocates + persists, subsequent calls return the
        // same value. backlog 50 is plenty -- c3 spawns ~3 workers, each issues a handful of
        // importScripts. peak concurrency ~10 connections.
        val target = WebViewOrigin.ensurePortAllocated()
        // SO_REUSEADDR prevents bind-fail when the previous server's socket is in TIME_WAIT
        // (back-out + re-launch within ~30s). without it, the second server crashes on bind.
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
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (running.get()) Timber.tag("Html5LocalHttpServer").w(e, "accept failed")
                continue
            }
            pool.submit { handleConnection(socket) }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 10_000 // 10s read timeout
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                val output = socket.getOutputStream()

                // request line: METHOD SP URI SP HTTP/1.1
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    writeError(output, 400, "Bad Request")
                    return
                }
                val method = parts[0]
                val target = parts[1]

                // capture Host header so worker subresource fetches against
                // <safeId>.localhost:<port> retain their authority -- chromium origin =
                // scheme+host+port, so the URL we hand to the interceptor must match what the
                // game sees, otherwise per-container path resolution + storage routing drift.
                var hostHeader: String? = null
                var rangeHeader: String? = null
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
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

                // construct an absolute URI matching the URL chromium thinks it requested.
                // hostHeader missing → fall back to 127.0.0.1:<port> (legacy worker stubs and
                // raw-socket health checks have no Host).
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
                // logged at debug; common for browser-side aborts.
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

        // buffer the body when (a) the client asked for a range, or (b) the mime is media.
        // chromium's video/audio elements need Content-Length + Accept-Ranges to advance past
        // metadata-load → fallback path without them produces "broken media" placeholders.
        // for non-media responses with no Range header we still stream via copyStream so
        // large static assets (e.g. zip-hosted PNG/JS) don't get pinned in memory.
        // size hint set by the interceptors on pristine passthrough bodies (see withContentLength).
        // drives the buffer-vs-stream decision below.
        val hintedLength = response.responseHeaders
            ?.entries?.firstOrNull { it.key.equals(HEADER_CONTENT_LENGTH, ignoreCase = true) }
            ?.value?.toLongOrNull()
        val mode = decideBodyMode(mime, rangeHeader != null, hintedLength)

        val prepared = if (mode == BodyMode.BUFFER) prepareBody(response, rangeHeader, baseStatus, baseReason) else null
        val status = prepared?.status ?: baseStatus
        val reason = prepared?.reason ?: baseReason
        val contentRange = prepared?.contentRange
        // STREAM_LARGE: emit the hinted length so chromium knows the body size up front (no
        // Accept-Ranges -- status stays 200 so the Accept-Ranges branch below won't fire).
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
            // Accept-Ranges advertised only when we got a Range request (i.e., on 206
            // responses or 416). on a plain 200 for media we hand the full buffer; chromium's
            // pipeline that THINKS it can seek via range issues an internal seek that fails
            // ("FFmpegDemuxer: demuxer seek failed") even though all bytes are in-process.
            // omitting Accept-Ranges keeps the pipeline on the sequential read path.
            if (status == 206 || status == 416) {
                sb.append("Accept-Ranges: bytes\r\n")
            }
        }
        if (contentRange != null) {
            sb.append("Content-Range: ").append(contentRange).append("\r\n")
        }
        // pass-through any headers the response chose to set (CORS, no-cache, etc.).
        response.responseHeaders?.forEach { (k, v) ->
            // skip headers we set ourselves to avoid duplicates.
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
            // HEAD on a stream-mode response (the existsSync probe path): no body emitted, but
            // the interceptor still handed us an open stream -- close it or we leak an fd per probe.
            // BUFFER mode already drained+closed response.data in prepareBody, hence the null guard.
            response.data?.close()
        }
        out.flush()
    }

    // outcome of buffering a response body for Range / media handling. null bytes = read failed.
    private data class PreparedBody(
        val status: Int,
        val reason: String,
        val contentRange: String?,
        val contentLength: Long?,
        val bytes: ByteArray?,
        val offset: Int,
        val length: Int,
    )

    // reads the response body once, slices it per the Range header (or serves whole on null /
    // unsatisfiable). drains response.data via .use{} -- caller must not stream from response.data
    // afterwards. returns null bytes on read failure so the caller can fall back to streaming.
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
        // unsatisfiable range → 416 with Content-Range: bytes */total
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

    // how a response body is delivered. pure decision in decideBodyMode so it's unit-testable
    // without binding a socket (the ctor binds 127.0.0.1, so instances can't run on plain JVM).
    internal enum class BodyMode {
        // copyStream + Connection: close EOF; no Content-Length. the default for non-media,
        // non-range responses (static JS/PNG) -- never pinned in memory.
        STREAM_PLAIN,

        // copyStream with an exact Content-Length, no Accept-Ranges. for media/range bodies whose
        // hinted size exceeds the threshold -- keeps long cutscenes off the heap.
        STREAM_LARGE,

        // read whole into memory, slice per Range (206/416) or serve whole (200). for media/range
        // bodies at or under the threshold -- preserves true seek support for short audio/video.
        BUFFER,
    }

    companion object {
        // bodies larger than this stream sequentially (when a Content-Length hint is present)
        // rather than buffering whole. 32MB covers short audio/video that benefits from in-memory
        // Range seeking while keeping multi-tens-of-MB cutscenes off the heap.
        internal const val STREAM_THRESHOLD_BYTES = 32L * 1024 * 1024

        // pure body-delivery decision. media MIME or a Range request wants Range-capable handling;
        // a known-large body streams sequentially instead (no whole-file buffer); everything else
        // streams plainly. hintedLength null (size unknown) falls back to BUFFER for media/range so
        // Range support is preserved at the cost of buffering -- only the hinted path can stream big.
        internal fun decideBodyMode(mime: String, hasRange: Boolean, hintedLength: Long?): BodyMode {
            val isMedia = mime.startsWith("video/", ignoreCase = true) ||
                mime.startsWith("audio/", ignoreCase = true)
            if (!hasRange && !isMedia) return BodyMode.STREAM_PLAIN
            if (hintedLength != null && hintedLength > STREAM_THRESHOLD_BYTES) return BodyMode.STREAM_LARGE
            return BodyMode.BUFFER
        }

        // parses `bytes=START-END` / `bytes=START-` / `bytes=-SUFFIX_LEN`. returns inclusive
        // [start, end] in absolute file offsets, or null if unsatisfiable (start beyond total,
        // unparseable, or multi-range). chromium only sends single-range for media playback.
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
                // suffix range: last N bytes
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
