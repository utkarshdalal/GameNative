package app.gamenative.runtime

import app.gamenative.service.DownloadService
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

// parallel to winlator's imagefs/, not nested: imagefs is wine-specific.
@Serializable
data class WebViewContainer(
    val id: String,
    val installPath: String,
    val entryPoint: String = "index.html",
    val engineProfile: String,
    // sub-folder under installPath holding index.html + assets. "" for RMMZ/C3, "www" for RMMV.
    val webRoot: String = "",
    // "" = pack default at launch.
    val inputMap: String = "",
    // literal because WebViewRuntime.id isn't initialized yet here; a drift test locks it to Container.RUNTIME_WEBVIEW.
    val runtime: String = "webview",
    // "english" matches Container.java's unset sentinel.
    val language: String = "english",
    // 0L = unset; first launch mints a per-container profile.
    val controlsProfileId: Long = 0L,
    val gestureConfig: String = "",
    // OFF = touch.js steps aside and raw touch reaches the canvas, for games with native touch handling.
    val isTouchscreenMode: Boolean = true,
    val overlayOpacity: Float = 0.4f,
    // controller-first: off by default, but elements are still seeded so flipping it on works.
    val overlayVisible: Boolean = false,
    // set on the first greenworks file API call; routes save-sync through GreenworksCloud instead of SteamUfs.
    val greenworksCloudObserved: Boolean = false,
    // devicePixelRatio override. -1f = follow PrefManager.html5RenderScale, 0f = device-native.
    // launch-time only: PIXI/C3 cache DPR at init.
    val renderScale: Float = -1f,
    // install dir mtime at last fingerprint; Html5InstallWatcher skips re-fingerprinting while it matches.
    // fingerprintedEngineId is kept separate so a manual engineProfile override doesn't invalidate the cache.
    val fingerprintMtime: Long = 0L,
    val fingerprintedEngineId: String = "",
    // pack:nwjs sub-bucket ("impact"/"terra"/"generic") for AngleOverrideAdvisor; null outside pack:nwjs.
    val subEngine: String? = null,
    // suspendPolicy is NOT stored here -- it lives on the wine Container as the single source of
    // truth, read by both runtimes.
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        // does NOT mkdirs (save() does) so read paths don't leak empty dirs.
        fun configFile(slug: String): File {
            val root = File(DownloadService.baseExternalAppDirPath, "html5-containers")
            return File(File(root, slug), "config.json")
        }

        // `file` is a test seam.
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

        // for ContainerConfigTransfer; shares the private `json` so its config can't drift from save/load.
        fun encodeToJson(container: WebViewContainer): String = json.encodeToString(container)

        fun decodeFromJson(text: String): WebViewContainer? =
            runCatching {
                json.decodeFromString<WebViewContainer>(text)
            }.onFailure {
                Timber.tag("WebViewContainer").e(it, "decode failed")
            }.getOrNull()
    }
}
