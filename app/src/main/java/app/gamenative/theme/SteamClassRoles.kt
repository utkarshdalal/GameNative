package app.gamenative.theme

import app.gamenative.theme.SteamRole.ACCENT
import app.gamenative.theme.SteamRole.ACCENT_HANDLE
import app.gamenative.theme.SteamRole.BUTTON
import app.gamenative.theme.SteamRole.BUTTON_PRIMARY
import app.gamenative.theme.SteamRole.CARD
import app.gamenative.theme.SteamRole.DIVIDER
import app.gamenative.theme.SteamRole.FOCUS
import app.gamenative.theme.SteamRole.FOCUS_WITHIN
import app.gamenative.theme.SteamRole.FOOTER
import app.gamenative.theme.SteamRole.HEADER
import app.gamenative.theme.SteamRole.ON_PRIMARY_TEXT
import app.gamenative.theme.SteamRole.PAGE_BACKGROUND
import app.gamenative.theme.SteamRole.PANEL
import app.gamenative.theme.SteamRole.TEXT
import app.gamenative.theme.SteamRole.TEXT_MUTED
import java.util.concurrent.ConcurrentHashMap

enum class SteamRole {
    PAGE_BACKGROUND, PANEL, HEADER, FOOTER, CARD, BUTTON, BUTTON_PRIMARY, FOCUS, FOCUS_WITHIN,
    ACCENT, ACCENT_HANDLE, DIVIDER, TEXT, TEXT_MUTED, ON_PRIMARY_TEXT,
}

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
            p("^gamepadcontextmenu_contextMenuContents$", PANEL, 6),
            p("^basiccontextmenu_contextMenuContents$", PANEL, 6),
            p("^gamepaddialog_GamepadDialogContent$", PANEL, 5),
            p("^DialogContent$", PANEL, 4),
            p("^gamepadpagedsettings_PagedSettingsDialog_PageListColumn$", PANEL, 5),
            p("^steamdeckcompatfilter_CompatFilterDialog$", PANEL, 4),
            p("^audio_VolumePopin$", PANEL, 4),
            p("^notificationcontent_(Short|Standard|Pinned)Template$", PANEL, 3),
            p("^shorttemplates_ShortTemplate$", PANEL, 3),

            p("^header_Header$", HEADER, 5),
            p("^gamepadtabbedpage_TabHeaderRowWrapper$", HEADER, 5),
            p("^quickaccessmenu_Title$", HEADER, 4),
            p("^gamepadcontextmenu_BasicContextMenuHeader$", HEADER, 4),
            p("^footer_BasicFooter$", FOOTER, 5),
            p("^footer_.*Footer$", FOOTER, 4),

            p("^appportrait_LibraryItemBox$", CARD, 6),
            p("^gamecapsule_GameCapsule$", CARD, 5),
            p("^libraryassetimage_GreyBackground$", CARD, 3),
            p("^libraryassetimage_Container$", CARD, 3),
            p("^appactivityday_StoreGameCapsule$", CARD, 3),
            p("^allcollections_Collection$", CARD, 4),
            p("^gamepadcontextmenu_contextMenuItem$", CARD, 4),
            p("^basiccontextmenu_contextMenuItem$", CARD, 4),
            p("^contextMenuItem$", CARD, 3),
            p("^gamepadpagedsettings_PagedSettingsDialog_PageListItem$", CARD, 4),
            p("^downloads_SectionItem$", CARD, 4),
            p("^achievementslist_AchievementListItemBase$", CARD, 3),
            p("^overlayguides_GuideListItemBase$", CARD, 3),
            p("^mandatoryupdate_NavigationMenuItem$", CARD, 2),
            p("^quickaccessmenu_Tab$", CARD, 3),
            p("^gamepadtabbedpage_Tab$", CARD, 3),
            p("^gamepaddialog_Field$", CARD, 2),

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
            p("^gamepadcontextmenu_ContextMenuSeparator$", DIVIDER, 5),
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

    /** Names other than role classes that the translator reads: `_On` state classes and focus animations. */
    fun isExtraInteresting(readable: String): Boolean = readable.endsWith("_On") || FOCUS_ANIMATION_RE.containsMatchIn(readable)

    val FOCUS_ANIMATION_RE = Regex("(Outline|Focus|Glow|Blink|Border)", RegexOption.IGNORE_CASE)
}
