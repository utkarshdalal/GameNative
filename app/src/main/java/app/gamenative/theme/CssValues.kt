package app.gamenative.theme

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colour with 0..255 channels and 0..1 alpha. */
data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double) {
    fun opaque(): Rgba = copy(a = 1.0)

    fun toArgb(): Int {
        val ai = (a.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (ai shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    fun toHex(): String {
        val base = "#%02x%02x%02x".format(Locale.ROOT, ch(r), ch(g), ch(b))
        return if (a >= 0.999) base else base + "%02x".format(Locale.ROOT, (a.coerceIn(0.0, 1.0) * 255).roundToInt())
    }

    /** HLS saturation; 0 for near-black and near-white. */
    val saturation: Double
        get() {
            val rr = r / 255; val gg = g / 255; val bb = b / 255
            val mx = max(rr, max(gg, bb)); val mn = min(rr, min(gg, bb))
            val l = (mx + mn) / 2
            if (mx == mn) return 0.0
            val s = if (l <= 0.5) (mx - mn) / (mx + mn) else (mx - mn) / (2 - mx - mn)
            return if (l > 0.08 && l < 0.92) s else 0.0
        }

    val luminance: Double get() = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255

    companion object {
        private fun ch(v: Double) = v.roundToInt().coerceIn(0, 255)

        fun fromArgb(argb: Int) = Rgba(
            ((argb shr 16) and 0xff).toDouble(),
            ((argb shr 8) and 0xff).toDouble(),
            (argb and 0xff).toDouble(),
            ((argb ushr 24) and 0xff) / 255.0,
        )
    }
}

object CssValues {

    private val NAMED: Map<String, String> = (
        "aliceblue:f0f8ff,antiquewhite:faebd7,aqua:00ffff,aquamarine:7fffd4,azure:f0ffff,beige:f5f5dc,bisque:ffe4c4," +
            "black:000000,blanchedalmond:ffebcd,blue:0000ff,blueviolet:8a2be2,brown:a52a2a,burlywood:deb887," +
            "cadetblue:5f9ea0,chartreuse:7fff00,chocolate:d2691e,coral:ff7f50,cornflowerblue:6495ed,cornsilk:fff8dc," +
            "crimson:dc143c,cyan:00ffff,darkblue:00008b,darkcyan:008b8b,darkgoldenrod:b8860b,darkgray:a9a9a9," +
            "darkgreen:006400,darkgrey:a9a9a9,darkkhaki:bdb76b,darkmagenta:8b008b,darkolivegreen:556b2f," +
            "darkorange:ff8c00,darkorchid:9932cc,darkred:8b0000,darksalmon:e9967a,darkseagreen:8fbc8f," +
            "darkslateblue:483d8b,darkslategray:2f4f4f,darkslategrey:2f4f4f,darkturquoise:00ced1,darkviolet:9400d3," +
            "deeppink:ff1493,deepskyblue:00bfff,dimgray:696969,dimgrey:696969,dodgerblue:1e90ff,firebrick:b22222," +
            "floralwhite:fffaf0,forestgreen:228b22,fuchsia:ff00ff,gainsboro:dcdcdc,ghostwhite:f8f8ff,gold:ffd700," +
            "goldenrod:daa520,gray:808080,grey:808080,green:008000,greenyellow:adff2f,honeydew:f0fff0,hotpink:ff69b4," +
            "indianred:cd5c5c,indigo:4b0082,ivory:fffff0,khaki:f0e68c,lavender:e6e6fa,lavenderblush:fff0f5," +
            "lawngreen:7cfc00,lemonchiffon:fffacd,lightblue:add8e6,lightcoral:f08080,lightcyan:e0ffff," +
            "lightgoldenrodyellow:fafad2,lightgray:d3d3d3,lightgreen:90ee90,lightgrey:d3d3d3,lightpink:ffb6c1," +
            "lightsalmon:ffa07a,lightseagreen:20b2aa,lightskyblue:87cefa,lightslategray:778899,lightslategrey:778899," +
            "lightsteelblue:b0c4de,lightyellow:ffffe0,lime:00ff00,limegreen:32cd32,linen:faf0e6,magenta:ff00ff," +
            "maroon:800000,mediumaquamarine:66cdaa,mediumblue:0000cd,mediumorchid:ba55d3,mediumpurple:9370db," +
            "mediumseagreen:3cb371,mediumslateblue:7b68ee,mediumspringgreen:00fa9a,mediumturquoise:48d1cc," +
            "mediumvioletred:c71585,midnightblue:191970,mintcream:f5fffa,mistyrose:ffe4e1,moccasin:ffe4b5," +
            "navajowhite:ffdead,navy:000080,oldlace:fdf5e6,olive:808000,olivedrab:6b8e23,orange:ffa500," +
            "orangered:ff4500,orchid:da70d6,palegoldenrod:eee8aa,palegreen:98fb98,paleturquoise:afeeee," +
            "palevioletred:db7093,papayawhip:ffefd5,peachpuff:ffdab9,peru:cd853f,pink:ffc0cb,plum:dda0dd," +
            "powderblue:b0e0e6,purple:800080,rebeccapurple:663399,red:ff0000,rosybrown:bc8f8f,royalblue:4169e1," +
            "saddlebrown:8b4513,salmon:fa8072,sandybrown:f4a460,seagreen:2e8b57,seashell:fff5ee,sienna:a0522d," +
            "silver:c0c0c0,skyblue:87ceeb,slateblue:6a5acd,slategray:708090,slategrey:708090,snow:fffafa," +
            "springgreen:00ff7f,steelblue:4682b4,tan:d2b48c,teal:008080,thistle:d8bfd8,tomato:ff6347," +
            "turquoise:40e0d0,violet:ee82ee,wheat:f5deb3,white:ffffff,whitesmoke:f5f5f5,yellow:ffff00," +
            "yellowgreen:9acd32"
        ).split(',').associate { it.substringBefore(':') to it.substringAfter(':') }

    private val COLOR_RE = Regex(
        "#[0-9a-fA-F]{3,8}\\b|\\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color)\\([^()]*\\)|\\b(?:" +
            NAMED.keys.sortedByDescending { it.length }.joinToString("|") + "|transparent)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val FN_RE = Regex("^(rgba?|hsla?|color-mix)\\((.*)\\)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val MIX_PART_RE = Regex("^(.*?)\\s+([\\d.]+)%$", RegexOption.DOT_MATCHES_ALL)
    private val URL_RE = Regex("url\\(\\s*(?:'([^']*)'|\"([^\"]*)\"|([^)\\s]*))\\s*\\)", RegexOption.IGNORE_CASE)
    private val LENGTH_RE = Regex("^\\s*(-?[\\d.]+)(px|em|rem|vw|vh|%)?")
    private val BLUR_RE = Regex("blur\\(\\s*([\\d.]+)px", RegexOption.IGNORE_CASE)

    /** Split on [sep] outside (), [] and strings. */
    fun splitTop(s: String, sep: Char): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                cur.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    cur.append(s[i + 1]); i += 2; continue
                }
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c; cur.append(c)
            } else if (c == '(' || c == '[') {
                depth++; cur.append(c)
            } else if (c == ')' || c == ']') {
                depth--; cur.append(c)
            } else if (c == sep && depth <= 0) {
                parts.add(cur.toString()); cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        parts.add(cur.toString())
        return parts
    }

    private fun num(tok: String, scale: Double = 1.0): Double {
        val t = tok.trim()
        return if (t.endsWith("%")) t.dropLast(1).toDouble() / 100.0 * scale else t.toDouble()
    }

    fun parseColor(input: String): Rgba? {
        val s = input.trim()
        val sl = s.lowercase()
        if (sl == "transparent") return Rgba(0.0, 0.0, 0.0, 0.0)
        NAMED[sl]?.let { return hex6(it, 1.0) }
        if (sl.startsWith("#")) {
            val h = sl.substring(1)
            if (!h.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val v = when (h.length) {
                3, 4 -> h.map { "$it$it".toInt(16) }
                6, 8 -> h.chunked(2).map { it.toInt(16) }
                else -> return null
            }
            return Rgba(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), if (v.size == 4) v[3] / 255.0 else 1.0)
        }
        val m = FN_RE.find(sl) ?: return null
        val fn = m.groupValues[1]
        val args = m.groupValues[2]
        if (fn == "color-mix") return parseColorMix(args)
        val parts = args.replace('/', ',').trim().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        return try {
            if (fn.startsWith("rgb")) {
                Rgba(num(parts[0], 255.0), num(parts[1], 255.0), num(parts[2], 255.0), if (parts.size > 3) num(parts[3]) else 1.0)
            } else {
                val hDeg = parts[0].removeSuffix("deg").toDouble()
                val sat = if (parts[1].endsWith("%")) num(parts[1]) else parts[1].toDouble() / 100
                val lig = if (parts[2].endsWith("%")) num(parts[2]) else parts[2].toDouble() / 100
                val a = if (parts.size > 3) num(parts[3]) else 1.0
                val (r, g, b) = hlsToRgb(((hDeg % 360) + 360) % 360 / 360.0, lig, sat)
                Rgba(r * 255, g * 255, b * 255, a)
            }
        } catch (e: NumberFormatException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    private fun hex6(h: String, a: Double) =
        Rgba(h.substring(0, 2).toInt(16).toDouble(), h.substring(2, 4).toInt(16).toDouble(), h.substring(4, 6).toInt(16).toDouble(), a)

    private fun hlsToRgb(h: Double, l: Double, s: Double): Triple<Double, Double, Double> {
        if (s == 0.0) return Triple(l, l, l)
        val m2 = if (l <= 0.5) l * (1 + s) else l + s - l * s
        val m1 = 2 * l - m2
        fun v(hue0: Double): Double {
            val hue = ((hue0 % 1.0) + 1.0) % 1.0
            return when {
                hue < 1.0 / 6 -> m1 + (m2 - m1) * hue * 6
                hue < 0.5 -> m2
                hue < 2.0 / 3 -> m1 + (m2 - m1) * (2.0 / 3 - hue) * 6
                else -> m1
            }
        }
        return Triple(v(h + 1.0 / 3), v(h), v(h - 1.0 / 3))
    }

    private fun parseColorMix(args: String): Rgba? {
        val parts = splitTop(args, ',')
        if (parts.size != 3) return null
        val cols = parts.drop(1).map { raw ->
            var p = raw.trim()
            var pct: Double? = null
            MIX_PART_RE.find(p)?.let { p = it.groupValues[1]; pct = it.groupValues[2].toDouble() / 100 }
            (parseColor(p) ?: return null) to pct
        }
        val (c1, q1) = cols[0]
        val (c2, q2) = cols[1]
        var p1 = q1
        var p2 = q2
        if (p1 == null && p2 == null) { p1 = 0.5; p2 = 0.5 } else if (p1 == null) p1 = 1 - p2!! else if (p2 == null) p2 = 1 - p1
        val t = p1!! + p2!!
        if (t <= 0) return null
        val a = p1 / t
        val b = p2 / t
        return Rgba(c1.r * a + c2.r * b, c1.g * a + c2.g * b, c1.b * a + c2.b * b, c1.a * a + c2.a * b)
    }

    /** Every colour literal in a value, in order (color-mix() handled separately, as in the reference prototype). */
    fun findColors(value: String): List<Rgba> {
        val out = ArrayList<Rgba>()
        for (m in COLOR_RE.findAll(value)) parseColor(m.value)?.let(out::add)
        var k = value.indexOf("color-mix(", ignoreCase = true)
        while (k >= 0) {
            var depth = 0
            var j = k + "color-mix".length
            while (j < value.length) {
                if (value[j] == '(') depth++ else if (value[j] == ')') { depth--; if (depth == 0) break }
                j++
            }
            parseColor(value.substring(k, minOf(j + 1, value.length)))?.let(out::add)
            k = value.indexOf("color-mix(", j, ignoreCase = true)
        }
        return out
    }

    /** Substitute var(--x, fallback) recursively; unresolved refs without a fallback stay as var(--x). */
    fun resolveVars(value: String, lookup: (String) -> String?, missing: MutableSet<String>? = null, depth: Int = 0): String {
        if (!value.contains("var(", ignoreCase = true) || depth > 12) return value
        val out = StringBuilder()
        val low = value.lowercase()
        var i = 0
        while (true) {
            val k = low.indexOf("var(", i)
            if (k < 0) { out.append(value, i, value.length); break }
            out.append(value, i, k)
            var dp = 0
            var j = k + 3
            while (j < value.length) {
                if (value[j] == '(') dp++ else if (value[j] == ')') { dp--; if (dp == 0) break }
                j++
            }
            val inner = value.substring(k + 4, minOf(j, value.length))
            val parts = splitTop(inner, ',')
            val name = parts[0].trim()
            val fb = if (parts.size > 1) parts.drop(1).joinToString(",").trim() else null
            val found = lookup(name)
            val v = when {
                found != null -> found
                fb != null -> fb
                else -> { missing?.add(name); "var($name)" }
            }
            out.append(if (v.contains("var(", ignoreCase = true) && v != "var($name)") resolveVars(v, lookup, missing, depth + 1) else v)
            i = j + 1
            if (i > value.length) break
        }
        return out.toString()
    }

    /** Length in dp (px = dp, em/rem = 16); null for %, viewport units or junk. */
    fun lengthDp(v: String): Float? {
        val m = LENGTH_RE.find(v) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2].ifEmpty { "px" }) {
            "px" -> n.toFloat()
            "em", "rem" -> (n * 16).toFloat()
            else -> null
        }
    }

    fun blurDp(v: String): Float? = BLUR_RE.find(v)?.groupValues?.get(1)?.toFloatOrNull()

    /** Reference viewport for vh/vw: the Steam Deck screen. */
    const val VIEWPORT_W = 1280.0
    const val VIEWPORT_H = 800.0

    enum class Dim { NUMBER, LENGTH, ANGLE }

    /** A number with its dimension; lengths in px, angles in degrees. */
    data class Quantity(val value: Double, val dim: Dim)

    private val QTY_RE = Regex("^(\\d*\\.?\\d+(?:e[+-]?\\d+)?)([a-z%]*)", RegexOption.IGNORE_CASE)

    /** Evaluates a length, angle or number, `calc()` included; vh/vw use [VIEWPORT_W] x [VIEWPORT_H]. Null for %, var() or junk. */
    fun quantity(input: String): Quantity? {
        val s = input.trim()
        if (s.isEmpty() || s.contains("var(", ignoreCase = true)) return null
        var i = 0
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun unit(n: Double, u: String): Quantity? = when (u.lowercase()) {
            "" -> Quantity(n, Dim.NUMBER)
            "px" -> Quantity(n, Dim.LENGTH)
            "vh" -> Quantity(n * VIEWPORT_H / 100, Dim.LENGTH)
            "vw" -> Quantity(n * VIEWPORT_W / 100, Dim.LENGTH)
            "em", "rem" -> Quantity(n * 16, Dim.LENGTH)
            "deg" -> Quantity(n, Dim.ANGLE)
            "rad" -> Quantity(n * 180 / Math.PI, Dim.ANGLE)
            "turn" -> Quantity(n * 360, Dim.ANGLE)
            "grad" -> Quantity(n * 0.9, Dim.ANGLE)
            else -> null
        }
        lateinit var expr: () -> Quantity?
        fun factor(): Quantity? {
            skip()
            if (i >= s.length) return null
            if (s.startsWith("calc(", i, ignoreCase = true)) i += 4
            when (s[i]) {
                '(' -> {
                    i++
                    val v = expr() ?: return null
                    skip()
                    if (i >= s.length || s[i] != ')') return null
                    i++
                    return v
                }
                '-' -> { i++; return factor()?.let { it.copy(value = -it.value) } }
                '+' -> { i++; return factor() }
            }
            val m = QTY_RE.find(s.substring(i)) ?: return null
            i += m.value.length
            return unit(m.groupValues[1].toDoubleOrNull() ?: return null, m.groupValues[2])
        }
        fun term(): Quantity? {
            var a = factor() ?: return null
            while (true) {
                skip()
                if (i >= s.length || (s[i] != '*' && s[i] != '/')) return a
                val op = s[i++]
                val b = factor() ?: return null
                a = if (op == '*') {
                    when {
                        a.dim == Dim.NUMBER -> Quantity(a.value * b.value, b.dim)
                        b.dim == Dim.NUMBER -> Quantity(a.value * b.value, a.dim)
                        else -> return null
                    }
                } else {
                    if (b.dim != Dim.NUMBER || b.value == 0.0) return null
                    Quantity(a.value / b.value, a.dim)
                }
            }
        }
        expr = {
            var a = term()
            while (a != null) {
                skip()
                if (i >= s.length || (s[i] != '+' && s[i] != '-')) break
                val op = s[i++]
                val b = term()
                a = if (b == null || (b.dim != a.dim && !(b.value == 0.0 || a.value == 0.0))) {
                    null
                } else {
                    val dim = if (a.value == 0.0 && a.dim == Dim.NUMBER) b.dim else a.dim
                    Quantity(if (op == '+') a.value + b.value else a.value - b.value, dim)
                }
            }
            a
        }
        val r = expr() ?: return null
        skip()
        return if (i == s.length) r else null
    }

    /** Length in px through [quantity]; a bare 0 counts as a length. */
    fun lengthPx(v: String): Double? = quantity(v)?.takeIf { it.dim == Dim.LENGTH || (it.dim == Dim.NUMBER && it.value == 0.0) }?.value

    /** `name(args)` calls at the top level of a value such as a transform, in order. */
    fun functions(v: String): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        val rx = Regex("([a-zA-Z-][a-zA-Z0-9-]*)\\(")
        var i = 0
        while (true) {
            val m = rx.find(v, i) ?: break
            var depth = 0
            var j = m.range.last
            while (j < v.length) {
                if (v[j] == '(') depth++ else if (v[j] == ')') { depth--; if (depth == 0) break }
                j++
            }
            val args = splitTop(v.substring(m.range.last + 1, minOf(j, v.length)), ',').map { it.trim() }
            out.add(m.groupValues[1].lowercase() to args)
            i = j + 1
            if (i >= v.length) break
        }
        return out
    }

    /** Uniform scale factor of a `transform` value, or null when it has none. */
    fun transformScale(v: String): Double? {
        for ((fn, args) in functions(v)) {
            if (fn != "scale") continue
            val x = quantity(args.getOrNull(0) ?: return null)?.takeIf { it.dim == Dim.NUMBER }?.value ?: return null
            val y = args.getOrNull(1)?.let { a -> quantity(a)?.takeIf { it.dim == Dim.NUMBER }?.value ?: return null } ?: x
            return if (kotlin.math.abs(x - y) < 1e-6) x else null
        }
        return null
    }

    /** Tilt in degrees of a `transform`: rotate/skew, or a rotateX/Y under perspective(). */
    fun transformTilt(v: String): Double? {
        val fns = functions(v)
        val perspective = fns.any { it.first == "perspective" }
        for ((fn, args) in fns) {
            val ok = fn in setOf("rotate", "rotatez", "skew", "skewx") || (perspective && fn in setOf("rotatey", "rotatex"))
            if (!ok) continue
            val a = quantity(args.firstOrNull() ?: continue) ?: return null
            if (a.dim == Dim.ANGLE) return a.value
            if (a.dim == Dim.NUMBER && a.value == 0.0) return 0.0
            return null
        }
        return null
    }

    /** One shadow layer: offsets, blur and spread in px plus its colour. */
    data class Shadow(val x: Double, val y: Double, val blur: Double, val spread: Double, val color: Rgba?, val inset: Boolean)

    /** Layers of a box-shadow / text-shadow value, or the drop-shadow() calls of a filter. */
    fun shadows(value: String, property: String): List<Shadow> {
        val layers = if (property == "filter") {
            functions(value).filter { it.first == "drop-shadow" }.map { it.second.joinToString(",") }
        } else {
            splitTop(value, ',')
        }
        val out = ArrayList<Shadow>()
        for (layer in layers) {
            val l = layer.trim()
            if (l.isEmpty() || l.equals("none", ignoreCase = true)) continue
            val color = findColors(l).firstOrNull()
            val rest = COLOR_RE.replace(l, " ")
            val toks = splitTop(rest.trim(), ' ').map { it.trim() }.filter { it.isNotEmpty() }
            val inset = toks.any { it.equals("inset", ignoreCase = true) }
            val nums = toks.filterNot { it.equals("inset", ignoreCase = true) }.map { lengthPx(it) ?: return@map null }
            if (nums.size < 2 || nums.any { it == null }) continue
            out.add(Shadow(nums[0]!!, nums[1]!!, nums.getOrNull(2) ?: 0.0, nums.getOrNull(3) ?: 0.0, color, inset))
        }
        return out
    }

    /** Blur radius of a glow (an outer shadow centred on its element), or null. */
    fun glowDp(value: String, property: String): Float? {
        var best: Double? = null
        for (s in shadows(value, property)) {
            if (s.inset || s.blur <= 0 || (s.color != null && s.color.a <= 0.05)) continue
            if (kotlin.math.abs(s.x) > s.blur / 2 || kotlin.math.abs(s.y) > s.blur / 2) continue
            if (best == null || s.blur > best) best = s.blur
        }
        return best?.toFloat()
    }

    /** Width and colour of a text-shadow that outlines text (offsets on every side, little blur), or null. */
    fun textShadowStroke(value: String): Pair<Float, Rgba>? {
        val layers = shadows(value, "text-shadow")
        if (layers.size < 3) return null
        val c = layers[0].color ?: return null
        if (c.a < 0.5) return null
        for (s in layers) {
            if (s.blur > 2 || kotlin.math.abs(s.x) > 3 || kotlin.math.abs(s.y) > 3) return null
            if (s.color == null || s.color.opaque().toHex() != c.opaque().toHex()) return null
        }
        val surrounds = layers.any { it.x < 0 } && layers.any { it.x > 0 } && layers.any { it.y < 0 } && layers.any { it.y > 0 }
        if (!surrounds) return null
        val w = layers.maxOf { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.y)) }
        return if (w > 0) w.toFloat() to c else null
    }

    fun firstUrl(v: String): String? {
        val m = URL_RE.find(v) ?: return null
        return (m.groups[1] ?: m.groups[2] ?: m.groups[3])?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** First linear/radial gradient in a value. */
    fun parseGradient(v: String): ThemeGradient? {
        val low = v.lowercase()
        val k = Regex("(?:-webkit-)?(?:repeating-)?(linear|radial|conic)-gradient\\(|-webkit-gradient\\(").find(low) ?: return null
        val open = k.range.last
        var depth = 0
        var j = open
        while (j < v.length) {
            if (v[j] == '(') depth++ else if (v[j] == ')') { depth--; if (depth == 0) break }
            j++
        }
        val args = splitTop(v.substring(open + 1, minOf(j, v.length)), ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (args.isEmpty()) return null
        var angle = 180f
        var stopsArgs = args
        val first = args[0].lowercase()
        if (k.groupValues.getOrNull(1) == "linear") {
            val deg = angleOf(first)
            if (deg != null) {
                angle = deg; stopsArgs = args.drop(1)
            } else if (first.startsWith("to ")) {
                angle = when (first.removePrefix("to ").split(Regex("\\s+")).sorted().joinToString(" ")) {
                    "top" -> 0f; "right" -> 90f; "bottom" -> 180f; "left" -> 270f
                    "right top" -> 45f; "bottom right" -> 135f; "bottom left" -> 225f; "left top" -> 315f
                    else -> 180f
                }
                stopsArgs = args.drop(1)
            }
        } else if (findColors(args[0]).isEmpty()) {
            stopsArgs = args.drop(1)
        }
        val colors = ArrayList<Int>()
        val stops = ArrayList<Float?>()
        for (a in stopsArgs) {
            val c = findColors(a).firstOrNull() ?: continue
            colors.add(c.toArgb())
            stops.add(Regex("(-?[\\d.]+)%").find(a)?.groupValues?.get(1)?.toFloatOrNull()?.let { (it / 100f).coerceIn(0f, 1f) })
        }
        if (colors.isEmpty()) return null
        if (colors.size == 1) { colors.add(colors[0]); stops.add(null) }
        val allStops = stops.all { it != null }
        return ThemeGradient(angle, colors, if (allStops) stops.map { it!! } else emptyList())
    }

    private fun angleOf(s: String): Float? {
        val m = Regex("^(-?[\\d.]+)(deg|turn|rad|grad)$").find(s) ?: return null
        val n = m.groupValues[1].toFloatOrNull() ?: return null
        val d = when (m.groupValues[2]) {
            "turn" -> n * 360f
            "rad" -> (n * 180.0 / Math.PI).toFloat()
            "grad" -> n * 0.9f
            else -> n
        }
        return ((d % 360f) + 360f) % 360f
    }
}
