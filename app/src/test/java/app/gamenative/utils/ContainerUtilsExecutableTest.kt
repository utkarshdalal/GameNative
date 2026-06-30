package app.gamenative.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerUtilsExecutableTest {

    @Test
    fun isUriScheme_returnsTrueForLink2ea() {
        assertTrue(ContainerUtils.isUriScheme("link2ea://launchgame/Origin.OFR.50.0002694"))
    }

    @Test
    fun isUriScheme_returnsTrueForSteamProtocol() {
        assertTrue(ContainerUtils.isUriScheme("steam://rungameid/12345"))
    }

    @Test
    fun isUriScheme_returnsFalseForExe() {
        assertFalse(ContainerUtils.isUriScheme("game.exe"))
    }

    @Test
    fun isUriScheme_returnsFalseForWindowsPath() {
        assertFalse(ContainerUtils.isUriScheme("C:\\Program Files\\game.exe"))
    }

    @Test
    fun isUriScheme_returnsFalseForEmpty() {
        assertFalse(ContainerUtils.isUriScheme(""))
    }

    @Test
    fun isBatchScript_returnsTrueForBat() {
        assertTrue(ContainerUtils.isBatchScript("launch.bat"))
    }

    @Test
    fun isBatchScript_returnsTrueForCmd() {
        assertTrue(ContainerUtils.isBatchScript("start.cmd"))
    }

    @Test
    fun isBatchScript_isCaseInsensitive() {
        assertTrue(ContainerUtils.isBatchScript("LAUNCH.BAT"))
        assertTrue(ContainerUtils.isBatchScript("Start.Cmd"))
    }

    @Test
    fun isBatchScript_returnsFalseForExe() {
        assertFalse(ContainerUtils.isBatchScript("game.exe"))
    }

    @Test
    fun isBatchScript_returnsFalseForEmpty() {
        assertFalse(ContainerUtils.isBatchScript(""))
    }

    @Test
    fun scanExecutablesInADrive_findsBatAndCmdFiles() {
        val tempDir = kotlin.io.path.createTempDirectory("scan-test").toFile()
        try {
            val drives = "A:${tempDir.absolutePath}"
            java.io.File(tempDir, "game.exe").createNewFile()
            java.io.File(tempDir, "launcher.bat").createNewFile()
            java.io.File(tempDir, "setup.cmd").createNewFile()
            java.io.File(tempDir, "readme.txt").createNewFile()

            val results = ContainerUtils.scanExecutablesInADrive(drives)

            assertTrue("Should find game.exe", results.any { it.endsWith("game.exe") })
            assertTrue("Should find launcher.bat", results.any { it.endsWith("launcher.bat") })
            assertTrue("Should find setup.cmd", results.any { it.endsWith("setup.cmd") })
            assertFalse("Should not find readme.txt", results.any { it.endsWith("readme.txt") })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun scanExecutablesInADrive_exeRanksHigherThanBatCmd() {
        val tempDir = kotlin.io.path.createTempDirectory("scan-priority-test").toFile()
        try {
            val drives = "A:${tempDir.absolutePath}"
            java.io.File(tempDir, "game.exe").createNewFile()
            java.io.File(tempDir, "game.bat").createNewFile()

            val results = ContainerUtils.scanExecutablesInADrive(drives)

            val exeIndex = results.indexOfFirst { it.endsWith("game.exe") }
            val batIndex = results.indexOfFirst { it.endsWith("game.bat") }
            assertTrue("game.exe should be found", exeIndex >= 0)
            assertTrue("game.bat should be found", batIndex >= 0)
            assertTrue("game.exe should rank before game.bat", exeIndex < batIndex)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
