package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.every
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
class VcRedistStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File
    private lateinit var containerRoot: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "vcredist-step-test").toFile()
        containerRoot = createTempDirectory(prefix = "vcredist-container-test").toFile()
        every { container.rootDir } returns containerRoot
    }

    private fun seedInstaller(year: String = "MSVC2017", filename: String = "VC_redist.x86.exe"): File {
        val installer = File(gameDir, "_CommonRedist/$year/$filename")
        installer.parentFile?.mkdirs()
        installer.writeText("dummy")
        return installer
    }

    @Test
    fun appliesTo_returnsTrue_whenInstallerPresentAndNoMarker() {
        seedInstaller()
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertTrue(applies)
    }

    @Test
    fun appliesTo_returnsFalse_whenNoInstallersBundled() {
        // Game ships no vcredist installers — nothing to do, step does not apply.
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertFalse(applies)
    }

    @Test
    fun appliesTo_returnsFalse_whenGameDirMarkerExists() {
        seedInstaller()
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.VCREDIST_INSTALLED)
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertFalse(applies)
    }

    @Test
    fun appliesTo_returnsFalse_whenAllRequiredYearsAlreadyInstalledInContainer() {
        seedInstaller(year = "MSVC2017")
        File(containerRoot, ".vcredist_installed_2017-x86").createNewFile()
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertFalse(applies)
    }

    @Test
    fun appliesTo_returnsTrue_whenContainerHasDifferentYearMarker() {
        // Previous game installed 2015; current game bundles 2019. The
        // container-level 2015 marker must not short-circuit the 2019 install.
        seedInstaller(year = "MSVC2019")
        File(containerRoot, ".vcredist_installed_2015-x86").createNewFile()
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertTrue(applies)
    }

    @Test
    fun appliesTo_x64MarkerDoesNotSkipX86Install() {
        // Previous game installed 2017 x64; current game bundles 2017 x86.
        // The two arches are independent — x64 marker must not short-circuit
        // the x86 install.
        seedInstaller(year = "MSVC2017", filename = "VC_redist.x86.exe")
        File(containerRoot, ".vcredist_installed_2017-x64").createNewFile()
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertTrue(applies)
    }

    @Test
    fun appliesTo_x86MarkerDoesNotSkipX64Install() {
        seedInstaller(year = "MSVC2017_x64", filename = "VC_redist.x64.exe")
        File(containerRoot, ".vcredist_installed_2017-x86").createNewFile()
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertTrue(applies)
    }

    @Test
    fun appliesTo_ignoresGameDirMarker_whenContainerMarkersIndicateMissingYears() {
        // Game-dir VCREDIST_INSTALLED marker exists (e.g. from a prior run)
        // but the container is missing the year+arch this game requires.
        // The coarse game-dir marker must not hide the missing runtime.
        seedInstaller(year = "MSVC2019", filename = "VC_redist.x86.exe")
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.VCREDIST_INSTALLED)
        File(containerRoot, ".vcredist_installed_2015-x86").createNewFile()
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertTrue(applies)
    }

    @Test
    fun appliesTo_migratesLegacyContainerMarker_andSkipsForCurrentGame() {
        // Pre-fix containers may have a coarse `.vcredist_installed` file at
        // the prefix root. We migrate it to per-year sidecars covering what
        // the current game bundles, then skip the install.
        seedInstaller(year = "MSVC2017")
        File(containerRoot, Marker.VCREDIST_INSTALLED.fileName).createNewFile()
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertFalse(applies)
        assertTrue(File(containerRoot, ".vcredist_installed_2017-x86").isFile)
        assertFalse(File(containerRoot, Marker.VCREDIST_INSTALLED.fileName).exists())
    }

    @Test
    fun appliesTo_migratesArchlessYearMarker_toX86() {
        // The prior round-5 fix wrote year-only markers like `.vcredist_installed_2017`.
        // We migrate those to `.vcredist_installed_2017-x86` (conservative
        // assumption: the most common older redist is x86).
        seedInstaller(year = "MSVC2017", filename = "VC_redist.x86.exe")
        File(containerRoot, ".vcredist_installed_2017").createNewFile()
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertFalse(applies)
        assertTrue(File(containerRoot, ".vcredist_installed_2017-x86").isFile)
        assertFalse(File(containerRoot, ".vcredist_installed_2017").exists())
        // Migration must NOT also create the x64 marker — we don't know if it
        // was actually installed.
        assertFalse(File(containerRoot, ".vcredist_installed_2017-x64").exists())
    }

    @Test
    fun buildCommand_returnsCommand_forDetectedInstaller() {
        seedInstaller()

        val cmd = VcRedistStep.buildCommand(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        val expected = "A:\\_CommonRedist\\MSVC2017\\VC_redist.x86.exe /install /passive /norestart"
        assertEquals(expected, checkNotNull(cmd))
    }

    @Test
    fun buildCommand_skipsAlreadyInstalledYears() {
        seedInstaller(year = "MSVC2017", filename = "VC_redist.x86.exe")
        seedInstaller(year = "MSVC2019", filename = "VC_redist.x86.exe")
        File(containerRoot, ".vcredist_installed_2017-x86").createNewFile()

        val cmd = VcRedistStep.buildCommand(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        val expected = "A:\\_CommonRedist\\MSVC2019\\VC_redist.x86.exe /install /passive /norestart"
        assertEquals(expected, checkNotNull(cmd))
    }

    @Test
    fun buildCommand_x64MarkerDoesNotSkipX86Installer() {
        seedInstaller(year = "MSVC2017", filename = "VC_redist.x86.exe")
        seedInstaller(year = "MSVC2017_x64", filename = "VC_redist.x64.exe")
        // Only x64 has been installed; x86 must still run.
        File(containerRoot, ".vcredist_installed_2017-x64").createNewFile()

        val cmd = VcRedistStep.buildCommand(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        val expected = "A:\\_CommonRedist\\MSVC2017\\VC_redist.x86.exe /install /passive /norestart"
        assertEquals(expected, checkNotNull(cmd))
    }

    @Test
    fun recordInstalledVersions_writesPerYearAndArchMarkers() {
        seedInstaller(year = "MSVC2017", filename = "VC_redist.x86.exe")
        seedInstaller(year = "MSVC2017_x64", filename = "VC_redist.x64.exe")
        seedInstaller(year = "MSVC2022", filename = "VC_redist.x86.exe")

        VcRedistStep.recordInstalledVersions(container, gameDir)

        assertTrue(File(containerRoot, ".vcredist_installed_2017-x86").isFile)
        assertTrue(File(containerRoot, ".vcredist_installed_2017-x64").isFile)
        assertTrue(File(containerRoot, ".vcredist_installed_2022-x86").isFile)
    }
}
