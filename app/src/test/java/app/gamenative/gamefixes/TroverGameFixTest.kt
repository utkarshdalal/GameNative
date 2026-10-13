package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.EpicGame
import app.gamenative.service.epic.EpicService
import com.winlator.container.Container
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class TroverGameFixTest {
    private lateinit var context: Context
    private lateinit var container: Container

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
        mockkObject(EpicService.Companion)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun applyFor_appliesTroverFixes_whenInstalled() {
        val installDir = createTempDirectory(prefix = "trover-fix").toFile()
        val rootDir = createTempDirectory(prefix = "root-fix").toFile()
        every { EpicService.getEpicGameOf(1) } returns EpicGame(
            id = 1,
            appName = "Sweetpea",
            namespace = "sweetpea",
            catalogId = "7f6bb22e14044be880ba254f683cd928",
            installPath = installDir.absolutePath,
            isInstalled = true
        )
        every { EpicService.getInstallPath(1) } returns installDir.absolutePath
        every { container.containerVariant } returns Container.BIONIC
        every { container.getRootDir() } returns rootDir
        every { container.box64Version } returns "0.4.2"
        every { container.box64Version = any() } just runs
        every { container.wineVersion } returns "proton-9.0-x86_64"
        every { container.wineVersion = any() } just runs
        every { container.graphicsDriver } returns "turnip"
        every { container.graphicsDriver = any() } just runs
        every { container.graphicsDriverVersion = any() } just runs
        every { container.envVars } returns ""
        every { container.envVars = any() } just runs
        every { container.execArgs } returns ""
        every { container.execArgs = any() } just runs
        every { container.isEpicOfflineMode } returns true
        every { container.setEpicOfflineMode(false) } just runs
        every { container.dxWrapper } returns ""
        every { container.dxWrapper = any() } just runs
        every { container.dxWrapperConfig } returns ""
        every { container.dxWrapperConfig = any() } just runs
        every { container.saveData() } just runs

        GameFixesRegistry.applyFor(context, "EPIC_1", container)

        verify(exactly = 1) { container.dxWrapper = "dxvk-async-1.10.3" }
        verify(exactly = 1) { container.setEnvVars(any()) }
        verify(exactly = 1) { container.execArgs = "-nohmd -windowed -ResX=1280 -ResY=720 -dx11 -vr.SteamVR.EnableVRInput=0 -vr.InstancedStereo=0 -vr.MultiView=0" }
        verify(exactly = 1) { container.setEpicOfflineMode(false) }
        verify(exactly = 1) { container.saveData() }
    }
}
