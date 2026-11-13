package app.gamenative.theme

import java.io.ByteArrayInputStream
import java.io.IOException
import org.brotli.dec.BrotliInputStream

/** Decodes WOFF 2.0 web fonts to the TrueType / OpenType file they wrap; font collections are not supported. Pure JVM. */
object Woff2Decoder {

    private const val SIGNATURE = 0x774F4632L
    private const val HEADER = 48
    private const val MAX_TABLES = 4096
    private const val MAX_POINTS = 0xFFFF
    private const val GLYF_HEADER = 36
    private const val TAG_GLYF = 0x676C7966
    private const val TAG_LOCA = 0x6C6F6361
    private const val TAG_HMTX = 0x686D7478
    private const val TAG_HHEA = 0x68686561
    private const val HEAD_INDEX_TO_LOC = 50

    private const val ON_CURVE = 0x01
    private const val X_SHORT = 0x02
    private const val Y_SHORT = 0x04
    private const val REPEAT = 0x08
    private const val X_SAME = 0x10
    private const val Y_SAME = 0x20
    private const val OVERLAP_SIMPLE = 0x40

    private const val ARGS_ARE_WORDS = 0x0001
    private const val HAVE_SCALE = 0x0008
    private const val MORE_COMPONENTS = 0x0020
    private const val HAVE_XY_SCALE = 0x0040
    private const val HAVE_2X2 = 0x0080
    private const val HAVE_INSTRUCTIONS = 0x0100

    private val KNOWN_TAGS = listOf(
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ",
        "VORG", "EBDT", "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE", "GDEF", "GPOS",
        "GSUB", "EBSC", "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar", "bdat", "bloc",
        "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop",
        "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
    ).map { t -> t.fold(0) { acc, c -> (acc shl 8) or c.code } }

    private class Malformed : RuntimeException(null, null, false, false)

    private class Entry(val tag: Int, val transformed: Boolean, val streamLength: Int) {
        var offset = 0
    }

    private class Glyf(val glyf: ByteArray, val glyfLength: Int, val loca: ByteArray, val longLoca: Boolean, val xMins: IntArray)

    /** The sfnt font inside [woff2], or null when it is not a well-formed WOFF 2.0 file. */
    fun decode(woff2: ByteArray): ByteArray? = try {
        decodeOrNull(woff2)
    } catch (e: RuntimeException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun decodeOrNull(woff2: ByteArray): ByteArray? {
        if (woff2.size < HEADER || Sfnt.u32(woff2, 0) != SIGNATURE) return null
        val flavor = Sfnt.u32(woff2, 4).toInt()
        if (!Sfnt.isFlavor(flavor)) return null
        val length = Sfnt.u32(woff2, 8)
        val numTables = Sfnt.u16(woff2, 12)
        if (Sfnt.u16(woff2, 14) != 0) return null
        val compressedSize = Sfnt.u32(woff2, 20)
        if (length > woff2.size || numTables == 0 || numTables > MAX_TABLES) return null

        val dir = In(woff2, HEADER, length.toInt())
        val entries = ArrayList<Entry>(numTables)
        val byTag = HashMap<Int, Entry>()
        var streamSize = 0L
        for (i in 0 until numTables) {
            val flags = dir.u8()
            val tag = if (flags and 0x3f == 0x3f) dir.u32().toInt() else KNOWN_TAGS[flags and 0x3f]
            val version = flags ushr 6
            val origLength = dir.base128()
            val glyfOrLoca = tag == TAG_GLYF || tag == TAG_LOCA
            val transformed = if (glyfOrLoca) version != 3 else version != 0
            if (transformed && !(glyfOrLoca && version == 0) && !(tag == TAG_HMTX && version == 1)) return null
            val streamLength = if (transformed) dir.base128() else origLength
            if (tag == TAG_LOCA && transformed && streamLength != 0L) return null
            if (origLength > Sfnt.MAX_BYTES || streamLength > Sfnt.MAX_BYTES) return null
            streamSize += streamLength
            if (streamSize > Sfnt.MAX_BYTES) return null
            val e = Entry(tag, transformed, streamLength.toInt())
            if (byTag.put(tag, e) != null) return null
            entries.add(e)
        }
        val glyfEntry = byTag[TAG_GLYF]
        val locaEntry = byTag[TAG_LOCA]
        val glyfTransformed = glyfEntry?.transformed == true
        if (glyfTransformed != (locaEntry?.transformed == true)) return null
        if (byTag[TAG_HMTX]?.transformed == true && !glyfTransformed) return null
        if (dir.pos + compressedSize > length) return null

        val stream = brotli(woff2, dir.pos, compressedSize.toInt(), streamSize.toInt()) ?: return null
        var at = 0
        for (e in entries) {
            e.offset = at
            at += e.streamLength
        }

        val tables = ArrayList<Sfnt.Table>(numTables)
        val glyf = if (glyfTransformed) reconstructGlyf(stream, glyfEntry!!.offset, glyfEntry.streamLength) else null
        for (e in entries) {
            tables.add(
                when {
                    glyf != null && e.tag == TAG_GLYF -> Sfnt.Table(e.tag, glyf.glyf, 0, glyf.glyfLength)
                    glyf != null && e.tag == TAG_LOCA -> Sfnt.Table(e.tag, glyf.loca, 0, glyf.loca.size)
                    glyf != null && e.tag == Sfnt.TAG_HEAD -> {
                        if (e.streamLength < HEAD_INDEX_TO_LOC + 2) return null
                        val head = stream.copyOfRange(e.offset, e.offset + e.streamLength)
                        Sfnt.put16(head, HEAD_INDEX_TO_LOC, if (glyf.longLoca) 1 else 0)
                        Sfnt.Table(e.tag, head, 0, head.size)
                    }
                    e.transformed && e.tag == TAG_HMTX -> {
                        val hhea = byTag[TAG_HHEA] ?: return null
                        if (hhea.streamLength < 36) return null
                        val hmtx = reconstructHmtx(stream, e.offset, e.streamLength, Sfnt.u16(stream, hhea.offset + 34), glyf!!.xMins)
                        Sfnt.Table(e.tag, hmtx, 0, hmtx.size)
                    }
                    else -> Sfnt.Table(e.tag, stream, e.offset, e.streamLength)
                },
            )
        }
        if (glyf != null && byTag[Sfnt.TAG_HEAD] == null) return null
        return Sfnt.assemble(flavor, tables)
    }

    /** Exactly [size] bytes of the Brotli stream at [off]; never inflates past [size] + 1 bytes. */
    private fun brotli(src: ByteArray, off: Int, len: Int, size: Int): ByteArray? {
        val out = ByteArray(size)
        BrotliInputStream(ByteArrayInputStream(src, off, len)).use { input ->
            var n = 0
            while (n < size) {
                val r = input.read(out, n, size - n)
                if (r <= 0) return null
                n += r
            }
            if (input.read() != -1) return null
        }
        return out
    }

    private fun reconstructGlyf(s: ByteArray, off: Int, len: Int): Glyf {
        val h = In(s, off, off + len)
        h.u16()
        val options = h.u16()
        val numGlyphs = h.u16()
        val indexFormat = h.u16()
        if (indexFormat > 1) throw Malformed()
        var p = off + GLYF_HEADER
        fun stream(size: Long): In {
            if (size > off + len - p) throw Malformed()
            return In(s, p, p + size.toInt()).also { p += size.toInt() }
        }
        val nContours = stream(h.u32())
        val nPoints = stream(h.u32())
        val flags = stream(h.u32())
        val glyphs = stream(h.u32())
        val composites = stream(h.u32())
        val bbox = stream(h.u32())
        val instructions = stream(h.u32())
        val overlap = if (options and 1 != 0) stream(((numGlyphs + 7) / 8).toLong()) else null
        val bboxBitmap = bbox.pos
        bbox.skip(((numGlyphs + 31) / 32) * 4)

        val out = Out(len * 2 + 1024)
        val loca = IntArray(numGlyphs + 1)
        val xMins = IntArray(numGlyphs)
        for (i in 0 until numGlyphs) {
            loca[i] = out.size
            val contours = nContours.s16()
            val hasBbox = (s[bboxBitmap + (i shr 3)].toInt() and (0x80 ushr (i and 7))) != 0
            when {
                contours == 0 -> if (hasBbox) throw Malformed()
                contours == -1 -> {
                    if (!hasBbox) throw Malformed()
                    out.u16(contours)
                    xMins[i] = bbox.s16()
                    out.u16(xMins[i])
                    out.bytes(s, bbox.take(6), 6)
                    val start = composites.pos
                    val haveInstructions = skipComposite(composites)
                    out.bytes(s, start, composites.pos - start)
                    if (haveInstructions) {
                        val n = glyphs.u255()
                        out.u16(n)
                        out.bytes(s, instructions.take(n), n)
                    }
                }
                contours < 0 -> throw Malformed()
                else -> {
                    val overlapBit = overlap != null && (s[overlap.pos + (i shr 3)].toInt() and (0x80 ushr (i and 7))) != 0
                    xMins[i] = simpleGlyph(s, contours, hasBbox, overlapBit, nPoints, flags, glyphs, bbox, instructions, out)
                }
            }
            out.pad4()
        }
        loca[numGlyphs] = out.size

        val longLoca = indexFormat == 1 || out.size > 0x1FFFE
        val locaBytes = ByteArray((numGlyphs + 1) * if (longLoca) 4 else 2)
        for (i in 0..numGlyphs) {
            if (longLoca) Sfnt.put32(locaBytes, i * 4, loca[i]) else Sfnt.put16(locaBytes, i * 2, loca[i] ushr 1)
        }
        return Glyf(out.buf, out.size, locaBytes, longLoca, xMins)
    }

    /** Writes one simple glyph to [out]; returns its xMin. */
    private fun simpleGlyph(
        s: ByteArray,
        contours: Int,
        hasBbox: Boolean,
        overlapBit: Boolean,
        nPoints: In,
        flagStream: In,
        glyphs: In,
        bbox: In,
        instructions: In,
        out: Out,
    ): Int {
        if (contours > nPoints.remaining) throw Malformed()
        val endPts = IntArray(contours)
        var total = 0
        for (c in 0 until contours) {
            total += nPoints.u255()
            if (total > MAX_POINTS) throw Malformed()
            endPts[c] = total - 1
        }
        val flagsAt = flagStream.take(total)
        val xs = IntArray(total)
        val ys = IntArray(total)
        var x = 0
        var y = 0
        for (k in 0 until total) {
            val flag = s[flagsAt + k].toInt() and 0x7f
            val dx: Int
            val dy: Int
            when {
                flag < 10 -> {
                    dx = 0
                    dy = sign(flag, ((flag and 14) shl 7) + glyphs.u8())
                }
                flag < 20 -> {
                    dx = sign(flag, (((flag - 10) and 14) shl 7) + glyphs.u8())
                    dy = 0
                }
                flag < 84 -> {
                    val b0 = flag - 20
                    val b1 = glyphs.u8()
                    dx = sign(flag, 1 + (b0 and 0x30) + (b1 shr 4))
                    dy = sign(flag shr 1, 1 + ((b0 and 0x0c) shl 2) + (b1 and 0x0f))
                }
                flag < 120 -> {
                    val b0 = flag - 84
                    dx = sign(flag, 1 + ((b0 / 12) shl 8) + glyphs.u8())
                    dy = sign(flag shr 1, 1 + (((b0 % 12) shr 2) shl 8) + glyphs.u8())
                }
                flag < 124 -> {
                    val b1 = glyphs.u8()
                    val b2 = glyphs.u8()
                    dx = sign(flag, (b1 shl 4) + (b2 shr 4))
                    dy = sign(flag shr 1, ((b2 and 0x0f) shl 8) + glyphs.u8())
                }
                else -> {
                    dx = sign(flag, glyphs.u16())
                    dy = sign(flag shr 1, glyphs.u16())
                }
            }
            x += dx
            y += dy
            xs[k] = x
            ys[k] = y
        }
        val instructionLength = glyphs.u255()

        out.u16(contours)
        val xMin: Int
        if (hasBbox) {
            xMin = bbox.s16()
            out.u16(xMin)
            out.bytes(s, bbox.take(6), 6)
        } else {
            var x0 = if (total > 0) xs[0] else 0
            var y0 = if (total > 0) ys[0] else 0
            var x1 = x0
            var y1 = y0
            for (k in 1 until total) {
                x0 = minOf(x0, xs[k])
                x1 = maxOf(x1, xs[k])
                y0 = minOf(y0, ys[k])
                y1 = maxOf(y1, ys[k])
            }
            xMin = x0.toShort().toInt()
            out.u16(x0)
            out.u16(y0)
            out.u16(x1)
            out.u16(y1)
        }
        for (e in endPts) out.u16(e)
        out.u16(instructionLength)
        out.bytes(s, instructions.take(instructionLength), instructionLength)

        var lastFlag = -1
        var repeat = 0
        var lastX = 0
        var lastY = 0
        for (k in 0 until total) {
            var flag = if (s[flagsAt + k].toInt() and 0x80 == 0) ON_CURVE else 0
            if (overlapBit && k == 0) flag = flag or OVERLAP_SIMPLE
            val dx = xs[k] - lastX
            val dy = ys[k] - lastY
            flag = flag or when {
                dx == 0 -> X_SAME
                dx > -256 && dx < 256 -> X_SHORT or (if (dx > 0) X_SAME else 0)
                else -> 0
            }
            flag = flag or when {
                dy == 0 -> Y_SAME
                dy > -256 && dy < 256 -> Y_SHORT or (if (dy > 0) Y_SAME else 0)
                else -> 0
            }
            if (flag == lastFlag && repeat != 255) {
                out.buf[out.size - 1] = (out.buf[out.size - 1].toInt() or REPEAT).toByte()
                repeat++
            } else {
                if (repeat != 0) out.u8(repeat)
                out.u8(flag)
                repeat = 0
            }
            lastX = xs[k]
            lastY = ys[k]
            lastFlag = flag
        }
        if (repeat != 0) out.u8(repeat)
        writeDeltas(xs, out)
        writeDeltas(ys, out)
        return xMin
    }

    private fun writeDeltas(v: IntArray, out: Out) {
        var last = 0
        for (c in v) {
            val d = c - last
            when {
                d == 0 -> {}
                d > -256 && d < 256 -> out.u8(if (d < 0) -d else d)
                else -> out.u16(d)
            }
            last = c
        }
    }

    private fun sign(flag: Int, value: Int): Int = if (flag and 1 != 0) value else -value

    /** Moves [c] past one composite glyph's components; true when the glyph has instructions. */
    private fun skipComposite(c: In): Boolean {
        var flags = MORE_COMPONENTS
        var instructions = false
        while (flags and MORE_COMPONENTS != 0) {
            flags = c.u16()
            if (flags and HAVE_INSTRUCTIONS != 0) instructions = true
            var n = 2 + if (flags and ARGS_ARE_WORDS != 0) 4 else 2
            n += when {
                flags and HAVE_SCALE != 0 -> 2
                flags and HAVE_XY_SCALE != 0 -> 4
                flags and HAVE_2X2 != 0 -> 8
                else -> 0
            }
            c.skip(n)
        }
        return instructions
    }

    private fun reconstructHmtx(s: ByteArray, off: Int, len: Int, numHMetrics: Int, xMins: IntArray): ByteArray {
        val numGlyphs = xMins.size
        val h = In(s, off, off + len)
        val flags = h.u8()
        if (flags and 0xfc != 0 || flags and 3 == 0) throw Malformed()
        if (numHMetrics < 1 || numHMetrics > numGlyphs) throw Malformed()
        val out = ByteArray(numHMetrics * 2 + numGlyphs * 2)
        for (i in 0 until numHMetrics) Sfnt.put16(out, i * 4, h.u16())
        for (i in 0 until numGlyphs) {
            val fromXMin = if (i < numHMetrics) flags and 1 != 0 else flags and 2 != 0
            val lsb = if (fromXMin) xMins[i] else h.s16()
            Sfnt.put16(out, if (i < numHMetrics) i * 4 + 2 else numHMetrics * 4 + (i - numHMetrics) * 2, lsb)
        }
        return out
    }

    /** Bounds-checked reader of [b] from [start] to [end]. */
    private class In(val b: ByteArray, start: Int, val end: Int) {
        var pos = start

        init {
            if (start < 0 || end > b.size || start > end) throw Malformed()
        }

        val remaining get() = end - pos

        /** Skips [n] bytes and returns where they start. */
        fun take(n: Int): Int {
            if (n < 0 || n > end - pos) throw Malformed()
            return pos.also { pos += n }
        }

        fun skip(n: Int) {
            take(n)
        }

        fun u8(): Int = b[take(1)].toInt() and 0xff

        fun u16(): Int = Sfnt.u16(b, take(2))

        fun s16(): Int = u16().toShort().toInt()

        fun u32(): Long = Sfnt.u32(b, take(4))

        fun base128(): Long {
            var v = 0L
            for (i in 0 until 5) {
                val c = u8()
                if (i == 0 && c == 0x80) throw Malformed()
                if (v and 0xFE000000L != 0L) throw Malformed()
                v = (v shl 7) or (c and 0x7f).toLong()
                if (c and 0x80 == 0) return v
            }
            throw Malformed()
        }

        fun u255(): Int = when (val c = u8()) {
            253 -> u16()
            254 -> u8() + 253 * 2
            255 -> u8() + 253
            else -> c
        }
    }

    /** Growable output capped at [Sfnt.MAX_BYTES]. */
    private class Out(initial: Int) {
        var buf = ByteArray(minOf(initial, Sfnt.MAX_BYTES))
        var size = 0

        private fun ensure(n: Int) {
            if (n > Sfnt.MAX_BYTES - size) throw Malformed()
            if (size + n > buf.size) buf = buf.copyOf(minOf(maxOf(buf.size * 2, size + n), Sfnt.MAX_BYTES))
        }

        fun u8(v: Int) {
            ensure(1)
            buf[size++] = v.toByte()
        }

        fun u16(v: Int) {
            ensure(2)
            Sfnt.put16(buf, size, v)
            size += 2
        }

        fun bytes(src: ByteArray, off: Int, len: Int) {
            ensure(len)
            System.arraycopy(src, off, buf, size, len)
            size += len
        }

        fun pad4() {
            val n = ((size + 3) and 3.inv()) - size
            ensure(n)
            size += n
        }
    }
}
