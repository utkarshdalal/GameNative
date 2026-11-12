package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeLayoutTranslationTest {

    private fun translate(vararg css: String) =
        ThemeTranslator.translate(css.map { SourceSheet(CssParser.parse(it)) { null } }, SteamClassMap.EMPTY)

    private fun layout(vararg css: String): ThemeLayout? = translate(*css).spec.layout

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    @Test
    fun homeRowScaleSetsCardScale() {
        val r = translate(":root { --s: 0.7; } .basicgamecarousel_BasicGameCarousel_ab12c { transform: scale(var(--s)); width: calc(100vw / var(--s)); }")
        assertEquals(0.7f, r.spec.layout!!.cardScale)
        assertEquals(1, r.appliedRules)
        assertEquals(1.25f, layout(".appportrait_LibraryItemBox_ab12c { zoom: 125%; }")!!.cardScale)
    }

    @Test
    fun cardScaleIsClamped() {
        assertEquals(1.5f, layout(".appportrait_LibraryItemBox_ab12c { transform: scale(3); }")!!.cardScale)
        assertEquals(0.5f, layout(".appportrait_LibraryItemBox_ab12c { transform: scale(0.2); }")!!.cardScale)
    }

    @Test
    fun focusPopsAndSmallScalesAreNotTileSizes() {
        assertNull(layout(".appportrait_LibraryItemBox_ab12c.gpfocus { transform: scale(1.3); }"))
        assertNull(layout(".gpfocuswithin .appportrait_LibraryItemBox_ab12c { transform: scale(1.3); }"))
        assertNull(layout(".appportrait_LibraryItemBox_ab12c { transform: scale(1.02); }"))
        assertNull("one focused selector makes the rule a focus effect",
            layout(".appportrait_LibraryItemBox_ab12c.gpfocus, .appportrait_LibraryItemBox_ab12c[tabindex] { transform: scale(1.3); }"))
        assertNull("not a tile", layout(".gamepadui_BasicHome_ab12c { transform: scale(0.7); }"))
    }

    @Test
    fun tallHomeRowMeansCarousel() {
        assertEquals("carousel", layout(".gamepadhomerecentgames_RecentGamesInnerContainer_ab12c { height: calc(100vh - 80px - 14px - 58px); }")!!.libraryLayout)
        assertEquals("carousel", layout(".basicgamecarousel_BasicGameCarousel_ab12c { height: 100vh !important; }")!!.libraryLayout)
        assertNull(layout(".gamepadhomerecentgames_RecentGamesInnerContainer_ab12c { height: 300px; }"))
        assertNull(layout(".gamepadhomerecentgames_RecentGamesInnerContainer_ab12c { height: 90%; }"))
    }

    @Test
    fun centredTitles() {
        assertEquals("center", layout(".appportrait_PortraitMessage_ab12c .appportrait_Message_ab12c { width: fit-content; margin: 0 auto 0 auto; }")!!.titleAlign)
        assertEquals("center", layout(".basicgamecarousel_CarouselGameLabel_ab12c { text-align: center; }")!!.titleAlign)
        assertEquals("center", layout(".marquee_Content_ab12c { display: flex; justify-content: center; }")!!.titleAlign)
        assertNull(layout(".marquee_Content_ab12c { display: flex; flex-direction: column; justify-content: center; }"))
        assertNull(layout(".appportrait_Message_ab12c { margin: 0 auto 0 0; }"))
        assertNull("not a title", layout(".gamepaddialog_FieldLabel_ab12c { text-align: center; }"))
    }

    @Test
    fun titleStrokeFromTextStrokeOrOutlineShadow() {
        val shadow = layout(".marquee_Content_ab12c { text-shadow: -1px 1px 2px #000, 1px 1px 2px #000, 1px -1px 0 #000, -1px -1px 0 #000; }")!!
        assertEquals(1f, shadow.titleStrokeDp)
        assertEquals(argb("#000000"), shadow.titleStrokeColor)
        val stroke = layout(".appportrait_Message_ab12c { -webkit-text-stroke: 2px #ff0000; }")!!
        assertEquals(2f, stroke.titleStrokeDp)
        assertEquals(argb("#ff0000"), stroke.titleStrokeColor)
        val split = layout(".appportrait_Message_ab12c { -webkit-text-stroke-width: 3px; -webkit-text-stroke-color: #00ff00; }")!!
        assertEquals(3f, split.titleStrokeDp)
        assertEquals(argb("#00ff00"), split.titleStrokeColor)
        assertNull("a soft shadow is not a stroke", layout(".marquee_Content_ab12c { text-shadow: 0 2px 8px #000; }"))
        assertNull("mixed colours", layout(".marquee_Content_ab12c { text-shadow: -1px 1px 0 #000, 1px 1px 0 #fff, 1px -1px 0 #000, -1px -1px 0 #000; }"))
    }

    @Test
    fun hiddenBadges() {
        val r = translate(
            """
            .BasicUI .appportrait_LibraryItemBox_ab12c .appportrait_SteamDeckCompatIcon_ab12c { display: none; }
            .BasicUI .gamecapsule_InLibrary_ab12c { opacity: 0; }
            """.trimIndent(),
        )
        assertEquals(true, r.spec.layout!!.hideBadges)
        assertEquals(2, r.appliedRules)
    }

    @Test
    fun badgesShownOnFocusAreNotHidden() {
        assertNull(
            layout(
                """
                .gamecapsule_InLibrary_ab12c { opacity: 0; }
                .gamecapsule_GameCapsule_ab12c.gpfocus .gamecapsule_InLibrary_ab12c { opacity: 1; }
                """.trimIndent(),
            ),
        )
        assertEquals(
            "display: none wins over a focus opacity",
            true,
            layout(
                """
                .gamecapsule_InLibrary_ab12c { display: none; }
                .gamecapsule_GameCapsule_ab12c.gpfocus .gamecapsule_InLibrary_ab12c { opacity: 1; }
                """.trimIndent(),
            )!!.hideBadges,
        )
        assertNull("restyled, not hidden", layout(".gamecapsule_InLibrary_ab12c { opacity: 1; border-radius: 16px; }"))
    }

    @Test
    fun hiddenFooter() {
        assertEquals(true, layout(".footer_BasicFooter_ab12c { display: none !important; }")!!.hideFooterHints)
        assertEquals(true, layout(".footer_BasicFooter_ab12c { visibility: hidden; }")!!.hideFooterHints)
        assertNull("smaller, not hidden", layout(".footer_BasicFooter_ab12c { min-height: 15px; height: 15px; }"))
        assertNull(layout(".footer_BasicFooter_ab12c { opacity: 0; } .footer_BasicFooter_ab12c { opacity: 1; }"))
    }

    @Test
    fun heroHeight() {
        assertEquals(2f, layout(".sharedappdetailsheader_TopCapsule_ab12c { height: 672px; }")!!.heroScale)
        assertEquals(1.5f, layout(":root { --h: 504px; } .BasicUI .sharedappdetailsheader_TopCapsule_ab12c { height: var(--h); }")!!.heroScale)
        assertEquals(2f, layout(".sharedappdetailsheader_HeaderBackgroundImage_ab12c { height: calc(100vh - 40px); }")!!.heroScale)
        assertEquals(0.5f, layout(".sharedappdetailsheader_TopCapsule_ab12c { height: 40px; }")!!.heroScale)
        assertNull(layout(".sharedappdetailsheader_TopCapsule_ab12c { height: 50%; }"))
        assertNull("home hero is not the game page hero", layout(".gamepadhomerecentgames_RecentGamesBackground_ab12c { height: 50vh; }"))
    }

    @Test
    fun tiltedTiles() {
        val r = translate(
            """
            :root { --tilt: -5deg; }
            .gamepadhome_RecentSection_ab12c .basicgamecarousel_BasicGameCarouselItemMediaContainer_ab12c div:first-child { transform: skew(var(--tilt)); }
            .gamepadhome_RecentSection_ab12c .appportrait_LibraryItemBox_ab12c { transform: skew(calc(-1 * var(--tilt))); }
            """.trimIndent(),
        )
        assertEquals("the outer tile wins over the counter-skew inside it", -5f, r.spec.layout!!.cardTiltDeg)
        assertEquals(15f, layout(".appportrait_LibraryItemBox_ab12c { transform: rotate(30deg); }")!!.cardTiltDeg)
        assertEquals(-10f, layout(".appportrait_LibraryItemBox_ab12c { transform: perspective(600px) rotateY(calc(2 * -5deg)); }")!!.cardTiltDeg)
        assertNull("rotateY without perspective", layout(".appportrait_LibraryItemBox_ab12c { transform: rotateY(20deg); }"))
        assertNull("not a tile", layout(".focusring_FocusRing_ab12c { transform: skew(-5deg); }"))
    }

    @Test
    fun tileCornersAreNotTheGlobalRadius() {
        val s = translate(
            """
            :root { --r: 0px; }
            .gamepadhomewhatsnew_GameIcon_ab12c, .gamepadhomewhatsnew_GameIcon_ab12c img { border-radius: var(--r); }
            """.trimIndent(),
        ).spec
        assertEquals(0f, s.layout!!.cardCornerRadiusDp)
        assertNull(s.cornerRadiusDp)
        val both = translate(".appportrait_LibraryItemBox_ab12c { border-radius: 6px; } .DialogButton { border-radius: 12px; }").spec
        assertEquals(6f, both.layout!!.cardCornerRadiusDp)
        assertEquals(12f, both.cornerRadiusDp)
    }

    @Test
    fun noLayoutEvidenceLeavesLayoutUnset() {
        val r = translate(
            """
            .applaunchingdetails_ControlOverviewContainer_ab12c { display: none; }
            .gamepadhomerecentgames_RecentGamesBackgroundContainer_ab12c { -webkit-mask-image: none; }
            .gamepadhomerecentgames_RecentGamesHeader_ab12c { display: none; }
            .appportrait_LibraryItemBox_ab12c { width: 200px; height: 300px; transition: transform 0.2s; }
            """.trimIndent(),
        )
        assertNull(r.spec.layout)
        assertEquals(0, r.appliedRules)
    }

    @Test
    fun gnLayoutVariables() {
        val s = translate(
            """
            :root {
              --gn-card-scale: 1.2;
              --gn-library-layout: "list";
              --gn-title-align: centre;
              --gn-title-stroke: 2px #000;
              --gn-hide-badges: true;
              --gn-hide-footer-hints: 0;
              --gn-hero-scale: 150%;
              --gn-card-tilt: -8deg;
              --gn-card-corner-radius: 6px;
            }
            """.trimIndent(),
        ).spec
        assertEquals(
            ThemeLayout(
                cardScale = 1.2f, libraryLayout = "list", titleAlign = "center", titleStrokeDp = 2f, titleStrokeColor = argb("#000"),
                hideBadges = true, hideFooterHints = false, heroScale = 1.5f, cardTiltDeg = -8f, cardCornerRadiusDp = 6f,
            ),
            s.layout,
        )
    }

    @Test
    fun gnLayoutVariablesAreClampedAndValidated() {
        val l = layout(
            ":root { --gn-card-scale: 9; --gn-hero-scale: 0.1; --gn-card-tilt: 40deg; --gn-library-layout: diagonal; --gn-title-align: justify; --gn-hide-badges: maybe; }",
        )!!
        assertEquals(1.5f, l.cardScale)
        assertEquals(0.5f, l.heroScale)
        assertEquals(15f, l.cardTiltDeg)
        assertNull(l.libraryLayout)
        assertNull(l.titleAlign)
        assertNull(l.hideBadges)
    }

    @Test
    fun gnLayoutVariableBeatsInferredLayoutPerValue() {
        val r = translate(
            ".appportrait_LibraryItemBox_ab12c { transform: scale(0.7); } .footer_BasicFooter_ab12c { display: none; }",
            ":root { --gn-card-scale: 1.25; }",
        )
        assertEquals(1.25f, r.spec.layout!!.cardScale)
        assertEquals(true, r.spec.layout!!.hideFooterHints)
        assertEquals("the footer rule still counts; the replaced scale rule does not", 2, r.appliedRules)
    }

    @Test
    fun gnComponentVariables() {
        val s = translate(
            """
            :root {
              --gn-card: #111111; --gn-button: #222222; --gn-on-button: #eeeeee; --gn-toggle: #33aa33;
              --gn-menu: #444444; --gn-on-menu: #fafafa;
              --gn-accent-gradient: linear-gradient(90deg, #ff0000, #00ff00);
              --gn-focus-glow: 8px;
            }
            """.trimIndent(),
        ).spec
        assertEquals(argb("#111111"), s.card)
        assertEquals(argb("#222222"), s.button)
        assertEquals(argb("#eeeeee"), s.onButton)
        assertEquals(argb("#33aa33"), s.toggle)
        assertEquals(argb("#444444"), s.menu)
        assertEquals(argb("#fafafa"), s.onMenu)
        assertEquals(listOf(argb("#ff0000"), argb("#00ff00")), s.accentGradient!!.colors)
        assertEquals(8f, s.focusGlowDp)
    }
}
