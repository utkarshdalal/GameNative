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
        val names = m.patches.map { it.name }
        // option names are the keys saved values are stored under; the first release's options must stay
        assertTrue(
            names.containsAll(
                colourPatches + listOf("Corner radius", "Background image", "Tile size", "Title alignment", "Hide badges", "Hide button hints"),
            ),
        )
        assertEquals(names.size, names.toSet().size)
        for (name in colourPatches) {
            val p = m.patches.first { it.name == name }
            assertEquals("checkbox", p.type)
            assertEquals("No", p.default)
            val c = p.components.single()
            assertEquals("Colour", c.name)
            assertEquals(DeckyThemeManifest.COLOR_PICKER, c.type)
            assertEquals("Yes", c.on)
            assertTrue(c.cssVariable in ThemeTranslator.GN_VARIABLES)
        }
        assertEquals(listOf("Default", "0", "4", "8", "12", "16", "24"), m.patches.first { it.name == "Corner radius" }.optionNames)
        assertTrue(m.patches.first { it.name == "Tile size" }.optionNames.containsAll(listOf("Small", "Default", "Large")))
        assertEquals("Default", m.patches.first { it.name == "Tile size" }.default)
        assertEquals(listOf("Default", "Left", "Centre"), m.patches.first { it.name == "Title alignment" }.optionNames)
        val image = m.patches.first { it.name == "Background image" }.components.single()
        assertEquals(DeckyThemeManifest.IMAGE_PICKER, image.type)
        assertEquals("Image", image.name)
        assertEquals(DeckyThemeManifest.FONT_PICKER, m.patches.first { it.name == "Font" }.components.single().type)
    }

    @Test
    fun everyOptionIsInASection() {
        val sections = listOf("Colours", "Text", "Top bar", "Footer", "Controls", "Focus", "Menus and panels", "Background", "Layout and shape")
        val m = ThemeLoader.loadManifest(dir)
        val seen = m.patches.map { BuiltInTheme.sectionOf(it.name) }
        assertTrue(seen.all { it in sections })
        assertEquals(sections, seen.distinct())
        assertNull(BuiltInTheme.sectionOf("No such option"))
    }

    /** Every option at a non-default value: every value a theme can set must come out set, so a new value without an option fails here. */
    @Test
    fun everyValueHasAnOption() {
        val s = builtInWithEveryOption(themes)
        assertEveryFieldSet(s, "ThemeSpec")
        assertEquals(File(themes, ".state/${BuiltInTheme.ID}/font.ttf").canonicalPath, File(s.fontFile!!).canonicalPath)
        assertEquals(argb("#12345680"), s.header!!.color)
        assertEquals(3, s.backgroundGradient!!.colors.size)
        assertEquals(2, s.panelFill!!.layers.size)
    }

    @Test
    fun gradientsFollowTheirDirectionAndColours() {
        val start = "Background gradient/Start"
        val end = "Background gradient/End"
        val colours = mapOf(start to "#ff0000", end to "#0000ff")
        fun grad(vararg choices: Pair<String, String>) =
            spec(ThemeValues(choices = mapOf("Background gradient" to "Yes") + choices, colors = colours)).backgroundGradient!!
        val down = grad()
        assertEquals(180f, down.angleDeg)
        assertEquals(listOf(argb("#ff0000"), argb("#0000ff")), down.colors)
        assertNull(down.radial)
        assertEquals(90f, grad("Background gradient direction" to "Left to right").angleDeg)
        assertEquals(135f, grad("Background gradient direction" to "Diagonal").angleDeg)
        val glow = grad("Background gradient direction" to "Centred glow").radial!!
        assertTrue(glow.circle)
        assertEquals(0.5f, glow.centerX)
        assertEquals(0.5f, glow.centerY)
        val three = spec(
            ThemeValues(
                choices = mapOf("Background gradient" to "Yes", "Background gradient middle colour" to "Yes"),
                colors = colours + ("Background gradient middle colour/Colour" to "#00ff00"),
            ),
        ).backgroundGradient!!
        assertEquals(listOf(argb("#ff0000"), argb("#00ff00"), argb("#0000ff")), three.colors)
        assertNull("the direction alone sets nothing", spec(ThemeValues(choices = mapOf("Background gradient direction" to "Diagonal"))).backgroundGradient)

        val bar = spec(
            ThemeValues(
                choices = mapOf("Top bar gradient" to "Yes", "Top bar gradient direction" to "Glow over gradient"),
                colors = mapOf("Top bar gradient/Start" to "#ff0000", "Top bar gradient/End" to "#0000ff"),
            ),
        ).header!!.fill!!
        assertEquals(2, bar.layers.size)
        assertEquals(argb("#ff0000"), bar.layers[0].colors[0])
        assertEquals(0, bar.layers[0].colors[1] ushr 24)
    }

    @Test
    fun linesAndSizesCompose() {
        val on = spec(
            ThemeValues(choices = mapOf("Footer line" to "Yes"), colors = mapOf("Footer line/Colour" to "#ff000080")),
        ).footer!!
        assertEquals(1f, on.borderDp)
        assertEquals(argb("#ff000080"), on.borderColor)
        val wide = spec(ThemeValues(choices = mapOf("Footer line" to "Yes", "Footer line width" to "3 px"))).footer!!
        assertEquals(3f, wide.borderDp)
        assertNull("a size without its switch sets nothing", spec(ThemeValues(choices = mapOf("Footer line width" to "3 px"))).footer)
        val stroke = spec(ThemeValues(choices = mapOf("Title outline" to "Yes", "Title outline style" to "Soft shadow"))).layout!!
        assertEquals(2f, stroke.titleStrokeDp)
        assertEquals(4f, stroke.titleStrokeBlurDp)
        assertEquals(argb("#000000"), stroke.titleStrokeColor)
    }

    @Test
    fun pickedFontIsUsed() {
        assertNull(spec(ThemeValues(choices = mapOf("Font" to "Yes"))).fontFile)
        val state = File(themes, ".state/${BuiltInTheme.ID}").apply { mkdirs() }
        val ttf = File(state, "font-1.ttf").apply { writeBytes(byteArrayOf(0, 1, 0, 0)) }
        val values = ThemeValues(choices = mapOf("Font" to "Yes"), fonts = mapOf("Font/Font file" to ttf.absolutePath))
        assertEquals(ttf.canonicalPath, spec(values).fontFile)
        assertNull(spec(values.copy(choices = emptyMap())).fontFile)
        val outside = File(tmp.newFolder("elsewhere"), "f.ttf").apply { writeBytes(byteArrayOf(0, 1, 0, 0)) }
        assertNull(spec(values.copy(fonts = mapOf("Font/Font file" to outside.absolutePath))).fontFile)
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
