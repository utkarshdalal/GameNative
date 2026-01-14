package app.gamenative.utils

import android.content.Context
import androidx.compose.ui.graphics.Color
import app.gamenative.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Service for fetching game compatibility information from GameNative API.
 */
object GameCompatibilityService {
    private const val API_BASE_URL = "https://api.gamenative.app/api/game-runs"
    private const val TIMEOUT_SECONDS = 10L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Data class for API request.
     */
    data class GameCompatibilityRequest(
        val gameNames: List<String>,
        val gpuName: String
    )

    /**
     * Data class for API response per game.
     */
    data class GameCompatibilityResponse(
        val gameName: String,
        val totalPlayableCount: Int,
        val gpuPlayableCount: Int,
        val avgRating: Float,
        val hasBeenTried: Boolean,
        val isNotWorking: Boolean
    )

    /**
     * Compatibility message with text and color.
     */
    data class CompatibilityMessage(
        val text: String,
        val color: Color
    )

    /**
     * Gets user-friendly compatibility message based on compatibility response.
     * Uses totalPlayableCount and gpuPlayableCount to determine the message.
     */
    fun getCompatibilityMessageFromResponse(context: Context, response: GameCompatibilityResponse): CompatibilityMessage {
        return when {
            response.totalPlayableCount > 0 && response.gpuPlayableCount > 0 ->
                CompatibilityMessage(context.getString(R.string.best_config_exact_gpu_match), Color.Green)
            response.gpuPlayableCount == 0 && response.totalPlayableCount > 0 ->
                CompatibilityMessage(context.getString(R.string.best_config_fallback_match), Color.Yellow)
            response.isNotWorking ->
                CompatibilityMessage(context.getString(R.string.library_not_compatible), Color.Red)
            else ->
                CompatibilityMessage(context.getString(R.string.library_compatibility_unknown), Color.Gray)
        }
    }

    /**
     * Fetches compatibility information for a batch of games.
     * Returns a map of game name to compatibility response, or null on error.
     */
    suspend fun fetchCompatibility(
        gameNames: List<String>,
        gpuName: String
    ): Map<String, GameCompatibilityResponse>? = withContext(Dispatchers.IO) {
        if (gameNames.isEmpty()) {
            return@withContext emptyMap()
        }

        try {
            withTimeout(TIMEOUT_SECONDS * 1000) {
                val requestBody = JSONObject().apply {
                    put("gameNames", org.json.JSONArray(gameNames))
                    put("gpuName", gpuName)
                }

                val mediaType = "application/json".toMediaType()
                val body = requestBody.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(API_BASE_URL)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Timber.tag("GameCompatibilityService")
                        .w("API request failed - HTTP ${response.code}")
                    return@withTimeout null
                }

                val responseBody = response.body?.string() ?: return@withTimeout null
                val jsonResponse = JSONObject(responseBody)

                val result = mutableMapOf<String, GameCompatibilityResponse>()
                val keys = jsonResponse.keys()

                while (keys.hasNext()) {
                    val gameName = keys.next()
                    val gameData = jsonResponse.getJSONObject(gameName)

                    val compatibilityResponse = GameCompatibilityResponse(
                        gameName = gameName,
                        totalPlayableCount = gameData.optInt("totalPlayableCount", 0),
                        gpuPlayableCount = gameData.optInt("gpuPlayableCount", 0),
                        avgRating = gameData.optDouble("avgRating", 0.0).toFloat(),
                        hasBeenTried = gameData.optBoolean("hasBeenTried", false),
                        isNotWorking = gameData.optBoolean("isNotWorking", false)
                    )

                    result[gameName] = compatibilityResponse
                }

                Timber.tag("GameCompatibilityService")
                    .d("Fetched compatibility for ${result.size} games")
                result
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Timber.tag("GameCompatibilityService")
                .e(e, "Timeout while fetching compatibility data")
            null
        } catch (e: Exception) {
            Timber.tag("GameCompatibilityService")
                .e(e, "Error fetching compatibility data: ${e.message}")
            null
        }
    }
}

