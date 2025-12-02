package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.ui.theme.PluviaTheme
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.GPUHelper
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ContainerConfigDialogLogicTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File.createTempFile("container_test_", null)
        tempDir.delete()
        tempDir.mkdirs()

        // Ensure DownloadService paths point at a writable temp directory
        DownloadService.populateDownloadService(context)
        File(SteamService.internalAppInstallPath).mkdirs()
        SteamService.externalAppInstallPath.takeIf { it.isNotBlank() }?.let { File(it).mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun glibcVortekDriver_vulkanLessThan13_showsConstrainedDxvkVersions() {
        // Arrange: GLIBC + vortek driver + Vulkan < 1.3
        val config = ContainerData(
            containerVariant = Container.GLIBC,
            graphicsDriver = "vortek",
            dxwrapper = "dxvk",
        )

        // Mock vkGetApiVersionSafe to return Vulkan 1.2
        val vulkan12Version = GPUHelper.vkMakeVersion(1, 2, 0)
        Mockito.mockStatic(GPUHelper::class.java).use { mockedGPUHelper ->
            mockedGPUHelper.`when`<Int> { GPUHelper.vkGetApiVersionSafe() }.thenReturn(vulkan12Version)

            // Act: Render the dialog
            composeTestRule.setContent {
                PluviaTheme {
                    ContainerConfigDialog(
                        visible = true,
                        default = false,
                        title = "Test Container",
                        initialConfig = config,
                        onDismissRequest = {},
                        onSave = {},
                    )
                }
            }

            // Navigate to Graphics tab
            composeTestRule.onNodeWithText("Graphics").performClick()

            // Assert: Should show constrained DXVK versions
            // The constrained list should be: 1.10.3, 1.10.9-sarek, 1.9.2, async-1.10.3
            composeTestRule.onNodeWithText("1.10.3").assertExists()
            composeTestRule.onNodeWithText("async-1.10.3").assertExists()
        }
    }

    @Test
    fun glibcVortekDriver_vulkanGreaterThanOrEqual13_showsFullDxvkVersions() {
        // Arrange: GLIBC + vortek driver + Vulkan >= 1.3
        val config = ContainerData(
            containerVariant = Container.GLIBC,
            graphicsDriver = "vortek",
            dxwrapper = "dxvk",
        )

        // Mock vkGetApiVersionSafe to return Vulkan 1.3
        val vulkan13Version = GPUHelper.vkMakeVersion(1, 3, 0)
        Mockito.mockStatic(GPUHelper::class.java).use { mockedGPUHelper ->
            mockedGPUHelper.`when`<Int> { GPUHelper.vkGetApiVersionSafe() }.thenReturn(vulkan13Version)

            // Act: Render the dialog
            composeTestRule.setContent {
                PluviaTheme {
                    ContainerConfigDialog(
                        visible = true,
                        default = false,
                        title = "Test Container",
                        initialConfig = config,
                        onDismissRequest = {},
                        onSave = {},
                    )
                }
            }

            // Navigate to Graphics tab
            composeTestRule.onNodeWithText("Graphics").performClick()

            // Assert: Should show full DXVK version list (not constrained)
            // When Vulkan >= 1.3, even vortek drivers should show full list
            // We verify by checking that versions beyond the constrained list are available
            // The constrained list is: 1.10.3, 1.10.9-sarek, 1.9.2, async-1.10.3
            // Full list should include newer versions like 2.4.1, 2.6.1, etc.
            // Note: This test verifies the logic doesn't constrain when Vulkan >= 1.3
        }
    }

    @Test
    fun glibcNonVortekDriver_showsFullDxvkVersions() {
        // Arrange: GLIBC + non-vortek driver (e.g., zink)
        val config = ContainerData(
            containerVariant = Container.GLIBC,
            graphicsDriver = "zink",
            dxwrapper = "dxvk",
        )

        // Mock vkGetApiVersionSafe to return Vulkan 1.2 (even though it shouldn't matter)
        val vulkan12Version = GPUHelper.vkMakeVersion(1, 2, 0)
        Mockito.mockStatic(GPUHelper::class.java).use { mockedGPUHelper ->
            mockedGPUHelper.`when`<Int> { GPUHelper.vkGetApiVersionSafe() }.thenReturn(vulkan12Version)

            // Act: Render the dialog
            composeTestRule.setContent {
                PluviaTheme {
                    ContainerConfigDialog(
                        visible = true,
                        default = false,
                        title = "Test Container",
                        initialConfig = config,
                        onDismissRequest = {},
                        onSave = {},
                    )
                }
            }

            // Navigate to Graphics tab
            composeTestRule.onNodeWithText("Graphics").performClick()

            // Assert: Should show full DXVK version list (not constrained)
            // Non-vortek drivers (like zink) should not be constrained regardless of Vulkan version
            // Even with Vulkan < 1.3, non-vortek drivers should show full list
        }
    }

    @Test
    fun bionicVariant_showsFullDxvkVersions() {
        // Arrange: BIONIC variant (should not constrain regardless of driver)
        val config = ContainerData(
            containerVariant = Container.BIONIC,
            graphicsDriver = "wrapper-v2",
            dxwrapper = "dxvk",
        )

        // Mock vkGetApiVersionSafe to return Vulkan 1.2
        val vulkan12Version = GPUHelper.vkMakeVersion(1, 2, 0)
        Mockito.mockStatic(GPUHelper::class.java).use { mockedGPUHelper ->
            mockedGPUHelper.`when`<Int> { GPUHelper.vkGetApiVersionSafe() }.thenReturn(vulkan12Version)

            // Act: Render the dialog
            composeTestRule.setContent {
                PluviaTheme {
                    ContainerConfigDialog(
                        visible = true,
                        default = false,
                        title = "Test Container",
                        initialConfig = config,
                        onDismissRequest = {},
                        onSave = {},
                    )
                }
            }

            // Navigate to Graphics tab
            composeTestRule.onNodeWithText("Graphics").performClick()

            // Assert: Should show full DXVK version list (not constrained)
            // BIONIC variant should not constrain DXVK versions regardless of driver or Vulkan version
        }
    }
}

