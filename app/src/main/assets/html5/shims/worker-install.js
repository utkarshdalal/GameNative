// main-thread Worker ctor proxy: new Worker(url) → /_worker_stub?mode=<classic|module>&orig=<url>,
// whose synthesized body installs the worker shims, then loads the original script (so sourcemaps
// still attach). opts.type is passed through unchanged -- coercing c3's classic workers breaks them.
// the synthetic host `game-<id>` contains '_' (not RFC-1123), so Chromium treats the origin as
// opaque and `location.origin` is "null": build absolute URLs from protocol + host instead.
(function () {
    'use strict';
    if (typeof self === 'undefined' || !self.Worker) return;

    var OrigWorker = self.Worker;

    // SAH ownership is exclusive per file across workers, so each worker holds its own saves and
    // exit-flush must reach all of them.
    var spawnedWorkers = [];
    self.__gnSpawnedWorkers = spawnedWorkers;
    // workers that announced gnFsActive. exit-flush targets only these, so SAH-less c3 workers
    // don't log "unknown message" and dead transient workers aren't awaited.
    var flushableWorkers = new Set();
    self.__gnFlushableWorkers = flushableWorkers;

    // exit-flush protocol: each worker posts gnFlushManifest{count}, then count gnFlushFile messages.
    // WebViewScreen polls until every worker heard from has received == manifest; dead workers
    // never post a manifest, so they don't gate exit.

    // telemetry only
    self.__gnFlushFilesWritten = 0;
    self.__gnFlushBytesWritten = 0;
    // Worker → { manifest: number|null, received: number }
    self.__gnFlushWorkerState = new Map();

    function ProxiedWorker(url, opts) {
        try {
            // c3 spawns both module (workermain) and classic workers; both go through the stub so
            // require / process / fs exist in every worker scope.
            var mode = (opts && opts.type === 'module') ? 'module' : 'classic';
            var base = self.location.protocol + '//' + self.location.host;
            var abs = new URL(url, base).href;
            var stub = base + '/_worker_stub?mode=' + mode + '&orig=' + encodeURIComponent(abs);
            var w = new OrigWorker(stub, opts);
            spawnedWorkers.push(w);
            // passive listener; the game's own messages are untouched (filtered by type).
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
    // keep the prototype chain so `instanceof Worker` and terminate() still work.
    ProxiedWorker.prototype = OrigWorker.prototype;
    try { Object.setPrototypeOf(ProxiedWorker, OrigWorker); } catch (_e) {}
    self.Worker = ProxiedWorker;
    if (self.__gnShimVerbose) try { console.log('Html5WorkerShim: Worker proxy installed (mode=per-spawn)'); } catch (e) {}
})();
