package app.gamenative.theme

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** JSON form of a [ThemeSpec], shared by community previews and the saved active theme. Pure JVM. */
object ThemeSpecJson {

    /**
     * Flat object: colors as `#AARRGGBB`, numbers, `layout`, gradients. File fields become `hasFont` / `hasBackgroundImage`,
     * and with [files] also their paths.
     */
    fun encode(s: ThemeSpec, files: Boolean = false): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        fun c(k: String, v: Int?) { if (v != null) m[k] = JsonPrimitive(argbHex(v)) }
        fun n(k: String, v: Float?) { if (v != null && v.isFinite()) m[k] = JsonPrimitive(v) }
        c("background", s.background)
        c("surface", s.surface)
        c("surfaceVariant", s.surfaceVariant)
        c("primary", s.primary)
        c("onPrimary", s.onPrimary)
        c("text", s.text)
        c("textMuted", s.textMuted)
        c("outline", s.outline)
        c("focus", s.focus)
        c("bar", s.bar)
        n("cornerRadiusDp", s.cornerRadiusDp)
        if (s.fontFile != null) m["hasFont"] = JsonPrimitive(true)
        if (s.backgroundImage != null) m["hasBackgroundImage"] = JsonPrimitive(true)
        if (files) {
            s.fontFile?.let { m["fontFile"] = JsonPrimitive(it) }
            s.backgroundImage?.let { m["backgroundImage"] = JsonPrimitive(it) }
        }
        s.backgroundGradient?.let { m["backgroundGradient"] = gradientJson(it) }
        n("panelOpacity", s.panelOpacity)
        n("blurDp", s.blurDp)
        c("card", s.card)
        c("button", s.button)
        c("onButton", s.onButton)
        c("toggle", s.toggle)
        c("menu", s.menu)
        c("onMenu", s.onMenu)
        s.accentGradient?.let { m["accentGradient"] = gradientJson(it) }
        n("focusGlowDp", s.focusGlowDp)
        s.layout?.takeIf { !it.isEmpty }?.let { l ->
            val lm = LinkedHashMap<String, JsonElement>()
            fun ln(k: String, v: Float?) { if (v != null && v.isFinite()) lm[k] = JsonPrimitive(v) }
            ln("cardScale", l.cardScale)
            l.libraryLayout?.let { lm["libraryLayout"] = JsonPrimitive(it) }
            l.titleAlign?.let { lm["titleAlign"] = JsonPrimitive(it) }
            ln("titleStrokeDp", l.titleStrokeDp)
            l.titleStrokeColor?.let { lm["titleStrokeColor"] = JsonPrimitive(argbHex(it)) }
            l.hideBadges?.let { lm["hideBadges"] = JsonPrimitive(it) }
            l.hideFooterHints?.let { lm["hideFooterHints"] = JsonPrimitive(it) }
            ln("heroScale", l.heroScale)
            ln("cardTiltDeg", l.cardTiltDeg)
            ln("cardCornerRadiusDp", l.cardCornerRadiusDp)
            ln("iconCornerRadiusDp", l.iconCornerRadiusDp)
            ln("titleStrokeBlurDp", l.titleStrokeBlurDp)
            ln("subtitleStrokeDp", l.subtitleStrokeDp)
            l.subtitleStrokeColor?.let { lm["subtitleStrokeColor"] = JsonPrimitive(argbHex(it)) }
            ln("subtitleStrokeBlurDp", l.subtitleStrokeBlurDp)
            l.shelfWideFirst?.let { lm["shelfWideFirst"] = JsonPrimitive(it) }
            l.footerGlyphInvert?.let { lm["footerGlyphInvert"] = JsonPrimitive(it) }
            l.shelfRowPosition?.let { lm["shelfRowPosition"] = JsonPrimitive(it) }
            l.shelfTitleAbove?.let { lm["shelfTitleAbove"] = JsonPrimitive(it) }
            l.shelfHideTitle?.let { lm["shelfHideTitle"] = JsonPrimitive(it) }
            l.shelfHideSubtitle?.let { lm["shelfHideSubtitle"] = JsonPrimitive(it) }
            ln("cardTiltAfterDeg", l.cardTiltAfterDeg)
            ln("cardTurnDeg", l.cardTurnDeg)
            ln("cardTurnAfterDeg", l.cardTurnAfterDeg)
            ln("cardTurnPerspectiveDp", l.cardTurnPerspectiveDp)
            l.cardFocusedFlat?.let { lm["cardFocusedFlat"] = JsonPrimitive(it) }
            fun lb(k: String, v: Boolean?) { if (v != null) lm[k] = JsonPrimitive(v) }
            fun lf(k: String, v: String?) { if (files && v != null) lm[k] = JsonPrimitive(v) }
            if (files) {
                l.tileFrames?.mapNotNull(::frameJson)?.takeIf { it.isNotEmpty() }?.let { lm["tileFrames"] = JsonArray(it) }
                l.tileFrameWide?.let(::frameJson)?.let { lm["tileFrameWide"] = it }
            }
            lf("shelfRowImage", l.shelfRowImage)
            l.coverReflection?.let { r ->
                lm["coverReflection"] = JsonObject(
                    linkedMapOf<String, JsonElement>(
                        "opacity" to JsonPrimitive(r.opacity),
                        "blurDp" to JsonPrimitive(r.blurDp),
                        "fadeStop" to JsonPrimitive(r.fadeStop),
                        "gapDp" to JsonPrimitive(r.gapDp),
                    ).apply { r.focusedOpacity?.let { put("focusedOpacity", JsonPrimitive(it)) } },
                )
            }
            ln("cardRestBrightness", l.cardRestBrightness)
            ln("cardRestSaturation", l.cardRestSaturation)
            ln("cardFocusedScale", l.cardFocusedScale)
            l.cardDepthSteps?.takeIf { it.isNotEmpty() }?.let { steps ->
                lm["cardDepthSteps"] = JsonArray(
                    steps.map {
                        JsonObject(
                            linkedMapOf(
                                "scale" to JsonPrimitive(it.scale),
                                "shiftXFraction" to JsonPrimitive(it.shiftXFraction),
                                "shiftYDp" to JsonPrimitive(it.shiftYDp),
                            ),
                        )
                    },
                )
            }
            ln("cardSpineWidthDp", l.cardSpineWidthDp)
            l.cardSpineColor?.let { lm["cardSpineColor"] = JsonPrimitive(argbHex(it)) }
            lb("shelfFitAll", l.shelfFitAll)
            lb("shelfFocusCentered", l.shelfFocusCentered)
            l.shelfHeaderText?.let { lm["shelfHeaderText"] = JsonPrimitive(it) }
            l.gridColumns?.let { lm["gridColumns"] = JsonPrimitive(it) }
            ln("gridTileAspect", l.gridTileAspect)
            lb("gridTitleBelow", l.gridTitleBelow)
            ln("uninstalledSaturation", l.uninstalledSaturation)
            ln("uninstalledOpacity", l.uninstalledOpacity)
            lf("uninstalledIcon", l.uninstalledIcon)
            l.tabColors?.takeIf { it.isNotEmpty() }?.let { cs -> lm["tabColors"] = JsonArray(cs.map { JsonPrimitive(argbHex(it)) }) }
            lb("hideTabCounts", l.hideTabCounts)
            lf("heroImage", l.heroImage)
            lb("heroImageTile", l.heroImageTile)
            ln("heroImageScale", l.heroImageScale)
            ln("heroHeightFraction", l.heroHeightFraction)
            l.homeOverlay?.let(::fillJson)?.let { lm["homeOverlay"] = it }
            l.screenOverlay?.let(::fillJson)?.let { lm["screenOverlay"] = it }
            l.footerHiddenHints?.takeIf { it.isNotEmpty() }?.let { hs -> lm["footerHiddenHints"] = JsonArray(hs.map { JsonPrimitive(it) }) }
            lb("hideTopBarSearch", l.hideTopBarSearch)
            m["layout"] = JsonObject(lm)
        }
        s.header?.let(::barJson)?.let { m["header"] = it }
        s.footer?.let(::barJson)?.let { m["footer"] = it }
        s.textRoles?.let { t ->
            obj {
                col("heading", t.heading)
                col("subtitle", t.subtitle)
                col("glowColor", t.glowColor)
                dp("glowDp", t.glowDp)
            }
        }?.let { m["textRoles"] = it }
        s.controls?.let { k ->
            obj {
                put("toggleOn", k.toggleOn?.let(::fillJson))
                col("toggleOff", k.toggleOff)
                col("thumbOn", k.thumbOn)
                col("thumbOff", k.thumbOff)
                col("sliderTrack", k.sliderTrack)
                col("sliderThumb", k.sliderThumb)
                put("buttonFill", k.buttonFill?.let(::fillJson))
                put("buttonFocusFill", k.buttonFocusFill?.let(::fillJson))
                col("onButtonFocus", k.onButtonFocus)
                col("buttonUnderlineColor", k.buttonUnderlineColor)
                dp("buttonUnderlineDp", k.buttonUnderlineDp)
                col("tabFill", k.tabFill)
                col("onTab", k.onTab)
            }
        }?.let { m["controls"] = it }
        s.focusStyle?.let { k ->
            obj {
                dp("ringDp", k.ringDp)
                col("ringColor", k.ringColor)
                put("fill", k.fill?.let(::fillJson))
                col("onFill", k.onFill)
                put("underline", k.underline?.let(::gradientJson))
                dp("underlineDp", k.underlineDp)
                put("blink", k.blink?.let { JsonPrimitive(it) })
            }
        }?.let { m["focusStyle"] = it }
        s.menuStyle?.let { k ->
            obj {
                dp("cornerRadiusDp", k.cornerRadiusDp)
                put("fill", k.fill?.let(::fillJson))
                dp("opacity", k.opacity)
                dp("blurDp", k.blurDp)
                col("borderColor", k.borderColor)
                dp("borderDp", k.borderDp)
                dp("glowDp", k.glowDp)
                col("focusFill", k.focusFill)
                col("onFocus", k.onFocus)
            }
        }?.let { m["menuStyle"] = it }
        s.panelFill?.let(::fillJson)?.let { m["panelFill"] = it }
        s.typography?.let { t ->
            obj {
                dp("scale", t.scale)
                dp("titleScale", t.titleScale)
                dp("footerScale", t.footerScale)
                put("headingWeight", t.headingWeight?.let { JsonPrimitive(it) })
                put("selectedWeight", t.selectedWeight?.let { JsonPrimitive(it) })
                dp("letterSpacingEm", t.letterSpacingEm)
                put("headingUppercase", t.headingUppercase?.let { JsonPrimitive(it) })
                put("tabUppercase", t.tabUppercase?.let { JsonPrimitive(it) })
                put("footerUppercase", t.footerUppercase?.let { JsonPrimitive(it) })
                put("allUppercase", t.allUppercase?.let { JsonPrimitive(it) })
            }
        }?.let { m["typography"] = it }
        s.hero?.let { h ->
            obj {
                dp("opacity", h.opacity)
                dp("blurDp", h.blurDp)
                dp("saturation", h.saturation)
                dp("overlay", h.overlay)
                dp("fadeBottom", h.fadeBottom)
            }
        }?.let { m["hero"] = it }
        s.motion?.let { k ->
            obj {
                dp("fillSlideSec", k.fillSlideSec)
                dp("panelSlideSec", k.panelSlideSec)
                dp("underlineSlideSec", k.underlineSlideSec)
            }
        }?.let { m["motion"] = it }
        return JsonObject(m)
    }

    /** Builds a JSON object from the values that are set; null when none is. */
    private class ObjBuilder {
        val m = LinkedHashMap<String, JsonElement>()
        fun put(k: String, v: JsonElement?) { if (v != null) m[k] = v }
        fun col(k: String, v: Int?) { if (v != null) m[k] = JsonPrimitive("#%08X".format(v)) }
        fun dp(k: String, v: Float?) { if (v != null && v.isFinite()) m[k] = JsonPrimitive(v) }
    }

    private fun obj(block: ObjBuilder.() -> Unit): JsonObject? = ObjBuilder().apply(block).m.takeIf { it.isNotEmpty() }?.let(::JsonObject)

    private fun barJson(b: ThemeBar): JsonObject? = obj {
        col("color", b.color)
        put("fill", b.fill?.let(::fillJson))
        col("borderColor", b.borderColor)
        dp("borderDp", b.borderDp)
        col("glowColor", b.glowColor)
        dp("glowDp", b.glowDp)
        dp("blurDp", b.blurDp)
        col("textColor", b.textColor)
        dp("heightScale", b.heightScale)
    }

    private fun fillJson(f: ThemeFill): JsonObject? = obj {
        if (f.layers.isNotEmpty()) put("layers", JsonArray(f.layers.map(::gradientJson)))
        col("color", f.color)
    }

    private fun parseFill(e: JsonElement?): ThemeFill? {
        val o = e as? JsonObject ?: return null
        val layers = (o["layers"] as? JsonArray)?.mapNotNull { parseGradient(it) } ?: emptyList()
        return ThemeFill(layers, parseArgb(o.str("color"))).takeIf { !it.isEmpty }
    }

    private fun parseBar(e: JsonElement?): ThemeBar? {
        val o = e as? JsonObject ?: return null
        return ThemeBar(
            color = parseArgb(o.str("color")),
            fill = parseFill(o["fill"]),
            borderColor = parseArgb(o.str("borderColor")),
            borderDp = o.float("borderDp"),
            glowColor = parseArgb(o.str("glowColor")),
            glowDp = o.float("glowDp"),
            blurDp = o.float("blurDp"),
            textColor = parseArgb(o.str("textColor")),
            heightScale = o.float("heightScale")?.takeIf { it > 0f }?.coerceIn(0.25f, 2f),
        ).takeIf { !it.isEmpty }
    }

    /** [encode] read back; file paths only with [files], so a preview from elsewhere never names a local file. Null when nothing is set. */
    fun decode(o: JsonObject, files: Boolean = false): ThemeSpec? {
        fun c(k: String) = parseArgb(o.str(k))
        fun n(k: String) = o.float(k)
        val l = o["layout"] as? JsonObject
        val layout = l?.let {
            ThemeLayout(
                cardScale = it.float("cardScale"),
                libraryLayout = it.str("libraryLayout"),
                titleAlign = it.str("titleAlign"),
                titleStrokeDp = it.float("titleStrokeDp"),
                titleStrokeColor = parseArgb(it.str("titleStrokeColor")),
                hideBadges = (it["hideBadges"] as? JsonPrimitive)?.booleanOrNull,
                hideFooterHints = (it["hideFooterHints"] as? JsonPrimitive)?.booleanOrNull,
                heroScale = it.float("heroScale"),
                cardTiltDeg = it.float("cardTiltDeg"),
                cardCornerRadiusDp = it.float("cardCornerRadiusDp"),
                iconCornerRadiusDp = it.float("iconCornerRadiusDp"),
                titleStrokeBlurDp = it.float("titleStrokeBlurDp"),
                subtitleStrokeDp = it.float("subtitleStrokeDp"),
                subtitleStrokeColor = parseArgb(it.str("subtitleStrokeColor")),
                subtitleStrokeBlurDp = it.float("subtitleStrokeBlurDp"),
                shelfWideFirst = (it["shelfWideFirst"] as? JsonPrimitive)?.booleanOrNull,
                footerGlyphInvert = (it["footerGlyphInvert"] as? JsonPrimitive)?.booleanOrNull,
                shelfRowPosition = it.str("shelfRowPosition"),
                shelfTitleAbove = (it["shelfTitleAbove"] as? JsonPrimitive)?.booleanOrNull,
                shelfHideTitle = (it["shelfHideTitle"] as? JsonPrimitive)?.booleanOrNull,
                shelfHideSubtitle = (it["shelfHideSubtitle"] as? JsonPrimitive)?.booleanOrNull,
                cardTiltAfterDeg = it.float("cardTiltAfterDeg"),
                cardTurnDeg = it.float("cardTurnDeg"),
                cardTurnAfterDeg = it.float("cardTurnAfterDeg"),
                cardTurnPerspectiveDp = it.float("cardTurnPerspectiveDp"),
                cardFocusedFlat = (it["cardFocusedFlat"] as? JsonPrimitive)?.booleanOrNull,
                tileFrames = (it["tileFrames"] as? JsonArray)?.takeIf { files }?.mapNotNull(::parseFrame)?.takeIf { f -> f.isNotEmpty() },
                tileFrameWide = it["tileFrameWide"]?.takeIf { files }?.let(::parseFrame),
                shelfRowImage = it.str("shelfRowImage")?.takeIf { files },
                coverReflection = (it["coverReflection"] as? JsonObject)?.let { r ->
                    r.float("opacity")?.let { o ->
                        ThemeReflection(
                            opacity = o,
                            focusedOpacity = r.float("focusedOpacity"),
                            blurDp = r.float("blurDp") ?: 0f,
                            fadeStop = r.float("fadeStop") ?: 1f,
                            gapDp = r.float("gapDp") ?: 0f,
                        )
                    }
                },
                cardRestBrightness = it.float("cardRestBrightness"),
                cardRestSaturation = it.float("cardRestSaturation"),
                cardFocusedScale = it.float("cardFocusedScale"),
                cardDepthSteps = (it["cardDepthSteps"] as? JsonArray)?.mapNotNull { e ->
                    (e as? JsonObject)?.let { s ->
                        ThemeDepthStep(s.float("scale") ?: 1f, s.float("shiftXFraction") ?: 0f, s.float("shiftYDp") ?: 0f)
                    }
                }?.takeIf { s -> s.isNotEmpty() },
                cardSpineWidthDp = it.float("cardSpineWidthDp"),
                cardSpineColor = parseArgb(it.str("cardSpineColor")),
                shelfFitAll = (it["shelfFitAll"] as? JsonPrimitive)?.booleanOrNull,
                shelfFocusCentered = (it["shelfFocusCentered"] as? JsonPrimitive)?.booleanOrNull,
                shelfHeaderText = it.str("shelfHeaderText"),
                gridColumns = it.int("gridColumns"),
                gridTileAspect = it.float("gridTileAspect"),
                gridTitleBelow = (it["gridTitleBelow"] as? JsonPrimitive)?.booleanOrNull,
                uninstalledSaturation = it.float("uninstalledSaturation"),
                uninstalledOpacity = it.float("uninstalledOpacity"),
                uninstalledIcon = it.str("uninstalledIcon")?.takeIf { files },
                tabColors = (it["tabColors"] as? JsonArray)?.mapNotNull { e -> parseArgb((e as? JsonPrimitive)?.contentOrNull) }?.takeIf { c -> c.isNotEmpty() },
                hideTabCounts = (it["hideTabCounts"] as? JsonPrimitive)?.booleanOrNull,
                heroImage = it.str("heroImage")?.takeIf { files },
                heroImageTile = (it["heroImageTile"] as? JsonPrimitive)?.booleanOrNull,
                heroImageScale = it.float("heroImageScale"),
                heroHeightFraction = it.float("heroHeightFraction"),
                homeOverlay = parseFill(it["homeOverlay"]),
                screenOverlay = parseFill(it["screenOverlay"]),
                footerHiddenHints = (it["footerHiddenHints"] as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?.takeIf { h -> h.isNotEmpty() },
                hideTopBarSearch = (it["hideTopBarSearch"] as? JsonPrimitive)?.booleanOrNull,
            ).takeIf { x -> !x.isEmpty }
        }
        val spec = ThemeSpec(
            background = c("background"),
            surface = c("surface"),
            surfaceVariant = c("surfaceVariant"),
            primary = c("primary"),
            onPrimary = c("onPrimary"),
            text = c("text"),
            textMuted = c("textMuted"),
            outline = c("outline"),
            focus = c("focus"),
            bar = c("bar"),
            cornerRadiusDp = n("cornerRadiusDp"),
            fontFile = if (files) o.str("fontFile") else null,
            backgroundImage = if (files) o.str("backgroundImage") else null,
            backgroundGradient = parseGradient(o["backgroundGradient"]),
            panelOpacity = n("panelOpacity")?.coerceIn(0f, 1f),
            blurDp = n("blurDp"),
            card = c("card"),
            button = c("button"),
            onButton = c("onButton"),
            toggle = c("toggle"),
            menu = c("menu"),
            onMenu = c("onMenu"),
            accentGradient = parseGradient(o["accentGradient"]),
            focusGlowDp = n("focusGlowDp"),
            layout = layout,
            header = parseBar(o["header"]),
            footer = parseBar(o["footer"]),
            textRoles = (o["textRoles"] as? JsonObject)?.let { t ->
                ThemeTextRoles(
                    heading = parseArgb(t.str("heading")),
                    subtitle = parseArgb(t.str("subtitle")),
                    glowColor = parseArgb(t.str("glowColor")),
                    glowDp = t.float("glowDp"),
                ).takeIf { !it.isEmpty }
            },
            controls = (o["controls"] as? JsonObject)?.let { k ->
                ThemeControls(
                    toggleOn = parseFill(k["toggleOn"]),
                    toggleOff = parseArgb(k.str("toggleOff")),
                    thumbOn = parseArgb(k.str("thumbOn")),
                    thumbOff = parseArgb(k.str("thumbOff")),
                    sliderTrack = parseArgb(k.str("sliderTrack")),
                    sliderThumb = parseArgb(k.str("sliderThumb")),
                    buttonFill = parseFill(k["buttonFill"]),
                    buttonFocusFill = parseFill(k["buttonFocusFill"]),
                    onButtonFocus = parseArgb(k.str("onButtonFocus")),
                    buttonUnderlineColor = parseArgb(k.str("buttonUnderlineColor")),
                    buttonUnderlineDp = k.float("buttonUnderlineDp"),
                    tabFill = parseArgb(k.str("tabFill")),
                    onTab = parseArgb(k.str("onTab")),
                ).takeIf { !it.isEmpty }
            },
            focusStyle = (o["focusStyle"] as? JsonObject)?.let { k ->
                ThemeFocus(
                    ringDp = k.float("ringDp"),
                    ringColor = parseArgb(k.str("ringColor")),
                    fill = parseFill(k["fill"]),
                    onFill = parseArgb(k.str("onFill")),
                    underline = parseGradient(k["underline"]),
                    underlineDp = k.float("underlineDp"),
                    blink = (k["blink"] as? JsonPrimitive)?.booleanOrNull,
                ).takeIf { !it.isEmpty }
            },
            menuStyle = (o["menuStyle"] as? JsonObject)?.let { k ->
                ThemeMenu(
                    cornerRadiusDp = k.float("cornerRadiusDp"),
                    fill = parseFill(k["fill"]),
                    opacity = k.float("opacity")?.coerceIn(0f, 1f),
                    blurDp = k.float("blurDp"),
                    borderColor = parseArgb(k.str("borderColor")),
                    borderDp = k.float("borderDp"),
                    glowDp = k.float("glowDp"),
                    focusFill = parseArgb(k.str("focusFill")),
                    onFocus = parseArgb(k.str("onFocus")),
                ).takeIf { !it.isEmpty }
            },
            panelFill = parseFill(o["panelFill"]),
            typography = (o["typography"] as? JsonObject)?.let { t ->
                fun scale(k: String) = t.float(k)?.takeIf { it > 0f }?.coerceIn(ThemeTypography.MIN_SCALE, ThemeTypography.MAX_SCALE)
                ThemeTypography(
                    scale = scale("scale"),
                    titleScale = scale("titleScale"),
                    footerScale = scale("footerScale"),
                    headingWeight = t.int("headingWeight")?.let(ThemeTypography::weight),
                    selectedWeight = t.int("selectedWeight")?.let(ThemeTypography::weight),
                    letterSpacingEm = t.float("letterSpacingEm")?.coerceIn(-0.1f, ThemeTypography.MAX_LETTER_SPACING_EM),
                    headingUppercase = t.bool("headingUppercase"),
                    tabUppercase = t.bool("tabUppercase"),
                    footerUppercase = t.bool("footerUppercase"),
                    allUppercase = t.bool("allUppercase"),
                ).takeIf { !it.isEmpty }
            },
            hero = (o["hero"] as? JsonObject)?.let { h ->
                ThemeHero(
                    opacity = h.float("opacity")?.coerceIn(0f, 1f),
                    blurDp = h.float("blurDp")?.coerceAtLeast(0f),
                    saturation = h.float("saturation")?.coerceIn(0f, 1f),
                    overlay = h.float("overlay")?.coerceIn(0f, ThemeHero.MAX_OVERLAY),
                    fadeBottom = h.float("fadeBottom")?.coerceIn(0f, 1f),
                ).takeIf { !it.isEmpty }
            },
            motion = (o["motion"] as? JsonObject)?.let { k ->
                fun sec(key: String) = k.float(key)?.let { if (it <= 0f) 0f else it.coerceIn(ThemeMotion.MIN_SEC, ThemeMotion.MAX_SEC) }
                ThemeMotion(
                    fillSlideSec = sec("fillSlideSec"),
                    panelSlideSec = sec("panelSlideSec"),
                    underlineSlideSec = sec("underlineSlideSec"),
                ).takeIf { !it.isEmpty }
            },
        )
        return spec.takeIf { !it.isEmpty }
    }

    private fun gradientJson(g: ThemeGradient): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>(
            "angle" to JsonPrimitive(g.angleDeg),
            "colors" to JsonArray(g.colors.map { JsonPrimitive(argbHex(it)) }),
            "stops" to JsonArray(g.stops.map { JsonPrimitive(it) }),
        ).apply {
            g.repeatDp?.let { put("repeatDp", JsonPrimitive(it)) }
            g.radial?.let { r ->
                val rm = linkedMapOf<String, JsonElement>(
                    "circle" to JsonPrimitive(r.circle),
                    "centerX" to JsonPrimitive(r.centerX),
                    "centerY" to JsonPrimitive(r.centerY),
                    "offsetXDp" to JsonPrimitive(r.offsetXDp),
                    "offsetYDp" to JsonPrimitive(r.offsetYDp),
                    "extent" to JsonPrimitive(r.extent),
                )
                r.radiusXDp?.let { rm["radiusXDp"] = JsonPrimitive(it) }
                r.radiusYDp?.let { rm["radiusYDp"] = JsonPrimitive(it) }
                put("radial", JsonObject(rm))
            }
        },
    )

    private fun parseRadial(e: JsonElement?): ThemeRadial? {
        val o = e as? JsonObject ?: return null
        return ThemeRadial(
            circle = (o["circle"] as? JsonPrimitive)?.booleanOrNull ?: false,
            centerX = o.float("centerX") ?: 0.5f,
            centerY = o.float("centerY") ?: 0.5f,
            offsetXDp = o.float("offsetXDp") ?: 0f,
            offsetYDp = o.float("offsetYDp") ?: 0f,
            extent = o.str("extent")?.takeIf { it in ThemeRadial.EXTENTS } ?: ThemeRadial.FARTHEST_CORNER,
            radiusXDp = o.float("radiusXDp"),
            radiusYDp = o.float("radiusYDp"),
        )
    }

    private fun frameJson(f: ThemeTileFrame): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>(
            "image" to JsonPrimitive(f.image),
            "insetLeft" to JsonPrimitive(f.insetLeft),
            "insetTop" to JsonPrimitive(f.insetTop),
            "insetWidth" to JsonPrimitive(f.insetWidth),
            "insetHeight" to JsonPrimitive(f.insetHeight),
        ).apply { f.mask?.let { put("mask", JsonPrimitive(it)) } },
    )

    private fun parseFrame(e: JsonElement?): ThemeTileFrame? {
        val o = e as? JsonObject ?: return null
        return ThemeTileFrame(
            image = o.str("image") ?: return null,
            mask = o.str("mask"),
            insetLeft = o.float("insetLeft") ?: 0f,
            insetTop = o.float("insetTop") ?: 0f,
            insetWidth = o.float("insetWidth") ?: 1f,
            insetHeight = o.float("insetHeight") ?: 1f,
        )
    }

    private fun parseGradient(e: JsonElement?): ThemeGradient? {
        val o = e as? JsonObject ?: return null
        val angle = o.float("angle") ?: 180f
        val colors = (o["colors"] as? JsonArray)?.map { parseArgb((it as? JsonPrimitive)?.contentOrNull) ?: return null } ?: return null
        if (colors.isEmpty()) return null
        val stops = (o["stops"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat()?.coerceIn(0f, 1f) } ?: emptyList()
        return ThemeGradient(
            angle, colors, if (stops.size == colors.size) stops else emptyList(), parseRadial(o["radial"]),
            o.float("repeatDp")?.takeIf { it > 0f },
        )
    }

    fun argbHex(argb: Int): String = "#%08X".format(argb)

    /** `#AARRGGBB` or `#RRGGBB`; null for anything else. */
    fun parseArgb(s: String?): Int? {
        val h = s?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6 && h.length != 8) return null
        val v = h.toLongOrNull(16) ?: return null
        return if (h.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toFloat()?.takeIf { it.isFinite() }
}
