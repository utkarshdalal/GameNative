package app.gamenative.theme

import app.gamenative.api.AccountApi
import app.gamenative.api.GameNativeApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/** GameNative community themes API. Every call throws [CommunityException] on failure. */
class CommunityThemeClient(
    private val transport: Transport,
    private val baseUrl: () -> String,
    private val boundary: () -> String = { "gn-theme-" + UUID.randomUUID().toString() },
) {

    enum class Auth { NONE, OPTIONAL, REQUIRED }

    class Request(
        val method: String,
        val url: String,
        val auth: Auth,
        val contentType: String? = null,
        val body: ByteArray? = null,
        val maxResponseBytes: Long = MAX_JSON_BYTES,
    )

    class Response(val code: Int, val body: ByteArray)

    interface Transport {
        /** Null when [Request.auth] is [Auth.REQUIRED] and there is no usable account session. */
        suspend fun send(request: Request): Response?
    }

    data class Upload(val id: String, val version: Int)

    suspend fun list(query: String, page: Int, sort: CommunitySort, mineOnly: Boolean): CommunityPage {
        val params = buildList {
            if (query.isNotBlank()) add("q" to query.trim())
            add("page" to page.coerceAtLeast(1).toString())
            add("per_page" to PAGE_SIZE.toString())
            add("sort" to if (sort == CommunitySort.NEW) "new" else "popular")
            if (mineOnly) add("mine" to "1")
        }
        val qs = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val r = call(Request("GET", "${baseUrl()}/api/themes?$qs", if (mineOnly) Auth.REQUIRED else Auth.OPTIONAL))
        return parsePage(r.body.toString(Charsets.UTF_8))
    }

    suspend fun download(id: String, dest: File) {
        val r = call(Request("GET", "${baseUrl()}/api/themes/${encodeId(id)}/download", Auth.OPTIONAL, maxResponseBytes = MAX_DOWNLOAD_BYTES))
        if (r.body.size < 4 || r.body[0] != 'P'.code.toByte() || r.body[1] != 'K'.code.toByte()) {
            throw CommunityException("server", "Download is not a theme file")
        }
        dest.writeBytes(r.body)
    }

    suspend fun upload(meta: JsonObject, zip: ByteArray): Upload {
        val b = boundary()
        val r = call(Request("POST", "${baseUrl()}/api/themes", Auth.REQUIRED, "multipart/form-data; boundary=$b", multipart(b, meta.toString(), zip)))
        val o = parseObject(r.body.toString(Charsets.UTF_8))
        val id = o.str("id") ?: throw CommunityException("server", "Upload response has no id")
        return Upload(id, o.int("version") ?: 1)
    }

    suspend fun terms(): CommunityTerms {
        val r = call(Request("GET", "${baseUrl()}/api/themes/terms", Auth.NONE))
        return parseTerms(r.body.toString(Charsets.UTF_8))
    }

    suspend fun delete(id: String) {
        expectNoContent(call(Request("DELETE", "${baseUrl()}/api/themes/${encodeId(id)}", Auth.REQUIRED)))
    }

    suspend fun report(id: String, reason: String, details: String?) {
        val body = JsonObject(mapOf("reason" to JsonPrimitive(reason), "details" to (details?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)))
        expectNoContent(call(Request("POST", "${baseUrl()}/api/themes/${encodeId(id)}/report", Auth.REQUIRED, JSON_TYPE, body.toString().toByteArray())))
    }

    private fun expectNoContent(r: Response) {
        if (r.code != 204) throw CommunityException("server", "Unexpected response ${r.code}")
    }

    private suspend fun call(request: Request): Response {
        val r = try {
            transport.send(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CommunityException) {
            throw e
        } catch (e: Exception) {
            throw CommunityException("network", e.message)
        } ?: throw CommunityException("unauthorized")
        if (r.code !in 200..299) {
            val e = errorOf(r.code, r.body.toString(Charsets.UTF_8))
            Timber.w("Community ${request.method} ${request.url.substringBefore('?')} -> ${r.code} ${e.code}: ${e.message}")
            throw e
        }
        return r
    }

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 16L * 1024 * 1024
        private const val JSON_TYPE = "application/json"
        private val CODES = setOf(
            "unauthorized", "not_found", "too_large", "invalid_theme", "text_rejected", "rate_limited",
            "terms_required", "terms_outdated", "forbidden",
        )

        val default: CommunityThemeClient by lazy { CommunityThemeClient(HttpTransport, baseUrl = { GameNativeApi.BASE_URL }) }

        /** [listed] as the offered update when it is newer than [installed]; an unknown installed version counts as older. */
        fun updateVersion(listed: Int, installed: Int?): String? =
            if (installed == null || listed > installed) listed.toString() else null

        fun encodeId(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "_" }

        fun errorOf(status: Int, body: String): CommunityException {
            val o = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (e: Exception) {
                null
            }
            val code = o?.str("error")?.takeIf { it in CODES } ?: when (status) {
                401 -> "unauthorized"
                403 -> "forbidden"
                404 -> "not_found"
                413 -> "too_large"
                422 -> "invalid_theme"
                429 -> "rate_limited"
                else -> "server"
            }
            return CommunityException(code, o?.str("message")?.takeIf { it.isNotBlank() })
        }

        fun multipart(boundary: String, meta: String, zip: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(zip.size + 1024)
            fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
            w("--$boundary\r\n")
            w("Content-Disposition: form-data; name=\"meta\"\r\n\r\n")
            w(meta)
            w("\r\n--$boundary\r\n")
            w("Content-Disposition: form-data; name=\"file\"; filename=\"theme.zip\"\r\n")
            w("Content-Type: application/zip\r\n\r\n")
            out.write(zip)
            w("\r\n--$boundary--\r\n")
            return out.toByteArray()
        }

        fun uploadMeta(
            name: String,
            description: String,
            authorName: String,
            preview: ThemeSpec?,
            replacesId: String?,
            termsVersion: String,
        ): JsonObject =
            JsonObject(
                linkedMapOf(
                    "name" to JsonPrimitive(name),
                    "description" to JsonPrimitive(description),
                    "author_name" to JsonPrimitive(authorName),
                    "preview" to (preview?.let(::previewJson) ?: JsonNull),
                    "replaces_id" to (replacesId?.let(::JsonPrimitive) ?: JsonNull),
                    "terms_version" to JsonPrimitive(termsVersion),
                ),
            )

        fun parseTerms(body: String): CommunityTerms {
            val o = parseObject(body)
            val version = o.str("version")?.trim()?.takeIf { it.isNotEmpty() } ?: throw CommunityException("server", "Terms have no version")
            return CommunityTerms(
                version = version,
                uploadTerms = o.str("upload_terms").orEmpty(),
                copyrightPolicy = o.str("copyright_policy").orEmpty(),
                contact = o.str("contact")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        fun parsePage(body: String): CommunityPage {
            val o = parseObject(body)
            val list = o["themes"] as? JsonArray ?: throw CommunityException("server", "Response has no theme list")
            val themes = list.mapNotNull { (it as? JsonObject)?.let(::parseTheme) }
            return CommunityPage(themes, (o["has_more"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }

        fun parseTheme(o: JsonObject): CommunityTheme? {
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val hidden = (o["hidden"] as? JsonPrimitive)?.booleanOrNull ?: false
            return CommunityTheme(
                id = id,
                name = o.str("name") ?: "",
                description = o.str("description")?.takeIf { it.isNotBlank() },
                authorName = o.str("author_name")?.takeIf { it.isNotBlank() },
                version = o.int("version") ?: 1,
                downloads = o.int("downloads") ?: 0,
                sizeBytes = (o["size_bytes"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L,
                preview = (o["preview"] as? JsonObject)?.let(::parsePreview),
                mine = (o["mine"] as? JsonPrimitive)?.booleanOrNull ?: false,
                updatedAt = o.str("updated_at"),
                hidden = hidden,
                hiddenReason = o.str("hidden_reason")?.takeIf { hidden && it.isNotBlank() },
            )
        }

        /** Flat preview object: colours as `#AARRGGBB`, numbers, `layout`, gradients; file fields become `hasFont` / `hasBackgroundImage`. */
        fun previewJson(s: ThemeSpec): JsonObject {
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

        fun parsePreview(o: JsonObject): ThemeSpec? {
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
            )
            return spec.takeIf { !it.isEmpty }
        }

        private fun gradientJson(g: ThemeGradient): JsonObject = JsonObject(
            linkedMapOf<String, JsonElement>(
                "angle" to JsonPrimitive(g.angleDeg),
                "colors" to JsonArray(g.colors.map { JsonPrimitive(argbHex(it)) }),
                "stops" to JsonArray(g.stops.map { JsonPrimitive(it) }),
            ).apply {
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

        private fun parseGradient(e: JsonElement?): ThemeGradient? {
            val o = e as? JsonObject ?: return null
            val angle = o.float("angle") ?: 180f
            val colors = (o["colors"] as? JsonArray)?.map { parseArgb((it as? JsonPrimitive)?.contentOrNull) ?: return null } ?: return null
            if (colors.isEmpty()) return null
            val stops = (o["stops"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat()?.coerceIn(0f, 1f) } ?: emptyList()
            return ThemeGradient(angle, colors, if (stops.size == colors.size) stops else emptyList(), parseRadial(o["radial"]))
        }

        fun argbHex(argb: Int): String = "#%08X".format(argb)

        /** `#AARRGGBB` or `#RRGGBB`; null for anything else. */
        fun parseArgb(s: String?): Int? {
            val h = s?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6 && h.length != 8) return null
            val v = h.toLongOrNull(16) ?: return null
            return if (h.length == 6) (0xFF000000L or v).toInt() else v.toInt()
        }

        private fun parseObject(body: String): JsonObject = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw CommunityException("server", "Unreadable response")

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.longOrNull?.toInt() ?: it.doubleOrNull?.toInt() }

        private fun JsonObject.float(key: String): Float? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toFloat()?.takeIf { it.isFinite() }
    }

    /** OkHttp through the app's GameNative account session; [AccountApi.sendAuthorized] refreshes once on a 401. */
    object HttpTransport : Transport {
        override suspend fun send(request: Request): Response? {
            val build: (okhttp3.Request.Builder) -> okhttp3.Request.Builder = { b ->
                val body = request.body?.toRequestBody(request.contentType?.toMediaTypeOrNull())
                b.url(request.url).method(request.method, body)
            }
            val read: (okhttp3.Response) -> Response = { resp ->
                Response(resp.code, readCapped(resp.body.byteStream(), request.maxResponseBytes))
            }
            fun plain(): Response = GameNativeApi.httpClient.newCall(build(okhttp3.Request.Builder()).build()).execute().use(read)
            return when (request.auth) {
                Auth.NONE -> plain()
                Auth.REQUIRED -> AccountApi.sendAuthorized(build, read)
                Auth.OPTIONAL -> AccountApi.sendAuthorized(build, read) ?: plain()
            }
        }

        private fun readCapped(input: InputStream, max: Long): ByteArray {
            val out = ByteArrayOutputStream()
            input.use {
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) throw IOException("Response is larger than ${max / (1024 * 1024)} MB")
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        }
    }
}
