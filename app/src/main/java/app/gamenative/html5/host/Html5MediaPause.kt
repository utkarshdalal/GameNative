package app.gamenative.html5.host

// JS evaluated when QuickMenu opens -- pauses everything WebView.onPause() misses.
// Android's WebView.onPause() stops JS timers + renderer compositing but leaves
// <audio>/<video> playback running AND does not touch Web Audio AudioContext state.
// Tyrano/Howler-based BGM (most VN engines) and any direct media elements bleed through
// QuickMenu without this shim. state stored on window so resume can restore exactly what
// we paused (don't blanket-resume -- e.g. don't un-pause an element the game itself paused).
internal const val PAUSE_MEDIA_JS = """
(function () {
    try {
        // preserve an existing snapshot. this runs on QuickMenu-open AND again on every foreground
        // while manually paused (screen-on revives the renderer + AudioContext). resetting would
        // drop the original resume list, so RESUME_MEDIA would then un-pause nothing.
        var state = window.__gnAudioPauseState;
        if (!state) state = window.__gnAudioPauseState = { media: [], audioCtxs: [] };
        function maybePause(m) {
            if (m && !m.paused && !m.ended) {
                try { m.pause(); if (state.media.indexOf(m) < 0) state.media.push(m); } catch (_e) {}
            }
        }
        // suspend ANY running AudioContext (chromium may have revived one we suspended earlier on
        // a screen-on); track it once so RESUME_MEDIA resumes it exactly once.
        function pauseCtx(c) {
            if (c && c.state === 'running') {
                try { c.suspend(); if (state.audioCtxs.indexOf(c) < 0) state.audioCtxs.push(c); } catch (_e) {}
            }
        }
        // <audio> + <video> attached to DOM. covers <video> playback + any titles that
        // appendChild their audio elements.
        var els = document.querySelectorAll('audio, video');
        for (var i = 0; i < els.length; i++) maybePause(els[i]);
        // __gnAudioRegistry -- populated by pack:tyrano's Audio constructor wrapper. Tyrano
        // kag.tag_audio.js builds `new Audio(url)` and stores it in JS-side objects without
        // ever attaching to DOM, so the querySelectorAll branch misses them entirely. the
        // registry is a Set; iteration order is insertion order.
        try {
            var reg = window.__gnAudioRegistry;
            if (reg && typeof reg.forEach === 'function') {
                reg.forEach(maybePause);
            }
        } catch (_e1) {}
        // __gnAudioCtxRegistry -- populated by audio-registry.js's AudioContext constructor
        // wrapper. the general hook: engines that drive sound straight through the Web Audio
        // API (Wayward + most Electron titles) hold a bare `new AudioContext()` that no branch
        // above reaches. registering every context at construction catches them all, and -- since
        // Howler's ctx and rpg_core's WebAudio._context are themselves `new AudioContext()` --
        // subsumes both special-cases below. suspend/resume round-trips cleanly in-game (verified
        // RMMV, Impact, Wayward -- no engine watchdog re-running it).
        try {
            var creg = window.__gnAudioCtxRegistry;
            if (creg && typeof creg.forEach === 'function') {
                creg.forEach(pauseCtx);
            }
        } catch (_e1b) {}
        // Howler.ctx + rpg_core WebAudio._context -- belt-and-suspenders for the rare case a
        // context predates the registry wrap. pauseCtx dedupes against what the registry caught.
        try { if (window.Howler) pauseCtx(window.Howler.ctx); } catch (_e2) {}
        try { if (window.WebAudio) pauseCtx(window.WebAudio._context); } catch (_e3) {}
    } catch (_e) {}
})();
"""

internal const val RESUME_MEDIA_JS = """
(function () {
    try {
        var state = window.__gnAudioPauseState;
        if (!state) return;
        for (var i = 0; i < state.media.length; i++) {
            try { state.media[i].play(); } catch (_e) {}
        }
        for (var j = 0; j < state.audioCtxs.length; j++) {
            try { state.audioCtxs[j].resume(); } catch (_e2) {}
        }
        window.__gnAudioPauseState = null;
    } catch (_e) {}
})();
"""
