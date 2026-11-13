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
        val colorPatches = listOf(
            "Background color", "Panel color", "Accent color", "Text color", "Secondary text color", "Focus color",
            "Bar color", "Tile color", "Button color", "Toggle color", "Menu color",
        )
        val names = m.patches.map { it.name }
        // option names are the keys saved values are stored under; a renamed option needs BuiltInTheme.migrateSaved
        assertTrue(
            names.containsAll(
                colorPatches + listOf("Corner radius", "Background image", "Tile size", "Title alignment", "Hide badges", "Hide button hints"),
            ),
        )
        assertEquals(names.size, names.toSet().size)
        for (name in colorPatches) {
            val p = m.patches.first { it.name == name }
            assertEquals("checkbox", p.type)
            assertEquals("No", p.default)
            val c = p.components.single()
            assertEquals("Color", c.name)
            assertEquals(DeckyThemeManifest.COLOR_PICKER, c.type)
            assertEquals("Yes", c.on)
            assertTrue(c.cssVariable in ThemeTranslator.GN_VARIABLES)
        }
        assertEquals(listOf("Default", "0", "4", "8", "12", "16", "24"), m.patches.first { it.name == "Corner radius" }.optionNames)
        assertTrue(m.patches.first { it.name == "Tile size" }.optionNames.containsAll(listOf("Small", "Default", "Large")))
        assertEquals("Default", m.patches.first { it.name == "Tile size" }.default)
        assertEquals(listOf("Default", "Left", "Center"), m.patches.first { it.name == "Title alignment" }.optionNames)
        val image = m.patches.first { it.name == "Background image" }.components.single()
        assertEquals(DeckyThemeManifest.IMAGE_PICKER, image.type)
        assertEquals("Image", image.name)
        assertEquals(DeckyThemeManifest.FONT_PICKER, m.patches.first { it.name == "Font" }.components.single().type)
    }

    @Test
    fun savedValuesUnderTheOldSpellingsMoveToTheCurrentNames() {
        val choices = linkedMapOf(
            "Background colour" to "Yes", "Title alignment" to "Centre", "Accent gradient direction" to "Centred glow",
            "Accent gradient middle colour" to "Yes", "Corner radius" to "8",
        )
        val colors = linkedMapOf("Background colour/Colour" to "#ff112233", "Accent gradient/Start" to "#ff445566")
        val images = linkedMapOf("Background image/Image" to "img-1.img")
        val fonts = linkedMapOf("Font/Font file" to "font.ttf")
        assertTrue(BuiltInTheme.migrateSaved(BuiltInTheme.ID, choices, colors, images, fonts))
        assertEquals(
            linkedMapOf(
                "Background color" to "Yes", "Title alignment" to "Center", "Accent gradient direction" to "Centered glow",
                "Accent gradient middle color" to "Yes", "Corner radius" to "8",
            ),
            choices,
        )
        assertEquals(linkedMapOf("Background color/Color" to "#ff112233", "Accent gradient/Start" to "#ff445566"), colors)
        assertEquals(mapOf("Background image/Image" to "img-1.img"), images)
        assertEquals(mapOf("Font/Font file" to "font.ttf"), fonts)

        // every moved key and value is one the current theme has
        val m = ThemeLoader.loadManifest(dir)
        val patches = m.patches.associateBy { it.name }
        for ((k, v) in choices) assertTrue("$k = $v", v in patches.getValue(k).optionNames || v in listOf("Yes", "No"))
        val keys = m.patches.flatMap { it.components }.map { it.key }
        assertTrue(keys.containsAll(colors.keys + images.keys + fonts.keys))

        // the second load finds nothing to move
        assertFalse(BuiltInTheme.migrateSaved(BuiltInTheme.ID, choices, colors, images, fonts))
    }

    @Test
    fun aValueAlreadySavedUnderTheNewNameWins() {
        val choices = linkedMapOf("Panel colour" to "No", "Panel color" to "Yes")
        val colors = linkedMapOf("Panel color/Color" to "#ff000001", "Panel colour/Colour" to "#ff000002")
        assertTrue(BuiltInTheme.migrateSaved(BuiltInTheme.ID, choices, colors, LinkedHashMap(), LinkedHashMap()))
        assertEquals(mapOf("Panel color" to "Yes"), choices)
        assertEquals(mapOf("Panel color/Color" to "#ff000001"), colors)
    }

    @Test
    fun otherThemesKeepTheirSavedNames() {
        val choices = linkedMapOf("Accent colour" to "Centre")
        val colors = linkedMapOf("Accent colour/Colour" to "#ff000001")
        assertFalse(BuiltInTheme.migrateSaved("Some theme", choices, colors, LinkedHashMap(), LinkedHashMap()))
        assertEquals(mapOf("Accent colour" to "Centre"), choices)
        assertEquals(mapOf("Accent colour/Colour" to "#ff000001"), colors)
    }

    @Test
    fun noCurrentNameIsChangedByTheRenameTable() {
        val m = ThemeLoader.loadManifest(dir)
        val names = m.patches.flatMap { p -> listOf(p.name) + p.optionNames + p.components.map { it.key } }
        for (n in names) assertEquals(n, BuiltInTheme.currentName(n))
    }

    @Test
    fun everyOptionIsInASection() {
        val sections = listOf("Colors", "Text", "Top bar", "Footer", "Controls", "Focus", "Menus and panels", "Background", "Layout and shape")
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
    fun gradientsFollowTheirDirectionAndColors() {
        val start = "Background gradient/Start"
        val end = "Background gradient/End"
        val colors = mapOf(start to "#ff0000", end to "#0000ff")
        fun grad(vararg choices: Pair<String, String>) =
            spec(ThemeValues(choices = mapOf("Background gradient" to "Yes") + choices, colors = colors)).backgroundGradient!!
        val down = grad()
        assertEquals(180f, down.angleDeg)
        assertEquals(listOf(argb("#ff0000"), argb("#0000ff")), down.colors)
        assertNull(down.radial)
        assertEquals(90f, grad("Background gradient direction" to "Left to right").angleDeg)
        assertEquals(135f, grad("Background gradient direction" to "Diagonal").angleDeg)
        val glow = grad("Background gradient direction" to "Centered glow").radial!!
        assertTrue(glow.circle)
        assertEquals(0.5f, glow.centerX)
        assertEquals(0.5f, glow.centerY)
        val three = spec(
            ThemeValues(
                choices = mapOf("Background gradient" to "Yes", "Background gradient middle color" to "Yes"),
                colors = colors + ("Background gradient middle color/Color" to "#00ff00"),
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
            ThemeValues(choices = mapOf("Footer line" to "Yes"), colors = mapOf("Footer line/Color" to "#ff000080")),
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
    fun colorPatchesSetTheirToken() {
        val tokens = mapOf(
            "Background color" to { s: ThemeSpec -> s.background },
            "Panel color" to { s: ThemeSpec -> s.surface },
            "Accent color" to { s: ThemeSpec -> s.primary },
            "Text color" to { s: ThemeSpec -> s.text },
            "Secondary text color" to { s: ThemeSpec -> s.textMuted },
            "Focus color" to { s: ThemeSpec -> s.focus },
            "Bar color" to { s: ThemeSpec -> s.bar },
            "Tile color" to { s: ThemeSpec -> s.card },
            "Button color" to { s: ThemeSpec -> s.button },
            "Toggle color" to { s: ThemeSpec -> s.toggle },
            "Menu color" to { s: ThemeSpec -> s.menu },
        )
        for ((patch, read) in tokens) {
            val on = spec(ThemeValues(choices = mapOf(patch to "Yes"), colors = mapOf("$patch/Color" to "#123456")))
            assertEquals(patch, argb("#123456"), read(on))
            val off = spec(ThemeValues(choices = mapOf(patch to "No"), colors = mapOf("$patch/Color" to "#123456")))
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
                    "Title alignment" to "Center",
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
        assertEquals("row", spec(ThemeValues(choices = mapOf("Library layout" to "Shelf"))).layout!!.libraryLayout)
        assertEquals("carousel", spec(ThemeValues(choices = mapOf("Library layout" to "Carousel"))).layout!!.libraryLayout)
        assertEquals(false, spec(ThemeValues(choices = mapOf("Shelf first tile" to "Same as the others"))).layout!!.shelfWideFirst)
        assertEquals(true, spec(ThemeValues(choices = mapOf("Shelf first tile" to "Wide"))).layout!!.shelfWideFirst)
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
