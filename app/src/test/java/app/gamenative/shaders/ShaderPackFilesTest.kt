package app.gamenative.shaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Per-preset cache logic (user decision 2026-08-12): no pack tarball anymore — each
 * preset downloads ONLY its own dependency closure (deps from the catalog) into the
 * cache dir, reusing shared files. These pure functions are the JVM-testable core.
 */
class ShaderPackFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun preset(
        path: String = "crt/crt-easymode.slangp",
        deps: List<String> = listOf("crt/crt-easymode.slangp", "crt/shaders/crt-easymode.slang"),
        broken: Boolean = false,
    ) = ShaderPreset(path = path, family = "crt", passes = 1, bytes = 6362, deps = deps, broken = broken)

    @Test
    fun `preset with full closure in cache is local`() {
        val packDir = tmp.newFolder("pack")
        preset().deps.forEach { rel ->
            File(packDir, rel).apply { parentFile.mkdirs(); writeText("x") }
        }
        assertTrue(isPresetLocal(preset(), packDir))
        assertTrue(missingFiles(preset(), packDir).isEmpty())
    }

    @Test
    fun `preset with any missing closure file is not local`() {
        val packDir = tmp.newFolder("pack2")
        File(packDir, "crt/crt-easymode.slangp").apply { parentFile.mkdirs(); writeText("x") }
        // .slang missing -> cloud state (missingFiles lists exactly the absent file).
        assertEquals(
            listOf("crt/shaders/crt-easymode.slang"),
            missingFiles(preset(), packDir),
        )
        assertFalse(isPresetLocal(preset(), packDir))
    }

    @Test
    fun `empty pack dir means every file is missing`() {
        val packDir = tmp.newFolder("pack3")
        assertEquals(preset().deps, missingFiles(preset(), packDir))
    }

    @Test
    fun `broken presets are never local even with all files`() {
        val packDir = tmp.newFolder("pack4")
        preset().deps.forEach { rel ->
            File(packDir, rel).apply { parentFile.mkdirs(); writeText("x") }
        }
        assertFalse(isPresetLocal(preset(broken = true), packDir))
    }

    @Test
    fun `preset without deps falls back to its own file`() {
        val packDir = tmp.newFolder("pack5")
        val p = preset(deps = emptyList())
        assertEquals(listOf(p.path), missingFiles(p, packDir))
        File(packDir, p.path).apply { parentFile.mkdirs(); writeText("x") }
        assertTrue(isPresetLocal(p, packDir))
    }

    @Test
    fun `raw url uses pinned commit and encodes path segments`() {
        assertEquals(
            "https://raw.githubusercontent.com/libretro/slang-shaders/a7f04a0698908015c6f9e3a3f446b3d17083269c/crt/crt-easymode.slangp",
            ShaderPack.rawUrlFor("a7f04a0698908015c6f9e3a3f446b3d17083269c", "crt/crt-easymode.slangp"),
        )
        // Paths with spaces (real catalog entries) are percent-encoded per segment.
        assertEquals(
            "https://raw.githubusercontent.com/libretro/slang-shaders/abc/ntsc/shaders/decoupled-guest/decoupled-guest-advanced-ntsc%203.slangp",
            ShaderPack.rawUrlFor("abc", "ntsc/shaders/decoupled-guest/decoupled-guest-advanced-ntsc 3.slangp"),
        )
        assertFalse(ShaderPack.rawUrlFor("abc", "x.slangp").contains("refs/heads"))
    }
}
