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
            stroke(l.titleStrokeDp, l.titleStrokeColor, l.titleStrokeBlurDp)?.let { out["--gn-title-stroke"] = it }
            l.hideBadges?.let { out["--gn-hide-badges"] = if (it) "1" else "0" }
            l.hideFooterHints?.let { out["--gn-hide-footer-hints"] = if (it) "1" else "0" }
            num(l.heroScale)?.let { out["--gn-hero-scale"] = it }
            length("--gn-card-tilt", l.cardTiltDeg, "deg")
            length("--gn-card-corner-radius", l.cardCornerRadiusDp)
            length("--gn-icon-corner-radius", l.iconCornerRadiusDp)
            stroke(l.subtitleStrokeDp, l.subtitleStrokeColor, l.subtitleStrokeBlurDp)?.let { out["--gn-subtitle-stroke"] = it }
            l.shelfWideFirst?.let { out["--gn-shelf-wide-first"] = if (it) "1" else "0" }
            l.footerGlyphInvert?.let { out["--gn-footer-glyph-invert"] = if (it) "1" else "0" }
            l.shelfRowPosition?.let { out["--gn-shelf-row-position"] = it }
            l.shelfTitleAbove?.let { out["--gn-shelf-title-above"] = if (it) "1" else "0" }
            l.shelfHideTitle?.let { out["--gn-shelf-hide-title"] = if (it) "1" else "0" }
            l.shelfHideSubtitle?.let { out["--gn-shelf-hide-subtitle"] = if (it) "1" else "0" }
            length("--gn-card-tilt-after", l.cardTiltAfterDeg, "deg")
            length("--gn-card-turn", l.cardTurnDeg, "deg")
            length("--gn-card-turn-after", l.cardTurnAfterDeg, "deg")
            length("--gn-card-turn-perspective", l.cardTurnPerspectiveDp)
            l.cardFocusedFlat?.let { out["--gn-card-focused-flat"] = if (it) "1" else "0" }
        }
        fun line(name: String, w: Float?, argb: Int?) {
            val n = num(w) ?: return
            out[name] = n + "px" + (argb?.let { " " + Rgba.fromArgb(it).toHex() } ?: "")
        }
        fun fill(name: String, f: ThemeFill?) {
            f?.let(::fillCss)?.let { out[name] = it }
        }
        for ((prefix, b) in listOf("--gn-header" to spec.header, "--gn-footer" to spec.footer)) {
            b ?: continue
            color(prefix, b.color)
            fill("$prefix-fill", b.fill)
            line("$prefix-border", b.borderDp, b.borderColor)
            line("$prefix-glow", b.glowDp, b.glowColor)
            length("$prefix-blur", b.blurDp)
            color("$prefix-text", b.textColor)
            num(b.heightScale)?.let { out["$prefix-height"] = it }
        }
        spec.textRoles?.let { t ->
            color("--gn-heading", t.heading)
            color("--gn-subtitle", t.subtitle)
            line("--gn-text-glow", t.glowDp, t.glowColor)
        }
        spec.controls?.let { c ->
            fill("--gn-toggle-on", c.toggleOn)
            color("--gn-toggle-off", c.toggleOff)
            color("--gn-thumb-on", c.thumbOn)
            color("--gn-thumb-off", c.thumbOff)
            color("--gn-slider-track", c.sliderTrack)
            color("--gn-slider-thumb", c.sliderThumb)
            fill("--gn-button-fill", c.buttonFill)
            fill("--gn-button-focus", c.buttonFocusFill)
            color("--gn-on-button-focus", c.onButtonFocus)
            line("--gn-button-underline", c.buttonUnderlineDp, c.buttonUnderlineColor)
            color("--gn-tab", c.tabFill)
            color("--gn-on-tab", c.onTab)
        }
        spec.focusStyle?.let { f ->
            length("--gn-focus-ring", f.ringDp)
            color("--gn-focus-ring-color", f.ringColor)
            fill("--gn-focus-fill", f.fill)
            color("--gn-on-focus-fill", f.onFill)
            f.underline?.let(::gradient)?.let { out["--gn-focus-underline"] = it }
            length("--gn-focus-underline-height", f.underlineDp)
            f.blink?.let { out["--gn-focus-blink"] = if (it) "1" else "0" }
        }
        spec.menuStyle?.let { m ->
            length("--gn-menu-radius", m.cornerRadiusDp)
            fill("--gn-menu-fill", m.fill)
            num(m.opacity)?.let { out["--gn-menu-opacity"] = it }
            length("--gn-menu-blur", m.blurDp)
            line("--gn-menu-border", m.borderDp, m.borderColor)
            length("--gn-menu-glow", m.glowDp)
            color("--gn-menu-focus", m.focusFill)
            color("--gn-on-menu-focus", m.onFocus)
        }
        fill("--gn-panel-fill", spec.panelFill)
        fun case(name: String, upper: Boolean?) {
            if (upper != null) out[name] = if (upper) "uppercase" else "none"
        }
        spec.typography?.let { t ->
            num(t.scale)?.let { out["--gn-text-size"] = it }
            num(t.titleScale)?.let { out["--gn-title-size"] = it }
            num(t.footerScale)?.let { out["--gn-footer-text-size"] = it }
            t.headingWeight?.let { out["--gn-heading-weight"] = it.toString() }
            t.selectedWeight?.let { out["--gn-selected-weight"] = it.toString() }
            length("--gn-letter-spacing", t.letterSpacingEm, "em")
            case("--gn-heading-case", t.headingUppercase)
            case("--gn-tab-case", t.tabUppercase)
            case("--gn-footer-case", t.footerUppercase)
            case("--gn-text-case", t.allUppercase)
        }
        spec.hero?.let { h ->
            num(h.opacity)?.let { out["--gn-hero-opacity"] = it }
            length("--gn-hero-blur", h.blurDp)
            num(h.saturation)?.let { out["--gn-hero-saturation"] = it }
            num(h.overlay)?.let { out["--gn-hero-overlay"] = it }
            num(h.fadeBottom)?.let { out["--gn-hero-fade"] = it }
        }
        spec.motion?.let { m ->
            length("--gn-fill-slide", m.fillSlideSec, "s")
            length("--gn-panel-slide", m.panelSlideSec, "s")
            length("--gn-focus-underline-slide", m.underlineSlideSec, "s")
        }
        return buildString {
            append(":root {\n")
            for ((k, v) in out) append("  ").append(k).append(": ").append(v).append(";\n")
            append("}\n")
        }
    }

    /** `<width>px <color> [<blur>px]`, as `--gn-title-stroke` reads it. */
    private fun stroke(w: Float?, argb: Int?, blur: Float?): String? {
        val n = num(w) ?: return null
        return "${n}px" + (argb?.let { " " + Rgba.fromArgb(it).toHex() } ?: "") + (num(blur)?.let { " ${it}px" } ?: "")
    }

    /** CSS background layers, top first, then the base color. */
    private fun fillCss(f: ThemeFill): String? {
        val parts = f.layers.mapNotNull(::gradient) + listOfNotNull(f.color?.let { Rgba.fromArgb(it).toHex() })
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun gradient(g: ThemeGradient): String? {
        if (g.colors.isEmpty()) return null
        val withStops = g.stops.size == g.colors.size
        val parts = g.colors.mapIndexed { i, c ->
            val hex = Rgba.fromArgb(c).toHex()
            if (withStops) "$hex ${percent(g.stops[i])}%" else hex
        }
        g.radial?.let { r -> return "radial-gradient(${radialPrelude(r) ?: return null}, ${parts.joinToString(", ")})" }
        val angle = num(g.angleDeg) ?: return null
        return "linear-gradient(${angle}deg, ${parts.joinToString(", ")})"
    }

    private fun radialPrelude(r: ThemeRadial): String? {
        val shape = buildList {
            add(if (r.circle) "circle" else "ellipse")
            val rx = r.radiusXDp?.let { num(it) ?: return null }
            val ry = r.radiusYDp?.takeIf { !r.circle }?.let { num(it) ?: return null }
            if (rx != null) {
                add("${rx}px")
                if (ry != null) add("${ry}px")
            } else if (r.extent != ThemeRadial.FARTHEST_CORNER) {
                add(r.extent)
            }
        }
        val pos = if (r.offsetXDp == 0f && r.offsetYDp == 0f) {
            "${position(r.centerX) ?: return null}% ${position(r.centerY) ?: return null}%"
        } else {
            "${edge(r.centerX, r.offsetXDp, "left", "right") ?: return null} ${edge(r.centerY, r.offsetYDp, "top", "bottom") ?: return null}"
        }
        return "${shape.joinToString(" ")} at $pos"
    }

    /** One axis of a four-value position: an edge keyword and its offset. */
    private fun edge(fraction: Float, offset: Float, near: String, far: String): String? {
        return when {
            offset == 0f -> "$near ${position(fraction) ?: return null}%"
            fraction == 0f -> "$near ${num(offset) ?: return null}px"
            fraction == 1f -> "$far ${num(-offset) ?: return null}px"
            else -> "$near ${position(fraction) ?: return null}%"
        }
    }

    /** Percentage text p with p / 100 == [fraction] as the translator computes it; positions may lie outside 0..1. */
    private fun position(fraction: Float): String? {
        if (fraction.isNaN() || fraction.isInfinite()) return null
        for (digits in 0..9) {
            val s = "%.${digits}f".format(Locale.ROOT, fraction.toDouble() * 100.0)
            if (s.toFloat() / 100f == fraction) return BigDecimal(s).stripTrailingZeros().toPlainString()
        }
        return BigDecimal(fraction.toDouble() * 100.0).toPlainString()
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
