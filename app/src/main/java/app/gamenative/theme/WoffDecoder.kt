package app.gamenative.theme

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Decodes WOFF 1.0 web fonts to the TrueType / OpenType file they wrap. Pure JVM. */
object WoffDecoder {

    const val MAX_SFNT_BYTES = 32 * 1024 * 1024
    private const val MAX_TABLES = 4096
    private const val WOFF_SIGNATURE = 0x774F4646
    private const val WOFF_HEADER = 44
    private const val WOFF_ENTRY = 20
    private const val SFNT_HEADER = 12
    private const val SFNT_ENTRY = 16
    private const val FLAVOR_TRUETYPE = 0x00010000
    private const val FLAVOR_CFF = 0x4F54544F
    private const val FLAVOR_APPLE = 0x74727565
    private const val TAG_HEAD = 0x68656164
    private const val CHECKSUM_MAGIC = 0xB1B0AFBAL

    private class Table(val tag: Int, val offset: Int, val compLength: Int, val origLength: Int)

    /** True when [sfnt] holds CFF outlines (an .otf), false for TrueType outlines (a .ttf). */
    fun isCff(sfnt: ByteArray): Boolean = sfnt.size >= 4 && u32(sfnt, 0) == FLAVOR_CFF.toLong()

    /** The sfnt font inside [woff], or null when it is not a well-formed WOFF 1.0 file. */
    fun decode(woff: ByteArray): ByteArray? = try {
        decodeOrNull(woff)
    } catch (e: RuntimeException) {
        null
    }

    private fun decodeOrNull(woff: ByteArray): ByteArray? {
        if (woff.size < WOFF_HEADER || u32(woff, 0) != WOFF_SIGNATURE.toLong()) return null
        val flavor = u32(woff, 4).toInt()
        if (flavor != FLAVOR_TRUETYPE && flavor != FLAVOR_CFF && flavor != FLAVOR_APPLE) return null
        val length = u32(woff, 8)
        val numTables = u16(woff, 12)
        if (u16(woff, 14) != 0) return null
        if (length > woff.size || numTables == 0 || numTables > MAX_TABLES) return null
        val dirEnd = WOFF_HEADER.toLong() + numTables.toLong() * WOFF_ENTRY
        if (dirEnd > length) return null

        val tables = ArrayList<Table>(numTables)
        val tags = HashSet<Int>()
        var outSize = SFNT_HEADER.toLong() + numTables.toLong() * SFNT_ENTRY
        for (i in 0 until numTables) {
            val e = WOFF_HEADER + i * WOFF_ENTRY
            val tag = u32(woff, e).toInt()
            val offset = u32(woff, e + 4)
            val compLength = u32(woff, e + 8)
            val origLength = u32(woff, e + 12)
            if (!tags.add(tag)) return null
            if (offset < dirEnd || offset + compLength > length) return null
            if (compLength > origLength || origLength > MAX_SFNT_BYTES) return null
            outSize += pad4(origLength)
            if (outSize > MAX_SFNT_BYTES) return null
            tables.add(Table(tag, offset.toInt(), compLength.toInt(), origLength.toInt()))
        }
        tables.sortBy { it.tag.toLong() and 0xffffffffL }

        val out = ByteArray(outSize.toInt())
        put32(out, 0, flavor)
        put16(out, 4, numTables)
        var pow = 1
        var log = 0
        while (pow * 2 <= numTables) {
            pow *= 2
            log++
        }
        put16(out, 6, pow * SFNT_ENTRY)
        put16(out, 8, log)
        put16(out, 10, numTables * SFNT_ENTRY - pow * SFNT_ENTRY)

        var pos = SFNT_HEADER + numTables * SFNT_ENTRY
        var headAt = -1
        tables.forEachIndexed { i, t ->
            if (t.compLength == t.origLength) {
                System.arraycopy(woff, t.offset, out, pos, t.origLength)
            } else if (!inflate(woff, t.offset, t.compLength, out, pos, t.origLength)) {
                return null
            }
            if (t.tag == TAG_HEAD) {
                if (t.origLength < 12) return null
                headAt = pos
                put32(out, pos + 8, 0)
            }
            val rec = SFNT_HEADER + i * SFNT_ENTRY
            put32(out, rec, t.tag)
            put32(out, rec + 4, checksum(out, pos, pad4(t.origLength.toLong()).toInt()).toInt())
            put32(out, rec + 8, pos)
            put32(out, rec + 12, t.origLength)
            pos += pad4(t.origLength.toLong()).toInt()
        }
        if (headAt >= 0) put32(out, headAt + 8, ((CHECKSUM_MAGIC - checksum(out, 0, out.size)) and 0xffffffffL).toInt())
        return out
    }

    private fun inflate(src: ByteArray, off: Int, len: Int, dest: ByteArray, at: Int, expected: Int): Boolean {
        val inflater = Inflater()
        try {
            inflater.setInput(src, off, len)
            var n = 0
            while (n < expected) {
                val r = inflater.inflate(dest, at + n, expected - n)
                if (r == 0) break
                n += r
            }
            if (n != expected) return false
            // a full output buffer can stop zlib before it reads the stream trailer
            return inflater.finished() || (inflater.inflate(ByteArray(1)) == 0 && inflater.finished())
        } catch (e: DataFormatException) {
            return false
        } finally {
            inflater.end()
        }
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Long {
        var sum = 0L
        var i = off
        val end = off + len
        while (i < end) {
            var word = 0L
            for (k in 0 until 4) word = (word shl 8) or (if (i + k < end) (b[i + k].toLong() and 0xff) else 0L)
            sum = (sum + word) and 0xffffffffL
            i += 4
        }
        return sum
    }

    private fun pad4(n: Long): Long = (n + 3) and 3L.inv()

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
            ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte()
        b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte()
        b[i + 3] = v.toByte()
    }
}
