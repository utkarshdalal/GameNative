package app.gamenative.service.ubisoft

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** Builds a small installer in the NSIS layout the reader expects, from made-up files only. */
object UbisoftNsisFixture {
    const val VAR_INSTDIR = 0x8015

    /** A minimal PE image: one section holding an export directory that names [dllName]. */
    fun pe(machine: Int, dllName: String?, x64: Boolean = machine == PeImage.MACHINE_X64): ByteArray {
        val b = ByteArray(0x400)
        b[0] = 'M'.code.toByte(); b[1] = 'Z'.code.toByte()
        putU32(b, 0x3c, 0x40)
        putU32(b, 0x40, 0x4550)
        val coff = 0x44
        val optSize = if (x64) 0xF0 else 0xE0
        putU16(b, coff, machine)
        putU16(b, coff + 2, 1)
        putU16(b, coff + 16, optSize)
        putU16(b, coff + 18, if (dllName != null) 0x2002 else 0x0002)
        val opt = coff + 20
        putU16(b, opt, if (x64) 0x20b else 0x10b)
        val dirs = opt + if (x64) 112 else 96
        if (dllName != null) {
            putU32(b, dirs, 0x1000)
            putU32(b, dirs + 4, 0x40)
        }
        val sect = opt + optSize
        ".edata".toByteArray().copyInto(b, sect)
        putU32(b, sect + 8, 0x200)
        putU32(b, sect + 12, 0x1000)
        putU32(b, sect + 16, 0x200)
        putU32(b, sect + 20, 0x200)
        if (dllName != null) {
            putU32(b, 0x200 + 12, 0x1028)
            dllName.toByteArray(Charsets.US_ASCII).copyInto(b, 0x228)
        }
        return b
    }

    class Builder {
        private val strings = ByteArrayOutputStream().apply { write(0); write(0) }
        private val entries = ArrayList<IntArray>()
        private val data = ByteArrayOutputStream()

        /** A string built from UTF-16 code units, so variable codes can be embedded. */
        private fun addUnits(units: List<Int>): Int {
            val index = strings.size() / 2
            for (u in units + 0) { strings.write(u and 0xff); strings.write(u ushr 8) }
            return index
        }

        private fun text(s: String) = s.map { it.code }

        /** SetOutPath $INSTDIR, optionally followed by a sub folder. */
        fun outPath(sub: String? = null): Builder {
            val units = listOf(3, VAR_INSTDIR) + (sub?.let { text("\\$it") } ?: emptyList())
            entries.add(intArrayOf(11, addUnits(units), 1, 0, 0, 0, 0))
            return this
        }

        fun file(name: String, bytes: ByteArray, compress: Boolean = true): Builder {
            val offset = data.size()
            val payload = if (compress) deflate(bytes) else bytes
            val word = payload.size.toLong() or if (compress) 0x80000000L else 0L
            writeU32(data, word)
            data.write(payload)
            entries.add(intArrayOf(20, 0, addUnits(text(name)), offset, 0, 0, 0))
            return this
        }

        fun build(stubSize: Int = 1024): ByteArray {
            val header = ByteArrayOutputStream()
            val entriesOffset = 4 + 8 * 8
            val stringsOffset = entriesOffset + entries.size * 28
            writeU32(header, 0)
            val blocks = Array(8) { longArrayOf(0, 0) }
            blocks[2] = longArrayOf(entriesOffset.toLong(), entries.size.toLong())
            blocks[3] = longArrayOf(stringsOffset.toLong(), 0)
            for (blk in blocks) { writeU32(header, blk[0]); writeU32(header, blk[1]) }
            for (e in entries) for (v in e) writeU32(header, v.toLong() and 0xffffffffL)
            header.write(strings.toByteArray())
            val headerBytes = header.toByteArray()
            val packed = deflate(headerBytes)

            val out = ByteArrayOutputStream()
            out.write(ByteArray(stubSize))
            val body = ByteArrayOutputStream()
            writeU32(body, packed.size.toLong() or 0x80000000L)
            body.write(packed)
            body.write(data.toByteArray())
            writeU32(out, 0)
            writeU32(out, 0xDEADBEEFL)
            out.write("NullsoftInst".toByteArray(Charsets.US_ASCII))
            writeU32(out, headerBytes.size.toLong())
            writeU32(out, (28 + body.size()).toLong())
            out.write(body.toByteArray())
            return out.toByteArray()
        }
    }

    fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** A complete fake runtime: every wanted file with the right machine and export name. */
    fun runtimeInstaller(build: String = "12345", override: Map<String, ByteArray> = emptyMap()): ByteArray {
        val b = Builder().outPath()
        for ((name, machine) in UbisoftRuntime.FILES) {
            val bytes = override[name] ?: pe(machine, name.takeIf { it.endsWith(".dll") })
            b.file(name, bytes)
        }
        b.file("version.txt", build.toByteArray(), compress = false)
        b.outPath("shareplay").file("uplay_r1.dll", pe(PeImage.MACHINE_X64, "other.dll"))
        return b.build()
    }

    private fun putU16(b: ByteArray, at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte() }

    private fun putU32(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xff).toInt())
    }
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    var reads = 0
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, length: Int): ByteArray {
        reads++
        if (position < 0 || position + length > bytes.size) throw java.io.EOFException()
        return bytes.copyOfRange(position.toInt(), position.toInt() + length)
    }
    override fun close() {}
}
