package app.gamenative.service.gog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GogFilteredProductsParserTest {

    @Test
    fun parsesHiddenPageCollectingAllProductIds() {
        val page = GogFilteredProductsParser.parseHiddenPage(
            """{"totalPages":3,"products":[{"id":1,"title":"a"},{"id":2},{"id":3,"isHidden":true}]}""",
        )
        assertEquals(setOf("1", "2", "3"), page.hiddenProductIds)
        assertEquals(3, page.totalPages)
    }

    @Test
    fun emptyHiddenPageWithZeroTotalPagesIsValid() {
        val page = GogFilteredProductsParser.parseHiddenPage(
            """{"totalPages":0,"products":[]}""",
        )
        assertEquals(emptySet<String>(), page.hiddenProductIds)
        assertEquals(0, page.totalPages)
    }

    @Test
    fun emptyHiddenPagePreservesTotalPages() {
        val page = GogFilteredProductsParser.parseHiddenPage(
            """{"totalPages":7,"products":[]}""",
        )
        assertEquals(emptySet<String>(), page.hiddenProductIds)
        assertEquals(7, page.totalPages)
    }

    @Test
    fun malformedJsonFails() {
        assertThrows(IllegalArgumentException::class.java) {
            GogFilteredProductsParser.parseHiddenPage("not json")
        }
    }

    @Test
    fun missingProductsFails() {
        assertThrows(IllegalArgumentException::class.java) {
            GogFilteredProductsParser.parseHiddenPage("""{"totalPages":1}""")
        }
    }

    @Test
    fun missingTotalPagesFails() {
        assertThrows(IllegalArgumentException::class.java) {
            GogFilteredProductsParser.parseHiddenPage("""{"products":[]}""")
        }
    }

    @Test
    fun negativeTotalPagesFails() {
        assertThrows(IllegalArgumentException::class.java) {
            GogFilteredProductsParser.parseHiddenPage("""{"totalPages":-1,"products":[]}""")
        }
    }

    @Test
    fun nonObjectProductFails() {
        assertThrows(IllegalArgumentException::class.java) {
            GogFilteredProductsParser.parseHiddenPage("""{"totalPages":1,"products":[42]}""")
        }
    }

    @Test
    fun missingProductIdFails() {
        assertThrows(IllegalArgumentException::class.java) {
            GogFilteredProductsParser.parseHiddenPage("""{"totalPages":1,"products":[{"title":"x"}]}""")
        }
    }

    @Test
    fun blankProductIdFails() {
        assertThrows(IllegalArgumentException::class.java) {
            GogFilteredProductsParser.parseHiddenPage("""{"totalPages":1,"products":[{"id":""}]}""")
        }
    }
}
