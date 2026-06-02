// defaults AudioContext latencyHint to 'playback' when the game passes none. chromium then uses a much larger
// output buffer than the 'interactive' default, so the audio renderer thread underruns less under thermal / CPU
// pressure. on 'interactive', RPG Maker MZ / Pixi titles can SIGTRAP the renderer when sync_reader times out.

// must run BEFORE game JS so the wrapper is in place for the game's first `new AudioContext()`.
(function () {
    'use strict';
    var Orig = window.AudioContext || window.webkitAudioContext;
    if (!Orig) return;
    if (Orig.__gnAudioLatencyPatched) return;

    function PatchedAudioContext(options) {
        var opts;
        if (options && typeof options === 'object') {
            opts = options;
        } else {
            opts = {};
        }
        if (!('latencyHint' in opts)) {
            opts.latencyHint = 'playback';
        }
        return Reflect.construct(Orig, [opts], PatchedAudioContext);
    }
    PatchedAudioContext.prototype = Orig.prototype;
    Object.defineProperty(PatchedAudioContext, '__gnAudioLatencyPatched', {
        value: true,
        configurable: false,
        enumerable: false,
        writable: false,
    });

    if (window.AudioContext) window.AudioContext = PatchedAudioContext;
    if (window.webkitAudioContext) window.webkitAudioContext = PatchedAudioContext;

    try {
        if (self.__gnShimVerbose) console.log('[audio-latency] AudioContext patched: default latencyHint=playback');
    } catch (e) {}
})();
