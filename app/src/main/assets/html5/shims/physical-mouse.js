// physical-mouse: bridges Android WebView's hover-event-without-button stream into DOM
// pointermove/mousemove. WebView translates mouse button-press / button-release / hover-with-
// button-down to DOM events natively, but ACTION_HOVER_MOVE without a button does NOT generate
// any DOM event. for engines that track cursor via pointermove/mousemove (c3, rmmv, electron,
// nwjs), this means cursor position only updates on click -- visually janky and breaks any
// hover-driven UI.
//
// host (WebViewScreen.setOnHoverListener) calls window.__gnPhysicalMouseHover(deviceX, deviceY)
// per ACTION_HOVER_MOVE. coords arrive in device pixels; we convert to CSS px via DPR.
//
// pack:c3 -- POJO injection via __gnC3InjectMousePointer is required (see
// feedback_c3_pojo_injection.md). dispatchEvent(new PointerEvent) on c3 causes tap-behind
// regressions. other packs use DOM dispatch (the cheap path).
(function () {
    'use strict';

    window.__gnPhysicalMouseHover = function (deviceX, deviceY) {
        var dpr = window.devicePixelRatio || 1;
        var x = deviceX / dpr;
        var y = deviceY / dpr;

        if (window.__gnC3InjectMousePointer) {
            try { window.__gnC3InjectMousePointer('pointermove', x, y, 0); } catch (_e) {}
            return;
        }

        var tgt = document.elementFromPoint(x, y) || document.body;
        if (!tgt) return;
        try {
            tgt.dispatchEvent(new PointerEvent('pointermove', {
                bubbles: true, cancelable: true,
                clientX: x, clientY: y,
                pointerType: 'mouse', pointerId: 1,
                button: -1, buttons: 0,
            }));
        } catch (_e) {}
        try {
            tgt.dispatchEvent(new MouseEvent('mousemove', {
                bubbles: true, cancelable: true,
                clientX: x, clientY: y,
                button: 0, buttons: 0,
            }));
        } catch (_e) {}
    };
})();
