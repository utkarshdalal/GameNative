package app.gamenative.service.epic.manifest.test

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.service.epic.manifest.ManifestUtils
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

/**
 * Comprehensive manifest parsing validation test suite.
 * Tests all manifest formats (v3, binary control files, etc.) against expected JSON outputs.
 */
@RunWith(AndroidJUnit4::class)
class ManifestParseValidationTest {

    private fun getContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun getManifestBytes(assetName: String): ByteArray {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { inputStream ->
            inputStream.readBytes()
        }
    }

    private fun getExpectedJson(assetName: String): JSONObject {
        val inputStream = InstrumentationRegistry.getInstrumentation().context.assets.open(assetName)
        val expectedText = inputStream.bufferedReader().use { it.readText() }
        return JSONObject(expectedText)
    }

    @Test
    fun testManifestParsingAgainstExpected() {
        val testManifests = listOf(
            "test-manifest.json" to "test-manifest.expected.json",
            "test-v3-manifest.json" to "test-v3-manifest.expected.json",
            "binary-control-file.manifest" to "binary-control-file.expected.json"
        )

        testManifests.forEach { (manifestAsset, expectedAsset) ->
            Timber.i("═══════════════════════════════════════════════════")
            Timber.i("Validating manifest: $manifestAsset")
            Timber.i("═══════════════════════════════════════════════════")

            // Parse with Kotlin
            val manifestBytes = getManifestBytes(manifestAsset)
            val manifest = ManifestUtils.loadFromBytes(manifestBytes)
            val actualJson = ManifestTestSerializer.serializeManifest(manifest)

            val expectedJson = getExpectedJson(expectedAsset)

            // Compare basic properties
            val differences = mutableListOf<String>()

            // Core manifest fields
            compareField(actualJson, expectedJson, "version", differences)
            compareField(actualJson, expectedJson, "headerSize", differences)
            compareField(actualJson, expectedJson, "isCompressed", differences)

            // Meta fields
            val actualMeta = actualJson.getJSONObject("meta")
            val expectedMeta = expectedJson.getJSONObject("meta")
            compareField(actualMeta, expectedMeta, "appName", differences)
            compareField(actualMeta, expectedMeta, "buildVersion", differences)
            compareField(actualMeta, expectedMeta, "launchExe", differences)
            compareField(actualMeta, expectedMeta, "launchCommand", differences)

            // Chunk data list
            val actualChunks = actualJson.getJSONObject("chunkDataList")
            val expectedChunks = expectedJson.getJSONObject("chunkDataList")
            compareField(actualChunks, expectedChunks, "count", differences)

            // Validate first 3 chunks in detail
            val actualChunkElements = actualChunks.getJSONArray("chunks")
            val expectedChunkElements = expectedChunks.getJSONArray("chunks")
            for (i in 0 until minOf(3, actualChunkElements.length(), expectedChunkElements.length())) {
                val actualChunk = actualChunkElements.getJSONObject(i)
                val expectedChunk = expectedChunkElements.getJSONObject(i)

                compareField(actualChunk, expectedChunk, "guidStr", differences, "Chunk $i")
                compareField(actualChunk, expectedChunk, "hash", differences, "Chunk $i")
                compareField(actualChunk, expectedChunk, "fileSize", differences, "Chunk $i")
                compareField(actualChunk, expectedChunk, "groupNum", differences, "Chunk $i")
            }

            // File manifest list
            val actualFiles = actualJson.getJSONObject("fileManifestList")
            val expectedFiles = expectedJson.getJSONObject("fileManifestList")
            compareField(actualFiles, expectedFiles, "count", differences)

            // Validate first 10 files in detail
            val actualFileElements = actualFiles.getJSONArray("files")
            val expectedFileElements = expectedFiles.getJSONArray("files")
            for (i in 0 until minOf(10, actualFileElements.length(), expectedFileElements.length())) {
                val actualFile = actualFileElements.getJSONObject(i)
                val expectedFile = expectedFileElements.getJSONObject(i)

                compareField(actualFile, expectedFile, "filename", differences, "File $i")
                compareField(actualFile, expectedFile, "hash", differences, "File $i")
                compareField(actualFile, expectedFile, "fileSize", differences, "File $i")

                // Validate chunk parts
                val actualChunkParts = actualFile.getJSONArray("chunkParts")
                val expectedChunkParts = expectedFile.getJSONArray("chunkParts")
                if (actualChunkParts.length() != expectedChunkParts.length()) {
                    differences.add("File $i (${actualFile.getString("filename")}): chunkParts count differs - Actual: ${actualChunkParts.length()}, Expected: ${expectedChunkParts.length()}")
                }
            }

            // Validate calculated sizes
            val actualDownloadSize = ManifestUtils.getTotalDownloadSize(manifest)
            val actualInstalledSize = ManifestUtils.getTotalInstalledSize(manifest)

            Timber.i("  Download size: ${ManifestUtils.formatBytes(actualDownloadSize)}")
            Timber.i("  Installed size: ${ManifestUtils.formatBytes(actualInstalledSize)}")

            // Log differences
            if (differences.isNotEmpty()) {
                Timber.e("❌ Found ${differences.size} differences for $manifestAsset:")
                differences.forEach { diff ->
                    Timber.e("  - $diff")
                }
                fail("Manifest parsing validation failed for $manifestAsset. See logs for details.")
            } else {
                Timber.i("✅ All validations passed for $manifestAsset!")
                Timber.i("  • Files: ${actualFiles.getInt("count")}")
                Timber.i("  • Chunks: ${actualChunks.getInt("count")}")
                Timber.i("  • Download: ${ManifestUtils.formatBytes(actualDownloadSize)}")
                Timber.i("  • Installed: ${ManifestUtils.formatBytes(actualInstalledSize)}")
            }
        }

        Timber.i("═══════════════════════════════════════════════════")
        Timber.i("✅ ALL MANIFEST TESTS PASSED!")
        Timber.i("═══════════════════════════════════════════════════")
    }

    @Test
    fun testV3ManifestSpecifics() {
        // Detailed test specifically for v3 manifest format - validates against expected JSON
        Timber.i("Running detailed v3 manifest validation...")

        val manifestBytes = getManifestBytes("test-v3-manifest.json")
        val manifest = ManifestUtils.loadFromBytes(manifestBytes)
        val expectedJson = getExpectedJson("test-v3-manifest.expected.json")

        // Verify manifest is not null and has required properties
        assertNotNull("Manifest should not be null", manifest)
        assertNotNull("Manifest meta should not be null", manifest.meta)

        // Validate against expected JSON values (not hardcoded)
        val expectedMeta = expectedJson.getJSONObject("meta")
        assertEquals("App name should match", expectedMeta.getString("appName"), manifest.meta?.appName)
        assertEquals("Build version should match", expectedMeta.getString("buildVersion"), manifest.meta?.buildVersion)
        assertEquals("Launch exe should match", expectedMeta.getString("launchExe"), manifest.meta?.launchExe)
        assertEquals("Manifest version should match", expectedJson.getInt("version"), manifest.version)

        // Validate counts
        val chunkCount = manifest.chunkDataList?.elements?.size ?: 0
        val fileCount = manifest.fileManifestList?.elements?.size ?: 0
        val expectedChunkCount = expectedJson.getJSONObject("chunkDataList").getInt("count")
        val expectedFileCount = expectedJson.getJSONObject("fileManifestList").getInt("count")

        assertEquals("Chunk count should match", expectedChunkCount, chunkCount)
        assertEquals("File count should match", expectedFileCount, fileCount)

        // Validate first file details from expected JSON
        val expectedFiles = expectedJson.getJSONObject("fileManifestList").getJSONArray("files")
        if (expectedFiles.length() > 0) {
            val expectedFirstFile = expectedFiles.getJSONObject(0)
            val firstFile = manifest.fileManifestList?.elements?.firstOrNull()
            assertNotNull("Should have files", firstFile)
            assertEquals("First file name should match", expectedFirstFile.getString("filename"), firstFile?.filename)
            assertEquals("First file size should match", expectedFirstFile.getLong("fileSize"), firstFile?.fileSize)
        }

        // Validate first chunk details from expected JSON
        val expectedChunks = expectedJson.getJSONObject("chunkDataList").getJSONArray("chunks")
        if (expectedChunks.length() > 0) {
            val expectedFirstChunk = expectedChunks.getJSONObject(0)
            val firstChunk = manifest.chunkDataList?.elements?.firstOrNull()
            assertNotNull("Should have chunks", firstChunk)
            assertEquals("First chunk GUID should match", expectedFirstChunk.getString("guidStr"), firstChunk?.guidStr)
            assertEquals("First chunk size should match", expectedFirstChunk.getLong("fileSize"), firstChunk?.fileSize)
            assertEquals("First chunk group should match", expectedFirstChunk.getInt("groupNum"), firstChunk?.groupNum)
        }

        Timber.i("✅ V3 manifest detailed validation passed!")
        Timber.i("  • App: ${manifest.meta?.appName} v${manifest.meta?.buildVersion}")
        Timber.i("  • Files: $fileCount, Chunks: $chunkCount")
    }

    @Test
    fun testManifestJsonSerialization() {
        // Test that JSON serialization produces valid structure
        val manifestBytes = getManifestBytes("test-v3-manifest.json")
        val manifest = ManifestUtils.loadFromBytes(manifestBytes)

        val summary = ManifestTestSerializer.createManifestSummary(manifest)

        // Verify JSON structure
        assertTrue("Should have version", summary.has("version"))
        assertTrue("Should have appName", summary.has("appName"))
        assertTrue("Should have buildVersion", summary.has("buildVersion"))
        assertTrue("Should have chunkCount", summary.has("chunkCount"))
        assertTrue("Should have fileCount", summary.has("fileCount"))
        assertTrue("Should have downloadSize", summary.has("downloadSize"))
        assertTrue("Should have installedSize", summary.has("installedSize"))
        assertTrue("Should have sampleFiles", summary.has("sampleFiles"))
        assertTrue("Should have sampleChunks", summary.has("sampleChunks"))

        Timber.i("✅ JSON serialization validation passed!")
    }

    private fun compareField(
        actualObj: JSONObject,
        expectedObj: JSONObject,
        field: String,
        differences: MutableList<String>,
        context: String = ""
    ) {
        try {
            if (!actualObj.has(field)) {
                differences.add("${if (context.isNotEmpty()) "$context: " else ""}Missing field '$field' in actual output")
                return
            }
            if (!expectedObj.has(field)) {
                differences.add("${if (context.isNotEmpty()) "$context: " else ""}Missing field '$field' in expected output")
                return
            }

            val actualValue = actualObj.get(field)
            val expectedValue = expectedObj.get(field)

            // Normalize values for comparison (handle boolean vs int, long vs string)
            val normalizedActual = normalizeValue(actualValue)
            val normalizedExpected = normalizeValue(expectedValue)

            if (normalizedActual != normalizedExpected) {
                differences.add("${if (context.isNotEmpty()) "$context: " else ""}Field '$field': Actual='$normalizedActual', Expected='$normalizedExpected'")
            }
        } catch (e: Exception) {
            differences.add("${if (context.isNotEmpty()) "$context: " else ""}Field '$field': Error comparing - ${e.message}")
        }
    }

    private fun normalizeValue(value: Any): String {
        return when (value) {
            is Boolean -> if (value) "1" else "0"
            is Int, is Long -> value.toString()
            is String -> value
            else -> value.toString()
        }
    }
}
