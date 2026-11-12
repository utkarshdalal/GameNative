package app.gamenative.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeCssWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    private fun readBack(css: String, dir: File, themes: File): ThemeSpec {
        val assets = ThemeAssets(themes)
        return ThemeTranslator.translate(listOf(SourceSheet(CssParser.parse(css)) { assets.resolve(it, dir, "") }), SteamClassMap.EMPTY).spec
    }

    @Test
    fun everyTokenAndLayoutValueRoundTrips() {
        val themes = tmp.newFolder("themes")
        val dir = File(themes, "T").apply { mkdirs() }
        val font = File(dir, "font.ttf").apply { writeText("f") }
        val image = File(dir, "background.png").apply { writeText("i") }
        val spec = ThemeSpec(
            background = argb("#10141c"),
            surface = argb("#18203080"),
            surfaceVariant = argb("#202a3c"),
            primary = argb("#4fc3f7"),
            onPrimary = argb("#000000"),
            text = argb("#e8edf5"),
            textMuted = argb("#9aa6b8cc"),
            outline = argb("#33445566"),
            focus = argb("#ff8800"),
            bar = argb("#0b0f17e6"),
            cornerRadiusDp = 12.5f,
            fontFile = font.canonicalPath,
            backgroundImage = image.canonicalPath,
            backgroundGradient = ThemeGradient(135f, listOf(argb("#000000"), argb("#22334480"), argb("#ffffff")), listOf(0f, 0.3f, 1f)),
            panelOpacity = 0.85f,
            blurDp = 6f,
            card = argb("#222c40"),
            button = argb("#2a3448"),
            onButton = argb("#f0f0f0"),
            toggle = argb("#00c853"),
            menu = argb("#182030f2"),
            onMenu = argb("#dddddd"),
            accentGradient = ThemeGradient(22.5f, listOf(argb("#ff0000"), argb("#0000ff"))),
            focusGlowDp = 9f,
            layout = ThemeLayout(
                cardScale = 1.25f,
                libraryLayout = "carousel",
                titleAlign = "center",
                titleStrokeDp = 1.5f,
                titleStrokeColor = argb("#101010"),
                hideBadges = true,
                hideFooterHints = false,
                heroScale = 0.75f,
                cardTiltDeg = -7.5f,
                cardCornerRadiusDp = 3f,
            ),
        )
        val names = mapOf(font.canonicalPath to "font.ttf", image.canonicalPath to "background.png")
        val css = ThemeCssWriter.write(spec) { names[it] }
        val sheet = CssParser.parse(css)
        assertEquals(1, sheet.rules.size)
        assertTrue(CommunityThemePackage.isNative(sheet))
        assertEquals(spec, readBack(css, dir, themes))
    }

    @Test
    fun partialValuesRoundTrip() {
        val themes = tmp.newFolder("themes")
        val dir = File(themes, "T").apply { mkdirs() }
        val spec = ThemeSpec(
            primary = argb("#123456"),
            panelOpacity = 0f,
            backgroundGradient = ThemeGradient(0f, listOf(argb("#000000"), argb("#ffffff"))),
            layout = ThemeLayout(titleStrokeDp = 0f, hideBadges = false, hideFooterHints = true, libraryLayout = "list", titleAlign = "start"),
        )
        assertEquals(spec, readBack(ThemeCssWriter.write(spec), dir, themes))
    }

    @Test
    fun filesWithoutAUrlAreLeftOut() {
        val css = ThemeCssWriter.write(ThemeSpec(primary = argb("#123456"), fontFile = "/x/font.ttf", backgroundImage = "/x/bg.png"))
        assertTrue(!css.contains("url("))
        assertTrue(css.contains("--gn-primary: #123456;"))
    }

    @Test
    fun emptySpecWritesAnEmptyBlock() {
        assertEquals(":root {\n}\n", ThemeCssWriter.write(ThemeSpec.EMPTY))
    }
}
