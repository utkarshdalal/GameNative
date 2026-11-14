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
    fun nestedValuesRoundTrip() {
        val themes = tmp.newFolder("themes")
        val dir = File(themes, "T").apply { mkdirs() }
        val spec = nestedSpec()
        val css = ThemeCssWriter.write(spec)
        assertTrue(CommunityThemePackage.isNative(CssParser.parse(css)))
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
    fun shelfLayoutRoundTrips() {
        val spec = ThemeSpec(
            layout = ThemeLayout(
                libraryLayout = "row", shelfWideFirst = false, shelfRowPosition = "bottom", shelfTitleAbove = true,
                shelfHideTitle = false, shelfHideSubtitle = true,
            ),
        )
        val css = ThemeCssWriter.write(spec)
        assertTrue(css.contains("--gn-library-layout: row;"))
        assertTrue(css.contains("--gn-shelf-wide-first: 0;"))
        assertTrue(css.contains("--gn-shelf-row-position: bottom;"))
        assertTrue(css.contains("--gn-shelf-title-above: 1;"))
        assertTrue(css.contains("--gn-shelf-hide-title: 0;"))
        assertTrue(css.contains("--gn-shelf-hide-subtitle: 1;"))
        assertEquals(spec, ThemeTranslator.translate(listOf(SourceSheet(CssParser.parse(css)) { null }), SteamClassMap.EMPTY).spec)
        assertEquals(spec, ThemeSpecJson.decode(ThemeSpecJson.encode(spec)))
    }

    @Test
    fun emptySpecWritesAnEmptyBlock() {
        assertEquals(":root {\n}\n", ThemeCssWriter.write(ThemeSpec.EMPTY))
    }

    private fun nestedSpec(): ThemeSpec {
        val halo = ThemeGradient(180f, listOf(argb("#80fff0"), argb("#80fff000")), listOf(0f, 0.6f), ThemeRadial(circle = true, centerX = 1.4f, centerY = 0.7f))
        val sheen = ThemeGradient(
            180f,
            listOf(argb("#e8e8e8"), argb("#e8e8e8"), argb("#b3b3b3"), argb("#b3b3b3")),
            listOf(0f, 0.3f, 0.7f, 1f),
            ThemeRadial(circle = true, centerX = 0f, centerY = 1f, offsetXDp = 35f, offsetYDp = -155f),
        )
        val oval = ThemeGradient(180f, listOf(argb("#ff0000"), argb("#0000ff")), radial = ThemeRadial(extent = ThemeRadial.CLOSEST_SIDE, centerX = 0.25f))
        val sized = ThemeGradient(180f, listOf(argb("#ff0000"), argb("#0000ff")), radial = ThemeRadial(radiusXDp = 40f, radiusYDp = 20f))
        val rainbow = ThemeGradient(90f, listOf(argb("#40dce7"), argb("#ffe666"), argb("#ca1c67")))
        return ThemeSpec(
            primary = argb("#ccdb76"),
            header = ThemeBar(color = argb("#00000080"), fill = ThemeFill(listOf(halo, oval), argb("#141414")), blurDp = 4f, textColor = argb("#2de2e6"), heightScale = 0.75f),
            footer = ThemeBar(
                color = argb("#00000030"),
                borderColor = argb("#f6019d"),
                borderDp = 2f,
                glowColor = argb("#f6019dcc"),
                glowDp = 24f,
                blurDp = 5f,
                textColor = argb("#f6019d"),
                heightScale = 0.375f,
            ),
            textRoles = ThemeTextRoles(heading = argb("#2de2e6"), subtitle = argb("#f6019d"), glowColor = argb("#ff00aa"), glowDp = 8f),
            controls = ThemeControls(
                toggleOn = ThemeFill(listOf(rainbow)),
                toggleOff = argb("#292929"),
                thumbOn = argb("#ffffff"),
                thumbOff = argb("#363636"),
                sliderTrack = argb("#ffffff4d"),
                sliderThumb = argb("#ffffff"),
                buttonFill = ThemeFill(listOf(sized)),
                buttonFocusFill = ThemeFill(listOf(sheen)),
                onButtonFocus = argb("#121212"),
                buttonUnderlineColor = argb("#bababa"),
                buttonUnderlineDp = 2f,
            ),
            focusStyle = ThemeFocus(
                ringDp = 2f,
                ringColor = argb("#ffffff99"),
                fill = ThemeFill(color = argb("#0f0f0f")),
                onFill = argb("#f5f5f5"),
                underline = rainbow,
                underlineDp = 4f,
                blink = true,
            ),
            menuStyle = ThemeMenu(
                cornerRadiusDp = 24f,
                fill = ThemeFill(listOf(halo)),
                opacity = 0.6f,
                blurDp = 20f,
                borderColor = argb("#2de2e6"),
                borderDp = 2f,
                glowDp = 10f,
                focusFill = argb("#ffffff"),
                onFocus = argb("#000000"),
            ),
            panelFill = ThemeFill(listOf(halo, halo.copy(radial = ThemeRadial(circle = true, centerX = 0.2f, centerY = 0f))), argb("#000000")),
            layout = ThemeLayout(
                titleStrokeDp = 1f,
                titleStrokeColor = argb("#000000"),
                titleStrokeBlurDp = 2f,
                subtitleStrokeDp = 1f,
                subtitleStrokeColor = argb("#000000"),
                subtitleStrokeBlurDp = 2f,
                iconCornerRadiusDp = 0f,
            ),
        )
    }
}
