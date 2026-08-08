// gamenative html5 worker-fs -- Html5WorkerShim component.
// WORKER-ONLY. addJavascriptInterface (__gnFsBridge) is MAIN-THREAD only in Android WebView;
// workers cannot call it. backend is OPFS FileSystemSyncAccessHandle (workers-only sync API
// per W3C spec; chromium android 109+, gated by ChromiumVersionGate.MIN_OPFS_SAH_MAJOR=109).
// surface mirrors fs.js v1 c3-NodeWebkit superset). fs.promises throws. all log
// messages tagged 'Html5WorkerShim:' for unified diagnostics.

// NOTE: worker-bootstrap.js does NOT block on OPFS resolution (microtasks don't drain
// inside classic-worker sync loops in chromium 109). Instead, every hot-path operation
// here checks __gnOpfsRoot at CALL time. By the time c3 calls fs.* the
// worker has yielded at least once (rAF/setTimeout/postMessage), microtasks have drained,
// and __gnOpfsRoot is populated. The probe + first writeFileSync slow-path use the
// existing async openSyncHandleAsync chain with the sync-XHR yield loop, which DOES
// drain microtasks at the next iteration once the task boundary is crossed.
'use strict';
(function () {
    var TAG = 'Html5WorkerShim';
    // shim diagnostic logger. default OFF. shared across all gamenative shims (main + worker
    // scopes both have `self`). enables both the per-worker boot sentinels and per-call fs.*
    // trace. without this, startup fires 100+ logs (per-worker dispatcher init × N workers).
    // to enable for diagnosis: in DevTools console (or worker scope), paste:
    // self.__gnShimVerbose = true
    // checked at log-time so toggling takes effect immediately. strategic logs that fire
    // ONCE per real event (eager-hydrate file, flush start/complete, UNHANDLED warnings)
    // bypass vlog and always print.
    function vlog(msg) {
        if (!self.__gnShimVerbose) return;
        try { console.log(TAG + ': ' + msg); } catch (_e) {}
    }

    function diag(obj) {
        try { console.warn(TAG + ': ' + JSON.stringify(obj)); } catch (_e) {}
    }

    // main-thread hydration handshake. opfs-hydrate-inbound runs in the page context and
    // copies wine→OPFS for cloud-restored bytes (~400ms for a 4-file save set in OVERWRITE
    // mode). without a handshake, eagerHydrateOpfs races: the worker grabs exclusive SAHs on
    // files while main is mid-hydration, which makes main's createWritable fail with
    // NoModificationAllowedError (2/4 files lost on Odin 3, including the critical settings.cfg).
    //
    // ONLY needed in OVERWRITE mode (wine has fresh cloud bytes). In SKIP-IF-EXISTS mode
    // (the common no-cloud-change relaunch path), main thread doesn't write -- no race --
    // worker can walk OPFS immediately. Kotlin injects the discriminator into the worker
    // stub as `self.__gnShouldWaitForMainHydration` BEFORE this IIFE parses; we read it once
    // and short-circuit when false. Without this short-circuit, a 5s BC timeout robbed
    // c3's first existsSync('/settings.cfg') of a populated sahCache (verified: empty cache
    // at +500ms, c3 fired existsSync at +4s, eager-hydrate didn't run until +5s timeout).
    var __gnMainHydrationDoneResolved = false;
    var __gnMainHydrationDonePromise = new Promise(function (resolve) {
        function settle() {
            if (__gnMainHydrationDoneResolved) return;
            __gnMainHydrationDoneResolved = true;
            resolve();
        }
        if (!self.__gnShouldWaitForMainHydration) {
            settle();
            try { console.log(TAG + ': main-hydration wait skipped (shouldWait=false)'); } catch (_e) {}
            return;
        }
        try {
            var bc = new BroadcastChannel('__gn_inbound_hydration__');
            bc.onmessage = function (e) {
                if (e && e.data && e.data.done) {
                    try { bc.close(); } catch (_e) {}
                    try { console.log(TAG + ': main-hydration BC received'); } catch (_e) {}
                    settle();
                }
            };
        } catch (_e) {
            settle();
            return;
        }
        setTimeout(function () {
            if (__gnMainHydrationDoneResolved) return;
            try { console.log(TAG + ': main-hydration BC timeout (5s) — proceeding'); } catch (_e) {}
            settle();
        }, 5000);
    });

    function rootOrThrow() {
        if (!self.__gnOpfsRoot) {
            throw new Error(TAG + ': OPFS root not yet resolved (worker called fs before yield)');
        }
        return self.__gnOpfsRoot;
    }

    // probe: 0xC3 0x4E 0x57 ('C3NW' marker). async chain -- observability only, off save path.
    // gates on root callback to avoid firing before bootstrap resolves.
    function fireProbe(root) {
        try {
            root.getFileHandle('__gn_worker_shim_probe__', { create: true }).then(function (fh) {
                return fh.createSyncAccessHandle();
            }).then(function (sah) {
                try {
                    var bytes = new Uint8Array([0xC3, 0x4E, 0x57]);
                    sah.write(bytes, { at: 0 });
                    sah.flush();
                } finally {
                    sah.close();
                }
                vlog('probe written');
            }).catch(function (e) {
                // co-resident workers race for the same probe path; only the first wins. SAH-
                // already-held is the expected loser case -- skip the warn for that.
                if (!e || e.name !== 'NoModificationAllowedError') {
                    diag({ event: 'probe_failed', message: String(e) });
                }
            });
        } catch (e) {
            diag({ event: 'probe_setup_failed', message: String(e) });
        }
    }
    // attach probe to bootstrap callback list (or fire immediately if root already resolved).
    if (self.__gnOpfsRoot) {
        fireProbe(self.__gnOpfsRoot);
    } else if (Array.isArray(self.__gnOpfsRootCallbacks)) {
        self.__gnOpfsRootCallbacks.push(function (root) { if (root) fireProbe(root); });
    }

    // path helpers -- verbatim path under OPFS root
    function splitPath(p) {
        var s = String(p || '');
        if (s.charAt(0) === '/') s = s.substring(1);
        return s.split('/').filter(function (x) { return x.length > 0 && x !== '.'; });
    }

    // canonicalize a relPath so cache lookups across all fs.* entry points share one form:
    //   1. backslash → forward slash. c3's NodeWebkit plugin sets `_slash = "\\"` when
    //      process.platform === "win32" (we DO claim win32 to match the Windows-NWjs posture
    //      across main + worker -- see worker-bootstrap.js). subsequent path composition uses
    //      that backslash, so existsSync inputs look like `/\save0.dat` or `/\Game1\save0.dat`.
    //      OPFS keys + sahCache always use forward slash, so we'd miss without normalization.
    //   2. collapse repeated slashes (// → /). c3 then computes
    //         _appFolder = path.dirname(process.execPath) + slash
    //      with execPath="/nwjs" yielding _appFolder="//" (or "/\" on win32 → after step 1, "//");
    //      existsSync(_appFolder + b) lands at "//save0.dat", "//settings.cfg", etc. Eager-
    //      hydrate writes single-slash keys ("/settings.cfg", "/Game1/save0.dat"), so without
    //      collapsing the double-slash queries miss the cache. Game thinks settings.cfg/saves
    //      don't exist and falls into the legacy probe loop ("Continue/Load" never appears).
    // node's path.normalize handles both naturally; we replicate just the slash-collapsing
    // subset (no `..` or `.` resolution -- splitPath already drops `.`).
    // Windows-absolute → OPFS translation. the win32 NW.js posture makes c3's NodeWebkit
    // compose absolute save paths under the game's Saved-Games folder, e.g.
    // "C:/users/xuser/Saved Games/Moonstone Island/Game1/save0.dat". __gnWinSaveRoot (injected
    // by Kotlin = the Windows form of the OPFS-mirrored wine save dir) IS the OPFS root, so a
    // path AT or BELOW it maps onto OPFS. mirrors Html5FsBridge.wineDriveC, but rooted at the
    // save dir (OPFS has no parent above it). returns null when not a win-save path.
    function normWin(s) {
        return String(s == null ? '' : s).replace(/\\/g, '/').replace(/\/+/g, '/').replace(/\/$/, '');
    }
    function winToRel(p) {
        var root = self.__gnWinSaveRoot;
        if (!root || typeof p !== 'string') return null;
        var nr = normWin(root), np = normWin(p);
        var nrl = nr.toLowerCase(), npl = np.toLowerCase();
        if (npl === nrl) return '/';
        if (npl.indexOf(nrl + '/') === 0) return '/' + np.substring(nr.length + 1); // tail keeps original case
        return null;
    }
    // p is a STRICT ancestor of the save root (e.g. "C:/users/xuser/Saved Games", which the game
    // existsSync/mkdir-probes before descending into its game folder). OPFS can't represent dirs
    // above its root, so these resolve as virtual existing directories.
    function isWinAncestor(p) {
        var root = self.__gnWinSaveRoot;
        if (!root || typeof p !== 'string') return false;
        var nr = normWin(root).toLowerCase(), np = normWin(p).toLowerCase();
        return np.length > 0 && np !== nr && nr.indexOf(np + '/') === 0;
    }

    function normPath(p) {
        if (typeof p !== 'string') return p;
        var w = winToRel(p);
        if (w !== null) return w; // C:/...SavedGames/<game>/... → OPFS-relative
        var hasBackslash = p.indexOf('\\') !== -1;
        var hasDoubleSlash = p.indexOf('//') !== -1;
        if (!hasBackslash && !hasDoubleSlash) return p;
        var s = hasBackslash ? p.replace(/\\/g, '/') : p;
        return s.indexOf('//') === -1 ? s : s.replace(/\/+/g, '/');
    }

    // SAH cache -- keyed by full relPath. populated by hydration at launch
    // AND by writeFileSync's open path on first write of any new file. once populated,
    // subsequent writes are fully synchronous via cached SAH.
    var sahCache = {};

    // directory cache -- c3 NodeWebkit calls fs.existsSync(<dirPath>) along a Fallback chain
    // to pick a savesPath. without this, dirs (incl. OPFS root '/') return false from
    // existsSync because only file paths land in sahCache. populated by eager-walk and by
    // every successful getDirectoryHandle({create:true}) on the write path.
    var dirCache = { '/': true, '': true, '.': true };
    function normDirKey(p) {
        var s = String(p == null ? '' : p);
        if (s.length > 1 && s.charAt(s.length - 1) === '/') s = s.substring(0, s.length - 1);
        return s;
    }
    function registerDir(relPath) {
        var s = normDirKey(relPath);
        // mark every prefix so existsSync('/a') and existsSync('/a/b') both hit when /a/b/c exists
        if (s.length === 0) { dirCache[''] = true; dirCache['/'] = true; return; }
        var abs = s.charAt(0) === '/';
        var segs = s.split('/').filter(function (x) { return x.length > 0; });
        var acc = abs ? '' : '';
        for (var i = 0; i < segs.length; i++) {
            acc = acc + '/' + segs[i];
            dirCache[acc] = true;
        }
    }

    // open + cache helper -- ASYNC. used by hydration and as the slow-path
    // initial-open for writeFileSync. once a file's SAH is cached, subsequent writes
    // are synchronous.
    function openSyncHandleAsync(root, relPath, create) {
        var segs = splitPath(relPath);
        if (segs.length === 0) throw new Error(TAG + ': empty path');
        var fileName = segs.pop();
        var dirPathAcc = '';
        var p = Promise.resolve(root);
        segs.forEach(function (seg) {
            dirPathAcc = dirPathAcc + '/' + seg;
            var capture = dirPathAcc;
            p = p.then(function (dir) {
                return dir.getDirectoryHandle(seg, { create: !!create }).then(function (sub) {
                    if (create) dirCache[capture] = true;
                    return sub;
                });
            });
        });
        return p.then(function (dir) { return dir.getFileHandle(fileName, { create: !!create }); })
                .then(function (fh) { return fh.createSyncAccessHandle(); });
    }

    var fs = {};

    // node fs APIs accept encoding as EITHER a string ("utf8") OR an options object
    // ({encoding:"utf8"}). c3 NodeWebkit Plugin Acts.WriteTextFile / ReadFile pass the
    // object form: `this._fs.writeFileSync(path, data, {encoding:"utf8"})`. without this
    // normalizer, our string-only check returned binary for utf8 reads and treated bytes
    // as utf8 on writes -- settings.cfg parsed as Uint8Array on c3's side, JSON.parse
    // failed silently, games dict never detected → legacy probe loop forever.
    function normEncoding(enc) {
        if (typeof enc === 'string') return enc;
        if (enc && typeof enc === 'object' && typeof enc.encoding === 'string') return enc.encoding;
        return null;
    }
    function isUtf8(enc) {
        var e = normEncoding(enc);
        return e === 'utf8' || e === 'utf-8' || e === 'UTF-8' || e === 'UTF8';
    }

    function utf8(str) {
        if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(String(str));
        var bytes = []; var s = String(str);
        for (var i = 0; i < s.length; i++) {
            var c = s.charCodeAt(i);
            if (c < 0x80) bytes.push(c);
            else if (c < 0x800) bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
            else bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
        }
        return new Uint8Array(bytes);
    }
    function utf8decode(bytes) {
        if (typeof TextDecoder !== 'undefined') return new TextDecoder().decode(bytes);
        var s = ''; for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
        return s;
    }
    function toBytes(data) {
        if (data instanceof Uint8Array) return data;
        if (data instanceof ArrayBuffer) return new Uint8Array(data);
        if (typeof data === 'string') return utf8(data);
        try { return new Uint8Array(data); } catch (_e) { return utf8(String(data)); }
    }

    // first-write path: launch-pull hydrates sahCache for all existing
    // save files BEFORE worker boot. brand-new file (game creates a save slot that never
    // existed) takes the slow-path open which uses a bounded sync-XHR yield to make the
    // open() resolve before writeFileSync returns. End-to-end atomic: every write call
    // returns only after the byte is durably in OPFS.

    // pending-write queue: brand-new files are written via async open. NW.js callers don't
    // await writeFileSync; bytes land in OPFS within microseconds (microtasks drain after
    // the worker yields). The exit-boundary FLUSH waits for the pending queue to drain so
    // saves are durable before the install-dir mirror runs.
    var pendingWrites = {};
    var pendingDrains = 0;
    function pendingWriteAsync(root, relPath, bytes) {
        pendingDrains++;
        return openSyncHandleAsync(root, relPath, true).then(function (sah) {
            try {
                // re-check pendingWrites -- a later writeFileSync may have queued newer bytes
                // for the same path. last-write-wins (matches Node fs semantics).
                var latest = pendingWrites[relPath] || bytes;
                sah.truncate(0);
                sah.write(latest, { at: 0 });
                sah.flush();
                sahCache[relPath] = { sah: sah, lastUse: Date.now() };
                announceSahActive();
                delete pendingWrites[relPath];
            } catch (e) {
                try { sah.close(); } catch (_e) {}
                diag({ event: 'pending_write_failed', path: relPath, message: String(e) });
            }
        }).catch(function (e) {
            diag({ event: 'pending_open_failed', path: relPath, message: String(e) });
        }).then(function () {
            pendingDrains--;
        });
    }
    self.__gnFsPendingDrains = function () { return pendingDrains; };

    fs.writeFileSync = function (relPath, data, encoding) {
        var origPath = relPath;
        relPath = normPath(relPath);
        var bytes = toBytes(data);
        if (self.__gnShimVerbose) try { console.log(TAG + ': writeFileSync ENTER ' + origPath + (origPath !== relPath ? ' (norm=' + relPath + ')' : '') + ' bytes=' + bytes.length); } catch (_e) {}
        vlog('fs.writeFileSync(' + relPath + ', ' + bytes.length + 'b)');
        var cached = sahCache[relPath];
        if (cached) {
            try {
                cached.sah.truncate(0);
                cached.sah.write(bytes, { at: 0 });
                cached.sah.flush();
                cached.lastUse = Date.now();
                return;
            } catch (e) {
                delete sahCache[relPath];
                diag({ event: 'sah_revoked', path: relPath, message: String(e) });
                // fall through to async open
            }
        }
        // brand-new file. classic workers can't block on .then microtasks (microtasks don't
        // drain inside sync code), so we queue + async-open + return without throwing.
        // c3's NW.js plugin doesn't await writeFileSync; the in-game "save complete" UI
        // depends only on writeFileSync returning without exception. Bytes land in OPFS
        // within microseconds once the worker yields.
        if (!self.__gnOpfsRoot) {
            // OPFS root not yet resolved -- queue, retry on root callback.
            pendingWrites[relPath] = bytes;
            announceSahActive();
            if (Array.isArray(self.__gnOpfsRootCallbacks)) {
                self.__gnOpfsRootCallbacks.push(function (root) {
                    if (!root) return;
                    pendingWriteAsync(root, relPath, bytes);
                });
            }
            return;
        }
        pendingWrites[relPath] = bytes;
        announceSahActive();
        pendingWriteAsync(self.__gnOpfsRoot, relPath, bytes);
    };

    fs.readFileSync = function (relPath, encoding) {
        var origPath = relPath;
        relPath = normPath(relPath);
        vlog('fs.readFileSync(' + relPath + ')');
        var wantUtf8 = isUtf8(encoding);
        if (self.__gnShimVerbose) try {
            var encDesc;
            if (encoding == null) encDesc = '<none>';
            else if (typeof encoding === 'string') encDesc = '"' + encoding + '"';
            else encDesc = JSON.stringify(encoding);
            console.log(TAG + ': readFileSync ENCODING ' + origPath + ' encoding=' + encDesc + ' wantUtf8=' + wantUtf8);
        } catch (_e) {}
        // pending write hits before SAH cache -- last-write-wins semantics during drain.
        if (Object.prototype.hasOwnProperty.call(pendingWrites, relPath)) {
            var pbuf = pendingWrites[relPath];
            if (self.__gnShimVerbose) try { console.log(TAG + ': readFileSync OK (pending) ' + origPath + ' (' + pbuf.length + 'b)'); } catch (_e) {}
            if (wantUtf8) return utf8decode(pbuf);
            return pbuf;
        }
        var cached = sahCache[relPath];
        if (!cached) {
            if (self.__gnShimVerbose) try { console.log(TAG + ': readFileSync ENOENT ' + origPath + (origPath !== relPath ? ' (norm=' + relPath + ')' : '')); } catch (_e) {}
            throw new Error('ENOENT: ' + relPath);
        }
        var size = cached.sah.getSize();
        var buf = new Uint8Array(size);
        cached.sah.read(buf, { at: 0 });
        var result = wantUtf8 ? utf8decode(buf) : buf;
        if (self.__gnShimVerbose) try {
            var sample;
            if (typeof result === 'string') {
                sample = 'STRING(' + result.length + ') head=' + JSON.stringify(result.slice(0, 60));
            } else {
                sample = 'BYTES(' + result.length + ') head=[' + Array.from(result.slice(0, 16)).join(',') + ']';
            }
            console.log(TAG + ': readFileSync OK ' + origPath + ' (' + size + 'b) → ' + sample);
        } catch (_e) {}
        return result;
    };

    fs.appendFileSync = function (relPath, data, encoding) {
        relPath = normPath(relPath);
        var bytes = toBytes(data);
        var cached = sahCache[relPath];
        if (cached) {
            try {
                var sz = cached.sah.getSize();
                cached.sah.write(bytes, { at: sz });
                cached.sah.flush();
                return;
            } catch (e) {
                delete sahCache[relPath];
                // fall through
            }
        }
        // brand-new file -- same async-open pattern as writeFileSync. don't throw on slow path.
        if (!self.__gnOpfsRoot) return;
        pendingDrains++;
        openSyncHandleAsync(self.__gnOpfsRoot, relPath, true).then(function (sah) {
            try {
                var sz = sah.getSize();
                sah.write(bytes, { at: sz });
                sah.flush();
                sahCache[relPath] = { sah: sah, lastUse: Date.now() };
                announceSahActive();
            } catch (e) {
                try { sah.close(); } catch (_e) {}
                diag({ event: 'append_failed', path: relPath, message: String(e) });
            }
        }).catch(function (e) {
            diag({ event: 'append_open_failed', path: relPath, message: String(e) });
        }).then(function () { pendingDrains--; });
    };

    fs.existsSync = function (relPath) {
        // ancestor of the save root (e.g. the game's Saved-Games parent) -- virtual dir, exists.
        if (isWinAncestor(relPath)) {
            if (self.__gnShimVerbose) try { console.log(TAG + ': existsSync HIT (win-ancestor) ' + relPath); } catch (_e) {}
            return true;
        }
        var p = normPath(relPath);
        var key = normDirKey(p);
        var hit = Object.prototype.hasOwnProperty.call(sahCache, p) ||
                  Object.prototype.hasOwnProperty.call(pendingWrites, p) ||
                  Object.prototype.hasOwnProperty.call(dirCache, key) ||
                  Object.prototype.hasOwnProperty.call(sahCache, key) ||
                  Object.prototype.hasOwnProperty.call(pendingWrites, key);
        if (self.__gnShimVerbose) try {
            // include short stack so we can correlate c3-runtime callers. trim to ~3 frames
            // above existsSync to skip our own wrapper noise.
            var stk = '';
            try {
                var t = new Error().stack || '';
                stk = ' caller=' + t.split('\n').slice(2, 4).join(' | ').replace(/http:\/\/127\.0\.0\.1:[0-9]+/g, '');
            } catch (_e) {}
            console.log(TAG + ': existsSync ' + (hit ? 'HIT ' : 'MISS ') + relPath + (p !== relPath ? ' (norm=' + p + ')' : '') + stk);
        } catch (_e) {}
        vlog('fs.existsSync(' + relPath + ')=' + hit);
        return hit;
    };

    fs.mkdirSync = function (relPath, opts) {
        var origPath = relPath;
        // ancestor of the save root -- virtual dir, already "exists"; no-op success.
        if (isWinAncestor(relPath)) {
            if (self.__gnShimVerbose) try { console.log(TAG + ': mkdirSync no-op (win-ancestor) ' + relPath); } catch (_e) {}
            return undefined;
        }
        relPath = normPath(relPath);
        if (self.__gnShimVerbose) try { console.log(TAG + ': mkdirSync ENTER ' + origPath + (origPath !== relPath ? ' (norm=' + relPath + ')' : '') + ' opts=' + JSON.stringify(opts)); } catch (_e) {}
        vlog('fs.mkdirSync(' + relPath + ', ' + JSON.stringify(opts) + ')');
        // OPFS auto-creates dirs on getFileHandle, BUT c3 PathExists checks the dir before
        // ever issuing a write -- so register the path so subsequent existsSync hits.
        registerDir(relPath);
        // best-effort actual creation so a directory we never touch with a file write still
        // exists across launches (c3 readdir on empty save dir, etc.). fire-and-forget.
        if (self.__gnOpfsRoot) {
            var segs = splitPath(relPath);
            if (segs.length > 0) {
                var p = Promise.resolve(self.__gnOpfsRoot);
                segs.forEach(function (seg) {
                    p = p.then(function (d) { return d.getDirectoryHandle(seg, { create: true }); });
                });
                p.catch(function (_e) {});
            }
        }
        return undefined;
    };

    fs.unlinkSync = function (relPath) {
        var origPath = relPath;
        relPath = normPath(relPath);
        if (self.__gnShimVerbose) try { console.log(TAG + ': unlinkSync ENTER ' + origPath + (origPath !== relPath ? ' (norm=' + relPath + ')' : '')); } catch (_e) {}
        vlog('fs.unlinkSync(' + relPath + ')');
        var cached = sahCache[relPath];
        if (cached) { try { cached.sah.close(); } catch (_e) {} delete sahCache[relPath]; }
        // async best-effort -- autosaves don't typically unlink, so async is acceptable here
        var segs = splitPath(relPath);
        if (segs.length === 0) return;
        var name = segs.pop();
        if (!self.__gnOpfsRoot) return;
        var p = Promise.resolve(self.__gnOpfsRoot);
        segs.forEach(function (seg) {
            p = p.then(function (d) { return d.getDirectoryHandle(seg, { create: false }); });
        });
        p.then(function (d) { return d.removeEntry(name); }).catch(function (e) {
            diag({ event: 'unlink_failed', path: relPath, message: String(e) });
        });
    };

    fs.readdirSync = function (relPath) {
        var origPath = relPath;
        relPath = normPath(relPath);
        if (self.__gnShimVerbose) try { console.log(TAG + ': readdirSync ENTER ' + origPath + (origPath !== relPath ? ' (norm=' + relPath + ')' : '')); } catch (_e) {}
        var prefix = String(relPath || '');
        if (prefix.length > 0 && prefix.charAt(prefix.length - 1) !== '/') prefix += '/';
        var out = [];
        function pushFromKey(k) {
            if (k.indexOf(prefix) === 0) out.push(k.substring(prefix.length).split('/')[0]);
        }
        for (var k1 in sahCache) {
            if (Object.prototype.hasOwnProperty.call(sahCache, k1)) pushFromKey(k1);
        }
        for (var k2 in pendingWrites) {
            if (Object.prototype.hasOwnProperty.call(pendingWrites, k2)) pushFromKey(k2);
        }
        var seen = {}; var dedupe = [];
        for (var i = 0; i < out.length; i++) { if (!seen[out[i]]) { seen[out[i]] = true; dedupe.push(out[i]); } }
        vlog('fs.readdirSync(' + relPath + ') → [' + dedupe.join(',') + ']');
        return dedupe;
    };

    fs.renameSync = function (oldRel, newRel) {
        var oOld = oldRel; var oNew = newRel;
        oldRel = normPath(oldRel); newRel = normPath(newRel);
        if (self.__gnShimVerbose) try { console.log(TAG + ': renameSync ENTER ' + oOld + ' → ' + oNew + (oOld !== oldRel || oNew !== newRel ? ' (norm: ' + oldRel + ' → ' + newRel + ')' : '')); } catch (_e) {}
        vlog('fs.renameSync(' + oldRel + ' → ' + newRel + ')');
        // OPFS has no rename until M119; emulate via copy+delete
        var src = sahCache[oldRel];
        if (!src) {
            // pending-write rename: move bytes into new pending entry without touching SAH.
            if (Object.prototype.hasOwnProperty.call(pendingWrites, oldRel)) {
                pendingWrites[newRel] = pendingWrites[oldRel];
                delete pendingWrites[oldRel];
                if (self.__gnOpfsRoot) pendingWriteAsync(self.__gnOpfsRoot, newRel, pendingWrites[newRel]);
                return;
            }
            throw new Error('ENOENT: ' + oldRel);
        }
        var size = src.sah.getSize();
        var buf = new Uint8Array(size);
        src.sah.read(buf, { at: 0 });
        fs.writeFileSync(newRel, buf);
        fs.unlinkSync(oldRel);
    };

    fs.statSync = function (relPath) {
        var origPath = relPath;
        // ancestor of the save root -- virtual directory.
        if (isWinAncestor(relPath)) {
            return { size: 0, mtime: new Date(), isFile: function () { return false; }, isDirectory: function () { return true; } };
        }
        relPath = normPath(relPath);
        if (self.__gnShimVerbose) try { console.log(TAG + ': statSync ENTER ' + origPath + (origPath !== relPath ? ' (norm=' + relPath + ')' : '')); } catch (_e) {}
        vlog('fs.statSync(' + relPath + ')');
        var cached = sahCache[relPath];
        if (cached) {
            return {
                size: cached.sah.getSize(),
                mtime: new Date(),
                isFile: function () { return true; },
                isDirectory: function () { return false; },
            };
        }
        if (Object.prototype.hasOwnProperty.call(pendingWrites, relPath)) {
            return {
                size: pendingWrites[relPath].length,
                mtime: new Date(),
                isFile: function () { return true; },
                isDirectory: function () { return false; },
            };
        }
        var dkey = normDirKey(relPath);
        if (Object.prototype.hasOwnProperty.call(dirCache, dkey)) {
            return {
                size: 0,
                mtime: new Date(),
                isFile: function () { return false; },
                isDirectory: function () { return true; },
            };
        }
        throw new Error('ENOENT: ' + relPath);
    };

    fs.copyFileSync = function (srcRel, dstRel) {
        var oSrc = srcRel; var oDst = dstRel;
        srcRel = normPath(srcRel); dstRel = normPath(dstRel);
        if (self.__gnShimVerbose) try { console.log(TAG + ': copyFileSync ENTER ' + oSrc + ' → ' + oDst); } catch (_e) {}
        vlog('fs.copyFileSync(' + srcRel + ' → ' + dstRel + ')');
        var src = sahCache[srcRel];
        var srcBytes;
        if (src) {
            var size = src.sah.getSize();
            srcBytes = new Uint8Array(size);
            src.sah.read(srcBytes, { at: 0 });
        } else if (Object.prototype.hasOwnProperty.call(pendingWrites, srcRel)) {
            srcBytes = pendingWrites[srcRel];
        } else {
            throw new Error('ENOENT: ' + srcRel);
        }
        fs.writeFileSync(dstRel, srcBytes);
    };

    fs.realpathSync = function (relPath) {
        vlog('fs.realpathSync(' + relPath + ')');
        return String(relPath);
    };
    fs.realpathSync.native = fs.realpathSync;

    fs.accessSync = function (relPath, mode) {
        vlog('fs.accessSync(' + relPath + ', ' + mode + ')');
        if (!fs.existsSync(relPath)) throw new Error('ENOENT: ' + relPath);
    };

    fs.lstatSync = function (relPath) {
        vlog('fs.lstatSync(' + relPath + ')');
        return fs.statSync(relPath);
    };

    fs.fstatSync = function (fd) {
        vlog('fs.fstatSync(' + fd + ')');
        // we don't issue fds -- return minimal shape
        return { size: 0, mtime: new Date(), isFile: function () { return true; }, isDirectory: function () { return false; } };
    };

    fs.openSync = function (relPath, flags, mode) {
        vlog('fs.openSync(' + relPath + ', ' + flags + ')');
        // we don't issue fds -- return path-as-fd. close/read/write fd APIs route by string.
        return relPath;
    };
    fs.closeSync = function (fd) {
        vlog('fs.closeSync(' + fd + ')');
    };
    fs.writeSync = function (fd, buf, offset, length, position) {
        vlog('fs.writeSync(' + fd + ', ' + (buf && buf.length) + 'b)');
        fs.writeFileSync(String(fd), buf);
        return (buf && buf.length) || 0;
    };
    fs.readSync = function (fd, buf, offset, length, position) {
        vlog('fs.readSync(' + fd + ', length=' + length + ')');
        var bytes = fs.readFileSync(String(fd));
        if (!bytes) return 0;
        var copyLen = Math.min(length || bytes.length, bytes.length);
        for (var i = 0; i < copyLen; i++) buf[(offset || 0) + i] = bytes[i];
        return copyLen;
    };

    // async fs.* -- log every call so we see if c3 uses them. promise-based passthroughs
    // wrap the sync variants. callbacks invoke (err, result) async via Promise.resolve.
    function asyncWrap(name, syncFn) {
        return function () {
            var args = Array.prototype.slice.call(arguments);
            if (self.__gnShimVerbose) try { console.log(TAG + ': ASYNC fs.' + name + ' ENTER ' + (typeof args[0] === 'string' ? args[0] : '<arg0:' + typeof args[0] + '>')); } catch (_e) {}
            var cb = (typeof args[args.length - 1] === 'function') ? args.pop() : null;
            try {
                var result = syncFn.apply(null, args);
                if (cb) Promise.resolve().then(function () { cb(null, result); });
                return result;
            } catch (e) {
                if (cb) Promise.resolve().then(function () { cb(e); });
                else throw e;
            }
        };
    }
    fs.writeFile = asyncWrap('writeFile', fs.writeFileSync);
    fs.readFile = asyncWrap('readFile', fs.readFileSync);
    fs.exists = function (relPath, cb) {
        if (self.__gnShimVerbose) try { console.log(TAG + ': ASYNC fs.exists ENTER ' + relPath); } catch (_e) {}
        var hit = fs.existsSync(relPath);
        if (cb) Promise.resolve().then(function () { cb(hit); });
    };
    fs.unlink = asyncWrap('unlink', fs.unlinkSync);
    fs.rename = asyncWrap('rename', fs.renameSync);
    fs.mkdir = asyncWrap('mkdir', fs.mkdirSync);
    fs.readdir = asyncWrap('readdir', fs.readdirSync);
    fs.stat = asyncWrap('stat', fs.statSync);
    fs.lstat = asyncWrap('lstat', fs.lstatSync);
    fs.access = asyncWrap('access', fs.accessSync);
    fs.copyFile = asyncWrap('copyFile', fs.copyFileSync);
    fs.appendFile = asyncWrap('appendFile', fs.appendFileSync);
    fs.realpath = asyncWrap('realpath', fs.realpathSync);
    fs.realpath.native = fs.realpath;

    // promise-based surface -- c3 NW.js sometimes uses fs.promises. log every access.
    var promisesImpl = {};
    ['writeFile', 'readFile', 'unlink', 'rename', 'mkdir', 'readdir', 'stat', 'lstat',
     'access', 'copyFile', 'appendFile', 'realpath']
        .forEach(function (n) {
            promisesImpl[n] = function () {
                var args = Array.prototype.slice.call(arguments);
                vlog('fs.promises.' + n + '(...)');
                try { return Promise.resolve(fs[n + 'Sync'].apply(null, args)); } catch (e) { return Promise.reject(e); }
            };
        });
    fs.promises = new Proxy(promisesImpl, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            try { console.warn(TAG + ': fs.promises.' + String(prop) + ' (UNHANDLED)'); } catch (_e) {}
            return function () { return Promise.reject(new Error(TAG + ': fs.promises.' + String(prop) + ' not implemented')); };
        },
    });

    // catch-all fallback so we see any c3 call into a method we forgot. once registered,
    // fs is wrapped in a Proxy that logs unknown accesses (call OR property read).
    var fsProxied = new Proxy(fs, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            if (typeof prop === 'symbol') return target[prop];
            try { console.warn(TAG + ': fs.' + String(prop) + ' (UNHANDLED — c3 wants this)'); } catch (_e) {}
            // return a logging stub so callers don't crash; c3 picks up "no-op succeeded" semantics.
            return function () {
                var args = Array.prototype.slice.call(arguments);
                try { console.warn(TAG + ': fs.' + String(prop) + '(...) called with ' + args.length + ' args'); } catch (_e) {}
                // if last arg is a callback, invoke it with no error so c3 thinks op succeeded.
                var cb = (typeof args[args.length - 1] === 'function') ? args[args.length - 1] : null;
                if (cb) Promise.resolve().then(function () { cb(null); });
                return undefined;
            };
        },
    });

    // hydration entry point used by OpfsMirrorBridge populates sahCache so
    // the FIRST writeFileSync from game code lands in the cached path (zero async slow-path).
    self.__gnFsHydrate = function (relPath, bytes) {
        var root = rootOrThrow();
        return openSyncHandleAsync(root, relPath, true).then(function (sah) {
            try {
                sah.truncate(0);
                sah.write(bytes, { at: 0 });
                sah.flush();
                sahCache[relPath] = { sah: sah, lastUse: Date.now() };
                announceSahActive();
            } catch (e) {
                diag({ event: 'hydrate_failed', path: relPath, message: String(e) });
                try { sah.close(); } catch (_e) {}
            }
        });
    };

    // Eager OPFS walk at worker boot -- repopulates sahCache from on-disk files so
    // existsSync/readFileSync see saves written in prior sessions. Without this, OPFS
    // persistence is invisible to c3's `_isNWjs ? fs.existsSync(savePath) : ...` boot
    // checks (e.g. /settings.cfg, /<gameFolder>/saveN.dat) and the game falls back to
    // legacy default paths, hiding the existing save.
    //
    // PRIMARY-WORKER GATE: opening a SyncAccessHandle is exclusive across worker scopes --
    // only one worker can hold a SAH for a given file at a time. Without gating, ALL
    // workers (c3's workermain + dispatchworker + jobworker) race for save-file SAHs.
    // Whoever wins keeps the SAH; whoever loses gets NoModificationAllowedError and skips.
    // Result: which worker holds which save is non-deterministic across launches. If
    // c3's existsSync (which runs in workermain to query "are there saves?") doesn't
    // hold the SAH for /Game1/saveN.dat, sahCache misses and the game shows "no saves"
    // even though the file is on disk. Synthesizer sets self.__gnPrimaryWorker=true ONLY
    // for module-mode workers (workermain), so dispatchworker/jobworker (classic) skip the
    // walk entirely. workermain wins every SAH race deterministically.
    function eagerHydrateOpfs(root) {
        if (!self.__gnPrimaryWorker) {
            if (self.__gnShimVerbose) try { console.log(TAG + ': eager-hydrate skipped (non-primary worker)'); } catch (_e) {}
            return;
        }
        // wait for main-thread inbound hydration before grabbing SAHs -- otherwise we race
        // page-context createWritable on cloud-restored files and lose the first wave of
        // SAH-conflicted writes (settings.cfg, save<N>.dat). promise auto-resolves on 5s
        // timeout if main never broadcasts (legacy bridge, no fresh wine bytes, etc.).
        __gnMainHydrationDonePromise.then(function () {
            eagerHydrateOpfsAfterMainDone(root);
        });
    }
    function eagerHydrateOpfsAfterMainDone(root) {
        function walk(dir, prefix) {
            return (async function () {
                try {
                    for await (var entry of dir.entries()) {
                        var name = entry[0]; var handle = entry[1];
                        if (name === '__gn_worker_shim_probe__') continue;
                        var p = prefix ? prefix + '/' + name : '/' + name;
                        if (handle.kind === 'file') {
                            try {
                                // open SAH eagerly so readFileSync hits the cache too. SAH is
                                // exclusive -- if a co-resident scope (workermain vs c3 blob worker)
                                // already opened it, this throws and we just skip; existsSync
                                // still returns true for the path because it's in pendingWrites/
                                // sahCache when we successfully claim it.
                                var sah = await handle.createSyncAccessHandle();
                                sahCache[p] = { sah: sah, lastUse: Date.now() };
                                announceSahActive();
                                try { console.log(TAG + ': eager-hydrated ' + p + ' (' + sah.getSize() + 'b)'); } catch (_e) {}
                            } catch (e) {
                                // SAH-already-held is the expected case in co-resident workers
                                // (whichever worker opens first wins; others silently skip). only
                                // log unexpected errors so the spam stays out of normal logs.
                                if (!e || e.name !== 'NoModificationAllowedError') {
                                    try { console.log(TAG + ': eager-hydrate skipped ' + p + ' (' + e.message + ')'); } catch (_e) {}
                                }
                            }
                        } else if (handle.kind === 'directory') {
                            dirCache[p] = true;
                            await walk(handle, p);
                        }
                    }
                } catch (e) {
                    diag({ event: 'eager_walk_failed', prefix: prefix, message: String(e) });
                }
            })();
        }
        walk(root, '');
    }
    if (self.__gnOpfsRoot) {
        eagerHydrateOpfs(self.__gnOpfsRoot);
    } else if (Array.isArray(self.__gnOpfsRootCallbacks)) {
        self.__gnOpfsRootCallbacks.push(function (root) { if (root) eagerHydrateOpfs(root); });
    }
    // diagnostic: dump full sahCache contents 500ms after worker boot so we can compare
    // what's loaded vs. what existsSync queries fail to find. always-on for triage.
    if (self.__gnPrimaryWorker) {
        setTimeout(function () {
            try {
                var keys = Object.keys(sahCache);
                console.log(TAG + ': sahCache snapshot (' + keys.length + ') ' + JSON.stringify(keys));
            } catch (_e) {}
        }, 500);
    }

    // exposed for OpfsMirrorBridge at exit boundary 
    self.__gnFsListCached = function () { return Object.keys(sahCache); };
    self.__gnFsReadCached = function (relPath) {
        var cached = sahCache[relPath];
        if (!cached) return null;
        var size = cached.sah.getSize();
        var buf = new Uint8Array(size);
        cached.sah.read(buf, { at: 0 });
        return buf;
    };

    // worker-side flush handler -- cloud-fix. main thread posts {type:'gnFlushNow'}
    // and we iterate sahCache (every file we hold an SAH for), read each via SAH (sync -- we
    // ARE the lock holder, no conflicts), base64-encode, and postMessage back as
    // {type:'gnFlushFile', path, b64}. Final {type:'gnFlushDone'}.

    // why this exists: main thread evaluateJavascript walking OPFS via getFile() hangs when
    // the worker holds SAHs (chromium 109 SAH-exclusive lock). worker self-flush avoids the
    // conflict because the worker reads via its own held SAH synchronously.

    // perf: bytesToB64 uses FileReader.readAsDataURL on a Blob -- chromium's native base64
    // path. avoids building a 5MB intermediate JS string before btoa (the prior pure-JS
    // path was the dominant cost, ~200-400ms per 5MB file). encoding tasks for all sahCache
    // entries kick off in parallel via Promise.all (FileReader runs on a chromium internal
    // thread, not the worker JS thread). 3×5MB flush dropped from ~600-1200ms to ~100-200ms.
    function bytesToB64Async(buf) {
        return new Promise(function (resolve, reject) {
            var fr = new FileReader();
            fr.onload = function () {
                var s = fr.result; // "data:application/octet-stream;base64,XXXX"
                var comma = s.indexOf(',');
                resolve(comma >= 0 ? s.substring(comma + 1) : '');
            };
            fr.onerror = function () { reject(fr.error || new Error('FileReader failed')); };
            fr.readAsDataURL(new Blob([buf]));
        });
    }
    // opt-in flush channel: announce ONCE when this worker actually opens an SAH. main
    // restricts gnFlushNow broadcast to announced workers. avoids posting to c3 dispatch/job
    // workers (no SAHs → c3's switch-default logs "unknown message 'gnFlushNow'") and to
    // dead transient workers that registered the listener at boot then terminated.
    var sahActiveAnnounced = false;
    function announceSahActive() {
        if (sahActiveAnnounced) return;
        sahActiveAnnounced = true;
        try { self.postMessage({ type: 'gnFsActive' }); } catch (_e) {}
    }

    self.addEventListener('message', async function (e) {
        if (!e || !e.data || e.data.type !== 'gnFlushNow') return;
        // wait briefly for any in-flight pendingWriteAsync to drain. brand-new files queue
        // bytes via pendingWriteAsync first-write path) -- if exit fires mid-drain we'd
        // miss them. cap the wait at 5s so a stuck async never blocks teardown.
        var pendDeadline = Date.now() + 5000;
        while (pendingDrains > 0 && Date.now() < pendDeadline) {
            await new Promise(function (r) { setTimeout(r, 25); });
        }
        var t0 = Date.now();

        // build work list (sahCache + pendingWrites that haven't reached sahCache).
        var workItems = [];
        var sahPaths = Object.keys(sahCache);
        for (var i = 0; i < sahPaths.length; i++) {
            var p = sahPaths[i];
            if (p.indexOf('__gn_worker_shim_probe') !== -1) continue;
            var entry = sahCache[p];
            if (!entry || !entry.sah) continue;
            workItems.push({ path: p, sah: entry.sah, pending: null });
        }
        var pendingPaths = Object.keys(pendingWrites);
        for (var j = 0; j < pendingPaths.length; j++) {
            var pp = pendingPaths[j];
            if (pp.indexOf('__gn_worker_shim_probe') !== -1) continue;
            if (Object.prototype.hasOwnProperty.call(sahCache, pp)) continue;
            workItems.push({ path: pp, sah: null, pending: pendingWrites[pp] });
        }

        // manifest FIRST: combined liveness ack + work-count. main reads this to know
        // (a) this worker is alive, (b) how many gnFlushFile messages to expect.
        // posted synchronously before any encode work -- guaranteed to land before any
        // gnFlushFile from this worker (postMessage is FIFO per channel).
        try { self.postMessage({ type: 'gnFlushManifest', count: workItems.length }); } catch (_e) {}
        try { console.log(TAG + ': flush starting, work=' + workItems.length); } catch (_e) {}

        // encode + post in parallel. FileReader uses chromium native base64 on a separate
        // thread -- Promise.all parallelism actually helps (vs same-thread JS encoding).
        var tasks = workItems.map(function (item) {
            return (async function () {
                try {
                    var buf;
                    if (item.sah) {
                        var sz = item.sah.getSize();
                        buf = new Uint8Array(sz);
                        item.sah.read(buf, { at: 0 });
                    } else {
                        buf = item.pending;
                    }
                    var b64 = await bytesToB64Async(buf);
                    self.postMessage({ type: 'gnFlushFile', path: item.path, b64: b64, size: buf.length });
                    return true;
                } catch (err) {
                    try { console.warn(TAG + ': flush task failed ' + item.path + ': ' + (err && err.message)); } catch (_e) {}
                    return false;
                }
            })();
        });
        var results = await Promise.all(tasks);
        var posted = 0;
        for (var k = 0; k < results.length; k++) if (results[k]) posted++;
        // no gnFlushDone -- main accounting uses received==manifest as completion signal,
        // so a separate done-message is redundant. log BEFORE we yield in case the worker
        // gets terminated by webview teardown immediately after.
        try { console.log(TAG + ': flush complete posted=' + posted + '/' + workItems.length + ' elapsed=' + (Date.now() - t0) + 'ms'); } catch (_e) {}
    });

    // register the PROXIED fs so unknown method calls log loudly. require('fs') returns
    // fsProxied; direct property accesses (fs.writeFileSync) hit the original target with
    // logging baked in.
    if (typeof self.require === 'function' && typeof self.require.register === 'function') {
        self.require.register('fs', fsProxied);
    }
    vlog('worker-fs registered (proxied surface, ' + Object.keys(fs).length + ' methods)');
})();
