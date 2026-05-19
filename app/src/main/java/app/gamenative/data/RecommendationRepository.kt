package app.gamenative.data

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

object RecommendationRepository {

    private const val API_URL = "https://api.gamenative.app/api/games/recommendation"
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getCurrentRecommendation(context: Context): RecommendedGame? =
        withContext(Dispatchers.IO) {
            val cached = loadCached()
            val cacheAgeMs = System.currentTimeMillis() - PrefManager.recommendationCacheTimestamp
            val cacheFresh = cached != null && cacheAgeMs in 0..CACHE_TTL_MS

            if (cacheFresh) {
                return@withContext cached
            }

            val fetched = fetchRemote()
            if (fetched != null) {
                return@withContext fetched
            }

            cached ?: loadBundledFallback(context)
        }

    private fun fetchRemote(): RecommendedGame? {
        return try {
            val mediaType = "application/json".toMediaType()
            val body = "{}".toRequestBody(mediaType)
            val request = Request.Builder()
                .url(API_URL)
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseBody = response.body?.string() ?: return null
                val game = parseRecommendation(responseBody) ?: return null
                PrefManager.recommendationCacheJson = responseBody
                PrefManager.recommendationCacheTimestamp = System.currentTimeMillis()
                game
            }
        } catch (e: Exception) {
            Timber.tag("RecommendationRepo").d(e, "Remote recommendation fetch failed, will try fallback")
            null
        }
    }

    private fun loadCached(): RecommendedGame? {
        val cached = PrefManager.recommendationCacheJson
        if (cached.isEmpty()) return null
        return try {
            parseRecommendation(cached)
        } catch (e: Exception) {
            Timber.tag("RecommendationRepo").d(e, "Failed to parse cached recommendation")
            null
        }
    }

    private fun parseRecommendation(body: String): RecommendedGame? {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("[")) {
            json.decodeFromString<List<RecommendedGame>>(body).firstOrNull()
        } else {
            json.decodeFromString<RecommendedGame>(body)
        }
    }

    private fun loadBundledFallback(context: Context): RecommendedGame? {
        return try {
            val body = context.assets.open("recommendations.json").bufferedReader().use { it.readText() }
            val list = json.decodeFromString<List<RecommendedGame>>(body)
            list.firstOrNull()
        } catch (e: Exception) {
            Timber.tag("RecommendationRepo").d(e, "Bundled recommendation fallback unavailable")
            null
        }
    }
}
