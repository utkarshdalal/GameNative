package app.gamenative.utils

import app.gamenative.data.DepotInfo
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.service.SteamService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.EnumSet
import kotlin.io.path.createTempDirectory

class SteamRedistDepotsTest {

    private fun depot(
        depotId: Int,
        depotFromApp: Int = SteamRedistDepots.STEAMWORKS_SHARED_APP_ID,
        sharedInstall: Boolean = true,
    ) = DepotInfo(
        depotId = depotId,
        dlcAppId = SteamService.INVALID_APP_ID,
        depotFromApp = depotFromApp,
        sharedInstall = sharedInstall,
        osList = EnumSet.of(OS.windows),
        osArch = OSArch.Arch64,
        manifests = emptyMap(),
        encryptedManifests = emptyMap(),
    )

    @Test
    fun `vc 2010 depot maps to vcrun2010`() {
        assertEquals(setOf("vcrun2010"), SteamRedistDepots.componentsForDepots(listOf(depot(228983))))
    }

    @Test
    fun `vc 2015 through 2022 depots map to a single vcrun2022`() {
        val depots = listOf(depot(228986), depot(228987), depot(228988), depot(228989))
        assertEquals(setOf("vcrun2022"), SteamRedistDepots.componentsForDepots(depots))
    }

    @Test
    fun `directx june 2010 depot maps to direct3d and xaudio`() {
        assertEquals(setOf("direct3d", "xaudio"), SteamRedistDepots.componentsForDepots(listOf(depot(228990))))
    }

    @Test
    fun `vc 2005 2008 2012 and 2013 depots map to their own components`() {
        assertEquals(setOf("vcrun2005"), SteamRedistDepots.componentsForDepots(listOf(depot(228981))))
        assertEquals(setOf("vcrun2008"), SteamRedistDepots.componentsForDepots(listOf(depot(228982))))
        assertEquals(setOf("vcrun2012"), SteamRedistDepots.componentsForDepots(listOf(depot(228984))))
        assertEquals(setOf("vcrun2013"), SteamRedistDepots.componentsForDepots(listOf(depot(228985))))
    }

    @Test
    fun `every steamworks depot maps to at least one component`() {
        SteamRedistDepots.DEPOT_COMPONENTS.forEach { (depotId, components) ->
            assertTrue("depot $depotId has no component", components.isNotEmpty())
        }
    }

    @Test
    fun `depots not shared from steamworks are ignored`() {
        val depots = listOf(
            depot(228989, depotFromApp = SteamService.INVALID_APP_ID),
            depot(228983, sharedInstall = false),
            depot(731, depotFromApp = SteamService.INVALID_APP_ID, sharedInstall = false),
        )
        assertTrue(SteamRedistDepots.componentsForDepots(depots).isEmpty())
    }

    @Test
    fun `unknown steamworks depot is ignored`() {
        assertTrue(SteamRedistDepots.componentsForDepots(listOf(depot(229000))).isEmpty())
    }

    @Test
    fun `mixed depots union their components`() {
        val depots = listOf(depot(228983), depot(228989), depot(228990), depot(1234, depotFromApp = 0, sharedInstall = false))
        assertEquals(
            setOf("vcrun2010", "vcrun2022", "direct3d", "xaudio"),
            SteamRedistDepots.componentsForDepots(depots),
        )
    }

    @Test
    fun `merge appends missing components as enabled`() {
        val merged = SteamRedistDepots.mergeComponents("direct3d=1,vcrun2010=1", setOf("vcrun2022", "xaudio"))
        assertEquals("direct3d=1,vcrun2010=1,vcrun2022=1,xaudio=1", merged)
    }

    @Test
    fun `merge never overrides an existing value`() {
        val current = "direct3d=0,vcrun2010=0,vcrun2022=0"
        assertEquals(current, SteamRedistDepots.mergeComponents(current, setOf("direct3d", "vcrun2010", "vcrun2022")))
    }

    @Test
    fun `merge only adds what is missing`() {
        val merged = SteamRedistDepots.mergeComponents("direct3d=0,wmdecoder=1", setOf("direct3d", "xaudio"))
        assertEquals("direct3d=0,wmdecoder=1,xaudio=1", merged)
    }

    @Test
    fun `merge into empty string`() {
        assertEquals("vcrun2022=1", SteamRedistDepots.mergeComponents("", setOf("vcrun2022")))
    }

    @Test
    fun `merge with nothing required returns input unchanged`() {
        val current = "direct3d=1,vcrun2010=1"
        assertEquals(current, SteamRedistDepots.mergeComponents(current, emptySet()))
    }

    @Test
    fun `install script installers enable their vc families when no depots exist`() {
        val gameDir = createTempDirectory(prefix = "bl2").toFile()
        try {
            val redist = File(gameDir, "Binaries/Redist").apply { mkdirs() }
            fun exe(name: String, major: Int) = File(redist, name).writeBytes(
                SyntheticPe.build(0x14c, intArrayOf(major, 0, 1, 0), mapOf("ProductName" to "Microsoft Visual C++ Redistributable")),
            )
            exe("vcredist_2005_x86.exe", 8)
            exe("vcredist_2008_x86.exe", 9)
            exe("vcredist_x86_vs2010sp1.exe", 10)
            File(gameDir, "InstallScript.vdf").writeText(
                """
                "InstallScript"
                {
                    "Run Process"
                    {
                        "VC2005"
                        {
                            "process 1" "%INSTALLDIR%\\Binaries\\Redist\\vcredist_2005_x86.exe"
                        }
                        "VC2008"
                        {
                            "process 1" "%INSTALLDIR%\\Binaries\\Redist\\vcredist_2008_x86.exe"
                        }
                        "VC2010"
                        {
                            "process 1" "%INSTALLDIR%\\Binaries\\Redist\\vcredist_x86_vs2010sp1.exe"
                        }
                    }
                }
                """.trimIndent(),
            )

            val required = SteamRedistDepots.componentsForDepots(emptyList()) + SteamRedistDepots.scriptComponents(gameDir, "")

            assertEquals(setOf("vcrun2005", "vcrun2008", "vcrun2010"), required)
            assertEquals(
                "vcrun2010=0,direct3d=1,vcrun2005=1,vcrun2008=1",
                SteamRedistDepots.mergeComponents("vcrun2010=0,direct3d=1", required),
            )
            assertEquals(emptySet<String>(), SteamRedistDepots.scriptComponents(null, ""))
            assertEquals(emptySet<String>(), SteamRedistDepots.scriptComponents(File(gameDir, "missing"), ""))
        } finally {
            gameDir.deleteRecursively()
        }
    }

    private fun vcInstaller(file: File, major: Int, machine: Int = 0x14c) {
        file.parentFile?.mkdirs()
        file.writeBytes(SyntheticPe.build(machine, intArrayOf(major, 0, 1, 0), mapOf("ProductName" to "Microsoft Visual C++ Redistributable")))
    }

    @Test
    fun `redist folder installers enable their vc families`() {
        val gameDir = createTempDirectory(prefix = "doom").toFile()
        try {
            vcInstaller(File(gameDir, "_CommonRedist/vcredist/2015/VC_redist.x64.exe"), 14, machine = 0x8664)

            assertEquals(setOf("vcrun2022"), SteamRedistDepots.layoutComponents(gameDir, null))
            assertEquals(
                "direct3d=1,vcrun2010=1,vcrun2022=1",
                SteamRedistDepots.mergeComponents("direct3d=1,vcrun2010=1", SteamRedistDepots.layoutComponents(gameDir, null)),
            )
        } finally {
            gameDir.deleteRecursively()
        }
    }

    @Test
    fun `every redist folder layout of the old vc_redist step is read`() {
        val gameDir = createTempDirectory(prefix = "layouts").toFile()
        try {
            vcInstaller(File(gameDir, "_commonredist/MSVC2013_x64/vcredist_x64.exe"), 12, machine = 0x8664)
            vcInstaller(File(gameDir, "_commonredist/MSVC2012/vcredist_x86.exe"), 11)
            vcInstaller(File(gameDir, "_commonredist/VC_redist.x86.exe"), 14)
            vcInstaller(File(gameDir, "Redist/vcredist_x86.exe"), 10)
            vcInstaller(File(gameDir, "_commonredist/DirectX/Jun2010/DXSETUP.exe"), 9)
            vcInstaller(File(gameDir, "_commonredist/MSVC2008/setup.exe"), 9)
            File(gameDir, "_commonredist/MSVC2005/vcredist_x86.exe").apply { parentFile?.mkdirs() }.writeText("not a binary")

            assertEquals(
                setOf("vcrun2010", "vcrun2012", "vcrun2013", "vcrun2022"),
                SteamRedistDepots.layoutComponents(gameDir, null),
            )
        } finally {
            gameDir.deleteRecursively()
        }
    }

    @Test
    fun `redist folders add nothing without vc installers`() {
        val gameDir = createTempDirectory(prefix = "none").toFile()
        try {
            assertEquals(emptySet<String>(), SteamRedistDepots.layoutComponents(gameDir, null))
            vcInstaller(File(gameDir, "Binaries/vcredist_x86.exe"), 10)
            File(gameDir, "_CommonRedist/vcredist/2015").mkdirs()
            assertEquals(emptySet<String>(), SteamRedistDepots.layoutComponents(gameDir, null))
            assertEquals(emptySet<String>(), SteamRedistDepots.layoutComponents(null, null))
            assertEquals(emptySet<String>(), SteamRedistDepots.layoutComponents(File(gameDir, "missing"), null))
        } finally {
            gameDir.deleteRecursively()
        }
    }

    @Test
    fun `redist folder installers an install script runs are not read twice`() {
        val gameDir = createTempDirectory(prefix = "scripted").toFile()
        try {
            vcInstaller(File(gameDir, "_CommonRedist/vcredist/2010/vcredist_x86.exe"), 10)
            vcInstaller(File(gameDir, "_CommonRedist/vcredist/2015/VC_redist.x86.exe"), 14)
            File(gameDir, "installscript.vdf").writeText(
                """
                "InstallScript"
                {
                    "Run Process"
                    {
                        "VC2010"
                        {
                            "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2010\\vcredist_x86.exe"
                        }
                    }
                }
                """.trimIndent(),
            )

            assertEquals(setOf("vcrun2010"), SteamRedistDepots.scriptComponents(gameDir, ""))
            assertEquals(setOf("vcrun2022"), SteamRedistDepots.layoutComponents(gameDir, ""))
        } finally {
            gameDir.deleteRecursively()
        }
    }
}
