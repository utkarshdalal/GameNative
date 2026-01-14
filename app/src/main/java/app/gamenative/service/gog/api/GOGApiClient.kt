package app.gamenative.service.gog.api

import android.content.Context
import app.gamenative.service.gog.GOGAuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.gamenative.utils.Net
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native Kotlin API client for GOG Content System
 *
 * Replaces Python GOGDL API calls with direct HTTP requests
 */
@Singleton
class GOGApiClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: GOGManifestParser
) {

    companion object {
        private const val GOG_CONTENT_SYSTEM = "https://content-system.gog.com"
        private const val GOG_CDN = "https://gog-cdn-fastly.gog.com"
    }

    private val httpClient = Net.http

    /**
     * Get all available builds for a game (both generation 1 and 2)
     *
     * TEMPORARY: Currently filtered to Generation 2 only for initial implementation
     * TODO: Add Gen 1 support later
     */
    suspend fun getBuilds(gameId: String, platform: String = "windows"): Result<BuildsResponse> =
        withContext(Dispatchers.IO) {
            try {
                val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
                if (credentials == null) {
                    return@withContext Result.failure(Exception("Not authenticated"))
                }

                // TEMPORARY: Filter to Gen 2 only for now
                // TODO: Remove generation filter to support Gen 1 games
                val url = "$GOG_CONTENT_SYSTEM/products/$gameId/os/$platform/builds?generation=2"

                Timber.tag("GOG").d("Fetching builds from: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch builds: HTTP ${response.code}")
                    )
                }

                val jsonStr = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                val buildsResponse = parser.parseBuilds(jsonStr)

                Timber.tag("GOG").d("Found ${buildsResponse.totalCount} build(s) for game $gameId")

                // Log generation info for debugging
                buildsResponse.items.take(3).forEach { build ->
                    Timber.tag("GOG").d(
                        "  Build ${build.buildId}: generation=${build.generation}, version=${build.versionName}"
                    )
                }
                // Assuming all we ned for downloading is the build Ids, and ensure that we're downloading everything.

                Result.success(buildsResponse)
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to get builds for game $gameId")
                Result.failure(e)
            }
        }

    /**
     * Fetch build manifest (zlib or gzip compressed JSON)
     *
     * @param manifestUrl URL from build.link field
     * @return Parsed manifest data
     */
    suspend fun fetchManifest(manifestUrl: String): Result<GOGManifestMeta> =
        withContext(Dispatchers.IO) {
            try {
                val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
                if (credentials == null) {
                    return@withContext Result.failure(Exception("Not authenticated"))
                }

                Timber.tag("GOG").d("Fetching manifest from: $manifestUrl")

                val request = Request.Builder()
                    .url(manifestUrl)
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch manifest: HTTP ${response.code}")
                    )
                }

                val manifestBytes = response.body?.bytes()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                // Decompress based on detected format
                val manifestStr = decompressManifest(manifestBytes)

                Timber.tag("GOG").d("Manifest decompressed, size: ${manifestStr.length} bytes")

                val manifest = parser.parseManifest(manifestStr)

                Timber.tag("GOG").i(
                    "Manifest parsed: ${manifest.installDirectory}, ${manifest.depots.size} depot(s)"
                )

                Result.success(manifest)
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to fetch manifest from $manifestUrl")
                Result.failure(e)
            }
        }

    /**
     * Fetch depot manifest (contains file list for a specific depot)
     *
     * @param manifestHash Hash from depot.manifest field
     * @return Parsed depot manifest
     */
    suspend fun fetchDepotManifest(manifestHash: String): Result<DepotManifest> =
        withContext(Dispatchers.IO) {
            try {
                val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
                if (credentials == null) {
                    return@withContext Result.failure(Exception("Not authenticated"))
                }

                // Build depot manifest URL
                val path = galaxyPath(manifestHash)
                val url = "$GOG_CDN/content-system/v2/meta/$path"

                Timber.tag("GOG").d("Fetching depot manifest: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch depot manifest: HTTP ${response.code}")
                    )
                }

                val depotBytes = response.body?.bytes()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                // Depot manifests are also compressed
                val depotStr = decompressManifest(depotBytes)

                val depotManifest = parser.parseDepotManifest(depotStr)

                Timber.tag("GOG").d(
                    "Depot manifest parsed: ${depotManifest.files.size} file(s), " +
                    "${depotManifest.directories.size} dir(s)"
                )

                Result.success(depotManifest)
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to fetch depot manifest $manifestHash")
                Result.failure(e)
            }
        }

    /**
     * Get secure download links for a product
     *
     * These are time-limited CDN URLs that work for all chunks in the product
     * No need to pass chunk hashes - the URLs work for any chunk
     *
     * @param productId Game or DLC product ID
     * @param path Path prefix (usually "/" for gen 2)
     * @param generation API generation (1 or 2)
     * @param root Optional root path (e.g., "/patches/store" for patches)
     * @return List of secure CDN URLs
     */
    suspend fun getSecureLink(
        productId: String,
        path: String = "/",
        generation: Int = 2,
        root: String? = null
    ): Result<SecureLinksResponse> = withContext(Dispatchers.IO) {
        try {
            val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
            if (credentials == null) {
                return@withContext Result.failure(Exception("Not authenticated"))
            }

            // Build secure link URL based on generation
            var url = if (generation == 2) {
                "$GOG_CONTENT_SYSTEM/products/$productId/secure_link?_version=2&generation=2&path=$path"
            } else {
                "$GOG_CONTENT_SYSTEM/products/$productId/secure_link?_version=2&type=depot&path=$path"
            }

            // Add root parameter if provided (for patches)
            if (root != null) {
                url += "&root=$root"
            }

            Timber.tag("GOG").d("Getting secure link for product $productId (gen $generation)")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to get secure link: HTTP ${response.code}")
                )
            }

            val jsonStr = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            // Log the actual response to debug parsing issues
            Timber.tag("GOG").d("Secure link response: $jsonStr")

            val secureLinks = parser.parseSecureLinks(jsonStr)

            Timber.tag("GOG").d("Got ${secureLinks.urls.size} secure URL(s) for product $productId")

            Result.success(secureLinks)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to get secure link for product $productId")
            Result.failure(e)
        }
    }

    /**
     * Decompress manifest data (auto-detects zlib or gzip)
     */
    private fun decompressManifest(data: ByteArray): String {
        // Check compression type by magic bytes
        val isGzipped = data.size >= 2 &&
                        data[0] == 0x1f.toByte() &&
                        data[1] == 0x8b.toByte()

        val isZlib = data.size >= 2 &&
                     data[0] == 0x78.toByte() &&
                     (data[1] == 0x9c.toByte() ||
                      data[1] == 0x01.toByte() ||
                      data[1] == 0xda.toByte())

        return when {
            isGzipped -> {
                // Decompress gzip
                val inputStream = GZIPInputStream(ByteArrayInputStream(data))
                inputStream.bufferedReader().use { it.readText() }
            }
            isZlib -> {
                // Decompress zlib (same as Epic chunk decompression)
                val inflater = Inflater()
                try {
                    inflater.setInput(data)
                    val outputStream = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)

                    while (!inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        outputStream.write(buffer, 0, count)
                    }

                    outputStream.toString("UTF-8")
                } finally {
                    inflater.end()
                }
            }
            else -> {
                // Try as plain text
                String(data, Charsets.UTF_8)
            }
        }
    }

    /**
     * Convert manifest hash to GOG Galaxy CDN path format
     *
     * Format: AA/BB/CCDD... where AA, BB are first two pairs of hex digits
     * Example: "abc123..." -> "ab/c1/abc123..."
     */
    private fun galaxyPath(hash: String): String {
        if (hash.length < 4) return hash
        return "${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"
    }
}
