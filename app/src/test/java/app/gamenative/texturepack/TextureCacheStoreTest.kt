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
            "bc1_4x4_9d2b7c7354fe4e23.a6",
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
    fun `astc size follows the 6x6 block layout for a6 keys`() {
        assertEquals(16L, TextureCacheStore.astcSizeBytes(6, 6, 6))
        assertEquals(64L, TextureCacheStore.astcSizeBytes(8, 8, 6))
        assertEquals(16L, TextureCacheStore.expectedAstcSizeForKey("bc1_4x4_9d2b7c7354fe4e23.a6"))
        assertEquals(64L, TextureCacheStore.expectedAstcSizeForKey("bc1_8x8_0000000000000001.a6"))
        assertEquals(1936L, TextureCacheStore.expectedAstcSizeForKey("bc1_64x64_0000000000000001.a6"))
        assertEquals(4096L, TextureCacheStore.expectedAstcSizeForKey("bc7_64x64_0000000000000001.a4"))
    }

    @Test
    fun `content key picks the out tag from the source tag`() {
        assertTrue(TextureCacheStore.contentKey("bc1", 4, 4, bc1Mip).endsWith(".a6"))
        assertTrue(TextureCacheStore.contentKey("bc7", 4, 4, bc1Mip).endsWith(".a4"))
        assertTrue(TextureCacheStore.contentKey("bc3", 4, 4, bc1Mip).endsWith(".a4"))
    }

    @Test
    fun `atomic write validates a raw 6x6 payload size`() {
        val dir = temp.newFolder("cache")
        val key = TextureCacheStore.contentKey("bc1", 64, 64, bc1Mip)

        assertFalse(TextureCacheStore.writeEntryAtomic(dir, key, ByteArray(4096)))
        assertTrue(TextureCacheStore.writeEntryAtomic(dir, key, ByteArray(1936) { 0x7f }))
        assertTrue(TextureCacheStore.hasEntry(dir, key))
        File(dir, key).writeBytes(ByteArray(4096))
        assertFalse(TextureCacheStore.hasEntry(dir, key))
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
    fun `atomic write accepts a zstd frame of any length`() {
        val dir = temp.newFolder("cache")
        val key = TextureCacheStore.contentKey("bc1", 4, 4, bc1Mip)
        val frame = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x01, 0x02)

        assertTrue(TextureCacheStore.writeEntryAtomic(dir, key, frame))
        assertTrue(TextureCacheStore.hasEntry(dir, key))
        assertTrue(TextureCacheStore.isServerEntry(dir, key))
        assertFalse(TextureCacheStore.isDeviceEntry(dir, key))
    }

    @Test
    fun `a raw payload of the expected size is a device entry`() {
        val dir = temp.newFolder("cache")
        val key = TextureCacheStore.contentKey("bc1", 4, 4, bc1Mip)

        assertTrue(TextureCacheStore.writeEntryAtomic(dir, key, ByteArray(16) { 0x7f }))
        assertTrue(TextureCacheStore.isDeviceEntry(dir, key))
        assertFalse(TextureCacheStore.isServerEntry(dir, key))
    }

    @Test
    fun `absent entries are neither server nor device made`() {
        val dir = temp.newFolder("cache")
        val key = TextureCacheStore.contentKey("bc1", 4, 4, bc1Mip)
        assertFalse(TextureCacheStore.isServerEntry(dir, key))
        assertFalse(TextureCacheStore.isDeviceEntry(dir, key))
    }

    @Test
    fun `entry with the wrong size on disk is not treated as cached`() {
        val dir = temp.newFolder("cache")
        val key = TextureCacheStore.contentKey("bc7", 8, 8, bc1Mip)
        File(dir, key).writeBytes(ByteArray(8))
        assertFalse(TextureCacheStore.hasEntry(dir, key))
    }

    @Test
    fun `prune removes other out tags and the source for the same stem`() {
        val dir = temp.newFolder("prune")
        val key = "bc1_4x4_0000000000000081.a6"
        val server = File(dir, key).also { it.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x00)) }
        val a4 = File(dir, "bc1_4x4_0000000000000081.a4").also { it.writeBytes(ByteArray(16)) }
        val a8 = File(dir, "bc1_4x4_0000000000000081.a8").also { it.writeBytes(ByteArray(16)) }
        val src = File(dir, "bc1_4x4_0000000000000081.src").also { it.writeBytes(ByteArray(8)) }
        val other = File(dir, "bc1_4x4_0000000000000082.a4").also { it.writeBytes(ByteArray(16)) }
        val otherSrc = File(dir, "bc1_4x4_0000000000000082.src").also { it.writeBytes(ByteArray(8)) }

        assertEquals(3, TextureCacheStore.pruneSuperseded(dir, key))
        assertTrue(server.isFile)
        assertFalse(a4.exists())
        assertFalse(a8.exists())
        assertFalse(src.exists())
        assertTrue(other.isFile)
        assertTrue(otherSrc.isFile)
    }

    @Test
    fun `prune keeps the oldest first use time on the new entry`() {
        val dir = temp.newFolder("prune-time")
        val key = "bc7_4x4_0000000000000083.a4"
        val server = File(dir, key).also {
            it.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x00))
            it.setLastModified(90_000L)
        }
        File(dir, "bc7_4x4_0000000000000083.a8").also {
            it.writeBytes(ByteArray(16))
            it.setLastModified(20_000L)
        }
        File(dir, "bc7_4x4_0000000000000083.src").also {
            it.writeBytes(ByteArray(8))
            it.setLastModified(10_000L)
        }

        TextureCacheStore.pruneSuperseded(dir, key)
        assertEquals(10_000L, server.lastModified())
    }

    @Test
    fun `prune with nothing superseded leaves the entry alone`() {
        val dir = temp.newFolder("prune-none")
        val key = "bc7_4x4_0000000000000084.a4"
        val server = File(dir, key).also {
            it.writeBytes(ByteArray(16))
            it.setLastModified(70_000L)
        }

        assertEquals(0, TextureCacheStore.pruneSuperseded(dir, key))
        assertTrue(server.isFile)
        assertEquals(70_000L, server.lastModified())
    }
}
