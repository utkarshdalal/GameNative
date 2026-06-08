package app.gamenative.utils.downloader

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.service.SteamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Utility for downloading container pattern files (extras, container patterns, proton patterns) from the manifest server.
 * Supports fallback to bundled assets for backward compatibility.
 */
object ContainerFilesDownloader {

    const val CONTAINER_FILES_MANIFEST_FILE = "container_files_download.json"
    const val CONTAINER_FILES_CACHE_DIR = "assets/container_files"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ContainerFilesManifest(
        val version: Int,
        val updatedAt: String,
        val components: List<ContainerFileComponent>
    )

    @Serializable
    data class ContainerFileComponent(
        val id: String,
        val name: String,
        val url: String
    )

    /**
     * Ensures a container file component is available, either from cache, server download, or bundled assets.
     *
     * @param context Android context
     * @param componentId Component identifier (e.g., "extras", "container_pattern_common")
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return File pointing to the downloaded component, or null if using bundled assets (legacy variant)
     */
    suspend fun ensureContainerFileAvailable(
        context: Context,
        componentId: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        // Validate component exists in manifest first (for both legacy and modern variants)
        val manifest = loadContainerFilesManifest(context)
        val component = manifest.components.find { it.id == componentId }
            ?: throw Exception("Container file $componentId not found in $CONTAINER_FILES_MANIFEST_FILE")

        // Legacy variant: use bundled assets
        if (!BuildConfig.MODERN_ANDROID) {
            Timber.d("Legacy variant: Container file $componentId will be extracted from bundled assets")
            return@withContext null
        }

        // Modern variant: download from server
        // Check if already downloaded and cached
        val destFile = File(context.filesDir, "$CONTAINER_FILES_CACHE_DIR/$componentId.tzst")
        if (destFile.exists() && destFile.length() > 0) {
            Timber.d("Using cached container file: $componentId at ${destFile.absolutePath}")
            return@withContext destFile
        }

        // Download from server using local manifest
        Timber.i("Downloading container file: $componentId from server")

        destFile.parentFile?.mkdirs()

        try {
            SteamService.fetchFileWithFallback(
                fileName = "container_files/${component.name}",
                dest = destFile,
                context = context,
                onProgress = onProgress
            )
            Timber.i("Successfully downloaded container file: $componentId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to download container file: $componentId")
            destFile.delete()
            throw e
        }

        return@withContext destFile
    }

    /**
     * Loads the container files manifest from assets.
     */
    private fun loadContainerFilesManifest(context: Context): ContainerFilesManifest {
        return try {
            val manifestJson = context.assets.open(CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString<ContainerFilesManifest>(manifestJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load $CONTAINER_FILES_MANIFEST_FILE")
            throw Exception("Failed to load container files manifest: ${e.message}", e)
        }
    }

    /**
     * Clears the cached container files to free up space.
     */
    fun clearCache(context: Context) {
        val cacheDir = File(context.filesDir, CONTAINER_FILES_CACHE_DIR)
        if (cacheDir.exists() && cacheDir.isDirectory) {
            cacheDir.listFiles()?.forEach { it.delete() }
            Timber.i("Cleared container files cache")
        }
    }

    /**
     * Gets the total size of cached container files.
     */
    fun getCacheSize(context: Context): Long {
        val cacheDir = File(context.filesDir, CONTAINER_FILES_CACHE_DIR)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return 0L

        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Preloads all container files in the background.
     * This ensures files are downloaded and cached before they're needed.
     * Safe to call on app startup - runs in background and doesn't block.
     */
    suspend fun preloadAllContainerFiles(context: Context) = withContext(Dispatchers.IO) {
        if (!BuildConfig.MODERN_ANDROID) {
            Timber.d("Legacy variant: Skipping container files preload (using bundled assets)")
            return@withContext
        }

        try {
            val manifest = loadContainerFilesManifest(context)
            Timber.i("Preloading ${manifest.components.size} container files in background")

            manifest.components.forEach { component ->
                try {
                    ensureContainerFileAvailable(context, component.id) { progress ->
                        // Silent download, no UI updates
                        if (progress >= 1.0f) {
                            Timber.d("Preloaded container file: ${component.id}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to preload container file: ${component.id}")
                    // Continue with other files even if one fails
                }
            }

            Timber.i("Container files preload complete")
        } catch (e: Exception) {
            Timber.e(e, "Failed to preload container files")
        }
    }
}

/**
 * Functional interface for Java interop to handle progress callbacks.
 */
@FunctionalInterface
interface ProgressCallback {
    fun onProgress(progress: Float)
}

/**
 * Blocking wrapper for Java interop with progress callback.
 * Calls the suspend function using runBlocking.
 */
@JvmOverloads
@JvmName("ensureContainerFileAvailableBlocking")
fun ensureContainerFileAvailableBlocking(
    context: Context,
    componentId: String,
    onProgress: ProgressCallback? = null
): File? {
    return runBlocking {
        ContainerFilesDownloader.ensureContainerFileAvailable(context, componentId) { progress ->
            onProgress?.onProgress(progress)
        }
    }
}
