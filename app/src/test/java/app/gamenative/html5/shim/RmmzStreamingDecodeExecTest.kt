package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Test

// EXECUTES shims/packs/rmmv.js under Rhino against a stub RMMZ WebAudio. pinned: BGM/BGS take the
// engine's VorbisDecoder path, SE/ME stay on native decodeAudioData, MV (no decoder path) is left
// alone, the choice can't flip for a load already in flight -- _shouldUseDecoder is re-asked
// throughout a load, and a flip mid-load leaves the sound with no decoder -- and a streamed load
// that errors reloads natively once instead of hitting the engine's load-error screen.
class RmmzStreamingDecodeExecTest {

    private fun runtime(mz: Boolean = true, vorbis: Boolean = true): ShimJsRuntime {
        val js = ShimJsRuntime()
        js.eval(
            """
            function setTimeout(fn, ms) { return 0; }
            var document = {
                addEventListener: function () {},
                createElement: function () { return { style: {} }; },
                head: { appendChild: function () {} },
                documentElement: { appendChild: function () {} },
            };
            window.document = document;
            window.addEventListener = function () {};
            ${if (vorbis) "function VorbisDecoder() {}" else ""}
            function WebAudio(url) { this._url = url; }
            ${if (mz) "WebAudio.prototype._shouldUseDecoder = function () { return false; };" else ""}
            WebAudio.prototype._startLoading = function () { this.started = (this.started || 0) + 1; };
            WebAudio.prototype._onError = function () { this._isError = true; };
            WebAudio.prototype._stopSourceNode = function () { this.stopped = true; };
            WebAudio.prototype._removeEndTimer = function () { this.timerRemoved = true; };
            WebAudio.prototype._onFetch = function (response) { this.reader = response.body.getReader(); };
            function fakeResponse() {
                return { body: { getReader: function () {
                    return { cancelled: false, cancel: function () { this.cancelled = true; return Promise.resolve(); } };
                } } };
            }
            WebAudio.prototype.play = function (loop, offset) { this.played = loop + '@' + offset; };
            window.WebAudio = WebAudio;
            function streams(url) {
                var a = new WebAudio(url);
                a._startLoading();
                return String(a._shouldUseDecoder());
            }
            """.trimIndent(),
        )
        js.load("packs/rmmv.js")
        return js
    }

    @Test
    fun bgmAndBgs_stream_seAndMe_stayNative() {
        runtime().use { js ->
            assertEquals("true", js.evalString("streams('audio/bgm/SafePlace.ogg')"))
            assertEquals("true", js.evalString("streams('audio/bgs/RoomTone.ogg')"))
            assertEquals("false", js.evalString("streams('audio/se/WalkSteps_Wood1.ogg')"))
            assertEquals("false", js.evalString("streams('audio/me/Fanfare.ogg')"))
        }
    }

    @Test
    fun loadStartedBeforeThePatch_keepsTheEngineAnswer() {
        runtime().use { js ->
            // no _startLoading through the wrapper = no pinned choice.
            assertEquals("false", js.evalString("String(new WebAudio('audio/bgm/SafePlace.ogg')._shouldUseDecoder())"))
        }
    }

    @Test
    fun originalStartLoading_stillRuns() {
        runtime().use { js ->
            assertEquals("1", js.evalString("var a = new WebAudio('audio/bgm/x.ogg'); a._startLoading(); String(a.started)"))
        }
    }

    @Test
    fun streamedError_reloadsNativelyOnce_thenErrorsNormally() {
        runtime().use { js ->
            js.eval(
                """
                var a = new WebAudio('audio/bgm/SafePlace.ogg');
                a._startLoading();
                a._isPlaying = true; a._loop = true;
                a._buffers = [1, 2]; a._sourceNodes = [1, 2]; a._totalTime = 10;
                a._onFetch(fakeResponse());
                a._onError();
                """.trimIndent(),
            )
            assertEquals("false", js.evalString("String(a._shouldUseDecoder())"))
            assertEquals("2", js.evalString("String(a.started)"))
            assertEquals("undefined", js.evalString("String(a._isError)"))
            assertEquals("0|0|0", js.evalString("a._buffers.length + '|' + a._sourceNodes.length + '|' + a._totalTime"))
            assertEquals("true@0", js.evalString("String(a.played)"))
            // the old download's reader is cancelled so it can't feed the reload; a pending end timer can't stop it.
            assertEquals("true", js.evalString("String(a.reader.cancelled)"))
            assertEquals("true", js.evalString("String(a.timerRemoved)"))
            js.eval("a._onError();")
            assertEquals("true", js.evalString("String(a._isError)"))
            assertEquals("2", js.evalString("String(a.started)"))
        }
    }

    @Test
    fun nativeError_isUntouched() {
        runtime().use { js ->
            js.eval("var a = new WebAudio('audio/se/x.ogg'); a._startLoading(); a._onError();")
            assertEquals("true", js.evalString("String(a._isError)"))
            assertEquals("1", js.evalString("String(a.started)"))
        }
    }

    @Test
    fun noVorbisDecoder_staysNative() {
        runtime(vorbis = false).use { js ->
            assertEquals("false", js.evalString("streams('audio/bgm/SafePlace.ogg')"))
        }
    }

    @Test
    fun mv_isLeftAlone() {
        runtime(mz = false).use { js ->
            assertEquals("undefined", js.evalString("typeof WebAudio.prototype._shouldUseDecoder"))
        }
    }
}
