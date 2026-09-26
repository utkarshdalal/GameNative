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

// dispatch contract only: each entry point hits the right strategy direction, and failures route through
// SnackbarManager without escaping. strategy internals are covered elsewhere, so forProfile is stubbed to a
// mocked RmmvFilesystem and no real leveldb / file IO runs.
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

        // NetworkMonitor.init only runs from PluviaApp.onCreate; default online, offline-gate tests override
        mockkObject(NetworkMonitor)
        every { NetworkMonitor.hasInternet } returns kotlinx.coroutines.flow.MutableStateFlow(true)

        mockkObject(SnackbarManager)
        every { SnackbarManager.show(any()) } just Runs

        // pin the loopback port so the resolver's derived webview origin is deterministic
        mockkObject(WebViewOrigin)
        every { WebViewOrigin.ensurePortAllocated() } returns 5723

        mockkObject(SteamService.Companion)
        mockkObject(ProfileRegistry)
        mockkObject(WebViewScreenViewModel.Companion)
        mockkObject(WebViewContainer.Companion)

        // default no-op so UFS tests never reach the network; greenworks tests override
        mockkObject(GreenworksCloudClient)
        coEvery { GreenworksCloudClient.upload(any(), any()) } returns
            GreenworksCloudClient.UploadResult(success = true, filesUploaded = 0, bytesUploaded = 0L)
        coEvery { GreenworksCloudClient.download(any()) } returns emptyList()

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
        // non-null appInfo clears the "non-Steam container" gate; non-empty UFS clears the "no cloud support"
        // gate; a non-windows root keeps syncMode = LOCAL_ONLY so paths resolve under tempFolder with no Wine
        // prefix / getAppDirPath statics involved.
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

    @Test
    fun onWebViewDestroyed_withActiveContainer_runsOutboundSync() {
        service.markActive("STEAM_2171440")
        service.start()
        PluviaApp.events.emit(AndroidEvent.WebViewDestroyed)

        // handler runs on the service's IO scope, so poll for the call
        waitForStrategyCall { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 1) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    @Test
    fun onWebViewDestroyed_withoutActiveContainer_isSilentNoOp() {
        service.start()
        PluviaApp.events.emit(AndroidEvent.WebViewDestroyed)
        Thread.sleep(100)
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    private fun oneLsEntry() = listOf(LocalStorageSnapshot.encode("k".toCharArray()) to LocalStorageSnapshot.encode("v".toCharArray()))

    @Test
    fun lsRestore_handedOutOncePerLaunch_markerWrittenWhenPageApplies() {
        val marker = Html5SaveSyncService.syncStateMarkerFile(context, "STEAM_2171440").apply { delete() }
        service.stageLsRestore("STEAM_2171440", oneLsEntry(), 1234L)

        assertTrue(service.takeLsRestore("STEAM_2171440")!!.isNotEmpty())
        assertNull("an in-game reload must not get it again", service.takeLsRestore("STEAM_2171440"))
        assertTrue("marker waits for the page", !marker.exists())

        service.markLsRestoreApplied("STEAM_2171440")

        assertEquals("1234", marker.readText())
    }

    // uninstalled and relaunched in the same process, before the boot purge: the page clears the stale keys instead.
    @Test
    fun lsRestore_pendingUninstallPurge_pageClearsOrigin_thenLeavesQueue() {
        Html5PendingLsPurge.enqueue(context, "STEAM_2171440")
        try {
            assertEquals("[]", service.takeLsRestore("STEAM_2171440"))

            service.markLsRestoreApplied("STEAM_2171440")

            assertTrue(!Html5PendingLsPurge.isPending(context, "STEAM_2171440"))
            assertNull("nothing left to clear", service.takeLsRestore("STEAM_2171440"))
        } finally {
            Html5PendingLsPurge.remove(context, "STEAM_2171440")
        }
    }

    // the live store never got the game's keys, so exit sync must not copy it over the Wine copy.
    @Test
    fun syncOutbound_skippedWhileLsRestoreNotApplied() {
        service.stageLsRestore("STEAM_2171440", oneLsEntry(), 1234L)

        runBlocking { service.syncOutbound("STEAM_2171440") }

        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
    }

    @Test
    fun syncInbound_wineNewer_callsStrategyInbound() {
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
        runBlocking { service.syncInbound("STEAM_2171440") }

        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SnackbarManager.show(any()) }
    }

    @Test
    fun syncInbound_wineUnchangedSinceLastSync_skipsStrategyCall() {
        // gate compares wine-newest mtime to the lastApplied marker, NOT to the webview side: chromium touches
        // LOG/MANIFEST on open, which would trip a webview-vs-wine comparison.
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

    @Test
    fun resolveSetup_nullAppInfo_silentNoOp_noSnackbar() {
        // null appInfo = non-Steam container or steam app not yet cached: silent no-op. without this gate it
        // falls through to SaveDirectoryResolver.resolve, which throws PathMissing for legitimate containers.
        every { SteamService.getAppInfoOf(any<Int>()) } returns null

        // wine-side files get syncInbound past the empty-dir guard to resolveSetup, where the branch fires
        val wineDir = File(fakeContainer.installPath, "www/save").apply { mkdirs() }
        File(wineDir, "file1.rpgsave").apply { writeText("fake"); setLastModified(System.currentTimeMillis()) }

        service.markActive("STEAM_2171440")

        runBlocking { service.syncInbound("STEAM_2171440") }
        runBlocking { service.mirrorOnFlip("STEAM_2171440", Html5SaveSyncService.FlipDirection.WEBVIEW_TO_WINE) }
        runBlocking { service.mirrorOnFlip("STEAM_2171440", Html5SaveSyncService.FlipDirection.WINE_TO_WEBVIEW) }

        verify(exactly = 0) { SnackbarManager.show(any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncOutbound(any(), any()) }
    }

    @Test
    fun resolveSetup_emptyUfs_surfacesUnsupportedGameSnackbarOncePerLaunch() {
        // empty UFS = Steam has no cloud config for the game: no-op + at-most-once info snackbar, fired BEFORE
        // pack profile resolve.
        every { SteamService.getAppInfoOf(2171440) } returns SteamApp(
            id = 2171440,
            name = "FelvidekShape",
            ufs = UFS(saveFilePatterns = emptyList()),
        )

        // wine-side files get syncInbound past the empty-dir guard to resolveSetup, where the branch fires
        val wineDir = File(fakeContainer.installPath, "www/save").apply { mkdirs() }
        File(wineDir, "file1.rpgsave").apply { writeText("fake"); setLastModified(System.currentTimeMillis()) }

        service.markActive("STEAM_2171440")

        runBlocking { service.syncInbound("STEAM_2171440") }
        runBlocking { service.syncInbound("STEAM_2171440") }

        verify(exactly = 1) {
            SnackbarManager.show(match<String> { it.contains("Steam Cloud", ignoreCase = true) })
        }
        verify(exactly = 0) {
            SnackbarManager.show(match<String> { it.contains("save path", ignoreCase = true) })
        }
        verify(exactly = 0) { SaveSyncStrategy.RmmvFilesystem.syncInbound(any(), any()) }
    }

    @Test
    fun resolveSetup_emptyUfs_offline_suppressesUnsupportedSnackbar() {
        // offline, "doesn't support Steam Cloud" is misleading: cached SteamApp.ufs may just not be populated yet.
        // the once-flag still flips so a mid-launch reconnect doesn't re-open the surface.
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
        // generic "Save sync failed" is the bucket most likely to be a swallowed network throw, so it's gated
        // offline. device-side failures (corruption/lock/missing/permission/incompatible) are NOT gated.
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
        // Steam advertises UFS but our pack profile resolve fails: a REAL gap on our side, so surface it loudly
        // (PathMissing), unlike the graceful empty-UFS path.
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

        verify(atLeast = 1) {
            SnackbarManager.show(match<String> { it.contains("save path", ignoreCase = true) })
        }
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

    // guards against origin-derivation drift between WebViewOrigin and SaveDirectoryResolver.
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
        val c = Container("GOG_12345")
        val resolved = runBlocking { service.resolveCloudSourceForContainer(c) }
        assertEquals("GOG_12345", (resolved as? CloudSource.GogRemoteConfig)?.appId)
    }

    @Test
    fun resolveCloudSourceForContainer_steamWithNonIntSuffix_returnsNull() {
        val c = Container("STEAM_notanumber")
        assertNull(runBlocking { service.resolveCloudSourceForContainer(c) })
    }

    @Test
    fun start_idempotent_secondCallNoOps() {
        service.start()
        service.start()
        // a duplicate handler would dispatch twice
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

    // LS keys use the URL form, IDB dirs the filename form: http://<safeId>.localhost:<port> ->
    // http_<safeId>.localhost_<port>.
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
                        // a real port, not chromium's "_0" default-port suffix
                        origins.webViewOriginFilename.substringAfterLast("_").toIntOrNull()?.let { it > 0 } == true
                },
            )
        }
    }

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

    // every consumer (resolver, rewriter) must derive the identical leveldb filename from a containerId.
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
        val captured = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= android.util.Log.WARN) captured += "[$tag] $message"
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val resolved = runBlocking { service.resolveCloudSourceForContainer(Container("STEAM_3333333")) }
            assertTrue(resolved is CloudSource.GreenworksCloud)
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
        // base64 of "abc123"
        every { mockBridge.consumeGreenworksOutboundSnapshot() } returns
            """{"cookieClickerSave.txt":"YWJjMTIz"}"""
        coEvery { GreenworksCloudClient.upload(1454400, any()) } returns
            GreenworksCloudClient.UploadResult(success = true, filesUploaded = 1, bytesUploaded = 6L)

        service.markActive("STEAM_1454400", "pack:electron")
        service.setActiveSteamworksBridge(mockBridge)

        // launch inbound first: outbound only uploads what a greenworks inbound restored.
        runBlocking { service.syncInbound("STEAM_1454400") }
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

    // inbound only hands the cloud bytes to the bridge; steamworks.js writes them to localStorage at parse
    // time (the page isn't loaded yet when inbound runs, so Kotlin can't).
    @Test
    fun syncInbound_greenworks_cachesCloudFilesOnBridge() {
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
        val files = listOf("cookieClickerSave.txt" to "abc123".toByteArray())
        coEvery { GreenworksCloudClient.download(1454400) } returns files

        service.markActive("STEAM_1454400", "pack:electron")
        service.setActiveSteamworksBridge(mockBridge)

        runBlocking { service.syncInbound("STEAM_1454400") }

        io.mockk.verify(exactly = 1) { mockBridge.setInboundCloudFiles(files) }
    }

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
        // a failed inbound must keep outbound from overwriting cloud with whatever the session accumulated
        coEvery { GreenworksCloudClient.download(1454400) } throws RuntimeException("network down")

        service.markActive("STEAM_1454400", "pack:electron")
        service.setActiveSteamworksBridge(io.mockk.mockk(relaxed = true))

        runBlocking { service.syncInbound("STEAM_1454400") }
        runBlocking { service.syncOutbound("STEAM_1454400") }

        coVerify(exactly = 0) { GreenworksCloudClient.upload(any(), any()) }
    }

    @Test
    fun syncOutbound_greenworks_suppressedWhenContainerBecameGreenworksMidSession() {
        // a reinstall wipes greenworksCloudObserved, so launch resolves SteamUfs and restores NOTHING; the game's
        // first greenworks call re-persists the flag mid-session, so exit would resolve GreenworksCloud and upload
        // the fresh-game save over the real one. outbound must stay shut when inbound never ran.
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
        every { mockBridge.consumeGreenworksOutboundSnapshot() } returns
            """{"cookieClickerSave.txt":"YWJjMTIz"}"""

        service.markActive("STEAM_1454400", "pack:electron")
        service.setActiveSteamworksBridge(mockBridge)

        // no syncInbound this session -- the flag only became true after launch.
        runBlocking { service.syncOutbound("STEAM_1454400") }

        coVerify(exactly = 0) { GreenworksCloudClient.upload(any(), any()) }
    }

    @Test
    fun syncOutbound_greenworks_uploadsStagedFileBytesVerbatim() {
        // saveFilesToCloud stages a FILE's raw bytes on the bridge rather than the gn:gw:* text namespace:
        // the localStorage snapshot uploads as btoa(utf8(value)), which inflates every byte >= 0x80 and leaves
        // a desktop Steam client unable to read the save back.
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
        val rawBytes = byteArrayOf(0x00, 0xac.toByte(), 0x10, 0xd8.toByte(), 0xff.toByte(), 0x41)
        val mockBridge = io.mockk.mockk<app.gamenative.html5.shim.SteamworksJsBridge>(relaxed = true)
        every { mockBridge.consumeStagedCloudFiles() } returns mapOf("save0.dat" to rawBytes)
        every { mockBridge.consumeGreenworksOutboundSnapshot() } returns "{}"
        coEvery { GreenworksCloudClient.upload(1454400, any()) } returns
            GreenworksCloudClient.UploadResult(success = true, filesUploaded = 1, bytesUploaded = 6L)

        service.markActive("STEAM_1454400", "pack:electron")
        service.setActiveSteamworksBridge(mockBridge)
        runBlocking { service.syncInbound("STEAM_1454400") }
        runBlocking { service.syncOutbound("STEAM_1454400") }

        coVerify(exactly = 1) {
            GreenworksCloudClient.upload(
                appId = 1454400,
                files = match { list ->
                    list.size == 1 && list[0].first == "save0.dat" && list[0].second.contentEquals(rawBytes)
                },
            )
        }
    }

    @Test
    fun syncOutbound_greenworks_stagedFileBeatsTheTextSnapshotOnTheSameName() {
        // same basename in both channels: the staged bytes are the file, the snapshot's are a
        // utf8 re-encoding of a string, so the file must win and must not be uploaded twice.
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
        val rawBytes = byteArrayOf(0xff.toByte(), 0x41)
        val mockBridge = io.mockk.mockk<app.gamenative.html5.shim.SteamworksJsBridge>(relaxed = true)
        every { mockBridge.consumeStagedCloudFiles() } returns mapOf("save0.dat" to rawBytes)
        // base64 of "abc123" under the SAME name
        every { mockBridge.consumeGreenworksOutboundSnapshot() } returns
            """{"save0.dat":"YWJjMTIz"}"""
        coEvery { GreenworksCloudClient.upload(1454400, any()) } returns
            GreenworksCloudClient.UploadResult(success = true, filesUploaded = 1, bytesUploaded = 2L)

        service.markActive("STEAM_1454400", "pack:electron")
        service.setActiveSteamworksBridge(mockBridge)
        runBlocking { service.syncInbound("STEAM_1454400") }
        runBlocking { service.syncOutbound("STEAM_1454400") }

        coVerify(exactly = 1) {
            GreenworksCloudClient.upload(
                appId = 1454400,
                files = match { list ->
                    list.size == 1 && list[0].second.contentEquals(rawBytes)
                },
            )
        }
    }

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
