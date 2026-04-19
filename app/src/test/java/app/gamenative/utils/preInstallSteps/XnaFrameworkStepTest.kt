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
class XnaFrameworkStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "xna-step-test").toFile()
    }

    @Test
    fun appliesTo_respectsMarkerPresence() {
        assertTrue(XnaFrameworkStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.XNA_INSTALLED)
        assertFalse(XnaFrameworkStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_detectsXnaMsiInstallers() {
        val msi = File(gameDir, "_CommonRedist/xnafx/xnafx40.msi")
        msi.parentFile?.mkdirs()
        msi.writeText("x")

        val cmd = XnaFrameworkStep.buildCommand(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        val expected = "msiexec /i A:\\_CommonRedist\\xnafx\\xnafx40.msi /quiet /norestart"
        assertEquals(expected, checkNotNull(cmd))
    }
}
