package app.gamenative.html5.savesync

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.html5.host.WebViewScreenViewModel
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.ProfileRegistry
import app.gamenative.html5.profile.SaveSpec
import app.gamenative.html5.profile.SaveSyncSpec
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import app.gamenative.ui.util.SnackbarManager
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// 3 1 coverage — fsbridge titles must short-circuit BEFORE
// SaveDirectoryResolver.resolve so stripped saves blocks don't throw PathMissing.
@RunWith(RobolectricTestRunner::class)
class Html5SaveSyncServiceFsBridgeTest {

    @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: Html5SaveSyncService
    private lateinit var fakeContainer: Container

    @Before
    fun setUp() {
        PluviaApp.html5RuntimeDisabled = false
        mockkObject(SnackbarManager)
        every { SnackbarManager.show(any()) } just Runs

        mockkObject(ProfileRegistry)
        mockkObject(SteamService.Companion)
        mockkObject(WebViewScreenViewModel.Companion)
        mockkObject(WebViewContainer.Companion)
        every { SteamService.getAppInfoOf(any<Int>()) } returns null
        every { WebViewScreenViewModel.slugFromAppId(any()) } returns null

        mockkConstructor(ContainerManager::class)

        // mirror Html5SaveSyncServiceTest pattern — Container is a Java class with static
        // clinit side effects but is directly constructable under Robolectric.
        fakeContainer = Container("STEAM_3373660").apply {
            installPath = tempFolder.newFolder("install").absolutePath
        }

        every { anyConstructed<ContainerManager>().hasContainer(any()) } returns true
        every { anyConstructed<ContainerManager>().getContainerById(any()) } returns fakeContainer

        service = Html5SaveSyncService(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---------------- fsbridge short-circuit ----------------

    @Test
    fun mirrorOnFlip_fsbridgeProfile_skipsResolverAndNoSnackbar() {
        val profile = fsbridgeProfile()
        every {
            ProfileRegistry.resolveProfile(any(), any(), any())
        } returns profile

        // public mirrorOnFlip path exercises resolveSetup + early-exit branch.
        runBlocking { service.mirrorOnFlip("STEAM_3373660", Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE) }

        // NO snackbar — fsbridge strategy returns null from resolveSetup, legitimate no-op.
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    @Test
    fun syncInbound_fsbridgeProfileWithStrippedSavesBlock_skipsResolver() {
        // profile has saves=null — forProfile returns FsBridge per the universal default.
        // would blow up in SaveDirectoryResolver.resolve if the short-circuit were missing.
        val profile = EngineProfile(engine = "pack:rmmv", saves = null)
        every {
            ProfileRegistry.resolveProfile(any(), any(), any())
        } returns profile

        runBlocking { service.syncInbound("STEAM_3373660") }

        // no throw, no snackbar = success.
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    @Test
    fun mirrorOnFlip_bothDirections_fsbridge_noSnackbar() {
        val profile = fsbridgeProfile()
        every {
            ProfileRegistry.resolveProfile(any(), any(), any())
        } returns profile

        runBlocking {
            service.mirrorOnFlip("STEAM_3373660", Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE)
            service.mirrorOnFlip("STEAM_3373660", Html5SaveSyncService.FlipDirection.WINE_TO_WEBVIEW)
        }

        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    // ---------------- fsbridge is the default even for explicit mechanism ----------------

    @Test
    fun mirrorOnFlip_profileWithExplicitFsbridgeMechanism_shortsCircuit() {
        val profile = EngineProfile(
            engine = "pack:rmmv",
            saves = SaveSpec(sync = SaveSyncSpec(mechanism = "fsbridge")),
        )
        every {
            ProfileRegistry.resolveProfile(any(), any(), any())
        } returns profile

        runBlocking { service.mirrorOnFlip("STEAM_3373660", Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    // ---------------- helpers ----------------

    private fun fsbridgeProfile(): EngineProfile =
        EngineProfile(
            engine = "pack:rmmv",
            saves = SaveSpec(

                sync = SaveSyncSpec(mechanism = "fsbridge"),
            ),
        )
}
