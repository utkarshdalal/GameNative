package app.gamenative.service.rockstar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class RockstarCloudCompressionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val plain = "The quick brown fox jumps over the lazy dog. ".repeat(3).toByteArray()

    // zlib: deflateInit2(level 9, windowBits -9, memLevel 1) behind the 22-byte launcher header
    private val reference = (
        "010087000000e6f263d514adf884ef6cc864d206d462" +
            "0bc94855282ccd4cce56482aca2fcf5348cbaf50c82acd2d2856c82f4b2d5228014ae72456552aa4e4a7eb2984d04c3100"
        ).hexToBytes()

    private fun file(name: String, bytes: ByteArray): File = folder.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun compressMatchesLauncherBytes() {
        val out = File(folder.root, "out.z")
        val result = RockstarCloudCompression.compress(file("SGTA400", plain), out)
        assertArrayEquals(reference, out.readBytes())
        assertEquals(plain.size.toLong(), result.size)
        assertEquals(md5(plain), result.md5)
    }

    @Test
    fun decompressRestoresPlainBytes() {
        val out = File(folder.root, "plain")
        val result = RockstarCloudCompression.decompress(file("blob", reference), out)
        assertArrayEquals(plain, out.readBytes())
        assertEquals(plain.size.toLong(), result.size)
        assertEquals(md5(plain), result.md5)
    }

    @Test
    fun roundTripLargeFileWithLongRepeats() {
        val data = ByteArray(300_000) { (it * 7 % 251).toByte() } + ByteArray(100_000) { 0x41 }
        val blob = File(folder.root, "big.z")
        RockstarCloudCompression.compress(file("SGTA401", data), blob)
        val back = File(folder.root, "big")
        RockstarCloudCompression.decompress(blob, back)
        assertArrayEquals(data, back.readBytes())
    }

    @Test
    fun headerMd5MismatchFails() {
        val tampered = reference.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertThrows(RockstarCloudException::class.java) {
            RockstarCloudCompression.decompress(file("blob", tampered), File(folder.root, "plain"))
        }
    }

    @Test
    fun headerSizeMismatchFails() {
        val tampered = reference.copyOf().also { it[2] = (it[2] - 1).toByte() }
        assertThrows(RockstarCloudException::class.java) {
            RockstarCloudCompression.decompress(file("blob", tampered), File(folder.root, "plain"))
        }
    }

    @Test
    fun unknownVersionFails() {
        val tampered = reference.copyOf().also { it[0] = 2 }
        assertThrows(RockstarCloudException::class.java) {
            RockstarCloudCompression.decompress(file("blob", tampered), File(folder.root, "plain"))
        }
    }

    @Test
    fun emptyFileCannotBeCompressed() {
        assertThrows(RockstarCloudException::class.java) {
            RockstarCloudCompression.compress(file("SGTA402", ByteArray(0)), File(folder.root, "out.z"))
        }
    }

    private fun md5(bytes: ByteArray): String = RockstarCloudApi.hex(MessageDigest.getInstance("MD5").digest(bytes))

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
