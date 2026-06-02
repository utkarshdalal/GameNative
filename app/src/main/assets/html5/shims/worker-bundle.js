// gamenative html5 worker-bundle -- classic-worker variant
// PICK = classic-worker+sync-XHR. load order matters:
// 1. bootstrap (require dispatcher + process + sync-XHR ready gate + OPFS root capture)
// 2. fs (registers 'fs' onto require, sees populated __gnOpfsRoot)
// 3. path/os/nw shims -- REUSED VERBATIM from main-thread bundle. those scripts reference
// `window.X` (window.require, window.nw, window.process, etc). Workers do not have
// `window`, so we alias self → window before importScripts. After registration the
// modules are reachable via worker's self.require regardless of which global the
// registration ran against.
'use strict';
if (typeof self.window === 'undefined') {
    self.window = self;
}
importScripts('/_shims/worker-bootstrap.js');
importScripts('/_shims/worker-fs.js');
importScripts('/_shims/path.js');
importScripts('/_shims/os.js');
importScripts('/_shims/nw.js');
if (self.__gnShimVerbose) try { console.log('Html5WorkerShim: bundle assembled (classic)'); } catch (_e) {}
