package app.gamenative.gamefixes

import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScummGameFixTest {
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("scumm-fix-tests").toFile()
        baseDir.deleteOnExit()
    }

    @Test
    fun apply_returnsFalse_whenScummVmExecutableIsMissing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val installDir = File(baseDir, "missing-exe").apply { mkdirs() }
        val container = createContainer("c1")
        val fix = ScummGameFix(gameSource = GameSource.GOG, gameId = "1454587428")

        val result = fix.apply(
            context = context,
            gameId = "1454587428",
            installPath = installDir.absolutePath,
            installPathWindows = "A:\\Games\\BrokenSword",
            container = container,
        )

        assertFalse(result)
        assertEquals("", container.execArgs)
    }

    @Test
    fun apply_setsLaunchArgsAndUpdatesIni_whenExecutableAndIniArePresent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val installDir = File(baseDir, "ok").apply { mkdirs() }
        val scummDir = File(installDir, "ScummVM").apply { mkdirs() }
        File(scummDir, "scummvm.exe").writeText("")
        val iniFile = File(scummDir, "scummvm.ini")
        iniFile.writeText(
            """
            [scummvm]
            updates_check=1
            lastselectedgame=monkey1
            [monkey1]
            path=C:\games\monkey1
            """.trimIndent(),
        )
        val container = createContainer("c2")
        val fix = ScummGameFix(gameSource = GameSource.GOG, gameId = "1454587428")

        val result = fix.apply(
            context = context,
            gameId = "1454587428",
            installPath = installDir.absolutePath,
            installPathWindows = "A:\\Games\\BrokenSword",
            container = container,
        )

        assertTrue(result)
        assertEquals("-c \"A:\\Games\\BrokenSword\\ScummVM\\scummvm.ini\" monkey1", container.execArgs)
        assertTrue(iniFile.readText().contains("updates_check=0"))
    }

    private fun createContainer(id: String): Container {
        val rootDir = File(baseDir, id).apply { mkdirs() }
        return Container(id).apply {
            this.rootDir = rootDir
            this.envVars = "WINEESYNC=1"
            this.execArgs = ""
        }
    }
}
