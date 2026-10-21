// registers every `new Audio()` and `new AudioContext()` so the host's pause/resume can reach them. engines often
// never attach Audio elements to the DOM (querySelectorAll('audio') finds nothing), and Web Audio engines hold a
// bare AudioContext nothing else references.
(function () {
    'use strict';
    if (window.__gnAudioWrapInstalled) return;
    window.__gnAudioWrapInstalled = true;

    try {
        window.__gnAudioRegistry = window.__gnAudioRegistry || new Set();
        var OrigAudio = window.Audio;
        if (typeof OrigAudio === 'function') {
            var Wrapped = function () {
                var a = arguments.length === 0
                    ? new OrigAudio()
                    : new OrigAudio(arguments[0]);
                try { window.__gnAudioRegistry.add(a); } catch (_e) {}
                return a;
            };
            // keeps `instanceof Audio` working.
            Wrapped.prototype = OrigAudio.prototype;
            try { Object.setPrototypeOf(Wrapped, OrigAudio); } catch (_e2) {}
            window.Audio = Wrapped;
        }
    } catch (_eA) { /* never crash the game */ }

    try {
        window.__gnAudioCtxRegistry = window.__gnAudioCtxRegistry || new Set();
        ['AudioContext', 'webkitAudioContext'].forEach(function (name) {
            var Orig = window[name];
            if (typeof Orig !== 'function') return;
            function WrappedAC() {
                var ctx = Reflect.construct(Orig, arguments, WrappedAC);
                try { window.__gnAudioCtxRegistry.add(ctx); } catch (_e3) {}
                return ctx;
            }
            WrappedAC.prototype = Orig.prototype;
            try { Object.setPrototypeOf(WrappedAC, Orig); } catch (_e4) {}
            window[name] = WrappedAC;
        });
    } catch (_eC) { /* never crash the game */ }
})();
