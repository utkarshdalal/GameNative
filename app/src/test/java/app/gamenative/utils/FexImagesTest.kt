package app.gamenative.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FexImagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val peOffset = 0x80

    /** DOS header, NT signature, COFF header and the optional header up to SizeOfImage; nothing else. */
    private fun pe(timeDateStamp: Long, sizeOfImage: Long, pe32Plus: Boolean = true, padding: Int = 0): ByteArray {
        val buf = ByteBuffer.allocate(peOffset + 24 + 240 + padding).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte())
        buf.putInt(0x3c, peOffset)
        buf.putInt(peOffset, 0x00004550)
        buf.putShort(peOffset + 4, (if (pe32Plus) 0x8664 else 0x14c).toShort())
        buf.putInt(peOffset + 8, timeDateStamp.toInt())
        buf.putShort(peOffset + 24, (if (pe32Plus) 0x20b else 0x10b).toShort())
        buf.putInt(peOffset + 24 + 56, sizeOfImage.toInt())
        return buf.array()
    }

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).apply {
        parentFile?.mkdirs()
        writeBytes(bytes)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `reads TimeDateStamp and SizeOfImage from a PE32+ header`() {
        val header = FexImages.readHeader(file("game.exe", pe(0x66778899, 41_000_000)))
        assertEquals(FexImages.Header(0x66778899, 41_000_000), header)
    }

    @Test
    fun `reads the same offsets from a PE32 header`() {
        val header = FexImages.readHeader(file("game32.exe", pe(1_719_000_000, 0x2000, pe32Plus = false)))
        assertEquals(FexImages.Header(1_719_000_000, 0x2000), header)
    }

    @Test
    fun `header fields are unsigned`() {
        val header = FexImages.readHeader(file("high.dll", pe(0xF0000000L, 0x80000000L)))
        assertEquals(FexImages.Header(0xF0000000L, 0x80000000L), header)
    }

    @Test
    fun `rejects files without the MZ or PE signatures`() {
        val noMz = pe(1, 2).also { it[0] = 'X'.code.toByte() }
        val noPe = pe(1, 2).also { it[peOffset + 2] = 1 }
        assertNull(FexImages.readHeader(file("nomz.exe", noMz)))
        assertNull(FexImages.readHeader(file("nope.exe", noPe)))
        assertNull(FexImages.readHeader(file("text.dll", "not a pe".toByteArray())))
    }

    @Test
    fun `rejects a header that runs past the end of the file`() {
        assertNull(FexImages.readHeader(file("short.exe", pe(1, 2).copyOf(peOffset + 24 + 40))))
        val farLfanew = pe(1, 2).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3c, -16) }
        assertNull(FexImages.readHeader(file("far.exe", farLfanew)))
    }

    @Test
    fun `describes PE files with sha256, lowercase name and header fields and skips the rest`() {
        val bytes = pe(1_719_000_000, 41_000_000)
        val exe = file("game/Brotato.exe", bytes)
        val text = file("game/readme.dll", "hello".toByteArray())
        val images = FexImages.describe(tmp.root, listOf(exe, text))
        assertEquals(listOf(FexImages.Image(sha256(bytes), "brotato.exe", 1_719_000_000, 41_000_000)), images)

        val index = JSONObject(File(tmp.root, ".gamenative/fex-image-hashes.json").readText())
        val entry = index.getJSONObject(exe.absolutePath)
        assertEquals(bytes.size.toLong(), entry.getLong("size"))
        assertEquals(exe.lastModified(), entry.getLong("mtime"))
        assertEquals(sha256(bytes), entry.getString("sha256"))
        assertFalse(index.has(text.absolutePath))
    }

    @Test
    fun `an unchanged file is not hashed again and a changed one is`() {
        val exe = file("game/game.exe", pe(10, 20))
        FexImages.describe(tmp.root, listOf(exe))

        // Swap the stored hash: if the index is used, the swapped value comes back.
        val indexFile = File(tmp.root, ".gamenative/fex-image-hashes.json")
        val marker = "a".repeat(64)
        val index = JSONObject(indexFile.readText())
        index.getJSONObject(exe.absolutePath).put("sha256", marker)
        indexFile.writeText(index.toString())
        assertEquals(marker, FexImages.describe(tmp.root, listOf(exe)).single().sha256)

        val updated = pe(11, 20, padding = 16)
        exe.writeBytes(updated)
        exe.setLastModified(exe.lastModified() + 2000)
        val image = FexImages.describe(tmp.root, listOf(exe)).single()
        assertEquals(sha256(updated), image.sha256)
        assertEquals(11L, image.timeDateStamp)
    }

    @Test
    fun `entries of deleted files are dropped from the index`() {
        val a = file("game/a.dll", pe(1, 2))
        val b = file("game/b.dll", pe(3, 4))
        FexImages.describe(tmp.root, listOf(a, b))
        assertTrue(b.delete())
        FexImages.describe(tmp.root, listOf(a))
        val index = JSONObject(File(tmp.root, ".gamenative/fex-image-hashes.json").readText())
        assertTrue(index.has(a.absolutePath))
        assertFalse(index.has(b.absolutePath))
    }
}
