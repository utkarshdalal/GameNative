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
 * Utility for downloading DXWrapper components (DXVK, VKD3D, D8VK) from the manifest server.
 * Supports fallback to bundled assets for backward compatibility.
 */
object DXWrapperDownloader {

    const val DXWRAPPER_MANIFEST_FILE = "dxwrapper_download.json"
    const val DXWRAPPER_CACHE_DIR = "assets/dxwrapper"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DXWrapperManifest(
        val version: Int,
        val updatedAt: String,
        val components: List<DXWrapperComponent>
    )

    @Serializable
    data class DXWrapperComponent(
        val id: String,
        val name: String,
        val url: String
    )

    /**
     * Ensures a DXWrapper component is available, either from cache, server download, or bundled assets.
     *
     * @param context Android context
     * @param componentId Component identifier (e.g., "dxvk-2.7.1", "vkd3d-3.0b")
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return File pointing to the downloaded component, or null if using bundled assets (legacy variant)
     */
    suspend fun ensureDXWrapperAvailable(
        context: Context,
        componentId: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        // Validate component exists in manifest first (for both legacy and modern variants)
        val manifest = loadDXWrapperManifest(context)
        val component = manifest.components.find { it.id == componentId }
            ?: return@withContext null

        // Legacy variant: use bundled assets
        if (!BuildConfig.MODERN_ANDROID) {
            Timber.d("Legacy variant: DXWrapper $componentId will be extracted from bundled assets")
            return@withContext null
        }

        // Modern variant: prefer a bundled asset (the common defaults) over downloading
        if (assetExists(context, "dxwrapper/$componentId.tzst")) {
            Timber.d("Using bundled dxwrapper asset: $componentId")
            return@withContext null
        }

        // Modern variant: download from server
        // Check if already downloaded and cached
        val destFile = File(context.filesDir, "$DXWRAPPER_CACHE_DIR/${component.name}")
        if (destFile.exists() && destFile.length() > 0) {
            Timber.d("Using cached dxwrapper: $componentId at ${destFile.absolutePath}")
            return@withContext destFile
        }

        // Download from server using local manifest
        Timber.i("Downloading dxwrapper: $componentId from server")

        destFile.parentFile?.mkdirs()

        try {
            SteamService.fetchFileWithFallback(
                fileName = "dxwrapper/${component.name}",
                dest = destFile,
                context = context,
                onProgress = onProgress
            )
            Timber.i("Successfully downloaded dxwrapper: $componentId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to download dxwrapper: $componentId")
            destFile.delete()
            throw e
        }

        return@withContext destFile
    }

    private fun assetExists(context: Context, path: String): Boolean {
        return try {
            context.assets.open(path).close()
            true
        } catch (e: java.io.IOException) {
            false
        }
    }

    /**
     * Loads the dxwrapper manifest from assets.
     */
    private fun loadDXWrapperManifest(context: Context): DXWrapperManifest {
        return try {
            val manifestJson = context.assets.open(DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString<DXWrapperManifest>(manifestJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load $DXWRAPPER_MANIFEST_FILE")
            throw Exception("Failed to load dxwrapper manifest: ${e.message}", e)
        }
    }

    /**
     * Clears the cached dxwrapper files to free up space.
     */
    fun clearCache(context: Context) {
        val cacheDir = File(context.filesDir, DXWRAPPER_CACHE_DIR)
        if (cacheDir.exists() && cacheDir.isDirectory) {
            cacheDir.listFiles()?.forEach { it.delete() }
            Timber.i("Cleared dxwrapper cache")
        }
    }

    /**
     * Gets the total size of cached dxwrapper files.
     */
    fun getCacheSize(context: Context): Long {
        val cacheDir = File(context.filesDir, DXWRAPPER_CACHE_DIR)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return 0L

        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
