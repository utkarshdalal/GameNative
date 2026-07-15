package app.gamenative.data

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

object RecommendationRepository {

    private const val API_URL = "https://api.gamenative.app/api/games/hero"
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    // Latest featured from the most recent fetch. Kept in memory (not the disk cache) so the
    // featured decision is always live and never served stale from the daily recommendation cache.
    @Volatile private var lastFeatured: FeaturedItem? = null

    /**
     * Static recommendation + optional featured for the All-tab hero slot.
     * Always fetches so featured reflects the current campaign; the recommendation stays stable
     * for the day via its own cache; the cache is the offline fallback.
     */
    suspend fun getHero(context: Context): HeroResponse =
        withContext(Dispatchers.IO) {
            val fetched = fetchRemote()
            if (fetched != null) {
                lastFeatured = fetched.featured
                return@withContext HeroResponse(
                    recommendation = stableRecommendation(fetched.recommendation),
                    featured = fetched.featured,
                )
            }
            // Offline: last stable recommendation (or bundled), no featured.
            HeroResponse(
                recommendation = loadCachedRecommendation() ?: loadBundledFallback(context),
                featured = null,
            )
        }

    suspend fun getCurrentRecommendation(context: Context): RecommendedGame? =
        getHero(context).recommendation

    /** Latest featured (if any) — used by the detail screen; no network. */
    fun getCachedFeatured(): FeaturedItem? = lastFeatured

    fun getFeaturedGame(context: Context): RecommendedGame? =
        lastFeatured?.toRecommendedGame(context)

    private fun fetchRemote(): HeroResponse? {
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
                parseHero(responseBody)
            }
        } catch (e: Exception) {
            Timber.tag("RecommendationRepo").d(e, "Remote hero fetch failed, will try fallback")
            null
        }
    }

    /**
     * Keeps the recommendation stable for a day: reuses the cached pick until it goes stale,
     * then adopts (and caches) the freshly fetched one.
     */
    private fun stableRecommendation(fresh: RecommendedGame?): RecommendedGame? {
        val cached = loadCachedRecommendation()
        val cacheAgeMs = System.currentTimeMillis() - PrefManager.recommendationCacheTimestamp
        if (cached != null && cacheAgeMs in 0..CACHE_TTL_MS) return cached

        if (fresh != null) {
            PrefManager.recommendationCacheJson = json.encodeToString(fresh)
            PrefManager.recommendationCacheTimestamp = System.currentTimeMillis()
            return fresh
        }
        return cached
    }

    private fun loadCachedRecommendation(): RecommendedGame? {
        val cached = PrefManager.recommendationCacheJson
        if (cached.isEmpty()) return null
        return try {
            parseRecommendation(cached)
        } catch (e: Exception) {
            Timber.tag("RecommendationRepo").d(e, "Failed to parse cached recommendation")
            null
        }
    }

    private fun parseHero(body: String): HeroResponse? {
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("{") -> {
                val hero = runCatching { json.decodeFromString<HeroResponse>(body) }.getOrNull()
                if (hero != null && (hero.recommendation != null || hero.featured != null)) {
                    hero
                } else {
                    // Legacy: a single recommendation object.
                    runCatching { json.decodeFromString<RecommendedGame>(body) }
                        .getOrNull()
                        ?.let { HeroResponse(recommendation = it) }
                }
            }
            // Legacy: the old bare array response.
            trimmed.startsWith("[") ->
                json.decodeFromString<List<RecommendedGame>>(body).firstOrNull()
                    ?.let { HeroResponse(recommendation = it) }
            else -> null
        }
    }

    /** Cache holds a bare recommendation object; tolerate legacy hero/array shapes too. */
    private fun parseRecommendation(body: String): RecommendedGame? {
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("[") ->
                json.decodeFromString<List<RecommendedGame>>(body).firstOrNull()
            trimmed.startsWith("{") ->
                runCatching { json.decodeFromString<HeroResponse>(body).recommendation }.getOrNull()
                    ?: runCatching { json.decodeFromString<RecommendedGame>(body) }.getOrNull()
            else -> null
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
