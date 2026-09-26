package app.gamenative.service.gog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GogFilteredProductsParserTest {

/** Verifies the behavior described by this test: parses Explicit Hidden Values For Visible And Hidden Products. */
    @Test
    fun parsesExplicitHiddenValuesForVisibleAndHiddenProducts() {
        val page = GogFilteredProductsParser.parsePage(
            """
            {
              "totalPages": 2,
              "products": [
                {"id": 1, "isHidden": true},
                {"id": "2", "isHidden": false}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(mapOf("1" to true, "2" to false), page.observations)
        assertEquals(2, page.totalPages)
    }

/** Verifies the behavior described by this test: accepts IDentical Duplicate Observations. */
    @Test
    fun acceptsIdenticalDuplicateObservations() {
        val page = GogFilteredProductsParser.parsePage(
            """{"totalPages":1,"products":[{"id":"1","isHidden":true},{"id":"1","isHidden":true}]}""",
        )

        assertEquals(mapOf("1" to true), page.observations)
    }

/** Verifies the behavior described by this test: rejects Conflicting Duplicate Observations. */
    @Test
    fun rejectsConflictingDuplicateObservations() {
        assertThrows(IllegalArgumentException::class.java) {
            GogFilteredProductsParser.parsePage(
                """{"totalPages":1,"products":[{"id":"1","isHidden":true},{"id":"1","isHidden":false}]}""",
            )
        }
    }

/** Verifies the behavior described by this test: rejects Missing Or Non Boolean Hidden Values. */
    @Test
    fun rejectsMissingOrNonBooleanHiddenValues() {
        listOf(
            """{"totalPages":1,"products":[{"id":"1"}]}""",
            """{"totalPages":1,"products":[{"id":"1","isHidden":"true"}]}""",
            """{"totalPages":1,"products":[{"id":"1","isHidden":1}]}""",
            """{"totalPages":1,"products":[{"id":"1","isHidden":null}]}""",
        ).forEach { rawJson ->
            assertThrows(IllegalArgumentException::class.java) {
                GogFilteredProductsParser.parsePage(rawJson)
            }
        }
    }

/** Verifies the behavior described by this test: rejects Missing Or Invalid Product IDs. */
    @Test
    fun rejectsMissingOrInvalidProductIds() {
        listOf(
            """{"totalPages":1,"products":[{"isHidden":true}]}""",
            """{"totalPages":1,"products":[{"id":"","isHidden":true}]}""",
            """{"totalPages":1,"products":[{"id":null,"isHidden":true}]}""",
            """{"totalPages":1,"products":[{"id":{},"isHidden":true}]}""",
        ).forEach { rawJson ->
            assertThrows(IllegalArgumentException::class.java) {
                GogFilteredProductsParser.parsePage(rawJson)
            }
        }
    }

/** Verifies the behavior described by this test: accepts Structurally Valid Empty Pages. */
    @Test
    fun acceptsStructurallyValidEmptyPages() {
        val page = GogFilteredProductsParser.parsePage(
            """{"totalPages":0,"products":[]}""",
        )

        assertEquals(emptyMap<String, Boolean>(), page.observations)
        assertEquals(0, page.totalPages)
    }

/** Verifies the behavior described by this test: rejects Malformed Page Envelope. */
    @Test
    fun rejectsMalformedPageEnvelope() {
        listOf(
            "not json",
            """{"products":[]}""",
            """{"totalPages":-1,"products":[]}""",
            """{"totalPages":"1","products":[]}""",
            """{"totalPages":1,"products":{}}""",
        ).forEach { rawJson ->
            assertThrows(IllegalArgumentException::class.java) {
                GogFilteredProductsParser.parsePage(rawJson)
            }
        }
    }
}
