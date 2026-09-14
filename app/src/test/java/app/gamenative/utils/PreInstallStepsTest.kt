package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class PreInstallStepsTest {
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
        gameDir = createTempDirectory(prefix = "preinstall-steps-test").toFile()
        every { container.drives } returns "A:${gameDir.absolutePath}"
        every { container.containerVariant } returns Container.BIONIC
    }

    @After
    fun tearDown() {
        PreInstallSteps.setStepsProviderForTests(null)
        gameDir.deleteRecursively()
    }

    @Test
    fun getPreInstallCommands_returnsEmpty_whenNoADriveExists() {
        every { container.drives } returns "D:/tmp"

        val result = PreInstallSteps.getPreInstallCommands(
            container = container,
            appId = "STEAM_400",
            gameSource = GameSource.STEAM,
            screenInfo = "1280x720",
            containerVariantChanged = false,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun getPreInstallCommands_buildsWrappedGuestCommand_forDetectedInstallerStep() {
        File(gameDir, "UbisoftConnectInstaller.exe").writeText("dummy")

        val result = PreInstallSteps.getPreInstallCommands(
            container = container,
            appId = "STEAM_400",
            gameSource = GameSource.STEAM,
            screenInfo = "1280x720",
            containerVariantChanged = false,
        )

        assertEquals(1, result.size)
        assertEquals(Marker.UBISOFT_CONNECT_INSTALLED, result.first().marker)
        assertEquals(
            "wine explorer /desktop=shell,1280x720 " +
                "winhandler.exe cmd /c \"A:\\_CommonRedist\\UbisoftConnect\\UbisoftConnectInstaller.exe /S " +
                "& taskkill /F /IM explorer.exe & wineserver -k\"",
            result.first().executable,
        )
    }

    @Test
    fun getPreInstallCommands_resetsMarkers_whenContainerVariantChanged() {
        File(gameDir, "UbisoftConnectInstaller.exe").writeText("dummy")
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.UBISOFT_CONNECT_INSTALLED)

        val withoutReset = PreInstallSteps.getPreInstallCommands(
            container = container,
            appId = "STEAM_400",
            gameSource = GameSource.STEAM,
            screenInfo = "1280x720",
            containerVariantChanged = false,
        )
        assertTrue(withoutReset.isEmpty())

        val withReset = PreInstallSteps.getPreInstallCommands(
            container = container,
            appId = "STEAM_400",
            gameSource = GameSource.STEAM,
            screenInfo = "1280x720",
            containerVariantChanged = true,
        )
        assertEquals(1, withReset.size)
        assertEquals(Marker.UBISOFT_CONNECT_INSTALLED, withReset.first().marker)
    }

    @Test
    fun markStepDone_createsSelectedMarkerFile() {
        PreInstallSteps.markStepDone(container, Marker.OPENAL_INSTALLED)

        assertTrue(File(gameDir, Marker.OPENAL_INSTALLED.fileName).exists())
    }

    @Test
    fun markAllDone_createsAllPreInstallMarkerFiles() {
        PreInstallSteps.markAllDone(container)

        val expectedMarkers = listOf(
            Marker.VCREDIST_INSTALLED,
            Marker.PHYSX_INSTALLED,
            Marker.OPENAL_INSTALLED,
            Marker.XNA_INSTALLED,
            Marker.GOG_SCRIPT_INSTALLED,
            Marker.UBISOFT_CONNECT_INSTALLED,
        )
        assertTrue(expectedMarkers.all { marker -> File(gameDir, marker.fileName).exists() })
    }

    @Test
    fun getPreInstallCommands_canUseInjectedProvider_forOrchestrationTesting() {
        PreInstallSteps.setStepsProviderForTests {
            listOf(
                FakeStep(Marker.VCREDIST_INSTALLED, applies = true, command = "echo step1"),
                FakeStep(Marker.OPENAL_INSTALLED, applies = false, command = "echo skipped"),
                FakeStep(Marker.XNA_INSTALLED, applies = true, command = null),
            )
        }

        val result = PreInstallSteps.getPreInstallCommands(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            screenInfo = "1024x768",
            containerVariantChanged = false,
        )

        assertEquals(1, result.size)
        assertEquals(Marker.VCREDIST_INSTALLED, result.first().marker)
        assertEquals(
            "wine explorer /desktop=shell,1024x768 winhandler.exe cmd /c \"echo step1 & taskkill /F /IM explorer.exe & wineserver -k\"",
            result.first().executable,
        )
    }

}
