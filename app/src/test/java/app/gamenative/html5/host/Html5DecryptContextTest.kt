package app.gamenative.html5.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

// pure-jvm tests per covers: real XOR byte-level PNG magic recovery, missing/malformed
// System.json, short-stream fail-null paths, and SequenceInputStream laziness (does NOT read
// tail upfront — only 32 source bytes consumed before wrapStream returns).
class Html5DecryptContextTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun writeSystemJson(root: File, body: String): File {
        val dataDir = File(root, "data").apply { mkdirs() }
        return File(dataDir, "System.json").apply { writeText(body) }
    }

    @Test
    fun decrypts_first_16_bytes_with_xor() {
        val root = tempFolder.newFolder()
        writeSystemJson(root, """{"encryptionKey":"d41d8cd98f00b204e9800998ecf8427e"}""")
        val ctx = Html5DecryptContext(root)
        assertTrue(ctx.hasKey)

        val keyHex = "d41d8cd98f00b204e9800998ecf8427e"
        val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pngMagic = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 1, 2, 3, 4, 5, 6, 7,
        )
        val encryptedRegion = ByteArray(16) { (pngMagic[it].toInt() xor key[it].toInt()).toByte() }
        val tail = ByteArray(32) { it.toByte() }
        val fakeHeader = ByteArray(16) { 0 }
        val encrypted = fakeHeader + encryptedRegion + tail

        val result = ctx.wrapStream(ByteArrayInputStream(encrypted))!!.readBytes()
        assertArrayEquals(pngMagic + tail, result)
    }

    @Test
    fun missing_system_json_returns_no_key() {
        val root = tempFolder.newFolder()
        val ctx = Html5DecryptContext(root)
        assertFalse(ctx.hasKey)
        assertNull(ctx.wrapStream(ByteArrayInputStream(ByteArray(100))))
    }

    @Test
    fun malformed_key_hex_returns_no_key() {
        val root = tempFolder.newFolder()
        writeSystemJson(root, """{"encryptionKey":"too-short"}""")
        val ctx = Html5DecryptContext(root)
        assertFalse(ctx.hasKey)
    }

    @Test
    fun missing_encryptionKey_field_returns_no_key() {
        val root = tempFolder.newFolder()
        writeSystemJson(root, """{"gameTitle":"Test"}""")
        val ctx = Html5DecryptContext(root)
        assertFalse(ctx.hasKey)
    }

    @Test
    fun file_shorter_than_rpgmv_header_returns_null_stream() {
        val root = tempFolder.newFolder()
        writeSystemJson(root, """{"encryptionKey":"d41d8cd98f00b204e9800998ecf8427e"}""")
        val ctx = Html5DecryptContext(root)
        assertNull(ctx.wrapStream(ByteArrayInputStream(ByteArray(10))))
    }

    @Test
    fun file_shorter_than_xor_region_returns_null_stream() {
        val root = tempFolder.newFolder()
        writeSystemJson(root, """{"encryptionKey":"d41d8cd98f00b204e9800998ecf8427e"}""")
        val ctx = Html5DecryptContext(root)
        // 16-byte header + 8-byte partial XOR region < 32 required
        assertNull(ctx.wrapStream(ByteArrayInputStream(ByteArray(24))))
    }

    @Test
    fun uses_sequence_input_stream_not_whole_file_read() {
        // proves SequenceInputStream laziness — wrapStream must consume exactly 32 bytes (16
        // header + 16 XOR region) before returning. a readBytes() impl would consume all 1024.
        val root = tempFolder.newFolder()
        writeSystemJson(root, """{"encryptionKey":"d41d8cd98f00b204e9800998ecf8427e"}""")
        val ctx = Html5DecryptContext(root)

        val counter = object : java.io.FilterInputStream(ByteArrayInputStream(ByteArray(1024))) {
            var consumed = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) consumed += n
                return n
            }

            override fun read(): Int {
                val v = super.read()
                if (v >= 0) consumed++
                return v
            }

            override fun skip(n: Long): Long {
                val s = super.skip(n)
                consumed += s.toInt()
                return s
            }
        }
        ctx.wrapStream(counter)
        assertTrue(
            "wrapStream should read exactly 32 source bytes (header+xor region), got ${counter.consumed}",
            counter.consumed == 32,
        )
    }
}
