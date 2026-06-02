package app.gamenative.html5.shim

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// EXECUTES events.js EventEmitter under Rhino. c2/NW.js Steam SDK wrappers do
// `EventEmitter.call(this)` + prototype chaining at first-script execution -- a broken emit/once/
// removeListener throws before the title boots. node-shape behavior locked here.
class ShimEventsExecTest {
    private lateinit var js: ShimJsRuntime

    @Before
    fun setUp() {
        js = ShimJsRuntime().load("require-dispatcher.js").load("events.js")
        js.eval("var EventEmitter = window.require('events'); var e = new EventEmitter();")
    }

    @After
    fun tearDown() {
        js.close()
    }

    @Test
    fun require_returns_constructor_with_self_reference() {
        assertEquals("function", js.evalString("typeof window.require('events')"))
        assertTrue(js.evalBoolean("window.require('events').EventEmitter === window.require('events')"))
    }

    @Test
    fun on_then_emit_invokes_with_args() {
        val sum = js.evalString(
            """
            (function () {
                var got = 0;
                e.on('add', function (a, b) { got = a + b; });
                e.emit('add', 3, 4);
                return String(got);
            })()
            """.trimIndent(),
        )
        assertEquals("7", sum)
    }

    @Test
    fun once_fires_exactly_once() {
        val hits = js.evalString(
            """
            (function () {
                var n = 0;
                e.once('x', function () { n++; });
                e.emit('x'); e.emit('x'); e.emit('x');
                return String(n);
            })()
            """.trimIndent(),
        )
        assertEquals("1", hits)
    }

    @Test
    fun remove_listener_stops_delivery() {
        val hits = js.evalString(
            """
            (function () {
                var n = 0;
                var fn = function () { n++; };
                e.on('y', fn);
                e.emit('y');
                e.removeListener('y', fn);
                e.emit('y');
                return String(n);
            })()
            """.trimIndent(),
        )
        assertEquals("1", hits)
    }

    @Test
    fun remove_listener_targets_once_via_original() {
        // removing a once() listener by its ORIGINAL fn (before it fires) must cancel it.
        val hits = js.evalString(
            """
            (function () {
                var n = 0;
                var fn = function () { n++; };
                e.once('z', fn);
                e.removeListener('z', fn);
                e.emit('z');
                return String(n);
            })()
            """.trimIndent(),
        )
        assertEquals("0", hits)
    }

    @Test
    fun emit_returns_false_when_no_listeners_true_otherwise() {
        assertFalse(js.evalBoolean("e.emit('nobody')"))
        assertTrue(
            js.evalBoolean("(function(){ e.on('somebody', function(){}); return e.emit('somebody'); })()"),
        )
    }

    @Test
    fun throwing_listener_does_not_abort_siblings() {
        // copy.slice + per-listener try/catch -> a thrower must not stop later listeners.
        val tail = js.evalString(
            """
            (function () {
                var reached = 0;
                e.on('t', function () { throw new Error('boom'); });
                e.on('t', function () { reached = 1; });
                e.emit('t');
                return String(reached);
            })()
            """.trimIndent(),
        )
        assertEquals("1", tail)
    }

    @Test
    fun listener_count_and_remove_all() {
        val result = js.evalString(
            """
            (function () {
                e.on('c', function () {}); e.on('c', function () {});
                var before = e.listenerCount('c');
                e.removeAllListeners('c');
                var after = e.listenerCount('c');
                return before + ',' + after;
            })()
            """.trimIndent(),
        )
        assertEquals("2,0", result)
    }
}
