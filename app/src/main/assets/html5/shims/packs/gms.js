// gamenative pack:gms shim -- handheld viewport fit + sub-frame tap normalization for GMS HTML5.
//
// (1) viewport fit: GMS sets canvas.width/height attributes but NOT canvas.style size, so the canvas
// renders at attribute resolution in CSS px and overflows handheld viewports.
//
// (2) sub-frame taps: the engine polls mouse-button state (_de) at room_speed and detects presses by XOR
// against the previous poll. a tap sets _de 0->1->0 within a SINGLE task, so no poll sees the press.
// so native end-events are swallowed and ONE synthetic pointerup (tagged __gnGmsSynth) fires after
// DEFER_MS, guaranteeing a poll sees _de=1.
(function () {
    'use strict';
    if (window.__gnGmsInstalled) return;
    window.__gnGmsInstalled = true;

    // background:#000 lives in always-injected base-background.js -- not duplicated here.
    var css =
        'html, body { margin: 0 !important; padding: 0 !important; ' +
        'width: 100vw !important; height: 100vh !important; ' +
        'overflow: hidden !important; } ' +
        'canvas { position: fixed !important; top: 0 !important; left: 0 !important; ' +
        'width: 100vw !important; height: 100vh !important; ' +
        'max-width: none !important; max-height: none !important; ' +
        'object-fit: contain !important; display: block !important; } ' +
        'div.gm4html5_div_class { position: fixed !important; top: 0 !important; left: 0 !important; ' +
        'width: 100vw !important; height: 100vh !important; ' +
        'margin: 0 !important; padding: 0 !important; }';
    function installCss() {
        var style = document.createElement('style');
        style.id = '__gnGmsCanvasFit';
        style.textContent = css;
        (document.head || document.documentElement).appendChild(style);
    }

    // must exceed one poll interval (1000/room_speed ms); 67ms covers room_speed=15 (one poll) and 30 (two).
    var DEFER_MS = 67;
    var lastPointer = { id: null, x: 0, y: 0, type: 'touch' };

    function recordPointerDown(ev) {
        lastPointer.id = ev.pointerId;
        lastPointer.x = ev.clientX;
        lastPointer.y = ev.clientY;
        // the synthetic release MUST carry the same pointerType as the press it replaces --
        // see dispatchSynthPointerUp.
        lastPointer.type = ev.pointerType || 'touch';
    }

    function swallowUnlessSynth(ev) {
        if (ev.__gnGmsSynth) return;
        ev.stopImmediatePropagation();
        // MOUSE releases have no touchend behind them, so nothing else schedules the synthetic release:
        // _de would stick at 1 after the first click and no later click would produce a press edge.
        // gated on pointerType so the window `mouseup` (no pointerType) doesn't schedule a second release.
        if (ev.pointerType === 'mouse' && lastPointer.id != null) {
            onPointerRelease(ev.currentTarget || ev.target);
        }
    }

    function dispatchSynthPointerUp(canvas, pointerId, clientX, clientY, pointerType) {
        try {
            var synth = new PointerEvent('pointerup', {
                pointerId: pointerId,
                isPrimary: true,
                bubbles: true,
                cancelable: true,
                button: 0, buttons: 0,
                pointerType: pointerType || 'touch',
                clientX: clientX, clientY: clientY,
                screenX: clientX, screenY: clientY,
            });
            try { Object.defineProperty(synth, '__gnGmsSynth', { value: true }); } catch (_e) {}
            canvas.dispatchEvent(synth);
            // MOUSE only: the engine's mouse path is the window `mouseup`, which we also swallow -- a
            // pointerup alone does not clear _de for a mouse. the window dispatch is probably redundant
            // (canvas dispatch bubbles) but untested without it; an extra mouseup is harmless (0 -> 0, no edge).
            if ((pointerType || 'touch') === 'mouse') {
                var up = new MouseEvent('mouseup', {
                    bubbles: true, cancelable: true,
                    button: 0, buttons: 0,
                    clientX: clientX, clientY: clientY,
                });
                try { Object.defineProperty(up, '__gnGmsSynth', { value: true }); } catch (_e) {}
                canvas.dispatchEvent(up);
                window.dispatchEvent(up);
            }
        } catch (_e) { /* swallow */ }
    }

    // shared by the touchend/touchcancel handlers and by a swallowed MOUSE release.
    function onPointerRelease(canvas) {
        if (lastPointer.id == null || !canvas) return;
        var pid = lastPointer.id;
        var cx = lastPointer.x, cy = lastPointer.y, ptype = lastPointer.type;
        lastPointer.id = null;
        setTimeout(function () { dispatchSynthPointerUp(canvas, pid, cx, cy, ptype); }, DEFER_MS);
    }

    function install() {
        installCss();
        var canvas = document.querySelector('canvas');
        if (!canvas) { setTimeout(install, 50); return; }
        canvas.addEventListener('pointerup', swallowUnlessSynth, { capture: true });
        canvas.addEventListener('pointerout', swallowUnlessSynth, { capture: true });
        canvas.addEventListener('pointerleave', swallowUnlessSynth, { capture: true });
        canvas.addEventListener('pointercancel', swallowUnlessSynth, { capture: true });
        window.addEventListener('mouseup', swallowUnlessSynth, { capture: true });
        canvas.addEventListener('pointerdown', recordPointerDown, { capture: true, passive: true });
        document.addEventListener('touchend', function () { onPointerRelease(canvas); }, { capture: true, passive: true });
        document.addEventListener('touchcancel', function () { onPointerRelease(canvas); }, { capture: true, passive: true });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', install, { once: true });
    } else {
        install();
    }
})();
