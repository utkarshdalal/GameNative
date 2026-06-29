package app.gamenative.utils

import app.gamenative.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]")
private val WHITESPACE = Regex("\\s+")
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private fun normalizedKey(input: String) =
    input.lowercase().replace(NON_ALPHANUMERIC, " ").replace(WHITESPACE, " ").trim()

/**
 * Fetches HowLongToBeat completion time stats for a game.
 *
 * Flow (ported from https://github.com/morwy/hltb-for-deck):
 *  1. GET /api/bleed/init → auth tokens (token, hpKey, hpVal)
 *  2. POST /api/bleed with auth headers + body → search results contain all comp times
 *
 * HLTB's CDN rejects HTTP/2 for this endpoint, so requests use the shared app client forced to HTTP/1.1.
 * Stats are cached for 12 hours.
 */
object HltbService {

    private const val DEFAULT_API_BASE_URL = "https://howlongtobeat.com"
    private const val SEARCH_PATH = "/api/bleed"
    private const val INIT_PATH = "$SEARCH_PATH/init"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36"
    const val GAME_URL = "https://howlongtobeat.com/game/"
    const val UNKNOWN_HOURS = "--"

    @Serializable
    data class Stats(
        val mainHours: String,
        val mainPlusHours: String,
        val completeHours: String,
        val allStylesHours: String,
        val gameId: Int = 0,
    )

    private data class Auth(val token: String, val hpKey: String, val hpVal: String)

    private sealed class SearchResult {
        data class Found(val stats: Stats) : SearchResult()
        object NoMatch : SearchResult()
        object AuthRejected : SearchResult()
    }

    @Volatile private var auth: Auth? = null
    @Volatile private var apiBaseUrl = DEFAULT_API_BASE_URL

    private val httpClient = Net.http.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /** Fetch auth tokens from the HLTB init endpoint. */
    private suspend fun fetchAuth(): Auth? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$apiBaseUrl$INIT_PATH?t=${System.currentTimeMillis()}")
                .header("Origin", apiBaseUrl).header("Referer", "$apiBaseUrl/").header("User-Agent", UA).build()
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseAuth(response.body?.string() ?: return@withContext null)?.also { auth = it }
            }
        } catch (e: Exception) { Timber.tag("HLTB").e(e, "fetchAuth"); null }
    }

    /** POST the HLTB search API, returning the best-matching game's stats. */
    private suspend fun search(name: String, a: Auth): SearchResult = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(buildSearchRequest(name, a)).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("HLTB").w("Search HTTP ${response.code} for '$name'")
                    if (response.code == 401 || response.code == 403) {
                        auth = null
                        return@withContext SearchResult.AuthRejected
                    }
                    return@withContext SearchResult.NoMatch
                }

                parseSearchResponse(name, response.body?.string() ?: return@withContext SearchResult.NoMatch)
                    ?.let(SearchResult::Found)
                    ?: SearchResult.NoMatch
            }
        } catch (e: Exception) {
            Timber.tag("HLTB").e(e, "search '$name'")
            SearchResult.NoMatch
        }
    }

    /** Public entry — cache-first, with one auth retry on failure. */
    suspend fun getStats(name: String): Stats? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        HltbCache.get(name)?.let { return@withContext it }
        val cachedAuth = auth ?: fetchAuth() ?: return@withContext null
        val stats = when (val firstAttempt = search(name, cachedAuth)) {
            is SearchResult.Found -> firstAttempt.stats
            SearchResult.NoMatch -> return@withContext null
            SearchResult.AuthRejected -> {
                val refreshedAuth = fetchAuth() ?: return@withContext null
                when (val secondAttempt = search(name, refreshedAuth)) {
                    is SearchResult.Found -> secondAttempt.stats
                    SearchResult.NoMatch,
                    SearchResult.AuthRejected,
                    -> return@withContext null
                }
            }
        }
        HltbCache.put(name, stats)
        stats
    }

    private fun parseAuth(body: String): Auth? {
        val data = JSONObject(body)
        val token = data.optString("token")
        var key = ""
        var value = ""
        for (field in data.keys()) {
            val normalizedField = field.lowercase()
            when {
                key.isEmpty() && normalizedField.contains("key") -> key = data.optString(field)
                value.isEmpty() && normalizedField.contains("val") -> value = data.optString(field)
            }
        }
        return if (token.isNotEmpty() && key.isNotEmpty() && value.isNotEmpty()) Auth(token, key, value) else null
    }

    private fun buildSearchRequest(name: String, auth: Auth): Request {
        val searchTerms = normalize(name).split(" ").filter { it.isNotBlank() }
        val body = JSONObject().apply {
            put("searchType", "games")
            put("searchTerms", JSONArray(searchTerms))
            put("searchPage", 1)
            put("size", 20)
            put("searchOptions", JSONObject().apply {
                put("games", JSONObject().apply {
                    put("userId", 0)
                    put("platform", "")
                    put("sortCategory", "name")
                    put("rangeCategory", "main")
                    put("modifier", "hide_dlc")
                    put("rangeTime", JSONObject().apply {
                        put("min", 0)
                        put("max", 0)
                    })
                    put("rangeYear", JSONObject().apply {
                        put("min", "")
                        put("max", "")
                    })
                    put("gameplay", JSONObject().apply {
                        put("perspective", "")
                        put("flow", "")
                        put("genre", "")
                        put("difficulty", "")
                    })
                })
                put("users", JSONObject())
                put("lists", JSONObject())
                put("filter", "")
                put("sort", 0)
                put("randomizer", 0)
            })
            put(auth.hpKey, auth.hpVal)
        }.toString()

        return Request.Builder()
            .url("$apiBaseUrl$SEARCH_PATH")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Origin", apiBaseUrl)
            .header("Referer", "$apiBaseUrl/")
            .header("User-Agent", UA)
            .header("x-auth-token", auth.token)
            .header("x-hp-key", auth.hpKey)
            .header("x-hp-val", auth.hpVal)
            .build()
    }

    private fun parseSearchResponse(name: String, body: String): Stats? {
        val data = JSONObject(body).optJSONArray("data") ?: return null
        if (data.length() == 0) {
            Timber.tag("HLTB").i("No results for '$name'")
            return null
        }

        val bestMatch = findBestMatch(name, data) ?: run {
            Timber.tag("HLTB").i("No acceptable match for '$name'")
            return null
        }
        if (!bestMatch.hasCompletionData()) {
            Timber.tag("HLTB").i("Match for '$name' has no completion data: '${bestMatch.optString("game_name")}'")
            return null
        }

        Timber.tag("HLTB").i("'$name' -> '${bestMatch.optString("game_name")}' main=${bestMatch.optLong("comp_main")}s")
        return Stats(
            mainHours = formatHours(bestMatch.optLong("comp_main")),
            mainPlusHours = formatHours(bestMatch.optLong("comp_plus")),
            completeHours = formatHours(bestMatch.optLong("comp_100")),
            allStylesHours = formatHours(bestMatch.optLong("comp_all")),
            gameId = bestMatch.optInt("game_id", 0),
        )
    }

    private fun findBestMatch(name: String, data: JSONArray): JSONObject? {
        val normalizedName = normalize(name)
        var bestMatch = data.getJSONObject(0)
        var bestDistance = Int.MAX_VALUE
        var bestNormalizedName = normalize(bestMatch.optString("game_name"))
        for (index in 0 until data.length()) {
            val candidate = data.getJSONObject(index)
            val candidateName = normalize(candidate.optString("game_name"))
            val distance = levenshtein(normalizedName, candidateName)
            if (distance < bestDistance) {
                bestDistance = distance
                bestMatch = candidate
                bestNormalizedName = candidateName
            }
            if (distance == 0) break
        }

        return bestMatch.takeIf { isAcceptableMatch(normalizedName, bestNormalizedName, bestDistance) }
    }

    private fun JSONObject.hasCompletionData() =
        listOf("comp_main", "comp_plus", "comp_100", "comp_all").any { optLong(it) > 0L }

    private fun isAcceptableMatch(query: String, candidate: String, distance: Int): Boolean {
        if (query.isBlank() || candidate.isBlank()) return false
        if (query == candidate) return true
        if (candidate.startsWith("$query ") || candidate.startsWith("$query:")) return true
        if (candidate.contains(" $query ")) return true
        val distanceThreshold = maxOf(3, query.length / 2)
        return distance <= distanceThreshold
    }

    internal fun formatHours(seconds: Long) = if (seconds <= 0) UNKNOWN_HOURS else "%.1f".format(seconds / 3600.0)
    internal fun normalize(input: String) = normalizedKey(input)
    internal fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        val dp = Array(left.length + 1) { IntArray(right.length + 1) }
        for (leftIndex in 0..left.length) dp[leftIndex][0] = leftIndex
        for (rightIndex in 0..right.length) dp[0][rightIndex] = rightIndex
        for (leftIndex in 1..left.length) for (rightIndex in 1..right.length)
            dp[leftIndex][rightIndex] = minOf(
                dp[leftIndex - 1][rightIndex] + 1,
                dp[leftIndex][rightIndex - 1] + 1,
                dp[leftIndex - 1][rightIndex - 1] +
                    (if (left[leftIndex - 1] == right[rightIndex - 1]) 0 else 1),
            )
        return dp[left.length][right.length]
    }

    internal fun setApiBaseUrlForTesting(baseUrl: String) {
        apiBaseUrl = baseUrl.trimEnd('/')
        auth = null
    }

    internal fun resetForTesting() {
        apiBaseUrl = DEFAULT_API_BASE_URL
        auth = null
    }
}

/** In-memory + DataStore cache for HLTB stats (12-hour TTL, max 200 entries). */
object HltbCache {
    private const val TTL = 12 * 3_600_000L
    internal const val MAX_ENTRIES = 200
    private val mem = mutableMapOf<String, HltbService.Stats>()
    private val stamps = mutableMapOf<String, Long>()
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable data class Entry(val stats: HltbService.Stats, val ts: Long)

    @Synchronized
    private fun load() {
        if (loaded) return
        try {
            val raw = PrefManager.hltbCache
            if (raw != "{}") {
                val now = System.currentTimeMillis()
                json.decodeFromString<Map<String, Entry>>(raw)
                    .asSequence()
                    .filter { (_, entry) -> now - entry.ts < TTL }
                    .sortedByDescending { (_, entry) -> entry.ts }
                    .take(MAX_ENTRIES)
                    .forEach { (key, entry) ->
                        mem[key] = entry.stats
                        stamps[key] = entry.ts
                    }
            }
        } catch (_: Exception) {
        } finally {
            loaded = true
        }
    }

    @Synchronized
    private fun save() {
        try {
            val now = System.currentTimeMillis()
            PrefManager.hltbCache = json.encodeToString(mem.mapValues { Entry(it.value, stamps[it.key] ?: now) })
        } catch (_: Exception) {}
    }

    @Synchronized
    fun get(name: String): HltbService.Stats? {
        load()
        val k = key(name)
        val ts = stamps[k] ?: return null
        if (System.currentTimeMillis() - ts >= TTL) { mem.remove(k); stamps.remove(k); return null }
        return mem[k]
    }

    @Synchronized
    fun put(name: String, stats: HltbService.Stats) {
        load()
        val k = key(name)
        if (mem.size >= MAX_ENTRIES && !mem.containsKey(k)) {
            // Evict the oldest entry to stay within memory budget
            stamps.minByOrNull { it.value }?.key?.let { oldest -> mem.remove(oldest); stamps.remove(oldest) }
        }
        mem[k] = stats
        stamps[k] = System.currentTimeMillis()
        save()
    }

    internal fun key(name: String) = normalizedKey(name)

    /** Reset state — for testing only. */
    @Synchronized
    internal fun reset() { mem.clear(); stamps.clear(); loaded = false }
}
