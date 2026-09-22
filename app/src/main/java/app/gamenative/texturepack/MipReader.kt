package app.gamenative.texturepack

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.Inflater
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FrameInputStream

class MipReader(private val gameDir: File) : Closeable {

    private class ChunkKey(val file: String, val offset: Long, val length: Int) {
        fun matches(f: String, o: Long, l: Int) = file == f && offset == o && length == l
    }

    private var cachedKey: ChunkKey? = null
    private var cachedChunk: ByteArray? = null
    private var openPath: String? = null
    private var openFile: RandomAccessFile? = null

    fun read(
        file: String,
        offset: Long,
        length: Int,
        codec: String,
        innerOffset: Int?,
        innerLength: Int?,
    ): ByteArray {
        if (codec == TextureCodec.NONE) {
            return readRaw(file, offset, length)
        }
        val cached = cachedChunk
        val chunk = if (cached != null && cachedKey?.matches(file, offset, length) == true) {
            cached
        } else {
            val raw = readRaw(file, offset, length)
            val decoded = decompress(raw, codec, innerOffset, innerLength)
            cachedKey = ChunkKey(file, offset, length)
            cachedChunk = decoded
            decoded
        }
        val start = innerOffset ?: 0
        val size = innerLength ?: (chunk.size - start)
        if (start < 0 || size < 0 || start + size > chunk.size) {
            throw IOException("mip slice [$start, ${start + size}) outside chunk of ${chunk.size} bytes")
        }
        return chunk.copyOfRange(start, start + size)
    }

    private fun readRaw(file: String, offset: Long, length: Int): ByteArray {
        val raf = openFor(file)
        val buffer = ByteArray(length)
        raf.seek(offset)
        raf.readFully(buffer)
        return buffer
    }

    private fun openFor(file: String): RandomAccessFile {
        val current = openFile
        if (current != null && openPath == file) return current
        current?.close()
        val target = File(gameDir, file)
        val opened = RandomAccessFile(target, "r")
        openFile = opened
        openPath = file
        return opened
    }

    private fun decompress(raw: ByteArray, codec: String, innerOffset: Int?, innerLength: Int?): ByteArray {
        val hint = if (innerOffset != null && innerLength != null) innerOffset + innerLength else null
        return when (codec) {
            TextureCodec.ZLIB -> inflate(raw, hint)
            TextureCodec.LZ4_BLOCK -> if (hint != null) lz4Block(raw, hint) else lz4Frame(raw)
            TextureCodec.LZ4_FRAME -> lz4Frame(raw)
            else -> throw IOException("unknown codec $codec")
        }
    }

    private fun inflate(raw: ByteArray, hint: Int?): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(raw)
            var out = ByteArray(hint ?: (raw.size * 4).coerceAtLeast(MIN_GROW))
            var written = 0
            while (!inflater.finished()) {
                if (written == out.size) {
                    if (hint != null) break
                    out = out.copyOf(out.size * 2)
                }
                val n = inflater.inflate(out, written, out.size - written)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                written += n
            }
            return if (written == out.size) out else out.copyOf(written)
        } finally {
            inflater.end()
        }
    }

    private fun lz4Block(raw: ByteArray, decompressedSize: Int): ByteArray {
        val dest = ByteArray(decompressedSize)
        val written = LZ4Factory.fastestJavaInstance()
            .safeDecompressor()
            .decompress(raw, 0, raw.size, dest, 0, decompressedSize)
        return if (written == decompressedSize) dest else dest.copyOf(written)
    }

    private fun lz4Frame(raw: ByteArray): ByteArray =
        LZ4FrameInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }

    override fun close() {
        try {
            openFile?.close()
        } catch (_: IOException) {
        }
        openFile = null
        openPath = null
        cachedKey = null
        cachedChunk = null
    }

    companion object {
        private const val MIN_GROW = 64 * 1024
    }
}
