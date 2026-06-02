package app.gamenative.html5.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// where a pack's own shim (pack:foo -> html5/shims/packs/foo.js) lands in the shim load order.
@Serializable
enum class PackShimPlacement {
    @SerialName("none")
    NONE,

    @SerialName("append")
    APPEND,

    // c3 needs __gnPointerTapConfig set before pointer-with-tap reads it. later fs-chain
    // prepends still push it back from index 0.
    @SerialName("prepend")
    PREPEND,
}

@Serializable
data class EngineProfile(
    val engine: String = "",
    val entryPoint: String = "index.html",
    val patches: List<Patch> = emptyList(),
    val input: InputSpec? = InputSpec(),
    val saves: SaveSpec? = SaveSpec(),
    val shims: List<String> = emptyList(),
    // asset basename under html5/packs/ (no .json).
    val overlay: String? = null,
    // GAMEPAD_* -> KEY_* at profile-create; forces keyboard synthesis for buttons the engine's
    // gamepad mapper ignores (e.g. rmmv has no Start binding).
    val gamepadKeySynthesisMap: Map<String, String> = emptyMap(),
    // pack:c3 only (resolveShimUrls also gates on the pack): proxies the main-thread Worker ctor.
    val workerShim: Boolean = false,
    // suppress chromium's gamepad->DOM key echo: phantom keydowns (START -> Enter) confuse engines
    // that read both getGamepads() and keydowns. opt out for packs that read keydowns INSTEAD.
    val suppressGamepadKbdEcho: Boolean = true,
    // report Windows desktop Chrome to match process.platform='win32'. does NOT touch the touch surface.
    val desktopUaSpoof: Boolean = true,
    // fs bridge is authoritative: miss = ENOENT, no asset XHR fallback. only for packs that use fs
    // purely for saves (rmmv); avoids a sync HEAD XHR per empty slot when save plugins probe
    // file1..fileN. leave false where engines read real assets via fs (c3 c2-archive, nwjs Impact).
    val fsBridgeOnly: Boolean = false,
    // emscripten/Unity .br/.gz need Content-Encoding decoded by chromium's network stack, which
    // shouldInterceptRequest does NOT run -- so serve them via the loopback HTTP server. false for
    // packs that decompress .gz assets in JS.
    val contentEncodedCompression: Boolean = false,
    // Unity's own fill-viewport sizing sits behind a mobile-UA check our desktop spoof defeats.
    val fillCanvas: Boolean = false,
    // resolveShimUrls auto-injects per this, so a new pack needs no ShimBundles entry.
    val packShimPlacement: PackShimPlacement = PackShimPlacement.NONE,
    // ~980px desktop layout viewport. needed by electron (fixed-width meta viewport) and c3 (intscale
    // + centering); false for rmmv/nwjs, where 980 pushes the canvas off-screen.
    val wideViewport: Boolean = false,
)
