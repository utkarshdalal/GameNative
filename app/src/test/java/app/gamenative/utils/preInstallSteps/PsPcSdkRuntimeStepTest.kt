package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
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
        // `PreInstallSteps.getPreInstallCommands` resolves the game directory from the drives,
        // and `markersStale` reads the variant.
        every { container.drives } returns "A:${gameDir.absolutePath}"
        every { container.containerVariant } returns Container.BIONIC
    }

    @After
    fun tearDown() {
        gameDir.deleteRecursively()
        prefixDir.deleteRecursively()
    }

    /**
     * The runtime as a Steam title ships it: the script and both packages in one directory, and
     * an install script whose Run Process step names the script — the lookup Steam launches use.
     */
    private fun runtime(dir: String, installScript: Boolean = true) {
        val target = File(gameDir, dir)
        target.mkdirs()
        File(target, PsPcSdkFiles.SCRIPT).writeText("@echo off\r\n")
        File(target, PsPcSdkFiles.MANAGER_MSI).writeText("m")
        File(target, PsPcSdkFiles.INSTALLER_MSI).writeText("i")
        if (installScript) installScript(dir)
    }

    private fun installScript(dir: String) {
        val guestDir = dir.replace('/', '\\')
        File(gameDir, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "PsPcSdkRuntime"
                    {
                        "process 1" "%INSTALLDIR%\\$guestDir\\${PsPcSdkFiles.SCRIPT}"
                        "NoCleanUp" "1"
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun build(step: PreInstallStep, gameSource: GameSource = GameSource.STEAM): String? = step.buildCommand(
        container = container,
        appId = "${gameSource.name}_3489700",
        gameSource = gameSource,
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
        assertFalse(File(gameDir, Marker.PSPC_SDK_RUNTIME_INSTALLED.fileName).exists())
    }

    @Test
    fun theRuntimeRunsAgain_whenTheMarkerIsSetButThePrefixHasNoRuntime() {
        runtime("SB/Binaries/Win64")
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.PSPC_SDK_RUNTIME_INSTALLED)
        // The prefix is there, and the runtime is not: a failed msiexec still gets marked done.
        File(prefixDir, ".wine/drive_c/ProgramData").mkdirs()

        assertTrue(PsPcSdkRuntimeStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun theRuntimeIsSkipped_whenThePrefixHasIt() {
        runtime("SB/Binaries/Win64")
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.PSPC_SDK_RUNTIME_INSTALLED)
        File(prefixDir, ".wine/drive_c/ProgramData/${PsPcSdkFiles.PREFIX_SDK_DIR}").mkdirs()

        assertFalse(PsPcSdkRuntimeStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun anInvisiblePrefixLeavesTheMarkerInCharge() {
        runtime("SB/Binaries/Win64")
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.PSPC_SDK_RUNTIME_INSTALLED)

        // No `.wine` to look into: the marker is the only evidence there is, so it is believed.
        assertFalse(PsPcSdkRuntimeStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
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
    fun aSteamTitleIsReadFromItsInstallScript_whereverTheScriptSits() {
        // The install script says where the vendor script is; no layout is assumed.
        runtime("Some/Other/Place/Deeper/Still")

        assertEquals(
            "msiexec /i A:\\Some\\Other\\Place\\Deeper\\Still\\${PsPcSdkFiles.INSTALLER_MSI} /quiet",
            build(PsPcSdkRuntimeStep),
        )
    }

    @Test
    fun aSteamTitleWithoutAnInstallScriptStepIsNotSearched() {
        // The files are there, but nothing names them: a Steam launch never walks the game
        // directory, so this title installs nothing.
        runtime("SB/Binaries/Win64", installScript = false)

        assertNull(build(PsPcSdkRuntimeStep))
    }

    @Test
    fun anInstallScriptNamingAnotherScriptIsNotEnough() {
        runtime("SB/Binaries/Win64", installScript = false)
        File(gameDir, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "Other"
                    {
                        "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\VC_redist.x64.exe"
                    }
                }
            }
            """.trimIndent(),
        )

        assertNull(build(PsPcSdkRuntimeStep))
    }

    @Test
    fun aNonSteamTitleIsFoundThreeDirectoriesDown() {
        // No install script to consult: <game>/a/b/c/script — the shape of
        // <game>/SB/Binaries/Win64/script — is found by the bounded walk.
        runtime("a/b/c", installScript = false)

        assertEquals(
            "msiexec /i A:\\a\\b\\c\\${PsPcSdkFiles.INSTALLER_MSI} /quiet",
            build(PsPcSdkRuntimeStep, GameSource.EPIC),
        )
    }

    @Test
    fun aScriptBeyondTheSearchBoundIsNotUsed_forANonSteamTitle() {
        // `MAX_DEPTH` counts entries from the game directory, its own children being 1: a script
        // four directories down is entry 5, one past the bound of 4.
        runtime("a/b/c/d", installScript = false)

        assertNull(build(PsPcSdkRuntimeStep, GameSource.EPIC))
    }

    @Test
    fun aMissingPackageSkipsOnlyItsOwnStep() {
        val dir = File(gameDir, "SB/Binaries/Win64")
        dir.mkdirs()
        File(dir, PsPcSdkFiles.SCRIPT).writeText("@echo off\r\n")
        File(dir, PsPcSdkFiles.INSTALLER_MSI).writeText("i")
        installScript("SB/Binaries/Win64")

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
    fun theSchedulerRunsTheManagerBeforeTheRuntime() {
        // The order the vendor script installs them in, asserted through the scheduler that
        // actually produces the chain rather than through the two singletons' own fields.
        runtime("SB/Binaries/Win64")

        val commands = PreInstallSteps.getPreInstallCommands(
            container = container,
            appId = "STEAM_3489700",
            gameSource = GameSource.STEAM,
            screenInfo = "1280x720",
            containerVariantChanged = false,
        )

        assertEquals(
            listOf(Marker.PSPC_SDK_MANAGER_INSTALLED, Marker.PSPC_SDK_RUNTIME_INSTALLED),
            commands.map { it.marker },
        )
        assertTrue(commands.first().executable.endsWith(PsPcSdkFiles.MANAGER_MSI + " /quiet"))
        assertTrue(commands.last().executable.endsWith(PsPcSdkFiles.INSTALLER_MSI + " /quiet"))
        // Both opt out of the shell, so neither leaves a console window on the virtual desktop.
        assertTrue(commands.none { it.executable.contains("cmd /c") })
    }
}
