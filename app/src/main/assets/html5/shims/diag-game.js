// always-on in-game half of Settings > HTML5 diagnostics. after load it samples frame pacing and snapshots the
// game's own canvases, then emits one console.warn line per window: "[GN-DIAG-GAME] {json}". the host
// (onConsoleMessage) persists the latest one; the device-wide half lives in html5/diag/probe.js.
(function () {
    'use strict';
    if (window.__gnDiagGameInstalled) return;
    window.__gnDiagGameInstalled = true;

    // first window lands after typical boot/title load, second one is likelier to be in gameplay.
    var WINDOWS = [{ startMs: 15000, lenMs: 10000 }, { startMs: 90000, lenMs: 10000 }];
    var MAX_TRACKED = 8;
    var tracked = [];

    // engines often make a throwaway support-check context first, so record several, not just the first.
    try {
        var proto = window.HTMLCanvasElement && window.HTMLCanvasElement.prototype;
        if (proto && proto.getContext) {
            var origGetContext = proto.getContext;
            proto.getContext = function (type) {
                var ctx = origGetContext.apply(this, arguments);
                if (ctx && tracked.length < MAX_TRACKED &&
                    (type === 'webgl2' || type === 'webgl' || type === 'experimental-webgl' || type === '2d')) {
                    var known = false;
                    for (var i = 0; i < tracked.length; i++) if (tracked[i].ctx === ctx) { known = true; break; }
                    if (!known) tracked.push({ canvas: this, ctx: ctx, type: type });
                }
                return ctx;
            };
        }
        if (proto && proto.transferControlToOffscreen) {
            var origTransfer = proto.transferControlToOffscreen;
            proto.transferControlToOffscreen = function () {
                if (tracked.length < MAX_TRACKED) tracked.push({ canvas: this, ctx: null, type: 'offscreen' });
                return origTransfer.apply(this, arguments);
            };
        }
    } catch (_e) { /* MUST NOT crash the page */ }

    var longTasks = { count: 0, totalMs: 0 };
    try {
        new PerformanceObserver(function (list) {
            var entries = list.getEntries();
            for (var i = 0; i < entries.length; i++) {
                longTasks.count++;
                longTasks.totalMs += entries[i].duration;
            }
        }).observe({ entryTypes: ['longtask'] });
    } catch (_e) { longTasks = null; }

    function contexts() {
        var out = [];
        for (var i = 0; i < tracked.length; i++) {
            var t = tracked[i];
            try {
                var e = { type: t.type, inDom: t.canvas.isConnected, css: [t.canvas.clientWidth, t.canvas.clientHeight] };
                if (t.type !== 'offscreen') e.backing = [t.canvas.width, t.canvas.height];
                if (t.ctx && t.type !== '2d') {
                    e.drawingBuffer = [t.ctx.drawingBufferWidth, t.ctx.drawingBufferHeight];
                    e.lost = t.ctx.isContextLost();
                }
                if (t.ctx && t.ctx.getContextAttributes) e.attrs = t.ctx.getContextAttributes();
                out.push(e);
            } catch (_e) {}
        }
        return out;
    }

    function pct(sorted, p) {
        if (!sorted.length) return null;
        return Math.round(sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] * 10) / 10;
    }

    // our rAF piggybacks on frames the game already drives, so it adds ~nothing.
    function sample(win, idx) {
        var deltas = [];
        var last = 0;
        var startLt = longTasks ? { count: longTasks.count, totalMs: longTasks.totalMs } : null;
        var t0 = performance.now();
        function tick(now) {
            if (last) deltas.push(now - last);
            last = now;
            if (now - t0 < win.lenMs) {
                requestAnimationFrame(tick);
                return;
            }
            var sorted = deltas.slice().sort(function (a, b) { return a - b; });
            var sum = 0, over33 = 0, over50 = 0;
            for (var i = 0; i < deltas.length; i++) {
                sum += deltas[i];
                if (deltas[i] > 33.4) over33++;
                if (deltas[i] > 50) over50++;
            }
            var report = {
                window: idx + 1,
                atSec: Math.round(performance.now() / 1000),
                visibility: document.visibilityState,
                frames: deltas.length,
                fpsAvg: sum ? Math.round(deltas.length / (sum / 1000) * 10) / 10 : 0,
                frameMsP50: pct(sorted, 0.5),
                frameMsP95: pct(sorted, 0.95),
                frameMsP99: pct(sorted, 0.99),
                framesOver33ms: over33,
                framesOver50ms: over50,
                dpr: window.devicePixelRatio,
                inner: [window.innerWidth, window.innerHeight],
                contexts: contexts(),
            };
            if (startLt) {
                report.longTasks = longTasks.count - startLt.count;
                report.longTaskMs = Math.round(longTasks.totalMs - startLt.totalMs);
            }
            try { console.warn('[GN-DIAG-GAME] ' + JSON.stringify(report)); } catch (_e) {}
        }
        requestAnimationFrame(tick);
    }

    function onLoad() {
        WINDOWS.forEach(function (w, i) {
            setTimeout(function () { try { sample(w, i); } catch (_e) {} }, w.startMs);
        });
    }
    if (document.readyState === 'complete') onLoad();
    else window.addEventListener('load', onLoad, { once: true });
})();
