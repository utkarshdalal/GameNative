package app.gamenative.service.rockstar

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RockstarRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun findsTheInstallerTheGameShipsAndTheInstalledRuntime() {
        val game = temporary.newFolder()
        assertNull(RockstarRuntime.installer(game))
        File(game, "Redistributables").mkdirs()
        File(game, "Redistributables/Social-Club-Setup.exe").writeText("MZ")
        assertEquals("Redistributables\\Social-Club-Setup.exe", RockstarRuntime.installer(game))
        val prefix = temporary.newFolder()
        assertFalse(RockstarRuntime.isInstalled(prefix))
        RockstarRuntime.socialClubDir(prefix).mkdirs()
        File(RockstarRuntime.socialClubDir(prefix), "socialclub.dll").writeText("MZ")
        assertTrue(RockstarRuntime.isInstalled(prefix))
    }
}
