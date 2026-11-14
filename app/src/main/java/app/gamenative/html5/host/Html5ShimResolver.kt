package app.gamenative.html5.host

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.PackShimPlacement
import app.gamenative.html5.shim.ShimBundles
import app.gamenative.html5.profile.EnginePackId

// ordered shim URL list for a launch. ORDER IS LOAD ORDER -- see the per-shim constraints below.
//
// ===== pack-shim placement contracts (read before editing pack JSONs or this function) =====
//
//   pack     | placement                   | reason
//   ---------|-----------------------------|--------------------------------------------------
//   c3       | PREPEND idx 0               | __gnPointerTapConfig must be set BEFORE
//            |                             | pointer-with-tap reads it -- listing "pack-c3" in
//            |                             | c3.json's shims would push it back.
//   rmmv     | EXPLICIT-EARLY (rmmv.json)  | patchWhenReady must start polling Graphics before
//            | + dedup auto-inject here    | YEP-style plugins reassign _centerElement; the
//            |                             | auto-inject position alone loses that race.
//   nwjs     | APPEND                      | capture-phase kbd-on-gamepad swallow installs on
//            |                             | first script execution -- order-agnostic after.
//   gms      | APPEND                      | canvas/viewport CSS cap -- order-agnostic.
//   tyrano   | APPEND                      | meta viewport rewrite at DOMContentLoaded.
//   electron | APPEND (electron.json only) | full bridge surface; pack JSON owns the contract.
//
// new pack: PREPEND if a shim has a parse-time dependency that must precede engine init, else APPEND.
// if load order races the title's own plugin loader, use EXPLICIT-EARLY in the pack JSON plus the
// dedup auto-inject here.
//
internal fun resolveShimUrls(
    profile: EngineProfile?,
    resolvedMode: String,
    includeDiagnostic: Boolean = false,
): List<String> {
    val ids = mutableListOf<String>()
    fun append(id: String) {
        ShimBundles.urlFor(id)?.takeIf { it !in ids }?.let { ids.add(it) }
    }
    fun prepend(id: String) {
        ShimBundles.urlFor(id)?.takeIf { it !in ids }?.let { ids.add(0, it) }
    }
    val fromProfile = profile?.shims?.mapNotNull { ShimBundles.urlFor(it) } ?: emptyList()
    ids.addAll(fromProfile)
    append(ShimBundles.GAMEPAD_ID)
    // swallows chromium's DOM keydown echo of gamepad buttons (START -> Enter etc.), which confuses
    // engines reading both getGamepads() and keydowns. MUST come AFTER gamepad.js, whose wrapper it reads.
    val suppressKbdEcho = profile?.suppressGamepadKbdEcho ?: true
    if (suppressKbdEcho) {
        append(ShimBundles.GAMEPAD_KBD_SUPPRESS_ID)
    }
    // covers navigator.platform / userAgentData, which the host-side UA override can't reach.
    if (profile?.desktopUaSpoof == true) {
        append(ShimBundles.DESKTOP_SPOOF_ID)
    }
    // gesture behavior comes from window.__gnGestureConfig, live-updated by the host.
    append(ShimBundles.TOUCH_ID)
    // WebView drops button-less hover events; without this bridge pointermove only fires while dragging.
    append(ShimBundles.PHYSICAL_MOUSE_ID)
    append(ShimBundles.STEAMWORKS_NOOP_ID)
    append(ShimBundles.GREENWORKS_NOOP_ID)
    append(ShimBundles.WEBGL_CAPS_PROBE_ID)
    //  - base-background: black html/body hides sub-pixel gaps around a scaled, letterboxed canvas
    //  - audio-registry: lets PAUSE_MEDIA_JS reach `new Audio` elements never attached to the DOM
    append(ShimBundles.BASE_BACKGROUND_ID)
    append(ShimBundles.AUDIO_REGISTRY_ID)
    append(ShimBundles.NODE_GLOBALS_ID)
    // under suspendPolicy=manual, swallows the window 'focus' from QuickMenu-close so focus-driven
    // engines (Impact) don't resume BGM while held paused.
    append(ShimBundles.MANUAL_FOCUS_HOLD_ID)
    // pack:foo -> pack-foo -> html5/shims/packs/foo.js; placement per the table at the top.
    profile?.engine?.let { engine ->
        val packShimId = "pack-" + engine.removePrefix("pack:")
        when (profile.packShimPlacement) {
            PackShimPlacement.APPEND -> append(packShimId)
            PackShimPlacement.PREPEND -> prepend(packShimId)
            PackShimPlacement.NONE -> {}
        }
    }
    // prepends land at index 0, so they're applied in REVERSE of load order: each one pushes the
    // earlier prepends back. do not reorder.
    prepend(ShimBundles.PATH_SHIM_ID)
    prepend(ShimBundles.CRYPTO_SHIM_ID)
    prepend(ShimBundles.EVENTS_SHIM_ID)
    prepend(ShimBundles.OS_SHIM_ID)
    // encodes stray `%` in XHR/fetch/Image.src URLs.
    prepend(ShimBundles.URL_SANITIZE_ID)
    // exposes --gn-bottom-inset for pack CSS fixing layout-vs-visual viewport mismatches.
    prepend(ShimBundles.VIEWPORT_INSET_ID)
    // prepended before js-yaml so it loads AFTER it.
    prepend(ShimBundles.YAML_BRIDGE_ID)
    prepend(ShimBundles.JS_YAML_ID)
    // some callers do fs ops before any path math.
    prepend(ShimBundles.FS_SHIM_ID)
    // dev-only; loads before fs and game code so every storage call from frame zero is traced.
    if (includeDiagnostic) {
        prepend(ShimBundles.DIAGNOSTIC_ID)
    }
    // ls-restore clears + refills this origin's localStorage, so it must run before any shim that writes
    // localStorage at parse time (steamworks.js greenworks restore) and before game code.
    prepend(ShimBundles.LS_RESTORE_ID)
    prepend(ShimBundles.INPUT_SYNTH_ID)
    // installs window.require, which fs/path/steamworks shims register against at parse time.
    prepend(ShimBundles.REQUIRE_DISPATCHER_ID)
    // the AudioContext wrapper must be in place before the game's first `new AudioContext()`.
    prepend(ShimBundles.AUDIO_LATENCY_ID)
    // polyfills removed Web Audio APIs on the prototypes; must precede any constructor wrapper or game code.
    prepend(ShimBundles.WEB_AUDIO_COMPAT_ID)
    // WebView's decoder hangs or throws EncodingError on parallel large decodes. must be installed
    // before game code captures BaseAudioContext.prototype.decodeAudioData.
    prepend(ShimBundles.AUDIO_DECODE_SERIAL_ID)
    // worker-install MUST land at index 0 so its Worker proxy exists before c3runtime.js spawns
    // its runtime worker. none of the shims above spawn workers.
    if (profile?.engine == EnginePackId.C3 && profile.workerShim) {
        // copies the wine save dir into OPFS once at launch so workers see cloud-restored saves.
        // prepended before worker-install so worker-install keeps index 0.
        prepend(ShimBundles.OPFS_HYDRATE_INBOUND_ID)
        prepend(ShimBundles.WORKER_INSTALL_ID)
    }
    // defers itself until Sprite_Picture exists, so position doesn't matter.
    append(ShimBundles.TEXT_PICTURE_CACHE_ID)
    return ids.toList()
}
