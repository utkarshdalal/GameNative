package app.gamenative.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                        "wineVersion": "proton-10.0-arm64ec-2",
                        "dxwrapper": "dxvk",
                        "dxwrapperConfig": "version=2.6.1",
                        "executablePath": "../../windows/system32/cmd.exe",
                        "execArgs": "/c dangerous-command",
                        "envVars": "UNSAFE=1"
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
        assertEquals("bionic", run.configString("containerVariant"))
        assertFalse(run.config.containsKey("id"))
        assertFalse(run.config.containsKey("executablePath"))
        assertFalse(run.config.containsKey("execArgs"))
        assertFalse(run.config.containsKey("envVars"))
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
    fun searchDevices_parsesExactModelResults() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "devices": [
                    {
                      "id": 45701,
                      "model": "samsung SM-F968U1",
                      "gpu": "Adreno (TM) 830",
                      "androidVer": "16",
                      "soc": "SM8750"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val devices = service.searchDevices("samsung SM-F968U1")

        assertEquals(45701, devices.single().id)
        assertEquals("SM8750", devices.single().soc)
        assertEquals(
            "samsung SM-F968U1",
            server.takeRequest().requestUrl?.queryParameter("model"),
        )
    }

    @Test
    fun findDevices_retriesModelOnlyWhenPrimaryResultsDoNotMatch() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "devices": [{
                        "id": 9,
                        "model": "samsung SM-S918U",
                        "gpu": "Adreno 740",
                        "androidVer": "16"
                    }]
                }""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{
                    "devices": [{
                        "id": 10,
                        "model": "samsung SM-S908U",
                        "gpu": "Adreno (TM) 730",
                        "androidVer": "16"
                    }]
                }""",
            ),
        )

        val result = service.findDevices(
            manufacturer = "samsung",
            model = "SM-S908U",
            gpu = "Qualcomm Adreno 730",
            androidVersion = "16",
        )

        assertEquals(listOf(10), result.map { it.id })
        assertEquals("samsung SM-S908U", server.takeRequest().requestUrl?.queryParameter("model"))
        assertEquals("SM-S908U", server.takeRequest().requestUrl?.queryParameter("model"))
    }

    @Test
    fun fetchConfigs_deviceFilterTakesPriorityOverGpu() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{ "runs": [], "total": 0, "page": 0, "pageSize": 20 }""",
            ),
        )

        service.fetchConfigs(
            gameId = 10,
            gpu = "Adreno 830",
            sort = CommunityConfigSort.NEWEST,
            page = 0,
            deviceIds = listOf(45701),
        )

        val request = server.takeRequest().requestUrl
        assertEquals("45701", request?.queryParameter("deviceId"))
        assertNull(request?.queryParameter("gpu"))
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
        assertNull(selectCommunityGame("ELDEN RING DELUXE", games))
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

    @Test
    fun deviceMatcher_returnsEveryCompatibleRecordForThePhysicalModel() {
        val devices = listOf(
            CommunityConfigDevice(1, "samsung SM-S908U", "Adreno 730", "15", "SM8450"),
            CommunityConfigDevice(2, "samsung SM-S908U", "Adreno (TM) 730", "16", "SM8450"),
            CommunityConfigDevice(3, "samsung SM-S908U", "", "16", ""),
            CommunityConfigDevice(4, "samsung SM-S908U", "Mali-G715", "16", ""),
            CommunityConfigDevice(5, "samsung SM-S918U", "Adreno 740", "16", ""),
        )

        val matches = selectCommunityDevices(
            devices = devices,
            manufacturer = "samsung",
            model = "SM-S908U",
            currentGpu = "Qualcomm Adreno 730",
            androidVersion = "16",
        )

        assertEquals(listOf(2, 1, 3), matches.map { it.id })
        assertEquals("samsung SM-F968U1", communityDeviceQuery("samsung", "SM-F968U1"))
        assertEquals("AYN Odin2", communityDeviceQuery("AYN", "AYN Odin2"))
    }

    @Test
    fun deviceMatcher_rejectsKnownIncompatibleGpus() {
        val matches = selectCommunityDevices(
            devices = listOf(
                CommunityConfigDevice(1, "samsung SM-S908U", "Mali-G715", "16", ""),
                CommunityConfigDevice(2, "samsung SM-S908U", "Xclipse 920", "16", ""),
            ),
            manufacturer = "samsung",
            model = "SM-S908U",
            currentGpu = "Adreno 730",
            androidVersion = "16",
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun fetchConfigs_aggregatesCompatibleDeviceIdsAndSortsRuns() = runBlocking {
        server.enqueue(MockResponse().setBody(configPage(runId = 1, rating = 3, deviceId = 11, total = 1)))
        server.enqueue(MockResponse().setBody(configPage(runId = 2, rating = 5, deviceId = 12, total = 1)))

        val result = service.fetchConfigs(
            gameId = 10,
            gpu = null,
            sort = CommunityConfigSort.HIGHEST_RATED,
            page = 0,
            deviceIds = listOf(11, 12),
        )

        assertEquals(listOf(2L, 1L), result.runs.map { it.id })
        assertEquals(2, result.total)
        assertFalse(result.hasMore)
        val requestedIds = listOf(server.takeRequest(), server.takeRequest())
            .mapNotNull { it.requestUrl?.queryParameter("deviceId") }
            .toSet()
        assertEquals(setOf("11", "12"), requestedIds)
    }

    @Test
    fun fetchConfigs_paginatesCompatibleDevicesAsOneGlobalList() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val deviceId = request.requestUrl?.queryParameter("deviceId")?.toIntOrNull()
                val page = request.requestUrl?.queryParameter("page")?.toIntOrNull()
                val run = when (deviceId to page) {
                    11 to 0 -> Triple(1L, 5, 11)
                    11 to 1 -> Triple(3L, 3, 11)
                    12 to 0 -> Triple(2L, 4, 12)
                    12 to 1 -> Triple(4L, 2, 12)
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(
                    configPage(
                        runId = run.first,
                        rating = run.second,
                        deviceId = run.third,
                        total = 2,
                        page = page ?: 0,
                        pageSize = 1,
                    ),
                )
            }
        }

        val pages = (0..3).map { page ->
            service.fetchConfigs(
                gameId = 10,
                gpu = null,
                sort = CommunityConfigSort.HIGHEST_RATED,
                page = page,
                limit = 1,
                deviceIds = listOf(11, 12),
            )
        }

        assertEquals(listOf(1L, 2L, 3L, 4L), pages.flatMap { it.runs }.map { it.id })
        assertTrue(pages.take(3).all { it.hasMore })
        assertFalse(pages.last().hasMore)
        assertTrue(pages.all { it.runs.size == 1 })
        assertEquals(4, pages.last().total)
    }

    @Test
    fun nullableMetadata_isParsedAsEmptyInsteadOfLiteralNull() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "runs": [{
                        "id": 1,
                        "rating": 3,
                        "tags": [null, "playable"],
                        "notes": null,
                        "appVersion": null,
                        "configs": {
                            "containerVariant": "bionic",
                            "wineVersion": "wine",
                            "dxwrapper": "dxvk",
                            "dxwrapperConfig": "version=2.6"
                        },
                        "device": {
                            "id": 11,
                            "model": "test",
                            "gpu": null,
                            "androidVer": null,
                            "soc": null
                        }
                    }],
                    "total": 1,
                    "page": 0,
                    "pageSize": 20
                }""",
            ),
        )

        val run = service.fetchConfigs(10, null, CommunityConfigSort.NEWEST, 0).runs.single()

        assertEquals("", run.notes)
        assertEquals("", run.appVersion)
        assertEquals(listOf("playable"), run.tags)
        assertEquals("", run.device.gpu)
        assertEquals("", run.device.androidVersion)
        assertEquals("", run.device.soc)
    }

    @Test
    fun malformedRuns_doNotKeepPaginationAliveAfterServerEnds() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{ "runs": [{ "id": 1, "configs": null }], "total": 1, "page": 0, "pageSize": 1 }""",
            ),
        )

        val result = service.fetchConfigs(10, null, CommunityConfigSort.NEWEST, 0)

        assertTrue(result.runs.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test
    fun oversizedResponse_isRejectedBeforeParsing() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(4 * 1024 * 1024 + 1)))

        val error = runCatching {
            service.fetchConfigs(10, null, CommunityConfigSort.NEWEST, 0)
        }.exceptionOrNull()

        assertTrue(error is CommunityConfigApiException)
        assertEquals("Compatibility response is too large", error?.message)
    }

    @Test
    fun communityConfigValidation_rejectsMissingRuntimeFieldsAndGlibcWhenUnsupported() {
        val unsafe = kotlinx.serialization.json.Json.parseToJsonElement(
            """{
                "containerVariant":"bionic",
                "wineVersion":"wine",
                "dxwrapper":"dxvk",
                "dxwrapperConfig":"version=2.6",
                "graphicsDriverConfig":{"version":"nested-values-are-not-accepted"},
                "executablePath":"cmd.exe",
                "cpuList":"0"
            }""",
        ).jsonObject

        val sanitized = sanitizeCommunityConfig(unsafe)
        assertTrue(isValidCommunityConfig(sanitized, allowGlibc = false))
        assertFalse(sanitized.containsKey("executablePath"))
        assertFalse(sanitized.containsKey("cpuList"))
        assertFalse(sanitized.containsKey("graphicsDriverConfig"))
        assertFalse(isValidCommunityConfig(JsonObject(sanitized - "dxwrapperConfig"), allowGlibc = false))

        val glibc = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"containerVariant":"glibc","dxwrapper":"dxvk","dxwrapperConfig":"version=2.6"}""",
        ).jsonObject
        assertFalse(isValidCommunityConfig(glibc, allowGlibc = false))
        assertTrue(isValidCommunityConfig(glibc, allowGlibc = true))
    }

    private fun configPage(
        runId: Long,
        rating: Int,
        deviceId: Int,
        total: Int,
        page: Int = 0,
        pageSize: Int = 20,
    ): String =
        """{
            "runs": [{
                "id": $runId,
                "deviceId": $deviceId,
                "rating": $rating,
                "configs": {
                    "containerVariant": "bionic",
                    "wineVersion": "wine",
                    "dxwrapper": "dxvk",
                    "dxwrapperConfig": "version=2.6"
                },
                "createdAt": "2026-01-0${runId}T00:00:00Z",
                "device": {"id": $deviceId, "model": "test", "gpu": "Adreno 730", "androidVer": "16"}
            }],
            "total": $total,
            "page": $page,
            "pageSize": $pageSize
        }"""
}
