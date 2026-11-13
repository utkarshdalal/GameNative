package app.gamenative.theme

import java.nio.ByteBuffer
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds small WOFF 1.0 files for tests. */
object TestWoff {

    val TABLES: List<Pair<String, ByteArray>> = listOf(
        "name" to ByteArray(40) { (it % 5).toByte() },
        "head" to ByteArray(54) { (it * 7).toByte() }.also {
            ByteBuffer.wrap(it).putInt(8, 0x12345678).putInt(12, 0x5F0F3CF5)
        },
        "OS/2" to ByteArray(10) { (100 + it).toByte() },
        "cmap" to byteArrayOf(1, 2, 3, 4, 5, 6, 7),
    )

    fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(9)
        d.setInput(b)
        d.finish()
        val out = ByteArray(b.size + 64)
        val n = d.deflate(out)
        d.end()
        return out.copyOf(n)
    }

    private fun pad4(n: Int) = (n + 3) and 3.inv()

    /** WOFF of [tables] in the given directory order; [stored] tables are written uncompressed. */
    fun woff(
        tables: List<Pair<String, ByteArray>> = TABLES,
        flavor: Int = 0x00010000,
        stored: Set<String> = setOf("OS/2"),
    ): ByteArray {
        val bodies = tables.map { (tag, data) -> if (tag in stored) data else deflate(data).takeIf { it.size < data.size } ?: data }
        val dirEnd = 44 + 20 * tables.size
        val total = dirEnd + bodies.sumOf { pad4(it.size) }
        val buf = ByteBuffer.allocate(total)
        buf.putInt(0x774F4646).putInt(flavor).putInt(total).putShort(tables.size.toShort()).putShort(0)
        buf.putInt(12 + 16 * tables.size + tables.sumOf { pad4(it.second.size) }).putShort(1).putShort(0)
        repeat(5) { buf.putInt(0) }
        var off = dirEnd
        tables.forEachIndexed { i, (tag, data) ->
            buf.put(tag.toByteArray(Charsets.US_ASCII)).putInt(off).putInt(bodies[i].size).putInt(data.size).putInt(0)
            off += pad4(bodies[i].size)
        }
        off = dirEnd
        for (b in bodies) {
            buf.position(off)
            buf.put(b)
            off += pad4(b.size)
        }
        return buf.array()
    }
}

class WoffDecoderTest {

    private fun checksum(b: ByteArray, off: Int, len: Int): Long {
        val bb = ByteBuffer.wrap(b)
        var sum = 0L
        var i = off
        while (i < off + len) {
            sum = (sum + (bb.getInt(i).toLong() and 0xffffffffL)) and 0xffffffffL
            i += 4
        }
        return sum
    }

    @Test
    fun decodesToAValidSfnt() {
        val sfnt = WoffDecoder.decode(TestWoff.woff())
        assertNotNull(sfnt)
        val b = ByteBuffer.wrap(sfnt!!)
        assertEquals(0x00010000, b.getInt(0))
        assertEquals(4, b.getShort(4).toInt())
        assertEquals("searchRange", 64, b.getShort(6).toInt())
        assertEquals("entrySelector", 2, b.getShort(8).toInt())
        assertEquals("rangeShift", 0, b.getShort(10).toInt())
        assertFalse(WoffDecoder.isCff(sfnt))

        val tags = (0 until 4).map { String(sfnt, 12 + it * 16, 4, Charsets.US_ASCII) }
        assertEquals("records are sorted by tag", listOf("OS/2", "cmap", "head", "name"), tags)
        val source = TestWoff.TABLES.toMap()
        var end = 12 + 4 * 16
        tags.forEachIndexed { i, tag ->
            val rec = 12 + i * 16
            val offset = b.getInt(rec + 8)
            val length = b.getInt(rec + 12)
            assertEquals(0, offset % 4)
            assertEquals("tables follow each other padded to 4 bytes", end, offset)
            assertEquals(source.getValue(tag).size, length)
            val data = sfnt.copyOfRange(offset, offset + length)
            if (tag == "head") {
                assertArrayEquals(source.getValue(tag).copyOfRange(12, 54), data.copyOfRange(12, 54))
                val zeroed = data.copyOf().also { ByteBuffer.wrap(it).putInt(8, 0) }
                val padded = zeroed.copyOf((length + 3) and 3.inv())
                assertEquals(checksum(padded, 0, padded.size), b.getInt(rec + 4).toLong() and 0xffffffffL)
            } else {
                assertArrayEquals(source.getValue(tag), data)
                assertEquals(checksum(sfnt, offset, (length + 3) and 3.inv()), b.getInt(rec + 4).toLong() and 0xffffffffL)
            }
            end = offset + ((length + 3) and 3.inv())
        }
        assertEquals(end, sfnt.size)
        assertEquals("checkSumAdjustment makes the font sum to the magic", 0xB1B0AFBAL, checksum(sfnt, 0, sfnt.size))
    }

    @Test
    fun cffFlavorIsKept() {
        val sfnt = WoffDecoder.decode(TestWoff.woff(flavor = 0x4F54544F))!!
        assertTrue(WoffDecoder.isCff(sfnt))
    }

    @Test
    fun malformedFilesAreRejected() {
        val good = TestWoff.woff()
        fun edited(edit: ByteBuffer.() -> Unit) = good.copyOf().also { ByteBuffer.wrap(it).edit() }
        val nameEntry = 44
        assertNull("empty", WoffDecoder.decode(ByteArray(0)))
        assertNull("not woff", WoffDecoder.decode(ByteArray(64)))
        assertNull("truncated", WoffDecoder.decode(good.copyOf(good.size - 8)))
        assertNull("truncated directory", WoffDecoder.decode(good.copyOf(50)))
        assertNull("unknown flavor", WoffDecoder.decode(edited { putInt(4, 0x41424344) }))
        assertNull("reserved set", WoffDecoder.decode(edited { putShort(14, 1) }))
        assertNull("no tables", WoffDecoder.decode(edited { putShort(12, 0) }))
        assertNull("too many tables for the file", WoffDecoder.decode(edited { putShort(12, 0x7fff) }))
        assertNull("table past the end", WoffDecoder.decode(edited { putInt(nameEntry + 4, good.size - 2) }))
        assertNull("table inside the header", WoffDecoder.decode(edited { putInt(nameEntry + 4, 8) }))
        assertNull("negative offset", WoffDecoder.decode(edited { putInt(nameEntry + 4, -4) }))
        assertNull("compressed longer than original", WoffDecoder.decode(edited { putInt(nameEntry + 12, 2) }))
        assertNull("original over the cap", WoffDecoder.decode(edited { putInt(nameEntry + 12, WoffDecoder.MAX_SFNT_BYTES + 1) }))
        assertNull("inflates to fewer bytes", WoffDecoder.decode(edited { putInt(nameEntry + 12, 41) }))
        assertNull("duplicate tag", WoffDecoder.decode(edited { putInt(nameEntry + 20, getInt(nameEntry)) }))
        val nameAt = ByteBuffer.wrap(good).getInt(nameEntry + 4)
        assertNull("corrupt zlib", WoffDecoder.decode(edited { put(nameAt, 0x00) }))
    }

    @Test
    fun randomBytesNeverThrow() {
        val rnd = java.util.Random(7)
        val good = TestWoff.woff()
        repeat(2000) {
            val b = good.copyOf()
            repeat(1 + rnd.nextInt(4)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            WoffDecoder.decode(b)
        }
    }
}
