package app.gamenative.html5.shim

// shim id registry. ids resolve to html5/shims/<id>.js (or packs/<x>.js for pack-<x>) by
// convention via deriveShimPath; the explicit `bundles` map below covers only ids whose asset
// filename differs from the id. the "/_shims/" URL prefix is synthesized by AssetInterceptor --
// files don't live at that URL, only the asset path. deep rationale per shim lives in its .js header.
object ShimBundles {
    const val STEAMWORKS_NOOP_ID = "steamworks-noop"
    const val NW_NOOP_ID = "nw-noop"
    // NW.js Steam titles require('./greenworks/greenworks'); stub avoids the real .node load.
    const val GREENWORKS_NOOP_ID = "greenworks-noop"
    // diagnostic, opt-in via pack JSON -- dumps WebGL caps to logcat on first getContext('webgl').
    const val WEBGL_CAPS_PROBE_ID = "webgl-caps-probe"
    // gamepad bridge -- always injected for html5.
    const val GAMEPAD_ID = "gamepad"
    // unified config-driven touch shim -- always injected for html5.
    const val TOUCH_ID = "touch"

    // pack shims -- asset files land in html5/shims/packs/.
    const val PACK_RMMV_ID = "pack-rmmv"
    const val PACK_C3_ID = "pack-c3"
    const val PACK_ELECTRON_ID = "pack-electron"
    // pack:nwjs (Impact engine) -- kbd-on-gamepad event swallow.
    const val PACK_NWJS_ID = "pack-nwjs"
    // pack:gms -- GameMaker canvas/viewport CSS cap (canvas ships raster attrs but no CSS size).
    const val PACK_GMS_ID = "pack-gms"
    // pack:tyrano -- TyranoScript VN; rewrites the meta viewport from Config.tjs scWidth/scHeight.
    const val PACK_TYRANO_ID = "pack-tyrano"

    // dev-only -- injected FIRST so it captures every localStorage/indexedDB call from frame zero.
    const val DIAGNOSTIC_ID = "diagnostic"

    // require-dispatcher must load FIRST so fs + path can register against it.
    const val REQUIRE_DISPATCHER_ID = "require-dispatcher"
    // node-compat fs façade; dispatches sync methods to __gnFsBridge.
    const val FS_SHIM_ID = "fs"
    // pure-JS path module (no bridge).
    const val PATH_SHIM_ID = "path"
    // pure-JS AES-256-CTR for require('crypto') -- always injected (cheap).
    const val CRYPTO_SHIM_ID = "crypto"
    // Node EventEmitter for require('events') -- always injected (c2-on-NW.js Steam wrappers chain off it).
    const val EVENTS_SHIM_ID = "events"
    // minimal node 'os' module (platform/type/arch/EOL) -- reports linux.
    const val OS_SHIM_ID = "os"
    // bundled js-yaml 3.14.1 UMD (window.jsyaml); paired with YAML_BRIDGE_ID which registers it.
    const val JS_YAML_ID = "js-yaml"
    const val YAML_BRIDGE_ID = "yaml-bridge"
    // wraps XHR/fetch/Image.src to encode stray `%` -- filenames with a literal `%` otherwise hit
    // ERR_NAME_NOT_RESOLVED before our interceptor runs.
    const val URL_SANITIZE_ID = "url-sanitize"

    // exposes `--gn-bottom-inset` CSS var for pack-level layout-vs-visual-viewport fixes
    // (fixed-viewport electron/nw.js titles using `<meta viewport content="width=N">`).
    const val VIEWPORT_INSET_ID = "viewport-inset"

    // WebGL1 NPOT FBO compat -- Pixi NPOT FBO textures go incomplete→black on older WebView;
    // widens textures still at the default mipmap MIN_FILTER.
    const val WEBGL1_NPOT_FIX_ID = "webgl1-npot-fbo-compat"

    // drains __gnInputBridge per rAF tick → synthetic KeyboardEvent/MouseEvent. always injected for html5.
    const val INPUT_SYNTH_ID = "input-synth"

    // swallow chromium's gamepad→DOM KeyEvent auto-dispatch while a pad button is held.
    // default-injected; pack opt-out via EngineProfile.suppressGamepadKbdEcho.
    const val GAMEPAD_KBD_SUPPRESS_ID = "gamepad-kbd-suppress"

    // navigator.* spoof to Windows desktop (matches process.platform='win32'). opt-in per title
    // via patches.json. does NOT touch the event surface.
    const val DESKTOP_SPOOF_ID = "desktop-spoof"

    // AudioContext latencyHint=playback -- larger output buffer to avoid SyncReader::Read timeouts
    // (CHECK SIGTRAP) under thermal/CPU pressure. always injected.
    const val AUDIO_LATENCY_ID = "audio-latency"

    // polyfills removed/legacy Web Audio APIs (setVelocity, noteOn/createGainNode, ...). prepended
    // first so prototype patches land before any AudioContext/AudioListener exists.
    const val WEB_AUDIO_COMPAT_ID = "web-audio-compat"

    // serialize decodeAudioData -- WebView's decoder pool fails/hangs on parallel large decodes.
    // always-on for html5.
    const val AUDIO_DECODE_SERIAL_ID = "audio-decode-serial"

    // RMMZ TextPicture bitmap cache -- avoids the per-change BaseTexture churn that trips a
    // chromium-109 CHECK. always injected; no-op until Sprite_Picture is defined.
    const val TEXT_PICTURE_CACHE_ID = "text-picture-cache"

    // main-thread Worker ctor proxy -- pack:c3 + workerShim only (resolved in resolveShimUrls).
    const val WORKER_INSTALL_ID = "worker-install"

    // worker-side bootstrap bundle, loaded INSIDE the worker via importScripts -- NOT a main-thread
    // shim; reachable via openShimAsset / the /_worker_stub entry.
    const val WORKER_BUNDLE_ID = "worker-bundle"

    // cloud INBOUND hydration -- copies wine-save-dir → OPFS at launch (SKIP-IF-EXISTS) so workers'
    // eagerHydrateOpfs sees cloud-restored saves on fresh device. pack:c3 + workerShim only.
    const val OPFS_HYDRATE_INBOUND_ID = "opfs-hydrate-inbound"

    // bridges WebView's hover-without-button stream into DOM pointermove/mousemove. host calls
    // window.__gnPhysicalMouseHover per hover event.
    const val PHYSICAL_MOUSE_ID = "physical-mouse"

    private const val STEAMWORKS_NOOP_ASSET = "html5/shims/steamworks.js"
    private const val STEAMWORKS_NOOP_URL = "/_shims/steamworks.js"
    private const val NW_NOOP_ASSET = "html5/shims/nw.js"
    private const val NW_NOOP_URL = "/_shims/nw.js"
    private const val GREENWORKS_NOOP_ASSET = "html5/shims/greenworks.js"
    private const val GREENWORKS_NOOP_URL = "/_shims/greenworks.js"
    private const val JS_YAML_ASSET = "html5/shims/js-yaml.min.js"
    private const val JS_YAML_URL = "/_shims/js-yaml.min.js"

    // always-injected base shims (extracted from pack-specific shims so every pack gets them).
    const val BASE_BACKGROUND_ID = "base-background"
    const val AUDIO_REGISTRY_ID = "audio-registry"
    const val NODE_GLOBALS_ID = "node-globals"

    // under suspendPolicy=manual, swallow the window 'focus' QuickMenu-close fires so focus-driven
    // engines (Impact/CrossCode) don't self-resume BGM while held paused. gated on __gnManualPaused.
    const val MANUAL_FOCUS_HOLD_ID = "manual-focus-hold"

    private val bundles: Map<String, Bundle> = mapOf(
        STEAMWORKS_NOOP_ID to Bundle(assetPath = STEAMWORKS_NOOP_ASSET, url = STEAMWORKS_NOOP_URL),
        NW_NOOP_ID to Bundle(assetPath = NW_NOOP_ASSET, url = NW_NOOP_URL),
        GREENWORKS_NOOP_ID to Bundle(assetPath = GREENWORKS_NOOP_ASSET, url = GREENWORKS_NOOP_URL),
        JS_YAML_ID to Bundle(assetPath = JS_YAML_ASSET, url = JS_YAML_URL),
    )

    // explicit map first; otherwise derive the path by convention so most shims need NO map entry.
    // the explicit `bundles` entries above are kept ONLY for ids whose asset filename differs from
    // the id (steamworks-noop→steamworks.js, nw-noop→nw.js, greenworks-noop→greenworks.js,
    // js-yaml→js-yaml.min.js). every other base/pack shim resolves via deriveShimPath.
    fun assetPathFor(bundleId: String): String? =
        bundles[bundleId]?.assetPath ?: deriveShimPath(bundleId)?.first

    fun urlFor(bundleId: String): String? =
        bundles[bundleId]?.url ?: deriveShimPath(bundleId)?.second

    // (assetPath, url) by convention for an id NOT in the explicit map. `pack-<x>` →
    // html5/shims/packs/<x>.js; any other clean id `<x>` → html5/shims/<x>.js. id must be a clean
    // lowercase/dash/digit token so a synthesized id can't escape html5/shims/ (no dots, slashes,
    // or `..`). internal (not private) so ShimBundlesConventionTest can assert the mapping.
    internal fun deriveShimPath(bundleId: String): Pair<String, String>? {
        if (bundleId.isEmpty() || !bundleId.all { it.isLetterOrDigit() || it == '-' }) return null
        val short = bundleId.removePrefix("pack-")
        if (short.isEmpty()) return null
        val sub = if (short != bundleId) "packs/" else ""
        return "html5/shims/$sub$short.js" to "/_shims/$sub$short.js"
    }

    data class Bundle(val assetPath: String, val url: String)
}
