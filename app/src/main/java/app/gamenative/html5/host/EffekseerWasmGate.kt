package app.gamenative.html5.host

import android.content.Context
import app.gamenative.PrefManager
import timber.log.Timber

// registry: TitleQuirks.EFFEKSEER_WASM.
// on chromium WebView 109, RMMZ's instantiated Effekseer WASM plus active WebAudio output hits a
// deterministic audio CHECK in the renderer after ~10-30s and kills it. only preventing WASM
// instantiation avoids it (blocking AudioContext / renderer init / destination connect does not).
// workaround: stub effekseer.min.js so createContext returns null; RMMZ skips effekseer setup and
// particle effects are simply absent.
object EffekseerWasmGate {
    // "auto" mode threshold: 109 is confirmed broken, 124+ assumed fixed. raise if the bug shows up above it.
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

    // RMMZ's Graphics._createEffekseerContext tolerates a null context.
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
