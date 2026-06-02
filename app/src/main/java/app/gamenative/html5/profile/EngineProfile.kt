package app.gamenative.html5.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// where a pack's own shim (id by convention: pack:foo → html5/shims/packs/foo.js) lands in the
// shim load order. read by resolveShimUrls. NONE = pack ships no auto-injected shim.
@Serializable
enum class PackShimPlacement {
    @SerialName("none")
    NONE,

    // after the always-on shims. order-agnostic packs (gms/tyrano/nwjs/rmmv).
    @SerialName("append")
    APPEND,

    // ahead of the always-on appends -- c3 needs __gnPointerTapConfig set before pointer-with-tap
    // reads it. later fs-chain prepends still push it back from index 0.
    @SerialName("prepend")
    PREPEND,
}

// pack default profile. one per engine pack under assets/html5/packs/<pack>.json; title-specific
// overrides live in assets/html5/packs/<pack>-patches.json keyed by appId.
@Serializable
data class EngineProfile(
    val engine: String = "",
    val entryPoint: String = "index.html",
    val patches: List<Patch> = emptyList(),
    // override only for a non-default input mode, e.g. native-controller (rmmv).
    val input: InputSpec? = InputSpec(),
    // override only for a non-default save mechanism or a chromiumProfileSubdir hop.
    val saves: SaveSpec? = SaveSpec(),
    val shims: List<String> = emptyList(),
    // pack default touch-overlay layout: asset basename under html5/packs/ (no .json). null = none.
    val overlay: String? = null,
    // pack GAMEPAD_* → KEY_* overrides, applied at profile-create. unmapped buttons stay GAMEPAD_*
    // (gamepad bridge); a KEY_* entry forces keyboard synthesis instead (e.g. rmmv titles whose
    // stock gamepadMapper has no Start binding).
    val gamepadKeySynthesisMap: Map<String, String> = emptyMap(),
    // pack:c3 only -- prepend worker-install.js so the main-thread Worker ctor is proxied.
    // resolveShimUrls AND-gates on the c3 pack; default false keeps other packs untouched.
    val workerShim: Boolean = false,
    // suppress chromium's gamepad→DOM KeyEvent auto-dispatch. phantom keydowns (START → Enter)
    // confuse engines that read both getGamepads() and DOM keydowns. opt out for packs that read
    // DOM keydowns INSTEAD of polling the Gamepad API.
    val suppressGamepadKbdEcho: Boolean = true,
    // report Windows desktop Chrome (navigator.* + UA header + desktop-spoof.js) to match our
    // process.platform='win32' posture. does NOT touch the touch surface. per-title opt-out via
    // patches.json for any title it regresses.
    val desktopUaSpoof: Boolean = true,
    // treat the fs bridge as authoritative: miss = ENOENT, no asset XHR fallback. correct for packs
    // that use fs ONLY for saves (rmmv routes assets through XHR/PIXI/<script>); leave false for
    // packs whose engines read real assets via fs (c3 c2-archive, nwjs Impact). avoids a sync HEAD
    // XHR + 404 per empty slot when rmmv save plugins probe file1..fileN with fs.existsSync.
    val fsBridgeOnly: Boolean = false,
    // emscripten/Unity builds fetch pre-compressed .br/.gz directly and require Content-Encoding so
    // chromium's network stack decompresses (the loader hard-errors otherwise). route those to the
    // loopback HTTP server; shouldInterceptRequest does NOT run the decoder. false for packs that
    // ship literally-named .gz assets they decompress in JS.
    val contentEncodedCompression: Boolean = false,
    // pack:unity -- force the canvas to fill the viewport at parse time (Unity's sizing hides behind
    // a mobile-UA check our desktop spoof defeats). see IndexInjectionConfig.fillCanvas.
    val fillCanvas: Boolean = false,
    // where this pack's own shim lands in the shim load order; resolveShimUrls auto-injects per this
    // value, so a new pack needs no ShimBundles entry. NONE = no auto-injected shim.
    val packShimPlacement: PackShimPlacement = PackShimPlacement.NONE,
    // use the ~980px desktop layout viewport (useWideViewPort + loadWithOverviewMode). needed by
    // electron (fixed-width meta viewport) and c3 (c2 intscale math + centering). false for
    // rmmv/nwjs which ship user-scalable=no with no width, where 980 pushes the canvas off-screen.
    val wideViewport: Boolean = false,
)
