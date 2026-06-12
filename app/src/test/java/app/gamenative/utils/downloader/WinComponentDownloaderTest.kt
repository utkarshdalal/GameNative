package app.gamenative.utils.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber
import java.io.File

/**
 * Test suite for WinComponentDownloader functionality.
 * Tests downloading, caching, and fallback to bundled assets.
 */
@RunWith(RobolectricTestRunner::class)
class WinComponentDownloaderTest {
    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        cacheDir = File(context.filesDir, WinComponentDownloader.WINCOMPONENTS_CACHE_DIR)

        // Clean up any existing cache
        WinComponentDownloader.clearCache(context)

        Timber.plant(Timber.DebugTree())
    }

    @After
    fun tearDown() {
        // Clean up after tests
        WinComponentDownloader.clearCache(context)
    }

    @Test
    fun testManifestContainsWinComponents() {
        val manifestJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        assertNotNull("${WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE} should exist in assets", manifestJson)
        assertTrue("Manifest should not be empty", manifestJson.isNotEmpty())

        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(manifestJson)

        assertTrue("Wincomponents list should not be empty", manifest.components.isNotEmpty())

        println("Found ${manifest.components.size} wincomponents in manifest:")
        manifest.components.forEach { component ->
            println("  - ${component.id}: ${component.url}")
        }
    }

    @Test
    fun testRequiredWinComponentsExist() {
        val manifestJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(manifestJson)

        val requiredComponents = listOf(
            "direct3d",
            "directsound",
            "vcrun2010",
            "wmdecoder"
        )

        requiredComponents.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Required component $componentId should exist in manifest", component)
            assertTrue("Component $componentId should have valid URL", component!!.url.isNotEmpty())
            assertTrue("Component $componentId URL should start with https://", component.url.startsWith("https://"))
        }
    }

    @Test
    fun testOptionalWinComponentsExist() {
        val manifestJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(manifestJson)

        val optionalComponents = listOf(
            "directmusic",
            "directshow",
            "directplay",
            "opengl",
            "openal",
            "xaudio",
            "ddraw"
        )

        optionalComponents.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Optional component $componentId should exist in manifest", component)
        }
    }

    @Test
    fun testWinComponentUrlFormat() {
        val manifestJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(manifestJson)

        manifest.components.forEach { component ->
            assertTrue(
                "Component ${component.id} URL should contain 'wincomponents/'",
                component.url.contains("wincomponents/")
            )
            assertTrue(
                "Component ${component.id} URL should end with .tzst",
                component.url.endsWith(".tzst")
            )
            assertEquals(
                "Component ${component.id} name should match ID.tzst",
                "${component.id}.tzst",
                component.name
            )
        }
    }


    @Test
    fun testCacheSizeCalculation() {
        assertEquals("Initial cache size should be 0", 0L, WinComponentDownloader.getCacheSize(context))

        // Create a mock cached file
        cacheDir.mkdirs()
        val testFile = File(cacheDir, "test.tzst")
        testFile.writeText("test content with some data to have a size")

        val cacheSize = WinComponentDownloader.getCacheSize(context)
        assertTrue("Cache size should be greater than 0", cacheSize > 0)

        // Clean up
        testFile.delete()
    }

    @Test
    fun testClearCache() {
        // Create some mock cached files
        cacheDir.mkdirs()
        val testFile1 = File(cacheDir, "test1.tzst")
        val testFile2 = File(cacheDir, "test2.tzst")
        testFile1.writeText("test content 1")
        testFile2.writeText("test content 2")

        assertTrue("Cache should have files", cacheDir.listFiles()?.isNotEmpty() == true)

        WinComponentDownloader.clearCache(context)

        val remainingFiles = cacheDir.listFiles() ?: emptyArray()
        assertEquals("Cache should be empty after clearing", 0, remainingFiles.size)
    }

    @Test
    fun testDownloadWinComponentFromServer() = runBlocking {
        // This test will attempt to download a small component
        // Skip if network is not available
        try {
            val componentId = "ddraw" // Smallest component

            // Ensure cache is clean
            WinComponentDownloader.clearCache(context)

            var progressCalled = false
            val downloadedFile = WinComponentDownloader.ensureWinComponentAvailable(
                context,
                componentId
            ) { progress ->
                progressCalled = true
                println("Download progress: ${(progress * 100).toInt()}%")
            }

            if (downloadedFile != null) {
                assertTrue("Downloaded file should exist", downloadedFile.exists())
                assertTrue("Downloaded file should have content", downloadedFile.length() > 0)
                assertTrue("Progress callback should be called", progressCalled)

                println("Successfully downloaded $componentId: ${downloadedFile.length()} bytes")
            } else {
                println("Component $componentId is bundled in assets (legacy variant)")
            }
        } catch (e: Exception) {
            println("Network test skipped or failed: ${e.message}")
            // Don't fail the test if network is unavailable
        }
    }

    @Test
    fun testCachedComponentReuse() = runBlocking {
        val componentId = "ddraw"

        // Create a mock cached file
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, "$componentId.tzst")
        cachedFile.writeText("mock cached content")

        val retrievedFile = WinComponentDownloader.ensureWinComponentAvailable(
            context,
            componentId
        ) { progress ->
            // Progress should not be called for cached files
            throw AssertionError("Progress callback should not be called for cached files")
        }

        if (retrievedFile != null) {
            assertEquals("Should return the cached file", cachedFile.absolutePath, retrievedFile.absolutePath)
            assertTrue("Cached file should exist", retrievedFile.exists())
        }
    }

    @Test
    fun testInvalidComponentThrowsException() = runBlocking {
        try {
            WinComponentDownloader.ensureWinComponentAvailable(
                context,
                "nonexistent_component_xyz"
            )
            throw AssertionError("Should have thrown exception for invalid component")
        } catch (e: Exception) {
            assertTrue(
                "Exception message should mention component not found",
                e.message?.contains("not found") == true
            )
        }
    }

    @Test
    fun testAllManifestComponentsHaveValidUrls() {
        val manifestJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(manifestJson)

        val validDomains = listOf(
            "downloads.gamenative.app",
            "pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev"
        )

        manifest.components.forEach { component ->
            val hasValidDomain = validDomains.any { domain ->
                component.url.contains(domain)
            }
            assertTrue(
                "Component ${component.id} should use a valid download domain",
                hasValidDomain
            )
        }
    }

    @Test
    fun testComponentNamingConsistency() {
        val manifestJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(manifestJson)

        manifest.components.forEach { component ->
            // URL should contain the component ID
            assertTrue(
                "Component ${component.id} URL should contain its ID",
                component.url.contains(component.id)
            )

            // Name should match ID with .tzst extension
            assertEquals(
                "Component ${component.id} name should match ID.tzst",
                "${component.id}.tzst",
                component.name
            )
        }
    }
}
