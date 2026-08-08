// gamenative Audio + AudioContext registries.
// many engines construct `new Audio(url)` without appending the element to the DOM, so
// `document.querySelectorAll('audio')` returns empty and host-side PAUSE_MEDIA_JS misses
// every BGM/SE element built that way (Tyrano kag.tag_audio.js is the canonical case).
// likewise, Web-Audio engines (Wayward + most Electron/native-feeling titles) drive sound
// through a bare `new AudioContext()` -- neither a DOM element, a `new Audio()`, Howler.ctx,
// nor rpg_core's WebAudio._context -- so the same branch misses it too. wrap both
// constructors at script-load time so every `new Audio(...)` / `new AudioContext(...)`
// registers into a Set-backed registry that PAUSE_MEDIA_JS iterates. cheap (one Set.add
// per construction). registering EVERY context also subsumes the Howler / WebAudio._context
// special-cases -- one general hook instead of per-engine branches.
//
// always-injected for html5 containers. titles that never use them pay only the parse
// cost; pause/resume gains free coverage for any future title that does.
(function () {
    'use strict';
    if (window.__gnAudioWrapInstalled) return;
    window.__gnAudioWrapInstalled = true;

    // new Audio(...) -> __gnAudioRegistry
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
            // preserve prototype + identity so `instanceof Audio` checks keep working.
            Wrapped.prototype = OrigAudio.prototype;
            try { Object.setPrototypeOf(Wrapped, OrigAudio); } catch (_e2) {}
            window.Audio = Wrapped;
        }
    } catch (_eA) { /* swallow — never crash host game */ }

    // new AudioContext(...) / new webkitAudioContext(...) -> __gnAudioCtxRegistry
    try {
        window.__gnAudioCtxRegistry = window.__gnAudioCtxRegistry || new Set();
        ['AudioContext', 'webkitAudioContext'].forEach(function (name) {
            var Orig = window[name];
            if (typeof Orig !== 'function') return;
            // Reflect.construct keeps the proto chain + forwards the optional options arg.
            function WrappedAC() {
                var ctx = Reflect.construct(Orig, arguments, WrappedAC);
                try { window.__gnAudioCtxRegistry.add(ctx); } catch (_e3) {}
                return ctx;
            }
            WrappedAC.prototype = Orig.prototype;
            try { Object.setPrototypeOf(WrappedAC, Orig); } catch (_e4) {}
            window[name] = WrappedAC;
        });
    } catch (_eC) { /* swallow — never crash host game */ }
})();
