package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.data.SteamApp
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class SteamInstallScriptStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File
    private lateinit var prefixDir: File

    @Before
    fun setUp() {
        gameDir = createTempDirectory(prefix = "steam-install-script-game").toFile()
        prefixDir = createTempDirectory(prefix = "steam-install-script-prefix").toFile()
        File(prefixDir, ".wine").mkdirs()
        container = mockk(relaxed = true)
        every { container.rootDir } returns prefixDir
    }

    @After
    fun tearDown() {
        SteamInstallScriptStep.resetForTests()
        gameDir.deleteRecursively()
        prefixDir.deleteRecursively()
    }

    @Test
    fun buildCommand_readsSteamMetadataAndExpandsRegistryValues() {
        File(gameDir, "InstallScript.vdf").writeText(
            """
            "InstallScript"
            {
                "Registry"
                {
                    "HKEY_LOCAL_MACHINE\\Software\\Electronic Arts\\SPORE"
                    {
                        "string"
                        {
                            "InstallLoc" "%INSTALLDIR%"
                            "DataDir" "%INSTALLDIR%\\Data"
                        }
                        "dword"
                        {
                            "Installed" "1"
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        SteamInstallScriptStep.appInfoProvider = { id ->
            SteamApp(id = id, installScript = "installscript.vdf")
        }

        val command = SteamInstallScriptStep.buildCommand(
            container,
            "STEAM_17390",
            GameSource.STEAM,
            gameDir,
            gameDir.absolutePath,
        )

        assertEquals(
            listOf(
                "reg add \"HKLM\\Software\\Electronic Arts\\SPORE\" /v \"InstallLoc\" /t REG_SZ /d \"A:\\\" /f /reg:32",
                "reg add \"HKLM\\Software\\Electronic Arts\\SPORE\" /v \"DataDir\" /t REG_SZ /d \"A:\\Data\" /f /reg:32",
                "reg add \"HKLM\\Software\\Electronic Arts\\SPORE\" /v \"Installed\" /t REG_DWORD /d \"1\" /f /reg:32",
            ).joinToString(" & "),
            command,
        )
    }

    @Test
    fun buildCommand_returnsNullForNonSteamContainerId() {
        SteamInstallScriptStep.appInfoProvider = { SteamApp(id = it, installScript = "installscript.vdf") }

        assertNull(
            SteamInstallScriptStep.buildCommand(
                container,
                "GOG_17390",
                GameSource.GOG,
                gameDir,
                gameDir.absolutePath,
            ),
        )
    }
}
