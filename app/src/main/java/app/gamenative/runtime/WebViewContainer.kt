package app.gamenative.runtime

import app.gamenative.service.DownloadService
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

// per-container config for html5 titles. lives parallel to winlator's imagefs/, not nested,
// because imagefs is wine-specific.
@Serializable
data class WebViewContainer(
    val id: String,
    val installPath: String,
    val entryPoint: String = "index.html",
    val engineProfile: String,
    // sub-folder under installPath that contains index.html + assets. "" for RMMZ/C3, "www" for RMMV.
    val webRoot: String = "",
    // "" = unset → resolveInputMode() picks the pack default at launch. an explicit literal
    // (e.g. "pointer-with-tap-detection") survives because kotlinx.serialization only fills
    // the default when the key is absent.
    val inputMap: String = "",
    // literal required for @Serializable default -- can't reference WebViewRuntime.id (companion
    // init order). drift test locks this to Container.RUNTIME_WEBVIEW.
    val runtime: String = "webview",
    // Goldberg-style per-container language for navigator.language injection. "english" matches
    // Container.java's unset sentinel. defaulted for back-compat with old configs.
    val language: String = "english",
    // 0L = unset; first launch mints a per-container profile via Html5DefaultControlsProfileFactory.
    // ignoreUnknownKeys=true fills missing field with 0L for old container JSONs.
    val controlsProfileId: Long = 0L,
    // "" = unset → TouchGestureConfig.fromJson returns defaults.
    val gestureConfig: String = "",
    // wine-parity TOUCHSCREEN_MODE. ON = touch.js interprets gestures (default, existing behavior).
    // OFF = touch.js suspends, raw touch passes through to canvas (for games with native touch handling).
    val isTouchscreenMode: Boolean = true,
    val overlayOpacity: Float = 0.4f,
    // controller-first UX: users opt into the touch overlay via QuickMenu. seedIfEmpty still runs
    // so elements exist on disk for the moment they flip it on.
    val overlayVisible: Boolean = false,
    // flipped by SteamworksJsBridge on the first observed greenworks file API call. once true,
    // Html5SaveSyncService routes through CloudSource.GreenworksCloud instead of SteamUfs.
    val greenworksCloudObserved: Boolean = false,
    // per-container window.devicePixelRatio override.
    // -1f → follow PrefManager.html5RenderScale (global default)
    // 0f → device-native (no override)
    // >0f → explicit value
    // PIXI/C3 cache DPR at init, so this is launch-time only -- live-switch tears the renderer down.
    val renderScale: Float = -1f,
    // fingerprint cache. fingerprintMtime is the installPath dir's File.lastModified() at the
    // time fingerprint() ran; Html5InstallWatcher skips re-fingerprinting when this matches the
    // current mtime. fingerprintedEngineId is the engineProfile that produced this cache -- kept
    // separate so engineProfile drift (e.g. manual override) doesn't silently invalidate the
    // cache. 0L sentinel = no cache yet (cold install).
    val fingerprintMtime: Long = 0L,
    val fingerprintedEngineId: String = "",
    // pack:nwjs sub-bucket: "impact"/"terra"/"generic". consumed by AngleOverrideAdvisor
    // (terra = ANGLE-override advisory); not consumed by ProfileRegistry. null when no
    // sub-bucket applies (RMMV/C3/electron).
    val subEngine: String? = null,
    // suspendPolicy is NOT stored here -- it lives on the wine Container as the single source of
    // truth, read by both runtimes.
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        // per-container config at ${externalFilesDir}/html5-containers/<slug>/config.json.
        // mirrors CustomGameScanner.defaultRootPath precedent. PURE path helper -- does NOT
        // mkdirs. save() handles directory creation. previously configFile() eagerly
        // mkdir'd which leaked empty dirs for every read path (Html5InstallWatcher cache
        // check, ContainerConfigTransfer round-trips, etc.).
        fun configFile(slug: String): File {
            val root = File(DownloadService.baseExternalAppDirPath, "html5-containers")
            return File(File(root, slug), "config.json")
        }

        // `file` override lets tests stand in a tempDir without touching
        // DownloadService.baseExternalAppDirPath. production callers omit it.
        fun save(slug: String, container: WebViewContainer, file: File = configFile(slug)) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(container))
        }

        fun load(slug: String, file: File = configFile(slug)): WebViewContainer? =
            runCatching {
                if (!file.exists()) return@runCatching null
                json.decodeFromString<WebViewContainer>(file.readText())
            }.onFailure {
                Timber.tag("WebViewContainer").e(it, "failed to load $slug")
            }.getOrNull()

        // public seams for ContainerConfigTransfer round-trip. KEEP `json` private -- these
        // proxy through the same configured instance so encode/decode stay in sync (ignoreUnknownKeys,
        // prettyPrint). avoids a second Json{} config drifting out of step with save/load.
        fun encodeToJson(container: WebViewContainer): String = json.encodeToString(container)

        fun decodeFromJson(text: String): WebViewContainer? =
            runCatching {
                json.decodeFromString<WebViewContainer>(text)
            }.onFailure {
                Timber.tag("WebViewContainer").e(it, "decode failed")
            }.getOrNull()
    }
}
