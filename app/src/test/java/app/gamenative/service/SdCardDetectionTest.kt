package app.gamenative.service

import app.gamenative.enums.Marker
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SdCardDetectionTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun createGameDir(base: File, gameName: String, complete: Boolean): File {
        val dir = File(base, gameName)
        dir.mkdirs()
        if (complete) {
            File(dir, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
        }
        return dir
    }

    @Test
    fun `completed install on second volume preferred over partial on first`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        createGameDir(internal, "MyGame", complete = false)
        createGameDir(sdcard, "MyGame", complete = true)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(File(sdcard, "MyGame").absolutePath, result)
    }

    @Test
    fun `falls back to first existing when none are complete`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        createGameDir(internal, "MyGame", complete = false)
        createGameDir(sdcard, "MyGame", complete = false)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(File(internal, "MyGame").absolutePath, result)
    }

    @Test
    fun `returns null when no directory exists`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val paths = listOf(internal.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertNull(result)
    }

    @Test
    fun `empty name is skipped — never returns install root`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val paths = listOf(internal.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf(""))

        assertNull(result)
    }
}
