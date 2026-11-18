package app.gamenative.data

import app.gamenative.utils.GameCompatibilityService
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

enum class CommunityCompatibilityVerdict(val sortPriority: Int) {
    WORKS(0),
    SHOULD_WORK(1),
    MAY_WORK(2),
    MIXED(3),
    UNKNOWN(4),
    WONT_WORK(5),
}

enum class CommunityEvidenceTier {
    SAME_DEVICE,
    SAME_SOC,
    SAME_GPU,
    COMPATIBLE_GPU_FAMILY,
    NONE,
}

enum class CommunityVerdictSource { UNKNOWN, SERVER }

enum class CommunityVerdictCaution { NONE, NO_MATCHING_SCOPE, MISSING_TIER }

@Serializable
data class CommunityRatingDistribution(
    val oneStar: Int = 0,
    val twoStar: Int = 0,
    val threeStar: Int = 0,
    val fourStar: Int = 0,
    val fiveStar: Int = 0,
) {
    val ratedTotal: Int get() = oneStar + twoStar + threeStar + fourStar + fiveStar
}

@Serializable
data class CommunityCompatibilitySummary(
    val verdict: CommunityCompatibilityVerdict,
    val evidenceTier: CommunityEvidenceTier,
    /** Recorded sessions, not a count of confirmed successful gameplay. */
    val sessionCount: Int = 0,
    /** Historical rated config uploads; not independent people or recent-only evidence. */
    val reportCount: Int = 0,
    val reportEvidenceTier: CommunityEvidenceTier = CommunityEvidenceTier.NONE,
    val medianFps: Int? = null,
    val hasDetailedReports: Boolean = false,
    val detailsLoaded: Boolean = false,
    /** Server-owned compatibility result from /api/game-compat. */
    val serverState: String? = null,
    val verdictSource: CommunityVerdictSource = CommunityVerdictSource.UNKNOWN,
    val verdictLoaded: Boolean = false,
    val loadFailed: Boolean = false,
    val isChecking: Boolean = false,
    val scopeCaution: CommunityVerdictCaution = CommunityVerdictCaution.NONE,
    val performanceCaution: Boolean = false,
) {
    companion object {
        fun unknown() = CommunityCompatibilitySummary(
            verdict = CommunityCompatibilityVerdict.UNKNOWN,
            evidenceTier = CommunityEvidenceTier.NONE,
        )
    }
}

object CommunityCompatibilityClassifier {
    fun fromCompatibilityResponse(
        response: GameCompatibilityService.GameCompatibilityResponse,
    ): CommunityCompatibilitySummary {
        val tierName = response.tier
        val metrics = tierName?.let(response.tiers::get)
        val state = response.state?.trim()?.lowercase()
        val evidenceTier = when (tierName) {
            "model" -> CommunityEvidenceTier.SAME_DEVICE
            "soc" -> CommunityEvidenceTier.SAME_SOC
            "gpu" -> CommunityEvidenceTier.SAME_GPU
            "family" -> CommunityEvidenceTier.COMPATIBLE_GPU_FAMILY
            else -> CommunityEvidenceTier.NONE
        }
        val decisiveState = state in setOf("great", "works", "may work", "unreliable", "broken")
        val caution = when {
            !decisiveState -> CommunityVerdictCaution.NONE
            evidenceTier == CommunityEvidenceTier.NONE -> CommunityVerdictCaution.NO_MATCHING_SCOPE
            metrics?.hasHardwareKey != true -> CommunityVerdictCaution.MISSING_TIER
            else -> CommunityVerdictCaution.NONE
        }
        val verdict = if (caution != CommunityVerdictCaution.NONE) {
            CommunityCompatibilityVerdict.UNKNOWN
        } else {
            when (state) {
                "great" -> CommunityCompatibilityVerdict.WORKS
                "works" -> CommunityCompatibilityVerdict.SHOULD_WORK
                "may work" -> CommunityCompatibilityVerdict.MAY_WORK
                "unreliable" -> CommunityCompatibilityVerdict.MIXED
                "broken" -> CommunityCompatibilityVerdict.WONT_WORK
                else -> CommunityCompatibilityVerdict.UNKNOWN
            }
        }
        val positive = verdict in setOf(
            CommunityCompatibilityVerdict.WORKS, CommunityCompatibilityVerdict.SHOULD_WORK, CommunityCompatibilityVerdict.MAY_WORK,
        )
        return CommunityCompatibilitySummary(
            verdict = verdict,
            evidenceTier = evidenceTier,
            sessionCount = metrics?.sessions ?: 0,
            medianFps = metrics?.medianFps?.takeIf { it.isFinite() && it > 0 }?.roundToInt(),
            serverState = response.state,
            verdictLoaded = true,
            verdictSource = CommunityVerdictSource.SERVER,
            scopeCaution = caution,
            performanceCaution = positive && metrics?.medianFps?.let { it.isFinite() && it > 0 && it < 30 } == true,
        )
    }

    /** Aggregate stars are context only. They lack the server's recency/deduplication rules. */
    fun withBulkRatings(
        summary: CommunityCompatibilitySummary,
        candidates: List<Pair<CommunityEvidenceTier, app.gamenative.utils.DeviceGameStatsService.DeviceGameStats?>>,
        statsAvailable: Boolean,
    ): CommunityCompatibilitySummary {
        val rated = candidates.filter { it.second?.ratings?.ratedTotal?.let { count -> count > 0 } == true }
        val selected = rated.firstOrNull { it.first == summary.evidenceTier } ?: rated.firstOrNull()
        val distribution = selected?.second?.ratings ?: CommunityRatingDistribution()
        return summary.copy(
            reportEvidenceTier = selected?.first ?: CommunityEvidenceTier.NONE,
            reportCount = distribution.ratedTotal,
            hasDetailedReports = selected != null,
            detailsLoaded = statsAvailable,
        )
    }
}
