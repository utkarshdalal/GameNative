package app.gamenative.data

import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.GameCompatibilityService.CompatibilityTierMetrics
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.sqrt
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

enum class CommunityVerdictCaution { NONE, NO_MATCHING_SCOPE, BROAD_NEGATIVE, MISSING_TIER }

enum class CommunityConfidenceCaution { NONE, LIMITED_EVIDENCE, CONFLICTING_FEEDBACK, MODEL_UNCONFIRMED, NEGATIVE_FEEDBACK, OLDER_EVIDENCE }

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
    val isCachedResultStale: Boolean = false,
    val scopeCaution: CommunityVerdictCaution = CommunityVerdictCaution.NONE,
    val ratingCaution: Boolean = false,
    /** Valid but small deciding-tier sample with no positive rated-device feedback. */
    val limitedRatingFeedback: Boolean = false,
    val confidenceCaution: CommunityConfidenceCaution = CommunityConfidenceCaution.NONE,
    val performanceCaution: Boolean = false,
) {
    companion object {
        fun unknown() = CommunityCompatibilitySummary(
            verdict = CommunityCompatibilityVerdict.UNKNOWN,
            evidenceTier = CommunityEvidenceTier.NONE,
        )
    }
}

/**
 * Apply the same confidence guards to the bulk response for every consumer. Historical config
 * upload histograms remain context, not independent votes or a second per-page classifier.
 */
object CommunityCompatibilityClassifier {
    private const val MIN_CORROBORATED_SESSIONS = 5
    private const val MIN_MIXED_ENTRIES = 4
    private const val MIN_NEGATIVE_ENTRIES = 5
    private const val POSITIVE_SHARE = 0.6
    private const val MAX_ACTIVITY_AGE_MILLIS = 180L * 24 * 60 * 60 * 1000

    fun fromCompatibilityResponse(
        response: GameCompatibilityService.GameCompatibilityResponse,
        nowMillis: Long = System.currentTimeMillis(),
    ): CommunityCompatibilitySummary {
        val serverTierName = response.tier
        val serverMetrics = serverTierName?.let(response.tiers::get)
        var tierName = serverTierName
        var metrics = serverMetrics
        var confidence = CommunityConfidenceCaution.NONE
        val state = response.state?.trim()?.lowercase()

        // A sparse model row must not outrank corroborated exact-GPU evidence. Do not escape
        // contradictory model feedback by choosing a more favorable broader row.
        if (state in setOf("great", "works") &&
            serverTierName == "model" &&
            serverMetrics?.hasHardwareKey == true &&
            !isCorroborated(serverMetrics, nowMillis) &&
            !hasUnfavorableFeedback(serverMetrics)
        ) {
            val gpu = response.tiers["gpu"]
            if (isCorroborated(gpu, nowMillis)) {
                tierName = "gpu"
                metrics = gpu
                confidence = CommunityConfidenceCaution.MODEL_UNCONFIRMED
            }
        }
        val evidenceTier = when (tierName) {
            "model" -> CommunityEvidenceTier.SAME_DEVICE
            "soc" -> CommunityEvidenceTier.SAME_SOC
            "gpu" -> CommunityEvidenceTier.SAME_GPU
            "family" -> CommunityEvidenceTier.COMPATIBLE_GPU_FAMILY
            else -> CommunityEvidenceTier.NONE
        }
        // Only use SoC when selected by the API; its presence must not re-rank other tiers.
        val knownScope = tierName in setOf("model", "soc", "gpu", "family")
        val exactScope = tierName in setOf("model", "soc", "gpu")
        val decisiveState = state in setOf("great", "works", "may work", "unreliable", "broken")
        val caution = when {
            !decisiveState -> CommunityVerdictCaution.NONE
            !knownScope -> CommunityVerdictCaution.NO_MATCHING_SCOPE
            metrics?.hasHardwareKey != true -> CommunityVerdictCaution.MISSING_TIER
            state in setOf("broken", "unreliable") && !exactScope -> CommunityVerdictCaution.BROAD_NEGATIVE
            else -> CommunityVerdictCaution.NONE
        }
        var verdict = if (caution != CommunityVerdictCaution.NONE) {
            CommunityCompatibilityVerdict.UNKNOWN
        } else {
            when (state) {
                // Great/Works express quality; the evidence tier separately describes hardware matching.
                "great", "works" -> when {
                    exactScope && state == "great" -> CommunityCompatibilityVerdict.WORKS
                    exactScope -> CommunityCompatibilityVerdict.SHOULD_WORK
                    knownScope -> CommunityCompatibilityVerdict.MAY_WORK
                    else -> CommunityCompatibilityVerdict.UNKNOWN
                }
                "may work" -> if (knownScope) CommunityCompatibilityVerdict.MAY_WORK else CommunityCompatibilityVerdict.UNKNOWN
                // Unreliable describes inconsistent reliability, not necessarily polarized star ratings.
                "unreliable" -> CommunityCompatibilityVerdict.MIXED
                "broken" -> CommunityCompatibilityVerdict.WONT_WORK
                else -> CommunityCompatibilityVerdict.UNKNOWN
            }
        }
        val support = ratingInterval(metrics?.okDevices, metrics?.ratedDevices)
        if (caution == CommunityVerdictCaution.NONE && state in setOf("great", "works", "unreliable")) {
            val rated = metrics?.ratedDevices ?: 0
            val ok = metrics?.okDevices ?: 0
            val oldActivity = isOldActivity(metrics, nowMillis)
            when {
                state == "unreliable" && support != null && rated >= MIN_NEGATIVE_ENTRIES && ok == 0 && !oldActivity -> {
                    verdict = CommunityCompatibilityVerdict.WONT_WORK
                    confidence = CommunityConfidenceCaution.NEGATIVE_FEEDBACK
                }
                exactScope &&
                    support != null &&
                    rated >= MIN_MIXED_ENTRIES &&
                    ok >= 2 &&
                    rated - ok >= 2 &&
                    (state == "unreliable" || ok.toDouble() / rated < POSITIVE_SHARE) &&
                    !oldActivity -> {
                    verdict = CommunityCompatibilityVerdict.MIXED
                    confidence = CommunityConfidenceCaution.CONFLICTING_FEEDBACK
                }
                state == "unreliable" -> {
                    if (oldActivity) confidence = CommunityConfidenceCaution.OLDER_EVIDENCE
                }
                exactScope && hasSessionSupport(metrics, nowMillis) && hasNoRatedFeedback(metrics) -> {
                    // Successful performance checks without ratings support Works, but not Great.
                    verdict = CommunityCompatibilityVerdict.SHOULD_WORK
                }
                !isCorroborated(metrics, nowMillis) -> {
                    verdict = CommunityCompatibilityVerdict.MAY_WORK
                    confidence = if (oldActivity) CommunityConfidenceCaution.OLDER_EVIDENCE else CommunityConfidenceCaution.LIMITED_EVIDENCE
                }
            }
        }
        val positive = verdict in setOf(
            CommunityCompatibilityVerdict.WORKS, CommunityCompatibilityVerdict.SHOULD_WORK, CommunityCompatibilityVerdict.MAY_WORK,
        )
        val ratingCaution = positive && support != null && support.second < 0.5
        return CommunityCompatibilitySummary(
            verdict = verdict,
            evidenceTier = evidenceTier,
            sessionCount = metrics?.sessions ?: 0,
            medianFps = metrics?.medianFps?.takeIf { it.isFinite() && it > 0 }?.roundToInt(),
            serverState = response.state,
            verdictLoaded = true,
            verdictSource = CommunityVerdictSource.SERVER,
            scopeCaution = caution,
            ratingCaution = ratingCaution,
            limitedRatingFeedback = positive && support != null && metrics?.okDevices == 0 && !ratingCaution,
            confidenceCaution = confidence,
            performanceCaution = positive && metrics?.medianFps?.let { it.isFinite() && it > 0 && it < 30 } == true,
        )
    }

    private fun isCorroborated(metrics: CompatibilityTierMetrics?, nowMillis: Long): Boolean {
        // Keep model-to-GPU fallback dependent on positive ratings, even when unrated sessions
        // are sufficient for a Works verdict at the selected tier.
        if (!hasSessionSupport(metrics, nowMillis)) return false
        if (ratingInterval(metrics?.okDevices, metrics?.ratedDevices) == null) return false
        return metrics!!.okDevices!! > 0 && metrics.okDevices.toDouble() / metrics.ratedDevices!! >= POSITIVE_SHARE
    }

    private fun hasSessionSupport(metrics: CompatibilityTierMetrics?, nowMillis: Long): Boolean {
        if (metrics == null ||
            !metrics.hasHardwareKey ||
            metrics.sessions < MIN_CORROBORATED_SESSIONS ||
            isOldActivity(metrics, nowMillis)
        ) {
            return false
        }
        if (metrics.playable !in 1..metrics.sessions) return false
        val rate = metrics.playableRate
        return rate != null && rate.isFinite() && rate in POSITIVE_SHARE..1.0
    }

    private fun hasNoRatedFeedback(metrics: CompatibilityTierMetrics?): Boolean =
        metrics != null &&
            (
                (metrics.ratedDevices == null && metrics.okDevices == null) ||
                    (metrics.ratedDevices == 0 && (metrics.okDevices == null || metrics.okDevices == 0))
                )

    private fun hasUnfavorableFeedback(metrics: CompatibilityTierMetrics?): Boolean =
        ratingInterval(metrics?.okDevices, metrics?.ratedDevices) != null &&
            metrics!!.okDevices!!.toDouble() / metrics.ratedDevices!! < POSITIVE_SHARE

    private fun isOldActivity(metrics: CompatibilityTierMetrics?, nowMillis: Long): Boolean {
        // This is the age of the most recent session, NOT the age/version of all ratings.
        val lastSeen = metrics?.lastSeen ?: return false
        val timestamp = runCatching { Instant.parse(lastSeen).toEpochMilli() }.getOrNull() ?: return false
        return nowMillis - timestamp > MAX_ACTIVITY_AGE_MILLIS
    }

    /** 95% Wilson bounds on positive rated-device entries at ONE tier; scopes are not additive. */
    internal fun ratingInterval(positive: Int?, total: Int?): Pair<Double, Double>? {
        if (total == null || total <= 0 || positive == null || positive !in 0..total) return null
        val n = total.toDouble()
        val proportion = positive / n
        val zSquared = 1.96 * 1.96
        val denominator = 1 + zSquared / n
        val center = (proportion + zSquared / (2 * n)) / denominator
        val margin = 1.96 * sqrt(proportion * (1 - proportion) / n + zSquared / (4 * n * n)) / denominator
        return (center - margin).coerceIn(0.0, 1.0) to (center + margin).coerceIn(0.0, 1.0)
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
