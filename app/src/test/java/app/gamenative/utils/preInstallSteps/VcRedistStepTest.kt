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
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "vcredist-step-test").toFile()
        rootDir = createTempDirectory(prefix = "vcredist-step-root").toFile()
        every { container.rootDir } returns rootDir
    }

    private fun build(): String? = VcRedistStep.buildCommand(
        container = container,
        appId = "STEAM_1",
        gameSource = GameSource.STEAM,
        gameDir = gameDir,
        gameDirPath = gameDir.absolutePath,
    )

    private fun addInstaller(relativePath: String) {
        val installer = File(gameDir, relativePath)
        installer.parentFile?.mkdirs()
        installer.writeText("dummy")
    }

    private fun systemReg(): String = File(rootDir, ".wine/system.reg").readText()

    @Test
    fun appliesTo_ignoresGameDirMarker() {
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.VCREDIST_INSTALLED)
        assertTrue(VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_returnsNull_whenHasRunKeyAlreadyInPrefix() {
        addInstaller("_CommonRedist/MSVC2017/VC_redist.x86.exe")
        assertEquals("A:\\_CommonRedist\\MSVC2017\\VC_redist.x86.exe /install /passive /norestart", build())

        VcRedistStep.onCompleted(container, gameDir)

        assertTrue(systemReg().contains("[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\GameNative\\\\_CommonRedist\\\\MSVC2017\\\\VC_redist.x86.exe]"))
        assertEquals(null, build())
    }

    @Test
    fun onCompleted_writesScriptHasRunKeyUnderWow6432Node() {
        addInstaller("_CommonRedist/vcredist/2022/VC_redist.x64.exe")
        File(gameDir, "_CommonRedist/vcredist/2022/installscript.vdf").writeText(
            """
            "InstallScript" { "Run Process" { "vc" {
                "HasRunKey" "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2022\\x64"
                "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
                "command 1" "/install /quiet /norestart"
            } } }
            """.trimIndent(),
        )
        checkNotNull(build())

        VcRedistStep.onCompleted(container, gameDir)

        assertTrue(systemReg().contains("[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022\\\\x64]"))
        assertEquals(null, build())
    }

    @Test
    fun buildCommand_runsOnlyEntriesWithoutHasRunKey() {
        addInstaller("_CommonRedist/vcredist/2019/VC_redist.x64.exe")
        addInstaller("_CommonRedist/vcredist/2022/VC_redist.x64.exe")
        checkNotNull(build())
        VcRedistStep.onCompleted(container, gameDir)
        File(gameDir, "_CommonRedist/vcredist/2022/VC_redist.x64.exe").delete()
        addInstaller("_CommonRedist/vcredist/2013/vcredist_x64.exe")

        assertEquals("A:\\_CommonRedist\\vcredist\\2013\\vcredist_x64.exe /install /passive /norestart", build())
    }

    @Test
    fun buildCommand_returnsCommand_forDetectedInstaller() {
        val installer = File(gameDir, "_CommonRedist/MSVC2017/VC_redist.x86.exe")
        installer.parentFile?.mkdirs()
        installer.writeText("dummy")

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
    fun buildCommand_queues2022Installer() {
        addInstaller("_CommonRedist/vcredist/2022/VC_redist.x64.exe")

        val expected = "A:\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe /install /passive /norestart"
        assertEquals(expected, checkNotNull(build()))
    }

    @Test
    fun buildCommand_writesV140Overrides_forVcRedistInstaller() {
        addInstaller("_CommonRedist/vcredist/2022/VC_redist.x64.exe")

        build()

        val userReg = File(rootDir, ".wine/user.reg").readText()
        assertTrue(userReg.contains("\"ucrtbase\"=\"builtin\""))
        assertTrue(userReg.contains("\"msvcp140\"=\"native,builtin\""))
        assertTrue(userReg.contains("\"vcruntime140\"=\"native,builtin\""))
    }

    @Test
    fun buildCommand_skipsOverrides_forPreV140Installer() {
        addInstaller("_CommonRedist/vcredist/2013/vcredist_x86.exe")

        build()

        assertFalse(File(rootDir, ".wine/user.reg").exists())
    }

    @Test
    fun buildCommand_usesScriptPathAndArgs_whenInstallScriptListsRedist() {
        addInstaller("VCRedist/vcredist_x86.exe")
        addInstaller("_CommonRedist/vcredist/2019/VC_redist.x64.exe")
        File(gameDir, "installscript.vdf").writeText(
            """
            "installscript"
            {
                "run process"
                {
                    "visual c++ 2008 sp1 redistributable package (x86)"
                    {
                        "process 1"		"%INSTALLDIR%\\VCRedist\\vcredist_x86.exe"
                        "command 1"		"/q:a"
                        "nocleanup"		"1"
                    }
                    "directx 9"
                    {
                        "process 1"		"%INSTALLDIR%\\DirectX\\DXSetup.exe"
                        "command 1"		"/silent"
                    }
                }
            }
            """.trimIndent(),
        )

        assertEquals("A:\\VCRedist\\vcredist_x86.exe /q:a", checkNotNull(build()))
    }

    @Test
    fun buildCommand_readsScriptsShippedUnderCommonRedist() {
        addInstaller("_CommonRedist/vcredist/2022/VC_redist.x64.exe")
        File(gameDir, "_CommonRedist/vcredist/2022/installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "VCRedist2022x64"
                    {
                        "HasRunKey"		"HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2022\\x64"
                        "process 1"		"%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
                        "command 1"		"/install /quiet /norestart"
                        "NoCleanUp"		"1"
                    }
                }
            }
            """.trimIndent(),
        )

        assertEquals("A:\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe /install /quiet /norestart", checkNotNull(build()))
        assertTrue(File(rootDir, ".wine/user.reg").readText().contains("\"ucrtbase\"=\"builtin\""))
    }

    @Test
    fun buildCommand_ignoresScriptEntriesWhoseFileIsMissing() {
        File(gameDir, "installscript.vdf").writeText(
            """
            "installscript"
            {
                "run process"
                {
                    "vc"
                    {
                        "process 1"		"%INSTALLDIR%\\VCRedist\\vcredist_x86.exe"
                        "command 1"		"/q"
                    }
                }
            }
            """.trimIndent(),
        )

        assertEquals(null, build())
    }
}
