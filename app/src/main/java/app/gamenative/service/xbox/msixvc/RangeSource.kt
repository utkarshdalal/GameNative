// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.msixvc

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Random access to a package. Implementations must be safe to call from several threads. */
interface RangeSource {
    val length: Long

    fun read(offset: Long, length: Int): ByteArray

    /** Sequential stream over [offset, offset + length). */
    fun openStream(offset: Long, length: Long): InputStream = ChunkedRangeStream(this, offset, length)
}

private class ChunkedRangeStream(
    private val source: RangeSource,
    private var pos: Long,
    length: Long,
) : InputStream() {
    private val end = pos + length
    private var buf = ByteArray(0)
    private var bufPos = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (bufPos >= buf.size) {
            if (pos >= end) return -1
            val n = minOf(CHUNK.toLong(), end - pos).toInt()
            buf = source.read(pos, n)
            pos += n
            bufPos = 0
        }
        val n = minOf(len, buf.size - bufPos)
        System.arraycopy(buf, bufPos, b, off, n)
        bufPos += n
        return n
    }

    companion object {
        private const val CHUNK = 1 shl 20
    }
}

class FileRangeSource(file: File) : RangeSource, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val channel = raf.channel
    override val length: Long = raf.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset + length > this.length) {
            throw MsixvcException.RangeRequestFailed("read $offset+$length outside 0..${this.length}")
        }
        val out = ByteArray(length)
        val bb = ByteBuffer.wrap(out)
        var pos = offset
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n < 0) throw IOException("unexpected end of file at $pos")
            pos += n
        }
        return out
    }

    override fun close() = raf.close()
}

/**
 * HTTP range reads. Each stream is one ranged GET that resumes from the current position
 * after a connection error, as xodus's HttpRead does.
 */
class HttpRangeSource(
    private val client: OkHttpClient,
    private val url: String,
    override val length: Long,
    private val maxRetries: Int = 6,
) : RangeSource {
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        openStream(offset, length.toLong()).use { s ->
            var p = 0
            while (p < length) {
                val n = s.read(out, p, length - p)
                if (n < 0) throw MsixvcException.RangeRequestFailed("short read at ${offset + p}")
                p += n
            }
        }
        return out
    }

    override fun openStream(offset: Long, length: Long): InputStream {
        if (offset < 0 || offset + length > this.length) {
            throw MsixvcException.RangeRequestFailed("range $offset+$length outside 0..${this.length}")
        }
        return ResumingStream(offset, offset + length)
    }

    private fun open(start: Long, end: Long): Response {
        val request = Request.Builder().url(url).header("Range", "bytes=$start-${end - 1}").build()
        val response = client.newCall(request).execute()
        if (response.code != 206) {
            response.close()
            throw IOException("expected 206 Partial Content, got ${response.code}")
        }
        val contentRange = response.header("Content-Range")
        val actualStart = contentRange?.removePrefix("bytes ")?.substringBefore('-')?.toLongOrNull()
        if (actualStart != start) {
            response.close()
            throw IOException("range resume mismatch: expected $start, got $contentRange")
        }
        return response
    }

    private inner class ResumingStream(private var pos: Long, private val end: Long) : InputStream() {
        private var response: Response? = null
        private var body: InputStream? = null
        private var failures = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= end) return -1
            while (true) {
                try {
                    val stream = body ?: open(pos, end).also { response = it }.body.byteStream().also { body = it }
                    val n = stream.read(b, off, minOf(len.toLong(), end - pos).toInt())
                    if (n < 0) throw IOException("connection closed at $pos of $end")
                    pos += n
                    failures = 0
                    return n
                } catch (e: IOException) {
                    closeCurrent()
                    failures++
                    if (failures > maxRetries) {
                        throw MsixvcException.RangeRequestFailed("range read failed at $pos: ${e.message}", e)
                    }
                    try {
                        Thread.sleep(500L * failures)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("interrupted", ie)
                    }
                }
            }
        }

        private fun closeCurrent() {
            runCatching { response?.close() }
            response = null
            body = null
        }

        override fun close() = closeCurrent()
    }

    companion object {
        /** Package size from the Content-Range total of a one-byte ranged GET. */
        fun probeLength(client: OkHttpClient, url: String): Long {
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
            client.newCall(request).execute().use { r ->
                if (r.code != 206) throw MsixvcException.RangeRequestFailed("expected 206, got ${r.code}")
                return r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    ?: throw MsixvcException.RangeRequestFailed("missing Content-Range total")
            }
        }
    }
}
