// some engines (e.g. Impact) mute/unmute BGM on window 'blur'/'focus'. closing the host's QuickMenu fires 'focus',
// which would unmute a game the manual suspend policy is still holding paused (webView.onPause() doesn't stop the
// engine's own focus handler). while __gnManualPaused is set, swallow the real 'focus'; the host dispatches a
// synthetic one on resume.
(function () {
    'use strict';

    function onFocus(e) {
        if (window.__gnManualPaused === true) {
            try { e.stopImmediatePropagation(); } catch (_) {}
            try { e.preventDefault(); } catch (_) {}
        }
    }

    // registered before game JS, in capture phase, so stopImmediatePropagation beats every engine focus listener.
    try {
        window.addEventListener('focus', onFocus, true);
    } catch (_e) { /* shim MUST NOT crash the page */ }

    if (self.__gnShimVerbose) try {
        console.log('gamenative manual-focus-hold shim loaded');
    } catch (e) {}
})();
