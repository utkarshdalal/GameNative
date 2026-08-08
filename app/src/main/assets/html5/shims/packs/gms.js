// gamenative pack:gms shim -- handheld viewport fit + sub-frame tap normalization for GMS HTML5.
//
// (1) viewport fit: GMS HTML5 builds set canvas.width/height attributes (raster) but NOT
// canvas.style.width/height (CSS layout). browsers render the canvas at its attribute
// resolution in CSS px → overflows handheld viewports. fix: CSS cap with object-fit:contain.
//
// (2) sub-frame tap normalization. the engine's mouse-button state (_de) is polled at
// room_speed and press is detected by XOR against the previous poll. a sub-frame tap sets
// _de 0→1 (pointerdown) then back to 0 (pointerup/out/leave/cancel or the legacy window
// mouseup path) within a SINGLE task, so no poll ever observes the press → no advance.
// long-presses work only because _de stays 1 across poll intervals.
//
// fix: swallow native end-events (pointerup/out/leave/cancel at canvas, mouseup at window)
// so the engine can't clear _de early, then dispatch ONE synthetic pointerup tagged
// __gnGmsSynth (bypasses the swallow) after DEFER_MS so a poll is guaranteed to see _de=1.
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

    // defer between touchend and the synthetic pointerup that releases _de. needs to exceed
    // one IO poll interval (1000/room_speed ms) so the engine's poll snapshots _de=1 at
    // least once. 67ms covers either worst-case room_speed=15 (one poll) or room_speed=30
    // (two polls). engine architecture constant, not a hand-tuned latency.
    var DEFER_MS = 67;
    var lastPointer = { id: null, x: 0, y: 0 };

    function recordPointerDown(ev) {
        lastPointer.id = ev.pointerId;
        lastPointer.x = ev.clientX;
        lastPointer.y = ev.clientY;
    }

    function swallowUnlessSynth(ev) {
        if (ev.__gnGmsSynth) return;
        ev.stopImmediatePropagation();
    }

    function dispatchSynthPointerUp(canvas, pointerId, clientX, clientY) {
        try {
            var synth = new PointerEvent('pointerup', {
                pointerId: pointerId,
                isPrimary: true,
                bubbles: true,
                cancelable: true,
                button: 0, buttons: 0,
                pointerType: 'touch',
                clientX: clientX, clientY: clientY,
                screenX: clientX, screenY: clientY,
            });
            try { Object.defineProperty(synth, '__gnGmsSynth', { value: true }); } catch (_e) {}
            canvas.dispatchEvent(synth);
        } catch (_e) { /* swallow */ }
    }

    function onTouchEnd(canvas) {
        if (lastPointer.id == null) return;
        var pid = lastPointer.id;
        var cx = lastPointer.x, cy = lastPointer.y;
        lastPointer.id = null;
        setTimeout(function () { dispatchSynthPointerUp(canvas, pid, cx, cy); }, DEFER_MS);
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
        document.addEventListener('touchend', function () { onTouchEnd(canvas); }, { capture: true, passive: true });
        document.addEventListener('touchcancel', function () { onTouchEnd(canvas); }, { capture: true, passive: true });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', install, { once: true });
    } else {
        install();
    }
})();
