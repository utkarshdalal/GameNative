package app.gamenative.mods

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NexusApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var client: NexusApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient()
        client = NexusApiClient(
            client = okHttpClient,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            nexusBaseUrl = server.url("").toString().trimEnd('/'),
            graphUrls = listOf(server.url("/graphql").toString()),
        )
    }

    @After
    fun tearDown() {
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        okHttpClient.cache?.close()
        server.shutdown()
    }

    @Test
    fun getModFiles_parsesFilesAndHeadersRequest() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "files": [
                        {
                          "file_id": 55,
                          "name": "Main file",
                          "file_name": "main.zip",
                          "version": "1.2",
                          "category_id": 1,
                          "category_name": "MAIN",
                          "is_primary": true,
                          "size": 2048,
                          "uploaded_timestamp": 123,
                          "uploaded_time": "2024-01-02T03:04:05.000Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val files = client.getModFiles("skyrimspecialedition", 123, apiKey = "key")

        assertEquals(1, files.size)
        assertEquals(55L, files.single().fileId)
        assertEquals("main.zip", files.single().fileName)
        assertEquals(2048L * 1024L, files.single().sizeBytes)
        assertEquals(1, files.single().categoryId)
        assertEquals("MAIN", files.single().categoryName)
        assertEquals(true, files.single().isPrimary)
        assertEquals("2024-01-02T03:04:05.000Z", files.single().uploadedTime)
        val request = server.takeRequest()
        assertEquals("key", request.headers["APIKEY"])
        assertEquals("GameNative", request.headers["Application-Name"])
    }

    @Test
    fun rateLimitResponse_throwsNexusApiException() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("x-rl-hourly-remaining", "0")
                .addHeader("x-rl-daily-remaining", "10")
                .setBody("{}"),
        )

        val error = runCatching {
            client.getModInfo("fallout4", 1, apiKey = "key")
        }.exceptionOrNull()

        assertTrue(error is NexusApiException)
        val nexusError = error as NexusApiException
        assertEquals(429, nexusError.statusCode)
        assertEquals(0, nexusError.hourlyRemaining)
        assertEquals(10, nexusError.dailyRemaining)
    }

    @Test
    fun getDownloadLinks_404_throwsClearUnavailableMessage() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("{}"),
        )

        val error = runCatching {
            client.getDownloadLinks("fallout4", 1, 2, apiKey = "key")
        }.exceptionOrNull()

        assertTrue(error is NexusApiException)
        assertEquals("This Nexus file is no longer downloadable", error?.message)
    }

    @Test
    fun getCollectionRevision_usesGraphQLCollectionEndpoint() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": {
                        "collection": { "name": "Test Collection" },
                        "collectionRevision": {
                          "revisionNumber": 5,
                          "modFiles": [
                            {
                              "fileId": 1000,
                              "optional": false,
                              "file": {
                                "fileId": 1000,
                                "name": "main.zip",
                                "sizeInBytes": 73400320,
                                "version": "1.0",
                                "mod": {
                                  "modId": 100,
                                  "name": "Main Mod",
                                  "game": { "domainName": "skyrimspecialedition" }
                                }
                              }
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val collection = client.getCollectionRevision(
            NexusCollectionReference("skyrimspecialedition", "test", 5),
            apiKey = "key",
        )

        assertEquals("Test Collection", collection.name)
        assertEquals(5, collection.revision)
        assertEquals(listOf(100L), collection.files.map { it.modId })
        assertEquals(listOf(1000L), collection.files.map { it.fileId })
        assertEquals(listOf(73400320L), collection.files.map { it.sizeBytes })
        assertTrue(collection.files.single().required)
        val request = server.takeRequest()
        assertEquals("/graphql", request.path)
        assertTrue(request.body.readUtf8().contains("collectionRevision"))
    }

    @Test
    fun getCollectionRevision_fallsBackToLegacyRestShape() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("{}"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "collection": {
                        "name": "Test Collection"
                      },
                      "revision": {
                        "revision_number": 5,
                        "mods": [
                          {
                            "mod_id": 100,
                            "file_id": 1000,
                            "mod_name": "Main Mod",
                            "file_name": "main.zip",
                            "position": 1,
                            "dependencies": [
                              { "mod_id": 50 }
                            ]
                          },
                          {
                            "mod_id": 50,
                            "file_id": 500,
                            "mod_name": "Required Mod",
                            "file_name": "required.zip",
                            "position": 2
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val collection = client.getCollectionRevision(
            NexusCollectionReference("skyrimspecialedition", "test", 5),
            apiKey = "key",
        )

        assertEquals("Test Collection", collection.name)
        assertEquals(5, collection.revision)
        assertEquals(listOf(50L, 100L), collection.files.map { it.modId })
        assertEquals(listOf(500L, 1000L), collection.files.map { it.fileId })
        assertEquals("/graphql", server.takeRequest().path)
        assertEquals("/v1/games/skyrimspecialedition/collections/test/revisions/5.json", server.takeRequest().path)
    }

    @Test
    fun getCollectionRevision_usesManifestWhenGraphQLModFilesAreTruncated() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": {
                        "collection": { "name": "Big Collection" },
                        "collectionRevision": {
                          "downloadLink": "/collection.json",
                          "modCount": 2,
                          "revisionNumber": 8,
                          "modFiles": [
                            {
                              "fileId": 1000,
                              "file": {
                                "fileId": 1000,
                                "name": "first.zip",
                                "mod": {
                                  "modId": 100,
                                  "name": "First",
                                  "game": { "domainName": "skyrimspecialedition" }
                                }
                              }
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "mods": [
                        {
                          "name": "First",
                          "source": {
                            "game": "skyrimspecialedition",
                            "modId": 100,
                            "fileId": 1000,
                            "logicalFileName": "first.zip",
                            "sizeBytes": 41943040
                          }
                        },
                        {
                          "name": "Second",
                          "optional": true,
                          "instructions": "Copy this to the game root folder",
                          "source": {
                            "game": "skyrimspecialedition",
                            "modId": 200,
                            "fileId": 2000,
                            "logicalFileName": "second.zip",
                            "size": "30 MB"
                          }
                        },
                        {
                          "name": "External ENB binaries",
                          "instructions": "Download manually from https://enbdev.com and place files in the game folder",
                          "destination": "Game Directory"
                        }
                      ],
                      "pluginLoadOrder": ["First.esm", "Second.esp"],
                      "rules": [
                        { "type": "before", "source": "First", "target": "Second" }
                      ],
                      "manualSteps": [
                        { "title": "Run tool", "text": "Run Nemesis after installing animations" }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val collection = client.getCollectionRevision(
            NexusCollectionReference("skyrimspecialedition", "test", 8),
            apiKey = "key",
        )

        assertEquals("Big Collection", collection.name)
        assertEquals(8, collection.revision)
        assertEquals(listOf(100L, 200L, 0L), collection.files.map { it.modId })
        assertEquals(listOf(41943040L, 30L * 1024L * 1024L, 0L), collection.files.map { it.sizeBytes })
        assertEquals(listOf(true, false, true), collection.files.map { it.required })
        assertEquals(
            listOf(
                NexusCollectionInstallClassification.AUTO_INSTALLABLE,
                NexusCollectionInstallClassification.NEEDS_PLACEMENT,
                NexusCollectionInstallClassification.EXTERNAL_MANUAL,
            ),
            collection.files.map { it.classification },
        )
        assertEquals(listOf("First.esm", "Second.esp"), collection.manifestInfo.rules.pluginLoadOrder)
        assertTrue(collection.manifestInfo.rules.ruleSources.any { it.path == "pluginLoadOrder" })
        assertTrue(collection.manifestInfo.rules.ruleSources.any { it.path == "rules" && "source" in it.itemKeys })
        assertEquals(1, collection.manifestInfo.manualSteps.size)
        assertEquals("/graphql", server.takeRequest().path)
        assertEquals("/collection.json", server.takeRequest().path)
    }

    @Test
    fun getCollectionRevision_keepsManifestRulesWhenGraphQLModFilesAreComplete() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": {
                        "collection": { "name": "Rule Collection" },
                        "collectionRevision": {
                          "downloadLink": "/rules-collection.json",
                          "modCount": 1,
                          "revisionNumber": 9,
                          "modFiles": [
                            {
                              "fileId": 1000,
                              "file": {
                                "fileId": 1000,
                                "name": "first.zip",
                                "mod": {
                                  "modId": 100,
                                  "name": "First",
                                  "game": { "domainName": "skyrimspecialedition" }
                                }
                              }
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "mods": [
                        {
                          "name": "First",
                          "source": {
                            "game": "skyrimspecialedition",
                            "modId": 100,
                            "fileId": 1000,
                            "logicalFileName": "first.zip"
                          }
                        }
                      ],
                      "pluginLoadOrder": ["First.esp"],
                      "rules": [
                        { "type": "after", "source": "First", "target": "Base" }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val collection = client.getCollectionRevision(
            NexusCollectionReference("skyrimspecialedition", "test", 9),
            apiKey = "key",
        )

        assertEquals(listOf(100L), collection.files.map { it.modId })
        assertEquals(listOf("First.esp"), collection.manifestInfo.rules.pluginLoadOrder)
        assertEquals(2, collection.manifestInfo.rules.ruleSources.size)
        assertEquals("/graphql", server.takeRequest().path)
        assertEquals("/rules-collection.json", server.takeRequest().path)
    }

    @Test
    fun nexusFileSelector_sortsCurrentFilesAndHidesOldFiles() {
        val files = listOf(
            NexusModFile(
                fileId = 1,
                name = "Old",
                version = "1.0",
                fileName = "old.zip",
                sizeBytes = 1,
                uploadedTimestamp = 300,
                categoryId = 4,
                categoryName = "OLD_VERSION",
            ),
            NexusModFile(
                fileId = 2,
                name = "Optional",
                version = "1.1",
                fileName = "optional.zip",
                sizeBytes = 1,
                uploadedTimestamp = 400,
                categoryId = 3,
                categoryName = "OPTIONAL",
            ),
            NexusModFile(
                fileId = 3,
                name = "Main",
                version = "1.2",
                fileName = "main.zip",
                sizeBytes = 1,
                uploadedTimestamp = 200,
                categoryId = 1,
                categoryName = "MAIN",
                isPrimary = true,
            ),
        )

        assertEquals(listOf(3L, 2L), NexusFileSelector.currentFiles(files).map { it.fileId })
        assertEquals(listOf(1L), NexusFileSelector.olderFiles(files).map { it.fileId })
    }

    @Test
    fun collectionPlanner_keepsMultipleFilesFromSameMod() {
        val files = listOf(
            NexusCollectionFile(
                gameDomain = "skyrimspecialedition",
                modId = 100,
                fileId = 1,
                position = 1,
            ),
            NexusCollectionFile(
                gameDomain = "skyrimspecialedition",
                modId = 100,
                fileId = 2,
                position = 2,
            ),
            NexusCollectionFile(
                gameDomain = "skyrimspecialedition",
                modId = 200,
                fileId = 3,
                position = 3,
                dependencyModIds = listOf(100),
            ),
        )

        val ordered = NexusCollectionPlanner.orderedFiles(files)

        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.fileId })
    }

    @Test
    fun collectionPlanner_ignoresDependencyModIdsFromOtherGameDomains() {
        val files = listOf(
            NexusCollectionFile(
                gameDomain = "skyrimspecialedition",
                modId = 200,
                fileId = 2,
                position = 0,
                dependencyModIds = listOf(100),
            ),
            NexusCollectionFile(
                gameDomain = "fallout4",
                modId = 100,
                fileId = 1,
                position = 1,
            ),
        )

        val ordered = NexusCollectionPlanner.orderedFiles(files)

        assertEquals(listOf(2L, 1L), ordered.map { it.fileId })
    }
}
