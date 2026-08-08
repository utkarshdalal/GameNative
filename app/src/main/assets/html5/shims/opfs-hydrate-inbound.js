// opfs-hydrate-inbound: copies wine save dir → OPFS at launch. fixes "fresh device /
// clear data / cloud restore" cases where syncInbound (Kotlin, runs before WebView)
// pulls cloud→wine but OPFS is empty -- c3 workers' eagerHydrateOpfs sees nothing,
// game starts as if no saves exist.

// TWO POLICIES, picked by bridge.shouldOverwriteOnHydrate():
//   (a) OVERWRITE -- wine just got fresh bytes from cloud (Kotlin syncInbound ran because
//       wine > lastApplied). OPFS may hold stale bytes from a prior session (e.g. an
//       empty-Game1 settings.cfg from FIRST-LAUNCH). force-rewrite from wine so c3
//       parses the up-to-date profile state. without this, cross-device cloud restores
//       silently fail to propagate to OPFS.
//   (b) SKIP-IF-EXISTS -- wine has no fresh cloud bytes (or shouldOverwrite isn't wired
//       on a legacy bridge build). preserves any unflushed in-game saves OPFS still holds
//       from a crash-mid-flush prior session. matches the original v1 hydration policy.

// gated to pack:c3+workerShim by resolveShimUrls (parity with worker-install.js).
// no-op if __gnOpfsMirrorBridge missing (defensive -- worker-install also checks).

// timing race accepted: workers may spawn before this Promise resolves. when that
// happens, eagerHydrateOpfs sees partial state on cold-start; OPFS persists across
// launches so the SECOND launch always sees the full hydrated set. acceptable since
// the trigger condition is a rare one-off (clear-data / new device / cloud restore).
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
            // SKIP-IF-EXISTS probe.
            var probeDir = root;
            try {
                for (var k = 0; k < parts.length; k++) {
                    probeDir = await probeDir.getDirectoryHandle(parts[k], { create: false });
                }
                await probeDir.getFileHandle(fileName, { create: false });
                return 'skip';     // file already exists; preserve OPFS bytes.
            } catch (_e) {
                // NotFoundError ⇒ fall through to write.
            }
        }

        var dir = root;
        for (var k2 = 0; k2 < parts.length; k2++) {
            dir = await dir.getDirectoryHandle(parts[k2], { create: true });
        }
        var fh = await dir.getFileHandle(fileName, { create: true });
        // createWritable can fail with NoModificationAllowedError if a worker SAH already
        // holds the file -- race window between this main-thread hydrator and the worker's
        // eagerHydrateOpfs SAH-walk. treat as a soft failure: the wine bytes will be
        // re-attempted on the next launch (lastApplied gate is mtime-based, not success-
        // based). meantime, the game falls back to whatever OPFS already had -- same state
        // as if SKIP-IF-EXISTS were active.
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
        // poll for inbound readiness -- pullInstallToOpfs runs in a sibling coroutine and
        // may not have resolved activeMirrorRoot yet. cap at ~3s; bail quietly otherwise.
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

        // shouldOverwrite: bridge method may not exist on legacy bridge builds. default false ⇒
        // SKIP-IF-EXISTS (back-compat). when true, force-overwrite every file from wine.
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

        // signal workers it's safe to grab SAHs. without this, worker-fs.js eagerHydrateOpfs
        // can win the race for /settings.cfg + /Game<N>/save<N>.dat SAHs while we're still
        // mid-loop, making subsequent createWritable calls fail with NoModificationAllowedError
        // (the half-hydrated state was 2/4 on first repro). post even on shouldOverwrite=false
        // -- the worker's wait is bounded so the message is just a "hydration complete, proceed"
        // signal regardless of whether bytes actually moved.
        try {
            var bc = new BroadcastChannel('__gn_inbound_hydration__');
            bc.postMessage({ done: true });
            try { bc.close(); } catch (_e) {}
        } catch (_e) {}
    })();
})();
