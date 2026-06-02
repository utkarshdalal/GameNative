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

// purgeKeysForOrigin filters by destination origin slice ONLY (the prior
// activeContainerOriginAscii AND-gate was unsatisfiable on inbound: active=file://,
// target=WebView origin → no key's slice could equal both, so purge silently produced
// deleted=0 and stale state survived inbound rewrites).
//
// co-resident origin protection is automatic in production because each container has a
// unique origin URL slice (e.g. http://steam-2738490.localhost:59099 vs
// http://steam-1516178466.localhost:59099). The "shared dst" scenario the prior test
// modeled doesn't occur in production: each Wine prefix is per-container, so wine-side
// `file://` LS dirs are physically separate; the WebView LS leveldb is shared but each
// container's slice differs.
class LevelDbRewriterPurgeFilterTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("ls-purge-filter-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    // PRIMARY ASSERTION: keys at OTHER origin slices (i.e. not the destination origin) are
    // untouched by purge. mirrors the production WebView LS scenario: container A's
    // inbound writes to its slice; container B's slice survives untouched.
    @Test
    fun otherOriginSlices_survive_purgeByTargetOriginOnly() {
        val activeUrl = "https://game-steam_1516178466" // container A
        val otherContainerUrl = "https://game-steam_2738490" // container B (co-resident on same WebView LS)
        val src = File(tmpRoot, "src-active")
        val dst = File(tmpRoot, "dst-shared-webview-ls")

        // src has container A's keys at A's URL
        FixtureBuilder.lsWithOrigins(src, activeUrl to mapOf("save" to "AsavePayload".toByteArray()))

        // dst (shared WebView LS) pre-seeded with both A's stale keys (target of purge) AND
        // B's keys at B's distinct origin slice (must survive).
        FixtureBuilder.lsWithOrigins(
            dst,
            activeUrl to mapOf("save" to "AstaleOld".toByteArray()),
            otherContainerUrl to mapOf("save" to "BsavePayload".toByteArray()),
        )
        val containerBMetaAccess = "METAACCESS:$otherContainerUrl".toByteArray(Charsets.US_ASCII)
        FixtureBuilder.putRaw(dst, containerBMetaAccess, byteArrayOf(99))

        // container A outbounds: from=activeUrl, to=activeUrl (single-container case), active=activeUrl.
        LevelDbRewriter.rewriteLsOrigin(
            src,
            dst,
            fromOriginUrl = activeUrl,
            toOriginUrl = activeUrl,
            activeContainerOriginUrl = activeUrl,
        )

        val keys = collectKeys(dst)

        // container B's keys at B's distinct slice MUST SURVIVE — purge filtered by activeUrl only.
        val containerBMeta = "META:$otherContainerUrl".toByteArray(Charsets.US_ASCII)
        val containerBUs = byteArrayOf('_'.code.toByte()) +
            otherContainerUrl.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            "save".toByteArray(Charsets.US_ASCII)
        assertTrue("container B META key must survive", keys.any { it.contentEquals(containerBMeta) })
        assertTrue("container B underscore key must survive", keys.any { it.contentEquals(containerBUs) })
        assertTrue("container B METAACCESS must survive", keys.any { it.contentEquals(containerBMetaAccess) })

        val pairs = collectKeyValuePairs(dst)
        val survivor = pairs.firstOrNull { (k, _) -> k.contentEquals(containerBUs) }
        assertTrue("container B kv pair retrieved", survivor != null)
        assertArrayEquals(
            "container B value byte-identical (untouched)",
            "BsavePayload".toByteArray(),
            survivor!!.second,
        )

        // container A's stale OLD value must NOT survive (purged by activeUrl match)
        assertFalse(
            "stale A value must not survive in dst",
            pairs.any { (_, v) -> v.contentEquals("AstaleOld".toByteArray()) },
        )
    }

    // BASELINE: when src + dst use same active=from=to URL, behavior MUST match 4
    // pre-fix — A's existing dst keys at the active origin get purged + replaced. This is the
    // single-container outbound case where 4 (Sol Cesto / Look Outside /
    // Wayward) were green; the new gate must not regress them.
    @Test
    fun ownOriginKeys_purgeUnchanged_phase64Baseline() {
        val activeUrl = "https://game-steam_2738490" // single-container case (Sol Cesto)
        val src = File(tmpRoot, "src-own")
        val dst = File(tmpRoot, "dst-own")

        // dst has stale prior data at activeUrl
        FixtureBuilder.lsWithOrigins(dst, activeUrl to mapOf("save" to "OLD".toByteArray()))
        // src has fresh data at same activeUrl, different value
        FixtureBuilder.lsWithOrigins(src, activeUrl to mapOf("save" to "NEW".toByteArray()))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromOriginUrl = activeUrl, toOriginUrl = activeUrl, activeContainerOriginUrl = activeUrl)

        // post-rewrite the dst key at activeUrl exists with NEW value. purge wiped OLD,
        // rewrite added NEW. baseline behavior preserved.
        val pairs = collectKeyValuePairs(dst)
        val expectedKey = byteArrayOf('_'.code.toByte()) +
            activeUrl.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            "save".toByteArray(Charsets.US_ASCII)
        val found = pairs.firstOrNull { (k, _) -> k.contentEquals(expectedKey) }
        assertTrue("active-origin key present after rewrite", found != null)
        assertArrayEquals("active-origin value is NEW (purged OLD, rewrote NEW)", "NEW".toByteArray(), found!!.second)
        // OLD bytes must not survive under any key
        assertFalse(
            "stale OLD value must not survive in dst",
            pairs.any { (_, v) -> v.contentEquals("OLD".toByteArray()) },
        )
    }

    // --- helpers ---

    private fun collectKeys(dir: File): List<ByteArray> =
        collectKeyValuePairs(dir).map { it.first }

    private fun collectKeyValuePairs(dir: File): List<Pair<ByteArray, ByteArray>> {
        val options = Options().apply {
            createIfMissing(false)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
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
