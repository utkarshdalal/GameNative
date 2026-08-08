package app.gamenative.html5.host

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.PackShimPlacement
import app.gamenative.html5.shim.ShimBundles
import app.gamenative.html5.profile.EnginePackId

// URL resolution extracted for testability. pure function -- no android deps.
// inputs: profile's explicit shims, the resolved input mode; output: ordered shim URL list.
// profile explicit shims win first. gamepad.js always added for html5 containers.
// pointer-with-tap.js conditional on resolvedMode. steamworks-noop default fires when profile
// had no explicit shims. tests live in WebViewScreenShimResolutionTest (same package).
//
// ===== pack-shim placement contracts (read this before editing pack JSONs or this function) =====
//
//   pack   | placement      | source of placement      | reason
//   -------|----------------|--------------------------|-----------------------------------------
//   c3     | PREPEND idx 0  | auto-inject here         | __gnPointerTapConfig must be set BEFORE
//          |                | (c3.json MUST NOT list   | pointer-with-tap reads it. listing in
//          |                | "pack-c3")               | c3.json's shims would push it back.
//   rmmv   | EXPLICIT-EARLY | rmmv.json shims list     | patchWhenReady must start polling
//          | + auto-inject  | + dedup auto-inject here | Graphics before YEP-style plugins
//          |                | (dedup gate)             | reassign Graphics._centerElement. solo
//          |                |                          | auto-inject lost the race on Felvidek.
//   nwjs   | APPEND         | auto-inject here         | capture-phase kbd-on-gamepad swallow
//          |                |                          | installs on first script execution;
//          |                |                          | placement among other shims doesn't
//          |                |                          | matter once capture is active.
//   gms    | APPEND         | auto-inject here         | canvas/viewport CSS cap -- order-agnostic.
//   tyrano | APPEND         | auto-inject here         | meta viewport rewrite at
//          |                |                          | DOMContentLoaded -- order-agnostic.
//   electron | APPEND       | electron.json shims list | full bridge surface -- not auto-injected
//            |              | only (no auto-inject)    | here; pack JSON owns the contract.
//
// adding a new pack: pick PREPEND if any shim it injects has a parse-time dependency that
// must precede engine init; otherwise APPEND. for engines where load-order races with the
// title's own plugin loader (rmmv YEP, c2 events plugin), prefer EXPLICIT-EARLY in pack JSON
// with a dedup auto-inject here as belt-and-braces -- the regression guard rmmv has.
//
internal fun resolveShimUrls(
    profile: EngineProfile?,
    resolvedMode: String,
    includeDiagnostic: Boolean = false,
): List<String> {
    val ids = mutableListOf<String>()
    // dedup helpers -- resolve shim ID → URL, skip if already present. append() lands at the
    // end of load order, prepend() at index 0. closes over `ids` to collapse the repeated
    // `urlFor(ID)?.takeIf { it !in ids }?.let { ... }` boilerplate below. behavior + ordering
    // identical to the prior hand-written lines.
    fun append(id: String) {
        ShimBundles.urlFor(id)?.takeIf { it !in ids }?.let { ids.add(it) }
    }
    fun prepend(id: String) {
        ShimBundles.urlFor(id)?.takeIf { it !in ids }?.let { ids.add(0, it) }
    }
    val fromProfile = profile?.shims?.mapNotNull { ShimBundles.urlFor(it) } ?: emptyList()
    ids.addAll(fromProfile)
    // gamepad.js -- always-on. wraps navigator.getGamepads() to read state from
    // __gnGamepadBridge. single source of truth for both physical input and overlay taps
    // (overlay GAMEPAD_* writes the same profile.gamepadState that physical KeyEvents do).
    append(ShimBundles.GAMEPAD_ID)
    // gamepad-kbd-suppress.js -- default-on capture-phase swallow of chromium's native
    // KeyEvent→DOM auto-dispatch for gamepad-source events. pack opt-out via
    // EngineProfile.suppressGamepadKbdEcho = false. default true keeps phantom DOM
    // keydowns (BUTTON_START → keyCode 13, etc.) from confusing engines that read both
    // navigator.getGamepads() and DOM keydowns. MUST come AFTER gamepad.js so the
    // anyGamepadButtonPressed() check sees gamepad.js's wrapper.
    val suppressKbdEcho = profile?.suppressGamepadKbdEcho ?: true
    if (suppressKbdEcho) {
        append(ShimBundles.GAMEPAD_KBD_SUPPRESS_ID)
    }
    // desktop-spoof.js -- gated on profile.desktopUaSpoof. patches navigator.platform and
    // navigator.userAgentData (the bits that WebSettings.userAgentString can't reach). default
    // off; opt-in per title via patches.json. paired with host-side UA override in WebView setup.
    if (profile?.desktopUaSpoof == true) {
        append(ShimBundles.DESKTOP_SPOOF_ID)
    }
    // unified config-driven shim -- always inject ShimBundles.TOUCH_ID. replaces
    // the four mode-specific shims (old IDs deleted). gesture behavior is
    // selected at runtime by window.__gnGestureConfig (parse-time injected via
    // IndexHtmlRewriter, live-updated via WebViewScreen.evaluateJavascript on dialog Done).
    append(ShimBundles.TOUCH_ID)
    // physical-mouse.js -- always-on. exposes window.__gnPhysicalMouseHover for the host's
    // setOnHoverListener to call per ACTION_HOVER_MOVE. WebView swallows hover-without-button
    // events; without this bridge, pointermove only fires during press-drag.
    append(ShimBundles.PHYSICAL_MOUSE_ID)
    // steamworks-noop / greenworks-noop / webgl-caps-probe -- always-inject for any html5
    // container. all three are read-only or harmless stubs that future-proof packs against
    // titles linking new Steam/greenworks surfaces. pack:tyrano + pack:electron both have
    // titles that link greenworks via various routes; webgl probe is pure introspection.
    // dedupe via ids-contains check.
    append(ShimBundles.STEAMWORKS_NOOP_ID)
    append(ShimBundles.GREENWORKS_NOOP_ID)
    append(ShimBundles.WEBGL_CAPS_PROBE_ID)
    // base-background / audio-registry / node-globals -- always-inject base shims. extracted
    // from pack-specific shims so every html5 container gets the same defensive coverage:
    //  - base-background: html/body background:#000 to hide sub-pixel rounding gaps under
    //    scaled-canvas letterbox
    //  - audio-registry: wraps window.Audio so PAUSE_MEDIA_JS can iterate audio elements
    //    that engines built via `new Audio` without DOM append (Tyrano pattern)
    //  - node-globals: __dirname + __filename completes the Node/Windows-NWjs posture
    //    we already apply to every container (process.platform=win32, process.env)
    append(ShimBundles.BASE_BACKGROUND_ID)
    append(ShimBundles.AUDIO_REGISTRY_ID)
    append(ShimBundles.NODE_GLOBALS_ID)
    // manual-focus-hold: under suspendPolicy=manual, swallow QuickMenu-close's window 'focus'
    // so focus-driven engines (Impact/CrossCode) don't self-resume BGM while held paused.
    // order-agnostic (capture-phase listener gated on host-set window.__gnManualPaused).
    append(ShimBundles.MANUAL_FOCUS_HOLD_ID)
    // pack's own shim -- auto-injected per packs/<id>.json packShimPlacement. id by convention:
    // pack:foo → pack-foo → html5/shims/packs/foo.js. per-pack placement + reasons live in the
    // table at the top of this file (regression-guarded by WebViewScreenShimResolutionTest).
    // a new pack needs ZERO code here.
    profile?.engine?.let { engine ->
        val packShimId = "pack-" + engine.removePrefix("pack:")
        when (profile.packShimPlacement) {
            PackShimPlacement.APPEND -> append(packShimId)
            PackShimPlacement.PREPEND -> prepend(packShimId)
            PackShimPlacement.NONE -> {}
        }
    }
    // fs shim chain prepends. load-time execution order at index 0..n
    // reads: [require-dispatcher, diagnostic?, fs, path, ...rest]. each ids.add(0, ...) pushes
    // earlier prepends further back, so we apply them in REVERSE of load order: path first,
    // then fs, then diagnostic (diagnostic-first-before-fs), then require-dispatcher LAST so
    // it lands at index 0 regardless of what came before.

    // path.js -- registers 'path' module onto window.require. pure-JS, no bridge.
    prepend(ShimBundles.PATH_SHIM_ID)
    // crypto.js -- pure-JS AES-256-CTR. registers 'crypto' module. cheap (~6KB) so always-on
    // for html5 containers; any title that doesn't use crypto pays only the parse cost.
    prepend(ShimBundles.CRYPTO_SHIM_ID)
    // events.js -- Node-compatible EventEmitter. registers 'events' module. always-on; titles
    // that don't use Node events pay only the parse cost. enables NW.js Steamworks wrappers
    // (e.g. Steam4C2.js).
    prepend(ShimBundles.EVENTS_SHIM_ID)
    // os.js -- minimal node "os" surface (platform, EOL, etc.). always-on; tiny.
    prepend(ShimBundles.OS_SHIM_ID)
    // url-sanitize.js -- wraps XHR/fetch/Image.src to encode stray `%`. always-on;
    // no-op for URLs that don't contain stray %.
    prepend(ShimBundles.URL_SANITIZE_ID)
    // viewport-inset.js -- exposes --gn-bottom-inset CSS variable for pack-level fixes to
    // layout-vs-visual viewport mismatches (desktop-ported titles with fixed viewport meta).
    // always-on; no-op when layout matches visual.
    prepend(ShimBundles.VIEWPORT_INSET_ID)
    // yaml-bridge.js -- registers js-yaml against require ids. prepend BEFORE js-yaml.min.js so
    // bridge runs AFTER js-yaml at load time (prepends apply in reverse load order).
    prepend(ShimBundles.YAML_BRIDGE_ID)
    // js-yaml.min.js -- bundled 3.14.1 UMD. exposes window.jsyaml. yaml-bridge consumes it.
    prepend(ShimBundles.JS_YAML_ID)
    // fs.js -- registers 'fs' module. must run BEFORE path.js chronologically because some
    // callers do fs ops before any path math; diagnostic sink should see fs calls logged too.
    // after path prepend: [path, ...]
    // after fs prepend: [fs, path, ...]
    // after diagnostic: [diagnostic, fs, path, ...]
    // after dispatcher: [require-dispatcher, diagnostic, fs, path, ...]
    // which matches exactly.
    prepend(ShimBundles.FS_SHIM_ID)
    // diagnostic shim must run FIRST -- before pack-c3 / game code -- so every
    // localStorage/indexedDB call from frame zero is traced. prepend after fs so it lands
    // BEFORE fs in final list (diagnostic sees fs calls logged via its own sink). gated:
    // dev builds only.
    if (includeDiagnostic) {
        prepend(ShimBundles.DIAGNOSTIC_ID)
    }
    // always inject input-synth.js for variant=html5. drains
    // __gnInputBridge per rAF tick → KeyboardEvent / MouseEvent dispatch. prepended BEFORE
    // require-dispatcher (so require-dispatcher still wins index 0) -- input-synth has no
    // require/fs/path deps so any "early" position works.
    prepend(ShimBundles.INPUT_SYNTH_ID)
    // require-dispatcher MUST run first -- installs window.require + register API
    // that fs.js / path.js / refactored steamworks.js all rely on at parse time. prepend LAST
    // so it lands at index 0 regardless of other prepends above.
    prepend(ShimBundles.REQUIRE_DISPATCHER_ID)
    // audio-latency shim must run BEFORE any game code so the AudioContext wrapper is in
    // place when the game does its first `new AudioContext()`. prepended last so it lands at
    // index 0 -- even before require-dispatcher. no deps; pure global-mutation.
    // final order: [audio-decode-serial, web-audio-compat, audio-latency, require-dispatcher, input-synth, diagnostic?, ..., gamepad, ...]
    prepend(ShimBundles.AUDIO_LATENCY_ID)
    // Web Audio compat -- patches AudioListener/PannerNode/AudioBufferSourceNode/
    // OscillatorNode/AudioContext prototypes for legacy/removed APIs (chromium dropped
    // Doppler etc.). prepended AFTER audio-latency so it lands at index 0 -- class-prototype
    // patches must be visible before any constructor wrapper or game code touches the API.
    prepend(ShimBundles.WEB_AUDIO_COMPAT_ID)
    // serialize decodeAudioData. WebView's decoder chokes on parallel large decodes (titles
    // doing Promise.all on many large PCM tracks hang or reject EncodingError). prepended
    // LAST so it lands at index 0 -- wrapper must be installed BEFORE any game code captures
    // BaseAudioContext.prototype.decodeAudioData.
    prepend(ShimBundles.AUDIO_DECODE_SERIAL_ID)
    // worker-install.js installs the main-thread Worker ctor proxy.
    // MUST land at index 0 so it executes BEFORE c3runtime.js spawns its NW.js runtime
    // worker (the one that c3 NodeWebkit plugin runs writeFileSync inside). gated on
    // engine=="pack:c3" AND workerShim==true. opt-in per title via c3-patches.json byAppId.
    // no other shim above spawns Workers (audio-latency / require-dispatcher / input-synth
    // / diagnostic / fs / path / crypto / os / yaml / url-sanitize / gamepad / touch / pack-c3
    // are all main-thread-only), so it's safe to land worker-install AFTER them in prepend
    // order -- they run only on the main thread, never in workers.
    if (profile?.engine == EnginePackId.C3 && profile.workerShim) {
        // issue-A: opfs-hydrate-inbound. fires once at launch -- copies wine save dir
        // → OPFS so workers' eagerHydrateOpfs sees cloud-restored saves on fresh device /
        // clear data. polls __gnOpfsMirrorBridge.isInboundReady so it doesn't run before
        // pullInstallToOpfs caches activeMirrorRoot. PREPENDED FIRST so worker-install
        // remains at index 0 (its "MUST land at 0" contract -- see test
        // IndexHtmlRewriterWorkerInstallTest.packC3_workerShimTrue_prependsWorkerInstallAtIndex0).
        prepend(ShimBundles.OPFS_HYDRATE_INBOUND_ID)
        prepend(ShimBundles.WORKER_INSTALL_ID)
    }
    // TextPicture cache shim -- defers install until Sprite_Picture is defined, so injection
    // order doesn't matter. append at the end so it doesn't perturb the require/fs/path/
    // diagnostic ordering above.
    append(ShimBundles.TEXT_PICTURE_CACHE_ID)
    return ids.toList()
}
