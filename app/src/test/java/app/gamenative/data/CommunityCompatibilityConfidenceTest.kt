package app.gamenative.data

import app.gamenative.data.CommunityCompatibilityVerdict.*
import app.gamenative.utils.GameCompatibilityService.CompatibilityTierMetrics
import app.gamenative.utils.GameCompatibilityService.GameCompatibilityResponse
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CommunityCompatibilityConfidenceTest {
    private val now = Instant.parse("2026-09-27T20:00:00Z").toEpochMilli()
    private fun metrics() = CompatibilityTierMetrics(
        key = "Synthetic hardware", sessions = 10, playable = 8, playableRate = 0.8,
        medianFps = 60.0, ratedDevices = 5, okDevices = 4,
    )
    private fun response(tier: String = "gpu", state: String = "Works", row: CompatibilityTierMetrics = metrics()) =
        GameCompatibilityResponse("Synthetic game", state, tier, mapOf(tier to row))
    private fun classify(value: GameCompatibilityResponse) = CommunityCompatibilityClassifier.fromCompatibilityResponse(value, now)
    private fun classify(row: CompatibilityTierMetrics, state: String = "Works", tier: String = "gpu") = classify(response(tier, state, row))
    private fun sparseModel() = response("model").copy(
        tiers = mapOf("model" to metrics().copy(sessions = 2, playable = 1, ratedDevices = null, okDevices = null), "gpu" to metrics()),
    )

    @Test fun hardwareAndServerStateMatrixDoesNotDependOnGameNames() {
        for (state in listOf("Great", "Works")) {
            val grade = if (state == "Great") WORKS else SHOULD_WORK
            val scopes = listOf("model" to grade, "gpu" to grade, "family" to MAY_WORK, "all" to UNKNOWN, "soc" to grade)
            for ((tier, expected) in scopes) {
                val value = response(tier, state)
                assertEquals(expected, classify(value).verdict)
                assertEquals(expected, classify(value.copy(gameName = "Different title")).verdict)
            }
        }
    }

    @Test fun unconfirmedModelFallsBackToCorroboratedGpuWithCorrectScopeAndOriginalServerState() {
        val result = classify(sparseModel())
        assertEquals(SHOULD_WORK, result.verdict)
        assertEquals(CommunityEvidenceTier.SAME_GPU, result.evidenceTier)
        assertEquals(CommunityConfidenceCaution.MODEL_UNCONFIRMED, result.confidenceCaution)
        assertEquals(10, result.sessionCount)
        assertEquals("Works", result.serverState)
    }

    @Test fun strongerBroaderRatingsCannotHideUnfavorableModelFeedback() {
        for ((rated, ok, expected) in listOf(Triple(1, 0, MAY_WORK), Triple(8, 0, MAY_WORK), Triple(4, 2, MIXED))) {
            val value = sparseModel()
            val model = value.tiers.getValue("model").copy(ratedDevices = rated, okDevices = ok)
            val result = classify(value.copy(tiers = value.tiers + ("model" to model)))
            assertEquals(expected, result.verdict)
            assertEquals(CommunityEvidenceTier.SAME_DEVICE, result.evidenceTier)
        }
    }

    @Test fun invalidFeedbackAndPerformanceDoNotEarnGreenBadges() {
        val row = metrics()
        val invalid = listOf(
            row.copy(ratedDevices = 1, okDevices = 2), row.copy(okDevices = -1), row.copy(ratedDevices = -1),
            row.copy(ratedDevices = null, okDevices = 0), row.copy(ratedDevices = 1, okDevices = null),
            row.copy(ratedDevices = 0, okDevices = 1),
            row.copy(playableRate = null), row.copy(playableRate = Double.NaN), row.copy(playableRate = 1.1),
            row.copy(playable = 0), row.copy(playable = row.sessions + 1),
        )
        invalid.forEach { assertEquals(MAY_WORK, classify(it).verdict) }
    }

    @Test fun absentRatingsAreNeutralButCannotProduceGreatOrBroadenHardwareMatching() {
        for ((rated, ok) in listOf(null to null, 0 to 0, 0 to null)) {
            val row = metrics().copy(ratedDevices = rated, okDevices = ok)
            for (state in listOf("Great", "Works")) {
                for (tier in listOf("model", "soc", "gpu", "family", "all")) {
                    val expected = when (tier) {
                        "family" -> MAY_WORK
                        "all" -> UNKNOWN
                        else -> SHOULD_WORK
                    }
                    assertEquals(expected, classify(row, state, tier).verdict)
                }
            }
            for (unsupported in listOf(
                row.copy(sessions = 4, playable = 4), row.copy(playableRate = 0.599), row.copy(playable = 0),
                row.copy(playable = 11), row.copy(playableRate = Double.NaN), row.copy(playableRate = null),
                row.copy(lastSeen = "2026-01-01T00:00:00Z"),
            )) {
                assertEquals(MAY_WORK, classify(unsupported, "Great").verdict)
            }
            assertEquals(
                SHOULD_WORK,
                classify(row.copy(sessions = 5, playable = 3, playableRate = 0.6)).verdict,
            )
        }
    }

    @Test fun unratedModelRetainsTheExistingCorroboratedGpuFallback() {
        val rows = mapOf("model" to metrics().copy(ratedDevices = 0, okDevices = 0), "gpu" to metrics())
        val result = classify(response("model", "Great").copy(tiers = rows))
        assertEquals(WORKS, result.verdict)
        assertEquals(CommunityEvidenceTier.SAME_GPU, result.evidenceTier)
        assertEquals(CommunityConfidenceCaution.MODEL_UNCONFIRMED, result.confidenceCaution)
        val noCorroboratedGpu = classify(
            response("model", "Great").copy(
                tiers = rows + ("gpu" to metrics().copy(ratedDevices = 0, okDevices = 0)),
            ),
        )
        assertEquals(SHOULD_WORK, noCorroboratedGpu.verdict)
        assertEquals(CommunityEvidenceTier.SAME_DEVICE, noCorroboratedGpu.evidenceTier)
    }

    @Test fun confidenceBoundariesSeparateMixedFromLimitedAndPositive() {
        val row = metrics().copy(sessions = 5, playable = 4)
        for ((rated, ok, expected) in listOf(
            Triple(1, 1, SHOULD_WORK), Triple(3, 1, MAY_WORK), Triple(4, 2, MIXED),
            Triple(5, 3, SHOULD_WORK), Triple(5, 2, MIXED), Triple(8, 0, MAY_WORK),
        )) {
            val evidence = row.copy(ratedDevices = rated, okDevices = ok)
            assertEquals(expected, classify(evidence).verdict)
        }
        assertEquals(MAY_WORK, classify(row.copy(sessions = 4)).verdict)
        assertEquals(SHOULD_WORK, classify(row.copy(playableRate = 0.6)).verdict)
        assertEquals(MAY_WORK, classify(row.copy(playableRate = 0.599)).verdict)
    }

    @Test fun matchingUnreliableIsPreservedUnlessStrongNegativeFeedbackEstablishesBroken() {
        assertEquals(WONT_WORK, classify(response("model", "Broken")).verdict)
        assertEquals(MIXED, classify(response("model", "Unreliable")).verdict)
        assertEquals(
            MIXED,
            classify(metrics().copy(sessions = 1, playable = 0, ratedDevices = null, okDevices = null), "Unreliable", "model").verdict,
        )
        val samples = listOf(Triple(4, 0, MIXED), Triple(5, 0, WONT_WORK), Triple(5, 1, MIXED), Triple(5, 2, MIXED))
        for ((rated, ok, expected) in samples) {
            assertEquals(expected, classify(metrics().copy(ratedDevices = rated, okDevices = ok), "Unreliable", "model").verdict)
        }
    }

    @Test fun mayWorkUntestedAndUnknownServerStatesAreNotPromotedByOtherTiers() {
        assertEquals(MAY_WORK, classify(response("model", "May Work")).verdict)
        assertEquals(UNKNOWN, classify(response("model", "Untested")).verdict)
        assertEquals(UNKNOWN, classify(response("model", "Future state")).verdict)
        assertEquals(UNKNOWN, classify(response("family", "Broken")).verdict)
    }

    @Test fun oldActivityLimitsConfidenceWithoutInventingRatingAgeOrVersion() {
        val old = metrics().copy(lastSeen = "2026-01-01T00:00:00Z")
        val result = classify(old)
        assertEquals(MAY_WORK, result.verdict)
        assertEquals(CommunityConfidenceCaution.OLDER_EVIDENCE, result.confidenceCaution)
        for (date in listOf(null, "malformed", "2026-09-26T12:30:00Z")) {
            assertEquals(SHOULD_WORK, classify(metrics().copy(lastSeen = date)).verdict)
        }
    }

    @Test fun frameRateWarnsAboutPerformanceButDoesNotDeclareFailureOrSuccess() {
        assertTrue(classify(metrics().copy(medianFps = 29.0), tier = "model").performanceCaution)
        for (fps in listOf(null, 0.0, Double.NaN, 30.0, 60.0)) {
            val result = classify(metrics().copy(medianFps = fps), tier = "model")
            assertEquals(SHOULD_WORK, result.verdict)
            assertFalse(result.performanceCaution)
        }
    }
}
