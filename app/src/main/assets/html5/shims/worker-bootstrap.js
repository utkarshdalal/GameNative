// worker bootstrap: require dispatcher, `process`, and async OPFS root capture.
// navigator.storage.getDirectory() resolves in a microtask, and microtasks do NOT drain inside
// synchronous code (Chromium 109: even a sync-XHR poll loop never lets `.then` fire), so we can't
// block until OPFS is ready. we start the lookup and return; the game script yields before its first
// save, by which time __gnOpfsRoot is set. a write before any yield is queued by worker-fs.js and
// drained from the root callback.
'use strict';
(function () {
    var TAG = 'Html5WorkerShim';
    // verbose gate, default OFF -- see worker-fs.js.
    function vlog(msg) { if (!self.__gnShimVerbose) return; try { console.log(TAG + ': ' + msg); } catch (_e) {} }

    // same shape as the main-thread require-dispatcher.js, but on `self`.
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

    // an OBJECT (not a function) so c3's _isNWjs path runs in worker scope. c3's NodeWebkit plugin
    // reads execPath and mainModule.filename during init; if either is missing it throws mid-init and
    // the savesPath resolution never runs. platform=win32 matches the main thread's Windows-NW.js posture.
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

    self.__gnOpfsRootCallbacks = [];
    function notifyRootReady(root) {
        var cbs = self.__gnOpfsRootCallbacks || [];
        self.__gnOpfsRootCallbacks = null;   // late callers read __gnOpfsRoot directly.
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
