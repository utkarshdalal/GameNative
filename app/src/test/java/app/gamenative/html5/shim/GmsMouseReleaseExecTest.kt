package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// EXECUTES shims/packs/gms.js under Rhino. the shim swallows native end-events so a sub-frame tap
// can't clear the engine's _de before it is polled, then re-dispatches ONE synthetic pointerup.
// a physical mouse produces no touch events, so if that re-dispatch is scheduled only from
// touchend/touchcancel, a mouse release is swallowed and never replaced: _de sticks at 1, hover
// works, buttons die after the first click.
class GmsMouseReleaseExecTest {

    private fun runtime(): ShimJsRuntime {
        val js = ShimJsRuntime().installBase64().installProxyShim()
        js.eval(
            """
            var timers = [];
            function setTimeout(fn, ms) { timers.push({ fn: fn, ms: ms }); return timers.length; }
            function fireTimers() { var t = timers; timers = []; for (var i = 0; i < t.length; i++) { try { t[i].fn(); } catch (e) {} } }

            var dispatched = [];
            var canvasListeners = {};
            var canvas = {
                addEventListener: function (n, fn) { (canvasListeners[n] = canvasListeners[n] || []).push(fn); },
                dispatchEvent: function (e) { dispatched.push(e.type + '|pt=' + (e.pointerType || '-') + '|synth=' + (e.__gnGmsSynth ? 1 : 0)); return true; },
            };
            var docListeners = {};
            var document = {
                readyState: 'complete',
                querySelector: function () { return canvas; },
                addEventListener: function (n, fn) { (docListeners[n] = docListeners[n] || []).push(fn); },
                createElement: function () { return { style: {} }; },
                head: { appendChild: function () {} },
                documentElement: { appendChild: function () {} },
            };
            window.document = document;
            window.addEventListener = function () {};
            window.dispatchEvent = function (e) { dispatched.push('WINDOW:' + e.type + '|synth=' + (e.__gnGmsSynth ? 1 : 0)); return true; };
            function MouseEvent(type, init) {
                this.type = type;
                this.clientX = init && init.clientX;
                this.clientY = init && init.clientY;
            }
            function PointerEvent(type, init) {
                this.type = type;
                this.pointerId = init && init.pointerId;
                this.pointerType = init && init.pointerType;
                this.clientX = init && init.clientX;
                this.clientY = init && init.clientY;
            }
            function fire(name, ev) {
                (canvasListeners[name] || []).forEach(function (fn) { fn(ev); });
            }
            """.trimIndent(),
        )
        js.load("packs/gms.js")
        return js
    }

    @Test
    fun aSwallowedMouseReleaseStillSchedulesTheSyntheticPointerUp() {
        runtime().use { js ->
            js.eval(
                """
                fire('pointerdown', { pointerId: 1, clientX: 10, clientY: 20, pointerType: 'mouse',
                                      stopImmediatePropagation: function () {} });
                fire('pointerup', { pointerId: 1, clientX: 10, clientY: 20, pointerType: 'mouse',
                                    currentTarget: canvas, stopImmediatePropagation: function () {} });
                fireTimers();
                """.trimIndent(),
            )
            val got = js.evalString("dispatched.join(',')")
            assertTrue(
                "the synthetic release must carry pointerType 'mouse' -- a 'touch' release does not " +
                    "clear the engine's button state for a mouse pointer; got " + got,
                got.contains("pointerup|pt=mouse|synth=1"),
            )
            assertTrue(
                "and the swallowed legacy window mouseup must be replaced too; got " + got,
                got.contains("WINDOW:mouseup|synth=1"),
            )
        }
    }

    @Test
    fun touchStillGetsExactlyOneSyntheticRelease() {
        // the touch path is unchanged, and a touch pointerup must NOT also schedule one (that would
        // double-release): pointerType is 'touch', so only touchend schedules.
        runtime().use { js ->
            js.eval(
                """
                fire('pointerdown', { pointerId: 2, clientX: 5, clientY: 6, pointerType: 'touch',
                                      stopImmediatePropagation: function () {} });
                fire('pointerup', { pointerId: 2, clientX: 5, clientY: 6, pointerType: 'touch',
                                    currentTarget: canvas, stopImmediatePropagation: function () {} });
                (docListeners['touchend'] || []).forEach(function (fn) { fn({}); });
                fireTimers();
                """.trimIndent(),
            )
            val synths = js.evalString(
                "String(dispatched.filter(function (d) { return d === 'pointerup|pt=touch|synth=1'; }).length)",
            )
            assertEquals("exactly one synthetic release for a touch tap", "1", synths)
            assertEquals(
                "touch must NOT get the mouse-only mouseup replacement",
                "0",
                js.evalString("String(dispatched.filter(function (d) { return d.indexOf('mouseup') >= 0; }).length)"),
            )
        }
    }
}
