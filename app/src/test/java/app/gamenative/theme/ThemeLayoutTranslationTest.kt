package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeLayoutTranslationTest {

    private fun translate(vararg css: String) =
        ThemeTranslator.translate(css.map { SourceSheet(CssParser.parse(it)) { null } }, SteamClassMap.EMPTY)

    private fun layout(vararg css: String): ThemeLayout? = translate(*css).spec.layout

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    /** The active stack the way the theme manager builds it, bottom theme first. */
    private fun stack(vararg themes: String): ThemeSpec {
        var active = ThemeSpec.EMPTY
        val defaults = ArrayList<String?>()
        for (css in themes) {
            val sheet = CssParser.parse(css)
            val res = ThemeTranslator.translate(listOf(SourceSheet(sheet) { null }), SteamClassMap.EMPTY)
            active = active.overlay(res.spec)
            if (!CommunityThemePackage.isNative(sheet)) defaults.add(res.defaultLayout)
        }
        return ThemeTranslator.withLayoutDefault(active, ThemeTranslator.stackDefaultLayout(defaults))
    }

    @Test
    fun steamDeckThemeOnTheHomeDefaultsToShelf() {
        val s = stack(".gamepadhome_TabbedContent_cE1Sa { background-color: #102030; } :root { --main-editable-accent-color: #ff0000; }")
        assertEquals("row", s.layout!!.libraryLayout)
        assertEquals("row", stack(":root { --gn-background: #101010; }", ".basicgamecarousel_SubMessage_ab12c { color: #f6019d; }").layout!!.libraryLayout)
    }

    @Test
    fun aHomeRuleWithoutAValueStillPicksTheShelf() {
        assertEquals("row", stack(".gamepadhomerecentgames_RecentGamesHeader_35iRe { display: none; }").layout!!.libraryLayout)
    }

    @Test
    fun steamDeckThemeOnTheLibraryGridOnlyDefaultsToGrid() {
        assertEquals("grid", stack(".gamepadlibrary_GamepadLibrary_ZBBhe .appgrid_YourCollection_11p6F { gap: 0px 16px !important; }").layout!!.libraryLayout)
        assertEquals(
            "home wins in a stack",
            "row",
            stack(".appgrid_YourCollection_11p6F { gap: 0px 16px; }", ".basicgamecarousel_SubMessage_ab12c { color: #f6019d; }").layout!!.libraryLayout,
        )
    }

    @Test
    fun steamDeckThemeOffTheHomeAndGridKeepsTheUsersLayout() {
        assertNull(stack(".gamepadui_BasicHome_ab12c { background-color: #102030; }").layout?.libraryLayout)
        assertNull(
            "the collections page is not the grid",
            stack(".allcollections_Grid_Ma65K .allcollections_Collection_3IWn- .libraryassetimage_GreyBackground_2E7G8 { background: #ff00aa !important; }")
                .layout?.libraryLayout,
        )
        assertNull(
            "a focus outline alone",
            stack(".appportrait_LibraryItemBox_ab12c:focus { outline: 2px solid #ffffff; }").layout?.libraryLayout,
        )
        assertNull(
            "a home rule only in a focus state",
            stack(".basicgamecarousel_BasicGameCarousel_3MdH5 .basicgamecarousel_CarouselGameLabelWrapper_ZkD6W.gpfocus { opacity: 0; }").layout?.libraryLayout,
        )
    }

    @Test
    fun nativeThemeKeepsTheLayout() {
        assertNull(stack(":root { --gn-background: #101010; --gn-primary: #ff0000; }").layout?.libraryLayout)
    }

    @Test
    fun steamDeckThemeThatAppliesNothingStaysUnthemed() {
        assertEquals(ThemeSpec.EMPTY, stack(".nothing_we_know_ab12c { color: red; }"))
        assertEquals(ThemeSpec.EMPTY, stack(""))
    }

    @Test
    fun explicitLayoutBeatsTheShelfDefault() {
        assertEquals("list", stack(":root { --gn-library-layout: list; }", ".gamepadhome_TabbedContent_cE1Sa { background-color: #102030; }").layout!!.libraryLayout)
        assertEquals("grid", stack(".gamepadhome_TabbedContent_cE1Sa { background-color: #102030; }", ":root { --gn-library-layout: grid; }").layout!!.libraryLayout)
    }

    @Test
    fun homeShelfEvidenceStillMeansRow() {
        assertEquals("row", stack(".gamepadhomerecentgames_RecentGamesInnerContainer_ab12c { height: 300px; }").layout!!.libraryLayout)
        assertEquals("row", stack(":root { --gn-background: #101010; }", ".basicgamecarousel_BasicGameCarousel_ab12c { height: 100vh !important; }").layout!!.libraryLayout)
    }

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
        assertEquals("a focus pop is the focused cover's scale", ThemeLayout(cardFocusedScale = 1.3f), layout(".appportrait_LibraryItemBox_ab12c.gpfocus { transform: scale(1.3); }"))
        assertNull(layout(".gpfocuswithin .appportrait_LibraryItemBox_ab12c { transform: scale(1.3); }")?.cardScale)
        assertNull(layout(".appportrait_LibraryItemBox_ab12c { transform: scale(1.02); }"))
        assertNull("one focused selector makes the rule a focus effect",
            layout(".appportrait_LibraryItemBox_ab12c.gpfocus, .appportrait_LibraryItemBox_ab12c[tabindex] { transform: scale(1.3); }")?.cardScale)
        assertNull("not a tile", layout(".gamepadui_BasicHome_ab12c { transform: scale(0.7); }"))
    }

    @Test
    fun resizedHomeShelfMeansRow() {
        assertEquals("row", layout(".gamepadhomerecentgames_RecentGamesInnerContainer_ab12c { height: calc(100vh - 80px - 14px - 58px); }")!!.libraryLayout)
        assertEquals("row", layout(".basicgamecarousel_BasicGameCarousel_ab12c { height: 100vh !important; }")!!.libraryLayout)
        assertEquals("row", layout(".gamepadhomerecentgames_RecentGamesInnerContainer_ab12c { height: 300px; }")!!.libraryLayout)
        assertNull(layout(".gamepadhomerecentgames_RecentGamesInnerContainer_ab12c { height: 90%; }"))
    }

    @Test
    fun scaledOrTiltedHomeShelfMeansRow() {
        assertEquals(
            ThemeLayout(cardScale = 0.7f, libraryLayout = "row"),
            layout(":root { --s: 0.7; } .basicgamecarousel_BasicGameCarousel_ab12c { transform: scale(var(--s)); width: calc(100vw / var(--s)); }"),
        )
        assertEquals(
            ThemeLayout(libraryLayout = "row", cardTiltDeg = -5f),
            layout(".gamepadhome_RecentSection_ab12c .basicgamecarousel_BasicGameCarouselItemMediaContainer_ab12c > div:first-child { transform: skew(-5deg); }"),
        )
        assertEquals(
            "a library tile inside the home shelf",
            "row",
            layout(".gamepadhome_RecentSection_ab12c .appportrait_LibraryItemBox_ab12c { transform: skew(-5deg); }")!!.libraryLayout,
        )
    }

    @Test
    fun portraitFeaturedCapsuleTurnsTheWideFirstTileOff() {
        assertEquals(
            false,
            layout(".basicgamecarousel_Featured_ab12c .appportrait_LibraryItemBox_ab12c { aspect-ratio: 2 / 3; }")!!.shelfWideFirst,
        )
        assertNull("still wide", layout(".basicgamecarousel_Featured_ab12c .appportrait_LibraryItemBox_ab12c { aspect-ratio: 460 / 215; }")?.shelfWideFirst)
        assertNull("not the featured capsule", layout(".appportrait_LibraryItemBox_ab12c { aspect-ratio: 2 / 3; }")?.shelfWideFirst)
        assertEquals(false, layout(":root { --gn-shelf-wide-first: 0; }")!!.shelfWideFirst)
        assertEquals(true, layout(":root { --gn-shelf-wide-first: true; }")!!.shelfWideFirst)
    }

    @Test
    fun tilesOutsideTheHomeShelfKeepTheLayout() {
        assertEquals(ThemeLayout(cardScale = 1.25f), layout(".appportrait_LibraryItemBox_ab12c { zoom: 125%; }"))
        assertEquals(ThemeLayout(cardTiltDeg = 15f), layout(".appportrait_LibraryItemBox_ab12c { transform: rotate(30deg); }"))
        assertNull("color on the shelf is not a layout", layout(".basicgamecarousel_SubMessage_ab12c { color: #f6019d; }"))
        assertNull("title alignment alone keeps the layout", layout(".basicgamecarousel_CarouselGameLabel_ab12c { text-align: center; }")!!.libraryLayout)
    }

    @Test
    fun centeredTitles() {
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
        assertNull("mixed colors", layout(".marquee_Content_ab12c { text-shadow: -1px 1px 0 #000, 1px 1px 0 #fff, 1px -1px 0 #000, -1px -1px 0 #000; }"))
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
        assertNull("home hero is not the game page hero", layout(".gamepadhomerecentgames_RecentGamesBackground_ab12c { height: 50vh; }")?.heroScale)
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
        val turned = layout(".appportrait_LibraryItemBox_ab12c { transform: perspective(600px) rotateY(calc(2 * -5deg)); }")!!
        assertEquals("a 3D turn is not a shear", ThemeLayout(cardTurnDeg = -10f, cardTurnPerspectiveDp = 600f), turned)
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
        assertEquals("game icons are not library tiles", 0f, s.layout!!.iconCornerRadiusDp)
        assertNull(s.layout!!.cardCornerRadiusDp)
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
        assertEquals("only the artwork mask, as a hero fade", 1, r.appliedRules)
    }

    @Test
    fun gnLayoutVariables() {
        val s = translate(
            """
            :root {
              --gn-card-scale: 1.2;
              --gn-library-layout: "list";
              --gn-title-align: center;
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
    fun titleAlignStillReadsCentre() {
        assertEquals("center", layout(":root { --gn-title-align: centre; }")!!.titleAlign)
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
        assertEquals("row", layout(":root { --gn-library-layout: row; }")!!.libraryLayout)
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

    @Test
    fun hiddenHomeArtworkIsNoArtwork() {
        fun opacity(css: String) = translate(css).spec.hero?.opacity
        val child = ".gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt>*"
        assertEquals("a dimmed child", 0.5f, opacity("$child { opacity: 0.5; bottom: 50vh; }"))
        assertEquals("hidden after being dimmed", 0f, opacity("$child { opacity: 0.5; bottom: 50vh; } $child { display: none; }"))
        assertEquals(
            "hidden children beat the container's own opacity",
            0f,
            opacity(
                """
                .gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt { position: absolute; top: 0; }
                .gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt > * { display: none; }
                .gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt, .gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt::after { opacity: 1; }
                """.trimIndent(),
            ),
        )
        assertEquals(
            0f,
            opacity(
                ".BasicUI .gamepadhomerecentgames_RecentGamesBackgroundImage_, .BasicUI .gamepadhomerecentgames_RecentGamesBackgroundImages_ " +
                    "{ display: none !important; filter: none !important; }",
            ),
        )
        assertEquals(
            0f,
            opacity(".gamepadhomerecentgames_RecentGamesBackgroundContainer, .basicgamecarousel_CarouselCapsuleBackgroundGlow { display: none !important; }"),
        )
        assertEquals(
            0f,
            opacity(".gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt, .gamepadhomerecentgames_RecentGamesBackgroundImages_30D-8 { display: none; }"),
        )
        assertEquals(0f, opacity(".gamepadhomerecentgames_RecentGamesBackground_1SRox { visibility: hidden; }"))
        assertNull("the theme's own layer", opacity(".gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt::before { opacity: 0; }"))
        assertNull("only while focused", opacity(".gpfocuswithin .gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt { display: none; }"))
    }

    @Test
    fun shelfRowAtTheBottom() {
        assertEquals(
            "bottom",
            layout(
                ".gamepadhomerecentgames_RecentGamesInnerContainer_282X0 .basicgamecarousel_BasicGameCarousel_3MdH5 { bottom: 0px; position: absolute !important; }",
            )!!.shelfRowPosition,
        )
        assertEquals("bottom", layout(".RecentGamesContainer.Panel { display: flex; } .RecentGamesInnerContainer { align-self: flex-end; }")!!.shelfRowPosition)
        assertEquals("bottom", layout(".gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { margin-top: 160px; }")!!.shelfRowPosition)
        assertNull("a small push", layout(".gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { margin-top: 20px; }")?.shelfRowPosition)
        assertNull("padding inside the row", layout(".basicgamecarousel_BasicGameCarousel_3MdH5 { padding-top: 85px; }")?.shelfRowPosition)
        assertNull(
            "pinned to the top",
            layout(".basicgamecarousel_BasicGameCarousel_3MdH5 { position: absolute; top: 0; bottom: 0; }")?.shelfRowPosition,
        )
    }

    @Test
    fun shelfRowInTheCenter() {
        assertEquals(
            "center",
            layout(".gamepadhomerecentgames_RecentGamesContainer_2_cRk { padding-top: 98px; padding-bottom: 61px; }")!!.shelfRowPosition,
        )
        assertEquals("center", layout(".gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { flex: 1; margin: auto; }")!!.shelfRowPosition)
        assertEquals(
            "center",
            layout(
                ".gamepadhomerecentgames_RecentGamesContainer_2_cRk { padding-top: 0; position: relative; height: 100%; display: flex; flex-direction: row; align-items: center; }",
            )!!.shelfRowPosition,
        )
        assertEquals(
            "center",
            layout(":root { --shtt: 120px; } .gamepadhomerecentgames_RecentGamesContainer_2_cRk { padding-top: var(--shtt, 54px); }")!!.shelfRowPosition,
        )
        assertNull(
            "centered across, not down",
            layout(".gamepadhomerecentgames_RecentGamesContainer_2_cRk { display: flex; flex-direction: column; align-items: center; }")?.shelfRowPosition,
        )
        assertNull("not a flex box", layout(".gamepadhomerecentgames_RecentGamesContainer_2_cRk { align-items: center; }")?.shelfRowPosition)
    }

    @Test
    fun shelfTitleAboveTheCovers() {
        assertEquals(
            true,
            layout(".basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { position: absolute; top: -52px; padding-top: 0; }")!!.shelfTitleAbove,
        )
        assertEquals(true, layout(".basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { margin-top: -265px; }")!!.shelfTitleAbove)
        assertNull("nudged, not moved", layout(".basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { margin-top: -10px; }")?.shelfTitleAbove)
        assertNull("not positioned", layout(".basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { top: -52px; }")?.shelfTitleAbove)
    }

    @Test
    fun hiddenShelfTitleAndSubtitle() {
        val noTitle = layout(".basicgamecarousel_CarouselGameLabel_3CKji { visibility:hidden }")!!
        assertEquals(true, noTitle.shelfHideTitle)
        assertNull(noTitle.shelfHideSubtitle)
        assertEquals(true, layout(".basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { display: none; }")!!.shelfHideTitle)
        val noSubtitle = layout(
            """
            .gamepadhome_RecentSection_39tNv .basicgamecarousel_Play_12eKc.basicgamecarousel_SubMessage_1BtXg { visibility: collapse; }
            .gamepadhome_RecentSection_39tNv .appportrait_PortraitMessage_3gwMk.appportrait_SubMessage_3DR9f { visibility: collapse; }
            """.trimIndent(),
        )!!
        assertEquals(true, noSubtitle.shelfHideSubtitle)
        assertNull(noSubtitle.shelfHideTitle)
        assertNull("a library tile's line", layout(".appportrait_SubMessage_3DR9f { display: none; }")?.shelfHideSubtitle)
        val stateOnly = layout(
            """
            :is(.basicgamecarousel_BasicGameCarousel_3MdH5, ._3MdH5Czolhh5rC_nofUlcQ):has(.gpfocuswithin) :not(.gpfocuswithin) >
              :is(.basicgamecarousel_CarouselGameLabelWrapper_ZkD6W, .ZkD6We6MqGbOsa9K3yiY3):is(.basicgamecarousel_ShowAsHovered_3tdVJ, ._3tdVJpA0NVV1y7pIQ87DkB) {
              opacity: 0 !important;
              visibility: hidden !important;
            }
            """.trimIndent(),
        )
        assertNull(stateOnly?.shelfHideTitle)
        assertNull(stateOnly?.shelfHideSubtitle)
    }

    @Test
    fun portraitWidthFeaturedCapsuleTurnsTheWideFirstTileOff() {
        assertEquals(
            false,
            layout(":root { --RWB-end-width: 133px; } .gamepadhome_RecentSection_39tNv .basicgamecarousel_Featured_2eJEJ { width: var(--RWB-end-width) !important; }")!!
                .shelfWideFirst,
        )
        assertEquals(
            false,
            layout(
                ":root { --switch-like-size-sub-height: 133px; } .gamepadhome_RecentSection_39tNv .appportrait_FeaturedCapsule_xp7A-, " +
                    ".gamepadhome_RecentSection_39tNv .appportrait_FeaturedCapsule_xp7A- img { width: var(--switch-like-size-sub-height) !important; }",
            )!!.shelfWideFirst,
        )
        assertNull("still the wide banner", layout(".gamepadhome_RecentSection_39tNv .basicgamecarousel_Featured_2eJEJ { width: 426.837px; }")?.shelfWideFirst)
    }

    @Test
    fun scalesOnSomeSiblingsAreNotTheTileSize() {
        val scope = ".gamepadhomerecentgames_RecentGamesInnerContainer .ReactVirtualized__Grid__innerScrollContainer:not(:has(.gpfocus)) > :has(.basicgamecarousel_Featured)"
        val item = ".basicgamecarousel_BasicGameCarouselItemMediaContainer"
        val l = layout(
            """
            $scope + * $item { opacity: 1; transform: translate(-15px, -5px) scale(0.86); }
            $scope + * + * $item { opacity: 1; transform: translate(-30px, -10px) scale(0.76); }
            $scope + * + * + * + * + * + * $item { opacity: 1; transform: translate(-90px, -30px) scale(0.55); }
            $scope + * + * + * + * + * + * + * $item { opacity: 1; transform: translate(-105px, -35px) scale(0.55); }
            """.trimIndent(),
        )
        assertNull(l?.cardScale)
        assertNull(layout(".ReactVirtualized__Grid__innerScrollContainer > div:nth-child(2) $item { transform: scale(0.8); }")?.cardScale)
        assertEquals("every tile", 0.8f, layout(".gamepadhome_RecentSection_39tNv $item { transform: scale(0.8); }")!!.cardScale)
    }

    /** The tilt theme's shared rules, its -5 angle patch and one tilt method, in the loader's order. */
    private fun tilted(method: String): ThemeLayout {
        val shared = """
            :root { --ren-tilt-angle: -5deg; --ren-most-recent-offset: 2%; --ren-view-more-offset: -7%; --ren-view-more-focus-scale: 0.88; }
            .gamepadhome_RecentSection_39tNv .basicgamecarousel_BasicGameCarousel_3MdH5 .ReactVirtualized__Grid__innerScrollContainer > div.gpfocuswithin,
            .gamepadhome_RecentSection_39tNv .basicgamecarousel_BasicGameCarousel_3MdH5 .ReactVirtualized__Grid__innerScrollContainer > div[tabindex] { position: relative; z-index: 1; }
            .gamepadhome_RecentSection_39tNv .basicgamecarousel_BasicGameCarouselItemMediaContainer_1HIFN > div { transform-style: preserve-3d; }
        """.trimIndent()
        val angle = ":root { --ren-tilt-angle: -5deg !important; --ren-most-recent-offset: 2% !important; }"
        return translate(shared, angle, method).spec.layout!!
    }

    private val row = ".gamepadhome_RecentSection_39tNv .basicgamecarousel_BasicGameCarousel_3MdH5 .ReactVirtualized__Grid__innerScrollContainer"
    private val media = ".basicgamecarousel_BasicGameCarouselItemMediaContainer_1HIFN"

    @Test
    fun tiltOneWay() {
        val l = tilted(
            """
            $row > div:nth-child(1).gpfocuswithin { transform: translateX(var(--ren-most-recent-offset)); }
            [data-id="GoToLibrary"] { transform: skew(var(--ren-tilt-angle)); transition: transform 0.4s; }
            [data-id="GoToLibrary"].gpfocuswithin { transform: skew(var(--ren-tilt-angle)) scale(1.05) translateX(var(--ren-view-more-offset)); }
            .gamepadhome_ScrollArea_3PhGY > .focusring_FocusRingRoot_3PH_X > .focusring_FocusRing_1IZrQ { transform: skew(var(--ren-tilt-angle)) scaleX(var(--ren-view-more-focus-scale)); }
            .gamepadhome_RecentSection_39tNv $media > div:first-child { transform: skew(var(--ren-tilt-angle)); transition-duration: 0.4s; }
            .gamepadhome_RecentSection_39tNv $media.gpfocuswithin > div:first-child { transform: skew(var(--ren-tilt-angle)) scale(1.02); }
            .gamepadhome_RecentSection_39tNv .basicgamecarousel_BasicGameCarousel_3MdH5 .appportrait_LibraryItemOverlayInnerArea_2GRcK > div,
            .gamepadhome_RecentSection_39tNv .basicgamecarousel_BasicGameCarousel_3MdH5 .appportrait_LibraryItemBox_WYgDg:before { transform: skew(calc(-1*var(--ren-tilt-angle))); }
            """.trimIndent(),
        )
        assertEquals(ThemeLayout(libraryLayout = "row", cardTiltDeg = -5f), l)
    }

    @Test
    fun tiltOpposites() {
        val l = tilted(
            """
            [data-id="GoToLibrary"] { transform: skew(var(--ren-tilt-angle)); transition: transform 0.4s; }
            [data-id="GoToLibrary"].gpfocuswithin { transform: scale(1.05); }
            .gamepadhome_RecentSection_39tNv $media > div:first-child { transform: skew(calc(-1*var(--ren-tilt-angle))); transition-duration: 0.4s; }
            $row > div.gpfocuswithin ~ div $media > div:first-child,
            $row > div[tabindex]  ~ div $media > div:first-child { transform: skew(var(--ren-tilt-angle)); }
            .gamepadhome_RecentSection_39tNv $media.gpfocuswithin > div:first-child,
            .gamepadhome_RecentSection_39tNv $media[tabindex] > div:first-child { transform: scale(1.02); }
            """.trimIndent(),
        )
        assertEquals(ThemeLayout(libraryLayout = "row", cardTiltDeg = 5f, cardTiltAfterDeg = -5f, cardFocusedFlat = true), l)
    }

    @Test
    fun tilt3d() {
        val l = tilted(
            """
            $row > div:nth-child(1) $media > div:first-child { transform: translateX(40%) perspective(600px) rotateY(calc(1*var(--ren-tilt-angle))) translateX(-40%); }
            [data-id="GoToLibrary"] { transform: perspective(600px) rotateY(calc(-2*var(--ren-tilt-angle))) scale(0.94); transition: transform 0.4s; }
            [data-id="GoToLibrary"].gpfocuswithin { transform: scale(1.05); }
            .gamepadhome_RecentSection_39tNv $media > div:first-child { transition-duration: .4s; transform: perspective(600px) rotateY(calc(2*var(--ren-tilt-angle))); }
            $row > div.gpfocuswithin ~ div $media > div:first-child,
            $row > div[tabindex]  ~ div $media > div:first-child { transform: perspective(600px) rotateY(calc(-2*var(--ren-tilt-angle))); }
            .gamepadhome_RecentSection_39tNv $media.gpfocuswithin > div:first-child,
            .gamepadhome_RecentSection_39tNv $media[tabindex] > div:first-child,
            $row > div:nth-child(1) $media.gpfocuswithin > div:first-child { transform: scale(1.02); }
            """.trimIndent(),
        )
        assertEquals(
            ThemeLayout(libraryLayout = "row", cardTurnDeg = -10f, cardTurnAfterDeg = 10f, cardTurnPerspectiveDp = 600f, cardFocusedFlat = true),
            l,
        )
    }

    @Test
    fun aFlatFocusedTileAloneIsNoLayout() {
        assertNull(layout(".gamepadhome_RecentSection_39tNv .basicgamecarousel_BasicGameCarouselItemMediaContainer_1HIFN.gpfocuswithin > div:first-child { transform: scale(1.02); }"))
    }
}
