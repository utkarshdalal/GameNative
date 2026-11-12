package app.gamenative.theme

import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** DeckThemes store API: theme list, theme records, zip blobs and the class name map. */
object ThemeStoreClient {

    private const val API = "https://api.deckthemes.com"
    private const val USER_AGENT = "GameNative"
    const val MAX_ZIP_BYTES = 100L * 1024 * 1024
    private const val PAGE_SIZE = 20

    private fun get(url: String): Request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    private fun getText(url: String): String =
        Net.http.newCall(get(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: throw IOException("Empty response for $url")
        }

    /** [filter] is the store's package type: `CSS` for themes, `AUDIO` for sound and music packs. */
    fun list(query: String, page: Int, filter: String = "CSS"): List<StoreTheme> {
        val url = "$API/themes".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("perPage", PAGE_SIZE.toString())
            .addQueryParameter("filters", filter)
            .addQueryParameter("order", "Most Downloaded")
            .apply { if (query.isNotBlank()) addQueryParameter("search", query.trim()) }
            .build()
            .toString()
        return parseList(getText(url))
    }

    fun parseList(json: String): List<StoreTheme> {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        return (root["items"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseTheme) } ?: emptyList()
    }

    /** Theme record with its store-listed dependencies. */
    fun details(id: String): Pair<StoreTheme, List<StoreTheme>>? {
        val obj = Json.parseToJsonElement(getText("$API/themes/${encode(id)}")) as? JsonObject ?: return null
        val theme = parseTheme(obj) ?: return null
        val deps = (obj["dependencies"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseTheme) } ?: emptyList()
        return theme to deps
    }

    /** Downloads a zip blob into [dest]. */
    fun downloadBlob(blobId: String, dest: File) {
        Net.http.newCall(get("$API/blobs/${encode(blobId)}")).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading theme")
            val body = response.body ?: throw IOException("Empty download")
            var total = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ZIP_BYTES) throw IOException("Theme download is too large")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** DeckThemes' class name map (`stable.json`), reduced to the names the role table uses. */
    fun fetchClassMap(): SteamClassMap = SteamClassMap.reduceStableJson(getText("$API/stable.json"))

    private fun encode(s: String): String = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun parseTheme(o: JsonObject): StoreTheme? {
        val id = o.str("id") ?: return null
        val name = o.str("displayName") ?: o.str("name") ?: return null
        val author = (o["author"] as? JsonObject)?.str("username") ?: o.str("specifiedAuthor")
        val download = o["download"] as? JsonObject
        val image = (o["images"] as? JsonArray)?.firstOrNull() as? JsonObject
        return StoreTheme(
            id = id,
            name = name,
            author = author,
            version = o.str("version"),
            description = o.str("description"),
            target = o.str("target"),
            downloads = download?.get("downloadCount")?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() } ?: 0,
            previewImageUrl = image?.str("id")?.let { "$API/blobs/$it" },
            downloadBlobId = download?.str("id"),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
