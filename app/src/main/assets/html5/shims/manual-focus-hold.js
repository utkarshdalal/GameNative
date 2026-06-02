// gamenative manual-focus-hold shim
//
// keeps focus-driven engines muted while the manual-resume suspend policy is holding the
// game paused. some engines (Impact/CrossCode lineage) tie BGM mute/unmute to the DOM
// window 'blur'/'focus' events. QuickMenu is a focus-stealing popup, so opening it fires
// window 'blur' (engine mutes -- desired) and closing it fires window 'focus' (engine
// unmutes). under suspendPolicy=manual the game must stay fully paused after QuickMenu
// closes until the user taps the resume widget, but webView.onPause() only freezes the
// renderer -- the engine's own focus handler resumes audio on its own, leaking a frozen
// frame with live BGM.
//
// while the host has set window.__gnManualPaused = true, swallow the real window 'focus'
// in capture phase so the engine's handler never runs and audio stays muted. the host
// clears the flag and dispatches a synthetic 'focus' from resumeFromManual, which unmutes
// at the correct moment (engines respond to synthetic focus -- verified on CrossCode).
//
// always-injected. inert for engines that don't key audio on window focus (RMMV ignores
// it), and inert outside manual-paused state (flag falsy). only swallows when actively
// holding a manual suspend.

(function () {
    'use strict';

    function onFocus(e) {
        if (window.__gnManualPaused === true) {
            try { e.stopImmediatePropagation(); } catch (_) {}
            try { e.preventDefault(); } catch (_) {}
        }
    }

    // capture phase + window-level so we beat any engine's bubble-phase / onfocus handler.
    // injected before game JS, so our listener is registered first and stopImmediatePropagation
    // kills the engine's later-registered focus listener.
    try {
        window.addEventListener('focus', onFocus, true);
    } catch (_e) { /* swallow — shim MUST NOT crash the host */ }

    if (self.__gnShimVerbose) try {
        console.log('gamenative manual-focus-hold shim loaded');
    } catch (e) {}
})();
