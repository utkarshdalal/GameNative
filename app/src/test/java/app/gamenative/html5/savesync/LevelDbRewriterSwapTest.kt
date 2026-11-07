package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// the swap must be rename-based: wipe-then-copy leaves a window where a kill empties or half-writes the wine
// save store -- the exact directory uploaded at close -- and android kills processes at teardown, which is
// when this runs.
class LevelDbRewriterSwapTest {

    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("swap-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    private fun store(dir: File, marker: String) {
        dir.mkdirs()
        File(dir, "CURRENT").writeText(marker)
        File(dir, "000003.ldb").writeBytes(ByteArray(1024) { marker.first().code.toByte() })
    }

    @Test
    fun swapIn_replacesTheStoreAndLeavesNoScratch() {
        val dst = File(tmpRoot, "leveldb").also { store(it, "OLD") }
        val staging = File(tmpRoot, "staging").also { store(it, "NEW") }

        LevelDbRewriter.swapIn(staging, dst)

        assertEquals("NEW", File(dst, "CURRENT").readText())
        // the staged tree is RENAMED in, not copied, so it is consumed by the swap
        assertEquals(
            "no swap scratch may survive a completed swap",
            listOf("leveldb"),
            tmpRoot.list()!!.sorted(),
        )
    }

    // same-filesystem staging is renamed in; a caller that staged on another filesystem still
    // gets a correct swap, just with one copy to bring the tree across first.
    @Test
    fun swapIn_fromAnotherFilesystemStillSwaps() {
        val dst = File(tmpRoot, "leveldb").also { store(it, "OLD") }
        val elsewhere = Files.createTempDirectory("other-fs-").toFile()
        try {
            store(elsewhere, "NEW")
            LevelDbRewriter.swapIn(elsewhere, dst)
            assertEquals("NEW", File(dst, "CURRENT").readText())
            assertEquals(listOf("leveldb"), tmpRoot.list()!!.sorted())
        } finally {
            elsewhere.deleteRecursively()
        }
    }

    @Test
    fun swapIn_ontoAFreshDestination_works() {
        val dst = File(tmpRoot, "leveldb")
        val staging = File(tmpRoot, "staging").also { store(it, "NEW") }

        LevelDbRewriter.swapIn(staging, dst)

        assertEquals("NEW", File(dst, "CURRENT").readText())
    }

    // a failed swap leaves the ORIGINAL store in place. with an empty source there is nothing to rename in,
    // so the swap has to put back what it moved aside instead of leaving the game with no store at all.
    @Test
    fun swapIn_failedSwapRestoresTheOriginalStore() {
        val dst = File(tmpRoot, "leveldb").also { store(it, "OLD") }
        val before = File(dst, "000003.ldb").readBytes()
        val staging = File(tmpRoot, "staging-empty") // never created: nothing to copy

        val failed = runCatching { LevelDbRewriter.swapIn(staging, dst) }.isFailure

        assertTrue("an empty rebuild must not report success", failed)
        assertTrue("destination must still exist", dst.isDirectory)
        assertEquals("OLD", File(dst, "CURRENT").readText())
        assertArrayEquals(before, File(dst, "000003.ldb").readBytes())
        assertEquals(
            "no swap scratch may be left behind",
            listOf("leveldb"),
            tmpRoot.list()!!.sorted(),
        )
    }

    // a kill between the two renames leaves dst missing and .gnold holding the only copy
    @Test
    fun recoverInterruptedSwap_restoresTheStoreFromGnold() {
        val dst = File(tmpRoot, "leveldb")
        store(File(tmpRoot, "leveldb.gnold"), "OLD")

        LevelDbRewriter.recoverInterruptedSwap(dst)

        assertTrue(dst.isDirectory)
        assertEquals("OLD", File(dst, "CURRENT").readText())
        assertFalse(File(tmpRoot, "leveldb.gnold").exists())
    }

    @Test
    fun recoverInterruptedSwap_dropsAPartialGnnewAndKeepsALiveStore() {
        val dst = File(tmpRoot, "leveldb").also { store(it, "LIVE") }
        store(File(tmpRoot, "leveldb.gnnew"), "PARTIAL")
        store(File(tmpRoot, "leveldb.gnold"), "STALE")

        LevelDbRewriter.recoverInterruptedSwap(dst)

        assertEquals("LIVE", File(dst, "CURRENT").readText())
        assertFalse(File(tmpRoot, "leveldb.gnnew").exists())
        assertFalse(File(tmpRoot, "leveldb.gnold").exists())
    }
}
