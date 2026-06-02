package app.gamenative.html5.install

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.html5.Html5OptInService
import app.gamenative.html5.fingerprint.FingerprintResult
import app.gamenative.html5.fingerprint.fingerprint
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import app.gamenative.data.GameSource

// event-bus subscriber that runs the engine fingerprinter on Steam DOWNLOAD_COMPLETE and
// auto-flips matched containers to webview runtime. silent miss when no engine matches.
// forward-only -- never re-scans existing installs.

// lifecycle: started once from PluviaApp.onCreate, never stopped during app lifetime.
// re-entry into start() is a no-op (idempotency via @Volatile flag).
@Singleton
class Html5InstallWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // app-scope lives for the process lifetime; mirrors PluviaApp.appScope shape.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var subscribed: Boolean = false

    // captured as val so off() can match the same reference if stop() is ever called.
    private val onInstallComplete: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
        scope.launch { handleInstallComplete(event.appId) }
    }

    private val onCustomGameDiscovered: (AndroidEvent.CustomGameDiscovered) -> Unit = { event ->
        scope.launch { handleCustomGameDiscovered(event.appId) }
    }

    fun start() {
        if (subscribed) return
        // chromium gate -- if html5 runtime is disabled (old WebView) skip subscribing entirely.
        if (PluviaApp.html5RuntimeDisabled) {
            Timber.tag(TAG).d("html5 runtime disabled — Html5InstallWatcher not subscribing")
            return
        }
        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallComplete)
        PluviaApp.events.on<AndroidEvent.CustomGameDiscovered, Unit>(onCustomGameDiscovered)
        subscribed = true
        Timber.tag(TAG).i("subscribed to LibraryInstallStatusChanged + CustomGameDiscovered")
    }

    // intentionally exposed for tests + future opt-out; production never calls this.
    fun stop() {
        if (!subscribed) return
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallComplete)
        PluviaApp.events.off<AndroidEvent.CustomGameDiscovered, Unit>(onCustomGameDiscovered)
        subscribed = false
        Timber.tag(TAG).i("unsubscribed from LibraryInstallStatusChanged + CustomGameDiscovered")
    }

    // visible-for-testing. runs on Dispatchers.IO. no exceptions escape -- every error path
    // logs via Timber and the container stays on the wine runtime.
    internal suspend fun handleInstallComplete(appId: Int) {
        // LibraryInstallStatusChanged fires from many sources (Steam/Amazon/Epic/GOG/
        // CustomGameScanner/ContainerStorageManager + uninstall/cancel paths). probe per-store
        // to derive the prefix. Steam wins on collision (none observed in the wild).
        // CUSTOM_GAME has its own event (CustomGameDiscovered) -- see handleCustomGameDiscovered.
        val containerAppId = when {
            SteamService.isAppInstalled(appId) -> "STEAM_$appId"
            GOGService.isGameInstalled(appId.toString()) -> "GOG_$appId"
            EpicService.isGameInstalled(context, appId) -> "EPIC_$appId"
            AmazonService.isGameInstalledByAppId(context, appId) -> "AMAZON_$appId"
            else -> {
                Timber.tag(TAG).v("appId=$appId not installed in any store (likely uninstall/cancel) — skipping")
                return
            }
        }
        runFingerprintAndFlip(containerAppId, appId, reEmitLibraryEvent = true)
    }

    // sideload "install" handler. parallel to handleInstallComplete but for custom games,
    // which surface via CustomGameDiscovered (no download phase, no LibraryInstallStatusChanged).
    internal suspend fun handleCustomGameDiscovered(appId: Int) {
        val containerAppId = "CUSTOM_GAME_$appId"
        runFingerprintAndFlip(containerAppId, appId, reEmitLibraryEvent = false)
    }

    // shared post-resolution flow: cache-check → fingerprint → flip / re-fingerprint / candidate
    // snackbar. fail-soft -- every error path logs and leaves the container on wine.
    //
    // re-fingerprint policy: not forward-only. installs that were already html5 still get
    // fingerprinted (cache-gated by mtime) so Steam/GOG updates that swap the engine (rare:
    // NW.js bump, MV→MZ port) update the recorded engineProfile + snackbar the user. unchanged
    // installs hit the cache and short-circuit before signature evaluation.
    private suspend fun runFingerprintAndFlip(
        containerAppId: String,
        appId: Int,
        reEmitLibraryEvent: Boolean,
    ) {
        try {
            val root = Html5OptInService.resolveFingerprintPath(containerAppId) ?: run {
                Timber.tag(TAG).d("no install path for $containerAppId — skipping")
                return
            }

            // cache gate: skip fingerprint when WebViewContainer JSON exists, dir mtime matches,
            // AND the cached engine matches the currently-recorded engineProfile. invalid cache
            // (mtime mismatch OR engine drift) falls through to a fresh fingerprint.
            val slug = Html5OptInService.slugFor(containerAppId)
            val cached = slug?.let { WebViewContainer.load(it) }
            val currentMtime = root.lastModified()
            val cacheValid = cached != null &&
                cached.fingerprintMtime > 0L &&
                cached.fingerprintMtime == currentMtime &&
                cached.fingerprintedEngineId.isNotBlank() &&
                cached.fingerprintedEngineId == cached.engineProfile
            if (cacheValid) {
                Timber.tag(TAG).v(
                    "$containerAppId cache hit (mtime=$currentMtime engine=${cached!!.engineProfile}) — skip fingerprint",
                )
                return
            }

            val result = fingerprint(root)
            val baseContainer = ContainerUtils.getOrCreateContainer(context, containerAppId)
            val alreadyHtml5 = baseContainer.runtime == Container.RUNTIME_WEBVIEW

            when (result) {
                is FingerprintResult.Candidate -> handleCandidate(containerAppId, appId, root, result, alreadyHtml5)
                FingerprintResult.Unknown -> handleUnknown(containerAppId, root, alreadyHtml5)
                is FingerprintResult.Matched -> handleMatched(
                    containerAppId = containerAppId,
                    appId = appId,
                    root = root,
                    match = result,
                    baseContainer = baseContainer,
                    alreadyHtml5 = alreadyHtml5,
                    cachedContainer = cached,
                    slug = slug,
                    currentMtime = currentMtime,
                    reEmitLibraryEvent = reEmitLibraryEvent,
                )
            }
        } catch (t: Throwable) {
            // fail-soft -- never crash the install pipeline. log + stay wine.
            Timber.tag(TAG).e(t, "fingerprint flow failed for $containerAppId — staying wine")
        }
    }

    private fun handleCandidate(
        containerAppId: String,
        appId: Int,
        root: File,
        candidate: FingerprintResult.Candidate,
        alreadyHtml5: Boolean,
    ) {
        if (alreadyHtml5) {
            // bizarre: container is html5 but fingerprint now reports a candidate-only engine.
            // means the install dropped its supported markers and looks like Godot/Unity/etc now.
            // log + leave alone; user can manually switch back to wine.
            Timber.tag(TAG).w(
                "$containerAppId already html5 but fingerprint now reports candidate=${candidate.engineHint} — leaving as-is",
            )
            return
        }
        val appName = resolveAppName(containerAppId, appId, root)
        SnackbarManager.show(
            context.getString(R.string.html5_install_candidate, appName, candidate.engineHint),
        )
        Timber.tag(TAG).i(
            "$containerAppId ($appName) recognized as ${candidate.engineHint} (${candidate.reason}) — unsupported, staying wine",
        )
    }

    private fun handleUnknown(containerAppId: String, root: File, alreadyHtml5: Boolean) {
        if (alreadyHtml5) {
            // container was previously html5 but engine markers are gone (engine bump that
            // removed our anchor file? rebrand? corrupt install?). don't auto-revert -- user's
            // saves/config are tied to html5; a manual flip back to wine is safer than guessing.
            Timber.tag(TAG)
                .w("$containerAppId already html5 but no engine matches now path=${root.absolutePath} — leaving as-is")
            return
        }
        Timber.tag(TAG).i("no engine match for $containerAppId path=${root.absolutePath} — staying wine")
    }

    private suspend fun handleMatched(
        containerAppId: String,
        appId: Int,
        root: File,
        match: FingerprintResult.Matched,
        baseContainer: Container,
        alreadyHtml5: Boolean,
        cachedContainer: WebViewContainer?,
        slug: String?,
        currentMtime: Long,
        reEmitLibraryEvent: Boolean,
    ) {
        if (!alreadyHtml5) {
            // fresh flip -- existing single-source-of-truth path through optIn + applyToContainerGated.
            val baseData = ContainerUtils.toContainerData(baseContainer)
            val html5Data = baseData.copy(containerVariant = Container.CONTAINER_VARIANT_HTML5)
            val applied = ContainerUtils.applyToContainerGated(context, containerAppId, html5Data)
            if (!applied) {
                Timber.tag(TAG).w(
                    "auto-flip rejected by gate for $containerAppId — html5 opt-in surfaced its own snackbar",
                )
                return
            }
            val appName = resolveAppName(containerAppId, appId, root)
            SnackbarManager.show(context.getString(R.string.html5_install_auto_detected, appName))
            Timber.tag(TAG).i(
                "auto-flipped $containerAppId ($appName) to webview runtime via engine=${match.engine} sub=${match.subEngine}",
            )
            if (reEmitLibraryEvent) {
                PluviaApp.events.emit(
                    AndroidEvent.LibraryInstallStatusChanged(
                        appId,
                        GameSource.fromContainerId(containerAppId) ?: GameSource.STEAM,
                    ),
                )
            }
            return
        }
        // already html5: re-fingerprint path. compare match to cached engine; if same, just bump
        // the cache mtime so future events short-circuit. if different, snackbar + update JSON.
        if (slug == null || cachedContainer == null) {
            Timber.tag(TAG).w(
                "$containerAppId html5 but slug=$slug cached=${cachedContainer != null} — skipping cache refresh",
            )
            return
        }
        if (cachedContainer.engineProfile == match.engine) {
            // engine unchanged -- refresh cache mtime to drop future fingerprint work.
            val refreshed = cachedContainer.copy(
                fingerprintMtime = currentMtime,
                fingerprintedEngineId = match.engine,
                subEngine = match.subEngine ?: cachedContainer.subEngine,
            )
            WebViewContainer.save(slug, refreshed)
            Timber.tag(TAG).v("$containerAppId engine unchanged (${match.engine}) — cache mtime refreshed")
            return
        }
        // engine changed across an update. snackbar + persist new engine. webRoot also updates.
        val oldEngine = cachedContainer.engineProfile
        val refreshed = cachedContainer.copy(
            engineProfile = match.engine,
            webRoot = match.webRoot,
            fingerprintMtime = currentMtime,
            fingerprintedEngineId = match.engine,
            subEngine = match.subEngine,
        )
        WebViewContainer.save(slug, refreshed)
        val appName = resolveAppName(containerAppId, appId, root)
        SnackbarManager.show(
            context.getString(R.string.html5_engine_changed, appName, oldEngine, match.engine),
        )
        Timber.tag(TAG).i(
            "$containerAppId engine changed $oldEngine → ${match.engine} (sub=${match.subEngine}) — config refreshed",
        )
    }

    private fun resolveAppName(containerAppId: String, appId: Int, root: File): String {
        val resolved = when {
            GameSource.STEAM.matches(containerAppId) -> SteamService.getAppInfoOf(appId)?.name
            GameSource.GOG.matches(containerAppId) -> GOGService.getGOGGameOf(appId.toString())?.title
            GameSource.EPIC.matches(containerAppId) -> EpicService.getEpicGameOf(appId)?.title
            GameSource.AMAZON.matches(containerAppId) -> AmazonService.getAmazonGameByAppId(appId)?.title
            // CustomGameScanner has no name lookup by id; folder basename IS the display name
            // (LibraryItem.name = folder.name in createLibraryItemFromFolder).
            GameSource.CUSTOM_GAME.matches(containerAppId) -> root.name
            else -> null
        }
        return resolved ?: "Game"
    }

    companion object {
        private const val TAG = "Html5InstallWatcher"
    }
}
