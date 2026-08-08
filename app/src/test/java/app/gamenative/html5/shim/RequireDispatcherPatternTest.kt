package app.gamenative.html5.shim

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

// pure-JVM SOURCE-level (grep) coverage of the require-dispatcher.js asset -- locks the
// register.pattern API contract so a later JS edit can't silently drop it. NOTE: no JVM JS
// engine is wired in, so this does NOT execute the shim; runtime behavior is validated only by
// on-device smoke testing.
class RequireDispatcherPatternTest {

    private val scriptPath = "app/src/main/assets/html5/shims/require-dispatcher.js"

    private fun source(): String {
        // gradle runs :app tests with cwd=app/ OR project root — candidate-list pattern
        // matches the SteamworksStubTest precedent.
        val candidates = listOf(
            File(scriptPath),
            File(scriptPath.removePrefix("app/")),
            File("../$scriptPath"),
        ).filter { it.exists() }
        check(candidates.isNotEmpty()) { "require-dispatcher.js not found; tried: $scriptPath" }
        return candidates.first().readText(Charsets.UTF_8)
    }

    @Test fun declares_patternDispatchers_array() {
        assertTrue(source().contains("var patternDispatchers = [];"))
    }

    @Test fun declares_register_pattern_api() {
        assertTrue(source().contains("myRequire.register.pattern = function"))
        assertTrue(source().contains("patternDispatchers.push"))
    }

    @Test fun dispatch_order_exact_before_pattern() {
        // exact-match lookup (Object.prototype.hasOwnProperty.call) must appear before
        // the patternDispatchers scan. lock it at source level.
        val src = source()
        val exactIdx = src.indexOf("hasOwnProperty.call(dispatchers")
        val patternIdx = src.indexOf("patternDispatchers[i].regex.test")
        assertTrue("expected exact-match before pattern scan — exact=$exactIdx pattern=$patternIdx", exactIdx in 0 until patternIdx)
    }

    @Test fun dispatch_order_pattern_before_originalRequire() {
        val src = source()
        val patternIdx = src.indexOf("patternDispatchers[i].regex.test")
        val originalIdx = src.indexOf("originalRequire(id)")
        assertTrue("expected pattern scan before fallback — pattern=$patternIdx original=$originalIdx", patternIdx in 0 until originalIdx)
    }

    @Test fun existing_require_main_filename_stub_preserved() {
        // regression guard: require.main.filename stub must still be installed.
        assertTrue(source().contains("window.require.main"))
        assertTrue(source().contains("filename: ''"))
    }

    @Test fun file_path_miss_throws_module_not_found() {
        // Tyrano's index.html uses `window.jQuery = require("./tyrano/libs/jquery.js")` in
        // a try/catch as a CJS-recovery pattern. real Node.js throws on miss → the catch
        // preserves the working window.$ set by the prior <script src>. returning undefined
        // wiped jQuery. file-path discriminator: id starts with '.' or contains '/'.
        val src = source()
        assertTrue("MODULE_NOT_FOUND code missing", src.contains("MODULE_NOT_FOUND"))
        assertTrue("expected throw on file-path miss", src.contains("throw err"))
        assertTrue(
            "expected file-path discriminator",
            src.contains("charAt(0) === '.'") && src.contains("indexOf('/')"),
        )
    }

    @Test fun bare_module_miss_returns_undefined() {
        // many titles probe for optional Node built-ins like 'buffer', 'fs', 'electron'
        // expecting undefined-on-miss. throw-all broke Welcome to Maison Chichigami at
        // require('buffer'). bare-module fallthrough is the legacy contract.
        assertTrue(source().contains("return undefined"))
    }
}
