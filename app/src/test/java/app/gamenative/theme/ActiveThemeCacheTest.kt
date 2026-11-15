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

class ActiveThemeCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var themes: File
    private lateinit var file: File

    @Before
    fun setUp() {
        themes = tmp.newFolder("themes")
        File(themes, BuiltInTheme.ID).apply { mkdirs() }.let { File(it, "theme.json").writeText(BuiltInTheme.themeJson) }
        file = File(themes, ".active.json")
        ThemeLoader.clearCache()
    }

    private fun everyField(): ThemeSpec {
        val spec = builtInWithEveryOption(themes)
        return spec.copy(layout = spec.layout!!.overlay(multiRuleLayout(File(themes, "css-only")))).also { assertEveryFieldSet(it, "ThemeSpec") }
    }

    @Test
    fun everyFieldRoundTrips() {
        val saved = ActiveThemeCache.Saved(everyField(), 3)
        ActiveThemeCache.write(file, saved, "24:100")
        assertEquals(saved, ActiveThemeCache.read(file, "24:100"))
        assertFalse(File(themes, ".active.json.tmp").exists())
    }

    @Test
    fun radialGradientsRoundTrip() {
        val halo = ThemeGradient(
            180f,
            listOf(0xFF80FFF0.toInt(), 0x0080FFF0),
            listOf(0f, 0.6f),
            ThemeRadial(circle = false, centerX = 1.4f, centerY = 0.7f, offsetXDp = -3f, offsetYDp = 35f, extent = ThemeRadial.CLOSEST_SIDE, radiusXDp = 120f, radiusYDp = 40f),
        )
        val spec = ThemeSpec(accentGradient = halo, panelFill = ThemeFill(listOf(halo, halo.copy(radial = null)), 0xFF000000.toInt()))
        val saved = ActiveThemeCache.Saved(spec, 1)
        assertEquals(saved, ActiveThemeCache.decode(ActiveThemeCache.encode(saved, "k"), "k"))
    }

    @Test
    fun emptySpecIsKept() {
        val saved = ActiveThemeCache.Saved(ThemeSpec.EMPTY, 1)
        assertEquals(saved, ActiveThemeCache.decode(ActiveThemeCache.encode(saved, "k"), "k"))
    }

    @Test
    fun normalizedSpecsSurviveARestartUnchanged() {
        val raw = everyField().copy(layout = ThemeLayout(), header = ThemeBar(), controls = ThemeControls(toggleOn = ThemeFill()))
        val shown = ActiveThemeCache.normalize(raw)
        assertNull(shown.layout)
        assertNull(shown.header)
        assertNull(shown.controls)
        assertEquals(shown, ActiveThemeCache.normalize(shown))
        val saved = ActiveThemeCache.Saved(shown, 1)
        assertEquals(saved, ActiveThemeCache.decode(ActiveThemeCache.encode(saved, "k"), "k"))
    }

    @Test
    fun anotherAppBuildTranslatesAgain() {
        ActiveThemeCache.write(file, ActiveThemeCache.Saved(everyField(), 1), "24:100")
        assertNull(ActiveThemeCache.read(file, "24:101"))
        assertNull(ActiveThemeCache.read(file, "25:100"))
        val text = file.readText()
        assertNull(ActiveThemeCache.decode(text.replace("\"format\":${ActiveThemeCache.FORMAT}", "\"format\":${ActiveThemeCache.FORMAT + 1}"), "24:100"))
    }

    @Test
    fun aMissingFontOrImageTranslatesAgain() {
        val spec = everyField()
        ActiveThemeCache.write(file, ActiveThemeCache.Saved(spec, 1), "k")
        File(spec.fontFile!!).delete()
        assertNull(ActiveThemeCache.read(file, "k"))
        val noFont = spec.copy(fontFile = null)
        ActiveThemeCache.write(file, ActiveThemeCache.Saved(noFont, 1), "k")
        assertEquals(noFont, ActiveThemeCache.read(file, "k")!!.spec)
        File(spec.backgroundImage!!).delete()
        assertNull(ActiveThemeCache.read(file, "k"))
    }

    @Test
    fun aDamagedFileTranslatesAgain() {
        val good = ActiveThemeCache.encode(ActiveThemeCache.Saved(ThemeSpec(background = 0xFF102030.toInt()), 1), "k")
        for (text in listOf("", "{", "[]", "null", good.dropLast(5), """{"format":1,"key":"k","enabled":1,"spec":"x"}""", """{"format":1,"key":"k","spec":{}}""", """{"format":"1","key":"k","enabled":1,"spec":{}}""")) {
            assertNull(text, ActiveThemeCache.decode(text, "k"))
        }
        assertNull(ActiveThemeCache.read(File(themes, "missing.json"), "k"))
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0))
        assertNull(ActiveThemeCache.read(file, "k"))
        assertTrue(ActiveThemeCache.decode(good, "k") != null)
    }

    @Test
    fun aNewerSaveReplacesTheFile() {
        ActiveThemeCache.write(file, ActiveThemeCache.Saved(ThemeSpec(background = 1), 1), "k")
        ActiveThemeCache.write(file, ActiveThemeCache.Saved(ThemeSpec(background = 2), 2), "k")
        assertEquals(ActiveThemeCache.Saved(ThemeSpec(background = 2), 2), ActiveThemeCache.read(file, "k"))
    }

    @Test
    fun previewsNeverNameLocalFiles() {
        val spec = ThemeSpec(fontFile = "/data/x/font.ttf", backgroundImage = "/data/x/bg.png", text = 0xFFFFFFFF.toInt())
        val preview = ThemeSpecJson.encode(spec)
        assertFalse(preview.containsKey("fontFile"))
        assertEquals(ThemeSpec(text = 0xFFFFFFFF.toInt()), ThemeSpecJson.decode(ThemeSpecJson.encode(spec, files = true)))
    }
}
