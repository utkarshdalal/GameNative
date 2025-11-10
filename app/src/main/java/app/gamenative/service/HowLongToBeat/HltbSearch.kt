package app.gamenative.service.HowLongToBeat.howlongtobeat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Internal HTTP client for HowLongToBeat API
 */
internal class HltbSearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true  // Encode default values in JSON
    }

    @Serializable
    data class SearchRequest(
        val searchType: String = "games",
        val searchTerms: List<String>,
        val searchPage: Int = 1,
        val size: Int = 5,
        val searchOptions: SearchOptions = SearchOptions()
    )

    @Serializable
    data class SearchOptions(
        val games: GamesOptions = GamesOptions(),
        val users: UsersOptions = UsersOptions(),
        val filter: String = "",
        val sort: Int = 0,
        val randomizer: Int = 0
    )

    @Serializable
    data class GamesOptions(
        val userId: Int = 0,
        val platform: String = "",
        val sortCategory: String = "name",
        val rangeCategory: String = "main",
        val rangeTime: RangeTime = RangeTime(),
        val gameplay: Gameplay = Gameplay(),
        val rangeYear: RangeYear = RangeYear(),
        val modifier: String = "hide_dlc"
    )

    @Serializable
    data class RangeTime(
        val min: Int = 0,
        val max: Int = 0
    )

    @Serializable
    data class Gameplay(
        val perspective: String = "",
        val flow: String = "",
        val genre: String = ""
    )

    @Serializable
    data class RangeYear(
        val min: String = "",
        val max: String = ""
    )

    @Serializable
    data class UsersOptions(
        val sortCategory: String = "postcount"
    )

    @Serializable
    data class SearchResponse(
        val data: List<SearchResultEntry>,
        val count: Int
    )

    @Serializable
    data class SearchResultEntry(
        val game_id: Int,
        val game_name: String,
        val game_image: String,
        val comp_main: Long, // in seconds
        val comp_plus: Long, // in seconds
        val comp_100: Long,  // in seconds
        val profile_platform: String? = null
    )

    /**
     * Searches for games matching the given terms
     * @param searchTerms List of search terms
     * @return Search response with matching games
     */
    suspend fun search(searchTerms: List<String>): SearchResponse = withContext(Dispatchers.IO) {
        val requestData = SearchRequest(searchTerms = searchTerms)
        val requestBody = json.encodeToString(requestData)
            .toRequestBody(MEDIA_TYPE_JSON)

        val request = Request.Builder()
            .url(SEARCH_URL)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36")
            .addHeader("Referer", "https://howlongtobeat.com/")
            .addHeader("Origin", "https://howlongtobeat.com")
            .addHeader("Authority", "howlongtobeat.com")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HltbException("Search failed: ${response.code} - ${response.message}")
            }

            val body = response.body?.string()
                ?: throw HltbException("Empty response body")

            json.decodeFromString<SearchResponse>(body)
        }
    }

    /**
     * Fetches the detail HTML page for a game
     * @param gameId The HLTB game ID
     * @return HTML string of the detail page
     */
    suspend fun detailHtml(gameId: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$DETAIL_URL?id=$gameId")
            .get()
            .addHeader("User-Agent", "Chrome: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.3")
            .addHeader("Content-Type", "application/json")
            .addHeader("Origin", "https://howlongtobeat.com/")
            .addHeader("Referer", "https://howlongtobeat.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HltbException("Detail fetch failed: ${response.code}")
            }

            response.body?.string()
                ?: throw HltbException("Empty response body")
        }
    }

    companion object {
        const val BASE_URL = "https://howlongtobeat.com"
        const val SEARCH_URL = "$BASE_URL/api/search"
        const val DETAIL_URL = "$BASE_URL/game"
        const val IMAGE_URL = "$BASE_URL/games/"

        private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Exception thrown when HLTB API requests fail
 */
class HltbException(message: String) : Exception(message)
