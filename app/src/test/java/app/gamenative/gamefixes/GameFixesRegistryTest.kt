package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GOGGame
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class GameFixesRegistryTest {
    private lateinit var context: Context
    private lateinit var container: Container

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
        mockkObject(GOGService.Companion)
        mockkObject(CustomGameScanner)
    }

    @After
    fun tearDown() {
        GameFixesRegistry.setFixesProviderForTests(null)
        unmockkAll()
    }

    @Test
    fun applyFor_usesInjectedFixesProviderForTests() {
        val installDir = createTempDirectory(prefix = "registry-gog-injected-fix").toFile()
        every { GOGService.getGOGGameOf("999") } returns GOGGame(
            id = "999",
            title = "Injected",
            isInstalled = true,
            installPath = installDir.absolutePath,
        )
        val fakeFix = mockk<GameFix>()
        every {
            fakeFix.apply(
                context,
                "999",
                installDir.absolutePath,
                "A:\\",
                container,
            )
        } returns true
        GameFixesRegistry.setFixesProviderForTests {
            mapOf((app.gamenative.data.GameSource.GOG to "999") to fakeFix)
        }

        GameFixesRegistry.applyFor(context, "GOG_999", container)

        verify(exactly = 1) {
            fakeFix.apply(
                context,
                "999",
                installDir.absolutePath,
                "A:\\",
                container,
            )
        }
    }

    @Test
    fun applyFor_appliesKnownGogLaunchArgFix_whenInstalledGamePathExists() {
        val installDir = createTempDirectory(prefix = "registry-gog-fix").toFile()
        every { GOGService.getGOGGameOf("1129934535") } returns GOGGame(
            id = "1129934535",
            title = "Mars: War Logs",
            isInstalled = true,
            installPath = installDir.absolutePath,
        )
        every { container.execArgs } returns ""
        every { container.execArgs = any() } just runs
        every { container.saveData() } just runs

        GameFixesRegistry.applyFor(context, "GOG_1129934535", container)

        verify(exactly = 1) { container.execArgs = "-lang=eng" }
        verify(exactly = 1) { container.saveData() }
    }

    @Test
    fun applyFor_doesNothing_whenGogGameIsNotInstalled() {
        every { GOGService.getGOGGameOf("1129934535") } returns GOGGame(
            id = "1129934535",
            title = "Mars: War Logs",
            isInstalled = false,
            installPath = "",
        )
        every { container.execArgs } returns "-novid"

        GameFixesRegistry.applyFor(context, "GOG_1129934535", container)

        verify(exactly = 0) { container.execArgs = "-lang=eng" }
        verify(exactly = 0) { container.saveData() }
        assertEquals("-novid", container.execArgs)
    }

    @Test
    fun applyFor_appliesWowForeverDxvkFix_whenCustomGameExeMatches() {
        val installDir = createTempDirectory(prefix = "registry-wow-forever").toFile()
        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_123") } returns installDir.absolutePath
        every { CustomGameScanner.getLaunchExecutable(container) } returns "_retail_\\WowB-ARM64.exe"
        every { container.drives } returns "D:/sdcard/Download/A:${installDir.absolutePath}"
        every { container.containerVariant } returns Container.BIONIC
        every { container.dxWrapper } returns "dxvk"
        every { container.dxWrapperConfig } returns "version=2.4.1,framerate=0,maxDeviceMemory=0"
        every { container.dxWrapperConfig = any() } just runs
        every { container.saveData() } just runs

        GameFixesRegistry.applyFor(context, "CUSTOM_GAME_123", container)

        verify(exactly = 1) {
            container.dxWrapperConfig = "version=$WOW_FOREVER_DXVK_VERSION,framerate=0,maxDeviceMemory=0"
        }
        verify(exactly = 1) { container.saveData() }
    }

    @Test
    fun applyFor_doesNothing_whenCustomGameExeIsNotWowForever() {
        val installDir = createTempDirectory(prefix = "registry-custom-other").toFile()
        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_123") } returns installDir.absolutePath
        every { CustomGameScanner.getLaunchExecutable(container) } returns "Game.exe"

        GameFixesRegistry.applyFor(context, "CUSTOM_GAME_123", container)

        verify(exactly = 0) { container.dxWrapperConfig = any() }
        verify(exactly = 0) { container.saveData() }
    }
}
