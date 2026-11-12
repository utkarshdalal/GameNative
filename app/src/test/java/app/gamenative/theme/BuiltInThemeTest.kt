package app.gamenative.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltInThemeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var themes: File
    private lateinit var dir: File

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    @Before
    fun setUp() {
        themes = tmp.newFolder("themes")
        dir = File(themes, BuiltInTheme.ID).apply { mkdirs() }
        File(dir, "theme.json").writeText(BuiltInTheme.themeJson)
        ThemeLoader.clearCache()
    }

    private fun spec(values: ThemeValues): ThemeSpec {
        val unit = ThemeUnit(dir, ThemeLoader.loadManifest(dir), values)
        return ThemeTranslator.translate(ThemeLoader.sheets(unit, ThemeAssets(themes)), SteamClassMap.EMPTY).spec
    }

    @Test
    fun manifestHasThePlainOptions() {
        val m = ThemeLoader.loadManifest(dir)
        assertEquals(BuiltInTheme.NAME, m.label)
        assertFalse(m.cssOnly)
        val colourPatches = listOf(
            "Background colour", "Panel colour", "Accent colour", "Text colour", "Secondary text colour", "Focus colour",
            "Bar colour", "Tile colour", "Button colour", "Toggle colour", "Menu colour",
        )
        assertEquals(
            colourPatches + listOf("Corner radius", "Background image", "Tile size", "Title alignment", "Hide badges", "Hide button hints"),
            m.patches.map { it.name },
        )
        for (name in colourPatches) {
            val p = m.patches.first { it.name == name }
            assertEquals("checkbox", p.type)
            assertEquals("No", p.default)
            val c = p.components.single()
            assertEquals(DeckyThemeManifest.COLOR_PICKER, c.type)
            assertEquals("Yes", c.on)
            assertTrue(c.cssVariable in ThemeTranslator.GN_VARIABLES)
        }
        assertEquals(listOf("Default", "0", "4", "8", "12", "16", "24"), m.patches.first { it.name == "Corner radius" }.optionNames)
        assertEquals(listOf("Small", "Default", "Large"), m.patches.first { it.name == "Tile size" }.optionNames)
        assertEquals(DeckyThemeManifest.IMAGE_PICKER, m.patches.first { it.name == "Background image" }.components.single().type)
    }

    @Test
    fun defaultsChangeNothing() {
        assertTrue(spec(ThemeValues()).isEmpty)
    }

    @Test
    fun colourPatchesSetTheirToken() {
        val tokens = mapOf(
            "Background colour" to { s: ThemeSpec -> s.background },
            "Panel colour" to { s: ThemeSpec -> s.surface },
            "Accent colour" to { s: ThemeSpec -> s.primary },
            "Text colour" to { s: ThemeSpec -> s.text },
            "Secondary text colour" to { s: ThemeSpec -> s.textMuted },
            "Focus colour" to { s: ThemeSpec -> s.focus },
            "Bar colour" to { s: ThemeSpec -> s.bar },
            "Tile colour" to { s: ThemeSpec -> s.card },
            "Button colour" to { s: ThemeSpec -> s.button },
            "Toggle colour" to { s: ThemeSpec -> s.toggle },
            "Menu colour" to { s: ThemeSpec -> s.menu },
        )
        for ((patch, read) in tokens) {
            val on = spec(ThemeValues(choices = mapOf(patch to "Yes"), colors = mapOf("$patch/Colour" to "#123456")))
            assertEquals(patch, argb("#123456"), read(on))
            val off = spec(ThemeValues(choices = mapOf(patch to "No"), colors = mapOf("$patch/Colour" to "#123456")))
            assertNull(patch, read(off))
        }
    }

    @Test
    fun dropdownsAndCheckboxesSetLayout() {
        val s = spec(
            ThemeValues(
                choices = mapOf(
                    "Corner radius" to "8",
                    "Tile size" to "Large",
                    "Title alignment" to "Centre",
                    "Hide badges" to "Yes",
                    "Hide button hints" to "Yes",
                ),
            ),
        )
        assertEquals(8f, s.cornerRadiusDp)
        assertEquals(
            ThemeLayout(cardScale = 1.25f, titleAlign = "center", hideBadges = true, hideFooterHints = true),
            s.layout,
        )
        assertEquals(0.8f, spec(ThemeValues(choices = mapOf("Tile size" to "Small"))).layout!!.cardScale)
        assertEquals("start", spec(ThemeValues(choices = mapOf("Title alignment" to "Left"))).layout!!.titleAlign)
        assertEquals(0f, spec(ThemeValues(choices = mapOf("Corner radius" to "0"))).cornerRadiusDp)
    }

    @Test
    fun backgroundImageNeedsAPickedImage() {
        assertNull(spec(ThemeValues(choices = mapOf("Background image" to "Yes"))).backgroundImage)
        val img = File(tmp.newFolder("state"), "bg.png").apply { writeBytes(byteArrayOf(1)) }
        val picked = spec(ThemeValues(choices = mapOf("Background image" to "Yes"), images = mapOf("Background image/Image" to img.absolutePath)))
        assertNull("images outside the themes folder never resolve", picked.backgroundImage)
        val inside = File(File(themes, ".state/${BuiltInTheme.ID}").apply { mkdirs() }, "bg.png").apply { writeBytes(byteArrayOf(1)) }
        val ok = spec(ThemeValues(choices = mapOf("Background image" to "Yes"), images = mapOf("Background image/Image" to inside.absolutePath)))
        assertEquals(inside.canonicalPath, ok.backgroundImage)
    }

    @Test
    fun storeVersions() {
        assertTrue(StoreVersion.isNewer("v1.2", "v1.1"))
        assertTrue(StoreVersion.isNewer("1.10", "1.9"))
        assertFalse(StoreVersion.isNewer("v1.1", "v1.1"))
        assertFalse(StoreVersion.isNewer("v1.0", "v1.1"))
        assertFalse(StoreVersion.isNewer("v1.0.0", "v1"))
        assertTrue(StoreVersion.isNewer("beta", "alpha"))
        assertTrue(StoreVersion.isNewer("v1.0", null))
    }
}
