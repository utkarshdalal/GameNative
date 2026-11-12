package app.gamenative.theme

import java.math.BigDecimal
import java.util.Locale

/** Writes a [ThemeSpec] as a `:root { --gn-*: ... }` block that [ThemeTranslator] reads back to the same spec. */
object ThemeCssWriter {

    /** [urlOf] maps [ThemeSpec.fontFile] / [ThemeSpec.backgroundImage] to the url written for it; null leaves the value out. */
    fun write(spec: ThemeSpec, urlOf: (String) -> String? = { null }): String {
        val out = LinkedHashMap<String, String>()
        fun color(name: String, argb: Int?) {
            if (argb != null) out[name] = Rgba.fromArgb(argb).toHex()
        }
        fun length(name: String, v: Float?, unit: String = "px") {
            num(v)?.let { out[name] = it + unit }
        }
        fun url(name: String, path: String?) {
            val u = path?.let(urlOf) ?: return
            out[name] = "url(\"${u.replace("\\", "/").replace("\"", "%22")}\")"
        }
        color("--gn-background", spec.background)
        color("--gn-surface", spec.surface)
        color("--gn-surface-variant", spec.surfaceVariant)
        color("--gn-primary", spec.primary)
        color("--gn-on-primary", spec.onPrimary)
        color("--gn-text", spec.text)
        color("--gn-text-muted", spec.textMuted)
        color("--gn-outline", spec.outline)
        color("--gn-focus", spec.focus)
        color("--gn-bar", spec.bar)
        length("--gn-corner-radius", spec.cornerRadiusDp)
        url("--gn-font", spec.fontFile)
        url("--gn-background-image", spec.backgroundImage)
        spec.backgroundGradient?.let(::gradient)?.let { out["--gn-background-gradient"] = it }
        num(spec.panelOpacity)?.let { out["--gn-panel-opacity"] = it }
        length("--gn-blur", spec.blurDp)
        color("--gn-card", spec.card)
        color("--gn-button", spec.button)
        color("--gn-on-button", spec.onButton)
        color("--gn-toggle", spec.toggle)
        color("--gn-menu", spec.menu)
        color("--gn-on-menu", spec.onMenu)
        spec.accentGradient?.let(::gradient)?.let { out["--gn-accent-gradient"] = it }
        length("--gn-focus-glow", spec.focusGlowDp)
        spec.layout?.let { l ->
            num(l.cardScale)?.let { out["--gn-card-scale"] = it }
            l.libraryLayout?.let { out["--gn-library-layout"] = it }
            l.titleAlign?.let { out["--gn-title-align"] = it }
            num(l.titleStrokeDp)?.let { w ->
                out["--gn-title-stroke"] = "${w}px" + (l.titleStrokeColor?.let { " " + Rgba.fromArgb(it).toHex() } ?: "")
            }
            l.hideBadges?.let { out["--gn-hide-badges"] = if (it) "1" else "0" }
            l.hideFooterHints?.let { out["--gn-hide-footer-hints"] = if (it) "1" else "0" }
            num(l.heroScale)?.let { out["--gn-hero-scale"] = it }
            length("--gn-card-tilt", l.cardTiltDeg, "deg")
            length("--gn-card-corner-radius", l.cardCornerRadiusDp)
        }
        return buildString {
            append(":root {\n")
            for ((k, v) in out) append("  ").append(k).append(": ").append(v).append(";\n")
            append("}\n")
        }
    }

    private fun gradient(g: ThemeGradient): String? {
        if (g.colors.isEmpty()) return null
        val angle = num(g.angleDeg) ?: return null
        val withStops = g.stops.size == g.colors.size
        val parts = g.colors.mapIndexed { i, c ->
            val hex = Rgba.fromArgb(c).toHex()
            if (withStops) "$hex ${percent(g.stops[i])}%" else hex
        }
        return "linear-gradient(${angle}deg, ${parts.joinToString(", ")})"
    }

    /** Plain decimal that parses back to [v]; null for NaN and infinities. */
    fun num(v: Float?): String? {
        if (v == null || v.isNaN() || v.isInfinite()) return null
        return BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
    }

    /** Percentage text p with p / 100 == [stop] as the translator computes it. */
    private fun percent(stop: Float): String {
        val target = stop.coerceIn(0f, 1f)
        for (digits in 0..9) {
            val s = "%.${digits}f".format(Locale.ROOT, target.toDouble() * 100.0)
            if ((s.toFloat() / 100f).coerceIn(0f, 1f) == target) return BigDecimal(s).stripTrailingZeros().toPlainString()
        }
        return BigDecimal(target.toDouble() * 100.0).toPlainString()
    }
}
