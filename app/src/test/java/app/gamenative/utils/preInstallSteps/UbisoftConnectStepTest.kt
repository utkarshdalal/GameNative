package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.mockk
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
class UbisoftConnectStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "ubisoft-step-test").toFile()
    }

    @Test
    fun appliesTo_returnsFalse_whenMarkerExists() {
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.UBISOFT_CONNECT_INSTALLED)
        assertFalse(UbisoftConnectStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun appliesTo_returnsTrue_whenMarkerMissing() {
        assertTrue(UbisoftConnectStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_returnsNull_whenInstallerMissing() {
        val cmd = UbisoftConnectStep.buildCommand(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        assertNull(cmd)
    }

    @Test
    fun buildCommand_usesInstallerUnderCommonRedist_whenPresent() {
        val installer = File(gameDir, "_CommonRedist/UbisoftConnect/UbisoftConnectInstaller.exe")
        installer.parentFile?.mkdirs()
        installer.writeText("x")

        val cmd = UbisoftConnectStep.buildCommand(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        assertEquals("A:\\_CommonRedist\\UbisoftConnect\\UbisoftConnectInstaller.exe /S", cmd)
    }
}
