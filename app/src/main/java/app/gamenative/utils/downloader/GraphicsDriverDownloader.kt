package app.gamenative.utils.downloader

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.service.SteamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Utility for downloading graphics driver components (Turnip, Zink, Virgl, Vortek, Adrenotools, Wrappers) from the manifest server.
 * Supports fallback to bundled assets for backward compatibility.
 */
object GraphicsDriverDownloader {

    const val GRAPHICS_DRIVER_MANIFEST_FILE = "graphics_driver_download.json"
    const val GRAPHICS_DRIVER_CACHE_DIR = "assets/graphics_driver"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GraphicsDriverManifest(
        val version: Int,
        val updatedAt: String,
        val components: List<GraphicsDriverComponent>
    )

    @Serializable
    data class GraphicsDriverComponent(
        val id: String,
        val name: String,
        val url: String
    )

    /**
     * Ensures a graphics driver component is available, either from cache, server download, or bundled assets.
     *
     * @param context Android context
     * @param componentId Component identifier (e.g., "turnip-25.3.0", "vortek-2.1")
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return File pointing to the downloaded component, or null if using bundled assets (legacy variant)
     */
    suspend fun ensureGraphicsDriverAvailable(
        context: Context,
        componentId: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        // Validate component exists in manifest first (for both legacy and modern variants)
        val manifest = loadGraphicsDriverManifest(context)
        val component = manifest.components.find { it.id == componentId }
            ?: return@withContext null

        // Legacy variant: use bundled assets
        if (!BuildConfig.MODERN_ANDROID) {
            Timber.d("Legacy variant: Graphics driver $componentId will be extracted from bundled assets")
            return@withContext null
        }

        // Modern variant: download from server
        // Check if already downloaded and cached

        // Use the actual filename from manifest (handles .tar.xz case)
        val destFile = File(context.filesDir, "$GRAPHICS_DRIVER_CACHE_DIR/${component.name}")
        if (destFile.exists() && destFile.length() > 0) {
            Timber.d("Using cached graphics driver: $componentId at ${destFile.absolutePath}")
            return@withContext destFile
        }

        // Download from server using local manifest
        Timber.i("Downloading graphics driver: $componentId from server")

        destFile.parentFile?.mkdirs()

        try {
            SteamService.Companion.fetchFileWithFallback(
                fileName = "graphics_driver/${component.name}",
                dest = destFile,
                context = context,
                onProgress = onProgress
            )
            Timber.i("Successfully downloaded graphics driver: $componentId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to download graphics driver: $componentId")
            destFile.delete()
            throw e
        }

        return@withContext destFile
    }

    /**
     * Loads the graphics driver manifest from assets.
     */
    private fun loadGraphicsDriverManifest(context: Context): GraphicsDriverManifest {
        if (BuildConfig.MODERN_ANDROID) {
            remoteManifest(context)?.let { return it }
        }
        return try {
            val manifestJson = context.assets.open(GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString<GraphicsDriverManifest>(manifestJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load $GRAPHICS_DRIVER_MANIFEST_FILE")
            throw Exception("Failed to load graphics driver manifest: ${e.message}", e)
        }
    }

    private fun remoteManifest(context: Context): GraphicsDriverManifest? {
        val cached = File(context.filesDir, "$GRAPHICS_DRIVER_CACHE_DIR/$GRAPHICS_DRIVER_MANIFEST_FILE")
        for (url in REMOTE_MANIFEST_URLS) {
            try {
                val request = okhttp3.Request.Builder().url(url).header("Cache-Control", "no-cache").build()
                manifestClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body.string()
                    val parsed = json.decodeFromString<GraphicsDriverManifest>(body)
                    cached.parentFile?.mkdirs()
                    cached.writeText(body)
                    Timber.i("Using remote graphics driver manifest from $url (updated ${parsed.updatedAt})")
                    return parsed
                }
            } catch (e: Exception) {
                Timber.w(e, "Remote graphics driver manifest unavailable at $url")
            }
        }
        return try {
            if (cached.exists()) json.decodeFromString<GraphicsDriverManifest>(cached.readText()) else null
        } catch (e: Exception) {
            null
        }
    }

    private val REMOTE_MANIFEST_URLS = listOf(
        "https://downloads.gamenative.app/$GRAPHICS_DRIVER_MANIFEST_FILE",
        "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/$GRAPHICS_DRIVER_MANIFEST_FILE",
    )

    private val manifestClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Clears the cached graphics driver files to free up space.
     */
    fun clearCache(context: Context) {
        val cacheDir = File(context.filesDir, GRAPHICS_DRIVER_CACHE_DIR)
        if (cacheDir.exists() && cacheDir.isDirectory) {
            cacheDir.listFiles()?.forEach { it.delete() }
            Timber.i("Cleared graphics driver cache")
        }
    }

    /**
     * Gets the total size of cached graphics driver files.
     */
    fun getCacheSize(context: Context): Long {
        val cacheDir = File(context.filesDir, GRAPHICS_DRIVER_CACHE_DIR)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return 0L

        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
