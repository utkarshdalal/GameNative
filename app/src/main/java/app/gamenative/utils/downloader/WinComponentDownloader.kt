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
 * Utility for downloading Wine components from the manifest server.
 * Supports fallback to bundled assets for backward compatibility.
 */
object WinComponentDownloader {

    const val WINCOMPONENTS_MANIFEST_FILE = "wincomponents_download.json"
    const val WINCOMPONENTS_CACHE_DIR = "assets/wincomponents"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class WinComponentManifest(
        val version: Int,
        val updatedAt: String,
        val components: List<WinComponent>
    )

    @Serializable
    data class WinComponent(
        val id: String,
        val name: String,
        val url: String
    )

    /**
     * Ensures a Wine component is available, either from cache, server download, or bundled assets.
     *
     * @param context Android context
     * @param componentId Component identifier (e.g., "direct3d", "directsound")
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return File pointing to the downloaded component, or null if using bundled assets (legacy variant)
     */
    suspend fun ensureWinComponentAvailable(
        context: Context,
        componentId: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        // Validate component exists in manifest first (for both legacy and modern variants)
        val manifest = loadWinComponentManifest(context)
        val component = manifest.components.find { it.id == componentId }
            ?: throw Exception("WinComponent $componentId not found in $WINCOMPONENTS_MANIFEST_FILE")

        // Legacy variant: use bundled assets
        if (!BuildConfig.MODERN_ANDROID) {
            Timber.d("Legacy variant: WinComponent $componentId will be extracted from bundled assets")
            // Return null to indicate using bundled assets
            return@withContext null
        }

        // Modern variant: download from server
        // Check if already downloaded and cached
        val destFile = File(context.filesDir, "$WINCOMPONENTS_CACHE_DIR/$componentId.tzst")
        if (destFile.exists() && destFile.length() > 0) {
            Timber.d("Using cached wincomponent: $componentId at ${destFile.absolutePath}")
            return@withContext destFile
        }

        // Download from server using local manifest
        Timber.i("Downloading wincomponent: $componentId from server")

        destFile.parentFile?.mkdirs()

        try {
            SteamService.Companion.fetchFileWithFallback(
                fileName = "wincomponents/$componentId.tzst",
                dest = destFile,
                context = context,
                onProgress = onProgress
            )
            Timber.i("Successfully downloaded wincomponent: $componentId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to download wincomponent: $componentId")
            destFile.delete()
            throw e
        }

        return@withContext destFile
    }

    /**
     * Loads the wincomponents manifest from assets.
     */
    private fun loadWinComponentManifest(context: Context): WinComponentManifest {
        return try {
            val manifestJson = context.assets.open(WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString<WinComponentManifest>(manifestJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load $WINCOMPONENTS_MANIFEST_FILE")
            throw Exception("Failed to load wincomponents manifest: ${e.message}", e)
        }
    }


    /**
     * Clears the cached wincomponents to free up space.
     */
    fun clearCache(context: Context) {
        val cacheDir = File(context.filesDir, WINCOMPONENTS_CACHE_DIR)
        if (cacheDir.exists() && cacheDir.isDirectory) {
            cacheDir.listFiles()?.forEach { it.delete() }
            Timber.i("Cleared wincomponent cache")
        }
    }

    /**
     * Gets the total size of cached wincomponents.
     */
    fun getCacheSize(context: Context): Long {
        val cacheDir = File(context.filesDir, WINCOMPONENTS_CACHE_DIR)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return 0L

        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
