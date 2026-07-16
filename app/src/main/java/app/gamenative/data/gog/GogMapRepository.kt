package app.gamenative.data.gog

import android.content.Context
import app.gamenative.utils.Net
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import timber.log.Timber

/**
 * Downloads and caches the precomputed GOG mapping (Steam appid / Epic namespace / normalized
 * title -> GOG game) and resolves owned games to GOG entries. The blob lives on R2 as a gzip;
 * the same normalization used by the build script is mirrored here so the title index matches.
 */
object GogMapRepository {

    private const val MAP_URL = "https://downloads.gamenative.app/gog-map.json.gz"
    private const val CACHE_FILE = "gog-map.json"
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: GogMap? = null

    private val roman = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7",
        "viii" to "8", "ix" to "9", "xi" to "11", "xii" to "12", "xiii" to "13",
    )

    fun normalizeTitle(title: String): String {
        var s = title.lowercase()
        s = s.replace(Regex("[™®©]"), "")
        s = s.replace("&", " and ")
        s = s.replace(Regex("[^a-z0-9]+"), " ")
        s = s.replace(
            Regex("\\b(goty|game of the year|complete|definitive|enhanced|deluxe|ultimate|remastered|collection|bundle)\\b"),
            "",
        )
        s = s.replace(Regex("\\bedition\\b"), "")
        s = s.split(" ").joinToString(" ") { roman[it] ?: it }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    suspend fun getMap(context: Context): GogMap? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }

        val file = File(context.filesDir, CACHE_FILE)
        val fresh = file.exists() && (System.currentTimeMillis() - file.lastModified()) in 0..CACHE_TTL_MS
        if (fresh) {
            parse(file.readText())?.let { cached = it; return@withContext it }
        }

        download(file)?.let { cached = it; return@withContext it }

        // Stale cache is better than nothing if the network is down.
        if (file.exists()) {
            parse(file.readText())?.let { cached = it; return@withContext it }
        }
        null
    }

    fun steamGogId(map: GogMap, appId: Int): String? = map.steam[appId.toString()]

    fun epicGogId(map: GogMap, namespace: String): String? = map.epic[namespace]

    fun titleGogId(map: GogMap, name: String): String? = map.title[normalizeTitle(name)]

    fun cachedMap(): GogMap? = cached

    @Volatile
    private var steamReverse: Map<String, String>? = null

    fun steamAppIdForGog(gogId: String): String? {
        val reverse = steamReverse
            ?: cached?.steam?.entries?.associate { (appId, gid) -> gid to appId }?.also { steamReverse = it }
            ?: return null
        return reverse[gogId]
    }

    private fun download(file: File): GogMap? {
        return try {
            val request = Request.Builder().url(MAP_URL).build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.bytes() ?: return null
                val text = if (raw.size >= 2 && (raw[0].toInt() and 0xff) == 0x1f && (raw[1].toInt() and 0xff) == 0x8b) {
                    GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8)
                } else {
                    String(raw, Charsets.UTF_8)
                }
                val map = parse(text) ?: return null
                file.writeText(text)
                map
            }
        } catch (e: Exception) {
            Timber.tag("GogMap").w(e, "GOG map download failed")
            null
        }
    }

    private fun parse(text: String): GogMap? = try {
        json.decodeFromString<GogMap>(text)
    } catch (e: Exception) {
        Timber.tag("GogMap").w(e, "GOG map parse failed")
        null
    }
}
