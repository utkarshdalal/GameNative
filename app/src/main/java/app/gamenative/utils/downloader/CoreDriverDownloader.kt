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
 * Utility for downloading core driver components (Adreno, SD8Elite) from the manifest server.
 * Supports fallback to bundled assets for backward compatibility.
 */
object CoreDriverDownloader {

    const val CORE_DRIVERS_MANIFEST_FILE = "core_drivers_download.json"
    const val CORE_DRIVERS_CACHE_DIR = "assets/core_drivers"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class CoreDriverManifest(
        val version: Int,
        val updatedAt: String,
        val components: List<CoreDriverComponent>
    )

    @Serializable
    data class CoreDriverComponent(
        val id: String,
        val name: String,
        val url: String
    )

    /**
     * Ensures a core driver component is available, either from cache, server download, or bundled assets.
     *
     * @param context Android context
     * @param componentName Component name (e.g., "Adreno_805_adpkg.zip", "SD8Elite_800.51.zip")
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return File pointing to the downloaded component, or null if using bundled assets (legacy variant)
     */
    suspend fun ensureCoreDriverAvailable(
        context: Context,
        componentName: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        // Extract component ID from name (remove .zip extension)
        val componentId = componentName.substringBeforeLast(".zip")

        // Validate component exists in manifest first (for both legacy and modern variants)
        val manifest = loadCoreDriverManifest(context)
        val component = manifest.components.find { it.id == componentId }
            ?: throw Exception("Core driver $componentId not found in $CORE_DRIVERS_MANIFEST_FILE")

        // Legacy variant: use bundled assets
        if (!BuildConfig.MODERN_ANDROID) {
            Timber.d("Legacy variant: Core driver $componentName will be extracted from bundled assets")
            return@withContext null
        }

        // Modern variant: download from server
        // Check if already downloaded and cached
        val destFile = File(context.filesDir, "$CORE_DRIVERS_CACHE_DIR/$componentName")
        if (destFile.exists() && destFile.length() > 0) {
            Timber.d("Using cached core driver: $componentName at ${destFile.absolutePath}")
            return@withContext destFile
        }

        // Download from server using local manifest
        Timber.i("Downloading core driver: $componentName from server")

        destFile.parentFile?.mkdirs()

        try {
            SteamService.Companion.fetchFileWithFallback(
                fileName = "core_drivers/$componentName",
                dest = destFile,
                context = context,
                onProgress = onProgress
            )
            Timber.i("Successfully downloaded core driver: $componentName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to download core driver: $componentName")
            destFile.delete()
            throw e
        }

        return@withContext destFile
    }

    /**
     * Loads the core driver manifest from assets.
     */
    private fun loadCoreDriverManifest(context: Context): CoreDriverManifest {
        return try {
            val manifestJson = context.assets.open(CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString<CoreDriverManifest>(manifestJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load $CORE_DRIVERS_MANIFEST_FILE")
            throw Exception("Failed to load core driver manifest: ${e.message}", e)
        }
    }

    /**
     * Clears the cached core driver files to free up space.
     */
    fun clearCache(context: Context) {
        val cacheDir = File(context.filesDir, CORE_DRIVERS_CACHE_DIR)
        if (cacheDir.exists() && cacheDir.isDirectory) {
            cacheDir.listFiles()?.forEach { it.delete() }
            Timber.i("Cleared core driver cache")
        }
    }

    /**
     * Gets the total size of cached core driver files.
     */
    fun getCacheSize(context: Context): Long {
        val cacheDir = File(context.filesDir, CORE_DRIVERS_CACHE_DIR)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return 0L

        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
