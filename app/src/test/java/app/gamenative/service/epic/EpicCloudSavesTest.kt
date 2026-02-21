package app.gamenative.service.epic

import app.gamenative.service.epic.manifest.BinaryManifest
import app.gamenative.service.epic.manifest.ChunkDataList
import app.gamenative.service.epic.manifest.ChunkInfo
import app.gamenative.service.epic.manifest.ChunkPart
import app.gamenative.service.epic.manifest.CustomFields
import app.gamenative.service.epic.manifest.EpicManifest
import app.gamenative.service.epic.manifest.FileManifest
import app.gamenative.service.epic.manifest.FileManifestList
import app.gamenative.service.epic.manifest.ManifestMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for Epic Cloud Saves manifest & chunk correctness.
 *
 * All expected hash values are generated from Legendary's reference implementation
 * (legendary/utils/rolling_hash.py, legendary/models/chunk.py, legendary/models/manifest.py)
 * and verified manually.
 */
class EpicCloudSavesTest {

    // -------------------------------------------------------------------------
    // Rolling hash — test vectors produced by Legendary's get_hash()
    // -------------------------------------------------------------------------

    private fun rollingHash(data: ByteArray): ULong =
        EpicCloudSavesManager.calculateRollingHash(data)

    @Test
    fun `rolling hash of empty array is zero`() {
        assertEquals(0uL, rollingHash(ByteArray(0)))
    }

    @Test
    fun `rolling hash matches Legendary for 'hello'`() {
        // Verified: python -c "from legendary.utils.rolling_hash import get_hash; print(hex(get_hash(b'hello')))"
        // = 0x58b665393bd872a  (i.e. 0x058B665393BD872A with leading zero)
        val expected = 0x058B665393BD872AuL
        assertEquals(expected, rollingHash("hello".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `rolling hash of 1MB zeros is zero`() {
        // table[0] = 0 because 0 has no bits set, so all 8 iterations are right-shifts of 0.
        // rotate(0) XOR 0 = 0 for every byte → final hash is always 0 for all-zero data.
        // Verified: get_hash(b'\x00' * 1048576) = 0x0
        assertEquals(0uL, rollingHash(ByteArray(1024 * 1024)))
    }

    @Test
    fun `rolling hash of 10 x FF bytes matches Legendary`() {
        // Verified: get_hash(b'\xff' * 10) = 0x3218245e5789c849
        val expected = 0x3218245E5789C849uL
        assertEquals(expected, rollingHash(ByteArray(10) { 0xFF.toByte() }))
    }

    @Test
    fun `rolling hash of Nuclear Throne string matches Legendary`() {
        // Verified: get_hash(b'Nuclear Throne save data test') = 0x828e301b81d11b24
        val expected = 0x828E301B81D11B24uL
        assertEquals(
            expected,
            rollingHash("Nuclear Throne save data test".toByteArray(Charsets.US_ASCII)),
        )
    }

    // -------------------------------------------------------------------------
    // CustomFields — read/write round-trip must be symmetric and include version byte
    // -------------------------------------------------------------------------

    @Test
    fun `CustomFields round-trips through write then read`() {
        val original = CustomFields()
        original["CloudSaveFolder"] = "{AppData}/nuclearthrone/"
        original["CloudSaveFolder_MAC"] = "{HOME}/nuclearthrone/"

        val buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        original.write(buf)
        buf.flip()

        val restored = CustomFields.read(buf)

        assertEquals("{AppData}/nuclearthrone/", restored["CloudSaveFolder"])
        assertEquals("{HOME}/nuclearthrone/", restored["CloudSaveFolder_MAC"])
    }

    @Test
    fun `CustomFields write produces correct binary layout`() {
        // Layout must be: size(4) + version(1) + count(4) + keys... + values...
        val cf = CustomFields()
        cf["Key"] = "Value"

        val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        cf.write(buf)
        buf.flip()

        val size = buf.int
        val version = buf.get() // version byte (must exist!)
        val count = buf.int

        assertEquals(0.toByte(), version)
        assertEquals(1, count)
        assertTrue("Section size should be > 9", size > 9)
    }

    @Test
    fun `CustomFields with no entries writes minimal valid section`() {
        val cf = CustomFields()
        val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        cf.write(buf)
        buf.flip()

        val size = buf.int
        val version = buf.get()
        val count = buf.int

        // size=9 (4 size + 1 version + 4 count), version=0, count=0
        assertEquals(9, size)
        assertEquals(0.toByte(), version)
        assertEquals(0, count)
    }

    // -------------------------------------------------------------------------
    // BinaryManifest — full serialize → readAll round-trip
    // -------------------------------------------------------------------------

    private fun buildMinimalManifest(): BinaryManifest {
        val m = BinaryManifest()
        m.meta = ManifestMeta().apply {
            featureLevel = 18
            dataVersion = 0
            appName = "TuracoFAKEACCOUNTID"
            buildVersion = "2026.02.20-15.38.35"
        }
        m.chunkDataList = ChunkDataList()
        m.fileManifestList = FileManifestList()
        m.customFields = CustomFields().apply {
            this["CloudSaveFolder"] = "{AppData}/nuclearthrone/"
        }
        return m
    }

    @Test
    fun `BinaryManifest serialize produces readable manifest`() {
        val original = buildMinimalManifest()
        val bytes = original.serialize()

        // Must start with the manifest magic
        val magic = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
        assertEquals(EpicManifest.HEADER_MAGIC, magic)

        // Must round-trip through readAll without throwing
        val restored = EpicManifest.readAll(bytes)
        assertNotNull(restored.meta)
        assertNotNull(restored.customFields)
    }

    @Test
    fun `BinaryManifest header is exactly 41 bytes`() {
        val m = buildMinimalManifest()
        val bytes = m.serialize()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        buf.int // magic
        val headerSize = buf.int
        assertEquals(41, headerSize)
    }

    @Test
    fun `BinaryManifest round-trips meta fields`() {
        val original = buildMinimalManifest()
        val restored = EpicManifest.readAll(original.serialize())

        assertEquals("TuracoFAKEACCOUNTID", restored.meta!!.appName)
        assertEquals("2026.02.20-15.38.35", restored.meta!!.buildVersion)
        assertEquals(18, restored.meta!!.featureLevel)
    }

    @Test
    fun `BinaryManifest round-trips CustomFields including CloudSaveFolder`() {
        val original = buildMinimalManifest()
        val restored = EpicManifest.readAll(original.serialize())

        assertEquals("{AppData}/nuclearthrone/", restored.customFields!!["CloudSaveFolder"])
    }

    @Test
    fun `BinaryManifest round-trips chunk data list`() {
        val m = buildMinimalManifest()

        val chunk = ChunkInfo().apply {
            guid = intArrayOf(0x11111111, 0x22222222, 0x33333333, 0x44444444)
            hash = 0xDEADBEEFCAFEBABEuL
            shaHash = ByteArray(20) { it.toByte() }
            groupNum = 42
            windowSize = 1024 * 1024
            fileSize = 512L
        }
        m.chunkDataList!!.elements.add(chunk)

        val restored = EpicManifest.readAll(m.serialize())
        val restoredChunk = restored.chunkDataList!!.elements.first()

        assertArrayEquals(chunk.guid, restoredChunk.guid)
        assertEquals(chunk.hash, restoredChunk.hash)
        assertEquals(chunk.groupNum, restoredChunk.groupNum)
        assertEquals(chunk.windowSize, restoredChunk.windowSize)
        assertEquals(chunk.fileSize, restoredChunk.fileSize)
    }

    @Test
    fun `BinaryManifest round-trips file manifest list with chunk parts`() {
        val m = buildMinimalManifest()
        val guid = intArrayOf(0xAAAAAAAA.toInt(), 0xBBBBBBBB.toInt(), 0xCCCCCCCC.toInt(), 0xDDDDDDDD.toInt())

        val chunk = ChunkInfo().apply {
            this.guid = guid
            hash = 0x1122334455667788uL
            shaHash = ByteArray(20)
            groupNum = 7
            windowSize = 1024 * 1024
            fileSize = 4096L
        }
        m.chunkDataList!!.elements.add(chunk)

        val fm = FileManifest().apply {
            filename = "SaveGame.sav"
            this.hash = ByteArray(20) { (it * 3).toByte() }
            fileSize = 1024L
            chunkParts.add(
                ChunkPart(
                    guid = guid,
                    offset = 0,
                    size = 1024,
                    fileOffset = 0L,
                ),
            )
        }
        m.fileManifestList!!.elements.add(fm)

        val restored = EpicManifest.readAll(m.serialize())
        val restoredFm = restored.fileManifestList!!.elements.first()

        assertEquals("SaveGame.sav", restoredFm.filename)
        assertEquals(1, restoredFm.chunkParts.size)
        assertArrayEquals(guid, restoredFm.chunkParts.first().guid)
        assertEquals(0, restoredFm.chunkParts.first().offset)
        assertEquals(1024, restoredFm.chunkParts.first().size)
    }

    // -------------------------------------------------------------------------
    // Chunk file — write then read consistency
    // -------------------------------------------------------------------------

    @Test
    fun `chunk header is exactly 66 bytes`() {
        val data = ByteArray(1024 * 1024)
        val guid = intArrayOf(1, 2, 3, 4)
        val hash = 0uL
        val shaHash = ByteArray(20)

        val result = EpicCloudSavesManager.compressChunk(data, guid, hash, shaHash)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)

        buf.int // magic
        val version = buf.int
        val headerSize = buf.int

        assertEquals(3, version)
        assertEquals(66, headerSize)
    }

    @Test
    fun `chunk round-trips through compressChunk then decompressChunk`() {
        val originalData = ByteArray(1024 * 1024).also {
            for (i in it.indices) it[i] = (i % 256).toByte()
        }
        val guid = intArrayOf(0x11111111, 0x22222222, 0x33333333, 0x44444444)
        val hash = rollingHash(originalData)
        val shaHash = java.security.MessageDigest.getInstance("SHA-1").digest(originalData)

        val compressed = EpicCloudSavesManager.compressChunk(originalData, guid, hash, shaHash)
        val decompressed = EpicCloudSavesManager.decompressChunk(compressed)

        assertArrayEquals(originalData, decompressed)
    }

    @Test
    fun `chunk GUID in header matches the supplied guid`() {
        val guid = intArrayOf(0xDEAD1234.toInt(), 0xBEEF5678.toInt(), 0xCAFE9ABC.toInt(), 0xFACEDEF0.toInt())
        val data = ByteArray(1024 * 1024)
        val result = EpicCloudSavesManager.compressChunk(data, guid, 0uL, ByteArray(20))

        val buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(16) // skip magic(4) + version(4) + headerSize(4) + compressedSize(4)
        val g0 = buf.int; val g1 = buf.int; val g2 = buf.int; val g3 = buf.int

        assertArrayEquals(guid, intArrayOf(g0, g1, g2, g3))
    }
}
