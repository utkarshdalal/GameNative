package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.gog.GOGManager
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class GogScriptInterpreterStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "gog-script-step-test").toFile()
        mockkObject(GOGService.Companion)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun appliesTo_requiresGogAndGlibcAndMissingMarker() {
        every { container.containerVariant } returns Container.GLIBC
        assertTrue(GogScriptInterpreterStep.appliesTo(container, GameSource.GOG, gameDir.absolutePath))
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.GOG_SCRIPT_INSTALLED)
        assertFalse(GogScriptInterpreterStep.appliesTo(container, GameSource.GOG, gameDir.absolutePath))
        MarkerUtils.removeMarker(gameDir.absolutePath, Marker.GOG_SCRIPT_INSTALLED)

        assertFalse(GogScriptInterpreterStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))

        every { container.containerVariant } returns Container.BIONIC
        assertFalse(GogScriptInterpreterStep.appliesTo(container, GameSource.GOG, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_returnsNull_whenServiceUnavailable() {
        every { GOGService.getInstance() } returns null

        val cmd = GogScriptInterpreterStep.buildCommand(
            container = container,
            appId = "GOG_1",
            gameSource = GameSource.GOG,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        assertNull(cmd)
    }

    @Test
    fun buildCommand_joinsParts_whenProvidedByGogManager() {
        val service = mockk<GOGService>()
        val manager = mockk<GOGManager>()
        every { GOGService.getInstance() } returns service
        every { service.gogManager } returns manager
        every { manager.getScriptInterpreterPartsForLaunch("GOG_1") } returns listOf(
            "A:\\_CommonRedist\\ISI\\scriptinterpreter.exe /S",
            "A:\\game\\launcher.exe",
        )

        val cmd = GogScriptInterpreterStep.buildCommand(
            container = container,
            appId = "GOG_1",
            gameSource = GameSource.GOG,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        assertEquals(
            "A:\\_CommonRedist\\ISI\\scriptinterpreter.exe /S & A:\\game\\launcher.exe",
            cmd,
        )
    }
}
