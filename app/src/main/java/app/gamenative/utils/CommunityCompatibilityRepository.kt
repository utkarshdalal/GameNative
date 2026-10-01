package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.data.CommunityCompatibilityClassifier
import app.gamenative.data.CommunityCompatibilitySummary
import app.gamenative.data.CommunityEvidenceTier
import app.gamenative.data.GameSource
import com.winlator.core.GPUInformation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Library badges, ordering and detail cards share this bulk-backed view.
 * Individual configs are fetched only when the user opens the config browser.
 */
object CommunityCompatibilityRepository {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val statsMutex = Mutex()
    private val loader = CompatibilityBatchLoader(
        cached = GameCompatibilityCache::getCached,
        publish = GameCompatibilityCache::cacheAll,
        fetch = GameCompatibilityService::fetchCompatibility,
        changed = { changes.update { it + 1 } },
    )

    fun isRefreshing() = loader.isRefreshing()

    fun cachedVerdict(gameName: String): CommunityCompatibilitySummary {
        val fresh = GameCompatibilityCache.getCached(gameName)
        val response = fresh ?: GameCompatibilityCache.getLastKnown(gameName)
        return (
            response?.let { CommunityCompatibilityClassifier.fromCompatibilityResponse(it) }
                ?: CommunityCompatibilitySummary.unknown()
            ).copy(
            isCachedResultStale = response != null && fresh == null,
            isChecking = loader.isLoading(gameName),
            loadFailed = loader.hasFailed(gameName),
        )
    }

    fun cachedSummary(source: GameSource, gameName: String): CommunityCompatibilitySummary {
        val verdict = cachedVerdict(gameName)
        val candidates = listOf(
            CommunityEvidenceTier.SAME_DEVICE to DeviceGameStatsCache.getStats(source, gameName),
            CommunityEvidenceTier.SAME_GPU to GpuGameStatsCache.getStats(source, gameName),
            CommunityEvidenceTier.COMPATIBLE_GPU_FAMILY to GpuFamilyGameStatsCache.getStats(source, gameName),
        )
        return CommunityCompatibilityClassifier.withBulkRatings(
            summary = verdict,
            candidates = candidates,
            statsAvailable = DeviceGameStatsCache.isAvailable() &&
                GpuGameStatsCache.isAvailable() &&
                GpuFamilyGameStatsCache.isAvailable() &&
                candidates.all { it.second == null || it.second?.ratings != null },
        )
    }

    suspend fun refreshGames(gameNames: List<String>, gpuName: String, force: Boolean = false): Boolean {
        GameCompatibilityCache.initialize()
        return loader.refresh(gameNames, gpuName, force)
    }

    suspend fun refreshStats(gpuName: String, force: Boolean = false): Boolean = statsMutex.withLock {
        DeviceGameStatsCache.initialize()
        GpuGameStatsCache.initialize()
        GpuFamilyGameStatsCache.initialize()
        changes.update { it + 1 }
        if (gpuName.isBlank() || gpuName == "Unknown GPU") return@withLock false
        try {
            val device = DeviceGameStatsCache.refreshIfStale(
                HardwareUtils.getMachineName(), gpuName, BuildConfig.MODERN_ANDROID, force,
            )
            val gpu = GpuGameStatsCache.refreshIfStale(gpuName, BuildConfig.MODERN_ANDROID, force)
            val family = GpuFamilyGameStatsCache.refreshIfStale(gpuName, BuildConfig.MODERN_ANDROID, force)
            device && gpu && family
        } finally {
            changes.update { it + 1 }
        }
    }

    suspend fun refreshGame(context: Context, gameName: String, force: Boolean = false): Boolean {
        val gpu = runCatching { GPUInformation.getRenderer(context) }.getOrNull().orEmpty().trim()
        val verdictLoaded = refreshGames(listOf(gameName), gpu, force)
        val statsLoaded = refreshStats(gpu, force)
        return verdictLoaded && statsLoaded
    }
}
