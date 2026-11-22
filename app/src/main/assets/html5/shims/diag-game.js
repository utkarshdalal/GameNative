// in-game half of an html5 debug run; injected only for one. from load it samples frame pacing in 10s windows
// and snapshots the game's own canvases, emitting one console.warn line per window: "[GN-DIAG-GAME] {json}". the host
// (onConsoleMessage) puts it in the run's report; the device half lives in html5/diag/probe.js.
(function () {
    'use strict';
    if (window.__gnDiagGameInstalled) return;
    window.__gnDiagGameInstalled = true;

    var WINDOW_MS = 10000;
    // engines and plugins make many throwaway/helper canvases; track generously, report only the visible ones.
    var MAX_TRACKED = 32;
    var tracked = [];

    // WEAK refs only: holding throwaway canvases alive would keep their GL contexts alive too, and chromium
    // evicts the oldest WebGL context past its per-page cap -- a debug run must not cost the game its context.
    var seen = typeof WeakSet === 'function' ? new WeakSet() : null;
    function track(canvas, ctx, type) {
        if (typeof WeakRef !== 'function' || !seen) return;
        var key = ctx || canvas;
        if (seen.has(key)) return;
        seen.add(key);
        if (tracked.length >= MAX_TRACKED) {
            tracked = tracked.filter(function (t) { return t.canvas.deref() !== undefined; });
        }
        if (tracked.length >= MAX_TRACKED) {
            // still full: evict the oldest off-DOM entry, so a late-created game canvas still gets in.
            for (var i = 0; i < tracked.length; i++) {
                var c = tracked[i].canvas.deref();
                if (!c || !c.isConnected) { tracked.splice(i, 1); break; }
            }
        }
        if (tracked.length < MAX_TRACKED) {
            tracked.push({ canvas: new WeakRef(canvas), ctx: ctx ? new WeakRef(ctx) : null, type: type });
        }
    }

    try {
        var proto = window.HTMLCanvasElement && window.HTMLCanvasElement.prototype;
        if (proto && proto.getContext) {
            var origGetContext = proto.getContext;
            proto.getContext = function (type) {
                var ctx = origGetContext.apply(this, arguments);
                if (ctx && (type === 'webgl2' || type === 'webgl' || type === 'experimental-webgl' || type === '2d')) {
                    try { track(this, ctx, type); } catch (_e) {}
                }
                return ctx;
            };
        }
        if (proto && proto.transferControlToOffscreen) {
            var origTransfer = proto.transferControlToOffscreen;
            proto.transferControlToOffscreen = function () {
                try { track(this, null, 'offscreen'); } catch (_e) {}
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

    // only canvases on screen (or handed to a worker); off-DOM support checks and text buffers are just counted.
    function contexts() {
        var out = [];
        var offDom = 0;
        for (var i = 0; i < tracked.length; i++) {
            var t = tracked[i];
            try {
                var canvas = t.canvas.deref();
                if (!canvas) continue;
                var ctx = t.ctx ? t.ctx.deref() : null;
                if (t.type !== 'offscreen' && !canvas.isConnected) { offDom++; continue; }
                var e = { type: t.type, inDom: canvas.isConnected, css: [canvas.clientWidth, canvas.clientHeight] };
                if (t.type !== 'offscreen') e.backing = [canvas.width, canvas.height];
                if (ctx && t.type !== '2d') {
                    e.drawingBuffer = [ctx.drawingBufferWidth, ctx.drawingBufferHeight];
                    e.lost = ctx.isContextLost();
                }
                if (ctx && ctx.getContextAttributes) e.attrs = ctx.getContextAttributes();
                out.push(e);
            } catch (_e) {}
        }
        return { onScreen: out, offDomCount: offDom };
    }

    function pct(sorted, p) {
        if (!sorted.length) return null;
        return Math.round(sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] * 10) / 10;
    }

    // a rAF gap this long is a pause (QuickMenu suspend, backgrounding), not a slow frame.
    var PAUSE_GAP_MS = 1000;
    var current = null;
    var windowCount = 0;
    var stopped = false;

    function newWindow() {
        windowCount++;
        current = {
            idx: windowCount,
            t0: performance.now(),
            deltas: [],
            pauses: 0,
            lt: longTasks ? { count: longTasks.count, totalMs: longTasks.totalMs } : null,
        };
    }

    function summarize(w, partial) {
        var deltas = w.deltas;
        var sorted = deltas.slice().sort(function (a, b) { return a - b; });
        var sum = 0, over33 = 0, over50 = 0;
        for (var i = 0; i < deltas.length; i++) {
            sum += deltas[i];
            if (deltas[i] > 33.4) over33++;
            if (deltas[i] > 50) over50++;
        }
        var report = {
            window: w.idx,
            partial: partial,
            atSec: Math.round(performance.now() / 1000),
            lenSec: Math.round((performance.now() - w.t0) / 100) / 10,
            visibility: document.visibilityState,
            frames: deltas.length,
            pauses: w.pauses,
            fpsAvg: sum ? Math.round(deltas.length / (sum / 1000) * 10) / 10 : 0,
            frameMsP50: pct(sorted, 0.5),
            frameMsP95: pct(sorted, 0.95),
            frameMsP99: pct(sorted, 0.99),
            framesOver33ms: over33,
            framesOver50ms: over50,
            dpr: window.devicePixelRatio,
            inner: [window.innerWidth, window.innerHeight],
        };
        var ctxs = contexts();
        report.contexts = ctxs.onScreen;
        report.offDomContexts = ctxs.offDomCount;
        if (w.lt) {
            report.longTasks = longTasks.count - w.lt.count;
            report.longTaskMs = Math.round(longTasks.totalMs - w.lt.totalMs);
        }
        return report;
    }

    // back-to-back windows from load, so a perf drop at any point of the session is caught; the host
    // keeps the first window and the latest ones. our rAF piggybacks on frames the game already drives.
    var last = 0;
    function tick(now) {
        if (stopped) return;
        if (last) {
            var d = now - last;
            if (d > PAUSE_GAP_MS) current.pauses++;
            else current.deltas.push(d);
        }
        last = now;
        if (now - current.t0 >= WINDOW_MS) {
            try { console.warn('[GN-DIAG-GAME] ' + JSON.stringify(summarize(current, false))); } catch (_e) {}
            newWindow();
        }
        requestAnimationFrame(tick);
    }

    // called by the host at teardown (evaluateJavascript), so a session shorter than one window still
    // reports. returns the partial window, or null when it holds too few frames to mean anything.
    window.__gnDiagFlush = function () {
        stopped = true;
        if (!current || current.deltas.length < 2) return null;
        return summarize(current, true);
    };

    function onLoad() {
        newWindow();
        requestAnimationFrame(tick);
    }
    if (document.readyState === 'complete') onLoad();
    else window.addEventListener('load', onLoad, { once: true });
})();
