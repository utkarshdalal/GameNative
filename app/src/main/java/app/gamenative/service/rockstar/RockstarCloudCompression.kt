package app.gamenative.service.rockstar

import com.jcraft.jzlib.Deflater
import com.jcraft.jzlib.JZlib
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Cloud save container used by the Rockstar launcher for titles whose title.rgl sets `cloudSaveCompression`
 * (GTA IV): a 22-byte little-endian header (u16 version=1, u32 plain size, 16-byte plain MD5) followed by a raw
 * deflate stream produced with zlib `deflateInit2(level 9, windowBits -9, memLevel 1)`. The launcher inflates
 * with windowBits -9, so back-references past 512 bytes are rejected; JZlib honours that window, java.util.zip does not.
 */
object RockstarCloudCompression {
    const val HEADER = 22
    const val MAX_PLAIN = 0x3FFFFFFL
    private const val VERSION = 1
    private const val WINDOW_BITS = 9
    private const val MEM_LEVEL = 1
    private const val BUFFER = 64 * 1024

    class Result(val size: Long, val md5: String)

    fun compress(plain: File, out: File): Result {
        val deflater = Deflater(JZlib.Z_BEST_COMPRESSION, WINDOW_BITS, MEM_LEVEL, JZlib.W_NONE)
        val md5 = MessageDigest.getInstance("MD5")
        val input = ByteArray(BUFFER)
        val output = ByteArray(BUFFER)
        var size = 0L
        try {
            RandomAccessFile(out, "rw").use { stream ->
                stream.setLength(0)
                stream.write(ByteArray(HEADER))
                fun drain(flush: Int): Int {
                    deflater.setOutput(output, 0, output.size)
                    val rc = deflater.deflate(flush)
                    if (rc != JZlib.Z_OK && rc != JZlib.Z_STREAM_END) {
                        throw RockstarCloudException("Rockstar cloud save ${plain.name}: deflate failed (${deflater.msg ?: rc})")
                    }
                    stream.write(output, 0, output.size - deflater.avail_out)
                    return rc
                }
                plain.inputStream().use { source ->
                    while (true) {
                        val n = source.read(input)
                        if (n < 0) break
                        size += n
                        if (size > MAX_PLAIN) throw RockstarCloudException("Rockstar cloud save ${plain.name}: size $size cannot be compressed")
                        md5.update(input, 0, n)
                        deflater.setInput(input, 0, n, false)
                        while (deflater.avail_in > 0) drain(JZlib.Z_NO_FLUSH)
                    }
                }
                if (size < 1) throw RockstarCloudException("Rockstar cloud save ${plain.name}: size $size cannot be compressed")
                while (drain(JZlib.Z_FINISH) != JZlib.Z_STREAM_END) Unit
                val digest = md5.digest()
                stream.seek(0)
                stream.write(header(size, digest))
                return Result(size, RockstarCloudApi.hex(digest))
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            deflater.end()
        }
    }

    fun decompress(blob: File, out: File): Result {
        val header = ByteArray(HEADER)
        val inflater = Inflater(true)
        val md5 = MessageDigest.getInstance("MD5")
        var size = 0L
        try {
            blob.inputStream().buffered().use { source ->
                var read = 0
                while (read < HEADER) {
                    val n = source.read(header, read, HEADER - read)
                    if (n < 0) throw RockstarCloudException("Rockstar cloud save ${blob.name}: truncated header")
                    read += n
                }
                val version = le(header, 0, 2)
                val expectedSize = le(header, 2, 4)
                if (version != VERSION.toLong()) {
                    throw RockstarCloudException("Rockstar cloud save ${blob.name}: unsupported container version $version")
                }
                if (expectedSize < 1 || expectedSize > MAX_PLAIN) {
                    throw RockstarCloudException("Rockstar cloud save ${blob.name}: bad plain size $expectedSize")
                }
                val expectedMd5 = header.copyOfRange(6, HEADER)
                val input = ByteArray(BUFFER)
                val output = ByteArray(BUFFER)
                out.outputStream().buffered().use { stream ->
                    while (!inflater.finished()) {
                        if (inflater.needsInput()) {
                            val n = source.read(input)
                            if (n < 0) throw RockstarCloudException("Rockstar cloud save ${blob.name}: truncated deflate stream")
                            inflater.setInput(input, 0, n)
                        }
                        val n = try {
                            inflater.inflate(output)
                        } catch (e: DataFormatException) {
                            throw RockstarCloudException("Rockstar cloud save ${blob.name}: inflate failed (${e.message})", cause = e)
                        }
                        if (n == 0 && !inflater.finished() && !inflater.needsInput()) {
                            throw RockstarCloudException("Rockstar cloud save ${blob.name}: inflate stalled")
                        }
                        size += n
                        if (size > expectedSize) throw RockstarCloudException("Rockstar cloud save ${blob.name}: inflated past the header size")
                        md5.update(output, 0, n)
                        stream.write(output, 0, n)
                    }
                }
                val digest = md5.digest()
                if (size != expectedSize) {
                    throw RockstarCloudException("Rockstar cloud save ${blob.name}: inflated $size bytes, header says $expectedSize")
                }
                if (!digest.contentEquals(expectedMd5)) {
                    throw RockstarCloudException("Rockstar cloud save ${blob.name}: inflated content does not match the header MD5")
                }
                return Result(size, RockstarCloudApi.hex(digest))
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            inflater.end()
        }
    }

    internal fun header(size: Long, md5: ByteArray): ByteArray {
        val header = ByteArray(HEADER)
        header[0] = VERSION.toByte()
        for (i in 0 until 4) header[2 + i] = (size shr (8 * i)).toByte()
        md5.copyInto(header, 6)
        return header
    }

    private fun le(data: ByteArray, offset: Int, bytes: Int): Long =
        (0 until bytes).fold(0L) { acc, i -> acc or ((data[offset + i].toLong() and 0xff) shl (8 * i)) }
}
