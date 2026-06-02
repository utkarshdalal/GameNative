// copies the wine save dir into OPFS at launch. cloud-inbound sync (Kotlin, before the WebView)
// only fills wine; on a fresh device / cleared data / cloud restore OPFS is empty and the workers'
// eager hydrate would find no saves.
// two policies, picked by bridge.shouldOverwriteOnHydrate():
//   OVERWRITE      -- wine just got fresh cloud bytes; OPFS may hold stale ones, so rewrite from wine.
//   SKIP-IF-EXISTS -- no fresh cloud bytes; keep OPFS, which may hold saves never flushed to wine
//                     (crash mid-flush). overwriting would lose them.
// gated to c3 packs with the worker shim by resolveShimUrls, like worker-install.js.
(function () {
    'use strict';
    if (typeof window === 'undefined' || !window.__gnOpfsMirrorBridge) return;
    if (!navigator || !navigator.storage || !navigator.storage.getDirectory) return;

    var TAG = '[opfs-hydrate-inbound]';

    async function hydrateOne(root, relPath, b64, overwrite) {
        var bin;
        try { bin = atob(b64); } catch (e) { return 'fail'; }
        var bytes = new Uint8Array(bin.length);
        for (var j = 0; j < bin.length; j++) bytes[j] = bin.charCodeAt(j);
        var parts = relPath.split('/').filter(Boolean);
        if (parts.length === 0) return 'fail';
        var fileName = parts.pop();

        if (!overwrite) {
            var probeDir = root;
            try {
                for (var k = 0; k < parts.length; k++) {
                    probeDir = await probeDir.getDirectoryHandle(parts[k], { create: false });
                }
                await probeDir.getFileHandle(fileName, { create: false });
                return 'skip';
            } catch (_e) {
                // NotFoundError: write it
            }
        }

        var dir = root;
        for (var k2 = 0; k2 < parts.length; k2++) {
            dir = await dir.getDirectoryHandle(parts[k2], { create: true });
        }
        var fh = await dir.getFileHandle(fileName, { create: true });
        // NoModificationAllowedError if a worker already holds the file's SAH. soft failure: the game
        // sees the existing OPFS bytes, as under SKIP-IF-EXISTS.
        var w;
        try {
            w = await fh.createWritable();
        } catch (e) {
            try { console.warn(TAG + ' createWritable failed for ' + relPath + ': ' + e.message); } catch (_) {}
            return 'fail';
        }
        await w.write(bytes);
        await w.close();
        return 'write';
    }

    (async function () {
        var bridge = window.__gnOpfsMirrorBridge;
        var root;
        try { root = await navigator.storage.getDirectory(); } catch (e) {
            try { console.warn(TAG + ' getDirectory failed: ' + e.message); } catch (_) {}
            return;
        }
        // pullInstallToOpfs runs in a sibling coroutine and may not have resolved the mirror root yet.
        var ready = false;
        for (var i = 0; i < 30; i++) {
            try { ready = !!(bridge.isInboundReady && bridge.isInboundReady()); } catch (_e) {}
            if (ready) break;
            await new Promise(function (r) { setTimeout(r, 100); });
        }
        if (!ready) {
            if (self.__gnShimVerbose) try { console.log(TAG + ' inbound not ready after ~3s, skipping hydration'); } catch (_) {}
            return;
        }
        var listJson;
        try { listJson = bridge.listInstallFiles(''); } catch (e) {
            try { console.warn(TAG + ' list failed: ' + e.message); } catch (_) {}
            return;
        }
        var list;
        try { list = JSON.parse(listJson || '[]'); } catch (e) { return; }
        if (!Array.isArray(list) || list.length === 0) return;

        var shouldOverwrite = false;
        try {
            shouldOverwrite = !!(bridge.shouldOverwriteOnHydrate && bridge.shouldOverwriteOnHydrate());
        } catch (_e) {}
        if (self.__gnShimVerbose) try { console.log(TAG + ' policy=' + (shouldOverwrite ? 'OVERWRITE' : 'SKIP-IF-EXISTS') + ' n=' + list.length); } catch (_) {}

        var written = 0; var skipped = 0; var failed = 0;
        for (var idx = 0; idx < list.length; idx++) {
            var relPath = list[idx];
            try {
                var b64 = bridge.readInstallFile(relPath);
                if (!b64) { failed++; continue; }
                var outcome = await hydrateOne(root, relPath, b64, shouldOverwrite);
                if (outcome === 'write') written++;
                else if (outcome === 'skip') skipped++;
                else failed++;
            } catch (e) {
                failed++;
                try { console.warn(TAG + ' hydrate failed ' + relPath + ': ' + e.message); } catch (_) {}
            }
        }
        if (self.__gnShimVerbose) try {
            console.log(TAG + ' done — written=' + written + ' skipped=' + skipped + ' failed=' + failed + ' total=' + list.length);
        } catch (_) {}

        // tell workers it's safe to grab SAHs; otherwise worker-fs.js can lock files mid-loop and
        // our createWritable calls fail. sent regardless of policy -- it just means "done".
        try {
            var bc = new BroadcastChannel('__gn_inbound_hydration__');
            bc.postMessage({ done: true });
            try { bc.close(); } catch (_e) {}
        } catch (_e) {}
    })();
})();
