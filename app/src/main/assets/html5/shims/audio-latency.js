// audio-latency: force AudioContext({latencyHint: 'playback'}) when the game doesn't
// pass its own hint. 'playback' tells chromium to use a larger output buffer (up to
// ~10x the default 'interactive' 256-frame ring), reducing the chance the audio
// renderer thread underruns under thermal / CPU pressure. RPG Maker MZ + Pixi-based
// titles default to 'interactive' and have been observed to SIGTRAP the renderer
// process when sync_reader times out (see project memory: WebView audio sync_reader
// glitch → CHECK trip).

// must run BEFORE game JS so the wrapper is installed when the game does its first
// `new AudioContext()`. ShimBundles places this at the top of the resolved URL list.
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
