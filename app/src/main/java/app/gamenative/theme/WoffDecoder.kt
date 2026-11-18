package app.gamenative.theme

import app.gamenative.theme.Sfnt.pad4
import app.gamenative.theme.Sfnt.u16
import app.gamenative.theme.Sfnt.u32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Decodes WOFF 1.0 web fonts to the TrueType / OpenType file they wrap. Pure JVM. */
object WoffDecoder {

    const val MAX_SFNT_BYTES = Sfnt.MAX_BYTES
    private const val MAX_TABLES = 4096
    private const val WOFF_SIGNATURE = 0x774F4646
    private const val WOFF_HEADER = 44
    private const val WOFF_ENTRY = 20

    private class Table(val tag: Int, val offset: Int, val compLength: Int, val origLength: Int)

    /** True when [sfnt] holds CFF outlines (an .otf), false for TrueType outlines (a .ttf). */
    fun isCff(sfnt: ByteArray): Boolean = sfnt.size >= 4 && u32(sfnt, 0) == Sfnt.FLAVOR_CFF.toLong()

    /** The sfnt font inside [woff], or null when it is not a well-formed WOFF 1.0 file. */
    fun decode(woff: ByteArray): ByteArray? = try {
        decodeOrNull(woff)
    } catch (e: RuntimeException) {
        null
    }

    private fun decodeOrNull(woff: ByteArray): ByteArray? {
        if (woff.size < WOFF_HEADER || u32(woff, 0) != WOFF_SIGNATURE.toLong()) return null
        val flavor = u32(woff, 4).toInt()
        if (!Sfnt.isFlavor(flavor)) return null
        val length = u32(woff, 8)
        val numTables = u16(woff, 12)
        if (u16(woff, 14) != 0) return null
        if (length > woff.size || numTables == 0 || numTables > MAX_TABLES) return null
        val dirEnd = WOFF_HEADER.toLong() + numTables.toLong() * WOFF_ENTRY
        if (dirEnd > length) return null

        val tables = ArrayList<Table>(numTables)
        val tags = HashSet<Int>()
        var outSize = Sfnt.HEADER.toLong() + numTables.toLong() * Sfnt.ENTRY
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
        var pos = Sfnt.writeHeader(out, flavor, numTables)
        var headAt = -1
        tables.forEachIndexed { i, t ->
            if (t.compLength == t.origLength) {
                System.arraycopy(woff, t.offset, out, pos, t.origLength)
            } else if (!inflate(woff, t.offset, t.compLength, out, pos, t.origLength)) {
                return null
            }
            if (t.tag == Sfnt.TAG_HEAD) {
                if (t.origLength < 12) return null
                headAt = pos
            }
            pos = Sfnt.record(out, i, t.tag, pos, t.origLength)
        }
        Sfnt.finish(out, headAt)
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
}
