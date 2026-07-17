package app.gamenative.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommunityConfigServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var service: CommunityConfigService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
        service = CommunityConfigService(
            client = httpClient,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
        server.shutdown()
    }

    @Test
    fun searchGames_encodesQueryAndParsesResults() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "games": [
                    { "id": 3405, "name": "ELDEN RING" },
                    { "id": 2149, "name": "ELDEN RING NIGHTREIGN" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = service.searchGames("ELDEN RING")

        assertEquals(listOf(3405, 2149), result.map { it.id })
        assertEquals("ELDEN RING", result.first().name)
        val request = server.takeRequest()
        assertEquals("/api/games/search?q=ELDEN%20RING", request.path)
    }

    @Test
    fun fetchConfigs_parsesRunAndUsesGpuFilter() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "runs": [
                    {
                      "id": 231820,
                      "deviceId": 10253,
                      "rating": 4,
                      "avgFps": 28.324,
                      "tags": ["playable", "minor_stutter"],
                      "notes": "Runs well",
                      "configs": {
                        "id": "STEAM_1245620",
                        "containerVariant": "bionic",
                        "wineVersion": "proton-10.0-arm64ec-2"
                      },
                      "createdAt": "2026-04-23T18:55:11.115263+00:00",
                      "appVersion": "0.9.0",
                      "gameName": "ELDEN RING",
                      "device": {
                        "id": 10253,
                        "model": "samsung SM-S908U",
                        "gpu": "Adreno (TM) 730",
                        "androidVer": "16",
                        "soc": "Snapdragon 8 Gen 1"
                      }
                    }
                  ],
                  "total": 5,
                  "page": 0,
                  "pageSize": 20
                }
                """.trimIndent(),
            ),
        )

        val page = service.fetchConfigs(
            gameId = 3405,
            gpu = "Adreno (TM) 730",
            sort = CommunityConfigSort.HIGHEST_RATED,
            page = 0,
        )

        assertEquals(5, page.total)
        assertEquals(1, page.runs.size)
        val run = page.runs.single()
        assertEquals(231820L, run.id)
        assertEquals(28.324, run.averageFps!!, 0.0001)
        assertEquals("STEAM", run.configStore)
        assertEquals("bionic", run.configString("containerVariant"))
        assertEquals("Snapdragon 8 Gen 1", run.device.soc)
        val request = server.takeRequest()
        assertEquals("3405", request.requestUrl?.queryParameter("gameId"))
        assertEquals("Adreno (TM) 730", request.requestUrl?.queryParameter("gpu"))
        assertEquals("rating", request.requestUrl?.queryParameter("sort"))
        assertEquals("desc", request.requestUrl?.queryParameter("dir"))
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
    }

    @Test
    fun fetchConfigs_omitsGpuAndSupportsNewestSort() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{ "runs": [], "total": 0, "page": 1, "pageSize": 20 }""",
            ),
        )

        service.fetchConfigs(
            gameId = 10,
            gpu = null,
            sort = CommunityConfigSort.NEWEST,
            page = 1,
        )

        val request = server.takeRequest()
        assertNull(request.requestUrl?.queryParameter("gpu"))
        assertEquals("created_at", request.requestUrl?.queryParameter("sort"))
        assertEquals("1", request.requestUrl?.queryParameter("page"))
    }

    @Test
    fun httpError_exposesStatusWithoutParsingRuns() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{ "error": { "message": "Try later" } }""",
            ),
        )

        val error = runCatching {
            service.fetchConfigs(10, null, CommunityConfigSort.NEWEST, 0)
        }.exceptionOrNull()

        assertTrue(error is CommunityConfigApiException)
        assertEquals(503, (error as CommunityConfigApiException).statusCode)
        assertEquals("Try later", error.message)
    }

    @Test
    fun selectCommunityGame_prefersNormalizedExactTitle() {
        val games = listOf(
            CommunityGame(1, "ELDEN RING NIGHTREIGN"),
            CommunityGame(2, "Elden Ring"),
        )

        assertEquals(2, selectCommunityGame("ELDEN RING", games)?.id)
        assertNull(selectCommunityGame("ELDEN RING", emptyList()))
    }

    @Test
    fun gpuMatcher_normalizesKnownVendorFormattingAndFallsBackSafely() {
        assertEquals("adreno:730", canonicalCommunityGpu("Qualcomm Adreno (TM) 730"))
        assertEquals("arm:g715", canonicalCommunityGpu("Immortalis-G715 MC11"))
        assertEquals(
            "exact_gpu_match",
            communityConfigMatchType("Qualcomm Adreno 730", "Adreno (TM) 730"),
        )
        assertEquals(
            "fallback_match",
            communityConfigMatchType("Adreno 730", "Adreno 740"),
        )
        assertTrue(canonicalCommunityGpu("Unknown GPU").isBlank())
    }
}
