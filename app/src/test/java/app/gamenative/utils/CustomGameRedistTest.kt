package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomGameRedistTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(root: File, path: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText("x") }

    private fun script(file: File, stepName: String, process: String) = file.apply {
        parentFile.mkdirs()
        writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "$stepName"
                    {
                        "process 1" "%INSTALLDIR%\\$process"
                    }
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `root install script supplies the installers its run process steps name`() {
        val game = tmp.newFolder("game")
        script(File(game, "installscript.vdf"), "VC 2019 x64", "_CommonRedist\\\\vcredist\\\\2019\\\\VC_redist.x64.exe")
        val x64 = touch(game, "_CommonRedist/vcredist/2019/VC_redist.x64.exe")
        touch(game, "_CommonRedist/vcredist/2013/vcredist_x64.exe")

        assertTrue(CustomGameRedist.hasSteamInstallScript(game))
        assertEquals(RedistPlan(emptySet(), listOf(x64)), CustomGameRedist.resolve(game))
    }

    @Test
    fun `commonredist install script within three levels supplies its installers`() {
        val game = tmp.newFolder("game")
        script(File(game, "_CommonRedist/DirectX/Jun2010/installscript.vdf"), "DirectX", "_CommonRedist\\\\DirectX\\\\Jun2010\\\\DXSETUP.exe")
        val dx = touch(game, "_CommonRedist/DirectX/Jun2010/DXSETUP.exe")

        assertEquals(RedistPlan(emptySet(), listOf(dx)), CustomGameRedist.resolve(game))
    }

    @Test
    fun `install script without run process steps gives no installers`() {
        val game = tmp.newFolder("game")
        touch(game, "installscript.vdf")
        touch(game, "_CommonRedist/vcredist/2019/VC_redist.x64.exe")

        assertEquals(RedistPlan.EMPTY, CustomGameRedist.resolve(game))
    }

    @Test
    fun `install script deeper than three levels is ignored`() {
        val game = tmp.newFolder("game")
        touch(game, "_CommonRedist/a/b/c/installscript.vdf")
        touch(game, "Game/installscript.vdf")

        assertFalse(CustomGameRedist.hasSteamInstallScript(game))
    }

    @Test
    fun `steamworks vcredist year folders map by folder year`() {
        val game = tmp.newFolder("game")
        val v2008 = touch(game, "_CommonRedist/vcredist/2008/vcredist_x86.exe")
        val v2013 = touch(game, "_CommonRedist/vcredist/2013/vcredist_x64.exe")
        val v2019x64 = touch(game, "_CommonRedist/vcredist/2019/VC_redist.x64.exe")
        val v2019x86 = touch(game, "_CommonRedist/vcredist/2019/VC_redist.x86.exe")
        touch(game, "_CommonRedist/vcredist/2019/readme.txt")
        touch(game, "_CommonRedist/vcredist/latest/VC_redist.x64.exe")
        touch(game, "_CommonRedist/vcredist/2010/other.exe")

        val plan = CustomGameRedist.resolve(game)

        assertEquals(setOf("vcrun2008", "vcrun2013", "vcrun2022"), plan.components)
        assertEquals(listOf(v2008, v2013, v2019x64, v2019x86), plan.installers)
    }

    @Test
    fun `steamworks directx and openal folders map to their components`() {
        val game = tmp.newFolder("game")
        val dx = touch(game, "_CommonRedist/DirectX/Jun2010/DXSETUP.exe")
        val oal = touch(game, "_CommonRedist/OpenAL/2.0.7.0/oalinst.exe")

        val plan = CustomGameRedist.resolve(game)

        assertEquals(setOf("direct3d", "xaudio", "openal"), plan.components)
        assertEquals(listOf(dx, oal), plan.installers)
    }

    @Test
    fun `redist oalinst maps to openal`() {
        val game = tmp.newFolder("game")
        val oal = touch(game, "Redist/oalinst.exe")

        val plan = CustomGameRedist.resolve(game)

        assertEquals(setOf("openal"), plan.components)
        assertEquals(listOf(oal), plan.installers)
    }

    @Test
    fun `other layouts and root dlls yield nothing`() {
        val game = tmp.newFolder("game")
        touch(game, "msvcp140.dll")
        touch(game, "Support/vcredist_x64_2012.exe")
        touch(game, "Redistributables/DXSETUP.exe")
        touch(game, "_CommonRedist/vcredist_x64.exe")
        touch(game, "_CommonRedist/MSVC2019/readme.exe")
        touch(game, "_CommonRedist/MSVCnew/VC_redist.x64.exe")
        touch(game, "redist/vc_redist.x64.exe")
        touch(game, "bin/redist/vcredist/2010/vcredist_x86.exe")

        assertEquals(RedistPlan.EMPTY, CustomGameRedist.resolve(game))
    }

    @Test
    fun `redist vcredist installers are listed without a component`() {
        val game = tmp.newFolder("game")
        val x64 = touch(game, "Redist/VCREDIST_X64.EXE")
        val x86 = touch(game, "Redist/vcredist_x86.exe")

        val plan = CustomGameRedist.resolve(game)

        assertTrue(plan.components.isEmpty())
        assertEquals(setOf(x64, x86), plan.installers.toSet())
        assertEquals(2, plan.installers.size)
    }

    @Test
    fun `commonredist root vc_redist maps to vcrun2022`() {
        val game = tmp.newFolder("game")
        val x86 = touch(game, "_commonredist/vc_redist.x86.exe")
        val x64 = touch(game, "_commonredist/VC_redist.x64.exe")

        val plan = CustomGameRedist.resolve(game)

        assertEquals(setOf("vcrun2022"), plan.components)
        assertEquals(listOf(x86, x64), plan.installers)
    }

    @Test
    fun `commonredist msvc folders map by folder year`() {
        val game = tmp.newFolder("game")
        val v2010 = touch(game, "_CommonRedist/MSVC2010/vcredist_x86.exe")
        val v2013 = touch(game, "_CommonRedist/msvc2013_x64/vcredist_x64.exe")
        val v2017 = touch(game, "_CommonRedist/MSVC2017_x64/VC_redist.x64.exe")
        touch(game, "_CommonRedist/MSVC2019/setup.exe")

        val plan = CustomGameRedist.resolve(game)

        assertEquals(setOf("vcrun2010", "vcrun2013", "vcrun2022"), plan.components)
        assertEquals(setOf(v2010, v2013, v2017), plan.installers.toSet())
    }
}
