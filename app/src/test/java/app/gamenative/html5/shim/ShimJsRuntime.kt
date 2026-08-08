package app.gamenative.html5.shim

import java.io.File
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

// minimal Rhino harness that EXECUTES the pure-logic shims so their behavior is locked by real
// execution, not source-string assertions. ONLY suitable for shims that need nothing beyond
// window/self/console (path.js, require-dispatcher.js, os.js). browser/bridge-coupled shims
// (fs/steamworks/electron/gamepad) depend on the DOM + Kotlin @JavascriptInterface bridges and
// are validated on-device, not here -- do NOT try to run them through this.
//
// NOTE: Rhino is not Chromium V8. these shims are deliberately ES5 (var/function, no class/async)
// so the two engines agree on them; if a shim moves to modern JS this harness will fail to parse,
// which is the signal to either keep it ES5 or drop it from JVM coverage.
class ShimJsRuntime : AutoCloseable {
    private val cx: Context = Context.enter().apply {
        languageVersion = Context.VERSION_ES6
        optimizationLevel = -1 // interpreted -- no bytecode gen; fine for tiny scripts
    }
    private val scope: Scriptable = cx.initStandardObjects()

    init {
        // browser-ish globals the shims touch. window === self (like a document context);
        // console is a no-op sink (shims only log when __gnShimVerbose is set, left unset here).
        cx.evaluateString(
            scope,
            """
            var console = { log: function(){}, warn: function(){}, error: function(){} };
            var window = {};
            var self = window;
            """.trimIndent(),
            "<bootstrap>", 1, null,
        )
    }

    fun load(assetRelPath: String): ShimJsRuntime {
        cx.evaluateString(scope, readShimAsset(assetRelPath), assetRelPath, 1, null)
        return this
    }

    // opt-in stubs for the few shims that need a hair more than window/console. NOT installed by
    // default -- the pure-shim path stays minimal; tests for fs.js / url-sanitize.js opt in.

    // btoa/atob over the Latin1 byte-string domain (the only domain fs.js/crypto build, via
    // String.fromCharCode(byte)). Rhino has no base64; without this fs binary round-trips can't run.
    fun installBase64(): ShimJsRuntime {
        // define as scope globals (var at top-level eval) so the shims' BARE btoa/atob resolve --
        // window is a separate object here, so window.btoa alone would not satisfy `btoa(...)`.
        eval(
            """
            var __b64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
            var btoa = function (s) {
                s = String(s); var out = '';
                for (var i = 0; i < s.length; i += 3) {
                    var b0 = s.charCodeAt(i) & 0xff;
                    var h1 = i + 1 < s.length, h2 = i + 2 < s.length;
                    var b1 = h1 ? s.charCodeAt(i + 1) & 0xff : 0;
                    var b2 = h2 ? s.charCodeAt(i + 2) & 0xff : 0;
                    out += __b64.charAt(b0 >> 2) + __b64.charAt(((b0 & 3) << 4) | (b1 >> 4));
                    out += h1 ? __b64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=';
                    out += h2 ? __b64.charAt(b2 & 63) : '=';
                }
                return out;
            };
            var atob = function (s) {
                s = String(s).replace(/[^A-Za-z0-9+/]/g, ''); var out = '';
                for (var i = 0; i < s.length; i += 4) {
                    var d0 = __b64.indexOf(s.charAt(i)), d1 = __b64.indexOf(s.charAt(i + 1));
                    var d2 = i + 2 < s.length ? __b64.indexOf(s.charAt(i + 2)) : -1;
                    var d3 = i + 3 < s.length ? __b64.indexOf(s.charAt(i + 3)) : -1;
                    out += String.fromCharCode((d0 << 2) | (d1 >> 4));
                    if (d2 >= 0) out += String.fromCharCode(((d1 & 15) << 4) | (d2 >> 2));
                    if (d3 >= 0) out += String.fromCharCode(((d2 & 3) << 6) | d3);
                }
                return out;
            };
            window.btoa = btoa;
            window.atob = atob;
            """.trimIndent(),
        )
        return this
    }

    // identity Proxy. Rhino 1.7.15 ships NO native Proxy (verified: no NativeProxy in the jar),
    // so fs.js -- which wraps its dispatch table + Buffer-likes in Proxy at load -- can't load
    // without this. the shim returns the TARGET verbatim and ignores the handler: faithful ONLY
    // for props that already live on the target (fs.*Sync dispatch methods, buf.toString/slice/
    // length, the path transforms they call). get-trap fallbacks (unknown-method NOT_IMPLEMENTED
    // throws, fs.promises, numeric byte indexing buf[i], Buffer.from) are NOT emulated -- do not
    // assert them here; they stay source-locked + device-validated.
    fun installProxyShim(): ShimJsRuntime {
        eval(
            """
            if (typeof Proxy === 'undefined') {
                this.Proxy = function (target, handler) { return target; };
            }
            """.trimIndent(),
        )
        return this
    }

    // recording XMLHttpRequest. tests drive behavior via window.__xhr.responder(method,url) ->
    // {status, body}; opened URLs land in window.__xhr.opens (+ .lastUrl). lets fs.js asset
    // fallbacks and url-sanitize.js's open() rewrite be asserted without a real browser.
    fun installXhr(): ShimJsRuntime {
        eval(
            """
            window.__xhr = {
                opens: [],
                lastUrl: null,
                responder: function (method, url) { return { status: 404, body: '' }; },
            };
            function XMLHttpRequest() { this.status = 0; this.responseText = ''; this._m = ''; this._u = ''; }
            XMLHttpRequest.prototype.open = function (method, url, async) {
                this._m = method; this._u = url;
                window.__xhr.lastUrl = url;
                window.__xhr.opens.push(method + ' ' + url);
            };
            XMLHttpRequest.prototype.overrideMimeType = function () {};
            XMLHttpRequest.prototype.setRequestHeader = function () {};
            XMLHttpRequest.prototype.send = function () {
                var r = window.__xhr.responder(this._m, this._u) || {};
                this.status = r.status || 0;
                this.responseText = (r.body != null) ? r.body : '';
            };
            """.trimIndent(),
        )
        return this
    }

    fun evalString(js: String): String = Context.toString(cx.evaluateString(scope, js, "<eval>", 1, null))

    fun evalBoolean(js: String): Boolean = Context.toBoolean(cx.evaluateString(scope, js, "<eval>", 1, null))

    fun eval(js: String): Any? = cx.evaluateString(scope, js, "<eval>", 1, null)

    override fun close() {
        Context.exit()
    }

    private fun readShimAsset(rel: String): String {
        val candidates = listOf(
            File("src/main/assets/html5/shims/$rel"),
            File("app/src/main/assets/html5/shims/$rel"),
            File("../app/src/main/assets/html5/shims/$rel"),
        ).filter { it.exists() }
        check(candidates.isNotEmpty()) { "shim asset not found: $rel (cwd=${File(".").absolutePath})" }
        return candidates.first().readText(Charsets.UTF_8)
    }
}
