package app.gamenative.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SteamInstallScriptRunProcessTest {
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        gameDir = createTempDirectory(prefix = "run-process-test").toFile()
    }

    @After
    fun tearDown() {
        gameDir.deleteRecursively()
    }

    private fun touch(relativePath: String) {
        val f = File(gameDir, relativePath)
        f.parentFile?.mkdirs()
        f.writeText("x")
    }

    @Test
    fun parse_expandsInstallDirAndPairsProcessWithCommand() {
        touch("VCRedist/vcredist_x86.exe")
        touch("DirectX/DXSetup.exe")

        val entries = SteamInstallScriptRunProcess.parse(
            """
            "installscript"
            {
                "run process"
                {
                    "vc"
                    {
                        "process 1"		"%INSTALLDIR%\\VCRedist\\vcredist_x86.exe"
                        "command 1"		"/q:a"
                        "description"		"Visual C++ 2008 SP1 Redistributable Package (x86)"
                        "nocleanup"		"1"
                        "ignoreexitcode"		"1"
                    }
                    "dx"
                    {
                        "process 1"		"%INSTALLDIR%\\DirectX\\DXSetup.exe"
                        "command 1"		"/silent"
                    }
                }
            }
            """.trimIndent(),
            gameDir,
        )

        assertEquals(listOf("A:\\VCRedist\\vcredist_x86.exe", "A:\\DirectX\\DXSetup.exe"), entries.map { it.winPath })
        assertEquals(listOf("/q:a", "/silent"), entries.map { it.args })
        assertEquals("A:\\VCRedist\\vcredist_x86.exe /q:a", entries[0].commandLine)
        assertEquals("vcredist_x86.exe", entries[0].exeName)
    }

    @Test
    fun parse_skipsMissingFilesAndPathsOutsideInstallDir() {
        val entries = SteamInstallScriptRunProcess.parse(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "missing" { "process 1" "%INSTALLDIR%\\nope.exe" "command 1" "/q" }
                    "outside" { "process 1" "%WINDIR%\\system32\\cmd.exe" "command 1" "/c dir" }
                }
            }
            """.trimIndent(),
            gameDir,
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun entries_collectsRootScriptAndEveryCommonRedistScript() {
        touch("_CommonRedist/vcredist/2019/VC_redist.x64.exe")
        touch("_CommonRedist/DirectX/Jun2010/DXSETUP.exe")
        File(gameDir, "_CommonRedist/vcredist/2019/installscript.vdf").writeText(
            """
            "InstallScript" { "Run Process" { "vc" {
                "HasRunKey" "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2019\\x64"
                "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2019\\VC_redist.x64.exe"
                "command 1" "/install /quiet /norestart"
            } } }
            """.trimIndent(),
        )
        File(gameDir, "_CommonRedist/DirectX/Jun2010/installscript.vdf").writeText(
            """
            "installscript" { "Run Process" { "dxsetup" {
                "process 1" "%INSTALLDIR%\\_CommonRedist\\DirectX\\Jun2010\\DXSETUP.exe"
                "command 1" "/silent"
            } } }
            """.trimIndent(),
        )

        val entries = SteamInstallScriptRunProcess.entries(gameDir)

        assertEquals(
            listOf(
                "A:\\_CommonRedist\\DirectX\\Jun2010\\DXSETUP.exe /silent",
                "A:\\_CommonRedist\\vcredist\\2019\\VC_redist.x64.exe /install /quiet /norestart",
            ),
            entries.map { it.commandLine },
        )
    }
}
