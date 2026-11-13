package app.gamenative.theme

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds small WOFF 2.0 files for tests; the Brotli stream is written as stored meta-blocks, so no encoder is needed. */
object TestWoff2 {

    private val KNOWN = mapOf(
        "cmap" to 0, "head" to 1, "hhea" to 2, "hmtx" to 3, "maxp" to 4, "name" to 5, "OS/2" to 6, "post" to 7, "glyf" to 10,
        "loca" to 11, "CFF " to 13, "Zapf" to 57, "Sill" to 62,
    )

    /** A table as stored in the WOFF2 stream; [transformLength] is written when not null. */
    class Entry(
        val tag: String,
        val data: ByteArray,
        val version: Int = 0,
        val origLength: Int = data.size,
        val transformLength: Int? = null,
        val knownIndex: Boolean = true,
    )

    class Pt(val x: Int, val y: Int, val on: Boolean = true)

    sealed class Glyph
    object Empty : Glyph()
    class Simple(
        val contours: List<List<Pt>>,
        val instructions: ByteArray = ByteArray(0),
        val bbox: IntArray? = null,
        val overlap: Boolean = false,
    ) : Glyph()
    class Composite(val components: ByteArray, val bbox: IntArray, val instructions: ByteArray? = null) : Glyph()

    fun brotliStored(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var bits = 0L
        var n = 0
        fun put(v: Int, count: Int) {
            bits = bits or (v.toLong() shl n)
            n += count
        }
        fun flush() {
            while (n > 0) {
                out.write((bits and 0xff).toInt())
                bits = bits ushr 8
                n = maxOf(0, n - 8)
            }
            bits = 0
            n = 0
        }
        put(0, 1)
        var at = 0
        while (at < data.size) {
            val len = minOf(65536, data.size - at)
            put(0, 1)
            put(0, 2)
            put(len - 1, 16)
            put(1, 1)
            flush()
            out.write(data, at, len)
            at += len
        }
        put(1, 1)
        put(1, 1)
        flush()
        return out.toByteArray()
    }

    fun base128(v: Long): ByteArray {
        val groups = ArrayList<Int>()
        var x = v
        do {
            groups.add(0, (x and 0x7f).toInt())
            x = x ushr 7
        } while (x != 0L)
        return ByteArray(groups.size) { i -> (groups[i] or if (i < groups.size - 1) 0x80 else 0).toByte() }
    }

    fun u255(v: Int): ByteArray = when {
        v < 253 -> byteArrayOf(v.toByte())
        v < 506 -> byteArrayOf(255.toByte(), (v - 253).toByte())
        v < 759 -> byteArrayOf(254.toByte(), (v - 506).toByte())
        else -> byteArrayOf(253.toByte(), (v ushr 8).toByte(), v.toByte())
    }

    fun directory(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            val idx = KNOWN[e.tag]?.takeIf { e.knownIndex } ?: 63
            out.write(idx or (e.version shl 6))
            if (idx == 63) out.write(e.tag.toByteArray(Charsets.ISO_8859_1))
            out.write(base128(e.origLength.toLong()))
            e.transformLength?.let { out.write(base128(it.toLong())) }
        }
        return out.toByteArray()
    }

    /** A WOFF2 file of [entries]; [stream] replaces the Brotli data built from the entries. */
    fun woff2(entries: List<Entry>, flavor: Int = 0x00010000, stream: ByteArray? = null): ByteArray {
        val dir = directory(entries)
        val compressed = stream ?: brotliStored(entries.fold(ByteArray(0)) { acc, e -> acc + e.data })
        val total = 48 + dir.size + compressed.size
        val buf = ByteBuffer.allocate((total + 3) and 3.inv())
        buf.putInt(0x774F4632).putInt(flavor).putInt(buf.capacity()).putShort(entries.size.toShort()).putShort(0)
        buf.putInt(0).putInt(compressed.size).putShort(1).putShort(0)
        repeat(5) { buf.putInt(0) }
        buf.put(dir).put(compressed)
        return buf.array()
    }

    private fun sign(v: Int, bit: Int) = if (v < 0) 0 else bit

    private fun triplet(dx: Int, dy: Int, on: Boolean, flags: ByteArrayOutputStream, data: ByteArrayOutputStream) {
        val onBit = if (on) 0 else 128
        val ax = Math.abs(dx)
        val ay = Math.abs(dy)
        val xy = sign(dx, 1) + sign(dy, 2)
        when {
            dx == 0 && ay < 1280 -> {
                flags.write(onBit + ((ay and 0xf00) shr 7) + sign(dy, 1))
                data.write(ay and 0xff)
            }
            dy == 0 && ax < 1280 -> {
                flags.write(onBit + 10 + ((ax and 0xf00) shr 7) + sign(dx, 1))
                data.write(ax and 0xff)
            }
            ax < 65 && ay < 65 -> {
                flags.write(onBit + 20 + ((ax - 1) and 0x30) + (((ay - 1) and 0x30) shr 2) + xy)
                data.write((((ax - 1) and 0xf) shl 4) or ((ay - 1) and 0xf))
            }
            ax < 769 && ay < 769 -> {
                flags.write(onBit + 84 + 12 * (((ax - 1) and 0x300) shr 8) + (((ay - 1) and 0x300) shr 6) + xy)
                data.write((ax - 1) and 0xff)
                data.write((ay - 1) and 0xff)
            }
            ax < 4096 && ay < 4096 -> {
                flags.write(onBit + 120 + xy)
                data.write(ax shr 4)
                data.write(((ax and 0xf) shl 4) or (ay shr 8))
                data.write(ay and 0xff)
            }
            else -> {
                flags.write(onBit + 124 + xy)
                data.write(ax shr 8)
                data.write(ax and 0xff)
                data.write(ay shr 8)
                data.write(ay and 0xff)
            }
        }
    }

    private fun ByteArrayOutputStream.s16(v: Int) {
        write(v ushr 8 and 0xff)
        write(v and 0xff)
    }

    /** The transformed (version 0) `glyf` table of [glyphs]. */
    fun glyf(glyphs: List<Glyph>, indexFormat: Int = 0, overlapBitmap: Boolean = glyphs.any { it is Simple && it.overlap }): ByteArray {
        val nContours = ByteArrayOutputStream()
        val nPoints = ByteArrayOutputStream()
        val flags = ByteArrayOutputStream()
        val glyphStream = ByteArrayOutputStream()
        val composites = ByteArrayOutputStream()
        val bboxes = ByteArrayOutputStream()
        val instructions = ByteArrayOutputStream()
        val bitmap = ByteArray(((glyphs.size + 31) / 32) * 4)
        val overlap = ByteArray((glyphs.size + 7) / 8)
        glyphs.forEachIndexed { i, g ->
            when (g) {
                Empty -> nContours.s16(0)
                is Simple -> {
                    nContours.s16(g.contours.size)
                    var x = 0
                    var y = 0
                    for (c in g.contours) {
                        nPoints.write(u255(c.size))
                        for (p in c) {
                            triplet(p.x - x, p.y - y, p.on, flags, glyphStream)
                            x = p.x
                            y = p.y
                        }
                    }
                    glyphStream.write(u255(g.instructions.size))
                    instructions.write(g.instructions)
                    g.bbox?.let { b ->
                        bitmap[i shr 3] = (bitmap[i shr 3].toInt() or (0x80 ushr (i and 7))).toByte()
                        b.forEach { bboxes.s16(it) }
                    }
                    if (g.overlap) overlap[i shr 3] = (overlap[i shr 3].toInt() or (0x80 ushr (i and 7))).toByte()
                }
                is Composite -> {
                    nContours.s16(-1)
                    composites.write(g.components)
                    g.instructions?.let {
                        glyphStream.write(u255(it.size))
                        instructions.write(it)
                    }
                    bitmap[i shr 3] = (bitmap[i shr 3].toInt() or (0x80 ushr (i and 7))).toByte()
                    g.bbox.forEach { bboxes.s16(it) }
                }
            }
        }
        val bbox = bitmap + bboxes.toByteArray()
        val streams = listOf(nContours, nPoints, flags, glyphStream, composites).map { it.toByteArray() } +
            listOf(bbox, instructions.toByteArray())
        val buf = ByteBuffer.allocate(36 + streams.sumOf { it.size } + if (overlapBitmap) overlap.size else 0)
        buf.putShort(0).putShort((if (overlapBitmap) 1 else 0).toShort()).putShort(glyphs.size.toShort()).putShort(indexFormat.toShort())
        streams.forEach { buf.putInt(it.size) }
        streams.forEach { buf.put(it) }
        if (overlapBitmap) buf.put(overlap)
        return buf.array()
    }

    fun head(): ByteArray = ByteArray(54) { (it * 3).toByte() }.also {
        ByteBuffer.wrap(it).putInt(8, 0x11223344).putInt(12, 0x5F0F3CF5).putShort(50, 7)
    }

    fun hhea(numHMetrics: Int): ByteArray = ByteArray(36).also { ByteBuffer.wrap(it).putShort(34, numHMetrics.toShort()) }

    /** Components: glyph 0 with word offsets and a scale, then glyph 4 with byte offsets, a 2x2 matrix and instructions. */
    val COMPONENTS: ByteArray = ByteBuffer.allocate(24)
        .putShort(0x0029).putShort(0).putShort(100).putShort(-50).putShort(0x4000)
        .putShort(0x0180).putShort(4).put(5).put(-6).putShort(0x4000).putShort(0).putShort(0).putShort(0x4000)
        .array()

    val GLYPHS: List<Glyph> = listOf(
        Simple(
            listOf(
                listOf(Pt(0, 0), Pt(0, 700), Pt(300, 700, on = false), Pt(300, 0), Pt(-20, 0)),
                listOf(Pt(-20, 37), Pt(40, 100, on = false), Pt(300, 600), Pt(-400, 60), Pt(800, 900), Pt(4000, -3000), Pt(-9000, 20000, on = false), Pt(-9000, 20000)),
            ),
            instructions = byteArrayOf(1, 2, 3),
        ),
        Empty,
        Composite(COMPONENTS, intArrayOf(-30, -40, 900, 950), instructions = byteArrayOf(9, 8)),
        Simple(listOf(listOf(Pt(5, 5), Pt(50, 5), Pt(50, 50))), bbox = intArrayOf(-100, -200, 300, 400), overlap = true),
        Simple(listOf(listOf(Pt(10, 10), Pt(20, 10), Pt(30, 10), Pt(40, 10)))),
    )

    fun font(
        glyphs: List<Glyph> = GLYPHS,
        indexFormat: Int = 0,
        hmtx: ByteArray? = null,
        numHMetrics: Int = 3,
        extra: List<Entry> = emptyList(),
    ): ByteArray {
        val g = glyf(glyphs, indexFormat)
        val entries = ArrayList<Entry>()
        entries.add(Entry("head", head()))
        entries.add(Entry("hhea", hhea(numHMetrics)))
        entries.add(Entry("glyf", g, version = 0, origLength = 4096, transformLength = g.size))
        entries.add(Entry("loca", ByteArray(0), version = 0, origLength = (glyphs.size + 1) * 2, transformLength = 0))
        if (hmtx != null) entries.add(Entry("hmtx", hmtx, version = 1, origLength = 4 * numHMetrics + 2 * (glyphs.size - numHMetrics), transformLength = hmtx.size))
        entries.addAll(extra)
        return woff2(entries)
    }
}

class Woff2DecoderTest {

    private class Parsed(val tables: Map<String, ByteArray>, val flavor: Int)

    private fun parse(sfnt: ByteArray): Parsed {
        val bb = ByteBuffer.wrap(sfnt)
        val n = bb.getShort(4).toInt()
        val tables = LinkedHashMap<String, ByteArray>()
        var lastTag = -1L
        var total = 0L
        var i = 0
        while (i < sfnt.size) {
            total = (total + (bb.getInt(i).toLong() and 0xffffffffL)) and 0xffffffffL
            i += 4
        }
        assertEquals("whole-file checksum", 0xB1B0AFBAL, total)
        for (k in 0 until n) {
            val rec = 12 + 16 * k
            val tag = bb.getInt(rec).toLong() and 0xffffffffL
            assertTrue("records sorted by tag", tag > lastTag)
            lastTag = tag
            val off = bb.getInt(rec + 8)
            val len = bb.getInt(rec + 12)
            assertEquals("tables are 4-byte aligned", 0, off % 4)
            val data = sfnt.copyOfRange(off, off + len)
            val padded = sfnt.copyOfRange(off, off + ((len + 3) and 3.inv())).also { if (tag == 0x68656164L) ByteBuffer.wrap(it).putInt(8, 0) }
            var sum = 0L
            var j = 0
            while (j < padded.size) {
                sum = (sum + (ByteBuffer.wrap(padded).getInt(j).toLong() and 0xffffffffL)) and 0xffffffffL
                j += 4
            }
            assertEquals("checksum of table $k", bb.getInt(rec + 4).toLong() and 0xffffffffL, sum)
            tables[String(sfnt, rec, 4, Charsets.ISO_8859_1)] = data
        }
        return Parsed(tables, bb.getInt(0))
    }

    private fun glyphs(t: Map<String, ByteArray>): List<ByteArray> {
        val long = ByteBuffer.wrap(t.getValue("head")).getShort(50).toInt() == 1
        val loca = ByteBuffer.wrap(t.getValue("loca"))
        val n = t.getValue("loca").size / (if (long) 4 else 2) - 1
        val glyf = t.getValue("glyf")
        return (0 until n).map { i ->
            val a = if (long) loca.getInt(i * 4) else (loca.getShort(i * 2).toInt() and 0xffff) * 2
            val b = if (long) loca.getInt(i * 4 + 4) else (loca.getShort(i * 2 + 2).toInt() and 0xffff) * 2
            assertEquals("glyphs are 4-byte aligned", 0, a % 4)
            glyf.copyOfRange(a, b)
        }
    }

    private class SimpleGlyph(val bbox: List<Int>, val endPts: List<Int>, val instructions: List<Byte>, val points: List<Triple<Int, Int, Boolean>>, val firstFlag: Int)

    /** Reads a simple glyph the way a TrueType rasterizer does. */
    private fun readSimple(g: ByteArray): SimpleGlyph {
        val b = ByteBuffer.wrap(g)
        val contours = b.short.toInt()
        val bbox = List(4) { b.short.toInt() }
        val endPts = List(contours) { b.short.toInt() and 0xffff }
        val instructions = ByteArray(b.short.toInt() and 0xffff).also { b.get(it) }.toList()
        val n = if (contours == 0) 0 else endPts.last() + 1
        val flags = IntArray(n)
        var k = 0
        while (k < n) {
            val f = b.get().toInt() and 0xff
            flags[k++] = f
            if (f and 8 != 0) repeat(b.get().toInt() and 0xff) { flags[k++] = f }
        }
        fun coords(short: Int, same: Int): List<Int> {
            var v = 0
            return flags.map { f ->
                v += when {
                    f and short != 0 -> (b.get().toInt() and 0xff).let { if (f and same != 0) it else -it }
                    f and same != 0 -> 0
                    else -> b.short.toInt()
                }
                v
            }
        }
        val xs = coords(2, 0x10)
        val ys = coords(4, 0x20)
        assertFalse("no trailing bytes", b.hasRemaining() && g.size - b.position() >= 4)
        return SimpleGlyph(bbox, endPts, instructions, List(n) { Triple(xs[it], ys[it], flags[it] and 1 != 0) }, flags.firstOrNull() ?: 0)
    }

    private fun decoded(woff2: ByteArray = TestWoff2.font()): Parsed = parse(Woff2Decoder.decode(woff2)!!)

    @Test
    fun simpleGlyphsAreRebuiltPointForPoint() {
        val t = decoded().tables
        val src = TestWoff2.GLYPHS[0] as TestWoff2.Simple
        val g = readSimple(glyphs(t)[0])
        val pts = src.contours.flatten()
        assertEquals(pts.map { Triple(it.x, it.y, it.on) }, g.points)
        assertEquals(listOf(4, 12), g.endPts)
        assertEquals(listOf<Byte>(1, 2, 3), g.instructions)
        assertEquals("bbox computed from the points", listOf(-9000, -3000, 4000, 20000), g.bbox)
        assertEquals(0, g.firstFlag and 0x40)
    }

    @Test
    fun simpleGlyphBytesMatchTheReferenceEncoding() {
        val expected = ByteBuffer.allocate(24)
            .putShort(1).putShort(10).putShort(10).putShort(40).putShort(10)
            .putShort(3).putShort(0)
            .put(0x37).put(0x3B).put(2)
            .put(10).put(10).put(10).put(10)
            .put(10)
            .array()
        val glyph = glyphs(decoded().tables)[4]
        assertArrayEquals(expected.copyOf(22), glyph.copyOf(22))
        assertEquals("padded to 4 bytes", 24, glyph.size)
    }

    @Test
    fun explicitBboxAndOverlapFlagAreKept() {
        val g = readSimple(glyphs(decoded().tables)[3])
        assertEquals(listOf(-100, -200, 300, 400), g.bbox)
        assertEquals(0x40, g.firstFlag and 0x40)
        assertEquals(listOf(Triple(5, 5, true), Triple(50, 5, true), Triple(50, 50, true)), g.points)
    }

    @Test
    fun compositeAndEmptyGlyphs() {
        val all = glyphs(decoded().tables)
        assertEquals("empty glyph", 0, all[1].size)
        val expected = ByteBuffer.allocate(40)
            .putShort(-1).putShort(-30).putShort(-40).putShort(900).putShort(950)
            .put(TestWoff2.COMPONENTS).putShort(2).put(9).put(8)
            .array()
        assertArrayEquals(expected, all[2])
    }

    @Test
    fun locaFormatFollowsTheGlyfHeader() {
        val short = decoded().tables
        assertEquals(0, ByteBuffer.wrap(short.getValue("head")).getShort(50).toInt())
        assertEquals(6 * 2, short.getValue("loca").size)
        val long = decoded(TestWoff2.font(indexFormat = 1)).tables
        assertEquals(1, ByteBuffer.wrap(long.getValue("head")).getShort(50).toInt())
        assertEquals(6 * 4, long.getValue("loca").size)
        assertEquals(glyphs(short).map { it.toList() }, glyphs(long).map { it.toList() })
        val head = TestWoff2.head()
        assertArrayEquals("head is kept apart from its checksum and loca format", head.copyOfRange(12, 50), long.getValue("head").copyOfRange(12, 50))
    }

    private fun hmtx(flags: Int, advances: List<Int>, lsbs: List<Int> = emptyList(), monoLsbs: List<Int> = emptyList()): ByteArray {
        val b = ByteBuffer.allocate(1 + 2 * (advances.size + lsbs.size + monoLsbs.size))
        b.put(flags.toByte())
        (advances + lsbs + monoLsbs).forEach { b.putShort(it.toShort()) }
        return b.array()
    }

    private fun metrics(hmtx: ByteArray): List<Int> = (0 until hmtx.size / 2).map { ByteBuffer.wrap(hmtx).getShort(it * 2).toInt() }

    @Test
    fun hmtxTransformTakesSideBearingsFromGlyphXMin() {
        val xMins = listOf(-9000, 0, -30, -100, 10)
        val both = decoded(TestWoff2.font(hmtx = hmtx(3, listOf(500, 600, 700)))).tables.getValue("hmtx")
        assertEquals(listOf(500, xMins[0], 600, xMins[1], 700, xMins[2], xMins[3], xMins[4]), metrics(both))

        val proportional = decoded(TestWoff2.font(hmtx = hmtx(1, listOf(500, 600, 700), monoLsbs = listOf(11, -12)))).tables.getValue("hmtx")
        assertEquals(listOf(500, xMins[0], 600, xMins[1], 700, xMins[2], 11, -12), metrics(proportional))

        val mono = decoded(TestWoff2.font(hmtx = hmtx(2, listOf(500, 600, 700), lsbs = listOf(1, 2, 3)))).tables.getValue("hmtx")
        assertEquals(listOf(500, 1, 600, 2, 700, 3, xMins[3], xMins[4]), metrics(mono))
    }

    @Test
    fun knownAndArbitraryTagsAndNullTransforms() {
        val extra = listOf(
            TestWoff2.Entry("name", byteArrayOf(1, 2, 3, 4, 5), knownIndex = false),
            TestWoff2.Entry("Zapf", byteArrayOf(6, 7)),
            TestWoff2.Entry("Sill", byteArrayOf(8)),
            TestWoff2.Entry("zzzz", byteArrayOf(9, 9, 9)),
            TestWoff2.Entry("hmtx", ByteArray(16) { it.toByte() }),
        )
        val t = decoded(TestWoff2.font(extra = extra)).tables
        assertEquals(listOf("Sill", "Zapf", "glyf", "head", "hhea", "hmtx", "loca", "name", "zzzz"), t.keys.toList())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), t["name"])
        assertArrayEquals(byteArrayOf(6, 7), t["Zapf"])
        assertArrayEquals(byteArrayOf(8), t["Sill"])
        assertArrayEquals(byteArrayOf(9, 9, 9), t["zzzz"])
        assertArrayEquals(ByteArray(16) { it.toByte() }, t["hmtx"])
    }

    @Test
    fun cffAndNullTransformedGlyf() {
        val cff = TestWoff2.woff2(listOf(TestWoff2.Entry("CFF ", ByteArray(70000) { (it * 31).toByte() }), TestWoff2.Entry("head", TestWoff2.head())), flavor = 0x4F54544F)
        val sfnt = Woff2Decoder.decode(cff)!!
        assertTrue(WoffDecoder.isCff(sfnt))
        assertArrayEquals("spans two stored meta-blocks", ByteArray(70000) { (it * 31).toByte() }, parse(sfnt).tables["CFF "])

        val glyf = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val loca = byteArrayOf(0, 0, 0, 4)
        val plain = TestWoff2.woff2(
            listOf(TestWoff2.Entry("head", TestWoff2.head()), TestWoff2.Entry("glyf", glyf, version = 3), TestWoff2.Entry("loca", loca, version = 3)),
        )
        val t = decoded(plain).tables
        assertArrayEquals(glyf, t["glyf"])
        assertArrayEquals(loca, t["loca"])
        assertEquals("head kept as is", 7, ByteBuffer.wrap(t.getValue("head")).getShort(50).toInt())
    }

    @Test
    fun malformedFilesGiveNull() {
        val good = TestWoff2.font()
        assertNotNull(Woff2Decoder.decode(good))
        fun edited(f: ByteBuffer.() -> Unit) = good.copyOf().also { ByteBuffer.wrap(it).f() }
        val head = TestWoff2.Entry("head", TestWoff2.head())
        fun with(vararg e: TestWoff2.Entry) = TestWoff2.woff2(e.toList())

        assertNull("empty", Woff2Decoder.decode(ByteArray(0)))
        assertNull("not woff2", Woff2Decoder.decode(ByteArray(64)))
        assertNull("woff 1", Woff2Decoder.decode(TestWoff.woff()))
        assertNull("truncated", Woff2Decoder.decode(good.copyOf(good.size - 12)))
        assertNull("truncated directory", Woff2Decoder.decode(good.copyOf(52)))
        assertNull("collection", Woff2Decoder.decode(edited { putInt(4, 0x74746366) }))
        assertNull("unknown flavor", Woff2Decoder.decode(edited { putInt(4, 0x41424344) }))
        assertNull("reserved set", Woff2Decoder.decode(edited { putShort(14, 1) }))
        assertNull("no tables", Woff2Decoder.decode(edited { putShort(12, 0) }))
        assertNull("length past the file", Woff2Decoder.decode(edited { putInt(8, good.size + 1) }))
        assertNull("compressed size past the file", Woff2Decoder.decode(edited { putInt(20, good.size) }))
        assertNull("compressed size short", Woff2Decoder.decode(edited { putInt(20, getInt(20) - 1) }))
        assertNull("duplicate tag", Woff2Decoder.decode(with(head, TestWoff2.Entry("name", byteArrayOf(1)), TestWoff2.Entry("name", byteArrayOf(2), knownIndex = false))))
        assertNull("unknown transform", Woff2Decoder.decode(with(head, TestWoff2.Entry("cmap", byteArrayOf(1), version = 1, transformLength = 1))))
        assertNull("glyf version 1", Woff2Decoder.decode(with(head, TestWoff2.Entry("glyf", byteArrayOf(1), version = 1, transformLength = 1))))
        val emptyGlyf = TestWoff2.glyf(listOf(TestWoff2.Empty))
        val glyfEntry = TestWoff2.Entry("glyf", emptyGlyf, transformLength = emptyGlyf.size)
        assertNotNull(Woff2Decoder.decode(with(head, glyfEntry, TestWoff2.Entry("loca", ByteArray(0), transformLength = 0))))
        assertNull("transformed loca with data", Woff2Decoder.decode(with(head, glyfEntry, TestWoff2.Entry("loca", byteArrayOf(0, 0), transformLength = 2))))
        assertNull("transformed glyf without loca", Woff2Decoder.decode(with(head, glyfEntry)))
        assertNull("transformed hmtx without transformed glyf", Woff2Decoder.decode(with(head, TestWoff2.Entry("hhea", TestWoff2.hhea(1)), TestWoff2.Entry("hmtx", byteArrayOf(3, 0, 1), version = 1, origLength = 4, transformLength = 3))))
        assertNull("transformed glyf without head", Woff2Decoder.decode(TestWoff2.font().let { f -> f.also { it[48] = 4 } }))
        assertNull("hmtx flags clear", Woff2Decoder.decode(TestWoff2.font(hmtx = hmtx(0, listOf(1, 2, 3), listOf(1, 2, 3), listOf(4, 5)))))
        assertNull("hmtx reserved flags", Woff2Decoder.decode(TestWoff2.font(hmtx = hmtx(7, listOf(1, 2, 3)))))
        assertNull("hmtx too short", Woff2Decoder.decode(TestWoff2.font(hmtx = hmtx(3, listOf(1, 2)))))
        assertNull("more hmetrics than glyphs", Woff2Decoder.decode(TestWoff2.font(hmtx = hmtx(3, listOf(1, 2, 3, 4, 5, 6)), numHMetrics = 6)))
        assertNull("leading zero in UIntBase128", Woff2Decoder.decode(TestWoff2.woff2(listOf(head)).also { it[49] = 0x80.toByte() }))
        assertNull("table over the cap", Woff2Decoder.decode(with(head, TestWoff2.Entry("name", byteArrayOf(1), origLength = WoffDecoder.MAX_SFNT_BYTES + 1, version = 0))))
        assertNull(
            "stream over the cap",
            Woff2Decoder.decode(with(TestWoff2.Entry("name", ByteArray(0), origLength = 20_000_000), TestWoff2.Entry("post", ByteArray(0), origLength = 20_000_000))),
        )
        assertNull("stream longer than declared", Woff2Decoder.decode(TestWoff2.woff2(listOf(head), stream = TestWoff2.brotliStored(TestWoff2.head() + byteArrayOf(1)))))
        assertNull("stream shorter than declared", Woff2Decoder.decode(TestWoff2.woff2(listOf(head), stream = TestWoff2.brotliStored(TestWoff2.head().copyOf(50)))))
        assertNull("corrupt brotli", Woff2Decoder.decode(TestWoff2.woff2(listOf(head), stream = byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f))))

        fun glyf(g: TestWoff2.Glyph, edit: ByteBuffer.() -> Unit = {}): ByteArray {
            val data = TestWoff2.glyf(listOf(g)).also { ByteBuffer.wrap(it).edit() }
            return TestWoff2.woff2(
                listOf(head, TestWoff2.Entry("glyf", data, transformLength = data.size), TestWoff2.Entry("loca", ByteArray(0), transformLength = 0)),
            )
        }
        val simple = TestWoff2.GLYPHS[4]
        assertNotNull(Woff2Decoder.decode(glyf(simple)))
        assertNotNull(Woff2Decoder.decode(glyf(TestWoff2.Empty)))
        assertNotNull(Woff2Decoder.decode(glyf(TestWoff2.GLYPHS[2])))
        assertNull("contours below -1", Woff2Decoder.decode(glyf(simple) { putShort(36, -2) }))
        assertNull("empty glyph with a bbox", Woff2Decoder.decode(glyf(TestWoff2.Empty) { put(36 + 2, 0x80.toByte()) }))
        assertNull("composite without a bbox", Woff2Decoder.decode(glyf(TestWoff2.GLYPHS[2]) { put(36 + 2 + 1 + 24, 0) }))
        assertNull("index format 2", Woff2Decoder.decode(glyf(simple) { putShort(6, 2) }))
        assertNull("stream sizes past the table", Woff2Decoder.decode(glyf(simple) { putInt(20, 1000) }))
        assertNull("too few points in the glyph stream", Woff2Decoder.decode(glyf(simple) { putInt(20, getInt(20) - 1) }))
    }

    @Test
    fun randomMutationsNeverThrowOrExceedTheCap() {
        val rnd = java.util.Random(11)
        val seeds = listOf(TestWoff2.font(), TestWoff2.font(hmtx = hmtx(1, listOf(500, 600, 700), monoLsbs = listOf(11, -12))), TestWoff2.font(indexFormat = 1))
        var decodedCount = 0
        repeat(6000) { i ->
            val good = seeds[i % seeds.size]
            val b = good.copyOf()
            repeat(1 + rnd.nextInt(4)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val out = Woff2Decoder.decode(if (rnd.nextInt(10) == 0) b.copyOf(rnd.nextInt(b.size)) else b)
            if (out != null) {
                decodedCount++
                assertTrue(out.size <= WoffDecoder.MAX_SFNT_BYTES)
            }
        }
        assertTrue("some mutations still decode", decodedCount > 0)
    }
}
