package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RedistPlanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `installers extra is newline separated distinct absolute paths`() {
        val a = File(tmp.root, "a/vc_redist.x64.exe")
        val b = File(tmp.root, "b/DXSETUP.exe")
        val plan = RedistPlan(setOf("vcrun2022"), listOf(a, b, a))

        assertEquals("${a.absolutePath}\n${b.absolutePath}", plan.installersExtra())
        assertEquals("", RedistPlan.EMPTY.installersExtra())
    }

    @Test
    fun `resolve walks segments case insensitively and rejects parent segments`() {
        val target = File(tmp.root, "Game/_CommonRedist/DirectX/DXSETUP.exe").apply { parentFile.mkdirs(); writeText("x") }
        val game = File(tmp.root, "Game")

        assertEquals(target, RedistPaths.resolve(game, "_commonredist\\directx\\dxsetup.exe"))
        assertNull(RedistPaths.resolve(game, "_CommonRedist/../_CommonRedist/DirectX/DXSETUP.exe"))
        assertNull(RedistPaths.resolve(game, "_CommonRedist/OpenAL/oalinst.exe"))
    }
}
