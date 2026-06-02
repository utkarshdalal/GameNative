package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// contract tests for FixtureBuilder -- verifies synthesized leveldbs are chromium-valid:
// iq80 + idb_cmp1 can re-open IDB fixtures; bytewise comparator can re-open LS fixtures.
// keys have the correct byte shapes per chromium's leveldb_coding_scheme.md.
// .sst to .ldb rename applied after close so fixtures match chromium's on-disk naming.
class FixtureBuilderTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("fixturebuilder-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    // re-opening a synthesized IDB under idb_cmp1 must succeed and produce at least 1 key.
    // first 5 bytes of the DatabaseNameKey must be [0, 0, 0, 0, 0xC9] per chromium spec
    // (plan Gap A fix — was 4-byte header in /03).
    @Test
    fun synth_idb_withDatabaseName_reopensUnderIdb1() {
        val dir = File(tmpRoot, "idb1")
        FixtureBuilder.idbWithDatabaseName(dir, "https_game-steam_379210_0", "db1")

        val keys = mutableListOf<ByteArray>()
        withLdbAsSst(dir) {
            openDb(dir, useIdb1 = true).use { db ->
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) {
                        keys += iter.next().key
                    }
                }
            }
        }

        assertTrue("IDB fixture must contain at least one key", keys.isNotEmpty())
        val dbNameKey = keys.firstOrNull { it.size >= 5 && it[4] == 0xC9.toByte() }
        assertNotNull("IDB fixture must contain a DatabaseNameKey (byte 4 == 0xC9)", dbNameKey)
        assertArrayEquals(
            "DatabaseNameKey first 5 bytes must be [0, 0, 0, 0, 0xC9]",
            byteArrayOf(0, 0, 0, 0, 0xC9.toByte()),
            dbNameKey!!.copyOfRange(0, 5),
        )
    }

    // DatabaseNameKey byte shape: offset 5 holds the LEB128 varint (first byte < 0x80 for short
    // origin), followed by UTF-16BE bytes of the origin (50 bytes = 25 code units x 2).
    // plan Gap A fix — 5-byte header shifted offsets by +1.
    @Test
    fun synth_idb_databaseNameKey_hasExpectedShape() {
        val origin = "https_game-steam_379210_0" // 25 chars = 25 code units
        val dir = File(tmpRoot, "idb2")
        FixtureBuilder.idbWithDatabaseName(dir, origin, "db1")

        val keys = mutableListOf<ByteArray>()
        withLdbAsSst(dir) {
            openDb(dir, useIdb1 = true).use { db ->
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) {
                        keys += iter.next().key
                    }
                }
            }
        }

        val dbNameKey = keys.firstOrNull { it.size >= 5 && it[4] == 0xC9.toByte() }
        assertNotNull("must have a DatabaseNameKey", dbNameKey)
        val key = dbNameKey!!

        // offset 5: varint code-unit count. 25 < 128 so single byte with MSB clear
        assertTrue(
            "varint at offset 5 must fit in one byte (first byte < 0x80)",
            (key[5].toInt() and 0x80) == 0,
        )
        val codeUnitCount = key[5].toInt() and 0x7F
        val originByteLen = codeUnitCount * 2

        // 25 code units x 2 bytes = 50 bytes of UTF-16BE origin
        val expectedOriginBytes = origin.toByteArray(Charsets.UTF_16BE)
        assertTrue(
            "key must be long enough to contain origin slice",
            key.size >= 6 + originByteLen,
        )
        assertArrayEquals(
            "origin slice must be UTF-16BE of origin string",
            expectedOriginBytes,
            key.copyOfRange(6, 6 + originByteLen),
        )
    }

    // LS fixture must contain both META: and underscore-prefix shapes for the given origin.
    @Test
    fun synth_ls_withOrigins_writesBothMetaAndUnderscoreShapes() {
        val url = "https://game-steam_379210"
        val dir = File(tmpRoot, "ls1")
        FixtureBuilder.lsWithOrigins(dir, url to mapOf("k1" to "v1".toByteArray()))

        val keys = mutableListOf<ByteArray>()
        withLdbAsSst(dir) {
            openDb(dir, useIdb1 = false).use { db ->
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) {
                        keys += iter.next().key
                    }
                }
            }
        }

        val metaKey = "META:$url".toByteArray(Charsets.US_ASCII)
        // NUL separator between origin and user key -- matches chromium LS shape 1
        val underscoreKey = byteArrayOf('_'.code.toByte()) +
            url.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            "k1".toByteArray(Charsets.US_ASCII)

        assertTrue(
            "LS fixture must contain META:<url> key",
            keys.any { it.contentEquals(metaKey) },
        )
        assertTrue(
            "LS fixture must contain _<url><NUL>k1 key",
            keys.any { it.contentEquals(underscoreKey) },
        )
    }

    // LS fixture with TWO origins must contain META: + underscore entries for BOTH.
    // locks -- cross-origin decoy fixture shape needed by LevelDbRewriterLsTest.
    @Test
    fun synth_ls_withDualOrigins_preservesBothOrigins() {
        val target = "https://game-steam_379210"
        val decoy = "https://game-steam_358130"
        val dir = File(tmpRoot, "ls2")
        FixtureBuilder.lsWithOrigins(
            dir,
            target to mapOf("save" to byteArrayOf(1, 2, 3)),
            decoy to mapOf("save" to byteArrayOf(4, 5, 6)),
        )

        val keys = mutableListOf<ByteArray>()
        withLdbAsSst(dir) {
            openDb(dir, useIdb1 = false).use { db ->
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) {
                        keys += iter.next().key
                    }
                }
            }
        }

        fun metaBytes(url: String) = "META:$url".toByteArray(Charsets.US_ASCII)
        fun underscoreBytes(url: String, k: String) =
            byteArrayOf('_'.code.toByte()) +
                url.toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0) +
                k.toByteArray(Charsets.US_ASCII)

        assertTrue(
            "dual-origin LS must contain META: for target",
            keys.any { it.contentEquals(metaBytes(target)) },
        )
        assertTrue(
            "dual-origin LS must contain META: for decoy",
            keys.any { it.contentEquals(metaBytes(decoy)) },
        )
        assertTrue(
            "dual-origin LS must contain underscore key for target",
            keys.any { it.contentEquals(underscoreBytes(target, "save")) },
        )
        assertTrue(
            "dual-origin LS must contain underscore key for decoy",
            keys.any { it.contentEquals(underscoreBytes(decoy, "save")) },
        )
    }

    // after FixtureBuilder exits, dir must contain .ldb files, NOT .sst files.
    // matches chromium's on-disk naming; withLdbAsSst in LevelDbRewriter depends on this.
    @Test
    fun synth_ldbRenameRound() {
        val idbDir = File(tmpRoot, "idb-ldb")
        val lsDir = File(tmpRoot, "ls-ldb")

        FixtureBuilder.idbWithDatabaseName(idbDir, "https_game-steam_379210_0", "db1")
        FixtureBuilder.lsWithOrigins(lsDir, "https://game-steam_379210" to mapOf("k" to byteArrayOf(1)))

        val idbLdbs = idbDir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty()
        val idbSsts = idbDir.listFiles { _, name -> name.endsWith(".sst") }.orEmpty()
        val lsLdbs = lsDir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty()
        val lsSsts = lsDir.listFiles { _, name -> name.endsWith(".sst") }.orEmpty()

        assertTrue("IDB dir must contain at least one .ldb file after FixtureBuilder", idbLdbs.isNotEmpty())
        assertFalse("IDB dir must contain NO .sst files after FixtureBuilder", idbSsts.isNotEmpty())
        assertTrue("LS dir must contain at least one .ldb file after FixtureBuilder", lsLdbs.isNotEmpty())
        assertFalse("LS dir must contain NO .sst files after FixtureBuilder", lsSsts.isNotEmpty())
    }

    // --- helpers ---

    // mirrors LevelDbRewriter.withLdbAsSst: rename .ldb -> .sst before block, restore after.
    // iq80 uses legacy .sst naming; fixtures are stored as .ldb matching chromium convention.
    private inline fun withLdbAsSst(dir: File, block: () -> Unit) {
        val renames = dir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty()
            .map { f -> f to File(f.parentFile, f.nameWithoutExtension + ".sst") }
        renames.forEach { (from, to) -> from.renameTo(to) }
        try {
            block()
        } finally {
            // restore .ldb extension (iq80 may also have written new .sst during block)
            dir.listFiles { _, name -> name.endsWith(".sst") }.orEmpty().forEach { sst ->
                sst.renameTo(File(sst.parentFile, sst.nameWithoutExtension + ".ldb"))
            }
        }
    }

    private fun openDb(dir: File, useIdb1: Boolean): org.iq80.leveldb.DB {
        val options = Options().apply {
            createIfMissing(false)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            if (useIdb1) comparator(Idb1Comparator())
        }
        return Iq80DBFactory.factory.open(dir, options)
    }
}
