package app.gamenative.service.ubisoft

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/** Random access to an installer, local or remote. */
interface ByteSource : Closeable {
    val size: Long

    /** Exactly [length] bytes at [position]; throws when the source is shorter. */
    fun read(position: Long, length: Int): ByteArray
}

class FileByteSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun read(position: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        raf.seek(position)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}

/**
 * Reads files out of a non-solid NSIS installer: each file is its own block, a little-endian
 * length word (high bit set when Deflate-compressed) followed by the data, so one file can be read
 * without touching the rest of the payload. Only the opcodes needed to list output paths and file
 * blocks are interpreted. Anything unexpected throws instead of guessing.
 */
class NsisReader(private val source: ByteSource, private val scanLimit: Long = 16L shl 20) {
    data class Entry(val name: String, val outPath: String, val dataOffset: Long) {
        /** Directly in the install folder: one path token for the output folder, none in the name. */
        val isRootLevel: Boolean get() = !outPath.contains('\\') && !outPath.contains('/') &&
            !name.contains('\\') && !name.contains('/')
    }

    class Archive(val firstHeaderOffset: Long, val dataStart: Long, val unicode: Boolean, val entries: List<Entry>)

    fun open(): Archive {
        val fh = findFirstHeader()
        val head = source.read(fh, FIRST_HEADER_SIZE)
        val headerLength = u32(head, 20).toInt()
        check(headerLength in 64..MAX_HEADER) { "Installer header has an unexpected size ($headerLength)" }
        val word = u32(source.read(fh + FIRST_HEADER_SIZE, 4), 0)
        val blockLength = (word and 0x7fffffffL).toInt()
        val raw = source.read(fh + FIRST_HEADER_SIZE + 4, blockLength)
        val header = if (word and 0x80000000L != 0L) inflate(raw, headerLength) else raw
        check(header.size == headerLength) { "Installer header did not unpack to its declared size" }
        val dataStart = fh + FIRST_HEADER_SIZE + 4 + blockLength
        val (unicode, entries) = parseHeader(header)
        return Archive(fh, dataStart, unicode, entries)
    }

    /** The file's bytes, inflated when the block is compressed. */
    fun extract(archive: Archive, entry: Entry, maxSize: Int = MAX_FILE): ByteArray {
        val word = u32(source.read(archive.dataStart + entry.dataOffset, 4), 0)
        val length = (word and 0x7fffffffL).toInt()
        check(length in 0..maxSize) { "${entry.name}: block is too large ($length)" }
        val raw = source.read(archive.dataStart + entry.dataOffset + 4, length)
        return if (word and 0x80000000L != 0L) inflate(raw, maxSize) else raw
    }

    private fun findFirstHeader(): Long {
        val chunk = 64 * 1024
        var base = 0L
        val end = minOf(scanLimit, source.size)
        while (base + FIRST_HEADER_SIZE <= end) {
            val bytes = source.read(base, minOf(chunk.toLong(), source.size - base).toInt())
            var i = 0
            while (i + FIRST_HEADER_SIZE <= bytes.size) {
                if (isFirstHeader(bytes, i)) return base + i
                i += 512
            }
            base += chunk
        }
        error("No NSIS header in the installer")
    }

    private fun isFirstHeader(b: ByteArray, at: Int): Boolean {
        if (u32(b, at + 4) != 0xDEADBEEFL) return false
        for (k in MAGIC.indices) if (b[at + 8 + k] != MAGIC[k]) return false
        return true
    }

    private fun parseHeader(h: ByteArray): Pair<Boolean, List<Entry>> {
        val entriesOffset = u32(h, 4 + 8 * BLOCK_ENTRIES).toInt()
        val entryCount = u32(h, 8 + 8 * BLOCK_ENTRIES).toInt()
        val stringsOffset = u32(h, 4 + 8 * BLOCK_STRINGS).toInt()
        check(entriesOffset in 0 until h.size && entriesOffset.toLong() + entryCount.toLong() * ENTRY_SIZE <= h.size) {
            "Installer header has a malformed entry table"
        }
        check(stringsOffset in 0 until h.size - 1) { "Installer header has a malformed string table" }
        val unicode = h[stringsOffset] == 0.toByte() && h[stringsOffset + 1] == 0.toByte()
        val strings = Strings(h, stringsOffset, unicode)
        val entries = ArrayList<Entry>()
        var outPath = ""
        for (i in 0 until entryCount) {
            val at = entriesOffset + i * ENTRY_SIZE
            when (u32(h, at).toInt()) {
                EW_CREATEDIR -> if (u32(h, at + 8) != 0L) outPath = strings.get(u32(h, at + 4).toInt())
                EW_EXTRACTFILE -> entries.add(Entry(strings.get(u32(h, at + 8).toInt()), outPath, u32(h, at + 12)))
            }
        }
        return unicode to entries
    }

    /** NSIS 3 strings; variables, shell folders and language strings are kept as opaque tokens. */
    private class Strings(private val h: ByteArray, private val base: Int, private val unicode: Boolean) {
        fun get(index: Int): String {
            val out = StringBuilder()
            var p = base + if (unicode) index * 2 else index
            while (p < h.size) {
                val c = if (unicode) u16(h, p).also { p += 2 } else (h[p].toInt() and 0xff).also { p += 1 }
                if (c == 0) return out.toString()
                if (c == CODE_SKIP && !unicode) {
                    out.append((h[p].toInt() and 0xff).toChar())
                    p += 1
                } else if (c in 1..4) {
                    val data = u16(h, p)
                    p += 2
                    when (c) {
                        CODE_SKIP -> out.append(data.toChar())
                        CODE_LANG -> out.append("\$L{").append(data and 0x7fff).append('}')
                        CODE_SHELL -> out.append("\$S{").append(data).append('}')
                        CODE_VAR -> out.append("\$V{").append(data and 0x7fff).append('}')
                    }
                } else {
                    out.append(c.toChar())
                }
            }
            error("Unterminated string in the installer header")
        }
    }

    companion object {
        const val FIRST_HEADER_SIZE = 28
        private val MAGIC = "NullsoftInst".toByteArray(Charsets.US_ASCII)
        private const val BLOCK_ENTRIES = 2
        private const val BLOCK_STRINGS = 3
        private const val ENTRY_SIZE = 28
        private const val EW_CREATEDIR = 11
        private const val EW_EXTRACTFILE = 20
        private const val CODE_LANG = 1
        private const val CODE_SHELL = 2
        private const val CODE_VAR = 3
        private const val CODE_SKIP = 4
        private const val MAX_HEADER = 64 shl 20
        const val MAX_FILE = 64 shl 20

        internal fun u32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xff) or ((b[at + 1].toLong() and 0xff) shl 8) or
                ((b[at + 2].toLong() and 0xff) shl 16) or ((b[at + 3].toLong() and 0xff) shl 24)

        internal fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

        /** Raw Deflate, as NSIS writes it. */
        fun inflate(data: ByteArray, maxSize: Int): ByteArray {
            val inflater = Inflater(true)
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream(minOf(maxOf(data.size * 2, 1024), maxSize))
                val buffer = ByteArray(64 * 1024)
                var fedPad = false
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n > 0) {
                        out.write(buffer, 0, n)
                        check(out.size() <= maxSize) { "Installer entry unpacks past its limit" }
                    } else if (inflater.needsInput()) {
                        // Raw streams can need one byte past the end before they report finished.
                        check(!fedPad) { "Installer entry is truncated" }
                        fedPad = true
                        inflater.setInput(ByteArray(1))
                    } else {
                        error("Installer entry is not plain Deflate")
                    }
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }
}

/** What the runtime check needs from a PE file: its machine and, for a DLL, its export name. */
data class PeImage(val machine: Int, val isDll: Boolean, val exportName: String?) {
    companion object {
        const val MACHINE_X86 = 0x14c
        const val MACHINE_X64 = 0x8664

        fun parse(d: ByteArray): PeImage {
            check(d.size > 0x40 && d[0] == 'M'.code.toByte() && d[1] == 'Z'.code.toByte()) { "not a PE file" }
            val pe = NsisReader.u32(d, 0x3c).toInt()
            check(pe in 0x40..(d.size - 24) && NsisReader.u32(d, pe) == 0x4550L) { "no PE signature" }
            val machine = NsisReader.u16(d, pe + 4)
            val sections = NsisReader.u16(d, pe + 6)
            val optionalSize = NsisReader.u16(d, pe + 20)
            val characteristics = NsisReader.u16(d, pe + 22)
            val opt = pe + 24
            val magic = NsisReader.u16(d, opt)
            val directories = when (magic) {
                0x10b -> opt + 96
                0x20b -> opt + 112
                else -> error("unknown optional header")
            }
            val exportRva = if (directories + 8 <= opt + optionalSize) NsisReader.u32(d, directories) else 0L
            val table = opt + optionalSize
            check(table + sections * 40 <= d.size) { "truncated section table" }
            fun fileOffset(rva: Long): Int? {
                for (i in 0 until sections) {
                    val s = table + i * 40
                    val virtualSize = NsisReader.u32(d, s + 8)
                    val va = NsisReader.u32(d, s + 12)
                    val rawSize = NsisReader.u32(d, s + 16)
                    val rawPtr = NsisReader.u32(d, s + 20)
                    if (rva >= va && rva < va + maxOf(virtualSize, rawSize)) {
                        val off = rva - va + rawPtr
                        return if (off in 0 until d.size) off.toInt() else null
                    }
                }
                return null
            }
            val exportName = if (exportRva == 0L) null else fileOffset(exportRva)?.let { dir ->
                if (dir + 16 > d.size) return@let null
                fileOffset(NsisReader.u32(d, dir + 12))?.let { start ->
                    var end = start
                    while (end < d.size && d[end] != 0.toByte()) end++
                    String(d, start, end - start, Charsets.US_ASCII)
                }
            }
            return PeImage(machine, characteristics and 0x2000 != 0, exportName)
        }
    }
}
