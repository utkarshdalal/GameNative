package app.gamenative.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockHeroResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `mock hero payload decodes through the production models`() {
        val hero = json.decodeFromString<HeroResponse>(RecommendationRepository.MOCK_HERO_JSON)

        assertNull(hero.recommendation)
        val featured = assertNotNull(hero.featured)

        assertEquals("mock-whisk", featured.campaignId)
        assertEquals(3602270, featured.appId)
        assertEquals("COMING_SOON", featured.status)
        assertTrue(featured.description.containsKey("en"))
        assertEquals(3, featured.screenshots.size)

        assertEquals(listOf("WISHLIST", "GET_DEMO", "VISIT"), featured.actions.map { it.type })

        val wishlist = featured.actions[0]
        assertEquals("primary", wishlist.style)
        assertNull(wishlist.appId)

        val demo = featured.actions[1]
        assertEquals(4320000, demo.appId)

        assertTrue(featured.actions.all { it.url.startsWith("https://") })
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
