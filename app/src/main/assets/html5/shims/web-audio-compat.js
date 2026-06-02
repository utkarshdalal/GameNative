// web-audio-compat: polyfill removed/legacy Web Audio APIs that older Construct 2 /
// Construct 3 / NW.js exports still call. Chromium dropped Doppler from the Web Audio
// spec years ago -- AudioListener.setVelocity / PannerNode.setVelocity were removed
// outright (no replacement). Legacy noteOn/noteOff/createGainNode/createDelayNode/
// createJavaScriptNode were removed in favor of start/stop/createGain/createDelay/
// createScriptProcessor. Each removed call thrown from game code TypeErrors mid-tick →
// freezes the run loop (Runtime.logic catches nothing, next frame same throw).

// Example hit: c2 titles whose c2runtime.js calls listener.setVelocity every tick freeze
// on the first audio update without this shim.

// MUST run BEFORE game JS so prototype patches are visible when constructors fire.
// always-on for html5 containers (cheap; no-op if APIs already exist).
(function () {
    'use strict';
    function noop() {}
    try {
        // Doppler removal: AudioListener / PannerNode setVelocity gone with no replacement.
        if (typeof AudioListener !== 'undefined' && AudioListener.prototype) {
            if (typeof AudioListener.prototype.setVelocity !== 'function') {
                AudioListener.prototype.setVelocity = noop;
            }
        }
        if (typeof PannerNode !== 'undefined' && PannerNode.prototype) {
            if (typeof PannerNode.prototype.setVelocity !== 'function') {
                PannerNode.prototype.setVelocity = noop;
            }
        }
        // Legacy note* → start/stop on buffer sources + oscillators.
        function aliasNoteApi(Ctor) {
            if (typeof Ctor === 'undefined' || !Ctor.prototype) return;
            var p = Ctor.prototype;
            if (typeof p.noteOn !== 'function' && typeof p.start === 'function') {
                p.noteOn = function (when) { return this.start(when || 0); };
            }
            if (typeof p.noteOff !== 'function' && typeof p.stop === 'function') {
                p.noteOff = function (when) { return this.stop(when || 0); };
            }
            if (typeof p.noteGrainOn !== 'function' && typeof p.start === 'function') {
                p.noteGrainOn = function (when, offset, duration) {
                    return this.start(when || 0, offset || 0, duration);
                };
            }
        }
        if (typeof AudioBufferSourceNode !== 'undefined') aliasNoteApi(AudioBufferSourceNode);
        if (typeof OscillatorNode !== 'undefined') aliasNoteApi(OscillatorNode);
        // Legacy createXxxNode aliases on AudioContext.prototype.
        var AC = window.AudioContext || window.webkitAudioContext;
        if (AC && AC.prototype) {
            var ap = AC.prototype;
            if (typeof ap.createGainNode !== 'function' && typeof ap.createGain === 'function') {
                ap.createGainNode = function () { return this.createGain.apply(this, arguments); };
            }
            if (typeof ap.createDelayNode !== 'function' && typeof ap.createDelay === 'function') {
                ap.createDelayNode = function () { return this.createDelay.apply(this, arguments); };
            }
            if (typeof ap.createJavaScriptNode !== 'function' && typeof ap.createScriptProcessor === 'function') {
                ap.createJavaScriptNode = function () { return this.createScriptProcessor.apply(this, arguments); };
            }
        }
        if (self.__gnShimVerbose) try { console.log('[web-audio-compat] legacy/removed Web Audio surface polyfilled'); } catch (e) {}
    } catch (e) {
        try { console.warn('[web-audio-compat] install failed', e); } catch (_) {}
    }
})();
