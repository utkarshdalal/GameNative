// worker-side node `fs` shim. __gnFsBridge (addJavascriptInterface) is main-thread only in Android
// WebView, so workers use OPFS FileSystemSyncAccessHandle (worker-only sync API; Chromium 109+, gated
// by ChromiumVersionGate.MIN_OPFS_SAH_MAJOR).
// worker-bootstrap.js can't block on OPFS root resolution (microtasks don't drain inside sync code in
// a classic worker), so every fs.* checks __gnOpfsRoot at CALL time. by the time the game calls fs.*
// the worker has yielded and the root is populated.
'use strict';
(function () {
    var TAG = 'Html5WorkerShim';
    // verbose logging, default OFF (per-worker init × N workers would spam 100+ lines). enable from
    // DevTools with `self.__gnShimVerbose = true`; checked per call. one-shot logs for real events
    // (eager-hydrate, flush, UNHANDLED) bypass it.
    function vlog(msg) {
        if (!self.__gnShimVerbose) return;
        try { console.log(TAG + ': ' + msg); } catch (_e) {}
    }

    function diag(obj) {
        try { console.warn(TAG + ': ' + JSON.stringify(obj)); } catch (_e) {}
    }

    // main-thread hydration handshake. opfs-hydrate-inbound copies cloud-restored wine bytes into
    // OPFS from the page context. if the worker grabs exclusive SAHs mid-copy, main's createWritable
    // fails with NoModificationAllowedError and those files are LOST.
    // only OVERWRITE mode writes from main. Kotlin sets self.__gnShouldWaitForMainHydration before
    // this parses; when false, skip the wait -- the 5s timeout would otherwise delay eager-hydrate
    // past the game's first existsSync.
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

    // writes a 'C3NW' marker file. observability only, off the save path.
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
                // co-resident workers race for the probe; SAH-already-held is the expected loss.
                if (!e || e.name !== 'NoModificationAllowedError') {
                    diag({ event: 'probe_failed', message: String(e) });
                }
            });
        } catch (e) {
            diag({ event: 'probe_setup_failed', message: String(e) });
        }
    }
    if (self.__gnOpfsRoot) {
        fireProbe(self.__gnOpfsRoot);
    } else if (Array.isArray(self.__gnOpfsRootCallbacks)) {
        self.__gnOpfsRootCallbacks.push(function (root) { if (root) fireProbe(root); });
    }

    function splitPath(p) {
        var s = String(p || '');
        if (s.charAt(0) === '/') s = s.substring(1);
        return s.split('/').filter(function (x) { return x.length > 0 && x !== '.'; });
    }

    // Windows-absolute → OPFS. under the win32 NW.js posture, c3's NodeWebkit composes absolute save
    // paths like "C:/users/xuser/Saved Games/<game>/Game1/save0.dat". __gnWinSaveRoot (Kotlin-injected
    // Windows form of the OPFS-mirrored wine save dir) IS the OPFS root, so paths at or below it map
    // onto OPFS. returns null for anything else.
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
    // STRICT ancestors of the save root (e.g. ".../Saved Games", probed before descending) can't
    // exist in OPFS, so they resolve as virtual existing directories.
    function isWinAncestor(p) {
        var root = self.__gnWinSaveRoot;
        if (!root || typeof p !== 'string') return false;
        var nr = normWin(root).toLowerCase(), np = normWin(p).toLowerCase();
        return np.length > 0 && np !== nr && nr.indexOf(np + '/') === 0;
    }

    // one cache-key form for every fs.* entry point:
    //   - backslash → '/': we claim win32, so c3's NodeWebkit composes with "\\" (`/\Game1\save0.dat`).
    //   - collapse '//': execPath "/nwjs" makes c3's _appFolder "//", so lookups hit "//save0.dat"
    //     while hydration keys are single-slash. a miss hides existing saves from the game.
    // no `.` / `..` resolution -- splitPath already drops `.`.
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

    // keyed by relPath; filled by hydration and by the first write of a new file. a cached SAH
    // makes writes fully synchronous.
    var sahCache = {};

    // dirs never land in sahCache, but c3 NodeWebkit existsSync-probes dirs (incl. '/') to pick a
    // savesPath. filled by the eager walk, mkdirSync and every create-mode getDirectoryHandle.
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

    // ASYNC; used by hydration and as writeFileSync's first-open slow path.
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

    // node accepts encoding as a string or {encoding}; c3 NodeWebkit passes the object form. missing
    // it returns bytes for utf8 reads and the game's JSON.parse of its settings silently fails.
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

    // brand-new files open asynchronously; NW.js callers don't await writeFileSync, so bytes queue in
    // pendingWrites until the open resolves. the exit flush waits for pendingDrains to hit zero so
    // saves are durable before the install-dir mirror runs.
    var pendingWrites = {};
    var pendingDrains = 0;
    function pendingWriteAsync(root, relPath, bytes) {
        pendingDrains++;
        return openSyncHandleAsync(root, relPath, true).then(function (sah) {
            try {
                // a later writeFileSync may have queued newer bytes for this path: last write wins.
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
            }
        }
        // brand-new file. a classic worker can't block on a promise, so queue + async-open and return
        // without throwing -- the game's save UI only needs writeFileSync not to throw.
        if (!self.__gnOpfsRoot) {
            // root not resolved yet: retry from the root callback.
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
        // pending bytes win over the SAH cache: last write wins during drain.
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
            }
        }
        // brand-new file: async open like writeFileSync, never throw.
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
            // short caller stack to correlate game callers.
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
        // ancestor of the save root: virtual dir, already exists.
        if (isWinAncestor(relPath)) {
            if (self.__gnShimVerbose) try { console.log(TAG + ': mkdirSync no-op (win-ancestor) ' + relPath); } catch (_e) {}
            return undefined;
        }
        relPath = normPath(relPath);
        if (self.__gnShimVerbose) try { console.log(TAG + ': mkdirSync ENTER ' + origPath + (origPath !== relPath ? ' (norm=' + relPath + ')' : '') + ' opts=' + JSON.stringify(opts)); } catch (_e) {}
        vlog('fs.mkdirSync(' + relPath + ', ' + JSON.stringify(opts) + ')');
        // c3 checks the dir exists before any write, so register it even though writes create dirs.
        registerDir(relPath);
        // create it for real too (fire-and-forget) so an empty dir survives relaunch.
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
        // async best-effort; saves rarely unlink.
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
        // OPFS has no rename (FileSystemHandle.move) before Chromium 119, so emulate it via copy+delete
        var src = sahCache[oldRel];
        if (!src) {
            // not on disk yet: move the queued bytes.
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
        // no real fds -- minimal shape
        return { size: 0, mtime: new Date(), isFile: function () { return true; }, isDirectory: function () { return false; } };
    };

    fs.openSync = function (relPath, flags, mode) {
        vlog('fs.openSync(' + relPath + ', ' + flags + ')');
        // no real fds: the path IS the fd; the fd APIs route by string.
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

    // callback variants wrap the sync ones; callbacks fire on a microtask.
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

    // unknown methods log loudly and return a stub that reports success to callbacks, so the game
    // keeps running instead of crashing.
    var fsProxied = new Proxy(fs, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            if (typeof prop === 'symbol') return target[prop];
            try { console.warn(TAG + ': fs.' + String(prop) + ' (UNHANDLED — c3 wants this)'); } catch (_e) {}
            return function () {
                var args = Array.prototype.slice.call(arguments);
                try { console.warn(TAG + ': fs.' + String(prop) + '(...) called with ' + args.length + ' args'); } catch (_e) {}
                var cb = (typeof args[args.length - 1] === 'function') ? args[args.length - 1] : null;
                if (cb) Promise.resolve().then(function () { cb(null); });
                return undefined;
            };
        },
    });

    // OpfsMirrorBridge hydration hook: pre-populates sahCache so the game's first write is sync.
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

    // eager OPFS walk at worker boot: repopulates sahCache so saves from prior sessions are visible to
    // the game's boot-time existsSync checks; otherwise it falls back to defaults and hides them.
    // PRIMARY WORKER ONLY: SAHs are exclusive across workers. if c3's workermain, dispatchworker and
    // jobworker all race, workermain (which asks "are there saves?") may lose and show none. the
    // synthesizer sets self.__gnPrimaryWorker only for module-mode workers (workermain).
    function eagerHydrateOpfs(root) {
        if (!self.__gnPrimaryWorker) {
            if (self.__gnShimVerbose) try { console.log(TAG + ': eager-hydrate skipped (non-primary worker)'); } catch (_e) {}
            return;
        }
        // wait for main-thread inbound hydration first (see handshake above); resolves after 5s anyway.
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
                                var sah = await handle.createSyncAccessHandle();
                                sahCache[p] = { sah: sah, lastUse: Date.now() };
                                announceSahActive();
                                try { console.log(TAG + ': eager-hydrated ' + p + ' (' + sah.getSize() + 'b)'); } catch (_e) {}
                            } catch (e) {
                                // SAH already held by a co-resident worker is expected; log only the rest.
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

    // exposed for OpfsMirrorBridge at the exit boundary
    self.__gnFsListCached = function () { return Object.keys(sahCache); };
    self.__gnFsReadCached = function (relPath) {
        var cached = sahCache[relPath];
        if (!cached) return null;
        var size = cached.sah.getSize();
        var buf = new Uint8Array(size);
        cached.sah.read(buf, { at: 0 });
        return buf;
    };

    // worker-side flush: main posts {type:'gnFlushNow'}; we read every file through our own SAH (we
    // ARE the lock holder) and post {type:'gnFlushFile', path, b64}. main can't walk OPFS itself --
    // getFile() hangs while a worker holds SAHs (Chromium SAH-exclusive lock).
    // base64 via FileReader.readAsDataURL: native, off the JS thread and parallel across files, far
    // cheaper than building a big binary string for btoa.
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
    // announce ONCE when this worker opens an SAH; main only sends gnFlushNow to announced workers,
    // so SAH-less c3 workers don't log "unknown message" and dead workers aren't awaited.
    var sahActiveAnnounced = false;
    function announceSahActive() {
        if (sahActiveAnnounced) return;
        sahActiveAnnounced = true;
        try { self.postMessage({ type: 'gnFsActive' }); } catch (_e) {}
    }

    self.addEventListener('message', async function (e) {
        if (!e || !e.data || e.data.type !== 'gnFlushNow') return;
        // let in-flight brand-new writes land first; cap at 5s so a stuck open never blocks teardown.
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

        // manifest FIRST: liveness ack + expected gnFlushFile count. postMessage is FIFO, so it lands
        // before any file.
        try { self.postMessage({ type: 'gnFlushManifest', count: workItems.length }); } catch (_e) {}
        try { console.log(TAG + ': flush starting, work=' + workItems.length); } catch (_e) {}

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
        // no done message: main treats received == manifest count as completion. log before yielding;
        // teardown may terminate the worker right after.
        try { console.log(TAG + ': flush complete posted=' + posted + '/' + workItems.length + ' elapsed=' + (Date.now() - t0) + 'ms'); } catch (_e) {}
    });

    // register the proxied fs so unknown methods log loudly.
    if (typeof self.require === 'function' && typeof self.require.register === 'function') {
        self.require.register('fs', fsProxied);
    }
    vlog('worker-fs registered (proxied surface, ' + Object.keys(fs).length + ' methods)');
})();
