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
 * Parsed/Formartted details returned by GOGApiClient.
 */
data class ParsedGogGame(
    val id: String,
    val title: String,
    val slug: String,
    val imageUrl: String,
    val iconUrl: String,
    val developer: String,
    val publisher: String,
    val genres: List<String>,
    val languages: List<String>,
    val description: String,
    val releaseDate: String,
    val downloadSize: Long,
    val isSecret: Boolean,
    val isDlc: Boolean
)

/**
 * Raw API Response details from gameDetails endpoint (Used for reference)
 */
data class RawGogApiResponse(
    val id: String?,
    val title: String?,
    val slug: String?,
    val images: Images?,
    val developers: List<Developer>?,
    val publisher: Any?, // Can be object with name or plain string
    val genres: List<Genre>?,
    val languages: Map<String, String>?, // Language code -> Language name
    val description: Description?,
    val release_date: String?,
    val downloads: Downloads?
) {
    data class Images(
        val logo2x: String?,
        val logo: String?,
        val icon: String?
    )

    data class Developer(
        val name: String?
    )

    data class Genre(
        val name: String?
    )

    data class Description(
        val lead: String?
    )

    data class Downloads(
        val installers: List<Installer>?
    )

    data class Installer(
        val id: String?,
        val name: String?,
        val os: String?,
        val language: String?,
        val total_size: Long?
    )
}

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
     * - Calls GOG_EMBED/user/data/games endpoint to get Ids
     * - Returns list of owned game IDs
     *
     * @param context Application context for auth access
     * @return Result containing list of game IDs or error
     */
    suspend fun getGameIds(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").d("Fetching GOG game IDs...")

            // Get credentials from AuthManager
            val credentialsResult = GOGAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                val error = credentialsResult.exceptionOrNull()
                Timber.tag("GOG").e(error, "Cannot list games: not authenticated")
                return@withContext Result.failure(Exception("Not authenticated. Please log in first."))
            }

            val credentials = credentialsResult.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty()) {
                Timber.tag("GOG").e("No valid access token found")
                return@withContext Result.failure(Exception("No valid credentials found"))
            }


            val url = "${GOGConstants.GOG_EMBED_URL}/user/data/games"
            val request = Request.Builder() // Returns an "owned" key with an array of ints.
                .url(url)
                .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                .addHeader("User-Agent", "GameNative/1.0")
                .get()
                .build()

            Timber.tag("GOG").d("Requesting game IDs from: $url")

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
                    ownedGames.get(it).toString()
                }

                Timber.tag("GOG").i("Successfully fetched ${gameIds.size} game IDs")
                Timber.tag("GOG").d("First 10 game IDs: ${gameIds.take(10).joinToString()}")
                return@withContext Result.success(gameIds)
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception fetching game IDs: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Fetch detailed information for a specific game by ID
     *
     * - Gets credentials from AuthManager
     * - Calls GOG_API/products/{id} endpoint to get gameInfo
     * - Returns game details as ParsedGogGame
     *
     * @param context Application context for auth access
     * @param gameId The GOG game ID
     * @param expanded List of fields to expand (defaults to downloads, description, screenshots)
     * @return Result containing ParsedGogGame with transformed details or error
     */
    suspend fun getGameById(
        context: Context,
        gameId: String,
        expanded: List<String> = listOf("downloads", "description", "screenshots")
    ): Result<ParsedGogGame> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").d("Fetching game details for gameId: $gameId")

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

            Timber.tag("GOG").d("Requesting game details from: $url")

            // Execute request
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.tag("GOG").e("Failed to fetch game details for $gameId: HTTP ${response.code} - $errorBody")
                    return@withContext Result.failure(
                        Exception("Failed to fetch game details: HTTP ${response.code}")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                if (responseBody.isBlank()) {
                    Timber.tag("GOG").w("Empty response when fetching game details for $gameId")
                    return@withContext Result.failure(Exception("Empty response from GOG"))
                }

                // Parse raw GOG API response
                val rawApiResponse = JSONObject(responseBody)

                // Transform to simplified, flattened structure
                val transformedResponse = transformGameDetails(rawApiResponse, gameId)

                return@withContext Result.success(transformedResponse)
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Exception fetching game details for $gameId: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

        /**
     * Fetch client secret from GOG build metadata API
     * @param gameId GOG game ID
     * @param installPath Game install path (for platform detection, defaults to "windows")
     * @return Client secret string, or null if not found
     */
    suspend fun getClientSecret(context: Context, gameId: String, installPath: String?): String? = withContext(Dispatchers.IO) {
        try {
            val platform = "windows" // For now, assume Windows (proton)
            val buildsUrl = "https://content-system.gog.com/products/$gameId/os/$platform/builds?generation=2"

            Timber.tag("GOG").d("[Cloud Saves] Fetching build metadata from: $buildsUrl")

            // Get credentials for API authentication
            val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
            if (credentials == null) {
                Timber.tag("GOG").w("[Cloud Saves] No credentials available for build metadata fetch")
                return@withContext null
            }

            val request = Request.Builder()
                .url(buildsUrl)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()

            // Fetch the builds list and extract manifest link
            val manifestLink = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("GOG").w("[Cloud Saves] Build metadata fetch failed: ${response.code}")
                    return@withContext null
                }

                val jsonStr = response.body?.string() ?: ""
                val buildsJson = JSONObject(jsonStr)

                // Get first build
                val items = buildsJson.optJSONArray("items")
                if (items == null || items.length() == 0) {
                    Timber.tag("GOG").w("[Cloud Saves] No builds found for game $gameId")
                    return@withContext null
                }

                val firstBuild = items.getJSONObject(0)
                val link = firstBuild.optString("link", "")
                if (link.isEmpty()) {
                    Timber.tag("GOG").w("[Cloud Saves] No manifest link in first build")
                    return@withContext null
                }

                Timber.tag("GOG").d("[Cloud Saves] Fetching build manifest from: $link")
                link
            }

            // Fetch the build manifest
            val manifestRequest = Request.Builder()
                .url(manifestLink)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()

            httpClient.newCall(manifestRequest).execute().use { manifestResponse ->
                if (!manifestResponse.isSuccessful) {
                    Timber.tag("GOG").w("[Cloud Saves] Manifest fetch failed: ${manifestResponse.code}")
                    return@withContext null
                }

                // Log response headers to debug compression
                val contentEncoding = manifestResponse.header("Content-Encoding")
                val contentType = manifestResponse.header("Content-Type")
                Timber.tag("GOG").d("[Cloud Saves] Response headers - Content-Encoding: $contentEncoding, Content-Type: $contentType")

                // Read the response bytes (can only read body once)
                val manifestBytes = manifestResponse.body?.bytes() ?: return@withContext null

                // Check compression type by magic bytes
                val isGzipped = manifestBytes.size >= 2 &&
                                manifestBytes[0] == 0x1f.toByte() &&
                                manifestBytes[1] == 0x8b.toByte()

                val isZlib = manifestBytes.size >= 2 &&
                             manifestBytes[0] == 0x78.toByte() &&
                             (manifestBytes[1] == 0x9c.toByte() ||
                              manifestBytes[1] == 0xda.toByte() ||
                              manifestBytes[1] == 0x01.toByte())

                Timber.tag("GOG").d("[Cloud Saves] Manifest bytes: ${manifestBytes.size}, isGzipped: $isGzipped, isZlib: $isZlib")

                // Decompress based on detected format
                val manifestStr = when {
                    isGzipped -> {
                        try {
                            Timber.tag("GOG").d("[Cloud Saves] Decompressing gzip manifest")
                            val gzipStream = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(manifestBytes))
                            gzipStream.bufferedReader().use { it.readText() }
                        } catch (e: Exception) {
                            Timber.tag("GOG").e(e, "[Cloud Saves] Gzip decompression failed")
                            return@withContext null
                        }
                    }
                    isZlib -> {
                        try {
                            Timber.tag("GOG").d("[Cloud Saves] Decompressing zlib manifest")
                            val inflaterStream = java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(manifestBytes))
                            inflaterStream.bufferedReader().use { it.readText() }
                        } catch (e: Exception) {
                            Timber.tag("GOG").e(e, "[Cloud Saves] Zlib decompression failed")
                            return@withContext null
                        }
                    }
                    else -> {
                        // Not compressed, read as plain text
                        Timber.tag("GOG").d("[Cloud Saves] Not compressed, reading as UTF-8")
                        String(manifestBytes, Charsets.UTF_8)
                    }
                }

                if (manifestStr.isEmpty()) {
                    Timber.tag("GOG").w("[Cloud Saves] Empty manifest response")
                    return@withContext null
                }

                Timber.tag("GOG").d("[Cloud Saves] Parsing manifest JSON (${manifestStr.take(100)}...)")
                val manifestJson = JSONObject(manifestStr)

                // Extract clientSecret from manifest
                val clientSecret = manifestJson.optString("clientSecret", "")
                if (clientSecret.isEmpty()) {
                    Timber.tag("GOG").w("[Cloud Saves] No clientSecret in manifest for game $gameId")
                    return@withContext null
                }

                Timber.tag("GOG").d("[Cloud Saves] Successfully retrieved clientSecret for game $gameId")
                return@withContext clientSecret
            }

        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "[Cloud Saves] Failed to get clientSecret for game $gameId")
            return@withContext null
        }
    }

    /**
     * Transform raw GOG API response into better format. Based on GOGDL implementation
     *
     * @param rawResponse Raw JSON from GOG API
     * @param gameId The game ID
     * @return ParsedGogGame with simplified structure
     */
    private fun transformGameDetails(rawResponse: JSONObject, gameId: String): ParsedGogGame {
        // Extract image URLs and add https: protocol if missing
        val images = rawResponse.optJSONObject("images")
        var logo2x = images?.optString("logo2x", "") ?: ""
        var logo = images?.optString("logo", "") ?: ""
        var icon = images?.optString("icon", "") ?: ""

        if (logo2x.startsWith("//")) logo2x = "https:$logo2x"
        if (logo.startsWith("//")) logo = "https:$logo"
        if (icon.startsWith("//")) icon = "https:$icon"

        val imageUrl = logo2x.ifEmpty { logo }

        // Extract developer (first from array)
        val developers = rawResponse.optJSONArray("developers")
        val developer = if (developers != null && developers.length() > 0) {
            developers.optJSONObject(0)?.optString("name", "") ?: ""
        } else {
            ""
        }

        // Extract publisher (can be object or string)
        val publisherObj = rawResponse.opt("publisher")
        val publisher = when (publisherObj) {
            is JSONObject -> publisherObj.optString("name", "")
            is String -> publisherObj
            else -> ""
        }

        // Extract genres (array of objects with name field)
        val genresArray = rawResponse.optJSONArray("genres")
        val genres = mutableListOf<String>()
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val genreObj = genresArray.opt(i)
                val genreName = when (genreObj) {
                    is JSONObject -> genreObj.optString("name", "")
                    is String -> genreObj
                    else -> ""
                }
                if (genreName.isNotEmpty()) {
                    genres.add(genreName)
                }
            }
        }

        // Extract language codes (keys from object)
        val languages = mutableListOf<String>()
        val langObj = rawResponse.optJSONObject("languages")
        if (langObj != null) {
            val keys = langObj.keys()
            while (keys.hasNext()) {
                languages.add(keys.next())
            }
        }

        // Extract description from nested structure
        val descriptionObj = rawResponse.opt("description")
        val description = when (descriptionObj) {
            is JSONObject -> descriptionObj.optString("lead", "")
            is String -> descriptionObj
            else -> ""
        }

        // Extract download size from first installer
        val downloads = rawResponse.optJSONObject("downloads")
        // Used in GOG Galaxy to hide specific entitlements
        val isSecret = rawResponse.optBoolean("is_secret", false)
        val gameType = rawResponse.optString("game_type", "dlc")
        val isDlc = gameType == "dlc"

        val installers = downloads?.optJSONArray("installers")
        val downloadSize = if (installers != null && installers.length() > 0) {
            installers.optJSONObject(0)?.optLong("total_size", 0L) ?: 0L
        } else {
            0L
        }

        // Return data class matching GOGDL format
        return ParsedGogGame(
            id = gameId,
            title = rawResponse.optString("title", "Unknown"),
            slug = rawResponse.optString("slug", ""),
            imageUrl = imageUrl,
            iconUrl = icon,
            developer = developer,
            publisher = publisher,
            genres = genres,
            languages = languages,
            description = description,
            releaseDate = rawResponse.optString("release_date", ""),
            downloadSize = downloadSize,
            isSecret = isSecret,
            isDlc = isDlc
        )
    }
}
