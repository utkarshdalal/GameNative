package app.gamenative.utils

import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Persistent cache for the GPU-wide game stats blob (keyed by GPU only) with a 6-hour TTL.
 *
 * Mirrors [DeviceGameStatsCache] but sources from the gpu-game-stats endpoint. Used for the
 * "successful runs on your GPU" stat, which aggregates across all devices with the same GPU.
 */
object GpuGameStatsCache {
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private var inMemory: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap()
    private var loadedTimestamp: Long = 0L
    private var cacheLoaded = false

    @Serializable
    private data class CachedStats(
        val stats: Map<String, Map<String, DeviceGameStatsData>>,
        val timestamp: Long,
    )

    @Serializable
    private data class DeviceGameStatsData(
        val successfulRuns: Int,
        val medianFps: Int,
        val fiveStarReviews: Int,
        val medianSessionSec: Int,
    )

    private fun DeviceGameStats.toData() = DeviceGameStatsData(
        successfulRuns, medianFps, fiveStarReviews, medianSessionSec,
    )

    private fun DeviceGameStatsData.toStats() = DeviceGameStats(
        successfulRuns, medianFps, fiveStarReviews, medianSessionSec,
    )

    @Synchronized
    private fun loadCache() {
        if (cacheLoaded) return
        try {
            val cacheJson = PrefManager.gpuGameStatsCache
            if (cacheJson.isNotEmpty() && cacheJson != "{}") {
                val cached = Json.decodeFromString<CachedStats>(cacheJson)
                inMemory = cached.stats.mapNotNull { (platform, games) ->
                    val source = runCatching { GameSource.valueOf(platform) }.getOrNull()
                        ?: return@mapNotNull null
                    source to games.mapValues { it.value.toStats() }
                }.toMap()
                loadedTimestamp = cached.timestamp
                Timber.tag("GpuGameStatsCache").d("Loaded ${inMemory.values.sumOf { it.size }} cached game stats")
            }
        } catch (e: Exception) {
            Timber.tag("GpuGameStatsCache").e(e, "Failed to load cache from persistent storage")
        }
        cacheLoaded = true
    }

    private fun saveCache(data: Map<GameSource, Map<String, DeviceGameStats>>, timestamp: Long) {
        try {
            val serializable = CachedStats(
                stats = data.entries.associate { (source, games) ->
                    source.name to games.mapValues { it.value.toData() }
                },
                timestamp = timestamp,
            )
            PrefManager.gpuGameStatsCache = Json.encodeToString(serializable)
            Timber.tag("GpuGameStatsCache").d("Saved ${data.values.sumOf { it.size }} game stats to persistent storage")
        } catch (e: Exception) {
            Timber.tag("GpuGameStatsCache").e(e, "Failed to save cache to persistent storage")
        }
    }

    /**
     * Fetches and persists fresh stats if the cache is empty or older than the TTL.
     */
    suspend fun refreshIfStale(gpuName: String, modernBuild: Boolean) {
        loadCache()

        val now = System.currentTimeMillis()
        // Use the timestamp (not emptiness) so a valid empty response is still cached for the TTL.
        if (loadedTimestamp != 0L && now - loadedTimestamp < CACHE_TTL_MS) {
            Timber.tag("GpuGameStatsCache").d("Cache is fresh, skipping fetch")
            return
        }

        val fetched = DeviceGameStatsService.fetchForGpu(gpuName, modernBuild)
        if (fetched != null) {
            inMemory = fetched
            loadedTimestamp = now
            saveCache(fetched, now)
        }
    }

    /** Gets stats for a single game, if available. */
    fun getStats(source: GameSource, gameName: String): DeviceGameStats? {
        loadCache()
        return inMemory[source]?.get(gameName)
    }

    /** Returns all cached stats, grouped by platform. */
    fun getAll(): Map<GameSource, Map<String, DeviceGameStats>> {
        loadCache()
        return inMemory
    }

    /** Clears the entire cache (both memory and persistent storage). */
    fun clear() {
        inMemory = emptyMap()
        loadedTimestamp = 0L
        PrefManager.gpuGameStatsCache = "{}"
        Timber.tag("GpuGameStatsCache").d("Cache cleared")
    }
}
