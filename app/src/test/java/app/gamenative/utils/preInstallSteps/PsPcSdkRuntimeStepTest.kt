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
class PsPcSdkRuntimeStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File
    private lateinit var prefixDir: File

    @Before
    fun setUp() {
        gameDir = createTempDirectory(prefix = "pspc-step-test").toFile()
        prefixDir = createTempDirectory(prefix = "pspc-prefix-test").toFile()
        container = mockk(relaxed = true)
        every { container.rootDir } returns prefixDir
    }

    /** The runtime as a title ships it: the script and both packages in one directory. */
    private fun runtime(dir: String) {
        val target = File(gameDir, dir)
        target.mkdirs()
        File(target, PsPcSdkFiles.SCRIPT).writeText("@echo off\r\n")
        File(target, PsPcSdkFiles.MANAGER_MSI).writeText("m")
        File(target, PsPcSdkFiles.INSTALLER_MSI).writeText("i")
    }

    private fun build(step: PreInstallStep): String? = step.buildCommand(
        container = container,
        appId = "STEAM_3489700",
        gameSource = GameSource.STEAM,
        gameDir = gameDir,
        gameDirPath = gameDir.absolutePath,
    )

    @Test
    fun appliesTo_followsTheMarkerTheFrameworkClearsWithThePrefix() {
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.PSPC_SDK_RUNTIME_INSTALLED)
        assertFalse(PsPcSdkRuntimeStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))

        // A recreated prefix is `PreInstallSteps.markersStale`'s business: it clears every
        // game-dir marker, so the step only has to follow the marker.
        MarkerUtils.removeMarker(gameDir.absolutePath, Marker.PSPC_SDK_RUNTIME_INSTALLED)
        assertTrue(PsPcSdkRuntimeStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_writesNothing() {
        runtime("SB/Binaries/Win64")

        // It also runs for the `hasPendingSteps` probe, which launches nothing — a stamp
        // written here would claim an install that a cancelled launch never did.
        build(PsPcSdkManagerStep)
        build(PsPcSdkRuntimeStep)

        assertFalse(File(prefixDir, ".wine").exists())
        assertFalse(File(gameDir, Marker.PSPC_SDK_MANAGER_INSTALLED.fileName).exists())
    }

    @Test
    fun bothStepsDoNothing_whenTheGameShipsNoRuntime() {
        assertNull(build(PsPcSdkManagerStep))
        assertNull(build(PsPcSdkRuntimeStep))
    }

    @Test
    fun eachStepInstallsItsOwnPackage() {
        runtime("SB/Binaries/Win64")

        assertEquals(
            "msiexec /i A:\\SB\\Binaries\\Win64\\${PsPcSdkFiles.MANAGER_MSI} /quiet",
            build(PsPcSdkManagerStep),
        )
        assertEquals(
            "msiexec /i A:\\SB\\Binaries\\Win64\\${PsPcSdkFiles.INSTALLER_MSI} /quiet",
            build(PsPcSdkRuntimeStep),
        )
    }

    @Test
    fun theRuntimeIsFoundThreeDirectoriesDown() {
        // <game>/a/b/c/script — the shape of <game>/SB/Binaries/Win64/script.
        runtime("a/b/c")

        assertEquals(
            "msiexec /i A:\\a\\b\\c\\${PsPcSdkFiles.INSTALLER_MSI} /quiet",
            build(PsPcSdkRuntimeStep),
        )
    }

    @Test
    fun theRuntimeIsNotSearchedForFourDirectoriesDown() {
        runtime("a/b/c/d")

        assertNull(build(PsPcSdkRuntimeStep))
    }

    @Test
    fun aMissingPackageSkipsOnlyItsOwnStep() {
        val dir = File(gameDir, "SB/Binaries/Win64")
        dir.mkdirs()
        File(dir, PsPcSdkFiles.SCRIPT).writeText("@echo off\r\n")
        File(dir, PsPcSdkFiles.INSTALLER_MSI).writeText("i")

        assertNull(build(PsPcSdkManagerStep))
        assertEquals(
            "msiexec /i A:\\SB\\Binaries\\Win64\\${PsPcSdkFiles.INSTALLER_MSI} /quiet",
            build(PsPcSdkRuntimeStep),
        )
    }

    @Test
    fun aPathThatNeedsQuotingIsRefused() {
        runtime("SB with space/Binaries/Win64")

        // The launcher splits the command line it is handed, so a space cannot be escaped.
        assertNull(build(PsPcSdkRuntimeStep))
    }

    @Test
    fun bothStepsRunWithoutAShell() {
        runtime("SB/Binaries/Win64")

        // `msiexec` is a windowed program; through `cmd /c` it costs a console window for the
        // length of the install, which is why these two opt out of the shell.
        assertFalse(PsPcSdkManagerStep.viaShell)
        assertFalse(PsPcSdkRuntimeStep.viaShell)
    }

    @Test
    fun theManagerInstallsBeforeTheRuntime() {
        // The vendor script's order. `PreInstallSteps` runs the list in order, so this is the
        // registration the order depends on.
        assertEquals(
            listOf(Marker.PSPC_SDK_MANAGER_INSTALLED, Marker.PSPC_SDK_RUNTIME_INSTALLED),
            listOf(PsPcSdkManagerStep.marker, PsPcSdkRuntimeStep.marker),
        )
    }
}
