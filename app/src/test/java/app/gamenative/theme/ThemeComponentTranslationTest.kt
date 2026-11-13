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
        assertEquals(listOf("menu", "onMenu", "menuStyle"), s.setFields())
        assertEquals(24f, s.menuStyle!!.cornerRadiusDp)
        assertNull("the title strip's blur is not the menu body's", s.menuStyle!!.blurDp)
        assertEquals(argb("#ffffff"), s.menuStyle!!.focusFill)
        assertEquals(argb("#000000"), s.menuStyle!!.onFocus)
        assertNull(s.cornerRadiusDp)
        assertEquals("the separator and the title strip do not feed the menu", 4, r.appliedRules)
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
    fun specificTextClassesBeatManyCatchAlls() {
        val s = translate(
            """
            .gamepaddialog_FieldLabel_ab12c { color: #f6019d !important; }
            .gamepaddialog_FieldDescription_ab12c { color: #2de2e6 !important; }
            .appdetailsgameinfopanel_Label_ab12c { color: #2de2e6; }
            .achievementslist_AchievementTitle_ab12c { color: #2de2e6; }
            .downloads_Title_ab12c { color: #2de2e6; }
            .gamepadhomefriends_Title_ab12c { color: #2de2e6; }
            .quickaccesscontrols_PanelSectionTitle_ab12c { color: #2de2e6; }
            .basicgamecarousel_SubMessage_ab12c { color: #f6019d; }
            .BasicUI .header_Header_ab12c { color: #2de2e6; }
            .header_Clock_ab12c { color: #2de2e6 !important; }
            .actionbuttonlegenditem_ActionButtonLegend_ab12c .actionbuttonlegenditem_ActionButtonLabel_ab12c { color: #f6019d; }
            """.trimIndent(),
        ).spec
        assertEquals("row labels come from the label class, not the many catch-all titles", argb("#f6019d"), s.text)
        assertEquals(argb("#2de2e6"), s.textMuted)
        assertEquals(argb("#2de2e6"), s.textRoles!!.heading)
        assertEquals(argb("#f6019d"), s.textRoles!!.subtitle)
        assertEquals(argb("#2de2e6"), s.header!!.textColor)
        assertEquals(argb("#f6019d"), s.footer!!.textColor)
    }

    @Test
    fun headerAndFooterKeepTheirOwnLookAndDoNotLeak() {
        val s = translate(
            """
            .header_Header_ab12c::before { background-color: rgba(0, 0, 0, 0.5); }
            .footer_BasicFooter_ab12c { border-top: 2px solid #f6019d; box-shadow: 0px 0px 24px 0px #f6019d; }
            .footer_BasicFooter_ab12c { backdrop-filter: blur(5px); background-color: #00000030; height: 20px; }
            .gamepaddialog_WithBottomSeparatorStandard_ab12c::after { background: #2de2e6; }
            """.trimIndent(),
        ).spec
        assertEquals(argb("#00000080"), s.header!!.color)
        assertNull(s.header!!.borderColor)
        val f = s.footer!!
        assertEquals(argb("#00000030"), f.color)
        assertEquals(argb("#f6019d"), f.borderColor)
        assertEquals(2f, f.borderDp)
        assertEquals(argb("#f6019d"), f.glowColor)
        assertEquals(24f, f.glowDp)
        assertEquals(5f, f.blurDp)
        assertEquals(0.5f, f.heightScale)
        assertEquals("the footer border is not the app's divider color", argb("#2de2e6"), s.outline)
        assertNull("the footer blur does not blur the background image", s.blurDp)
    }

    @Test
    fun togglesKeepGradientHandlesAndOffTrack() {
        val s = translate(
            """
            .gamepaddialog_ToggleRail_ab12c { background: #292929; border: 2px solid #292929; }
            .gamepaddialog_ToggleSwitch_ab12c { background: #363636; }
            .gamepaddialog_On_ab12c > .gamepaddialog_ToggleRail_ab12c { background: linear-gradient(to right, #ccdb76, #76dbda, #db7689); border: none; }
            .gamepaddialog_On_ab12c > .gamepaddialog_ToggleSwitch_ab12c { background: #fff; }
            .gamepaddialog_Toggle_ab12c.gamepaddialog_Disabled_ab12c .gamepaddialog_ToggleSwitch_ab12c { background: #111111; }
            .gamepadslider_SliderTrack_ab12c { background: #ffffff4D; }
            .gamepadslider_SliderHandle_ab12c { background: #fff; }
            button.gamepaddialog_Button_ab12c.DialogButton { background: #0f0f0f; color: #bababa; border-bottom: 2px solid #bababa; }
            button.gamepaddialog_Button_ab12c.DialogButton.gpfocus { background: radial-gradient(circle at top -155px left 35px, #e8e8e8 0% 30%, #b3b3b3 70% 100%); color: #121212; }
            """.trimIndent(),
        ).spec
        val c = s.controls!!
        val on = c.toggleOn!!.layers.single()
        assertEquals(listOf(argb("#ccdb76"), argb("#76dbda"), argb("#db7689")), on.colors)
        assertEquals(90f, on.angleDeg)
        assertEquals(argb("#292929"), c.toggleOff)
        assertEquals(argb("#ffffff"), c.thumbOn)
        assertEquals("the disabled handle is not the off handle", argb("#363636"), c.thumbOff)
        assertEquals(argb("#ffffff4d"), c.sliderTrack)
        assertEquals(argb("#ffffff"), c.sliderThumb)
        assertEquals(argb("#bababa"), c.buttonUnderlineColor)
        assertEquals(2f, c.buttonUnderlineDp)
        val focused = c.buttonFocusFill!!.layers.single()
        assertEquals(ThemeRadial(circle = true, centerX = 0f, centerY = 0f, offsetXDp = 35f, offsetYDp = -155f), focused.radial)
        assertEquals(argb("#121212"), c.onButtonFocus)
        assertEquals(argb("#0f0f0f"), s.button)
    }

    @Test
    fun aPlainHandleRuleStylesBothStates() {
        val c = translate(".gamepaddialog_ToggleSwitch_ab12c { background: #2de2e6; }").spec.controls!!
        assertEquals(argb("#2de2e6"), c.thumbOff)
        assertEquals(argb("#2de2e6"), c.thumbOn)
    }

    @Test
    fun focusRingWidthFillUnderlineAndBlink() {
        val ring = translate(
            """
            .focusring_FocusRing_ab12c { border: 2px solid rgba(255, 255, 255, 0.6); animation: focusring_blinker_3wFMM_alt 1.2s ease-in-out 0.4s 20; }
            """.trimIndent(),
        ).spec.focusStyle!!
        assertEquals(2f, ring.ringDp)
        assertEquals(argb("#ffffff99"), ring.ringColor)
        assertEquals(true, ring.blink)

        val nav = translate(
            """
            [class="mainmenu_ItemOuter_1BbM8"]:has(.gpfocus) { background: #0f0f0f; }
            [class="mainmenu_ItemOuter_1BbM8"]:has(.gpfocus)::after { content: ""; height: 4px; bottom: 0; background: linear-gradient(to right, #40dce7, #ffe666, #ca1c67); }
            .gamepadpagedsettings_PagedSettingsDialog_PageListItem_ab12c:focus .gamepadpagedsettings_PageListItem_Title_ab12c { color: #f5f5f5; }
            """.trimIndent(),
        ).spec.focusStyle!!
        assertEquals(argb("#0f0f0f"), nav.fill!!.color)
        assertEquals(argb("#f5f5f5"), nav.onFill)
        assertEquals(listOf(argb("#40dce7"), argb("#ffe666"), argb("#ca1c67")), nav.underline!!.colors)
        assertEquals(4f, nav.underlineDp)

        val kf = translate(":root { --b: rgba(45, 226, 230, 0.4); } @keyframes appportrait_blinker_2tSbN1 { 50% { border: 2px solid var(--b); } }").spec
        assertEquals(2f, kf.focusStyle!!.ringDp)
        assertEquals(true, kf.focusStyle!!.blink)

        assertNull(
            "text for a focus fill the theme does not set is dropped",
            translate(".gpfocus .dropdownlabel_DropDownLabelTitle_ab12c { color: #121212; }").spec.focusStyle,
        )
    }

    @Test
    fun menuShapeEffectsAndFocusedItem() {
        val s = translate(
            """
            .basiccontextmenu_contextMenuContents_ab12c { background: rgba(0, 0, 0, 0.6); border: 2px solid #2de2e6; box-shadow: 0px 0px 10px 0px #2de2e6; border-radius: 16px; }
            .basiccontextmenu_contextMenuItem_ab12c.basiccontextmenu_Focused_ab12c { background: #67707b; color: black; }
            """.trimIndent(),
        ).spec
        val m = s.menuStyle!!
        assertEquals(16f, m.cornerRadiusDp)
        assertEquals(0.6f, m.opacity!!, 0.01f)
        assertEquals(argb("#2de2e6"), m.borderColor)
        assertEquals(2f, m.borderDp)
        assertEquals(10f, m.glowDp)
        assertEquals(argb("#67707b"), m.focusFill)
        assertEquals("black is 4:1 on this gray, above the floor, so the theme's choice stays", argb("#000000"), m.onFocus)
        assertEquals(argb("#000000"), s.menu)
        assertNull(s.cornerRadiusDp)
    }

    @Test
    fun panelFillKeepsEveryRadialLayer() {
        val s = translate(
            """
            .mainmenu_Menu_ab12c { background: radial-gradient(circle at 20% 0%, #171717 0%, transparent 60%), radial-gradient(circle at 140% 70%, #ec51a3 0%, transparent 60%), #141414; }
            """.trimIndent(),
        ).spec
        val f = s.panelFill!!
        assertEquals(argb("#141414"), f.color)
        assertEquals(2, f.layers.size)
        assertEquals(1.4f, f.layers[1].radial!!.centerX)
        assertEquals("transparent fades the halo's own color", argb("#ec51a300"), f.layers[1].colors[1])
        assertEquals(argb("#141414"), s.surface)
    }

    @Test
    fun aPageColorWithoutAccentGetsANeutralAccent() {
        val s = translate(".gamepadui_BasicHome_ab12c { background: #000; } .gamepadlibrary_GamepadLibrary_ab12c { background: #000; }").spec
        assertEquals(argb("#ffffff"), s.primary)
        val withText = translate(".gamepadui_BasicHome_ab12c { background: #000; } .gamepaddialog_FieldLabel_ab12c { color: #d0d0d0; }").spec
        assertEquals(argb("#d0d0d0"), withText.primary)
        assertEquals("a theme with an accent keeps it", argb("#ff0000"), translate(".gamepadui_BasicHome_ab12c { background: #000; } .DialogButton.Primary { background: #ff0000; }").spec.primary)
        assertNull("no page color, no accent", translate(".focusring_FocusRing_ab12c { border: 2px solid #fff; }").spec.primary)
    }

    @Test
    fun subtitleOutlineAndTextGlow() {
        val s = translate(
            """
            .basicgamecarousel_SubMessage_ab12c, .marquee_Content_ab12c { text-shadow: -1px 1px 2px #000, 1px 1px 2px #000, 1px -1px 0 #000, -1px -1px 0 #000; }
            .gamepaddialog_FieldLabel_ab12c { text-shadow: 0 0 8px #ff00aa; }
            """.trimIndent(),
        ).spec
        val l = s.layout!!
        assertEquals(1f, l.titleStrokeDp)
        assertEquals(2f, l.titleStrokeBlurDp)
        assertEquals(1f, l.subtitleStrokeDp)
        assertEquals(argb("#000000"), l.subtitleStrokeColor)
        assertEquals(2f, l.subtitleStrokeBlurDp)
        assertEquals(argb("#ff00aa"), s.textRoles!!.glowColor)
        assertEquals(8f, s.textRoles!!.glowDp)
    }

    @Test
    fun animationsAreNotTranslated() {
        val r = translate(".appportrait_LibraryItemBox_ab12c { animation: pulse 1s infinite; transition: transform 0.2s; }")
        assertTrue(r.spec.isEmpty)
    }

    @Test
    fun textOnAFillKeepsTheThemeColorWhenReadable() {
        val s = translate(".DialogButton { background: #202020; color: #dddddd; }").spec
        assertEquals(argb("#dddddd"), s.onButton)
    }

    @Test
    fun unreadableTextOnAFillTakesTheThemesOtherTextColor() {
        val s = translate(
            """
            .gamepaddialog_FieldLabel_q1w2e { color: #111111; }
            .gamepadcontextmenu_contextMenuItem_ab12c.gamepadcontextmenu_Focused_ab12c { background: #e9fafe; color: #ffffff; }
            """.trimIndent(),
        ).spec
        assertEquals(argb("#e9fafe"), s.menuStyle!!.focusFill)
        assertEquals(argb("#111111"), s.menuStyle!!.onFocus)
    }

    @Test
    fun unreadableTextWithNoReadableThemeColorIsBlackOrWhite() {
        val light = translate(".gamepadcontextmenu_contextMenuItem_ab12c.gamepadcontextmenu_Focused_ab12c { background: #e9fafe; color: #ffffff; }").spec
        assertEquals(argb("#000000"), light.menuStyle!!.onFocus)
        val pink = translate(".DialogButton.Primary { background: #f6019d; color: #ffffff; }").spec
        assertEquals("white is 4:1 on this pink, above the floor, so the theme's choice stays", argb("#ffffff"), pink.onPrimary)
        val pale = translate(".DialogButton.Primary { background: #ffb3d9; color: #ffffff; }").spec
        assertEquals(argb("#000000"), pale.onPrimary)
    }

    @Test
    fun aTranslucentFillIsJudgedOverWhatIsUnderIt() {
        val s = translate(
            """
            .gamepaddialog_Field_aaaaa.gpfocus { background: rgba(255, 255, 255, 0.15); color: #ffffff; }
            """.trimIndent(),
        ).spec
        assertEquals(argb("#ffffff26"), s.focusStyle!!.fill!!.flatColor)
        assertEquals("a faint white wash on the dark app keeps white text", argb("#ffffff"), s.focusStyle!!.onFill)
    }

    @Test
    fun menuTitleStripDoesNotStyleTheMenuBody() {
        val s = translate(
            ".gamepadcontextmenu_BasicContextMenuHeader_ab12c { background: #ffffff; color: #ff0000; backdrop-filter: blur(20px); }",
        ).spec
        assertNull(s.onMenu)
        assertNull(s.menu)
        assertNull(s.menuStyle?.blurDp)
    }
}
