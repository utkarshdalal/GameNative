package app.gamenative.html5.savesync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xerial.snappy.Snappy

// verifies LevelDbRewriter.maybeDecompressSnappyValue against a captured Wayward
// FF 11 02 record (kCompressedWithSnappy). fixture extracted by Ff1102FixtureExtractor
// from /tmp/wayward-idb/... — record #62 (0_gameThumbnail, 53848 bytes raw).

// chromium wire format (idb_value_wrapping.cc): `<data_version_varint> ff 11 02 <snappy>`.
// decompressed output must start with native `ff 15 fe` wrapper + V8 SSV v15.
class LevelDbRewriterSnappyValueTest {

    @Test
    fun decompressesCapturedWaywardRecord() {
        val bytes = loadFixture("/html5-saves/wayward-ff1102-thumbnail.bin")
        assertEquals("fixture size drifted — re-run Ff1102FixtureExtractor", 53848, bytes.size)
        // header: leading varint `4d` then FF 11 02
        assertEquals(0x4D.toByte(), bytes[0])
        assertEquals(0xFF.toByte(), bytes[1])
        assertEquals(0x11.toByte(), bytes[2])
        assertEquals(0x02.toByte(), bytes[3])

        val result = LevelDbRewriter.maybeDecompressSnappyValue(bytes)

        // leading varint preserved, then native wrapper `ff 15 fe` + 12 zero trailer
        assertEquals(0x4D.toByte(), result[0])
        assertEquals(0xFF.toByte(), result[1])
        assertEquals(0x15.toByte(), result[2])
        assertEquals(0xFE.toByte(), result[3])
        for (i in 4..15) {
            assertEquals("byte $i should be 0 (native trailer)", 0.toByte(), result[i])
        }
        // V8 SSV v15 starts immediately after native wrapper: `ff 0f 6f` (version 15 + beginObject)
        assertEquals(0xFF.toByte(), result[16])
        assertEquals(0x0F.toByte(), result[17])
        assertEquals(0x6F.toByte(), result[18])

        // snappy declared uncompressed size = 76547 (varint `83 d6 04`), plus 1 leading varint byte.
        assertEquals(76548, result.size)
    }

    // models the post-maybeInlineBlobValue shape for a ff 11 01 (blob-wrapped) record:
    // main value contributes <leading_varint>, sidecar bytes start with ff 11 02. the sidecar is
    // SYNTHESIZED via Snappy.compress (round-trips the real snappy lib) instead of shipping a
    // ~0.5MB captured device blob — same concatenation + large-output decompress path, zero binary
    // fixture. decompressesCapturedWaywardRecord above remains the real-chromium-data anchor.
    @Test
    fun decompressesInlinedBlobSidecar() {
        // what chromium would have snappy-compressed: native wrapper (ff 15 fe + 12-zero trailer)
        // then V8 SSV (ff 0f 6f …). large + varied so rawUncompress exercises a real multi-KB
        // output buffer rather than a trivial single-block payload.
        val ssv = ByteArray(200_000) { (it * 31 + 7).toByte() }
        val payload = byteArrayOf(0xFF.toByte(), 0x15.toByte(), 0xFE.toByte()) +
            ByteArray(12) +
            byteArrayOf(0xFF.toByte(), 0x0F.toByte(), 0x6F.toByte()) +
            ssv
        val sidecar = byteArrayOf(0xFF.toByte(), 0x11.toByte(), 0x02.toByte()) + Snappy.compress(payload)
        assertEquals(0xFF.toByte(), sidecar[0])
        assertEquals(0x11.toByte(), sidecar[1])
        assertEquals(0x02.toByte(), sidecar[2])
        val synthetic = byteArrayOf(0x4D.toByte()) + sidecar

        val result = LevelDbRewriter.maybeDecompressSnappyValue(synthetic)

        // leading varint preserved; ff 11 02 replaced by the decompressed native-wrapped payload.
        assertEquals(0x4D.toByte(), result[0])
        assertEquals(0xFF.toByte(), result[1])
        assertEquals(0x15.toByte(), result[2])
        assertEquals(0xFE.toByte(), result[3])
        assertEquals(0xFF.toByte(), result[16])
        assertEquals(0x0F.toByte(), result[17])
        assertEquals(0x6F.toByte(), result[18])
        assertEquals(1 + payload.size, result.size)
        // full-fidelity round-trip: output is exactly leading-varint + the original payload.
        assertArrayEquals(byteArrayOf(0x4D.toByte()) + payload, result)
    }

    @Test
    fun nativeValuePassesThrough() {
        // already-native record: leading varint + ff 15 fe + 12 zeros + SSV
        val native = byteArrayOf(
            0x4D,
            0xFF.toByte(), 0x15.toByte(), 0xFE.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xFF.toByte(), 0x0F.toByte(), 0x6F.toByte(), 0x00,
        )
        assertArrayEquals(native, LevelDbRewriter.maybeDecompressSnappyValue(native))
    }

    @Test
    fun blobWrappedValuePassesThrough() {
        // ff 11 01 (kReplaceWithBlob) — handled by maybeInlineBlobValue, not this.
        val blob = byteArrayOf(
            0x4D,
            0xFF.toByte(), 0x11.toByte(), 0x01.toByte(),
            0x00, 0x01, 0x02, 0x03,
        )
        assertArrayEquals(blob, LevelDbRewriter.maybeDecompressSnappyValue(blob))
    }

    @Test
    fun truncatedValuePassesThrough() {
        // shorter than leading-varint + 3-byte marker — must not crash
        val tiny = byteArrayOf(0x4D, 0xFF.toByte())
        assertArrayEquals(tiny, LevelDbRewriter.maybeDecompressSnappyValue(tiny))
    }

    @Test
    fun corruptSnappyPayloadPassesThrough() {
        // matches the FF 11 02 marker but payload is not a valid snappy stream —
        // Snappy.uncompress throws, we log + return original so caller sees raw bytes.
        val bogus = byteArrayOf(
            0x4D,
            0xFF.toByte(), 0x11.toByte(), 0x02.toByte(),
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )
        val result = LevelDbRewriter.maybeDecompressSnappyValue(bogus)
        assertArrayEquals(bogus, result)
        assertTrue(true) // no exception reached here
    }

    private fun loadFixture(path: String): ByteArray =
        (javaClass.getResourceAsStream(path) ?: error("missing fixture: $path")).use { it.readBytes() }
}
