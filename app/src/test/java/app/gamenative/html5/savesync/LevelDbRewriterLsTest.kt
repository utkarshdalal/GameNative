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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        val oldMeta = "META:$fromUrl".toByteArray(Charsets.US_ASCII)
        assertFalse("old META key must not exist", keys.any { it.contentEquals(oldMeta) })
    }

    @Test
    fun rewriteLsOrigin_underscoreKey_rewritten() {
        val fromUrl = "https://game-steam_379210"
        val toUrl = "file://"
        val src = File(tmpRoot, "src-us")
        val dst = File(tmpRoot, "dst-us")
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("k1" to "v1".toByteArray()))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, toUrl, fromUrl)

        val keys = collectKeys(dst)
        val expected = byteArrayOf('_'.code.toByte()) +
            toUrl.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            "k1".toByteArray(Charsets.US_ASCII)
        assertTrue("underscore key with new origin must exist", keys.any { it.contentEquals(expected) })
        val vals = collectKeyValues(dst)
        val v = vals[expected.contentHashCode().toString()]
            ?: vals.entries.firstOrNull { it.key == expected.contentHashCode().toString() }?.value
        val found = collectKeyValuePairs(dst).firstOrNull { (k, _) -> k.contentEquals(expected) }
        assertTrue("underscore key found", found != null)
        assertArrayEquals("value unchanged", "v1".toByteArray(), found!!.second)
    }

    // other origins in the shared WebView LS must NOT cross into the Wine copy
    @Test
    fun rewriteLsOrigin_otherOriginNotCopied() {
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
        val decoyMeta = "META:$decoyUrl".toByteArray(Charsets.US_ASCII)
        assertFalse("decoy META key must not be copied", keys.any { it.contentEquals(decoyMeta) })
        assertFalse("decoy underscore key must not be copied", keys.any { it.contentEquals(usKey(decoyUrl, "foo")) })
        val newMeta = "META:file://".toByteArray(Charsets.US_ASCII)
        assertTrue("target META key rewritten", keys.any { it.contentEquals(newMeta) })
    }

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

    // VERSION is the only origin-less key chromium writes -- it passes through verbatim; any
    // other key we can't parse (unknown global, `_` key without NUL) is skipped
    @Test
    fun rewriteLsOrigin_versionKeyPassesThrough_unparsedKeysSkipped() {
        val fromUrl = "https://game-steam_379210"
        val src = File(tmpRoot, "src-nonorigin")
        val dst = File(tmpRoot, "dst-nonorigin")
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("k" to byteArrayOf(1)))
        val version = "VERSION".toByteArray(Charsets.US_ASCII)
        val unknown = "INITIALIZED".toByteArray(Charsets.US_ASCII)
        val noNul = "_https://game-steam_379210".toByteArray(Charsets.US_ASCII)
        FixtureBuilder.putRaw(src, version, "1".toByteArray())
        FixtureBuilder.putRaw(src, unknown, byteArrayOf(1))
        FixtureBuilder.putRaw(src, noNul, byteArrayOf(1))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, "file://", fromUrl)

        val keys = collectKeys(dst)
        assertTrue("VERSION must pass through", keys.any { it.contentEquals(version) })
        assertFalse("unknown origin-less key must be skipped", keys.any { it.contentEquals(unknown) })
        assertFalse("underscore key without NUL must be skipped", keys.any { it.contentEquals(noNul) })
    }

    // a superstring origin must NOT match: exact region compare, not startsWith
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
        val decoyMeta = "META:$decoyUrl".toByteArray(Charsets.US_ASCII)
        assertFalse("superstring-decoy META must not be copied", keys.any { it.contentEquals(decoyMeta) })
        assertFalse("superstring-decoy underscore key must not be copied", keys.any { it.contentEquals(usKey(decoyUrl, "x")) })
        assertFalse("superstring-decoy must not be rewritten", keys.any { it.contentEquals(usKey("file://", "x")) })
    }

    // fromOrigin not present in fixture -- no origin-bearing key crosses
    @Test
    fun rewriteLsOrigin_missingFromOrigin_copiesNothing() {
        val decoyUrl = "https://game-steam_358130"
        val fromUrl = "https://game-steam_379210"
        val src = File(tmpRoot, "src-missing")
        val dst = File(tmpRoot, "dst-missing")
        FixtureBuilder.lsWithOrigins(src, decoyUrl to mapOf("k" to byteArrayOf(7)))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, "file://", fromUrl)

        assertTrue("no origin-bearing key may cross", collectKeys(dst).none { LevelDbRewriter.lsKeyOrigin(it) != null })
    }

    // METAACCESS: MUST be checked before META:, which it also starts with; otherwise the exact-length origin
    // compare fails and the key passes through stale.
    @Test
    fun rewriteLsOrigin_metaAccessKey_activeOrigin_rewritten() {
        val fromUrl = "https://game-steam_379210"
        val toUrl = "file://"
        val src = File(tmpRoot, "src-metaaccess")
        val dst = File(tmpRoot, "dst-metaaccess")
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("k" to byteArrayOf(1)))
        val oldMetaAccess = "METAACCESS:$fromUrl".toByteArray(Charsets.US_ASCII)
        FixtureBuilder.putRaw(src, oldMetaAccess, byteArrayOf(99))

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, toUrl, fromUrl)

        val keys = collectKeys(dst)
        val expectedNewMetaAccess = "METAACCESS:$toUrl".toByteArray(Charsets.US_ASCII)
        assertTrue("METAACCESS rewritten to new origin", keys.any { it.contentEquals(expectedNewMetaAccess) })
        assertFalse("old METAACCESS must be gone", keys.any { it.contentEquals(oldMetaAccess) })
    }

    @Test
    fun rewriteLsOrigin_metaAccessKey_otherOriginNotCopied() {
        val targetUrl = "https://game-steam_379210"
        val decoyUrl = "https://game-steam_358130"
        val src = File(tmpRoot, "src-ma-decoy")
        val dst = File(tmpRoot, "dst-ma-decoy")
        FixtureBuilder.lsWithOrigins(
            src,
            targetUrl to mapOf("save" to "data".toByteArray()),
            decoyUrl to mapOf("foo" to "bar".toByteArray()),
        )
        val targetMetaAccess = "METAACCESS:$targetUrl".toByteArray(Charsets.US_ASCII)
        val decoyMetaAccess = "METAACCESS:$decoyUrl".toByteArray(Charsets.US_ASCII)
        FixtureBuilder.putRaw(src, targetMetaAccess, byteArrayOf(11))
        FixtureBuilder.putRaw(src, decoyMetaAccess, byteArrayOf(22))

        LevelDbRewriter.rewriteLsOrigin(src, dst, targetUrl, "file://", targetUrl)

        val keys = collectKeys(dst)
        val newTargetMetaAccess = "METAACCESS:file://".toByteArray(Charsets.US_ASCII)
        assertTrue("target METAACCESS rewritten", keys.any { it.contentEquals(newTargetMetaAccess) })
        assertFalse("decoy METAACCESS must not be copied", keys.any { it.contentEquals(decoyMetaAccess) })
    }

    // real chromium LS puts a TYPE BYTE (0x01) between the NUL separator and user key: "_<url>\x00\x01<key>".
    // the rewrite must carry the whole tail, not re-encode the user key.
    @Test
    fun rewriteLsOrigin_underscoreWithLevelDbTypeByte_preservesTail() {
        val fromUrl = "https://game-steam_379210"
        val toUrl = "file://"
        val src = File(tmpRoot, "src-typebyte")
        val dst = File(tmpRoot, "dst-typebyte")
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("seed" to byteArrayOf(0)))
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

    @Test
    fun rewriteLsOrigin_emptyDb_completesCleanly() {
        val src = File(tmpRoot, "src-empty")
        val dst = File(tmpRoot, "dst-empty")
        FixtureBuilder.lsWithOrigins(src)

        LevelDbRewriter.rewriteLsOrigin(
            src, dst,
            "https://game-steam_379210", "file://",
            "https://game-steam_379210",
        )

        assertTrue("dst must exist after empty-db rewrite", dst.isDirectory)
    }

    // inbound: a Wine copy carrying another game's stale keys must not roll that game back in
    // the shared WebView LS
    @Test
    fun rewriteLsOrigin_inbound_staleOtherOriginKeysDoNotRollBackLiveStore() {
        val pcUrl = "file://"
        val gameA = "http://steam-1.localhost:59099"
        val gameB = "http://steam-2.localhost:59099"
        val wine = File(tmpRoot, "wine-ls")
        val live = File(tmpRoot, "webview-ls")
        FixtureBuilder.lsWithOrigins(
            wine,
            pcUrl to mapOf("save" to "A-cloud".toByteArray()),
            gameB to mapOf("save" to "B-stale".toByteArray()),
        )
        FixtureBuilder.lsWithOrigins(live, gameB to mapOf("save" to "B-live".toByteArray()))

        LevelDbRewriter.rewriteLsOrigin(wine, live, pcUrl, gameA, pcUrl)

        val pairs = collectKeyValuePairs(live)
        fun valueOf(key: ByteArray) = pairs.firstOrNull { (k, _) -> k.contentEquals(key) }?.second
        assertArrayEquals("game B keeps its live value", "B-live".toByteArray(), valueOf(usKey(gameB, "save")))
        assertArrayEquals("game A restored from the Wine copy", "A-cloud".toByteArray(), valueOf(usKey(gameA, "save")))
    }

    // html5 outbound: the Wine copy keeps only this game's pc origin -- leaked game, login-page and
    // stale-origin keys all go; VERSION stays
    @Test
    fun rewriteLsOrigin_keepOnlyToOriginInDst_leavesOnlyPcOrigin() {
        val pcUrl = "chrome-extension://lmepkikdgdbfdpjokdmnnanopegnpjda"
        val gameA = "http://gog-1.localhost:59099"
        val leaked = listOf(
            "http://steam-2.localhost:59099",
            "https://game-steam_379210",
            "https://login.gog.com",
            "file://",
            "chrome-extension://anopiimlkmdoenonenclohfilpeenfmj",
        )
        val live = File(tmpRoot, "webview-ls")
        val wine = File(tmpRoot, "wine-ls")
        FixtureBuilder.lsWithOrigins(live, gameA to mapOf("save" to "A-new".toByteArray()))
        FixtureBuilder.lsWithOrigins(wine, *(leaked + pcUrl).map { it to mapOf("k" to byteArrayOf(1)) }.toTypedArray())
        leaked.forEach { FixtureBuilder.putRaw(wine, "METAACCESS:$it".toByteArray(Charsets.US_ASCII), byteArrayOf(2)) }
        FixtureBuilder.putRaw(wine, "VERSION".toByteArray(Charsets.US_ASCII), "1".toByteArray())

        LevelDbRewriter.rewriteLsOrigin(live, wine, gameA, pcUrl, gameA, keepOnlyToOriginInDst = true)

        val keys = collectKeys(wine)
        assertEquals(setOf(pcUrl), keys.mapNotNull { LevelDbRewriter.lsKeyOrigin(it) }.toSet())
        assertArrayEquals("A-new".toByteArray(), collectKeyValuePairs(wine).first { (k, _) -> k.contentEquals(usKey(pcUrl, "save")) }.second)
        assertTrue("VERSION stays", keys.any { it.contentEquals("VERSION".toByteArray(Charsets.US_ASCII)) })
    }

    // empty-source guard, inbound: a Wine copy with no keys under the pc origin must not wipe the
    // game's live WebView keys
    @Test
    fun rewriteLsOrigin_emptySource_keepsDestinationKeysForGame() {
        val pcUrl = "chrome-extension://chfcdekbbipkpnldpbmcehghciebolme"
        val gameA = "http://steam-1627840.localhost:59099"
        val wine = File(tmpRoot, "wine-ls")
        val live = File(tmpRoot, "webview-ls")
        FixtureBuilder.lsWithOrigins(wine, "file://" to mapOf("save" to "old-origin".toByteArray()))
        FixtureBuilder.lsWithOrigins(live, gameA to mapOf("save" to "A-live".toByteArray()))

        LevelDbRewriter.rewriteLsOrigin(wine, live, pcUrl, gameA, pcUrl)

        val found = collectKeyValuePairs(live).firstOrNull { (k, _) -> k.contentEquals(usKey(gameA, "save")) }
        assertTrue("game A key survives", found != null)
        assertArrayEquals("A-live".toByteArray(), found!!.second)
    }

    // empty-source guard, html5 outbound: the Wine copy's own-origin keys survive, keep-only still
    // drops leaked origins
    @Test
    fun rewriteLsOrigin_emptySource_keepOnly_dropsLeaksButKeepsOwnOrigin() {
        val pcUrl = "chrome-extension://lmepkikdgdbfdpjokdmnnanopegnpjda"
        val gameA = "http://gog-1252295864.localhost:59099"
        val live = File(tmpRoot, "webview-ls")
        val wine = File(tmpRoot, "wine-ls")
        FixtureBuilder.lsWithOrigins(live, "http://steam-2.localhost:59099" to mapOf("k" to byteArrayOf(1)))
        FixtureBuilder.lsWithOrigins(
            wine,
            pcUrl to mapOf("options.scale" to "2".toByteArray()),
            "https://login.gog.com" to mapOf("k" to byteArrayOf(1)),
        )

        LevelDbRewriter.rewriteLsOrigin(live, wine, gameA, pcUrl, gameA, keepOnlyToOriginInDst = true)

        assertEquals(setOf(pcUrl), collectKeys(wine).mapNotNull { LevelDbRewriter.lsKeyOrigin(it) }.toSet())
    }

    // page-captured localStorage replaces the from-origin keys read from leveldb -- at outbound time
    // chromium may have only the session-start deletes on disk
    @Test
    fun rewriteLsOrigin_pageEntriesReplaceLeveldbFromOriginKeys() {
        val gameA = "http://steam-379210.localhost:59099"
        val live = File(tmpRoot, "webview-ls")
        val wine = File(tmpRoot, "wine-ls")
        FixtureBuilder.lsWithOrigins(live, gameA to mapOf("stale" to "on-disk".toByteArray()))
        FixtureBuilder.putRaw(live, "VERSION".toByteArray(Charsets.US_ASCII), "1".toByteArray())
        val key = LocalStorageSnapshot.encode("-1options".toCharArray())
        val value = LocalStorageSnapshot.encode("page-value".toCharArray())

        LevelDbRewriter.rewriteLsOrigin(
            live, wine, gameA, "file://", gameA,
            keepOnlyToOriginInDst = true,
            fromOriginEntries = listOf(key to value),
        )

        val pairs = collectKeyValuePairs(wine)
        val pageKey = byteArrayOf('_'.code.toByte()) + "file://".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + key
        assertArrayEquals(value, pairs.first { (k, _) -> k.contentEquals(pageKey) }.second)
        assertFalse("leveldb's own copy of the origin's keys is not used", pairs.any { (k, _) -> k.contentEquals(usKey("file://", "stale")) })
        assertTrue("META still rewritten", pairs.any { (k, _) -> k.contentEquals("META:file://".toByteArray(Charsets.US_ASCII)) })
        assertTrue("VERSION kept", pairs.any { (k, _) -> k.contentEquals("VERSION".toByteArray(Charsets.US_ASCII)) })
    }

    // an empty page capture falls under the empty-source guard: the Wine copy's own keys stay
    @Test
    fun rewriteLsOrigin_emptyPageEntries_keepDestinationKeys() {
        val gameA = "http://steam-379210.localhost:59099"
        val live = File(tmpRoot, "webview-ls")
        val wine = File(tmpRoot, "wine-ls")
        FixtureBuilder.lsWithOrigins(live, gameA to mapOf("k" to byteArrayOf(1)))
        FixtureBuilder.lsWithOrigins(wine, "file://" to mapOf("pc" to "desktop".toByteArray()))

        LevelDbRewriter.rewriteLsOrigin(
            live, wine, gameA, "file://", gameA,
            keepOnlyToOriginInDst = true,
            fromOriginEntries = emptyList(),
        )

        val found = collectKeyValuePairs(wine).firstOrNull { (k, _) -> k.contentEquals(usKey("file://", "pc")) }
        assertTrue("wine copy's own key survives", found != null)
        assertArrayEquals("desktop".toByteArray(), found!!.second)
    }

    // a failed rewrite must not cost dst its tables: rollback runs while dst's .ldb files are renamed to
    // .sst, so a file snapshot taken before the rename would make rollback delete every table.
    @Test
    fun rewriteLsOrigin_failedOpen_rollbackKeepsDestinationTables() {
        val fromUrl = "http://steam-1.localhost:59099"
        val src = File(tmpRoot, "src-rollback")
        val dst = File(tmpRoot, "dst-rollback")
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("k1" to "v1".toByteArray()))
        FixtureBuilder.lsWithOrigins(dst, "http://steam-2.localhost:59099" to mapOf("other" to "live".toByteArray()))
        fun tables() = dst.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty().map { it.name }.toSet()
        val tablesBefore = tables()
        assertTrue("fixture must have tables", tablesBefore.isNotEmpty())
        // CURRENT naming a missing manifest makes the dst open throw inside the rewrite's try
        File(dst, "CURRENT").writeText("MANIFEST-999999\n")

        try {
            LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, "file://", fromUrl)
            throw AssertionError("expected SaveSyncFailure")
        } catch (expected: SaveSyncFailure) {
        }

        assertEquals(tablesBefore, tables())
    }

    // outbound rewrites a staged copy and mirrors it back: tables the rewrite didn't touch must keep their
    // mtimes, or timestamp-based cloud sync re-uploads every unchanged file on each exit.
    @Test
    fun rewriteLsOrigin_outboundStaging_untouchedTablesKeepMtime() {
        val fromUrl = "http://steam-1.localhost:59099"
        val toUrl = "file://"
        val src = File(tmpRoot, "src-mtime")
        val dst = File(tmpRoot, "dst-mtime")
        FixtureBuilder.lsWithOrigins(src, fromUrl to mapOf("k1" to "new".toByteArray()))
        FixtureBuilder.lsWithOrigins(dst, toUrl to mapOf("k1" to "old".toByteArray()))
        val oldMtime = 1_600_000_000_000L
        val tablesBefore = dst.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty().onEach { it.setLastModified(oldMtime) }
        assertTrue("fixture must have tables", tablesBefore.isNotEmpty())

        LevelDbRewriter.rewriteLsOrigin(src, dst, fromUrl, toUrl, fromUrl, keepOnlyToOriginInDst = true)

        tablesBefore.forEach { table ->
            assertTrue("${table.name} must survive", table.isFile)
            assertEquals("${table.name} mtime", oldMtime, table.lastModified())
        }
        assertArrayEquals("new".toByteArray(), collectKeyValues(dst)[usKey(toUrl, "k1").contentHashCode().toString()])
    }

    // launch restore reads the Wine copy's keys for the pc origin (the page writes them back); other origins, META
    // and VERSION stay out.
    @Test
    fun readLsOriginEntries_returnsOnlyUserKeysOfOrigin() {
        val dir = File(tmpRoot, "wine-ls")
        FixtureBuilder.lsWithOrigins(
            dir,
            "file://" to mapOf("a" to "1".toByteArray(), "b" to "2".toByteArray()),
            "http://other.localhost:1" to mapOf("x" to "9".toByteArray()),
        )
        FixtureBuilder.putRaw(dir, "VERSION".toByteArray(), "1".toByteArray())

        val entries = LevelDbRewriter.readLsOriginEntries(dir, "file://")!!

        assertEquals(listOf("a" to "1", "b" to "2"), entries.map { (k, v) -> String(k) to String(v) })
    }

    @Test
    fun readLsOriginEntries_nullWhenOriginHasNoKeys() {
        val dir = File(tmpRoot, "wine-ls-other")
        FixtureBuilder.lsWithOrigins(dir, "http://other.localhost:1" to mapOf("x" to "9".toByteArray()))

        assertNull(LevelDbRewriter.readLsOriginEntries(dir, "file://"))
        assertNull(LevelDbRewriter.readLsOriginEntries(File(tmpRoot, "missing"), "file://"))
    }

    // seeding the staging dir from the wine copy recovers last session's log into yet another table that iq80
    // never compacts away (compactionEnabled=false), so the wine store -- and its cloud copy -- would grow by a
    // full dump per session. the size must track live keys, not play count.
    @Test
    fun rewriteLsOrigin_repeatedOutbound_doesNotAccumulateDeadTables() {
        val pcUrl = "file://"
        val gameA = "http://steam-379210.localhost:59099"
        val live = File(tmpRoot, "webview-ls-growth")
        val wine = File(tmpRoot, "wine-ls-growth")
        // incompressible, so the sizes below track real save bytes rather than snappy's view of them
        val rnd = java.util.Random(42)
        fun blob() = CharArray(payloadBytes) { (rnd.nextInt(94) + 32).toChar() }
        FixtureBuilder.lsWithOrigins(live, gameA to mapOf("save" to ByteArray(4096).also { rnd.nextBytes(it) }))
        FixtureBuilder.putRaw(live, "VERSION".toByteArray(Charsets.US_ASCII), "1".toByteArray())

        fun outbound(session: Int) = LevelDbRewriter.rewriteLsOrigin(
            live, wine, gameA, pcUrl, gameA,
            keepOnlyToOriginInDst = true,
            fromOriginEntries = listOf(
                LocalStorageSnapshot.encode("-1save".toCharArray()) to
                    LocalStorageSnapshot.encode(("s$session" + String(blob())).toCharArray()),
            ),
        )

        repeat(12) { outbound(it + 1) }

        // compaction is gated on table count, so the store rides between its live size and the threshold
        assertTrue("tables kept climbing: ${tableCount(wine)}", tableCount(wine) <= 5)
        assertTrue(
            "wine store is ${storeBytes(wine)} bytes for ~$payloadBytes bytes of live keys",
            storeBytes(wine) <= payloadBytes * 5,
        )
        val pairs = collectKeyValuePairs(wine)
        val pageKey = byteArrayOf('_'.code.toByte()) + pcUrl.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
            LocalStorageSnapshot.encode("-1save".toCharArray())
        val save = pairs.first { (k, _) -> k.contentEquals(pageKey) }.second
        assertTrue(
            "newest session's value must win",
            String(LocalStorageSnapshot.decode(save)).startsWith("s12"),
        )
        assertTrue("VERSION stays", pairs.any { (k, _) -> k.contentEquals("VERSION".toByteArray(Charsets.US_ASCII)) })
    }

    // the IDB-saving game shape: the page localStorage capture is empty, the empty-source guard keeps dst's own
    // keys, and every session still seeds the staging dir from dst.
    @Test
    fun rewriteLsOrigin_repeatedOutbound_withNoPageEntries_doesNotAccumulateDeadTables() {
        val pcUrl = "chrome-extension://anopiimlkmdoenonenclohfilpeenfmj"
        val gameA = "http://steam-2738490.localhost:59099"
        val rnd = java.util.Random(7)
        val live = File(tmpRoot, "webview-ls-noentries")
        val wine = File(tmpRoot, "wine-ls-noentries")
        // src carries another game's keys only -- nothing under gameA
        FixtureBuilder.lsWithOrigins(live, "http://steam-1.localhost:59099" to mapOf("k" to byteArrayOf(1)))
        // the live WebView store always carries VERSION; it is the one key that crosses on this
        // path, and it leaves a non-empty log for the next session's open to recover into another table.
        FixtureBuilder.putRaw(live, "VERSION".toByteArray(Charsets.US_ASCII), "1".toByteArray())
        FixtureBuilder.lsWithOrigins(
            wine,
            pcUrl to mapOf("save" to ByteArray(payloadBytes).also { rnd.nextBytes(it) }),
        )
        FixtureBuilder.putRaw(wine, "VERSION".toByteArray(Charsets.US_ASCII), "1".toByteArray())
        val saveBefore = collectKeyValuePairs(wine).first { (k, _) -> k.contentEquals(usKey(pcUrl, "save")) }.second

        fun outbound() = LevelDbRewriter.rewriteLsOrigin(
            live, wine, gameA, pcUrl, gameA,
            keepOnlyToOriginInDst = true,
            fromOriginEntries = emptyList(),
        )

        repeat(12) { outbound() }

        assertTrue("tables kept climbing: ${tableCount(wine)}", tableCount(wine) <= 5)
        assertTrue(
            "wine store is ${storeBytes(wine)} bytes for ~$payloadBytes bytes of live keys",
            storeBytes(wine) <= payloadBytes * 5,
        )
        val pairs = collectKeyValuePairs(wine)
        assertArrayEquals(
            "the guard must still keep the wine copy's own save",
            saveBefore,
            pairs.first { (k, _) -> k.contentEquals(usKey(pcUrl, "save")) }.second,
        )
        assertTrue("VERSION stays", pairs.any { (k, _) -> k.contentEquals("VERSION".toByteArray(Charsets.US_ASCII)) })
    }

    // one session's worth of live keys in the growth fixtures below
    private val payloadBytes = 96 * 1024

    private fun tableCount(dir: File): Int =
        dir.listFiles().orEmpty().count { it.name.endsWith(".ldb") || it.name.endsWith(".sst") }

    // total bytes of the leveldb payload, ignoring the advisory/runtime files iq80 and chromium
    // recreate on every open.
    private fun storeBytes(dir: File): Long =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name !in setOf("LOG", "LOG.old", "LOCK") }
            .sumOf { it.length() }

    private fun usKey(url: String, key: String): ByteArray =
        byteArrayOf('_'.code.toByte()) + url.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + key.toByteArray(Charsets.US_ASCII)

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
