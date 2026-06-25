package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.utils.launchdependencies.GogScriptInterpreterDependency
import app.gamenative.utils.launchdependencies.LaunchDependency
import app.gamenative.utils.launchdependencies.LaunchDependencyCallbacks
import com.winlator.container.Container
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchDependenciesTest {
    private lateinit var context: Context
    private lateinit var container: Container
    private lateinit var sut: LaunchDependencies

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
        sut = LaunchDependencies()
        mockkObject(GogScriptInterpreterDependency)
    }

    @After
    fun tearDown() {
        sut.setDependenciesProviderForTests(null)
        unmockkAll()
    }

    @Test
    fun getLaunchDependencies_usesInjectedProviderForTests() {
        val fakeDependency = mockk<LaunchDependency>()
        every { fakeDependency.appliesTo(container, GameSource.STEAM, 123) } returns true
        sut.setDependenciesProviderForTests { listOf(fakeDependency) }

        val result = sut.getLaunchDependencies(container, GameSource.STEAM, 123)

        assertEquals(1, result.size)
        assertEquals(fakeDependency, result.first())
    }

    @Test
    fun getLaunchDependencies_returnsEmpty_whenDependencyDoesNotApply() {
        val fakeDependency = mockk<LaunchDependency>()
        every { fakeDependency.appliesTo(container, GameSource.STEAM, 123) } returns false
        sut.setDependenciesProviderForTests { listOf(fakeDependency) }
        val result = sut.getLaunchDependencies(container, GameSource.STEAM, 123)

        assertTrue(result.isEmpty())
    }

    @Test
    fun ensureLaunchDependencies_installsWhenUnsatisfied_andAlwaysResetsLoadingUi() = runBlocking {
        sut.setDependenciesProviderForTests { listOf(GogScriptInterpreterDependency) }
        every { GogScriptInterpreterDependency.appliesTo(container, GameSource.GOG, 7) } returns true
        every { GogScriptInterpreterDependency.isSatisfied(context, container, GameSource.GOG, 7) } returns false
        every { GogScriptInterpreterDependency.getLoadingMessage(context, container, GameSource.GOG, 7) } returns "Downloading dep"
        coEvery {
            GogScriptInterpreterDependency.install(
                context,
                container,
                any<LaunchDependencyCallbacks>(),
                GameSource.GOG,
                7,
            )
        } just runs

        val messages = mutableListOf<String>()
        val progress = mutableListOf<Float>()

        sut.ensureLaunchDependencies(
            context = context,
            container = container,
            gameSource = GameSource.GOG,
            gameId = 7,
            setLoadingMessage = { messages += it },
            setLoadingProgress = { progress += it },
        )

        coVerify(exactly = 1) {
            GogScriptInterpreterDependency.install(
                context,
                container,
                any<LaunchDependencyCallbacks>(),
                GameSource.GOG,
                7,
            )
        }
        assertEquals("Downloading dep", messages.first())
        assertEquals(context.getString(R.string.main_loading), messages.last())
        assertEquals(1f, progress.last())
    }

    @Test
    fun ensureLaunchDependencies_skipsInstallWhenAlreadySatisfied_andStillResetsLoadingUi() = runBlocking {
        sut.setDependenciesProviderForTests { listOf(GogScriptInterpreterDependency) }
        every { GogScriptInterpreterDependency.appliesTo(container, GameSource.GOG, 8) } returns true
        every { GogScriptInterpreterDependency.isSatisfied(context, container, GameSource.GOG, 8) } returns true

        val messages = mutableListOf<String>()
        val progress = mutableListOf<Float>()

        sut.ensureLaunchDependencies(
            context = context,
            container = container,
            gameSource = GameSource.GOG,
            gameId = 8,
            setLoadingMessage = { messages += it },
            setLoadingProgress = { progress += it },
        )

        coVerify(exactly = 0) {
            GogScriptInterpreterDependency.install(
                any(),
                any(),
                any<LaunchDependencyCallbacks>(),
                any(),
                any(),
            )
        }
        assertEquals(listOf(context.getString(R.string.main_loading)), messages)
        assertEquals(listOf(1f), progress)
    }

    @Test
    fun ensureLaunchDependencies_resetsLoadingUi_evenIfInstallFails() = runBlocking {
        sut.setDependenciesProviderForTests { listOf(GogScriptInterpreterDependency) }
        every { GogScriptInterpreterDependency.appliesTo(container, GameSource.GOG, 9) } returns true
        every { GogScriptInterpreterDependency.isSatisfied(context, container, GameSource.GOG, 9) } returns false
        every { GogScriptInterpreterDependency.getLoadingMessage(context, container, GameSource.GOG, 9) } returns "Downloading dep"
        coEvery {
            GogScriptInterpreterDependency.install(
                context,
                container,
                any<LaunchDependencyCallbacks>(),
                GameSource.GOG,
                9,
            )
        } throws IllegalStateException("install failed")

        val messages = mutableListOf<String>()
        val progress = mutableListOf<Float>()

        var thrown: IllegalStateException? = null
        try {
            sut.ensureLaunchDependencies(
                context = context,
                container = container,
                gameSource = GameSource.GOG,
                gameId = 9,
                setLoadingMessage = { messages += it },
                setLoadingProgress = { progress += it },
            )
            fail("Expected IllegalStateException to be thrown")
        } catch (e: IllegalStateException) {
            thrown = e
        }

        assertTrue(thrown != null)
        assertEquals(context.getString(R.string.main_loading), messages.last())
        assertEquals(1f, progress.last())
    }
}
