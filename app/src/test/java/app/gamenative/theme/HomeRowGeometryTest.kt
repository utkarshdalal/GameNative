package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home row's placement from Steam's box model, with the resting rules of store themes (trimmed, default options,
 * class names in their `module_Name_hash` spelling). Fractions are of the area between Steam's header and footer;
 * "measured" ones come from the themes' store screenshots.
 */
class HomeRowGeometryTest {

    private fun layout(vararg css: String): ThemeLayout =
        ThemeTranslator.translate(css.map { SourceSheet(CssParser.parse(it)) { null } }, SteamClassMap.EMPTY).spec.layout ?: ThemeLayout()

    private val area = CssValues.VIEWPORT_H - HomeRowGeometry.HEADER_PX - HomeRowGeometry.FOOTER_PX

    /** Top and bottom of the covers as fractions of Steam's area. */
    private fun fractions(l: ThemeLayout): Pair<Double, Double> {
        val stock = HomeRowGeometry.STOCK
        val scale = (l.cardScale ?: 1f).toDouble()
        val rowH = HomeRowGeometry.COVER_H * scale
        val anchor = l.shelfRowAnchor?.toDouble() ?: stock.anchor
        val offset = l.shelfRowOffsetPx?.toDouble() ?: stock.offsetPx
        val top = anchor * (area - rowH) + offset
        return top / area to (top + rowH) / area
    }

    private fun assertRow(l: ThemeLayout, anchor: Double, offset: Double) {
        assertEquals(anchor, (l.shelfRowAnchor ?: 0f).toDouble(), 1e-3)
        assertEquals(offset, (l.shelfRowOffsetPx ?: HomeRowGeometry.STOCK.offsetPx.toFloat()).toDouble(), 0.01)
    }

    private fun assertMeasured(l: ThemeLayout, top: Double, bottom: Double) {
        val (t, b) = fractions(l)
        assertEquals("top", top, t, 0.08)
        assertEquals("bottom", bottom, b, 0.08)
    }

    @Test
    fun steamsOwnPlacement() {
        val s = HomeRowGeometry.STOCK
        assertEquals(0.0, s.anchor, 0.0)
        // 54 (container padding) + 28 (header line) + 4 (carousel margin) + 12 (carousel padding) = 98 from the top
        assertEquals(58.0, s.offsetPx, 1e-9)
        assertEquals(98.0, s.rowTop(), 1e-9)
        assertEquals(10.0, s.titleOffsetPx!!, 1e-9)
        assertEquals(1.0, s.scale, 0.0)
        assertFalse(s.titleHidden)
    }

    @Test
    fun whatTheModelCannotFollowKeepsSteamsPlacement() {
        val p = HomeRowGeometry.place(
            mapOf(HomeRowGeometry.Part.CONTAINER to mapOf("position" to HomeRowGeometry.Decl("absolute", false))),
        )
        assertEquals(HomeRowGeometry.STOCK, p)
        val skewed = HomeRowGeometry.place(
            mapOf(HomeRowGeometry.Part.INNER to mapOf("transform" to HomeRowGeometry.Decl("rotate(5deg)", false))),
        )
        assertEquals(HomeRowGeometry.STOCK.offsetPx, skewed.offsetPx, 0.0)
    }

    @Test
    fun aRowThatStopsFollowingTheHeightKeepsItsPlaceAtSteamsHeight() {
        val p = HomeRowGeometry.place(
            mapOf(HomeRowGeometry.Part.CONTAINER to mapOf("padding-top" to HomeRowGeometry.Decl("clamp(54px, 24vh, 160px)", false))),
        )
        assertFalse(p.linear)
        assertEquals(0.0, p.anchor, 0.0)
        assertEquals(128.0 + 44 - 40, p.offsetPx, 1e-6)
    }

    @Test
    fun theStockLayoutWritesNothing() {
        val l = layout(".gamepadhomerecentgames_RecentGamesContainer_2_cRk { padding-top: 54px; }")
        assertNull(l.shelfRowAnchor)
        assertNull(l.shelfRowOffsetPx)
        assertNull(l.shelfTitleOffsetPx)
    }

    private val switchLike = """
        .gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { height: calc(100vh - 80px - 14px - 58px); }
        .gamepadhomerecentgames_RecentGamesInnerContainer_282X0 .basicgamecarousel_BasicGameCarousel_3MdH5 { bottom: 0px; position: absolute !important; }
        .basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { position: absolute; top: -52px; padding-top: 0; }
        .basicgamecarousel_BasicGameCarousel_3MdH5 { padding-top: 85px; }
        .gamepadhomerecentgames_RecentGamesHeader_35iRe { display: none; }
        .basicgamecarousel_BasicGameCarousel_3MdH5 > .ReactVirtualized__Grid__innerScrollContainer { height: 221px !important; }
        .gamepadhome_TabbedContent_cE1Sa { margin-top: 0px; }
    """.trimIndent()

    @Test
    fun switchLikeHome() {
        val l = layout(switchLike)
        // set on the bottom, 83 px above it: room for a friends bar under the row
        assertRow(l, 1.0, -83.0)
        assertEquals("the title's box ends 4 px above the covers", -4f, l.shelfTitleOffsetPx)
        // its "No Friends" option, as on the store screenshot: the row ends 25 px above the footer
        val noFriends = layout(switchLike, ".gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { height: calc(100vh - 80px - 14px); }")
        assertRow(noFriends, 1.0, -25.0)
        assertMeasured(noFriends, 0.506, 0.944)
    }

    @Test
    fun centeredHome() {
        val l = layout(
            """
            @media all and (max-width: 1200px) { .basicgamecarousel_BasicGameCarousel_3MdH5 { height: 320px !important; } }
            .gamepadhomerecentgames_RecentGamesContainer_2_cRk { padding-top: 98px; padding-bottom: 61px; }
            .gamepadhomerecentgames_RecentGamesHeader_35iRe { visibility: hidden; }
            """.trimIndent(),
        )
        assertRow(l, 0.0, 102.0)
        assertNull("the title as on Steam", l.shelfTitleOffsetPx)
        assertMeasured(l, 0.236, 0.675)
    }

    @Test
    fun fullscreenHome() {
        val l = layout(
            """
            .gamepadhomerecentgames_RecentGamesHeader_35iRe { position: relative; top: 10px; font-size: 18px; }
            .gamepadhome_RecentSection_39tNv { height: 100vh; }
            .gamepadhomerecentgames_RecentGamesContainer_2_cRk { padding-top: 0; position: relative; height: 100%; display: flex; flex-direction: row; align-items: center; }
            .gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { transform: scale(1.5) translateX(11.12vw) translateY(-5px); width: 66.5vw; }
            .basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { width: 350px !important; transition-duration: 0.08s; }
            """.trimIndent(),
        )
        assertEquals(1.5f, l.cardScale)
        assertRow(l, 0.5, -48.0)
        assertEquals("Steam's 10 px under the covers, scaled with them", 15f, l.shelfTitleOffsetPx)
        // the focused wide cover, its focus lift taken out
        assertMeasured(l, 0.119, 0.765)
    }

    @Test
    fun centerHomeLayout() {
        val l = layout(
            """
            :root { --shtt: 120px; --shttr: -17px; --shtts: 100%; --shtgr: 36px; }
            .gamepadhomerecentgames_RecentGamesContainer_2_cRk { padding-top: var(--shtt, 54px); }
            .gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { top: var(--shttr); }
            .basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { zoom: var(--shtts); }
            .basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { padding-top: var(--shtgr); }
            @media (min-width: 900px) { .gamepadhomerecentgames_RecentGamesContainer_2_cRk { padding-top: calc(var(--shtt) + 3vh); } }
            """.trimIndent(),
        )
        assertRow(l, 0.0, 107.0)
        assertEquals(32f, l.shelfTitleOffsetPx)
    }

    @Test
    fun emuDeckTheme() {
        val l = layout(
            """
            .RecentSection.Panel { height: 100vh; display: flex; }
            .RecentGamesContainer.Panel { display: flex; }
            .RecentGamesInnerContainer { align-self: flex-end; }
            .basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { display: none; }
            .RecentGamesInnerContainer > div > .Panel { width: 100vw; }
            .ReactVirtualized__Grid.BasicGameCarousel { height: 52vh !important; }
            """.trimIndent(),
        )
        // the inner box (a 52vh carousel) set on the bottom of the full-height section
        assertRow(l, 0.48, 106.16)
        assertEquals(true, l.shelfHideTitle)
        assertNull(l.shelfTitleOffsetPx)
        assertMeasured(l, 0.503, 0.941)
    }

    @Test
    fun switchLikeMenuOfAThemeWithIt() {
        val l = layout(
            """
            .gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { margin-top: 160px; }
            .basicgamecarousel_CarouselGameLabelWrapper_ZkD6W { margin-top: -265px; }
            .basicgamecarousel_BasicGameCarousel_3MdH5 { padding-top: 55px; }
            .gamepadhomerecentgames_RecentGamesHeader_35iRe { display: none; }
            """.trimIndent(),
        )
        assertRow(l, 0.0, 233.0)
        assertEquals(-3.5f, l.shelfTitleOffsetPx)
        assertMeasured(l, 0.506, 0.944)
    }

    private val artHero = """
        :root { --met-game-carousel-height-tens: -50vh; --met-game-carousel-height-ones: -0vh; --met-game-carousel-font-height: -3.5em; --met-hero-height-tens: 0vh; --met-hero-height-ones: 0vh; }
        :root { --met-game-carousel-height-tens: 50vh; --met-game-carousel-font-height: -4em; }
        .gpfocuswithin>.basicgamecarousel_CarouselGameLabelWrapper_ZkD6W, :hover~.basicgamecarousel_CarouselGameLabelWrapper_ZkD6W, .basicgamecarousel_CarouselGameLabelWrapper_ZkD6W.basicgamecarousel_ShowAsHovered_3tdVJ {
            transform: translateY(-0.2em); transition-delay: .4s; visibility: visible; top: var(--met-game-carousel-font-height); position: absolute!important;
        }
        .basicgamecarousel_BasicGameCarousel_3MdH5 { top: -1em; height: 100vh!important; }
        .gamepadhomerecentgames_RecentGamesContainer_2_cRk { margin-top: 0vh; padding-top: 0 }
        .gamepadhome_RecentSection_39tNv { padding: 0px; height: 105vh; }
        .gamepadhomerecentgames_RecentGamesHeader_35iRe { display: none!important }
        .gamepadhomerecentgames_RecentGamesContainer_2_cRk { top: calc(var(--met-hero-height-ones) + var(--met-hero-height-tens)); }
        .gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { top: calc((var(--met-game-carousel-height-ones) + var(--met-game-carousel-height-tens) - calc(var(--met-hero-height-ones) + var(--met-hero-height-tens)))); }
        .gamepadhomerecentgames_RecentGamesContainer_2_cRk { margin-top: -1vh; }
        .basicgamecarousel_BasicGameCarouselItemMediaContainer_1HIFN { bottom: -2.8em; position: absolute!important; }
        .gamepadhomerecentgames_RecentGamesInnerContainer_282X0 { height: 70vh!important; }
    """.trimIndent()

    @Test
    fun artHero() {
        val l = layout(artHero)
        // under the artwork band: pushed down by 49vh (50vh less the container's -1vh) and the covers' 2.8em drop
        assertRow(l, 0.49, 141.76)
        assertNull("its title rule is a focus state; at rest the title is Steam's", l.shelfTitleOffsetPx)
        assertMeasured(l, 0.588, 1.026)
    }

    @Test
    fun tvOsDeck() {
        val l = layout(
            """
            .gamepadhome_BackstackRootTest:not(.BigArtMode) .gamepadhomerecentgames_RecentGamesContainer { padding-top: clamp(54px, 24vh, 210px); }
            .basicgamecarousel_CarouselGameLabel { margin-left: 2px !important; font-size: 17px; }
            """.trimIndent(),
        )
        assertRow(l, 0.24, 71.08)
        assertNull(l.shelfTitleOffsetPx)
        val (top, _) = fractions(l)
        assertTrue(top in 0.28..0.30)
    }
}
