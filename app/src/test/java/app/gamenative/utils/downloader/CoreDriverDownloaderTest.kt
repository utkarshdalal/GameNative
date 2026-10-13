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
 * Test suite for CoreDriverDownloader functionality.
 * Tests downloading, caching, and fallback to bundled assets for Adreno and SD8Elite drivers.
 */
@RunWith(RobolectricTestRunner::class)
class CoreDriverDownloaderTest {
    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        cacheDir = File(context.filesDir, CoreDriverDownloader.CORE_DRIVERS_CACHE_DIR)

        // Clean up any existing cache
        CoreDriverDownloader.clearCache(context)

        Timber.plant(Timber.DebugTree())
    }

    @After
    fun tearDown() {
        // Clean up after tests
        CoreDriverDownloader.clearCache(context)
    }

    @Test
    fun testManifestContainsCoreDriverComponents() {
        val manifestJson = context.assets.open(CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        assertNotNull("${CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE} should exist in assets", manifestJson)
        assertTrue("Manifest should not be empty", manifestJson.isNotEmpty())

        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<CoreDriverDownloader.CoreDriverManifest>(manifestJson)

        assertTrue("Core driver components list should not be empty", manifest.components.isNotEmpty())

        println("Found ${manifest.components.size} core driver components in manifest:")
        manifest.components.forEach { component ->
            println("  - ${component.id}: ${component.url}")
        }
    }

    @Test
    fun testAdrenoDriversExist() {
        val manifestJson = context.assets.open(CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<CoreDriverDownloader.CoreDriverManifest>(manifestJson)

        val adrenoDrivers = listOf(
            "Adreno_805_adpkg",
            "Adreno_819.2_adpkg"
        )

        adrenoDrivers.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Adreno driver $componentId should exist in manifest", component)
            assertTrue("Component $componentId should have valid URL", component!!.url.isNotEmpty())
            assertTrue("Component $componentId URL should start with https://", component.url.startsWith("https://"))
            assertTrue("Component $componentId name should end with .zip", component.name.endsWith(".zip"))
        }
    }

    @Test
    fun testSD8EliteDriversExist() {
        val manifestJson = context.assets.open(CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<CoreDriverDownloader.CoreDriverManifest>(manifestJson)

        val sd8EliteDrivers = listOf(
            "SD8Elite_2-842.6",
            "SD8Elite_800.35",
            "SD8Elite_800.51"
        )

        sd8EliteDrivers.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("SD8Elite driver $componentId should exist in manifest", component)
            assertTrue("Component $componentId should have valid URL", component!!.url.isNotEmpty())
            assertTrue("Component $componentId name should end with .zip", component.name.endsWith(".zip"))
        }
    }

    @Test
    fun testCoreDriverUrlFormat() {
        val manifestJson = context.assets.open(CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<CoreDriverDownloader.CoreDriverManifest>(manifestJson)

        manifest.components.forEach { component ->
            assertTrue(
                "Component ${component.id} URL should contain 'core_drivers/'",
                component.url.contains("core_drivers/")
            )
            assertTrue(
                "Component ${component.id} URL should end with .zip",
                component.url.endsWith(".zip")
            )
            assertEquals(
                "Component ${component.id} name should match ID.zip",
                "${component.id}.zip",
                component.name
            )
        }
    }

    @Test
    fun testCacheSizeCalculation() {
        assertEquals("Initial cache size should be 0", 0L, CoreDriverDownloader.getCacheSize(context))

        // Create a mock cached file
        cacheDir.mkdirs()
        val testFile = File(cacheDir, "Adreno_test.zip")
        testFile.writeText("test content with some data to have a size")

        val cacheSize = CoreDriverDownloader.getCacheSize(context)
        assertTrue("Cache size should be greater than 0", cacheSize > 0)

        // Clean up
        testFile.delete()
    }

    @Test
    fun testClearCache() {
        // Create some mock cached files
        cacheDir.mkdirs()
        val testFile1 = File(cacheDir, "Adreno_805_adpkg.zip")
        val testFile2 = File(cacheDir, "SD8Elite_800.51.zip")
        testFile1.writeText("test content 1")
        testFile2.writeText("test content 2")

        assertTrue("Cache should have files", cacheDir.listFiles()?.isNotEmpty() == true)

        CoreDriverDownloader.clearCache(context)

        val remainingFiles = cacheDir.listFiles() ?: emptyArray()
        assertEquals("Cache should be empty after clearing", 0, remainingFiles.size)
    }

    @Test
    fun testDownloadCoreDriverFromServer() = runBlocking {
        // This test will attempt to download a component
        // Skip if network is not available
        try {
            val componentName = "Adreno_805_adpkg.zip"

            // Ensure cache is clean
            CoreDriverDownloader.clearCache(context)

            var progressCalled = false
            val downloadedFile = CoreDriverDownloader.ensureCoreDriverAvailable(
                context,
                componentName
            ) { progress ->
                progressCalled = true
                println("Download progress: ${(progress * 100).toInt()}%")
            }

            if (downloadedFile != null) {
                assertTrue("Downloaded file should exist", downloadedFile.exists())
                assertTrue("Downloaded file should have content", downloadedFile.length() > 0)
                assertTrue("Progress callback should be called", progressCalled)
                assertTrue("Downloaded file should be a zip", downloadedFile.name.endsWith(".zip"))

                println("Successfully downloaded $componentName: ${downloadedFile.length()} bytes")
            } else {
                println("Component $componentName is bundled in assets (legacy variant)")
            }
        } catch (e: Exception) {
            println("Network test skipped or failed: ${e.message}")
            // Don't fail the test if network is unavailable
        }
    }

    @Test
    fun testCachedComponentReuse() = runBlocking {
        val componentName = "Adreno_805_adpkg.zip"

        // Create a mock cached file
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, componentName)
        cachedFile.writeText("mock cached content")

        val retrievedFile = CoreDriverDownloader.ensureCoreDriverAvailable(
            context,
            componentName
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
            CoreDriverDownloader.ensureCoreDriverAvailable(
                context,
                "nonexistent_driver_xyz.zip"
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
        val manifestJson = context.assets.open(CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<CoreDriverDownloader.CoreDriverManifest>(manifestJson)

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
        val manifestJson = context.assets.open(CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<CoreDriverDownloader.CoreDriverManifest>(manifestJson)

        manifest.components.forEach { component ->
            // URL should contain the component name
            assertTrue(
                "Component ${component.id} URL should contain its name",
                component.url.contains(component.name)
            )

            // Name should match ID with .zip extension
            assertEquals(
                "Component ${component.id} name should match ID.zip",
                "${component.id}.zip",
                component.name
            )
        }
    }

    @Test
    fun testNoOverlapWithOtherDownloaders() {
        val coreDriverJson = context.assets.open(CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val coreDriverManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<CoreDriverDownloader.CoreDriverManifest>(coreDriverJson)

        val graphicsDriverJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val graphicsDriverManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(graphicsDriverJson)

        val dxwrapperJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val dxwrapperManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(dxwrapperJson)

        val wincomponentJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val wincomponentManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(wincomponentJson)

        val coreDriverIds = coreDriverManifest.components.map { it.id }.toSet()
        val graphicsDriverIds = graphicsDriverManifest.components.map { it.id }.toSet()
        val dxwrapperIds = dxwrapperManifest.components.map { it.id }.toSet()
        val wincomponentIds = wincomponentManifest.components.map { it.id }.toSet()

        val overlapWithGraphics = coreDriverIds.intersect(graphicsDriverIds)
        val overlapWithDxwrapper = coreDriverIds.intersect(dxwrapperIds)
        val overlapWithWincomponent = coreDriverIds.intersect(wincomponentIds)

        assertTrue(
            "Core driver and Graphics driver manifests should not have overlapping IDs",
            overlapWithGraphics.isEmpty()
        )
        assertTrue(
            "Core driver and DXWrapper manifests should not have overlapping IDs",
            overlapWithDxwrapper.isEmpty()
        )
        assertTrue(
            "Core driver and WinComponent manifests should not have overlapping IDs",
            overlapWithWincomponent.isEmpty()
        )
    }

    @Test
    fun testAllComponentsAreZipFiles() {
        val manifestJson = context.assets.open(CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<CoreDriverDownloader.CoreDriverManifest>(manifestJson)

        manifest.components.forEach { component ->
            assertTrue(
                "Component ${component.id} should be a zip file",
                component.name.endsWith(".zip")
            )
        }
    }
}
