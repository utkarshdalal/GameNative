package app.gamenative.html5.shim

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pure-JVM SOURCE-level coverage of packs/electron.js -- grep-level structural invariants on
// the shim JS (precedent: SteamworksStubTest). NOTE: no JVM JS engine is wired in, so the shim
// is NOT executed here; the *_jvmMirror tests below assert a Kotlin copy of the greenworks
// regex (kept honest by the adjacent assertion that the literal regex is present in the JS),
// and runtime behavior is validated by on-device smoke testing.
class ShimScriptElectronTest {

    private fun load(path: String): String {
        val candidates = listOf(
            File(path),
            File(path.removePrefix("app/")),
            File("../$path"),
        ).filter { it.exists() }
        check(candidates.isNotEmpty()) { "$path not found" }
        return candidates.first().readText(Charsets.UTF_8)
    }

    private fun electronJs(): String = load("app/src/main/assets/html5/shims/packs/electron.js")
    private fun electronJson(): String = load("app/src/main/assets/html5/packs/electron.json")

    // ---------- pack JSON ----------

    @Test fun pack_json_declares_engineAndShims() {
        val j = electronJson()
        assertTrue(j.contains("\"engine\": \"pack:electron\"") || j.contains("\"engine\":\"pack:electron\""))
        assertTrue(j.contains("\"pack-electron\""))
        // steamworks-noop is host-level always-inject; no longer required in pack JSON
    }

    // ---------- electron.js structure ----------

    @Test fun electron_js_registers_bareName() {
        assertTrue(electronJs().contains("window.require.register('electron',"))
    }

    @Test fun electron_js_appGetPath_readsFromGnElectronCtx() {
        assertTrue(electronJs().contains("window.__gnElectronCtx"))
        assertTrue(electronJs().contains("getPath: function (name)"))
    }

    @Test fun electron_js_lifecycle_onReadyImplemented() {
        // app.on('ready') delivers via setTimeout(0).
        assertTrue(electronJs().contains("on: function (event, cb)"))
        assertTrue(electronJs().contains("event === 'ready'"))
        assertTrue(electronJs().contains("setTimeout(function ()"))
    }

    @Test fun electron_js_lifecycle_whenReadyResolves() {
        assertTrue(electronJs().contains("whenReady: function () { return Promise.resolve(); }"))
    }

    // ---------- NOT_IMPLEMENTED_V1 proxies ----------

    @Test fun electron_js_enumerates_throwing_namespace_proxies() {
        // each throw-on-access namespace gets its own Proxy so logcat NOT_IMPLEMENTED_V1
        // tags are per-namespace greppable. ipcRenderer is intentionally absent from this list —
        // it's a silent-noop stub (see electron_js_ipcRenderer_isNoopStub) because preload
        // scripts (e.g. Cookie Clicker) call .on/.send during boot and throwing kills the game.
        val js = electronJs()
        listOf(
            "BrowserWindow", "shell", "dialog", "Menu",
            "Tray", "powerSaveBlocker", "screen", "globalShortcut", "webContents",
        ).forEach { ns ->
            assertTrue("missing namespace proxy: $ns", js.contains("makeNotImplementedProxy('$ns'"))
        }
    }

    @Test fun electron_js_browserWindow_hasConstructTrap() {
        // construct trap is REQUIRED for BrowserWindow (games do new BrowserWindow(...))
        assertTrue(electronJs().contains("makeNotImplementedProxy('BrowserWindow', true)"))
    }

    @Test fun electron_js_ipcRenderer_isNoopStub() {
        // ipcRenderer must NOT throw on .on/.send/.invoke — preload scripts capture it during
        // boot (Cookie Clicker 1454400). contract: built by makeIpcRendererNoop (not the
        // throwing factory), and the stub exposes the standard IPC surface as functions.
        val js = electronJs()
        assertTrue("ipcRenderer must be wired to the noop factory",
            js.contains("ipcRenderer:") && js.contains("makeIpcRendererNoop()"))
        assertTrue("ipcRenderer must NOT use the throwing factory",
            !js.contains("makeNotImplementedProxy('ipcRenderer'"))
        listOf("send:", "sendSync:", "invoke:", "on:", "once:", "off:", "removeListener:").forEach { method ->
            assertTrue("ipcRenderer noop missing method $method", js.contains(method))
        }
    }

    @Test fun electron_js_hasConstructHandlerImpl() {
        val js = electronJs()
        assertTrue("handler.construct impl missing", js.contains("handler.construct"))
        assertTrue("construct trap throws via logNotImplemented", js.contains("(construct)"))
    }

    // ---------- greenworks pattern ----------

    @Test fun electron_js_declares_greenworks_anchored_regex() {
        val js = electronJs()
        // literal regex from — exact match required (character-for-character).
        assertTrue(
            "greenworks regex literal missing or modified — must be /(?:^|[/\\\\])greenworks(?:-[^./\\\\]+)?\\.node$/",
            js.contains("/(?:^|[/\\\\])greenworks(?:-[^./\\\\]+)?\\.node$/"),
        )
    }

    @Test fun electron_js_registers_pattern_forGreenworks() {
        val js = electronJs()
        assertTrue(js.contains("window.require.register.pattern("))
        assertTrue(js.contains("GREENWORKS_NODE_REGEX,"))
        assertTrue(js.contains("window.require('greenworks')"))
    }

    @Test fun require_nonGreenworksDotNode_returnsStructuredError_withProxyProto() {
        // non-greenworks .node returns structured error object with __proto__ Proxy.
        // this single test covers BOTH the __notImplemented marker AND the __proto__ Proxy
        // throw-on-access contract (subsumes old "returnsStructuredError" test).
        val js = electronJs()
        assertTrue("NODE_MODULE_REGEX declared", js.contains("NODE_MODULE_REGEX"))
        assertTrue("structured error marker present", js.contains("__notImplemented: 'NOT_IMPLEMENTED_V1'"))
        assertTrue("moduleType marker present", js.contains("moduleType: 'native-.node'"))
        // literal __proto__ Proxy contract — must be `__proto__: new Proxy`. without this,
        // method access silently returns undefined.
        assertTrue("__proto__ Proxy baked in", js.contains("__proto__: new Proxy"))
        // proxy's get trap must return a function that throws — not a plain value.
        assertTrue(
            "proxy get trap throws on access",
            js.contains("throw new Error('NOT_IMPLEMENTED_V1: native-.node access on '"),
        )
        // template registered via pattern dispatcher (not per-call factory).
        assertTrue(
            "template registered via register.pattern",
            js.contains("NOT_IMPLEMENTED_NODE_TEMPLATE"),
        )
    }

    // ---------- regex semantics (SMOKE-time parity check — done at JS runtime) ----------

    @Test fun greenworks_regex_matchesAllArchVariants_jvmMirror() {
        // JVM Pattern mirror of the JS regex, verifying the literal SPEC min-4 acceptance rows.
        val jsPattern = Regex("(?:^|[/\\\\])greenworks(?:-[^./\\\\]+)?\\.node$")
        listOf(
            "./greenworks.node",
            "./greenworks-linux32.node",
            "./greenworks-linux64.node",
            "./greenworks-win32.node",
            "./greenworks-win64.node",
            "./greenworks-darwin-x64.node",
            "./greenworks-darwin-arm64.node",
            "greenworks.node",
            "native/greenworks-linux64.node",
        ).forEach { path ->
            assertTrue("expected regex match for $path", jsPattern.containsMatchIn(path))
        }
    }

    @Test fun greenworks_regex_rejectsMasquerading_jvmMirror() {
        val jsPattern = Regex("(?:^|[/\\\\])greenworks(?:-[^./\\\\]+)?\\.node$")
        listOf(
            "./my-greenworks-linux64.node",
            "./greenworks-linux64.node.js",
            "./greenworks/linux64.node",
        ).forEach { path ->
            assertFalse("expected NO match for $path", jsPattern.containsMatchIn(path))
        }
    }
}
