package app.gamenative.texturepack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TexturePackSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cacheDir(): File = temp.newFolder("texcache")

    private fun entry(dir: File, key: String): File =
        File(dir, key).also { it.writeBytes(ByteArray(TextureCacheStore.expectedAstcSizeForKey(key)!!.toInt())) }

    private fun source(dir: File, key: String, size: Int = 8, modified: Long = 1_000L): File =
        TexturePackSync.sourceFile(dir, key).also {
            it.writeBytes(ByteArray(size))
            it.setLastModified(modified)
        }

    @Test
    fun `sidecar name drops the entry suffix`() {
        val dir = cacheDir()
        val key = "bc1_4x4_0000000000000001.a4"
        assertEquals("bc1_4x4_0000000000000001.src", TexturePackSync.sourceFile(dir, key).name)
        assertEquals(key, TexturePackSync.keyOf(TexturePackSync.sourceFile(dir, key)))
    }

    @Test
    fun `pack keys cover both encoded entries and pending sources`() {
        val dir = cacheDir()
        val encoded = "bc1_4x4_000000000000000a.a4"
        val deviceMade = "bc1_4x4_000000000000000b.a4"
        entry(dir, encoded)
        entry(dir, deviceMade)
        source(dir, deviceMade)
        val orphan = "bc1_4x4_000000000000000c.a4"
        source(dir, orphan)

        assertEquals(listOf(encoded, deviceMade, orphan).sorted(), TexturePackSync.packKeys(dir))
        assertEquals(
            listOf(deviceMade, orphan).sorted(),
            TexturePackSync.sourceFiles(dir).map { TexturePackSync.keyOf(it) }.sorted(),
        )
    }

    @Test
    fun `download picks missing keys and device made keys but skips pending`() {
        val dir = cacheDir()
        val serverMade = "bc1_4x4_0000000000000010.a4"
        val deviceMade = "bc1_4x4_0000000000000011.a4"
        val absent = "bc1_4x4_0000000000000012.a4"
        val notEncoded = "bc1_4x4_0000000000000013.a4"
        val flaggedPending = "bc1_4x4_0000000000000014.a4"
        entry(dir, serverMade)
        entry(dir, deviceMade)
        source(dir, deviceMade)
        entry(dir, flaggedPending)
        source(dir, flaggedPending)

        val entries = listOf(
            PackEntry(serverMade, 16L),
            PackEntry(deviceMade, 16L),
            PackEntry(absent, 16L),
            PackEntry(notEncoded, 16L),
            PackEntry(flaggedPending, 16L, pending = true),
        )

        assertEquals(
            listOf(deviceMade, absent),
            TexturePackSync.downloadKeys(dir, entries, setOf(notEncoded)),
        )
    }

    @Test
    fun `settled keys merge server have with fresh uploads`() {
        val response = LookupResponse(
            have = listOf("a.a4"),
            want = listOf("b.a4", "c.a4"),
            pending = listOf("d.a4"),
            invalid = listOf("e.a4"),
        )
        assertEquals(setOf("a.a4", "b.a4"), TexturePackSync.settledKeys(response, listOf("b.a4")))
    }

    @Test
    fun `pending cap drops the oldest sources until the rest fit`() {
        val dir = cacheDir()
        val oldest = source(dir, "k_4x4_0000000000000001.a4", size = 40, modified = 1_000L)
        val middle = source(dir, "k_4x4_0000000000000002.a4", size = 40, modified = 2_000L)
        val newest = source(dir, "k_4x4_0000000000000003.a4", size = 40, modified = 3_000L)

        val doomed = TexturePackSync.overCapSources(TexturePackSync.sourceFiles(dir), capBytes = 50L)
        assertEquals(listOf(oldest.name, middle.name), doomed.map { it.name })
        assertTrue(newest.isFile)
    }

    @Test
    fun `pending cap keeps everything when the total fits`() {
        val dir = cacheDir()
        source(dir, "k_4x4_0000000000000001.a4", size = 10, modified = 1_000L)
        source(dir, "k_4x4_0000000000000002.a4", size = 10, modified = 2_000L)

        assertTrue(TexturePackSync.overCapSources(TexturePackSync.sourceFiles(dir), capBytes = 20L).isEmpty())
    }
}
