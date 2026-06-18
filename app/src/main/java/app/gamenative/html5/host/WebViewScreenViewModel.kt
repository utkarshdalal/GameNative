package app.gamenative.html5.host

import android.content.Context
import androidx.lifecycle.ViewModel
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.ProfileRegistry
import app.gamenative.html5.savesync.Html5SaveSyncService
import app.gamenative.html5.shim.Html5DiagnosticBridge
import app.gamenative.html5.Html5SlugUtil
import app.gamenative.runtime.WebViewContainer
import app.gamenative.utils.ContainerUtils
import app.gamenative.service.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

// no StateFlow: the screen's state lives in the WebView itself.
@HiltViewModel
class WebViewScreenViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    // exposed here so the composable gets injected singletons without EntryPointAccessors.
    val html5SaveSyncService: Html5SaveSyncService,
    val html5DiagnosticBridge: Html5DiagnosticBridge,
) : ViewModel() {

    data class Loaded(
        val container: WebViewContainer,
        val profile: EngineProfile?,
    )

    // null = container missing.
    fun loadByAppId(appId: String): Loaded? {
        val slug = slugFromAppId(appId) ?: return null
        val base = WebViewContainer.load(slug) ?: return null
        // language is owned by the wine Container; copy it in once so every consumer can read container.language.
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
        // appId -> html5-containers/<slug> dir name. process-cached: the uncached scan parses every
        // container JSON and runs on hot paths.
        //  - SENTINEL_NONE caches "no html5 container" so wine titles short-circuit
        //  - install / delete MUST call invalidateSlugCache
        //  - a missing rootDir isn't cached
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
                        if (container.id != appId) {
                            null
                        } else {
                            // migrates legacy hashed slugs; safe because the match is on container.id, not dir name.
                            Html5SlugUtil.canonicalize(dir, container.installPath, container.id).name
                        }
                    }
                }
                ?.firstOrNull()
            slugCache[appId] = resolved ?: SENTINEL_NONE
            return resolved
        }

        fun invalidateSlugCache(appId: String) {
            slugCache.remove(appId)
        }
    }
}
