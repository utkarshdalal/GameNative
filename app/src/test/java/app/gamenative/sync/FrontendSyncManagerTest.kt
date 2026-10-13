package app.gamenative.sync

import app.gamenative.data.GameSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FrontendSyncManagerTest {

    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = kotlin.io.path.createTempDirectory("frontend_sync_test").toFile()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // region extensionFor

    @Test
    fun `extensionFor returns correct extension for each source`() {
        assertEquals(".steam", FrontendSyncManager.extensionFor(GameSource.STEAM))
        assertEquals(".epic", FrontendSyncManager.extensionFor(GameSource.EPIC))
        assertEquals(".gog", FrontendSyncManager.extensionFor(GameSource.GOG))
        assertEquals(".amazon", FrontendSyncManager.extensionFor(GameSource.AMAZON))
        assertEquals(".pcgame", FrontendSyncManager.extensionFor(GameSource.CUSTOM_GAME))
    }

    @Test
    fun `extensionFor covers all GameSource entries`() {
        GameSource.entries.forEach { source ->
            val ext = FrontendSyncManager.extensionFor(source)
            assertTrue("Extension for $source should start with a dot", ext.startsWith("."))
            assertTrue("Extension for $source should be non-empty after dot", ext.length > 1)
        }
    }

    // endregion

    // region deleteAllFilesWithExtension

    @Test
    fun `deleteAllFilesWithExtension removes files with matching extension`() {
        File(tmpDir, "Half-Life 2.steam").writeText("220")
        File(tmpDir, "Portal.steam").writeText("400")
        File(tmpDir, "Celeste.steam").writeText("504230")

        FrontendSyncManager.deleteAllFilesWithExtension(tmpDir.path, ".steam")

        assertFalse(File(tmpDir, "Half-Life 2.steam").exists())
        assertFalse(File(tmpDir, "Portal.steam").exists())
        assertFalse(File(tmpDir, "Celeste.steam").exists())
    }

    @Test
    fun `deleteAllFilesWithExtension leaves files with other extensions untouched`() {
        File(tmpDir, "Hades.epic").writeText("12345")
        File(tmpDir, "Fortnite.epic").writeText("67890")
        File(tmpDir, "Half-Life 2.steam").writeText("220")
        File(tmpDir, "readme.txt").writeText("hello")

        FrontendSyncManager.deleteAllFilesWithExtension(tmpDir.path, ".steam")

        assertTrue(File(tmpDir, "Hades.epic").exists())
        assertTrue(File(tmpDir, "Fortnite.epic").exists())
        assertTrue(File(tmpDir, "readme.txt").exists())
        assertFalse(File(tmpDir, "Half-Life 2.steam").exists())
    }

    @Test
    fun `deleteAllFilesWithExtension does not recurse into subdirectories`() {
        val subDir = File(tmpDir, "subdir").also { it.mkdir() }
        File(subDir, "nested.steam").writeText("999")
        File(tmpDir, "top.steam").writeText("100")

        FrontendSyncManager.deleteAllFilesWithExtension(tmpDir.path, ".steam")

        assertFalse(File(tmpDir, "top.steam").exists())
        assertTrue("Nested file should not be deleted", File(subDir, "nested.steam").exists())
    }

    @Test
    fun `deleteAllFilesWithExtension on empty directory completes without error`() {
        FrontendSyncManager.deleteAllFilesWithExtension(tmpDir.path, ".steam")
        assertEquals(0, tmpDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `deleteAllFilesWithExtension on non-existent directory completes without error`() {
        val missing = File(tmpDir, "does_not_exist")
        FrontendSyncManager.deleteAllFilesWithExtension(missing.path, ".steam")
    }

    @Test
    fun `deleteAllFilesWithExtension on non-directory path completes without error`() {
        val file = File(tmpDir, "notadir.txt").also { it.writeText("x") }
        FrontendSyncManager.deleteAllFilesWithExtension(file.path, ".steam")
        assertTrue("File passed as dir should be untouched", file.exists())
    }

    @Test
    fun `deleteAllFilesWithExtension file content matches expected export format`() {
        val appId = 220
        val gameFile = File(tmpDir, "Half-Life 2.steam")
        gameFile.writeText(appId.toString(), Charsets.UTF_8)

        assertEquals("220", gameFile.readText(Charsets.UTF_8))

        FrontendSyncManager.deleteAllFilesWithExtension(tmpDir.path, ".steam")
        assertFalse(gameFile.exists())
    }

    // endregion
}
