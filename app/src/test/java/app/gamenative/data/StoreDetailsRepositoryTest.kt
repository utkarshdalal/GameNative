package app.gamenative.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreDetailsRepositoryTest {

    @Test
    fun `gog locale mapper uses canonical storefront locale tags`() {
        assertEquals("es-ES", gogLocaleForAppLocale(Locale.forLanguageTag("es")))
        assertEquals("es-ES", gogLocaleForAppLocale(Locale.forLanguageTag("es-MX")))
        assertEquals("pt-BR", gogLocaleForAppLocale(Locale.forLanguageTag("pt-BR")))
        assertEquals("zh-Hans", gogLocaleForAppLocale(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh-Hant", gogLocaleForAppLocale(Locale.forLanguageTag("zh-TW")))
    }

    @Test
    fun `steam parser returns description reviews tags and media`() {
        val details =
            """
            {
              "123": {
                "success": true,
                "data": {
                  "about_the_game": "<p>Fight &amp; win.</p><p>Bring a friend.</p>",
                  "genres": [{"description": "Action"}],
                  "categories": [{"description": "Co-op"}],
                  "screenshots": [{"path_full": "https://cdn.example/shot.jpg"}],
                  "movies": [{"mp4": {"max": "https://cdn.example/trailer.mp4"}}]
                }
              }
            }
            """.trimIndent()
        val reviews =
            """
            {
              "query_summary": {
                "total_positive": 84,
                "total_reviews": 100,
                "review_score_desc": "Very Positive"
              }
            }
            """.trimIndent()

        val result = parseSteamStoreDetails(123, details, reviews)

        assertEquals("Fight & win.\nBring a friend.", result.description)
        assertEquals(84, result.reviewPercentage)
        assertEquals(100, result.reviewCount)
        assertEquals("Very Positive", result.reviewSummary)
        assertEquals(listOf("Action", "Co-op"), result.tags)
        assertEquals(listOf("https://cdn.example/shot.jpg"), result.screenshots)
        assertEquals(listOf("https://cdn.example/trailer.mp4"), result.videos)
    }

    @Test
    fun `gog parser normalizes image URLs and five star rating`() {
        val details =
            """
            {
              "description": {"full": "<b>A grand adventure</b>"},
              "links": {"product": "https://www.gog.com/game/grand_adventure"},
              "screenshots": [
                {"formatter_template_url": "//images.gog.com/abc_{formatter}.jpg"}
              ],
              "videos": [{"video_url": "https://video.example/trailer.m3u8"}]
            }
            """.trimIndent()
        val rating = """{"value":4.35,"count":321}"""
        val tags = """{"_embedded":{"tags":[{"name":"RPG"},{"name":"Story Rich"}]}}"""

        val result = parseGogStoreDetails(details, rating, tags)

        assertEquals("A grand adventure", result.description)
        assertEquals(87, result.reviewPercentage)
        assertEquals(321, result.reviewCount)
        assertEquals(listOf("RPG", "Story Rich"), result.tags)
        assertEquals(listOf("https://images.gog.com/abc_ggvgl_2x.jpg"), result.screenshots)
    }

    @Test
    fun `gog parser removes localization keys and reads v2 screenshots`() {
        val details =
            """
            {
              "description": {
                "full": "product_description_1426071866<br>product_feature_1426071866"
              }
            }
            """.trimIndent()
        val game =
            """
            {
              "_embedded": {
                "screenshots": [{
                  "_links": {
                    "self": {"href": "https://images.example/shot_{formatter}.jpg"}
                  }
                }]
              }
            }
            """.trimIndent()

        val result = parseGogStoreDetails(details, null, game)

        assertEquals("", result.description)
        assertEquals(listOf("https://images.example/shot_1600.jpg"), result.screenshots)
    }

    @Test
    fun `gog included product parser returns a different parent product`() {
        val game =
            """
            {
              "_links": {
                "isIncludedInGames": [
                  {"id": 1426071866},
                  {"id": 1440161275}
                ]
              }
            }
            """.trimIndent()

        assertEquals(1440161275, parseGogIncludedInProductId(1426071866, game))
    }

    @Test
    fun `amazon parser uses rich fields when the entitlement contains them`() {
        val product =
            """
            {
              "description": "A fast game",
              "productUrl": "https://gaming.amazon.com/example",
              "productDetail": {
                "details": {
                  "genres": ["Racing", {"name": "Arcade"}],
                  "screenshots": ["https://cdn.example/screenshot.jpg"],
                  "backgroundUrl1": "https://cdn.example/background.jpg",
                  "videos": [{"url": "https://cdn.example/trailer.mp4"}]
                }
              }
            }
            """.trimIndent()

        val result = parseAmazonStoreDetails(product)

        assertEquals("A fast game", result.description)
        assertEquals(listOf("Racing", "Arcade"), result.tags)
        assertEquals(
            listOf(
                "https://cdn.example/screenshot.jpg",
                "https://cdn.example/background.jpg",
            ),
            result.screenshots,
        )
        assertEquals(listOf("https://cdn.example/trailer.mp4"), result.videos)
    }

    @Test
    fun `steam parser skips empty markup when choosing a description`() {
        val details =
            """
            {
              "123": {
                "success": true,
                "data": {
                  "about_the_game": "<br><p> </p>",
                  "detailed_description": "<p>The actual description.</p>"
                }
              }
            }
            """.trimIndent()

        val result = parseSteamStoreDetails(123, details, null)

        assertEquals("The actual description.", result.description)
    }

    @Test
    fun `gog parser skips localization placeholders when choosing a description`() {
        val details =
            """
            {
              "description": {
                "full": "product_description_1426071866<br>product_feature_1426071866",
                "lead": "The actual description."
              }
            }
            """.trimIndent()

        val result = parseGogStoreDetails(details, null, null)

        assertEquals("The actual description.", result.description)
    }

    @Test
    fun `gog description fallback is used only when localized copy is missing or still English`() {
        assertTrue(shouldUseGogDescriptionFallback("", "English copy"))
        assertTrue(shouldUseGogDescriptionFallback("English   copy", "English copy"))
        assertEquals(
            false,
            shouldUseGogDescriptionFallback("Descripción en español", "English copy"),
        )
        assertEquals(false, shouldUseGogDescriptionFallback("Store copy", ""))
    }

    @Test
    fun `gog parser deduplicates the same screenshot template across endpoints`() {
        val details =
            """
            {
              "screenshots": [
                {"formatter_template_url": "//images.gog.com/abc_{formatter}.jpg"}
              ]
            }
            """.trimIndent()
        val game =
            """
            {
              "_embedded": {
                "screenshots": [{
                  "_links": {
                    "self": {"href": "https://images.gog.com/abc_{formatter}.jpg"}
                  }
                }]
              }
            }
            """.trimIndent()

        val result = parseGogStoreDetails(details, null, game)

        assertEquals(listOf("https://images.gog.com/abc_ggvgl_2x.jpg"), result.screenshots)
    }

    @Test
    fun `gog parser omits an unrated zero score`() {
        val result = parseGogStoreDetails(
            detailsJson = null,
            reviewsJson = """{"value":0,"count":0}""",
            tagsJson = null,
        )

        assertNull(result.reviewPercentage)
        assertNull(result.reviewCount)
    }

    @Test
    fun `remote details retain missing local fallback fields`() {
        val remote = StoreGameDetails(
            description = "Fresh description",
            tags = listOf("Action"),
        )
        val local = StoreGameDetails(
            description = "Old description",
            reviewPercentage = 91,
            tags = listOf("action", "Co-op"),
            screenshots = listOf("https://cdn.example/local.jpg"),
        )

        val result = remote.mergedWith(local)

        assertEquals("Fresh description", result.description)
        assertEquals(91, result.reviewPercentage)
        assertEquals(listOf("Action", "Co-op"), result.tags)
        assertEquals(listOf("https://cdn.example/local.jpg"), result.screenshots)
    }

    @Test
    fun `remote and local media are combined without duplicates`() {
        val remote = StoreGameDetails(
            screenshots = listOf("https://cdn.example/remote.jpg"),
            videos = listOf("https://cdn.example/trailer.mp4"),
        )
        val local = StoreGameDetails(
            screenshots = listOf(
                "https://cdn.example/local.jpg",
                "https://cdn.example/remote.jpg",
            ),
            videos = listOf("https://cdn.example/trailer.mp4"),
        )

        val result = remote.mergedWith(local)

        assertEquals(
            listOf("https://cdn.example/remote.jpg", "https://cdn.example/local.jpg"),
            result.screenshots,
        )
        assertEquals(listOf("https://cdn.example/trailer.mp4"), result.videos)
    }

    @Test
    fun `title-only catalog description is discarded`() {
        val details = StoreGameDetails(description = "The Example: Deluxe Edition")

        val result = details.withoutTitleOnlyDescription("The Example")

        assertEquals("", result.description)
    }

    @Test
    fun `steam search requires a normalized exact title match`() {
        val search =
            """
            {
              "items": [
                {"id": 1, "name": "Example Soundtrack"},
                {"id": 42, "name": "The Example: Deluxe Edition"}
              ]
            }
            """.trimIndent()

        assertEquals(42, parseSteamSearchAppId("The Example", search))
        assertNull(parseSteamSearchAppId("Something Else", search))
    }

    @Test
    fun `steam search permits a replacement bundle only for a superseded gog product`() {
        val search =
            """
            {
              "items": [
                {"id": 2280, "name": "DOOM + DOOM II"},
                {"id": 99, "name": "Fantasy Pack - Quests of DOOM II"}
              ]
            }
            """.trimIndent()

        assertNull(parseSteamSearchAppId("DOOM II", search))
        assertEquals(
            2280,
            parseSteamSearchAppId("DOOM II", search, allowReplacementBundleMatch = true),
        )
    }
}
