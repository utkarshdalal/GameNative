// gamenative html5 worker-bundle (ES module variant)
// MODULE-WORKER variant: c3's main worker (workermain.js, where c3runtime runs and where
// the AJAX/NodeWebkit plugins evaluate `_isNWjs && require('fs')`) is constructed with
// {type:'module'}, so importScripts is unavailable. We inline the bootstrap+fs surface
// here and dynamic-import the side-effect path/os/nw shims (they parse fine as modules).

// Loaded via the stub body synthesized by AssetInterceptor.synthesizeWorkerStubBody for
// mode=module: `await import('/_shims/worker-bundle.mjs'); await import(orig);`
// Top-level await is legal in module workers, so by the time `import(orig)` runs, this
// module has fully resolved including all dynamic imports below.

'use strict';

const TAG = 'Html5WorkerShim';
// shared shim diagnostic gate -- see worker-fs.js for full doc. default OFF.
function vlog(msg) { if (!self.__gnShimVerbose) return; try { console.log(TAG + ': ' + msg); } catch (_e) {} }

// diagnostic: path.js per-call tracing. set self.__gnPathTrace = true (here or in
// DevTools) to log args + result + caller frame for every path function. default OFF --
// flipping on burns the device fans (2.7M log lines / 21s observed during MI title screen
// trace, with c3 + path traces both active). re-enable when investigating path
// composition issues.
// self.__gnPathTrace = true;

// alias for shims that read window.foo / write window.bar -- they all check `window.require`
// and self-register against `window.require.register(...)` (path.js / os.js / nw.js).
if (typeof self.window === 'undefined') {
    self.window = self;
}

// require dispatcher -- same shape as worker-bootstrap.js (classic variant).
const dispatchers = {};
function workerRequire(id) {
    vlog('require(' + id + ')');
    if (Object.prototype.hasOwnProperty.call(dispatchers, id)) return dispatchers[id];
    try {
        const base = id.split('/').pop().replace(/\.js$/, '');
        if (Object.prototype.hasOwnProperty.call(dispatchers, base)) return dispatchers[base];
    } catch (_e) {}
    try { console.warn(TAG + ': require MISS for "' + id + '" — registered: [' + Object.keys(dispatchers).join(',') + ']'); } catch (_e) {}
    throw new Error(TAG + ': module not found: ' + id);
}
workerRequire.register = function (id, impl) {
    vlog('require.register(' + id + ')');
    dispatchers[id] = impl;
};
self.require = workerRequire;

// process -- c3's _isNWjs check is exportType-based, not process-based, but the NodeWebkit
// plugin reads self.process AFTER detection so we still need it. platform=win32 to match the
// Windows-NWjs posture invariant on main thread (any future shim that branches on platform in
// worker scope should land on the same value).
self.process = {
    platform: 'win32',
    versions: { node: '20.11.1', nw: '0.83.0' },
    cwd: function () { return '/'; },
    env: {},
    arch: 'x64',
    mainModule: { filename: '/index.html' },
    execPath: '/nw',
};
workerRequire.register('process', self.process);

// child_process -- c3 NodeWebkit plugin reads `require('child_process')` for RunFile/ShellOpen.
// Stubbed so plugin construction succeeds; methods log + no-op.
const childProcess = {
    exec: function (cmd, cb) {
        vlog('child_process.exec(' + String(cmd).slice(0, 80) + ')');
        if (typeof cb === 'function') Promise.resolve().then(function () { cb(null, '', ''); });
    },
    execSync: function (cmd) {
        vlog('child_process.execSync(' + String(cmd).slice(0, 80) + ')');
        return '';
    },
    spawn: function () {
        vlog('child_process.spawn(...)');
        return { on: function () {}, stdout: { on: function () {} }, stderr: { on: function () {} } };
    },
};
workerRequire.register('child_process', childProcess);

// OPFS root resolution -- kicked off async, completes by the time c3 does its first save.
self.__gnOpfsRootCallbacks = [];
function notifyRootReady(root) {
    const cbs = self.__gnOpfsRootCallbacks || [];
    self.__gnOpfsRootCallbacks = null;
    for (let i = 0; i < cbs.length; i++) {
        try { cbs[i](root); } catch (_e) {}
    }
}
navigator.storage.getDirectory().then(function (root) {
    self.__gnOpfsRoot = root;
    vlog('OPFS root captured (mjs)');
    notifyRootReady(root);
}).catch(function (e) {
    try { console.warn(TAG + ': OPFS getDirectory failed: ' + e.message); } catch (_e) {}
    notifyRootReady(null);
});

// fs surface -- load the existing worker-fs.js as a side-effect module. It registers itself
// onto self.require via the same dispatcher we just installed. Its IIFE references self.window
// indirectly via worker-bundle.js conventions; we already aliased self.window=self above.
await import('/_shims/worker-fs.js');

// path / os / nw -- dynamic imports. Each is a pure IIFE that parses fine as a module and
// self-registers via window.require.register (window===self via alias). path.js reads
// window.require, so it MUST run AFTER we set self.require above.
await import('/_shims/path.js');
await import('/_shims/os.js');
await import('/_shims/nw.js');

vlog('bundle assembled (module)');
