package app.gamenative.theme

data class TranslationResult(
    val spec: ThemeSpec,
    /** Style rules in the injected CSS after nesting is flattened. */
    val totalRules: Int,
    /** Rules whose values ended up in [spec]. */
    val appliedRules: Int,
    /** The library layout the theme is drawn for: "row" when its rules reach Steam's home, "grid" for the library grid only. */
    val defaultLayout: String? = null,
)

/**
 * Turns CSS Loader theme CSS (written for Steam's UI) into [ThemeSpec] tokens: selector subjects are mapped
 * to roles through [SteamClassRoles], every role declaration votes for a token, and the best-scored value wins.
 * `--gn-*` root variables set a token directly.
 */
object ThemeTranslator {

    private val GLOBAL_SELECTORS = setOf(":root", "html", "body", "*", ":root *", "body *", "html body")
    private val STEAM_DEFAULT_FONTS = setOf("motiva sans", "arial", "sans-serif", "helvetica", "inherit", "initial", "unset")
    private val CLASS_RE = Regex("\\.(-?[_a-zA-Z][_a-zA-Z0-9-]*)")
    private val ATTR_CLASS_RE = Regex("\\[\\s*class\\s*([*^$|~]?=)\\s*[\"']?([^\"'\\]]+)[\"']?\\s*]")
    private val NOT_RE = Regex(":not\\((?:[^()]|\\([^()]*\\))*\\)")
    private val BRACKET_RE = Regex("\\[[^\\]]*]")
    private val QUOTED_RE = Regex("['\"]([^'\"]+)['\"]")
    private val KEYFRAME_STEAM_RE = Regex("^([a-z0-9]+)_([A-Za-z0-9]+)_[A-Za-z0-9]{5,6}$")
    private val TOGGLE_TRACK_RE = Regex("_(ToggleRail|SliderTrack)$")
    private val PAGE_LIKE_RE = Regex("(Background|Page|Root|Body|Content|Container|Dialog)\\b")
    private val BG_PROPS = setOf("background", "background-color", "background-image")
    private val LINE_PROPS = setOf(
        "border", "border-color", "outline", "outline-color", "box-shadow", "border-bottom",
        "border-top", "border-left", "border-right", "border-bottom-color", "filter",
    )

    /** `--gn-*` variable -> token key. */
    val GN_VARIABLES: Map<String, String> = linkedMapOf(
        "--gn-background" to "background",
        "--gn-surface" to "surface",
        "--gn-surface-variant" to "surfaceVariant",
        "--gn-primary" to "primary",
        "--gn-on-primary" to "onPrimary",
        "--gn-text" to "text",
        "--gn-text-muted" to "textMuted",
        "--gn-outline" to "outline",
        "--gn-focus" to "focus",
        "--gn-bar" to "bar",
        "--gn-corner-radius" to "cornerRadius",
        "--gn-font" to "font",
        "--gn-background-image" to "backgroundImage",
        "--gn-background-gradient" to "backgroundGradient",
        "--gn-panel-opacity" to "panelOpacity",
        "--gn-blur" to "blur",
        "--gn-card" to "card",
        "--gn-button" to "button",
        "--gn-on-button" to "onButton",
        "--gn-toggle" to "toggle",
        "--gn-menu" to "menu",
        "--gn-on-menu" to "onMenu",
        "--gn-accent-gradient" to "accentGradient",
        "--gn-focus-glow" to "focusGlow",
        "--gn-card-scale" to "cardScale",
        "--gn-library-layout" to "libraryLayout",
        "--gn-title-align" to "titleAlign",
        "--gn-title-stroke" to "titleStroke",
        "--gn-hide-badges" to "hideBadges",
        "--gn-hide-footer-hints" to "hideFooterHints",
        "--gn-hero-scale" to "heroScale",
        "--gn-card-tilt" to "cardTilt",
        "--gn-card-corner-radius" to "cardCornerRadius",
        "--gn-icon-corner-radius" to "iconCornerRadius",
        "--gn-subtitle-stroke" to "subtitleStroke",
        "--gn-shelf-wide-first" to "shelfWideFirst",
        "--gn-shelf-row-position" to "shelfRowPosition",
        "--gn-shelf-title-above" to "shelfTitleAbove",
        "--gn-shelf-hide-title" to "shelfHideTitle",
        "--gn-shelf-hide-subtitle" to "shelfHideSubtitle",
        "--gn-card-tilt-after" to "cardTiltAfter",
        "--gn-card-turn" to "cardTurn",
        "--gn-card-turn-after" to "cardTurnAfter",
        "--gn-card-turn-perspective" to "cardTurnPerspective",
        "--gn-card-focused-flat" to "cardFocusedFlat",
        "--gn-header" to "headerColor",
        "--gn-header-fill" to "headerFill",
        "--gn-header-border" to "headerBorder",
        "--gn-header-glow" to "headerGlow",
        "--gn-header-blur" to "headerBlur",
        "--gn-header-text" to "headerText",
        "--gn-header-height" to "headerHeight",
        "--gn-footer" to "footerColor",
        "--gn-footer-fill" to "footerFill",
        "--gn-footer-border" to "footerBorder",
        "--gn-footer-glow" to "footerGlow",
        "--gn-footer-blur" to "footerBlur",
        "--gn-footer-text" to "footerText",
        "--gn-footer-height" to "footerHeight",
        "--gn-footer-glyph-invert" to "footerGlyphInvert",
        "--gn-heading" to "heading",
        "--gn-subtitle" to "subtitle",
        "--gn-text-glow" to "textGlow",
        "--gn-toggle-on" to "toggleOn",
        "--gn-toggle-off" to "toggleOff",
        "--gn-thumb-on" to "thumbOn",
        "--gn-thumb-off" to "thumbOff",
        "--gn-slider-track" to "sliderTrack",
        "--gn-slider-thumb" to "sliderThumb",
        "--gn-button-fill" to "buttonFill",
        "--gn-button-focus" to "buttonFocusFill",
        "--gn-on-button-focus" to "onButtonFocus",
        "--gn-button-underline" to "buttonUnderline",
        "--gn-tab" to "tabFill",
        "--gn-on-tab" to "onTab",
        "--gn-focus-ring" to "focusRing",
        "--gn-focus-ring-color" to "focusRingColor",
        "--gn-focus-fill" to "focusFill",
        "--gn-on-focus-fill" to "focusText",
        "--gn-focus-underline" to "focusUnderline",
        "--gn-focus-underline-height" to "focusUnderlineH",
        "--gn-focus-blink" to "focusBlink",
        "--gn-menu-radius" to "menuRadius",
        "--gn-menu-fill" to "menuFill",
        "--gn-menu-opacity" to "menuOpacity",
        "--gn-menu-blur" to "menuBlur",
        "--gn-menu-border" to "menuBorder",
        "--gn-menu-glow" to "menuGlow",
        "--gn-menu-focus" to "menuFocus",
        "--gn-on-menu-focus" to "onMenuFocus",
        "--gn-panel-fill" to "panelFill",
        "--gn-text-size" to "fontScale",
        "--gn-title-size" to "titleScale",
        "--gn-footer-text-size" to "footerScale",
        "--gn-heading-weight" to "headingWeight",
        "--gn-selected-weight" to "selectedWeight",
        "--gn-letter-spacing" to "letterSpacing",
        "--gn-heading-case" to "headingCase",
        "--gn-tab-case" to "tabCase",
        "--gn-footer-case" to "footerCase",
        "--gn-text-case" to "textCase",
        "--gn-hero-opacity" to "heroOpacity",
        "--gn-hero-blur" to "heroBlur",
        "--gn-hero-saturation" to "heroSaturation",
        "--gn-hero-overlay" to "heroOverlay",
        "--gn-hero-fade" to "heroFade",
        "--gn-fill-slide" to "fillSlide",
        "--gn-panel-slide" to "panelSlide",
        "--gn-focus-underline-slide" to "underlineSlide",
    )

    private val TYPOGRAPHY_KEYS = listOf(
        "fontScale", "titleScale", "footerScale", "headingWeight", "selectedWeight", "letterSpacing", "headingCase", "tabCase", "footerCase",
        "textCase",
    )
    private val HERO_KEYS = listOf("heroOpacity", "heroBlur", "heroSaturation", "heroOverlay", "heroFade")
    private val MOTION_KEYS = listOf("fillSlide", "panelSlide", "underlineSlide")

    /** Token keys of the nested [ThemeSpec] values -> the [ThemeSpec.setFields] name they belong to. */
    private val NESTED_FIELD_KEYS: Map<String, String> = buildMap {
        for (bar in listOf("header", "footer")) {
            for (k in listOf("Color", "Fill", "Border", "Glow", "Blur", "Text", "Height")) put(bar + k, bar)
        }
        for (k in listOf("heading", "subtitle", "textGlow")) put(k, "textRoles")
        for (k in listOf(
            "toggleOn", "toggleOff", "thumbOn", "thumbOff", "sliderTrack", "sliderThumb",
            "buttonFill", "buttonFocusFill", "onButtonFocus", "buttonUnderline", "tabFill", "onTab",
        )) put(k, "controls")
        for (k in listOf("focusRing", "focusRingColor", "focusFill", "focusText", "focusUnderline", "focusUnderlineH", "focusBlink")) put(k, "focusStyle")
        for (k in listOf("menuRadius", "menuFill", "menuOpacity", "menuBlur", "menuBorder", "menuGlow", "menuFocus", "onMenuFocus")) put(k, "menuStyle")
        put("panelFill", "panelFill")
        for (k in TYPOGRAPHY_KEYS) put(k, "typography")
        for (k in HERO_KEYS) put(k, "hero")
        for (k in MOTION_KEYS) put(k, "motion")
    }

    private val COLOR_KEYS = listOf(
        "background", "surface", "surfaceVariant", "primary", "onPrimary", "text", "textMuted", "outline", "focus", "bar",
        "card", "button", "onButton", "toggle", "menu", "onMenu",
    )
    private val LAYOUT_KEYS = listOf(
        "cardScale", "libraryLayout", "titleAlign", "titleStroke", "heroScale", "cardTilt", "cardCornerRadius",
        "iconCornerRadius", "subtitleStroke", "shelfWideFirst", "footerGlyphInvert", "shelfRowPosition", "shelfTitleAbove",
        "shelfHideTitle", "shelfHideSubtitle", "cardTiltAfter", "cardTurn", "cardTurnAfter", "cardTurnPerspective", "cardFocusedFlat",
    ) + SIMPLE_EXTRA_KEYS
    /** Layout values read off a single declaration that have no `--gn-*` variable. */
    private val SIMPLE_EXTRA_KEYS
        get() = listOf(
            "shelfRowImage", "cardRestBrightness", "cardRestSaturation", "cardFocusedScale", "shelfFitAll", "shelfHeaderText", "gridColumns",
            "gridTileAspect", "gridTitleBelow", "uninstalledSaturation", "uninstalledOpacity", "uninstalledIcon", "hideTabCounts", "heroImage",
            "heroImageTile", "heroImageScale", "heroHeightFraction", "hideTopBarSearch", "cardFocusPop", "cardFocusPopWide",
        )
    private val COMPONENT_KEYS = setOf("card", "button", "toggle", "menu", "onMenu", "onButton")

    /** The theme's other text colors, tried in order when its text on a fill is unreadable. */
    private val TEXT_ALTERNATIVES = listOf("text", "onMenu", "textMuted")
    private val MENU_MODULES = listOf("gamepadcontextmenu_", "basiccontextmenu_")
    private val BARE_ELEMENT_RE = Regex("^[a-zA-Z][a-zA-Z0-9-]*(:[a-z-]+(\\([^()]*\\))?)*$")
    private val KEYFRAME_SHADOW_RE = Regex("(box-shadow|filter)\\s*:\\s*([^;}]+)", RegexOption.IGNORE_CASE)
    private val KEYFRAME_BORDER_RE = Regex("(?<![-\\w])(border|border-width|outline|outline-width)\\s*:\\s*([^;}]+)", RegexOption.IGNORE_CASE)
    private val HOVER_STATE_RE = Regex("(ShowAsHovered|Hovered|PortraitHover)$")
    private val DISABLED_RE = Regex("Disabled|\\[disabled]|:disabled")
    private val FOOTER_TEXT_RE = Regex("^(footer|actionbuttonlegenditem)_")
    private val TEXT_ROLES = setOf(SteamRole.TEXT, SteamRole.TEXT_MUTED)
    private const val MAX_UNDERLINE_PX = 8.0
    private const val SUBTITLE_CLASS = "basicgamecarousel_SubMessage"
    private const val SETTINGS_PAGE_LIST_ITEM = "gamepadpagedsettings_PagedSettingsDialog_PageListItem"

    /** Height of Steam's top bar and button-hint bar on the reference viewport. */
    private const val STEAM_BAR_PX = 40.0
    private val RING_PROPS = setOf("border", "border-width", "outline", "outline-width")
    private val BLINK_RE = Regex("blink", RegexOption.IGNORE_CASE)

    /** Text elements whose font-size sets a size token, with Steam's own size for them in px. */
    private val STEAM_FONT_PX: Map<String, Pair<String, Double>> = mapOf(
        "gamepaddialog_FieldLabel" to ("fontScale" to 16.0),
        "gamepaddialog_FieldDescription" to ("fontScale" to 12.0),
        "dropdownlabel_DropDownLabelTitle" to ("fontScale" to 16.0),
        "DialogButton" to ("fontScale" to 16.0),
        "gamepaddialog_Button" to ("fontScale" to 16.0),
        "mainmenu_ItemLabel" to ("fontScale" to 18.0),
        "appportrait_Message" to ("titleScale" to 18.0),
        "actionbuttonlegenditem_ActionButtonLabel" to ("footerScale" to 12.0),
    )
    private val TYPE_PROPS = setOf("font-size", "font-weight", "letter-spacing", "text-transform")

    /** The selected tab or menu item (not the focused one). */
    private val SELECTED_RE = Regex("_(Selected|Active)$")
    private val TAB_LABEL_RE = Regex("^(gamepadtabbedpage_Tab|gamepadtabbedpage_TabTitle|quickaccessmenu_Tab)$")

    /** The selected tab of Steam's tabbed pages (the library's tab row), apart from the focused one. */
    private const val SELECTED_TAB_CLASS = "gamepadtabbedpage_Selected"
    private const val FOOTER_GLYPH_CLASS = "actionbuttonlegenditem_ActionButtonGlyph"

    /** Upper-casing this many kinds of text (rows, buttons, menus, tabs, ...) is upper-casing all of it. */
    private const val ALL_UPPERCASE_KINDS = 4
    private val DURATION_RE = Regex("(?<![\\w.-])(\\d*\\.?\\d+)(ms|s)(?![\\w-])", RegexOption.IGNORE_CASE)
    private val SLIDE_KEYFRAME_RE = Regex("background-position", RegexOption.IGNORE_CASE)

    /** Steam darkens the home artwork to 50 %, the app default overlay. */
    private const val STEAM_HERO_BRIGHTNESS = 0.5
    private const val BODY_FONT_PX = 16.0

    /** Steam's game page hero height on the reference viewport: calc(80px + 20vw). */
    private const val DEFAULT_HERO_PX = 80 + 0.2 * CssValues.VIEWPORT_W

    /** A selector reaching into Steam's home shelf. */
    private val HOME_SHELF_SCOPE = Regex("(^|[\\s.>+~])(basicgamecarousel_|gamepadhomerecentgames_|gamepadhome_RecentSection)")

    /** The wide first capsule of Steam's home shelf. */
    private val FEATURED_TILE_RE = Regex("(basicgamecarousel|appportrait)_Featured(?![A-Za-z])")

    private const val MAX_TILT_DEG = 15f

    /** A cover turned further is seen edge-on. */
    private const val MAX_TURN_DEG = 80f

    /** Smaller scale changes on tiles are focus pops, not a resize. */
    private const val MIN_SCALE_CHANGE = 0.05

    /** Modules of Steam's home screen and of its library grid; a theme with rules there is drawn for that screen. */
    private val HOME_MODULES = listOf("gamepadhome_", "gamepadhomerecentgames_", "basicgamecarousel_")
    private val GRID_MODULES = listOf("appgrid_", "gamepadlibrary_")

    private val SHELF_ROW_POSITIONS = setOf("top", "center", "bottom")
    private const val RECENT_CONTAINER = "gamepadhomerecentgames_RecentGamesContainer"
    private const val RECENT_INNER = "gamepadhomerecentgames_RecentGamesInnerContainer"
    private const val HOME_CAROUSEL = "basicgamecarousel_BasicGameCarousel"
    private val SHELF_TITLE_CLASSES = setOf("basicgamecarousel_CarouselGameLabel", "basicgamecarousel_CarouselGameLabelWrapper")
    private val FEATURED_CLASSES = setOf("basicgamecarousel_Featured", "appportrait_FeaturedCapsule")

    /** Steam's wide first capsule on the home shelf; one half as wide or less is no longer the wide tile. */
    private const val STEAM_FEATURED_PX = 426.837

    /** The cover box Steam lifts toward the viewer when focused, under the perspective its parent sets. */
    private const val COVER_BOX = "appportrait_LibraryItemBox"
    private const val STEAM_FOCUS_PERSPECTIVE_PX = 300.0
    private val FOCUS_MARK_RE = Regex(":focus|gpfocus|\\[tabindex")

    /** Height of a cover on Steam's home row (a 2:3 capsule 133px wide). */
    private const val STEAM_COVER_PX = 199.5

    /** Pushing the home row this far down from the top puts it at the bottom of the screen. */
    private const val ROW_TO_BOTTOM_PX = 160.0

    /** Top padding of the home row's container that centers the row (Steam's own is far smaller). */
    private const val ROW_CENTER_PADDING_PX = 98.0

    /** Lifting the game title this far moves it above the covers, about a home cover's height. */
    private const val TITLE_ABOVE_PX = 200.0

    /** The copy of the focused cover's artwork Steam draws behind it for its glow. */
    private const val GLOW_CLASS = "basicgamecarousel_CarouselCapsuleBackgroundGlow"

    /** The gradient Steam lays over the bottom of the home artwork. */
    private const val FADE_CLASS = "gamepadhomerecentgames_RecentGamesBackgroundFadeGradient"

    /** Elements of a cover that a frame image is painted on, and those of the wide cover. */
    private val COVER_CLASSES = setOf(
        "appportrait_Portrait", "appportrait_PortraitImage", "appportrait_FeaturedCapsule", "appportrait_Landscape", "appportrait_Capsule",
    )
    private val WIDE_COVER_CLASSES = setOf("appportrait_FeaturedCapsule", "appportrait_Landscape")

    /** The cover's artwork inside it. */
    private val COVER_IMAGE_CLASSES = setOf(
        "libraryassetimage_PortraitImage", "libraryassetimage_LandscapeImage", "libraryassetimage_Visibility", "libraryassetimage_Visible",
    )
    private val BARE_IMG_RE = Regex("^img(:[a-z-]+(\\([^()]*\\))?)*$")
    private val NTH_RE = Regex(":nth-child\\(([^)]*)\\)")
    private val ANB_RE = Regex("^([+-]?\\d*)n([+-]\\d+)?$")
    private val FRAME_PROPS = setOf("background-size", "background-position-y", "height", "top", "left", "width")

    /** On Steam's home row the wide capsule is the first child, so portrait covers start at the second. */
    private const val FIRST_PORTRAIT_CHILD = 2
    private const val MAX_GRID_COLUMNS = 12
    private val REPEAT_RE = Regex("repeat\\(\\s*(\\d+)\\s*,\\s*([^)]+)\\)")
    private val GRIDCELL_RE = Regex("\\[role=[\"']?gridcell[\"']?]")
    private val NTH_LITERAL_RE = Regex(":nth-(?:of-type|child)\\(\\s*(\\d+)\\s*\\)")
    private val FOOTER_SEL_RE = Regex("footer", RegexOption.IGNORE_CASE)

    /** A controller button named by an attribute (`[data-button="A"]`) or a class (`Button_A`). */
    private val HINT_RE = Regex("(?:data-(?:gamepad-|controller-)?button|aria-label|title)\\s*=\\s*[\"']?([A-Za-z])[\"']?\\s*]|[Bb]utton_([A-Za-z])\\b")
    private val RASTER_RE = Regex("\\.(png|jpe?g|webp)$", RegexOption.IGNORE_CASE)
    private val CALC_PCT_RE = Regex("(-?[\\d.]+)\\s*\\*\\s*100%")
    private val PCT_ONLY_RE = Regex("(-?[\\d.]+)%")
    private val ROW_ITEM_RE = Regex("^(div|\\*)?([.:\\[].*)?$")
    private val CSS_ESCAPE_RE = Regex("\\\\(.)")

    /** Pseudo-classes on an ancestor that pick some of the tiles, not all of them. */
    private val STRUCTURAL_RE = Regex(":(nth-child|nth-last-child|nth-of-type|nth-last-of-type|first-child|last-child|first-of-type|last-of-type)")

    private class Rule(val rule: CssRule, val sheet: Int, val id: Int)

    private class SubjectInfo(
        val names: List<Pair<String, SteamClassMap.How>>,
        val base: RoleMatch?,
        val focus: Boolean,
        val pseudoEl: Boolean,
        val weakFocus: Boolean,
        val onState: Boolean,
        /** The subject is a context menu, dropdown or dialog, or sits inside one. */
        val inMenu: Boolean,
        val layout: LayoutMatch?,
        /** Any compound is focused, hovered or in an interaction state. */
        val anyFocus: Boolean,
        /** The selector targets a disabled control. */
        val disabled: Boolean,
    )

    private class Cand(
        val value: String,
        var weight: Double,
        val rid: Int,
        val selector: String,
        val property: String,
        val important: Boolean,
        /** Only the highest tier present competes, so a specific class rule is not outvoted by many catch-alls. */
        var tier: Int,
    )

    private class Picked(val value: String, val score: Double, val rids: Set<Int>)

    private class Cands {
        val c = HashMap<String, MutableList<Cand>>()

        fun add(key: String, value: String, weight: Double, rid: Int, selector: String, property: String, important: Boolean, tier: Int = 0) {
            val list = c.getOrPut(key) { ArrayList() }
            for (x in list) {
                if (x.rid == rid && x.value == value) {
                    if (weight > x.weight) x.weight = weight
                    if (tier > x.tier) x.tier = tier
                    return
                }
            }
            // the same selector + property later in the cascade replaces the earlier value unless that one was !important
            list.removeAll { x ->
                x.selector == selector && x.property == property && x.rid != rid && x.value != value && !(x.important && !important)
            }
            list.add(Cand(value, weight, rid, selector, property, important, tier))
        }

        fun pick(key: String): Picked? {
            val all = c[key] ?: return null
            if (all.isEmpty()) return null
            val top = all.maxOf { it.tier }
            val list = all.filter { it.tier == top }
            val score = LinkedHashMap<String, Double>()
            list.forEachIndexed { i, x -> score[x.value] = (score[x.value] ?: 0.0) + x.weight + i * 1e-6 }
            var best: Map.Entry<String, Double>? = null
            for (e in score.entries) if (best == null || e.value > best.value) best = e
            val win = best!!.key
            return Picked(win, best.value, list.filter { it.value == win }.map { it.rid }.toSet())
        }
    }

    /** Per badge class: how the theme hides or shows it. */
    private class BadgeState {
        var hard = false
        var soft = false
        var focusShow = false
        val rids = HashSet<Int>()
    }

    fun splitCompounds(sel: String): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (c in sel.trim()) {
            if (quote != null) {
                cur.append(c)
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c; cur.append(c)
            } else if (c == '(' || c == '[') {
                depth++; cur.append(c)
            } else if (c == ')' || c == ']') {
                depth--; cur.append(c)
            } else if (depth == 0 && (c == ' ' || c == '>' || c == '+' || c == '~' || c == '\t' || c == '\n')) {
                if (cur.isNotEmpty()) { parts.add(cur.toString()); cur.setLength(0) }
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        return parts
    }

    /** Classes in a compound outside :not(), plus `[class=...]` attribute values. */
    fun compoundClasses(compound: String): Pair<List<String>, List<String>> {
        val noNot = NOT_RE.replace(compound, "")
        val cl = CLASS_RE.findAll(BRACKET_RE.replace(noNot, "")).map { it.groupValues[1] }.toList()
        val attrs = ATTR_CLASS_RE.findAll(noNot).map { it.groupValues[2] }.toList()
        return cl to attrs
    }

    /** True when a selector in [sheets] names a hashed class that [cm] cannot read. */
    fun namesUnknownClasses(sheets: List<CssSheet>, cm: SteamClassMap): Boolean =
        sheets.any { s ->
            s.rules.any { r ->
                r.selectors.any { sel ->
                    val (cl, attrs) = compoundClasses(sel)
                    (cl + attrs).any { cm.readable(it).second == SteamClassMap.How.OPAQUE_UNMAPPED }
                }
            }
        }

    private fun subjectInfo(sel: String, cm: SteamClassMap): SubjectInfo? {
        val comps = splitCompounds(sel)
        if (comps.isEmpty()) return null
        val subj = comps.last()
        val (cl, attrs) = compoundClasses(subj)
        val names = (cl + attrs).map { cm.readable(it) }
        val roles = names.mapNotNull { SteamClassRoles.roleOf(it.first) }
        val base = roles.filter { it.role != SteamRole.FOCUS && it.role != SteamRole.FOCUS_WITHIN }
        val focusCls = roles.filter { it.role == SteamRole.FOCUS }
        var focusAncestor = false
        var anyFocus = false
        var inMenu = false
        for ((ci, comp) in comps.withIndex()) {
            val readable = compoundClasses(comp).let { (a, b) -> (a + b).map { cm.readable(it).first } }
            val state = SteamClassRoles.STATE_PSEUDO.any { NOT_RE.replace(comp, "").contains(it) }
            val compRoles = readable.mapNotNull { SteamClassRoles.roleOf(it)?.role }
            if (state || readable.any { HOVER_STATE_RE.containsMatchIn(it) } ||
                compRoles.any { it == SteamRole.FOCUS || it == SteamRole.FOCUS_WITHIN }
            ) {
                anyFocus = true
            }
            if (compRoles.any { it == SteamRole.MENU || it == SteamRole.MENU_ITEM } || readable.any { r -> MENU_MODULES.any { r.startsWith(it) } }) {
                inMenu = true
            }
            if (ci < comps.size - 1 && (state || compRoles.any { it == SteamRole.FOCUS })) focusAncestor = true
        }
        val noNot = NOT_RE.replace(subj, "")
        val pseudoFocus = SteamClassRoles.STATE_PSEUDO.any { noNot.contains(it) }
        val b = base.maxByOrNull { it.priority } ?: focusCls.maxByOrNull { it.priority }
        val onState = comps.any { comp -> compoundClasses(comp).first.any { cm.readable(it).first.endsWith("_On") } }
        val pe = subj.contains("::before") || subj.contains("::after") || subj.contains(":before") || subj.contains(":after")
        var layout = names.mapNotNull { SteamClassRoles.layoutRoleOf(it.first) }.maxByOrNull { it.priority }
        val bareSubj = subj.replace("::before", "").replace("::after", "")
        val anyChild = bareSubj == "*"
        if (layout == null && names.isEmpty() && comps.size > 1 && (anyChild || BARE_ELEMENT_RE.matches(bareSubj))) {
            // `.tile img`, `.tile > div:first-child`: the element is part of the tile or hero above it; `.art > *` is the home artwork
            val (pcl, pat) = compoundClasses(comps[comps.size - 2])
            val roles = if (anyChild) setOf(LayoutRole.HOME_HERO) else setOf(LayoutRole.TILE, LayoutRole.ICON, LayoutRole.HERO, LayoutRole.HOME_HERO)
            layout = (pcl + pat).mapNotNull { SteamClassRoles.layoutRoleOf(cm.readable(it).first) }
                .filter { it.role in roles }
                .maxByOrNull { it.priority }
        }
        // the glow copy carries a tile class but is not the cover
        if (layout?.role == LayoutRole.TILE && names.any { it.first == GLOW_CLASS }) layout = null
        if (layout?.role == LayoutRole.TILE) {
            // artwork inside the top bar's download indicator or a game-icon wrapper is an icon, not a library tile
            val inBar = comps.dropLast(1).any { comp ->
                compoundClasses(comp).let { (a, b) -> a + b }.map { cm.readable(it).first }.any { it.startsWith("header_") || it.contains("GameIcon") }
            }
            if (inBar) layout = LayoutMatch(LayoutRole.ICON, layout.priority, layout.readable)
        }
        return SubjectInfo(
            names = names,
            base = b,
            focus = focusCls.isNotEmpty() || pseudoFocus || focusAncestor,
            pseudoEl = pe,
            weakFocus = base.isEmpty() && focusCls.isNotEmpty() && names.size > roles.size,
            onState = onState,
            inMenu = inMenu,
            layout = layout,
            anyFocus = anyFocus,
            disabled = DISABLED_RE.containsMatchIn(NOT_RE.replace(sel, "")),
        )
    }

    private sealed interface Bg {
        object None : Bg
        data class Image(val url: String?) : Bg
        data class Gradient(val colors: List<Rgba>, val baseLayer: Boolean) : Bg
        data class Color(val color: Rgba) : Bg
        object Unknown : Bg
    }

    private fun bgParts(value: String): Pair<Bg, Rgba?> {
        val v = value.trim()
        val lv = v.lowercase()
        if (lv in setOf("none", "unset", "initial", "inherit", "transparent", "revert", "0")) {
            return Bg.None to (if (lv in setOf("transparent", "none", "unset")) Rgba(0.0, 0.0, 0.0, 0.0) else null)
        }
        // of several image layers the bottom one is the backdrop the others sit on
        if (lv.contains("url(")) return Bg.Image(lastUrl(v)) to null
        if (lv.contains("gradient(")) {
            val layers = CssValues.splitTop(v, ',')
            val base = layers.lastOrNull()?.let { CssValues.parseColor(it.trim()) }
            val cols = listOfNotNull(base) + CssValues.findColors(v)
            return Bg.Gradient(cols, base != null) to cols.firstOrNull()
        }
        val cols = CssValues.findColors(v)
        if (cols.isNotEmpty()) return Bg.Color(cols.last()) to cols.last()
        return Bg.Unknown to null
    }

    /** Url of the last image layer of a background value. */
    private fun lastUrl(v: String): String? = CssValues.splitTop(v, ',').mapNotNull { CssValues.firstUrl(it) }.lastOrNull()

    /** Which accent component an [SteamRole.ACCENT] class is; two kinds agreeing on a color make it the app accent. */
    private fun accentKind(readable: String): String = when {
        readable.contains("Toggle") -> "toggle"
        readable.contains("Slider") || readable.contains("Tick") -> "slider"
        readable.contains("Progress") || readable.contains("Percent") -> "progress"
        readable.contains("ActiveDot") -> "dot"
        else -> "accent"
    }

    private fun fmt(v: Double): String = "%.2f".format(java.util.Locale.ROOT, v)

    /**
     * Opens the library on [defaultLayout] (the screen the stack's Steam Deck themes are drawn for, see
     * [TranslationResult.defaultLayout]) unless a theme in the stack picks a layout.
     */
    fun withLayoutDefault(stack: ThemeSpec, defaultLayout: String?): ThemeSpec =
        if (defaultLayout == null || stack.layout?.libraryLayout != null) {
            stack
        } else {
            stack.overlay(ThemeSpec(layout = ThemeLayout(libraryLayout = defaultLayout)))
        }

    /** The default layout of a stack of Steam Deck themes: the shelf when one of them is drawn for the home, else the grid when one is. */
    fun stackDefaultLayout(defaults: List<String?>): String? = when {
        "row" in defaults -> "row"
        "grid" in defaults -> "grid"
        else -> null
    }

    fun translate(sources: List<SourceSheet>, cm: SteamClassMap): TranslationResult {
        val rules = ArrayList<Rule>()
        sources.forEachIndexed { si, s -> s.sheet.rules.forEach { rules.add(Rule(it, si, rules.size)) } }

        // custom properties: later wins, !important beats normal
        val gvars = HashMap<String, Triple<String, Boolean, Rule>>()
        val scoped = HashMap<String, String>()
        for (r in rules) {
            val global = r.rule.selectors.any { it.trim() in GLOBAL_SELECTORS }
            for (d in r.rule.decls) {
                if (!d.property.startsWith("--")) continue
                if (global) {
                    val prev = gvars[d.property]
                    if (prev == null || d.important || !prev.second) gvars[d.property] = Triple(d.value, d.important, r)
                } else {
                    scoped.putIfAbsent(d.property, d.value)
                }
            }
        }
        val globalLookup: (String) -> String? = { gvars[it]?.first ?: scoped[it] }
        fun lookupFor(rule: CssRule): (String) -> String? {
            val local = HashMap<String, String>()
            for (d in rule.decls) if (d.property.startsWith("--")) local[d.property] = d.value
            return { name -> local[name] ?: gvars[name]?.first ?: scoped[name] }
        }

        val cands = Cands()
        val bgCounter = LinkedHashMap<String, Int>()
        val bgRules = HashMap<String, Int>()
        val satCounter = LinkedHashMap<String, Int>()
        val satRules = HashMap<String, Int>()
        val fontScore = LinkedHashMap<String, Int>()
        val fontRules = HashMap<String, MutableSet<Int>>()
        val globalFonts = HashSet<String>()
        val accentKinds = HashMap<String, MutableSet<String>>()
        val badges = LinkedHashMap<String, BadgeState>()
        val footerHide = HashSet<Int>()
        var footerShown = false
        val upperKinds = HashSet<String>()
        val upperRules = HashSet<Int>()
        var homeScoped = false
        var gridScoped = false
        val ex = Extras()
        val slideKeyframes = sources.flatMap { it.sheet.keyframes }.filter { SLIDE_KEYFRAME_RE.containsMatchIn(it.body) }.mapTo(HashSet()) { it.name }

        for (r in rules) {
            val lk = lookupFor(r.rule)
            val resolved = r.rule.decls.map { d ->
                d to (if (d.value.contains("var(", ignoreCase = true)) CssValues.resolveVars(d.value, lk) else d.value)
            }
            for ((d, rv) in resolved) {
                if (d.property.startsWith("--")) continue
                for (c in CssValues.findColors(rv)) {
                    if (c.a >= 0.5 && c.saturation >= 0.35) {
                        val k = c.opaque().toHex()
                        satCounter[k] = (satCounter[k] ?: 0) + 1
                        satRules.putIfAbsent(k, r.id)
                    }
                }
            }
            val infos = r.rule.selectors.filterNot { it.contains("::-webkit-scrollbar") }.mapNotNull { sel ->
                subjectInfo(sel, cm)?.let { sel to it }
            }
            val roleInfos = infos.filter { it.second.base != null }
            // one focused selector makes the whole rule a focus effect for layout purposes
            val ruleFocus = infos.any { it.second.anyFocus }
            // a rule written for small game icons styles the artwork it lists with them as icons too
            val ruleIcon = infos.any { it.second.layout?.role == LayoutRole.ICON }
            val global = r.rule.selectors.any { it.trim() in GLOBAL_SELECTORS }
            val ruleHeightPx = resolved.lastOrNull { it.first.property == "height" }?.second?.let(CssValues::lengthPx)
            val declPairs = resolved.map { it.first.property to it.second }
            // the screen a theme is drawn for: any resting-state rule on Steam's home, else on its library grid
            for ((sel, si) in infos) {
                if (si.anyFocus) continue
                val all = selectorNames(sel, cm)
                if (all.any { n -> HOME_MODULES.any { n.startsWith(it) } }) {
                    homeScoped = true
                } else if (all.any { n -> GRID_MODULES.any { n.startsWith(it) } }) {
                    gridScoped = true
                }
            }

            for ((d, rv) in resolved) {
                val prop = d.property
                if (prop.startsWith("--")) {
                    val keys = SteamClassRoles.STEAM_VAR_TOKENS[prop]
                    if (global && keys != null) {
                        for (key in keys) {
                            when (key) {
                                "cornerRadius" -> CssValues.lengthDp(rv)?.let { cands.add(key, it.toString(), 2.5, r.id, ":root", prop, d.important) }
                                "backgroundGradient" -> cands.add(key, rv, 2.5, r.id, ":root", prop, d.important)
                                else -> CssValues.findColors(rv).firstOrNull()?.let { cands.add(key, it.toHex(), 2.5, r.id, ":root", prop, d.important) }
                            }
                        }
                    }
                    continue
                }
                if (prop == "font-family" || (prop == "font" && QUOTED_RE.containsMatchIn(rv))) {
                    val fam = if (prop == "font-family") rv.split(',')[0].trim().trim('\'', '"') else QUOTED_RE.find(rv)!!.groupValues[1]
                    if (fam.isNotEmpty() && fam.lowercase() !in STEAM_DEFAULT_FONTS && !fam.contains("var(")) {
                        val textRole = infos.any { it.second.base?.role == SteamRole.TEXT || it.second.base?.role == SteamRole.TEXT_MUTED }
                        val w = if (global || textRole) 3 else 1
                        fontScore[fam] = (fontScore[fam] ?: 0) + w
                        if (global) globalFonts.add(fam.lowercase())
                        fontRules.getOrPut(fam) { HashSet() }.add(r.id)
                    }
                }
                if (prop in TYPE_PROPS) {
                    if (global && prop == "letter-spacing") {
                        letterSpacingEm(rv, BODY_FONT_PX)?.let { cands.add("letterSpacing", fmt(it), 1.0, r.id, ":root", prop, d.important) }
                    }
                    for ((sel, si) in infos) typeDecl(sel, si, prop, rv, d.important, r, cands, cm)
                }
                if (prop == "text-transform" && rv.trim().lowercase() == "uppercase") {
                    for ((_, si) in infos) upperKind(si, global)?.let { upperKinds.add(it); upperRules.add(r.id) }
                }
                for ((sel, si) in infos) {
                    if (si.inMenu || si.pseudoEl) continue
                    val names = si.names.map { it.first }
                    if (SELECTED_TAB_CLASS in names && !focusedBeyondSelected(sel, cm)) {
                        val w = 3.0 + (if (d.important) 0.5 else 0.0)
                        if (prop in BG_PROPS) {
                            val painted = overBase(rv)?.let(CssValues::parseGradient)?.colors?.firstOrNull()?.let(Rgba::fromArgb)
                            (painted ?: bgParts(rv).second)?.takeIf { it.a > 0 }?.let { cands.add("tabFill", it.toHex(), w, r.id, sel, prop, d.important) }
                        } else if (prop == "color") {
                            CssValues.findColors(rv).firstOrNull()?.takeIf { it.a > 0.3 }?.let {
                                cands.add("onTab", it.opaque().toHex(), w, r.id, sel, prop, d.important)
                            }
                        }
                    }
                    if (prop == "filter" && FOOTER_GLYPH_CLASS in names && !si.anyFocus) {
                        glyphInvert(rv)?.let { cands.add("footerGlyphInvert", if (it) "1" else "0", 2.0, r.id, sel, prop, d.important) }
                    }
                }
                if (prop in BG_PROPS && roleInfos.isEmpty()) {
                    val (bg, _) = bgParts(rv)
                    if (bg is Bg.Color && bg.color.a > 0.5 && bg.color.saturation < 0.25) {
                        val subjNames = r.rule.selectors.joinToString(" ") { sel ->
                            val comps = splitCompounds(sel)
                            if (comps.isEmpty()) "" else compoundClasses(comps.last()).first.joinToString(" ") { cm.readable(it).first }
                        }
                        if (PAGE_LIKE_RE.containsMatchIn(subjNames)) {
                            val k = bg.color.toHex()
                            bgCounter[k] = (bgCounter[k] ?: 0) + 1
                            bgRules.putIfAbsent(k, r.id)
                        }
                    }
                }
                for ((sel, si) in infos) {
                    if (!si.pseudoEl) shelfDecl(si, sel, prop, rv, d.important, r, cands, declPairs, cm, stateOnly = ruleFocus)
                    extrasDecl(si, sel, prop, rv, d.important, r, cands, declPairs, cm, ruleFocus, ruleIcon, sources[r.sheet], ex)
                    val lr = si.layout?.let { if (ruleIcon && it.role == LayoutRole.TILE) it.copy(role = LayoutRole.ICON) else it } ?: continue
                    if (prop == "transform" && lr.role == LayoutRole.TILE && !si.pseudoEl) tiltDecl(lr, sel, rv, d.important, r, cands, cm)
                    when (layoutDecl(lr, si, ruleFocus, sel, prop, rv, d.important, r, cands, badges, declPairs)) {
                        true -> footerHide.add(r.id)
                        false -> footerShown = true
                        null -> {}
                    }
                }
                if (prop == "color") {
                    for ((sel, si) in infos) {
                        if (si.anyFocus || si.inMenu) continue
                        val readable = si.names.map { it.first }
                        val role = si.base?.role
                        if (readable.any { SteamClassRoles.HEADING_RE.containsMatchIn(it) }) {
                            CssValues.findColors(rv).firstOrNull()?.takeIf { it.a > 0.3 }?.let {
                                cands.add("heading", it.opaque().toHex(), 3.0 + (if (d.important) 0.5 else 0.0), r.id, sel, prop, d.important)
                            }
                        }
                        val key = when {
                            role == SteamRole.FOOTER || readable.any { FOOTER_TEXT_RE.containsMatchIn(it) } -> "footerText"
                            role == SteamRole.HEADER || readable.any { it.startsWith("header_") } -> "headerText"
                            else -> continue
                        }
                        if (role == SteamRole.ACCENT) continue
                        val hx = CssValues.findColors(rv).firstOrNull()?.takeIf { it.a > 0.3 }?.opaque()?.toHex() ?: continue
                        cands.add(key, hx, 2.0 + (if (d.important) 0.5 else 0.0), r.id, sel, prop, d.important)
                    }
                }
                for ((sel, si) in roleInfos) {
                    val base = si.base!!
                    val role = base.role
                    val prio = base.priority
                    val focusState = si.focus
                    val wImp = (if (d.important) 0.5 else 0.0) + prio * 0.01
                    val fw = if (si.weakFocus) 0.3 else 3.0
                    fun add(key: String, value: String, weight: Double, tier: Int = 0) = cands.add(key, value, weight, r.id, sel, prop, d.important, tier)

                    // a short bar under the focused item, drawn by its ::before / ::after
                    fun underline(): Boolean {
                        val h = ruleHeightPx ?: return false
                        if (h <= 0 || h > MAX_UNDERLINE_PX) return false
                        add("focusUnderline", rv, 3 + wImp)
                        add("focusUnderlineH", fmt(h), 3 + wImp)
                        return true
                    }
                    if (si.inMenu) {
                        // menus, dropdowns and dialogs only feed their own tokens
                        if (focusState) {
                            if (si.pseudoEl || si.disabled) continue
                            if (prop in BG_PROPS && role == SteamRole.MENU_ITEM) {
                                bgParts(rv).second?.takeIf { it.a >= 0.5 }?.let { add("menuFocus", it.opaque().toHex(), 3 + wImp) }
                            } else if (prop == "color" && (role == SteamRole.MENU_ITEM || role in TEXT_ROLES)) {
                                CssValues.findColors(rv).firstOrNull()?.takeIf { it.a > 0.3 }?.let {
                                    add("onMenuFocus", it.opaque().toHex(), (if (role == SteamRole.MENU_ITEM) 3.0 else 2.0) + wImp)
                                }
                            }
                            continue
                        }
                        // the title strip over a menu has its own fill, text and blur, not the menu body's
                        val menuHeader = role == SteamRole.MENU_ITEM && base.readable.contains("Header")
                        if (prop in BG_PROPS) {
                            val (bg, col) = bgParts(rv)
                            if (col == null) continue
                            if (role == SteamRole.MENU && col.a > 0) {
                                add("menu", col.opaque().toHex(), (if (col.a >= 0.5) 3.0 else 2.0) + wImp)
                                if (col.a < 0.999) add("menuOpacity", fmt(col.a), 3 + wImp)
                                if (bg is Bg.Gradient) add("menuFill", rv, 3 + wImp)
                            }
                            if (role == SteamRole.MENU_ITEM && col.a >= 0.5 && !base.readable.contains("Separator") && !menuHeader) {
                                add("menu", col.opaque().toHex(), 2 + wImp)
                            }
                        } else if (prop == "border-radius" && role == SteamRole.MENU) {
                            val v = CssValues.lengthDp(rv)
                            if (v != null && !rv.contains('%') && v < 100) add("menuRadius", v.toString(), 3 + wImp)
                        } else if ((prop == "backdrop-filter" || prop == "-webkit-backdrop-filter") && (role == SteamRole.MENU || role == SteamRole.MENU_ITEM) && !menuHeader) {
                            CssValues.blurDp(rv)?.let { add("menuBlur", it.toString(), (if (role == SteamRole.MENU) 3.0 else 1.0) + wImp) }
                        } else if (role == SteamRole.MENU && (prop == "border" || prop == "outline")) {
                            val c = CssValues.findColors(rv).firstOrNull { it.a > 0.05 }
                            val w = CssValues.borderWidthDp(rv)
                            if (c != null && w != null && w > 0) add("menuBorder", "${fmt(w.toDouble())} ${c.toHex()}", 3 + wImp)
                        } else if (role == SteamRole.MENU && (prop == "box-shadow" || prop == "filter")) {
                            CssValues.glowDp(rv, prop)?.let { add("menuGlow", it.toString(), 3 + wImp) }
                        } else if ((prop == "color" || prop == "fill") && !menuHeader) {
                            val hx = CssValues.findColors(rv).firstOrNull()?.takeIf { it.a > 0.3 }?.opaque()?.toHex() ?: continue
                            val w = when (role) {
                                SteamRole.MENU, SteamRole.MENU_ITEM, SteamRole.TEXT -> 3.0
                                else -> 1.0
                            }
                            add("onMenu", hx, w + wImp)
                        }
                        continue
                    }
                    if (prop == "animation" || prop == "animation-name") {
                        val anim = if (prop == "animation") rv else "$rv " + resolved.lastOrNull { it.first.property == "animation-duration" }?.second.orEmpty()
                        val slideKey = when {
                            si.disabled -> null
                            role == SteamRole.ACCENT -> "fillSlide".takeIf { si.pseudoEl || si.onState || !TOGGLE_TRACK_RE.containsMatchIn(base.readable) }
                            role == SteamRole.PANEL -> "panelSlide".takeIf { !focusState }
                            role == SteamRole.NAV_ITEM || role == SteamRole.CARD || role == SteamRole.CONTROL ->
                                "underlineSlide".takeIf { focusState && si.pseudoEl && (ruleHeightPx ?: 0.0) > 0 && ruleHeightPx!! <= MAX_UNDERLINE_PX }
                            else -> null
                        }
                        if (slideKey != null) slideSeconds(anim, slideKeyframes)?.let { add(slideKey, fmt(it), 3 + wImp) }
                    }
                    when {
                        prop in BG_PROPS -> {
                            val (bg, col) = bgParts(rv)
                            when (role) {
                                SteamRole.PAGE_BACKGROUND -> if (!focusState) {
                                    if (bg is Bg.Gradient) add("backgroundGradient", rv, 3 + wImp)
                                    if (bg is Bg.Image) bg.url?.let { u -> sources[r.sheet].resolveUrl(u)?.let { add("backgroundImage", it, 3 + wImp) } }
                                    if (col != null && col.a > 0) add("background", col.toHex(), 3 + wImp)
                                }
                                SteamRole.PANEL -> if (!focusState && col != null) {
                                    add("panelOpacity", fmt(col.a), (if (prio >= 5) 2.0 else 0.4) + wImp)
                                    if (col.a > 0) add("surface", col.opaque().toHex(), 3 + wImp)
                                    if (bg is Bg.Gradient) add("panelFill", rv, 3 + wImp)
                                }
                                SteamRole.HEADER, SteamRole.FOOTER -> if (!focusState && col != null) {
                                    val bar = if (role == SteamRole.HEADER) "header" else "footer"
                                    add("bar", col.toHex(), 2 + wImp)
                                    add(bar + "Color", col.toHex(), 2 + wImp)
                                    if (bg is Bg.Gradient) add(bar + "Fill", rv, 2 + wImp)
                                }
                                SteamRole.CARD, SteamRole.CONTROL -> if (role == SteamRole.CONTROL || !notALibraryTile(sel, si, ruleIcon, cm)) {
                                    val key = if (role == SteamRole.CARD) "card" else "surfaceVariant"
                                    if (!focusState && bg is Bg.None && col != null) {
                                        add(key, "transparent", 3 + wImp)
                                    } else if (col != null && col.a > 0) {
                                        if (!focusState) {
                                            add(key, col.toHex(), 3 + wImp)
                                        } else if (!si.pseudoEl || !underline()) {
                                            add("focus", col.toHex(), 2.5 + wImp)
                                            // the settings page list beside the rows: the rows' own focus fill is the app's
                                            val sideList = base.readable == SETTINGS_PAGE_LIST_ITEM
                                            if (!si.pseudoEl) add("focusFill", rv, (if (sideList) 1.5 else 2.5) + wImp)
                                        }
                                    }
                                }
                                SteamRole.NAV_ITEM -> if (focusState && col != null && col.a > 0) {
                                    if (!si.pseudoEl) add("focusFill", rv, 3 + wImp) else underline()
                                }
                                SteamRole.BUTTON -> if (col != null && col.a > 0 && !si.disabled) {
                                    if (!focusState) {
                                        add("button", col.opaque().toHex(), 3 + wImp)
                                        if (bg is Bg.Gradient) add("buttonFill", rv, 3 + wImp)
                                    } else {
                                        add("focus", col.toHex(), 1.5 + wImp)
                                        add("buttonFocusFill", rv, 3 + wImp)
                                    }
                                }
                                SteamRole.BUTTON_PRIMARY -> if (col != null && col.a > 0) {
                                    val sat = col.saturation >= 0.35
                                    if (focusState) {
                                        add("focus", col.toHex(), 1.5 + wImp)
                                    } else {
                                        add("primary", col.toHex(), (if (sat) 3.0 else 1.0) + wImp)
                                        if (bg is Bg.Gradient) add("accentGradient", rv, 3 + wImp)
                                        if (sat) accentKinds.getOrPut(col.opaque().toHex()) { HashSet() }.add("primaryButton")
                                    }
                                }
                                SteamRole.FOCUS -> if (col != null && col.a > 0) add("focus", col.toHex(), fw + wImp)
                                SteamRole.ACCENT -> {
                                    // the ON fill of toggle rails and slider tracks is their ::before or the _On state
                                    val bareTrack = TOGGLE_TRACK_RE.containsMatchIn(base.readable) && !si.pseudoEl && !si.onState
                                    val rail = base.readable.endsWith("_ToggleRail")
                                    if (bareTrack) {
                                        if (!focusState && !si.disabled && col != null && col.a > 0) add(if (rail) "toggleOff" else "sliderTrack", col.toHex(), 3 + wImp)
                                    } else if (col != null && col.a > 0) {
                                        add("toggle", col.opaque().toHex(), 2 + wImp)
                                        if (bg is Bg.Gradient) add("accentGradient", rv, 2 + wImp)
                                        if (rail && !si.disabled) add("toggleOn", rv, (if (focusState) 1.0 else 3.0) + wImp)
                                        accentKinds.getOrPut(col.opaque().toHex()) { HashSet() }.add(accentKind(base.readable))
                                    }
                                }
                                SteamRole.ACCENT_HANDLE -> if (!focusState && !si.disabled && col != null && col.a > 0) {
                                    val hx = col.opaque().toHex()
                                    if (!base.readable.endsWith("_ToggleSwitch")) {
                                        add("sliderThumb", hx, 3 + wImp)
                                    } else if (si.onState) {
                                        add("thumbOn", hx, 3 + wImp)
                                    } else {
                                        // the plain handle rule styles both states until an _On rule overrides it
                                        add("thumbOff", hx, 3 + wImp)
                                        add("thumbOn", hx, 1 + wImp)
                                    }
                                }
                                SteamRole.DIVIDER -> if (col != null && col.a > 0) add("outline", col.toHex(), 3 + wImp, prio)
                                else -> {}
                            }
                        }
                        prop == "color" || prop == "fill" -> {
                            val hx = CssValues.findColors(rv).firstOrNull()?.toHex() ?: continue
                            if (!focusState && prop == "color" && base.readable == SUBTITLE_CLASS) add("subtitle", hx, 3 + wImp)
                            when {
                                role == SteamRole.TEXT && !focusState -> add("text", hx, (if (prio > 1) 3.0 else 1.0) + wImp, prio)
                                role == SteamRole.TEXT_MUTED && !focusState -> add("textMuted", hx, (if (prio > 1) 3.0 else 1.0) + wImp, prio)
                                (role == SteamRole.BUTTON_PRIMARY || role == SteamRole.ON_PRIMARY_TEXT) && !focusState -> add("onPrimary", hx, 2.5 + wImp)
                                role == SteamRole.BUTTON && !focusState && prop == "color" -> add("onButton", hx, 3 + wImp)
                                role == SteamRole.BUTTON && focusState && prop == "color" && !si.disabled -> add("onButtonFocus", hx, 3 + wImp)
                                role in setOf(SteamRole.PAGE_BACKGROUND, SteamRole.PANEL, SteamRole.CARD, SteamRole.CONTROL) &&
                                    !focusState && prop == "color" -> add("text", hx, 1 + wImp)
                                focusState && prop == "color" && !si.pseudoEl &&
                                    role in setOf(SteamRole.CARD, SteamRole.CONTROL, SteamRole.NAV_ITEM, SteamRole.TEXT, SteamRole.TEXT_MUTED) ->
                                    add("focusText", hx, (if (role in TEXT_ROLES) 2.0 else 3.0) + wImp)
                            }
                        }
                        prop in LINE_PROPS -> {
                            if (prop == "filter" && !rv.contains("drop-shadow")) continue
                            val c = CssValues.findColors(rv).firstOrNull { it.a > 0.05 } ?: continue
                            val hx = c.opaque().toHex()
                            when {
                                focusState || role == SteamRole.FOCUS -> {
                                    add("focus", hx, fw + wImp)
                                    if (!si.weakFocus && (prop == "box-shadow" || prop == "filter")) {
                                        CssValues.glowDp(rv, prop)?.let { add("focusGlow", it.toString(), 3 + wImp) }
                                    }
                                    if (!si.weakFocus && (role == SteamRole.FOCUS || role == SteamRole.CARD) && prop in RING_PROPS) {
                                        CssValues.borderWidthDp(rv)?.let { add("focusRing", it.toString(), fw + wImp) }
                                        if (role == SteamRole.FOCUS) add("focusRingColor", c.toHex(), fw + wImp)
                                    }
                                }
                                role == SteamRole.HEADER || role == SteamRole.FOOTER -> {
                                    val bar = if (role == SteamRole.HEADER) "header" else "footer"
                                    val edge = if (role == SteamRole.HEADER) "border-bottom" else "border-top"
                                    if (prop == "border" || prop == edge) {
                                        CssValues.borderWidthDp(rv)?.takeIf { it > 0 }?.let { add(bar + "Border", "${fmt(it.toDouble())} ${c.toHex()}", 2 + wImp) }
                                    }
                                    if (prop == "box-shadow") {
                                        CssValues.glowDp(rv, prop)?.let { add(bar + "Glow", "${fmt(it.toDouble())} ${c.toHex()}", 2 + wImp) }
                                    }
                                }
                                role == SteamRole.DIVIDER -> add("outline", hx, 3 + wImp, prio)
                                role in setOf(SteamRole.CARD, SteamRole.CONTROL, SteamRole.PANEL) && prop.startsWith("border") -> add("outline", hx, 2 + wImp)
                                role == SteamRole.BUTTON_PRIMARY && prop.startsWith("border") -> add("primary", hx, 1.0)
                                role == SteamRole.BUTTON && prop == "border-bottom" && !si.disabled -> {
                                    CssValues.borderWidthDp(rv)?.takeIf { it > 0 }?.let { add("buttonUnderline", "${fmt(it.toDouble())} ${c.toHex()}", 3 + wImp) }
                                }
                            }
                        }
                        prop == "border-radius" || prop == "border-top-left-radius" -> {
                            if (si.layout?.role == LayoutRole.TILE || si.layout?.role == LayoutRole.ICON) continue
                            val v = CssValues.lengthDp(rv) ?: continue
                            if (rv.contains('%') || v >= 100) continue
                            val w = when (role) {
                                SteamRole.CARD, SteamRole.CONTROL -> 3.0
                                SteamRole.BUTTON, SteamRole.BUTTON_PRIMARY -> 2.0
                                SteamRole.PANEL, SteamRole.FOCUS -> 1.0
                                else -> 0.0
                            }
                            if (w > 0) add("cornerRadius", v.toString(), w + wImp)
                        }
                        prop == "backdrop-filter" || prop == "-webkit-backdrop-filter" -> {
                            val b = CssValues.blurDp(rv) ?: continue
                            when (role) {
                                SteamRole.HEADER -> add("headerBlur", b.toString(), 2.0)
                                SteamRole.FOOTER -> add("footerBlur", b.toString(), 2.0)
                                SteamRole.PANEL, SteamRole.CARD, SteamRole.PAGE_BACKGROUND -> add("blur", b.toString(), if (role == SteamRole.PANEL) 3.0 else 2.0)
                                else -> {}
                            }
                        }
                        prop == "text-shadow" && role in TEXT_ROLES && !focusState -> {
                            if (CssValues.textShadowStroke(rv) != null) continue
                            val glow = CssValues.glowDp(rv, prop) ?: continue
                            val c = CssValues.shadows(rv, prop).firstNotNullOfOrNull { it.color?.takeIf { col -> col.a > 0.05 } } ?: continue
                            add("textGlow", "${fmt(glow.toDouble())} ${c.toHex()}", 2 + wImp)
                        }
                        prop == "height" && (role == SteamRole.HEADER || role == SteamRole.FOOTER) && !focusState && !si.pseudoEl -> {
                            val px = CssValues.lengthPx(rv)?.takeIf { it > 0 } ?: continue
                            add(if (role == SteamRole.HEADER) "headerHeight" else "footerHeight", fmt(px / STEAM_BAR_PX), 2 + wImp)
                        }
                        (prop == "animation" || prop == "animation-name") && (focusState || role == SteamRole.FOCUS) -> {
                            if (BLINK_RE.containsMatchIn(rv)) add("focusBlink", "1", 2 + wImp)
                        }
                        prop == "opacity" && role == SteamRole.PANEL && !focusState -> {
                            rv.trim().toDoubleOrNull()?.let { add("panelOpacity", fmt(it), if (prio >= 5) 2.0 else 0.4) }
                        }
                    }
                }
            }
        }

        // keyframes that override Steam's focus animations carry the theme's focus color and glow
        var kfId = -1
        for (src in sources) for (kf in src.sheet.keyframes) {
            val id = kfId--
            val (mapped, how) = cm.readable(kf.name)
            val m = KEYFRAME_STEAM_RE.find(kf.name)
            val rd = if (m != null) "${m.groupValues[1]}_${m.groupValues[2]}" else mapped
            if (m == null && how == SteamClassMap.How.GLOBAL) continue
            if (!SteamClassRoles.FOCUS_ANIMATION_RE.containsMatchIn(rd) || kf.body.isBlank()) continue
            val body = CssValues.resolveVars(kf.body, globalLookup)
            CssValues.findColors(body).lastOrNull { it.a > 0.3 }?.let {
                cands.add("focus", it.opaque().toHex(), 2.0, id, "@keyframes ${kf.name}", "border-color", false)
            }
            for (sm in KEYFRAME_SHADOW_RE.findAll(body)) {
                val p = sm.groupValues[1].lowercase()
                if (p == "filter" && !sm.groupValues[2].contains("drop-shadow")) continue
                CssValues.glowDp(sm.groupValues[2], p)?.let { cands.add("focusGlow", it.toString(), 2.0, id, "@keyframes ${kf.name}", p, false) }
            }
            // the width the ring settles on: the last border width in the animation
            KEYFRAME_BORDER_RE.findAll(body).lastOrNull()?.let { bm ->
                CssValues.borderWidthDp(bm.groupValues[2])?.let { cands.add("focusRing", it.toString(), 2.0, id, "@keyframes ${kf.name}", "border", false) }
            }
            if (BLINK_RE.containsMatchIn(rd)) cands.add("focusBlink", "1", 2.0, id, "@keyframes ${kf.name}", "animation", false)
        }

        if ("global" in upperKinds || upperKinds.size >= ALL_UPPERCASE_KINDS) {
            for (rid in upperRules) cands.add("textCase", "uppercase", 3.0, rid, "*", "text-transform", false)
        }

        val tokens = HashMap<String, String>()
        val used = HashMap<String, MutableSet<Int>>()
        val scores = HashMap<String, Double>()
        for (key in COLOR_KEYS + LAYOUT_KEYS + NESTED_FIELD_KEYS.keys +
            listOf("cornerRadius", "backgroundGradient", "backgroundImage", "panelOpacity", "blur", "accentGradient", "focusGlow")
        ) {
            val p = cands.pick(key) ?: continue
            tokens[key] = p.value
            scores[key] = p.score
            used[key] = p.rids.toMutableSet()
        }
        // a wallpaper on the artwork's container is drawn at the container's own opacity, whatever hides the artwork in it
        if (tokens.containsKey("heroImage")) {
            ex.heroContainerOpacity?.let { (o, rid) ->
                tokens["heroOpacity"] = fmt(o.coerceIn(0.0, 1.0))
                used["heroOpacity"] = mutableSetOf(rid)
            }
        }
        // a flat focused cover only means something next to tilted or turned ones
        if (listOf("cardTilt", "cardTiltAfter", "cardTurn", "cardTurnAfter").none { tokens.containsKey(it) }) {
            tokens.remove("cardFocusedFlat")
            used.remove("cardFocusedFlat")
        }
        for (key in listOf("surfaceVariant", "card")) {
            if (tokens[key] != "transparent") continue
            val under = tokens["surface"] ?: tokens["background"]
            if (under != null) tokens[key] = under else tokens.remove(key)
        }
        if (tokens.containsKey("backgroundImage")) tokens.remove("backgroundGradient")
        tokens["accentGradient"]?.let { g -> overBase(g)?.let { tokens["accentGradient"] = it } }
        tokens["accentGradient"]?.let { g ->
            val first = CssValues.parseGradient(g)?.colors?.firstOrNull()?.let { Rgba.fromArgb(it).opaque().toHex() }
            if (first == null) tokens.remove("accentGradient") else if (tokens["toggle"] == null && used["accentGradient"].orEmpty().isNotEmpty()) {
                tokens["toggle"] = first
                used["toggle"] = used["accentGradient"]!!.toMutableSet()
            }
        }
        // the accent is app-wide only when two kinds of component agree on it
        tokens["toggle"]?.let { t ->
            val tc = CssValues.parseColor(t) ?: return@let
            if ((accentKinds[t]?.size ?: 0) < 2) return@let
            val p = tokens["primary"]?.let(CssValues::parseColor)
            if (p == null || (p.saturation < 0.2 && tc.saturation >= 0.35)) {
                tokens["primary"] = t
                used["primary"] = used["toggle"].orEmpty().toMutableSet()
                tokens.remove("onPrimary")
                used.remove("onPrimary")
            }
        }
        if (!tokens.containsKey("primary") && COMPONENT_KEYS.none { tokens.containsKey(it) }) {
            satCounter.entries.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.let {
                tokens["primary"] = it.key
                used["primary"] = listOfNotNull(satRules[it.key]).toMutableSet()
            }
        }
        for ((bgKey, fgKey) in listOf(
            "primary" to "onPrimary", "button" to "onButton", "menu" to "onMenu", "menuFocus" to "onMenuFocus",
            "focusFill" to "focusText", "buttonFocusFill" to "onButtonFocus", "tabFill" to "onTab",
        )) {
            val bgc = tokens[bgKey]?.takeIf { it != "transparent" }
                ?.let { CssValues.parseColor(it) ?: CssValues.parseFill(it)?.flatColor?.let(Rgba::fromArgb) }
                ?.takeIf { it.a > 0.3 } ?: continue
            val under = (tokens["surface"] ?: tokens["background"])?.let(CssValues::parseColor)?.opaque()?.toArgb() ?: ThemeContrast.BLACK
            val fill = ThemeContrast.over(bgc.toArgb(), under)
            val fg = tokens[fgKey]?.let(CssValues::parseColor)?.toArgb()
            if (fg != null && ThemeContrast.ratio(fg, fill) >= ThemeContrast.TEXT) continue
            val others = TEXT_ALTERNATIVES.map { k -> tokens[k]?.takeIf { k != fgKey }?.let(CssValues::parseColor)?.opaque()?.toArgb() }
            tokens[fgKey] = Rgba.fromArgb(ThemeContrast.onFill(fill, ThemeContrast.TEXT, others)).toHex()
            used.remove(fgKey)
        }
        // text for a focus fill the theme does not set would land on the app's own focus look
        if (!tokens.containsKey("focusFill")) tokens.remove("focusText")
        if (!tokens.containsKey("background")) {
            bgCounter.entries.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.let {
                tokens["background"] = it.key
                used["background"] = listOfNotNull(bgRules[it.key]).toMutableSet()
            }
        }
        if (tokens.containsKey("panelOpacity") && (scores["panelOpacity"] ?: 0.0) < 2) tokens.remove("panelOpacity")

        var hideBadges: Boolean? = null
        val hiddenBadges = badges.values.filter { it.hard || (it.soft && !it.focusShow) }
        val focusOnlyBadges = badges.values.filter { !it.hard && it.soft && it.focusShow }
        if (hiddenBadges.isNotEmpty() && focusOnlyBadges.isEmpty()) {
            hideBadges = true
            used["hideBadges"] = hiddenBadges.flatMap { it.rids }.toMutableSet()
        }
        var hideFooter: Boolean? = null
        if (footerHide.isNotEmpty() && !footerShown) {
            hideFooter = true
            used["hideFooterHints"] = footerHide.toMutableSet()
        }

        var fontFile: String? = null
        // each family's faces, the one drawn as regular text first, then each face's urls, cheapest format first, so a
        // family falls back to any of its urls that resolves to a usable font
        val fontFaces = HashMap<String, MutableList<Pair<SourceSheet, List<CssDecl>>>>()
        for (src in sources) for (ff in src.sheet.fontFaces) {
            val fam = ff.lastOrNull { it.property == "font-family" }?.value?.trim()?.trim('\'', '"') ?: continue
            if (RemoteFonts.sources(ff).isNotEmpty()) fontFaces.getOrPut(fam.lowercase()) { ArrayList() }.add(src to ff)
        }
        val fontUrls = fontFaces.mapValues { (_, faces) ->
            faces.sortedWith { a, b -> RemoteFonts.FACE_ORDER.compare(a.second, b.second) }
                .flatMap { (src, ff) -> RemoteFonts.sources(ff).map { src to it } }
        }
        // the app draws one family, so a family the theme sets on all text (its body font) beats one set on headings
        val fontOrder = compareByDescending<Map.Entry<String, Int>> { it.key.lowercase() in globalFonts }.thenByDescending { it.value }
        for ((fam, _) in fontScore.entries.filter { fontUrls.containsKey(it.key.lowercase()) }.sortedWith(fontOrder)) {
            fontFile = fontUrls.getValue(fam.lowercase()).firstNotNullOfOrNull { (src, url) -> src.resolveFont(url) } ?: continue
            used["font"] = fontRules[fam] ?: HashSet()
            break
        }

        val inferred = withExtras(toSpec(tokens, fontFile, hideBadges, hideFooter), ex)
        val (gn, gnRules) = gnSpec(gvars, sources)
        var spec = inferred.overlay(gn)
        if (spec.primary == null && spec.accentGradient == null && (inferred.background != null || inferred.surface != null)) {
            spec = spec.copy(primary = neutralAccent(spec), onPrimary = null)
        }
        val applied = HashSet<Int>(gnRules)
        val gnFields = fieldsOf(gn).toSet()
        val keptFields = fieldsOf(inferred).filter { it !in gnFields }.toSet()
        for ((key, rids) in used) if (fieldOfKey(key) in keptFields) applied.addAll(rids)
        applied.addAll(ex.emitted)

        val defaultLayout = when {
            homeScoped -> "row"
            gridScoped -> "grid"
            else -> null
        }
        return TranslationResult(spec, rules.size, applied.count { it >= 0 }, defaultLayout)
    }

    /** Feeds one declaration on a layout element. Returns true/false for a footer hide/show, else null. */
    private fun layoutDecl(
        lr: LayoutMatch,
        si: SubjectInfo,
        ruleFocus: Boolean,
        sel: String,
        prop: String,
        rv: String,
        important: Boolean,
        r: Rule,
        cands: Cands,
        badges: MutableMap<String, BadgeState>,
        decls: List<Pair<String, String>>,
    ): Boolean? {
        val v = rv.trim()
        val lv = v.lowercase()
        val w = lr.priority + (if (important) 0.5 else 0.0)
        fun add(key: String, value: String) = cands.add(key, value, w, r.id, sel, prop, important)
        val hide = when (prop) {
            "display" -> if (lv == "none") 2 else 0
            "visibility" -> if (lv == "hidden" || lv == "collapse") 1 else 0
            "opacity" -> {
                val o = if (lv.endsWith("%")) lv.dropLast(1).toDoubleOrNull()?.div(100) else lv.toDoubleOrNull()
                if (o == null) -1 else if (o <= 0.0) 1 else 0
            }
            else -> -1
        }
        if (lr.role == LayoutRole.BADGE) {
            if (hide < 0) return null
            val st = badges.getOrPut(lr.readable) { BadgeState() }
            if (hide > 0 && !si.anyFocus) {
                if (hide == 2) st.hard = true else st.soft = true
                st.rids.add(r.id)
            } else if (hide == 0 && si.anyFocus) {
                st.focusShow = true
            }
            return null
        }
        if (lr.role == LayoutRole.FOOTER) {
            if (hide < 0 || ruleFocus) return null
            return hide > 0
        }
        if (ruleFocus) return null
        when (lr.role) {
            LayoutRole.TILE, LayoutRole.HOME_ROW -> {
                val scale = when (prop) {
                    "transform" -> CssValues.transformScale(v)
                    "zoom" -> if (lv.endsWith("%")) lv.dropLast(1).toDoubleOrNull()?.div(100) else lv.toDoubleOrNull()
                    "scale" -> CssValues.quantity(v.split(Regex("\\s+")).first())?.takeIf { it.dim == CssValues.Dim.NUMBER }?.value
                    else -> null
                }
                // Resizing or reshaping Steam's home shelf picks the app's shelf layout, which draws those changes.
                val homeShelf = lr.role == LayoutRole.HOME_ROW || lr.readable.startsWith("basicgamecarousel_") || HOME_SHELF_SCOPE.containsMatchIn(sel)
                if (scale != null && scale > 0 && kotlin.math.abs(scale - 1) >= MIN_SCALE_CHANGE && !selectsSomeTiles(sel)) {
                    add("cardScale", fmt(scale.coerceIn(0.5, 1.5)))
                    if (homeShelf) add("libraryLayout", "row")
                }
                if (lr.role == LayoutRole.TILE && prop == "aspect-ratio" && FEATURED_TILE_RE.containsMatchIn(sel)) {
                    val parts = lv.split('/').map { it.trim().toDoubleOrNull() }
                    val ratio = when {
                        parts.size == 1 -> parts[0]
                        parts.size == 2 && parts[1] != null && parts[1]!! > 0 -> parts[0]?.div(parts[1]!!)
                        else -> null
                    }
                    if (ratio != null && ratio <= 1.0) add("shelfWideFirst", "0")
                }
                if (lr.role == LayoutRole.TILE && (prop == "border-radius" || prop == "border-top-left-radius")) {
                    val px = CssValues.lengthDp(v)
                    if (px != null && !v.contains('%') && px < 100) add("cardCornerRadius", px.toString())
                }
                if (lr.role == LayoutRole.HOME_ROW && prop == "height") {
                    CssValues.lengthPx(v)?.takeIf { it > 0 }?.let { add("libraryLayout", "row") }
                }
            }
            LayoutRole.ICON -> if (prop == "border-radius" || prop == "border-top-left-radius") {
                val px = CssValues.lengthDp(v)
                if (px != null && !v.contains('%') && px < 100) add("iconCornerRadius", px.toString())
            }
            LayoutRole.TITLE, LayoutRole.SUBTITLE -> {
                val centered = lr.role == LayoutRole.TITLE && when (prop) {
                    "text-align" -> lv == "center"
                    "margin", "margin-inline" -> {
                        val parts = lv.split(Regex("\\s+"))
                        val (left, right) = when (parts.size) {
                            1 -> parts[0] to parts[0]
                            2, 3 -> parts[1] to parts[1]
                            else -> parts[3] to parts[1]
                        }
                        left == "auto" && right == "auto"
                    }
                    "margin-left" -> decls.any { it.first == "margin-right" && it.second.trim() == "auto" } && lv == "auto"
                    "justify-content" -> lv == "center" && decls.none { it.first == "flex-direction" && it.second.trim().startsWith("column") }
                    else -> false
                }
                if (centered) add("titleAlign", "center")
                var blur = 0f
                val stroke: Pair<Float, Rgba>? = when (prop) {
                    "-webkit-text-stroke" -> {
                        val c = CssValues.findColors(v).firstOrNull()
                        val width = v.split(Regex("\\s+")).firstNotNullOfOrNull { CssValues.lengthDp(it) }
                        if (c != null && width != null && width > 0) width to c else null
                    }
                    "-webkit-text-stroke-width" -> {
                        val c = decls.lastOrNull { it.first == "-webkit-text-stroke-color" }?.second?.let { CssValues.findColors(it).firstOrNull() }
                        val width = CssValues.lengthDp(v)
                        if (c != null && width != null && width > 0) width to c else null
                    }
                    "text-shadow" -> CssValues.textShadowStroke(v)?.also { blur = CssValues.textShadowBlurDp(v) }
                    else -> null
                }
                val key = if (lr.role == LayoutRole.TITLE) "titleStroke" else "subtitleStroke"
                stroke?.let { (sw, c) -> add(key, "${fmt(sw.toDouble())} ${c.opaque().toHex()}" + (if (blur > 0f) " ${fmt(blur.toDouble())}" else "")) }
            }
            LayoutRole.HERO -> if (prop == "height") {
                CssValues.lengthPx(v)?.takeIf { it > 0 }?.let { add("heroScale", fmt((it / DEFAULT_HERO_PX).coerceIn(0.5, 2.0))) }
            }
            // a hidden artwork is no artwork whatever its opacity elsewhere; ::before / ::after are the theme's own layers
            LayoutRole.HOME_HERO -> if (si.pseudoEl) {
                return null
            } else if (hide > 0) {
                cands.add("heroOpacity", fmt(0.0), w, r.id, sel, prop, important, tier = 1)
            } else {
                when (prop) {
                    "opacity" -> fraction(lv)?.let { add("heroOpacity", fmt(it.coerceIn(0.0, 1.0))) }
                    "filter" -> for ((key, value) in heroFilter(lv)) add(key, value)
                    "-webkit-mask-image", "mask-image" -> (if (lv == "none") 0.0 else heroFade(v))?.let { add("heroFade", fmt(it)) }
                }
            }
            else -> {}
        }
        return null
    }

    /**
     * Feeds one resting-state declaration on Steam's home shelf to the "row" layout's shape: where the row sits, where
     * the game title goes, which text is hidden and whether the first tile stays wide. With [stateOnly] (a focus or hover
     * rule) only where the title goes is read: Steam shows the title only in that state, so it is the title's place.
     */
    private fun shelfDecl(
        si: SubjectInfo,
        sel: String,
        prop: String,
        rv: String,
        important: Boolean,
        r: Rule,
        cands: Cands,
        decls: List<Pair<String, String>>,
        cm: SteamClassMap,
        stateOnly: Boolean = false,
    ) {
        val names = si.names.map { shelfName(it.first) }.toSet()
        if (names.isEmpty()) return
        if (stateOnly && names.none { it in SHELF_TITLE_CLASSES }) return
        val v = rv.trim().lowercase()
        val w = 3.0 + (if (important) 0.5 else 0.0)
        fun add(key: String, value: String) = cands.add(key, value, w, r.id, sel, prop, important)
        fun last(p: String) = decls.lastOrNull { it.first == p }?.second?.trim()?.lowercase()
        fun px(value: String?) = value?.let(CssValues::lengthPx)
        val hidden = when (prop) {
            "display" -> v == "none"
            "visibility" -> v == "hidden" || v == "collapse"
            "opacity" -> fraction(v)?.let { it <= 0.0 } ?: false
            else -> false
        }

        if (HOME_CAROUSEL in names && prop == "bottom" && (last("position") == "absolute" || last("position") == "fixed") && last("top") == null) {
            if ((px(v) ?: -1.0) >= 0) add("shelfRowPosition", "bottom")
        }
        if (RECENT_INNER in names) {
            if (prop == "align-self" && (v == "flex-end" || v == "end")) add("shelfRowPosition", "bottom")
            if ((prop == "margin-top" || prop == "top") && (px(v) ?: 0.0) >= ROW_TO_BOTTOM_PX) add("shelfRowPosition", "bottom")
            val autoTopAndBottom = when (prop) {
                "margin" -> v.split(Regex("\\s+")).let { p -> p.first() == "auto" && p.getOrElse(2) { p.first() } == "auto" }
                "margin-top" -> v == "auto" && last("margin-bottom") == "auto"
                else -> false
            }
            if (autoTopAndBottom) add("shelfRowPosition", "center")
        }
        if (RECENT_CONTAINER in names) {
            if (prop == "padding-top") {
                val top = px(v) ?: 0.0
                val bottom = px(last("padding-bottom")) ?: 0.0
                if (top >= ROW_CENTER_PADDING_PX || (top > 0 && bottom > 0)) add("shelfRowPosition", "center")
            }
            val flex = last("display").let { it == "flex" || it == "inline-flex" }
            val column = last("flex-direction")?.startsWith("column") == true
            if (flex && ((prop == "align-items" && v == "center" && !column) || (prop == "justify-content" && v == "center" && column))) {
                add("shelfRowPosition", "center")
            }
        }
        if (names.any { it in SHELF_TITLE_CLASSES }) {
            val lifted = when (prop) {
                "top" -> last("position") == "absolute" && (px(v) ?: 0.0) < 0
                "margin-top" -> (px(v) ?: 0.0) <= -TITLE_ABOVE_PX
                else -> false
            }
            if (lifted) add("shelfTitleAbove", "1")
            if (stateOnly) return
            if (hidden) add("shelfHideTitle", "1")
        }
        val homeSubtitle = "basicgamecarousel_SubMessage" in names ||
            ("appportrait_SubMessage" in names && selectorNames(sel, cm).any { n -> HOME_MODULES.any { n.startsWith(it) } })
        if (hidden && homeSubtitle) add("shelfHideSubtitle", "1")
        if (names.any { it in FEATURED_CLASSES } && prop == "width") {
            px(v)?.takeIf { it > 0 && it <= STEAM_FEATURED_PX / 2 }?.let { add("shelfWideFirst", "0") }
        }
    }

    /** Values gathered over the whole theme for layout values that several rules make up together. */
    private class Extras {
        /** Cover frame rules ("F|selector") and cover image rules ("I|selector"). */
        val frames = LinkedHashMap<String, FrameSel>()
        var reflSeen = false
        var reflOpacity: Double? = null
        var reflFocused: Double? = null
        var reflBlur: Double? = null
        var reflMask: ThemeGradient? = null
        var reflGap: Double? = null
        var boxGap: Double? = null
        var boxMask: ThemeGradient? = null
        val depth = java.util.TreeMap<Int, ThemeDepthStep>()
        var spineSeen = false
        var spineWidth: Float? = null
        var spineColor: Int? = null
        val tabColors = java.util.TreeMap<Int, Int>()
        var homeOverlay: ThemeFill? = null
        var screenOverlay: ThemeFill? = null
        val footerHints = java.util.TreeSet<String>()
        var heroContainerOpacity: Pair<Double, Int>? = null

        /** Rules behind each value, by value name. */
        val rids = HashMap<String, MutableSet<Int>>()

        /** Rules behind the values that made it into the layout. */
        val emitted = HashSet<Int>()

        fun note(key: String, rid: Int) {
            rids.getOrPut(key) { HashSet() }.add(rid)
        }
    }

    /** The resting declarations one cover or cover-image selector gets, later and !important winning as in CSS. */
    private class FrameSel(val wide: Boolean, val image: Boolean, val nth: Pair<Int, Int>?) {
        val decls = HashMap<String, Pair<String, Boolean>>()
        val rids = HashSet<Int>()

        fun put(prop: String, value: String, important: Boolean) {
            val prev = decls[prop]
            if (prev == null || important || !prev.second) decls[prop] = value to important
        }

        fun get(prop: String): String? = decls[prop]?.first
    }

    /** Feeds one declaration to the layout values read off whole selectors (frames, reflections, the grid, overlays, ...). */
    private fun extrasDecl(
        si: SubjectInfo,
        sel: String,
        prop: String,
        rv: String,
        important: Boolean,
        r: Rule,
        cands: Cands,
        decls: List<Pair<String, String>>,
        cm: SteamClassMap,
        ruleFocus: Boolean,
        ruleIcon: Boolean,
        src: SourceSheet,
        ex: Extras,
    ) {
        val names = si.names.map { shelfName(it.first) }.toSet()
        val all = selectorNames(sel, cm).map(::shelfName)
        val v = rv.trim()
        val lv = v.lowercase()
        val w = 3.0 + (if (important) 0.5 else 0.0)
        fun add(key: String, value: String) = cands.add(key, value, w, r.id, sel, prop, important)
        fun last(p: String) = decls.lastOrNull { it.first == p }?.second?.trim()?.lowercase()
        val hidden = when (prop) {
            "display" -> lv == "none"
            "visibility" -> lv == "hidden" || lv == "collapse"
            "opacity" -> fraction(lv)?.let { it <= 0.0 } ?: false
            else -> false
        }
        val resting = !ruleFocus
        val home = all.any { n -> HOME_MODULES.any { n.startsWith(it) } }
        val grid = all.any { n -> GRID_MODULES.any { n.startsWith(it) } }
        val bg = prop == "background" || prop == "background-image"
        val tile = si.layout?.role == LayoutRole.TILE

        if (resting && !si.pseudoEl) {
            if (HOME_CAROUSEL in names && bg) lastUrl(v)?.let(src.resolveUrl)?.let { add("shelfRowImage", it) }
            if (!grid) frameDecl(names, sel, prop, v, important, r, src, ex, cm)
            if (tile && !ruleIcon && prop == "filter") {
                for ((fn, args) in CssValues.functions(v)) {
                    val a = args.firstOrNull()?.let(::fraction) ?: continue
                    when (fn) {
                        "brightness" -> if (kotlin.math.abs(a - 1) > 0.01) add("cardRestBrightness", fmt(a.coerceIn(0.0, 2.0)))
                        "saturate" -> if (kotlin.math.abs(a - 1) > 0.01) add("cardRestSaturation", fmt(a.coerceIn(0.0, 2.0)))
                        "grayscale" -> if (a > 0.01) add("cardRestSaturation", fmt((1 - a).coerceIn(0.0, 1.0)))
                    }
                }
            }
            if (tile && prop == "-webkit-box-reflect" && lv.startsWith("below")) {
                ex.boxGap = lv.removePrefix("below").trim().split(Regex("\\s+")).firstOrNull()?.let(CssValues::lengthPx) ?: 0.0
                ex.boxMask = CssValues.parseGradient(v)
                ex.note("reflection", r.id)
            }
            if ("appgrid_YourCollection" in names && prop == "grid-template-columns") {
                REPEAT_RE.find(lv)?.let { m ->
                    m.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_GRID_COLUMNS }?.let { add("gridColumns", it.toString()) }
                    val col = CssValues.lengthPx(m.groupValues[2].trim())
                    val row = last("grid-auto-rows")?.let(CssValues::lengthPx)
                    if (col != null && row != null && col > 0 && row > 0) add("gridTileAspect", fmt(col / row))
                }
            }
            if (prop == "zoom" && ("appgrid_GridWithControls" in names || "appgrid_YourCollection" in names)) {
                fraction(lv)?.takeIf { it > 0 && kotlin.math.abs(it - 1) >= MIN_SCALE_CHANGE }?.let { add("cardScale", fmt(it.coerceIn(0.5, 1.5))) }
            }
            if (prop == "display" && lv != "none" && GRIDCELL_RE.containsMatchIn(sel) && names.isEmpty()) add("gridTitleBelow", "1")
            if ("gamepadtabbedpage_Tab" in names && (prop == "background" || prop == "background-color")) {
                val subj = splitCompounds(sel).last()
                if (!subj.contains("aria-selected")) {
                    val n = NTH_LITERAL_RE.find(subj)?.groupValues?.get(1)?.toIntOrNull()
                    val c = CssValues.findColors(v).lastOrNull()?.takeIf { it.a > 0 }
                    if (n != null && c != null) {
                        ex.tabColors[n] = c.toArgb()
                        ex.note("tabColors", r.id)
                    }
                }
            }
            if (si.layout?.role == LayoutRole.HOME_HERO && names.isNotEmpty()) {
                if (bg) {
                    lastUrl(v)?.let(src.resolveUrl)?.let { path ->
                        add("heroImage", path)
                        val repeat = last("background-repeat") ?: lv.takeIf { prop == "background" }
                        if (repeat != null && repeat.contains("repeat") && !repeat.contains("no-repeat")) add("heroImageTile", "1")
                        last("background-size")?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.endsWith("%") }?.let(::fraction)
                            ?.takeIf { it > 0 }?.let { add("heroImageScale", fmt(it)) }
                    }
                }
                if (prop == "opacity" && names.any { it == "gamepadhomerecentgames_RecentGamesBackgroundContainer" }) {
                    fraction(lv)?.let { ex.heroContainerOpacity = it to r.id }
                }
            }
            if (si.layout?.role == LayoutRole.HOME_HERO && prop == "height") {
                CssValues.lengthPx(v)?.let { it / CssValues.VIEWPORT_H }?.takeIf { it > 0 && it < 1 }?.let { add("heroHeightFraction", fmt(it)) }
            }
            if (FADE_CLASS in names && bg) {
                val masked = (last("-webkit-mask-image") ?: last("mask-image"))?.let { it != "none" } == true
                if (!masked) {
                    CssValues.parseFill(v)?.let {
                        ex.homeOverlay = it
                        ex.note("homeOverlay", r.id)
                    }
                }
            }
        }
        if (resting && hidden) {
            if ("gamepadtabbedpage_TabCount" in names) add("hideTabCounts", "1")
            if (names.any { it == "searchbar_SearchAndTitleContainer" || it == "searchbar_SearchBox" }) add("hideTopBarSearch", "1")
            if (FOOTER_SEL_RE.containsMatchIn(sel)) {
                val found = HINT_RE.findAll(sel).map { it.groupValues[1].ifEmpty { it.groupValues[2] }.uppercase() }.toList()
                if (found.isNotEmpty()) {
                    ex.footerHints.addAll(found)
                    ex.note("footerHints", r.id)
                }
            }
        }

        // the mirrored copy Steam draws under each cover for its glow, turned into a reflection
        if (GLOW_CLASS in names && !si.pseudoEl) {
            if (!si.anyFocus) {
                when (prop) {
                    "transform" -> if (mirrored(v)) {
                        ex.reflSeen = true
                        ex.note("reflection", r.id)
                    }
                    "opacity" -> fraction(lv)?.let { ex.reflOpacity = it }
                    "filter" -> CssValues.functions(v).firstOrNull { it.first == "blur" }?.second?.firstOrNull()?.let(CssValues::lengthPx)?.let { ex.reflBlur = it }
                    "-webkit-mask-image", "mask-image" -> ex.reflMask = CssValues.parseGradient(v)
                    "top" -> {
                        val t = CssValues.lengthPx(v)
                        val h = last("height")?.let(CssValues::lengthPx)
                        if (t != null && h != null && t >= h) ex.reflGap = t - h
                    }
                }
            } else if (prop == "opacity" && tileSlot(sel, cm) == TileSlot.FOCUSED) {
                fraction(lv)?.let { ex.reflFocused = it }
            }
        }
        if (tile && !si.pseudoEl && prop == "transform") {
            depthStep(sel, v, cm)?.let { (k, step) ->
                ex.depth[k] = step
                ex.note("depth", r.id)
            }
        }
        // a pseudo-element of a cover turned edge-on is the side face of a box
        if (tile && si.pseudoEl && resting) {
            when (prop) {
                "transform" -> if (CssValues.functions(v).any { it.first == "rotatey" && angleArg(it.second)?.let { a -> kotlin.math.abs(kotlin.math.abs(a) - 90) < 0.5 } == true }) {
                    ex.spineSeen = true
                    ex.note("spine", r.id)
                }
                "width" -> CssValues.lengthPx(v)?.takeIf { it > 0 }?.let {
                    ex.spineWidth = it.toFloat()
                    ex.note("spine", r.id)
                }
                "background-color", "background" -> CssValues.findColors(v).firstOrNull { it.a > 0 }?.let {
                    ex.spineColor = it.toArgb()
                    ex.note("spine", r.id)
                }
            }
        }
        if (isRowItem(sel)) {
            if (prop == "transform" && FOCUS_MARK_RE.containsMatchIn(NOT_RE.replace(sel, "")) && tileSlot(sel, cm) == TileSlot.FOCUSED) {
                focusedScale(v)?.let { add("cardFocusedScale", it) }
            }
            if (resting && home && prop == "flex-grow" && (lv.toDoubleOrNull() ?: 0.0) > 0 && last("flex-basis")?.let(CssValues::lengthPx) == 0.0) {
                add("shelfFitAll", "1")
            }
        }
        if (si.pseudoEl && resting && "gamepadhomerecentgames_RecentGamesHeader" in names && prop == "content" && last("display") != "none") {
            unquote(v)?.let { add("shelfHeaderText", it) }
        }
        if (!si.anyFocus && "DownloadArrow" in all) {
            if (!si.pseudoEl && prop == "filter") {
                for ((fn, args) in CssValues.functions(v)) {
                    val a = args.firstOrNull()?.let(::fraction) ?: continue
                    when (fn) {
                        "grayscale" -> add("uninstalledSaturation", fmt((1 - a).coerceIn(0.0, 1.0)))
                        "saturate" -> add("uninstalledSaturation", fmt(a.coerceIn(0.0, 1.0)))
                    }
                }
            }
            if (!si.pseudoEl && prop == "opacity") fraction(lv)?.let { add("uninstalledOpacity", fmt(it.coerceIn(0.0, 1.0))) }
            if (si.pseudoEl && prop == "content") {
                CssValues.firstUrl(v)?.let(src.resolveUrl)?.takeIf { RASTER_RE.containsMatchIn(it) }?.let { add("uninstalledIcon", it) }
            }
        }
        // a layer over the whole UI that lets input through
        if (si.pseudoEl && resting && bg && "BasicUI" in names) {
            val inFront = last("pointer-events") == "none" && (last("position") == "absolute" || last("position") == "fixed")
            val shown = last("opacity")?.let { fraction(it)?.let { o -> o > 0 } } ?: true
            if (inFront && shown && last("display") != "none") {
                CssValues.parseFill(v)?.let { tiled(it, last("background-size")) }?.let {
                    ex.screenOverlay = it
                    ex.note("screenOverlay", r.id)
                }
            }
        }
    }

    /** Collects the frame image, mask and image box of the home row's covers, per selector. */
    private fun frameDecl(names: Set<String>, sel: String, prop: String, v: String, important: Boolean, r: Rule, src: SourceSheet, ex: Extras, cm: SteamClassMap) {
        val parts = selectorParts(NOT_RE.replace(sel, ""))
        if (parts.isEmpty()) return
        val above = parts.dropLast(1)
        fun classesOf(c: String) = compoundClasses(c).let { (a, b) -> a + b }.map { shelfName(cm.readable(it).first) }
        val coverHere = names.any { it in COVER_CLASSES }
        val imageHere = !coverHere && (
            names.any { it in COVER_IMAGE_CLASSES } ||
                (names.isEmpty() && BARE_IMG_RE.matches(parts.last().second) && above.lastOrNull()?.let { classesOf(it.second).any { c -> c in COVER_IMAGE_CLASSES } } == true)
            )
        if (!coverHere && !imageHere) return
        val coverNames = if (coverHere) names else above.flatMap { classesOf(it.second) }.toSet()
        if (coverNames.none { it in COVER_CLASSES }) return
        val nth = above.firstNotNullOfOrNull { NTH_RE.find(it.second) }?.let { anb(it.groupValues[1]) }
        val key = (if (coverHere) "F|" else "I|") + sel.replace(Regex("\\s+"), " ").trim()
        val fs = ex.frames.getOrPut(key) { FrameSel(coverNames.any { it in WIDE_COVER_CLASSES }, !coverHere, nth) }
        when (prop) {
            "background", "background-image" -> if (coverHere) fs.put("image", lastUrl(v)?.let(src.resolveUrl) ?: "none", important) else return
            "-webkit-mask-image", "mask-image" -> fs.put("mask", lastUrl(v)?.let(src.resolveUrl) ?: "none", important)
            in FRAME_PROPS -> fs.put(prop, v.lowercase(), important)
            else -> return
        }
        fs.rids.add(r.id)
    }

    /** Portrait frames in the order of the covers they cycle over, and the wide cover's frame. */
    private fun frames(ex: Extras): Pair<List<ThemeTileFrame>?, ThemeTileFrame?> {
        val covers = ex.frames.values.filter { !it.image && it.get("image")?.let { p -> p != "none" } == true }
        val images = ex.frames.values.filter { it.image }
        fun frameOf(f: FrameSel): ThemeTileFrame {
            val img = images.filter { it.wide == f.wide && (it.nth == null || it.nth == f.nth) }.sortedBy { if (it.nth == null) 1 else 0 }
            val mask = img.firstNotNullOfOrNull { it.get("mask")?.takeIf { m -> m != "none" } } ?: f.get("mask")?.takeIf { it != "none" }
            val box = img.firstOrNull { i -> listOf("left", "top", "width", "height").all { i.get(it) != null } }
            val insets = box?.let { insetsOf(f, it) }
            ex.emitted.addAll(f.rids)
            img.forEach { ex.emitted.addAll(it.rids) }
            return ThemeTileFrame(
                image = f.get("image")!!,
                mask = mask,
                insetLeft = insets?.get(0) ?: 0f,
                insetTop = insets?.get(1) ?: 0f,
                insetWidth = insets?.get(2) ?: 1f,
                insetHeight = insets?.get(3) ?: 1f,
            )
        }
        val wide = covers.firstOrNull { it.wide }?.let(::frameOf)
        val portraits = covers.filter { !it.wide }
        val cycled = portraits.filter { (it.nth?.first ?: 0) > 0 }
        val list = if (cycled.isNotEmpty()) {
            val period = cycled.first().nth!!.first
            (0 until period).mapNotNull { i -> cycled.firstOrNull { anbMatches(it.nth!!, i + FIRST_PORTRAIT_CHILD) }?.let(::frameOf) }
        } else {
            portraits.firstOrNull { it.nth == null }?.let { listOf(frameOf(it)) }
        }
        return list?.takeIf { it.isNotEmpty() } to wide
    }

    /**
     * Where the cover image sits in its frame, as fractions of the frame image's own box: percentages of the cover as they
     * are; pixels against the frame image drawn at its `background-size` width (as wide as the cover) and placed by
     * `background-position-y` inside the cover's box, which is Steam's capsule height whatever height the element is given.
     */
    private fun insetsOf(f: FrameSel, img: FrameSel): List<Float>? {
        val vals = listOf("left", "top", "width", "height").map { img.get(it) ?: return null }
        if (vals.all { it.endsWith("%") }) return vals.map { (fraction(it) ?: return null).toFloat() }
        val px = vals.map { CssValues.lengthPx(it) ?: return null }
        val fw = f.get("background-size")?.split(Regex("\\s+"))?.firstOrNull()?.let(CssValues::lengthPx)?.takeIf { it > 0 } ?: return null
        val (iw, ih) = imageSize(f.get("image") ?: return null) ?: return null
        val fh = fw * ih / iw
        val eh = STEAM_COVER_PX
        val top0 = (eh - fh) * (f.get("background-position-y")?.let(::fraction) ?: 0.0)
        return listOf(px[0] / fw, (px[1] - top0) / fh, px[2] / fw, px[3] / fh).map { it.toFloat().coerceIn(-1f, 2f) }
    }

    /** Width and height of a PNG from its header; null for other files. */
    private fun imageSize(path: String): Pair<Double, Double>? = try {
        java.io.File(path).inputStream().use { s ->
            val b = ByteArray(24)
            var n = 0
            while (n < b.size) {
                val k = s.read(b, n, b.size - n)
                if (k < 0) break
                n += k
            }
            fun int(o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
            if (n < b.size || b[0] != 0x89.toByte() || b[1] != 'P'.code.toByte()) {
                null
            } else {
                val w = int(16)
                val h = int(20)
                if (w > 0 && h > 0) w.toDouble() to h.toDouble() else null
            }
        }
    } catch (e: java.io.IOException) {
        null
    }

    /** `An+B` of `:nth-child()` as (A, B); null for forms it cannot read. */
    private fun anb(s: String): Pair<Int, Int>? {
        val t = s.replace(" ", "").lowercase()
        if (t == "odd") return 2 to 1
        if (t == "even") return 2 to 0
        t.toIntOrNull()?.let { return 0 to it }
        val m = ANB_RE.matchEntire(t) ?: return null
        val a = when (val g = m.groupValues[1]) {
            "", "+" -> 1
            "-" -> -1
            else -> g.toIntOrNull() ?: return null
        }
        val b = m.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        return a to b
    }

    private fun anbMatches(ab: Pair<Int, Int>, n: Int): Boolean {
        val (a, b) = ab
        if (a == 0) return n == b
        val d = n - b
        return d % a == 0 && d / a >= 0
    }

    /** A transform flipping its element upside down. */
    private fun mirrored(v: String): Boolean = CssValues.functions(v).any { (fn, args) ->
        val y = when (fn) {
            "scaley" -> args.getOrNull(0)
            "scale" -> args.getOrNull(1)
            else -> null
        }
        (y?.let { CssValues.quantity(it)?.value } ?: 0.0) < 0
    }

    /**
     * Opacity at the cover's edge and the share of the reflection it fades out over, from the mask gradient of a mirrored
     * copy: a gradient `to top` starts at the edge, one `to bottom` ends there. Null for other masks.
     */
    private fun reflectionFade(g: ThemeGradient): Pair<Double, Double>? {
        if (g.radial != null || (g.angleDeg != 0f && g.angleDeg != 180f) || g.colors.isEmpty()) return null
        val n = g.colors.size
        val pos = if (g.stops.size == n) g.stops.map { it.toDouble() } else List(n) { if (n == 1) 0.0 else it.toDouble() / (n - 1) }
        val pts = g.colors.indices.map { i -> (if (g.angleDeg == 0f) pos[i] else 1 - pos[i]) to ((g.colors[i] ushr 24) / 255.0) }.sortedBy { it.first }
        val fade = pts.firstOrNull { it.second <= 0.01 && it.first > 0 }?.first ?: 1.0
        return pts.first().second to fade
    }

    /**
     * Step k-1 for a cover k siblings (`+`) after the focused one or after the featured capsule (`:has()`), from its
     * `translate()` and `scale()`. The share of a horizontal shift is of the unscaled cover's width (a translate percentage
     * refers to the element's own box), the rest of it is in dp.
     */
    private fun depthStep(sel: String, v: String, cm: SteamClassMap): Pair<Int, ThemeDepthStep>? {
        val parts = selectorParts(NOT_RE.replace(sel, ""))
        val anchor = parts.indexOfLast { (_, c) ->
            isFocusCompound(c, cm) || (c.contains(":has(") && compoundClasses(c).first.any { cm.readable(it).first == "basicgamecarousel_Featured" })
        }
        if (anchor < 0) return null
        val after = parts.drop(anchor + 1)
        if (after.any { it.first == '~' }) return null
        val k = after.count { it.first == '+' }
        if (k == 0) return null
        val scale = CssValues.transformScale(v) ?: 1.0
        val tr = CssValues.functions(v).firstOrNull { it.first == "translate" }?.second
        val x = tr?.getOrNull(0)?.let(::percentShare) ?: 0.0
        val xDp = tr?.getOrNull(0)?.let(::lengthBesideShare) ?: 0.0
        val y = tr?.getOrNull(1)?.let(CssValues::lengthPx) ?: 0.0
        if (scale == 1.0 && x == 0.0 && xDp == 0.0 && y == 0.0) return null
        return (k - 1) to ThemeDepthStep(scale.toFloat(), x.toFloat(), y.toFloat(), xDp.toFloat())
    }

    /** The share of the element's own size in a translate length: `-14%` or `calc(-0.14 * 100% - 30px)` -> -0.14. */
    private fun percentShare(v: String): Double? {
        CALC_PCT_RE.find(v)?.let { return it.groupValues[1].toDoubleOrNull() }
        return PCT_ONLY_RE.find(v)?.groupValues?.get(1)?.toDoubleOrNull()?.div(100)
    }

    /** The length in a translate value once its share of the element is taken out: `calc(-0.14 * 100% - 30px)` -> -30. */
    private fun lengthBesideShare(v: String): Double? = CssValues.lengthPx(PCT_ONLY_RE.replace(CALC_PCT_RE.replace(v, "0"), "0"))

    /** `innerScrollContainer > div`: one item of a row of covers. */
    private fun isRowItem(sel: String): Boolean {
        val parts = selectorParts(NOT_RE.replace(sel, ""))
        if (parts.size < 2) return false
        val (comb, subj) = parts.last()
        return comb == '>' && ROW_ITEM_RE.matches(subj) && parts[parts.size - 2].second.contains("innerScrollContainer")
    }

    /** Scale of a focused cover other than 1. */
    private fun focusedScale(v: String): String? =
        CssValues.transformScale(v)?.takeIf { it > 0 && it != 1.0 }?.let { fmt(it.coerceIn(0.5, 2.0)) }

    /**
     * On-screen size of the focused cover box under a `transform` that replaces Steam's own: `none` is 1, `translateZ(N)`
     * under Steam's perspective is P / (P - N), `scale(s)` is s, and they multiply.
     */
    private fun focusPop(v: String): Double {
        var s = 1.0
        for ((fn, args) in CssValues.functions(v)) {
            when (fn) {
                "translatez" -> args.firstOrNull()?.let(CssValues::lengthPx)?.takeIf { it < STEAM_FOCUS_PERSPECTIVE_PX }?.let {
                    s *= STEAM_FOCUS_PERSPECTIVE_PX / (STEAM_FOCUS_PERSPECTIVE_PX - it)
                }
                "translate3d" -> args.getOrNull(2)?.let(CssValues::lengthPx)?.takeIf { it < STEAM_FOCUS_PERSPECTIVE_PX }?.let {
                    s *= STEAM_FOCUS_PERSPECTIVE_PX / (STEAM_FOCUS_PERSPECTIVE_PX - it)
                }
                "scale" -> CssValues.transformScale("scale(${args.joinToString(", ")})")?.let { s *= it }
            }
        }
        return s
    }

    /** A quoted CSS string's text; null for anything else or an empty string. */
    private fun unquote(v: String): String? {
        val t = v.trim()
        if (t.length < 2 || (t[0] != '"' && t[0] != '\'') || t.last() != t[0]) return null
        return CSS_ESCAPE_RE.replace(t.substring(1, t.length - 1)) { it.groupValues[1] }.trim().takeIf { it.isNotEmpty() }
    }

    /** [f] with each layer repeating at the size its `background-size` entry gives it along the gradient line. */
    private fun tiled(f: ThemeFill, sizes: String?): ThemeFill {
        if (sizes == null) return f
        val entries = CssValues.splitTop(sizes, ',').map { it.trim().split(Regex("\\s+")) }
        return f.copy(
            layers = f.layers.mapIndexed { i, g ->
                val e = entries.getOrNull(i) ?: return@mapIndexed g
                val along = when (g.angleDeg) {
                    90f, 270f -> e.getOrNull(0)
                    0f, 180f -> e.getOrNull(1) ?: e.getOrNull(0)
                    else -> null
                }
                val px = along?.takeIf { !it.endsWith("%") }?.let(CssValues::lengthPx)?.takeIf { it > 0 }
                if (g.radial == null && px != null) g.copy(repeatDp = px.toFloat()) else g
            },
        )
    }

    /** [spec] with the layout values that several rules make up. */
    private fun withExtras(spec: ThemeSpec, ex: Extras): ThemeSpec {
        val (portraits, wide) = frames(ex)
        val reflection = ex.boxGap?.let { gap ->
            val (edge, fade) = ex.boxMask?.let(::reflectionFade) ?: (1.0 to 1.0)
            ThemeReflection(opacity = edge.toFloat(), fadeStop = fade.toFloat(), gapDp = gap.toFloat())
        } ?: if (ex.reflSeen) {
            val (edge, fade) = ex.reflMask?.let(::reflectionFade) ?: (1.0 to 1.0)
            ThemeReflection(
                opacity = ((ex.reflOpacity ?: 1.0) * edge).toFloat().coerceIn(0f, 1f),
                focusedOpacity = ex.reflFocused?.let { (it * edge).toFloat().coerceIn(0f, 1f) },
                blurDp = (ex.reflBlur ?: 0.0).toFloat(),
                fadeStop = fade.toFloat(),
                gapDp = (ex.reflGap ?: 0.0).toFloat(),
            )
        } else {
            null
        }
        val depth = if (ex.depth.isEmpty()) {
            null
        } else {
            var prev = ThemeDepthStep()
            (0..ex.depth.lastKey()).map { i -> (ex.depth[i] ?: prev).also { prev = it } }
        }
        val tabs = ex.tabColors.takeIf { it.isNotEmpty() && it.lastKey() - it.firstKey() + 1 == it.size }?.values?.toList()
        val extra = ThemeLayout(
            tileFrames = portraits,
            tileFrameWide = wide,
            coverReflection = reflection,
            cardDepthSteps = depth,
            cardSpineWidthDp = ex.spineWidth.takeIf { ex.spineSeen },
            cardSpineColor = ex.spineColor.takeIf { ex.spineSeen },
            tabColors = tabs,
            homeOverlay = ex.homeOverlay,
            screenOverlay = ex.screenOverlay,
            footerHiddenHints = ex.footerHints.toList().takeIf { it.isNotEmpty() },
        )
        if (extra.isEmpty) return spec
        for ((key, set) in listOf(
            "reflection" to reflection, "depth" to depth, "spine" to (extra.cardSpineWidthDp ?: extra.cardSpineColor), "tabColors" to tabs,
            "homeOverlay" to ex.homeOverlay, "screenOverlay" to ex.screenOverlay, "footerHints" to extra.footerHiddenHints,
        )) {
            if (set != null) ex.emitted.addAll(ex.rids[key].orEmpty())
        }
        return spec.copy(layout = (spec.layout ?: ThemeLayout()).overlay(extra))
    }

    /** Where a tile rule applies on a row: every cover, the covers after the focused one, or the focused cover. */
    private enum class TileSlot { BASE, AFTER, FOCUSED }

    /**
     * The lean of tiles from a `transform`: `skew()` / `rotate()` is a tilt, `perspective() rotateY()` a 3D turn. A rule on
     * the focused tile only tells whether the focused tile stays flat; rules on one position of the row are skipped.
     */
    private fun tiltDecl(lr: LayoutMatch, sel: String, rv: String, important: Boolean, r: Rule, cands: Cands, cm: SteamClassMap) {
        val slot = tileSlot(sel, cm) ?: return
        val w = lr.priority + (if (important) 0.5 else 0.0)
        fun add(key: String, value: String) = cands.add(key, value, w, r.id, sel, "transform", important)
        val fns = CssValues.functions(rv)
        if (slot == TileSlot.FOCUSED) {
            val clean = NOT_RE.replace(sel, "")
            // a hover state alone is not the focused cover
            if (!FOCUS_MARK_RE.containsMatchIn(clean)) return
            if (fns.none { it.first.startsWith("skew") || it.first.startsWith("rotate") }) add("cardFocusedFlat", "1")
            // a transform on the focused cover box replaces Steam's own pop of it; one on what holds or fills the box adds to it
            if (lr.readable == COVER_BOX) {
                val wide = splitCompounds(clean).lastOrNull()?.let { c -> compoundClasses(c).first.any { cm.readable(it).first == "appportrait_Landscape" } } == true
                add(if (wide) "cardFocusPopWide" else "cardFocusPop", "%.4f".format(java.util.Locale.ROOT, focusPop(rv).coerceIn(0.5, 2.0)))
            } else {
                focusedScale(rv)?.let { add("cardFocusedScale", it) }
            }
            return
        }
        val after = slot == TileSlot.AFTER
        val homeShelf = lr.readable.startsWith("basicgamecarousel_") || HOME_SHELF_SCOPE.containsMatchIn(sel)
        val tilt = fns.firstOrNull { it.first in setOf("rotate", "rotatez", "skew", "skewx") }?.let { angleArg(it.second) }
        if (tilt != null && kotlin.math.abs(tilt) > 1e-3) {
            add(if (after) "cardTiltAfter" else "cardTilt", fmt(tilt.coerceIn(-MAX_TILT_DEG.toDouble(), MAX_TILT_DEG.toDouble())))
            if (homeShelf) add("libraryLayout", "row")
        }
        val perspective = fns.firstOrNull { it.first == "perspective" }?.second?.firstOrNull()?.let(CssValues::lengthDp)?.takeIf { it > 0 } ?: return
        val turn = fns.firstOrNull { it.first == "rotatey" }?.let { angleArg(it.second) }
        if (turn != null && kotlin.math.abs(turn) > 1e-3) {
            add(if (after) "cardTurnAfter" else "cardTurn", fmt(turn.coerceIn(-MAX_TURN_DEG.toDouble(), MAX_TURN_DEG.toDouble())))
            add("cardTurnPerspective", fmt(perspective.toDouble()))
            if (homeShelf) add("libraryLayout", "row")
        }
    }

    /** First argument of a transform function in degrees; 0 for a bare 0, null for anything else. */
    private fun angleArg(args: List<String>): Double? {
        val q = CssValues.quantity(args.firstOrNull() ?: return null) ?: return null
        return when {
            q.dim == CssValues.Dim.ANGLE -> q.value
            q.dim == CssValues.Dim.NUMBER && q.value == 0.0 -> 0.0
            else -> null
        }
    }

    /**
     * [TileSlot] of a tile selector: a focus state (`.gpfocus`, `.gpfocuswithin`, `[tabindex]`, `:focus`, ...) followed by `~`
     * is the covers after the focused one; a focus state with no `~` after it is the focused cover; none is every cover.
     * Null for selectors that pick a position (`+`, `:nth-child()` above the subject, `~` without a focus state).
     */
    private fun tileSlot(sel: String, cm: SteamClassMap): TileSlot? {
        val parts = selectorParts(NOT_RE.replace(sel, ""))
        if (parts.any { it.first == '+' } || parts.dropLast(1).any { STRUCTURAL_RE.containsMatchIn(it.second) }) return null
        val focusAt = parts.indexOfLast { isFocusCompound(it.second, cm) }
        val siblingAt = parts.indexOfLast { it.first == '~' }
        return when {
            focusAt < 0 -> if (siblingAt >= 0) null else TileSlot.BASE
            siblingAt > focusAt -> TileSlot.AFTER
            else -> TileSlot.FOCUSED
        }
    }

    private fun isFocusCompound(comp: String, cm: SteamClassMap): Boolean {
        if (SteamClassRoles.STATE_PSEUDO.any { comp.contains(it) } || comp.contains("[tabindex")) return true
        return compoundClasses(comp).let { (a, b) -> a + b }.map { cm.readable(it).first }.any { n ->
            val role = SteamClassRoles.roleOf(n)?.role
            role == SteamRole.FOCUS || role == SteamRole.FOCUS_WITHIN || HOVER_STATE_RE.containsMatchIn(n)
        }
    }

    /** Compounds of a selector, each with the combinator before it (' ' for descendant and for the first). */
    private fun selectorParts(sel: String): List<Pair<Char, String>> {
        val out = ArrayList<Pair<Char, String>>()
        val cur = StringBuilder()
        var comb = ' '
        var depth = 0
        var quote: Char? = null
        fun flush() {
            if (cur.isEmpty()) return
            out.add(comb to cur.toString())
            cur.setLength(0)
            comb = ' '
        }
        for (c in sel.trim()) {
            if (quote != null) {
                cur.append(c)
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
                cur.append(c)
            } else if (c == '(' || c == '[') {
                depth++
                cur.append(c)
            } else if (c == ')' || c == ']') {
                depth--
                cur.append(c)
            } else if (depth == 0 && (c == '>' || c == '+' || c == '~')) {
                flush()
                comb = c
            } else if (depth == 0 && c.isWhitespace()) {
                flush()
            } else {
                cur.append(c)
            }
        }
        flush()
        return out
    }

    /** A readable home class without the trailing `_` of CSS Loader's `module_Name_` spelling; bare `RecentGames*` names get their module. */
    private fun shelfName(readable: String): String {
        val n = readable.removeSuffix("_")
        return if (!n.contains('_') && n.startsWith("RecentGames")) "gamepadhomerecentgames_$n" else n
    }

    /** Readable classes of every compound of [sel], outside :not(). */
    private fun selectorNames(sel: String, cm: SteamClassMap): List<String> =
        splitCompounds(sel).flatMap { comp -> compoundClasses(comp).let { (a, b) -> a + b } }.map { cm.readable(it).first }

    /** Artwork styled with small game icons, or inside Steam's collections page, is not the library tile. */
    private fun notALibraryTile(sel: String, si: SubjectInfo, ruleIcon: Boolean, cm: SteamClassMap): Boolean =
        ruleIcon || si.layout?.role == LayoutRole.ICON || selectorNames(sel, cm).any { it.startsWith("allcollections_") }

    /** True when [sel] picks some tiles by their position among their siblings (`+`, `~`, `:nth-child()` above the subject). */
    private fun selectsSomeTiles(sel: String): Boolean {
        val s = NOT_RE.replace(sel, "")
        var depth = 0
        var quote: Char? = null
        for (c in s) {
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '(' || c == '[') {
                depth++
            } else if (c == ')' || c == ']') {
                depth--
            } else if (depth == 0 && (c == '+' || c == '~')) {
                return true
            }
        }
        return splitCompounds(s).dropLast(1).any { STRUCTURAL_RE.containsMatchIn(it) }
    }

    /** Feeds one font-size, font-weight, letter-spacing or text-transform declaration on a text element. */
    private fun typeDecl(sel: String, si: SubjectInfo, prop: String, rv: String, important: Boolean, r: Rule, cands: Cands, cm: SteamClassMap) {
        if (si.pseudoEl || si.disabled) return
        val readable = si.names.map { it.first }
        val selected = readable.any { SELECTED_RE.containsMatchIn(it) }
        if (si.anyFocus && (!selected || focusedBeyondSelected(sel, cm))) return
        val w = 3.0 + (if (important) 0.5 else 0.0)
        fun add(key: String, value: String) = cands.add(key, value, w, r.id, sel, prop, important)
        val heading = readable.any { SteamClassRoles.HEADING_RE.containsMatchIn(it) }
        when (prop) {
            "font-size" -> {
                if (selected) return
                val px = fontPx(rv) ?: return
                val (key, steamPx) = readable.firstNotNullOfOrNull { STEAM_FONT_PX[it] } ?: return
                add(key, fmt((px / steamPx).coerceIn(ThemeTypography.MIN_SCALE.toDouble(), ThemeTypography.MAX_SCALE.toDouble())))
            }
            "font-weight" -> {
                val weight = cssWeight(rv) ?: return
                if (selected) add("selectedWeight", weight.toString()) else if (heading) add("headingWeight", weight.toString())
            }
            "letter-spacing" -> {
                if (selected) return
                val known = readable.firstNotNullOfOrNull { STEAM_FONT_PX[it]?.second }
                if (known == null && si.base?.role !in TEXT_ROLES) return
                letterSpacingEm(rv, known ?: BODY_FONT_PX)?.let { add("letterSpacing", fmt(it)) }
            }
            "text-transform" -> {
                val case = when (rv.trim().lowercase()) {
                    "uppercase" -> "uppercase"
                    "none", "lowercase", "capitalize" -> "none"
                    else -> return
                }
                when {
                    heading -> add("headingCase", case)
                    readable.any { TAB_LABEL_RE.matches(it) } -> add("tabCase", case)
                    readable.any { FOOTER_TEXT_RE.containsMatchIn(it) } -> add("footerCase", case)
                }
            }
        }
    }

    /** True when [sel] is focused, hovered or pressed beyond naming the selected item. */
    private fun focusedBeyondSelected(sel: String, cm: SteamClassMap): Boolean {
        val noNot = NOT_RE.replace(sel, "")
        if (SteamClassRoles.STATE_PSEUDO.any { noNot.contains(it) }) return true
        return splitCompounds(sel).any { comp ->
            compoundClasses(comp).let { (a, b) -> a + b }.map { cm.readable(it).first }.any { n ->
                val role = SteamClassRoles.roleOf(n)?.role
                ((role == SteamRole.FOCUS || role == SteamRole.FOCUS_WITHIN) && !SELECTED_RE.containsMatchIn(n)) || HOVER_STATE_RE.containsMatchIn(n)
            }
        }
    }

    /** Which kind of text an upper-casing rule reaches, or null for kinds that do not tell. */
    private fun upperKind(si: SubjectInfo, global: Boolean): String? {
        if (global) return "global"
        if (si.anyFocus || si.pseudoEl) return null
        val readable = si.names.map { it.first }
        val role = si.base?.role
        return when {
            readable.any { SteamClassRoles.HEADING_RE.containsMatchIn(it) } -> "heading"
            readable.any { TAB_LABEL_RE.matches(it) } -> "tab"
            readable.any { FOOTER_TEXT_RE.containsMatchIn(it) } -> "footer"
            si.inMenu || role == SteamRole.MENU_ITEM -> "menu"
            role == SteamRole.BUTTON || role == SteamRole.BUTTON_PRIMARY -> "button"
            readable.any { it.startsWith("gamepaddialog_Field") } -> "row"
            readable.any { it.startsWith("mainmenu_") || it.startsWith("gamepadpagedsettings_") } -> "nav"
            else -> null
        }
    }

    /** `invert(1)` -> true, `none` or a light invert -> false; null for other filters. */
    private fun glyphInvert(v: String): Boolean? {
        if (v.trim().lowercase() == "none") return false
        val amount = CssValues.functions(v).firstOrNull { it.first == "invert" }?.second?.firstOrNull()?.let(::fraction) ?: return null
        return amount >= 0.5
    }

    /**
     * A translucent linear gradient over a solid color (`linear-gradient(#000c, #000c), green`) as the one opaque
     * gradient it paints; null for any other value.
     */
    private fun overBase(v: String): String? {
        val layers = CssValues.splitTop(v, ',')
        if (layers.size < 2) return null
        val base = CssValues.parseColor(layers.last().trim())?.takeIf { it.a >= 0.999 } ?: return null
        val g = CssValues.parseGradient(v)?.takeIf { it.radial == null } ?: return null
        if (g.colors.all { (it ushr 24) == 0xff }) return null
        val under = base.toArgb()
        val stops = g.stops.takeIf { it.size == g.colors.size }
        val parts = g.colors.mapIndexed { i, c ->
            Rgba.fromArgb(ThemeContrast.over(c, under)).toHex() + (stops?.let { " ${fmt(it[i] * 100.0)}%" } ?: "")
        }
        return "linear-gradient(${fmt(g.angleDeg.toDouble())}deg, ${parts.joinToString(", ")})"
    }

    /** An absolute font size in px; null for relative sizes (em, %, keywords) and viewport units. */
    private fun fontPx(v: String): Double? {
        val lv = v.trim().lowercase()
        if (!lv.contains("px") || lv.contains("em") || lv.contains('%') || lv.contains("vh") || lv.contains("vw")) return null
        return CssValues.lengthPx(lv)?.takeIf { it > 0 }
    }

    private fun cssWeight(v: String): Int? = when (val lv = v.trim().lowercase()) {
        "normal" -> 400
        "bold", "bolder" -> 700
        "lighter" -> 300
        else -> lv.toIntOrNull()?.takeIf { it in 1..1000 }?.let(ThemeTypography::weight)
    }

    /** Letter spacing in em for text of [fontPx]; `normal` is 0. */
    private fun letterSpacingEm(v: String, fontPx: Double): Double? {
        val lv = v.trim().lowercase()
        if (lv == "normal") return 0.0
        val em = when {
            lv.endsWith("rem") -> null
            lv.endsWith("em") -> lv.removeSuffix("em").trim().toDoubleOrNull()
            else -> CssValues.lengthPx(lv)?.div(fontPx)
        } ?: return null
        return em.coerceIn(-0.1, ThemeTypography.MAX_LETTER_SPACING_EM.toDouble())
    }

    /** Duration in seconds of the first animation in [value] that runs one of [slides]. */
    private fun slideSeconds(value: String, slides: Set<String>): Double? {
        for (part in CssValues.splitTop(value, ',')) {
            val tokens = part.trim().split(Regex("\\s+"))
            if (tokens.none { it in slides }) continue
            val m = DURATION_RE.find(part) ?: continue
            val n = m.groupValues[1].toDoubleOrNull() ?: continue
            val sec = if (m.groupValues[2].lowercase() == "ms") n / 1000 else n
            if (sec > 0) return sec.coerceIn(ThemeMotion.MIN_SEC.toDouble(), ThemeMotion.MAX_SEC.toDouble())
        }
        return null
    }

    /** `0.7`, `70%` -> 0.7. */
    private fun fraction(v: String): Double? {
        val lv = v.trim()
        return if (lv.endsWith("%")) lv.dropLast(1).trim().toDoubleOrNull()?.div(100) else lv.toDoubleOrNull()
    }

    /** Hero artwork tokens from a CSS `filter`: `none` clears every effect; brightness, blur, saturate and grayscale map one each. */
    private fun heroFilter(v: String): List<Pair<String, String>> {
        if (v.trim() == "none") return listOf("heroOverlay" to fmt(0.0), "heroBlur" to fmt(0.0), "heroSaturation" to fmt(1.0))
        val out = ArrayList<Pair<String, String>>()
        for ((fn, args) in CssValues.functions(v)) {
            val a = args.firstOrNull() ?: continue
            when (fn) {
                "brightness" -> fraction(a)?.let {
                    out.add("heroOverlay" to fmt(((1 - it) / (1 - STEAM_HERO_BRIGHTNESS)).coerceIn(0.0, ThemeHero.MAX_OVERLAY.toDouble())))
                }
                "blur" -> CssValues.lengthPx(a)?.let { out.add("heroBlur" to fmt(it.coerceAtLeast(0.0))) }
                "saturate" -> fraction(a)?.let { out.add("heroSaturation" to fmt(it.coerceIn(0.0, 1.0))) }
                "grayscale" -> fraction(a)?.let { out.add("heroSaturation" to fmt((1 - it).coerceIn(0.0, 1.0))) }
            }
        }
        return out
    }

    /**
     * Bottom fade of a `mask-image`: a top-to-bottom linear gradient from opaque to clear gives the share of the height
     * it fades over. Other masks, and `none` (the app draws no fade by default), give null.
     */
    private fun heroFade(v: String): Double? {
        val g = CssValues.parseGradient(v)?.takeIf { it.radial == null && it.angleDeg == 180f && it.stops.size == it.colors.size } ?: return null
        val alpha = g.colors.map { (it ushr 24) / 255.0 }
        val lastOpaque = alpha.indexOfLast { it >= 0.99 }
        if (lastOpaque < 0 || lastOpaque == alpha.lastIndex || alpha.last() > 0.5) return null
        if (alpha.subList(0, lastOpaque).any { it < 0.99 }) return null
        return (1.0 - g.stops[lastOpaque]).coerceIn(0.0, 1.0)
    }

    private val LAYOUT_FIELD_KEYS = mapOf(
        "cardScale" to "layout.cardScale",
        "libraryLayout" to "layout.libraryLayout",
        "titleAlign" to "layout.titleAlign",
        "titleStroke" to "layout.titleStroke",
        "hideBadges" to "layout.hideBadges",
        "hideFooterHints" to "layout.hideFooterHints",
        "heroScale" to "layout.heroScale",
        "cardTilt" to "layout.cardTilt",
        "cardCornerRadius" to "layout.cardCornerRadius",
        "iconCornerRadius" to "layout.iconCornerRadius",
        "subtitleStroke" to "layout.subtitleStroke",
        "shelfWideFirst" to "layout.shelfWideFirst",
        "footerGlyphInvert" to "layout.footerGlyphInvert",
        "shelfRowPosition" to "layout.shelfRowPosition",
        "shelfTitleAbove" to "layout.shelfTitleAbove",
        "shelfHideTitle" to "layout.shelfHideTitle",
        "shelfHideSubtitle" to "layout.shelfHideSubtitle",
        "cardTiltAfter" to "layout.cardTiltAfter",
        "cardTurn" to "layout.cardTurn",
        "cardTurnAfter" to "layout.cardTurnAfter",
        "cardTurnPerspective" to "layout.cardTurnPerspective",
        "cardFocusedFlat" to "layout.cardFocusedFlat",
    ) + SIMPLE_EXTRA_KEYS.associateWith { "layout.$it" }

    private fun fieldOfKey(key: String): String = LAYOUT_FIELD_KEYS[key] ?: NESTED_FIELD_KEYS[key] ?: key

    /** A page colored by the theme with no accent gets its text color as the accent (white or black without one), not the app's purple. */
    private fun neutralAccent(s: ThemeSpec): Int {
        s.text?.let { return Rgba.fromArgb(it).opaque().toArgb() }
        val page = Rgba.fromArgb(s.background ?: s.surface ?: 0)
        return if (page.luminance > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    /** [ThemeSpec.setFields] with the layout split per value. */
    private fun fieldsOf(s: ThemeSpec): List<String> = buildList {
        addAll(s.setFields().filter { it != "layout" })
        val l = s.layout ?: return@buildList
        if (l.cardScale != null) add("layout.cardScale")
        if (l.libraryLayout != null) add("layout.libraryLayout")
        if (l.titleAlign != null) add("layout.titleAlign")
        if (l.titleStrokeDp != null || l.titleStrokeColor != null) add("layout.titleStroke")
        if (l.hideBadges != null) add("layout.hideBadges")
        if (l.hideFooterHints != null) add("layout.hideFooterHints")
        if (l.heroScale != null) add("layout.heroScale")
        if (l.cardTiltDeg != null) add("layout.cardTilt")
        if (l.cardCornerRadiusDp != null) add("layout.cardCornerRadius")
        if (l.iconCornerRadiusDp != null) add("layout.iconCornerRadius")
        if (l.subtitleStrokeDp != null || l.subtitleStrokeColor != null) add("layout.subtitleStroke")
        if (l.shelfWideFirst != null) add("layout.shelfWideFirst")
        if (l.footerGlyphInvert != null) add("layout.footerGlyphInvert")
        if (l.shelfRowPosition != null) add("layout.shelfRowPosition")
        if (l.shelfTitleAbove != null) add("layout.shelfTitleAbove")
        if (l.shelfHideTitle != null) add("layout.shelfHideTitle")
        if (l.shelfHideSubtitle != null) add("layout.shelfHideSubtitle")
        if (l.cardTiltAfterDeg != null) add("layout.cardTiltAfter")
        if (l.cardTurnDeg != null) add("layout.cardTurn")
        if (l.cardTurnAfterDeg != null) add("layout.cardTurnAfter")
        if (l.cardTurnPerspectiveDp != null) add("layout.cardTurnPerspective")
        if (l.cardFocusedFlat != null) add("layout.cardFocusedFlat")
        val simple = mapOf(
            "shelfRowImage" to l.shelfRowImage, "cardRestBrightness" to l.cardRestBrightness, "cardRestSaturation" to l.cardRestSaturation,
            "cardFocusedScale" to l.cardFocusedScale, "shelfFitAll" to l.shelfFitAll, "shelfHeaderText" to l.shelfHeaderText,
            "gridColumns" to l.gridColumns, "gridTileAspect" to l.gridTileAspect, "gridTitleBelow" to l.gridTitleBelow,
            "uninstalledSaturation" to l.uninstalledSaturation, "uninstalledOpacity" to l.uninstalledOpacity, "uninstalledIcon" to l.uninstalledIcon,
            "hideTabCounts" to l.hideTabCounts, "heroImage" to l.heroImage, "heroImageTile" to l.heroImageTile, "heroImageScale" to l.heroImageScale,
            "heroHeightFraction" to l.heroHeightFraction, "hideTopBarSearch" to l.hideTopBarSearch,
            "cardFocusPop" to l.cardFocusPop, "cardFocusPopWide" to l.cardFocusPopWide,
        )
        for ((k, v) in simple) if (v != null) add("layout.$k")
    }

    private fun color(v: String?): Int? = v?.let { CssValues.parseColor(it) ?: CssValues.findColors(it).firstOrNull() }?.toArgb()

    /** Lengths in dp of a value such as `2px #000 1px`, colors skipped. */
    private fun lengths(v: String): List<Float> =
        CssValues.withoutColors(v).trim().split(Regex("\\s+")).mapNotNull { CssValues.lengthDp(it) }

    /** `<width> <color>` -> width and color. */
    private fun line(v: String?): Pair<Float, Int?>? {
        v ?: return null
        val w = lengths(v).firstOrNull() ?: return null
        return w to color(v)
    }

    private fun size(v: String?): Float? = v?.let { CssValues.blurDp(it) ?: CssValues.lengthDp(it) }?.takeIf { it >= 0 }

    private fun fill(v: String?): ThemeFill? = v?.let(CssValues::parseFill)

    private fun bar(t: Map<String, String>, prefix: String): ThemeBar? {
        val border = line(t[prefix + "Border"])
        val glow = line(t[prefix + "Glow"])
        return ThemeBar(
            color = color(t[prefix + "Color"]),
            fill = fill(t[prefix + "Fill"])?.takeIf { it.layers.isNotEmpty() },
            borderColor = border?.second,
            borderDp = border?.first,
            glowColor = glow?.second,
            glowDp = glow?.first,
            blurDp = size(t[prefix + "Blur"]),
            textColor = color(t[prefix + "Text"]),
            heightScale = t[prefix + "Height"]?.let(::number)?.takeIf { it > 0 }?.coerceIn(0.25f, 2f),
        ).takeIf { !it.isEmpty }
    }

    private fun toSpec(t: Map<String, String>, fontFile: String?, hideBadges: Boolean?, hideFooter: Boolean?): ThemeSpec {
        val stroke = t["titleStroke"]?.let(::lengths)
        val subStroke = t["subtitleStroke"]?.let(::lengths)
        val layout = ThemeLayout(
            cardScale = t["cardScale"]?.toFloatOrNull(),
            libraryLayout = t["libraryLayout"],
            titleAlign = t["titleAlign"],
            titleStrokeDp = stroke?.getOrNull(0),
            titleStrokeColor = color(t["titleStroke"]),
            hideBadges = hideBadges,
            hideFooterHints = hideFooter,
            heroScale = t["heroScale"]?.toFloatOrNull(),
            cardTiltDeg = t["cardTilt"]?.toFloatOrNull(),
            cardCornerRadiusDp = t["cardCornerRadius"]?.toFloatOrNull(),
            iconCornerRadiusDp = size(t["iconCornerRadius"]),
            titleStrokeBlurDp = stroke?.getOrNull(1),
            subtitleStrokeDp = subStroke?.getOrNull(0),
            subtitleStrokeColor = color(t["subtitleStroke"]),
            subtitleStrokeBlurDp = subStroke?.getOrNull(1),
            shelfWideFirst = t["shelfWideFirst"]?.let(::flag),
            footerGlyphInvert = t["footerGlyphInvert"]?.let(::flag),
            shelfRowPosition = t["shelfRowPosition"]?.takeIf { it in SHELF_ROW_POSITIONS },
            shelfTitleAbove = t["shelfTitleAbove"]?.let(::flag),
            shelfHideTitle = t["shelfHideTitle"]?.let(::flag),
            shelfHideSubtitle = t["shelfHideSubtitle"]?.let(::flag),
            cardTiltAfterDeg = t["cardTiltAfter"]?.toFloatOrNull(),
            cardTurnDeg = t["cardTurn"]?.toFloatOrNull(),
            cardTurnAfterDeg = t["cardTurnAfter"]?.toFloatOrNull(),
            cardTurnPerspectiveDp = t["cardTurnPerspective"]?.toFloatOrNull(),
            cardFocusedFlat = t["cardFocusedFlat"]?.let(::flag),
            shelfRowImage = t["shelfRowImage"],
            cardRestBrightness = t["cardRestBrightness"]?.toFloatOrNull(),
            cardRestSaturation = t["cardRestSaturation"]?.toFloatOrNull(),
            cardFocusedScale = t["cardFocusedScale"]?.toFloatOrNull(),
            shelfFitAll = t["shelfFitAll"]?.let(::flag),
            shelfHeaderText = t["shelfHeaderText"],
            gridColumns = t["gridColumns"]?.toIntOrNull(),
            gridTileAspect = t["gridTileAspect"]?.toFloatOrNull(),
            gridTitleBelow = t["gridTitleBelow"]?.let(::flag),
            uninstalledSaturation = t["uninstalledSaturation"]?.toFloatOrNull(),
            uninstalledOpacity = t["uninstalledOpacity"]?.toFloatOrNull(),
            uninstalledIcon = t["uninstalledIcon"],
            hideTabCounts = t["hideTabCounts"]?.let(::flag),
            heroImage = t["heroImage"],
            heroImageTile = t["heroImageTile"]?.let(::flag),
            heroImageScale = t["heroImageScale"]?.toFloatOrNull(),
            heroHeightFraction = t["heroHeightFraction"]?.toFloatOrNull(),
            hideTopBarSearch = t["hideTopBarSearch"]?.let(::flag),
            cardFocusPop = t["cardFocusPop"]?.toFloatOrNull(),
            cardFocusPopWide = t["cardFocusPopWide"]?.toFloatOrNull(),
        )
        val textGlow = line(t["textGlow"])
        val textRoles = ThemeTextRoles(
            heading = color(t["heading"]),
            subtitle = color(t["subtitle"]),
            glowColor = textGlow?.second,
            glowDp = textGlow?.first,
        )
        val underline = line(t["buttonUnderline"])
        val controls = ThemeControls(
            toggleOn = fill(t["toggleOn"]),
            toggleOff = color(t["toggleOff"]),
            thumbOn = color(t["thumbOn"]),
            thumbOff = color(t["thumbOff"]),
            sliderTrack = color(t["sliderTrack"]),
            sliderThumb = color(t["sliderThumb"]),
            buttonFill = fill(t["buttonFill"]),
            buttonFocusFill = fill(t["buttonFocusFill"]),
            onButtonFocus = color(t["onButtonFocus"]),
            buttonUnderlineColor = underline?.second,
            buttonUnderlineDp = underline?.first,
            tabFill = color(t["tabFill"]),
            onTab = color(t["onTab"]),
        )
        val focusUnderline = fill(t["focusUnderline"])?.let { f ->
            f.layers.firstOrNull() ?: f.color?.let { ThemeGradient(90f, listOf(it, it)) }
        }
        val focusStyle = ThemeFocus(
            ringDp = size(t["focusRing"]),
            ringColor = color(t["focusRingColor"]),
            fill = fill(t["focusFill"]),
            onFill = color(t["focusText"]),
            underline = focusUnderline,
            underlineDp = size(t["focusUnderlineH"]),
            blink = t["focusBlink"]?.let(::flag),
        )
        val menuBorder = line(t["menuBorder"])
        val menuStyle = ThemeMenu(
            cornerRadiusDp = size(t["menuRadius"]),
            fill = fill(t["menuFill"])?.takeIf { it.layers.isNotEmpty() },
            opacity = t["menuOpacity"]?.let(::number)?.coerceIn(0f, 1f),
            blurDp = size(t["menuBlur"]),
            borderColor = menuBorder?.second,
            borderDp = menuBorder?.first,
            glowDp = size(t["menuGlow"]),
            focusFill = color(t["menuFocus"]),
            onFocus = color(t["onMenuFocus"]),
        )
        fun scale(k: String) = t[k]?.let(::number)?.takeIf { it > 0f }?.coerceIn(ThemeTypography.MIN_SCALE, ThemeTypography.MAX_SCALE)
        val typography = ThemeTypography(
            scale = scale("fontScale"),
            titleScale = scale("titleScale"),
            footerScale = scale("footerScale"),
            headingWeight = t["headingWeight"]?.let(::cssWeight),
            selectedWeight = t["selectedWeight"]?.let(::cssWeight),
            letterSpacingEm = t["letterSpacing"]?.let { v -> (number(v) ?: letterSpacingEm(v, BODY_FONT_PX)?.toFloat()) }
                ?.coerceIn(-0.1f, ThemeTypography.MAX_LETTER_SPACING_EM),
            headingUppercase = t["headingCase"]?.let(::uppercase),
            tabUppercase = t["tabCase"]?.let(::uppercase),
            footerUppercase = t["footerCase"]?.let(::uppercase),
            allUppercase = t["textCase"]?.let(::uppercase),
        )
        fun unit(k: String) = t[k]?.let(::number)?.coerceIn(0f, 1f)
        val hero = ThemeHero(
            opacity = unit("heroOpacity"),
            blurDp = size(t["heroBlur"]),
            saturation = unit("heroSaturation"),
            overlay = t["heroOverlay"]?.let(::number)?.coerceIn(0f, ThemeHero.MAX_OVERLAY),
            fadeBottom = unit("heroFade"),
        )
        fun seconds(k: String) = t[k]?.let(::duration)
        val motion = ThemeMotion(
            fillSlideSec = seconds("fillSlide"),
            panelSlideSec = seconds("panelSlide"),
            underlineSlideSec = seconds("underlineSlide"),
        )
        return ThemeSpec(
            background = color(t["background"]),
            surface = color(t["surface"]),
            surfaceVariant = color(t["surfaceVariant"]),
            primary = color(t["primary"]),
            onPrimary = color(t["onPrimary"]),
            text = color(t["text"]),
            textMuted = color(t["textMuted"]),
            outline = color(t["outline"]),
            focus = color(t["focus"]),
            bar = color(t["bar"]),
            cornerRadiusDp = t["cornerRadius"]?.toFloatOrNull(),
            fontFile = fontFile,
            backgroundImage = t["backgroundImage"],
            backgroundGradient = t["backgroundGradient"]?.let(CssValues::parseGradient),
            panelOpacity = t["panelOpacity"]?.toFloatOrNull()?.coerceIn(0f, 1f),
            blurDp = t["blur"]?.toFloatOrNull(),
            card = color(t["card"]),
            button = color(t["button"]),
            onButton = color(t["onButton"]),
            toggle = color(t["toggle"]),
            menu = color(t["menu"]),
            onMenu = color(t["onMenu"]),
            accentGradient = t["accentGradient"]?.let(CssValues::parseGradient),
            focusGlowDp = t["focusGlow"]?.toFloatOrNull(),
            layout = layout.takeIf { !it.isEmpty },
            header = bar(t, "header"),
            footer = bar(t, "footer"),
            textRoles = textRoles.takeIf { !it.isEmpty },
            controls = controls.takeIf { !it.isEmpty },
            focusStyle = focusStyle.takeIf { !it.isEmpty },
            menuStyle = menuStyle.takeIf { !it.isEmpty },
            panelFill = fill(t["panelFill"])?.takeIf { it.layers.isNotEmpty() },
            typography = typography.takeIf { !it.isEmpty },
            hero = hero.takeIf { !it.isEmpty },
            motion = motion.takeIf { !it.isEmpty },
        )
    }

    /** `uppercase` -> true, `none` (or another case) -> false. */
    private fun uppercase(v: String): Boolean? = when (v.trim().trim('"', '\'').lowercase()) {
        "uppercase" -> true
        "none", "lowercase", "capitalize", "normal" -> false
        else -> null
    }

    /** `12s`, `500ms` or a bare number of seconds, within [ThemeMotion.MIN_SEC]..[ThemeMotion.MAX_SEC]; 0 = still. */
    private fun duration(v: String): Float? {
        val lv = v.trim().lowercase()
        val sec = when {
            lv.endsWith("ms") -> lv.removeSuffix("ms").trim().toFloatOrNull()?.div(1000f)
            lv.endsWith("s") -> lv.removeSuffix("s").trim().toFloatOrNull()
            else -> lv.toFloatOrNull()
        } ?: return null
        if (sec <= 0f) return 0f
        return sec.coerceIn(ThemeMotion.MIN_SEC, ThemeMotion.MAX_SEC)
    }

    private fun number(v: String): Float? =
        if (v.endsWith("%")) v.dropLast(1).trim().toFloatOrNull()?.div(100f) else v.toFloatOrNull()

    private fun flag(v: String): Boolean? = when (v.trim().trim('"', '\'').lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }

    private fun layoutSpec(l: ThemeLayout) = ThemeSpec(layout = l)

    /** An angle in degrees, or a bare number taken as degrees. */
    private fun degrees(v: String): Float? =
        CssValues.quantity(v)?.takeIf { it.dim == CssValues.Dim.ANGLE || it.dim == CssValues.Dim.NUMBER }?.value?.toFloat()

    /** Tokens set by `--gn-*` root variables, and the rules that set them. */
    private fun gnSpec(gvars: Map<String, Triple<String, Boolean, Rule>>, sources: List<SourceSheet>): Pair<ThemeSpec, Set<Int>> {
        val lookup: (String) -> String? = { gvars[it]?.first }
        val rids = HashSet<Int>()
        var spec = ThemeSpec.EMPTY
        for ((name, key) in GN_VARIABLES) {
            val (raw, _, rule) = gvars[name] ?: continue
            val v = CssValues.resolveVars(raw, lookup).trim()
            val word = v.trim('"', '\'').trim().lowercase()
            val resolveUrl = sources[rule.sheet].resolveUrl
            val one: ThemeSpec? = when (key) {
                "cornerRadius" -> CssValues.lengthDp(v)?.let { ThemeSpec(cornerRadiusDp = it) }
                "blur" -> (CssValues.blurDp(v) ?: CssValues.lengthDp(v))?.let { ThemeSpec(blurDp = it) }
                "panelOpacity" -> number(v)?.let { ThemeSpec(panelOpacity = it.coerceIn(0f, 1f)) }
                "font" -> (CssValues.firstUrl(v) ?: v.trim('"', '\''))
                    .let(sources[rule.sheet].resolveFont)
                    ?.let { ThemeSpec(fontFile = it) }
                "backgroundImage" -> (CssValues.firstUrl(v) ?: v.trim('"', '\'')).let(resolveUrl)?.let { ThemeSpec(backgroundImage = it) }
                "backgroundGradient" -> CssValues.parseGradient(v)?.let { ThemeSpec(backgroundGradient = it) }
                "accentGradient" -> CssValues.parseGradient(v)?.let { ThemeSpec(accentGradient = it) }
                "focusGlow" -> (CssValues.blurDp(v) ?: CssValues.lengthDp(v))?.takeIf { it >= 0 }?.let { ThemeSpec(focusGlowDp = it) }
                "cardScale" -> number(v)?.takeIf { it > 0 }?.let { layoutSpec(ThemeLayout(cardScale = it.coerceIn(0.5f, 1.5f))) }
                "libraryLayout" -> word.takeIf { it in setOf("grid", "list", "carousel", "row") }?.let { layoutSpec(ThemeLayout(libraryLayout = it)) }
                "titleAlign" -> when (word) {
                    "center", "centre" -> "center"
                    "start", "left" -> "start"
                    else -> null
                }?.let { layoutSpec(ThemeLayout(titleAlign = it)) }
                "titleStroke", "subtitleStroke" -> {
                    val ls = lengths(v)
                    val width = ls.firstOrNull()
                    val c = CssValues.findColors(v).firstOrNull()?.toArgb()
                    val blur = ls.getOrNull(1)
                    val title = key == "titleStroke"
                    when {
                        word == "none" -> layoutSpec(if (title) ThemeLayout(titleStrokeDp = 0f) else ThemeLayout(subtitleStrokeDp = 0f))
                        width != null && width >= 0 -> layoutSpec(
                            if (title) {
                                ThemeLayout(titleStrokeDp = width, titleStrokeColor = c, titleStrokeBlurDp = blur)
                            } else {
                                ThemeLayout(subtitleStrokeDp = width, subtitleStrokeColor = c, subtitleStrokeBlurDp = blur)
                            },
                        )
                        else -> null
                    }
                }
                "iconCornerRadius" -> size(v)?.let { layoutSpec(ThemeLayout(iconCornerRadiusDp = it)) }
                in NESTED_FIELD_KEYS -> toSpec(mapOf(key to v), null, null, null).takeIf { !it.isEmpty }
                "hideBadges" -> flag(v)?.let { layoutSpec(ThemeLayout(hideBadges = it)) }
                "hideFooterHints" -> flag(v)?.let { layoutSpec(ThemeLayout(hideFooterHints = it)) }
                "shelfWideFirst" -> flag(v)?.let { layoutSpec(ThemeLayout(shelfWideFirst = it)) }
                "footerGlyphInvert" -> flag(v)?.let { layoutSpec(ThemeLayout(footerGlyphInvert = it)) }
                "shelfRowPosition" -> word.takeIf { it in SHELF_ROW_POSITIONS }?.let { layoutSpec(ThemeLayout(shelfRowPosition = it)) }
                "shelfTitleAbove" -> flag(v)?.let { layoutSpec(ThemeLayout(shelfTitleAbove = it)) }
                "shelfHideTitle" -> flag(v)?.let { layoutSpec(ThemeLayout(shelfHideTitle = it)) }
                "shelfHideSubtitle" -> flag(v)?.let { layoutSpec(ThemeLayout(shelfHideSubtitle = it)) }
                "heroScale" -> number(v)?.takeIf { it > 0 }?.let { layoutSpec(ThemeLayout(heroScale = it.coerceIn(0.5f, 2f))) }
                "cardTilt" -> degrees(v)?.let { layoutSpec(ThemeLayout(cardTiltDeg = it.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG))) }
                "cardTiltAfter" -> degrees(v)?.let { layoutSpec(ThemeLayout(cardTiltAfterDeg = it.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG))) }
                "cardTurn" -> degrees(v)?.let { layoutSpec(ThemeLayout(cardTurnDeg = it.coerceIn(-MAX_TURN_DEG, MAX_TURN_DEG))) }
                "cardTurnAfter" -> degrees(v)?.let { layoutSpec(ThemeLayout(cardTurnAfterDeg = it.coerceIn(-MAX_TURN_DEG, MAX_TURN_DEG))) }
                "cardTurnPerspective" -> CssValues.lengthDp(v)?.takeIf { it > 0 }?.let { layoutSpec(ThemeLayout(cardTurnPerspectiveDp = it)) }
                "cardFocusedFlat" -> flag(v)?.let { layoutSpec(ThemeLayout(cardFocusedFlat = it)) }
                "cardCornerRadius" -> CssValues.lengthDp(v)?.takeIf { it >= 0 }?.let { layoutSpec(ThemeLayout(cardCornerRadiusDp = it)) }
                else -> color(v)?.let { colorSpec(key, it) }
            }
            if (one != null) {
                spec = spec.overlay(one)
                rids.add(rule.id)
            }
        }
        return spec to rids
    }

    private fun colorSpec(key: String, argb: Int): ThemeSpec = when (key) {
        "background" -> ThemeSpec(background = argb)
        "surface" -> ThemeSpec(surface = argb)
        "surfaceVariant" -> ThemeSpec(surfaceVariant = argb)
        "primary" -> ThemeSpec(primary = argb)
        "onPrimary" -> ThemeSpec(onPrimary = argb)
        "text" -> ThemeSpec(text = argb)
        "textMuted" -> ThemeSpec(textMuted = argb)
        "outline" -> ThemeSpec(outline = argb)
        "focus" -> ThemeSpec(focus = argb)
        "bar" -> ThemeSpec(bar = argb)
        "card" -> ThemeSpec(card = argb)
        "button" -> ThemeSpec(button = argb)
        "onButton" -> ThemeSpec(onButton = argb)
        "toggle" -> ThemeSpec(toggle = argb)
        "menu" -> ThemeSpec(menu = argb)
        "onMenu" -> ThemeSpec(onMenu = argb)
        else -> ThemeSpec.EMPTY
    }
}
