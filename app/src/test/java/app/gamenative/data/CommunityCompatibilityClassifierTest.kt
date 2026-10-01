package app.gamenative.data

import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import app.gamenative.utils.GameCompatibilityService.CompatibilityTierMetrics
import app.gamenative.utils.GameCompatibilityService.GameCompatibilityResponse
import org.junit.Assert.*
import org.junit.Test

class CommunityCompatibilityClassifierTest {
    private fun classify(state: String, tier: String? = "gpu") =
        CommunityCompatibilityClassifier.fromCompatibilityResponse(response(state, tier))
    private fun response(state: String, tier: String? = "gpu") = GameCompatibilityResponse(
        gameName = "Example", state = state, tier = tier,
        tiers = tier?.let { mapOf(it to metrics()) }.orEmpty(),
    )

    private fun metrics(rated: Int? = 4, ok: Int? = 4) = CompatibilityTierMetrics(
        key = "hardware", sessions = 10, playable = 10, playableRate = 1.0, medianFps = null,
        lastSeen = null, ratedDevices = rated, okDevices = ok,
    )

    @Test fun limitedEvidenceIsNeverPromotedToWorks() {
        listOf("model", "soc", "gpu", "family").forEach {
            assertEquals(CommunityCompatibilityVerdict.MAY_WORK, classify("May Work", it).verdict)
        }
    }

    @Test fun absentAndUnrecognizedStatesDoNotInventCompatibility() {
        for (state in listOf("Untested", "Future state", "")) {
            assertEquals(CommunityCompatibilityVerdict.UNKNOWN, classify(state).verdict)
        }
        for (tier in listOf(null, "new tier")) {
            assertEquals(CommunityCompatibilityVerdict.UNKNOWN, classify("Great", tier).verdict)
        }
    }

    @Test fun missingDataIsDistinctFromExplicitUntested() {
        assertFalse(CommunityCompatibilitySummary.unknown().verdictLoaded)
        val untested = classify("Untested", null)
        assertTrue(untested.verdictLoaded)
        assertEquals(CommunityCompatibilityVerdict.UNKNOWN, untested.verdict)
    }

    @Test fun bulkRatingsDoNotReplaceServerVerdictOrInventRecency() {
        val stats = DeviceGameStats(44, 60, 0, 581, CommunityRatingDistribution(oneStar = 32, twoStar = 1))
        for (state in listOf("Great", "Broken", "Unreliable", "Untested")) {
            val server = classify(state)
            val detailed = CommunityCompatibilityClassifier.withBulkRatings(
                server, listOf(CommunityEvidenceTier.SAME_GPU to stats), true,
            )
            assertEquals(server.verdict, detailed.verdict)
            assertEquals(server.evidenceTier, detailed.evidenceTier)
            assertEquals(33, detailed.reportCount)
            assertEquals(CommunityVerdictSource.SERVER, detailed.verdictSource)
        }
    }

    @Test fun histogramPrefersVerdictScopeThenClosestAvailableWithoutChangingVerdictScope() {
        val device = DeviceGameStats(1, 60, 1, 100, CommunityRatingDistribution(fiveStar = 1))
        val gpu = DeviceGameStats(10, 60, 3, 100, CommunityRatingDistribution(oneStar = 2, fiveStar = 3))
        val server = classify("Works")
        val summary = CommunityCompatibilityClassifier.withBulkRatings(
            server, listOf(CommunityEvidenceTier.SAME_DEVICE to device, CommunityEvidenceTier.SAME_GPU to gpu), true,
        )
        assertEquals(5, summary.reportCount)
        assertEquals(CommunityEvidenceTier.SAME_GPU, summary.reportEvidenceTier)
        val fallback = CommunityCompatibilityClassifier.withBulkRatings(
            server, listOf(CommunityEvidenceTier.SAME_DEVICE to device), true,
        )
        assertEquals(CommunityEvidenceTier.SAME_GPU, fallback.evidenceTier)
        assertEquals(CommunityEvidenceTier.SAME_DEVICE, fallback.reportEvidenceTier)
        assertEquals(server.verdict, fallback.verdict)
    }

    @Test fun oldFourFieldStatsCannotMasqueradeAsACompleteHistogram() {
        val summary = CommunityCompatibilityClassifier.withBulkRatings(
            classify("Works"),
            listOf(CommunityEvidenceTier.SAME_GPU to DeviceGameStats(100, 60, 20, 100)), false,
        )
        assertEquals(0, summary.reportCount)
        assertFalse(summary.hasDetailedReports)
        assertFalse(summary.detailsLoaded)
        assertEquals(CommunityCompatibilityVerdict.SHOULD_WORK, summary.verdict)
    }

    @Test fun broadFailuresDoNotClaimFailureOnThisDevice() {
        for (state in listOf("Broken", "Unreliable")) {
            for (tier in listOf("family", "all", null)) {
                val result = classify(state, tier)
                assertEquals(CommunityCompatibilityVerdict.UNKNOWN, result.verdict)
                assertNotEquals(CommunityVerdictCaution.NONE, result.scopeCaution)
                assertEquals(state, result.serverState)
            }
        }
    }

    @Test fun missingDecidingTierCannotClaimWorks() {
        val result = CommunityCompatibilityClassifier.fromCompatibilityResponse(response("Works", "model").copy(tiers = emptyMap()))
        assertEquals(CommunityCompatibilityVerdict.UNKNOWN, result.verdict)
        assertEquals(CommunityVerdictCaution.MISSING_TIER, result.scopeCaution)
    }

    @Test fun sampleAwareSupportDoesNotAddOtherTiers() {
        fun checked(n: Int, ok: Int) = CommunityCompatibilityClassifier.fromCompatibilityResponse(
            response("Great").copy(tiers = mapOf("gpu" to metrics(n, ok), "family" to metrics(10000, 10000))),
        )
        assertTrue(checked(20, 20).ratingSupport!! > checked(1, 1).ratingSupport!!)
        assertTrue(checked(20, 18).ratingSupport!! > checked(1, 1).ratingSupport!!)
        assertTrue(checked(8, 0).ratingCaution)
        assertFalse(checked(1, 0).ratingCaution)
        assertEquals(CommunityCompatibilityVerdict.MAY_WORK, checked(8, 0).verdict)
        assertNull(checked(0, 0).ratingSupport)
        assertNull(checked(1, 2).ratingSupport)
        assertFalse(checked(1, 2).ratingCaution)
        assertEquals(CommunityCompatibilityVerdict.MAY_WORK, checked(0, 0).verdict)
    }

    @Test fun zeroPositiveSamplesLimitConfidenceWithoutClaimingConfirmedFailure() {
        for (n in 1..3) {
            val result = CommunityCompatibilityClassifier.fromCompatibilityResponse(
                response("Works").copy(tiers = mapOf("gpu" to metrics(n, 0))),
            )
            assertTrue(result.limitedRatingFeedback)
            assertFalse(result.ratingCaution)
            assertEquals(CommunityCompatibilityVerdict.MAY_WORK, result.verdict)
        }
        val stronger = CommunityCompatibilityClassifier.fromCompatibilityResponse(
            response("Works").copy(tiers = mapOf("gpu" to metrics(4, 0))),
        )
        assertTrue(stronger.ratingCaution)
        assertFalse(stronger.limitedRatingFeedback)
        assertEquals(CommunityCompatibilityVerdict.MAY_WORK, stronger.verdict)
    }

    @Test fun missingInvalidOrPositiveFeedbackDoesNotClaimNonePositive() {
        for ((n, ok) in listOf(null to null, 0 to 0, -1 to 0, 1 to null, null to 0, 1 to -1, 1 to 2, 1 to 1, 3 to 1)) {
            val result = CommunityCompatibilityClassifier.fromCompatibilityResponse(
                response("Works").copy(tiers = mapOf("gpu" to metrics(n, ok))),
            )
            assertFalse("rated=$n, positive=$ok", result.limitedRatingFeedback)
        }
    }

    @Test fun limitedFeedbackUsesOnlyTheDecidingTierAndNeverConfigUploadCount() {
        val result = CommunityCompatibilityClassifier.fromCompatibilityResponse(
            response("Works").copy(
                tiers = mapOf("gpu" to metrics(1, 0), "model" to metrics(5, 5), "family" to metrics(100, 100)),
            ),
        )
        val detailed = CommunityCompatibilityClassifier.withBulkRatings(
            result,
            listOf(CommunityEvidenceTier.SAME_GPU to DeviceGameStats(16, 60, 0, 100, CommunityRatingDistribution(oneStar = 29))),
            true,
        )
        assertTrue(detailed.limitedRatingFeedback)
        assertFalse(detailed.ratingCaution)
        assertEquals(1, detailed.ratedDevices)
        assertEquals(29, detailed.reportCount)
        assertEquals(result.verdict, detailed.verdict)
        val otherTierOnly = CommunityCompatibilityClassifier.fromCompatibilityResponse(
            response("Works").copy(tiers = mapOf("gpu" to metrics(null, null), "family" to metrics(1, 0))),
        )
        assertFalse(otherTierOnly.limitedRatingFeedback)
    }

    @Test fun limitedFeedbackWarningDoesNotAppearForNonPositiveVerdicts() {
        for (state in listOf("Broken", "Untested")) {
            val result = CommunityCompatibilityClassifier.fromCompatibilityResponse(
                response(state).copy(tiers = mapOf("gpu" to metrics(1, 0))),
            )
            assertFalse(result.limitedRatingFeedback)
        }
    }
}
