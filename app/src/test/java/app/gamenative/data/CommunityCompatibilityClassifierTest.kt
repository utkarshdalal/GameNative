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

    @Test fun serverStateMapsDirectlyToVerdict() {
        val expected = mapOf(
            "Great" to CommunityCompatibilityVerdict.WORKS,
            "Works" to CommunityCompatibilityVerdict.SHOULD_WORK,
            "May Work" to CommunityCompatibilityVerdict.MAY_WORK,
            "Unreliable" to CommunityCompatibilityVerdict.MIXED,
            "Broken" to CommunityCompatibilityVerdict.WONT_WORK,
            "Untested" to CommunityCompatibilityVerdict.UNKNOWN,
        )
        for (tier in listOf("model", "soc", "gpu", "family")) {
            for ((state, verdict) in expected) {
                assertEquals("$state at $tier", verdict, classify(state, tier).verdict)
            }
        }
    }

    @Test fun serverTierMapsToEvidenceTier() {
        val expected = mapOf(
            "model" to CommunityEvidenceTier.SAME_DEVICE,
            "soc" to CommunityEvidenceTier.SAME_SOC,
            "gpu" to CommunityEvidenceTier.SAME_GPU,
            "family" to CommunityEvidenceTier.COMPATIBLE_GPU_FAMILY,
        )
        for ((tier, evidence) in expected) {
            val result = classify("Works", tier)
            assertEquals(evidence, result.evidenceTier)
            assertEquals(10, result.sessionCount)
            assertEquals(CommunityVerdictCaution.NONE, result.scopeCaution)
        }
        assertEquals(CommunityEvidenceTier.NONE, classify("Works", "all").evidenceTier)
    }

    @Test fun ratingCountsDoNotChangeServerVerdict() {
        for ((rated, ok) in listOf(null to null, 0 to 0, 8 to 0, 5 to 2, 20 to 20)) {
            for (state in listOf("Great", "Works", "Unreliable")) {
                val result = CommunityCompatibilityClassifier.fromCompatibilityResponse(
                    response(state).copy(tiers = mapOf("gpu" to metrics(rated, ok))),
                )
                assertEquals(classify(state).verdict, result.verdict)
            }
        }
    }

    @Test fun decisiveStateWithoutKnownTierIsUnknown() {
        for (state in listOf("Great", "Broken", "Unreliable")) {
            for (tier in listOf("all", null)) {
                val result = classify(state, tier)
                assertEquals(CommunityCompatibilityVerdict.UNKNOWN, result.verdict)
                assertEquals(CommunityVerdictCaution.NO_MATCHING_SCOPE, result.scopeCaution)
                assertEquals(state, result.serverState)
            }
        }
    }

    @Test fun missingDecidingTierCannotClaimWorks() {
        val result = CommunityCompatibilityClassifier.fromCompatibilityResponse(response("Works", "model").copy(tiers = emptyMap()))
        assertEquals(CommunityCompatibilityVerdict.UNKNOWN, result.verdict)
        assertEquals(CommunityVerdictCaution.MISSING_TIER, result.scopeCaution)
    }

    @Test fun verdictDoesNotDependOnGameName() {
        for (state in listOf("Great", "Works", "Broken")) {
            val value = response(state)
            assertEquals(
                CommunityCompatibilityClassifier.fromCompatibilityResponse(value).verdict,
                CommunityCompatibilityClassifier.fromCompatibilityResponse(value.copy(gameName = "Different title")).verdict,
            )
        }
    }

    @Test fun frameRateWarnsAboutPerformanceButDoesNotChangeVerdict() {
        fun withFps(fps: Double?) = CommunityCompatibilityClassifier.fromCompatibilityResponse(
            response("Works", "model").copy(tiers = mapOf("model" to metrics().copy(medianFps = fps))),
        )
        assertTrue(withFps(29.0).performanceCaution)
        assertEquals(CommunityCompatibilityVerdict.SHOULD_WORK, withFps(29.0).verdict)
        for (fps in listOf(null, 0.0, Double.NaN, 30.0, 60.0)) {
            val result = withFps(fps)
            assertEquals(CommunityCompatibilityVerdict.SHOULD_WORK, result.verdict)
            assertFalse(result.performanceCaution)
        }
    }
}
