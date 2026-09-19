// classic-worker shim bundle. load order matters: bootstrap (require + process + OPFS root), then
// fs, then the main-thread path / os / nw shims reused verbatim. those reference `window.*`, which
// workers lack, so alias self → window first.
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
