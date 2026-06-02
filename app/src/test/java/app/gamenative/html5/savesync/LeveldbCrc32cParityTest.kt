package app.gamenative.html5.savesync

import org.iq80.leveldb.util.PureJavaCrc32C
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.CRC32C

// guards the LeveldbManifestSynthesizer.wrapInLogRecord swap from java.util.zip.CRC32C (API 34+,
// ClassNotFound on Android 13 / API 33) to iq80's PureJavaCrc32C. both MUST produce the identical
// standard CRC-32C value -- a mismatch makes chromium reject the synthesized leveldb log record,
// so the source SST never imports and the save loads empty. this test runs on the JVM where
// java.util.zip.CRC32C exists, pinning PureJavaCrc32C to its reference output.
class LeveldbCrc32cParityTest {
    private fun pure(bytes: ByteArray): Long =
        PureJavaCrc32C().apply { update(bytes, 0, bytes.size) }.value

    private fun jdk(bytes: ByteArray): Long =
        CRC32C().apply { update(bytes) }.value

    @Test
    fun matches_jdk_crc32c_across_inputs() {
        val cases = listOf(
            ByteArray(0),
            byteArrayOf(1),
            byteArrayOf(0),
            byteArrayOf(1, 2, 3, 4, 5),
            "hello leveldb".toByteArray(),
            ByteArray(1024) { (it * 31 + 7).toByte() },
            ByteArray(65535) { (it xor 0xA5).toByte() },
        )
        for (c in cases) assertEquals("len=${c.size}", jdk(c), pure(c))
    }

    @Test
    fun matches_with_leading_type_byte_then_payload() {
        // mirrors wrapInLogRecord: type byte 1 fed first, then payload.
        val payload = ByteArray(300) { (it * 17).toByte() }
        val pureV = PureJavaCrc32C().apply { update(1); update(payload, 0, payload.size) }.value
        val jdkV = CRC32C().apply { update(byteArrayOf(1)); update(payload) }.value
        assertEquals(jdkV, pureV)
    }
}
