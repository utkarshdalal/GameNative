package app.gamenative.html5.savesync

import android.content.Context
import android.util.Base64
import android.webkit.WebView
import app.gamenative.NetworkMonitor
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.html5.host.WebViewOrigin
import app.gamenative.html5.host.WebViewScreenViewModel
import app.gamenative.html5.savesync.OriginCodec
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.ProfileRegistry
import app.gamenative.html5.shim.SteamworksJsBridge
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import app.gamenative.ui.util.SnackbarManager
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId
import app.gamenative.data.GameSource

// central save-sync orchestrator wired to three runtime boundaries:
// - exit (WebView → Wine): event-bus subscriber on AndroidEvent.WebViewDestroyed
// - launch (Wine → WebView): syncInbound() called pre-loadUrl by WebViewScreen, mtime-gated
// - flip (both directions): mirrorOnFlip() called pre-variant-write by ContainerUtils
// strategy dispatch via SaveSyncStrategy.forProfile; paths via SaveDirectoryResolver.
// never throws from public entry points -- every failure runs through handleFailure
// (snackbar + Timber.e); gameplay exit/launch/flip MUST proceed regardless.
// start() subscribed once from PluviaApp.onCreate; stop() exists for tests.
@Singleton
class Html5SaveSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // app-scope lives for process lifetime; same shape Html5InstallWatcher uses.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var subscribed: Boolean = false

    // tracks which container is currently running in webview. WebViewScreen.LaunchedEffect sets
    // this before loadUrl; the event-bus handler snapshots it on WebViewDestroyed before
    // onDispose clears (see handleExitSync -- the val-snapshot beats any clearActive race).
    @Volatile
    private var activeContainerId: String? = null

    // at-most-once snackbar for "this game doesn't support Steam Cloud" -- inbound + outbound +
    // flip can all hit the unsupported branch, only the first surfaces UI. cleared by clearActive.
    @Volatile
    private var unsupportedSnackbarShown: Boolean = false

    // cached engineProfile id for the active container, passed through from WebViewScreen at
    // LaunchedEffect time so resolveSetup needn't round-trip through WebViewContainer.load(slug).
    // closes a launch-time race where the slug load returned empty before inbound sync fired.
    @Volatile
    private var activeEngineProfileId: String = ""

    // per-app inbound-failure flag. set by handleFailure on inbound failure; cleared on
    // successful inbound. consulted by syncOutbound -- if set, outbound is suppressed so we
    // don't propagate iq80's failed-open recovery garbage (LOCK / fresh MANIFEST / log) to
    // cloud, which contaminates the snapshot for both Android and desktop. session-scoped
    // (process restart resets), so a clean relaunch can retry inbound from scratch.
    private val inboundFailedThisSession = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // true when syncInbound actually restored cloud bytes onto the wine prefix (wine > lastApplied)
    // that OPFS doesn't know about yet. opfs-hydrate-inbound reads it via
    // OpfsMirrorBridge.shouldOverwriteOnHydrate() to switch SKIP-IF-EXISTS → OVERWRITE; otherwise a
    // stale first-launch OPFS file survives forever. cleared by clearActive so each launch re-evaluates.
    @Volatile
    private var wineHasFreshBytes: Boolean = false

    fun getWineHasFreshBytes(): Boolean = wineHasFreshBytes

    // wine save dir for OpfsMirror containers, so the bridge writes OPFS bytes to the same path
    // GOG/Steam cloud scans on upload. populated by pullInstallToOpfs; null until inbound completes
    // (bridge falls back to installPath).
    @Volatile
    private var activeMirrorRoot: File? = null

    // WebView + bridge for greenworks-only paths, populated alongside markActive, nulled by
    // clearActive. syncInboundGreenworks needs the WebView to evaluateJavascript before loadUrl;
    // syncOutboundGreenworks needs the bridge to read the captured snapshot post-WebViewDestroyed.
    @Volatile
    private var activeWebView: WebView? = null

    @Volatile
    private var activeSteamworksBridge: SteamworksJsBridge? = null

    // captured as a val so stop()'s off() matches the same listener reference.
    // runs outbound SYNCHRONOUSLY (runBlocking) so the caller's emit() blocks until the
    // webview → wine write (+ LOCK cleanup) completes. otherwise MainViewModel.exitSteamApp
    // kicks off SteamAutoCloud.syncUserFiles in parallel, which scans wine mid-write and
    // captures transient iq80 LOCK files for cloud upload. the post-destroy emit point in
    // WebViewScreen.onDispose is on Main; blocking here is acceptable during teardown --
    // UI is already gone, and syncOutbound is bounded (~1s for typical save-store sizes).
    private val onWebViewDestroyed: (AndroidEvent.WebViewDestroyed) -> Unit = { _ ->
        val snapshot = activeContainerId
        if (snapshot == null) {
            Timber.tag(TAG).w("WebViewDestroyed fired with no active containerId — exit-sync skipped")
        } else {
            runBlocking(Dispatchers.IO) { syncOutbound(snapshot) }
        }
    }

    fun start() {
        if (subscribed) return
        if (PluviaApp.html5RuntimeDisabled) {
            Timber.tag(TAG).d("html5 runtime disabled — Html5SaveSyncService not subscribing")
            return
        }
        PluviaApp.events.on<AndroidEvent.WebViewDestroyed, Unit>(onWebViewDestroyed)
        subscribed = true
        Timber.tag(TAG).i("subscribed to WebViewDestroyed")
    }

    // visible-for-testing + symmetric cleanup. production never calls this.
    fun stop() {
        if (!subscribed) return
        PluviaApp.events.off<AndroidEvent.WebViewDestroyed, Unit>(onWebViewDestroyed)
        subscribed = false
        Timber.tag(TAG).i("unsubscribed from WebViewDestroyed")
    }

    // ---------- active-container tracking (WebViewScreen hooks) ----------

    fun markActive(appId: String) {
        activeContainerId = appId
        // back-compat: callers that don't know the engineProfile yet keep prior behavior
        // (resolveSetup falls back to disk load). new overload below is the preferred path
        // for the WebView lifecycle part-C).
        activeEngineProfileId = ""
        unsupportedSnackbarShown = false
    }

    // part-C: pass the already-loaded engineProfile so the first inbound sync at
    // launch doesn't race the WebViewContainer disk-load. WebViewScreen has the value in
    // hand at LaunchedEffect time (loaded.container.engineProfile) -- pass it through.
    fun markActive(appId: String, engineProfileId: String) {
        activeContainerId = appId
        activeEngineProfileId = engineProfileId
        unsupportedSnackbarShown = false
    }

    fun clearActive() {
        activeContainerId = null
        activeEngineProfileId = ""
        unsupportedSnackbarShown = false
        activeMirrorRoot = null
        wineHasFreshBytes = false
        // drop WebView + bridge refs symmetrically with activeContainerId.
        activeWebView = null
        activeSteamworksBridge = null
    }

    // ---------- public entry points ----------

    // launch-sync . called pre-loadUrl from WebViewScreen. gated on whether wine-side
    // mtimes have advanced past the last successfully-applied snapshot -- NOT against webview
    // mtimes. webview leveldb gets touched by chromium on open (LOG/MANIFEST), racing the
    // launch flow and falsely reporting "webview newer". marker-based gate is race-free.
    suspend fun syncInbound(appId: String) = withContext(Dispatchers.IO) {
        try {
            // greenworks short-circuit BEFORE resolveSetup runs. greenworks
            // bytes don't live on the wine prefix -- UFS-pattern walk doesn't apply.
            // resolve the source via container lookup (mirrors resolveSetup's first step
            // but skip the rest).
            val container = ContainerManager(context).getContainerById(appId)
            if (container != null) {
                val source = resolveCloudSourceForContainer(container)
                if (source is CloudSource.GreenworksCloud) {
                    syncInboundGreenworks(appId)
                    inboundFailedThisSession.remove(appId)
                    return@withContext
                }
            }

            // existing UFS path -- unchanged.
            val setup = resolveSetup(appId) ?: return@withContext
            val wineNewest = newestFileMtime(setup.paths.wine.userDataRoot)
            if (wineNewest == 0L) {
                Timber.tag(TAG).d("launch-sync skipped: no wine-side files for appId=%s", appId)
                return@withContext
            }
            val lastApplied = readLastAppliedMtime(appId)
            if (wineNewest <= lastApplied) {
                Timber.tag(TAG).d(
                    "launch-sync skipped: wine unchanged since last sync (wine=%d, lastApplied=%d) for appId=%s",
                    wineNewest, lastApplied, appId,
                )
                return@withContext
            }
            Timber.tag(TAG).d(
                "launch-sync running: wine advanced (wine=%d > lastApplied=%d) for appId=%s",
                wineNewest, lastApplied, appId,
            )
            runSync(appId, setup, Direction.INBOUND)
            writeLastAppliedMtime(appId, newestFileMtime(setup.paths.wine.userDataRoot))
            inboundFailedThisSession.remove(appId)
            // wine just got fresh bytes (cloud restored OR external write). signal opfs-
            // hydrate-inbound to OVERWRITE OPFS instead of SKIP-IF-EXISTS, so a stale OPFS
            // settings.cfg with empty profile slots gets replaced by the populated wine copy.
            wineHasFreshBytes = true
        } catch (t: Throwable) {
            inboundFailedThisSession.add(appId)
            handleFailure(t, direction = Direction.INBOUND, appId = appId)
        }
    }

    // always-pull launch direction -- install dir is canonical between
    // sessions, OPFS is ephemeral. called from WebViewScreen LaunchedEffect AFTER syncInbound
    // (so wine-side state is current before mirror copies install→OPFS). the JS-side hydration
    // runs from worker bootstrap worker-bootstrap.js) calling __gnOpfsMirrorBridge;
    // this function is the kotlin observability gate so logcat shows the boundary.
    
    // OBSERVABILITY-ONLY: short-circuits if active strategy isn't OpfsMirror. byte movement
    // is in JS context -- see OpfsMirrorBridge.{listInstallFiles,readInstallFile,writeInstallFile}.
    suspend fun pullInstallToOpfs(appId: String) = withContext(Dispatchers.IO) {
        try {
            val setup = resolveSetup(appId) ?: return@withContext
            if (SaveSyncStrategy.forProfile(setup.profile) !is SaveSyncStrategy.OpfsMirror) {
                return@withContext
            }
            // cloud-fix: cache wine save dir for the bridge to flush into. cloud sync scans
            // setup.paths.wine.userDataRoot -- bytes mirrored there get uploaded next cycle.
            activeMirrorRoot = setup.paths.wine.userDataRoot
            Timber.tag("Html5WorkerShim").i(
                "pullInstallToOpfs: staged appId=%s mirrorRoot=%s",
                appId, setup.paths.wine.userDataRoot.absolutePath,
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "pullInstallToOpfs failed appId=%s", appId)
        }
    }

    fun getActiveMirrorRoot(): File? = activeMirrorRoot

    // WebViewScreen.LaunchedEffect calls this once per launch alongside markActive.
    // matches the markActive(appId, engineProfileId) overload for parameter-discoverability;
    // separate setter so non-html5 paths aren't churned. cleared by clearActive.
    fun setActiveWebView(webView: WebView, bridge: SteamworksJsBridge) {
        activeWebView = webView
        activeSteamworksBridge = bridge
    }

    // exit-boundary flush gate. the actual OPFS→install hydration runs via
    // webView.evaluateJavascript in WebViewScreen.onDispose (it has the webView ref);
    // this method is a logging gate so device grep Html5WorkerShim:V for boundary
    // timing. short-circuits if active strategy isn't OpfsMirror.
    suspend fun flushOpfsToInstall(appId: String) = withContext(Dispatchers.IO) {
        try {
            val setup = resolveSetup(appId) ?: return@withContext
            if (SaveSyncStrategy.forProfile(setup.profile) !is SaveSyncStrategy.OpfsMirror) {
                return@withContext
            }
            Timber.tag("Html5WorkerShim").i(
                "flushOpfsToInstall: requested appId=%s installPath=%s",
                appId, setup.container.installPath,
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "flushOpfsToInstall failed appId=%s", appId)
        }
    }

    // variant-flip mirror-sync . unconditional -- ignores mtimes.
    // WEBVIEW_TO_WINE runs strategy.syncOutbound; WINE_TO_WEBVIEW runs strategy.syncInbound.
    suspend fun mirrorOnFlip(appId: String, direction: FlipDirection) = withContext(Dispatchers.IO) {
        val internalDir = when (direction) {
            FlipDirection.WEBVIEW_TO_WINE -> Direction.OUTBOUND
            FlipDirection.WINE_TO_WEBVIEW -> Direction.INBOUND
        }
        try {
            val setup = resolveSetup(appId) ?: return@withContext
            runSync(appId, setup, internalDir)
        } catch (t: Throwable) {
            handleFailure(t, direction = internalDir, appId = appId)
        }
    }

    // greenworks pre-launch eager seed. mirrors Html5AchievementSeed shape:
    // download cloud bytes BEFORE webView.loadUrl; inject via evaluateJavascript('localStorage.setItem(...)').
    // base64-of-utf-8 marshalling avoids surrogate-pair corruption.
    
    // failure-soft: any exception logs + adds to inboundFailedThisSession (gate for outbound).
    // first-launch path (no cloud bytes yet): manifest is empty, evaluateJavascript is a no-op.
    private suspend fun syncInboundGreenworks(
        appId: String,
    ) = withContext(Dispatchers.IO) {
        val numericAppId = GameSource.STEAM.idOf(appId).toIntOrNull()
        if (numericAppId == null) {
            Timber.tag("Html5GreenworksCloud").d(
                "syncInboundGreenworks: appId not STEAM_<int>: %s — skipping",
                appId,
            )
            return@withContext
        }
        val webView = activeWebView
        if (webView == null) {
            Timber.tag("Html5GreenworksCloud").i(
                "syncInboundGreenworks: no active webView (was setActiveWebView called?) — skipping for appId=%s",
                appId,
            )
            return@withContext
        }
        val files = GreenworksCloudClient.download(numericAppId)
        // cache on the bridge so steamworks.js's inline restore (parse-time, correct origin)
        // can apply the bytes to localStorage. webView.evaluateJavascript at this point
        // writes to about:blank's localStorage -- the WebView hasn't navigated to the game's
        // origin yet (loadUrl is gated on this function's completion via saveSyncInboundComplete).
        // navigating early to write localStorage racy + adds a frame; cached-bytes-via-shim
        // is the cleanest path: writes hit the game's localStorage at parse time of the
        // actual page, before any game JS reads it.
        activeSteamworksBridge?.setInboundCloudFiles(files)
        if (files.isEmpty()) {
            Timber.tag("Html5GreenworksCloud").i(
                "INBOUND n=0 (cloud empty for appId=%s)",
                appId,
            )
            return@withContext
        }
        val totalBytes = files.sumOf { it.second.size.toLong() }
        Timber.tag("Html5GreenworksCloud").i(
            "INBOUND n=%d bytes=%d appId=%s",
            files.size, totalBytes, appId,
        )
        // build one big evaluateJavascript that loops setItem per file. base64-encode bytes
        // here on the kotlin side; JS atob's into a real string on the LS side.
        // localStorage.setItem stores strings -- bytes that round-tripped via base64 from
        // desktop greenworks decode back to the same string the desktop wrote.
        val sb = StringBuilder("(function(){")
        files.forEach { (filename, bytes) ->
            // strip control chars / quotes from filename -- V5 validation ran upstream in
            // GreenworksCloudClient.download but defense-in-depth for the JS scope.
            val safeName = filename.replace("'", "").replace("\\", "")
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            sb.append("try{localStorage.setItem('gn:gw:")
                .append(safeName)
                .append("',atob('")
                .append(b64)
                .append("'));}catch(e){}")
        }
        sb.append("})();")
        val js = sb.toString()
        // evaluateJavascript must run on Main thread per WebView API contract.
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(js, null)
        }
    }

    // greenworks boundary outbound. captured the LS snapshot via
    // SteamworksJsBridge.captureGreenworksOutboundSnapshot BEFORE webView.destroy(). this
    // helper consumes the snapshot post-WebViewDestroyed and uploads via JavaSteam.
    
    // honors inboundFailedThisSession gate symmetrically with the UFS path:
    // syncOutbound (the public entry) checks the gate BEFORE this helper runs, so we
    // don't need to re-check here.
    private suspend fun syncOutboundGreenworks(
        appId: String,
    ) = withContext(Dispatchers.IO) {
        val numericAppId = GameSource.STEAM.idOf(appId).toIntOrNull()
        if (numericAppId == null) {
            Timber.tag("Html5GreenworksCloud").d(
                "syncOutboundGreenworks: appId not STEAM_<int>: %s — skipping",
                appId,
            )
            return@withContext
        }
        val bridge = activeSteamworksBridge
        if (bridge == null) {
            Timber.tag("Html5GreenworksCloud").i(
                "syncOutboundGreenworks: no active bridge — skipping for appId=%s",
                appId,
            )
            return@withContext
        }
        val snapshotJson = bridge.consumeGreenworksOutboundSnapshot()
        if (snapshotJson.isNullOrEmpty()) {
            Timber.tag("Html5GreenworksCloud").w(
                "syncOutboundGreenworks: no captured snapshot — capture didn't run? skipping for appId=%s",
                appId,
            )
            return@withContext
        }
        // parse {"<filename>":"<base64-bytes>"} → List<Pair<String, ByteArray>>.
        val files = mutableListOf<Pair<String, ByteArray>>()
        val obj = runCatching { JSONObject(snapshotJson) }.getOrNull()
        if (obj == null) {
            Timber.tag("Html5GreenworksCloud").w(
                "syncOutboundGreenworks: snapshot JSON malformed — len=%d appId=%s",
                snapshotJson.length, appId,
            )
            return@withContext
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val b64 = obj.optString(name, "")
            if (b64.isEmpty()) continue
            val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: continue
            files += name to bytes
        }
        if (files.isEmpty()) {
            Timber.tag("Html5GreenworksCloud").i(
                "OUTBOUND n=0 (snapshot empty) appId=%s",
                appId,
            )
            return@withContext
        }
        val totalBytes = files.sumOf { it.second.size.toLong() }
        Timber.tag("Html5GreenworksCloud").i(
            "OUTBOUND n=%d bytes=%d appId=%s",
            files.size, totalBytes, appId,
        )
        val result = GreenworksCloudClient.upload(numericAppId, files)
        Timber.tag("Html5GreenworksCloud").i(
            "OUTBOUND done ok=%s filesUploaded=%d bytesUploaded=%d appId=%s",
            result.success, result.filesUploaded, result.bytesUploaded, appId,
        )
        // invalidate the bridge's cached quota on a successful upload
        // so the next getCloudQuota call refetches the live manifest. cheaper than a wall-clock
        // TTL -- quota only changes when WE upload.
        if (result.success) {
            bridge.invalidateCloudQuotaCache()
        }
        if (!result.success) {
            // route through the existing failure path so SnackbarManager surfaces the issue
            // per / no-toasts rule.
            handleFailure(
                RuntimeException("greenworks upload reported failure"),
                direction = Direction.OUTBOUND,
                appId = appId,
            )
        }
    }

    // ---------- internal (event-bus path) ----------

    // exit-sync . always runs on WebViewDestroyed, no mtime check.
    // also bumps lastApplied marker so the NEXT inbound gate (which sees outbound-touched
    // wine files as "newer") doesn't run a redundant inbound that just round-trips our own data.
    internal suspend fun syncOutbound(appId: String) = withContext(Dispatchers.IO) {
        // gate on inbound success this session. if inbound failed, our wine prefix is in an
        // unknown post-failure state -- running outbound would write bad bytes (or nothing) to
        // cloud, contaminating the snapshot. hold off until process restart + clean inbound.
        if (appId in inboundFailedThisSession) {
            Timber.tag(TAG).w(
                "outbound suppressed: prior inbound failed this session for appId=%s — " +
                    "cloud upload skipped to prevent corruption-amplification. " +
                    "restart app to retry inbound.",
                appId,
            )
            return@withContext
        }
        try {
            // greenworks short-circuit BEFORE resolveSetup runs.
            val container = ContainerManager(context).getContainerById(appId)
            if (container != null) {
                val source = resolveCloudSourceForContainer(container)
                if (source is CloudSource.GreenworksCloud) {
                    syncOutboundGreenworks(appId)
                    return@withContext
                }
            }

            val setup = resolveSetup(appId) ?: return@withContext
            runSync(appId, setup, Direction.OUTBOUND)
            writeLastAppliedMtime(appId, newestFileMtime(setup.paths.wine.userDataRoot))
        } catch (t: Throwable) {
            handleFailure(t, direction = Direction.OUTBOUND, appId = appId)
        }
    }

    // ---------- shared body ----------

    private enum class Direction { OUTBOUND, INBOUND }

    enum class FlipDirection { WEBVIEW_TO_WINE, WINE_TO_WEBVIEW }

    // result of per-sync setup -- resolved once, reused by whichever direction runs.
    private data class SyncSetup(
        val container: Container,
        val profile: EngineProfile,
        val source: CloudSource,
        val paths: SaveDirectoryResolver.SavePathPair,
        val strategy: SaveSyncStrategy,
        val origins: Origins,
    )

    // resolve container + profile + paths + strategy. null = legitimate no-op (e.g. no container
    // yet) -- caller logs + bails without surfacing a user-visible failure. throws SaveSyncFailure
    // for conditions that MUST surface (missing profile for a live webview session, etc.).
    private suspend fun resolveSetup(appId: String): SyncSetup? {
        val containerManager = ContainerManager(context)
        if (!containerManager.hasContainer(appId)) {
            Timber.tag(TAG).d("no container yet for appId=%s — sync no-op", appId)
            return null
        }
        val container = containerManager.getContainerById(appId)
            ?: run {
                Timber.tag(TAG).w("ContainerManager.getContainerById returned null for %s", appId)
                return null
            }

        val source = resolveCloudSourceForContainer(container)

        // source == null = non-Steam non-GOG container (CUSTOM_GAME_*/EPIC_/AMAZON_/sideloaded)
        // OR steam app not yet cached. cloud sync not applicable -- silent no-op (user knows
        // the container is non-mapped), no snackbar. without this gate, execution falls through
        // to ProfileRegistry.resolveProfile + SaveDirectoryResolver.resolve, which can throw
        // PathMissing for legitimate non-mapped containers ("save path not found" symptom).
        if (source == null) {
            Timber.tag(TAG).i(
                "no cloud source for appId=%s — non-mapped or not-yet-cached container, sync no-op",
                appId,
            )
            return null
        }

        // authoritative "does this store advertise cloud for this game?" gate. for SteamUfs,
        // empty saveFilePatterns = Steam itself has no cloud config. for GogRemoteConfig, empty
        // wineSaveRoots = GOG remote-config has no entry. graceful no-op + at-most-once info
        // snackbar.
        if (!source.isSupported) {
            Timber.tag(TAG).i(
                "cloud source unsupported for appId=%s kind=%s — game has no cloud config",
                appId, source::class.simpleName,
            )
            handleUnsupportedGame(appId, engineId = "")
            return null
        }

        // slugFromAppId is the JSON-dir reverse-lookup (html5-containers/<slug>/
        // config.json), NOT the origin slug -- origin keys on `<safeId>.localhost` in WebViewScreen.
        // part-A: pack-only -- engineId is the persisted WebViewContainer.engineProfile
        // (set by Html5OptInService at fingerprint time). per-game profile JSONs removed.
        // part-C: prefer in-memory engineProfile (cached by markActive at WebView
        // launch BEFORE first sync fires). disk-load fallback survives for paths that
        // resolve setup outside the WebView lifecycle (mirrorOnFlip from ContainerUtils, tests).
        val cachedEngineId = activeEngineProfileId
        val engineId = cachedEngineId.ifEmpty {
            val webViewContainerSlug = WebViewScreenViewModel.slugFromAppId(appId)
            webViewContainerSlug
                ?.let { WebViewContainer.load(it)?.engineProfile }
                .orEmpty()
        }
        val profile = ProfileRegistry.resolveProfile(
            context = context,
            appId = appId,
            engineId = engineId,
        )
        if (profile == null) {
            // ufs patterns present (cloud-supported game) but pack profile resolve failed --
            // this is a real GameNative gap, not a game limitation. surface loudly so it
            // gets noticed and mapped. (the graceful "no cloud support" path is gated on
            // appInfo.ufs.saveFilePatterns.isEmpty() above, NOT on this branch.)
            throw SaveSyncFailure.PathMissing("no pack profile for appId=$appId engineId=$engineId")
        }

        // fsbridge titles write bytes directly to disk via
        // Html5FsBridge -- sync boundaries are no-ops. short-circuit BEFORE SaveDirectoryResolver
        // which would throw PathMissing on profiles whose saves block was stripped.
        // strategy resolution is cheap (reads profile.saves?.sync?.mechanism only).
        val earlyStrategy = SaveSyncStrategy.forProfile(profile)
        if (earlyStrategy is SaveSyncStrategy.FsBridge) {
            Timber.tag(TAG).d(
                "fsbridge strategy — sync no-op for appId=%s (bytes already on disk via Html5FsBridge)",
                appId,
            )
            return null
        }

        val paths = SaveDirectoryResolver.resolve(
            context = context,
            appId = appId,
            container = container,
            profile = profile,
            source = source,
        )

        val strategy = SaveSyncStrategy.forProfile(profile)

        // derive 4 origin shapes. URL forms drive LS rewriter; filename forms
        // drive IDB rewriter. webViewOriginUrl reconstructed from WebViewOrigin.hostFor so
        // OriginCodec.filenameFromUrl produces the SAME output as WebViewOrigin.levelDbPrefix
        // (drift lock asserted in Html5SaveSyncServiceTest.webViewOriginFilename_matchesWebViewOriginLevelDbPrefix_driftLock).
        val webViewOriginUrl = WebViewOrigin.originUrl(container.id)
        val webViewOriginFilename = OriginCodec.filenameFromUrl(webViewOriginUrl)

        // pcOrigin precedence: on-disk discovered > profile-declared. SaveDirectoryResolver's
        // wine-side IDB glob picks up the REAL chromium origin (e.g. Electron's
        // chrome-extension_<hash>_0), not the placeholder the pack JSON pins (file://).
        // LS + IDB key rewriters key off these origin strings: if we passed the placeholder,
        // zero leveldb keys would match and the webview would see empty stores after sync.
        val discoveredOriginFilename = paths.wine.indexedDbLevelDb?.name?.removeSuffix(".indexeddb.leveldb")
        val profilePcOriginUrl = profile.saves?.sync?.pcOrigin.orEmpty()
        val pcOriginFilename = discoveredOriginFilename
            ?: if (profilePcOriginUrl.isNotBlank()) OriginCodec.filenameFromUrl(profilePcOriginUrl) else ""
        val pcOriginUrl = if (discoveredOriginFilename != null) {
            runCatching { OriginCodec.urlFromFilename(discoveredOriginFilename) }.getOrDefault(profilePcOriginUrl)
        } else {
            profilePcOriginUrl
        }
        val origins = Origins(
            webViewOriginUrl = webViewOriginUrl,
            webViewOriginFilename = webViewOriginFilename,
            pcOriginUrl = pcOriginUrl,
            pcOriginFilename = pcOriginFilename,
        )

        return SyncSetup(
            container = container,
            profile = profile,
            source = source,
            paths = paths,
            strategy = strategy,
            origins = origins,
        )
    }

    private fun runSync(appId: String, setup: SyncSetup, direction: Direction) {
        val dirLabel = direction.name.lowercase()
        // fs-authoritative routing. titles that call Node fs (via Html5FsBridge) canonically
        // save to disk; their chromium LS/IDB is runtime scratch. swap LevelDbOriginRewrite for
        // FsBridge no-op so we don't burn the 10s CURRENT-poll on empty shells or upload
        // scratch bytes to cloud. gated by Html5FsAuthoritative.ROUTING_ENABLED so we can force
        // leveldb-rewrite for regression testing if this masks a real bug.
        // SaveSyncSpec.bypassFsBridgeReroute opts a title OUT of the reroute. Impact-class NW.js
        // titles write BOTH fs files (cc.save) AND chromium-profile leveldb on real desktop;
        // Galaxy's cross-device sync cross-validates the pair, so the dual-write must mirror
        // that shape. Default false keeps the safe FsBridge no-op posture for any other
        // fs-using title. Configured per-title via <pack>-patches.json byAppId override.
        val bypassFsBridgeReroute = setup.profile.saves?.sync?.bypassFsBridgeReroute == true
        val effectiveStrategy = if (
            setup.strategy is SaveSyncStrategy.LevelDbOriginRewrite &&
            !bypassFsBridgeReroute &&
            Html5FsAuthoritative.isFsAuthoritative(context, appId)
        ) {
            Timber.tag(TAG).i(
                "sync rerouting to fsbridge (fs-authoritative) direction=%s appId=%s originalMechanism=%s",
                dirLabel, appId, setup.strategy.mechanism,
            )
            SaveSyncStrategy.FsBridge
        } else {
            setup.strategy
        }
        Timber.tag(TAG).d(
            "sync begin direction=%s appId=%s mode=%s mechanism=%s",
            dirLabel,
            appId,
            setup.paths.syncMode,
            effectiveStrategy.mechanism,
        )
        when (direction) {
            Direction.OUTBOUND -> effectiveStrategy.syncOutbound(setup.paths, setup.origins)
            Direction.INBOUND -> effectiveStrategy.syncInbound(setup.paths, setup.origins)
        }
        Timber.tag(TAG).i(
            "sync ok direction=%s appId=%s mode=%s mechanism=%s",
            dirLabel,
            appId,
            setup.paths.syncMode,
            effectiveStrategy.mechanism,
        )
    }

    private fun newestFileMtime(dir: File?): Long {
        if (dir == null || !dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
    }

    // per-container marker file recording the wine mtime applied by the most recent
    // successful sync (inbound OR outbound). race-free replacement for the old
    // wine-vs-webview mtime gate, which lost to chromium touching leveldb LOG/MANIFEST
    // when WebView opened the database during launch.
    private fun markerFile(appId: String): File =
        syncStateMarkerFile(context, appId).also { it.parentFile?.mkdirs() }

    private fun readLastAppliedMtime(appId: String): Long {
        val f = markerFile(appId)
        if (!f.isFile) return 0L
        return runCatching { f.readText().trim().toLong() }.getOrDefault(0L)
    }

    private fun writeLastAppliedMtime(appId: String, mtime: Long) {
        runCatching { markerFile(appId).writeText(mtime.toString()) }
            .onFailure { Timber.tag(TAG).w(it, "failed to persist lastApplied mtime for %s", appId) }
    }

    // ---------- failure surface ----------

    private fun handleFailure(t: Throwable, direction: Direction, appId: String) {
        val failure = when (t) {
            is SaveSyncFailure -> t
            else -> SaveSyncFailure.Other(t)
        }
        // per lookup via when -- compile-checked + grep-friendly.
        val copy = context.getString(stringIdForKey(failure.userFacingKey))
        // offline: misleading to surface "saves may not reach Steam Cloud" -- user knows
        // there's no network. device-side failures (corruption/lock/missing/permission/
        // incompatible) still surface; only the generic "Other" is gated since it's the bucket
        // most likely to be a swallowed network throw. logs always fire.
        val gateOffline = failure.userFacingKey == "save_sync_other"
        if (!gateOffline || NetworkMonitor.hasInternet.value) {
            SnackbarManager.show(copy)
        }
        Timber.tag(TAG).e(
            t,
            "sync failed direction=%s appId=%s key=%s online=%s",
            direction.name.lowercase(),
            appId,
            failure.userFacingKey,
            NetworkMonitor.hasInternet.value,
        )
    }

    // keys match SaveSyncFailure.userFacingKey 1:1 + strings.xml <string name=...> entries 1:1.
    private fun stringIdForKey(key: String): Int = when (key) {
        "save_sync_lock" -> R.string.save_sync_lock
        "save_sync_corruption" -> R.string.save_sync_corruption
        "save_sync_missing" -> R.string.save_sync_missing
        "save_sync_permission" -> R.string.save_sync_permission
        "save_sync_incompatible" -> R.string.save_sync_incompatible
        else -> R.string.save_sync_other
    }

    // part-B: graceful path for "this game has no pack profile / no Steam Cloud
    // support" -- keep the legitimate save_sync_missing copy for known-supported games whose
    // on-disk save dir is corrupt / absent. log INFO (not ERROR) since this is not a bug.
    // at-most-once snackbar per launch via unsupportedSnackbarShown flag (cleared by
    // clearActive on WebView teardown).
    private fun handleUnsupportedGame(appId: String, engineId: String) {
        val online = NetworkMonitor.hasInternet.value
        Timber.tag(TAG).i(
            "save sync unsupported for appId=%s engineId=%s online=%s — game has no pack profile",
            appId, engineId, online,
        )
        // offline: cached SteamApp.ufs may be empty / pack:electron greenworks hailMary throws
        // → mis-routed as "this game doesn't support Steam Cloud". user knows there's no
        // network -- suppress the surface to avoid the misleading message. flag still flips so
        // a single misfire doesn't reopen on reconnect mid-launch.
        if (!unsupportedSnackbarShown) {
            unsupportedSnackbarShown = true
            if (online) {
                SnackbarManager.show(context.getString(R.string.save_sync_unsupported_game))
            }
        }
    }

    // ---------- helpers ----------

    // per-container-id dispatch to CloudSource. STEAM_ → SteamUfs (UFS-pattern walk); GOG_ →
    // GogRemoteConfig (remote-config save locations on the wine prefix); EPIC_ → EpicSavedGames
    // (saveFolder template expanded against wine prefix). CUSTOM_GAME_/AMAZON_/unknown return
    // null → resolveSetup short-circuits to no-op. Amazon has no cloud-save manager at all
    // (wine OR html5); CUSTOM_GAME by design ships LOCAL_ONLY.
    
    // suspend boundary added first-launch fix -- pack:electron containers without UFS
    // patterns get a one-shot greenworks probe (getAppFileListChange RPC) before falling
    // through to SteamUfs. on success, the persisted greenworksCloudObserved flag flips and
    // future calls return GreenworksCloud directly (no probe). closes the chicken-and-egg
    // where the snackbar fired on first launch even for titles that DID round-trip greenworks.
    internal suspend fun resolveCloudSourceForContainer(container: Container): CloudSource? {
        val id = container.id
        return when {
            GameSource.STEAM.matches(id) -> {
                val appIdInt = GameSource.STEAM.idOf(id).toIntOrNull() ?: return null
                val app = SteamService.getAppInfoOf(appIdInt) ?: return null
                // greenworks-observed containers route through
                // CloudSource.GreenworksCloud BEFORE the UFS path. file load is sub-ms
                // (config.json < 1KB); acceptable on resolveSetup hot path.
                val slug = WebViewScreenViewModel.slugFromAppId(id)
                val webViewContainer = slug?.let { WebViewContainer.load(it) }
                val greenworksObserved = webViewContainer?.greenworksCloudObserved == true
                if (greenworksObserved) {
                    // hybrid log -- surface BOTH-signals titles for follow-up review.
                    // greenworks still wins per architecture; just log so we know.
                    if (app.ufs.saveFilePatterns.isNotEmpty()) {
                        Timber.tag(TAG).w(
                            "hybrid cloud: container has BOTH UFS patterns AND greenworksCloudObserved" +
                                " — review for follow-up. appId=%s patterns=%d",
                            id, app.ufs.saveFilePatterns.size,
                        )
                    }
                    return CloudSource.GreenworksCloud(
                        appId = id,
                        container = container,
                        observed = true,
                    )
                }
                // first-launch hail-mary: pack:electron + no UFS patterns is a strong
                // greenworks signal (Cookie Clicker shape). probe Steam directly with a single
                // getAppFileListChange -- on success, persist the flag and route through
                // GreenworksCloud. on failure (offline / not authed / RPC throw), fall through
                // to SteamUfs and let the existing unsupported-snackbar path handle it.
                val rescued = attemptGreenworksHailMary(
                    appId = id,
                    appIdInt = appIdInt,
                    container = container,
                    slug = slug,
                    webViewContainer = webViewContainer,
                    hasUfsPatterns = app.ufs.saveFilePatterns.isNotEmpty(),
                )
                if (rescued != null) return rescued
                CloudSource.SteamUfs(steamApp = app, container = container)
            }
            GameSource.GOG.matches(id) -> CloudSource.GogRemoteConfig(context = context, appId = id)
            GameSource.EPIC.matches(id) -> CloudSource.EpicSavedGames(context = context, appId = id)
            else -> null
        }
    }

    // first-launch fix. probe Steam Cloud for [appIdInt] when the container is
    // pack:electron + has no UFS patterns. on success, persist greenworksCloudObserved=true
    // and return CloudSource.GreenworksCloud. only runs for the chicken-and-egg first-launch
    // case; subsequent launches see greenworksCloudObserved=true and skip this entirely.
    private suspend fun attemptGreenworksHailMary(
        appId: String,
        appIdInt: Int,
        container: Container,
        slug: String?,
        webViewContainer: WebViewContainer?,
        hasUfsPatterns: Boolean,
    ): CloudSource.GreenworksCloud? {
        // gate: only fire for pack:electron containers with no UFS patterns. RMMV/c3/etc.
        // don't typically use greenworks; UFS-using titles already work via the existing path.
        if (slug == null || webViewContainer == null) return null
        if (webViewContainer.engineProfile != EnginePackId.ELECTRON) return null
        if (hasUfsPatterns) return null
        Timber.tag(TAG).i(
            "hail-mary: probing greenworks for pack:electron appId=%s (no UFS patterns, flag not yet set)",
            appId,
        )
        val cloudOk = GreenworksCloudClient.probeCloud(appIdInt)
        if (!cloudOk) return null
        // persist the flag so subsequent launches skip the probe. file IO is sub-ms.
        val saveOk = runCatching {
            WebViewContainer.save(slug, webViewContainer.copy(greenworksCloudObserved = true))
        }
            .onFailure { Timber.tag(TAG).w(it, "hail-mary: persist failed for appId=%s", appId) }
            .isSuccess
        Timber.tag(TAG).i(
            "hail-mary: success appId=%s — flag persisted=%s, routing through GreenworksCloud",
            appId, saveOk,
        )
        return CloudSource.GreenworksCloud(
            appId = appId,
            container = container,
            observed = true,
        )
    }

    companion object {
        private const val TAG = "Html5SaveSyncService"

        // single source of truth for the per-app inbound-gate marker. owned here so the path
        // can't drift; ContainerUtils uninstall cleanup deletes it via clearSyncState.
        fun syncStateMarkerFile(context: Context, appId: String): File =
            File(File(context.filesDir, "html5/sync-state"), "$appId.lastApplied")

        // wipe the inbound-gate marker on container uninstall. WHY: a reinstall's cloud-restore
        // writes backdated mtimes, which read as "older" than a stale surviving marker, so the
        // launch-sync gate (wine <= lastApplied) wrongly skips inbound and the WebView reads
        // stale OPFS instead of the restored saves (title screen shows no Continue). no-op if absent.
        fun clearSyncState(context: Context, appId: String) {
            val f = syncStateMarkerFile(context, appId)
            if (f.exists() && f.delete()) {
                Timber.tag(TAG).i("clearSyncState: removed inbound-gate marker for %s", appId)
            }
        }
    }
}
