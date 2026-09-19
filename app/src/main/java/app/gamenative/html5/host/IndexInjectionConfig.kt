package app.gamenative.html5.host

// parse-time injection knobs for IndexHtmlRewriter. defaults are no-ops.
data class IndexInjectionConfig(
    // navigator.language pin. null = device-native.
    val locale: String? = null,
    // pack:electron only -- __gnElectronCtx pre-snippet (productName, appPath, etc.).
    val electronCtx: Map<String, String>? = null,
    // unified touch.js shim gesture config (TouchGestureConfig.fromJson(...).toJson()).
    val gestureConfigJson: String? = null,
    // pack:nwjs -- Steam launch args mirrored to window.__gnNwArgv (e.g. OMORI AES key).
    val nwArgvJson: String? = null,
    // pack:nwjs Impact-engine -- Windows-form path emitted as window.__gnNwAppDataPath.
    val nwAppDataPath: String? = null,
    // process.mainModule.filename -- typically "<webRoot>/index.html".
    val mainModuleFilename: String = "",
    // pack:electron only -- archive-relative preload.js URL, e.g. "/preload.js".
    val electronPreloadUrl: String? = null,
    // perf: window.devicePixelRatio override. null = device-native.
    val renderScaleOverride: Float? = null,
    // pack:rmmv default. when true, fs.js treats bridge as authoritative (no asset XHR fallback).
    val fsBridgeOnly: Boolean = false,
    // true = touch.js interprets gestures.
    val touchscreenMode: Boolean = true,
    // pack:unity -- Unity's WebGL template only fills the viewport behind a mobile UA check, which our
    // desktop UA spoof defeats, so the canvas stays at its fixed size.
    val fillCanvas: Boolean = false,
)
