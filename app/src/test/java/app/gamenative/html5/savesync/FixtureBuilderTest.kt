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

// FixtureBuilder is only useful if its output is chromium-valid: re-openable under the right comparator,
// keys shaped per leveldb_coding_scheme.md, SSTables named .ldb.
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

    @Test
    fun synth_idb_databaseNameKey_hasExpectedShape() {
        val origin = "https_game-steam_379210_0" // 25 code units: short enough for a 1-byte varint
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

        assertTrue(
            "varint at offset 5 must fit in one byte (first byte < 0x80)",
            (key[5].toInt() and 0x80) == 0,
        )
        val codeUnitCount = key[5].toInt() and 0x7F
        val originByteLen = codeUnitCount * 2

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

    // LevelDbRewriterLsTest relies on this cross-origin decoy shape.
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

    // LevelDbRewriter.withLdbAsSst depends on fixtures using chromium's .ldb naming.
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

    // mirrors LevelDbRewriter.withLdbAsSst: iq80 only reads legacy .sst names.
    private inline fun withLdbAsSst(dir: File, block: () -> Unit) {
        val renames = dir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty()
            .map { f -> f to File(f.parentFile, f.nameWithoutExtension + ".sst") }
        renames.forEach { (from, to) -> from.renameTo(to) }
        try {
            block()
        } finally {
            // sweep all .sst, not just the renamed set -- iq80 may have written new ones during block
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
