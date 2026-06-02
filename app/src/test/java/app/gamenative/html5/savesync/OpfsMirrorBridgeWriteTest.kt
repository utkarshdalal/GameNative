package app.gamenative.html5.savesync

import android.util.Base64
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// robolectric for real android.util.Base64, so the write path runs end to end instead of stopping at the
// sandbox check.
@RunWith(RobolectricTestRunner::class)
class OpfsMirrorBridgeWriteTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun bridge(): OpfsMirrorBridge =
        OpfsMirrorBridge(containerId = "test", rootResolver = { tmp.root })

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    @Test fun writeInstallFile_writesExactBytesAndLeavesNoStagingFile() {
        assertTrue(bridge().writeInstallFile("save/slot1.dat", b64("hello")))

        val target = File(tmp.root, "save/slot1.dat")
        assertEquals("hello", target.readText())
        assertEquals(
            "staging file must not survive the write",
            listOf("slot1.dat"),
            target.parentFile.list()!!.sorted(),
        )
    }

    @Test fun writeInstallFile_overwritesExistingContent() {
        File(tmp.root, "save").mkdirs()
        File(tmp.root, "save/slot1.dat").writeText("OLD")

        assertTrue(bridge().writeInstallFile("save/slot1.dat", b64("NEW")))

        assertEquals("NEW", File(tmp.root, "save/slot1.dat").readText())
    }

    // the exit-boundary flush can die mid-write, so the bytes must land somewhere else first and
    // only replace the save once they are all there. squatting the staging path with a directory
    // is the one way to fail the staged write from outside and see which file took the damage.
    @Test fun writeInstallFile_stagingFailureLeavesPreviousSaveIntact() {
        File(tmp.root, "save").mkdirs()
        val target = File(tmp.root, "save/slot1.dat").apply { writeText("OLD") }
        File(tmp.root, "save/slot1.dat.gntmp").mkdirs()

        assertFalse(bridge().writeInstallFile("save/slot1.dat", b64("NEW")))

        assertEquals("OLD", target.readText())
    }

    // GOG uploads by mtime and the exit flush hands back EVERY file, so an unchanged save must
    // keep its mtime or each session re-uploads the whole set.
    @Test fun writeInstallFile_identicalBytesKeepMtime() {
        File(tmp.root, "save").mkdirs()
        val target = File(tmp.root, "save/slot1.dat").apply { writeText("SAME") }
        target.setLastModified(1_000_000_000L)

        assertTrue(bridge().writeInstallFile("save/slot1.dat", b64("SAME")))

        assertEquals(1_000_000_000L, target.lastModified())
        assertEquals("SAME", target.readText())
    }

    // same size, different bytes -- the skip must compare content, not just length.
    @Test fun writeInstallFile_sameSizeDifferentBytesStillReplaces() {
        File(tmp.root, "save").mkdirs()
        val target = File(tmp.root, "save/slot1.dat").apply { writeText("OLD1") }
        target.setLastModified(1_000_000_000L)

        assertTrue(bridge().writeInstallFile("save/slot1.dat", b64("NEW1")))

        assertEquals("NEW1", target.readText())
        assertTrue(target.lastModified() != 1_000_000_000L)
    }
}
