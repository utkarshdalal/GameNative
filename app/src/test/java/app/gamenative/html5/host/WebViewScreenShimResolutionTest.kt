package app.gamenative.html5.host

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.InputSpec
import app.gamenative.html5.profile.PackShimPlacement
import app.gamenative.html5.shim.ShimBundles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// pure-jvm — ShimBundles is object-only, EngineProfile is @Serializable with no android deps.
class WebViewScreenShimResolutionTest {

    @Test fun gamepad_always_injected_even_without_profile() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "native-controller")
        assertTrue("gamepad shim must appear", urls.any { it.endsWith("/gamepad.js") })
        // steamworks-noop default applies when profile has no shims
        assertTrue("steamworks-noop default applies", urls.any { it.endsWith("/steamworks.js") })
    }

    // unified config-driven touch shim. resolveShimUrls always injects
    // ShimBundles.TOUCH_ID (touch.js) regardless of controller mode — the four mode-specific
    // shims (pointer-with-tap / touch-touchpad / touch-passthrough / touch-gestures) collapse
    // to a single shim driven by window.__gnGestureConfig at runtime.
    @Test fun unified_touch_shim_injected_regardless_of_controller_mode() {
        val withController = resolveShimUrls(null, "native-controller")
        assertTrue(
            "default touch shim injects /touch.js even alongside controller",
            withController.any { it.endsWith("/touch.js") },
        )
        val withTapInput = resolveShimUrls(null, "pointer-with-tap-detection")
        assertTrue(withTapInput.any { it.endsWith("/touch.js") })
    }

    @Test fun profile_explicit_shims_win_steamworks_default_suppressed() {
        val profile = EngineProfile(
            engine = "pack:c3",
            shims = listOf("steamworks-noop", "nw-noop"),
            input = InputSpec(mode = "pointer-with-tap-detection"),
        )
        val urls = resolveShimUrls(profile, "pointer-with-tap-detection")
        assertTrue("nw-noop from profile", urls.any { it.endsWith("/nw.js") })
        // steamworks-noop is in both profile list + default. should appear once.
        val steamCount = urls.count { it.endsWith("/steamworks.js") }
        assertEquals(1, steamCount)
    }

    // unified touch.js never duplicates. profile.shims listing "touch" alongside
    // the resolveShimUrls unconditional add must produce a single URL entry.
    @Test fun touch_shim_not_duplicated_when_profile_lists_it() {
        val profile = EngineProfile(
            engine = "pack:c3",
            shims = listOf(ShimBundles.TOUCH_ID),
            input = InputSpec(mode = "pointer-with-tap-detection"),
        )
        val urls = resolveShimUrls(profile, "pointer-with-tap-detection")
        val touchCount = urls.count { it.endsWith("/touch.js") }
        assertEquals(1, touchCount)
    }

    @Test fun gamepad_not_duplicated_when_profile_lists_it() {
        val profile = EngineProfile(engine = "pack:rmmv", shims = listOf("gamepad"))
        val urls = resolveShimUrls(profile, "native-controller")
        val gpCount = urls.count { it.endsWith("/gamepad.js") }
        assertEquals(1, gpCount)
    }

    @Test fun empty_profile_shims_still_gets_gamepad_plus_steamworks_default() {
        val profile = EngineProfile(engine = "pack:rmmv", shims = emptyList())
        val urls = resolveShimUrls(profile, "native-controller")
        assertTrue("gamepad", urls.any { it.endsWith("/gamepad.js") })
        assertTrue("steamworks-noop default fallback", urls.any { it.endsWith("/steamworks.js") })
    }

    // pack-rmmv shim appended per packShimPlacement=APPEND (rmmv.json). placement drives
    // injection now -- the shim id is derived by convention (pack:rmmv → pack-rmmv).
    @Test fun pack_rmmv_appends_pack_rmmv_shim_url() {
        val profile = EngineProfile(engine = "pack:rmmv", packShimPlacement = PackShimPlacement.APPEND)
        val urls = resolveShimUrls(profile, resolvedMode = "native-controller")
        assertTrue("should include pack-rmmv shim: $urls", urls.contains("/_shims/packs/rmmv.js"))
    }

    // (now pack-c3 shim prepended BEFORE the unified touch.js so
    // __gnPointerTapConfig (legacy) and __gnGestureConfig defaults are set before touch.js reads.
    @Test fun pack_c3_prepends_pack_c3_shim_url_before_unified_touch() {
        val profile = EngineProfile(
            engine = "pack:c3",
            packShimPlacement = PackShimPlacement.PREPEND,
            shims = listOf("steamworks-noop", "nw-noop"),
        )
        val urls = resolveShimUrls(profile, resolvedMode = "pointer-with-tap-detection")
        val c3Idx = urls.indexOf("/_shims/packs/c3.js")
        val touchIdx = urls.indexOf("/_shims/touch.js")
        assertTrue("pack-c3 shim not found: $urls", c3Idx >= 0)
        assertTrue("touch.js shim not found: $urls", touchIdx >= 0)
        assertTrue("pack-c3 must come BEFORE touch.js: c3=$c3Idx touch=$touchIdx", c3Idx < touchIdx)
    }

    // ordering regression guard: merged profile with other shims still gets pack-c3
    // BEFORE touch.js + gamepad. (require-dispatcher + diagnostic + fs + path prepend AHEAD
    // of pack-c3; pack-c3 no longer at absolute index 0, but still precedes the load-bearing
    // consumers.)
    @Test fun pack_c3_prepends_c3_url_even_when_merged_profile_has_other_shims() {
        val profile = EngineProfile(
            engine = "pack:c3",
            packShimPlacement = PackShimPlacement.PREPEND,
            shims = listOf("steamworks-noop", "nw-noop"),
        )
        val urls = resolveShimUrls(profile, resolvedMode = "pointer-with-tap-detection")
        val c3Idx = urls.indexOf("/_shims/packs/c3.js")
        val touchIdx = urls.indexOf("/_shims/touch.js")
        val gamepadIdx = urls.indexOf("/_shims/gamepad.js")

        assertTrue("c3.js missing: $urls", c3Idx >= 0)
        assertTrue("touch.js missing: $urls", touchIdx >= 0)
        assertTrue("c3=$c3Idx must be < touch=$touchIdx", c3Idx < touchIdx)
        assertTrue("c3=$c3Idx must be < gamepad=$gamepadIdx", c3Idx < gamepadIdx)
    }

    // non-pack engines must not get pack shims
    @Test fun non_pack_engine_does_not_add_pack_shims() {
        val profile = EngineProfile(engine = "pack:unknown")
        val urls = resolveShimUrls(profile, resolvedMode = "native-controller")
        assertTrue("no pack-rmmv for unknown engine: $urls", !urls.contains("/_shims/packs/rmmv.js"))
        assertTrue("no pack-c3 for unknown engine: $urls", !urls.contains("/_shims/packs/c3.js"))
    }

    // ---------------- 3 shim chain order ----------------

    // audio-decode-serial wraps BaseAudioContext.prototype.decodeAudioData — must land at
    // index 0 so the wrapper is in place before any game code captures decodeAudioData off
    // the prototype.
    // web-audio-compat patches AudioListener/PannerNode/etc. prototypes — index 1, before
    // audio-latency's AudioContext wrapper or any game code instantiates AudioContext.
    // audio-latency wraps AudioContext globally — index 2, still before game code.
    // require-dispatcher installs window.require which fs/path/steamworks consume at parse
    // time — index 3, still before any game JS that calls require().
    @Test fun resolveShimUrls_audioDecodeSerialAtZero_webAudioCompatAtOne_audioLatencyAtTwo_dispatcherAtThree() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = false)
        assertEquals(ShimBundles.urlFor(ShimBundles.AUDIO_DECODE_SERIAL_ID), urls[0])
        assertEquals(ShimBundles.urlFor(ShimBundles.WEB_AUDIO_COMPAT_ID), urls[1])
        assertEquals(ShimBundles.urlFor(ShimBundles.AUDIO_LATENCY_ID), urls[2])
        assertEquals(ShimBundles.urlFor(ShimBundles.REQUIRE_DISPATCHER_ID), urls[3])
    }

    @Test fun resolveShimUrls_orderIsDispatcherThenFsThenPath() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = false)
        val dispatcher = ShimBundles.urlFor(ShimBundles.REQUIRE_DISPATCHER_ID)!!
        val fs = ShimBundles.urlFor(ShimBundles.FS_SHIM_ID)!!
        val path = ShimBundles.urlFor(ShimBundles.PATH_SHIM_ID)!!
        val dispatcherIdx = urls.indexOf(dispatcher)
        val fsIdx = urls.indexOf(fs)
        val pathIdx = urls.indexOf(path)
        assertTrue("dispatcher must be present", dispatcherIdx >= 0)
        assertTrue("fs must be present", fsIdx >= 0)
        assertTrue("path must be present", pathIdx >= 0)
        assertTrue("dispatcher must precede fs", dispatcherIdx < fsIdx)
        assertTrue("fs must precede path", fsIdx < pathIdx)
    }

    @Test fun resolveShimUrls_withDiagnostic_orderIsDispatcherDiagnosticFsPath() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = true)
        val dispatcher = urls.indexOf(ShimBundles.urlFor(ShimBundles.REQUIRE_DISPATCHER_ID)!!)
        val diagnostic = urls.indexOf(ShimBundles.urlFor(ShimBundles.DIAGNOSTIC_ID)!!)
        val fs = urls.indexOf(ShimBundles.urlFor(ShimBundles.FS_SHIM_ID)!!)
        val path = urls.indexOf(ShimBundles.urlFor(ShimBundles.PATH_SHIM_ID)!!)
        assertTrue("dispatcher present", dispatcher >= 0)
        assertTrue("diagnostic present", diagnostic >= 0)
        assertTrue("fs present", fs >= 0)
        assertTrue("path present", path >= 0)
        assertTrue("dispatcher < diagnostic: d=$dispatcher diag=$diagnostic", dispatcher < diagnostic)
        assertTrue("diagnostic < fs: diag=$diagnostic fs=$fs", diagnostic < fs)
        assertTrue("fs < path: fs=$fs path=$path", fs < path)
    }

    // bridge chain registered for every html5 container regardless of engine pack.
    @Test fun resolveShimUrls_universalRegistration_appliesEvenWithNullProfile() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = false)
        assertTrue("fs shim must always be injected", urls.contains(ShimBundles.urlFor(ShimBundles.FS_SHIM_ID)!!))
        assertTrue("path shim must always be injected", urls.contains(ShimBundles.urlFor(ShimBundles.PATH_SHIM_ID)!!))
        assertTrue("dispatcher must always be injected", urls.contains(ShimBundles.urlFor(ShimBundles.REQUIRE_DISPATCHER_ID)!!))
    }

    // input-synth.js drains __gnInputBridge for KEY_*/MOUSE_* synth and is unconditional.
    // gamepad.js (covered by gamepad_shim_alwaysInjected) is the single navigator.getGamepads
    // polyfill — overlay taps and physical input both write the same profile.gamepadState.
    @Test fun resolveShimUrls_inputSynth_universallyInjected() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = false)
        assertTrue("input-synth shim must be injected: $urls", urls.contains(ShimBundles.urlFor(ShimBundles.INPUT_SYNTH_ID)!!))
    }

    // Refactor pin: gamepad-kbd-suppress.js is default-on for every html5 container
    // (suppressGamepadKbdEcho default = true on EngineProfile). null-profile path must
    // include it.
    @Test fun gamepadKbdSuppress_default_on_when_profile_null() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = false)
        assertTrue(
            "gamepad-kbd-suppress shim must be injected by default: $urls",
            urls.contains(ShimBundles.urlFor(ShimBundles.GAMEPAD_KBD_SUPPRESS_ID)!!),
        )
    }

    // Refactor pin: explicit profile with suppressGamepadKbdEcho = false omits the shim.
    // opt-out for packs that genuinely want chromium's gamepad→kbd auto-dispatch.
    @Test fun gamepadKbdSuppress_omitted_when_profile_opts_out() {
        val profile = EngineProfile(
            engine = "pack:custom",
            suppressGamepadKbdEcho = false,
        )
        val urls = resolveShimUrls(profile, resolvedMode = "", includeDiagnostic = false)
        assertEquals(
            "gamepad-kbd-suppress shim must NOT be injected on opt-out: $urls",
            0,
            urls.count { it == ShimBundles.urlFor(ShimBundles.GAMEPAD_KBD_SUPPRESS_ID) },
        )
    }

    // Refactor pin: explicit suppressGamepadKbdEcho = true behaves identically to the default.
    // ensures a pack JSON with `"suppressGamepadKbdEcho": true` doesn't accidentally double-inject.
    @Test fun gamepadKbdSuppress_explicitTrue_does_not_double_inject() {
        val profile = EngineProfile(
            engine = "pack:custom",
            suppressGamepadKbdEcho = true,
        )
        val urls = resolveShimUrls(profile, resolvedMode = "", includeDiagnostic = false)
        assertEquals(
            "gamepad-kbd-suppress shim must appear exactly once: $urls",
            1,
            urls.count { it == ShimBundles.urlFor(ShimBundles.GAMEPAD_KBD_SUPPRESS_ID) },
        )
    }

    // desktopUaSpoof default off — desktop-spoof shim must NOT appear unless opted in.
    @Test fun desktopSpoof_omitted_by_default() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = false)
        assertEquals(
            "desktop-spoof shim must NOT be injected by default: $urls",
            0,
            urls.count { it == ShimBundles.urlFor(ShimBundles.DESKTOP_SPOOF_ID) },
        )
    }

    @Test fun desktopSpoof_omitted_when_profile_optsOut_explicitly() {
        val profile = EngineProfile(engine = "pack:nwjs", desktopUaSpoof = false)
        val urls = resolveShimUrls(profile, resolvedMode = "", includeDiagnostic = false)
        assertEquals(
            "desktop-spoof shim must NOT be injected when desktopUaSpoof=false: $urls",
            0,
            urls.count { it == ShimBundles.urlFor(ShimBundles.DESKTOP_SPOOF_ID) },
        )
    }

    @Test fun desktopSpoof_injected_when_profile_optsIn() {
        val profile = EngineProfile(engine = "pack:nwjs", desktopUaSpoof = true)
        val urls = resolveShimUrls(profile, resolvedMode = "", includeDiagnostic = false)
        assertEquals(
            "desktop-spoof shim must appear exactly once when desktopUaSpoof=true: $urls",
            1,
            urls.count { it == ShimBundles.urlFor(ShimBundles.DESKTOP_SPOOF_ID) },
        )
    }
}
