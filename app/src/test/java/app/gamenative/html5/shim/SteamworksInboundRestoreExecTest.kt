package app.gamenative.html5.shim

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

// EXECUTES steamworks.js's parse-time greenworks cloud restore under Rhino. outbound capture
// stores localStorage values as base64 of their UTF-8 bytes, so inbound must decode UTF-8 or
// non-ASCII saves change on every round trip.
class SteamworksInboundRestoreExecTest {
    private fun restore(files: Map<String, ByteArray>): ShimJsRuntime {
        val json = files.entries.joinToString(",", "{", "}") { (name, bytes) ->
            "\"$name\":\"${Base64.getEncoder().encodeToString(bytes)}\""
        }
        val js = ShimJsRuntime().installBase64().installProxyShim()
        js.eval(
            """
            window.localStorage = {
                _m: {},
                setItem: function (k, v) { this._m[k] = String(v); },
                getItem: function (k) { return Object.prototype.hasOwnProperty.call(this._m, k) ? this._m[k] : null; },
                removeItem: function (k) { delete this._m[k]; },
                key: function (i) { return Object.keys(this._m)[i] || null; },
                get length() { return Object.keys(this._m).length; },
            };
            var __gnSteamworksBridge = { getInboundCloudJson: function () { return '$json'; } };
            """.trimIndent(),
        )
        return js.load("require-dispatcher.js").load("steamworks.js")
    }

    @Test
    fun restores_utf8_text_as_the_original_string() {
        val text = "café ✓ 日本 😀"
        restore(mapOf("save.txt" to text.toByteArray(Charsets.UTF_8))).use { js ->
            assertEquals(text, js.evalString("window.localStorage.getItem('gn:gw:save.txt')"))
        }
    }

    @Test
    fun keeps_raw_byte_string_when_not_utf8() {
        restore(mapOf("bin.dat" to byteArrayOf(0xff.toByte(), 0x41))).use { js ->
            assertEquals("ÿA", js.evalString("window.localStorage.getItem('gn:gw:bin.dat')"))
        }
    }
}
