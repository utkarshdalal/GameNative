package app.gamenative.html5.host

import android.content.Context
import androidx.lifecycle.ViewModel
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.ProfileRegistry
import app.gamenative.html5.savesync.Html5SaveSyncService
import app.gamenative.html5.shim.Html5DiagnosticBridge
import app.gamenative.runtime.WebViewContainer
import app.gamenative.utils.ContainerUtils
import app.gamenative.service.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

// minimal VM (mirrors XServerViewModel's 65-line shape). loads container + profile + slug.
// no StateFlow needed -- screen is mostly lifecycle + webview; state lives in
// the WebView itself.
@HiltViewModel
class WebViewScreenViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    // save-sync bridges into WebViewScreen's LaunchedEffect + onDispose.
    // exposed via VM so the composable gets the injected singleton without EntryPointAccessors.
    val html5SaveSyncService: Html5SaveSyncService,
    // dev-only diagnostic shim sink. gated by FeatureGate at WebView-build site.
    val html5DiagnosticBridge: Html5DiagnosticBridge,
) : ViewModel() {

    // slug field dropped -- origin keys on <safeId>.localhost and shim log
    // dirs rekey on container.id. html5-containers/<slug>/ JSON-dir addressing still uses
    // slug, but it's derived on demand inside loadByAppId (not persisted in Loaded).
    data class Loaded(
        val container: WebViewContainer,
        val profile: EngineProfile?,
    )

    // pure resolve -- called from composable's remember block.
    // null -> container missing; profile null -> fall back to engine-default url set.
    fun loadByAppId(appId: String): Loaded? {
        val slug = slugFromAppId(appId) ?: return null
        val base = WebViewContainer.load(slug) ?: return null
        // language is owned by the wine Container (the container-config dialog's source of truth);
        // the WebViewContainer sidecar never carried it (defaulted to "english"). populate it here
        // ONCE so every consumer reads the correct value from container.language: navigator.language
        // (IndexHtmlRewriter), Steam getCurrentGameLanguage (SteamworksJsBridge), and achievement
        // display-name localization (Html5AchievementWatcher).
        val wineLanguage = runCatching { ContainerUtils.getContainer(appContext, appId).language }
            .getOrDefault(base.language)
        val container = base.copy(language = wineLanguage)
        val profile = ProfileRegistry.resolveProfile(
            context = appContext,
            appId = appId,
            engineId = container.engineProfile,
        )
        return Loaded(container, profile)
    }

    companion object {
        // origin + save-sync no longer use slug -- slug survives ONLY as
        // the html5-containers/<slug>/config.json dir name.
        // slugFromAppId remains the reverse-lookup: appId -> slug-dirname. html5-containers/
        // rename to container.id-dirname is deferred cleanup.
        // Html5Routing.isHtml5App and Html5SaveSyncService still call this for JSON-dir probes.
        //
        // process-cached. uncached lookup is O(N · disk-IO · JSON-parse) over every html5
        // container and fires from many hot paths (Library launch dispatch, dialog Save,
        // overlay drag persist, every event-bus collector tick that checks isHtml5App).
        // invariants:
        //  - SENTINEL_NONE keys appIds we've verified have NO html5 container, so subsequent
        //    isHtml5App calls for wine titles short-circuit at map lookup
        //  - install / delete callers MUST invalidate (Html5InstallWatcher on fingerprint
        //    completion; ContainerUtils.deleteHtml5JsonDir on container removal)
        //  - non-existent rootDir returns null without writing the cache (no html5 containers
        //    at all → next install will trigger invalidate-and-populate)
        private const val SENTINEL_NONE = ""
        private val slugCache = ConcurrentHashMap<String, String>()

        fun slugFromAppId(appId: String): String? {
            slugCache[appId]?.let { return if (it == SENTINEL_NONE) null else it }
            val rootDir = File(DownloadService.baseExternalAppDirPath, "html5-containers")
            if (!rootDir.exists()) return null
            val resolved = rootDir.listFiles { f -> f.isDirectory }
                ?.asSequence()
                ?.mapNotNull { dir ->
                    WebViewContainer.load(dir.name)?.let { container ->
                        dir.name.takeIf { container.id == appId }
                    }
                }
                ?.firstOrNull()
            slugCache[appId] = resolved ?: SENTINEL_NONE
            return resolved
        }

        // called by Html5InstallWatcher after a new html5 container is committed, and by
        // ContainerUtils.deleteHtml5JsonDir after a container's JSON dir is removed. takes
        // appId so the targeted entry can be dropped without nuking unrelated hits.
        fun invalidateSlugCache(appId: String) {
            slugCache.remove(appId)
        }

        // wholesale clear for migration / repair paths (e.g. opt-out flip wipes the entire
        // html5-containers dir).
        fun invalidateAllSlugCache() {
            slugCache.clear()
        }
    }
}
