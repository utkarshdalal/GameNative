package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class OpenALStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "openal-step-test").toFile()
    }

    @Test
    fun appliesTo_respectsMarkerPresence() {
        assertTrue(OpenALStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.OPENAL_INSTALLED)
        assertFalse(OpenALStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_detectsOpenAlExecutables() {
        val exe = File(gameDir, "_CommonRedist/OpenAL/oalinst.exe")
        exe.parentFile?.mkdirs()
        exe.writeText("x")

        val cmd = OpenALStep.buildCommand(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        val expected = "A:\\_CommonRedist\\OpenAL\\oalinst.exe /s"
        assertEquals(expected, checkNotNull(cmd))
    }
}
