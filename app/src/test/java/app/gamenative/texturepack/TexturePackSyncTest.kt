package app.gamenative.texturepack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class TexturePackSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cacheDir(): File = temp.newFolder("texcache")

    private fun entry(dir: File, key: String): File =
        File(dir, key).also { it.writeBytes(ByteArray(TextureCacheStore.expectedAstcSizeForKey(key)!!.toInt())) }

    private fun serverEntry(dir: File, key: String): File =
        File(dir, key).also { it.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x00)) }

    private fun source(dir: File, key: String, size: Int = 8, modified: Long = 1_000L): File =
        TexturePackSync.sourceFile(dir, key).also {
            it.writeBytes(ByteArray(size))
            it.setLastModified(modified)
        }

    @Test
    fun `key of a bc1 source uses the 6x6 out tag`() {
        val dir = cacheDir()
        assertEquals("bc1_64x64_0000000000000001.a6", TexturePackSync.keyOf(File(dir, "bc1_64x64_0000000000000001.src")))
    }

    @Test
    fun `key of a bc7 source uses the 4x4 out tag`() {
        val dir = cacheDir()
        assertEquals("bc7_64x64_0000000000000001.a4", TexturePackSync.keyOf(File(dir, "bc7_64x64_0000000000000001.src")))
    }

    @Test
    fun `sidecar name strips either out tag`() {
        val dir = cacheDir()
        assertEquals("bc7_8x8_0000000000000002.src", TexturePackSync.sourceFile(dir, "bc7_8x8_0000000000000002.a4").name)
        assertEquals("bc1_8x8_0000000000000003.src", TexturePackSync.sourceFile(dir, "bc1_8x8_0000000000000003.a6").name)
    }

    @Test
    fun `cached keys accept both out tags`() {
        val dir = cacheDir()
        val a4 = "bc7_4x4_0000000000000004.a4"
        val a6 = "bc1_4x4_0000000000000005.a6"
        entry(dir, a4)
        entry(dir, a6)
        File(dir, "bc1_4x4_0000000000000006.txt").writeBytes(ByteArray(16))
        assertEquals(listOf(a4, a6).sorted(), TexturePackSync.cachedKeys(dir).sorted())
    }

    @Test
    fun `cached keys map device made bc1 entries to the 6x6 key`() {
        val dir = cacheDir()
        File(dir, "bc1_4x4_0000000000000007.a4").writeBytes(ByteArray(16))
        File(dir, "bc1_4x4_0000000000000008.a8").writeBytes(ByteArray(16))
        File(dir, "bc7_4x4_0000000000000009.a8").writeBytes(ByteArray(16))
        assertEquals(
            listOf(
                "bc1_4x4_0000000000000007.a6",
                "bc1_4x4_0000000000000008.a6",
                "bc7_4x4_0000000000000009.a4",
            ),
            TexturePackSync.cachedKeys(dir).sorted(),
        )
    }

    @Test
    fun `pack keys hold one 6x6 key for a bc1 texture made at 4x4 with a sidecar`() {
        val dir = cacheDir()
        File(dir, "bc1_4x4_000000000000000d.a4").writeBytes(ByteArray(16))
        File(dir, "bc1_4x4_000000000000000d.src").writeBytes(ByteArray(8))
        assertEquals(listOf("bc1_4x4_000000000000000d.a6"), TexturePackSync.packKeys(dir))
    }

    @Test
    fun `bc1 source beside a 4x4 or 8x8 entry uploads as 6x6`() {
        val dir = cacheDir()
        assertEquals(
            "bc1_64x64_0000000000000001.src",
            TexturePackSync.sourceFile(dir, "bc1_64x64_0000000000000001.a4").name,
        )
        assertEquals(
            "bc1_64x64_0000000000000001.a6",
            TexturePackSync.keyOf(TexturePackSync.sourceFile(dir, "bc1_64x64_0000000000000001.a4")),
        )
        assertEquals(
            "bc1_64x64_0000000000000001.a6",
            TexturePackSync.keyOf(TexturePackSync.sourceFile(dir, "bc1_64x64_0000000000000001.a8")),
        )
    }

    @Test
    fun `download picks the server 6x6 entry when only a device made 4x4 entry exists`() {
        val dir = cacheDir()
        val local = "bc1_4x4_0000000000000020.a4"
        val server = "bc1_4x4_0000000000000020.a6"
        File(dir, local).writeBytes(ByteArray(16))
        TexturePackSync.sourceFile(dir, local).writeBytes(ByteArray(8))

        assertEquals(listOf(server), TexturePackSync.downloadKeys(dir, listOf(PackEntry(server, 16L)), emptySet()))
    }

    @Test
    fun `ready server entries count only downloaded server made entries`() {
        val dir = cacheDir()
        val downloaded = "bc1_4x4_0000000000000030.a6"
        val deviceMade = "bc1_4x4_0000000000000031.a6"
        val missing = "bc1_4x4_0000000000000032.a6"
        val pending = "bc1_4x4_0000000000000033.a6"
        serverEntry(dir, downloaded)
        entry(dir, deviceMade)
        serverEntry(dir, pending)

        val entries = listOf(
            PackEntry(downloaded, 16L),
            PackEntry(deviceMade, 16L),
            PackEntry(missing, 16L),
            PackEntry(pending, 16L, pending = true),
        )
        assertEquals(1, TexturePackSync.readyServerEntries(dir, entries, emptySet()))
    }

    @Test
    fun `server pack is present only with a fingerprint and at least one server entry`() {
        assertTrue(TexturePackGate.packPresent("fp", "3"))
        assertFalse(TexturePackGate.packPresent("", "3"))
        assertFalse(TexturePackGate.packPresent("fp", "0"))
        assertFalse(TexturePackGate.packPresent("fp", ""))
        assertFalse(TexturePackGate.packPresent("fp", "x"))
    }

    @Test
    fun `sidecar name drops the entry suffix`() {
        val dir = cacheDir()
        val key = "bc1_4x4_0000000000000001.a6"
        assertEquals("bc1_4x4_0000000000000001.src", TexturePackSync.sourceFile(dir, key).name)
        assertEquals(key, TexturePackSync.keyOf(TexturePackSync.sourceFile(dir, key)))
    }

    @Test
    fun `pack keys cover both encoded entries and pending sources`() {
        val dir = cacheDir()
        val encoded = "bc1_4x4_000000000000000a.a6"
        val deviceMade = "bc1_4x4_000000000000000b.a6"
        entry(dir, encoded)
        entry(dir, deviceMade)
        source(dir, deviceMade)
        val orphan = "bc1_4x4_000000000000000c.a6"
        source(dir, orphan)

        assertEquals(listOf(encoded, deviceMade, orphan).sorted(), TexturePackSync.packKeys(dir).sorted())
        assertEquals(
            listOf(deviceMade, orphan).sorted(),
            TexturePackSync.sourceFiles(dir).map { TexturePackSync.keyOf(it) }.sorted(),
        )
    }

    @Test
    fun `download picks missing keys and device made keys but skips pending`() {
        val dir = cacheDir()
        val serverMade = "bc1_4x4_0000000000000010.a6"
        val deviceMade = "bc1_4x4_0000000000000011.a6"
        val absent = "bc1_4x4_0000000000000012.a6"
        val notEncoded = "bc1_4x4_0000000000000013.a6"
        val flaggedPending = "bc1_4x4_0000000000000014.a6"
        serverEntry(dir, serverMade)
        entry(dir, deviceMade)
        source(dir, deviceMade)
        serverEntry(dir, flaggedPending)
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

    @Test
    fun `pack keys follow the first use order of their files`() {
        val dir = cacheDir()
        val late = "bc7_4x4_0000000000000041.a4"
        val early = "bc1_4x4_0000000000000042.a6"
        val middle = "bc7_4x4_0000000000000043.a4"
        entry(dir, late).setLastModified(30_000L)
        File(dir, "bc1_4x4_0000000000000042.a4").also {
            it.writeBytes(ByteArray(16))
            it.setLastModified(50_000L)
        }
        source(dir, early, modified = 10_000L)
        source(dir, middle, modified = 20_000L)
        entry(dir, middle).setLastModified(40_000L)

        assertEquals(listOf(early, middle, late), TexturePackSync.packKeys(dir))
    }

    @Test
    fun `pack keys with equal times fall back to key order`() {
        val dir = cacheDir()
        val b = "bc7_4x4_0000000000000052.a4"
        val a = "bc7_4x4_0000000000000051.a4"
        entry(dir, b).setLastModified(5_000L)
        entry(dir, a).setLastModified(5_000L)

        assertEquals(listOf(a, b), TexturePackSync.packKeys(dir))
    }

    @Test
    fun `dimensions parse from the key`() {
        assertEquals(2048 to 512, TexturePackKeys.dimensionsOf("bc7_2048x512_0000000000000063.a4"))
        assertEquals(null, TexturePackKeys.dimensionsOf("not_a_key"))
    }

    @Test
    fun `upload batches respect the record and byte limits`() {
        val dir = cacheDir()
        val files = (1..5).map { source(dir, "bc7_4x4_000000000000007$it.a4", size = 10) }

        assertEquals(listOf(2, 2, 1), TexturePackSync.uploadBatches(files, maxRecords = 2, maxBytes = 1_000L).map { it.size })
        assertEquals(listOf(2, 2, 1), TexturePackSync.uploadBatches(files, maxRecords = 64, maxBytes = 25L).map { it.size })
        val big = source(dir, "bc7_4x4_0000000000000079.a4", size = 100)
        assertEquals(listOf(1, 1), TexturePackSync.uploadBatches(listOf(files[0], big), maxRecords = 64, maxBytes = 50L).map { it.size })
    }

    @Test
    fun `stored fingerprint uses the saved install listing without scanning`() = runBlocking {
        val install = temp.newFolder("install")
        File(install, "fresh.pak").writeBytes(ByteArray(4))
        val listing = File(temp.root, TexturePackSync.INSTALL_LISTING_FILE)
        val saved = listOf(PrepareFileEntry("saved/archive.pak", 10L))
        TexturePackSync.writeListing(listing, saved)

        assertEquals(saved, TexturePackSync.installFiles("fp", listing, install))
    }

    @Test
    fun `missing fingerprint scans the install and saves the listing`() = runBlocking {
        val install = temp.newFolder("install")
        File(install, "sub").mkdirs()
        File(install, "sub/archive.pak").writeBytes(ByteArray(3))
        val listing = File(temp.root, TexturePackSync.INSTALL_LISTING_FILE)
        TexturePackSync.writeListing(listing, listOf(PrepareFileEntry("stale.pak", 1L)))

        val files = TexturePackSync.installFiles("", listing, install)

        assertEquals(listOf(PrepareFileEntry("sub/archive.pak", 3L)), files)
        assertEquals(files, TexturePackSync.readListing(listing))
    }

    @Test
    fun `stored fingerprint without a saved listing scans once`() = runBlocking {
        val install = temp.newFolder("install")
        File(install, "archive.pak").writeBytes(ByteArray(2))
        val listing = File(temp.root, TexturePackSync.INSTALL_LISTING_FILE)

        assertEquals(listOf(PrepareFileEntry("archive.pak", 2L)), TexturePackSync.installFiles("fp", listing, install))
        assertTrue(listing.isFile)
    }
}
