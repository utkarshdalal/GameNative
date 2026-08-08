package app.gamenative.html5.shim

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// EXECUTES require-dispatcher.js under Rhino and asserts the real dispatch behavior (exact >
// pattern > originalRequire > miss). complements the source-level RequireDispatcherPatternTest,
// which only greps the asset. these catch logic regressions a string match can't.
class RequireDispatcherExecTest {
    private lateinit var js: ShimJsRuntime

    @Before
    fun setUp() {
        js = ShimJsRuntime().load("require-dispatcher.js")
    }

    @After
    fun tearDown() {
        js.close()
    }

    @Test
    fun installs_window_require() {
        assertEquals("function", js.evalString("typeof window.require"))
        assertEquals("function", js.evalString("typeof window.require.register"))
        assertEquals("function", js.evalString("typeof window.require.register.pattern"))
    }

    @Test
    fun exact_register_then_require_returns_impl() {
        js.eval("window.require.register('foomod', { ok: 1 })")
        assertEquals("1", js.evalString("String(window.require('foomod').ok)"))
    }

    @Test
    fun pattern_register_matches_by_regex() {
        js.eval("window.require.register.pattern(/^greenworks/, { gw: true })")
        assertTrue(js.evalBoolean("window.require('greenworks/lib/x').gw === true"))
    }

    @Test
    fun exact_match_beats_pattern() {
        js.eval("window.require.register('m', 'exact'); window.require.register.pattern(/^m$/, 'pat')")
        assertEquals("exact", js.evalString("window.require('m')"))
    }

    @Test
    fun bare_module_miss_returns_undefined() {
        // titles probe for optional Node built-ins and assume undefined == not-provided.
        assertEquals("undefined", js.evalString("typeof window.require('buffer')"))
    }

    @Test
    fun filepath_miss_throws_module_not_found() {
        assertEquals(
            "MODULE_NOT_FOUND",
            js.evalString(
                "(function(){ try { window.require('./nope.js'); return 'NO_THROW'; } " +
                    "catch (e) { return e.code; } })()",
            ),
        )
    }

    @Test
    fun require_main_filename_is_empty_string() {
        assertEquals("", js.evalString("window.require.main.filename"))
    }

    @Test
    fun pattern_first_match_wins_by_insertion_order() {
        // two patterns both match -> the earliest-registered impl wins (pack shims register first).
        js.eval("window.require.register.pattern(/^x/, 'first'); window.require.register.pattern(/^x/, 'second');")
        assertEquals("first", js.evalString("window.require('xyz')"))
    }

    @Test
    fun throwing_pattern_regex_is_skipped_not_fatal() {
        // a bad regex whose .test throws must be swallowed and the loop continue -- a single
        // misbehaving pattern can't take down every require() in the title.
        js.eval("window.require.register.pattern({ test: function () { throw new Error('bad'); } }, 'X');")
        assertEquals("undefined", js.evalString("typeof window.require('nope')"))
    }

    @Test
    fun original_require_is_consulted_then_falls_through() {
        // a fresh runtime: window.require must be set BEFORE the dispatcher loads so it captures
        // the prior impl as originalRequire (Tyrano's CJS-recovery pattern relies on this).
        ShimJsRuntime().use { local ->
            local.eval(
                "window.require = function (id) { if (id === 'fromOrig') return 'ORIG'; throw new Error('orig-miss'); };",
            )
            local.load("require-dispatcher.js")
            // miss on dispatch table -> delegate to captured originalRequire.
            assertEquals("ORIG", local.evalString("window.require('fromOrig')"))
            // originalRequire throwing -> fall through to bare-name undefined, not a crash.
            assertEquals("undefined", local.evalString("typeof window.require('whatever')"))
        }
    }
}
