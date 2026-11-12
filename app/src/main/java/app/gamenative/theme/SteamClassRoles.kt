package app.gamenative.theme

import app.gamenative.theme.SteamRole.ACCENT
import app.gamenative.theme.SteamRole.ACCENT_HANDLE
import app.gamenative.theme.SteamRole.BUTTON
import app.gamenative.theme.SteamRole.BUTTON_PRIMARY
import app.gamenative.theme.SteamRole.CARD
import app.gamenative.theme.SteamRole.CONTROL
import app.gamenative.theme.SteamRole.DIVIDER
import app.gamenative.theme.SteamRole.FOCUS
import app.gamenative.theme.SteamRole.FOCUS_WITHIN
import app.gamenative.theme.SteamRole.FOOTER
import app.gamenative.theme.SteamRole.HEADER
import app.gamenative.theme.SteamRole.MENU
import app.gamenative.theme.SteamRole.MENU_ITEM
import app.gamenative.theme.SteamRole.ON_PRIMARY_TEXT
import app.gamenative.theme.SteamRole.PAGE_BACKGROUND
import app.gamenative.theme.SteamRole.PANEL
import app.gamenative.theme.SteamRole.TEXT
import app.gamenative.theme.SteamRole.TEXT_MUTED
import java.util.concurrent.ConcurrentHashMap

enum class SteamRole {
    PAGE_BACKGROUND, PANEL, HEADER, FOOTER, CARD, CONTROL, MENU, MENU_ITEM, BUTTON, BUTTON_PRIMARY, FOCUS, FOCUS_WITHIN,
    ACCENT, ACCENT_HANDLE, DIVIDER, TEXT, TEXT_MUTED, ON_PRIMARY_TEXT,
}

/** Steam elements whose size, visibility, alignment or shape map to a [ThemeLayout] value. */
enum class LayoutRole { TILE, HOME_ROW, TITLE, BADGE, FOOTER, HERO }

data class LayoutMatch(val role: LayoutRole, val priority: Int, val readable: String)

data class RoleMatch(val role: SteamRole, val priority: Int, val readable: String)

/** Readable Steam class name (`module_Name`) -> role; highest priority wins inside one compound. */
object SteamClassRoles {

    private class Pattern(val rx: Regex, val role: SteamRole, val priority: Int)

    private fun p(rx: String, role: SteamRole, prio: Int) = Pattern(Regex(rx), role, prio)

    private val PATTERNS: List<Pattern> =
        listOf(
            p("^gamepadui_BasicHome$", PAGE_BACKGROUND, 5),
            p("^gamepadui_OpaqueBackground$", PAGE_BACKGROUND, 5),
            p("^gamepadhome_TabbedContent$", PAGE_BACKGROUND, 5),
            p("^gamepadlibrary_GamepadLibrary$", PAGE_BACKGROUND, 5),
            p("^gamepadpage_GamepadPage$", PAGE_BACKGROUND, 5),
            p("^gamepadpage_DialogBackground$", PAGE_BACKGROUND, 5),
            p("^basicappdetailssectionstyler_AppDetailsRoot$", PAGE_BACKGROUND, 5),
            p("^downloads_DownloadsPage$", PAGE_BACKGROUND, 5),
            p("^librarysettings_LibrarySettings$", PAGE_BACKGROUND, 4),
            p("^contentmanagement_ContentManagement$", PAGE_BACKGROUND, 4),
            p("^gamepadtabbedpage_TabContents$", PAGE_BACKGROUND, 4),
            p("^gamepadpagedsettings_PagedSettingDialog_ContentColumn$", PAGE_BACKGROUND, 4),
            p("^gamepadsearch_GamepadSearch$", PAGE_BACKGROUND, 4),
            p("^loadingthrobber_Container$", PAGE_BACKGROUND, 3),

            p("^quickaccessmenu_QuickAccessMenu$", PANEL, 6),
            p("^quickaccessmenu_Menu$", PANEL, 6),
            p("^quickaccessmenu_ViewPlaceholder$", PANEL, 5),
            p("^quickaccessmenu_AllTabContents$", PANEL, 5),
            p("^mainmenu_Menu$", PANEL, 6),
            p("^gamepadpagedsettings_PagedSettingsDialog_PageListColumn$", PANEL, 5),
            p("^audio_VolumePopin$", PANEL, 4),
            p("^notificationcontent_(Short|Standard|Pinned)Template$", PANEL, 3),
            p("^shorttemplates_ShortTemplate$", PANEL, 3),

            p("^header_Header$", HEADER, 5),
            p("^gamepadtabbedpage_TabHeaderRowWrapper$", HEADER, 5),
            p("^quickaccessmenu_Title$", HEADER, 4),
            p("^footer_BasicFooter$", FOOTER, 5),
            p("^footer_.*Footer$", FOOTER, 4),

            p("^appportrait_LibraryItemBox$", CARD, 6),
            p("^gamecapsule_GameCapsule$", CARD, 5),
            p("^libraryassetimage_GreyBackground$", CARD, 3),
            p("^libraryassetimage_Container$", CARD, 3),
            p("^appactivityday_StoreGameCapsule$", CARD, 3),
            p("^allcollections_Collection$", CARD, 4),
            p("^gamepadpagedsettings_PagedSettingsDialog_PageListItem$", CARD, 4),
            p("^downloads_SectionItem$", CARD, 4),
            p("^achievementslist_AchievementListItemBase$", CARD, 3),
            p("^overlayguides_GuideListItemBase$", CARD, 3),
            p("^mandatoryupdate_NavigationMenuItem$", CARD, 2),
            p("^quickaccessmenu_Tab$", CONTROL, 3),
            p("^gamepadtabbedpage_Tab$", CONTROL, 3),
            p("^gamepaddialog_Field$", CONTROL, 2),

            p("^gamepadcontextmenu_contextMenuContents$", MENU, 6),
            p("^basiccontextmenu_contextMenuContents$", MENU, 6),
            p("^gamepaddialog_GamepadDialogContent$", MENU, 5),
            p("^DialogContent$", MENU, 4),
            p("^steamdeckcompatfilter_CompatFilterDialog$", MENU, 4),
            p("^dropdown_DialogDropDownMenu$", MENU, 4),
            p("^gamepadcontextmenu_contextMenuItem$", MENU_ITEM, 4),
            p("^basiccontextmenu_contextMenuItem$", MENU_ITEM, 4),
            p("^contextMenuItem$", MENU_ITEM, 3),
            p("^gamepadcontextmenu_BasicContextMenuHeader$", MENU_ITEM, 4),
            p("^gamepadcontextmenu_ContextMenuSeparator$", MENU_ITEM, 5),

            p("^DialogButton$", BUTTON, 5),
            p("^gamepaddialog_Button$", BUTTON, 5),
            p("^downloads_Button$", BUTTON, 4),
            p("^appdetailsplaysection_MenuButton$", BUTTON, 4),
            p("^gamepaddropdown_DropDownControlButton$", BUTTON, 3),
            p("^Primary$", BUTTON_PRIMARY, 7),
            p("^appactionbutton_ButtonChild$", BUTTON_PRIMARY, 6),
            p("^appactionbutton_PlayButton$", BUTTON_PRIMARY, 6),
            p("^appactionbutton_Green$", BUTTON_PRIMARY, 6),
            p("^basicappdetailssectionstyler_AppActionButton$", BUTTON_PRIMARY, 5),
            p("^gamepadcontextmenu_(Positive|Emphasis)$", BUTTON_PRIMARY, 6),

            p("^gpfocus$", FOCUS, 8),
            p("^gpfocuswithin$", FOCUS_WITHIN, 1),
            p("^focusring_FocusRing$", FOCUS, 8),
            p("^gamepadcontextmenu_Focused$", FOCUS, 8),
            p("^basiccontextmenu_Focused$", FOCUS, 8),
            p("^gamepadcontextmenu_Selected$", FOCUS, 7),
            p("^quickaccessmenu_Selected$", FOCUS, 7),
            p("^gamepadtabbedpage_Selected$", FOCUS, 7),
            p("^gamepaddialog_HighlightOnFocus$", FOCUS, 6),
            p("^mandatoryupdate_FocusedColumn$", FOCUS, 6),
            p("^gamepadcontextmenu_active$", FOCUS, 6),

            p("^gamepaddialog_ToggleRail$", ACCENT, 6),
            p("^gamepaddialog_ToggleSwitch$", ACCENT_HANDLE, 6),
            p("^gamepadslider_SliderTrack$", ACCENT, 5),
            p("^gamepadslider_TickActive$", ACCENT, 6),
            p("^gamepadslider_SliderHandle$", ACCENT_HANDLE, 5),
            p("^progressbar_Percent$", ACCENT, 5),
            p("^header_HeaderDownloadProgressBar$", ACCENT, 5),
            p("^appdetailsplaysection_DetailsProgressBar$", ACCENT, 5),
            p("^appportrait_UninstalledProgressBar$", ACCENT, 5),
            p("^appdetailsachievementssection_AchievementProgress$", ACCENT, 4),
            p("^mainmenu_ActiveDot$", ACCENT, 5),
            p("^mainmenuapprunning_ActiveDot$", ACCENT, 5),
            p("^appportrait_AppPortraitBanner$", ACCENT, 3),

            p("^gamepaddialog_WithBottomSeparatorStandard$", DIVIDER, 5),
            p("^gamepaddialog_WithBottomSeparator$", DIVIDER, 5),
            p("^mediapage_DividerLine$", DIVIDER, 4),
            p("^basicgamecarousel_FeaturedSeparator$", DIVIDER, 4),

            p("^gamepaddialog_FieldLabel$", TEXT, 4),
            p("^dropdownlabel_DropDownLabelTitle$", TEXT, 4),
            p("^appactionbutton_ButtonText$", ON_PRIMARY_TEXT, 5),
            p("^gamepadpagedsettings_PageListItem_Title$", TEXT, 4),
            p("^mainmenu_ItemLabel$", TEXT, 4),
            p("^marquee_Content$", TEXT, 2),
            p("^gamepaddialog_FieldDescription$", TEXT_MUTED, 4),
            p("^dropdownlabel_DropDownLabel(Upper)?Description$", TEXT_MUTED, 4),
            p("^basicgamecarousel_SubMessage$", TEXT_MUTED, 3),
            p("^[a-z0-9]+_[A-Za-z_]*(Title|Label|Header|HeaderText|Headline|Name)$", TEXT, 1),
            p("^[a-z0-9]+_[A-Za-z_]*(Description|Desc|SubTitle|Subtitle|SubHeader|Percent|Date|Status|Detail)$", TEXT_MUTED, 1),
        )

    private class LayoutPattern(val rx: Regex, val role: LayoutRole, val priority: Int)

    private fun l(rx: String, role: LayoutRole, prio: Int) = LayoutPattern(Regex(rx), role, prio)

    private val LAYOUT_PATTERNS: List<LayoutPattern> =
        listOf(
            l("^basicgamecarousel_BasicGameCarouselItem(MediaContainer)?$", LayoutRole.TILE, 6),
            l("^appportrait_LibraryItemBox$", LayoutRole.TILE, 4),
            l("^appportrait_PortraitImage$", LayoutRole.TILE, 4),
            l("^gamecapsule_GameCapsule$", LayoutRole.TILE, 4),
            l("^libraryassetimage_(Container|GreyBackground)$", LayoutRole.TILE, 3),
            l("^[a-z]+_GameIcon$", LayoutRole.TILE, 3),
            l("^mainmenu_RunningAppIcon$", LayoutRole.TILE, 3),

            l("^basicgamecarousel_BasicGameCarousel$", LayoutRole.HOME_ROW, 5),
            l("^gamepadhomerecentgames_RecentGamesInnerContainer$", LayoutRole.HOME_ROW, 5),

            l("^appportrait_(PortraitMessage|Message|CarouselItemLabel|LibraryItemBoxTitle)$", LayoutRole.TITLE, 4),
            l("^basicgamecarousel_CarouselGameLabel$", LayoutRole.TITLE, 4),
            l("^marquee_Content$", LayoutRole.TITLE, 3),

            l("^appportrait_(SteamDeckCompatIcon|LibraryItemIcons|UninstalledIcon|LibraryItemBoxSubscript|LibraryItemUpdateBadge)$", LayoutRole.BADGE, 4),
            l("^gamecapsule_(DeckCompat|InLibrary)$", LayoutRole.BADGE, 4),

            l("^footer_BasicFooter$", LayoutRole.FOOTER, 5),
            l("^footer_FooterLegend$", LayoutRole.FOOTER, 4),

            l("^sharedappdetailsheader_(TopCapsule|HeaderBackgroundImage)$", LayoutRole.HERO, 5),
        )

    private val layoutCache = ConcurrentHashMap<String, Any>()

    fun layoutRoleOf(readable: String): LayoutMatch? {
        val hit = layoutCache.getOrPut(readable) {
            var best: LayoutPattern? = null
            for (pt in LAYOUT_PATTERNS) if (pt.rx.containsMatchIn(readable) && (best == null || pt.priority > best.priority)) best = pt
            best?.let { LayoutMatch(it.role, it.priority, readable) } ?: NONE
        }
        return hit as? LayoutMatch
    }

    /** Pseudo-classes that put a rule into the focus state. */
    val STATE_PSEUDO = listOf(":focus", ":hover", ":active", ":focus-within", ":focus-visible", ":checked")

    /** Steam's own root variables -> the token keys an override of them feeds. */
    val STEAM_VAR_TOKENS: Map<String, List<String>> = mapOf(
        "--gpColor-Blue" to listOf("primary", "focus"),
        "--gpColor-BlueHi" to listOf("focus"),
        "--gpColor-ChalkyBlue" to listOf("primary"),
        "--gpSystemDarkestGrey" to listOf("background"),
        "--gpSystemDarkerGrey" to listOf("surface"),
        "--gpSystemDarkGrey" to listOf("surfaceVariant"),
        "--gpSystemLightestGrey" to listOf("text"),
        "--gpSystemLighterGrey" to listOf("textMuted"),
        "--gpSystemLightGrey" to listOf("textMuted"),
        "--gpStoreDarkerGrey" to listOf("background"),
        "--gpCorner-Medium" to listOf("cornerRadius"),
        "--gpCorner-Large" to listOf("cornerRadius"),
        "--gpGradient-LibraryBackground" to listOf("backgroundGradient"),
        "--gpBackground-DarkMedium" to listOf("surface"),
    )

    private val cache = ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    fun roleOf(readable: String): RoleMatch? {
        val hit = cache.getOrPut(readable) {
            var best: Pattern? = null
            for (pt in PATTERNS) if (pt.rx.containsMatchIn(readable) && (best == null || pt.priority > best.priority)) best = pt
            best?.let { RoleMatch(it.role, it.priority, readable) } ?: NONE
        }
        return hit as? RoleMatch
    }

    /** Names other than role classes that the translator reads: `_On` state classes, focus animations and layout elements. */
    fun isExtraInteresting(readable: String): Boolean =
        readable.endsWith("_On") || FOCUS_ANIMATION_RE.containsMatchIn(readable) || layoutRoleOf(readable) != null

    val FOCUS_ANIMATION_RE = Regex("(Outline|Focus|Glow|Blink|Border)", RegexOption.IGNORE_CASE)
}
