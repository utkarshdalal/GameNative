// gamenative html5 worker-install -- main-thread Worker ctor proxy.
// routes new Worker(originalUrl) → /_worker_stub?mode=classic&orig=<encoded> so
// AssetInterceptor synthesizes the matching entry body. PICK = classic-worker+sync-XHR
// c3 runtime ships classic workers using importScripts; coercing
// to module-worker would break c3runtime, so we DO NOT set opts.type.

// PRESERVES sourcemaps because the original URL is loaded SECOND (importScripts inside the
// stub body) -- chromium DevTools attaches the sourcemap correctly.

// origin-binding note: the synthetic host `game-<id>` contains '_' (RFC-1123 violation), so
// chromium classifies the origin as opaque and `location.origin` returns the literal "null".
// Build the absolute URL from `location.protocol + '//' + location.host` instead.
(function () {
    'use strict';
    if (typeof self === 'undefined' || !self.Worker) return;

    var OrigWorker = self.Worker;

    // track every Worker we wrap so the main-thread exit-flush can postMessage them all.
    // each worker has its own sahCache (SAH ownership is exclusive per-file across worker
    // scopes); flushing them in parallel is the only way to get every save's bytes.
    var spawnedWorkers = [];
    self.__gnSpawnedWorkers = spawnedWorkers;
    // opt-in flush set: workers that announced gnFsActive (they actually opened an SAH or
    // queued a pendingWrite). exit-flush iterates this subset, not __gnSpawnedWorkers, so
    // dispatch/job workers without SAHs don't receive gnFlushNow (and c3's switch-default
    // doesn't log "unknown message"). also filters dead transient workers that never wrote.
    var flushableWorkers = new Set();
    self.__gnFlushableWorkers = flushableWorkers;

    // exit-flush state -- manifest-first protocol. each worker posts a single
    // gnFlushManifest{count} (combined liveness ack + work-count) followed by N
    // gnFlushFile{path,b64,size} messages. WebViewScreen polls until every worker we've
    // heard from has received==manifest. dead workers (never post manifest) are simply
    // absent from the map and don't gate exit.

    // counters retained for telemetry only (FLUSH n=N bytes=B logcat line).
    self.__gnFlushFilesWritten = 0;
    self.__gnFlushBytesWritten = 0;
    // per-worker accounting. key: Worker ref. value: { manifest: number|null, received: number }.
    // initialized lazily on first message from each worker.
    self.__gnFlushWorkerState = new Map();

    function ProxiedWorker(url, opts) {
        try {
            // mode follows opts.type -- c3 spawns workermain.js as {type:'module'} (where
            // c3runtime + AJAX/NodeWebkit plugins live) AND classic workers (dispatchworker,
            // jobworker, c3runtime blob worker). Both paths route through /_worker_stub so
            // self.require/process/fs are present in every c3 worker scope.
            var mode = (opts && opts.type === 'module') ? 'module' : 'classic';
            // build absolute base from protocol+host (location.origin may be "null" on opaque origins).
            var base = self.location.protocol + '//' + self.location.host;
            var abs = new URL(url, base).href;
            var stub = base + '/_worker_stub?mode=' + mode + '&orig=' + encodeURIComponent(abs);
            var w = new OrigWorker(stub, opts);
            spawnedWorkers.push(w);
            // attach a passive listener that watches for our flush protocol messages. multiple
            // listeners coexist -- c3's own message protocol is unaffected (we filter on type).
            w.addEventListener('message', function (e) {
                if (!e || !e.data) return;
                var d = e.data;
                if (d.type === 'gnFsActive') {
                    flushableWorkers.add(w);
                    return;
                }
                if (d.type !== 'gnFlushManifest' && d.type !== 'gnFlushFile') return;
                var st = self.__gnFlushWorkerState.get(w);
                if (!st) {
                    st = { manifest: null, received: 0 };
                    self.__gnFlushWorkerState.set(w, st);
                }
                if (d.type === 'gnFlushManifest') {
                    st.manifest = d.count;
                } else {
                    st.received++;
                    try {
                        if (typeof window !== 'undefined' && window.__gnOpfsMirrorBridge) {
                            var ok = window.__gnOpfsMirrorBridge.writeInstallFile(d.path, d.b64);
                            if (ok) {
                                self.__gnFlushFilesWritten++;
                                self.__gnFlushBytesWritten += (d.size || 0);
                            }
                        }
                    } catch (err) {
                        try { console.warn('Html5WorkerShim: flush relay failed ' + d.path + ': ' + err.message); } catch (_e) {}
                    }
                }
            });
            return w;
        } catch (e) {
            try { console.warn('Html5WorkerShim: proxy fallback to OrigWorker', e); } catch (_e) {}
            return new OrigWorker(url, opts);
        }
    }
    // PRESERVE prototype chain so `instanceof Worker` and Worker.prototype.terminate work.
    ProxiedWorker.prototype = OrigWorker.prototype;
    try { Object.setPrototypeOf(ProxiedWorker, OrigWorker); } catch (_e) {}
    self.Worker = ProxiedWorker;
    if (self.__gnShimVerbose) try { console.log('Html5WorkerShim: Worker proxy installed (mode=per-spawn)'); } catch (e) {}
})();
