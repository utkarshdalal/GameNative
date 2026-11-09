package app.gamenative.utils

import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class PhysXLayoutTest {
    private lateinit var container: Container
    private lateinit var gameDir: File
    private lateinit var prefixDir: File
    private lateinit var systemReg: File
    private val winComponentsSlot = slot<String>()

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "physx-layout-game").toFile()
        prefixDir = createTempDirectory(prefix = "physx-layout-prefix").toFile()
        systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n[Software\\\\Wine] 1700000000\n#time=1\n\n")
        every { container.drives } returns "A:${gameDir.absolutePath}"
        every { container.winComponents = capture(winComponentsSlot) } returns Unit
    }

    @After
    fun tearDown() {
        gameDir.deleteRecursively()
        prefixDir.deleteRecursively()
    }

    private fun touch(path: String) {
        val file = File(gameDir, path)
        file.parentFile?.mkdirs()
        file.writeText("x")
    }

    private fun detected(source: GameSource?, installScript: String? = null): Boolean =
        PhysXLayout.hasInstaller(PhysXLayout.candidateInstallers(source, gameDir, installScript))

    @Test
    fun hasInstaller_detectsLegacyMsiInCommonRedist() {
        touch("_CommonRedist/PhysX/9.13.0604/PhysX-9.13.0604-SystemSoftware-Legacy.msi")
        assertTrue(detected(null))
        assertTrue(detected(GameSource.STEAM, ""))
        assertTrue(detected(GameSource.CUSTOM_GAME))
    }

    @Test
    fun hasInstaller_ignoresUnrelatedFiles() {
        touch("_CommonRedist/PhysX/readme.txt")
        touch("redist/oalinst.exe")
        touch("redist/PhysX_9.23.1019_SystemSoftware.exe")
        touch("Binaries/PhysXLoader.dll")
        touch("Binaries/Redist/PhysX_9.12.0613_SystemSoftware.exe")
        assertFalse(detected(null))
        assertFalse(detected(GameSource.STEAM, ""))
    }

    @Test
    fun hasInstaller_detectsInstallersNamedByInstallScriptSteps() {
        touch("Binaries/Redist/PhysX_9.12.0613_SystemSoftware.exe")
        File(gameDir, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "PhysX"
                    {
                        "process 1" "%INSTALLDIR%\\Binaries\\Redist\\PhysX_9.12.0613_SystemSoftware.exe"
                        "command 1" "/quiet"
                    }
                }
            }
            """.trimIndent(),
        )

        assertTrue(detected(GameSource.STEAM, ""))
        assertFalse(detected(null))
    }

    @Test
    fun hasInstaller_recognisesPhysxByVersionResource() {
        val setup = File(gameDir, "Binaries/Redist/NvSetup.exe").apply {
            parentFile!!.mkdirs()
            writeBytes(SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(9, 12, 613, 0), mapOf("ProductName" to "NVIDIA PhysX System Software")))
        }
        val directx = File(gameDir, "Binaries/Redist/DXSETUP.exe").apply {
            writeBytes(SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(9, 29, 1974, 0), mapOf("ProductName" to "Microsoft DirectX for Windows")))
        }

        assertTrue(PhysXLayout.hasInstaller(listOf(directx, setup)))
        assertFalse(PhysXLayout.hasInstaller(listOf(directx)))
    }

    @Test
    fun candidateInstallers_includePlanAndUnrealInstallersForStoreGames() {
        touch("_CommonRedist/vcredist/2019/VC_redist.x64.exe")
        touch("Engine/Extras/Redist/en-us/UE4PrereqSetup_x64.exe")
        touch("_CommonRedist/PhysX/PhysX_9.23.1019_SystemSoftware.exe")

        val candidates = PhysXLayout.candidateInstallers(GameSource.CUSTOM_GAME, gameDir, null).map { it.relativeTo(gameDir).path }

        assertEquals(
            listOf(
                "_CommonRedist/vcredist/2019/VC_redist.x64.exe",
                "Engine/Extras/Redist/en-us/UE4PrereqSetup_x64.exe",
                "_CommonRedist/PhysX/PhysX_9.23.1019_SystemSoftware.exe",
            ),
            candidates,
        )
        assertTrue(detected(GameSource.CUSTOM_GAME))
    }

    @Test
    fun applyForLaunch_enablesPhysxComponent() {
        touch("_CommonRedist/PhysX/PhysX_9.23.1019_SystemSoftware.exe")
        every { container.winComponents } returns "direct3d=1,vcrun2010=1"

        PhysXLayout.applyForLaunch(container)

        assertEquals("direct3d=1,vcrun2010=1,physx=1", winComponentsSlot.captured)
        verify { container.saveData() }
    }

    @Test
    fun applyForLaunch_keepsUserChoiceWhenPhysxAlreadyListed() {
        touch("_CommonRedist/PhysX/PhysX_9.23.1019_SystemSoftware.exe")
        every { container.winComponents } returns "direct3d=1,physx=0"

        PhysXLayout.applyForLaunch(container)

        assertFalse(winComponentsSlot.isCaptured)
        verify(exactly = 0) { container.saveData() }
    }

    @Test
    fun applyForLaunch_leavesContainerAloneWithoutInstaller() {
        every { container.winComponents } returns "direct3d=1"

        PhysXLayout.applyForLaunch(container)

        assertFalse(winComponentsSlot.isCaptured)
    }

    @Test
    fun registryValues_haveNoDuplicateNames() {
        val values = PhysXLayout.registryValues()
        assertEquals(values.size, values.map { it.key to it.name }.distinct().size)
        assertTrue(values.all { (it.string == null) != (it.dword == null) })
    }

    @Test
    fun applyRegistry_writesBothRegistryViews() {
        PhysXLayout.applyRegistry(systemReg, true)

        WineRegistryEditor(systemReg).use { editor ->
            for (root in listOf("Software", "Software\\Wow6432Node")) {
                assertEquals(
                    "C:\\Program Files (x86)\\NVIDIA Corporation\\PhysX\\Engine",
                    editor.getStringValue("$root\\AGEIA Technologies", "PhysXCore Path"),
                )
                assertEquals(43, editor.getDwordValue("$root\\AGEIA Technologies\\PhysX_A32_Engines", "2.8.3"))
                assertEquals(2, editor.getDwordValue("$root\\AGEIA Technologies\\PhysX_A32_Engines", "2.3.1"))
                assertEquals(44, editor.getDwordValue("$root\\AGEIA Technologies\\PhysX_A64_Engines", "2.8.3"))
                assertEquals(9231019, editor.getDwordValue("$root\\AGEIA Technologies", "PhysX Version"))
                assertEquals(
                    "C:\\Program Files (x86)\\NVIDIA Corporation\\PhysX\\Engine\\C22346819C37\\PhysXCore.dll",
                    editor.getStringValue(
                        "$root\\NVIDIA Corporation\\PhysXUpdateLoader\\DllTable\\3F527913-C421-4c9b-BDBA-C22346819C37",
                        "dll_path",
                    ),
                )
            }
        }
    }

    @Test
    fun applyRegistry_disabledRemovesPhysxKeys() {
        PhysXLayout.applyRegistry(systemReg, true)
        PhysXLayout.applyRegistry(systemReg, false)

        WineRegistryEditor(systemReg).use { editor ->
            for (root in listOf("Software", "Software\\Wow6432Node")) {
                assertNull(editor.getStringValue("$root\\AGEIA Technologies", "PhysXCore Path"))
                assertNull(editor.getStringValue("$root\\NVIDIA Corporation\\PhysX_SystemSoftware", "PhysXCore Path"))
            }
        }
        assertTrue(systemReg.readText().contains("[Software\\\\Wine]"))
    }

    @Test
    fun setWinComponentRegistryKeys_routesPhysxToLayout() {
        WineUtils.setWinComponentRegistryKeys(systemReg, PhysXLayout.COMPONENT, true, false)

        WineRegistryEditor(systemReg).use { editor ->
            assertEquals("GPU", editor.getStringValue("Software\\Wow6432Node\\AGEIA Technologies", "HwSelection"))
        }
    }
}
