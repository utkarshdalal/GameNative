package app.gamenative.html5.shim

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Html5FsBridgeRmTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun bridge(root: File, driveC: File? = null) = Html5FsBridge("c1", root, wineDriveC = driveC)

    @Test
    fun recursiveRemovesATree() {
        val root = tmp.newFolder("root")
        File(root, "data/7/record.bin").apply { parentFile.mkdirs(); writeText("x") }
        File(root, "data/7/meta.json").writeText("{}")
        assertTrue(bridge(root).rm("data/7", true))
        assertFalse(File(root, "data/7").exists())
        assertTrue(File(root, "data").isDirectory)
    }

    @Test
    fun aNonEmptyDirNeedsRecursive() {
        val root = tmp.newFolder("root")
        File(root, "d/f").apply { parentFile.mkdirs(); writeText("x") }
        assertFalse(bridge(root).rm("d", false))
        assertTrue(File(root, "d/f").exists())
    }

    @Test
    fun aLinkInsideIsRemovedWithoutTouchingWhatItPointsTo() {
        val root = tmp.newFolder("root")
        val outside = tmp.newFolder("outside")
        File(outside, "keep.txt").writeText("keep")
        File(root, "d").mkdirs()
        Files.createSymbolicLink(File(root, "d/link").toPath(), outside.toPath())
        assertTrue(bridge(root).rm("d", true))
        assertFalse(File(root, "d").exists())
        assertTrue(File(outside, "keep.txt").exists())
    }

    @Test
    fun theRootsThemselvesAreNeverRemoved() {
        val root = tmp.newFolder("root")
        val driveC = tmp.newFolder("drive_c")
        File(driveC, "users").mkdirs()
        assertFalse(bridge(root, driveC).rm("C:/", true))
        assertFalse(bridge(root, driveC).rm(".", true))
        assertTrue(driveC.isDirectory)
        assertTrue(root.isDirectory)
    }

    @Test
    fun missingAndEscapingPathsAreRefused() {
        val root = tmp.newFolder("root")
        assertFalse(bridge(root).rm("nope", true))
        assertFalse(bridge(root).rm("../outside", true))
    }
}
