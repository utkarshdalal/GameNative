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
    private val FONT_EXT = setOf("ttf", "otf")

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
    )

    private val COLOR_KEYS = listOf(
        "background", "surface", "surfaceVariant", "primary", "onPrimary", "text", "textMuted", "outline", "focus", "bar",
        "card", "button", "onButton", "toggle", "menu", "onMenu",
    )
    private val LAYOUT_KEYS = listOf(
        "cardScale", "libraryLayout", "titleAlign", "titleStroke", "heroScale", "cardTilt", "cardCornerRadius",
    )
    private val COMPONENT_KEYS = setOf("card", "button", "toggle", "menu", "onMenu", "onButton")
    private val MENU_MODULES = listOf("gamepadcontextmenu_", "basiccontextmenu_")
    private val BARE_ELEMENT_RE = Regex("^[a-zA-Z][a-zA-Z0-9-]*(:[a-z-]+(\\([^()]*\\))?)*$")
    private val KEYFRAME_SHADOW_RE = Regex("(box-shadow|filter)\\s*:\\s*([^;}]+)", RegexOption.IGNORE_CASE)
    private val HOVER_STATE_RE = Regex("(ShowAsHovered|Hovered|PortraitHover)$")

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
    )

    private class Cand(val value: String, var weight: Double, val rid: Int, val selector: String, val property: String, val important: Boolean)

    private class Picked(val value: String, val score: Double, val rids: Set<Int>)

    private class Cands {
        val c = HashMap<String, MutableList<Cand>>()

        fun add(key: String, value: String, weight: Double, rid: Int, selector: String, property: String, important: Boolean) {
            val list = c.getOrPut(key) { ArrayList() }
            for (x in list) {
                if (x.rid == rid && x.value == value) {
                    if (weight > x.weight) x.weight = weight
                    return
                }
            }
            // the same selector + property later in the cascade replaces the earlier value unless that one was !important
            list.removeAll { x ->
                x.selector == selector && x.property == property && x.rid != rid && x.value != value && !(x.important && !important)
            }
            list.add(Cand(value, weight, rid, selector, property, important))
        }

        fun pick(key: String): Picked? {
            val list = c[key] ?: return null
            if (list.isEmpty()) return null
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
                .filter { it.role == LayoutRole.TILE || it.role == LayoutRole.HERO }
                .maxByOrNull { it.priority }
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

    /** Which accent component an [SteamRole.ACCENT] class is; two kinds agreeing on a colour make it the app accent. */
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
            val global = r.rule.selectors.any { it.trim() in GLOBAL_SELECTORS }

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
                    val lr = si.layout ?: continue
                    when (layoutDecl(lr, si, ruleFocus, sel, prop, rv, d.important, r, cands, badges, resolved.map { it.first.property to it.second })) {
                        true -> footerHide.add(r.id)
                        false -> footerShown = true
                        null -> {}
                    }
                }
                for ((sel, si) in roleInfos) {
                    val base = si.base!!
                    val role = base.role
                    val prio = base.priority
                    val focusState = si.focus
                    val wImp = (if (d.important) 0.5 else 0.0) + prio * 0.01
                    val fw = if (si.weakFocus) 0.3 else 3.0
                    fun add(key: String, value: String, weight: Double) = cands.add(key, value, weight, r.id, sel, prop, d.important)
                    if (si.inMenu) {
                        // menus, dropdowns and dialogs only feed their own tokens
                        if (focusState) continue
                        if (prop in BG_PROPS) {
                            val col = bgParts(rv).second ?: continue
                            if (role == SteamRole.MENU && col.a >= 0.5) add("menu", col.opaque().toHex(), 3 + wImp)
                            if (role == SteamRole.MENU_ITEM && col.a >= 0.5 && !base.readable.contains("Separator") && !base.readable.contains("Header")) {
                                add("menu", col.opaque().toHex(), 2 + wImp)
                            }
                        } else if (prop == "color" || prop == "fill") {
                            val hx = CssValues.findColors(rv).firstOrNull()?.takeIf { it.a > 0.3 }?.opaque()?.toHex() ?: continue
                            val w = when (role) {
                                SteamRole.MENU, SteamRole.MENU_ITEM, SteamRole.TEXT -> 3.0
                                else -> 1.0
                            }
                            add("onMenu", hx, w + wImp)
                        }
                        continue
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
                                }
                                SteamRole.HEADER, SteamRole.FOOTER -> if (!focusState && col != null) add("bar", col.toHex(), 2 + wImp)
                                SteamRole.CARD, SteamRole.CONTROL -> {
                                    val key = if (role == SteamRole.CARD) "card" else "surfaceVariant"
                                    if (!focusState && bg is Bg.None && col != null) {
                                        add(key, "transparent", 3 + wImp)
                                    } else if (col != null && col.a > 0) {
                                        if (!focusState) add(key, col.toHex(), 3 + wImp) else add("focus", col.toHex(), 2.5 + wImp)
                                    }
                                }
                                SteamRole.BUTTON -> if (col != null && col.a > 0) {
                                    if (!focusState) add("button", col.opaque().toHex(), 3 + wImp) else add("focus", col.toHex(), 1.5 + wImp)
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
                                    if (!bareTrack && col != null && col.a > 0) {
                                        add("toggle", col.opaque().toHex(), 2 + wImp)
                                        if (bg is Bg.Gradient) add("accentGradient", rv, 2 + wImp)
                                        accentKinds.getOrPut(col.opaque().toHex()) { HashSet() }.add(accentKind(base.readable))
                                    }
                                }
                                SteamRole.DIVIDER -> if (col != null && col.a > 0) add("outline", col.toHex(), 3 + wImp)
                                else -> {}
                            }
                        }
                        prop == "color" || prop == "fill" -> {
                            val hx = CssValues.findColors(rv).firstOrNull()?.toHex() ?: continue
                            when {
                                role == SteamRole.TEXT && !focusState -> add("text", hx, (if (prio > 1) 3.0 else 1.0) + wImp)
                                role == SteamRole.TEXT_MUTED && !focusState -> add("textMuted", hx, (if (prio > 1) 3.0 else 1.0) + wImp)
                                (role == SteamRole.BUTTON_PRIMARY || role == SteamRole.ON_PRIMARY_TEXT) && !focusState -> add("onPrimary", hx, 2.5 + wImp)
                                role == SteamRole.BUTTON && !focusState && prop == "color" -> add("onButton", hx, 3 + wImp)
                                role in setOf(SteamRole.PAGE_BACKGROUND, SteamRole.PANEL, SteamRole.CARD, SteamRole.CONTROL) &&
                                    !focusState && prop == "color" -> add("text", hx, 1 + wImp)
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
                                }
                                role == SteamRole.DIVIDER ||
                                    (role in setOf(SteamRole.CARD, SteamRole.CONTROL, SteamRole.PANEL, SteamRole.FOOTER, SteamRole.HEADER) && prop.startsWith("border")) ->
                                    add("outline", hx, (if (role == SteamRole.DIVIDER) 3.0 else 2.0) + wImp)
                                role == SteamRole.BUTTON_PRIMARY && prop.startsWith("border") -> add("primary", hx, 1.0)
                            }
                        }
                        prop == "border-radius" || prop == "border-top-left-radius" -> {
                            if (si.layout?.role == LayoutRole.TILE) continue
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
                            if (role in setOf(SteamRole.PANEL, SteamRole.HEADER, SteamRole.FOOTER, SteamRole.CARD, SteamRole.PAGE_BACKGROUND)) {
                                add("blur", b.toString(), if (role == SteamRole.PANEL) 3.0 else 2.0)
                            }
                        }
                        prop == "opacity" && role == SteamRole.PANEL && !focusState -> {
                            rv.trim().toDoubleOrNull()?.let { add("panelOpacity", fmt(it), if (prio >= 5) 2.0 else 0.4) }
                        }
                    }
                }
            }
        }

        // keyframes that override Steam's focus animations carry the theme's focus colour and glow
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
        }

        val tokens = HashMap<String, String>()
        val used = HashMap<String, MutableSet<Int>>()
        val scores = HashMap<String, Double>()
        for (key in COLOR_KEYS + LAYOUT_KEYS + listOf("cornerRadius", "backgroundGradient", "backgroundImage", "panelOpacity", "blur", "accentGradient", "focusGlow")) {
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
        for ((bgKey, fgKey) in listOf("primary" to "onPrimary", "button" to "onButton", "menu" to "onMenu")) {
            val bgc = tokens[bgKey]?.takeIf { it != "transparent" }?.let(CssValues::parseColor) ?: continue
            val fg = tokens[fgKey]?.let(CssValues::parseColor)
            val la = bgc.luminance
            if (fg == null || (maxOf(la, fg.luminance) + 0.05) / (minOf(la, fg.luminance) + 0.05) < 2.0) {
                tokens[fgKey] = if (la > 0.4) "#000000" else "#ffffff"
                used.remove(fgKey)
            }
        }
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
        val fontFiles = HashMap<String, String>()
        for (src in sources) for (ff in src.sheet.fontFaces) {
            val fam = ff.lastOrNull { it.property == "font-family" }?.value?.trim()?.trim('\'', '"') ?: continue
            val url = ff.lastOrNull { it.property == "src" }?.value?.let(CssValues::firstUrl) ?: continue
            val path = src.resolveUrl(url) ?: continue
            if (path.substringAfterLast('.').lowercase() !in FONT_EXT) continue
            fontFiles.putIfAbsent(fam.lowercase(), path)
        }
        fontScore.entries.filter { fontFiles.containsKey(it.key.lowercase()) }.maxByOrNull { it.value }?.let {
            fontFile = fontFiles[it.key.lowercase()]
            used["font"] = fontRules[it.key] ?: HashSet()
        }

        val inferred = toSpec(tokens, fontFile, hideBadges, hideFooter)
        val (gn, gnRules) = gnSpec(gvars, sources)
        val spec = inferred.overlay(gn)
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
            LayoutRole.TITLE -> {
                val centred = when (prop) {
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
                if (centred) add("titleAlign", "center")
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
                    "text-shadow" -> CssValues.textShadowStroke(v)
                    else -> null
                }
                stroke?.let { (sw, c) -> add("titleStroke", "${fmt(sw.toDouble())} ${c.opaque().toHex()}") }
            }
            LayoutRole.HERO -> if (prop == "height") {
                CssValues.lengthPx(v)?.takeIf { it > 0 }?.let { add("heroScale", fmt((it / DEFAULT_HERO_PX).coerceIn(0.5, 2.0))) }
            }
            else -> {}
        }
        return null
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
    )

    private fun fieldOfKey(key: String): String = LAYOUT_FIELD_KEYS[key] ?: key

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
    }

    private fun color(v: String?): Int? = v?.let { CssValues.parseColor(it) ?: CssValues.findColors(it).firstOrNull() }?.toArgb()

    private fun toSpec(t: Map<String, String>, fontFile: String?, hideBadges: Boolean?, hideFooter: Boolean?): ThemeSpec {
        val stroke = t["titleStroke"]?.split(' ')
        val layout = ThemeLayout(
            cardScale = t["cardScale"]?.toFloatOrNull(),
            libraryLayout = t["libraryLayout"],
            titleAlign = t["titleAlign"],
            titleStrokeDp = stroke?.getOrNull(0)?.toFloatOrNull(),
            titleStrokeColor = color(stroke?.getOrNull(1)),
            hideBadges = hideBadges,
            hideFooterHints = hideFooter,
            heroScale = t["heroScale"]?.toFloatOrNull(),
            cardTiltDeg = t["cardTilt"]?.toFloatOrNull(),
            cardCornerRadiusDp = t["cardCornerRadius"]?.toFloatOrNull(),
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
        )
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
                    .let(resolveUrl)
                    ?.takeIf { it.substringAfterLast('.').lowercase() in FONT_EXT }
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
                "titleStroke" -> {
                    val width = v.split(Regex("\\s+")).firstNotNullOfOrNull { CssValues.lengthDp(it) }
                    val c = CssValues.findColors(v).firstOrNull()
                    when {
                        word == "none" -> layoutSpec(ThemeLayout(titleStrokeDp = 0f))
                        width != null && width >= 0 -> layoutSpec(ThemeLayout(titleStrokeDp = width, titleStrokeColor = c?.toArgb()))
                        else -> null
                    }
                }
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
