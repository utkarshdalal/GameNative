package app.gamenative.service

import app.gamenative.data.ConfigInfo
import app.gamenative.data.SteamApp
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

    @Test
    fun `dot names are skipped — never returns install root or its parent`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        createGameDir(internal, "OtherGame", complete = true)
        val paths = listOf(internal.absolutePath)

        assertNull(SteamService.resolveExistingAppDir(paths, listOf(".", "..", "./", "OtherGame/..", " . ")))
    }

    @Test
    fun `dot installdir falls back to the app name`() {
        val app = SteamApp(id = 3082170, name = "Skeleton Hell", config = ConfigInfo(installDir = "."))

        assertEquals("Skeleton Hell", SteamService.getAppDirName(app))
    }

    @Test
    fun `unsafe installdir and name fall back to the app id`() {
        val app = SteamApp(id = 42, name = "..", config = ConfigInfo(installDir = "../.."))

        assertEquals("42", SteamService.getAppDirName(app))
    }

    @Test
    fun `install root and its parents are never a game folder`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val game = createGameDir(internal, "MyGame", complete = true)
        val roots = listOf(internal.absolutePath)

        assertTrue(SteamService.isInstallRootOrAbove(File(internal, "."), roots))
        assertTrue(SteamService.isInstallRootOrAbove(internal, roots))
        assertTrue(SteamService.isInstallRootOrAbove(internal.parentFile!!, roots))
        assertFalse(SteamService.isInstallRootOrAbove(game, roots))
    }
}
