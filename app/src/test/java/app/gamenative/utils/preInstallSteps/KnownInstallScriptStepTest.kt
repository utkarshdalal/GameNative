package app.gamenative.utils.preInstallSteps

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.PreInstallSteps
import app.gamenative.utils.KnownInstallScriptStep
import app.gamenative.utils.SteamInstallScriptRegistry
import com.winlator.container.Container
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.types.FileData
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
import java.util.EnumSet
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

    /**
     * The installed depot manifest, as the engine leaves it under the game directory, flagging
     * [relativePaths] as install scripts — which is how Steam knows which files to run.
     */
    private fun flagScripts(vararg relativePaths: String, depotId: Int = 3489701) {
        val manifest = DepotManifest().apply {
            depotID = depotId
            manifestGID = 1L
            files = ArrayList(
                relativePaths.map { path ->
                    FileData(
                        path.replace('/', '\\'),
                        ByteArray(20),
                        mutableListOf<ChunkData>(),
                        EnumSet.of(EDepotFileFlag.InstallScript),
                        0L,
                        ByteArray(20),
                        "",
                    )
                },
            )
        }
        val completed = File(gameDir, ".DepotDownloader/completed").apply { mkdirs() }
        manifest.saveToFile(File(completed, "${depotId}_1.manifest").absolutePath)
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
        assertEquals("quiet", step.command)
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

    /**
     * Nothing to run is marked the way a run is, so a title without a known step is asked once per
     * install rather than parsing its depot manifests on every launch. The marker is the one Verify
     * Files and a recreated prefix clear, so a later change is still seen.
     */
    @Test
    fun aGameWithNothingToRunIsMarkedAndNotAskedAgain() {
        File(gameDir, "installscript.vdf").writeText(vdf)
        File(gameDir, "tools/something_else.bat").apply {
            parentFile?.mkdirs()
            writeText("@echo off\r\n")
        }

        assertNull(build())
        assertTrue(MarkerUtils.hasMarker(gameDir.absolutePath, Marker.KNOWN_INSTALL_SCRIPT_RUN))
        assertFalse(KnownInstallScriptStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))

        // The marker stands in for the answer: even once the step is on disk, nothing is asked until
        // the marker is cleared.
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")
        assertFalse(KnownInstallScriptStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
        MarkerUtils.clearInstalledPrerequisiteMarkers(gameDir.absolutePath)
        assertTrue(KnownInstallScriptStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    /** A scheduled step writes no marker from here: the framework marks it when its session ends. */
    @Test
    fun aScheduledStepIsNotMarkedByTheProbe() {
        ship("SB/Binaries/Win64/install_pspc_sdk_runtime.bat")

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
        assertFalse(MarkerUtils.hasMarker(gameDir.absolutePath, Marker.KNOWN_INSTALL_SCRIPT_RUN))
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
        // `SB/Binaries/Win64/` next to the shipping exe and the installers, and the depot manifest
        // flagging it — the app's info names no script at all.
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/installscript.vdf").writeText(vdf)
        flagScripts("SB/Binaries/Win64/installscript.vdf")
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
        flagScripts("SB/Binaries/Win64/installscript.vdf")
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
     * Stellar Blade's real layout: `_CommonRedist` carries scripts of its own, and the depot
     * manifest flags them exactly like the game's. They are read, and none of their steps names a
     * program on the whitelist, so the PlayStation step is still the one that runs.
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
        flagScripts(
            "_CommonRedist/DirectX/Jun2010/installscript.vdf",
            "_CommonRedist/vcredist/2022/installscript.vdf",
            "SB/Binaries/Win64/installscript.vdf",
        )

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    /** Every flagged script is read, whatever its depth: Steam runs them all, so the order of discovery decides nothing. */
    @Test
    fun everyFlaggedScriptIsRead() {
        // The deep script names the batch; the shallow one names an unlisted program.
        File(gameDir, "Bin").mkdirs()
        File(gameDir, "Bin/installscript.vdf").writeText(
            """"InstallScript" { "Run Process" { "Other" { "process 1" "%INSTALLDIR%\\other.exe" } } }""",
        )
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")
        File(gameDir, "Deep/Er/Still").mkdirs()
        File(gameDir, "Deep/Er/Still/installscript.vdf").writeText(vdf)
        flagScripts("Bin/installscript.vdf", "Deep/Er/Still/installscript.vdf")

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    /**
     * A script the manifests do not flag is not Steam's to run, and not ours: the game directory is
     * never searched, so a stray copy beside the exe installs nothing without a manifest naming it.
     */
    @Test
    fun anUnflaggedScriptIsNotRead() {
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/installscript.vdf").writeText(vdf)
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")
        // A manifest is present, so this is not the manifest-less fallback; it flags something else.
        flagScripts("_CommonRedist/vcredist/2022/installscript.vdf")

        assertNull(build())
    }

    /** With no manifest record at all, the root `installscript.vdf` is still read, as Steam's default. */
    @Test
    fun withoutManifestsTheRootScriptIsStillRead() {
        ship("SB/Binaries/Win64/install_pspc_sdk_runtime.bat")

        assertEquals(
            listOf(File(gameDir, "installscript.vdf")),
            KnownInstallScriptStep.installScripts(gameDir, null),
        )
    }

    /** The app-info name is resolved as a path under the install root, like a manifest entry. */
    @Test
    fun theAppInfoNameIsResolvedAsAPath() {
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        val script = File(gameDir, "SB/Binaries/Win64/installscript.vdf").apply { writeText(vdf) }

        assertEquals(
            listOf(script),
            KnownInstallScriptStep.installScripts(gameDir, "SB\\Binaries\\Win64\\installscript.vdf"),
        )
    }

    /**
     * `command N` pairs with `process N`. A block with two processes gives each its own arguments;
     * handing both of them every command would run the installer with the other one's.
     */
    @Test
    fun aStepsArgumentsAreItsOwnIndexNotTheWholeBlock() {
        File(gameDir, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "Twins"
                    {
                        "process 1"		"%INSTALLDIR%\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat"
                        "command 1"		"quiet"
                        "process 2"		"%INSTALLDIR%\\tools\\other.bat"
                        "command 2"		"/S"
                    }
                }
            }
            """.trimIndent(),
        )
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" quiet",
            build(),
        )
    }

    /** A quoted path in the script's own arguments stays quoted, or it splits into two arguments. */
    @Test
    fun aQuotedArgumentKeepsItsQuotes() {
        File(gameDir, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "PSPC"
                    {
                        "process 1"		"%INSTALLDIR%\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat"
                        "command 1"		"\"C:\\Program Files\\PSPC\\runtime.msi\" /quiet"
                    }
                }
            }
            """.trimIndent(),
        )
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")

        assertEquals(
            "\"A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat\" " +
                "\"C:\\Program Files\\PSPC\\runtime.msi\" /quiet",
            build(),
        )
    }

    /** `Is64BitWindows 0` is a 32-bit-Windows step; Steam skips it here and so do we. */
    @Test
    fun aStepForA32BitWindowsIsNotRun() {
        File(gameDir, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "PSPC"
                    {
                        "process 1"		"%INSTALLDIR%\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat"
                        "command 1"		"quiet"
                        "Requirement_OS"
                        {
                            "Is64BitWindows"		"0"
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        File(gameDir, "SB/Binaries/Win64/install_pspc_sdk_runtime.bat").writeText("@echo off\r\n")

        assertNull(build())
    }

    /**
     * Steam's `installScript` may be a path relative to the install root rather than a bare name,
     * and a path compared against child names matches nothing. Resolved component by component,
     * case-insensitively, like the registry resolves a step's own path.
     */
    @Test
    fun aRelativeScriptPathIsResolved() {
        File(gameDir, "SB/Binaries/Win64").mkdirs()
        val script = File(gameDir, "SB/Binaries/Win64/installscript.vdf").apply { writeText(vdf) }

        assertEquals(script, KnownInstallScriptStep.resolveBelow(gameDir, "sb\\\\binaries\\\\win64\\\\installscript.vdf"))
        assertEquals(script, KnownInstallScriptStep.resolveBelow(gameDir, "SB/Binaries/Win64/installscript.vdf"))
        assertNull(KnownInstallScriptStep.resolveBelow(gameDir, "SB/../SB/Binaries/Win64/installscript.vdf"))
        assertNull(KnownInstallScriptStep.resolveBelow(gameDir, "SB/Binaries/Win64"))
    }
}
