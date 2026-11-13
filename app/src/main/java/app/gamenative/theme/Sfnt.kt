package app.gamenative.theme

/** Writes TrueType / OpenType (sfnt) files from the tables of a decoded web font. Pure JVM. */
internal object Sfnt {

    const val MAX_BYTES = 32 * 1024 * 1024
    const val FLAVOR_TRUETYPE = 0x00010000
    const val FLAVOR_CFF = 0x4F54544F
    const val FLAVOR_APPLE = 0x74727565
    const val TAG_HEAD = 0x68656164
    const val HEADER = 12
    const val ENTRY = 16
    private const val CHECKSUM_MAGIC = 0xB1B0AFBAL

    /** [length] bytes of table [tag] at [offset] of [data]. */
    class Table(val tag: Int, val data: ByteArray, val offset: Int, val length: Int)

    fun isFlavor(flavor: Int): Boolean = flavor == FLAVOR_TRUETYPE || flavor == FLAVOR_CFF || flavor == FLAVOR_APPLE

    /** The sfnt holding [tables], or null when it would be larger than [MAX_BYTES] or its `head` is too short. */
    fun assemble(flavor: Int, tables: List<Table>): ByteArray? {
        val sorted = tables.sortedBy { it.tag.toLong() and 0xffffffffL }
        var size = HEADER.toLong() + sorted.size.toLong() * ENTRY
        for (t in sorted) {
            if (t.tag == TAG_HEAD && t.length < 12) return null
            size += pad4(t.length.toLong())
            if (size > MAX_BYTES) return null
        }
        val out = ByteArray(size.toInt())
        var pos = writeHeader(out, flavor, sorted.size)
        var headAt = -1
        sorted.forEachIndexed { i, t ->
            System.arraycopy(t.data, t.offset, out, pos, t.length)
            if (t.tag == TAG_HEAD) headAt = pos
            pos = record(out, i, t.tag, pos, t.length)
        }
        finish(out, headAt)
        return out
    }

    /** Writes the sfnt header for [numTables] tables; returns where the first table goes. */
    fun writeHeader(out: ByteArray, flavor: Int, numTables: Int): Int {
        put32(out, 0, flavor)
        put16(out, 4, numTables)
        var pow = 1
        var log = 0
        while (pow * 2 <= numTables) {
            pow *= 2
            log++
        }
        put16(out, 6, pow * ENTRY)
        put16(out, 8, log)
        put16(out, 10, numTables * ENTRY - pow * ENTRY)
        return HEADER + numTables * ENTRY
    }

    /** Writes the record of table number [index], whose [length] bytes are at [pos]; returns where the next table goes. */
    fun record(out: ByteArray, index: Int, tag: Int, pos: Int, length: Int): Int {
        if (tag == TAG_HEAD) put32(out, pos + 8, 0)
        val padded = pad4(length.toLong()).toInt()
        val rec = HEADER + index * ENTRY
        put32(out, rec, tag)
        put32(out, rec + 4, checksum(out, pos, padded).toInt())
        put32(out, rec + 8, pos)
        put32(out, rec + 12, length)
        return pos + padded
    }

    /** Sets `head.checkSumAdjustment` once every table is recorded; [headAt] is where `head` is, or -1. */
    fun finish(out: ByteArray, headAt: Int) {
        if (headAt >= 0) put32(out, headAt + 8, ((CHECKSUM_MAGIC - checksum(out, 0, out.size)) and 0xffffffffL).toInt())
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

    fun pad4(n: Long): Long = (n + 3) and 3L.inv()

    fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
            ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)

    fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }

    fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte()
        b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte()
        b[i + 3] = v.toByte()
    }
}
