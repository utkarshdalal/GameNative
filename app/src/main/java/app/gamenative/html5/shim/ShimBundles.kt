package app.gamenative.html5.shim

// the "/_shims/" URL prefix is synthesized by AssetInterceptor; files only live at the asset path.
// per-shim rationale lives in each .js header.
object ShimBundles {
    const val STEAMWORKS_NOOP_ID = "steamworks-noop"
    const val NW_NOOP_ID = "nw-noop"
    const val GREENWORKS_NOOP_ID = "greenworks-noop"
    const val WEBGL_CAPS_PROBE_ID = "webgl-caps-probe"
    const val GAMEPAD_ID = "gamepad"
    const val TOUCH_ID = "touch"

    const val PACK_RMMV_ID = "pack-rmmv"
    const val PACK_C3_ID = "pack-c3"
    const val PACK_ELECTRON_ID = "pack-electron"
    const val PACK_NWJS_ID = "pack-nwjs"
    const val PACK_GMS_ID = "pack-gms"
    const val PACK_TYRANO_ID = "pack-tyrano"

    // dev-only. injected FIRST so it captures every storage call from frame zero.
    const val DIAGNOSTIC_ID = "diagnostic"

    // require-dispatcher must load FIRST so fs + path can register against it.
    const val REQUIRE_DISPATCHER_ID = "require-dispatcher"
    const val FS_SHIM_ID = "fs"
    const val PATH_SHIM_ID = "path"
    const val CRYPTO_SHIM_ID = "crypto"
    const val EVENTS_SHIM_ID = "events"
    const val OS_SHIM_ID = "os"
    const val JS_YAML_ID = "js-yaml"
    const val YAML_BRIDGE_ID = "yaml-bridge"
    // filenames with a literal `%` otherwise hit ERR_NAME_NOT_RESOLVED before our interceptor runs.
    const val URL_SANITIZE_ID = "url-sanitize"

    const val VIEWPORT_INSET_ID = "viewport-inset"

    // Pixi NPOT FBO textures go incomplete -> black on older WebView.
    const val WEBGL1_NPOT_FIX_ID = "webgl1-npot-fbo-compat"

    const val INPUT_SYNTH_ID = "input-synth"

    // must run before any game script.
    const val LS_RESTORE_ID = "ls-restore"

    const val GAMEPAD_KBD_SUPPRESS_ID = "gamepad-kbd-suppress"

    const val DESKTOP_SPOOF_ID = "desktop-spoof"

    // larger output buffer avoids SyncReader::Read timeouts (CHECK SIGTRAP) under thermal/CPU pressure.
    const val AUDIO_LATENCY_ID = "audio-latency"

    // prepended first so prototype patches land before any AudioContext/AudioListener exists.
    const val WEB_AUDIO_COMPAT_ID = "web-audio-compat"

    // WebView's decoder pool fails/hangs on parallel large decodes.
    const val AUDIO_DECODE_SERIAL_ID = "audio-decode-serial"

    // RMMZ TextPicture per-change BaseTexture churn trips a chromium-109 CHECK.
    const val TEXT_PICTURE_CACHE_ID = "text-picture-cache"

    const val WORKER_INSTALL_ID = "worker-install"

    // loaded INSIDE the worker via importScripts -- NOT a main-thread shim.
    const val WORKER_BUNDLE_ID = "worker-bundle"

    // copies cloud-restored saves into OPFS at launch (skip-if-exists) so a fresh device sees them.
    const val OPFS_HYDRATE_INBOUND_ID = "opfs-hydrate-inbound"

    const val PHYSICAL_MOUSE_ID = "physical-mouse"

    private const val STEAMWORKS_NOOP_ASSET = "html5/shims/steamworks.js"
    private const val STEAMWORKS_NOOP_URL = "/_shims/steamworks.js"
    private const val NW_NOOP_ASSET = "html5/shims/nw.js"
    private const val NW_NOOP_URL = "/_shims/nw.js"
    private const val GREENWORKS_NOOP_ASSET = "html5/shims/greenworks.js"
    private const val GREENWORKS_NOOP_URL = "/_shims/greenworks.js"
    private const val JS_YAML_ASSET = "html5/shims/js-yaml.min.js"
    private const val JS_YAML_URL = "/_shims/js-yaml.min.js"

    const val BASE_BACKGROUND_ID = "base-background"
    const val AUDIO_REGISTRY_ID = "audio-registry"
    const val NODE_GLOBALS_ID = "node-globals"

    // focus-driven engines (e.g. Impact) would otherwise resume BGM on QuickMenu close while paused.
    const val MANUAL_FOCUS_HOLD_ID = "manual-focus-hold"

    private val bundles: Map<String, Bundle> = mapOf(
        STEAMWORKS_NOOP_ID to Bundle(assetPath = STEAMWORKS_NOOP_ASSET, url = STEAMWORKS_NOOP_URL),
        NW_NOOP_ID to Bundle(assetPath = NW_NOOP_ASSET, url = NW_NOOP_URL),
        GREENWORKS_NOOP_ID to Bundle(assetPath = GREENWORKS_NOOP_ASSET, url = GREENWORKS_NOOP_URL),
        JS_YAML_ID to Bundle(assetPath = JS_YAML_ASSET, url = JS_YAML_URL),
    )

    // `bundles` holds ONLY ids whose asset filename differs from the id; the rest derive by convention.
    fun assetPathFor(bundleId: String): String? =
        bundles[bundleId]?.assetPath ?: deriveShimPath(bundleId)?.first

    fun urlFor(bundleId: String): String? =
        bundles[bundleId]?.url ?: deriveShimPath(bundleId)?.second

    // `pack-<x>` -> html5/shims/packs/<x>.js, `<x>` -> html5/shims/<x>.js. id restricted to
    // letters/digits/dash so it can't escape html5/shims/.
    internal fun deriveShimPath(bundleId: String): Pair<String, String>? {
        if (bundleId.isEmpty() || !bundleId.all { it.isLetterOrDigit() || it == '-' }) return null
        val short = bundleId.removePrefix("pack-")
        if (short.isEmpty()) return null
        val sub = if (short != bundleId) "packs/" else ""
        return "html5/shims/$sub$short.js" to "/_shims/$sub$short.js"
    }

    data class Bundle(val assetPath: String, val url: String)
}
