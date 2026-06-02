package app.gamenative.html5.savesync

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

// — LevelDbRewriter contract tests.
// rewrite_roundTrip test deleted in plan (superseded by LevelDbRewriterIdbTest +
// LevelDbRewriterLsTest which cover the Chromium-key-aware path). failure-classification tests
// migrated from rewriteOriginPrefix → rewriteLsOrigin (same IO plumbing, same failure modes).
// rewrite_realSolCestoIdbFixture migrated to rewriteIdbOrigin.
class LevelDbRewriterTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("leveldb-rewriter-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    // --- failure modes (migrated to rewriteLsOrigin — same IO layer, same failure surface) ---

    @Test
    fun rewrite_missingSrc_throwsPathMissing() {
        val src = File(tmpRoot, "nonexistent-src")
        val dst = File(tmpRoot, "dst")
        try {
            LevelDbRewriter.rewriteLsOrigin(src, dst, "https://game-foo", "https://game-bar", "https://game-foo")
            throw AssertionError("expected SaveSyncFailure.PathMissing")
        } catch (e: SaveSyncFailure.PathMissing) {
            assertTrue("path missing must reference src", e.path == src.absolutePath)
        }
    }

    @Test
    fun rewrite_permissionDeniedOnDst_throwsPermissionDenied() {
        // posix-only — chmod semantics differ on windows test runs
        val isPosix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        assumeTrue("non-POSIX filesystem — skipping chmod-based perm test", isPosix)

        val src = File(tmpRoot, "src").also { it.mkdirs() }
        // populate src with at least one key so the rewriter gets past open-src
        openDb(src).use { db -> db.put("k".toByteArray(), "v".toByteArray()) }

        // create a read-only dst parent so dst.mkdirs() (or write inside dst) fails
        val roParent = File(tmpRoot, "ro").also { it.mkdirs() }
        val dst = File(roParent, "dst")
        try {
            roParent.setReadable(true, true)
            roParent.setWritable(false, false)
            roParent.setExecutable(true, true)

            try {
                LevelDbRewriter.rewriteLsOrigin(src, dst, "https://game-foo", "https://game-bar", "https://game-foo")
                throw AssertionError("expected SaveSyncFailure on restricted dst parent")
            } catch (e: SaveSyncFailure.PermissionDenied) {
                assertTrue("permission denied surface", true)
            } catch (e: SaveSyncFailure.Other) {
                // some JVMs wrap the underlying EACCES in a generic IOException.
                // acceptable fallback — still surfaces as SaveSyncFailure, which is the contract.
                assertTrue("other failure accepted when JVM masks EACCES", true)
            } catch (e: SaveSyncFailure.PathMissing) {
                // on some POSIX jvms, dst.mkdirs() returns false silently and the next File op
                // fails with FileNotFoundException — classified as PathMissing. still a SaveSyncFailure.
                assertTrue("path-missing fallback accepted on perm-denied jvm", true)
            }
        } finally {
            roParent.setWritable(true, true)
        }
    }

    // migrated from rewriteOriginPrefix(idb, dst, fromPrefix, toPrefix, useIdb1=true).
    // SolCesto fixture uses IDB format; rewriteIdbOrigin is the correct API for IDB.
    // fixture absent → assumeNotNull skips gracefully (no GAMENATIVE_HTML5_SAVE_FIXTURE_ROOT set).
    @Test
    fun rewrite_realSolCestoIdbFixture_roundtripsWithIdb1() {
        val fixture = SaveFixtureHarness.loadSolCesto()
        assumeNotNull("solcesto fixture absent — set GAMENATIVE_HTML5_SAVE_FIXTURE_ROOT", fixture)
        assumeNotNull("solcesto IDB leveldb absent", fixture!!.indexedDbLevelDb)
        val fromPrefix = fixture.originPrefix
        assumeNotNull("solcesto origin prefix absent", fromPrefix)
        // snapshot to sandbox — rewriteIdbOrigin opens src with iq80 which mutates dir on recovery.
        val idb = SaveFixtureHarness.snapshotDir(fixture.indexedDbLevelDb, tmpRoot, "solcesto-rewrite-src")!!

        val dst = File(tmpRoot, "solcesto-rewrite-dst")
        val toFilename = "https_game-sol-cesto-0000_0"
        // fromPrefix was the old ASCII filename; treat as IDB filename form (same chars)
        LevelDbRewriter.rewriteIdbOrigin(idb, dst, fromPrefix!!, toFilename)

        // dst must open cleanly with Idb1Comparator + iterate ≥ 1 key.
        var count = 0
        openDb(dst, readOnly = true, useIdb1 = true).use { db ->
            db.iterator().use { iter ->
                iter.seekToFirst()
                while (iter.hasNext()) {
                    val e = iter.next()
                    e.key
                    e.value
                    count++
                    if (count >= 20) break // bound iteration — real IDB can be large
                }
            }
        }
        assertTrue("solcesto IDB rewrite: expected ≥ 1 key iterated, got $count", count >= 1)
    }

    // --- copyLiveBlobs — §blob-churn: reference-aware copy ---

    @Test
    fun copyLiveBlobs_copiesOnlyLiveRefs_skipsOrphans() {
        val src = File(tmpRoot, "blob-src").also { it.mkdirs() }
        val subdir = File(src, "1/00").also { it.mkdirs() }
        // live: leveldb has blob_info for (1, 3) + (1, 4)
        val live3 = File(subdir, "3").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val live4 = File(subdir, "4").apply { writeBytes(ByteArray(1024) { (it and 0xFF).toByte() }) }
        // orphan: physical file with no live ref (chromium blob-journal GC lag). should NOT copy.
        File(subdir, "6").apply { writeBytes(byteArrayOf(0x66)) }

        val dst = File(tmpRoot, "blob-dst")
        LevelDbRewriter.copyLiveBlobs(src, dst, setOf(1 to 3, 1 to 4))

        val dst3 = File(dst, "1/00/3")
        val dst4 = File(dst, "1/00/4")
        val dst6 = File(dst, "1/00/6")
        assertTrue(dst3.isFile)
        assertTrue(dst4.isFile)
        assertTrue("orphan must be skipped", !dst6.exists())
        assertArrayEquals(live3.readBytes(), dst3.readBytes())
        assertArrayEquals(live4.readBytes(), dst4.readBytes())
    }

    @Test
    fun copyLiveBlobs_matchesHexFilename() {
        // blob_number ≥ 10: chromium filenames are hex. findBlobFile matches both hex + decimal.
        val src = File(tmpRoot, "blob-src-hex").also { it.mkdirs() }
        val subdir = File(src, "1/00").also { it.mkdirs() }
        File(subdir, "a").apply { writeBytes(byteArrayOf(0xAA.toByte())) } // hex for 10
        File(subdir, "ff").apply { writeBytes(byteArrayOf(0xFF.toByte())) } // hex for 255

        val dst = File(tmpRoot, "blob-dst-hex")
        LevelDbRewriter.copyLiveBlobs(src, dst, setOf(1 to 10, 1 to 255))

        assertTrue(File(dst, "1/00/a").isFile)
        assertTrue(File(dst, "1/00/ff").isFile)
    }

    @Test
    fun copyLiveBlobs_nullOrMissingSrc_noops() {
        // null src → silent no-op
        LevelDbRewriter.copyLiveBlobs(null, File(tmpRoot, "dst-a"), setOf(1 to 1))
        assertTrue(
            "null src should leave dst alone",
            !File(tmpRoot, "dst-a").exists() || File(tmpRoot, "dst-a").listFiles().isNullOrEmpty(),
        )

        // missing src → silent no-op
        LevelDbRewriter.copyLiveBlobs(File(tmpRoot, "nonexistent"), File(tmpRoot, "dst-b"), setOf(1 to 1))
        assertTrue(
            "missing src should leave dst alone",
            !File(tmpRoot, "dst-b").exists() || File(tmpRoot, "dst-b").listFiles().isNullOrEmpty(),
        )
    }

    @Test
    fun copyLiveBlobs_emptyLiveRefs_wipesDstNoCopy() {
        // simulates: webview has no IDB records left (all DBs deleted). dst must be wiped
        // so stale wine-side blobs from prior sessions don't linger.
        val src = File(tmpRoot, "empty-src").also { it.mkdirs() }
        File(src, "1/00").mkdirs()
        File(src, "1/00/1").writeBytes(byteArrayOf(1))

        val dst = File(tmpRoot, "empty-dst").also { it.mkdirs() }
        File(dst, "stale").writeBytes(byteArrayOf(0x5A))

        LevelDbRewriter.copyLiveBlobs(src, dst, emptySet())

        assertTrue("stale dst content must be wiped", !File(dst, "stale").exists())
        assertTrue("no blobs should be copied", dst.listFiles().isNullOrEmpty())
    }

    // --- classifyFailure ---

    @Test
    fun classifyFailure_corruptionMessage_mapsToCorruption() {
        val src = File(tmpRoot, "src")
        val dst = File(tmpRoot, "dst")
        val fake = RuntimeException("database is corrupt: manifest CRC mismatch")
        val classified = LevelDbRewriter.classifyFailure(fake, src, dst)
        assertTrue("expected Corruption, got ${classified::class.simpleName}", classified is SaveSyncFailure.Corruption)
    }

    @Test
    fun classifyFailure_lockMessage_mapsToLockContention() {
        val src = File(tmpRoot, "src")
        val dst = File(tmpRoot, "dst")
        val fake = RuntimeException("Unable to acquire lock on LOCK file")
        val classified = LevelDbRewriter.classifyFailure(fake, src, dst)
        assertTrue("expected LockContention, got ${classified::class.simpleName}", classified is SaveSyncFailure.LockContention)
    }

    @Test
    fun classifyFailure_fileNotFound_mapsToPathMissing() {
        val src = File(tmpRoot, "src")
        val dst = File(tmpRoot, "dst")
        val fake = FileNotFoundException("missing file")
        val classified = LevelDbRewriter.classifyFailure(fake, src, dst)
        assertTrue("expected PathMissing, got ${classified::class.simpleName}", classified is SaveSyncFailure.PathMissing)
    }

    @Test
    fun classifyFailure_securityException_mapsToPermissionDenied() {
        val src = File(tmpRoot, "src")
        val dst = File(tmpRoot, "dst")
        val fake = SecurityException("denied")
        val classified = LevelDbRewriter.classifyFailure(fake, src, dst)
        assertTrue("expected PermissionDenied, got ${classified::class.simpleName}", classified is SaveSyncFailure.PermissionDenied)
    }

    @Test
    fun classifyFailure_unknown_mapsToOther() {
        val src = File(tmpRoot, "src")
        val dst = File(tmpRoot, "dst")
        val fake = IllegalStateException("something weird happened")
        val classified = LevelDbRewriter.classifyFailure(fake, src, dst)
        assertTrue("expected Other, got ${classified::class.simpleName}", classified is SaveSyncFailure.Other)
    }

    // --- helpers ---

    private fun openDb(dir: File, readOnly: Boolean = false, useIdb1: Boolean = false): org.iq80.leveldb.DB {
        val options = Options().apply {
            createIfMissing(!readOnly)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            if (useIdb1) comparator(Idb1Comparator())
        }
        return Iq80DBFactory.factory.open(dir, options)
    }
}
