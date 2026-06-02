package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// origin match must be partition-agnostic: a `@<n>` storage-partitioning suffix is matched past and copied through.
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

    @Test
    fun rewriteIdbOrigin_shortOriginReplace_roundTrips() {
        val src = File(tmpRoot, "src-basic")
        val dst = File(tmpRoot, "dst-basic")
        FixtureBuilder.idbWithDatabaseName(src, "https_game-steam_379210_0", "GameDB")

        LevelDbRewriter.rewriteIdbOrigin(src, dst, "https_game-steam_379210_0", "file__0")

        val keys = collectKeys(dst, useIdb1 = true)
        val originBytes = OriginCodec.utf16BePrefixBytes("file__0")
        val header = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val found = keys.any { k ->
            k.size >= header.size + 1 + originBytes.size &&
                k.copyOfRange(0, header.size).contentEquals(header) &&
                containsOriginAt(k, originBytes)
        }
        assertTrue("expected DatabaseNameKey with new origin bytes", found)
        val internalKey = byteArrayOf(1, 42, 0, 1, 0, 0, 0, 1)
        assertTrue("internal db-id key must survive rewrite", keys.any { it.contentEquals(internalKey) })
    }

    @Test
    fun rewriteIdbOrigin_varintShrink_roundTrips() {
        val fromOrigin = "https_game-steam_379210_0" // 25 CU
        val toOrigin = "file__0"                      // 7 CU
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
        assertEquals("varint byte for 7 CU", 0x07.toByte(), dbKey[5])
        assertArrayEquals(
            "origin UTF-16BE bytes",
            toBytes,
            dbKey.copyOfRange(6, 6 + toBytes.size),
        )
        // dbname "MyDB" = 1-byte varint + 8 UTF-16BE bytes
        assertEquals("expected key length 5+1+14+9=29", 29, dbKey.size)
    }

    // varint width change: from-origin needs >= 128 CU so FixtureBuilder encodes a 2-byte varint, to-origin 1 byte.
    @Test
    fun rewriteIdbOrigin_varintGrow_roundTrips() {
        val longHost = "a".repeat(130)
        val fromOrigin = "https_${longHost}_0" // 135 CU: 2-byte varint (0x87 0x01)
        val toOrigin = "file__0"               // 7 CU: 1-byte varint
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
        assertEquals("varint shrunk to 1 byte (value 7)", 0x07.toByte(), dbKey[5])
        assertTrue("dst key shorter than 5+2+270+dbname", dbKey.size < 5 + 2 + 270)
        // header 5 + varint 1 + origin 14 + dbname "D" (1 + 2)
        assertEquals("dst key length 23", 23, dbKey.size)
    }

    // no database for the from-origin -> dst kept; copying through would replace it with databases the game can't see.
    @Test
    fun rewriteIdbOrigin_originBytesNotFound_returnsNullAndLeavesDestination() {
        val src = File(tmpRoot, "src-nomatch")
        val dst = File(tmpRoot, "dst-nomatch")
        FixtureBuilder.idbWithDatabaseName(src, "https_other_0", "Z")
        FixtureBuilder.idbWithDatabaseName(dst, "file__0", "GameDB")
        val before = snapshotFiles(dst)

        assertNull(LevelDbRewriter.rewriteIdbOrigin(src, dst, "https_game-steam_379210_0", "file__0"))

        val after = snapshotFiles(dst)
        assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after[name]) }
    }

    // internal db-id key (key[0]==0x01) is NOT a DatabaseNameKey and must pass through verbatim
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

    // unterminated varint must NOT crash: either the key is skipped or a classified SaveSyncFailure is thrown.
    @Test
    fun rewriteIdbOrigin_corruptVarint_doesNotCrash() {
        val src = File(tmpRoot, "src-corrupt")
        val dst = File(tmpRoot, "dst-corrupt")

        // 5-byte header + 3 continuation bytes with no terminal varint byte
        val corruptKey = byteArrayOf(0, 0, 0, 0, 0xC9.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())
        val normal = byteArrayOf(1, 0, 0, 0, 1) // non-origin internal key
        buildRawIdb(src) { db ->
            db.put(corruptKey, byteArrayOf(7))
            db.put(normal, byteArrayOf(8))
        }

        var threw: Throwable? = null
        try {
            LevelDbRewriter.rewriteIdbOrigin(src, dst, "https_game-steam_379210_0", "file__0")
        } catch (f: SaveSyncFailure) {
            threw = f
        } catch (t: Throwable) {
            threw = t
            assertTrue("unexpected exception type (not SaveSyncFailure): ${t::class.simpleName}", false)
        }
        // if no exception: src holds no parseable from-origin database, so dst is never written
        if (threw == null) {
            assertTrue("dst must not be created", !dst.exists())
        }
    }

    @Test
    fun encodeLeb128_boundary() {
        assertArrayEquals("0 encodes to [0x00]", byteArrayOf(0x00), LevelDbRewriter.encodeLeb128(0L))
        assertArrayEquals("127 encodes to [0x7F]", byteArrayOf(0x7F), LevelDbRewriter.encodeLeb128(127L))
        assertArrayEquals("128 encodes to [0x80, 0x01]", byteArrayOf(0x80.toByte(), 0x01), LevelDbRewriter.encodeLeb128(128L))
        assertArrayEquals("16383 encodes to [0xFF, 0x7F]", byteArrayOf(0xFF.toByte(), 0x7F), LevelDbRewriter.encodeLeb128(16383L))
        assertArrayEquals("16384 encodes to [0x80, 0x80, 0x01]", byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), LevelDbRewriter.encodeLeb128(16384L))
    }

    // ground truth: a DatabaseNameKey captured from desktop Chromium.
    // header 00 00 00 00 c9 (length-packed byte + 3 varints + type 0xC9), varint 09 (9 CU),
    // then UTF-16BE "file__0@1" -- the partition suffix must survive the rewrite.
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

        val varint = LevelDbRewriter.decodeLeb128At(dbKey, 5)
        assertNotNull("varint must decode", varint)
        assertEquals("rewritten varint encodes 27 code units (25 base + 2 suffix)", 27L, varint!!.first)

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

    // suffix copy-through must not depend on the base length staying the same
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

    private fun containsOriginAt(key: ByteArray, originBytes: ByteArray): Boolean {
        if (key.size < 6) return false
        val varint = LevelDbRewriter.decodeLeb128At(key, 5) ?: return false
        val start = 5 + varint.second
        if (start + originBytes.size > key.size) return false
        return key.copyOfRange(start, start + originBytes.size).contentEquals(originBytes)
    }

    // takes the probe's space-separated hex dump format so captured bytes paste straight in
    private fun rawKeyFromHex(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    // header 00 00 00 00 c9, varint origin-CU, UTF-16BE origin, varint dbName-CU, UTF-16BE dbName
    private fun makeDatabaseNameKey(originFull: String, databaseName: String): ByteArray {
        val originBytes = originFull.toByteArray(Charsets.UTF_16BE)
        val dbNameBytes = databaseName.toByteArray(Charsets.UTF_16BE)
        return byteArrayOf(0, 0, 0, 0, 0xC9.toByte()) +
            LevelDbRewriter.encodeLeb128((originBytes.size / 2).toLong()) +
            originBytes +
            LevelDbRewriter.encodeLeb128((dbNameBytes.size / 2).toLong()) +
            dbNameBytes
    }

    // a failed rewrite must leave the existing destination alone; wiping dst before the fallible
    // shadow/open/iterate steps would leave an empty Wine IDB for the exit upload.
    @Test
    fun rewriteIdbOrigin_failure_leavesExistingDestinationUntouched() {
        val src = File(tmpRoot, "src-fail")
        val dst = File(tmpRoot, "dst-fail")
        FixtureBuilder.idbWithDatabaseName(src, "http_steam-1.localhost_59099", "GameDB")
        src.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty().forEach { it.writeBytes(ByteArray(it.length().toInt()) { 0x5A }) }
        FixtureBuilder.idbWithDatabaseName(dst, "file__0", "GameDB")
        val before = snapshotFiles(dst)
        assertTrue("fixture must have files", before.isNotEmpty())

        assertThrows(Throwable::class.java) {
            LevelDbRewriter.rewriteIdbOrigin(src, dst, "http_steam-1.localhost_59099", "file__0")
        }

        val after = snapshotFiles(dst)
        assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after[name]) }
    }

    // an uncommitted webview IDB shell is skipped with null, telling outbound to keep the Wine blob dir.
    @Test
    fun rewriteIdbOrigin_emptyShellSource_returnsNullAndLeavesDestination() {
        val src = File(tmpRoot, "src-shell").apply { mkdirs() }
        File(src, "LOG").writeText("")
        val dst = File(tmpRoot, "dst-shell")
        FixtureBuilder.idbWithDatabaseName(dst, "file__0", "GameDB")
        val before = snapshotFiles(dst)

        assertNull(LevelDbRewriter.rewriteIdbOrigin(src, dst, "http_steam-1.localhost_59099", "file__0"))

        assertEquals(before.keys, snapshotFiles(dst).keys)
    }

    private fun snapshotFiles(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes() }

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

    // raw keys FixtureBuilder can't produce (corrupt varints, captured bytes)
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
        dir.listFiles { _, n -> n.endsWith(".sst") }?.forEach { f ->
            f.renameTo(File(f.parentFile, f.nameWithoutExtension + ".ldb"))
        }
    }
}
