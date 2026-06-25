package app.gamenative.html5.savesync

import android.content.Context
import android.util.Base64
import app.gamenative.NetworkMonitor
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.html5.host.WebViewOrigin
import app.gamenative.html5.host.WebViewScreenViewModel
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId
import app.gamenative.data.GameSource

// save sync at three runtime boundaries:
// - exit (WebView -> Wine): on AndroidEvent.WebViewDestroyed
// - launch (Wine -> WebView): syncInbound() before loadUrl, gated on the lastApplied marker
// - runtime flip (either way): mirrorOnFlip() from ContainerUtils
// public entry points never throw -- failures go to handleFailure; exit/launch/flip MUST proceed.
@Singleton
class Html5SaveSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var subscribed: Boolean = false

    // the exit handler snapshots this into a val so a racing clearActive can't null it mid-sync.
    @Volatile
    private var activeContainerId: String? = null

    // inbound, outbound and flip can all hit "no cloud support"; only the first shows a snackbar.
    @Volatile
    private var unsupportedSnackbarShown: Boolean = false

    // passed in at launch: loading it from disk raced inbound sync and came back empty.
    @Volatile
    private var activeEngineProfileId: String = ""

    // after a failed inbound, outbound is suppressed: the Wine copy may hold iq80's failed-open
    // leftovers (LOCK / fresh MANIFEST / log), and uploading them corrupts the cloud save for every
    // device. process-scoped so a relaunch retries cleanly.
    private val inboundFailedThisSession = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // the cloud source can CHANGE mid-session: a game resolves to SteamUfs at launch (flag unset, no
    // restore), then its first greenworks call sets greenworksCloudObserved, so exit resolves to
    // GreenworksCloud and would overwrite the real cloud save with a fresh-game one. only upload what
    // we restored. NOT cleared by clearActive: outbound runs after it.
    private val greenworksInboundRanThisSession = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // inbound brought Wine bytes OPFS doesn't have yet: hydration must OVERWRITE, or a stale OPFS file
    // survives forever. per launch.
    @Volatile
    private var wineHasFreshBytes: Boolean = false

    fun getWineHasFreshBytes(): Boolean = wineHasFreshBytes

    // Wine save dir for OpfsMirror flushes -- where cloud upload scans. null until pullInstallToOpfs.
    @Volatile
    private var activeMirrorRoot: File? = null

    // greenworks only: inbound hands cloud bytes to steamworks.js through it, outbound reads the
    // snapshot captured before destroy.
    @Volatile
    private var activeSteamworksBridge: SteamworksJsBridge? = null

    // SYNCHRONOUS so emit() blocks until the Wine write (+ LOCK cleanup) is done; otherwise
    // exitSteamApp's cloud sync runs in parallel and uploads transient iq80 LOCK files. blocking Main
    // is acceptable here: the UI is already gone and outbound takes ~1s.
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

    // tests only.
    fun stop() {
        if (!subscribed) return
        PluviaApp.events.off<AndroidEvent.WebViewDestroyed, Unit>(onWebViewDestroyed)
        subscribed = false
        Timber.tag(TAG).i("unsubscribed from WebViewDestroyed")
    }

    fun markActive(appId: String) {
        activeContainerId = appId
        // resolveSetup falls back to a disk load; prefer the overload below.
        activeEngineProfileId = ""
        unsupportedSnackbarShown = false
    }

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
        activeSteamworksBridge = null
    }

    // gated on Wine mtimes vs the lastApplied marker, NOT vs WebView mtimes: chromium touches
    // LOG/MANIFEST on open, which falsely reads as "webview newer".
    // force skips the gate: an explicit "keep remote" download can carry cloud mtimes older than the marker.
    suspend fun syncInbound(appId: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            // greenworks bytes aren't in the Wine prefix; skip resolveSetup entirely.
            val container = ContainerManager(context).getContainerById(appId)
            if (container != null) {
                val source = resolveCloudSourceForContainer(container)
                if (source is CloudSource.GreenworksCloud) {
                    syncInboundGreenworks(appId)
                    inboundFailedThisSession.remove(appId)
                    return@withContext
                }
            }

            val setup = resolveSetup(appId) ?: return@withContext
            val wineNewest = newestFileMtime(setup.paths.wine.userDataRoot)
            if (wineNewest == 0L) {
                Timber.tag(TAG).d("launch-sync skipped: no wine-side files for appId=%s", appId)
                return@withContext
            }
            val lastApplied = readLastAppliedMtime(appId)
            if (!force && wineNewest <= lastApplied) {
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
            // GOG/Epic run inbound twice per launch (PluviaMain, then WebViewScreen): reuse a restore staged
            // but not yet applied instead of redoing the rewrites.
            val staged = pendingLsRestores[appId]
            if (!force && staged != null && staged.wineMtime == wineNewest) {
                staged.delivered = false
                inboundFailedThisSession.remove(appId)
                wineHasFreshBytes = true
                Timber.tag(TAG).d("launch-sync: localStorage restore already staged for appId=%s", appId)
                return@withContext
            }
            val effectiveStrategy = runSync(appId, setup, Direction.INBOUND)
            val appliedMtime = newestFileMtime(setup.paths.wine.userDataRoot)
            val lsEntries = if (effectiveStrategy is SaveSyncStrategy.LevelDbOriginRewrite) {
                LevelDbRewriter.readLsOriginEntries(setup.paths.wine.localStorageLevelDb, setup.origins.pcOriginUrl)
            } else {
                null
            }
            if (lsEntries != null) {
                stageLsRestore(appId, lsEntries, appliedMtime)
            } else {
                pendingLsRestores.remove(appId)
                writeLastAppliedMtime(appId, appliedMtime)
            }
            inboundFailedThisSession.remove(appId)
            wineHasFreshBytes = true
        } catch (t: Throwable) {
            inboundFailedThisSession.add(appId)
            handleFailure(t, direction = Direction.INBOUND, appId = appId)
        }
    }

    // runs AFTER syncInbound so the Wine side is current. moves no bytes itself -- the page hydrates
    // OPFS through OpfsMirrorBridge; this only resolves the Wine save dir the bridge should use.
    suspend fun pullInstallToOpfs(appId: String) = withContext(Dispatchers.IO) {
        try {
            val setup = resolveSetup(appId) ?: return@withContext
            if (SaveSyncStrategy.forProfile(setup.profile) !is SaveSyncStrategy.OpfsMirror) {
                return@withContext
            }
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

    fun setActiveSteamworksBridge(bridge: SteamworksJsBridge) {
        activeSteamworksBridge = bridge
    }

    // runtime flip: unconditional, ignores mtimes.
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

    // runs BEFORE loadUrl; steamworks.js writes the cached bytes to localStorage at parse time.
    // a throw propagates to syncInbound, which then blocks this session's outbound.
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
        val bridge = activeSteamworksBridge
        if (bridge == null) {
            Timber.tag("Html5GreenworksCloud").i(
                "syncInboundGreenworks: no active bridge (was setActiveSteamworksBridge called?) — skipping for appId=%s",
                appId,
            )
            return@withContext
        }
        val files = GreenworksCloudClient.download(numericAppId)
        // not evaluateJavascript: loadUrl waits on this function, so the WebView is still on
        // about:blank, where localStorage access throws SecurityError.
        bridge.setInboundCloudFiles(files)
        // an empty cloud counts: it's a real "nothing to restore", not a skipped restore.
        greenworksInboundRanThisSession.add(appId)
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
    }

    // uploads the LS snapshot captured before destroy(). syncOutbound already checked the inbound gates.
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
        // staged files stay raw bytes (never string-ified) so the cloud blob matches what desktop reads;
        // the LS snapshot carries the text namespace. both go in one upload.
        val stagedFiles = bridge.consumeStagedCloudFiles()
        val snapshotJson = bridge.consumeGreenworksOutboundSnapshot()
        if (snapshotJson.isNullOrEmpty() && stagedFiles.isEmpty()) {
            Timber.tag("Html5GreenworksCloud").w(
                "syncOutboundGreenworks: no captured snapshot — capture didn't run? skipping for appId=%s",
                appId,
            )
            return@withContext
        }
        // {"<filename>":"<base64-bytes>"}
        val files = mutableListOf<Pair<String, ByteArray>>()
        val obj = if (snapshotJson.isNullOrEmpty()) null else runCatching { JSONObject(snapshotJson) }.getOrNull()
        if (obj == null && !snapshotJson.isNullOrEmpty()) {
            Timber.tag("Html5GreenworksCloud").w(
                "syncOutboundGreenworks: snapshot JSON malformed — len=%d appId=%s",
                snapshotJson.length, appId,
            )
        }
        val keys = obj?.keys()
        while (keys?.hasNext() == true) {
            val name = keys.next()
            val b64 = obj.optString(name, "")
            if (b64.isEmpty()) continue
            val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: continue
            // a staged file wins: its bytes are the file's, the snapshot's would be utf8-inflated.
            if (name !in stagedFiles) files += name to bytes
        }
        stagedFiles.forEach { (name, bytes) -> files += name to bytes }
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
        // quota only changes when WE upload, so no TTL is needed.
        if (result.success) {
            bridge.invalidateCloudQuotaCache()
        }
        if (!result.success) {
            handleFailure(
                RuntimeException("greenworks upload reported failure"),
                direction = Direction.OUTBOUND,
                appId = appId,
            )
        }
    }

    // no mtime gate. bumps lastApplied so the next launch doesn't re-import our own write.
    internal suspend fun syncOutbound(appId: String) = withContext(Dispatchers.IO) {
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
            val container = ContainerManager(context).getContainerById(appId)
            if (container != null) {
                val source = resolveCloudSourceForContainer(container)
                if (source is CloudSource.GreenworksCloud) {
                    if (appId !in greenworksInboundRanThisSession) {
                        Timber.tag(TAG).w(
                            "outbound suppressed: greenworks inbound never ran this session for appId=%s — " +
                                "the container became greenworks mid-session, so uploading now would " +
                                "overwrite the cloud save with state we never restored. next launch " +
                                "restores first, then uploads.",
                            appId,
                        )
                        return@withContext
                    }
                    syncOutboundGreenworks(appId)
                    return@withContext
                }
            }

            val setup = resolveSetup(appId) ?: return@withContext
            // restore never applied by the page: the live store lacks the game's keys, and the rewrite
            // would overwrite the Wine copy with that.
            if (pendingLsRestores.containsKey(appId)) {
                Timber.tag(TAG).w("outbound skipped: localStorage restore not applied by the page appId=%s", appId)
                return@withContext
            }
            runSync(appId, setup, Direction.OUTBOUND)
            writeLastAppliedMtime(appId, newestFileMtime(setup.paths.wine.userDataRoot))
        } catch (t: Throwable) {
            handleFailure(t, direction = Direction.OUTBOUND, appId = appId)
        }
    }

    private enum class Direction { OUTBOUND, INBOUND }

    enum class FlipDirection { WEBVIEW_TO_WINE, WINE_TO_WEBVIEW }

    private data class SyncSetup(
        val container: Container,
        val profile: EngineProfile,
        val source: CloudSource,
        val paths: SaveDirectoryResolver.SavePathPair,
        val strategy: SaveSyncStrategy,
        val origins: Origins,
        val nwjsOriginDerived: Boolean = false,
    )

    // null = legitimate no-op, nothing surfaced. throws SaveSyncFailure for what MUST surface.
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

        // no cloud store (custom/Amazon) or Steam app info not cached yet: silent no-op. resolving
        // further would throw PathMissing and show a bogus "save path not found".
        if (source == null) {
            Timber.tag(TAG).i(
                "no cloud source for appId=%s — non-mapped or not-yet-cached container, sync no-op",
                appId,
            )
            return null
        }

        // the store advertises no cloud for this game.
        if (!source.isSupported) {
            Timber.tag(TAG).i(
                "cloud source unsupported for appId=%s kind=%s — game has no cloud config",
                appId, source::class.simpleName,
            )
            handleUnsupportedGame(appId, engineId = "")
            return null
        }

        // disk fallback for callers outside the WebView lifecycle (mirrorOnFlip, tests). slugFromAppId is
        // the html5-containers/<slug>/ dir name, NOT the origin slug.
        val cachedEngineId = activeEngineProfileId
        val engineId = cachedEngineId.ifEmpty {
            val webViewContainerSlug = WebViewScreenViewModel.slugFromAppId(appId)
            webViewContainerSlug
                ?.let { WebViewContainer.load(it)?.engineProfile }
                .orEmpty()
        }
        val resolvedProfile = ProfileRegistry.resolveProfile(
            context = context,
            appId = appId,
            engineId = engineId,
        )
        if (resolvedProfile == null) {
            // the game has cloud but we have no pack profile: a GameNative gap, so surface it loudly.
            throw SaveSyncFailure.PathMissing("no pack profile for appId=$appId engineId=$engineId")
        }
        // before path resolution: SaveDirectoryResolver derives the wine IDB dir from pcOrigin too.
        val derivedNwjsOrigin = nwjsOriginFor(resolvedProfile, appId)
        val profile = derivedNwjsOrigin?.let { origin ->
            resolvedProfile.copy(saves = resolvedProfile.saves?.copy(sync = resolvedProfile.saves.sync?.copy(pcOrigin = origin)))
        } ?: resolvedProfile

        // BEFORE SaveDirectoryResolver, which throws PathMissing on profiles without a saves block.
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

        // must equal WebViewOrigin.levelDbPrefix (drift-locked by a test in Html5SaveSyncServiceTest).
        val webViewOriginUrl = WebViewOrigin.originUrl(container.id)
        val webViewOriginFilename = OriginCodec.filenameFromUrl(webViewOriginUrl)

        // the origin discovered on disk beats the profile's placeholder (file://): the rewriters key off
        // it, and a wrong origin matches zero keys, leaving the WebView with empty stores.
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
            nwjsOriginDerived = derivedNwjsOrigin != null,
        )
    }

    // NW.js web storage lives under chrome-extension://<id of the manifest name>; the pack default
    // file:// never matches the PC. only replaces that default -- a pinned origin is left alone.
    private fun nwjsOriginFor(profile: EngineProfile, appId: String): String? {
        val sync = profile.saves?.sync ?: return null
        if (sync.mechanism != "leveldb-origin-rewrite" || sync.pcOrigin != "file://") return null
        val installPath = WebViewScreenViewModel.slugFromAppId(appId)
            ?.let { WebViewContainer.load(it)?.installPath }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return NwjsAppOrigin.fromInstall(File(installPath))?.also {
            Timber.tag(TAG).i("derived NW.js pc origin appId=%s origin=%s", appId, it)
        }
    }

    // the old file:// placeholder origin wrote a file__0 IDB copy the PC never reads; once the NW.js
    // origin is derived it would only keep uploading dead bytes.
    private fun scrubPlaceholderIdb(appId: String, paths: SaveDirectoryResolver.SavePathPair) {
        val idbDir = paths.wine.indexedDbLevelDb?.parentFile ?: return
        listOf("file__0.indexeddb.leveldb", "file__0.indexeddb.blob")
            .map { File(idbDir, it) }
            .filter { it.exists() }
            .forEach { dir ->
                runCatching { dir.deleteRecursively() }
                    .onSuccess { ok -> Timber.tag(TAG).i("scrubbed placeholder idb appId=%s dir=%s ok=%s", appId, dir.absolutePath, ok) }
                    .onFailure { Timber.tag(TAG).w(it, "scrub placeholder idb failed appId=%s dir=%s", appId, dir.absolutePath) }
            }
    }

    // the page's localStorage, handed over by teardown just before destroy() (LocalStorageSnapshot).
    // tagged with its appId; consumed once by that app's next outbound.
    @Volatile
    private var pageLocalStorage: Pair<String, List<Pair<ByteArray, ByteArray>>>? = null

    // teardown asks before capturing: only a leveldb-rewrite exit reads LS, and fs-authoritative titles
    // reroute away from it (same test as runSync). greenworks titles sync through their own snapshot.
    suspend fun wantsPageLocalStorage(appId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val container = ContainerManager(context).getContainerById(appId) ?: return@runCatching false
            if (resolveCloudSourceForContainer(container) is CloudSource.GreenworksCloud) return@runCatching false
            val setup = resolveSetup(appId) ?: return@runCatching false
            setup.strategy is SaveSyncStrategy.LevelDbOriginRewrite &&
                (setup.profile.saves?.sync?.syncChromiumProfile == true || !Html5FsAuthoritative.isFsAuthoritative(context, appId))
        }.getOrDefault(false)
    }

    // null clears any capture left from an exit whose outbound never ran.
    fun offerPageLocalStorage(appId: String, entries: List<Pair<ByteArray, ByteArray>>?) {
        pageLocalStorage = entries?.let { appId to it }
    }

    private fun takePageLocalStorage(appId: String): List<Pair<ByteArray, ByteArray>>? {
        val entries = pageLocalStorage?.takeIf { it.first == appId }?.second
        pageLocalStorage = null
        return entries
    }

    // the page applies the launch restore (ls-restore.js) instead of an iq80 write: chromium keeps the
    // LS leveldb open for the whole process once any WebView used it, so iq80 would be a second writer
    // on a live db. lastApplied is written only once the page reports success, so a launch that never
    // loads the page retries next time.
    private class LsRestore(val json: String, val wineMtime: Long) {
        // handed out once per launch so an in-game reload doesn't roll localStorage back.
        @Volatile var delivered = false
    }

    private val pendingLsRestores = java.util.concurrent.ConcurrentHashMap<String, LsRestore>()

    internal fun stageLsRestore(appId: String, entries: List<Pair<ByteArray, ByteArray>>, wineMtime: Long) {
        pendingLsRestores[appId] = LsRestore(LocalStorageSnapshot.toRestoreJson(entries), wineMtime)
        Timber.tag(TAG).i("staged localStorage restore appId=%s keys=%d", appId, entries.size)
    }

    internal fun takeLsRestore(appId: String): String? {
        val restore = pendingLsRestores[appId]?.takeIf { !it.delivered }
        if (restore == null) {
            // uninstalled earlier in this process, not purged yet: an empty restore makes the page clear stale keys.
            return if (Html5PendingLsPurge.isPending(context, appId)) "[]" else null
        }
        restore.delivered = true
        return restore.json
    }

    internal fun markLsRestoreApplied(appId: String) {
        // the page just cleared this origin, which is all the pending uninstall purge would do.
        Html5PendingLsPurge.remove(context, appId)
        val restore = pendingLsRestores.remove(appId) ?: return
        writeLastAppliedMtime(appId, restore.wineMtime)
        Timber.tag(TAG).i("page applied localStorage restore appId=%s", appId)
    }

    // returns the strategy that actually ran (fs-authoritative titles reroute to FsBridge).
    private fun runSync(appId: String, setup: SyncSetup, direction: Direction): SaveSyncStrategy {
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
            // evict the Wine-side leveldb copy so it isn't re-uploaded or re-pulled (whether the cloud
            // copy goes too is provider-dependent: GOG mirror-deletes, Steam doesn't).
            if (direction == Direction.OUTBOUND) {
                scrubWineLevelDbStaging(appId, setup.paths)
            }
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
            Direction.OUTBOUND -> {
                effectiveStrategy.syncOutbound(setup.paths, setup.origins, takePageLocalStorage(appId))
                if (setup.nwjsOriginDerived && effectiveStrategy is SaveSyncStrategy.LevelDbOriginRewrite) {
                    scrubPlaceholderIdb(appId, setup.paths)
                }
            }
            Direction.INBOUND -> effectiveStrategy.syncInbound(setup.paths, setup.origins)
        }
        Timber.tag(TAG).i(
            "sync ok direction=%s appId=%s mode=%s mechanism=%s",
            dirLabel,
            appId,
            setup.paths.syncMode,
            effectiveStrategy.mechanism,
        )
        return effectiveStrategy
    }

    // ONLY the chromium leaf dirs; the resolver keeps them distinct from userDataRoot, so game saves
    // are never touched.
    internal fun scrubWineLevelDbStaging(appId: String, paths: SaveDirectoryResolver.SavePathPair) {
        listOfNotNull(
            paths.wine.localStorageLevelDb,
            paths.wine.indexedDbLevelDb,
            paths.wine.indexedDbBlob,
        ).filter { it.exists() }.forEach { dir ->
            runCatching { dir.deleteRecursively() }
                .onSuccess { ok -> Timber.tag(TAG).i("scrubbed wine leveldb staging appId=%s dir=%s ok=%s", appId, dir.absolutePath, ok) }
                .onFailure { Timber.tag(TAG).w(it, "scrub wine leveldb staging failed appId=%s dir=%s", appId, dir.absolutePath) }
        }
    }

    private fun newestFileMtime(dir: File?): Long {
        if (dir == null || !dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
    }

    // Wine mtime as of the last successful sync in either direction.
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

    private fun handleFailure(t: Throwable, direction: Direction, appId: String) {
        val failure = when (t) {
            is SaveSyncFailure -> t
            else -> SaveSyncFailure.Other(t)
        }
        val copy = context.getString(stringIdForKey(failure.userFacingKey))
        // offline, the generic "Other" is most likely a network throw the user already knows about;
        // device-side failures still surface.
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

    private fun stringIdForKey(key: String): Int = when (key) {
        "save_sync_lock" -> R.string.save_sync_lock
        "save_sync_corruption" -> R.string.save_sync_corruption
        "save_sync_missing" -> R.string.save_sync_missing
        "save_sync_permission" -> R.string.save_sync_permission
        "save_sync_incompatible" -> R.string.save_sync_incompatible
        else -> R.string.save_sync_other
    }

    // not a bug, so INFO; once per launch.
    private fun handleUnsupportedGame(appId: String, engineId: String) {
        val online = NetworkMonitor.hasInternet.value
        Timber.tag(TAG).i(
            "save sync unsupported for appId=%s engineId=%s online=%s — game has no pack profile",
            appId, engineId, online,
        )
        // offline, "unsupported" is often wrong (empty cached UFS, greenworks probe can't run), so stay
        // quiet -- but still flip the flag so a reconnect mid-launch doesn't show it.
        if (!unsupportedSnackbarShown) {
            unsupportedSnackbarShown = true
            if (online) {
                SnackbarManager.show(context.getString(R.string.save_sync_unsupported_game))
            }
        }
    }

    // null for custom games (LOCAL_ONLY by design) and Amazon (no cloud-save manager at all).
    internal suspend fun resolveCloudSourceForContainer(container: Container): CloudSource? {
        val id = container.id
        return when {
            GameSource.STEAM.matches(id) -> {
                val appIdInt = GameSource.STEAM.idOf(id).toIntOrNull() ?: return null
                val app = SteamService.getAppInfoOf(appIdInt) ?: return null
                // greenworks wins over UFS.
                val slug = WebViewScreenViewModel.slugFromAppId(id)
                val webViewContainer = slug?.let { WebViewContainer.load(it) }
                val greenworksObserved = webViewContainer?.greenworksCloudObserved == true
                if (greenworksObserved) {
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

    // pack:electron with no UFS patterns is a strong greenworks signal. probe Steam Cloud once
    // BEFORE the game's first greenworks call, else first launch reports "no cloud support". on
    // success the flag is persisted, so later launches skip the probe.
    private suspend fun attemptGreenworksHailMary(
        appId: String,
        appIdInt: Int,
        container: Container,
        slug: String?,
        webViewContainer: WebViewContainer?,
        hasUfsPatterns: Boolean,
    ): CloudSource.GreenworksCloud? {
        if (slug == null || webViewContainer == null) return null
        if (webViewContainer.engineProfile != EnginePackId.ELECTRON) return null
        if (hasUfsPatterns) return null
        Timber.tag(TAG).i(
            "hail-mary: probing greenworks for pack:electron appId=%s (no UFS patterns, flag not yet set)",
            appId,
        )
        val cloudOk = GreenworksCloudClient.probeCloud(appIdInt)
        if (!cloudOk) return null
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

        fun syncStateMarkerFile(context: Context, appId: String): File =
            File(syncStateDir(context), "$appId.lastApplied")

        private fun syncStateDir(context: Context): File = File(context.filesDir, "html5/sync-state")

        // after boot wedge repair wiped a WebView store, the markers vouch for data that is gone; dropping
        // them makes each next launch restore the Wine copy before exit sync can overwrite it.
        fun clearAllSyncState(context: Context): Int {
            val removed = syncStateDir(context).listFiles { f -> f.name.endsWith(".lastApplied") }
                .orEmpty()
                .count { it.delete() }
            if (removed > 0) Timber.tag(TAG).i("clearAllSyncState: removed %d inbound-gate marker(s)", removed)
            return removed
        }

        // on uninstall: a reinstall's cloud restore has backdated mtimes that read as older than a
        // surviving marker, so inbound would wrongly be skipped and the game would see no saves.
        fun clearSyncState(context: Context, appId: String) {
            val f = syncStateMarkerFile(context, appId)
            if (f.exists() && f.delete()) {
                Timber.tag(TAG).i("clearSyncState: removed inbound-gate marker for %s", appId)
            }
        }
    }
}
