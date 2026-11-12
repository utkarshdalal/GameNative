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
                m["layout"] = JsonObject(lm)
            }
            return JsonObject(m)
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
            )
            return spec.takeIf { !it.isEmpty }
        }

        private fun gradientJson(g: ThemeGradient): JsonObject = JsonObject(
            linkedMapOf(
                "angle" to JsonPrimitive(g.angleDeg),
                "colors" to JsonArray(g.colors.map { JsonPrimitive(argbHex(it)) }),
                "stops" to JsonArray(g.stops.map { JsonPrimitive(it) }),
            ),
        )

        private fun parseGradient(e: JsonElement?): ThemeGradient? {
            val o = e as? JsonObject ?: return null
            val angle = o.float("angle") ?: 180f
            val colors = (o["colors"] as? JsonArray)?.map { parseArgb((it as? JsonPrimitive)?.contentOrNull) ?: return null } ?: return null
            if (colors.isEmpty()) return null
            val stops = (o["stops"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat()?.coerceIn(0f, 1f) } ?: emptyList()
            return ThemeGradient(angle, colors, if (stops.size == colors.size) stops else emptyList())
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
