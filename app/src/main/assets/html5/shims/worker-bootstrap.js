// gamenative html5 worker-bootstrap -- classic-worker variant
// PICK = classic-worker+sync-XHR 

// Classic-worker microtask reality: navigator.storage.getDirectory() is async and resolves
// in a microtask. Microtasks DO NOT drain inside synchronous loops (tested in Chromium 109
// on Adreno 740 -- 100 sync-XHR poll iterations did not let `.then` fire). So we cannot
// "block until OPFS ready" inside this IIFE. Instead:

// 1. Kick off `navigator.storage.getDirectory().then(captureRoot)` -- IIFE returns immediately.
// 2. importScripts(c3worker) runs synchronously after bootstrap returns. c3 sets up its own
// handlers and YIELDS (via setTimeout/postMessage/rAF) before its first save call.
// 3. By the time c3's first fs.writeFileSync fires, microtasks have drained at least once
// and __gnOpfsRoot is populated. worker-fs.js trusts this on its hot path.
// 4. The rare case where c3 writes synchronously in its initial import (before any yield)
// is handled by worker-fs.js: it queues the write and drains on root-resolved callback.
'use strict';
(function () {
    var TAG = 'Html5WorkerShim';
    // shared shim diagnostic gate -- see worker-fs.js for full doc. default OFF.
    function vlog(msg) { if (!self.__gnShimVerbose) return; try { console.log(TAG + ': ' + msg); } catch (_e) {} }

    // require dispatcher -- same shape as main-thread require-dispatcher.js but on `self`.
    // shims registered after this (worker-fs, path, os, nw) bind module ids onto this table.
    var dispatchers = {};
    function workerRequire(id) {
        vlog('require(' + id + ')');
        if (Object.prototype.hasOwnProperty.call(dispatchers, id)) return dispatchers[id];
        try {
            var base = id.split('/').pop().replace(/\.js$/, '');
            if (Object.prototype.hasOwnProperty.call(dispatchers, base)) return dispatchers[base];
        } catch (_e) {}
        try { console.warn(TAG + ': require MISS for "' + id + '" — registered modules: [' + Object.keys(dispatchers).join(',') + ']'); } catch (_e) {}
        throw new Error(TAG + ': module not found: ' + id);
    }
    workerRequire.register = function (id, impl) {
        vlog('require.register(' + id + ')');
        dispatchers[id] = impl;
    };
    self.require = workerRequire;

    // process -- OBJECT (not function) so c3's _isNWjs path runs in worker scope.
    // execPath / mainModule.filename are read by c3's NodeWebkit Plugin _InitNWjs():
    // this._appFolder = path.dirname(this._process.execPath) + slash
    // this._projectFilesFolder = this._process.mainModule.filename
    // when missing, accessing `.filename` on undefined throws TypeError mid-init, leaving
    // the plugin partially initialized -- downstream events that gate on _projectFilesFolder
    // never fire, including the savesPath fallback chain that resolves the per-gameFile
    // save folder. give them harmless string values so init runs to completion.
    // platform=win32 to match the Windows-NWjs posture invariant on main thread (any future
    // shim that branches on platform in worker scope should land on the same value).
    self.process = {
        platform: 'win32',
        versions: { node: '20.11.1', nw: '0.83.0' },
        cwd: function () { return '/'; },
        env: {},
        arch: 'x64',
        execPath: '/nwjs',
        mainModule: { filename: '/index.html' },
    };
    workerRequire.register('process', self.process);

    // Async OPFS root resolution. Microtask drains after bootstrap IIFE returns + after
    // c3's importScripts returns + after the first c3 yield.
    self.__gnOpfsRootCallbacks = [];
    function notifyRootReady(root) {
        var cbs = self.__gnOpfsRootCallbacks || [];
        self.__gnOpfsRootCallbacks = null;   // freeze list -- late callers go via __gnOpfsRoot direct.
        for (var i = 0; i < cbs.length; i++) {
            try { cbs[i](root); } catch (_e) {}
        }
    }
    navigator.storage.getDirectory().then(function (root) {
        self.__gnOpfsRoot = root;
        vlog('OPFS root captured');
        notifyRootReady(root);
    }).catch(function (e) {
        try { console.warn(TAG + ': OPFS getDirectory failed: ' + e.message); } catch (_e) {}
        notifyRootReady(null);
    });

    vlog('bootstrap dispatcher installed (classic)');
})();
