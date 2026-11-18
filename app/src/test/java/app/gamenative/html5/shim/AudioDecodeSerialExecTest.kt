package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// EXECUTES audio-decode-serial.js under Rhino. it needs only BaseAudioContext + Promise, so the
// harness's browser-coupling caveat does not apply. what is pinned here is WHEN the retry copy is
// taken: decodes run one at a time, so a copy taken at call time stays alive for every decode
// still waiting in the queue -- and titles can queue hundreds of decodes (tens of MB of encoded
// audio) at boot.
class AudioDecodeSerialExecTest {

    private fun runtime(): ShimJsRuntime = ShimJsRuntime().also { js ->
        js.eval(
            """
            var sliceCount = 0;
            var pending = [];
            function fakeBuffer(n) {
                return { byteLength: n, slice: function () { sliceCount++; return fakeBuffer(n); } };
            }
            var BaseAudioContext = function () {};
            BaseAudioContext.prototype.decodeAudioData = function (data) {
                return new Promise(function (resolve, reject) { pending.push({ data: data, resolve: resolve }); });
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
    fun decodesRunOneAtATime() {
        runtime().use { js ->
            js.eval(
                """
                var ctx = new BaseAudioContext();
                for (var i = 0; i < 4; i++) { ctx.decodeAudioData(fakeBuffer(16)); }
                """.trimIndent(),
            )
            assertEquals("only one decode may be in flight", "1", js.evalString("String(pending.length)"))
        }
    }
}
