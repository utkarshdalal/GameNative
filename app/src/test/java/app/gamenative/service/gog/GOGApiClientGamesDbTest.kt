package app.gamenative.service.gog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class GOGApiClientGamesDbTest {

    @Test
    fun parseGamesDbInfo_readsCoverDeveloperPublisherAndReleaseDate() {
        val release = JSONObject(
            """
            {
              "external_id": "1097893768",
              "first_release_date": "2018-03-27T00:00:00+0000",
              "game": {
                "first_release_date": "2002-06-18T00:00:00+0000",
                "developers": [{"id": "1", "name": "BioWare"}, {"id": "2", "name": "Beamdog"}],
                "publishers": [{"id": "3", "name": "Beamdog"}],
                "vertical_cover": {"url_format": "https://images.gog.com/abc{formatter}.{ext}"}
              }
            }
            """.trimIndent(),
        )

        val info = GOGApiClient.parseGamesDbInfo(release)

        assertEquals("https://images.gog.com/abc_glx_vertical_cover.webp", info.verticalCoverUrl)
        assertEquals("BioWare", info.developer)
        assertEquals("Beamdog", info.publisher)
        assertEquals("2018-03-27T00:00:00+0000", info.releaseDate)
    }

    @Test
    fun parseGamesDbInfo_fallsBackToGameReleaseDateAndLeavesMissingFieldsEmpty() {
        val release = JSONObject(
            """
            {
              "first_release_date": null,
              "game": {
                "first_release_date": "2016-08-29T00:00:00+0000",
                "developers": [],
                "publishers": []
              }
            }
            """.trimIndent(),
        )

        val info = GOGApiClient.parseGamesDbInfo(release)

        assertEquals("", info.verticalCoverUrl)
        assertEquals("", info.developer)
        assertEquals("", info.publisher)
        assertEquals("2016-08-29T00:00:00+0000", info.releaseDate)
    }

    @Test
    fun parseGamesDbInfo_emptyResponseIsEmptyInfo() {
        assertEquals(GogGamesDbInfo(), GOGApiClient.parseGamesDbInfo(JSONObject("{}")))
    }
}
