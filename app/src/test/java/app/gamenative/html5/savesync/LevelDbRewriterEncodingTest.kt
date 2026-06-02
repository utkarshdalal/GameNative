package app.gamenative.html5.savesync

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// wire-format tests for LeveldbManifestSynthesizer's MANIFEST synthesis helpers. these encode
// chromium-leveldb's binary VersionEdit + log-record framing — a single off-by-one in
// varint encoding or an incorrect CRC mask silently corrupts the synthesized manifest and
// iq80 fails to open. we lock the formats here against future drift.
//
// references:
//  - leveldb varint:        7-bit groups, MSB=continuation. RFC: leveldb db_format.md
//  - leveldb log record:    [4 CRC32C-masked][2 LE length][1 type][payload]
//  - leveldb CRC mask:      ((crc >>> 15) | (crc << 17)) + 0xa282ead8
class LevelDbRewriterEncodingTest {

    // ---------------- writeVarint ----------------

    @Test fun varint_zero_isSingleZeroByte() {
        val out = ByteArrayOutputStream()
        LeveldbManifestSynthesizer.writeVarint(out, 0L)
        assertArrayEquals(byteArrayOf(0), out.toByteArray())
    }

    @Test fun varint_one_isSingleOneByte() {
        val out = ByteArrayOutputStream()
        LeveldbManifestSynthesizer.writeVarint(out, 1L)
        assertArrayEquals(byteArrayOf(1), out.toByteArray())
    }

    @Test fun varint_127_isSingleByte_lastBeforeContinuation() {
        // 0x7F is the highest single-byte varint. 0x80 forces a continuation bit.
        val out = ByteArrayOutputStream()
        LeveldbManifestSynthesizer.writeVarint(out, 127L)
        assertArrayEquals(byteArrayOf(0x7F), out.toByteArray())
    }

    @Test fun varint_128_isTwoBytes() {
        val out = ByteArrayOutputStream()
        LeveldbManifestSynthesizer.writeVarint(out, 128L)
        // 128 = 0b10000000 → low 7 bits = 0, high 1 bit = 1 → [0x80, 0x01]
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), out.toByteArray())
    }

    @Test fun varint_184_isTwoBytes_realFileNumberFromProductionRepro() {
        // 184 = 0xb8 = 0b10111000 → low 7 bits = 0x38, with cont = 0xB8; high = 0x01
        val out = ByteArrayOutputStream()
        LeveldbManifestSynthesizer.writeVarint(out, 184L)
        assertArrayEquals(byteArrayOf(0xB8.toByte(), 0x01), out.toByteArray())
    }

    @Test fun varint_16384_isThreeBytes() {
        val out = ByteArrayOutputStream()
        LeveldbManifestSynthesizer.writeVarint(out, 16384L)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), out.toByteArray())
    }

    // ---------------- writeLengthPrefixed ----------------

    @Test fun lengthPrefixed_emptyBytes_emitsZeroVarintAndNoBody() {
        val out = ByteArrayOutputStream()
        LeveldbManifestSynthesizer.writeLengthPrefixed(out, ByteArray(0))
        assertArrayEquals(byteArrayOf(0), out.toByteArray())
    }

    @Test fun lengthPrefixed_shortString_lengthThenBytes() {
        val out = ByteArrayOutputStream()
        val payload = "hello".toByteArray(Charsets.UTF_8)
        LeveldbManifestSynthesizer.writeLengthPrefixed(out, payload)
        // [varint(5)] + "hello"
        assertArrayEquals(byteArrayOf(5, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()), out.toByteArray())
    }

    // ---------------- wrapInLogRecord ----------------

    @Test fun logRecord_envelopeShape() {
        // "abc" payload → 4 CRC + 2 length(3) + 1 type(1) + 3 payload = 10 bytes total.
        val payload = "abc".toByteArray(Charsets.US_ASCII)
        val record = LeveldbManifestSynthesizer.wrapInLogRecord(payload)
        assertEquals(4 + 2 + 1 + payload.size, record.size)
        // length is little-endian 16-bit
        assertEquals(payload.size and 0xFF, record[4].toInt() and 0xFF)
        assertEquals((payload.size ushr 8) and 0xFF, record[5].toInt() and 0xFF)
        // type byte is FULL = 1
        assertEquals(1, record[6].toInt() and 0xFF)
        // payload follows
        assertArrayEquals(payload, record.copyOfRange(7, 7 + payload.size))
    }

    @Test fun logRecord_crcMatches_levelDbMaskedCrc32c() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val record = LeveldbManifestSynthesizer.wrapInLogRecord(payload)

        // expected CRC: CRC32C over [type=1] + payload, then leveldb masked.
        val crc32c = CRC32C()
        crc32c.update(byteArrayOf(1))
        crc32c.update(payload)
        val raw = crc32c.value
        val masked = (((raw ushr 15) or (raw shl 17)) + 0xA282EAD8L) and 0xFFFFFFFFL

        val recordedCrc = (record[0].toLong() and 0xFFL) or
            ((record[1].toLong() and 0xFFL) shl 8) or
            ((record[2].toLong() and 0xFFL) shl 16) or
            ((record[3].toLong() and 0xFFL) shl 24)
        assertEquals(masked, recordedCrc)
    }

    @Test fun logRecord_emptyPayload_zeroLengthHeader() {
        val record = LeveldbManifestSynthesizer.wrapInLogRecord(ByteArray(0))
        // 4 CRC + 2 length(0) + 1 type = 7 bytes, no payload
        assertEquals(7, record.size)
        assertEquals(0, record[4].toInt() and 0xFF)
        assertEquals(0, record[5].toInt() and 0xFF)
        assertEquals(1, record[6].toInt() and 0xFF)
    }

    // ---------------- encodeVersionEdit ----------------

    @Test fun versionEdit_encodesComparator_logNumber_nextFileNumber_lastSequence_newFiles() {
        // realistic single-file case.
        val smallest = "a".toByteArray(Charsets.US_ASCII)
        val largest = "z".toByteArray(Charsets.US_ASCII)
        val body = LeveldbManifestSynthesizer.encodeVersionEdit(
            comparatorName = "leveldb.BytewiseComparator",
            logNumber = 193L,
            nextFileNumber = 195L,
            newFiles = listOf(Triple(186L, 570976L, smallest to largest)),
        )

        // walk the body and verify each tag in order
        var pos = 0
        // tag 1 (Comparator)
        assertEquals(1, body[pos].toInt()); pos++
        // varint length
        assertEquals(26, body[pos].toInt()); pos++
        assertArrayEquals(
            "leveldb.BytewiseComparator".toByteArray(Charsets.UTF_8),
            body.copyOfRange(pos, pos + 26),
        )
        pos += 26

        // tag 2 (LogNumber)
        assertEquals(2, body[pos].toInt()); pos++
        // varint(193) = 0xC1, 0x01
        assertEquals(0xC1.toByte(), body[pos]); pos++
        assertEquals(0x01.toByte(), body[pos]); pos++

        // tag 3 (NextFileNumber)
        assertEquals(3, body[pos].toInt()); pos++
        // varint(195) = 0xC3, 0x01
        assertEquals(0xC3.toByte(), body[pos]); pos++
        assertEquals(0x01.toByte(), body[pos]); pos++

        // tag 4 (LastSequence)
        assertEquals(4, body[pos].toInt()); pos++
        // varint(1_000_000_000) — just verify it consumes bytes; specific encoding tested elsewhere
        // 1_000_000_000 = 5 bytes in varint
        pos += 5

        // tag 7 (NewFile)
        assertEquals(7, body[pos].toInt()); pos++
        // level 0
        assertEquals(0, body[pos].toInt()); pos++
        // file number 186 = 0xBA, 0x01
        assertEquals(0xBA.toByte(), body[pos]); pos++
        assertEquals(0x01.toByte(), body[pos]); pos++
        // file size 570976 — 3-byte varint, skip exact verification
        pos += 3
        // smallest length-prefixed string
        assertEquals(1, body[pos].toInt()); pos++ // length=1
        assertEquals('a'.code.toByte(), body[pos]); pos++
        // largest length-prefixed string
        assertEquals(1, body[pos].toInt()); pos++ // length=1
        assertEquals('z'.code.toByte(), body[pos]); pos++

        assertEquals("body fully consumed", pos, body.size)
    }

    @Test fun versionEdit_multipleNewFiles_writeInOrder() {
        val body = LeveldbManifestSynthesizer.encodeVersionEdit(
            comparatorName = "leveldb.BytewiseComparator",
            logNumber = 0L,
            nextFileNumber = 200L,
            newFiles = listOf(
                Triple(186L, 100L, byteArrayOf(1) to byteArrayOf(2)),
                Triple(189L, 100L, byteArrayOf(3) to byteArrayOf(4)),
                Triple(192L, 100L, byteArrayOf(5) to byteArrayOf(6)),
            ),
        )
        // count tag-7 occurrences. tag 7 appears once per NewFile entry.
        var newFileCount = 0
        for (b in body) {
            if (b == 7.toByte()) newFileCount++
        }
        // floor: must be at least 3 (could include false positives in length/varint payloads,
        // but with 1-byte values our bytes are small enough that 7 is unique to the tag).
        assertTrue("expected >= 3 NewFile tags, found $newFileCount", newFileCount >= 3)
    }

    @Test fun versionEdit_idbComparatorName_isUsedVerbatim() {
        // chromium IDB requires the literal "idb_cmp1" string. iq80 reads this back via
        // CustomUserComparator(Idb1Comparator()) and must match. drift-lock the literal.
        val body = LeveldbManifestSynthesizer.encodeVersionEdit(
            comparatorName = "idb_cmp1",
            logNumber = 0L,
            nextFileNumber = 1L,
            newFiles = emptyList(),
        )
        // tag 1 + length(8) + "idb_cmp1"
        val expected = byteArrayOf(1, 8) +
            "idb_cmp1".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, body.copyOfRange(0, 10))
    }
}
