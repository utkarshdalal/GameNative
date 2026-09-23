package app.gamenative.steam.curated

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

class CuratedListRemoteSourceTest {

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
    fun fetchReturnsValidatedAppIds() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(listJson(totalCount = 3, appIds = listOf(3, 1, 2))),
        )

        val appIds = CuratedListRemoteSource.fetch(
            client,
            server.url("/curated_lists/steam/four_three_games.json"),
        )

        assertEquals(setOf(1, 2, 3), appIds)
    }

    @Test
    fun httpFailureIsRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(CuratedListRemoteSource.fetch(client, server.url("/")))
    }

    @Test
    fun invalidPayloadsAreRejected() {
        assertNull(CuratedListRemoteSource.parse("not json"))
        assertNull(
            CuratedListRemoteSource.parse(listJson(version = 2, totalCount = 1, appIds = listOf(1))),
        )
        assertNull(
            CuratedListRemoteSource.parse(
                listJson(curatorClanId = 1L, totalCount = 1, appIds = listOf(1)),
            ),
        )
        assertNull(
            CuratedListRemoteSource.parse(
                listJson(reviewType = "not-recommended", totalCount = 1, appIds = listOf(1)),
            ),
        )
        assertNull(CuratedListRemoteSource.parse(listJson(totalCount = 0, appIds = emptyList())))
        assertNull(CuratedListRemoteSource.parse(listJson(totalCount = 5, appIds = listOf(1, 2))))
        assertNull(CuratedListRemoteSource.parse(listJson(totalCount = 1, appIds = listOf(1, 1))))
    }

    private fun listJson(
        version: Int = 1,
        curatorClanId: Long = 43078746L,
        reviewType: String = "recommended",
        totalCount: Int,
        appIds: List<Int>,
    ): String {
        return buildJsonObject {
            put("version", version)
            put("curatorClanId", curatorClanId)
            put("curatorSlug", "43078746-Does-it-4-3")
            put("reviewType", reviewType)
            put("fetchedAt", "2026-08-21")
            put("totalCount", totalCount)
            put("appIds", buildJsonArray { appIds.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }
}
