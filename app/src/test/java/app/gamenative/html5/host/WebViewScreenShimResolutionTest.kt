package app.gamenative.html5.host

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.InputSpec
import app.gamenative.html5.profile.PackShimPlacement
import app.gamenative.html5.shim.ShimBundles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewScreenShimResolutionTest {

    @Test fun gamepad_always_injected_even_without_profile() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "native-controller")
        assertTrue("gamepad shim must appear", urls.any { it.endsWith("/gamepad.js") })
        assertTrue("steamworks-noop default applies", urls.any { it.endsWith("/steamworks.js") })
    }

    // resolveShimUrls always injects ShimBundles.TOUCH_ID (touch.js) regardless of controller
    // mode -- the mode-specific touch shims collapsed into one driven by window.__gnGestureConfig.
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
        // steamworks-noop is in both profile list + default; must appear once.
        val steamCount = urls.count { it.endsWith("/steamworks.js") }
        assertEquals(1, steamCount)
    }

    // profile.shims listing "touch" alongside the unconditional add must produce a single URL.
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

    // rmmv.json sets packShimPlacement=APPEND; the shim id is derived by convention (pack:rmmv -> pack-rmmv).
    @Test fun pack_rmmv_appends_pack_rmmv_shim_url() {
        val profile = EngineProfile(engine = "pack:rmmv", packShimPlacement = PackShimPlacement.APPEND)
        val urls = resolveShimUrls(profile, resolvedMode = "native-controller")
        assertTrue("should include pack-rmmv shim: $urls", urls.contains("/_shims/packs/rmmv.js"))
    }

    // pack-c3 goes BEFORE touch.js so __gnPointerTapConfig and __gnGestureConfig defaults are set
    // before touch.js reads them.
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

    // a merged profile with other shims still gets pack-c3 BEFORE touch.js + gamepad. pack-c3 isn't
    // at index 0 (require-dispatcher, diagnostic, fs, path prepend ahead of it) but still
    // precedes the load-bearing consumers.
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

    @Test fun non_pack_engine_does_not_add_pack_shims() {
        val profile = EngineProfile(engine = "pack:unknown")
        val urls = resolveShimUrls(profile, resolvedMode = "native-controller")
        assertTrue("no pack-rmmv for unknown engine: $urls", !urls.contains("/_shims/packs/rmmv.js"))
        assertTrue("no pack-c3 for unknown engine: $urls", !urls.contains("/_shims/packs/c3.js"))
    }

    // ---------------- shim chain order ----------------

    // audio-decode-serial wraps BaseAudioContext.prototype.decodeAudioData -- index 0 so the
    // wrapper is in place before any game code captures decodeAudioData off the prototype.
    // web-audio-compat patches AudioListener/PannerNode/etc. prototypes -- index 1, before
    // audio-latency's AudioContext wrapper or any game code instantiates AudioContext.
    // audio-latency wraps AudioContext globally -- index 2, still before game code.
    // require-dispatcher installs window.require which fs/path/steamworks consume at parse
    // time -- index 3, still before any game JS that calls require().
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

    @Test fun resolveShimUrls_universalRegistration_appliesEvenWithNullProfile() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = false)
        assertTrue("fs shim must always be injected", urls.contains(ShimBundles.urlFor(ShimBundles.FS_SHIM_ID)!!))
        assertTrue("path shim must always be injected", urls.contains(ShimBundles.urlFor(ShimBundles.PATH_SHIM_ID)!!))
        assertTrue("dispatcher must always be injected", urls.contains(ShimBundles.urlFor(ShimBundles.REQUIRE_DISPATCHER_ID)!!))
    }

    // input-synth.js drains __gnInputBridge for KEY_*/MOUSE_* synth and is unconditional.
    // gamepad.js is the single navigator.getGamepads polyfill -- overlay taps and physical input
    // both write the same profile.gamepadState.
    @Test fun resolveShimUrls_inputSynth_universallyInjected() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = false)
        assertTrue("input-synth shim must be injected: $urls", urls.contains(ShimBundles.urlFor(ShimBundles.INPUT_SYNTH_ID)!!))
    }

    // gamepad-kbd-suppress.js is default-on (suppressGamepadKbdEcho defaults true), so the
    // null-profile path must include it.
    @Test fun gamepadKbdSuppress_default_on_when_profile_null() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "", includeDiagnostic = false)
        assertTrue(
            "gamepad-kbd-suppress shim must be injected by default: $urls",
            urls.contains(ShimBundles.urlFor(ShimBundles.GAMEPAD_KBD_SUPPRESS_ID)!!),
        )
    }

    // opt-out for packs that genuinely want chromium's gamepad->kbd auto-dispatch.
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

    // a pack JSON with `"suppressGamepadKbdEcho": true` must not double-inject.
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

    // desktopUaSpoof defaults off -- the shim must NOT appear unless opted in.
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
