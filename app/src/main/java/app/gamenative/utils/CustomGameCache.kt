package app.gamenative.utils

import java.io.File
import kotlin.math.abs
import timber.log.Timber

/**
 * Manages caching of Custom Game app IDs and their folder paths.
 * Provides fast lookups and automatic invalidation when manual folders change.
 */
internal object CustomGameCache {
    // Cache: appId (Int) -> folder path (String)
    private var appIdCache: Map<Int, String>? = null
    private var cacheManualFolders: Set<String>? = null

    /**
     * Builds the appId cache by scanning all Custom Game manual folders.
     * Returns a map of appId (Int) -> folder path (String).
     */
    fun buildCache(
        getManualFolders: () -> Set<String>,
        readGameIdFromFile: (File) -> Int?
    ): Map<Int, String> {
        val cache = mutableMapOf<Int, String>()
        
        val manualFolders = getManualFolders()
        for (path in manualFolders) {
            val folder = File(path)
            if (!folder.exists() || !folder.isDirectory) continue

            val folderId = readGameIdFromFile(folder)
                ?: abs(folder.absolutePath.hashCode()).let { if (it == 0) 1 else it }

            cache[folderId] = folder.absolutePath
        }

        Timber.tag("CustomGameCache").d("Built appId cache with ${cache.size} entries")
        return cache
    }

    /**
     * Gets or rebuilds the appId cache if needed.
     * Cache is invalidated when Custom Game manual folders change.
     */
    fun getOrRebuildCache(
        getManualFolders: () -> Set<String>,
        readGameIdFromFile: (File) -> Int?
    ): Map<Int, String> {
        val currentManualFolders = getManualFolders()
        val cachedManual = cacheManualFolders
        
        // Rebuild if manual folders changed or cache is null
        if (appIdCache == null || cachedManual != currentManualFolders) {
            appIdCache = buildCache(getManualFolders, readGameIdFromFile)
            cacheManualFolders = currentManualFolders
        }
        
        return appIdCache!!
    }

    /**
     * Invalidates the appId cache, forcing a rebuild on next access.
     * Call this when Custom Game paths change, after deletion, or after manual refresh.
     */
    fun invalidate() {
        appIdCache = null
        cacheManualFolders = null
        Timber.tag("CustomGameCache").d("AppId cache invalidated")
    }

    /**
     * Updates the cache with a new entry.
     * Removes any stale entries with the same path but different appId to maintain consistency.
     * Used for incremental updates when scanning new games.
     */
    fun addEntry(appId: Int, folderPath: String) {
        if (appIdCache != null) {
            appIdCache = appIdCache!!.toMutableMap().apply {
                // Remove any stale entries with the same path but different appId
                val staleEntries = filter { it.value == folderPath && it.key != appId }.keys
                staleEntries.forEach { remove(it) }
                
                // Add or update the entry with the correct appId
                put(appId, folderPath)
            }
        }
    }

    /**
     * Gets the current cache (without rebuilding).
     * Returns null if cache is not built yet.
     */
    fun getCache(): Map<Int, String>? = appIdCache
}

