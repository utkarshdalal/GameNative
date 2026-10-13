package app.gamenative.gamefixes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class VulkanExtensionBlacklistFixTest {
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("vulkan-ext-blacklist-fix-tests").toFile()
        baseDir.deleteOnExit()
    }

    @Test
    fun apply_setsBlacklistAndSavesContainer_whenBlacklistIsEmpty() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = createContainer("c1", "version=Turnip,blacklistedExtensions=,maxDeviceMemory=0")
        val configFile = container.configFile
        if (configFile.exists()) {
            configFile.delete()
        }

        val fix = VulkanExtensionBlacklistFix(
            extensions = listOf("VK_EXT_fragment_density_map"),
        )

        val result = fix.apply(
            context = context,
            gameId = "658920",
            installPath = "",
            installPathWindows = "",
            container = container,
        )

        val config = KeyValueSet(container.graphicsDriverConfig)
        assertTrue(result)
        assertEquals("VK_EXT_fragment_density_map", config.get("blacklistedExtensions"))
        assertEquals("Turnip", config.get("version"))
        assertEquals("0", config.get("maxDeviceMemory"))
        assertTrue(configFile.exists())
    }

    @Test
    fun apply_appendsToExistingBlacklist_whenOtherExtensionsAreBlacklisted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = createContainer("c2", "version=Turnip,blacklistedExtensions=VK_KHR_maintenance1,maxDeviceMemory=0")

        val fix = VulkanExtensionBlacklistFix(
            extensions = listOf("VK_EXT_fragment_density_map"),
        )

        val result = fix.apply(
            context = context,
            gameId = "658920",
            installPath = "",
            installPathWindows = "",
            container = container,
        )

        val config = KeyValueSet(container.graphicsDriverConfig)
        assertTrue(result)
        assertEquals("VK_KHR_maintenance1|VK_EXT_fragment_density_map", config.get("blacklistedExtensions"))
        assertEquals("0", config.get("maxDeviceMemory"))
    }

    @Test
    fun apply_doesNotDuplicateOrSave_whenExtensionAlreadyBlacklisted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val original = "version=Turnip,blacklistedExtensions=VK_KHR_maintenance1|VK_EXT_fragment_density_map,maxDeviceMemory=0"
        val container = createContainer("c3", original)
        val configFile = container.configFile
        if (configFile.exists()) {
            configFile.delete()
        }

        val fix = VulkanExtensionBlacklistFix(
            extensions = listOf("VK_EXT_fragment_density_map"),
        )

        val result = fix.apply(
            context = context,
            gameId = "658920",
            installPath = "",
            installPathWindows = "",
            container = container,
        )

        assertTrue(result)
        assertEquals(original, container.graphicsDriverConfig)
        assertTrue(!configFile.exists())
    }

    private fun createContainer(id: String, graphicsDriverConfig: String): Container {
        val rootDir = File(baseDir, id).apply { mkdirs() }
        return Container(id).apply {
            this.rootDir = rootDir
            this.graphicsDriverConfig = graphicsDriverConfig
        }
    }
}
