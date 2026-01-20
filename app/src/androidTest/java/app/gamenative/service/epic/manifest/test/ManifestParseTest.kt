package app.gamenative.service.epic.manifest.test

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.service.epic.manifest.ManifestUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

/**
 * Basic manifest parsing test to verify Kotlin manifest parsing works correctly
 */
@RunWith(AndroidJUnit4::class)
class ManifestParseTest {

    private fun getContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun getManifestBytes(assetName: String): ByteArray {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { inputStream ->
            inputStream.readBytes()
        }
    }

    @Test
    fun testBasicManifestParsing() {
        // Test that we can parse a basic manifest without errors
        val testManifests = listOf(
            "test-manifest.json",
            "test-v3-manifest.json",
            "binary-control-file.manifest"
        )

        testManifests.forEach { manifestAsset ->
            try {
                Timber.i("Parsing manifest: $manifestAsset")

                val manifestBytes = getManifestBytes(manifestAsset)
                val manifest = ManifestUtils.loadFromBytes(manifestBytes)

                // Create summary to verify structure
                val summary = ManifestTestSerializer.createManifestSummary(manifest)

                Timber.i("Manifest parsed successfully:")
                Timber.i(summary.toString(2))

                // Basic assertions
                assertNotNull("Manifest should not be null", manifest)
                assertTrue("Manifest version should be positive", manifest.version > 0)

            } catch (e: Exception) {
                fail("Failed to parse manifest $manifestAsset: ${e.message}")
            }
        }
    }
}
