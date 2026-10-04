package app.gamenative.data

import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import app.gamenative.utils.GameCompatibilityService.CompatibilityTierMetrics
import app.gamenative.utils.GameCompatibilityService.GameCompatibilityResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityCompatibilitySocTest {
    private val strong = CompatibilityTierMetrics("Chipset", 10, 8, 0.8, 45.0, ratedDevices = 5, okDevices = 4)
    private val weak = strong.copy(sessions = 1, playable = 1, ratedDevices = null, okDevices = null)

    private fun classify(tier: String?, state: String = "Great", rows: Map<String, CompatibilityTierMetrics>) =
        CommunityCompatibilityClassifier.fromCompatibilityResponse(GameCompatibilityResponse("Game", state, tier, rows))

    @Test fun selectedChipsetMapsLikeGpu() {
        val rows = listOf(
            strong, weak, strong.copy(sessions = 4), strong.copy(playableRate = 0.599),
            strong.copy(playableRate = 0.6), strong.copy(playableRate = Double.NaN),
            strong.copy(playable = 11), strong.copy(playable = 0),
            strong.copy(ratedDevices = 0, okDevices = 0), strong.copy(ratedDevices = 1, okDevices = 2),
            strong.copy(ratedDevices = 1, okDevices = 0), strong.copy(ratedDevices = 4, okDevices = 2),
            strong.copy(ratedDevices = 4, okDevices = 0), strong.copy(ratedDevices = 5, okDevices = 0),
            strong.copy(lastSeen = "2026-01-01T00:00:00Z"), strong.copy(lastSeen = "malformed"),
            strong.copy(medianFps = 20.0), strong.copy(key = ""), strong.copy(key = " NULL "),
        )
        for (state in listOf("Great", "Works", "May Work", "Unreliable", "Broken", "Untested", "Future state")) {
            for (row in rows) {
                val gpu = classify("gpu", state, mapOf("gpu" to row))
                val soc = classify("soc", state, mapOf("soc" to row))
                assertEquals(
                    "$state: $row", gpu.copy(evidenceTier = CommunityEvidenceTier.SAME_SOC), soc,
                )
            }
        }
    }

    @Test fun missingSelectedChipsetCannotBorrowOtherHardwareEvidence() {
        val result = classify("soc", rows = mapOf("gpu" to strong, "model" to strong))
        assertEquals(CommunityCompatibilityVerdict.UNKNOWN, result.verdict)
        assertEquals(CommunityVerdictCaution.MISSING_TIER, result.scopeCaution)
    }

    @Test fun unselectedChipsetCannotChangeExistingDecisions() {
        for (tier in listOf("model", "gpu", "family", "all", "future", null)) {
            for (state in listOf("Great", "Works", "Unreliable", "Broken", "May Work", "Untested")) {
                for (model in listOf(strong, weak, strong.copy(ratedDevices = 4, okDevices = 2))) {
                    val rows = mapOf("model" to model, "gpu" to strong, "family" to strong, "all" to strong)
                    val before = classify(tier, state, rows)
                    for (soc in listOf(strong, weak, strong.copy(ratedDevices = 8, okDevices = 0))) {
                        assertEquals(before, classify(tier, state, rows + ("soc" to soc)))
                    }
                }
            }
        }
    }

    @Test fun sharedConfigsKeepTheirOwnScopeAndDoNotReplaceChipsetEvidence() {
        val summary = classify("soc", rows = mapOf("soc" to strong))
        val stats = DeviceGameStats(0, 0, 0, 0, CommunityRatingDistribution(oneStar = 20))
        for (tier in listOf(CommunityEvidenceTier.SAME_DEVICE, CommunityEvidenceTier.SAME_GPU)) {
            val result = CommunityCompatibilityClassifier.withBulkRatings(summary, listOf(tier to stats), true)
            assertEquals(summary.verdict, result.verdict)
            assertEquals(CommunityEvidenceTier.SAME_SOC, result.evidenceTier)
            assertEquals(tier, result.reportEvidenceTier)
            assertEquals(20, result.reportCount)
            assertEquals(10, result.sessionCount)
        }
    }
}
