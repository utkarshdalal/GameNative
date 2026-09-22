package app.gamenative.texturepack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextureCacheStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val bc1Mip = ByteArray(8) { it.toByte() }

    @Test
    fun `content key matches the known xxh64 seed 1 vector`() {
        assertEquals(
            "bc1_4x4_9d2b7c7354fe4e23.a4",
            TextureCacheStore.contentKey("bc1", 4, 4, bc1Mip),
        )
    }

    @Test
    fun `astc size follows the 4x4 block layout`() {
        assertEquals(16L, TextureCacheStore.astcSizeBytes(4, 4))
        assertEquals(16L, TextureCacheStore.astcSizeBytes(1, 1))
        assertEquals(64L, TextureCacheStore.astcSizeBytes(8, 8))
        assertEquals(16L, TextureCacheStore.expectedAstcSizeForKey("bc1_4x4_9d2b7c7354fe4e23.a4"))
        assertEquals(64L, TextureCacheStore.expectedAstcSizeForKey("bc6hu_8x8_0000000000000001.a4"))
    }

    @Test
    fun `atomic write only accepts payloads of the expected astc size`() {
        val dir = temp.newFolder("cache")
        val key = TextureCacheStore.contentKey("bc1", 4, 4, bc1Mip)

        assertFalse(TextureCacheStore.writeEntryAtomic(dir, key, ByteArray(15)))
        assertFalse(TextureCacheStore.hasEntry(dir, key))
        assertEquals(emptyList<String>(), dir.list()!!.toList())

        val payload = ByteArray(16) { 0x7f }
        assertTrue(TextureCacheStore.writeEntryAtomic(dir, key, payload))
        assertTrue(TextureCacheStore.hasEntry(dir, key))
        assertEquals(listOf(key), dir.list()!!.toList())
        assertTrue(payload.contentEquals(File(dir, key).readBytes()))
    }

    @Test
    fun `entry with the wrong size on disk is not treated as cached`() {
        val dir = temp.newFolder("cache")
        val key = TextureCacheStore.contentKey("bc7", 8, 8, bc1Mip)
        File(dir, key).writeBytes(ByteArray(8))
        assertFalse(TextureCacheStore.hasEntry(dir, key))
    }
}
