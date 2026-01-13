package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.GOGGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Direct HTTP client for GOG API operations.
 * Uses GOGAuthManager for authentication tokens.
 */
object GOGApiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch list of game IDs owned by the user
     *
     * - Gets credentials from AuthManager
     * - Calls GOG_EMBED/user/data/games endpoint
     * - Returns list of owned game IDs
     *
     * @param context Application context for auth access
     * @return Result containing list of game IDs or error
     */
    suspend fun getGameIds(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Fetching GOG game IDs via direct HTTP call...")

            // Get credentials from AuthManager
            val credentialsResult = GOGAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                val error = credentialsResult.exceptionOrNull()
                Timber.e(error, "Cannot list games: not authenticated")
                return@withContext Result.failure(Exception("Not authenticated. Please log in first."))
            }

            val credentials = credentialsResult.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty()) {
                Timber.e("No valid access token found")
                return@withContext Result.failure(Exception("No valid credentials found"))
            }

            // Build request to GOG_EMBED/user/data/games
            val url = "${GOGConstants.GOG_EMBED_URL}/user/data/games"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                .addHeader("User-Agent", "GameNative/1.0")
                .get()
                .build()

            Timber.d("Requesting game IDs from: $url")

            // Execute request
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.e("Failed to fetch game IDs: HTTP ${response.code} - $errorBody")
                    return@withContext Result.failure(
                        Exception("Failed to fetch game IDs: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                if (responseBody.isBlank()) {
                    Timber.w("Empty response when fetching game IDs")
                    return@withContext Result.failure(Exception("Empty response from GOG"))
                }

                // Parse JSON response
                val userData = JSONObject(responseBody)
                val ownedGames = userData.optJSONArray("owned") ?: JSONArray()

                val gameIds = List(ownedGames.length()) {
                    ownedGames.getString(it)
                }

                Timber.i("Successfully fetched ${gameIds.size} game IDs")
                return@withContext Result.success(gameIds)
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching game IDs: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetch detailed information for a specific game by ID
     *
     * - Gets credentials from AuthManager
     * - Calls GOG_API/products/{id}?expand=... endpoint
     * - Returns game details as JSONObject
     *
     * @param context Application context for auth access
     * @param gameId The GOG game ID
     * @param expanded List of fields to expand (defaults to downloads, description, screenshots)
     * @return Result containing JSONObject with game details or error
     */
    suspend fun getGameById(
        context: Context,
        gameId: String,
        expanded: List<String> = listOf("downloads", "description", "screenshots")
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Fetching game details for gameId: $gameId")

            // Get credentials from AuthManager
            val credentialsResult = GOGAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                val error = credentialsResult.exceptionOrNull()
                Timber.e(error, "Cannot fetch game details: not authenticated")
                return@withContext Result.failure(Exception("Not authenticated"))
            }

            val credentials = credentialsResult.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty()) {
                Timber.e("No valid access token found")
                return@withContext Result.failure(Exception("No valid credentials found"))
            }

            // Build URL with expanded fields
            val expandedParam = if (expanded.isNotEmpty()) {
                "?expand=${expanded.joinToString(",")}"
            } else {
                ""
            }
            val url = "${GOGConstants.GOG_BASE_API_URL}/products/$gameId$expandedParam"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                .addHeader("User-Agent", "GameNative/1.0")
                .get()
                .build()

            Timber.d("Requesting game details from: $url")

            // Execute request
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.e("Failed to fetch game details for $gameId: HTTP ${response.code} - $errorBody")
                    return@withContext Result.failure(
                        Exception("Failed to fetch game details: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                if (responseBody.isBlank()) {
                    Timber.w("Empty response when fetching game details for $gameId")
                    return@withContext Result.failure(Exception("Empty response from GOG"))
                }

                // Parse JSON response
                val gameDetails = JSONObject(responseBody)

                Timber.i("Successfully fetched game details for $gameId")
                return@withContext Result.success(gameDetails)
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching game details for $gameId: ${e.message}")
            Result.failure(e)
        }
    }
}
