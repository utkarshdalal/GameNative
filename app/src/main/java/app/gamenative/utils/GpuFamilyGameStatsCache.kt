package app.gamenative.utils

import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.data.GameSource

/** Shared-cache wrapper for the gpufamily bulk endpoint. */
object GpuFamilyGameStatsCache {
    private val cache by lazy {
        BulkGameStatsCache(
            read = { PrefManager.gpuFamilyGameStatsCache },
            write = PrefManager::persistGpuFamilyGameStatsCache,
            scope = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.HARDWARE}/${Build.BOARD}/${BuildConfig.MODERN_ANDROID}",
        )
    }

    suspend fun refreshIfStale(gpuName: String, modernBuild: Boolean, force: Boolean = false): Boolean =
        cache.refresh(force) { DeviceGameStatsService.fetchForFamily(gpuName, modernBuild) }

    suspend fun initialize() = cache.initialize()

    fun getStats(source: GameSource, gameName: String) = cache.getAll()[source]?.get(gameName)
    fun getAll() = cache.getAll()
    fun isAvailable() = cache.isAvailable()
}
