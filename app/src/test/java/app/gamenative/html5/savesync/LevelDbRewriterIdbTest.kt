package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// plan — IDB DatabaseNameKey-aware origin rewrite tests.
// covers Gap A (5-byte header matching real chromium bytes) + Gap B (partition-agnostic
// origin match for `@<n>` storage-partitioning suffix, 
// verifies varint re-encode (shrink + grow), origin-not-found passthrough,
// internal-db-id passthrough, corrupt-varint no-crash,
// desktop-probe ground-truth bytes round-trip, bare / @1 / @2 partition variants.
class LevelDbRewriterIdbTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("idb-rewriter-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    // basic round-trip: origin replaced, internal key untouched
    @Test
    fun rewriteIdbOrigin_shortOriginReplace_roundTrips() {
        val src = File(tmpRoot, "src-basic")
        val dst = File(tmpRoot, "dst-basic")
        FixtureBuilder.idbWithDatabaseName(src, "https_game-steam_379210_0", "GameDB")

        LevelDbRewriter.rewriteIdbOrigin(src, dst, "https_game-steam_379210_0", "file__0")

        val keys = collectKeys(dst, useIdb1 = true)
        // at least one DatabaseNameKey with new origin
        val originBytes = OriginCodec.utf16BePrefixBytes("file__0")
        val header = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val found = keys.any { k ->
            k.size >= header.size + 1 + originBytes.size &&
                k.copyOfRange(0, header.size).contentEquals(header) &&
                containsOriginAt(k, originBytes)
        }
        assertTrue("expected DatabaseNameKey with new origin bytes", found)
        // internal db-id key must survive
        val internalKey = byteArrayOf(1, 42, 0, 1, 0, 0, 0, 1)
        assertTrue("internal db-id key must survive rewrite", keys.any { it.contentEquals(internalKey) })
    }

    // varint shrink: 25 CU origin (1-byte varint) → 7 CU origin (1-byte varint)
    @Test
    fun rewriteIdbOrigin_varintShrink_roundTrips() {
        val fromOrigin = "https_game-steam_379210_0" // 25 chars → 25 CU, varint=1 byte
        val toOrigin = "file__0"                      // 7 chars → 7 CU, varint=1 byte
        val src = File(tmpRoot, "src-shrink")
        val dst = File(tmpRoot, "dst-shrink")
        FixtureBuilder.idbWithDatabaseName(src, fromOrigin, "MyDB")

        LevelDbRewriter.rewriteIdbOrigin(src, dst, fromOrigin, toOrigin)

        val keys = collectKeys(dst, useIdb1 = true)
        val header = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val toBytes = OriginCodec.utf16BePrefixBytes(toOrigin)
        val dbKey = keys.find { k ->
            k.size >= header.size && k.copyOfRange(0, header.size).contentEquals(header)
        }
        assertNotNull("DatabaseNameKey must exist in dst", dbKey)
        dbKey!!
        // varint at offset 5 encodes 7 (1 byte: 0x07)
        assertEquals("varint byte for 7 CU", 0x07.toByte(), dbKey[5])
        // origin bytes at offset 6 should be UTF-16BE of "file__0" (14 bytes)
        assertArrayEquals(
            "origin UTF-16BE bytes",
            toBytes,
            dbKey.copyOfRange(6, 6 + toBytes.size),
        )
        // total key length: 5 (header) + 1 (varint) + 14 (origin) + dbname suffix
        // dbname "MyDB" = 4 CU → 1-byte varint + 8 UTF-16BE bytes = 9 bytes
        assertEquals("expected key length 5+1+14+9=29", 29, dbKey.size)
    }

    // varint grow: origin with >=128 CUs (2-byte varint) → 7 CU origin (1-byte varint).
    // fixture must be built with a 135-char origin so FixtureBuilder encodes 2-byte varint.
    @Test
    fun rewriteIdbOrigin_varintGrow_roundTrips() {
        // 135-char origin: "https_" + 'a'*130 + "_0" (scheme + 130 host chars + port)
        val longHost = "a".repeat(130)
        val fromOrigin = "https_${longHost}_0" // 135 CU → 2-byte varint (0x87 0x01)
        val toOrigin = "file__0"               // 7 CU → 1-byte varint
        val src = File(tmpRoot, "src-grow")
        val dst = File(tmpRoot, "dst-grow")
        FixtureBuilder.idbWithDatabaseName(src, fromOrigin, "D")

        LevelDbRewriter.rewriteIdbOrigin(src, dst, fromOrigin, toOrigin)

        val keys = collectKeys(dst, useIdb1 = true)
        val header = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val dbKey = keys.find { k ->
            k.size >= 5 && k.copyOfRange(0, 5).contentEquals(header)
        }
        assertNotNull("DatabaseNameKey in dst", dbKey)
        dbKey!!
        // varint should now be 1-byte (0x07)
        assertEquals("varint shrunk to 1 byte (value 7)", 0x07.toByte(), dbKey[5])
        // length decrease: (2-byte - 1-byte) varint + (135-7)*2 origin bytes = 1 + 256 = 257 bytes shorter
        // src key len: 5 + 2 + 270 + 1-byte-varint("D") + 2 = 5+2+270+1+2 = 280
        // dst key len: 5 + 1 + 14 + 1 + 2 = 23
        // assert dst key is shorter than a hypothetical same-size key
        assertTrue("dst key shorter than 5+2+270+dbname", dbKey.size < 5 + 2 + 270)
        // specifically: 5+1+14+1+2 = 23
        assertEquals("dst key length 23", 23, dbKey.size)
    }

    // non-matching origin → all keys pass through byte-for-byte
    @Test
    fun rewriteIdbOrigin_originBytesNotFound_keyPassesThrough() {
        val src = File(tmpRoot, "src-nomatch")
        val dst = File(tmpRoot, "dst-nomatch")
        FixtureBuilder.idbWithDatabaseName(src, "https_other_0", "Z")

        LevelDbRewriter.rewriteIdbOrigin(src, dst, "https_game-steam_379210_0", "file__0")

        // snapshot src keys for comparison
        val srcKeys = collectKeys(src, useIdb1 = true)
        val dstKeys = collectKeys(dst, useIdb1 = true)
        assertEquals("key count unchanged", srcKeys.size, dstKeys.size)
        for (sk in srcKeys) {
            assertTrue(
                "src key must appear byte-for-byte in dst",
                dstKeys.any { it.contentEquals(sk) },
            )
        }
    }

    // internal db-id key (key[0]==0x01) passes through verbatim — NOT a DatabaseNameKey
    @Test
    fun rewriteIdbOrigin_internalDbIdKey_passesThrough() {
        val src = File(tmpRoot, "src-internal")
        val dst = File(tmpRoot, "dst-internal")
        FixtureBuilder.idbWithDatabaseName(src, "https_game-steam_379210_0", "Main")

        LevelDbRewriter.rewriteIdbOrigin(src, dst, "https_game-steam_379210_0", "file__0")

        val internalKey = byteArrayOf(1, 42, 0, 1, 0, 0, 0, 1)
        val dstKeys = collectKeys(dst, useIdb1 = true)
        val found = dstKeys.any { it.contentEquals(internalKey) }
        assertTrue("internal db-id key must exist unchanged in dst", found)
    }

    // corrupt varint (unterminated: 3 continuation bytes with no terminal) — must NOT crash.
    // acceptable outcome: key passes through (null from helper) or classifyFailure.
    @Test
    fun rewriteIdbOrigin_corruptVarint_doesNotCrash() {
        val src = File(tmpRoot, "src-corrupt")
        val dst = File(tmpRoot, "dst-corrupt")

        // build a custom IDB with a key whose varint is unterminated.
        // 5-byte header + 3 continuation bytes with no terminal varint byte
        val corruptKey = byteArrayOf(0, 0, 0, 0, 0xC9.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())
        val normal = byteArrayOf(1, 0, 0, 0, 1) // non-origin internal key
        buildRawIdb(src) { db ->
            db.put(corruptKey, byteArrayOf(7))
            db.put(normal, byteArrayOf(8))
        }

        // must complete without throwing (corrupt varint → null → pass-through) or throw SaveSyncFailure
        var threw: Throwable? = null
        try {
            LevelDbRewriter.rewriteIdbOrigin(src, dst, "https_game-steam_379210_0", "file__0")
        } catch (f: SaveSyncFailure) {
            threw = f // classified failure is acceptable
        } catch (t: Throwable) {
            threw = t
            assertTrue("unexpected exception type (not SaveSyncFailure): ${t::class.simpleName}", false)
        }
        // if no exception: dst should have both keys (corrupt one passes through)
        if (threw == null) {
            val dstKeys = collectKeys(dst, useIdb1 = true)
            assertTrue("at least one key in dst", dstKeys.isNotEmpty())
        }
    }

    // encodeLeb128 boundary values (internal helper, test via reflection)
    @Test
    fun encodeLeb128_boundary() {
        assertArrayEquals("0 encodes to [0x00]", byteArrayOf(0x00), LevelDbRewriter.encodeLeb128(0L))
        assertArrayEquals("127 encodes to [0x7F]", byteArrayOf(0x7F), LevelDbRewriter.encodeLeb128(127L))
        assertArrayEquals("128 encodes to [0x80, 0x01]", byteArrayOf(0x80.toByte(), 0x01), LevelDbRewriter.encodeLeb128(128L))
        assertArrayEquals("16383 encodes to [0xFF, 0x7F]", byteArrayOf(0xFF.toByte(), 0x7F), LevelDbRewriter.encodeLeb128(16383L))
        assertArrayEquals("16384 encodes to [0x80, 0x80, 0x01]", byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), LevelDbRewriter.encodeLeb128(16384L))
    }

    // ========== plan new coverage ==========

    // GAP A + B ground truth — real desktop wayward DatabaseNameKey (captured by WaywardDesktopProbe).
    // raw bytes: `00 00 00 00 c9 09 00 66 00 69 00 6c 00 65 00 5f 00 5f 00 30 00 40 00 31`
    // header: 00 00 00 00 c9 (5 bytes — length-packed + 3 varints + type 0xC9)
    // varint: 09 (9 code units)
    // origin: UTF-16BE of "file__0@1" (18 bytes, partition suffix @1)
    // this test writes that exact key + round-trips via rewriteIdbOrigin(file__0 → https_game-steam_379210_0).
    // expected emitted origin: "https_game-steam_379210_0@1" (partition suffix preserved).
    @Test
    fun rewriteIdbOrigin_desktopProbeBytes_roundTripsToWebViewForm() {
        val src = File(tmpRoot, "src-desktop")
        val dst = File(tmpRoot, "dst-desktop")

        val desktopProbeKey = rawKeyFromHex(
            "00 00 00 00 c9 09 00 66 00 69 00 6c 00 65 00 5f 00 5f 00 30 00 40 00 31",
        )
        buildRawIdb(src) { db ->
            db.put(desktopProbeKey, byteArrayOf(42))
        }

        LevelDbRewriter.rewriteIdbOrigin(src, dst, "file__0", "https_game-steam_379210_0")

        val keys = collectKeys(dst, useIdb1 = true)
        val header = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val dbKey = keys.find { k -> k.size >= 5 && k.copyOfRange(0, 5).contentEquals(header) }
        assertNotNull("rewritten DatabaseNameKey with 5-byte header must exist in dst", dbKey)
        dbKey!!

        // decode varint at offset 5 — should encode 26 code units (25 base + 1 partition `@1` — wait:
        // "https_game-steam_379210_0" = 25 CU, partition "@1" = 2 CU → 27 CU total.
        // but actually desktop origin "file__0@1" = 9 CU, base "file__0" = 7, suffix "@1" = 2.
        // new base "https_game-steam_379210_0" = 25 CU + suffix 2 = 27 CU.
        val varint = LevelDbRewriter.decodeLeb128At(dbKey, 5)
        assertNotNull("varint must decode", varint)
        assertEquals("rewritten varint encodes 27 code units (25 base + 2 suffix)", 27L, varint!!.first)

        // origin slice starts at 5 + varintSize
        val originStart = 5 + varint.second
        val originLen = varint.first.toInt() * 2
        val originSlice = dbKey.copyOfRange(originStart, originStart + originLen)
        val expectedOrigin = "https_game-steam_379210_0@1".toByteArray(Charsets.UTF_16BE)
        assertArrayEquals(
            "rewritten origin must preserve @1 partition suffix",
            expectedOrigin,
            originSlice,
        )
    }

    // partition-agnostic — bare `file__0` (no partition) matches without emitting a suffix.
    @Test
    fun rewriteIdbOrigin_bareFileUnderscore0_matchesWithoutPartition() {
        val src = File(tmpRoot, "src-bare")
        val dst = File(tmpRoot, "dst-bare")
        FixtureBuilder.idbWithDatabaseName(src, "file__0", "GameDB")

        LevelDbRewriter.rewriteIdbOrigin(src, dst, "file__0", "https_game-steam_379210_0")

        val keys = collectKeys(dst, useIdb1 = true)
        val header = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val dbKey = keys.find { k -> k.size >= 5 && k.copyOfRange(0, 5).contentEquals(header) }
        assertNotNull("DatabaseNameKey with 5-byte header in dst", dbKey)
        dbKey!!

        val varint = LevelDbRewriter.decodeLeb128At(dbKey, 5)!!
        assertEquals("emitted origin is 25 CU (no partition)", 25L, varint.first)

        val originStart = 5 + varint.second
        val originLen = varint.first.toInt() * 2
        val originSlice = dbKey.copyOfRange(originStart, originStart + originLen)
        val expected = "https_game-steam_379210_0".toByteArray(Charsets.UTF_16BE)
        assertArrayEquals("no partition suffix appended when source has none", expected, originSlice)
    }

    // partition-agnostic — `file__0@1` preserves @1 suffix after rewrite.
    // uses buildRawIdb + synthesized 5-byte-header key so RED is self-contained
    // (FixtureBuilder partition-suffix support lands in ).
    @Test
    fun rewriteIdbOrigin_partition_at1_preservesSuffix() {
        val src = File(tmpRoot, "src-at1")
        val dst = File(tmpRoot, "dst-at1")
        buildRawIdb(src) { db ->
            db.put(makeDatabaseNameKey("file__0@1", "GameDB"), byteArrayOf(1))
        }

        LevelDbRewriter.rewriteIdbOrigin(src, dst, "file__0", "https_game-steam_379210_0")

        val keys = collectKeys(dst, useIdb1 = true)
        val header = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val dbKey = keys.find { k -> k.size >= 5 && k.copyOfRange(0, 5).contentEquals(header) }
        assertNotNull("DatabaseNameKey with 5-byte header in dst", dbKey)
        dbKey!!

        val varint = LevelDbRewriter.decodeLeb128At(dbKey, 5)!!
        assertEquals("emitted origin is 27 CU (25 base + 2 suffix)", 27L, varint.first)

        val originStart = 5 + varint.second
        val originLen = varint.first.toInt() * 2
        val originSlice = dbKey.copyOfRange(originStart, originStart + originLen)
        val expected = "https_game-steam_379210_0@1".toByteArray(Charsets.UTF_16BE)
        assertArrayEquals("@1 partition suffix must be preserved", expected, originSlice)
    }

    // partition-agnostic — @2 preserved even when base SHRINKS (https→file).
    // locks suffix copy-through independently of base length change.
    @Test
    fun rewriteIdbOrigin_partition_at2_preservesSuffix() {
        val src = File(tmpRoot, "src-at2")
        val dst = File(tmpRoot, "dst-at2")
        buildRawIdb(src) { db ->
            db.put(
                makeDatabaseNameKey("https_game-steam_379210_0@2", "GameDB"),
                byteArrayOf(1),
            )
        }

        LevelDbRewriter.rewriteIdbOrigin(src, dst, "https_game-steam_379210_0", "file__0")

        val keys = collectKeys(dst, useIdb1 = true)
        val header = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val dbKey = keys.find { k -> k.size >= 5 && k.copyOfRange(0, 5).contentEquals(header) }
        assertNotNull("DatabaseNameKey with 5-byte header in dst", dbKey)
        dbKey!!

        val varint = LevelDbRewriter.decodeLeb128At(dbKey, 5)!!
        assertEquals("emitted origin is 9 CU (7 base + 2 suffix)", 9L, varint.first)

        val originStart = 5 + varint.second
        val originLen = varint.first.toInt() * 2
        val originSlice = dbKey.copyOfRange(originStart, originStart + originLen)
        val expected = "file__0@2".toByteArray(Charsets.UTF_16BE)
        assertArrayEquals("@2 partition suffix preserved through base shrink", expected, originSlice)
    }

    // inbound direction (webview → file): webview-form bare origin strips cleanly to `file__0`.
    // confirms non-partitioned webview keys round-trip without spurious suffix emission.
    @Test
    fun rewriteIdbOrigin_inbound_webViewToFile_stripsWithoutSuffix() {
        val src = File(tmpRoot, "src-inbound")
        val dst = File(tmpRoot, "dst-inbound")
        FixtureBuilder.idbWithDatabaseName(src, "https_game-steam_379210_0", "GameDB")

        LevelDbRewriter.rewriteIdbOrigin(src, dst, "https_game-steam_379210_0", "file__0")

        val keys = collectKeys(dst, useIdb1 = true)
        val header = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val dbKey = keys.find { k -> k.size >= 5 && k.copyOfRange(0, 5).contentEquals(header) }
        assertNotNull("DatabaseNameKey with 5-byte header in dst", dbKey)
        dbKey!!

        val varint = LevelDbRewriter.decodeLeb128At(dbKey, 5)!!
        assertEquals("bare inbound: 7 CU for file__0 (no suffix)", 7L, varint.first)

        val originStart = 5 + varint.second
        val originLen = varint.first.toInt() * 2
        val originSlice = dbKey.copyOfRange(originStart, originStart + originLen)
        val expected = "file__0".toByteArray(Charsets.UTF_16BE)
        assertArrayEquals("inbound emits bare file__0 when src had no suffix", expected, originSlice)
    }

    // --- helpers ---

    private fun containsOriginAt(key: ByteArray, originBytes: ByteArray): Boolean {
        // look for varint at offset 5 + origin bytes immediately after
        if (key.size < 6) return false
        val varint = LevelDbRewriter.decodeLeb128At(key, 5) ?: return false
        val start = 5 + varint.second
        if (start + originBytes.size > key.size) return false
        return key.copyOfRange(start, start + originBytes.size).contentEquals(originBytes)
    }

    // parse space-separated hex pairs into bytes. matches WaywardDesktopProbe dump format
    // so on-the-wire bytes from the probe can be pasted directly into fixture literals.
    private fun rawKeyFromHex(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    // synth a chromium DatabaseNameKey with 5-byte header (header 00 00 00 00 c9,
    // varint origin-CU, UTF-16BE origin, varint dbName-CU, UTF-16BE dbName).
    // used for partition-variant tests in RED — decoupled from FixtureBuilder
    // so the RED test file compiles before adds partition-suffix fixture support.
    private fun makeDatabaseNameKey(originFull: String, databaseName: String): ByteArray {
        val originBytes = originFull.toByteArray(Charsets.UTF_16BE)
        val dbNameBytes = databaseName.toByteArray(Charsets.UTF_16BE)
        return byteArrayOf(0, 0, 0, 0, 0xC9.toByte()) +
            LevelDbRewriter.encodeLeb128((originBytes.size / 2).toLong()) +
            originBytes +
            LevelDbRewriter.encodeLeb128((dbNameBytes.size / 2).toLong()) +
            dbNameBytes
    }

    private fun collectKeys(dir: File, useIdb1: Boolean): List<ByteArray> {
        val options = Options().apply {
            createIfMissing(false)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            if (useIdb1) comparator(Idb1Comparator())
        }
        val ldbFiles = dir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty()
        ldbFiles.forEach { f -> f.renameTo(File(f.parentFile, f.nameWithoutExtension + ".sst")) }
        return try {
            Iq80DBFactory.factory.open(dir, options).use { db ->
                val keys = mutableListOf<ByteArray>()
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) keys += iter.next().key
                }
                keys
            }
        } finally {
            dir.listFiles { _, name -> name.endsWith(".sst") }.orEmpty()
                .forEach { f -> f.renameTo(File(f.parentFile, f.nameWithoutExtension + ".ldb")) }
        }
    }

    // write a raw IDB-shaped leveldb without using FixtureBuilder (for corrupt-key tests)
    private fun buildRawIdb(dir: File, block: (org.iq80.leveldb.DB) -> Unit) {
        dir.mkdirs()
        val options = Options().apply {
            createIfMissing(true)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            comparator(Idb1Comparator())
        }
        Iq80DBFactory.factory.open(dir, options).use { db ->
            block(db)
            (db as? org.iq80.leveldb.impl.DbImpl)?.flushMemTable()
        }
        // rename .sst → .ldb to match chromium convention
        dir.listFiles { _, n -> n.endsWith(".sst") }?.forEach { f ->
            f.renameTo(File(f.parentFile, f.nameWithoutExtension + ".ldb"))
        }
    }
}
