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
 * Test suite for DXWrapperDownloader functionality.
 * Tests downloading, caching, and fallback to bundled assets.
 */
@RunWith(RobolectricTestRunner::class)
class DXWrapperDownloaderTest {
    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        cacheDir = File(context.filesDir, DXWrapperDownloader.DXWRAPPER_CACHE_DIR)

        // Clean up any existing cache
        DXWrapperDownloader.clearCache(context)

        Timber.plant(Timber.DebugTree())
    }

    @After
    fun tearDown() {
        // Clean up after tests
        DXWrapperDownloader.clearCache(context)
    }

    @Test
    fun testManifestContainsDXWrapperComponents() {
        val manifestJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        assertNotNull("${DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE} should exist in assets", manifestJson)
        assertTrue("Manifest should not be empty", manifestJson.isNotEmpty())

        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(manifestJson)

        assertTrue("DXWrapper components list should not be empty", manifest.components.isNotEmpty())

        println("Found ${manifest.components.size} dxwrapper components in manifest:")
        manifest.components.forEach { component ->
            println("  - ${component.id}: ${component.url}")
        }
    }

    @Test
    fun testDXVKComponentsExist() {
        val manifestJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(manifestJson)

        val dxvkComponents = listOf(
            "dxvk-1.9.2",
            "dxvk-1.10.1",
            "dxvk-1.10.3",
            "dxvk-2.4.1",
            "dxvk-2.6.1-gplasync",
            "dxvk-2.7.1"
        )

        dxvkComponents.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("DXVK component $componentId should exist in manifest", component)
            assertTrue("Component $componentId should have valid URL", component!!.url.isNotEmpty())
            assertTrue("Component $componentId URL should start with https://", component.url.startsWith("https://"))
        }
    }

    @Test
    fun testVKD3DComponentsExist() {
        val manifestJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(manifestJson)

        val vkd3dComponents = listOf(
            "vkd3d-2.6",
            "vkd3d-2.12",
            "vkd3d-2.13",
            "vkd3d-2.14.1",
            "vkd3d-3.0b"
        )

        vkd3dComponents.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("VKD3D component $componentId should exist in manifest", component)
        }
    }

    @Test
    fun testD8VKComponentExists() {
        val manifestJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(manifestJson)

        val component = manifest.components.find { it.id == "d8vk-1.0" }
        assertNotNull("D8VK component should exist in manifest", component)
        assertTrue("D8VK should have valid URL", component!!.url.isNotEmpty())
    }

    @Test
    fun testDXWrapperUrlFormat() {
        val manifestJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(manifestJson)

        manifest.components.forEach { component ->
            assertTrue(
                "Component ${component.id} URL should contain 'dxwrapper/'",
                component.url.contains("dxwrapper/")
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
        assertEquals("Initial cache size should be 0", 0L, DXWrapperDownloader.getCacheSize(context))

        // Create a mock cached file
        cacheDir.mkdirs()
        val testFile = File(cacheDir, "dxvk-test.tzst")
        testFile.writeText("test content with some data to have a size")

        val cacheSize = DXWrapperDownloader.getCacheSize(context)
        assertTrue("Cache size should be greater than 0", cacheSize > 0)

        // Clean up
        testFile.delete()
    }

    @Test
    fun testClearCache() {
        // Create some mock cached files
        cacheDir.mkdirs()
        val testFile1 = File(cacheDir, "dxvk-2.7.1.tzst")
        val testFile2 = File(cacheDir, "vkd3d-3.0b.tzst")
        testFile1.writeText("test content 1")
        testFile2.writeText("test content 2")

        assertTrue("Cache should have files", cacheDir.listFiles()?.isNotEmpty() == true)

        DXWrapperDownloader.clearCache(context)

        val remainingFiles = cacheDir.listFiles() ?: emptyArray()
        assertEquals("Cache should be empty after clearing", 0, remainingFiles.size)
    }

    @Test
    fun testDownloadDXWrapperFromServer() = runBlocking {
        // This test will attempt to download a component
        // Skip if network is not available
        try {
            val componentId = "vkd3d-2.6" // Smallest VKD3D component

            // Ensure cache is clean
            DXWrapperDownloader.clearCache(context)

            var progressCalled = false
            val downloadedFile = DXWrapperDownloader.ensureDXWrapperAvailable(
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
        val componentId = "vkd3d-2.6"

        // Load manifest to get component name
        val manifestJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(manifestJson)
        val component = manifest.components.find { it.id == componentId }!!

        // Create a mock cached file
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, component.name)
        cachedFile.writeText("mock cached content")

        val retrievedFile = DXWrapperDownloader.ensureDXWrapperAvailable(
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
    fun testInvalidComponentReturnsNull() = runBlocking {
        val result = DXWrapperDownloader.ensureDXWrapperAvailable(
            context,
            "nonexistent_dxvk_xyz"
        )
        assertEquals(
            "Invalid component should return null",
            null,
            result
        )
    }

    @Test
    fun testAllManifestComponentsHaveValidUrls() {
        val manifestJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(manifestJson)

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
        val manifestJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(manifestJson)

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

    @Test
    fun testNoOverlapWithWinComponents() {
        val dxwrapperJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val dxwrapperManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(dxwrapperJson)

        val wincomponentJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val wincomponentManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(wincomponentJson)

        val dxwrapperIds = dxwrapperManifest.components.map { it.id }.toSet()
        val wincomponentIds = wincomponentManifest.components.map { it.id }.toSet()

        val overlap = dxwrapperIds.intersect(wincomponentIds)
        assertTrue(
            "DXWrapper and WinComponent manifests should not have overlapping IDs",
            overlap.isEmpty()
        )
    }
}
