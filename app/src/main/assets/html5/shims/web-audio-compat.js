// polyfills Web Audio APIs that chromium removed but older Construct 2/3 and NW.js exports still call: Doppler
// setVelocity (no replacement) and the legacy noteOn/noteOff/create*Node names. the TypeError from a removed call
// fires every tick and freezes the run loop (e.g. c2runtime.js calls listener.setVelocity per audio update).
//
// MUST run BEFORE game JS.
(function () {
    'use strict';
    function noop() {}
    try {
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
