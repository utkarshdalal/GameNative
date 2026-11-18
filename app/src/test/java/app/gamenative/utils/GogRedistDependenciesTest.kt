package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GogRedistDependenciesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `older msvc ids map to their own vcrun`() {
        assertEquals(setOf("vcrun2005"), GogRedistDependencies.componentsFor("MSVC2005"))
        assertEquals(setOf("vcrun2008"), GogRedistDependencies.componentsFor("MSVC2008_x64"))
        assertEquals(setOf("vcrun2010"), GogRedistDependencies.componentsFor("MSVC2010"))
        assertEquals(setOf("vcrun2012"), GogRedistDependencies.componentsFor("MSVC2012"))
        assertEquals(setOf("vcrun2013"), GogRedistDependencies.componentsFor("MSVC2013_x64"))
    }

    @Test
    fun `msvc 2015 through 2022 map to a single vcrun2022`() {
        val mapping = GogRedistDependencies.map(
            listOf("MSVC2015", "MSVC2017", "MSVC2017_x64", "MSVC2019", "MSVC2022_x64"),
        )
        assertEquals(setOf("vcrun2022"), mapping.components)
        assertTrue(mapping.unmapped.isEmpty())
    }

    @Test
    fun `directx ids map to direct3d and xaudio`() {
        for (id in listOf("DirectX", "DX9", "D3DX9", "D3DX11_43")) {
            assertEquals(id, setOf("direct3d", "xaudio"), GogRedistDependencies.componentsFor(id))
        }
    }

    @Test
    fun `openal maps to openal`() {
        assertEquals(setOf("openal"), GogRedistDependencies.componentsFor("OpenAL"))
    }

    @Test
    fun `dotnet physx xna and unknown ids are reported as unmapped`() {
        val ids = listOf("DOTNET4", "DOTNET35", "PhysX", "XNA40", "ISI", "MSVC1999")
        val mapping = GogRedistDependencies.map(ids)
        assertTrue(mapping.components.isEmpty())
        assertEquals(ids, mapping.unmapped)
    }

    @Test
    fun `mixed list keeps mapped components and reports the rest`() {
        val mapping = GogRedistDependencies.map(listOf("MSVC2010", "DirectX", "PhysX", "MSVC2019_x64"))
        assertEquals(setOf("vcrun2010", "direct3d", "xaudio", "vcrun2022"), mapping.components)
        assertEquals(listOf("PhysX"), mapping.unmapped)
    }

    private fun touch(root: File, path: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText("x") }

    @Test
    fun `plan collects installers from the commonredist folder of each mapped id`() {
        val game = tmp.newFolder("game")
        val vc = touch(game, "_CommonRedist/MSVC2019_x64/VC_redist.x64.exe")
        val dx = touch(game, "_CommonRedist/DirectX/DXSETUP.exe")
        val msi = touch(game, "_CommonRedist/DirectX/extra.msi")
        touch(game, "_CommonRedist/DirectX/readme.txt")
        touch(game, "_CommonRedist/DirectX/Jun2010/nested.exe")
        touch(game, "_CommonRedist/PhysX/PhysX_setup.exe")

        val plan = GogRedistDependencies.plan(game, listOf("MSVC2019_x64", "DirectX", "PhysX"))

        assertEquals(setOf("vcrun2022", "direct3d", "xaudio"), plan.components)
        assertEquals(listOf(vc, dx, msi), plan.installers)
        assertEquals(listOf("PhysX"), plan.unmapped)
    }

    @Test
    fun `installer folder lookup ignores case and missing folders yield nothing`() {
        val game = tmp.newFolder("game")
        val vc = touch(game, "_commonredist/msvc2010/vcredist_x86.exe")

        val plan = GogRedistDependencies.plan(game, listOf("MSVC2010", "MSVC2013"))

        assertEquals(setOf("vcrun2010", "vcrun2013"), plan.components)
        assertEquals(listOf(vc), plan.installers)
    }
}
