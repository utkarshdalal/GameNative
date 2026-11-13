package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeShelfFidelityTranslationTest {

    private fun translate(vararg css: String) =
        ThemeTranslator.translate(css.map { SourceSheet(CssParser.parse(it)) { null } }, SteamClassMap.EMPTY).spec

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    @Test
    fun selectedTabGivesTheTabFillAndText() {
        val s = translate(
            """
            .gamepadtabbedpage_Tab_3eEbS.gamepadtabbedpage_Selected_3Gp1b { background: #bcdceb; color: #000; }
            .gamepadtabbedpage_Tab_3eEbS.gamepadtabbedpage_Selected_3Gp1b.gpfocus { background: #ff0000; color: #fff; }
            """.trimIndent(),
        )
        assertEquals(argb("#bcdceb"), s.controls!!.tabFill)
        assertEquals(argb("#000000"), s.controls!!.onTab)
    }

    @Test
    fun aSelectedTabOverATranslucentLayerIsTheColorItPaints() {
        val s = translate(
            """
            :root { --dark: linear-gradient(0deg, #000C, #000C), #1bff80; --accent: #1bff80; }
            .gamepadtabbedpage_Tab_3eEbS.gamepadtabbedpage_Selected_3Gp1b { background: var(--dark); }
            .gamepadtabbedpage_TabTitle_1nq0i { color: var(--accent); }
            .Primary { background: var(--dark); }
            """.trimIndent(),
        )
        assertEquals(argb("#053219"), s.controls!!.tabFill)
        assertEquals(listOf(argb("#053219"), argb("#053219")), s.accentGradient!!.colors)
    }

    @Test
    fun tabTitleCaseIsTheTabCase() {
        val s = translate(".gamepadtabbedpage_TabTitle_1nq0i { text-transform: uppercase; }")
        assertEquals(true, s.typography!!.tabUppercase)
        assertNull(s.typography!!.allUppercase)
    }

    @Test
    fun upperCasingManyKindsOfTextIsUpperCasingAllOfIt() {
        val s = translate(
            """
            .gamepadtabbedpage_TabTitle_1nq0i { text-transform: uppercase; }
            .gamepaddialog_FieldLabel_3b0U- { text-transform: uppercase; }
            .DialogButton { text-transform: uppercase; }
            .gamepadpagedsettings_PagedSettingsDialog_PageListItem_1ix7r { text-transform: uppercase; }
            """.trimIndent(),
        )
        assertEquals(true, s.typography!!.allUppercase)
        assertEquals(true, translate("* { text-transform: uppercase; }").typography!!.allUppercase)
    }

    @Test
    fun anInvertedFooterGlyphIsADarkGlyph() {
        val s = translate(".footer_BasicFooter .actionbuttonlegenditem_ActionButtonGlyph { filter: invert(1); }")
        assertEquals(true, s.layout!!.footerGlyphInvert)
        assertNull(translate(".footer_BasicFooter .actionbuttonlegenditem_ActionButtonGlyph { filter: blur(2px); }").layout)
    }

    @Test
    fun variablesInTheSettingsPageGradientResolve() {
        val s = translate(
            """
            :root { --bg: white; --hl: white; }
            :root { --bg: linear-gradient(135deg, var(--bg-a), var(--bg-b)); --hl: linear-gradient(135deg, var(--hl-a), var(--hl-b)); }
            :root { --bg-a: #bd5ece; --bg-b: #2ab1ac; --hl-a: #bd5ece; --hl-b: #2ab1ac; }
            ._33vqr13-jdnjTkKKTh414f { background: var(--bg); }
            .gamepadpagedsettings_PagedSettingsDialog_3fHGN { background: var(--bg); }
            .gamepadpagedsettings_PagedSettingsDialog_PageListItem_1ix7r:focus { background: linear-gradient(90deg, #ffffff66, rgba(0, 0, 0, 0)) !important; }
            .gamepaddialog_Field_S-_La.gamepaddialog_HighlightOnFocus_wE4V6.gpfocus { background: var(--hl); }
            """.trimIndent(),
        )
        assertEquals(listOf(argb("#bd5ece"), argb("#2ab1ac")), s.backgroundGradient!!.colors)
        assertEquals(135f, s.backgroundGradient!!.angleDeg)
        assertEquals(listOf(argb("#bd5ece"), argb("#2ab1ac")), s.focusStyle!!.fill!!.layers.single().colors)
    }

    @Test
    fun theBodyFontBeatsAHeadingFontAndTheRegularFaceIsUsed() {
        val css = """
            @font-face { font-family: 'Space Mono'; font-style: italic; font-weight: 400; src: url(https://f/mono-i.ttf); }
            @font-face { font-family: 'Space Mono'; font-style: normal; font-weight: 400; src: url(https://f/mono.ttf); }
            @font-face { font-family: 'DM Sans'; font-style: italic; font-weight: 400; src: url(https://f/sans-i.ttf); }
            @font-face { font-family: 'DM Sans'; font-style: normal; font-weight: 700; src: url(https://f/sans-700.ttf); }
            @font-face { font-family: 'DM Sans'; font-style: normal; font-weight: 400; src: url(https://f/sans.ttf); }
            .gamepadtabbedpage_TabTitle_1nq0i, .DialogHeader, .appportrait_Message_3CRLA { font-family: "Space Mono"; }
            .gamepaddialog_FieldLabel_3b0U-, .gamepaddialog_FieldDescription_2OJfk { font-family: "Space Mono"; }
            * { font-family: 'DM Sans', monospace !important; }
        """.trimIndent()
        val s = ThemeTranslator.translate(listOf(SourceSheet(CssParser.parse(css), { null }, { it })), SteamClassMap.EMPTY).spec
        assertEquals("https://f/sans.ttf", s.fontFile)
    }

    @Test
    fun faceOrderPrefersUprightRegularLatin() {
        fun face(style: String?, weight: String?, range: String? = null) = listOfNotNull(
            style?.let { CssDecl("font-style", it, false) },
            weight?.let { CssDecl("font-weight", it, false) },
            range?.let { CssDecl("unicode-range", it, false) },
        )
        val italic = face("italic", "400")
        val bold = face("normal", "700")
        val cyrillic = face("normal", "400", "U+0400-045F")
        val latin = face("normal", "400", "U+0000-00FF, U+0131")
        val variable = face(null, "100 900")
        assertEquals(listOf(latin, variable, cyrillic, bold, italic), listOf(italic, bold, cyrillic, latin, variable).sortedWith(RemoteFonts.FACE_ORDER))
    }

    @Test
    fun newValuesRoundTripThroughTheirVariables() {
        val spec = ThemeSpec(
            controls = ThemeControls(tabFill = argb("#bcdceb"), onTab = argb("#000000")),
            layout = ThemeLayout(footerGlyphInvert = true),
            typography = ThemeTypography(allUppercase = true),
        )
        val back = translate(ThemeCssWriter.write(spec))
        assertEquals(spec, back)
        assertEquals(spec, ThemeSpecJson.decode(ThemeSpecJson.encode(spec)))
        assertTrue(ThemeCssWriter.write(spec).contains("--gn-on-tab"))
    }
}
