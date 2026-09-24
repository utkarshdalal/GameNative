package app.gamenative.service.rockstar

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RockstarHelperDeploymentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun stagesOnlyPrivateHelperFilesAndOverwritesChangedCopies() {
        val files = temporary.newFolder()
        val game = temporary.newFolder()
        RockstarHelperArchive.ensureExtracted(files) { rockstarTestArchive().inputStream() }
        File(game, "Game.exe").writeText("original game")
        File(game, "bink2w64.dll").writeText("original bink")
        File(game, "rgscstub.ini").writeText("user settings")
        RockstarHelperDeployment.prepare(files, game)
        assertEquals("${RockstarHelperDeployment.DIRECTORY}/Launcher.exe", RockstarHelperDeployment.executable(game))
        val helper = File(game, RockstarHelperDeployment.executable(game))
        val bytes = helper.readBytes()
        assertEquals(setOf("Game.exe", "bink2w64.dll", "rgscstub.ini", RockstarHelperDeployment.DIRECTORY), game.list()!!.toSet())
        assertEquals("original bink", File(game, "bink2w64.dll").readText())
        RockstarHelperDeployment.prepare(files, game)
        helper.writeText("damaged")
        RockstarHelperDeployment.prepare(files, game)
        assertArrayEquals(bytes, helper.readBytes())
        assertEquals("original game", File(game, "Game.exe").readText())
        assertEquals("user settings", File(game, "rgscstub.ini").readText())
    }

    @Test fun stagesIntoTheSubdirectoryThatHoldsTheTitle() {
        val files = temporary.newFolder()
        val game = temporary.newFolder()
        RockstarHelperArchive.ensureExtracted(files) { rockstarTestArchive().inputStream() }
        val title = File(game, "GTAIV").apply { mkdir() }
        File(title, "title.rgl").writeText("RGLM")
        assertEquals("GTAIV/${RockstarHelperDeployment.DIRECTORY}/Launcher.exe", RockstarHelperDeployment.executable(game))
        RockstarHelperDeployment.prepare(files, game)
        assertTrue(File(game, RockstarHelperDeployment.executable(game)).isFile)
        assertFalse(File(game, RockstarHelperDeployment.DIRECTORY).exists())
        val report = File(title, "${RockstarHelperDeployment.DIRECTORY}/game-executable.txt")
        report.writeText("GTAIV.exe")
        assertEquals("GTAIV.exe", RockstarHelperDeployment.gameExecutable(game))
    }

    @Test fun acceptsOnlySafeExecutableReportsFromStub() {
        val game = temporary.newFolder()
        assertNull(RockstarHelperDeployment.gameExecutable(game))
        val report = File(game, "${RockstarHelperDeployment.DIRECTORY}/game-executable.txt")
        report.parentFile!!.mkdir()
        report.writeText("Bin/Game.exe")
        assertEquals("Bin/Game.exe", RockstarHelperDeployment.gameExecutable(game))
        report.writeText("../outside.exe")
        assertNull(RockstarHelperDeployment.gameExecutable(game))
    }
}
