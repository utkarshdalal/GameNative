package app.gamenative.utils

import app.gamenative.data.CommunityCompatibilityClassifier
import app.gamenative.data.CommunityCompatibilityVerdict
import app.gamenative.utils.GameCompatibilityCache.CachedCompatibilityResponse
import app.gamenative.utils.GameCompatibilityService.CompatibilityTierMetrics
import app.gamenative.utils.GameCompatibilityService.GameCompatibilityResponse
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCompatibilityServiceTest {
    @Test fun selectedChipsetSurvivesParsingAndExistingCacheFormat() {
        val response = GameCompatibilityService.parseCompatibilityResponse(
            """{"results":{"Game":{"state":"Great","tier":"soc","tiers":{
                "soc":{"key":"Chipset","sessions":10,"playable":8,"playableRate":0.8,"ratedDevices":5,"okDevices":4},
                "gpu":{"key":"GPU","sessions":100,"playable":0}
            }}}}""",
            listOf("Game"),
        ).getValue("Game")
        val cached = CachedCompatibilityResponse(response, 123)
        val encoded = GameCompatibilityCache.json.encodeToString(cached)
        val restored = GameCompatibilityCache.json.decodeFromString<CachedCompatibilityResponse>(encoded)
        assertEquals(cached, restored)
        val summary = CommunityCompatibilityClassifier.fromCompatibilityResponse(restored.response)
        assertEquals(CommunityCompatibilityVerdict.WORKS, summary.verdict)
        assertEquals(app.gamenative.data.CommunityEvidenceTier.SAME_SOC, summary.evidenceTier)
        assertEquals(10, summary.sessionCount)
        assertEquals("SAME_SOC", GameCompatibilityService.badgeProperties(restored.response)["compat_evidence_tier"])
    }

    @Test
    fun parsesStateTierAndRequiredMetricsIgnoringUnusedServerFields() {
        val body = """
            {"results":{
                "Game":{"state":"Broken","tier":"gpu","tiers":{
                    "gpu":{"key":"GPU","sessions":61,"playable":43,"playableRate":0.72,
                        "ratedDevices":8,"okDevices":0,"medianFps":34,"unusedFutureField":42},
                    "all":{"key":"all","sessions":100,"playable":70,"medianFps":null}}},
                "Never Reported":{"state":"Untested","tier":null,"tiers":{}}
            }}
        """.trimIndent()

        val parsed = GameCompatibilityService.parseCompatibilityResponse(
            body,
            listOf("Game", "Never Reported"),
        )

        val game = parsed.getValue("Game")
        assertEquals("Broken", game.state)
        assertEquals(8, game.tiers.getValue("gpu").ratedDevices)
        assertEquals(0, game.tiers.getValue("gpu").okDevices)
        assertEquals("gpu", game.tier)
        assertEquals(61, game.tiers.getValue("gpu").sessions)
        assertEquals(0.72, game.tiers.getValue("gpu").playableRate!!, 0.001)
        assertEquals(70, game.tiers.getValue("all").playable)
        assertEquals(34.0, game.tiers.getValue("gpu").medianFps!!, 0.001)
        assertEquals(
            CommunityCompatibilityVerdict.WONT_WORK,
            CommunityCompatibilityClassifier.fromCompatibilityResponse(game).verdict,
        )

        val untested = parsed.getValue("Never Reported")
        assertEquals("Untested", untested.state)
        assertNull(untested.tier)
        assertTrue(untested.tiers.isEmpty())
        assertEquals(
            CommunityCompatibilityVerdict.UNKNOWN,
            CommunityCompatibilityClassifier.fromCompatibilityResponse(untested).verdict,
        )
    }

    @Test
    fun missingRequestedEntryIsNotInventedAsUntested() {
        val parsed = GameCompatibilityService.parseCompatibilityResponse(
            """{ "results": {} }""",
            listOf("Missing Game"),
        )

        assertTrue(parsed.isEmpty())
    }

    @Test fun acceptsUnwrappedModernResponsesButNotOldCountOnlyResponses() {
        val result = GameCompatibilityService.parseCompatibilityResponse(
            """{"Game":{"state":"Broken","tier":"gpu","tiers":{"gpu":{"key":"GPU"}}},"Legacy":{"total":50},"Other":{"state":"Works"}}""",
            listOf("Game", "Legacy"),
        )
        assertEquals(setOf("Game"), result.keys)
        assertEquals("Broken", result.getValue("Game").state)
    }

    @Test fun oldSchemaThreeCacheStillDecodesWithoutTheRemovedLegacyFields() {
        val encoded = """{"response":{"gameName":"Game","state":"Works","tier":"gpu","tiers":{"gpu":{"key":"GPU","sessions":10,"playable":8}},
            "totalPlayableCount":10,"gpuPlayableCount":8,"hasBeenTried":true,"isNotWorking":false},
            "timestamp":123,"schemaVersion":3,"modernBuild":false}"""
        val restored = GameCompatibilityCache.json.decodeFromString<GameCompatibilityCache.CachedCompatibilityResponse>(encoded)
        assertEquals("Works", restored.response.state)
        assertEquals(10, restored.response.tiers.getValue("gpu").sessions)
        assertNull(restored.response.tiers.getValue("gpu").ratedDevices)
        assertNull(restored.response.tiers.getValue("gpu").playableRate)
    }

    @Test fun analyticsUsesTheSameVerdictAsTheLibraryRatherThanLegacyRunCounts() {
        val response = GameCompatibilityResponse(
            "Game", "Works", "gpu",
            mapOf(
                "gpu" to GameCompatibilityService.CompatibilityTierMetrics("GPU", 10, 8, .8, ratedDevices = 5, okDevices = 4),
            ),
        )
        val properties = GameCompatibilityService.badgeProperties(response)
        assertEquals("SHOULD_WORK", properties["compat_badge"])
        assertEquals("Works", properties["compat_server_state"])
    }

    @Test fun oldCacheMetricsAreIgnoredWithoutLosingEvidenceOrInvalidatingTheCache() {
        val responseJson = """{"gameName":"Game","state":"Works","tier":"gpu","tiers":{"gpu":{
            "key":"GPU","sessions":10,"playable":8,"playableRate":0.8,"medianFps":42.0,
            "lastSeen":"1970-01-01T00:00:01Z","ratedDevices":5,"okDevices":4,
            "p99FrameMs":41.5,"throttleShare":0.2,"medianTimeToThrottleS":410,
            "medianPlayableSeconds":1260,"knownConfigShare":0.57,"totalPlayable":84}}}"""
        val expected = GameCompatibilityResponse(
            "Game", "Works", "gpu",
            mapOf("gpu" to CompatibilityTierMetrics("GPU", 10, 8, 0.8, 42.0, "1970-01-01T00:00:01Z", 5, 4)),
        )
        val fromApi = GameCompatibilityService.parseCompatibilityResponse("""{"results":{"Game":$responseJson}}""", listOf("Game"))
        assertEquals(expected, fromApi.getValue("Game"))
        for (modernBuild in listOf(false, true)) {
            val oldCache = """{"Game":{"response":$responseJson,"timestamp":123,"schemaVersion":3,"modernBuild":$modernBuild}}"""
            val restored = GameCompatibilityCache.json.decodeFromString<Map<String, CachedCompatibilityResponse>>(oldCache)
            assertEquals(CachedCompatibilityResponse(expected, 123), restored.getValue("Game"))
            assertEquals(
                CommunityCompatibilityVerdict.SHOULD_WORK,
                CommunityCompatibilityClassifier.fromCompatibilityResponse(restored.getValue("Game").response).verdict,
            )
            val compact = GameCompatibilityCache.json.encodeToString(restored)
            val unusedFields = listOf(
                "p99FrameMs", "throttleShare", "medianTimeToThrottleS", "medianPlayableSeconds", "knownConfigShare", "totalPlayable",
            )
            for (unused in unusedFields) {
                assertFalse(compact.contains("\"$unused\""))
            }
            assertEquals(restored, GameCompatibilityCache.json.decodeFromString<Map<String, CachedCompatibilityResponse>>(compact))
        }
    }
}
