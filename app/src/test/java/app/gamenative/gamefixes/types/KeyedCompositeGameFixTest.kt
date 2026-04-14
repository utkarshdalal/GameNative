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
class KeyedCompositeGameFixTest {
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("keyed-composite-fix-tests").toFile()
        baseDir.deleteOnExit()
    }

    @Test
    fun keyedComposite_apply_runsAllFixesInOrder_whenAllFixesSucceed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val applied = mutableListOf<String>()
        val container = createContainer("c1")

        val fix = KeyedCompositeGameFix(
            gameSource = GameSource.STEAM,
            gameId = "2868840",
            fixes = listOf(
                RecordingFix("first", true, applied),
                RecordingFix("second", true, applied),
                RecordingFix("third", true, applied),
            ),
        )

        val result = fix.apply(
            context = context,
            gameId = "2868840",
            installPath = "",
            installPathWindows = "",
            container = container,
        )

        assertTrue(result)
        assertEquals(listOf("first", "second", "third"), applied)
    }

    @Test
    fun keyedComposite_apply_returnsFalseButStillRunsRemainingFixes_whenAnyFixFails() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val applied = mutableListOf<String>()
        val container = createContainer("c2")

        val fix = KeyedCompositeGameFix(
            gameSource = GameSource.STEAM,
            gameId = "2868840",
            fixes = listOf(
                RecordingFix("first", true, applied),
                RecordingFix("second", false, applied),
                RecordingFix("third", true, applied),
            ),
        )

        val result = fix.apply(
            context = context,
            gameId = "2868840",
            installPath = "",
            installPathWindows = "",
            container = container,
        )

        assertFalse(result)
        assertEquals(listOf("first", "second", "third"), applied)
    }

    @Test
    fun keyedComposite_keyedProperties_matchConstructorValues() {
        val fix = KeyedCompositeGameFix(
            gameSource = GameSource.GOG,
            gameId = "1635627436",
            fixes = emptyList(),
        )

        assertEquals(GameSource.GOG, fix.gameSource)
        assertEquals("1635627436", fix.gameId)
    }

    private fun createContainer(id: String): Container {
        val rootDir = File(baseDir, id).apply { mkdirs() }
        return Container(id).apply {
            this.rootDir = rootDir
            this.envVars = "WINEESYNC=1"
        }
    }

    private class RecordingFix(
        private val label: String,
        private val result: Boolean,
        private val applied: MutableList<String>,
    ) : GameFix {
        override fun apply(
            context: android.content.Context,
            gameId: String,
            installPath: String,
            installPathWindows: String,
            container: Container,
        ): Boolean {
            applied += label
            return result
        }
    }
}
