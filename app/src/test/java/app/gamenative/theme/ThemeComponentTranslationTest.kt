package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeComponentTranslationTest {

    private fun translate(vararg css: String) =
        ThemeTranslator.translate(css.map { SourceSheet(CssParser.parse(it)) { null } }, SteamClassMap.EMPTY)

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    @Test
    fun contextMenuThemeOnlySetsMenuTokens() {
        val r = translate(
            """
            .gamepadcontextmenu_contextMenuContents_ab12c { background: #000000; border-radius: 24px; border: 1px solid #333333; }
            .gamepadcontextmenu_BasicContextMenuHeader_ab12c { background: rgba(255, 255, 255, 0.4); color: #eeeeee; backdrop-filter: blur(20px); }
            .gamepadcontextmenu_contextMenuItem_ab12c { background: #101010; color: #ffffff; }
            .gamepadcontextmenu_contextMenuItem_ab12c.gamepadcontextmenu_Focused_ab12c { background: #ffffff; color: #000000; }
            .gamepadcontextmenu_contextMenuItem_ab12c .dropdownlabel_DropDownLabelTitle_ab12c { color: #ffffff; }
            .gamepadcontextmenu_ContextMenuSeparator_ab12c { background: #222222; }
            """.trimIndent(),
        )
        val s = r.spec
        assertEquals(argb("#000000"), s.menu)
        assertEquals(argb("#ffffff"), s.onMenu)
        assertEquals(listOf("menu", "onMenu"), s.setFields())
        assertEquals(3, r.appliedRules)
    }

    @Test
    fun tilesListRowsAndButtonsGetTheirOwnTokens() {
        val s = translate(
            """
            .appportrait_LibraryItemBox_ab12c { background: #223344; }
            .downloads_SectionItem_ab12c { background: #223344; }
            .DialogButton { background: #445566; color: #ddeeff; }
            .quickaccessmenu_Tab_ab12c { background: #112233; }
            """.trimIndent(),
        ).spec
        assertEquals(argb("#223344"), s.card)
        assertEquals(argb("#445566"), s.button)
        assertEquals(argb("#ddeeff"), s.onButton)
        assertEquals(argb("#112233"), s.surfaceVariant)
        assertNull(s.primary)
        assertNull(s.surface)
    }

    @Test
    fun appWideSurfacesStayAppWide() {
        val s = translate(
            """
            .gamepadui_BasicHome_ab12c { background: #050505; }
            .quickaccessmenu_QuickAccessMenu_ab12c { background: #101010; }
            .mainmenu_Menu_ab12c { background: #101010; }
            .gamepaddialog_GamepadDialogContent_ab12c { background: #050505; }
            """.trimIndent(),
        ).spec
        assertEquals(argb("#050505"), s.background)
        assertEquals(argb("#101010"), s.surface)
        assertEquals(argb("#050505"), s.menu)
    }

    @Test
    fun oneAccentComponentSetsOnlyTheToggle() {
        val s = translate(".gamepaddialog_ToggleRail_ab12c::before { background: #ff8800; }").spec
        assertEquals(argb("#ff8800"), s.toggle)
        assertNull(s.primary)
    }

    @Test
    fun accentComponentsThatAgreeSetThePrimary() {
        val s = translate(
            """
            .gamepaddialog_ToggleRail_ab12c::before { background: #ff8800; }
            .progressbar_Percent_ab12c { background: #ff8800; }
            """.trimIndent(),
        ).spec
        assertEquals(argb("#ff8800"), s.toggle)
        assertEquals(argb("#ff8800"), s.primary)
        assertEquals(argb("#000000"), s.onPrimary)
    }

    @Test
    fun primaryButtonSetsThePrimaryButFocusedOnesDoNot() {
        assertEquals(argb("#22aa44"), translate(".appactionbutton_ButtonChild_ab12c { background: #22aa44; }").spec.primary)
        val focused = translate(".appactionbutton_ButtonChild_ab12c:focus { background: #22aa44; }").spec
        assertNull(focused.primary)
        assertEquals(argb("#22aa44"), focused.focus)
    }

    @Test
    fun gradientFillBecomesAccentGradient() {
        val s = translate(
            """
            :root { --a: #ccdd77; --b: #77dddd; }
            .gamepaddialog_ToggleRail_ab12c::before { background: linear-gradient(to right, var(--a), var(--b)); }
            .gamepadslider_TickActive_ab12c { background: linear-gradient(to right, var(--a), var(--b)); }
            .appactionbutton_ButtonChild_ab12c { background: #111111; }
            """.trimIndent(),
        ).spec
        val g = s.accentGradient!!
        assertEquals(90f, g.angleDeg)
        assertEquals(listOf(argb("#ccdd77"), argb("#77dddd")), g.colors)
        assertEquals(argb("#ccdd77"), s.toggle)
        assertEquals("a neutral primary button gives way to the agreeing gradient", argb("#ccdd77"), s.primary)
    }

    @Test
    fun gradientOnOneComponentKeepsPrimaryUnset() {
        val s = translate(".progressbar_Percent_ab12c { background: linear-gradient(90deg, #ff0000, #0000ff); }").spec
        assertEquals(argb("#ff0000"), s.toggle)
        assertEquals(2, s.accentGradient!!.colors.size)
        assertNull(s.primary)
    }

    @Test
    fun focusGlowFromFocusedShadow() {
        val s = translate(".appportrait_LibraryItemBox_ab12c:focus { box-shadow: 0px 0px 24px 0px #00e5ff; }").spec
        assertEquals(24f, s.focusGlowDp)
        assertEquals(argb("#00e5ff"), s.focus)
        val drop = translate(".DialogButton.gpfocus { filter: drop-shadow(0 0 10px #ff00aa); }").spec
        assertEquals(10f, drop.focusGlowDp)
        assertEquals(argb("#ff00aa"), drop.focus)
    }

    @Test
    fun focusGlowFromSteamFocusKeyframes() {
        val s = translate(
            ":root { --glow: #ff00ff; } @keyframes appportrait_blinker_2tSbN { 50% { border: 2px solid var(--glow); box-shadow: 0px 0px 20px 0px var(--glow); } }",
        ).spec
        assertEquals(20f, s.focusGlowDp)
        assertEquals(argb("#ff00ff"), s.focus)
    }

    @Test
    fun shadowsThatAreNotFocusGlowsAreIgnored() {
        assertNull("not focused", translate(".appportrait_LibraryItemBox_ab12c { box-shadow: 0 0 24px #00e5ff; }").spec.focusGlowDp)
        assertNull("drop shadow below, not a glow", translate(".appportrait_LibraryItemBox_ab12c:focus { box-shadow: 0px 12px 10px 0px #000; }").spec.focusGlowDp)
        assertNull("ring without blur", translate(".appportrait_LibraryItemBox_ab12c:focus { box-shadow: 0 0 0 3px #fff; }").spec.focusGlowDp)
        assertNull("unknown element", translate("._3kDfK9pXq2LmNw8Rz1Ab4.gpfocus { filter: drop-shadow(0 0 10px #fff); }").spec.focusGlowDp)
        assertNull("menu-scoped", translate(".gamepadcontextmenu_contextMenuItem_ab12c:focus { box-shadow: 0 0 10px #fff; }").spec.focusGlowDp)
    }

    @Test
    fun animationsAreNotTranslated() {
        val r = translate(".appportrait_LibraryItemBox_ab12c { animation: pulse 1s infinite; transition: transform 0.2s; }")
        assertTrue(r.spec.isEmpty)
    }
}
