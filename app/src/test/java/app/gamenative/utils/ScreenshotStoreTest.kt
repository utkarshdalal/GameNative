package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScreenshotStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `fileNameFor uses game name then date with png extension`() {
        val name = ScreenshotStore.fileNameFor("Half-Life 2", 1_700_000_000_000L)
        assertTrue(name, name.startsWith("Half-Life 2_"))
        assertTrue(name, name.endsWith(".png"))
        // <name>_<yyyy-MM-dd_HH-mm-ss>.png
        assertTrue(name, Regex("""Half-Life 2_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.png""").matches(name))
    }

    @Test
    fun `fileNameFor adds sequence suffix to avoid same-second collisions`() {
        val base = ScreenshotStore.fileNameFor("Doom", 1_700_000_000_000L, 0)
        val collide = ScreenshotStore.fileNameFor("Doom", 1_700_000_000_000L, 1)
        assertEquals(base.removeSuffix(".png") + "-1.png", collide)
    }

    @Test
    fun `uniqueDownloadName returns name unchanged when free`() {
        assertEquals("Doom_2026.png", ScreenshotStore.uniqueDownloadName("Doom_2026.png") { false })
    }

    @Test
    fun `uniqueDownloadName appends sequence before extension on collision`() {
        val taken = setOf("Doom_2026.png", "Doom_2026 (1).png")
        assertEquals("Doom_2026 (2).png", ScreenshotStore.uniqueDownloadName("Doom_2026.png") { it in taken })
    }

    @Test
    fun `uniqueDownloadName appends sequence to bare name when there is no extension`() {
        val taken = setOf("Doom")
        assertEquals("Doom (1)", ScreenshotStore.uniqueDownloadName("Doom") { it in taken })
    }

    @Test
    fun `sanitizeGameName replaces filesystem-unsafe characters`() {
        assertEquals("Portal_ Reloaded", ScreenshotStore.sanitizeGameName("Portal: Reloaded"))
        assertEquals("a_b_c", ScreenshotStore.sanitizeGameName("a/b\\c"))
    }

    @Test
    fun `sanitizeGameName falls back to Screenshot when blank`() {
        assertEquals("Screenshot", ScreenshotStore.sanitizeGameName("   "))
        assertEquals("Screenshot", ScreenshotStore.sanitizeGameName("///"))
    }

    @Test
    fun `resolveRoot uses internal screenshots dir when not external`() {
        val internal = tmp.newFolder("files")
        val root = ScreenshotStore.resolveRoot(internal, useExternal = false, externalPath = "")
        assertEquals(File(internal, "screenshots"), root)
    }

    @Test
    fun `resolveRoot falls back to internal when external path is blank`() {
        val internal = tmp.newFolder("files")
        val root = ScreenshotStore.resolveRoot(internal, useExternal = true, externalPath = "")
        assertEquals(File(internal, "screenshots"), root)
    }

    @Test
    fun `resolveRoot uses external path when external and path set`() {
        val internal = tmp.newFolder("files")
        val ext = tmp.newFolder("ext")
        val root = ScreenshotStore.resolveRoot(internal, useExternal = true, externalPath = ext.absolutePath)
        assertEquals(ext, root)
    }

    @Test
    fun `gameDir is appId subdirectory of root`() {
        val root = tmp.newFolder("root")
        assertEquals(File(root, "STEAM_280190"), ScreenshotStore.gameDir(root, "STEAM_280190"))
    }

    @Test
    fun `gameDir sanitizes appId so it cannot escape root via path traversal`() {
        val root = tmp.newFolder("root")
        // Separators and traversal tokens are collapsed to a single safe segment under root.
        assertEquals(root, ScreenshotStore.gameDir(root, "../..").parentFile)
        assertEquals(root, ScreenshotStore.gameDir(root, "../../etc/passwd").parentFile)
        assertEquals(root, ScreenshotStore.gameDir(root, "a/b").parentFile)
    }

    @Test
    fun `list returns empty when game dir does not exist`() {
        val root = tmp.newFolder("root")
        assertEquals(emptyList<ScreenshotItem>(), ScreenshotStore.list(root, "STEAM_1"))
    }

    @Test
    fun `list returns png files sorted by last-modified newest first and ignores non-png`() {
        val root = tmp.newFolder("root")
        val dir = File(root, "STEAM_1").apply { mkdirs() }
        val oldest = File(dir, "Doom_2026-01-01_10-00-00.png").apply { writeText("a"); setLastModified(100L) }
        val newest = File(dir, "Doom_2026-01-03_10-00-00.png").apply { writeText("b"); setLastModified(300L) }
        val middle = File(dir, "Doom_2026-01-02_10-00-00.png").apply { writeText("c"); setLastModified(200L) }
        File(dir, "notes.txt").writeText("ignore")

        val result = ScreenshotStore.list(root, "STEAM_1")

        assertEquals(listOf(newest.name, middle.name, oldest.name), result.map { it.file.name })
        assertEquals(listOf(300L, 200L, 100L), result.map { it.dateTakenMillis })
    }

    @Test
    fun `list matches png extension case-insensitively`() {
        val root = tmp.newFolder("root")
        val dir = File(root, "STEAM_1").apply { mkdirs() }
        File(dir, "Doom_2026-01-01_10-00-00.PNG").apply { writeText("a"); setLastModified(100L) }

        val result = ScreenshotStore.list(root, "STEAM_1")

        assertEquals(listOf("Doom_2026-01-01_10-00-00.PNG"), result.map { it.file.name })
    }
}
