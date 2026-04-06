package app.gamenative.utils

import android.graphics.BitmapFactory
import app.gamenative.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Utility class for fetching game images from SteamGridDB API.
 * Images are stored locally in the game folder to avoid repeated API calls.
 */
object SteamGridDB {
    private const val API_BASE_URL = "https://www.steamgriddb.com/api/v2"
    private const val SEARCH_ENDPOINT = "/search/autocomplete"
    private const val GAMES_STEAM_ENDPOINT = "/games/steam"
    private const val GRIDS_ENDPOINT = "/grids/game"
    private const val HEROES_ENDPOINT = "/heroes/game"
    private const val LOGOS_ENDPOINT = "/logos/game"
    private const val ICONS_ENDPOINT = "/icons/game"

    private val httpClient = Net.http

    private fun apiKeyOrNull(logMissing: Boolean = false): String? {
        val apiKey = app.gamenative.BuildConfig.STEAMGRIDDB_API_KEY.takeIf { it.isNotEmpty() }
        if (apiKey == null && logMissing) {
            Timber.tag("SteamGridDB").i("SteamGridDB API key not configured")
        }
        return apiKey
    }

    private fun extensionFromUrl(url: String): String = when {
        url.endsWith(".png", ignoreCase = true) -> ".png"
        url.endsWith(".jpg", ignoreCase = true) -> ".jpg"
        url.endsWith(".jpeg", ignoreCase = true) -> ".jpg"
        url.endsWith(".webp", ignoreCase = true) -> ".webp"
        else -> ".png"
    }

    private fun authorizedRequest(url: String, apiKey: String): Request =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

    private fun basicRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .build()

    private fun fetchJson(url: String, apiKey: String, errorLabel: String): JSONObject? {
        val response = httpClient.newCall(authorizedRequest(url, apiKey)).execute()
        response.use {
            if (!it.isSuccessful) {
                Timber.tag("SteamGridDB").w("$errorLabel - HTTP ${it.code}")
                return null
            }
            val body = it.body?.string() ?: return null
            return JSONObject(body)
        }
    }

    private fun downloadImageBytes(url: String, errorLabel: String): ByteArray? {
        val response = httpClient.newCall(basicRequest(url)).execute()
        response.use {
            if (!it.isSuccessful) {
                Timber.tag("SteamGridDB").w("$errorLabel - HTTP ${it.code}")
                return null
            }
            return it.body?.bytes()
        }
    }

    /**
     * Get the SteamGridDB API key from BuildConfig.
     * The key should be added to local.properties as: STEAMGRIDDB_API_KEY=YOUR_API_KEY
     * Or set as an environment variable: STEAMGRIDDB_API_KEY=YOUR_API_KEY
     * Returns null if the key is not configured.
     */
    private fun getApiKey(): String? {
        return apiKeyOrNull()
    }

    /**
     * Search for a game by name and return the first match.
     * Returns null if no match is found or if API key is missing.
     */
    private suspend fun searchGameInternal(gameName: String, requireSetting: Boolean): GameSearchResult? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyOrNull(logMissing = true)
        if (apiKey == null) {
            Timber.tag("SteamGridDB").i("Skipping image fetch for '$gameName' - API key not configured")
            return@withContext null
        }

        if (requireSetting && !PrefManager.fetchSteamGridDBImages) {
            Timber.tag("SteamGridDB").d("Image fetching is disabled in settings")
            return@withContext null
        }

        try {
            val encodedName = URLEncoder.encode(gameName, "UTF-8")
            val json = fetchJson(
                url = "$API_BASE_URL$SEARCH_ENDPOINT/$encodedName",
                apiKey = apiKey,
                errorLabel = "Search failed for '$gameName'"
            ) ?: return@withContext null

            if (!json.optBoolean("success", false)) {
                Timber.tag("SteamGridDB").w("Search API returned success=false for '$gameName'")
                return@withContext null
            }

            val dataArray = json.optJSONArray("data")
            if (dataArray == null || dataArray.length() == 0) {
                Timber.tag("SteamGridDB").d("No results found for '$gameName'")
                return@withContext null
            }

            // Use the first match
            val firstMatch = dataArray.getJSONObject(0)
            val gameId = firstMatch.optInt("id", 0)
            val name = firstMatch.optString("name", gameName)
            val releaseDate = firstMatch.optLong("release_date", 0L)

            if (gameId == 0) {
                Timber.tag("SteamGridDB").w("Invalid game ID in search results for '$gameName'")
                return@withContext null
            }

            Timber.tag("SteamGridDB").i("Found game '$name' (ID: $gameId) for '$gameName'")
            return@withContext GameSearchResult(gameId, name, releaseDate)
        } catch (e: Exception) {
            Timber.tag("SteamGridDB").e(e, "Error searching for game '$gameName'")
            return@withContext null
        }
    }

    suspend fun searchGame(gameName: String): GameSearchResult? {
        return searchGameInternal(gameName, requireSetting = true)
    }

    private suspend fun resolveGameBySteamAppId(steamAppId: Int): GameSearchResult? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey() ?: return@withContext null
        if (steamAppId <= 0) return@withContext null

        try {
            val json = fetchJson(
                url = "$API_BASE_URL$GAMES_STEAM_ENDPOINT/$steamAppId",
                apiKey = apiKey,
                errorLabel = "Failed to resolve Steam app ID $steamAppId"
            ) ?: return@withContext null
            if (!json.optBoolean("success", false)) return@withContext null

            val data = json.optJSONObject("data") ?: return@withContext null
            val gameId = data.optInt("id", 0)
            if (gameId == 0) return@withContext null

            GameSearchResult(
                gameId = gameId,
                name = data.optString("name"),
                releaseDate = data.optLong("release_date", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchShortcutIcons(gameName: String, steamAppId: Int? = null, limit: Int = 8): List<ShortcutIconOption> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey() ?: return@withContext emptyList()
        val searchResult =
            (steamAppId?.let { resolveGameBySteamAppId(it) })
                ?: searchGameInternal(gameName, requireSetting = false)
                ?: return@withContext emptyList()

        try {
            val json = fetchJson(
                url = "$API_BASE_URL$ICONS_ENDPOINT/${searchResult.gameId}",
                apiKey = apiKey,
                errorLabel = "Failed to fetch icons for '$gameName'"
            ) ?: return@withContext emptyList()
            if (!json.optBoolean("success", false)) {
                Timber.tag("SteamGridDB").w("Icon API returned success=false for '$gameName'")
                return@withContext emptyList()
            }

            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                // Loop until either the length of the data or the specified limit, whichever is smaller
                for (i in 0 until data.length().coerceAtMost(limit)) {
                    val item = data.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (url.isBlank()) continue

                    // Exclude clearly animated assets for pinned launcher shortcuts.
                    // TODO: Remove this and just filter on the API request instead
                    // mimes
                    //Array of strings (MimesIcon)
                    //Items Enum: "image/png" "image/vnd.microsoft.icon"
                    //Filter results by image mime types. Multiple types can be provided as comma delimited strings.

                    val mime = item.optString("mime")
                    val isAnimated = item.optBoolean("animated", false) ||
                        mime.contains("gif", ignoreCase = true) ||
                        mime.contains("apng", ignoreCase = true)
                    if (isAnimated) continue

                    add(
                        ShortcutIconOption(
                            url = url,
                            style = item.optString("style"),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag("SteamGridDB").e(e, "Error fetching shortcut icons for '$gameName'")
            emptyList()
        }
    }

    /**
     * Check if an image is horizontal (width > height) or vertical (height > width)
     * Returns true if horizontal, false if vertical, null if cannot determine
     */
    private suspend fun isImageHorizontal(imageBytes: ByteArray): Boolean? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true // Only decode bounds, not the full image
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

            val width = options.outWidth
            val height = options.outHeight

            if (width > 0 && height > 0) {
                return@withContext width > height
            }
            return@withContext null
        } catch (e: Exception) {
            Timber.tag("SteamGridDB").w(e, "Failed to determine image orientation")
            return@withContext null
        }
    }

    /**
     * Download and save an image from a URL
     * @return Pair of (file path, isHorizontal) or null if failed
     */
    private suspend fun downloadAndSaveImage(
        imageUrl: String,
        gameFolder: File,
        fileName: String
    ): Pair<String, Boolean?>? = withContext(Dispatchers.IO) {
        try {
            // Download the image
            val imageBytes = downloadImageBytes(imageUrl, "Failed to download image from $imageUrl")
                ?: return@withContext null

            // Determine orientation
            val isHorizontal = isImageHorizontal(imageBytes)

            // Determine file extension from URL
            val extension = extensionFromUrl(imageUrl)

            val outputFile = File(gameFolder, "$fileName$extension")

            // Save to file
            FileOutputStream(outputFile).use { it.write(imageBytes) }

            Timber.tag("SteamGridDB").i("Saved image to ${outputFile.absolutePath} (horizontal: $isHorizontal)")
            return@withContext Pair(outputFile.absolutePath, isHorizontal)
        } catch (e: Exception) {
            Timber.tag("SteamGridDB").e(e, "Error downloading image from $imageUrl")
            return@withContext null
        }
    }

    /**
     * Fetch grids for a game, find horizontal and vertical images, and save them separately.
     * @param gameId The SteamGridDB game ID
     * @param gameFolder The folder where images should be saved
     * @return Pair of (heroPath, capsulePath) where hero is horizontal grid and capsule is vertical grid
     */
    private suspend fun fetchGrids(
        gameId: Int,
        gameFolder: File
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey() ?: return@withContext Pair(null, null)

        try {
            val json = fetchJson(
                url = "$API_BASE_URL$GRIDS_ENDPOINT/$gameId",
                apiKey = apiKey,
                errorLabel = "Failed to fetch grids for game $gameId"
            ) ?: return@withContext Pair(null, null)

            if (!json.optBoolean("success", false)) {
                Timber.tag("SteamGridDB").w("API returned success=false for grids (game $gameId)")
                return@withContext Pair(null, null)
            }

            val dataArray = json.optJSONArray("data")
            if (dataArray == null || dataArray.length() == 0) {
                Timber.tag("SteamGridDB").d("No grid images found for game $gameId")
                return@withContext Pair(null, null)
            }

            var heroPath: String? = null
            var capsulePath: String? = null

            // Loop through all grid images to find horizontal (hero) and vertical (capsule)
            for (i in 0 until dataArray.length()) {
                val imageObj = dataArray.getJSONObject(i)
                val imageUrl = imageObj.optString("url", "")

                if (imageUrl.isEmpty()) continue

                // Determine file extension from URL
                val extension = extensionFromUrl(imageUrl)

                // Download the image to check orientation first
                val imageBytes = downloadImageBytes(
                    imageUrl,
                    "Failed to download grid image from $imageUrl"
                ) ?: continue

                // Determine orientation
                val isHorizontal = isImageHorizontal(imageBytes)

                // Save directly to the final filename based on orientation
                if (isHorizontal == true && heroPath == null) {
                    // This is horizontal - use for hero
                    val heroFile = File(gameFolder, "steamgriddb_grid_hero$extension")
                    try {
                        FileOutputStream(heroFile).use { it.write(imageBytes) }
                        heroPath = heroFile.absolutePath
                        Timber.tag("SteamGridDB").i("Found horizontal grid for hero: ${heroFile.name}")
                    } catch (e: Exception) {
                        Timber.tag("SteamGridDB").e(e, "Failed to save hero image")
                    }
                } else if (isHorizontal == false && capsulePath == null) {
                    val capsuleFile = File(gameFolder, "steamgriddb_grid_capsule$extension")
                    try {
                        FileOutputStream(capsuleFile).use { it.write(imageBytes) }
                        capsulePath = capsuleFile.absolutePath
                        Timber.tag("SteamGridDB").i("Found vertical grid for capsule: ${capsuleFile.name}")
                    } catch (e: Exception) {
                        Timber.tag("SteamGridDB").e(e, "Failed to save capsule image")
                    }
                }

                // If we don't need this image, we just skip it (no temp file to delete)

                // If we found both, we can stop
                if (heroPath != null && capsulePath != null) {
                    break
                }
            }

            // Log if we didn't find both images
            if (heroPath == null) {
                Timber.tag("SteamGridDB").w("No horizontal grid found for hero (game $gameId)")
            }
            if (capsulePath == null) {
                Timber.tag("SteamGridDB").w("No vertical grid found for capsule (game $gameId)")
            }

            return@withContext Pair(heroPath, capsulePath)
        } catch (e: Exception) {
            Timber.tag("SteamGridDB").e(e, "Error fetching grids for game $gameId")
            return@withContext Pair(null, null)
        }
    }

    /**
     * Fetch images of a specific type for a game and save them to the game folder.
     * @param gameId The SteamGridDB game ID
     * @param gameFolder The folder where images should be saved
     * @param imageType The type of image to fetch (heroes, logos) - grids are handled separately
     * @return The path to the saved image file, or null if not found
     */
    private suspend fun fetchAndSaveImage(
        gameId: Int,
        gameFolder: File,
        imageType: String
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey() ?: return@withContext null

        try {
            val endpoint = when (imageType) {
                "hero" -> HEROES_ENDPOINT
                "logo" -> LOGOS_ENDPOINT
                else -> {
                    Timber.tag("SteamGridDB").w("Unknown image type: $imageType (grids should use fetchGrids)")
                    return@withContext null
                }
            }

            val json = fetchJson(
                url = "$API_BASE_URL$endpoint/$gameId",
                apiKey = apiKey,
                errorLabel = "Failed to fetch $imageType for game $gameId"
            ) ?: return@withContext null

            if (!json.optBoolean("success", false)) {
                Timber.tag("SteamGridDB").w("API returned success=false for $imageType (game $gameId)")
                return@withContext null
            }

            val dataArray = json.optJSONArray("data")
            if (dataArray == null || dataArray.length() == 0) {
                Timber.tag("SteamGridDB").d("No $imageType images found for game $gameId")
                return@withContext null
            }

            // Use the first image (usually the most popular/verified one)
            val firstImage = dataArray.getJSONObject(0)
            val imageUrl = firstImage.optString("url", "")

            if (imageUrl.isEmpty()) {
                Timber.tag("SteamGridDB").w("No URL in $imageType data for game $gameId")
                return@withContext null
            }

            // Determine file extension from URL
            val extension = extensionFromUrl(imageUrl)

            val fileName = "steamgriddb_${imageType}$extension"
            val outputFile = File(gameFolder, fileName)

            // Download the image
            val imageBytes = downloadImageBytes(imageUrl, "Failed to download image from $imageUrl")
                ?: return@withContext null

            // Save to file
            FileOutputStream(outputFile).use { it.write(imageBytes) }

            Timber.tag("SteamGridDB").i("Saved $imageType image to ${outputFile.absolutePath}")
            return@withContext outputFile.absolutePath
        } catch (e: Exception) {
            Timber.tag("SteamGridDB").e(e, "Error fetching $imageType for game $gameId")
            return@withContext null
        }
    }

    /**
     * Fetch all images (grids, heroes, logos) for a game and save them to the game folder.
     * Also updates the release date if found.
     * @param gameName The name of the game to search for
     * @param gameFolderPath The path to the game folder where images should be saved
     * @return ImageFetchResult containing paths to saved images and release date
     */
    suspend fun fetchGameImages(
        gameName: String,
        gameFolderPath: String
    ): ImageFetchResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyOrNull(logMissing = true)
        if (apiKey == null) {
            Timber.tag("SteamGridDB").i("Skipping image fetch for '$gameName' - API key not configured")
            return@withContext ImageFetchResult(null, null, null, null, null)
        }

        if (!PrefManager.fetchSteamGridDBImages) {
            Timber.tag("SteamGridDB").d("Image fetching is disabled in settings")
            return@withContext ImageFetchResult(null, null, null, null, null)
        }

        val gameFolder = File(gameFolderPath)
        if (!gameFolder.exists() || !gameFolder.isDirectory) {
            Timber.tag("SteamGridDB").w("Game folder does not exist: $gameFolderPath")
            return@withContext ImageFetchResult(null, null, null, null, null)
        }

        // Check if images already exist (skip if all are present)
        // Grids are saved as grid_hero (horizontal) and grid_capsule (vertical)
        // Heroes are saved as hero (for header)
        val existingGridHero = gameFolder.listFiles { file ->
            file.isFile && file.name.startsWith("steamgriddb_grid_hero", ignoreCase = true) &&
            (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
        }?.isNotEmpty() == true
        val existingGridCapsule = gameFolder.listFiles { file ->
            file.isFile && file.name.startsWith("steamgriddb_grid_capsule", ignoreCase = true) &&
            (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
        }?.isNotEmpty() == true
        val existingHero = gameFolder.listFiles { file ->
            file.isFile && file.name.startsWith("steamgriddb_hero", ignoreCase = true) &&
            !file.name.contains("grid_", ignoreCase = true) &&
            (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
        }?.isNotEmpty() == true
        val existingLogo = gameFolder.listFiles { file ->
            file.isFile && file.name.startsWith("steamgriddb_logo", ignoreCase = true) &&
            (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
        }?.isNotEmpty() == true

        if (existingGridHero && existingGridCapsule && existingHero && existingLogo) {
            Timber.tag("SteamGridDB").d("All images already exist for '$gameName', skipping fetch")
            val gridHeroFile = gameFolder.listFiles { file ->
                file.isFile && file.name.startsWith("steamgriddb_grid_hero", ignoreCase = true) &&
                (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
            }?.firstOrNull()
            val gridCapsuleFile = gameFolder.listFiles { file ->
                file.isFile && file.name.startsWith("steamgriddb_grid_capsule", ignoreCase = true) &&
                (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
            }?.firstOrNull()
            val heroFile = gameFolder.listFiles { file ->
                file.isFile && file.name.startsWith("steamgriddb_hero", ignoreCase = true) &&
                !file.name.contains("grid_", ignoreCase = true) &&
                (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
            }?.firstOrNull()
            val logoFile = gameFolder.listFiles { file ->
                file.isFile && file.name.startsWith("steamgriddb_logo", ignoreCase = true) &&
                (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
            }?.firstOrNull()
            return@withContext ImageFetchResult(
                gridPath = gridHeroFile?.absolutePath, // Hero path (horizontal grid)
                heroPath = heroFile?.absolutePath, // Header path (heroes endpoint)
                logoPath = logoFile?.absolutePath,
                capsulePath = gridCapsuleFile?.absolutePath, // Capsule path (vertical grid)
                releaseDate = null // Don't update release date if images already exist
            )
        }

        // Search for the game
        val searchResult = searchGame(gameName) ?: return@withContext ImageFetchResult(null, null, null, null, null)

        // Fetch grids (returns hero and capsule paths)
        val (gridHeroPath, gridCapsulePath) = if (!existingGridHero || !existingGridCapsule) {
            fetchGrids(searchResult.gameId, gameFolder)
        } else {
            val gridHeroFile = gameFolder.listFiles { file ->
                file.isFile && file.name.startsWith("steamgriddb_grid_hero", ignoreCase = true) &&
                (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
            }?.firstOrNull()
            val gridCapsuleFile = gameFolder.listFiles { file ->
                file.isFile && file.name.startsWith("steamgriddb_grid_capsule", ignoreCase = true) &&
                (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
            }?.firstOrNull()
            Pair(gridHeroFile?.absolutePath, gridCapsuleFile?.absolutePath)
        }

        // Fetch heroes (for header)
        val heroPath = if (!existingHero) {
            fetchAndSaveImage(searchResult.gameId, gameFolder, "hero")
        } else {
            gameFolder.listFiles { file ->
                file.isFile && file.name.startsWith("steamgriddb_hero", ignoreCase = true) &&
                !file.name.contains("grid_", ignoreCase = true) &&
                (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
            }?.firstOrNull()?.absolutePath
        }

        // Fetch logos
        val logoPath = if (!existingLogo) {
            fetchAndSaveImage(searchResult.gameId, gameFolder, "logo")
        } else {
            gameFolder.listFiles { file ->
                file.isFile && file.name.startsWith("steamgriddb_logo", ignoreCase = true) &&
                (file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true))
            }?.firstOrNull()?.absolutePath
        }

        // Mark as fetched in metadata and save release date if found
        try {
            val existing = GameMetadataManager.read(gameFolder)
            val appId = existing?.appId
                ?: abs(gameFolder.absolutePath.hashCode()).let { if (it == 0) 1 else it }

            GameMetadataManager.update(
                folder = gameFolder,
                appId = appId,
                steamgriddbFetched = true,
                releaseDate = if (searchResult.releaseDate > 0) searchResult.releaseDate else null
            )
        } catch (e: Exception) {
            Timber.tag("SteamGridDB").w(e, "Failed to update metadata after fetch")
        }

        return@withContext ImageFetchResult(
            gridPath = gridHeroPath, // Horizontal grid for hero view
            heroPath = heroPath, // Heroes endpoint for header view
            logoPath = logoPath,
            capsulePath = gridCapsulePath, // Vertical grid for capsule view
            releaseDate = searchResult.releaseDate
        )
    }

    /**
     * Data class for search results
     */
    data class GameSearchResult(
        val gameId: Int,
        val name: String,
        val releaseDate: Long
    )

    /**
     * Data class for image fetch results
     */
    data class ImageFetchResult(
        val gridPath: String?, // Horizontal grid for hero view
        val heroPath: String?, // Heroes endpoint for header view
        val logoPath: String?,
        val capsulePath: String? = null, // Vertical grid for capsule view
        val releaseDate: Long? = null
    )

    data class ShortcutIconOption(
        val url: String,
        val style: String = "",
    )
}
