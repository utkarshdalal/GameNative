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
 * Test suite for GraphicsDriverDownloader functionality.
 * Tests downloading, caching, and fallback to bundled assets.
 */
@RunWith(RobolectricTestRunner::class)
class GraphicsDriverDownloaderTest {
    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        cacheDir = File(context.filesDir, GraphicsDriverDownloader.GRAPHICS_DRIVER_CACHE_DIR)

        // Clean up any existing cache
        GraphicsDriverDownloader.clearCache(context)

        Timber.plant(Timber.DebugTree())
    }

    @After
    fun tearDown() {
        // Clean up after tests
        GraphicsDriverDownloader.clearCache(context)
    }

    @Test
    fun testManifestContainsGraphicsDriverComponents() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        assertNotNull("${GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE} should exist in assets", manifestJson)
        assertTrue("Manifest should not be empty", manifestJson.isNotEmpty())

        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        assertTrue("Graphics driver components list should not be empty", manifest.components.isNotEmpty())

        println("Found ${manifest.components.size} graphics driver components in manifest:")
        manifest.components.forEach { component ->
            println("  - ${component.id}: ${component.url}")
        }
    }

    @Test
    fun testTurnipComponentsExist() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        val turnipComponents = listOf(
            "turnip-24.1.0",
            "turnip-25.0.0",
            "turnip-25.1.0",
            "turnip-25.2.0",
            "turnip-25.3.0"
        )

        turnipComponents.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Turnip component $componentId should exist in manifest", component)
            assertTrue("Component $componentId should have valid URL", component!!.url.isNotEmpty())
            assertTrue("Component $componentId URL should start with https://", component.url.startsWith("https://"))
        }
    }

    @Test
    fun testVortekComponentsExist() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        val vortekComponents = listOf("vortek-2.0", "vortek-2.1")

        vortekComponents.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Vortek component $componentId should exist in manifest", component)
        }
    }

    @Test
    fun testZinkAndVirglComponentsExist() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        val components = listOf("zink-22.2.5", "virgl-23.1.9")

        components.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Component $componentId should exist in manifest", component)
        }
    }

    @Test
    fun testAdrenotoolsComponentsExist() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        val adrenotoolsComponents = listOf(
            "adrenotools-Turnip_Gen8_V25",
            "adrenotools-turnip25.1.0",
            "adrenotools-v762",
            "adrenotools-v805"
        )

        adrenotoolsComponents.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Adrenotools component $componentId should exist in manifest", component)
        }
    }

    @Test
    fun testWrapperComponentsExist() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        val wrapperComponents = listOf(
            "wrapper",
            "wrapper-v2",
            "wrapper-legacy",
            "wrapper-leegao"
        )

        wrapperComponents.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Wrapper component $componentId should exist in manifest", component)
        }
    }

    @Test
    fun testExtraComponentsExist() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        val extraComponents = listOf("extra_libs", "zink_dlls", "libvulkan_wrapper")

        extraComponents.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Extra component $componentId should exist in manifest", component)
        }
    }

    @Test
    fun testGraphicsDriverUrlFormat() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        manifest.components.forEach { component ->
            assertTrue(
                "Component ${component.id} URL should contain 'graphics_driver/'",
                component.url.contains("graphics_driver/")
            )
            assertTrue(
                "Component ${component.id} URL should end with .tzst or .tar.xz",
                component.url.endsWith(".tzst") || component.url.endsWith(".tar.xz")
            )
        }
    }

    @Test
    fun testCacheSizeCalculation() {
        assertEquals("Initial cache size should be 0", 0L, GraphicsDriverDownloader.getCacheSize(context))

        // Create a mock cached file
        cacheDir.mkdirs()
        val testFile = File(cacheDir, "turnip-test.tzst")
        testFile.writeText("test content with some data to have a size")

        val cacheSize = GraphicsDriverDownloader.getCacheSize(context)
        assertTrue("Cache size should be greater than 0", cacheSize > 0)

        // Clean up
        testFile.delete()
    }

    @Test
    fun testClearCache() {
        // Create some mock cached files
        cacheDir.mkdirs()
        val testFile1 = File(cacheDir, "turnip-25.3.0.tzst")
        val testFile2 = File(cacheDir, "vortek-2.1.tzst")
        testFile1.writeText("test content 1")
        testFile2.writeText("test content 2")

        assertTrue("Cache should have files", cacheDir.listFiles()?.isNotEmpty() == true)

        GraphicsDriverDownloader.clearCache(context)

        val remainingFiles = cacheDir.listFiles() ?: emptyArray()
        assertEquals("Cache should be empty after clearing", 0, remainingFiles.size)
    }

    @Test
    fun testDownloadGraphicsDriverFromServer() = runBlocking {
        // This test will attempt to download a component
        // Skip if network is not available
        try {
            val componentId = "vortek-2.1" // Smallest component

            // Ensure cache is clean
            GraphicsDriverDownloader.clearCache(context)

            var progressCalled = false
            val downloadedFile = GraphicsDriverDownloader.ensureGraphicsDriverAvailable(
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
        val componentId = "vortek-2.1"

        // Load manifest to get component name
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)
        val component = manifest.components.find { it.id == componentId }!!

        // Create a mock cached file
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, component.name)
        cachedFile.writeText("mock cached content")

        val retrievedFile = GraphicsDriverDownloader.ensureGraphicsDriverAvailable(
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
        val result = GraphicsDriverDownloader.ensureGraphicsDriverAvailable(
            context,
            "nonexistent_driver_xyz"
        )
        assertEquals(
            "Invalid component should return null",
            null,
            result
        )
    }

    @Test
    fun testAllManifestComponentsHaveValidUrls() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

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
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        manifest.components.forEach { component ->
            // URL should contain the component name
            assertTrue(
                "Component ${component.id} URL should contain its name",
                component.url.contains(component.name)
            )
        }
    }

    @Test
    fun testNoOverlapWithOtherDownloaders() {
        val graphicsDriverJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val graphicsDriverManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(graphicsDriverJson)

        val dxwrapperJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val dxwrapperManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(dxwrapperJson)

        val wincomponentJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val wincomponentManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(wincomponentJson)

        val graphicsDriverIds = graphicsDriverManifest.components.map { it.id }.toSet()
        val dxwrapperIds = dxwrapperManifest.components.map { it.id }.toSet()
        val wincomponentIds = wincomponentManifest.components.map { it.id }.toSet()

        val overlapWithDxwrapper = graphicsDriverIds.intersect(dxwrapperIds)
        val overlapWithWincomponent = graphicsDriverIds.intersect(wincomponentIds)

        assertTrue(
            "Graphics driver and DXWrapper manifests should not have overlapping IDs",
            overlapWithDxwrapper.isEmpty()
        )
        assertTrue(
            "Graphics driver and WinComponent manifests should not have overlapping IDs",
            overlapWithWincomponent.isEmpty()
        )
    }

    @Test
    fun testLibvulkanWrapperHasTarXzExtension() {
        val manifestJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(manifestJson)

        val libvulkanComponent = manifest.components.find { it.id == "libvulkan_wrapper" }
        assertNotNull("libvulkan_wrapper should exist", libvulkanComponent)
        assertTrue(
            "libvulkan_wrapper should have .tar.xz extension",
            libvulkanComponent!!.name.endsWith(".tar.xz")
        )
    }
}
