package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// EXECUTES audio-decode-serial.js under Rhino. it needs only BaseAudioContext + Promise, so the
// harness's browser-coupling caveat does not apply. what is pinned here is WHEN the retry copy is
// taken: decodes run one at a time per lane, so a copy taken at call time stays alive for every decode
// still waiting in the queue -- and titles can queue hundreds of decodes (tens of MB of encoded
// audio) at boot.
class AudioDecodeSerialExecTest {

    private fun runtime(): ShimJsRuntime = ShimJsRuntime().also { js ->
        js.eval(
            """
            var sliceCount = 0;
            var pending = [];
            // the retry path waits 50ms; run it now so a rejection settles within one eval.
            function setTimeout(fn) { fn(); return 0; }
            function fakeBuffer(n) {
                return { byteLength: n, slice: function () { sliceCount++; return fakeBuffer(n); } };
            }
            var BaseAudioContext = function () {};
            BaseAudioContext.prototype.decodeAudioData = function (data) {
                return new Promise(function (resolve, reject) { pending.push({ data: data, resolve: resolve, reject: reject }); });
            };
            """.trimIndent(),
        )
        js.load("audio-decode-serial.js")
    }

    @Test
    fun installs_overItsOwnMarker() {
        runtime().use { js ->
            assertTrue(js.evalBoolean("BaseAudioContext.prototype.__gnDecodeSerialized === true"))
        }
    }

    @Test
    fun queuedDecodes_doNotEachHoldARetryCopy() {
        runtime().use { js ->
            js.eval(
                """
                var ctx = new BaseAudioContext();
                for (var i = 0; i < 8; i++) { ctx.decodeAudioData(fakeBuffer(1024 * 1024)); }
                """.trimIndent(),
            )
            // at most the decode that actually started may hold a copy; the seven still queued
            // must not. copying up front would make this 8.
            val slices = js.evalString("String(sliceCount)").toInt()
            assertTrue("queued decodes took $slices retry copies", slices <= 1)
        }
    }

    @Test
    fun smallDecode_doesNotWaitBehindALargeOne() {
        runtime().use { js ->
            js.eval(
                """
                var ctx = new BaseAudioContext();
                ctx.decodeAudioData(fakeBuffer(8 * 1024 * 1024));
                ctx.decodeAudioData(fakeBuffer(8 * 1024 * 1024));
                ctx.decodeAudioData(fakeBuffer(16 * 1024));
                ctx.decodeAudioData(fakeBuffer(16 * 1024));
                """.trimIndent(),
            )
            // one per lane: the first large and the first small; the rest wait in their own lane.
            assertEquals("2", js.evalString("String(pending.length)"))
            assertEquals("16384", js.evalString("String(pending[1].data.byteLength)"))
        }
    }

    @Test
    fun lanesAdvanceIndependently_afterResolve() {
        runtime().use { js ->
            js.eval(
                """
                var ctx = new BaseAudioContext();
                ctx.decodeAudioData(fakeBuffer(8 * 1024 * 1024));
                ctx.decodeAudioData(fakeBuffer(8 * 1024 * 1024 + 1));
                ctx.decodeAudioData(fakeBuffer(16));
                ctx.decodeAudioData(fakeBuffer(17));
                """.trimIndent(),
            )
            js.eval("pending[1].resolve({ duration: 1 });")
            // next small started; the first large is still the only large in flight.
            assertEquals("3", js.evalString("String(pending.length)"))
            assertEquals("17", js.evalString("String(pending[2].data.byteLength)"))
            js.eval("pending[0].resolve({ duration: 1 });")
            assertEquals("4", js.evalString("String(pending.length)"))
            assertEquals("8388609", js.evalString("String(pending[3].data.byteLength)"))
        }
    }

    @Test
    fun laneAdvances_afterADecodeFailsTwice() {
        runtime().use { js ->
            js.eval(
                """
                var ctx = new BaseAudioContext();
                var failed = false;
                ctx.decodeAudioData(fakeBuffer(16)).catch(function () { failed = true; });
                ctx.decodeAudioData(fakeBuffer(17));
                """.trimIndent(),
            )
            // decodes start on a microtask, so settle in a separate eval.
            js.eval("pending[0].reject(new Error('x'));")
            // the retry is the second native call for the same lane slot.
            assertEquals("2", js.evalString("String(pending.length)"))
            assertEquals("16", js.evalString("String(pending[1].data.byteLength)"))
            js.eval("pending[1].reject(new Error('x'));")
            assertEquals("true", js.evalString("String(failed)"))
            assertEquals("3", js.evalString("String(pending.length)"))
            assertEquals("17", js.evalString("String(pending[2].data.byteLength)"))
        }
    }

    @Test
    fun decodesRunOneAtATime() {
        runtime().use { js ->
            js.eval(
                """
                var ctx = new BaseAudioContext();
                for (var i = 0; i < 4; i++) { ctx.decodeAudioData(fakeBuffer(16)); }
                """.trimIndent(),
            )
            assertEquals("only one decode per lane may be in flight", "1", js.evalString("String(pending.length)"))
        }
    }
}
