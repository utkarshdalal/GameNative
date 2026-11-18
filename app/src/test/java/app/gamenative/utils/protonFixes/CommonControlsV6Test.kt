package app.gamenative.utils.protonFixes

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.WineInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class CommonControlsV6Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private val assembly = "microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef"
    private val context = mockk<Context>(relaxed = true)

    /** A wine tree with `lib/wine/<peDir>/<file>` holding [content] for every entry. */
    private fun wineTree(name: String, files: Map<String, Map<String, String>>): File {
        val root = tmp.newFolder(name)
        for ((peDir, dlls) in files) {
            val dir = File(root, "lib/wine/$peDir").apply { mkdirs() }
            for ((dll, content) in dlls) File(dir, dll).writeText(content)
        }
        return root
    }

    private fun container(root: File, basePrefix: File? = null): Container {
        val container = mockk<Container>(relaxed = true)
        every { container.rootDir } returns root
        every { container.isOverlay } returns (basePrefix != null)
        every { container.basePrefix } returns (basePrefix?.path ?: "")
        return container
    }

    private fun winsxs(wine: File) = File(wine, "drive_c/windows/winsxs")
    private fun assemblyDll(winsxs: File, arch: String) = File(winsxs, "${arch}_$assembly/comctl32.dll")
    private fun manifest(winsxs: File, arch: String) = File(winsxs, "manifests/${arch}_$assembly.manifest")

    /** What setupapi leaves behind: a copy of the DLL and Wine's own manifest. */
    private fun seedAssembly(winsxs: File, arch: String, content: String) {
        assemblyDll(winsxs, arch).apply { parentFile!!.mkdirs() }.writeText(content)
        manifest(winsxs, arch).apply { parentFile!!.mkdirs() }.writeText(
            "<assembly><assemblyIdentity name=\"Microsoft.Windows.Common-Controls\" " +
                "processorArchitecture=\"$arch\"/><file name=\"comctl32.dll\"><windowClass>Combobox</windowClass></file></assembly>",
        )
    }

    private fun assertServes(winsxs: File, arch: String, content: String) {
        assertEquals(content, assemblyDll(winsxs, arch).readText())
        assertTrue(manifest(winsxs, arch).readText().contains("processorArchitecture=\"$arch\""))
    }

    private val preSplitArm64ec = mapOf(
        "aarch64-windows" to mapOf("comctl32.dll" to "pre-split arm64"),
        "i386-windows" to mapOf("comctl32.dll" to "pre-split x86"),
    )

    @Test
    fun `a build without comctl32_v6 keeps its own assembly and manifest`() {
        val tree = wineTree("proton-10.0-arm64ec", preSplitArm64ec)
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        seedAssembly(sxs, "arm64", "pre-split arm64")
        seedAssembly(sxs, "x86", "pre-split x86")

        val installed = CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "10.0", "arm64ec", tree.path))

        // Only the amd64_ name, which arm64ec always gets, is new; Wine's two copies are left as they are.
        assertEquals(1, installed)
        val arm64Src = File(tree, "lib/wine/aarch64-windows/comctl32.dll")
        assertFalse(Files.isSameFile(assemblyDll(sxs, "arm64").toPath(), arm64Src.toPath()))
        assertServes(sxs, "arm64", "pre-split arm64")
        assertServes(sxs, "x86", "pre-split x86")
        assertServes(sxs, "amd64", "pre-split arm64")
        assertEquals(0, CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "10.0", "arm64ec", tree.path)))
    }

    @Test
    fun `a prefix that lost the assembly is repaired from the build's comctl32`() {
        val tree = wineTree(
            "proton-9.0-x86_64",
            mapOf(
                "x86_64-windows" to mapOf("comctl32.dll" to "pre-split amd64"),
                "i386-windows" to mapOf("comctl32.dll" to "pre-split x86"),
            ),
        )
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        File(sxs, "manifests").mkdirs()

        val installed = CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "9.0", "x86_64", tree.path))

        assertEquals(2, installed)
        assertServes(sxs, "amd64", "pre-split amd64")
        assertServes(sxs, "x86", "pre-split x86")
    }

    @Test
    fun `an assembly left by another build is replaced with this build's DLL`() {
        val tree = wineTree("proton-10.0-arm64ec", preSplitArm64ec)
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        seedAssembly(sxs, "amd64", "proton 11 v6 arm64")
        seedAssembly(sxs, "arm64", "proton 11 v6 arm64")
        seedAssembly(sxs, "x86", "proton 11 v6 x86")

        val installed = CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "10.0", "arm64ec", tree.path))

        assertEquals(3, installed)
        assertServes(sxs, "amd64", "pre-split arm64")
        assertServes(sxs, "arm64", "pre-split arm64")
        assertServes(sxs, "x86", "pre-split x86")
    }

    @Test
    fun `an arch the build has no PE directory for is still removed`() {
        val tree = wineTree(
            "proton-9.0-x86_64",
            mapOf(
                "x86_64-windows" to mapOf("comctl32.dll" to "pre-split amd64"),
                "i386-windows" to mapOf("comctl32.dll" to "pre-split x86"),
            ),
        )
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        seedAssembly(sxs, "arm64", "proton 11 v6 arm64")

        CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "9.0", "x86_64", tree.path))

        assertFalse(File(sxs, "arm64_$assembly").exists())
        assertFalse(manifest(sxs, "arm64").exists())
        assertServes(sxs, "amd64", "pre-split amd64")
    }

    @Test
    fun `a thin container's stale local copy gives way to the base prefix`() {
        val tree = wineTree("proton-10.0-arm64ec", preSplitArm64ec)
        val root = tmp.newFolder("container")
        val base = tmp.newFolder("base_prefix", ".wine")
        val upper = winsxs(File(root, ".wine"))
        seedAssembly(upper, "amd64", "pattern x86_64 v6")

        CommonControlsV6.ensureInstalled(context, container(root, base), WineInfo("proton", "10.0", "arm64ec", tree.path))

        assertFalse(File(upper, "amd64_$assembly").exists())
        assertFalse(manifest(upper, "amd64").exists())
        val sxs = winsxs(base)
        assertServes(sxs, "amd64", "pre-split arm64")
        assertServes(sxs, "arm64", "pre-split arm64")
        assertServes(sxs, "x86", "pre-split x86")
    }

    @Test
    fun `a split build installs comctl32_v6 and never its v5 comctl32`() {
        val tree = wineTree(
            "proton-11.0-arm64ec",
            mapOf(
                "aarch64-windows" to mapOf("comctl32.dll" to "v5 arm64", "comctl32_v6.dll" to "v6 arm64"),
                "i386-windows" to mapOf("comctl32.dll" to "v5 x86", "comctl32_v6.dll" to "v6 x86"),
            ),
        )
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        seedAssembly(sxs, "arm64", "pre-split arm64")

        val wine = WineInfo("proton", "11.0", "arm64ec", tree.path)
        assertEquals(3, CommonControlsV6.ensureInstalled(context, container(root), wine))
        assertServes(sxs, "amd64", "v6 arm64")
        assertServes(sxs, "arm64", "v6 arm64")
        assertServes(sxs, "x86", "v6 x86")
        assertEquals(0, CommonControlsV6.ensureInstalled(context, container(root), wine))

        // A split build missing one arch's v6 DLL drops that arch instead of serving the v5 DLL.
        File(tree, "lib/wine/i386-windows/comctl32_v6.dll").delete()
        CommonControlsV6.ensureInstalled(context, container(root), wine)
        assertFalse(File(sxs, "x86_$assembly").exists())
        assertFalse(manifest(sxs, "x86").exists())
        assertArrayEquals("v6 arm64".toByteArray(), assemblyDll(sxs, "arm64").readBytes())
    }
}
