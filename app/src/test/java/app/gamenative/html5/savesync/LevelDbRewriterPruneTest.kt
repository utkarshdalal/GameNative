package app.gamenative.html5.savesync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// pruneStaleArtifacts: drop pre-cloud-overlay residuals (older MANIFESTs and lower-numbered
// .log files) so iq80's needsCompaction()-via-numLogFiles>1 trigger doesn't fire mid-restore.
//
// invariants under test:
//   - CURRENT's referenced MANIFEST stays
//   - other MANIFEST-* files get deleted
//   - the highest-numbered .log file stays; older .log files get deleted
//   - .ldb / .sst data files are never touched (they may be referenced by historical
//     manifest entries — deleting them would corrupt the rewritten state)
//   - LOG / LOG.old (telemetry) untouched
class LevelDbRewriterPruneTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test fun prune_deletesObsoleteManifests_keepsLiveManifest() {
        val dir = tempFolder.newFolder("ldb")
        File(dir, "CURRENT").writeText("MANIFEST-000191\n")
        File(dir, "MANIFEST-000006").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "MANIFEST-000191").writeBytes(byteArrayOf(4, 5, 6))

        LevelDbRewriter.pruneStaleArtifacts(dir)

        assertFalse("stale manifest must be deleted", File(dir, "MANIFEST-000006").exists())
        assertTrue("live manifest must survive", File(dir, "MANIFEST-000191").exists())
        assertTrue("CURRENT must survive", File(dir, "CURRENT").exists())
    }

    @Test fun prune_deletesLowerNumberedLogs_keepsHighestLog() {
        // post-cloud-overlay shape: cloud download brought 000193.log (live for new manifest);
        // 000007.log + 000080.log are pre-overlay residuals from a fresh local leveldb.
        val dir = tempFolder.newFolder("ldb")
        File(dir, "CURRENT").writeText("MANIFEST-000191\n")
        File(dir, "MANIFEST-000191").writeBytes(byteArrayOf(1))
        File(dir, "000007.log").writeBytes(byteArrayOf(1))
        File(dir, "000080.log").writeBytes(byteArrayOf(1))
        File(dir, "000193.log").writeBytes(byteArrayOf(1))

        LevelDbRewriter.pruneStaleArtifacts(dir)

        assertFalse("000007.log must be pruned", File(dir, "000007.log").exists())
        assertFalse("000080.log must be pruned", File(dir, "000080.log").exists())
        assertTrue("000193.log (highest) must survive", File(dir, "000193.log").exists())
    }

    @Test fun prune_keepsSstAndLdbDataFiles() {
        // .ldb files may be referenced by historical NewFile entries; deletion would corrupt.
        val dir = tempFolder.newFolder("ldb")
        File(dir, "CURRENT").writeText("MANIFEST-000191\n")
        File(dir, "MANIFEST-000191").writeBytes(byteArrayOf(1))
        File(dir, "000186.ldb").writeBytes(byteArrayOf(1))
        File(dir, "000189.ldb").writeBytes(byteArrayOf(1))
        File(dir, "000192.ldb").writeBytes(byteArrayOf(1))

        LevelDbRewriter.pruneStaleArtifacts(dir)

        assertTrue("000186.ldb must survive", File(dir, "000186.ldb").exists())
        assertTrue("000189.ldb must survive", File(dir, "000189.ldb").exists())
        assertTrue("000192.ldb must survive", File(dir, "000192.ldb").exists())
    }

    @Test fun prune_keepsTelemetryLogs() {
        val dir = tempFolder.newFolder("ldb")
        File(dir, "CURRENT").writeText("MANIFEST-000191\n")
        File(dir, "MANIFEST-000191").writeBytes(byteArrayOf(1))
        File(dir, "LOG").writeBytes(byteArrayOf(1))
        File(dir, "LOG.old").writeBytes(byteArrayOf(1))

        LevelDbRewriter.pruneStaleArtifacts(dir)

        assertTrue("LOG must survive — telemetry, not data", File(dir, "LOG").exists())
        assertTrue("LOG.old must survive — telemetry, not data", File(dir, "LOG.old").exists())
    }

    @Test fun prune_singleLogFile_isNoop() {
        // common case: dir already has only one .log; prune must not touch it.
        val dir = tempFolder.newFolder("ldb")
        File(dir, "CURRENT").writeText("MANIFEST-000191\n")
        File(dir, "MANIFEST-000191").writeBytes(byteArrayOf(1))
        File(dir, "000193.log").writeBytes(byteArrayOf(1))

        LevelDbRewriter.pruneStaleArtifacts(dir)

        assertTrue("single .log must survive", File(dir, "000193.log").exists())
    }

    @Test fun prune_currentMissing_doesNotThrow_doesNotTouchManifests() {
        // defensive: if CURRENT is missing or unreadable, prune leaves manifests alone (we
        // don't know which one is live).
        val dir = tempFolder.newFolder("ldb")
        File(dir, "MANIFEST-000006").writeBytes(byteArrayOf(1))
        File(dir, "MANIFEST-000191").writeBytes(byteArrayOf(1))

        LevelDbRewriter.pruneStaleArtifacts(dir)

        assertTrue(File(dir, "MANIFEST-000006").exists())
        assertTrue(File(dir, "MANIFEST-000191").exists())
    }

    @Test fun prune_combinedPostCloudOverlayShape_matchesProductionScenario() {
        // exact production scenario from sol cesto cross-device restore:
        // cloud download into wine prefix that already had odin's own outbound residuals.
        val dir = tempFolder.newFolder("ldb")
        File(dir, "CURRENT").writeText("MANIFEST-000191\n")
        File(dir, "MANIFEST-000006").writeBytes(byteArrayOf(1))      // odin's outbound
        File(dir, "MANIFEST-000191").writeBytes(byteArrayOf(1))      // cloud download (live)
        File(dir, "000007.log").writeBytes(byteArrayOf(1))           // odin's outbound
        File(dir, "000080.log").writeBytes(byteArrayOf(1))           // older
        File(dir, "000193.log").writeBytes(byteArrayOf(1))           // cloud download (live)
        File(dir, "000186.ldb").writeBytes(byteArrayOf(1))           // cloud
        File(dir, "000189.ldb").writeBytes(byteArrayOf(1))           // cloud
        File(dir, "000192.ldb").writeBytes(byteArrayOf(1))           // cloud
        File(dir, "LOG").writeBytes(byteArrayOf(1))
        File(dir, "LOG.old").writeBytes(byteArrayOf(1))

        LevelDbRewriter.pruneStaleArtifacts(dir)

        // pruned
        assertFalse(File(dir, "MANIFEST-000006").exists())
        assertFalse(File(dir, "000007.log").exists())
        assertFalse(File(dir, "000080.log").exists())
        // survived
        assertTrue(File(dir, "MANIFEST-000191").exists())
        assertTrue(File(dir, "CURRENT").exists())
        assertTrue(File(dir, "000193.log").exists())
        assertTrue(File(dir, "000186.ldb").exists())
        assertTrue(File(dir, "000189.ldb").exists())
        assertTrue(File(dir, "000192.ldb").exists())
        assertTrue(File(dir, "LOG").exists())
        assertTrue(File(dir, "LOG.old").exists())
    }
}
