package app.gamenative.html5.host

// parse-time injection knobs for IndexHtmlRewriter + the three asset interceptors. each field
// is consumed by IndexHtmlRewriter.inject as a `window.__gn*` parse-time emit OR by the
// rewriter's behavioral logic. defaults are byte-identical no-ops.
//
// most fields apply to a subset of packs (electronCtx/electronPreloadUrl → pack:electron;
// nwArgvJson/nwAppDataPath → pack:nwjs; etc.) but live in the same DTO because they all
// flow through the same single rewriter entry point. callers construct one instance and
// thread it through; adding the 11th knob is a one-line schema change instead of editing
// 4 method signatures.
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
    // process.mainModule.filename -- typically "<webRoot>/index.html". empty = back-compat.
    val mainModuleFilename: String = "",
    // pack:electron only -- archive-relative preload.js URL, e.g. "/preload.js".
    val electronPreloadUrl: String? = null,
    // perf: window.devicePixelRatio override. null = device-native.
    val renderScaleOverride: Float? = null,
    // pack:rmmv default. when true, fs.js treats bridge as authoritative (no asset XHR fallback).
    val fsBridgeOnly: Boolean = false,
    // wine-parity TOUCHSCREEN_MODE persistence. true = touch.js interprets gestures (default).
    val touchscreenMode: Boolean = true,
    // pack:unity -- force the game canvas to fill the viewport. Unity's WebGL template only
    // applies its 100%/fixed canvas sizing behind a `navigator.userAgent` mobile check, which
    // our desktop UA spoof defeats → canvas renders at its fixed 1280x720 (only partly visible).
    // when true, IndexHtmlRewriter injects a force-fill stylesheet at parse time. default false
    // keeps all other packs byte-identical.
    val fillCanvas: Boolean = false,
)
