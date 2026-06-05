package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class PreLaunchSetupTest {
    private lateinit var container: Container
    private lateinit var gameDir: File

    private data class FakeStep(
        override val marker: Marker,
        val applies: Boolean,
        val command: String?,
    ) : PreInstallStep {
        override fun appliesTo(container: Container, gameSource: GameSource, gameDirPath: String): Boolean = applies

        override fun buildCommand(
            container: Container,
            appId: String,
            gameSource: GameSource,
            gameDir: File,
            gameDirPath: String,
        ): String? = command
    }

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "prelaunch-setup-test").toFile()
        every { container.drives } returns "A:${gameDir.absolutePath}"
        every { container.containerVariant } returns Container.BIONIC
    }

    @After
    fun tearDown() {
        PreInstallSteps.setStepsProviderForTests(null)
        gameDir.deleteRecursively()
    }

    @Test
    fun buildChain_mapsPreInstallCommandsToChainedCommands() {
        PreInstallSteps.setStepsProviderForTests {
            listOf(
                FakeStep(Marker.VCREDIST_INSTALLED, applies = true, command = "echo vcredist"),
                FakeStep(Marker.OPENAL_INSTALLED, applies = true, command = "echo openal"),
            )
        }

        val chain = PreLaunchSetup.buildChain(
            container = container,
            appId = "GOG_400",
            gameSource = GameSource.GOG,
            screenInfo = "1280x720",
            containerVariantChanged = false,
        )

        assertEquals(2, chain.size)
        assertTrue(chain[0].executable.contains("echo vcredist"))
        assertTrue(chain[1].executable.contains("echo openal"))
    }

    @Test
    fun buildChain_returnsEmptyList_whenNoStepsApply() {
        PreInstallSteps.setStepsProviderForTests {
            listOf(FakeStep(Marker.VCREDIST_INSTALLED, applies = false, command = "echo skip"))
        }

        val chain = PreLaunchSetup.buildChain(
            container = container,
            appId = "GOG_400",
            gameSource = GameSource.GOG,
            screenInfo = "1280x720",
            containerVariantChanged = false,
        )

        assertTrue(chain.isEmpty())
    }

    @Test
    fun buildChain_onComplete_createsMarkerFile() {
        PreInstallSteps.setStepsProviderForTests {
            listOf(FakeStep(Marker.OPENAL_INSTALLED, applies = true, command = "echo openal"))
        }

        val chain = PreLaunchSetup.buildChain(
            container = container,
            appId = "GOG_400",
            gameSource = GameSource.GOG,
            screenInfo = "1280x720",
            containerVariantChanged = false,
        )

        assertEquals(1, chain.size)
        chain[0].onComplete()
        assertTrue(File(gameDir, Marker.OPENAL_INSTALLED.fileName).exists())
    }
}
