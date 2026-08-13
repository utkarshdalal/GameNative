package app.gamenative.shaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderCatalogTest {

    private val fixture = """
        {
          "source": {"repo": "libretro/slang-shaders", "ref": "master", "commit": "abc", "packBytes": 53113982},
          "families": [
            {"name": "crt", "count": 2},
            {"name": "root", "count": 1}
          ],
          "files": ["crt/easymode.slang", "stock.slang"],
          "presets": [
            {"path": "crt/easymode.slangp", "family": "crt", "passes": 3, "bytes": 1024, "deps": ["crt/easymode.slangp", "crt/shaders/easymode.slang"], "broken": false},
            {"path": "crt/guest/advanced.slangp", "family": "crt", "subfolder": "guest", "passes": 5, "bytes": 2048, "deps": ["crt/guest/advanced.slangp"]},
            {"path": "crt/guest/advanced-ntsc.slangp", "family": "crt", "subfolder": "guest", "passes": 2, "bytes": 512, "broken": true},
            {"path": "bilinear.slangp", "family": "root", "passes": 1, "bytes": 665}
          ]
        }
    """.trimIndent()

    private val catalog = ShaderCatalog.parse(fixture)

    @Test
    fun `parses source families and presets`() {
        assertEquals("libretro/slang-shaders", catalog.data.source.repo)
        assertEquals(2, catalog.families.size)
        assertEquals(4, catalog.presets.size)
        assertEquals(53113982L, catalog.data.source.packBytes)
        assertEquals(listOf("crt/easymode.slang", "stock.slang"), catalog.packFiles)
    }

    @Test
    fun `looks up preset by path`() {
        assertEquals("Easymode", friendlyName(catalog.preset("crt/easymode.slangp")!!.path))
        assertNull(catalog.preset("nope.slangp"))
    }

    @Test
    fun `subfolders are distinct and sorted`() {
        assertEquals(listOf("guest"), catalog.subfolders("crt"))
        assertTrue(catalog.subfolders("root").isEmpty())
    }

    @Test
    fun `presetsIn filters by family and subfolder`() {
        assertEquals(3, catalog.presetsIn("crt").size)
        assertEquals(2, catalog.presetsIn("crt", "guest").size)
        assertEquals(1, catalog.presetsIn("root").size)
    }

    @Test
    fun `search matches name family and path case-insensitively`() {
        assertEquals(1, catalog.search("easymode").size)
        assertEquals(1, catalog.search("EASY").size)
        assertEquals(3, catalog.search("crt").size) // family + paths
        assertTrue(catalog.search("").isEmpty())
        assertTrue(catalog.search("zzz").isEmpty())
    }

    @Test
    fun `paging slices without overrun`() {
        val items = catalog.presets
        assertEquals(4, catalog.page(items, 0, 12).size)
        assertEquals(2, catalog.page(items, 0, 2).size)
        assertEquals(2, catalog.page(items, 1, 2).size)
        assertTrue(catalog.page(items, 5, 2).isEmpty())
    }

    @Test
    fun `presets carry their dependency closure`() {
        val easy = catalog.preset("crt/easymode.slangp")!!
        assertEquals(listOf("crt/easymode.slangp", "crt/shaders/easymode.slang"), easy.deps)
        // Missing deps default to empty (old catalogs / presets without deps key).
        assertEquals(emptyList<String>(), catalog.preset("bilinear.slangp")!!.deps)
    }

    @Test
    fun `broken presets are flagged unusable`() {
        val broken = catalog.preset("crt/guest/advanced-ntsc.slangp")!!
        assertTrue(broken.broken)
        assertFalse(catalog.isUsable(broken))
        assertTrue(catalog.isUsable(catalog.preset("bilinear.slangp")!!))
    }

    @Test
    fun `friendlyName title-cases separators and strips extension`() {
        assertEquals("Easymode", friendlyName("crt/easymode.slangp"))
        assertEquals("Advanced Ntsc", friendlyName("crt/guest/advanced-ntsc.slangp"))
        assertEquals("Crt Sony Megatron V2", friendlyName("crt-sony-megatron-v2.slangp"))
    }
}
