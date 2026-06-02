// WebView emits no DOM event for ACTION_HOVER_MOVE without a button, so engines tracking the cursor via
// pointermove/mousemove only see it move on click, breaking hover UI. the host calls __gnPhysicalMouseHover
// per hover move with device-pixel coords.
//
// c3 MUST go through the POJO injector: dispatching a real PointerEvent into c3 causes tap-behind bugs.
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
