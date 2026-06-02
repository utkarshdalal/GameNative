// gamenative pack:gms shim -- handheld viewport fit + sub-frame tap normalization for GMS HTML5.
//
// (1) viewport fit: GMS HTML5 builds set canvas.width/height attributes (raster) but NOT
// canvas.style.width/height (CSS layout). browsers render the canvas at its attribute
// resolution in CSS px → overflows handheld viewports. fix: CSS cap with object-fit:contain.
//
// (2) sub-frame tap normalization. modern GMS HTML5 builds bind input via
//   canvas.addEventListener('pointerdown'|'pointerup'|'pointerout'|'pointerleave'|
//                           'pointercancel', _je)
//   plus window.onmouseup = _Y81 (legacy mouse path the engine also keeps)
//
// the engine's _je pointer handler treats pointerup AND pointerout AND pointerleave AND
// pointercancel all as "end" → sets _de=0 and _4e[i]._mc=0. _Y81 also clears _de via
// _de &= ~1. the IO poll (_3Z2, paced at room_speed in _4p3 → _M1._Dd → _ae._oZ2 → _3Z2)
// snapshots _de and XOR-compares against the previous snapshot to detect the 0→1 press
// transition that triggers mouse_check_button_pressed (= this._hc[0]).
//
// THE BUG (verified live in DevTools):
// for a sub-frame quick tap, ALL of these end-paths fire in the same task as pointerdown.
// _de transitions 0→1 (pointerdown) → 0 (any of pointerup/out/leave/cancel/_Y81) within a
// SINGLE task. The next IO poll observes _de=0 with _1Z2=0 -- no transition, no _hc[0]=1,
// no advance. long-presses work because _de stays at 1 across multiple poll intervals.
//
// fix:
//   (a) swallow native pointerup + pointerout + pointerleave + pointercancel at canvas
//       capture phase so the engine never sees them clear _de
//   (b) swallow mouseup at window capture phase so _Y81 doesn't clear _de via touch.js's
//       synthetic mouseup (touch.js dispatches one on touchend; it bubbles to window)
//   (c) on touchend, dispatch a SYNTHETIC pointerup tagged with __gnGmsSynth after DEFER_MS
//       (constant + derivation below). the synth bypasses our own swallow (tag check) and
//       reaches _je as the only path that releases _de back to 0. the delay spans one IO poll
//       interval so the engine's poll is guaranteed to observe _de=1 at least once.
//
// derived from engine architecture (poll cadence + XOR press-detection), not a magic
// latency. verified live with quick taps reliably advancing.
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
