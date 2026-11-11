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
    )

    private val COLOR_KEYS = listOf("background", "surface", "surfaceVariant", "primary", "onPrimary", "text", "textMuted", "outline", "focus", "bar")

    private class Rule(val rule: CssRule, val sheet: Int, val id: Int)

    private class SubjectInfo(
        val names: List<Pair<String, SteamClassMap.How>>,
        val base: RoleMatch?,
        val focus: Boolean,
        val pseudoEl: Boolean,
        val weakFocus: Boolean,
        val onState: Boolean,
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
        for (comp in comps.dropLast(1)) {
            val (acl, _) = compoundClasses(comp)
            if (acl.any { SteamClassRoles.roleOf(cm.readable(it).first)?.role == SteamRole.FOCUS }) focusAncestor = true
            if (SteamClassRoles.STATE_PSEUDO.any { comp.contains(it) }) focusAncestor = true
        }
        val noNot = NOT_RE.replace(subj, "")
        val pseudoFocus = SteamClassRoles.STATE_PSEUDO.any { noNot.contains(it) }
        val b = base.maxByOrNull { it.priority } ?: focusCls.maxByOrNull { it.priority }
        val onState = comps.any { comp -> compoundClasses(comp).first.any { cm.readable(it).first.endsWith("_On") } }
        val pe = subj.contains("::before") || subj.contains("::after") || subj.contains(":before") || subj.contains(":after")
        return SubjectInfo(
            names = names,
            base = b,
            focus = focusCls.isNotEmpty() || pseudoFocus || focusAncestor,
            pseudoEl = pe,
            weakFocus = base.isEmpty() && focusCls.isNotEmpty() && names.size > roles.size,
            onState = onState,
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
                for ((sel, si) in roleInfos) {
                    val base = si.base!!
                    val role = base.role
                    val prio = base.priority
                    val focusState = si.focus
                    val wImp = (if (d.important) 0.5 else 0.0) + prio * 0.01
                    val fw = if (si.weakFocus) 0.3 else 3.0
                    fun add(key: String, value: String, weight: Double) = cands.add(key, value, weight, r.id, sel, prop, d.important)
                    when {
                        prop in BG_PROPS -> {
                            val (bg, col0) = bgParts(rv)
                            val col = col0
                            when (role) {
                                SteamRole.PAGE_BACKGROUND -> if (!focusState) {
                                    if (bg is Bg.Gradient) add("backgroundGradient", rv, 3 + wImp)
                                    if (bg is Bg.Image) bg.url?.let { u -> sources[r.sheet].resolveUrl(u)?.let { add("backgroundImage", it, 3 + wImp) } }
                                    if (col != null && col.a > 0) add("background", col.toHex(), 3 + wImp)
                                }
                                SteamRole.PANEL -> if (!focusState && col != null) {
                                    add("panelOpacity", "%.2f".format(java.util.Locale.ROOT, col.a), (if (prio >= 5) 2.0 else 0.4) + wImp)
                                    if (col.a > 0) add("surface", col.opaque().toHex(), 3 + wImp)
                                }
                                SteamRole.HEADER, SteamRole.FOOTER -> if (!focusState && col != null) add("bar", col.toHex(), 2 + wImp)
                                SteamRole.CARD -> if (!focusState && bg is Bg.None && col != null) {
                                    add("surfaceVariant", "transparent", 3 + wImp)
                                } else if (col != null && col.a > 0) {
                                    if (!focusState) add("surfaceVariant", col.toHex(), 3 + wImp) else add("focus", col.toHex(), 2.5 + wImp)
                                }
                                SteamRole.BUTTON -> if (col != null && col.a > 0) {
                                    when {
                                        !focusState && col.saturation >= 0.35 -> add("primary", col.toHex(), 1 + wImp)
                                        !focusState -> add("surfaceVariant", col.toHex(), 1 + wImp)
                                        else -> add("focus", col.toHex(), 1.5 + wImp)
                                    }
                                }
                                SteamRole.BUTTON_PRIMARY -> if (col != null && col.a > 0) add("primary", col.toHex(), 3 + wImp)
                                SteamRole.FOCUS -> if (col != null && col.a > 0) add("focus", col.toHex(), fw + wImp)
                                SteamRole.ACCENT -> {
                                    // the ON fill of toggle rails and slider tracks is their ::before or the _On state
                                    val bareTrack = TOGGLE_TRACK_RE.containsMatchIn(base.readable) && !si.pseudoEl && !si.onState
                                    if (!bareTrack && col != null && col.a > 0) add("primary", col.toHex(), 2 + wImp)
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
                                role == SteamRole.BUTTON_PRIMARY || role == SteamRole.ON_PRIMARY_TEXT -> add("onPrimary", hx, 2.5 + wImp)
                                role in setOf(SteamRole.PAGE_BACKGROUND, SteamRole.PANEL, SteamRole.CARD, SteamRole.BUTTON) &&
                                    !focusState && prop == "color" -> add("text", hx, 1 + wImp)
                            }
                        }
                        prop in LINE_PROPS -> {
                            if (prop == "filter" && !rv.contains("drop-shadow")) continue
                            val c = CssValues.findColors(rv).firstOrNull { it.a > 0.05 } ?: continue
                            val hx = c.opaque().toHex()
                            when {
                                focusState || role == SteamRole.FOCUS -> add("focus", hx, fw + wImp)
                                role == SteamRole.DIVIDER ||
                                    (role in setOf(SteamRole.CARD, SteamRole.PANEL, SteamRole.FOOTER, SteamRole.HEADER) && prop.startsWith("border")) ->
                                    add("outline", hx, (if (role == SteamRole.DIVIDER) 3.0 else 2.0) + wImp)
                                role == SteamRole.BUTTON_PRIMARY && prop.startsWith("border") -> add("primary", hx, 1.0)
                            }
                        }
                        prop == "border-radius" || prop == "border-top-left-radius" -> {
                            val v = CssValues.lengthDp(rv) ?: continue
                            if (rv.contains('%') || v >= 100) continue
                            val w = when (role) {
                                SteamRole.CARD -> 3.0
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
                            rv.trim().toDoubleOrNull()?.let { add("panelOpacity", "%.2f".format(java.util.Locale.ROOT, it), if (prio >= 5) 2.0 else 0.4) }
                        }
                    }
                }
            }
        }

        // keyframes that override Steam's focus animations carry the theme's focus colour
        var kfId = -1
        for (src in sources) for (kf in src.sheet.keyframes) {
            val id = kfId--
            val (mapped, how) = cm.readable(kf.name)
            val m = KEYFRAME_STEAM_RE.find(kf.name)
            val rd = if (m != null) "${m.groupValues[1]}_${m.groupValues[2]}" else mapped
            if (m == null && how == SteamClassMap.How.GLOBAL) continue
            if (!SteamClassRoles.FOCUS_ANIMATION_RE.containsMatchIn(rd) || kf.body.isBlank()) continue
            val body = CssValues.resolveVars(kf.body, globalLookup)
            val c = CssValues.findColors(body).lastOrNull { it.a > 0.3 } ?: continue
            cands.add("focus", c.opaque().toHex(), 2.0, id, "@keyframes ${kf.name}", "border-color", false)
        }

        val tokens = HashMap<String, String>()
        val used = HashMap<String, MutableSet<Int>>()
        val scores = HashMap<String, Double>()
        for (key in COLOR_KEYS + listOf("cornerRadius", "backgroundGradient", "backgroundImage", "panelOpacity", "blur")) {
            val p = cands.pick(key) ?: continue
            tokens[key] = p.value
            scores[key] = p.score
            used[key] = p.rids.toMutableSet()
        }
        val primary = tokens["primary"]?.takeIf { it != "transparent" }?.let(CssValues::parseColor)
        val onPrimary = tokens["onPrimary"]?.let(CssValues::parseColor)
        if (primary != null && onPrimary != null) {
            val la = primary.luminance
            val lb = onPrimary.luminance
            if ((maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) < 2.0) {
                tokens["onPrimary"] = if (la > 0.4) "#000000" else "#ffffff"
                used.remove("onPrimary")
            }
        }
        if (tokens["surfaceVariant"] == "transparent") {
            val under = tokens["surface"] ?: tokens["background"]
            if (under != null) tokens["surfaceVariant"] = under else tokens.remove("surfaceVariant")
        }
        if (tokens.containsKey("backgroundImage")) tokens.remove("backgroundGradient")
        if (!tokens.containsKey("primary")) {
            satCounter.entries.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.let {
                tokens["primary"] = it.key
                used["primary"] = listOfNotNull(satRules[it.key]).toMutableSet()
            }
        }
        if (tokens["primary"] != null && tokens["onPrimary"] == null) {
            CssValues.parseColor(tokens["primary"]!!)?.let { tokens["onPrimary"] = if (it.luminance > 0.4) "#000000" else "#ffffff" }
        }
        if (!tokens.containsKey("background")) {
            bgCounter.entries.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.let {
                tokens["background"] = it.key
                used["background"] = listOfNotNull(bgRules[it.key]).toMutableSet()
            }
        }
        if (tokens.containsKey("panelOpacity") && (scores["panelOpacity"] ?: 0.0) < 2) tokens.remove("panelOpacity")

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

        val inferred = toSpec(tokens, fontFile)
        val (gn, gnRules) = gnSpec(gvars, sources)
        val spec = inferred.overlay(gn)
        val applied = HashSet<Int>(gnRules)
        val gnFields = gn.setFields().toSet()
        val keptFields = inferred.setFields().filter { it !in gnFields }.toSet()
        for ((key, rids) in used) if (key in keptFields) applied.addAll(rids)

        return TranslationResult(spec, rules.size, applied.count { it >= 0 })
    }

    private fun color(v: String?): Int? = v?.let { CssValues.parseColor(it) ?: CssValues.findColors(it).firstOrNull() }?.toArgb()

    private fun toSpec(t: Map<String, String>, fontFile: String?) = ThemeSpec(
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
    )

    /** Tokens set by `--gn-*` root variables, and the rules that set them. */
    private fun gnSpec(gvars: Map<String, Triple<String, Boolean, Rule>>, sources: List<SourceSheet>): Pair<ThemeSpec, Set<Int>> {
        val lookup: (String) -> String? = { gvars[it]?.first }
        val rids = HashSet<Int>()
        var spec = ThemeSpec.EMPTY
        for ((name, key) in GN_VARIABLES) {
            val (raw, _, rule) = gvars[name] ?: continue
            val v = CssValues.resolveVars(raw, lookup).trim()
            val resolveUrl = sources[rule.sheet].resolveUrl
            val one: ThemeSpec? = when (key) {
                "cornerRadius" -> CssValues.lengthDp(v)?.let { ThemeSpec(cornerRadiusDp = it) }
                "blur" -> (CssValues.blurDp(v) ?: CssValues.lengthDp(v))?.let { ThemeSpec(blurDp = it) }
                "panelOpacity" -> (if (v.endsWith("%")) v.dropLast(1).toFloatOrNull()?.div(100f) else v.toFloatOrNull())
                    ?.let { ThemeSpec(panelOpacity = it.coerceIn(0f, 1f)) }
                "font" -> (CssValues.firstUrl(v) ?: v.trim('"', '\''))
                    .let(resolveUrl)
                    ?.takeIf { it.substringAfterLast('.').lowercase() in FONT_EXT }
                    ?.let { ThemeSpec(fontFile = it) }
                "backgroundImage" -> (CssValues.firstUrl(v) ?: v.trim('"', '\'')).let(resolveUrl)?.let { ThemeSpec(backgroundImage = it) }
                "backgroundGradient" -> CssValues.parseGradient(v)?.let { ThemeSpec(backgroundGradient = it) }
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
        else -> ThemeSpec.EMPTY
    }
}
