package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Test

// EXECUTES shims/packs/c3.js under Rhino. injections that arrive before the runtime hook is ready
// are queued for replay; the queue is observed here through what actually gets replayed, since
// pendingInjections is closure-private and the shim exposes no accessor (and shouldn't grow one
// just for a test).
class C3PointerQueueExecTest {

    // c3.js touches a few browser globals at load. setTimeout is a manual queue so hookWhenReady's
    // 100-retry budget runs deterministically instead of on a real clock.
    private fun runtime(): ShimJsRuntime {
        val js = ShimJsRuntime().installBase64().installProxyShim()
        js.eval(
            """
            var __gnTimers = [];
            function setTimeout(fn, ms) { __gnTimers.push(fn); return __gnTimers.length; }
            function drainTimers(max) {
                var n = 0;
                while (__gnTimers.length > 0 && n < max) {
                    var fn = __gnTimers.shift();
                    try { fn(); } catch (e) {}
                    n++;
                }
                return n;
            }
            var performance = { now: function () { return 0; } };
            var console = { log: function () {}, warn: function () {}, error: function () {} };
            var __gnStyle = { id: '', textContent: '' };
            var document = {
                getElementById: function () { return null; },
                createElement: function () { return __gnStyle; },
                documentElement: { appendChild: function () {} },
                head: { appendChild: function () {} },
            };
            window.outerWidth = 1280; window.innerWidth = 1280;
            window.outerHeight = 720; window.innerHeight = 720;
            window.document = document;
            """.trimIndent(),
        )
        js.load("require-dispatcher.js")
        js.load("packs/c3.js")
        return js
    }

    // makes DOMHandler appear, lets one retry tick install the hook, then pushes a native pointer
    // event through it -- which is what captures pointerInstance and replays the queue.
    private val installHookAndReplay = """
        var __gnReplayed = 0;
        self.DOMHandler = function () {};
        self.DOMHandler.prototype._PostToRuntimeMaybeSync = function (type, payload, opts) {
            // count only the replayed injections (all pointermove); the pointerdown below is the
            // trigger and reaches this stub through the installed wrapper too.
            if (type === 'pointermove') __gnReplayed++;
        };
        drainTimers(1);
        var h = new self.DOMHandler();
        self.DOMHandler.prototype._PostToRuntimeMaybeSync.call(
            h, 'pointerdown', { pointerType: 'mouse' }, {},
        );
    """

    @Test
    fun queuedInjectionsReplayOnceTheHookInstalls() {
        runtime().use { js ->
            js.eval("for (var i = 0; i < 5; i++) window.__gnC3InjectMousePointer('pointermove', i, i, 0);")
            js.eval(installHookAndReplay)
            assertEquals("5", js.evalString("String(__gnReplayed)"))
        }
    }

    @Test
    fun queueIsCappedWhileTheHookIsStillPending() {
        // a c2runtime title has no DOMHandler at all, and even a real c3 title may never capture a
        // pointerInstance in a touch-only session -- either way this queue must not grow unbounded.
        runtime().use { js ->
            js.eval("for (var i = 0; i < 1000; i++) window.__gnC3InjectMousePointer('pointermove', i, i, 0);")
            js.eval(installHookAndReplay)
            assertEquals("capped at the documented 256, not 1000", "256", js.evalString("String(__gnReplayed)"))
        }
    }

    @Test
    fun retryBudgetTerminates() {
        // c2runtime path: no DOMHandler ever appears, so hookWhenReady must stop rescheduling
        // rather than retry for the life of the session.
        // NOTE: the give-up ALSO clears the queue and drops later injections, and that half is not
        // observable from JS -- pendingInjections is private and, the budget being spent, no hook
        // can install to replay it. queueIsCappedWhileTheHookIsStillPending is what bounds memory
        // in every case; this test only pins the retry loop terminating.
        runtime().use { js ->
            js.eval("for (var i = 0; i < 10; i++) window.__gnC3InjectMousePointer('pointermove', i, i, 0);")
            js.eval("drainTimers(500);")
            assertEquals("no retry left scheduled", "0", js.evalString("String(__gnTimers.length)"))
        }
    }
}
