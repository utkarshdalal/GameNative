package app.gamenative.texturepack

import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TexturePackFramingTest {

    @Test
    fun `records round trip in order`() {
        val records = listOf(
            TexturePackRecord("bc7_4x4_0000000000000001.a4", ByteArray(16) { it.toByte() }),
            TexturePackRecord("bc1_8x8_0000000000000002.a6", ByteArray(0)),
            TexturePackRecord("bc3_64x64_0000000000000003.a4", ByteArray(70_000) { (it * 31).toByte() }),
        )

        val decoded = TexturePackFraming.decode(ByteArrayInputStream(TexturePackFraming.encode(records)))

        assertEquals(records.map { it.key }, decoded.map { it.key })
        records.zip(decoded).forEach { (expected, actual) -> assertArrayEquals(expected.payload, actual.payload) }
    }

    @Test
    fun `lengths are little endian`() {
        val bytes = TexturePackFraming.encode(listOf(TexturePackRecord("ab", ByteArray(0x0102))))

        assertEquals(2 + 2 + 4 + 0x0102, bytes.size)
        assertEquals(2, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals('a'.code, bytes[2].toInt())
        assertEquals('b'.code, bytes[3].toInt())
        assertEquals(0x02, bytes[4].toInt())
        assertEquals(0x01, bytes[5].toInt())
        assertEquals(0, bytes[6].toInt())
        assertEquals(0, bytes[7].toInt())
    }

    @Test
    fun `empty stream has no records`() {
        assertNull(TexturePackFraming.readRecord(ByteArrayInputStream(ByteArray(0))))
    }

    @Test(expected = EOFException::class)
    fun `truncated payload is an error`() {
        val bytes = TexturePackFraming.encode(listOf(TexturePackRecord("k", ByteArray(32))))
        TexturePackFraming.decode(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)))
    }
}
