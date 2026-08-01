package app.gamenative.steam.curated

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FourThreeCuratorSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun fetchRequestsRecommendedEntriesAndPaginates() = runBlocking {
        server.enqueue(jsonResponse(totalCount = 3, appIds = listOf(3, 1)))
        server.enqueue(jsonResponse(totalCount = 3, appIds = listOf(2)))

        val appIds = FourThreeCuratorSource.fetch(
            client = client,
            endpoint = server.url("/curator/43078746/ajaxgetfilteredrecommendations/"),
            pageSize = 2,
        )

        assertEquals(setOf(1, 2, 3), appIds)
        val firstRequest = server.takeRequest().requestUrl
        assertEquals("0", firstRequest?.queryParameter("curations"))
        assertEquals("0", firstRequest?.queryParameter("start"))
        assertEquals("2", firstRequest?.queryParameter("count"))
        assertEquals(CURATOR_CLAN_ID_4_3.toString(), firstRequest?.queryParameter("clanid"))
        assertEquals("2", server.takeRequest().requestUrl?.queryParameter("start"))
    }

    @Test
    fun incompleteResponseIsRejected() = runBlocking {
        server.enqueue(jsonResponse(totalCount = 3, appIds = listOf(1, 2)))
        server.enqueue(jsonResponse(totalCount = 3, appIds = emptyList()))

        val appIds = FourThreeCuratorSource.fetch(
            client = client,
            endpoint = server.url("/"),
            pageSize = 2,
        )

        assertNull(appIds)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun httpFailureIsRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val appIds = FourThreeCuratorSource.fetch(client, server.url("/"))

        assertNull(appIds)
    }

    @Test
    fun parserDeduplicatesAppIds() {
        val page = FourThreeCuratorSource.parse(
            responseBody(success = 1, totalCount = 2, appIds = listOf(300, 10, 10)),
        )

        assertEquals(setOf(10, 300), page?.appIds)
        assertEquals(2, page?.totalCount)
    }

    @Test
    fun invalidResponsesAreRejected() {
        assertNull(FourThreeCuratorSource.parse("not json"))
        assertNull(FourThreeCuratorSource.parse(responseBody(success = 0, totalCount = 1, appIds = listOf(1))))
        assertNull(
            FourThreeCuratorSource.parse(
                buildJsonObject {
                    put("success", 1)
                    put("results_html", "<a data-ds-appid=\"1\"></a>")
                }.toString(),
            ),
        )
        assertNull(
            FourThreeCuratorSource.parse(
                responseBody(success = 1, totalCount = 1, appIds = listOf(1))
                    .replace("color_recommended", "color_not_recommended"),
            ),
        )
    }

    private fun jsonResponse(totalCount: Int, appIds: List<Int>): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(responseBody(success = 1, totalCount = totalCount, appIds = appIds))
    }

    private fun responseBody(success: Int, totalCount: Int, appIds: List<Int>): String {
        val html = appIds.joinToString("") { appId ->
            """<a data-ds-appid="$appId"><span class="color_recommended">Recommended</span><img data-ds-appid="$appId"></a>"""
        }
        return buildJsonObject {
            put("success", success)
            put("total_count", totalCount)
            put("results_html", html)
        }.toString()
    }
}
