package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.service.gog.GOGManifestUtils
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

    @Test
    fun `gog manifest with no dependencies falls back to the commonredist folders`() {
        val game = tmp.newFolder("gog")
        File(game, GOGManifestUtils.MANIFEST_FILE_NAME).writeText("""{"dependencies":["", " "]}""")
        val vc = File(game, "_CommonRedist/MSVC2019_x64/VC_redist.x64.exe").apply { parentFile.mkdirs(); writeText("x") }

        assertNull(GOGManifestUtils.readDependencies(game))
        val plan = RedistComponents.planFor(GameSource.GOG, game)!!
        assertEquals(setOf("vcrun2022"), plan.components)
        assertEquals(listOf(vc), plan.installers)
    }
}
