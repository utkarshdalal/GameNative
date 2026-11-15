package app.gamenative.theme

/**
 * Steam's home row laid out as Valve's stylesheet and the theme's resting rules put it, in Steam CSS px on the Steam Deck
 * viewport ([CssValues.VIEWPORT_W] x [CssValues.VIEWPORT_H]).
 *
 * The row's top is reported against the area between Steam's header and footer as
 * `top = areaTop + anchor * (areaHeight - rowHeight) + offsetPx`, found by laying the home out at several viewport
 * heights, so it can be placed in an area of another height. The label (game title and status) is reported against
 * the row. Anything the model cannot evaluate leaves Steam's own placement.
 */
object HomeRowGeometry {

    /** The home elements a theme can move the row or its label with. */
    enum class Part {
        /** `gamepadhome_RecentSection` */
        SECTION,

        /** `gamepadhomerecentgames_RecentGamesContainer` */
        CONTAINER,

        /** `gamepadhomerecentgames_RecentGamesInnerContainer` */
        INNER,

        /** `gamepadhomerecentgames_RecentGamesHeader` ("Recent games") */
        HEADER,

        /** `basicgamecarousel_BasicGameCarousel` */
        CAROUSEL,

        /** `basicgamecarousel_BasicGameCarouselItemMediaContainer` */
        MEDIA,

        /** `basicgamecarousel_CarouselGameLabelWrapper` */
        LABEL_WRAPPER,

        /** `basicgamecarousel_CarouselGameLabel` */
        LABEL,
    }

    /** Readable Steam class of each part. */
    val PART_CLASSES: Map<String, Part> = mapOf(
        "gamepadhome_RecentSection" to Part.SECTION,
        "gamepadhomerecentgames_RecentGamesContainer" to Part.CONTAINER,
        "gamepadhomerecentgames_RecentGamesInnerContainer" to Part.INNER,
        "gamepadhomerecentgames_RecentGamesHeader" to Part.HEADER,
        "basicgamecarousel_BasicGameCarousel" to Part.CAROUSEL,
        "basicgamecarousel_BasicGameCarouselItemMediaContainer" to Part.MEDIA,
        "basicgamecarousel_CarouselGameLabelWrapper" to Part.LABEL_WRAPPER,
        "basicgamecarousel_CarouselGameLabel" to Part.LABEL,
    )

    private val PART_BY_NAME: Map<String, Part> = PART_CLASSES.mapKeys { it.key.substringAfter('_') }

    /** The part a readable class names, in `module_Name` or CSS Loader's bare `Name` spelling. */
    fun partOf(readable: String): Part? = PART_CLASSES[readable] ?: PART_BY_NAME[readable]

    /** A resting declaration of the theme, `var()` resolved. */
    data class Decl(val value: String, val important: Boolean)

    /** `.header_Header { height: 40px }` and `.footer_BasicFooter { min-height: 40px }`, both absolutely positioned. */
    const val HEADER_PX = 40.0
    const val FOOTER_PX = 40.0

    /** `.gamepadhomerecentgames_RecentGamesContainer { padding-top: 54px }` */
    private const val CONTAINER_PADDING_TOP = 54.0

    /** The "Recent games" header row: its label's `line-height: 28px`. */
    private const val HEADER_LINE = 28.0

    /** `.basicgamecarousel_BasicGameCarousel { margin-top: 4px; padding: 12px 2.8vw 16px 2.8vw }` */
    private const val CAROUSEL_MARGIN_TOP = 4.0
    private const val CAROUSEL_PADDING_TOP = 12.0
    private const val CAROUSEL_PADDING_BOTTOM = 16.0

    /** A portrait cover on the Deck (the wide first one is 426.837 x 199.5). */
    const val COVER_W = 133.0
    const val COVER_H = 199.5

    /** `ItemMarginRight: 12px` and `CarouselHorizontalPadding: 2.8vw` of the carousel's module. */
    const val ITEM_GAP = 12.0
    const val SIDE_PADDING_VW = 2.8

    /** `LabelHeight: 52px`: an item is a cover plus the label under it. */
    private const val LABEL_HEIGHT = 52.0
    private const val ITEM_H = COVER_H + LABEL_HEIGHT

    /** The carousel's inline height: item + CarouselTopPadding + CarouselBottomPadding + 30, border-box. */
    private const val CAROUSEL_H = ITEM_H + CAROUSEL_PADDING_TOP + CAROUSEL_PADDING_BOTTOM + 30

    /** `.basicgamecarousel_CarouselGameLabelWrapper { padding-top: 14px }` */
    private const val LABEL_PADDING_TOP = 14.0

    /** `.gpfocuswithin > .basicgamecarousel_CarouselGameLabelWrapper { transform: translateY(-4px) }`: the shown label. */
    private const val LABEL_SHOWN_SHIFT = -4.0

    /** Viewport heights the row is laid out at to find its anchor. */
    private val PROBE_HEIGHTS = listOf(CssValues.VIEWPORT_H, CssValues.VIEWPORT_H + 100, CssValues.VIEWPORT_H + 200)
    private const val EPS = 1e-6
    private const val LINEAR_TOLERANCE = 0.01

    private val VH_RE = Regex("(?<![\\w.])(\\d*\\.?\\d+)vh\\b", RegexOption.IGNORE_CASE)
    private val CENTER_ORIGINS = setOf("center", "center center", "50% 50%", "50%", "50% 50% 0", "50% 50% 0px")

    /**
     * Where the row sits.
     * [anchor] 0 hangs it from the top of the area between the bars, 1 sets it on the bottom; [offsetPx] moves it from
     * there. [scale] is what the theme's transforms do to the covers' size. [titleOffsetPx]: negative = the label's box
     * ends that far above the row's top, positive = its text starts that far below the row's bottom; null = hidden or
     * not evaluable. [linear] is false when the row does not follow the area's height linearly; it is then kept where
     * it is at Steam's own area height ([anchor] 0).
     */
    data class Placement(
        val anchor: Double,
        val offsetPx: Double,
        val scale: Double,
        val titleOffsetPx: Double?,
        val titleHidden: Boolean,
        val linear: Boolean,
    ) {
        /** Top of the covers from the top of a viewport [viewportH] tall with Steam's bars. */
        fun rowTop(viewportH: Double = CssValues.VIEWPORT_H): Double =
            HEADER_PX + anchor * (viewportH - HEADER_PX - FOOTER_PX - COVER_H * scale) + offsetPx
    }

    /** Steam's own placement. */
    val STOCK: Placement = place(emptyMap())

    /** The row laid out with [decls]; parts or values the model cannot evaluate keep Steam's placement. */
    fun place(decls: Map<Part, Map<String, Decl>>): Placement {
        val stock = if (decls.isEmpty()) null else STOCK
        val rows = PROBE_HEIGHTS.map { h -> runCatching { Eval(decls, h).row() }.getOrNull() }
        val first = rows[0] ?: return stock ?: error("Steam's own layout must evaluate")
        val title = runCatching { Eval(decls, CssValues.VIEWPORT_H).title(first.scale) }.getOrNull()
        val titleOffset = title?.second
        val hidden = title?.first == true
        val area0 = CssValues.VIEWPORT_H - HEADER_PX - FOOTER_PX
        val rowH = COVER_H * first.scale
        val tops = rows.map { it?.top }
        var anchor = 0.0
        var linear = false
        if (tops.all { it != null }) {
            val dh = PROBE_HEIGHTS[1] - PROBE_HEIGHTS[0]
            val d1 = tops[1]!! - tops[0]!!
            val d2 = tops[2]!! - tops[1]!!
            val a = d1 / dh
            if (kotlin.math.abs(d2 - d1) < LINEAR_TOLERANCE && a > -EPS && a < 1 + EPS) {
                anchor = a.coerceIn(0.0, 1.0)
                linear = true
            }
        }
        val offset = first.top - HEADER_PX - anchor * (area0 - rowH)
        return Placement(anchor, offset, first.scale, titleOffset, hidden, linear)
    }

    private class Unevaluable : RuntimeException()

    private fun fail(): Nothing = throw Unevaluable()

    private class Row(val top: Double, val scale: Double)

    /** An affine map y -> a * y + b about an element's center. */
    private class Affine(val a: Double, val b: Double)

    /** One layout of the home at viewport height [vh]. */
    private class Eval(val d: Map<Part, Map<String, Decl>>, val vh: Double) {

        fun decl(p: Part, prop: String): Decl? = d[p]?.get(prop)

        fun word(p: Part, prop: String): String? = decl(p, prop)?.value?.trim()?.lowercase()

        /** A length; % of [base] (none = not evaluable); vh at this layout's viewport height. */
        fun length(w: String, base: Double?): Double {
            if (w.endsWith("%")) {
                val f = w.dropLast(1).trim().toDoubleOrNull() ?: fail()
                return (base ?: fail()) * f / 100
            }
            val s = VH_RE.replace(w) { m -> java.math.BigDecimal(m.groupValues[1].toDouble() * vh / 100).toPlainString() + "px" }
            return CssValues.lengthPx(s) ?: fail()
        }

        fun px(p: Part, prop: String, stock: Double, base: Double? = null): Double {
            val w = word(p, prop) ?: return stock
            return length(w, base)
        }

        /** A margin: `auto` is null. */
        fun margin(p: Part, prop: String, stock: Double = 0.0): Double? {
            val w = word(p, prop) ?: return stock
            return if (w == "auto") null else length(w, null)
        }

        /** A box offset (`top`, `bottom`); `auto` or unset is null. */
        fun offset(p: Part, prop: String, base: Double? = null): Double? {
            val w = word(p, prop) ?: return null
            return if (w == "auto") null else length(w, base)
        }

        fun position(p: Part, stock: String): String {
            val w = word(p, "position") ?: return stock
            return when (w) {
                "static", "relative", "absolute", "fixed", "sticky" -> w
                else -> fail()
            }
        }

        /** `position: relative` shift: top, else minus bottom. */
        fun relative(p: Part): Double = offset(p, "top") ?: offset(p, "bottom")?.let { -it } ?: 0.0

        /** The vertical part of a `transform`, composed as CSS does (the last function applies first). */
        fun transform(p: Part, ownHeight: Double): Affine {
            val w = word(p, "transform") ?: return Affine(1.0, 0.0)
            if (w == "none") return Affine(1.0, 0.0)
            word(p, "transform-origin")?.let { if (it !in CENTER_ORIGINS) fail() }
            var a = 1.0
            var b = 0.0
            for ((fn, args) in CssValues.functions(w).asReversed()) {
                var fa = 1.0
                var fb = 0.0
                when (fn) {
                    "translatey" -> fb = length(args.singleOrNull() ?: fail(), ownHeight)
                    "translate" -> fb = args.getOrNull(1)?.let { length(it, ownHeight) } ?: 0.0
                    "translate3d" -> {
                        fb = length(args.getOrNull(1) ?: fail(), ownHeight)
                        if (length(args.getOrNull(2) ?: fail(), null) != 0.0) fail()
                    }
                    "translatex" -> {}
                    "scale" -> fa = CssValues.transformScale("scale(${args.joinToString(", ")})")?.takeIf { it > 0 } ?: fail()
                    else -> fail()
                }
                b = fa * b + fb
                a *= fa
            }
            return Affine(a, b)
        }

        /** Properties that would move the part in ways the model does not follow. */
        fun unsupported(p: Part, vararg props: String) {
            for (prop in props) {
                val w = word(p, prop) ?: continue
                val neutral = when (prop) {
                    "zoom" -> w == "normal" || w == "1" || w == "100%"
                    "scale", "translate", "transform" -> w == "none"
                    else -> false
                }
                if (!neutral) fail()
            }
        }

        fun flex(p: Part): Boolean = word(p, "display").let { it == "flex" || it == "inline-flex" }

        fun row(): Row {
            unsupported(Part.SECTION, "transform", "translate", "scale", "zoom")
            unsupported(Part.CONTAINER, "transform", "translate", "scale", "zoom")
            unsupported(Part.INNER, "translate", "scale", "zoom")
            unsupported(Part.CAROUSEL, "translate", "scale", "zoom")
            unsupported(Part.MEDIA, "transform", "translate", "scale", "zoom")

            // the home's scroll area starts at the top of the viewport; Steam's header is drawn over it
            val sectionPos = position(Part.SECTION, "static")
            if (sectionPos != "static" && sectionPos != "relative") fail()
            val sectionTop = (margin(Part.SECTION, "margin-top") ?: 0.0) + (if (sectionPos == "relative") relative(Part.SECTION) else 0.0)
            val sectionH = word(Part.SECTION, "height")?.takeIf { it != "auto" }?.let { length(it, vh) }
            val sectionPadTop = px(Part.SECTION, "padding-top", 0.0)
            val sectionFlexRow = flex(Part.SECTION) && word(Part.SECTION, "flex-direction").let { it == null || it == "row" }
            val sectionStretch = word(Part.SECTION, "align-items").let { it == null || it == "stretch" || it == "normal" }

            val containerPos = position(Part.CONTAINER, "relative")
            if (containerPos != "static" && containerPos != "relative") fail()
            val cMarginTop = margin(Part.CONTAINER, "margin-top") ?: 0.0
            val cMarginBottom = margin(Part.CONTAINER, "margin-bottom") ?: 0.0
            val cPadTop = px(Part.CONTAINER, "padding-top", CONTAINER_PADDING_TOP)
            val cPadBottom = px(Part.CONTAINER, "padding-bottom", 0.0)
            val cBorderBox = word(Part.CONTAINER, "box-sizing") == "border-box"
            var containerH = word(Part.CONTAINER, "height")?.takeIf { it != "auto" }?.let { h ->
                if (h.endsWith("%") && sectionH == null) null else length(h, sectionH)
            }?.let { if (cBorderBox) it else it + cPadTop + cPadBottom }
            if (containerH == null && sectionFlexRow && sectionStretch && sectionH != null) {
                containerH = sectionH - sectionPadTop - cMarginTop - cMarginBottom
            }
            val containerTop = sectionTop + sectionPadTop + cMarginTop + (if (containerPos == "relative") relative(Part.CONTAINER) else 0.0)
            val contentTop = containerTop + cPadTop
            val contentH = containerH?.let { it - cPadTop - cPadBottom }

            val headerPos = position(Part.HEADER, "static")
            val headerOuter = when {
                word(Part.HEADER, "display") == "none" -> 0.0
                headerPos == "absolute" || headerPos == "fixed" -> 0.0
                else -> px(Part.HEADER, "height", HEADER_LINE) + px(Part.HEADER, "padding-top", 0.0) + px(Part.HEADER, "padding-bottom", 0.0) +
                    (margin(Part.HEADER, "margin-top") ?: 0.0) + (margin(Part.HEADER, "margin-bottom") ?: 0.0)
            }

            // Steam sets the carousel's position and height inline; only !important beats them
            val carPosDecl = decl(Part.CAROUSEL, "position")
            val carPos = if (carPosDecl?.important == true) position(Part.CAROUSEL, "relative") else "relative"
            if (carPos == "fixed" || carPos == "sticky") fail()
            val carAbsolute = carPos == "absolute"
            val carMarginTop = margin(Part.CAROUSEL, "margin-top", CAROUSEL_MARGIN_TOP) ?: 0.0
            val carMarginBottom = margin(Part.CAROUSEL, "margin-bottom") ?: 0.0
            val carPadTop = px(Part.CAROUSEL, "padding-top", CAROUSEL_PADDING_TOP)

            val innerPos = position(Part.INNER, "relative")
            if (innerPos == "fixed" || innerPos == "absolute" || innerPos == "sticky") fail()
            if (innerPos == "static" && carAbsolute) fail()
            val iPadTop = px(Part.INNER, "padding-top", 0.0)
            val iPadBottom = px(Part.INNER, "padding-bottom", 0.0)
            val iMarginTop = margin(Part.INNER, "margin-top")
            val iMarginBottom = margin(Part.INNER, "margin-bottom")
            val iExplicit = word(Part.INNER, "height")?.takeIf { it != "auto" }?.let { h ->
                if (h.endsWith("%") && contentH == null) null else length(h, contentH)
            }?.let { if (word(Part.INNER, "box-sizing") == "border-box") it else it + iPadTop + iPadBottom }

            val carH = decl(Part.CAROUSEL, "height")?.takeIf { it.important }?.let { length(it.value.trim().lowercase(), iExplicit) } ?: CAROUSEL_H
            val flowH = headerOuter + if (carAbsolute) 0.0 else carMarginTop + carH + carMarginBottom
            var innerH = iExplicit ?: (iPadTop + flowH + iPadBottom)

            val mTop = iMarginTop ?: 0.0
            val mBottom = iMarginBottom ?: 0.0
            val autoTop = iMarginTop == null
            val autoBottom = iMarginBottom == null
            val containerFlex = flex(Part.CONTAINER)
            val direction = word(Part.CONTAINER, "flex-direction") ?: "row"
            if (containerFlex && direction.endsWith("reverse")) fail()
            val column = direction.startsWith("column")

            fun placeAt(where: String): Double {
                val ch = contentH ?: return contentTop + mTop
                val free = ch - innerH - mTop - mBottom
                return when (where) {
                    "start" -> contentTop + mTop
                    "center" -> contentTop + mTop + free / 2
                    "end" -> contentTop + mTop + free
                    else -> fail()
                }
            }
            fun autoMargins(): String? = when {
                autoTop && autoBottom -> "center"
                autoTop -> "end"
                autoBottom -> "start"
                else -> null
            }
            var innerTop = when {
                containerFlex && !column -> {
                    val align = word(Part.INNER, "align-self")?.takeIf { it != "auto" } ?: word(Part.CONTAINER, "align-items") ?: "stretch"
                    autoMargins()?.let { placeAt(it) } ?: when (align) {
                        "flex-start", "start", "self-start", "baseline" -> placeAt("start")
                        "center" -> placeAt("center")
                        "flex-end", "end", "self-end" -> placeAt("end")
                        "stretch", "normal" -> {
                            if (iExplicit == null && contentH != null) innerH = contentH - mTop - mBottom
                            placeAt("start")
                        }
                        else -> fail()
                    }
                }
                containerFlex -> {
                    val grow = (word(Part.INNER, "flex-grow") ?: word(Part.INNER, "flex")?.split(Regex("\\s+"))?.firstOrNull())
                        ?.toDoubleOrNull()?.let { it > 0 } == true
                    if (grow && contentH != null) innerH = contentH - mTop - mBottom
                    autoMargins()?.let { placeAt(it) } ?: when (word(Part.CONTAINER, "justify-content") ?: "flex-start") {
                        "flex-start", "start", "normal", "stretch", "space-between", "left", "right" -> placeAt("start")
                        "center", "space-around", "space-evenly" -> placeAt("center")
                        "flex-end", "end" -> placeAt("end")
                        else -> fail()
                    }
                }
                else -> contentTop + mTop
            }
            if (innerPos == "relative") innerTop += relative(Part.INNER)

            val carStatic = innerTop + iPadTop + headerOuter + carMarginTop
            val carTop = if (carAbsolute) {
                val top = offset(Part.CAROUSEL, "top", innerH)
                val bottom = offset(Part.CAROUSEL, "bottom", innerH)
                when {
                    top != null -> innerTop + top + carMarginTop
                    bottom != null -> innerTop + innerH - bottom - carH - carMarginBottom
                    else -> carStatic
                }
            } else {
                carStatic + relative(Part.CAROUSEL)
            }

            val mediaTop = when (position(Part.MEDIA, "relative")) {
                "relative" -> relative(Part.MEDIA)
                "static" -> 0.0
                "absolute" -> offset(Part.MEDIA, "top", ITEM_H)?.let { it + (margin(Part.MEDIA, "margin-top") ?: 0.0) }
                    ?: offset(Part.MEDIA, "bottom", ITEM_H)?.let { -it }
                    ?: 0.0
                else -> fail()
            }

            var top = carTop + carPadTop + mediaTop
            var scale = 1.0
            val car = transform(Part.CAROUSEL, carH)
            val carCenter = carTop + carH / 2
            top = carCenter + car.a * (top - carCenter) + car.b
            scale *= car.a
            val inner = transform(Part.INNER, innerH)
            val innerCenter = innerTop + innerH / 2
            top = innerCenter + inner.a * (top - innerCenter) + inner.b
            scale *= inner.a
            return Row(top, scale)
        }

        /** (hidden, offset) of the label against the row, in on-screen px under the row's [scale]. */
        fun title(scale: Double): Pair<Boolean, Double?> {
            if (word(Part.LABEL_WRAPPER, "display") == "none" || word(Part.LABEL, "display") == "none") return true to null
            unsupported(Part.LABEL_WRAPPER, "zoom", "translate", "scale")
            unsupported(Part.LABEL, "zoom", "translate", "scale")
            val h = px(Part.LABEL_WRAPPER, "height", LABEL_HEIGHT)
            val padTop = px(Part.LABEL_WRAPPER, "padding-top", LABEL_PADDING_TOP)
            val padBottom = px(Part.LABEL_WRAPPER, "padding-bottom", 0.0)
            val mTop = margin(Part.LABEL_WRAPPER, "margin-top") ?: 0.0
            val mBottom = margin(Part.LABEL_WRAPPER, "margin-bottom") ?: 0.0
            // in the media container, under the cover's wrapper (the item's height less LabelHeight)
            val staticTop = COVER_H + mTop
            val boxTop = when (position(Part.LABEL_WRAPPER, "static")) {
                "static" -> staticTop
                "relative" -> staticTop + relative(Part.LABEL_WRAPPER)
                "absolute" -> offset(Part.LABEL_WRAPPER, "top", ITEM_H)?.let { it + mTop }
                    ?: offset(Part.LABEL_WRAPPER, "bottom", ITEM_H)?.let { ITEM_H - it - mBottom - (padTop + h + padBottom) }
                    ?: staticTop
                else -> fail()
            }
            // Steam's rule for the shown label sets its transform; only an !important resting one replaces it
            val shown = decl(Part.LABEL_WRAPPER, "transform")?.takeIf { it.important }?.let { transform(Part.LABEL_WRAPPER, h).also { t -> if (t.a != 1.0) fail() }.b }
                ?: LABEL_SHOWN_SHIFT
            val labelPos = position(Part.LABEL, "relative")
            if (labelPos != "relative" && labelPos != "static") fail()
            val labelTransform = transform(Part.LABEL, h - padTop)
            if (labelTransform.a != 1.0) fail()
            val labelShift = (margin(Part.LABEL, "margin-top") ?: 0.0) + (if (labelPos == "relative") relative(Part.LABEL) else 0.0) + labelTransform.b
            val textTop = boxTop + padTop + shown + labelShift
            val textBottom = textTop + h
            val above = (textTop + textBottom) / 2 < COVER_H / 2
            val offset = if (above) textBottom * scale else (textTop - COVER_H) * scale
            if (above && offset >= 0 || !above && offset < 0) return false to null
            return false to offset
        }
    }
}
