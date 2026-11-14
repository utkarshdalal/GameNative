package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.every
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
    fun appliesTo_reinstalls_whenMarkerExistsButLauncherMissing() {
        val rootDir = createTempDirectory(prefix = "ubisoft-step-root").toFile()
        File(rootDir, ".wine").mkdirs()
        every { container.rootDir } returns rootDir
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.UBISOFT_CONNECT_INSTALLED)

        assertTrue(UbisoftConnectStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
        assertFalse(MarkerUtils.hasMarker(gameDir.absolutePath, Marker.UBISOFT_CONNECT_INSTALLED))
        assertFalse(UbisoftConnectStep.isDone(container))
    }

    @Test
    fun appliesTo_returnsFalse_whenMarkerExistsAndLauncherInstalled() {
        val rootDir = createTempDirectory(prefix = "ubisoft-step-root").toFile()
        val prefix = File(rootDir, ".wine")
        val launcherDir = File(prefix, "drive_c/Program Files (x86)/Ubisoft/Ubisoft Game Launcher")
        launcherDir.mkdirs()
        File(launcherDir, "upc.exe").writeText("x")
        File(prefix, "system.reg").writeText(
            "WINE REGISTRY Version 2\n\n" +
                "[Software\\\\Wow6432Node\\\\Ubisoft\\\\Launcher] 1730000000\n" +
                "#time=1db0000000000000\n" +
                "\"InstallDir\"=\"C:\\\\Program Files (x86)\\\\Ubisoft\\\\Ubisoft Game Launcher\\\\\"\n",
        )
        every { container.rootDir } returns rootDir
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.UBISOFT_CONNECT_INSTALLED)

        assertFalse(UbisoftConnectStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
        assertTrue(UbisoftConnectStep.isDone(container))
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
