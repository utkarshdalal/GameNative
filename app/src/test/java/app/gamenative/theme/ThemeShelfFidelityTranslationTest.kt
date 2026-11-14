package app.gamenative.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeShelfFidelityTranslationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Files named like the last path segment of the urls that resolve to them. */
    private fun files(vararg names: String): Map<String, File> = names.associateWith { File(tmp.root, it).apply { writeBytes(byteArrayOf(1)) } }

    /** A PNG header of [w] x [h], all a translation reads of an image. */
    private fun png(name: String, w: Int, h: Int): File = File(tmp.root, name).apply {
        val b = java.nio.ByteBuffer.allocate(24)
        b.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52))
        b.putInt(w).putInt(h)
        writeBytes(b.array())
    }

    private fun translateWith(files: Map<String, File>, vararg css: String) =
        ThemeTranslator.translate(css.map { c -> SourceSheet(CssParser.parse(c)) { u -> files[u.substringAfterLast('/')]?.absolutePath } }, SteamClassMap.EMPTY).spec

    private fun layoutWith(files: Map<String, File>, vararg css: String) = translateWith(files, *css).layout

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

    @Test
    fun gameIconRulesAreNotTheLibraryTile() {
        val s = translate(
            """
            :root { --game-icon-radius: 0px; }
            .gamepadhomewhatsnew_GameIcon_2RrB8,
            .gamepadhomewhatsnew_GameIcon_2RrB8 img,
            .basicpartnereventspage_GameIconAndName_3R5H5 .libraryassetimage_Container_1R9r2,
            .basicpartnereventspage_GameIconAndName_3R5H5 .libraryassetimage_Container_1R9r2 img {
              background-color: transparent !important;
              border-radius: var(--game-icon-radius) !important;
            }
            .header_HeaderDownloadContainer_2ssr3 .libraryassetimage_GreyBackground_2E7G8,
            .header_HeaderDownloadContainer_2ssr3 img { border-radius: 0px !important; }
            .appportrait_LibraryItemBox_ab12c { background: #123456; }
            """.trimIndent(),
        )
        assertEquals(0f, s.layout!!.iconCornerRadiusDp)
        assertNull(s.layout!!.cardCornerRadiusDp)
        assertNull(s.cornerRadiusDp)
        assertEquals("the icons' clear fill is not the tile fill", argb("#123456"), s.card)
    }

    @Test
    fun collectionsPageRulesAreNotTheTileFill() {
        assertNull(translate(".allcollections_Grid_Ma65K .allcollections_Collection_3IWn- .libraryassetimage_GreyBackground_2E7G8 { background: #ff00aa !important; }").card)
        assertEquals(argb("#ff00aa"), translate(".libraryassetimage_GreyBackground_2E7G8 { background: #ff00aa; }").card)
    }

    @Test
    fun theLoadingScreenIsNotThePage() {
        val s = translate(
            """
            .loadingthrobber_Container_3sa1N.loadingthrobber_PreloadThrobber_1-epa,
            .loadingthrobber_Container_3sa1N.loadingthrobber_ContainerBackground_2ngG3 { background: #000000 !important; }
            """.trimIndent(),
        )
        assertNull(s.background)
    }

    @Test
    fun shelfAndTiltValuesRoundTripThroughTheirVariables() {
        val spec = ThemeSpec(
            layout = ThemeLayout(
                shelfRowPosition = "center", shelfTitleAbove = true, shelfHideTitle = true, shelfHideSubtitle = false,
                cardTiltDeg = 5f, cardTiltAfterDeg = -5f, cardTurnDeg = -10f, cardTurnAfterDeg = 10f, cardTurnPerspectiveDp = 600f,
                cardFocusedFlat = true,
            ),
        )
        assertEquals(spec, translate(ThemeCssWriter.write(spec)))
        assertEquals(spec, ThemeSpecJson.decode(ThemeSpecJson.encode(spec)))
        assertEquals(spec, ActiveThemeCache.normalize(spec))
    }

    @Test
    fun coverFramesCycleOverThePortraitCoversAndTheWideOne() {
        val f = files(
            "landscape_1.png", "landscape_1_mask.png", "landscape_1_mask2.png", "portrait_1.png", "portrait_1_mask.png", "portrait_1_mask2.png",
            "portrait_2.png", "portrait_2_mask.png", "recents.png",
        )
        val img = "/themes_custom/Phantom/images"
        val portrait = ".appportrait_InRecentGames_biTV-.appportrait_Portrait_1Pf6J"
        val wide = ".appportrait_InRecentGames_biTV-.appportrait_FeaturedCapsule_xp7A-"
        val l = layoutWith(
            f,
            """
            $portrait > .libraryassetimage_PortraitImage_3Ehhd > img,
            $wide > .libraryassetimage_LandscapeImage_ZsU8n > img,
            .appgrid_YourCollection_11p6F > * > .appportrait_Portrait_1Pf6J > .libraryassetimage_PortraitImage_3Ehhd > img { top: 7%; left: 7%; width: 86%; height: 86%; }
            $wide { background-image: url("$img/landscape_1.png"); background-size: cover; -webkit-mask-image: url("$img/landscape_1_mask2.png"); }
            $wide > .libraryassetimage_LandscapeImage_ZsU8n { -webkit-mask-image: url("$img/landscape_1_mask.png"); }
            *:nth-child(4n-2) > * > * > * > * > $portrait,
            .appgrid_YourCollection_11p6F > *:nth-child(5n-4) .appportrait_Portrait_1Pf6J {
              background-image: url("$img/portrait_1.png"); background-size: cover; -webkit-mask-image: url("$img/portrait_1_mask2.png");
            }
            *:nth-child(4n-2) > * > * > * > * > $portrait > .libraryassetimage_PortraitImage_3Ehhd { -webkit-mask-image: url("$img/portrait_1_mask.png"); }
            *:nth-child(4n-1) > * > * > * > * > $portrait { background-image: url("$img/portrait_2.png"); background-size: cover; -webkit-mask-image: url("$img/portrait_2.png"); }
            *:nth-child(4n-1) > * > * > * > * > $portrait > .libraryassetimage_PortraitImage_3Ehhd { -webkit-mask-image: url("$img/portrait_2_mask.png"); }
            .gamepadhomerecentgames_RecentGamesInnerContainer_282X0 > div > div > .basicgamecarousel_BasicGameCarousel_3MdH5 {
              background-image: url("$img/recents.png"); background-size: contain;
            }
            """.trimIndent(),
        )!!
        fun p(n: String) = f.getValue(n).absolutePath
        assertEquals(
            listOf(
                ThemeTileFrame(p("portrait_1.png"), p("portrait_1_mask.png"), 0.07f, 0.07f, 0.86f, 0.86f),
                ThemeTileFrame(p("portrait_2.png"), p("portrait_2_mask.png"), 0.07f, 0.07f, 0.86f, 0.86f),
            ),
            l.tileFrames,
        )
        assertEquals(ThemeTileFrame(p("landscape_1.png"), p("landscape_1_mask.png"), 0.07f, 0.07f, 0.86f, 0.86f), l.tileFrameWide)
        assertEquals(p("recents.png"), l.shelfRowImage)
    }

    @Test
    fun aFramePlacedInPixelsIsMeasuredAgainstItsImage() {
        val frame = png("cartridge.png", 863, 1000)
        val cover = ".appportrait_PortraitImage_2IYf7.appportrait_Capsule_13w3S.appportrait_CapsuleVisible_3QIfJ"
        val l = layoutWith(
            mapOf("cartridge.png" to frame),
            """
            $cover>.libraryassetimage_Visibility_3d_bT.libraryassetimage_Visible_yDr03 { top: 65px; width: 91px; left: 21px; height: 82px; border-radius: 1px; }
            $cover { background: transparent; background-repeat: no-repeat; background-size: 133px; background-position-x: 50%; background-position-y: 50%; height: 100px; }
            """.trimIndent(),
            "$cover { background-image: url(\"/themes_custom/Cartridge%20Theme/cartridge/default/cartridge.png\")!important; }",
        )!!
        val fr = l.tileFrames!!.single()
        assertEquals(frame.absolutePath, fr.image)
        val fh = 133.0 * 1000 / 863
        assertEquals(21 / 133.0, fr.insetLeft.toDouble(), 1e-4)
        assertEquals((65 - (100 - fh) / 2) / fh, fr.insetTop.toDouble(), 1e-4)
        assertEquals(91 / 133.0, fr.insetWidth.toDouble(), 1e-4)
        assertEquals(82 / fh, fr.insetHeight.toDouble(), 1e-4)
        assertNull(l.tileFrameWide)
    }

    @Test
    fun aMirroredGlowCopyIsAReflection() {
        val glow = ".libraryassetimage_Container_1R9r2.basicgamecarousel_CarouselCapsuleBackgroundGlow_1H3Kf"
        val l = translate(
            """
            :root { --ren-reflection-gradient: #ffffff00 50%; --ren-reflection-focused-opacity: 0.5; --ren-reflection-unfocused-opacity: 0.3; --ren-reflection-blur: 3px; }
            .ReactVirtualized__Grid__innerScrollContainer $glow {
              opacity:var(--ren-reflection-unfocused-opacity); filter:none; width:100%; height:199.5px; top:199.5px; transform:scaleX(1) scaleY(-1);
              -webkit-mask-image: linear-gradient(to top,white, var(--ren-reflection-gradient)); filter:blur(var(--ren-reflection-blur));
            }
            .ReactVirtualized__Grid__innerScrollContainer div.gpfocuswithin $glow { opacity:var(--ren-reflection-focused-opacity); transform:scaleX(1.05) scaleY(-1.05) translateY(-9.5px); }
            """.trimIndent(),
        ).layout!!
        assertEquals(ThemeReflection(opacity = 0.3f, focusedOpacity = 0.5f, blurDp = 3f, fadeStop = 0.5f, gapDp = 0f), l.coverReflection)
        assertNull("the glow copy is not a cover", l.cardFocusedScale)
        assertNull(translate(".ReactVirtualized__Grid__innerScrollContainer $glow { opacity: 0.3; }").layout?.coverReflection)
    }

    @Test
    fun aBoxReflectIsAReflection() {
        val r = translate(
            """
            .gamepadhome_ScrollArea .appportrait_LibraryItemBox.appportrait_InRecentGames {
              border-radius: 3px !important;
              -webkit-box-reflect: below 3px linear-gradient(transparent 86%, rgba(255, 255, 255, 0.32));
            }
            """.trimIndent(),
        ).layout!!.coverReflection!!
        assertEquals(0.32f, r.opacity, 0.01f)
        assertEquals(0.14f, r.fadeStop, 0.01f)
        assertEquals(3f, r.gapDp)
        assertNull(r.focusedOpacity)
    }

    @Test
    fun dimmedCoversAtRest() {
        val l = translate(
            """
            :root { --ren-game-tile-brightness: 0.5; --ren-game-tile-saturate: 0.5; }
            .BasicUI .appportrait_LibraryItemBox_WYgDg { filter : brightness(var(--ren-game-tile-brightness)) saturate(var(--ren-game-tile-saturate)); }
            """.trimIndent(),
        ).layout!!
        assertEquals(0.5f, l.cardRestBrightness)
        assertEquals(0.5f, l.cardRestSaturation)
        assertNull("only while focused", translate(".appportrait_LibraryItemBox_WYgDg.gpfocus { filter: brightness(0.5); }").layout?.cardRestBrightness)
    }

    @Test
    fun theFocusedRowItemsScale() {
        val l = translate(
            """
            :root { --bfg-focused-scale: 1.2; }
            .ReactVirtualized__Grid__innerScrollContainer > div.gpfocuswithin,
            .ReactVirtualized__Grid__innerScrollContainer > div:focus-within { transform: scale(var(--bfg-focused-scale)); transform-origin: center center; z-index: 6 !important; }
            """.trimIndent(),
        ).layout!!
        assertEquals(1.2f, l.cardFocusedScale)
        assertNull(l.cardScale)
        assertNull("every item at rest", translate(".ReactVirtualized__Grid__innerScrollContainer > div { transform: scale(1.2); }").layout?.cardFocusedScale)
    }

    @Test
    fun depthStepsThroughSiblingChains() {
        val scope = ".gamepadhomerecentgames_RecentGamesInnerContainer .ReactVirtualized__Grid__innerScrollContainer:not(:has(.gpfocus)) > :has(.basicgamecarousel_Featured)"
        val item = ".basicgamecarousel_BasicGameCarouselItemMediaContainer"
        val l = translate(
            """
            $scope + * $item { opacity: 1; transform: translate(-15px, -5px) scale(0.86); }
            $scope + * + * $item { opacity: 1; transform: translate(calc(-0.140 * 100% - 2 * var(--nxe-depth-gap, 15px)), calc(2 * var(--nxe-depth-rise, -5px))) scale(0.76); }
            $scope + * + * + * + * $item { opacity: 1; transform: translate(calc(-0.700 * 100% - 4 * var(--nxe-depth-gap, 15px)), calc(4 * var(--nxe-depth-rise, -5px))) scale(0.62); }
            """.trimIndent(),
        ).layout!!
        assertEquals(
            listOf(
                ThemeDepthStep(0.86f, 0f, -5f),
                ThemeDepthStep(0.76f, -0.14f, -10f),
                ThemeDepthStep(0.76f, -0.14f, -10f),
                ThemeDepthStep(0.62f, -0.7f, -20f),
            ),
            l.cardDepthSteps,
        )
        assertNull(l.cardScale)
    }

    @Test
    fun aCoverTurnedEdgeOnPseudoElementIsItsSpine() {
        val media = ".basicgamecarousel_BasicGameCarouselItemMediaContainer_1HIFN > div:first-child"
        val l = translate(
            """
            :root { --dvd-case-color: #222222; --dvd-case-depth: 23px; }
            $media::before, $media::after { content: ""; position: absolute; top: 0; width: var(--dvd-case-depth); height: 100%; }
            $media::before { left: 0; transform-origin: left 25%; transform: rotateY(90deg); background-color: var(--dvd-case-color); }
            $media::after { right: 0; transform: rotateY(-90deg); background-color: var(--dvd-case-color); }
            """.trimIndent(),
        ).layout!!
        assertEquals(23f, l.cardSpineWidthDp)
        assertEquals(argb("#222222"), l.cardSpineColor)
        assertNull("a pseudo-element that is not turned edge-on", translate("$media::before { width: 23px; background-color: #222; }").layout?.cardSpineWidthDp)
    }

    @Test
    fun coversSharingTheRowWidth() {
        val css = "{ position: relative !important; flex-grow: 1; flex-basis: 0; max-width: 133px; min-width: 2px; }"
        assertEquals(true, translate(".gamepadhome_RecentSection_39tNv .ReactVirtualized__Grid__innerScrollContainer > div $css").layout!!.shelfFitAll)
        assertNull("not the home row", translate(".ReactVirtualized__Grid__innerScrollContainer > div $css").layout?.shelfFitAll)
    }

    @Test
    fun headerTextFromTheRowHeaderContent() {
        val l = translate(
            """
            :root { --flangrande-custom-text: "Welcome back you deckhead!"; }
            .gamepadhomerecentgames_RecentGamesHeader_35iRe { visibility: hidden; }
            .gamepadhomerecentgames_RecentGamesHeader_35iRe::after { content: var(--flangrande-custom-text); visibility: visible; position: absolute; left: 2.8vw; }
            """.trimIndent(),
        ).layout!!
        assertEquals("Welcome back you deckhead!", l.shelfHeaderText)
        assertNull(translate(".gamepadhomerecentgames_RecentGamesHeader_35iRe::after { content: \"\"; }").layout?.shelfHeaderText)
    }

    @Test
    fun libraryGridShape() {
        val compact = translate(
            """
            .appgrid_GridWithControls_rz1mr.Panel { max-width: calc(5 * 220px + 4 * 15px + 30px); zoom: 1.3; margin: 0 auto; }
            .appgrid_YourCollection_11p6F { display: grid !important; grid-template-columns: repeat(5, 120px); grid-auto-rows: 120px; gap: 15px !important; }
            """.trimIndent(),
        ).layout!!
        assertEquals(5, compact.gridColumns)
        assertEquals(1f, compact.gridTileAspect)
        assertEquals(1.3f, compact.cardScale)
        assertEquals(0.87f, translate(":root { --mli-zoom: 87%; } .appgrid_GridWithControls_rz1mr.Panel { zoom: var(--mli-zoom); }").layout!!.cardScale)
        assertEquals(
            true,
            translate(":not([id^=\"virtual\"]) > div[role=\"gridcell\"] > div.Panel > div:nth-of-type(2) { display: block !important; font-size: 12px; }").layout!!.gridTitleBelow,
        )
    }

    @Test
    fun uninstalledCovers() {
        val f = files("installEmpty.svg", "install.png")
        val l = layoutWith(
            f,
            """
            :root { --uninstalled-grayscale: 100%; --uninstalled-opacity: 0.5; }
            .Panel[role="link"]:has(.DownloadArrow, .N8aJrSpxQ6II4CY_aFdYI) img { opacity: var(--uninstalled-opacity); filter: grayscale(var(--uninstalled-grayscale)); }
            .Panel[role="link"]:has(.DownloadArrow, .N8aJrSpxQ6II4CY_aFdYI):focus img { opacity: 0.9; filter: grayscale(90%); }
            .Panel[role="link"]:has(.DownloadArrow) > div:last-child::after { content: url("/themes_custom/Icons/SVGs/installEmpty.svg"); }
            """.trimIndent(),
        )!!
        assertEquals(0f, l.uninstalledSaturation)
        assertEquals(0.5f, l.uninstalledOpacity)
        assertNull("an SVG cannot be drawn", l.uninstalledIcon)
        assertEquals(
            f.getValue("install.png").absolutePath,
            layoutWith(f, ".Panel[role=\"link\"]:has(.DownloadArrow) > div:last-child::after { content: url(\"icons/install.png\"); }")!!.uninstalledIcon,
        )
    }

    @Test
    fun libraryTabColorsAndCounts() {
        val tab = ".gamepadlibrary_GamepadLibrary_ZBBhe .gamepadtabbedpage_FixCenterAlignScroll_1CJeU .gamepadtabbedpage_Tab_3eEbS"
        val l = translate(
            """
            $tab:nth-of-type(3) { background-color: #ff0000; }
            $tab:nth-of-type(4) { background-color: #00ff00; }
            $tab:nth-of-type(5) { background-color: #0000ff; }
            $tab[aria-selected="false"] { filter: drop-shadow(0px 3px 2px #000000a1) invert(0.2) opacity(0.75); }
            .gamepadlibrary_GamepadLibrary_ZBBhe span.gamepadtabbedpage_TabCount_1ui4I { display:none; }
            """.trimIndent(),
        ).layout!!
        assertEquals(listOf(argb("#ff0000"), argb("#00ff00"), argb("#0000ff")), l.tabColors)
        assertEquals(true, l.hideTabCounts)
    }

    @Test
    fun aWallpaperOnTheHomeArtwork() {
        val f = files("stars.png", "wallpaper.png")
        val container = ".gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt"
        val space = translateWith(
            f,
            """
            $container { position: absolute; top: 0; background: #000 url("/themes_custom/Animated CSS Space Background/stars.png") repeat; background-size: 150%; z-index: 0; }
            $container > * { display: none; }
            $container, $container::after { opacity: 1; }
            """.trimIndent(),
        )
        assertEquals(f.getValue("stars.png").absolutePath, space.layout!!.heroImage)
        assertEquals(true, space.layout!!.heroImageTile)
        assertEquals(1.5f, space.layout!!.heroImageScale)
        assertEquals(1f, space.hero!!.opacity)
        val half = translateWith(
            f,
            """
            :root { --static-background: url("wallpaper.png"); }
            $container { position: absolute; top: 0; background-image: var(--static-background); background-repeat: no-repeat; background-size: cover; opacity: 0.5; }
            $container > * { display: none; }
            """.trimIndent(),
        )
        assertEquals(f.getValue("wallpaper.png").absolutePath, half.layout!!.heroImage)
        assertNull(half.layout!!.heroImageTile)
        assertEquals("the wallpaper keeps the container's opacity", 0.5f, half.hero!!.opacity)
        assertEquals("hidden artwork without a wallpaper", 0f, translate("$container > * { display: none; } $container { opacity: 0.5; }").hero!!.opacity)
    }

    @Test
    fun homeArtworkBand() {
        val s = translate(
            """
            .gamepadhomerecentgames_RecentGamesBackgroundImages_30D-8 .gamepadhomerecentgames_RecentGamesBackground_1SRox {
              height: 50vh!important; left: 0!important; max-height: 50vh!important; min-height: 100%!important; position: absolute;
            }
            .gamepadhomerecentgames_RecentGamesBackgroundContainer_QNkOt, .gamepadhomerecentgames_RecentGamesBackgroundImages_30D-8 {
              -webkit-mask-image: linear-gradient(rgba(0, 0, 0, 1) 90%, rgba(0, 0, 0, 0) calc(100% - 5px));
            }
            """.trimIndent(),
        )
        assertEquals(0.5f, s.layout!!.heroHeightFraction)
        assertEquals(0.1f, s.hero!!.fadeBottom!!, 1e-4f)
    }

    @Test
    fun aFillOverTheHomeArtwork() {
        val fade = ".gamepadhomerecentgames_RecentGamesBackgroundFadeGradient_1ksIq"
        val l = translate(
            """
            :root { --obsidian-main-color: #000; }
            $fade {
              background: -webkit-gradient(linear, left 80%, left 90%, from(rgba(0, 0, 0, 0)), to(var(--obsidian-main-color))),
                -webkit-gradient(linear, 0% 0%, 100% 0%, color-stop(1%, var(--obsidian-main-color)), color-stop(5%, rgba(0, 0, 0, 0)),
                color-stop(95%, rgba(0, 0, 0, 0)), color-stop(99%, var(--obsidian-main-color))) !important;
            }
            """.trimIndent(),
        ).layout!!
        val layers = l.homeOverlay!!.layers
        assertEquals(listOf(180f, 90f), layers.map { it.angleDeg })
        assertEquals(listOf(0.8f, 0.9f), layers[0].stops)
        assertEquals(listOf(0.01f, 0.05f, 0.95f, 0.99f), layers[1].stops)
        assertEquals(argb("#000000"), layers[0].colors[1])
        assertNull(
            "a masked fade is only drawn where its mask lets it",
            translate("$fade { background: radial-gradient(155.42% 100% at 0% 100%, #21323d 0%, #162431 60%); -webkit-mask-image: linear-gradient(0, #152533 10%, transparent 20%); }")
                .layout?.homeOverlay,
        )
        assertNull(
            "only while the home is focused",
            translate(".gamepadhome_RecentSection_39tNv.Panel.Focusable.gpfocuswithin { background: linear-gradient(138deg, #ff196555, transparent, #0ff5); }").layout?.homeOverlay,
        )
    }

    @Test
    fun scanLinesOverTheWholeScreen() {
        val l = translate(
            """
            @media screen {
              .BasicUI::before {
                content: " "; display: block; position: absolute; top: 0; left: 0; bottom: 0; right: 0;
                background: linear-gradient(rgba(18, 16, 16, 0) 50%, rgba(0, 0, 0, 0.25) 50%), linear-gradient(90deg, rgba(255, 0, 0, 0.06), rgba(0, 255, 0, 0.02), rgba(0, 0, 255, 0.06));
                z-index: 10000; background-size: 100% 3px, 2px 100%; pointer-events: none; animation: flicker 0.5s infinite; filter: blur(.5px);
              }
              .BasicUI::after { content: " "; display: block; position: absolute; top: 0; left: 0; bottom: 0; right: 0; background: rgba(18, 16, 16, 0.1); opacity: 0; z-index: 10000; pointer-events: none; }
            }
            """.trimIndent(),
        ).layout!!
        val layers = l.screenOverlay!!.layers
        assertEquals(listOf(180f, 90f), layers.map { it.angleDeg })
        assertEquals(listOf(3f, 2f), layers.map { it.repeatDp })
        assertEquals(listOf(0.5f, 0.5f), layers[0].stops)
    }

    @Test
    fun repeatingGradientsRepeatEveryLastStop() {
        val g = CssValues.parseGradient("repeating-linear-gradient(180deg, #111, #111 2px, #222 2px, #222 4px)")!!
        assertEquals(4f, g.repeatDp)
        assertEquals(listOf(0f, 0.5f, 0.5f, 1f), g.stops)
        assertNull(CssValues.parseGradient("linear-gradient(180deg, #111, #222)")!!.repeatDp)
    }

    @Test
    fun hiddenFooterHintsAndSearch() {
        val footers = """[class*="footer_BasicFooter"], [class*="FooterLegend"], [class*="BasicFooter"]"""
        val l = translate(
            """
            :is($footers) :is([data-button="A"], [data-button="B"], [data-button="a"], [aria-label="A"], [title="B"]) { display: none !important; }
            :is($footers) > *:has([data-button="A"], [class*="Button_A"], [class*="Button_B"], [class*="button_B"]) { display: none !important; }
            .searchbar_SearchAndTitleContainer_17M8g { display: none; }
            """.trimIndent(),
        ).layout!!
        assertEquals(listOf("A", "B"), l.footerHiddenHints)
        assertNull(l.hideFooterHints)
        assertEquals(true, l.hideTopBarSearch)
    }

    @Test
    fun aPageWallpaperUnderAGradientInTheModuleUnderscoreSpelling() {
        val f = files("clouds.png")
        val s = translateWith(
            f,
            """
            :root { --w95-wallpaper: url('/themes_custom/Windows%2095%20for%20Deck/assets/clouds.png'); }
            .BasicUI .gamepadui_BasicHome_ {
              background-color: #008080 !important;
              background-image: linear-gradient(110deg,rgba(0,49,70,.22),rgba(0,66,73,.50)),var(--w95-wallpaper) !important;
              background-size: cover !important;
            }
            """.trimIndent(),
        )
        assertEquals(f.getValue("clouds.png").absolutePath, s.backgroundImage)
    }

    @Test
    fun roundTwoValuesSurviveTheCache() {
        val dir = tmp.newFolder("css-only")
        val spec = ThemeSpec(layout = cssOnlyLayout(dir))
        assertEquals(spec, ThemeSpecJson.decode(ThemeSpecJson.encode(spec, files = true), files = true))
        assertEquals(spec, ActiveThemeCache.normalize(spec))
        val shared = ThemeSpecJson.decode(ThemeSpecJson.encode(spec))!!.layout!!
        assertNull("a shared preview names no local file", shared.heroImage)
        assertNull(shared.tileFrames)
        assertEquals(5, shared.gridColumns)
    }
}
