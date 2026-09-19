package app.gamenative.service.gog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GogGalaxyReleasesParserTest {

/** Verifies the behavior described by this test: parses Owned GOG Hidden And Visible Releases And Ignores Other Releases. */
    @Test
    fun parsesOwnedGogHiddenAndVisibleReleasesAndIgnoresOtherReleases() {
        val page = GogGalaxyReleasesParser.parsePage(
            """
            {
              "items": [
                {"platform_id":"gog","owned":true,"external_id":"1","hidden":true},
                {"platform_id":"gog","owned":true,"external_id":"2","hidden":false},
                {"platform_id":"steam","owned":true,"external_id":"steam-1"},
                {"platform_id":"gog","owned":false,"external_id":"3"},
                {"platform_id":"epic","owned":false,"external_id":"epic-1"}
              ],
              "next_page_token":"next",
              "total_count":5
            }
            """.trimIndent(),
        )

        assertEquals(mapOf("1" to true, "2" to false), page.observations)
        assertEquals("next", page.nextPageToken)
        assertEquals(5, page.totalCount)
        assertEquals(5, page.rawItemCount)
    }

/** Verifies the behavior described by this test: accepts Missing Null And Empty Continuation Tokens. */
    @Test
    fun acceptsMissingNullAndEmptyContinuationTokens() {
        listOf(
            """{"items":[]}""",
            """{"items":[],"next_page_token":null}""",
            """{"items":[],"next_page_token":""}""",
        ).forEach { rawJson ->
            val page = GogGalaxyReleasesParser.parsePage(rawJson)
            assertNull(page.nextPageToken)
        }
    }

/** Verifies the behavior described by this test: accepts IDentical Duplicate Owned GOG Releases. */
    @Test
    fun acceptsIdenticalDuplicateOwnedGogReleases() {
        val page = GogGalaxyReleasesParser.parsePage(
            """{"items":[
                {"platform_id":"gog","owned":true,"external_id":"1","hidden":true},
                {"platform_id":"gog","owned":true,"external_id":"1","hidden":true}
            ]}""",
        )

        assertEquals(mapOf("1" to true), page.observations)
    }

/** Verifies the behavior described by this test: ignores Irrelevant Releases Without GOG Specific Fields. */
    @Test
    fun ignoresIrrelevantReleasesWithoutGogSpecificFields() {
        val page = GogGalaxyReleasesParser.parsePage(
            """{"items":[
                {"platform_id":"steam","owned":true},
                {"platform_id":"gog","owned":false},
                {"platform_id":"epic","owned":false}
            ]}""",
        )

        assertEquals(emptyMap<String, Boolean>(), page.observations)
    }

/** Verifies the behavior described by this test: rejects Conflicting Duplicate Owned GOG Releases. */
    @Test
    fun rejectsConflictingDuplicateOwnedGogReleases() {
        assertThrows(IllegalArgumentException::class.java) {
            GogGalaxyReleasesParser.parsePage(
                """{"items":[
                    {"platform_id":"gog","owned":true,"external_id":"1","hidden":true},
                    {"platform_id":"gog","owned":true,"external_id":"1","hidden":false}
                ]}""",
            )
        }
    }

/** Verifies the behavior described by this test: rejects Malformed Required Selection Fields. */
    @Test
    fun rejectsMalformedRequiredSelectionFields() {
        listOf(
            """{"items":[{"owned":true,"external_id":"1","hidden":true}]}""",
            """{"items":[{"platform_id":1,"owned":true,"external_id":"1","hidden":true}]}""",
            """{"items":[{"platform_id":"steam","external_id":"1"}]}""",
            """{"items":[{"platform_id":"steam","owned":"true","external_id":"1"}]}""",
            """{"items":[{"platform_id":"gog","owned":"true","external_id":"1","hidden":true}]}""",
            """{"items":[{"platform_id":"gog","owned":true,"hidden":true}]}""",
            """{"items":[{"platform_id":"gog","owned":true,"external_id":"1","hidden":"true"}]}""",
            """{"items":[{"platform_id":"gog","owned":true,"external_id":"1","hidden":null}]}""",
        ).forEach { rawJson ->
            assertThrows(IllegalArgumentException::class.java) {
                GogGalaxyReleasesParser.parsePage(rawJson)
            }
        }
    }

/** Verifies the behavior described by this test: rejects Malformed Page Envelope And Counts. */
    @Test
    fun rejectsMalformedPageEnvelopeAndCounts() {
        listOf(
            "not json",
            "{}",
            """{"releases":[]}""",
            """{"items":{}}""",
            """{"items":[],"next_page_token":1}""",
            """{"items":[],"total_count":"1"}""",
            """{"items":[],"total_count":-1}""",
        ).forEach { rawJson ->
            assertThrows(IllegalArgumentException::class.java) {
                GogGalaxyReleasesParser.parsePage(rawJson)
            }
        }
    }
}
