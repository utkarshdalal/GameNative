package app.gamenative.html5.host

import android.content.Context
import app.gamenative.PrefManager
import timber.log.Timber

// registry: TitleQuirks.EFFEKSEER_WASM.
// gate for the Effekseer WASM stub workaround. RMMZ ships Effekseer (particle effects engine)
// which compiles to a ~1.2MB WASM module + ~32MB linear memory. on chromium WebView 109
// (system WebView on Adreno 830 / firmware-locked devices) the combination of Effekseer's
// instantiated WASM + active WebAudio output triggers a deterministic audio CHECK in chromium's
// renderer audio path after ~10-30s of playback. SIGTRAP → renderer dies → exit to library.
//
// confirmed empirically:
// - bug present in chromium 109.0.5414.123 (system WebView on tested device)
// - reproduces with Effekseer 1.70b AND 1.70e (LO's bundled version + latest upstream)
// - blocking the AudioContext, the renderer init, or the destination connect did NOT help
// - only preventing WASM instantiation prevents the crash
// - upstream chromium fix unknown (issue tracker pages locked); newer WebView versions may
//   have fixed it. we apply the stub conservatively for older majors.
//
// workaround: serve a stub for js/libs/effekseer.min.js that defines window.effekseer with
// initRuntime calling onLoad immediately and createContext returning null. Effekseer's WASM
// is never fetched; RMMZ's Graphics._createEffekseerContext sees null and skips renderer
// setup. particle effects are silent + invisible; everything else works.
object EffekseerWasmGate {
    // chromium major version below which the workaround applies in "auto" mode. 109 is
    // confirmed broken; 124+ is assumed-fixed (the Odin 3 test device runs WV124 and is
    // the validation target for "newer WebView, no crash needed"). adjust upward if a
    // future test shows the bug persists past this version.
    const val AFFECTED_BELOW_MAJOR: Int = 124

    fun shouldStubWasm(context: Context): Boolean {
        return when (PrefManager.html5EffekseerWasmStubMode) {
            "on" -> {
                Timber.tag("EffekseerWasmGate").i("stub forced ON via pref")
                true
            }
            "off" -> {
                Timber.tag("EffekseerWasmGate").i("stub forced OFF via pref")
                false
            }
            else -> {
                val major = ChromiumVersionGate.getMajor(context) ?: 0
                val apply = major < AFFECTED_BELOW_MAJOR
                Timber.tag("EffekseerWasmGate").d(
                    "auto resolve: chromium-major=%d threshold=%d → stub=%s",
                    major, AFFECTED_BELOW_MAJOR, apply,
                )
                apply
            }
        }
    }

    // tiny JS body served when stubbing. defines window.effekseer with a no-op initRuntime
    // (synchronously fires onLoad) and createContext returning null. RMMZ tolerates null --
    // see rmmz_core.js Graphics._createEffekseerContext (try-catch that skips on null).
    val stubScript: String = """
        (function () {
            window.effekseer = {
                initRuntime: function (wasmUrl, onLoad, onError) {
                    try { console.log('[effekseer-stub] initRuntime ' + wasmUrl + ' (stubbed — chromium-109 audio CHECK workaround)'); } catch (_) {}
                    try { setTimeout(onLoad, 0); } catch (_) {}
                },
                createContext: function () { return null; }
            };
        })();
    """.trimIndent()
}
