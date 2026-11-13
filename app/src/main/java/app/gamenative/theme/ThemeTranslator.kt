package app.gamenative.theme

data class TranslationResult(
    val spec: ThemeSpec,
    /** Style rules in the injected CSS after nesting is flattened. */
    val totalRules: Int,
    /** Rules whose values ended up in [spec]. */
    val appliedRules: Int,
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
            "buttonFill", "buttonFocusFill", "onButtonFocus", "buttonUnderline",
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
        "iconCornerRadius", "subtitleStroke",
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
    private val TAB_LABEL_RE = Regex("^(gamepadtabbedpage_Tab|quickaccessmenu_Tab)$")
    private val DURATION_RE = Regex("(?<![\\w.-])(\\d*\\.?\\d+)(ms|s)(?![\\w-])", RegexOption.IGNORE_CASE)
    private val SLIDE_KEYFRAME_RE = Regex("background-position", RegexOption.IGNORE_CASE)

    /** Steam darkens the home artwork to 50 %, the app default overlay. */
    private const val STEAM_HERO_BRIGHTNESS = 0.5
    private const val BODY_FONT_PX = 16.0

    /** Steam's game page hero height on the reference viewport: calc(80px + 20vw). */
    private const val DEFAULT_HERO_PX = 80 + 0.2 * CssValues.VIEWPORT_W
    private const val CAROUSEL_MIN_HEIGHT_PX = 0.6 * CssValues.VIEWPORT_H

    /** Smaller scale changes on tiles are focus pops, not a resize. */
    private const val MIN_SCALE_CHANGE = 0.05

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
        if (layout == null && names.isEmpty() && comps.size > 1 && BARE_ELEMENT_RE.matches(subj.replace("::before", "").replace("::after", ""))) {
            // `.tile img`, `.tile > div:first-child`: the element is part of the tile or hero above it
            val (pcl, pat) = compoundClasses(comps[comps.size - 2])
            layout = (pcl + pat).mapNotNull { SteamClassRoles.layoutRoleOf(cm.readable(it).first) }
                .filter { it.role == LayoutRole.TILE || it.role == LayoutRole.ICON || it.role == LayoutRole.HERO }
                .maxByOrNull { it.priority }
        }
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
        if (lv.contains("url(")) return Bg.Image(CssValues.firstUrl(v)) to null
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

    /** Which accent component an [SteamRole.ACCENT] class is; two kinds agreeing on a color make it the app accent. */
    private fun accentKind(readable: String): String = when {
        readable.contains("Toggle") -> "toggle"
        readable.contains("Slider") || readable.contains("Tick") -> "slider"
        readable.contains("Progress") || readable.contains("Percent") -> "progress"
        readable.contains("ActiveDot") -> "dot"
        else -> "accent"
    }

    private fun fmt(v: Double): String = "%.2f".format(java.util.Locale.ROOT, v)

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
        val accentKinds = HashMap<String, MutableSet<String>>()
        val badges = LinkedHashMap<String, BadgeState>()
        val footerHide = HashSet<Int>()
        var footerShown = false
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
                        fontRules.getOrPut(fam) { HashSet() }.add(r.id)
                    }
                }
                if (prop in TYPE_PROPS) {
                    if (global && prop == "letter-spacing") {
                        letterSpacingEm(rv, BODY_FONT_PX)?.let { cands.add("letterSpacing", fmt(it), 1.0, r.id, ":root", prop, d.important) }
                    }
                    for ((sel, si) in infos) typeDecl(sel, si, prop, rv, d.important, r, cands, cm)
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
                    val lr = si.layout?.let { if (ruleIcon && it.role == LayoutRole.TILE) it.copy(role = LayoutRole.ICON) else it } ?: continue
                    when (layoutDecl(lr, si, ruleFocus, sel, prop, rv, d.important, r, cands, badges, resolved.map { it.first.property to it.second })) {
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
                                SteamRole.CARD, SteamRole.CONTROL -> {
                                    val key = if (role == SteamRole.CARD) "card" else "surfaceVariant"
                                    if (!focusState && bg is Bg.None && col != null) {
                                        add(key, "transparent", 3 + wImp)
                                    } else if (col != null && col.a > 0) {
                                        if (!focusState) {
                                            add(key, col.toHex(), 3 + wImp)
                                        } else if (!si.pseudoEl || !underline()) {
                                            add("focus", col.toHex(), 2.5 + wImp)
                                            if (!si.pseudoEl) add("focusFill", rv, 2.5 + wImp)
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
        for (key in listOf("surfaceVariant", "card")) {
            if (tokens[key] != "transparent") continue
            val under = tokens["surface"] ?: tokens["background"]
            if (under != null) tokens[key] = under else tokens.remove(key)
        }
        if (tokens.containsKey("backgroundImage")) tokens.remove("backgroundGradient")
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
            "focusFill" to "focusText", "buttonFocusFill" to "onButtonFocus",
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
        // each face's urls, cheapest format first, so a face falls back to any of its urls that resolves to a usable font
        val fontUrls = HashMap<String, MutableList<Pair<SourceSheet, String>>>()
        for (src in sources) for (ff in src.sheet.fontFaces) {
            val fam = ff.lastOrNull { it.property == "font-family" }?.value?.trim()?.trim('\'', '"') ?: continue
            val urls = RemoteFonts.sources(ff)
            if (urls.isNotEmpty()) fontUrls.getOrPut(fam.lowercase()) { ArrayList() }.addAll(urls.map { src to it })
        }
        for ((fam, _) in fontScore.entries.filter { fontUrls.containsKey(it.key.lowercase()) }.sortedByDescending { it.value }) {
            fontFile = fontUrls.getValue(fam.lowercase()).firstNotNullOfOrNull { (src, url) -> src.resolveFont(url) } ?: continue
            used["font"] = fontRules[fam] ?: HashSet()
            break
        }

        val inferred = toSpec(tokens, fontFile, hideBadges, hideFooter)
        val (gn, gnRules) = gnSpec(gvars, sources)
        var spec = inferred.overlay(gn)
        if (spec.primary == null && spec.accentGradient == null && (inferred.background != null || inferred.surface != null)) {
            spec = spec.copy(primary = neutralAccent(spec), onPrimary = null)
        }
        val applied = HashSet<Int>(gnRules)
        val gnFields = fieldsOf(gn).toSet()
        val keptFields = fieldsOf(inferred).filter { it !in gnFields }.toSet()
        for ((key, rids) in used) if (fieldOfKey(key) in keptFields) applied.addAll(rids)

        return TranslationResult(spec, rules.size, applied.count { it >= 0 })
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
                if (scale != null && scale > 0 && kotlin.math.abs(scale - 1) >= MIN_SCALE_CHANGE) add("cardScale", fmt(scale.coerceIn(0.5, 1.5)))
                if (lr.role == LayoutRole.TILE && prop == "transform") {
                    CssValues.transformTilt(v)?.takeIf { kotlin.math.abs(it) > 1e-3 }?.let { add("cardTilt", fmt(it.coerceIn(-15.0, 15.0))) }
                }
                if (lr.role == LayoutRole.TILE && (prop == "border-radius" || prop == "border-top-left-radius")) {
                    val px = CssValues.lengthDp(v)
                    if (px != null && !v.contains('%') && px < 100) add("cardCornerRadius", px.toString())
                }
                if (lr.role == LayoutRole.HOME_ROW && prop == "height") {
                    CssValues.lengthPx(v)?.takeIf { it >= CAROUSEL_MIN_HEIGHT_PX }?.let { add("libraryLayout", "carousel") }
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
            LayoutRole.HOME_HERO -> when (prop) {
                "opacity" -> fraction(lv)?.let { add("heroOpacity", fmt(it.coerceIn(0.0, 1.0))) }
                "filter" -> for ((key, value) in heroFilter(lv)) add(key, value)
                "-webkit-mask-image", "mask-image" -> heroFade(v)?.let { add("heroFade", fmt(it)) }
            }
            else -> {}
        }
        return null
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
    )

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
                "libraryLayout" -> word.takeIf { it in setOf("grid", "list", "carousel") }?.let { layoutSpec(ThemeLayout(libraryLayout = it)) }
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
                "heroScale" -> number(v)?.takeIf { it > 0 }?.let { layoutSpec(ThemeLayout(heroScale = it.coerceIn(0.5f, 2f))) }
                "cardTilt" -> (
                    CssValues.quantity(v)?.takeIf { it.dim == CssValues.Dim.ANGLE || it.dim == CssValues.Dim.NUMBER }?.value?.toFloat()
                    )?.let { layoutSpec(ThemeLayout(cardTiltDeg = it.coerceIn(-15f, 15f))) }
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
