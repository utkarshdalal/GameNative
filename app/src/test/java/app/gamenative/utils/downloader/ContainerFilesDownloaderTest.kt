package app.gamenative.utils.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import com.winlator.container.ContainerManager
import com.winlator.core.OnExtractFileListener
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber
import java.io.File

/**
 * Test suite for ContainerFilesDownloader functionality.
 * Tests downloading, caching, and fallback to bundled assets for container pattern files.
 */
@RunWith(RobolectricTestRunner::class)
class ContainerFilesDownloaderTest {
    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
        cacheDir = File(context.filesDir, ContainerFilesDownloader.CONTAINER_FILES_CACHE_DIR)

        // Clean up any existing cache
        ContainerFilesDownloader.clearCache(context)

        Timber.plant(Timber.DebugTree())
    }

    @After
    fun tearDown() {
        // Clean up after tests
        ContainerFilesDownloader.clearCache(context)
    }

    @Test
    fun testManifestContainsContainerFiles() {
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        assertNotNull("${ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE} should exist in assets", manifestJson)
        assertTrue("Manifest should not be empty", manifestJson.isNotEmpty())

        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

        assertTrue("Container files list should not be empty", manifest.components.isNotEmpty())

        println("Found ${manifest.components.size} container file components in manifest:")
        manifest.components.forEach { component ->
            println("  - ${component.id}: ${component.url}")
        }
    }

    @Test
    fun testExtrasComponentExists() {
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

        val component = manifest.components.find { it.id == "extras" }
        assertNotNull("extras component should exist in manifest", component)
        assertTrue("extras should have valid URL", component!!.url.isNotEmpty())
        assertTrue("extras URL should start with https://", component.url.startsWith("https://"))
        assertEquals("extras name should be extras.tzst", "extras.tzst", component.name)
    }

    @Test
    fun testContainerPatternCommonExists() {
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

        val component = manifest.components.find { it.id == "container_pattern_common" }
        assertNotNull("container_pattern_common should exist in manifest", component)
        assertEquals(
            "Name should invalidate the previous cache",
            "container_pattern_common_20260821.tzst",
            component!!.name,
        )
        assertEquals(
            "URL should reference the versioned archive",
            "https://downloads.gamenative.app/container_files/container_pattern_common_20260821.tzst",
            component.url,
        )
    }

    @Test
    fun testCacheFileUsesManifestFilename() {
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

        val component = manifest.components.first { it.id == "container_pattern_common" }
        val cacheFile = ContainerFilesDownloader.getCacheFile(cacheDir, component)
        assertEquals(component.name, cacheFile.name)
        assertFalse(
            "The previous ID-based cache path must not be reused",
            cacheFile.name == "${component.id}.tzst",
        )
    }

    @Test
    fun testReusableCacheRequiresNonEmptyRegularFile() {
        cacheDir.mkdirs()
        val missing = File(cacheDir, "missing.tzst")
        val empty = File(cacheDir, "empty.tzst").apply { createNewFile() }
        val directory = File(cacheDir, "directory.tzst").apply { mkdirs() }
        val valid = File(cacheDir, "valid.tzst").apply { writeText("archive") }

        assertFalse(ContainerFilesDownloader.isReusableCacheFile(missing))
        assertFalse(ContainerFilesDownloader.isReusableCacheFile(empty))
        assertFalse(ContainerFilesDownloader.isReusableCacheFile(directory))
        assertTrue(ContainerFilesDownloader.isReusableCacheFile(valid))
    }

    @Test
    fun testObsoleteArchiveCleanupKeepsCurrentAndUnrelatedFiles() {
        cacheDir.mkdirs()
        val currentCommon = File(cacheDir, "container_pattern_common_20260821.tzst").apply {
            writeText("current common")
        }
        val currentExtras = File(cacheDir, "extras.tzst").apply { writeText("current extras") }
        val oldCommon = File(cacheDir, "container_pattern_common.tzst").apply { writeText("old") }
        val olderDatedCommon = File(cacheDir, "container_pattern_common_20260701.tzst").apply {
            writeText("older")
        }
        val unrelatedFile = File(cacheDir, "download.version").apply { writeText("metadata") }
        val unrelatedDirectory = File(cacheDir, "directory.tzst").apply { mkdirs() }

        ContainerFilesDownloader.removeObsoleteCachedArchives(
            cacheDir,
            setOf(currentCommon.name, currentExtras.name),
        )

        assertTrue(currentCommon.isFile)
        assertTrue(currentExtras.isFile)
        assertFalse(oldCommon.exists())
        assertFalse(olderDatedCommon.exists())
        assertTrue(unrelatedFile.isFile)
        assertTrue(unrelatedDirectory.isDirectory)
    }

    @Test
    fun testWfmOnlyExtractionCreatesMissingParentDirectories() {
        assumeFalse("Bundled archive is only available in the legacy variant", BuildConfig.MODERN_ANDROID)

        val destination = File(context.cacheDir, "wfm-only-extraction")
        destination.deleteRecursively()
        try {
            assertTrue(ContainerManager(context).extractContainerPatternCommonWfm(destination, null))
            val wfm = File(destination, "home/xuser/.wine/drive_c/windows/wfm.exe")
            assertTrue("WFM should be extracted into a newly created prefix", wfm.isFile)
            assertTrue("Extracted WFM should not be empty", wfm.length() > 0)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun testWfmOnlyExtractionFailsWhenWfmIsNotSelected() {
        assumeFalse("Bundled archive is only available in the legacy variant", BuildConfig.MODERN_ANDROID)

        val destination = File(context.cacheDir, "wfm-rejected-extraction")
        destination.deleteRecursively()
        try {
            val rejectWfm = OnExtractFileListener { _, _ -> null }
            assertFalse(
                ContainerManager(context).extractContainerPatternCommonWfm(destination, rejectWfm),
            )
            val wfm = File(destination, "home/xuser/.wine/drive_c/windows/wfm.exe")
            assertFalse("Rejected WFM should not be reported as extracted", wfm.exists())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun testContainerPatternGamenativeExists() {
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

        val component = manifest.components.find { it.id == "container_pattern_gamenative" }
        assertNotNull("container_pattern_gamenative should exist in manifest", component)
        assertEquals("Name should match", "container_pattern_gamenative.tzst", component!!.name)
    }

    @Test
    fun testProtonContainerPatternsExist() {
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

        val protonPatterns = listOf(
            "proton-9.0-x86_64_container_pattern",
            "proton-9.0-arm64ec_container_pattern"
        )

        protonPatterns.forEach { componentId ->
            val component = manifest.components.find { it.id == componentId }
            assertNotNull("Proton pattern $componentId should exist in manifest", component)
            assertEquals("Name should match", "$componentId.tzst", component!!.name)
        }
    }

    @Test
    fun testContainerFileUrlFormat() {
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

        manifest.components.forEach { component ->
            assertTrue(
                "Component ${component.id} URL should contain 'container_files/'",
                component.url.contains("container_files/")
            )
            assertTrue(
                "Component ${component.id} URL should end with .tzst",
                component.url.endsWith(".tzst")
            )
        }
    }

    @Test
    fun testCacheSizeCalculation() {
        assertEquals("Initial cache size should be 0", 0L, ContainerFilesDownloader.getCacheSize(context))

        // Create a mock cached file
        cacheDir.mkdirs()
        val testFile = File(cacheDir, "extras.tzst")
        testFile.writeText("test content with some data to have a size")

        val cacheSize = ContainerFilesDownloader.getCacheSize(context)
        assertTrue("Cache size should be greater than 0", cacheSize > 0)

        // Clean up
        testFile.delete()
    }

    @Test
    fun testClearCache() {
        // Create some mock cached files
        cacheDir.mkdirs()
        val testFile1 = File(cacheDir, "extras.tzst")
        val testFile2 = File(cacheDir, "container_pattern_common_20260821.tzst")
        testFile1.writeText("test content 1")
        testFile2.writeText("test content 2")

        assertTrue("Cache should have files", cacheDir.listFiles()?.isNotEmpty() == true)

        ContainerFilesDownloader.clearCache(context)

        val remainingFiles = cacheDir.listFiles() ?: emptyArray()
        assertEquals("Cache should be empty after clearing", 0, remainingFiles.size)
    }

    @Test
    fun testDownloadContainerFileFromServer() = runBlocking {
        // This test will attempt to download a component
        // Skip if network is not available
        try {
            val componentId = "container_pattern_common"

            // Ensure cache is clean
            ContainerFilesDownloader.clearCache(context)

            var progressCalled = false
            val downloadedFile = ContainerFilesDownloader.ensureContainerFileAvailable(
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
                assertTrue("Downloaded file should be tzst", downloadedFile.name.endsWith(".tzst"))

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
        val componentId = "extras"

        // Create a mock cached file
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, "$componentId.tzst")
        cachedFile.writeText("mock cached content")

        val retrievedFile = ContainerFilesDownloader.ensureContainerFileAvailable(
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
            ContainerFilesDownloader.ensureContainerFileAvailable(
                context,
                "nonexistent_pattern_xyz"
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
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

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
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

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
        val containerFilesJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val containerFilesManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(containerFilesJson)

        val graphicsDriverJson = context.assets.open(GraphicsDriverDownloader.GRAPHICS_DRIVER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val graphicsDriverManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<GraphicsDriverDownloader.GraphicsDriverManifest>(graphicsDriverJson)

        val dxwrapperJson = context.assets.open(DXWrapperDownloader.DXWRAPPER_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val dxwrapperManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<DXWrapperDownloader.DXWrapperManifest>(dxwrapperJson)

        val wincomponentJson = context.assets.open(WinComponentDownloader.WINCOMPONENTS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val wincomponentManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<WinComponentDownloader.WinComponentManifest>(wincomponentJson)

        val coreDriverJson = context.assets.open(CoreDriverDownloader.CORE_DRIVERS_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val coreDriverManifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<CoreDriverDownloader.CoreDriverManifest>(coreDriverJson)

        val containerFilesIds = containerFilesManifest.components.map { it.id }.toSet()
        val graphicsDriverIds = graphicsDriverManifest.components.map { it.id }.toSet()
        val dxwrapperIds = dxwrapperManifest.components.map { it.id }.toSet()
        val wincomponentIds = wincomponentManifest.components.map { it.id }.toSet()
        val coreDriverIds = coreDriverManifest.components.map { it.id }.toSet()

        assertTrue(
            "Container files and Graphics driver manifests should not have overlapping IDs",
            containerFilesIds.intersect(graphicsDriverIds).isEmpty()
        )
        assertTrue(
            "Container files and DXWrapper manifests should not have overlapping IDs",
            containerFilesIds.intersect(dxwrapperIds).isEmpty()
        )
        assertTrue(
            "Container files and WinComponent manifests should not have overlapping IDs",
            containerFilesIds.intersect(wincomponentIds).isEmpty()
        )
        assertTrue(
            "Container files and Core driver manifests should not have overlapping IDs",
            containerFilesIds.intersect(coreDriverIds).isEmpty()
        )
    }

    @Test
    fun testAllComponentsAreTzstFiles() {
        val manifestJson = context.assets.open(ContainerFilesDownloader.CONTAINER_FILES_MANIFEST_FILE).bufferedReader().use { it.readText() }
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<ContainerFilesDownloader.ContainerFilesManifest>(manifestJson)

        manifest.components.forEach { component ->
            assertTrue(
                "Component ${component.id} should be a tzst file",
                component.name.endsWith(".tzst")
            )
        }
    }

    @Test
    fun testBlockingWrapperFunction() {
        // Test the blocking wrapper function used by Java code (e.g., ImageFsInstaller)
        val componentId = "extras"

        // Create a mock cached file
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, "$componentId.tzst")
        cachedFile.writeText("mock cached content for blocking test")

        // Call the blocking wrapper (not in a coroutine context)
        val retrievedFile = ensureContainerFileAvailableBlocking(
            context,
            componentId,
            object : ProgressCallback {
                override fun onProgress(progress: Float) {
                    // Progress should not be called for cached files
                    throw AssertionError("Progress callback should not be called for cached files")
                }
            }
        )

        if (retrievedFile != null) {
            assertEquals("Should return the cached file", cachedFile.absolutePath, retrievedFile.absolutePath)
            assertTrue("Cached file should exist", retrievedFile.exists())
            println("Blocking wrapper successfully returned cached file: ${retrievedFile.absolutePath}")
        } else {
            println("Blocking wrapper returned null (legacy variant)")
        }
    }

    @Test
    fun testBlockingWrapperWithDownload() {
        // Test blocking wrapper with actual download attempt
        try {
            val componentId = "container_pattern_common"

            // Ensure cache is clean
            ContainerFilesDownloader.clearCache(context)

            var progressCalled = false
            val downloadedFile = ensureContainerFileAvailableBlocking(
                context,
                componentId,
                object : ProgressCallback {
                    override fun onProgress(progress: Float) {
                        progressCalled = true
                        println("Blocking download progress: ${(progress * 100).toInt()}%")
                    }
                }
            )

            if (downloadedFile != null) {
                assertTrue("Downloaded file should exist", downloadedFile.exists())
                assertTrue("Downloaded file should have content", downloadedFile.length() > 0)
                println("Blocking wrapper successfully downloaded: ${downloadedFile.length()} bytes")
            } else {
                println("Blocking wrapper returned null (legacy variant)")
            }
        } catch (e: Exception) {
            println("Network test skipped or failed: ${e.message}")
            // Don't fail the test if network is unavailable
        }
    }

    @Test
    fun testBlockingWrapperWithNullCallback() {
        // Test that blocking wrapper works with null progress callback (Java interop)
        val componentId = "extras"

        // Create a mock cached file
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, "$componentId.tzst")
        cachedFile.writeText("mock cached content for null callback test")

        // Call the blocking wrapper with null callback (simulating Java code)
        val retrievedFile = ensureContainerFileAvailableBlocking(
            context,
            componentId,
            null  // No progress callback
        )

        if (retrievedFile != null) {
            assertEquals("Should return the cached file", cachedFile.absolutePath, retrievedFile.absolutePath)
            assertTrue("Cached file should exist", retrievedFile.exists())
            println("Blocking wrapper with null callback successfully returned: ${retrievedFile.absolutePath}")
        } else {
            println("Blocking wrapper returned null (legacy variant)")
        }
    }
}
