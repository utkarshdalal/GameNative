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

// purgeKeysForOrigin filters by destination origin slice ONLY. also gating on the active origin is
// unsatisfiable on inbound (active=file://, target=WebView origin), so nothing would be purged and stale
// state would survive. co-resident games stay safe because each container has a unique origin slice in
// the shared WebView LS, and Wine-side LS dirs are per-prefix.
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

    @Test
    fun otherOriginSlices_survive_purgeByTargetOriginOnly() {
        val activeUrl = "https://game-steam_1516178466"
        val otherContainerUrl = "https://game-steam_2738490"
        val src = File(tmpRoot, "src-active")
        val dst = File(tmpRoot, "dst-shared-webview-ls")

        FixtureBuilder.lsWithOrigins(src, activeUrl to mapOf("save" to "AsavePayload".toByteArray()))

        FixtureBuilder.lsWithOrigins(
            dst,
            activeUrl to mapOf("save" to "AstaleOld".toByteArray()),
            otherContainerUrl to mapOf("save" to "BsavePayload".toByteArray()),
        )
        val containerBMetaAccess = "METAACCESS:$otherContainerUrl".toByteArray(Charsets.US_ASCII)
        FixtureBuilder.putRaw(dst, containerBMetaAccess, byteArrayOf(99))

        LevelDbRewriter.rewriteLsOrigin(
            src,
            dst,
            fromOriginUrl = activeUrl,
            toOriginUrl = activeUrl,
            activeContainerOriginUrl = activeUrl,
        )

        val keys = collectKeys(dst)

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

        assertFalse(
            "stale A value must not survive in dst",
            pairs.any { (_, v) -> v.contentEquals("AstaleOld".toByteArray()) },
        )
    }

    // the common single-container case (active == from == to): existing dst keys are purged + replaced
    @Test
    fun ownOriginKeys_purgeUnchanged_phase64Baseline() {
        val activeUrl = "https://game-steam_2738490"
        val src = File(tmpRoot, "src-own")
        val dst = File(tmpRoot, "dst-own")

        FixtureBuilder.lsWithOrigins(dst, activeUrl to mapOf("save" to "OLD".toByteArray()))
        FixtureBuilder.lsWithOrigins(src, activeUrl to mapOf("save" to "NEW".toByteArray()))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromOriginUrl = activeUrl, toOriginUrl = activeUrl, activeContainerOriginUrl = activeUrl)

        val pairs = collectKeyValuePairs(dst)
        val expectedKey = byteArrayOf('_'.code.toByte()) +
            activeUrl.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            "save".toByteArray(Charsets.US_ASCII)
        val found = pairs.firstOrNull { (k, _) -> k.contentEquals(expectedKey) }
        assertTrue("active-origin key present after rewrite", found != null)
        assertArrayEquals("active-origin value is NEW (purged OLD, rewrote NEW)", "NEW".toByteArray(), found!!.second)
        assertFalse(
            "stale OLD value must not survive in dst",
            pairs.any { (_, v) -> v.contentEquals("OLD".toByteArray()) },
        )
    }

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
