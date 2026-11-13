package app.gamenative.theme

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeTypographyHeroMotionTest {

    private fun translate(vararg css: String) =
        ThemeTranslator.translate(css.map { SourceSheet(CssParser.parse(it)) { null } }, SteamClassMap.EMPTY)

    private fun spec(vararg css: String): ThemeSpec = translate(*css).spec

    @Test
    fun fontSizesBecomeScalesAgainstSteamSizes() {
        val t = spec(
            """
            .gamepaddialog_FieldLabel_ab12c { font-size: 14px; }
            .DialogButton { font-size: 14px !important; }
            .BasicUI .appportrait_PortraitMessage_ab12c .appportrait_Message_ab12c { font-size: 22px !important; }
            .actionbuttonlegenditem_ActionButtonLabel_ab12c { font-size: 10px !important; }
            """.trimIndent(),
        ).typography!!
        assertEquals(0.88f, t.scale)
        assertEquals(1.22f, t.titleScale)
        assertEquals(0.83f, t.footerScale)
    }

    @Test
    fun fontScalesAreClampedAndRelativeSizesIgnored() {
        assertEquals(ThemeTypography.MAX_SCALE, spec(".gamepaddialog_FieldLabel_ab12c { font-size: 40px; }").typography!!.scale)
        assertEquals(ThemeTypography.MIN_SCALE, spec(".actionbuttonlegenditem_ActionButtonLabel_ab12c { font-size: 6px; }").typography!!.footerScale)
        assertNull(spec(".gamepaddialog_FieldLabel_ab12c { font-size: 1.1em; }").typography)
        assertNull(spec(".gamepaddialog_FieldLabel_ab12c { font-size: medium; }").typography)
        assertNull("focus grow", spec(".gamepaddialog_FieldLabel_ab12c.gpfocus { font-size: 20px; }").typography)
        assertNull("not a known text element", spec(".downloads_StartTime_ab12c { font-size: 8px; }").typography)
    }

    @Test
    fun weightsSpacingAndCase() {
        val t = spec(
            """
            .gamepadcontextmenu_Selected_ab12c { font-weight: bold; }
            .gamepadcontextmenu_Selected_ab12c.gamepadcontextmenu_Focused_ab12c { font-weight: 300; }
            .quickaccesscontrols_PanelSectionTitle_ab12c { font-weight: 800; text-transform: uppercase; letter-spacing: 1.6px; }
            .gamepadtabbedpage_Tab_ab12c { text-transform: none; }
            .actionbuttonlegenditem_ActionButtonLabel_ab12c { text-transform: uppercase; }
            """.trimIndent(),
        ).typography!!
        assertEquals(700, t.selectedWeight)
        assertEquals(800, t.headingWeight)
        assertEquals(0.1f, t.letterSpacingEm)
        assertEquals(true, t.headingUppercase)
        assertEquals(false, t.tabUppercase)
        assertEquals(true, t.footerUppercase)
    }

    @Test
    fun heroArtworkFromArtHeroRules() {
        val h = spec(
            """
            .gamepadhomerecentgames_RecentGamesBackgroundContainer_ab12c, .gamepadhomerecentgames_RecentGamesBackgroundImages_ab12c {
                -webkit-mask-image: linear-gradient(rgba(0, 0, 0, 1) 90%, rgba(0, 0, 0, 0) calc(100% - 5px));
            }
            .gamepadhomerecentgames_RecentGamesBackgroundImage_ab12c { opacity: 1; filter: brightness(100%) !important; }
            .gamepadhomerecentgames_RecentGamesBackground_ab12c { opacity: 1 !important; filter: none !important }
            """.trimIndent(),
        ).hero!!
        assertEquals(1f, h.opacity)
        assertEquals(0f, h.overlay)
        assertEquals(0f, h.blurDp)
        assertEquals(1f, h.saturation)
        assertEquals(0.1f, h.fadeBottom!!, 1e-4f)
    }

    @Test
    fun heroFilterFunctions() {
        val h = spec(".gamepadhomerecentgames_RecentGamesBackgroundImage_ab12c { opacity: 70%; filter: brightness(50%) blur(6px) grayscale(0.25); }").hero!!
        assertEquals(0.7f, h.opacity)
        assertEquals(1f, h.overlay)
        assertEquals(6f, h.blurDp)
        assertEquals(0.75f, h.saturation)
        assertNull("radial masks are not a bottom fade", spec(
            ".gamepadhomerecentgames_RecentGamesBackgroundImages_ab12c { -webkit-mask-image: radial-gradient(100% 90% at 50% 90%, black 80%, transparent 100%); }",
        ).hero)
    }

    @Test
    fun removedHomeArtworkMaskIsNoFade() {
        val h = spec(".gamepadhomerecentgames_RecentGamesBackgroundContainer_ab12c { -webkit-mask-image: none; }").hero!!
        assertEquals(ThemeHero(fadeBottom = 0f), h)
    }

    @Test
    fun slidingGradientAnimations() {
        val keyframes = "@keyframes gradient { 0% { background-position: 0% 50%; } 50% { background-position: 100% 50%; } 100% { background-position: 0% 50%; } }"
        val m = spec(
            """
            $keyframes
            @keyframes pop { 0% { transform: scale(1.1); } }
            .gamepaddialog_ToggleRail_ab12c::before { background: linear-gradient(90deg, red, blue); animation: gradient 20s ease infinite forwards; }
            .mainmenu_Menu_ab12c { background: linear-gradient(red, blue); animation: gradient 14000ms infinite; }
            [class="mainmenu_ItemOuter_ab12c"]:has(.gpfocus)::after { height: 4px; background: linear-gradient(90deg, red, blue);
                animation: gradient 12s infinite alternate, pop .1s ease-in forwards; }
            """.trimIndent(),
        ).motion!!
        assertEquals(20f, m.fillSlideSec)
        assertEquals(14f, m.panelSlideSec)
        assertEquals(12f, m.underlineSlideSec)
        assertNull("keyframes that do not move the background", spec(
            "@keyframes pop { 0% { transform: scale(1.1); } } .gamepaddialog_ToggleRail_ab12c::before { animation: pop 2s; }",
        ).motion)
        assertNull("animation none", spec("$keyframes .gamepaddialog_ToggleRail_ab12c::before { animation: none; }").motion)
    }

    @Test
    fun gnVariables() {
        val s = spec(
            """
            :root {
              --gn-text-size: 110%;
              --gn-title-size: 1.2;
              --gn-footer-text-size: 2;
              --gn-heading-weight: bold;
              --gn-selected-weight: 650;
              --gn-letter-spacing: 1.6px;
              --gn-heading-case: uppercase;
              --gn-tab-case: none;
              --gn-footer-case: "uppercase";
              --gn-hero-opacity: 80%;
              --gn-hero-blur: 4px;
              --gn-hero-saturation: 0.5;
              --gn-hero-overlay: 3;
              --gn-hero-fade: 0.2;
              --gn-fill-slide: 12s;
              --gn-panel-slide: 500ms;
              --gn-focus-underline-slide: 0;
            }
            """.trimIndent(),
        )
        assertEquals(
            ThemeTypography(1.1f, 1.2f, ThemeTypography.MAX_SCALE, 700, 700, 0.1f, headingUppercase = true, tabUppercase = false, footerUppercase = true),
            s.typography,
        )
        assertEquals(ThemeHero(0.8f, 4f, 0.5f, ThemeHero.MAX_OVERLAY, 0.2f), s.hero)
        assertEquals(ThemeMotion(12f, 0.5f, 0f), s.motion)
        assertEquals(listOf("typography", "hero", "motion"), s.setFields())
    }

    @Test
    fun writerAndJsonRoundTrip() {
        val s = ThemeSpec(
            typography = ThemeTypography(0.9f, 1.25f, 0.85f, 300, 900, -0.02f, headingUppercase = false, tabUppercase = true, footerUppercase = true),
            hero = ThemeHero(0.6f, 12f, 0.25f, 0.5f, 0.3f),
            motion = ThemeMotion(20f, 4f, 0f),
        )
        val back = ThemeTranslator.translate(listOf(SourceSheet(CssParser.parse(ThemeCssWriter.write(s))) { null }), SteamClassMap.EMPTY).spec
        assertEquals(s, back)
        assertEquals(s, ThemeSpecJson.decode(ThemeSpecJson.encode(s)))
        assertNull(ThemeSpecJson.decode(JsonObject(emptyMap())))
    }

    @Test
    fun overlayMergesPerValue() {
        val below = ThemeSpec(typography = ThemeTypography(scale = 0.9f, tabUppercase = true), motion = ThemeMotion(fillSlideSec = 4f))
        val top = ThemeSpec(typography = ThemeTypography(scale = 1.1f), hero = ThemeHero(opacity = 1f))
        val s = below.overlay(top)
        assertEquals(ThemeTypography(scale = 1.1f, tabUppercase = true), s.typography)
        assertEquals(ThemeHero(opacity = 1f), s.hero)
        assertEquals(ThemeMotion(fillSlideSec = 4f), s.motion)
    }
}
