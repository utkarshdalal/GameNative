package app.gamenative.data

import android.content.Context
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import timber.log.Timber

object RecommendationRepository {

    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/utkarshdalal/GameNative/refs/heads/master/recommendations.json"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getCurrentRecommendation(context: Context): RecommendedGame? =
        withContext(Dispatchers.IO) {
            fetchRemote() ?: loadBundledFallback(context)
        }

    private fun fetchRemote(): RecommendedGame? {
        return try {
            val request = Request.Builder().url(REMOTE_URL).build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val list = json.decodeFromString<List<RecommendedGame>>(body)
                list.firstOrNull()
            }
        } catch (e: Exception) {
            Timber.tag("RecommendationRepo").d(e, "Remote recommendation fetch failed, will try fallback")
            null
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
