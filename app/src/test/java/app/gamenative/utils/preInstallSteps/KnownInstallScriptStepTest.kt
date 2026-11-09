package app.gamenative.utils.preInstallSteps

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.PreInstallSteps
import app.gamenative.utils.SteamInstallScriptRegistry
import app.gamenative.utils.KnownInstallScriptStep
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
class KnownInstallScriptStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File

    /** Stellar Blade's real script, with a second, unlisted step for the whitelist to skip. */
    private val vdf = """
        "InstallScript"
        {
            "Run Process"
            {
                "UE4PrereqSetup_x64"
                {
                    "process 1"		"%INSTALLDIR%\\Engine\\Extras\\Redist\\en-us\\UE4PrereqSetup_x64.exe"
                    "command 1"		"/q"
                }
                "PlayStationPcSdk_3.5"
                {
                    "process 1"		"%INSTALLDIR%\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat"
                    "command 1"		"quiet"
                }
                "Not On The List"
                {
                    "process 1"		"%INSTALLDIR%\\tools\\something_else.bat"
                    "command 1"		"/S"
                }
            }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        gameDir = createTempDirectory(prefix = "steam-script-step").toFile()
        container = mockk(relaxed = true)
        every { container.drives } returns "A:${gameDir.absolutePath}"
        every { container.containerVariant } returns Container.BIONIC
    }

    @After
    fun tearDown() {
        PreInstallSteps.setStepsProviderForTests(null)
        gameDir.deleteRecursively()
    }

    private fun ship(vararg names: String) {
        File(gameDir, "installscript.vdf").writeText(vdf)
        for (name in names) {
            File(gameDir, name).apply {
                parentFile?.mkdirs()
                writeText("@echo off\r\n")
            }
        }
    }

    private fun build(): String? = KnownInstallScriptStep.buildCommand(
        container = container,
        appId = "STEAM_3489700",
        gameSource = GameSource.STEAM,
        gameDir = gameDir,
        gameDirPath = gameDir.absolutePath,
    )

    @Test
    fun theWhitelistedScriptIsTheCommand() {
        ship("SB/Binaries/Win64/install_pspc_sdk_runtime.bat", "tools/something_else.bat")

        // Both come from the install script: the path from its `process 1`, and `quiet` from its
        // own `command 1` — the whitelist only says this file may run.
        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    @Test
    fun theStepsOwnCommandIsWhatCarriesTheArguments() {
        ship("SB/Binaries/Win64/install_pspc_sdk_runtime.bat")

        val step = SteamInstallScriptRegistry.runProcessSteps(gameDir, null)
            .single { it.file?.name == "install_pspc_sdk_runtime.bat" }

        // The script's own `command 1`, not ours: nothing in the app hardcodes it.
        assertEquals(listOf("quiet"), step.commands)
    }

    @Test
    fun aScriptTheWhitelistDoesNotNameIsNotRun() {
        // Only the other step is on disk, so nothing is scheduled.
        File(gameDir, "installscript.vdf").writeText(vdf)
        File(gameDir, "tools/something_else.bat").apply {
            parentFile?.mkdirs()
            writeText("@echo off\r\n")
        }

        assertNull(build())
    }

    @Test
    fun aScriptNamedButAbsentIsNotRun() {
        // Named by the script, never installed: nothing to execute.
        File(gameDir, "installscript.vdf").writeText(vdf)

        assertNull(build())
    }

    @Test
    fun aGameWithNoInstallScriptSchedulesNothing() {
        assertNull(build())
    }

    @Test
    fun itRunsWithoutAShell() {
        // `viaShell = false`: the command is the guest executable, not a `cmd /c` line, so no
        // console window of ours and no `taskkill`/`wineserver -k` chain.
        assertFalse(KnownInstallScriptStep.viaShell)
    }

    @Test
    fun itIsSteamOnlyAndFollowsTheMarker() {
        assertFalse(KnownInstallScriptStep.appliesTo(container, GameSource.GOG, gameDir.absolutePath))
        assertTrue(KnownInstallScriptStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))

        MarkerUtils.addMarker(gameDir.absolutePath, Marker.KNOWN_INSTALL_SCRIPT_RUN)
        assertFalse(KnownInstallScriptStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun theSchedulerProducesOneShellFreeCommand() {
        ship("SB/Binaries/Win64/install_pspc_sdk_runtime.bat")

        val commands = PreInstallSteps.getPreInstallCommands(
            container = container,
            appId = "STEAM_3489700",
            gameSource = GameSource.STEAM,
            screenInfo = "1280x720",
            containerVariantChanged = false,
        )

        assertEquals(listOf(Marker.KNOWN_INSTALL_SCRIPT_RUN), commands.map { it.marker })
        assertFalse(commands.single().viaShell)
        assertEquals(
            "wine explorer /desktop=shell,1280x720 " +
                "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            commands.single().executable,
        )
    }

    @Test
    fun aScriptBesideTheShippingExeIsFound() {
        // Stellar Blade's actual layout: no `installscript.vdf` at the install root, one in
        // `SB/Binaries/Win64/` next to the shipping exe and the installers.
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/installscript.vdf").writeText(vdf)
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")
        File(gameDir, "SB/Binaries/Win64/SB-Win64-Shipping.exe").writeText("x")

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    /**
     * Stellar Blade's script, byte for byte (signature block trimmed): tabs, double backslashes and
     * all. The fixture below is the readable version; this is the one that proves the parser and the
     * resolver handle what the depot actually ships.
     */
    @Test
    fun theRealStellarBladeScriptResolvesToTheWhitelistedStep() {
        val real = "\"InstallScript\"\n" +
            "{\n" +
            "\t\"Run Process\"\n" +
            "\t{\n" +
            "\t\t\"UE4PrereqSetup_x64\"\n" +
            "\t\t{\n" +
            "\t\t\t\"process 1\"\t\t\"%INSTALLDIR%\\\\Engine\\\\Extras\\\\Redist\\\\en-us\\\\UE4PrereqSetup_x64.exe\"\n" +
            "\t\t\t\"command 1\"\t\t\"/q\"\n" +
            "\t\t}\n" +
            "\t\t\"PlayStationPcSdk_3.5\"\n" +
            "\t\t{\n" +
            "\t\t\t\"process 1\"\t\t\"%INSTALLDIR%\\\\SB\\\\Binaries\\\\Win64\\\\install_pspc_sdk_runtime.bat\"\n" +
            "\t\t\t\"command 1\"\t\t\"quiet\"\n" +
            "\t\t}\n" +
            "\t}\n" +
            "}\n"
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/installscript.vdf").writeText(real)
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    @Test
    fun theWhitelistResolvesThePathFromTheScript() {
        // The step the vdf names, with its file resolved to a host path, and only that one: the
        // other two steps name programs the whitelist does not carry.
        ship("SB/Binaries/Win64/install_pspc_sdk_runtime.bat", "tools/something_else.bat")

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    /**
     * Stellar Blade's real layout: `_CommonRedist` is the first directory in listing order and
     * carries a script of its own, so a search that stops at the first `installscript.vdf` finds a
     * redistributable's and the PlayStation step is never seen.
     */
    @Test
    fun aRedistScriptIsNotMistakenForTheGamesOwn() {
        File(gameDir, "_CommonRedist/DirectX/Jun2010").mkdirs()
        File(gameDir, "_CommonRedist/vcredist/2022").mkdirs()
        File(gameDir, "_CommonRedist/DirectX/Jun2010/installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "DXSETUP"
                    {
                        "process 1"		"%INSTALLDIR%\\_CommonRedist\\DirectX\\Jun2010\\DXSETUP.exe"
                        "command 1"		"/silent"
                    }
                }
            }
            """.trimIndent(),
        )
        File(gameDir, "_CommonRedist/vcredist/2022/installscript.vdf").writeText(
            """"InstallScript" { "Run Process" { "VC" { "process 1" "%INSTALLDIR%\\vc.exe" } } }""",
        )
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")
        File(gameDir, "SB/Binaries/Win64/installscript.vdf").writeText(vdf)

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    /** Two candidates and no redistributables: the game's script is the shallower one. */
    @Test
    fun theShallowestScriptWins() {
        // The shallow script names the batch; the deeper one names an unlisted program. Were the
        // deeper script the one read, the result would be null.
        File(gameDir, "Bin").mkdirs()
        File(gameDir, "Bin/installscript.vdf").writeText(vdf)
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")
        File(gameDir, "Deep/Er/Still").mkdirs()
        File(gameDir, "Deep/Er/Still/installscript.vdf").writeText(
            """"InstallScript" { "Run Process" { "Other" { "process 1" "%INSTALLDIR%\\other.exe" } } }""",
        )

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    /** A title whose only script is a redistributable's has no script of its own. */
    @Test
    fun aRedistOnlyTreeHasNoScriptOfItsOwn() {
        File(gameDir, "_CommonRedist/DirectX/Jun2010").mkdirs()
        File(gameDir, "_CommonRedist/DirectX/Jun2010/install_pspc_sdk_runtime.bat")
            .writeText("@echo off\r\n")
        File(gameDir, "_CommonRedist/DirectX/Jun2010/installscript.vdf").writeText(vdf)

        // The redist script is still read for marking, but it is not the game's script, so its
        // steps are not this step's to run.
        assertNull(build())
    }
}
