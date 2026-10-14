package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EpicRedistPrereqsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `unreal prerequisite setup maps to vcrun2022 and direct3d`() {
        val expected = setOf("vcrun2022", "direct3d")
        assertEquals(
            expected,
            EpicRedistPrereqs.componentsFor("", "Engine/Extras/Redist/en-us/UE4PrereqSetup_x64.exe"),
        )
        assertEquals(
            expected,
            EpicRedistPrereqs.componentsFor("", "Engine\\Extras\\Redist\\en-us\\UEPrereqSetup_x64.exe"),
        )
        assertEquals(
            expected,
            EpicRedistPrereqs.componentsFor("Unreal Engine 4 Prerequisites", "Setup/Installer.exe"),
        )
    }

    @Test
    fun `vcredist path uses the year in the path`() {
        assertEquals(setOf("vcrun2013"), EpicRedistPrereqs.componentsFor("", "Redist/2013/vcredist_x64.exe"))
        assertEquals(setOf("vcrun2010"), EpicRedistPrereqs.componentsFor("", "_Redist/vcredist_x86_2010.exe"))
        assertEquals(setOf("vcrun2022"), EpicRedistPrereqs.componentsFor("", "Redist/2019/VC_redist.x64.exe"))
    }

    @Test
    fun `vcredist path without a year defaults to vcrun2022`() {
        assertEquals(setOf("vcrun2022"), EpicRedistPrereqs.componentsFor("", "Prereqs/VC_redist.x64.exe"))
    }

    @Test
    fun `directx setup maps to direct3d and xaudio`() {
        assertEquals(setOf("direct3d", "xaudio"), EpicRedistPrereqs.componentsFor("", "Redist/DirectX/DXSETUP.exe"))
        assertEquals(setOf("direct3d", "xaudio"), EpicRedistPrereqs.componentsFor("DirectX Runtime", "Redist/setup.exe"))
    }

    @Test
    fun `unknown prerequisite is reported`() {
        val mapping = EpicRedistPrereqs.map(listOf("id"), "Custom Launcher Setup", "Launcher/setup.exe")
        assertTrue(mapping.components.isEmpty())
        assertEquals(listOf("Custom Launcher Setup"), mapping.unmapped)
    }

    @Test
    fun `empty prerequisite yields nothing`() {
        val mapping = EpicRedistPrereqs.map(emptyList(), "", "")
        assertTrue(mapping.components.isEmpty())
        assertTrue(mapping.unmapped.isEmpty())
    }

    private fun touch(root: File, path: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText("x") }

    @Test
    fun `prereq path resolves case insensitively with either separator`() {
        val game = tmp.newFolder("game")
        val setup = touch(game, "Engine/Extras/Redist/en-us/UE4PrereqSetup_x64.exe")

        assertEquals(setup, EpicRedistPrereqs.installerFor(game, "engine\\extras\\redist\\EN-US\\ue4prereqsetup_x64.exe"))
        assertEquals(setup, EpicRedistPrereqs.installerFor(game, "Engine/Extras/Redist/en-us/UE4PrereqSetup_x64.exe"))
    }

    @Test
    fun `missing blank or escaping prereq path resolves to nothing`() {
        val game = tmp.newFolder("game")
        touch(tmp.root, "outside.exe")

        assertNull(EpicRedistPrereqs.installerFor(game, "Redist/missing.exe"))
        assertNull(EpicRedistPrereqs.installerFor(game, ""))
        assertNull(EpicRedistPrereqs.installerFor(game, "../outside.exe"))
    }

    @Test
    fun `plan collects the installer only for a mapped prereq`() {
        val game = tmp.newFolder("game")
        val setup = touch(game, "Engine/Extras/Redist/en-us/UEPrereqSetup_x64.exe")
        touch(game, "Launcher/setup.exe")

        val mapped = EpicRedistPrereqs.plan(game, listOf("id"), "", "Engine/Extras/Redist/en-us/UEPrereqSetup_x64.exe")
        assertEquals(setOf("vcrun2022", "direct3d"), mapped.components)
        assertEquals(listOf(setup), mapped.installers)

        val unmapped = EpicRedistPrereqs.plan(game, listOf("id"), "Custom Launcher Setup", "Launcher/setup.exe")
        assertTrue(unmapped.components.isEmpty())
        assertTrue(unmapped.installers.isEmpty())
        assertEquals(listOf("Custom Launcher Setup"), unmapped.unmapped)
    }
}
