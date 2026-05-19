package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IniFileFixTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = Files.createTempDirectory("ini_file_fix_test_").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun apply_updatesExistingValuesAndAppendsMissingKeys() {
        val iniFile = File(tempDir, "Settings.ini")
        iniFile.writeText(
            """
            Music=1
            SoundFX=0
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
        val container = RecordingContainer("STEAM_752580")
        val fix = IniFileFix(
            relativePath = "Settings.ini",
            defaultValues = linkedMapOf(
                "Music" to "0",
                "SoundFX" to "1",
                "Speech" to "1",
            ),
        )

        val changed = fix.apply(
            context = context,
            gameId = "752580",
            installPath = tempDir.absolutePath,
            installPathWindows = "A:\\",
            container = container,
        )

        assertTrue(changed)
        assertEquals(
            """
            Music=0
            SoundFX=1
            Speech=1
            """.trimIndent() + "\n",
            normalizeLineEndings(iniFile.readText(StandardCharsets.UTF_8)),
        )
        assertEquals(0, container.saveCalls)
    }

    @Test
    fun steamFix752580_ignoresLegacyMigrationMarkersAndStillReappliesIniValues() {
        val iniFile = File(tempDir, "Settings.ini")
        iniFile.writeText(
            """
            Music=1
            SoundFX=0
            Speech=0
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
        val container = RecordingContainer("STEAM_752580")
        container.putExtra("imperivm_audio_settings_v1", "1")

        val changed = STEAM_Fix_752580.apply(
            context = context,
            gameId = "752580",
            installPath = tempDir.absolutePath,
            installPathWindows = "A:\\",
            container = container,
        )

        assertTrue(changed)
        assertEquals(
            """
            Music=0
            SoundFX=1
            Speech=1
            """.trimIndent() + "\n",
            normalizeLineEndings(iniFile.readText(StandardCharsets.UTF_8)),
        )
        assertEquals(0, container.saveCalls)
    }

    private fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n")

    private class RecordingContainer(id: String) : Container(id) {
        var saveCalls = 0

        override fun saveData() {
            saveCalls += 1
        }
    }
}
