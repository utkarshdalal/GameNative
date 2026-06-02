package app.gamenative.html5.install

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.GameSource
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
import app.gamenative.utils.CustomGameCache
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

// fingerprints games on install/discovery and auto-flips matches to the webview runtime.
// reacts to events only -- never scans the existing library. started once from PluviaApp.onCreate.
@Singleton
class Html5InstallWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var subscribed: Boolean = false

    private val onInstallComplete: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
        scope.launch { handleInstallComplete(event.appId) }
    }

    private val onCustomGameDiscovered: (AndroidEvent.CustomGameDiscovered) -> Unit = { event ->
        scope.launch { handleCustomGameDiscovered(event.appId, event.folderPath) }
    }

    fun start() {
        if (subscribed) return
        if (PluviaApp.html5RuntimeDisabled) {
            Timber.tag(TAG).d("html5 runtime disabled — Html5InstallWatcher not subscribing")
            return
        }
        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallComplete)
        PluviaApp.events.on<AndroidEvent.CustomGameDiscovered, Unit>(onCustomGameDiscovered)
        subscribed = true
        Timber.tag(TAG).i("subscribed to LibraryInstallStatusChanged + CustomGameDiscovered")
    }

    // tests only; production never stops the watcher.
    fun stop() {
        if (!subscribed) return
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallComplete)
        PluviaApp.events.off<AndroidEvent.CustomGameDiscovered, Unit>(onCustomGameDiscovered)
        subscribed = false
        Timber.tag(TAG).i("unsubscribed from LibraryInstallStatusChanged + CustomGameDiscovered")
    }

    internal suspend fun handleInstallComplete(appId: Int) {
        // the event carries a bare id and also fires on uninstall/cancel, so probe each store.
        // Steam wins on an id collision. custom games come via CustomGameDiscovered instead.
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
        runFingerprintAndFlip(containerAppId, appId)
    }

    internal suspend fun handleCustomGameDiscovered(appId: Int, folderPath: String) {
        seedCustomGameLookup(appId, folderPath)
        val containerAppId = "CUSTOM_GAME_$appId"
        runFingerprintAndFlip(containerAppId, appId)
    }

    // the manual-folder pref write that precedes this event is async and may not have landed, and every
    // lookup below resolves the folder from that pref. seed the lookup cache with the folder the event
    // carries: the cache is kept while the pref is unchanged and rebuilt from it once the write lands.
    internal fun seedCustomGameLookup(appId: Int, folderPath: String) {
        CustomGameScanner.getFolderPathFromAppId("${GameSource.CUSTOM_GAME.name}_$appId")
        CustomGameCache.addEntry(appId, folderPath)
    }

    // fail-soft: every error path logs and leaves the container on wine.
    // already-html5 installs are re-fingerprinted (mtime-cached) so an update that swaps the
    // engine (e.g. MV->MZ port) updates the recorded engineProfile.
    private suspend fun runFingerprintAndFlip(
        containerAppId: String,
        appId: Int,
    ) {
        try {
            val root = Html5OptInService.resolveFingerprintPath(containerAppId) ?: run {
                Timber.tag(TAG).d("no install path for $containerAppId — skipping")
                return
            }

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
                )
            }
        } catch (t: Throwable) {
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
            // don't auto-revert; the user can switch back to wine manually.
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
            // don't auto-revert: saves/config are tied to html5; a manual flip is safer than guessing.
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
    ) {
        if (!alreadyHtml5) {
            if (slug != null && cachedContainer != null) {
                // sidecar + wine runtime = the user switched back to wine (uninstall deletes the sidecar,
                // so a fresh install still flips below). don't override that; just keep the sidecar current
                // for a later manual flip.
                val engineChanged = cachedContainer.engineProfile != match.engine
                WebViewContainer.save(
                    slug,
                    cachedContainer.copy(
                        engineProfile = match.engine,
                        webRoot = if (engineChanged) match.webRoot else cachedContainer.webRoot,
                        fingerprintMtime = currentMtime,
                        fingerprintedEngineId = match.engine,
                        subEngine = if (engineChanged) match.subEngine else match.subEngine ?: cachedContainer.subEngine,
                    ),
                )
                Timber.tag(TAG).i("$containerAppId has an html5 sidecar but runs wine -- user reverted, not re-flipping")
                return
            }
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
            // a first opt-in has no sidecar yet, so applyToContainerGated emits nothing; without this the
            // library keeps its cached wine runtime and the card shows no webview badge.
            PluviaApp.events.emit(
                AndroidEvent.LibraryInstallStatusChanged(
                    appId,
                    GameSource.fromContainerId(containerAppId) ?: GameSource.STEAM,
                ),
            )
            return
        }
        if (slug == null || cachedContainer == null) {
            Timber.tag(TAG).w(
                "$containerAppId html5 but slug=$slug cached=${cachedContainer != null} — skipping cache refresh",
            )
            return
        }
        if (cachedContainer.engineProfile == match.engine) {
            val refreshed = cachedContainer.copy(
                fingerprintMtime = currentMtime,
                fingerprintedEngineId = match.engine,
                subEngine = match.subEngine ?: cachedContainer.subEngine,
            )
            WebViewContainer.save(slug, refreshed)
            Timber.tag(TAG).v("$containerAppId engine unchanged (${match.engine}) — cache mtime refreshed")
            return
        }
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
            // custom games are displayed by folder name.
            GameSource.CUSTOM_GAME.matches(containerAppId) -> root.name
            else -> null
        }
        return resolved ?: "Game"
    }

    companion object {
        private const val TAG = "Html5InstallWatcher"
    }
}
