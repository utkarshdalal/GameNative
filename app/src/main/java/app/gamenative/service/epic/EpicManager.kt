package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicGame
import app.gamenative.db.dao.EpicGameDao
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 *
 * TODO: Download Game, Uninstall Game, Ensure we can track Progress via STDOUT parsing
 * TODO: Launching games using the different execution params that we store.
 *
 * | Install | `legendary install <APPNAME> --base-path <PATH> --platform Windows` | Progress output |
 * | Launch | `legendary launch <APPNAME> --offline --skip-version-check` | Launch output |
 * TODO: We should see if we need to put any disclaimers around online games not being supported and THEY BETTER NOT TRY FORTNITE.
 */

/**
 * EpicManager handles Epic Games library management
 *
 * Responsibilities:
 * - Fetch game library from Epic via Legendary CLI
 * - Parse game metadata from JSON
 * - Update Room database
 * - Detect existing installations
 *
 * Uses legendary CLI commands:
 * - `legendary list --third-party --json` - Full library with metadata
 * - `legendary info <app_name> --json` - Detailed game info
 */
@Singleton
class EpicManager @Inject constructor(
    private val epicGameDao: EpicGameDao,
) {

    private val REFRESH_BATCH_SIZE = 10

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Separate client for CDN downloads - no connection pooling, follows redirects
    private val cdnClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // DarkSiders 2 example for grabbing the details. Requires the Namespace and the catalog ID.
    // https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/091d95ea332843498122beee1a786d71/bulk/items?id=8c04901974534bd0818f747952b0a19b&includeDLCDetails=true&includeMainGameDetails=true

    // WE should just query the asset list first to get a list of assets, then we can query for each game if possible.
    // ! TODO: Convert from grabbing everything, we can make our request since they're not difficult.
    data class EpicAssetList(
        val appName: String,
        val labelName: String,
        val buildVersion: String,
        val catalogItemId: String,
        val namespace: String,
        val assetId: String,
        val metadata: AssetMetadata?,
    )

    data class AssetMetadata(
        val installationPoolId: String,
        val update_type: String,
    )

    data class LibraryItem(
        val namespace: String, // We also need this to construct the URL for grabbing the game information....
        val catalogItemId: String?, // Catalogue Item ID is the one we use to grab the game details -> It's the most important ID.
        val appName: String,
        val country: String?,
        val platform: List<String>?,
        val productId: String,
        val sandboxName: String,
        val sandboxType: String,
        val recordType: String?,
        val acquisitionDate: String?,
        val dependencies: List<String>?,
    )

    data class ParsedLibraryItem(
        val appName: String,
        val namespace: String,
        val catalogItemId: String,
        val sandboxType: String?,
        val country: String?,
    )

    data class LibraryItemsResponse(
        val responseMetadata: ResponseMetadata,
        val records: List<LibraryItem>?,
    )

    data class ResponseMetadata(
        val nextCursor: String?,
        val stateToken: String?,
    )

    data class ManifestSizes(
        val installSize: Long,
        val downloadSize: Long,
    )

    // Usually consists of DieselGameBox and DieselGameBoxTall that we can use. We should use DieselGameBoxtall for capsule & the other for everything else.
    data class EpicKeyImage(
        val type: String,
        val url: String, // Full URL of the game art.
        val md5: String?,
        val width: Int?,
        val height: Int?,
        val size: Int?,
        val uploadedDate: String?, // "2019-12-19T21:54:10.003Z"
    )

    data class EpicCategory(
        val path: String,
    )

    data class EpicCustomAttribute(
        val type: String,
        val value: String,
    )
    data class EpicReleaseInfo(
        val id: String,
        val appId: String,
        val platform: List<String>?,
        val dateAdded: String?,
        val releaseNote: String?,
        val versionTitle: String?,
    )

    data class EpicMainGameItem(
        val id: String,
        val namespace: String,
    )

    data class GameInfoResponse(
        val id: String,
        val title: String,
        val description: String,
        val keyImages: List<EpicKeyImage>,
        val categories: List<EpicCategory>,
        val namespace: String,
        val status: String?,
        val creationDate: String?, // "2025-03-04T08:39:07.841Z",
        val lastModifiedDate: String?, // "2025-03-06T07:37:16.597Z",
        val customAttributes: EpicCustomAttribute?,
        val entitlementName: String?,
        val entitlementType: String?,
        val itemType: String?,
        val releaseInfo: EpicReleaseInfo,
        val developer: String,
        val developerId: String?,
        val eulaIds: List<String>?,
        val endOfSupport: Boolean?,
        val mainGameItemList: List<String>?,
        val ageGatings: Map<String, Int>?,
        val applicationId: String?,
        val baseAppName: String?,
        val baseProductId: String?,
        val mainGameItem: EpicMainGameItem?,
    )

    // GET LIBRARY ITEMS
    // https://library-service.live.use1a.on.epicgames.com/library/api/public/items

    // Get manifest DARKSIDERS 2 EXAMPLE : https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/public/assets/v2/platform/Windows/namespace/091d95ea332843498122beee1a786d71/catalogItem/8c04901974534bd0818f747952b0a19b/app/Hoki/label/Live

    /**
     * Refresh the entire library (called manually by user or after login)
     * Fetches all games from Epic via Legendary and updates the database
     */
    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!EpicAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot refresh library: not authenticated with Epic")
                return@withContext Result.failure(Exception("Not authenticated with Epic"))
            }

            Timber.tag("Epic").i("Refreshing Epic library from Epic API...")

            // Get a list of basic info for each game.
            val listResult = fetchLibrary(context)

            if (listResult.isFailure) {
                val error = listResult.exceptionOrNull()
                Timber.tag("Epic").e(error, "Failed to fetch games from Epic: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Failed to fetch Epic library"))
            }

            val games = listResult.getOrNull() ?: emptyList()
            Timber.tag("Epic").i("Successfully fetched ${games.size} games from Epic")

            if (games.isEmpty()) {
                Timber.tag("Epic").w("No games found in Epic library")
                return@withContext Result.success(0)
            }

            // ! Get the game information and store each one in batches
            val epicGames = mutableListOf<EpicGame>()
            for ((index, game) in games.withIndex()) {
                val result = fetchGameInfo(context, game)

                if (result.isSuccess) {
                    val epicGame = result.getOrNull()
                    if (epicGame != null) {
                        epicGames.add(epicGame)
                        Timber.tag("Epic").d("Refreshed Game: ${epicGame.title}")
                    }
                } else {
                    Timber.tag("Epic").w("Epic game ${game.appName} could not be fetched")
                }

                if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == games.size - 1) {
                    if (epicGames.isNotEmpty()) {
                        epicGameDao.upsertPreservingInstallStatus(epicGames)
                        Timber.tag("Epic").d("Batch inserted ${epicGames.size} games (processed ${index + 1}/${games.size})")
                        epicGames.clear()
                    }
                }
            }

            Timber.tag("Epic").i("Successfully refreshed Epic library")
            Result.success(epicGames.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh Epic library")
            Result.failure(e)
        }
    }

    // We should only pull out the following:

    /**
     * Fetch user's Epic library
     *
     * Calls: GET https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true
     *
     * Returns list of library items with app names, namespaces, and catalog IDs
     */
    suspend fun fetchLibrary(context: Context): Result<List<ParsedLibraryItem>> = withContext(Dispatchers.IO) {
        // Grab the initial library amount. We need the following from each game:
        // namespace
        // appName
        // productId
        // catalogItemId
        // country
        // Initial list is a List<LibraryItem> - We then parse it into a <ParsedLibraryItem>
        try {
            // Get Credentials and restore them
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
            }

            val accessToken = credentials.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No access token"))
            }

            val gameList = mutableListOf<ParsedLibraryItem>()
            var cursor: String? = null

            // Fetch all pages of library items
            do {
                val url = buildString {
                    append("${EpicConstants.EPIC_LIBRARY_API_URL}?includeMetadata=true")
                    if (cursor != null) {
                        append("&cursor=$cursor")
                    }
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                    .get()
                    .build()

                Timber.tag("Epic").d("Fetching Epic library page: cursor=$cursor")

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "Unknown error"
                    Timber.tag("Epic").e("Library fetch failed: ${response.code} - $error")
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $error"))
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    Timber.tag("Epic").e("Empty response body from library API")
                    return@withContext Result.failure(Exception("Empty response"))
                }

                val json = JSONObject(body)
                val records = json.optJSONArray("records") ?: JSONArray()

                Timber.tag("Epic").d("Received ${records.length()} library items in this page")

                // Process records and fetch game info for each
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)

                    // Skip items without app name
                    if (!record.has("appName")) {
                        continue
                    }

                    val appName = record.getString("appName")
                    val namespace = record.getString("namespace")
                    val catalogItemId = record.getString("catalogItemId")
                    val sandboxType = record.optString("sandboxType", "")
                    val country = record.optString("country", "")

                    // Skip UE assets, private sandboxes, and broken entries
                    if (namespace == "ue" || sandboxType == "PRIVATE" || appName == "1") {
                        Timber.tag("Epic").d("Skipping $appName (namespace=$namespace, sandbox=$sandboxType)")
                        continue
                    }

                    // Add the basic game to the gameList.
                    val gameInfo = ParsedLibraryItem(appName, namespace, catalogItemId, sandboxType, country)
                    gameList.add(gameInfo)
                }
                // Get cursor for next page - stop if cursor is null or same as previous
                val metadata = json.optJSONObject("responseMetadata")
                val oldCursor = cursor
                cursor = metadata?.optString("nextCursor")?.takeIf { it.isNotEmpty() }
            } while (cursor != null && cursor != oldCursor)

            Timber.tag("Epic").i("Successfully fetched ${gameList.size} games from Epic library")
            Result.success(gameList)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to fetch Epic library")
            Result.failure(e)
        }
    }

    private suspend fun fetchGameInfo(
        context: Context,
        game: ParsedLibraryItem,
    ): Result<EpicGame> = withContext(Dispatchers.IO) {
        try {
            // Get Credentials and restore them
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
            }

            val accessToken = credentials.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No access token"))
            }

            val country = game.country ?: "US" // Do we really need this?

            // TODO: Investigate if we need &locale=en-US param.
            val url = "${EpicConstants.EPIC_CATALOG_API_URL}/shared/namespace/${game.namespace}/bulk/items" +
                "?id=${game.catalogItemId}&includeDLCDetails=true&includeMainGameDetails=true" +
                "&country=$country"

            Timber.tag("Epic").d("fetching game info for ${game.appName} - url: $url")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Timber.w("Failed to fetch game info for ${game.catalogItemId}: ${response.code}")
                return@withContext Result.failure(Exception("Could not fetch game info: ${response.code}"))
            }

            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Could not fetch game info for ${game.catalogItemId}"))
            }

            val json = JSONObject(body)
            val gameData = json.optJSONObject(game.catalogItemId)

            if (gameData != null) {
                val epicGame = parseGameFromCatalog(gameData, game.appName)
                return@withContext Result.success(epicGame)
            } else {
                return@withContext Result.failure(Exception("Game data not found in response"))
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching game info for ${game.catalogItemId}")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Parse Epic catalog JSON into EpicGame object
     *
     * Catalog structure:
     * {
     *   "id": "catalogItemId",
     *   "namespace": "namespace",
     *   "title": "Game Title",
     *   "description": "Description...",
     *   "keyImages": [...],
     *   "categories": [...],
     *   "developer": "Developer",
     *   "developerDisplayName": "Developer Display Name",
     *   "publisher": "Publisher",
     *   "publisherDisplayName": "Publisher Display Name",
     *   "releaseInfo": [...],
     *   "mainGameItem": { ... },  // Present for DLC
     *   ...
     * }
     */
    private fun parseGameFromCatalog(data: JSONObject, libraryAppName: String): EpicGame {
        val catalogItemId = data.getString("id")
        val namespace = data.getString("namespace")
        val title = data.getString("title")
        val description = data.optString("description", "")

        // Use the appName from library API (passed as parameter)
        // This is the real appName needed for downloads, not the catalog ID
        val appName = libraryAppName

        // Extract images - map to EpicGame's art fields
        val keyImages = data.optJSONArray("keyImages")
        var artCover = "" // DieselGameBoxTall - Tall cover art
        var artSquare = "" // DieselGameBox - Square box art
        var artLogo = "" // DieselGameBoxLogo - Logo image
        var artPortrait = "" // DieselStoreFrontWide - Wide banner

        if (keyImages != null) {
            for (i in 0 until keyImages.length()) {
                val img = keyImages.getJSONObject(i)
                val imgType = img.optString("type")
                val imgUrl = img.optString("url", "")

                when (imgType) {
                    "DieselGameBoxTall" -> artCover = imgUrl
                    "DieselGameBox" -> artSquare = imgUrl
                    "DieselGameBoxLogo" -> artLogo = imgUrl
                    "DieselStoreFrontWide" -> artPortrait = imgUrl
                    "Thumbnail" -> if (artSquare.isEmpty()) artSquare = imgUrl
                }
            }
        }

        // Check if this is DLC
        val isDLC = data.has("mainGameItem")
        val baseGameAppName = if (isDLC) {
            data.optJSONObject("mainGameItem")?.optString("id", "") ?: ""
        } else {
            ""
        }

        // Get developer/publisher
        val developer = data.optString("developerDisplayName", data.optString("developer", ""))
        val publisher = data.optString("publisherDisplayName", data.optString("publisher", ""))

        // Get categories to check for mods
        val categories = data.optJSONArray("categories")
        var isMod = false
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                if (cat.optString("path") == "mods") {
                    isMod = true
                    break
                }
            }
        }

        // Release date - convert to string format
        val releaseInfo = data.optJSONArray("releaseInfo")
        var releaseDate = ""
        if (releaseInfo != null && releaseInfo.length() > 0) {
            val release = releaseInfo.getJSONObject(0)
            releaseDate = release.optString("dateAdded", "")
        }

        // Parse genres/tags from categories
        val genresList = mutableListOf<String>()
        val tagsList = mutableListOf<String>()
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                val path = cat.optString("path", "")
                if (path.startsWith("games/")) {
                    genresList.add(path.removePrefix("games/"))
                } else if (path.isNotEmpty() && path != "mods") {
                    tagsList.add(path)
                }
            }
        }

        return EpicGame(
            id = catalogItemId,
            appName = appName,
            title = title,
            namespace = namespace,
            developer = developer,
            publisher = publisher,
            description = description,
            artCover = artCover,
            artSquare = artSquare,
            artLogo = artLogo,
            artPortrait = artPortrait,
            isDLC = isDLC,
            baseGameAppName = baseGameAppName,
            releaseDate = releaseDate,
            genres = genresList,
            tags = tagsList,
            isInstalled = false, // Will be updated from local database
            installPath = "",
            platform = "Windows",
            version = "",
            executable = "",
            installSize = 0,
            downloadSize = 0,
            canRunOffline = false, // Unknown from catalog API, will need manifest
            requiresOT = false,
            cloudSaveEnabled = false,
            saveFolder = "",
            thirdPartyManagedApp = "",
            isEAManaged = false,
            lastPlayed = 0,
            playTime = 0,
        )
    }

    /**
     * Extract image URL from keyImages array by type
     */
    private fun extractImageUrl(keyImages: JSONArray?, imageType: String): String {
        if (keyImages == null) return ""

        for (i in 0 until keyImages.length()) {
            val image = keyImages.optJSONObject(i) ?: continue
            if (image.optString("type") == imageType) {
                return image.optString("url", "")
            }
        }
        return ""
    }

    /**
     * Parse JSON array into list of strings
     */
    private fun parseJsonArray(jsonArray: JSONArray?): List<String> {
        val result = mutableListOf<String>()
        if (jsonArray != null) {
            for (j in 0 until jsonArray.length()) {
                val item = jsonArray.opt(j)
                when (item) {
                    is String -> result.add(item)
                    is JSONObject -> result.add(item.optString("name", item.toString()))
                    else -> result.add(item.toString())
                }
            }
        }
        return result
    }

    /**
     * Get a single game by ID
     */
    suspend fun getGameById(gameId: String): EpicGame? {
        return withContext(Dispatchers.IO) {
            try {
                epicGameDao.getById(gameId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Epic game by ID: $gameId")
                null
            }
        }
    }

    suspend fun getDLCForTitle(appId: String): List<EpicGame> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.tag("Epic").i("Getting DLC for appId: $appId")
                val dlcTitles = epicGameDao.getAllDlcTitles().firstOrNull() ?: emptyList()
                if (dlcTitles.isNotEmpty()) {
                    for (title in dlcTitles) {
                        Timber.tag("Epic").i("ALL DLCs: ${title.title} \nBase Game: ${title.baseGameAppName}")
                    }
                }
                epicGameDao.getDLCForTitle(appId).firstOrNull() ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Failed to get DLC for app name: $appId")
                emptyList()
            }
        }
    }

    /**
     * Get a single game by app name (Legendary identifier)
     */
    suspend fun getGameByAppName(appName: String): EpicGame? {
        return withContext(Dispatchers.IO) {
            try {
                epicGameDao.getByAppName(appName)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Epic game by app name: $appName")
                null
            }
        }
    }

    suspend fun insertGame(game: EpicGame) {
        withContext(Dispatchers.IO) {
            epicGameDao.insert(game)
        }
    }

    suspend fun updateGame(game: EpicGame) {
        withContext(Dispatchers.IO) {
            epicGameDao.update(game)
        }
    }

    suspend fun uninstall(appId: String) {
        withContext(Dispatchers.IO) {
            epicGameDao.uninstall(appId)
        }
    }

    /**
     * Start background sync (called after login)
     */
    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!EpicAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot start background sync: no stored credentials")
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("Epic").i("Starting Epic library background sync...")

            // TODO: Remove once finished testing.
            Timber.tag("Epic").i("Starting Epic library background sync...")
            Result.success(Unit)

            val result = refreshLibrary(context)

            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                Timber.tag("Epic").i("Background sync completed: $count games synced")
                Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                Timber.e(error, "Background sync failed: ${error?.message}")
                Result.failure(error ?: Exception("Background sync failed"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync Epic library in background")
            Result.failure(e)
        }
    }

    data class ManifestResult(
        val manifestBytes: ByteArray,
        val cdnUrls: List<CdnUrl>,
    )

    // authQueryParams:  e.g., "?f_token=..." or "?ak_token=..."
    // cloudDir: e.g., "/Builds/Org/{org}/{build}/default" - the path prefix for chunks
    data class CdnUrl(
        val baseUrl: String,
        val authQueryParams: String,
        val cloudDir: String = "", // Full build path for chunk downloads
    )

    /**
     * Fetch manifest binary data from Epic API and CDN
     *
     * Returns the raw manifest bytes and CDN base URLs from the API response
     */
    suspend fun fetchManifestFromEpic(
        context: Context,
        namespace: String,
        catalogItemId: String,
        appName: String,
    ): Result<ManifestResult> = withContext(Dispatchers.IO) {
        try {
            // Get credentials
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                return@withContext Result.failure(credentials.exceptionOrNull() ?: Exception("No credentials"))
            }

            val accessToken = credentials.getOrNull()?.accessToken
            if (accessToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No access token"))
            }

            // Fetch manifest URL from Epic API
            val manifestUrl = "${EpicConstants.EPIC_LAUNCHER_API_URL}/launcher/api/public/assets/v2/platform" +
                "/Windows/namespace/$namespace/catalogItem/$catalogItemId/app" +
                "/$appName/label/Live"

            Timber.tag("Epic").d("Fetching manifest metadata from: $manifestUrl")

            val request = Request.Builder()
                .url(manifestUrl)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Manifest API request failed: ${response.code}"))
            }

            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Empty manifest API response"))
            }

            val manifestJson = JSONObject(body)
            val elements = manifestJson.optJSONArray("elements")

            if (elements == null || elements.length() == 0) {
                return@withContext Result.failure(Exception("No elements in manifest API response"))
            }

            val element = elements.getJSONObject(0)
            val manifests = element.optJSONArray("manifests")

            if (manifests == null || manifests.length() == 0) {
                return@withContext Result.failure(Exception("No manifests in API response"))
            }

            // Extract CDN base URLs from manifest URIs with their auth tokens
            // Each manifest entry represents the same content on a different CDN
            val cdnUrls = mutableListOf<CdnUrl>()
            for (i in 0 until manifests.length()) {
                val manifest = manifests.getJSONObject(i)
                val uri = manifest.getString("uri")

                // Extract base URL (e.g., "https://fastly-download.epicgames.com")
                val baseUrl = uri.substringBefore("/Builds")
                if (baseUrl.isEmpty() || !baseUrl.startsWith("http")) {
                    continue
                }

                // Extract CloudDir (build path) from URI
                // Example: https://fastly-download.epicgames.com/Builds/Org/{org}/{build}/default/...
                // CloudDir: /Builds/Org/{org}/{build}/default
                val cloudDir = if (uri.contains("/Builds")) {
                    val afterBase = uri.substringAfter(baseUrl)
                    val manifestFilename = afterBase.substringAfterLast("/")
                    afterBase.substringBefore("/" + manifestFilename)
                } else {
                    ""
                }

                // Extract authentication query parameters for this CDN
                val queryParams = manifest.optJSONArray("queryParams")
                val authParams = if (queryParams != null && queryParams.length() > 0) {
                    val params = StringBuilder("?")
                    for (j in 0 until queryParams.length()) {
                        val param = queryParams.getJSONObject(j)
                        val name = param.getString("name")
                        val value = param.getString("value")
                        if (j > 0) params.append("&")
                        params.append("$name=$value")
                    }
                    params.toString()
                } else {
                    ""
                }

                cdnUrls.add(CdnUrl(baseUrl, authParams, cloudDir))
            }

            // Error if no CDN URLs could be extracted
            if (cdnUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No CDN URLs found in manifest API response"))
            }

            Timber.tag("Epic").d("Found ${cdnUrls.size} CDN mirrors")

            // Use the first manifest to download the manifest file
            val manifestObj = manifests.getJSONObject(0)
            var manifestUri = manifestObj.getString("uri")

            // Append query parameters (CDN authentication tokens) for manifest download
            val manifestQueryParams = manifestObj.optJSONArray("queryParams")
            if (manifestQueryParams != null && manifestQueryParams.length() > 0) {
                val params = StringBuilder()
                for (i in 0 until manifestQueryParams.length()) {
                    val param = manifestQueryParams.getJSONObject(i)
                    val name = param.getString("name")
                    val value = param.getString("value")
                    if (i == 0) {
                        params.append("?")
                    } else {
                        params.append("&")
                    }
                    params.append("$name=$value")
                }
                manifestUri += params.toString()
            }

            Timber.tag("Epic").d("Downloading manifest binary from: $manifestUri")

            // Manifest downloads from CDN don't need/accept Epic auth tokens
            val manifestRequest = Request.Builder()
                .url(manifestUri)
                .header("User-Agent", EpicConstants.EPIC_USER_AGENT)
                .get()
                .build()

            val manifestResponse = cdnClient.newCall(manifestRequest).execute()

            if (!manifestResponse.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to download manifest binary: ${manifestResponse.code}"))
            }

            val manifestBytes = manifestResponse.body?.bytes()
            if (manifestBytes == null) {
                return@withContext Result.failure(Exception("Empty manifest bytes from CDN"))
            }

            Timber.tag("Epic").d("Manifest fetched with ${cdnUrls.size} CDN URLs")
            Result.success(ManifestResult(manifestBytes, cdnUrls))
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching manifest")
            Result.failure(e)
        }
    }

    /**
     * Fetch install size for a game by downloading its manifest
     * Manifest is small (~500KB-1MB) and contains all file metadata
     * Returns size in bytes, or 0 if failed
     */
    suspend fun fetchManifestSizes(context: Context, appName: String): ManifestSizes = withContext(Dispatchers.IO) {
        try {
            // Get the game info to get namespace and catalogItemId
            val game = getGameByAppName(appName)
            if (game == null) {
                Timber.tag("Epic").w("Game not found in database: $appName")
                return@withContext ManifestSizes(installSize = 0L, downloadSize = 0L)
            }

            // Fetch manifest using shared function
            val manifestResult = fetchManifestFromEpic(context, game.namespace, game.id, game.appName)
            if (manifestResult.isFailure) {
                Timber.tag("Epic").w("Failed to fetch manifest: ${manifestResult.exceptionOrNull()?.message}")
                return@withContext ManifestSizes(installSize = 0L, downloadSize = 0L)
            }

            val manifestData = manifestResult.getOrNull()!!

            // Parse with Kotlin parser
            val manifest = app.gamenative.service.epic.manifest.EpicManifest.readAll(manifestData.manifestBytes)

            // Calculate install size
            val installSize = manifest.fileManifestList?.elements?.sumOf { it.fileSize } ?: 0L
            val downloadSize = app.gamenative.service.epic.manifest.ManifestUtils.getTotalDownloadSize(manifest)
            Timber.tag("Epic").d(
                "Manifest stats for $appName: version=${manifest.version}, featureLevel=${manifest.meta?.featureLevel}, " +
                    "buildVersion=${manifest.meta?.buildVersion}, buildId=${manifest.meta?.buildId}",
            )
            Timber.tag("Epic").d(
                "Manifest stats for $appName: files=${manifest.fileManifestList?.count}, " +
                    "chunks=${manifest.chunkDataList?.count}",
            )
            Timber.tag("Epic").d("Install size for $appName: $installSize bytes")
            Timber.tag("Epic").d("Download size for $appName: $downloadSize bytes")

            return@withContext ManifestSizes(installSize = installSize, downloadSize = downloadSize)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Exception fetching install size for $appName")
            ManifestSizes(installSize = 0L, downloadSize = 0L)
        }
    }
}
