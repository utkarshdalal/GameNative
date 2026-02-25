package app.gamenative.service.gog.api

import app.gamenative.service.gog.GOGConstants
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive tests for GOGManifestParser
 * Uses real data classes and JSON parsing
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GOGManifestParserTest {
    private lateinit var parser: GOGManifestParser

    @Before
    fun setUp() {
        parser = GOGManifestParser()
    }


    // Functions to create dependency items
    private fun createTestChunk(
        md5: String = "aabbccdd11223344",
        size: Long = 1000L,
        compressedSize: Long? = 500L,
    ) = FileChunk(
        compressedMd5 = md5,
        size = size,
        compressedSize = compressedSize,
        md5 = md5,
    )

    private fun createTestDepotFile(
        path: String = "game.exe",
        productId: String? = null,
        chunks: List<FileChunk> = listOf(createTestChunk()),
        flags: List<String> = emptyList(),
    ) = DepotFile(
        path = path,
        productId = productId,
        chunks = chunks,
        md5 = null,
        sha256 = null,
        flags = flags,
    )

    private fun createTestDepot(
        languages: List<String> = listOf("en-US"),
        manifest: String = "depot_manifest_url",
    ) = Depot(
        languages = languages,
        size = 1000000,
        manifest = manifest,
        productId = "1",
        compressedSize = 5,
        osBitness = emptyList(),
    )

    private fun createTestProduct(productId: String = "12345") = Product(
        productId = productId,
        name = "Test Game",
    )

    private fun createTestBuild(
        buildId: String = "build123",
        generation: Int = 2,
    ) = GOGBuild(
        buildId = buildId,
        productId = "12345",
        platform = "windows",
        generation = generation,
        versionName = "1.0.0",
        branch = "master",
        link = "https://cdn.gog.com/manifest",
        legacyBuildId = null,
    )



    // Tests here

    @Test
    fun testSelectBuild_returnsGen2Build() {
        val gen1 = createTestBuild(buildId = "old", generation = 1)
        val gen2 = createTestBuild(buildId = "new", generation = 2)
        val builds = listOf(gen1, gen2)

        val result = parser.selectBuild(builds)

        assertNotNull(result)
        assertEquals(2, result?.generation)
        assertEquals("new", result?.buildId)
    }

    @Test
    fun testSelectBuild_returnsNullWhenEmpty() {
        val result = parser.selectBuild(emptyList())
        assertNull(result)
    }

    @Test
    fun testSelectBuild_returnsNullWhenNoGen2() {
        val gen1Only = listOf(createTestBuild(generation = 1))
        val result = parser.selectBuild(gen1Only, preferredGeneration = 2)
        assertNull(result)
    }

    @Test
    fun testSelectBuild_returnsGen1WhenRequested() {
        val gen1Only = listOf(createTestBuild(buildId = "legacy", generation = 1))
        val result = parser.selectBuild(gen1Only, preferredGeneration = 1)
        assertNotNull(result)
        assertEquals(1, result?.generation)
        assertEquals("legacy", result?.buildId)
    }

    @Test
    fun testFilterDepotsByLanguage() {
        val enDepot = createTestDepot(languages = listOf("en"))
        val frDepot = createTestDepot(languages = listOf("fr"))
        val manifest = GOGManifestMeta(
            baseProductId = "12345",
            installDirectory = "game",
            depots = listOf(enDepot, frDepot),
            dependencies = emptyList(),
            products = emptyList(),
        )

        val (depots, effectiveLang) = parser.filterDepotsByLanguage(manifest, "english")

        assertEquals(1, depots.size)
        assertTrue(depots[0].languages.contains("en"))
        assertTrue(effectiveLang in GOGConstants.CONTAINER_LANGUAGE_TO_GOG_CODES.getValue(GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE))
    }

    @Test
    fun testFilterDepotsByLanguage_enUSMatchesEn() {
        // Container language "english" resolves to en-US, en; games using en-US in manifest should match
        val enUSDepot = createTestDepot(languages = listOf("en-US"))
        val frDepot = createTestDepot(languages = listOf("fr"))
        val manifest = GOGManifestMeta(
            baseProductId = "12345",
            installDirectory = "game",
            depots = listOf(enUSDepot, frDepot),
            dependencies = emptyList(),
            products = emptyList(),
        )

        val (depots, effectiveLang) = parser.filterDepotsByLanguage(manifest, "english")

        assertEquals(1, depots.size)
        assertTrue(depots[0].languages.contains("en-US"))
        assertTrue(effectiveLang in GOGConstants.CONTAINER_LANGUAGE_TO_GOG_CODES.getValue(GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE))
    }

    @Test
    fun testFilterDepotsByLanguage_fallbackToEnglishWhenRequestedLanguageHasNoDepots() {
        val enUSDepot = createTestDepot(languages = listOf("en-US"))
        val manifest = GOGManifestMeta(
            baseProductId = "12345",
            installDirectory = "game",
            depots = listOf(enUSDepot),
            dependencies = emptyList(),
            products = emptyList(),
        )

        val (depots, effectiveLang) = parser.filterDepotsByLanguage(manifest, "greek")

        assertEquals(1, depots.size)
        assertTrue(depots[0].languages.contains("en-US"))
        assertTrue(effectiveLang in GOGConstants.CONTAINER_LANGUAGE_TO_GOG_CODES.getValue(GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE))
    }

    @Test
    fun testFilterDepotsByBitness() {
        val depot64 = createTestDepot().copy(osBitness = listOf("64"))
        val depot32 = createTestDepot().copy(osBitness = listOf("32"))
        val depotBoth = createTestDepot().copy(osBitness = listOf("32", "64"))
        val depots = listOf(depot64, depot32, depotBoth)

        val result = parser.filterDepotsByBitness(depots, "64")

        assertEquals(2, result.size)
        assertTrue(result.any { it.osBitness?.contains("64") == true })
    }

    @Test
    fun testFilterDepotsByBitness_nullBitnessIncluded() {
        val depotWithBitness = createTestDepot().copy(osBitness = listOf("64"))
        val depotNoBitness = createTestDepot().copy(osBitness = null)
        val depots = listOf(depotWithBitness, depotNoBitness)

        val result = parser.filterDepotsByBitness(depots, "64")

        assertEquals(2, result.size) // Both should be included
    }

    @Test
    fun testFilterDepotsByOwnership() {
        val ownedDepot = createTestDepot().copy(productId = "12345")
        val unownedDepot = createTestDepot().copy(productId = "67890")
        val depots = listOf(ownedDepot, unownedDepot)
        val ownedProductIds = setOf("12345")

        val result = parser.filterDepotsByOwnership(depots, ownedProductIds)

        assertEquals(1, result.size)
        assertEquals("12345", result[0].productId)
    }

    @Test
    fun testSeparateBaseDLC() {
        val baseFile = createTestDepotFile(path = "base.exe", productId = null)
        val dlcFile = createTestDepotFile(path = "dlc.dat", productId = "dlc_123")
        val files = listOf(baseFile, dlcFile)

        val (base, dlc) = parser.separateBaseDLC(files, "12345")

        assertEquals(1, base.size)
        assertEquals(1, dlc.size)
        assertEquals("base.exe", base[0].path)
        assertEquals("dlc.dat", dlc[0].path)
    }

    @Test
    fun testSeparateSupportFiles() {
        val gameFile = createTestDepotFile(path = "game.exe", flags = emptyList())
        val supportFile = createTestDepotFile(path = "__support/redist/vcredist.exe", flags = listOf("support"))
        val files = listOf(gameFile, supportFile)

        val (game, support) = parser.separateSupportFiles(files)

        assertEquals(1, game.size)
        assertEquals(1, support.size)
        assertEquals("__support/redist/vcredist.exe", support[0].path)
        assertTrue(support[0].flags.contains("support"))
    }

    @Test
    fun testCalculateTotalSize_usesCompressedSize() {
        val chunk1 = createTestChunk(size = 1000, compressedSize = 500)
        val chunk2 = createTestChunk(size = 2000, compressedSize = 800)
        val file = createTestDepotFile(chunks = listOf(chunk1, chunk2))

        val result = parser.calculateTotalSize(listOf(file))

        assertEquals(1300L, result) // 500 + 800
    }

    @Test
    fun testCalculateTotalSize_fallsBackToSizeWhenNoCompression() {
        val chunk = createTestChunk(size = 1000, compressedSize = null)
        val file = createTestDepotFile(chunks = listOf(chunk))

        val result = parser.calculateTotalSize(listOf(file))

        assertEquals(1000L, result)
    }

    @Test
    fun testCalculateUncompressedSize() {
        val chunk1 = createTestChunk(size = 1000, compressedSize = 500)
        val chunk2 = createTestChunk(size = 2000, compressedSize = 800)
        val file = createTestDepotFile(chunks = listOf(chunk1, chunk2))

        val result = parser.calculateUncompressedSize(listOf(file))

        assertEquals(3000L, result) // 1000 + 2000
    }

    @Test
    fun testFindDLCProducts() {
        val baseProduct = createTestProduct("12345")
        val dlcProduct = createTestProduct("67890")
        val manifest = GOGManifestMeta(
            baseProductId = "12345",
            installDirectory = "game",
            depots = emptyList(),
            dependencies = emptyList(),
            products = listOf(baseProduct, dlcProduct),
        )

        val result = parser.findDLCProducts(manifest)

        assertEquals(1, result.size)
        assertEquals("67890", result[0].productId)
    }

    @Test
    fun testHasDLC_true() {
        val manifest = GOGManifestMeta(
            baseProductId = "12345",
            installDirectory = "game",
            depots = emptyList(),
            dependencies = emptyList(),
            products = listOf(createTestProduct("12345"), createTestProduct("dlc")),
        )

        assertTrue(parser.hasDLC(manifest))
    }

    @Test
    fun testHasDLC_false() {
        val manifest = GOGManifestMeta(
            baseProductId = "12345",
            installDirectory = "game",
            depots = emptyList(),
            dependencies = emptyList(),
            products = listOf(createTestProduct("12345")),
        )

        assertFalse(parser.hasDLC(manifest))
    }

    @Test
    fun testBuildChunkUrlMap() {
        val chunks = listOf("aabbccdd11223344", "11223344aabbccdd")
        val baseUrls = listOf("https://cdn.gog.com/content")

        val result = parser.buildChunkUrlMap(chunks, baseUrls)

        assertEquals(2, result.size)
        assertEquals("https://cdn.gog.com/content/aa/bb/aabbccdd11223344", result["aabbccdd11223344"])
        assertEquals("https://cdn.gog.com/content/11/22/11223344aabbccdd", result["11223344aabbccdd"])
    }

    @Test
    fun testBuildChunkUrlMap_emptyUrls() {
        val result = parser.buildChunkUrlMap(listOf("hash"), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun testBuildChunkUrlMap_shortHash() {
        val chunks = listOf("abc")
        val baseUrls = listOf("https://cdn.gog.com")

        val result = parser.buildChunkUrlMap(chunks, baseUrls)

        assertEquals("https://cdn.gog.com/abc", result["abc"])
    }

    @Test
    fun testBuildChunkUrlMapWithProducts() {
        val chunks = listOf("aabbccdd11223344", "11223344aabbccdd")
        val chunkToProductMap = mapOf(
            "aabbccdd11223344" to "12345",
            "11223344aabbccdd" to "67890"
        )
        val productUrlMap = mapOf(
            "12345" to listOf("https://cdn1.gog.com/product1"),
            "67890" to listOf("https://cdn2.gog.com/product2")
        )

        val result = parser.buildChunkUrlMapWithProducts(chunks, chunkToProductMap, productUrlMap)

        assertEquals(2, result.size)
        assertEquals("https://cdn1.gog.com/product1/aa/bb/aabbccdd11223344", result["aabbccdd11223344"])
        assertEquals("https://cdn2.gog.com/product2/11/22/11223344aabbccdd", result["11223344aabbccdd"])
    }

    @Test
    fun testBuildChunkUrlMapWithProducts_missingProduct() {
        val chunks = listOf("aabbccdd11223344")
        val chunkToProductMap = mapOf("aabbccdd11223344" to "12345")
        val productUrlMap = emptyMap<String, List<String>>()

        val result = parser.buildChunkUrlMapWithProducts(chunks, chunkToProductMap, productUrlMap)

        assertTrue(result.isEmpty())
    }

    @Test
    fun testExtractChunkHashes_preservesOrder() {
        val chunk1 = createTestChunk("aaaa")
        val chunk2 = createTestChunk("bbbb")
        val chunk3 = createTestChunk("cccc")
        val file1 = createTestDepotFile(chunks = listOf(chunk1, chunk2))
        val file2 = createTestDepotFile(chunks = listOf(chunk3))

        val result = parser.extractChunkHashes(listOf(file1, file2))

        assertEquals(listOf("aaaa", "bbbb", "cccc"), result)
    }

    @Test
    fun testExtractChunkHashes_removesDuplicates() {
        val chunk1 = createTestChunk("aaaa")
        val chunk2 = createTestChunk("aaaa") // duplicate
        val file = createTestDepotFile(chunks = listOf(chunk1, chunk2))

        val result = parser.extractChunkHashes(listOf(file))

        assertEquals(1, result.size)
        assertEquals("aaaa", result[0])
    }

    @Test
    fun testDetectGeneration() {
        val gen2Build = createTestBuild(generation = 2)
        assertEquals(2, parser.detectGeneration(gen2Build))

        val gen1Build = createTestBuild(generation = 1)
        assertEquals(1, parser.detectGeneration(gen1Build))
    }

    // ========== JSON Parsing Tests ==========

    @Test
    fun testParseBuilds() {
        val json = """
            {
                "total_count": 1,
                "count": 1,
                "items": [{
                    "build_id": "123",
                    "product_id": "456",
                    "os": "windows",
                    "generation": 2,
                    "version_name": "1.0",
                    "branch": "master",
                    "link": "https://cdn.gog.com/manifest"
                }]
            }
        """.trimIndent()

        val result = parser.parseBuilds(json)

        assertEquals(1, result.totalCount)
        assertEquals(1, result.count)
        assertEquals(1, result.items.size)
        assertEquals("123", result.items[0].buildId)
        assertEquals(2, result.items[0].generation)
        assertEquals("456", result.items[0].productId)
    }

    @Test
    fun testParseManifest() {
        val json = """
            {
                "baseProductId": "12345",
                "installDirectory": "game",
                "depots": [],
                "dependencies": [],
                "products": [{
                    "productId": "12345",
                    "name": "Test Game"
                }]
            }
        """.trimIndent()

        val result = parser.parseManifest(json)

        assertEquals("12345", result.baseProductId)
        assertEquals("game", result.installDirectory)
        assertEquals(1, result.products.size)
    }

    @Test
    fun testParseDepotManifest() {
        val json = """
            {
                "depot": {
                    "items": [
                        {
                            "type": "DepotFile",
                            "path": "game.exe",
                            "chunks": [],
                            "flags": []
                        }
                    ]
                }
            }
        """.trimIndent()

        val result = parser.parseDepotManifest(json)

        assertEquals(1, result.files.size)
        assertEquals("game.exe", result.files[0].path)
    }

    @Test
    fun testParseDependencyManifest() {
        val json = """
            {
                "depots": [{
                    "dependencyId": "vcredist2015",
                    "manifest": "manifest_url",
                    "compressedSize": 1000,
                    "size": 2000,
                    "languages": ["*"],
                    "readableName": "Visual C++ 2015",
                    "signature": "sig123",
                    "internal": false
                }]
            }
        """.trimIndent()

        val result = parser.parseDependencyManifest(json)

        assertEquals(1, result.depots.size)
        assertEquals("vcredist2015", result.depots[0].dependencyId)
        assertEquals("Visual C++ 2015", result.depots[0].readableName)
    }

    @Test
    fun testParseSecureLinks() {
        val json = """
            {
                "urls": [{
                    "url_format": "https://secure-cdn.gog.com/{path}?token={token}",
                    "parameters": {
                        "path": "content",
                        "token": "xyz123"
                    }
                }]
            }
        """.trimIndent()

        val result = parser.parseSecureLinks(json)

        assertEquals(1, result.urls.size)
        assertTrue(result.urls[0].contains("secure-cdn.gog.com"))
        assertTrue(result.urls[0].contains("xyz123"))
    }

    // ========== Decompression Tests ==========

    @Test
    fun testDecompressManifest_gzip() {
        val plainText = "Hello GOG World"
        val compressed = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzipOut ->
                gzipOut.write(plainText.toByteArray())
            }
            baos.toByteArray()
        }

        val result = parser.decompressManifest(compressed)

        assertEquals(plainText, result)
    }

    @Test
    fun testDecompressManifest_zlib() {
        val plainText = "Hello GOG World"
        val compressed = ByteArrayOutputStream().use { baos ->
            val deflater = Deflater()
            try {
                val buffer = ByteArray(1024)
                deflater.setInput(plainText.toByteArray())
                deflater.finish()
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    baos.write(buffer, 0, count)
                }
            } finally {
                deflater.end()
            }
            baos.toByteArray()
        }

        val result = parser.decompressManifest(compressed)

        assertEquals(plainText, result)
    }

    @Test
    fun testDecompressManifest_plainText() {
        val plainText = "Hello GOG World"
        val result = parser.decompressManifest(plainText.toByteArray())
        assertEquals(plainText, result)
    }

    @Test
    fun testParseManifestV1_handlesGen1AndLanguageNormalization() {
        val json = JSONObject().apply {
            put("product", JSONObject().apply {
                put("installDirectory", "Beyond Good and Evil")
                put("rootGameID", "1207658746")
                put("timestamp", "27565470")
                put("depots", JSONArray().apply {
                    put(JSONObject().apply {
                        put("gameIDs", JSONArray().put("1207658746"))
                        put("languages", JSONArray().put("Neutral"))
                        put("manifest", "b974058a-6dc2-41a3-b886-295a7b82a251")
                        put("size", 12345L)
                    })
                    put(JSONObject().apply {
                        put("gameIDs", JSONArray().put("1207658746"))
                        put("languages", JSONArray().put("en-US"))
                        put("manifest", "494a4547-48ea-40c0-ad21-b96ab8fa8cba")
                        put("size", 67890L)
                    })
                })
                put("gameIDs", JSONArray().put(JSONObject().apply {
                    put("gameID", "1207658746")
                    put("name", JSONObject().put("en", "Beyond Good and Evil"))
                }))
            })
        }
        val result = parser.parseManifestV1(json)
        assertEquals("Beyond Good and Evil", result.installDirectory)
        assertEquals("1207658746", result.baseProductId)
        assertEquals("27565470", result.productTimestamp)
        assertEquals(2, result.depots.size)
        assertEquals(listOf("*"), result.depots[0].languages)
        assertTrue(result.depots[1].languages.contains("en-US"))
        assertEquals(1, result.products.size)
        assertEquals("1207658746", result.products[0].productId)
    }

    @Test
    fun testParseManifestV1_emptyLanguagesBecomesWildcard() {
        val json = JSONObject().apply {
            put("product", JSONObject().apply {
                put("installDirectory", "Game")
                put("rootGameID", "1")
                put("timestamp", "1")
                put("depots", JSONArray().put(JSONObject().apply {
                    put("gameIDs", JSONArray().put("1"))
                    put("manifest", "abc")
                    put("size", 0L)
                }))
                put("gameIDs", JSONArray())
            })
        }
        val result = parser.parseManifestV1(json)
        assertEquals(1, result.depots.size)
        assertEquals(listOf("*"), result.depots[0].languages)
    }

    @Test
    fun testParseV1DepotManifest_filtersDirectories() {
        val json = """
            {"depot":{"files":[
                {"path":"game.exe","hash":"abc123","size":100,"offset":0},
                {"directory":true,"path":"data"},
                {"path":"readme.txt","hash":"def","size":10,"offset":100}
            ]}}
        """.trimIndent()
        val result = parser.parseV1DepotManifest(json)
        assertEquals(2, result.size)
        assertEquals("game.exe", result[0].path)
        assertEquals("abc123", result[0].hash)
        assertEquals(100L, result[0].size)
        assertEquals(0L, result[0].offset)
        assertEquals("readme.txt", result[1].path)
    }

    @Test
    fun testParseV1DepotManifest_emptyFilesReturnsEmpty() {
        val result = parser.parseV1DepotManifest("{}")
        assertTrue(result.isEmpty())
    }

    @Test
    fun testParseV1DepotManifest_missingHashDefaultsToEmpty() {
        val json = """{"files":[{"path":"file.bin","size":5,"offset":0}]}"""
        val result = parser.parseV1DepotManifest(json)
        assertEquals(1, result.size)
        assertEquals("", result[0].hash)
        assertEquals(5L, result[0].size)
    }
}
