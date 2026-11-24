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

    // --- audio: decode queue + per-sound load-to-playback trace ---
    // fed by audio-decode-serial.js; per-window totals land in the frame window as audioDecode.
    function freshDecodeStats() {
        return { calls: 0, failed: 0, bytes: 0, waitMsTotal: 0, waitMsMax: 0, decodeMsTotal: 0, decodeMsMax: 0, depthMax: 0 };
    }
    // decodes before the first frame window (boot audio) are folded into window 1.
    var preWindowDecode = freshDecodeStats();
    self.__gnDecodeObserver = function (d) {
        var w = current ? current.decode : preWindowDecode;
        if (!w) return;
        w.calls++;
        if (!d.ok) w.failed++;
        w.bytes += d.size || 0;
        w.waitMsTotal += d.waitMs;
        w.decodeMsTotal += d.decodeMs;
        if (d.waitMs > w.waitMsMax) w.waitMsMax = d.waitMs;
        if (d.decodeMs > w.decodeMsMax) w.decodeMsMax = d.decodeMs;
        if (d.depthAtEnqueue > w.depthMax) w.depthMax = d.depthAtEnqueue;
    };

    // RPG Maker MV/MZ WebAudio: request -> fetched -> first decode -> play() -> actually playing. one line per
    // sound when it starts ([GN-DIAG-AUDIO]); sounds still waiting at teardown come back from __gnDiagFlush.
    var AUDIO_EMIT_MAX = 150;
    var audioEmitted = 0;
    var audioPending = [];
    function msSince(rec, t) { return t == null ? null : Math.round(t - rec.t0); }
    function ctxState() {
        try { return window.WebAudio && WebAudio._context ? WebAudio._context.state : null; } catch (_e) { return null; }
    }
    function audioSnapshot(rec) {
        var out = {
            url: rec.url, fetchedMs: msSince(rec, rec.fetched), firstDecodedMs: msSince(rec, rec.firstDecoded),
            playCalledMs: msSince(rec, rec.playCalled), startedMs: msSince(rec, rec.started),
            decodes: rec.decodes, decodeLatencyMsMax: Math.round(rec.decodeMsMax),
            decodeLatencyMsTotal: Math.round(rec.decodeMsTotal), ctxAtPlay: rec.ctxAtPlay, ctxAtStart: rec.ctxAtStart,
            atSec: Math.round(rec.t0 / 1000),
        };
        try {
            var e = performance.getEntriesByName(new URL(rec.url, location.href).href)[0];
            if (e) {
                out.fetchKB = Math.round((e.encodedBodySize || e.transferSize || 0) / 1024);
                out.ttfbMs = Math.round(e.responseStart - e.startTime);
                out.downloadMs = Math.round(e.responseEnd - e.responseStart);
            }
        } catch (_e) {}
        return out;
    }
    function wrap(proto, name, make) {
        if (typeof proto[name] === 'function') proto[name] = make(proto[name]);
    }
    function patchWebAudio() {
        var WA = window.WebAudio;
        if (!WA || !WA.prototype) return false;
        var P = WA.prototype;
        if (P.__gnDiagAudio) return true;
        P.__gnDiagAudio = true;
        function begin(wa) {
            var rec = {
                url: String(wa._url || ''), t0: performance.now(), fetched: null, firstDecoded: null,
                playCalled: null, started: null, decodes: 0, decodeMsMax: 0, decodeMsTotal: 0, inflight: [],
                ctxAtPlay: null, ctxAtStart: null,
            };
            wa.__gnA = rec;
            audioPending.push(rec);
            if (audioPending.length > 64) audioPending.shift();
        }
        // MZ starts loads in _startLoading, MV in _load.
        ['_startLoading', '_load'].forEach(function (n) {
            wrap(P, n, function (orig) {
                return function () { try { begin(this); } catch (_e) {} return orig.apply(this, arguments); };
            });
        });
        wrap(P, '_onXhrLoad', function (orig) {
            return function () {
                var r = this.__gnA;
                if (r && r.fetched == null) r.fetched = performance.now();
                return orig.apply(this, arguments);
            };
        });
        // MZ fetch path: _updateBuffer runs once more with _isLoaded when the stream ends.
        wrap(P, '_updateBuffer', function (orig) {
            return function () {
                var r = this.__gnA;
                if (r && this._isLoaded && r.fetched == null) r.fetched = performance.now();
                return orig.apply(this, arguments);
            };
        });
        // MZ re-decodes the growing prefix while streaming; time each call to its _onDecode.
        wrap(P, '_decodeAudioData', function (orig) {
            return function () {
                var r = this.__gnA;
                if (r) { r.decodes++; r.inflight.push(performance.now()); }
                return orig.apply(this, arguments);
            };
        });
        wrap(P, '_onDecode', function (orig) {
            return function () {
                var r = this.__gnA;
                if (r && r.inflight.length) {
                    var dt = performance.now() - r.inflight.shift();
                    r.decodeMsTotal += dt;
                    if (dt > r.decodeMsMax) r.decodeMsMax = dt;
                }
                return orig.apply(this, arguments);
            };
        });
        wrap(P, '_onLoad', function (orig) {
            return function () {
                var r = this.__gnA;
                if (r && r.firstDecoded == null) r.firstDecoded = performance.now();
                return orig.apply(this, arguments);
            };
        });
        wrap(P, 'play', function (orig) {
            return function () {
                var r = this.__gnA;
                if (r && r.playCalled == null) { r.playCalled = performance.now(); r.ctxAtPlay = ctxState(); }
                return orig.apply(this, arguments);
            };
        });
        wrap(P, '_startPlaying', function (orig) {
            return function () {
                var r = this.__gnA;
                if (r && r.started == null) {
                    r.started = performance.now();
                    r.ctxAtStart = ctxState();
                    var i = audioPending.indexOf(r);
                    if (i >= 0) audioPending.splice(i, 1);
                    if (audioEmitted < AUDIO_EMIT_MAX) {
                        audioEmitted++;
                        try { console.warn('[GN-DIAG-AUDIO] ' + JSON.stringify(audioSnapshot(r))); } catch (_e) {}
                    }
                }
                return orig.apply(this, arguments);
            };
        });
        return true;
    }
    (function pollWebAudio(n) {
        if (patchWebAudio() || n <= 0) return;
        setTimeout(function () { pollWebAudio(n - 1); }, 50);
    })(600);

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
            decode: preWindowDecode || freshDecodeStats(),
        };
        preWindowDecode = null;
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
        if (w.decode && w.decode.calls) report.audioDecode = w.decode;
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
        var out = (current && current.deltas.length >= 2) ? summarize(current, true) : null;
        if (audioPending.length) {
            out = out || { window: current ? current.idx : 0, partial: true };
            out.audioPending = audioPending.map(audioSnapshot);
        }
        return out;
    };

    function onLoad() {
        newWindow();
        requestAnimationFrame(tick);
    }
    if (document.readyState === 'complete') onLoad();
    else window.addEventListener('load', onLoad, { once: true });
})();
