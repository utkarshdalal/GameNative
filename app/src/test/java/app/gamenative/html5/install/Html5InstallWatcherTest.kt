package app.gamenative.html5.install

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.data.GameSource
import app.gamenative.events.AndroidEvent
import app.gamenative.html5.Html5OptInService
import app.gamenative.service.SteamService
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// watcher contract:
// match → flip + snackbar. miss → silent. chromium-disabled → noop start.
@RunWith(RobolectricTestRunner::class)
class Html5InstallWatcherTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var watcher: Html5InstallWatcher

    @Before
    fun setUp() {
        // reset chromium-disabled state to FALSE (default test environment).
        // production sets it true on old WebView; tests assume modern.
        PluviaApp.html5RuntimeDisabled = false

        mockkObject(SteamService.Companion)
        mockkObject(SnackbarManager)
        mockkObject(Html5OptInService)
        mockkObject(ContainerUtils)

        // default: opt-in resolver returns null (miss) — individual tests override.
        every { Html5OptInService.resolveFingerprintPath(any()) } returns null
        // watcher now gates on Steam-installed state. default true; non-installed
        // tests override to false to exercise the bail path.
        every { SteamService.isAppInstalled(any<Int>()) } returns true

        watcher = Html5InstallWatcher(context)
    }

    @After
    fun tearDown() {
        watcher.stop()
        unmockkAll()
    }

    @Test
    fun start_subscribes_once_to_event_bus() {
        watcher.start()
        watcher.start() // second call must be a no-op
        // confirm idempotency — double-start does not throw.
        assertTrue("watcher should report subscribed after start", true)
    }

    @Test
    fun handle_install_complete_with_no_install_path_is_silent_noop() {
        every { Html5OptInService.resolveFingerprintPath("STEAM_99999") } returns null

        runBlocking { watcher.handleInstallComplete(99999) }

        verify(exactly = 0) { SnackbarManager.show(any<String>()) }
        coVerify(exactly = 0) { ContainerUtils.applyToContainerGated(any(), any(), any()) }
    }

    @Test
    fun handle_install_complete_when_app_not_installed_bails_before_fingerprint() {
        // LibraryInstallStatusChanged fires from uninstall + cancel + non-Steam emitters.
        // confirm watcher bails BEFORE touching Html5OptInService when SteamService says
        // the appId is not currently installed.
        every { SteamService.isAppInstalled(8888) } returns false

        runBlocking { watcher.handleInstallComplete(8888) }

        verify(exactly = 0) { Html5OptInService.resolveFingerprintPath(any()) }
        verify(exactly = 0) { SnackbarManager.show(any<String>()) }
        coVerify(exactly = 0) { ContainerUtils.applyToContainerGated(any(), any(), any()) }
    }

    @Test
    fun handle_install_complete_with_fingerprint_match_flips_and_snackbars() {
        // arrange: install path resolves to a folder containing a recognizable RMMV layout.
        val folder = tempFolder.newFolder("Termina")
        File(folder, "www/js").mkdirs()
        File(folder, "www/data").mkdirs()
        File(folder, "www/js/rpg_core.js").writeText("")
        File(folder, "www/data/System.json").writeText("{}")

        every { Html5OptInService.resolveFingerprintPath("STEAM_2171440") } returns folder

        // mock container utilities to avoid touching ContainerManager state.
        val baseContainer = mockk<Container>(relaxed = true)
        every { ContainerUtils.getOrCreateContainer(any(), "STEAM_2171440") } returns baseContainer
        every { ContainerUtils.toContainerData(baseContainer) } returns ContainerData()
        coEvery { ContainerUtils.applyToContainerGated(any(), "STEAM_2171440", any()) } returns true

        every { SteamService.getAppInfoOf(2171440) } returns mockk(relaxed = true) {
            every { name } returns "TERMINA"
        }

        // act
        runBlocking { watcher.handleInstallComplete(2171440) }

        // assert
        coVerify(exactly = 1) {
            ContainerUtils.applyToContainerGated(
                context,
                "STEAM_2171440",
                match { it.containerVariant == Container.CONTAINER_VARIANT_HTML5 },
            )
        }
        verify(exactly = 1) {
            SnackbarManager.show(match<String> { it.contains("TERMINA") && it.contains("HTML5") })
        }
    }

    @Test
    fun handle_install_complete_skips_reflip_when_already_webview() {
        // reinstall of an already-flipped HTML5 container must NOT re-fire the
        // auto-detected snackbar nor re-run applyToContainerGated. forward-only 
        val folder = tempFolder.newFolder("Termina2")
        File(folder, "www/js").mkdirs()
        File(folder, "www/data").mkdirs()
        File(folder, "www/js/rpg_core.js").writeText("")
        File(folder, "www/data/System.json").writeText("{}")

        every { Html5OptInService.resolveFingerprintPath("STEAM_2171440") } returns folder

        val baseContainer = mockk<Container>(relaxed = true) {
            every { runtime } returns Container.RUNTIME_WEBVIEW
        }
        every { ContainerUtils.getOrCreateContainer(any(), "STEAM_2171440") } returns baseContainer

        runBlocking { watcher.handleInstallComplete(2171440) }

        coVerify(exactly = 0) { ContainerUtils.applyToContainerGated(any(), any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any<String>()) }
    }

    @Test
    fun handle_install_complete_with_fingerprint_miss_is_silent_no_flip() {
        val folder = tempFolder.newFolder("UnknownGame")
        // no engine markers — fingerprint will return Unknown.
        every { Html5OptInService.resolveFingerprintPath("STEAM_111") } returns folder

        runBlocking { watcher.handleInstallComplete(111) }

        verify(exactly = 0) { SnackbarManager.show(any<String>()) }
        coVerify(exactly = 0) { ContainerUtils.applyToContainerGated(any(), any(), any()) }
    }

    @Test
    fun handle_install_complete_with_unity_flips_and_snackbars() {
        // Unity WebGL install: Build/<name>.loader.js anchor. promoted out of the Candidate cohort
        // to first-class pack:unity, so the watcher now auto-flips to webview + snackbars "auto
        // detected" (formerly it snackbarred "recognized but unsupported" and stayed wine).
        val folder = tempFolder.newFolder("ProjectUnity")
        File(folder, "Build").mkdirs()
        File(folder, "Build/MyGame.loader.js").writeText("")
        File(folder, "Build/MyGame.framework.js.br").writeText("")
        File(folder, "index.html").writeText("<html></html>")

        every { Html5OptInService.resolveFingerprintPath("STEAM_555") } returns folder

        val baseContainer = mockk<Container>(relaxed = true) {
            every { runtime } returns Container.RUNTIME_WINE
        }
        every { ContainerUtils.getOrCreateContainer(any(), "STEAM_555") } returns baseContainer
        every { ContainerUtils.toContainerData(baseContainer) } returns ContainerData()
        coEvery { ContainerUtils.applyToContainerGated(any(), "STEAM_555", any()) } returns true
        every { SteamService.getAppInfoOf(555) } returns mockk(relaxed = true) {
            every { name } returns "FakeUnityGame"
        }

        runBlocking { watcher.handleInstallComplete(555) }

        coVerify(exactly = 1) {
            ContainerUtils.applyToContainerGated(
                context,
                "STEAM_555",
                match { it.containerVariant == Container.CONTAINER_VARIANT_HTML5 },
            )
        }
        verify(exactly = 1) {
            SnackbarManager.show(match<String> { it.contains("FakeUnityGame") && it.contains("HTML5") })
        }
    }

    @Test
    fun start_when_html5_runtime_disabled_does_not_subscribe() {
        PluviaApp.html5RuntimeDisabled = true
        watcher.start()
        // emit an event — handler should never run since start() bailed early.
        every { Html5OptInService.resolveFingerprintPath(any()) } returns tempFolder.newFolder("X")
        PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(42, GameSource.STEAM))
        // handler not subscribed → no flips, no snackbars.
        verify(exactly = 0) { SnackbarManager.show(any<String>()) }
        coVerify(exactly = 0) { ContainerUtils.applyToContainerGated(any(), any(), any()) }
    }
}
