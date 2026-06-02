package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// plan — LS dual-shape origin rewrite tests.
// verifies META: + underscore rewrites, active-origin filter, decoy-origin survival
//, non-origin passthrough, empty-db no-crash, superstring decoy rejection (Pitfall 2).
class LevelDbRewriterLsTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("ls-rewriter-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    // META:<url> key gets rewritten to META:<newUrl>
    @Test
    fun rewriteLsOrigin_metaKey_rewritten() {
        val fromUrl = "https://game-steam_379210"
        val toUrl = "file://"
        val src = File(tmpRoot, "src-meta")
        val dst = File(tmpRoot, "dst-meta")
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("k1" to "v1".toByteArray()))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, toUrl, fromUrl)

        val keys = collectKeys(dst)
        val expectedMeta = "META:$toUrl".toByteArray(Charsets.US_ASCII)
        assertTrue("META key with new origin must exist", keys.any { it.contentEquals(expectedMeta) })
        // old META key gone
        val oldMeta = "META:$fromUrl".toByteArray(Charsets.US_ASCII)
        assertFalse("old META key must not exist", keys.any { it.contentEquals(oldMeta) })
    }

    // _<url><NUL><user-key> gets rewritten with new url
    @Test
    fun rewriteLsOrigin_underscoreKey_rewritten() {
        val fromUrl = "https://game-steam_379210"
        val toUrl = "file://"
        val src = File(tmpRoot, "src-us")
        val dst = File(tmpRoot, "dst-us")
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("k1" to "v1".toByteArray()))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, toUrl, fromUrl)

        val keys = collectKeys(dst)
        // expected: _file://<NUL>k1
        val expected = byteArrayOf('_'.code.toByte()) +
            toUrl.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            "k1".toByteArray(Charsets.US_ASCII)
        assertTrue("underscore key with new origin must exist", keys.any { it.contentEquals(expected) })
        // value must be "v1"
        val vals = collectKeyValues(dst)
        val v = vals[expected.contentHashCode().toString()]
            ?: vals.entries.firstOrNull { it.key == expected.contentHashCode().toString() }?.value
        // check via raw iteration
        val found = collectKeyValuePairs(dst).firstOrNull { (k, _) -> k.contentEquals(expected) }
        assertTrue("underscore key found", found != null)
        assertArrayEquals("value unchanged", "v1".toByteArray(), found!!.second)
    }

    // decoy origin keys survive the rewrite byte-for-byte
    @Test
    fun rewriteLsOrigin_decoyOriginSurvives() {
        val targetUrl = "https://game-steam_379210"
        val decoyUrl = "https://game-steam_358130"
        val src = File(tmpRoot, "src-decoy")
        val dst = File(tmpRoot, "dst-decoy")
        FixtureBuilder.lsWithOrigins(
            src,
            targetUrl to mapOf("save" to "data".toByteArray()),
            decoyUrl to mapOf("foo" to "bar".toByteArray()),
        )

        LevelDbRewriter.rewriteLsOrigin(src, dst, targetUrl, "file://", targetUrl)

        val keys = collectKeys(dst)
        // decoy META key must survive verbatim
        val decoyMeta = "META:$decoyUrl".toByteArray(Charsets.US_ASCII)
        assertTrue("decoy META key must survive", keys.any { it.contentEquals(decoyMeta) })
        // decoy underscore key must survive verbatim
        val decoyUs = byteArrayOf('_'.code.toByte()) +
            decoyUrl.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            "foo".toByteArray(Charsets.US_ASCII)
        assertTrue("decoy underscore key must survive", keys.any { it.contentEquals(decoyUs) })
        // target origin was rewritten — new META key present
        val newMeta = "META:file://".toByteArray(Charsets.US_ASCII)
        assertTrue("target META key rewritten", keys.any { it.contentEquals(newMeta) })
    }

    // values must be byte-identical after rewrite
    @Test
    fun rewriteLsOrigin_valuesUnchanged() {
        val fromUrl = "https://game-steam_379210"
        val toUrl = "file://"
        val kv = mapOf(
            "alpha" to byteArrayOf(1, 2, 3),
            "beta" to byteArrayOf(0x42, 0x00, 0xFF.toByte()),
            "gamma" to "hello world".toByteArray(),
        )
        val src = File(tmpRoot, "src-vals")
        val dst = File(tmpRoot, "dst-vals")
        FixtureBuilder.lsWithOrigins(src, fromUrl to kv)

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, toUrl, fromUrl)

        val pairs = collectKeyValuePairs(dst)
        for ((k, expectedVal) in kv) {
            val newKey = byteArrayOf('_'.code.toByte()) +
                toUrl.toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0) +
                k.toByteArray(Charsets.US_ASCII)
            val found = pairs.firstOrNull { (key, _) -> key.contentEquals(newKey) }
            assertTrue("key for '$k' must exist", found != null)
            assertArrayEquals("value for '$k' unchanged", expectedVal, found!!.second)
        }
    }

    // non-origin-bearing keys (e.g. INITIALIZED) pass through verbatim
    @Test
    fun rewriteLsOrigin_nonOriginKey_passesThrough() {
        val fromUrl = "https://game-steam_379210"
        val src = File(tmpRoot, "src-nonorigin")
        val dst = File(tmpRoot, "dst-nonorigin")
        // write target origin + a plain non-origin key
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("k" to byteArrayOf(1)))
        // add a raw non-origin key via FixtureBuilder.putRaw
        FixtureBuilder.putRaw(src, "INITIALIZED".toByteArray(Charsets.US_ASCII), byteArrayOf(1))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, "file://", fromUrl)

        val keys = collectKeys(dst)
        val rawKey = "INITIALIZED".toByteArray(Charsets.US_ASCII)
        assertTrue("non-origin INITIALIZED key must pass through", keys.any { it.contentEquals(rawKey) })
    }

    // Pitfall 2: superstring origin must NOT match when doing regionEquals (not startsWith)
    @Test
    fun rewriteLsOrigin_activeFilter_rejectsSubstringPrefixMatch() {
        val targetUrl = "https://game-steam_379210"
        val decoyUrl = "https://game-steam_3792100" // superstring of target
        val src = File(tmpRoot, "src-super")
        val dst = File(tmpRoot, "dst-super")
        FixtureBuilder.lsWithOrigins(
            src,
            targetUrl to mapOf("k" to byteArrayOf(1)),
            decoyUrl to mapOf("x" to byteArrayOf(2)),
        )

        LevelDbRewriter.rewriteLsOrigin(src, dst, targetUrl, "file://", targetUrl)

        val keys = collectKeys(dst)
        // decoy's META key must survive unchanged
        val decoyMeta = "META:$decoyUrl".toByteArray(Charsets.US_ASCII)
        assertTrue("superstring-decoy META must survive", keys.any { it.contentEquals(decoyMeta) })
        // decoy underscore key must survive unchanged
        val decoyUs = byteArrayOf('_'.code.toByte()) +
            decoyUrl.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            "x".toByteArray(Charsets.US_ASCII)
        assertTrue("superstring-decoy underscore key must survive", keys.any { it.contentEquals(decoyUs) })
    }

    // fromOrigin not present in fixture — no rewrites, all keys pass through
    @Test
    fun rewriteLsOrigin_missingFromOrigin_noRewrites() {
        val decoyUrl = "https://game-steam_358130"
        val fromUrl = "https://game-steam_379210"
        val src = File(tmpRoot, "src-missing")
        val dst = File(tmpRoot, "dst-missing")
        FixtureBuilder.lsWithOrigins(src, decoyUrl to mapOf("k" to byteArrayOf(7)))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, "file://", fromUrl)

        val srcKeys = collectKeys(src)
        val dstKeys = collectKeys(dst)
        // every src key appears byte-for-byte in dst
        for (sk in srcKeys) {
            assertTrue("src key must appear in dst unchanged", dstKeys.any { it.contentEquals(sk) })
        }
    }

    // Gap C: METAACCESS:<url> key on active origin gets rewritten to METAACCESS:<newUrl>.
    // shape-3 prefix is 10 bytes (vs META: at 5), MUST be checked before shape-2 or the
    // META: prefix naively matches and exact-length compare fails + key passes through stale.
    @Test
    fun rewriteLsOrigin_metaAccessKey_activeOrigin_rewritten() {
        val fromUrl = "https://game-steam_379210"
        val toUrl = "file://"
        val src = File(tmpRoot, "src-metaaccess")
        val dst = File(tmpRoot, "dst-metaaccess")
        // seed so lsWithOrigins creates a valid db, then append METAACCESS key raw
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("k" to byteArrayOf(1)))
        val oldMetaAccess = "METAACCESS:$fromUrl".toByteArray(Charsets.US_ASCII)
        FixtureBuilder.putRaw(src, oldMetaAccess, byteArrayOf(99))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, toUrl, fromUrl)

        val keys = collectKeys(dst)
        val expectedNewMetaAccess = "METAACCESS:$toUrl".toByteArray(Charsets.US_ASCII)
        assertTrue("METAACCESS rewritten to new origin", keys.any { it.contentEquals(expectedNewMetaAccess) })
        assertFalse("old METAACCESS must be gone", keys.any { it.contentEquals(oldMetaAccess) })
    }

    // Gap C + decoy origin METAACCESS key survives byte-for-byte — active-origin
    // filter gates the shape-3 branch same as shape-2 META.
    @Test
    fun rewriteLsOrigin_metaAccessKey_decoyOriginSurvives_D201() {
        val targetUrl = "https://game-steam_379210"
        val decoyUrl = "https://game-steam_358130"
        val src = File(tmpRoot, "src-ma-decoy")
        val dst = File(tmpRoot, "dst-ma-decoy")
        // seed target + decoy via normal LS builder
        FixtureBuilder.lsWithOrigins(
            src,
            targetUrl to mapOf("save" to "data".toByteArray()),
            decoyUrl to mapOf("foo" to "bar".toByteArray()),
        )
        // append METAACCESS for both origins
        val targetMetaAccess = "METAACCESS:$targetUrl".toByteArray(Charsets.US_ASCII)
        val decoyMetaAccess = "METAACCESS:$decoyUrl".toByteArray(Charsets.US_ASCII)
        FixtureBuilder.putRaw(src, targetMetaAccess, byteArrayOf(11))
        FixtureBuilder.putRaw(src, decoyMetaAccess, byteArrayOf(22))

        LevelDbRewriter.rewriteLsOrigin(src, dst, targetUrl, "file://", targetUrl)

        val keys = collectKeys(dst)
        // target METAACCESS was rewritten
        val newTargetMetaAccess = "METAACCESS:file://".toByteArray(Charsets.US_ASCII)
        assertTrue("target METAACCESS rewritten", keys.any { it.contentEquals(newTargetMetaAccess) })
        // decoy METAACCESS survives verbatim
        assertTrue("decoy METAACCESS must survive", keys.any { it.contentEquals(decoyMetaAccess) })
    }

    // real chromium LS embeds a leveldb TYPE BYTE (0x01) between NUL separator and user key:
    // "_<url>\x00\x01<key>"
    // shape-1 branch uses copyOfRange(nullSep, rawKey.size) for the tail, so the 0x01
    // byte naturally survives. explicit regression lock pernote.
    @Test
    fun rewriteLsOrigin_underscoreWithLevelDbTypeByte_preservesTail() {
        val fromUrl = "https://game-steam_379210"
        val toUrl = "file://"
        val src = File(tmpRoot, "src-typebyte")
        val dst = File(tmpRoot, "dst-typebyte")
        // seed with a plain underscore+META pair so LS db is valid
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("seed" to byteArrayOf(0)))
        // append raw underscore key with 0x01 type-byte after NUL separator
        val rawKey = byteArrayOf('_'.code.toByte()) +
            fromUrl.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00, 0x01) +
            "databases".toByteArray(Charsets.US_ASCII)
        FixtureBuilder.putRaw(src, rawKey, byteArrayOf(7, 7, 7))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, toUrl, fromUrl)

        val expectedNewKey = byteArrayOf('_'.code.toByte()) +
            toUrl.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00, 0x01) +
            "databases".toByteArray(Charsets.US_ASCII)
        val pairs = collectKeyValuePairs(dst)
        val found = pairs.firstOrNull { (k, _) -> k.contentEquals(expectedNewKey) }
        assertTrue("underscore+type-byte key with new origin must exist (tail 0x01 preserved)", found != null)
        assertArrayEquals("value unchanged", byteArrayOf(7, 7, 7), found!!.second)
    }

    // empty db completes without throwing; dst is a valid (possibly empty) leveldb
    @Test
    fun rewriteLsOrigin_emptyDb_completesCleanly() {
        val src = File(tmpRoot, "src-empty")
        val dst = File(tmpRoot, "dst-empty")
        // build a minimal (empty) LS db — lsWithOrigins with zero entries
        FixtureBuilder.lsWithOrigins(src) // no origins

        LevelDbRewriter.rewriteLsOrigin(
            src, dst,
            "https://game-steam_379210", "file://",
            "https://game-steam_379210",
        )

        assertTrue("dst must exist after empty-db rewrite", dst.isDirectory)
    }

    // --- helpers ---

    private fun collectKeys(dir: File): List<ByteArray> =
        collectKeyValuePairs(dir).map { it.first }

    private fun collectKeyValues(dir: File): Map<String, ByteArray> =
        collectKeyValuePairs(dir).associate { (k, v) -> k.contentHashCode().toString() to v }

    private fun collectKeyValuePairs(dir: File): List<Pair<ByteArray, ByteArray>> {
        val options = Options().apply {
            createIfMissing(false)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            // LS uses bytewise comparator (no idb1)
        }
        val ldbFiles = dir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty()
        ldbFiles.forEach { f -> f.renameTo(File(f.parentFile, f.nameWithoutExtension + ".sst")) }
        return try {
            Iq80DBFactory.factory.open(dir, options).use { db ->
                val pairs = mutableListOf<Pair<ByteArray, ByteArray>>()
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) {
                        val e = iter.next()
                        pairs += e.key to e.value
                    }
                }
                pairs
            }
        } finally {
            dir.listFiles { _, name -> name.endsWith(".sst") }.orEmpty()
                .forEach { f -> f.renameTo(File(f.parentFile, f.nameWithoutExtension + ".ldb")) }
        }
    }
}
