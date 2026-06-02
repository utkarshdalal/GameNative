package app.gamenative.html5.savesync

import android.content.Context
import app.gamenative.NetworkMonitor
import app.gamenative.PluviaApp
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.enums.PathType
import app.gamenative.events.AndroidEvent
import app.gamenative.html5.host.WebViewOrigin
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// dispatch contract tests.
// robolectric for Context; MockK for ContainerManager + ProfileRegistry + SteamService statics.
// strategy internals are covered by tests — here we verify the service dispatches to
// the right strategy method (outbound / inbound) for each entry point, and that failures
// route through SnackbarManager + Timber without escaping.

// approach: mockkObject(SaveSyncStrategy.RmmvFilesystem) + stub forProfile → the object so we
// can verify(outbound vs inbound) without exercising real leveldb / file IO.
@RunWith(RobolectricTestRunner::class)
class Html5SaveSyncServiceTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: Html5SaveSyncService
    private lateinit var fakeContainer: Container

    @Before
    fun setUp() {
        PluviaApp.html5RuntimeDisabled = false

        // default: online. offline-gate tests override per-test. NetworkMonitor.init runs from
        // PluviaApp.onCreate in production; tests stub the StateFlow so existing snackbar
        // assertions keep working.
        mockkObject(NetworkMonitor)
        every { NetworkMonitor.hasInternet } returns kotlinx.coroutines.flow.MutableStateFlow(true)

        mockkObject(SnackbarManager)
        every { SnackbarManager.show(any()) } just Runs

        // pin loopback port so origin URLs are deterministic — resolver derives webview
        // origin filename from WebViewOrigin.originUrl which calls ensurePortAllocated.
        mockkObject(WebViewOrigin)
        every { WebViewOrigin.ensurePortAllocated() } returns 5723

        mockkObject(SteamService.Companion)
        mockkObject(ProfileRegistry)
        mockkObject(WebViewScreenViewModel.Companion)
        mockkObject(WebViewContainer.Companion)

        // GreenworksCloudClient stub — default no-op so existing UFS tests don't
        // accidentally exercise the network path; greenworks branch tests override per-test.
        mockkObject(GreenworksCloudClient)
        coEvery { GreenworksCloudClient.upload(any(), any()) } returns
            GreenworksCloudClient.UploadResult(success = true, filesUploaded = 0, bytesUploaded = 0L)
        coEvery { GreenworksCloudClient.download(any()) } returns emptyList()

        // strategy dispatch — short-circuit into a mocked RmmvFilesystem so the test never
        // touches leveldb / RmmvSaveMapper internals.
        mockkObject(SaveSyncStrategy.RmmvFilesystem)
        every { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) } just Runs
        every { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) } just Runs
        mockkObject(SaveSyncStrategy.Companion)
        every { SaveSyncStrategy.forProfile(any()) } returns SaveSyncStrategy.RmmvFilesystem

        mockkConstructor(ContainerManager::class)
        fakeContainer = Container("STEAM_2171440").apply {
            installPath = tempFolder.newFolder("install").absolutePath
        }
        every { anyConstructed<ContainerManager>().hasContainer("STEAM_2171440") } returns true
        every { anyConstructed<ContainerManager>().getContainerById("STEAM_2171440") } returns fakeContainer

        every { ProfileRegistry.resolveProfile(any(), any(), any()) } returns rmmvFilesystemProfile()
        every { WebViewScreenViewModel.slugFromAppId(any()) } returns null
        // default appInfo NON-NULL with 1 NON-windows-rooted UFS pattern.
        // non-null clears the new "non-Steam container" gate; non-empty UFS clears the
        // "no cloud support" gate; non-windows root keeps syncMode = LOCAL_ONLY (matches the
        // prior null-appInfo behavior — install path resolves under tempFolder, no Wine prefix
        // / SteamService.getAppDirPath statics involved). tests that need the null-appInfo
        // branch (silent no-op) override per-test.
        every { SteamService.getAppInfoOf(any<Int>()) } returns SteamApp(
            id = 2171440,
            name = "TERMINA",
            ufs = UFS(
                saveFilePatterns = listOf(
                    SaveFilePattern(root = PathType.LinuxHome, path = "ignored", pattern = "*"),
                ),
            ),
        )

        service = Html5SaveSyncService(context)
    }

    @After
    fun tearDown() {
        service.stop()
        unmockkAll()
    }

    // ---------- exit-sync / WebViewDestroyed ----------

    @Test
    fun onWebViewDestroyed_withActiveContainer_runsOutboundSync() {
        service.markActive("STEAM_2171440")
        service.start()
        PluviaApp.events.emit(AndroidEvent.WebViewDestroyed)

        // event-bus handler launches on service's IO scope — poll-wait for the strategy call
        // rather than Thread.sleep (bounded; faster green).
        waitForStrategyCall { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 1) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    @Test
    fun onWebViewDestroyed_withoutActiveContainer_isSilentNoOp() {
        service.start()
        // no markActive → handler logs warn but dispatches nothing.
        PluviaApp.events.emit(AndroidEvent.WebViewDestroyed)
        Thread.sleep(100)
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    // ---------- launch-sync / mtime gate ----------

    @Test
    fun syncInbound_wineNewer_callsStrategyInbound() {
        // seed wine-side so its mtime > webview-side (webview dir absent → mtime 0).
        val wineDir = File(fakeContainer.installPath, "www/save").apply { mkdirs() }
        File(wineDir, "file1.rpgsave").apply {
            writeText("fake")
            setLastModified(System.currentTimeMillis())
        }

        runBlocking { service.syncInbound("STEAM_2171440") }

        verify(exactly = 1) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    @Test
    fun syncInbound_wineEmpty_skipsStrategyCall() {
        // nothing on disk → wine mtime is 0 → shouldSyncInbound returns false → no strategy call.
        runBlocking { service.syncInbound("STEAM_2171440") }

        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    @Test
    fun syncInbound_wineUnchangedSinceLastSync_skipsStrategyCall() {
        // marker-based gate: if wine-newest mtime <= lastApplied marker, skip.
        // race-free replacement for the old webview-vs-wine comparison (chromium touches
        // LOG/MANIFEST on open, which falsely tripped the old gate). seed a marker at
        // the wine file's mtime to simulate "wine unchanged since last successful sync".
        val wineDir = File(fakeContainer.installPath, "www/save").apply { mkdirs() }
        val wineMtime = System.currentTimeMillis()
        File(wineDir, "file1.rpgsave").apply {
            writeText("unchanged")
            setLastModified(wineMtime)
        }
        val markerDir = File(context.filesDir, "html5/sync-state").apply { mkdirs() }
        File(markerDir, "STEAM_2171440.lastApplied").writeText(wineMtime.toString())

        runBlocking { service.syncInbound("STEAM_2171440") }

        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
    }

    // ---------- mirror on flip ----------

    @Test
    fun mirrorOnFlip_webViewToWine_runsOutbound() {
        runBlocking {
            service.mirrorOnFlip("STEAM_2171440", Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE)
        }
        verify(exactly = 1) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    @Test
    fun mirrorOnFlip_wineToWebView_runsInbound() {
        runBlocking {
            service.mirrorOnFlip("STEAM_2171440", Html5SaveSyncService.FlipDirection.WINE_TO_WEBVIEW)
        }
        verify(exactly = 1) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    // ---------- failure surface ----------

    @Test
    fun resolveSetup_nullAppInfo_silentNoOp_noSnackbar() {
        // appInfo == null = non-Steam container (CUSTOM_GAME_*/GOG_/EPIC_/
        // sideloaded TERMINA-shape) OR steam app not yet cached. silent no-op — no snackbar
        // (user knows the container is non-Steam), no error. without this gate, fell through
        // to ProfileRegistry.resolveProfile + SaveDirectoryResolver.resolve, which threw
        // PathMissing for legitimate non-Steam containers ("save path not found" symptom).
        every { SteamService.getAppInfoOf(any<Int>()) } returns null

        // seed wine-side files so syncInbound advances past the empty-dir guard and reaches
        // resolveSetup (which is where the null-appInfo branch fires).
        val wineDir = File(fakeContainer.installPath, "www/save").apply { mkdirs() }
        File(wineDir, "file1.rpgsave").apply { writeText("fake"); setLastModified(System.currentTimeMillis()) }

        service.markActive("STEAM_2171440")

        runBlocking { service.syncInbound("STEAM_2171440") }
        runBlocking { service.mirrorOnFlip("STEAM_2171440", Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE) }
        runBlocking { service.mirrorOnFlip("STEAM_2171440", Html5SaveSyncService.FlipDirection.WINE_TO_WEBVIEW) }

        // ZERO snackbars across all three entry points
        verify(exactly = 0) { SnackbarManager.show(any()) }
        // ZERO strategy invocations — sync no-ops without dispatch
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
    }

    @Test
    fun resolveSetup_emptyUfs_surfacesUnsupportedGameSnackbarOncePerLaunch() {
        // part-B (corrected): authoritative gate is appInfo.ufs.saveFilePatterns
        // .isEmpty() — Steam side has no cloud config for this game (e.g. Felvidek 2299900,
        // observed UFS count=0). graceful no-op + at-most-once "doesn't support Steam Cloud"
        // info snackbar. fires BEFORE pack profile resolve.
        every { SteamService.getAppInfoOf(2171440) } returns SteamApp(
            id = 2171440,
            name = "FelvidekShape",
            ufs = UFS(saveFilePatterns = emptyList()),
        )

        // seed wine-side files so syncInbound advances past the empty-dir guard and reaches
        // resolveSetup (which is where the UFS-empty branch fires).
        val wineDir = File(fakeContainer.installPath, "www/save").apply { mkdirs() }
        File(wineDir, "file1.rpgsave").apply { writeText("fake"); setLastModified(System.currentTimeMillis()) }

        service.markActive("STEAM_2171440")

        runBlocking { service.syncInbound("STEAM_2171440") }
        runBlocking { service.syncInbound("STEAM_2171440") }

        // exactly once across the two attempts (at-most-once gate)
        verify(exactly = 1) {
            SnackbarManager.show(match<String> { it.contains("Steam Cloud", ignoreCase = true) })
        }
        // never the legitimate "save path not found" copy
        verify(exactly = 0) {
            SnackbarManager.show(match<String> { it.contains("save path", ignoreCase = true) })
        }
        // UFS-empty gate fires BEFORE profile resolve — strategy must not be invoked
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
    }

    @Test
    fun resolveSetup_emptyUfs_offline_suppressesUnsupportedSnackbar() {
        // offline (no network) → "this game doesn't support Steam Cloud" is misleading,
        // since cached SteamApp.ufs may simply not be populated yet. flag still flips so a
        // mid-launch reconnect doesn't re-open the surface.
        every { NetworkMonitor.hasInternet } returns kotlinx.coroutines.flow.MutableStateFlow(false)

        every { SteamService.getAppInfoOf(2171440) } returns SteamApp(
            id = 2171440,
            name = "FelvidekShape",
            ufs = UFS(saveFilePatterns = emptyList()),
        )
        val wineDir = File(fakeContainer.installPath, "www/save").apply { mkdirs() }
        File(wineDir, "file1.rpgsave").apply { writeText("fake"); setLastModified(System.currentTimeMillis()) }

        service.markActive("STEAM_2171440")
        runBlocking { service.syncInbound("STEAM_2171440") }

        verify(exactly = 0) {
            SnackbarManager.show(match<String> { it.contains("Steam Cloud", ignoreCase = true) })
        }
    }

    @Test
    fun handleFailure_other_offline_suppressesGenericFailureSnackbar() {
        // generic "Save sync failed" is the bucket most likely to be a swallowed network
        // throw — gate offline. device-side failures (corruption/lock/missing/permission/
        // incompatible) are NOT gated and exercised by other tests in this file.
        every { NetworkMonitor.hasInternet } returns kotlinx.coroutines.flow.MutableStateFlow(false)

        every { ProfileRegistry.resolveProfile(any(), any(), any()) } throws
            IllegalStateException("unexpected")

        runBlocking { service.syncInbound("STEAM_2171440") }

        verify(exactly = 0) {
            SnackbarManager.show(match<String> { it.startsWith("Save sync failed") })
        }
    }

    @Test
    fun resolveSetup_nonEmptyUfs_butResolveFails_throwsPathMissing() {
        // part-B (corrected): cloud-supported game (Steam advertises UFS patterns)
        // but our pack profile resolve fails → REAL GameNative gap. surface loudly via
        // SaveSyncFailure.PathMissing → save_sync_missing snackbar ("Save path not found").
        // distinct from the empty-UFS graceful path above.
        every { SteamService.getAppInfoOf(2171440) } returns SteamApp(
            id = 2171440,
            name = "LookOutsideShape",
            ufs = UFS(
                saveFilePatterns = listOf(
                    SaveFilePattern(root = PathType.GameInstall, path = "www/save", pattern = "*"),
                ),
            ),
        )
        every { ProfileRegistry.resolveProfile(any(), any(), any()) } returns null

        val wineDir = File(fakeContainer.installPath, "www/save").apply { mkdirs() }
        File(wineDir, "file1.rpgsave").apply { writeText("fake"); setLastModified(System.currentTimeMillis()) }

        service.markActive("STEAM_2171440")
        runBlocking { service.syncInbound("STEAM_2171440") }

        // loud "save path not found" — the real-bug surface
        verify(atLeast = 1) {
            SnackbarManager.show(match<String> { it.contains("save path", ignoreCase = true) })
        }
        // NOT the graceful unsupported copy
        verify(exactly = 0) {
            SnackbarManager.show(match<String> { it.contains("Steam Cloud", ignoreCase = true) })
        }
    }

    @Test
    fun handleFailure_genericExceptionWrappedAsOther() {
        every { ProfileRegistry.resolveProfile(any(), any(), any()) } throws
            IllegalStateException("unexpected")

        runBlocking { service.syncInbound("STEAM_2171440") }

        verify(exactly = 1) {
            SnackbarManager.show(match<String> { it.startsWith("Save sync failed") })
        }
    }

    @Test
    fun handleFailure_exitSyncFailureDoesNotEscape() {
        // exit-sync path must snackbar + never throw. drive via event-bus emit.
        every { ProfileRegistry.resolveProfile(any(), any(), any()) } throws
            RuntimeException("boom")

        service.markActive("STEAM_2171440")
        service.start()

        PluviaApp.events.emit(AndroidEvent.WebViewDestroyed)

        waitFor {
            try {
                verify(atLeast = 1) { SnackbarManager.show(any()) }
                true
            } catch (t: Throwable) {
                false
            }
        }
        verify(atLeast = 1) { SnackbarManager.show(any()) }
    }

    // ---------- Origins bundle carries webview URL form ----------

    // strategy.syncOutbound receives Origins where webViewOriginFilename derives from the
    // per-container synthetic origin (http://<safeId>.localhost:<port> →
    // http_<safeId>.localhost_<port>). guards against origin-derivation drift between
    // WebViewOrigin and SaveDirectoryResolver.
    @Test
    fun configureSetup_origins_bundle_carries_per_container_webview_origin() {
        runBlocking {
            service.mirrorOnFlip("STEAM_2171440", Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE)
        }
        verify(exactly = 1) {
            SaveSyncStrategy.RmmvFilesystem.syncOutbound(
                any(),
                match<Origins> { it.webViewOriginFilename == "http_steam-2171440.localhost_5723" },
            )
        }
    }

    // ---------- resolveCloudSourceForContainer ----------

    @Test
    fun resolveCloudSourceForContainer_steamPrefix_returnsSteamUfs() {
        val steamApp = steamAppWithUfs(
            SaveFilePattern(root = PathType.GameInstall, path = "www/save", pattern = "*"),
        )
        every { SteamService.getAppInfoOf(2171440) } returns steamApp

        val c = Container("STEAM_2171440")
        val resolved = runBlocking { service.resolveCloudSourceForContainer(c) }
        assertEquals(steamApp.id, (resolved as? CloudSource.SteamUfs)?.steamApp?.id)
    }

    @Test
    fun resolveCloudSourceForContainer_customGamePrefix_returnsNull() {
        val c = Container("CUSTOM_GAME_1846830703")
        assertNull(runBlocking { service.resolveCloudSourceForContainer(c) })
    }

    @Test
    fun resolveCloudSourceForContainer_gogPrefix_returnsGogRemoteConfig() {
        // GOG_ now mapped to CloudSource.GogRemoteConfig (was returning null pre-260430-ur0).
        val c = Container("GOG_12345")
        val resolved = runBlocking { service.resolveCloudSourceForContainer(c) }
        assertEquals("GOG_12345", (resolved as? CloudSource.GogRemoteConfig)?.appId)
    }

    @Test
    fun resolveCloudSourceForContainer_steamWithNonIntSuffix_returnsNull() {
        val c = Container("STEAM_notanumber")
        assertNull(runBlocking { service.resolveCloudSourceForContainer(c) })
    }

    // ---------- start/stop idempotency ----------

    @Test
    fun start_idempotent_secondCallNoOps() {
        service.start()
        service.start()
        // no duplicate event-handler. fire event + confirm only one strategy call.
        service.markActive("STEAM_2171440")
        PluviaApp.events.emit(AndroidEvent.WebViewDestroyed)
        waitForStrategyCall { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 1) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
    }

    @Test
    fun start_skipsWhenHtml5RuntimeDisabled() {
        PluviaApp.html5RuntimeDisabled = true
        service.start()
        service.markActive("STEAM_2171440")
        PluviaApp.events.emit(AndroidEvent.WebViewDestroyed)
        Thread.sleep(100)
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    @Test
    fun resolveSetup_missingContainer_skipsSilently() {
        every { anyConstructed<ContainerManager>().hasContainer(any<String>()) } returns false

        runBlocking { service.syncInbound("STEAM_2171440") }

        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    // ---------- 4 new origin-shape tests ----------

    // origins bundle carries URL form for LS + filename form for IDB.
    // v2.1 origin: http://<safeId>.localhost:<port> → http_<safeId>.localhost_<port>.
    @Test
    fun syncOutbound_passesUrlFormToLsAndFilenameFormToIdb() {
        runBlocking {
            service.mirrorOnFlip("STEAM_2171440", Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE)
        }
        verify(exactly = 1) {
            SaveSyncStrategy.RmmvFilesystem.syncOutbound(
                any(),
                match<Origins> { origins ->
                    origins.webViewOriginUrl.startsWith("http://") &&
                        origins.webViewOriginUrl.contains(".localhost:") &&
                        origins.webViewOriginFilename.startsWith("http_") &&
                        origins.webViewOriginFilename.contains(".localhost_") &&
                        // port suffix must be a positive integer (not the historical "_0")
                        origins.webViewOriginFilename.substringAfterLast("_").toIntOrNull()?.let { it > 0 } == true
                },
            )
        }
    }

    // T2: pcOrigin URL in profile → pcOriginFilename derived via OriginCodec.filenameFromUrl.
    // "file://" → "file__0"
    @Test
    fun resolveSetup_pcOriginUrl_derivesFilenameViaOriginCodec() {
        every { ProfileRegistry.resolveProfile(any(), any(), any()) } returns
            levelDbProfileWithPcOrigin(pcOrigin = "file://")

        runBlocking {
            service.mirrorOnFlip("STEAM_2171440", Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE)
        }
        verify(exactly = 1) {
            SaveSyncStrategy.RmmvFilesystem.syncOutbound(
                any(),
                match<Origins> { it.pcOriginFilename == "file__0" },
            )
        }
    }

    // drift lock — OriginCodec.filenameFromUrl(WebViewOrigin.originUrl(id)) must equal
    // WebViewOrigin.levelDbPrefix(id) for every container id shape. asserts the
    // single-code-path invariant: chromium leveldb filename derivation funnels through
    // OriginCodec, and we need every consumer (resolver, rewriter) to compute identical
    // strings from the same containerId.
    @Test
    fun webViewOriginFilename_matchesWebViewOriginLevelDbPrefix_driftLock() {
        val ids = listOf("STEAM_379210", "STEAM_2738490", "CUSTOM_GAME_1846830703", "GOG_12345")
        for (id in ids) {
            val derived = OriginCodec.filenameFromUrl(WebViewOrigin.originUrl(id))
            val canonical = WebViewOrigin.levelDbPrefix(id)
            assertEquals(
                "drift detected for containerId=$id: OriginCodec path '$derived' != WebViewOrigin path '$canonical'",
                canonical,
                derived,
            )
        }
    }

    // ---------- greenworks branch ----------

    @Test
    fun resolveCloudSourceForContainer_steamWithGreenworksObserved_returnsGreenworksCloud() {
        every { WebViewScreenViewModel.slugFromAppId("STEAM_1454400") } returns "cookie-clicker"
        every { WebViewContainer.load("cookie-clicker", any()) } returns WebViewContainer(
            id = "STEAM_1454400",
            installPath = "/tmp",
            engineProfile = "pack:electron",
            greenworksCloudObserved = true,
        )
        every { SteamService.getAppInfoOf(1454400) } returns SteamApp(
            id = 1454400,
            name = "Cookie Clicker",
            ufs = UFS(saveFilePatterns = emptyList()),
        )
        val container = Container("STEAM_1454400")
        val resolved = runBlocking { service.resolveCloudSourceForContainer(container) }
        assertTrue(
            "expected GreenworksCloud, got ${resolved?.javaClass?.simpleName}",
            resolved is CloudSource.GreenworksCloud,
        )
        assertEquals("STEAM_1454400", (resolved as CloudSource.GreenworksCloud).appId)
        assertTrue(resolved.observed)
    }

    @Test
    fun resolveCloudSourceForContainer_steamWithoutGreenworksObserved_returnsSteamUfs() {
        every { WebViewScreenViewModel.slugFromAppId("STEAM_2171440") } returns "termina"
        every { WebViewContainer.load("termina", any()) } returns WebViewContainer(
            id = "STEAM_2171440",
            installPath = "/tmp",
            engineProfile = "pack:rmmv",
            greenworksCloudObserved = false,
        )
        val container = Container("STEAM_2171440")
        val resolved = runBlocking { service.resolveCloudSourceForContainer(container) }
        assertTrue(
            "expected SteamUfs (greenworksCloudObserved=false), got ${resolved?.javaClass?.simpleName}",
            resolved is CloudSource.SteamUfs,
        )
    }

    @Test
    fun resolveCloudSourceForContainer_steamWithoutWebViewContainer_returnsSteamUfs() {
        every { WebViewScreenViewModel.slugFromAppId(any()) } returns null
        val container = Container("STEAM_2171440")
        val resolved = runBlocking { service.resolveCloudSourceForContainer(container) }
        assertTrue(resolved is CloudSource.SteamUfs)
    }

    @Test
    fun resolveCloudSourceForContainer_hybridLogsWarn() {
        every { WebViewScreenViewModel.slugFromAppId("STEAM_3333333") } returns "hybrid-game"
        every { WebViewContainer.load("hybrid-game", any()) } returns WebViewContainer(
            id = "STEAM_3333333",
            installPath = "/tmp",
            engineProfile = "pack:electron",
            greenworksCloudObserved = true,
        )
        every { SteamService.getAppInfoOf(3333333) } returns SteamApp(
            id = 3333333,
            name = "Hybrid",
            ufs = UFS(
                saveFilePatterns = listOf(
                    SaveFilePattern(root = PathType.LinuxHome, path = "ignored", pattern = "*"),
                ),
            ),
        )
        // tree spy to capture Timber WARN.
        val captured = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= android.util.Log.WARN) captured += "[$tag] $message"
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val resolved = runBlocking { service.resolveCloudSourceForContainer(Container("STEAM_3333333")) }
            // greenworks variant wins per architecture.
            assertTrue(resolved is CloudSource.GreenworksCloud)
            // hybrid log fired.
            assertTrue(
                "expected hybrid cloud WARN; captured=$captured",
                captured.any { it.contains("hybrid cloud") },
            )
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun syncOutbound_greenworks_consumesSnapshotAndCallsUpload() {
        // resolve setup chain (mirror the steamWithGreenworksObserved test).
        every { WebViewScreenViewModel.slugFromAppId(any()) } returns "cookie-clicker"
        every { WebViewContainer.load("cookie-clicker", any()) } returns WebViewContainer(
            id = "STEAM_1454400",
            installPath = fakeContainer.installPath,
            engineProfile = "pack:electron",
            greenworksCloudObserved = true,
        )
        every { SteamService.getAppInfoOf(1454400) } returns SteamApp(
            id = 1454400,
            name = "Cookie Clicker",
            ufs = UFS(saveFilePatterns = emptyList()),
        )
        every { anyConstructed<ContainerManager>().hasContainer("STEAM_1454400") } returns true
        every { anyConstructed<ContainerManager>().getContainerById("STEAM_1454400") } returns
            Container("STEAM_1454400").apply { installPath = fakeContainer.installPath }
        val mockBridge = io.mockk.mockk<app.gamenative.html5.shim.SteamworksJsBridge>(relaxed = true)
        val mockWebView = io.mockk.mockk<android.webkit.WebView>(relaxed = true)
        // base64 of "abc123" = "YWJjMTIz"
        every { mockBridge.consumeGreenworksOutboundSnapshot() } returns
            """{"cookieClickerSave.txt":"YWJjMTIz"}"""
        coEvery { GreenworksCloudClient.upload(1454400, any()) } returns
            GreenworksCloudClient.UploadResult(success = true, filesUploaded = 1, bytesUploaded = 6L)

        service.markActive("STEAM_1454400", "pack:electron")
        service.setActiveWebView(mockWebView, mockBridge)

        runBlocking { service.syncOutbound("STEAM_1454400") }

        coVerify(exactly = 1) {
            GreenworksCloudClient.upload(
                appId = 1454400,
                files = match { list ->
                    list.size == 1 &&
                        list[0].first == "cookieClickerSave.txt" &&
                        list[0].second.contentEquals("abc123".toByteArray())
                },
            )
        }
    }

    // NOTE: the inbound happy-path (download → evaluateJavascript('localStorage.setItem(...)'))
    // is covered by Plan device smoke, NOT here. syncInboundGreenworks dispatches via
    // withContext(Dispatchers.Main) before invoking webView.evaluateJavascript (WebView API
    // contract), and we don't pull kotlinx-coroutines-test in for Robolectric Main-dispatcher
    // setup. Robolectric's paused main looper hangs runBlocking indefinitely. The outbound
    // path has no Main hop and IS covered (syncOutbound_greenworks_consumesSnapshotAndCallsUpload).

    @Test
    fun syncOutbound_greenworks_suppressedAfterInboundFailure() {
        every { WebViewScreenViewModel.slugFromAppId(any()) } returns "cookie-clicker"
        every { WebViewContainer.load("cookie-clicker", any()) } returns WebViewContainer(
            id = "STEAM_1454400",
            installPath = fakeContainer.installPath,
            engineProfile = "pack:electron",
            greenworksCloudObserved = true,
        )
        every { SteamService.getAppInfoOf(1454400) } returns SteamApp(
            id = 1454400,
            name = "Cookie Clicker",
            ufs = UFS(saveFilePatterns = emptyList()),
        )
        every { anyConstructed<ContainerManager>().hasContainer("STEAM_1454400") } returns true
        every { anyConstructed<ContainerManager>().getContainerById("STEAM_1454400") } returns
            Container("STEAM_1454400").apply { installPath = fakeContainer.installPath }
        // simulate prior inbound failure for this appId by making download throw — handler
        // catches, adds appId to inboundFailedThisSession; subsequent outbound short-circuits.
        coEvery { GreenworksCloudClient.download(1454400) } throws RuntimeException("network down")

        service.markActive("STEAM_1454400", "pack:electron")
        service.setActiveWebView(io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true))

        runBlocking { service.syncInbound("STEAM_1454400") }
        // now syncOutbound MUST short-circuit on the inboundFailedThisSession gate.
        runBlocking { service.syncOutbound("STEAM_1454400") }

        // upload was never called — the gate fired before either greenworks branch could.
        coVerify(exactly = 0) { GreenworksCloudClient.upload(any(), any()) }
    }

    // ---------- fixtures ----------

    private fun rmmvFilesystemProfile() = EngineProfile(
        engine = "pack:rmmv",
        saves = SaveSpec(
            sync = SaveSyncSpec(
                mechanism = "rmmv-filesystem",
                localSaveSubdir = "www/save",
            ),
        ),
    )

    private fun levelDbProfileWithPcOrigin(pcOrigin: String = "") = EngineProfile(
        engine = "pack:electron",
        saves = SaveSpec(
            sync = SaveSyncSpec(
                pcOrigin = pcOrigin,
                mechanism = "leveldb-origin-rewrite",
                localSaveSubdir = "www/save",
            ),
        ),
    )

    private fun steamAppWithUfs(vararg patterns: SaveFilePattern): SteamApp = SteamApp(
        id = 2171440,
        name = "TERMINA",
        ufs = UFS(saveFilePatterns = patterns.toList()),
    )

    private fun waitFor(maxMillis: Long = 2000, pollMillis: Long = 25, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + maxMillis
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(pollMillis)
        }
    }

    private fun waitForStrategyCall(maxMillis: Long = 2000, verifyBlock: io.mockk.MockKVerificationScope.() -> Unit) {
        waitFor(maxMillis) {
            try {
                io.mockk.verify(atLeast = 1, verifyBlock = verifyBlock)
                true
            } catch (t: Throwable) {
                false
            }
        }
    }
}
