package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GOGGame
import app.gamenative.service.gog.GOGService
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
}
