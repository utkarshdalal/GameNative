package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// EXECUTES shims/touch.js under Rhino. absolute mode fires a mousedown on touchstart; if the
// matching mouseup is gated on `primary.consumed`, any sequence that sets that flag -- a
// long-press firing, or a second finger arriving -- ends with the page still believing the left
// button is held down.
class TouchButtonReleaseExecTest {

    private fun runtime(): ShimJsRuntime {
        val js = ShimJsRuntime().installBase64().installProxyShim()
        js.eval(
            """
            var dispatched = [];
            var timers = [];
            function setTimeout(fn, ms) { timers.push(fn); return timers.length; }
            function clearTimeout(id) { }
            function requestAnimationFrame(fn) { return 0; }
            function fireTimers() { var t = timers; timers = []; for (var i = 0; i < t.length; i++) { try { t[i](); } catch (e) {} } }

            // record type AND button: a two-finger tap fires its own right-button mouseup, which
            // would otherwise mask a missing LEFT-button release.
            var target = { dispatchEvent: function (e) { dispatched.push(e.type + ':' + e.button); return true; } };
            function MouseEvent(type, init) { this.type = type; this.button = (init && init.button) | 0; }
            var listeners = {};
            var document = {
                addEventListener: function (name, fn) { listeners[name] = fn; },
                removeEventListener: function () {},
                elementFromPoint: function () { return target; },
                body: target,
                documentElement: target,
                activeElement: null,
                createElement: function () { return { style: {} }; },
                head: { appendChild: function () {} },
            };
            window.document = document;
            window.innerWidth = 1280; window.innerHeight = 720;
            window.addEventListener = function () {};
            window.visualViewport = null;
            var console = { log: function () {}, warn: function () {}, error: function () {} };
            var performance = { now: function () { return 0; } };
            // absolute pointer mode with tap + long-press on -- the shipped default shape.
            window.__gnGestureConfig = {
                tapEnabled: true, dragEnabled: true, longPressEnabled: true,
                longPressDelay: 300, longPressAction: 'right_click',
                cursorMode: 'absolute', doubleTapEnabled: false,
                twoFingerTapEnabled: true, threeFingerTapEnabled: false,
            };
            function touch(id, x, y) { return { identifier: id, clientX: x, clientY: y, target: target }; }
            function evt(touches, changed) {
                return { touches: touches, changedTouches: changed, preventDefault: function () {}, target: target };
            }
            """.trimIndent(),
        )
        js.load("touch.js")
        return js
    }

    @Test
    fun longPressStillReleasesTheButtonOnTouchEnd() {
        runtime().use { js ->
            js.eval("listeners.touchstart(evt([touch(1, 100, 100)], [touch(1, 100, 100)]));")
            assertTrue("absolute mode fires a left mousedown on touchstart", js.evalString("dispatched.join(',')").contains("mousedown:0"))

            // long-press timer fires: it dispatches its own click and sets `consumed`.
            js.eval("fireTimers();")
            js.eval("dispatched = [];")
            js.eval("listeners.touchend(evt([], [touch(1, 100, 100)]));")

            assertTrue(
                "touchend after a long-press must still release the LEFT button; got " +
                    js.evalString("dispatched.join(',')"),
                js.evalString("dispatched.join(',')").contains("mouseup:0"),
            )
        }
    }

    @Test
    fun secondFingerStillReleasesTheButtonOnTouchEnd() {
        runtime().use { js ->
            js.eval("listeners.touchstart(evt([touch(1, 100, 100)], [touch(1, 100, 100)]));")
            // second finger arrives -> gesture becomes multi-finger, primary is consumed.
            js.eval("listeners.touchstart(evt([touch(1, 100, 100), touch(2, 200, 200)], [touch(2, 200, 200)]));")
            js.eval("dispatched = [];")
            js.eval("listeners.touchend(evt([touch(2, 200, 200)], [touch(1, 100, 100)]));")

            assertTrue(
                "touchend after a second finger must still release the LEFT button; got " +
                    js.evalString("dispatched.join(',')"),
                js.evalString("dispatched.join(',')").contains("mouseup:0"),
            )
        }
    }

    @Test
    fun anOrdinaryTapReleasesExactlyOnce() {
        // the release is hoisted out of the tap-classification block, so it must not also fire
        // from inside it.
        runtime().use { js ->
            js.eval("listeners.touchstart(evt([touch(1, 100, 100)], [touch(1, 100, 100)]));")
            js.eval("dispatched = [];")
            js.eval("listeners.touchend(evt([], [touch(1, 100, 100)]));")

            val ups = js.evalString("String(dispatched.filter(function (t) { return t === 'mouseup:0'; }).length)")
            assertEquals("exactly one mouseup for a plain tap", "1", ups)
            assertTrue("and it still classifies as a click", js.evalString("dispatched.join(',')").contains("click:0"))
        }
    }
}
