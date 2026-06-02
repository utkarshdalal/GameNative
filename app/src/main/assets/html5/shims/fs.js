// node `fs` shim: routes the sync surface (plus callback variants and fs.promises) to the host-side
// Html5FsBridge via __gnFsBridge. any other fs method returns a stub that logs, then throws (or
// rejects) NOT_IMPLEMENTED_V1, so logcat shows every fs method a title needs.
// binary data crosses the bridge as base64. readFileSync without encoding returns a minimal Buffer
// polyfill; with 'utf8' it returns a string.
(function () {
    'use strict';

    var BRIDGE_NAME = '__gnFsBridge';
    var TAG = 'gamenative fs';

    function bridge() {
        return window[BRIDGE_NAME];
    }

    // unknown fs.* logs here before throwing, so logcat shows what each title needs.
    function diagLog(obj) {
        try { console.warn(TAG + ': ' + JSON.stringify(obj)); } catch (e) { /* swallow */ }
    }

    // ---------------- base64 ----------------

    function isByteArrayLike(v) {
        return (typeof ArrayBuffer !== 'undefined' && v instanceof ArrayBuffer) ||
               (typeof Uint8Array !== 'undefined' && v instanceof Uint8Array) ||
               (v && typeof v === 'object' && typeof v.length === 'number' &&
                typeof v.byteLength === 'number');
    }

    function toBase64(input) {
        var bytes;
        if (input instanceof ArrayBuffer) {
            bytes = new Uint8Array(input);
        } else if (input instanceof Uint8Array) {
            bytes = input;
        } else {
            bytes = new Uint8Array(input);
        }
        var bin = '';
        for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        return btoa(bin);
    }

    function fromBase64(b64) {
        // node's Buffer.from(str, 'base64') silently strips invalid characters; atob throws.
        // sanitize so a malformed non-null bridge return yields empty bytes, not an
        // InvalidCharacterError. null (ENOENT) is handled by callers before this.
        if (typeof b64 !== 'string' || b64.length === 0) return new Uint8Array(0);
        try {
            var clean = b64.replace(/[^A-Za-z0-9+/=]/g, '');
            var bin = atob(clean);
            var out = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
            return out;
        } catch (e) {
            try { console.warn('gamenative fs: fromBase64 invalid input (len=' + b64.length + ') — returning empty bytes'); } catch (_e) {}
            return new Uint8Array(0);
        }
    }

    // ---------------- Buffer ----------------
    // node global `Buffer`; fs callers expect it on binary paths. minimal surface (from, isBuffer,
    // concat, alloc*, toString, length, indexed access, slice); anything else logs + throws.

    function utf8StringToBytes(s) {
        var enc = unescape(encodeURIComponent(s));
        var out = new Uint8Array(enc.length);
        for (var i = 0; i < enc.length; i++) out[i] = enc.charCodeAt(i);
        return out;
    }

    function bytesToUtf8String(bytes) {
        var bin = '';
        for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        try { return decodeURIComponent(escape(bin)); } catch (e) { return bin; }
    }

    function wrapBytes(bytes) {
        var target = {
            __isGnBuffer: true,
            length: bytes.length,
            byteLength: bytes.length,
            _bytes: bytes,
            toString: function (encoding) {
                if (!encoding || encoding === 'utf8' || encoding === 'utf-8') return bytesToUtf8String(bytes);
                if (encoding === 'base64') return toBase64(bytes);
                if (encoding === 'hex') {
                    var h = '';
                    for (var i = 0; i < bytes.length; i++) {
                        var b = bytes[i].toString(16);
                        h += (b.length === 1 ? '0' : '') + b;
                    }
                    return h;
                }
                diagLog({ bufferInstance: 'toString', encoding: String(encoding), note: 'NOT_IMPLEMENTED_V1' });
                throw new Error('NOT_IMPLEMENTED_V1: buf.toString("' + encoding + '")');
            },
            // node semantics: a view over the same memory; negative / missing indices mirror Buffer.
            slice: function (start, end) {
                var len = bytes.length;
                var s = (typeof start === 'number') ? start : 0;
                var e = (typeof end === 'number') ? end : len;
                if (s < 0) s = Math.max(0, len + s);
                if (e < 0) e = Math.max(0, len + e);
                s = Math.min(s, len); e = Math.min(e, len);
                if (e < s) e = s;
                return wrapBytes(bytes.subarray(s, e));
            },
        };
        return new Proxy(target, {
            get: function (t, prop) {
                if (prop in t) return t[prop];
                if (typeof prop === 'string' && /^\d+$/.test(prop)) return bytes[parseInt(prop, 10)];
                if (typeof prop === 'symbol') return undefined;
                // a Buffer must NOT look thenable. promise resolution reads `.then` on any resolved
                // value; the throwing stub below would make `await fs.promises.readFile(...)` reject
                // even though the read succeeded. real node Buffers have no .then.
                if (prop === 'then') return undefined;
                diagLog({ bufferInstance: String(prop), note: 'NOT_IMPLEMENTED_V1' });
                return function () {
                    throw new Error('NOT_IMPLEMENTED_V1: buf.' + String(prop));
                };
            },
        });
    }

    function bufferFrom(data, encoding) {
        if (typeof data === 'string') {
            if (!encoding || encoding === 'utf8' || encoding === 'utf-8') return wrapBytes(utf8StringToBytes(data));
            if (encoding === 'base64') return wrapBytes(fromBase64(data));
            if (encoding === 'hex') {
                var n = Math.floor(data.length / 2);
                var out = new Uint8Array(n);
                for (var i = 0; i < n; i++) out[i] = parseInt(data.substr(i * 2, 2), 16);
                return wrapBytes(out);
            }
            diagLog({ buffer: 'from', encoding: String(encoding), note: 'NOT_IMPLEMENTED_V1' });
            throw new Error('NOT_IMPLEMENTED_V1: Buffer.from(str,"' + encoding + '")');
        }
        if (data instanceof ArrayBuffer) return wrapBytes(new Uint8Array(data));
        if (data instanceof Uint8Array) return wrapBytes(data);
        if (data && typeof data === 'object' && data.__isGnBuffer === true) return wrapBytes(data._bytes);
        if (isByteArrayLike(data)) return wrapBytes(new Uint8Array(data));
        diagLog({ buffer: 'from', type: typeof data, note: 'NOT_IMPLEMENTED_V1' });
        throw new Error('NOT_IMPLEMENTED_V1: Buffer.from(' + typeof data + ')');
    }

    function bufferConcat(list, totalLength) {
        var arrs = [];
        var sum = 0;
        for (var i = 0; i < list.length; i++) {
            var item = list[i];
            var bytes;
            if (item == null) continue;
            if (item.__isGnBuffer === true) bytes = item._bytes;
            else if (item instanceof Uint8Array) bytes = item;
            else if (item instanceof ArrayBuffer) bytes = new Uint8Array(item);
            else if (item.length !== undefined) {
                bytes = new Uint8Array(item.length);
                for (var j = 0; j < item.length; j++) bytes[j] = item[j] & 0xff;
            } else continue;
            arrs.push(bytes);
            sum += bytes.length;
        }
        var capped = (typeof totalLength === 'number') ? Math.min(sum, totalLength) : sum;
        var out = new Uint8Array(capped);
        var off = 0;
        for (var k = 0; k < arrs.length && off < capped; k++) {
            var a = arrs[k];
            var copy = Math.min(a.length, capped - off);
            out.set(a.subarray(0, copy), off);
            off += copy;
        }
        return wrapBytes(out);
    }

    // allocUnsafe* is uninitialized in node; zero-filling is a safe superset. electron-store's
    // file reader uses all three.
    function bufferAlloc(size, fill, encoding) {
        var n = size | 0;
        if (n < 0) throw new RangeError('Buffer size must be non-negative');
        var bytes = new Uint8Array(n);
        if (fill !== undefined && fill !== 0) {
            if (typeof fill === 'number') {
                bytes.fill(fill & 0xff);
            } else if (typeof fill === 'string') {
                var src = bufferFrom(fill, encoding || 'utf8')._bytes;
                if (src.length > 0) {
                    for (var i = 0; i < n; i++) bytes[i] = src[i % src.length];
                }
            }
        }
        return wrapBytes(bytes);
    }
    function bufferAllocUnsafe(size) {
        var n = size | 0;
        if (n < 0) throw new RangeError('Buffer size must be non-negative');
        return wrapBytes(new Uint8Array(n));
    }

    var BufferShim = new Proxy(function () {
        throw new Error('NOT_IMPLEMENTED_V1: new Buffer(); use Buffer.from(...) instead');
    }, {
        get: function (t, prop) {
            if (prop === 'from') return bufferFrom;
            if (prop === 'isBuffer') return function (v) { return !!(v && v.__isGnBuffer === true); };
            if (prop === 'concat') return bufferConcat;
            if (prop === 'alloc') return bufferAlloc;
            if (prop === 'allocUnsafe') return bufferAllocUnsafe;
            if (prop === 'allocUnsafeSlow') return bufferAllocUnsafe;
            if (prop === 'prototype') return {};
            if (typeof prop === 'symbol') return undefined;
            diagLog({ buffer: String(prop), note: 'NOT_IMPLEMENTED_V1' });
            return function () {
                throw new Error('NOT_IMPLEMENTED_V1: Buffer.' + String(prop));
            };
        },
    });

    // don't clobber a Buffer something earlier already installed.
    if (typeof window['Buffer'] === 'undefined') {
        window.Buffer = BufferShim;
    }
    // the helpers above must also recognize our Buffer wrapper so writes take the base64 path.
    var _origIsByteArrayLike = isByteArrayLike;
    isByteArrayLike = function (v) {
        if (v && typeof v === 'object' && v.__isGnBuffer === true) return true;
        return _origIsByteArrayLike(v);
    };
    var _origToBase64 = toBase64;
    toBase64 = function (input) {
        if (input && typeof input === 'object' && input.__isGnBuffer === true) return _origToBase64(input._bytes);
        return _origToBase64(input);
    };

    // ---------------- absolute-path asset reads ----------------
    // some older Electron titles treat '/' as the game root (fs.readFileSync('/img/foo.json')).
    // Html5FsBridge is save-sandbox only and rejects absolutes, so those reads use sync XHR against
    // the same origin the asset interceptor serves (asar-packed or loose, no per-title knowledge).
    // sync XHR matches the blocking semantics fs.*Sync callers expect.

    function assetTryReadSync(pth, wantBinary) {
        try {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', pth, false);
            if (wantBinary) {
                // responseType is forbidden on sync XHR, so force raw bytes through responseText.
                xhr.overrideMimeType('text/plain; charset=x-user-defined');
            }
            xhr.send(null);
            if (xhr.status < 200 || xhr.status >= 300) return null;
            if (!wantBinary) return xhr.responseText;
            var txt = xhr.responseText;
            var bytes = new Uint8Array(txt.length);
            for (var i = 0; i < txt.length; i++) bytes[i] = txt.charCodeAt(i) & 0xff;
            return bytes;
        } catch (_e) {
            return null;
        }
    }

    function assetExistsSync(pth) {
        try {
            var xhr = new XMLHttpRequest();
            xhr.open('HEAD', pth, false);
            xhr.send(null);
            if (xhr.status >= 200 && xhr.status < 300) return true;
            // 405: some interceptors only handle GET, so retry. 404 is authoritative.
            if (xhr.status === 405) {
                var x2 = new XMLHttpRequest();
                x2.open('GET', pth, false);
                x2.send(null);
                return x2.status >= 200 && x2.status < 300;
            }
            return false;
        } catch (_e) {
            return false;
        }
    }

    function isAssetPath(pth) {
        return typeof pth === 'string' && pth.length > 0 && pth.charAt(0) === '/';
    }

    // Construct 2 audio paths sometimes embed a `file://` substring (see url-sanitize.rewriteFileUrl).
    // strip it so the bridge sees the real path tail, not garbage withinSandbox can't normalize.
    function stripEmbeddedFileScheme(pth) {
        if (typeof pth !== 'string') return pth;
        var idx = pth.indexOf('file://');
        if (idx === -1) return pth;
        var rest = pth.substring(idx + 7);
        if (rest.charAt(0) === '/') rest = rest.substring(1);
        else if (rest.charAt(0) === '.' && rest.charAt(1) === '/') rest = rest.substring(2);
        return '/' + rest;
    }

    // games written for Windows mix in backslash separators (`data\os\themes`); node fs on Windows
    // accepts both, Android treats `\` as a filename char. normalize universally.
    function normalizeSeparators(pth) {
        if (typeof pth !== 'string') return pth;
        return pth.indexOf('\\') === -1 ? pth : pth.replace(/\\/g, '/');
    }

    // only NW.js dotfile user-data absolutes (`/.local/<vendor>/...`, `/.config/...`, from
    // path.join(os.homedir(), '.local/...')) lose the leading '/' for the bridge. other absolutes
    // reach the bridge as-is and are rejected (reads / exists then fall back to asset XHR).
    // a broader strip would silently re-root real absolute paths under the sandbox. safe because
    // our shims pin execPath / startPath / app.getPath / env paths to C:/... or ".", never '/'.
    function bridgeRel(pth) {
        if (typeof pth !== 'string') return pth;
        pth = normalizeSeparators(stripEmbeddedFileScheme(pth));
        if (pth.length >= 2 && pth.charAt(0) === '/' && pth.charAt(1) === '.') {
            return pth.substring(1);
        }
        return pth;
    }

    // absolute paths whose first segment is a hidden dir (`/.local/`, `/.config/`) are user-data,
    // never assets: the bridge result is authoritative, no asset XHR fallback.
    // `/./` is EXCLUDED -- it comes from relative inputs like `.\data\...` and IS an asset path.
    function looksLikeUserDataAbsolute(pth) {
        return typeof pth === 'string' && pth.length >= 3 &&
            pth.charAt(0) === '/' && pth.charAt(1) === '.' && pth.charAt(2) !== '/';
    }

    // plugins compose `path.dirname(process.mainModule.filename) + '/data/X'`. filename is ""
    // (IndexHtmlRewriter), so this yields "./data/X": route it through the asset interceptor too.
    function isRelativeAssetPath(pth) {
        return typeof pth === 'string' && (pth.indexOf('./') === 0 || pth.indexOf('../') === 0);
    }

    // packs that use fs ONLY for saves (RMMV: assets load via XHR / URLs, never fs) get
    // window.__gnFsBridgeOnly from IndexHtmlRewriter: a bridge miss is ENOENT, no asset XHR.
    // otherwise save menus probing file1..fileN issue one sync HEAD per empty slot -- hundreds
    // of 404s block the renderer. read per call, not cached.
    function fsBridgeOnly() {
        return typeof self !== 'undefined' && self.__gnFsBridgeOnly === true;
    }

    function normalizeRelativeAssetPath(pth) {
        // `../` is rejected -- asset paths never climb above the game root.
        var p = pth;
        while (p.indexOf('./') === 0) p = p.substring(2);
        if (p.indexOf('../') === 0) return null;
        return '/' + p;
    }

    // ---------------- sync methods ----------------

    function writeFileSync(pth, data, options) {
        var b = bridge();
        if (!b) throw new Error('__gnFsBridge missing');
        // encoding follows the data type; options are ignored. writes are NEVER asset writes.
        var rp = bridgeRel(pth);

        if (typeof data === 'string') {
            if (!b.writeFile(rp, data, 'utf8')) throw new Error('EIO: writeFileSync failed ' + pth);
            return;
        }
        if (isByteArrayLike(data)) {
            var b64 = toBase64(data);
            if (!b.writeFile(rp, b64, 'base64')) throw new Error('EIO: writeFileSync failed ' + pth);
            return;
        }
        // like node: number / boolean etc. are written as String(data).
        if (!b.writeFile(rp, String(data), 'utf8')) throw new Error('EIO: writeFileSync failed ' + pth);
    }

    function readFileSync(pth, options) {
        var enc = null;
        if (typeof options === 'string') enc = options;
        else if (options && typeof options.encoding === 'string') enc = options.encoding;
        var wantUtf8 = (enc === 'utf8' || enc === 'utf-8');

        // bridge first (in-process, fast). the save sandbox is separate from the install dir, so
        // asset paths (`/img/...`) miss cleanly and fall through to the asset interceptor.
        var b = bridge();
        if (b) {
            try {
                if (wantUtf8) {
                    var sb = b.readFile(bridgeRel(pth), 'utf8');
                    // == null catches both null and undefined -- some WebView builds marshal
                    // Kotlin null returns from @JavascriptInterface as JS undefined.
                    if (sb != null) return sb;
                } else {
                    var b64 = b.readFile(bridgeRel(pth), 'base64');
                    if (b64 != null) return wrapBytes(fromBase64(b64));
                }
            } catch (_e) { /* fall through */ }
        }

        // asset fallback. user-data absolutes are bridge-only (see looksLikeUserDataAbsolute).
        // bare-relative paths (`hs/home.hsp`) are install-dir-relative: prepend '/' and asset-XHR.
        if (fsBridgeOnly()) throw new Error('ENOENT: readFileSync ' + pth);
        if (isAssetPath(pth) && !looksLikeUserDataAbsolute(pth)) {
            if (wantUtf8) {
                var s = assetTryReadSync(pth, false);
                if (s !== null) return s;
            } else {
                var bytes = assetTryReadSync(pth, true);
                if (bytes !== null) return wrapBytes(bytes);
            }
            throw new Error('ENOENT: readFileSync ' + pth);
        }
        if (looksLikeUserDataAbsolute(pth)) {
            throw new Error('ENOENT: readFileSync ' + pth);
        }
        var bareAssetPath = isRelativeAssetPath(pth) ? normalizeRelativeAssetPath(pth) :
            (typeof pth === 'string' && pth.length > 0 && pth.charAt(0) !== '/' ? '/' + normalizeSeparators(pth) : null);
        if (bareAssetPath !== null) {
            if (wantUtf8) {
                var s2 = assetTryReadSync(bareAssetPath, false);
                if (s2 !== null) return s2;
            } else {
                var bytes2 = assetTryReadSync(bareAssetPath, true);
                if (bytes2 !== null) return wrapBytes(bytes2);
            }
        }
        throw new Error('ENOENT: readFileSync ' + pth);
    }

    function existsSync(pth) {
        // bridge first, then asset fallback -- same routing as readFileSync.
        var b = bridge();
        if (b) {
            try {
                if (b.exists(bridgeRel(pth))) return true;
            } catch (_e) { /* fall through */ }
        }
        if (fsBridgeOnly()) return false;
        if (isAssetPath(pth) && !looksLikeUserDataAbsolute(pth)) return assetExistsSync(pth);
        var bareAssetPath = isRelativeAssetPath(pth) ? normalizeRelativeAssetPath(pth) :
            (typeof pth === 'string' && pth.length > 0 && pth.charAt(0) !== '/' ? '/' + normalizeSeparators(pth) : null);
        if (bareAssetPath !== null) return assetExistsSync(bareAssetPath);
        return false;
    }

    function unlinkSync(pth) {
        var b = bridge();
        if (!b) throw new Error('__gnFsBridge missing');
        if (!b.unlink(bridgeRel(pth))) throw new Error('ENOENT: unlinkSync ' + pth);
    }

    function statSync(pth) {
        var b = bridge();
        if (!b) throw new Error('__gnFsBridge missing');
        var json = b.stat(bridgeRel(pth));
        var parsed;
        try { parsed = JSON.parse(json); } catch (e) { throw new Error('EIO: statSync parse ' + pth); }
        if (parsed.error) throw new Error(parsed.error + ': statSync ' + pth);
        // minimal fs.Stats: games only check isFile / isDirectory.
        return {
            size: parsed.size,
            mtimeMs: parsed.mtimeMs,
            mtime: new Date(parsed.mtimeMs),
            isFile: function () { return !!parsed.isFile; },
            isDirectory: function () { return !!parsed.isDirectory; },
        };
    }

    function mkdirSync(pth, options) {
        var b = bridge();
        if (!b) throw new Error('__gnFsBridge missing');
        var recursive = false;
        if (options === true) recursive = true;
        else if (options && options.recursive === true) recursive = true;
        if (!b.mkdir(bridgeRel(pth), recursive)) throw new Error('EEXIST or EIO: mkdirSync ' + pth);
    }

    function readdirSync(pth) {
        if (self.__gnShimVerbose) try { console.log('[gn-fs] readdirSync entry pth=' + JSON.stringify(pth)); } catch (_e) {}
        // the interceptor's synthetic /_asar_listdir endpoint merges zip entries with loose disk
        // children. relative paths are normalized to the form the asset interceptor serves.
        // user-data absolutes skip the XHR -- the bridge owns those.
        var listdirPath = isAssetPath(pth) ? pth :
            (isRelativeAssetPath(pth) ? normalizeRelativeAssetPath(pth) :
                (typeof pth === 'string' && pth.length > 0 && pth.charAt(0) !== '/' ?
                    '/' + normalizeSeparators(pth) : null));
        if (listdirPath !== null && !looksLikeUserDataAbsolute(listdirPath)) {
            try {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '/_asar_listdir' + listdirPath, false);
                xhr.send(null);
                if (self.__gnShimVerbose) try { console.log('[gn-fs] readdirSync xhr ' + listdirPath + ' status=' + xhr.status); } catch (_e2) {}
                if (xhr.status >= 200 && xhr.status < 300) {
                    var arr = JSON.parse(xhr.responseText || '[]');
                    if (arr.length > 0) {
                        if (self.__gnShimVerbose) try { console.log('[gn-fs] readdirSync via xhr returned ' + arr.length + ' entries for ' + JSON.stringify(pth)); } catch (_e3) {}
                        return arr;
                    }
                    // empty may be a real empty dir OR an unrecognized endpoint: ask the bridge too.
                }
            } catch (_e) { /* swallow */ }
        }
        var b = bridge();
        if (!b) throw new Error('__gnFsBridge missing');
        var json = b.readdir(bridgeRel(pth));
        var result;
        try { result = JSON.parse(json); } catch (e) { result = []; }
        if (self.__gnShimVerbose) try { console.log('[gn-fs] readdirSync via bridge returned ' + (result && result.length || 0) + ' entries for ' + JSON.stringify(pth)); } catch (_e4) {}
        return result;
    }

    function renameSync(oldP, newP) {
        var b = bridge();
        if (!b) throw new Error('__gnFsBridge missing');
        if (!b.rename(bridgeRel(oldP), bridgeRel(newP))) throw new Error('ENOENT: renameSync ' + oldP);
    }

    function appendFileSync(pth, data, options) {
        var b = bridge();
        if (!b) throw new Error('__gnFsBridge missing');
        var rp = bridgeRel(pth);
        if (typeof data === 'string') {
            if (!b.appendFile(rp, data, 'utf8')) throw new Error('EIO: appendFileSync ' + pth);
            return;
        }
        if (isByteArrayLike(data)) {
            if (!b.appendFile(rp, toBase64(data), 'base64')) throw new Error('EIO: appendFileSync ' + pth);
            return;
        }
        if (!b.appendFile(rp, String(data), 'utf8')) throw new Error('EIO: appendFileSync ' + pth);
    }

    // many Electron apps open log files via createWriteStream at boot; throwing kills init.
    // saves never use streams, so a noop sink loses nothing.
    function createWriteStream(pth, _options) {
        diagLog({ fs: 'createWriteStream', path: String(pth), note: 'noop-sink' });
        var truthy = function () { return true; };
        var sink;
        sink = {
            write: truthy,
            end: function (chunk, enc, cb) {
                if (typeof chunk === 'function') cb = chunk;
                else if (typeof enc === 'function') cb = enc;
                if (cb) try { cb(); } catch (_e) { /* swallow */ }
                return true;
            },
            close: truthy,
            destroy: truthy,
            on: function () { return sink; },
            once: function () { return sink; },
            off: function () { return sink; },
            removeListener: function () { return sink; },
            emit: truthy,
            writable: true,
            destroyed: false,
        };
        return sink;
    }

    // callback variants deliver via setTimeout(0) so callers expecting async semantics don't
    // get re-entrant callbacks.
    function writeFile(pth, data, options, cb) {
        if (typeof options === 'function') { cb = options; options = null; }
        if (typeof cb !== 'function') cb = function () {};
        var rp = bridgeRel(pth);
        setTimeout(function () {
            try {
                var b = bridge();
                if (!b) return cb(new Error('__gnFsBridge missing'));
                if (typeof data === 'string') {
                    if (!b.writeFile(rp, data, 'utf8')) return cb(new Error('EIO: writeFile failed ' + pth));
                    return cb(null);
                }
                if (isByteArrayLike(data)) {
                    var b64 = toBase64(data);
                    if (!b.writeFile(rp, b64, 'base64')) return cb(new Error('EIO: writeFile failed ' + pth));
                    return cb(null);
                }
                if (!b.writeFile(rp, String(data), 'utf8')) return cb(new Error('EIO: writeFile failed ' + pth));
                cb(null);
            } catch (e) {
                cb(e);
            }
        }, 0);
    }

    // withFileTypes has no real type info: a name with an extension is treated as a file.
    // extend _asar_listdir to return types if a title needs accuracy.
    function readdir(pth, opts, cb) {
        if (typeof opts === 'function') { cb = opts; opts = null; }
        if (typeof cb !== 'function') cb = function () {};
        var withFileTypes = !!(opts && opts.withFileTypes);
        setTimeout(function () {
            try {
                var names = readdirSync(pth);
                if (!withFileTypes) return cb(null, names);
                var dirents = names.map(function (n) {
                    var hasExt = n.indexOf('.') > 0;
                    return {
                        name: n,
                        isFile: function () { return hasExt; },
                        isDirectory: function () { return !hasExt; },
                        isSymbolicLink: function () { return false; },
                        isBlockDevice: function () { return false; },
                        isCharacterDevice: function () { return false; },
                        isFIFO: function () { return false; },
                        isSocket: function () { return false; },
                    };
                });
                cb(null, dirents);
            } catch (e) {
                cb(e);
            }
        }, 0);
    }

    // some engines call fs.rename(old, new, null) fire-and-forget, so the noop-callback guard is
    // load-bearing.
    function rename(oldP, newP, cb) {
        if (typeof cb !== 'function') cb = function () {};
        setTimeout(function () {
            try {
                var b = bridge();
                if (!b) return cb(new Error('__gnFsBridge missing'));
                if (!b.rename(bridgeRel(oldP), bridgeRel(newP))) {
                    return cb(new Error('ENOENT: rename ' + oldP));
                }
                cb(null);
            } catch (e) { cb(e); }
        }, 0);
    }

    function unlink(pth, cb) {
        if (typeof cb !== 'function') cb = function () {};
        setTimeout(function () {
            try {
                var b = bridge();
                if (!b) return cb(new Error('__gnFsBridge missing'));
                if (!b.unlink(bridgeRel(pth))) return cb(new Error('ENOENT: unlink ' + pth));
                cb(null);
            } catch (e) { cb(e); }
        }, 0);
    }

    // fs.exists calls back with `(exists)` -- NO error arg. deprecated in node, still common in engines.
    function exists(pth, cb) {
        if (typeof cb !== 'function') cb = function () {};
        setTimeout(function () {
            try { cb(existsSync(pth)); } catch (_e) { cb(false); }
        }, 0);
    }

    function stat(pth, cb) {
        if (typeof cb !== 'function') cb = function () {};
        setTimeout(function () {
            try { cb(null, statSync(pth)); } catch (e) { cb(e); }
        }, 0);
    }

    function mkdir(pth, options, cb) {
        if (typeof options === 'function') { cb = options; options = null; }
        if (typeof cb !== 'function') cb = function () {};
        setTimeout(function () {
            try { mkdirSync(pth, options); cb(null); } catch (e) { cb(e); }
        }, 0);
    }

    function readFile(pth, opts, cb) {
        if (typeof opts === 'function') { cb = opts; opts = null; }
        if (typeof cb !== 'function') cb = function () {};
        var enc = null;
        if (typeof opts === 'string') enc = opts;
        else if (opts && typeof opts.encoding === 'string') enc = opts.encoding;
        var wantUtf8 = (enc === 'utf8' || enc === 'utf-8');
        setTimeout(function () {
            try {
                // same routing as readFileSync.
                var b = bridge();
                if (b) {
                    try {
                        if (wantUtf8) {
                            var sb = b.readFile(bridgeRel(pth), 'utf8');
                            if (sb != null) return cb(null, sb);
                        } else {
                            var b64 = b.readFile(bridgeRel(pth), 'base64');
                            if (b64 != null) return cb(null, wrapBytes(fromBase64(b64)));
                        }
                    } catch (_e) { /* fall through */ }
                }
                if (fsBridgeOnly()) return cb(new Error('ENOENT: readFile ' + pth));
                if (isAssetPath(pth) && !looksLikeUserDataAbsolute(pth)) {
                    if (wantUtf8) {
                        var s = assetTryReadSync(pth, false);
                        if (s === null) return cb(new Error('ENOENT: readFile ' + pth));
                        return cb(null, s);
                    }
                    var bytes = assetTryReadSync(pth, true);
                    if (bytes === null) return cb(new Error('ENOENT: readFile ' + pth));
                    return cb(null, wrapBytes(bytes));
                }
                cb(new Error('ENOENT: readFile ' + pth));
            } catch (e) {
                cb(e);
            }
        }, 0);
    }

    // ---------------- dispatch table + Proxy fallback ----------------

    var dispatch = {
        writeFileSync: writeFileSync,
        readFileSync: readFileSync,
        writeFile: writeFile,
        readFile: readFile,
        readdir: readdir,
        existsSync: existsSync,
        unlinkSync: unlinkSync,
        statSync: statSync,
        lstatSync: statSync, // alias -- games rarely distinguish for plain files
        mkdirSync: mkdirSync,
        readdirSync: readdirSync,
        renameSync: renameSync,
        appendFileSync: appendFileSync,
        createWriteStream: createWriteStream,
        // callback variants
        rename: rename,
        unlink: unlink,
        exists: exists,
        stat: stat,
        lstat: stat,
        mkdir: mkdir,
    };

    // NW.js titles use fs.promises for save reads and engine-init readdir, so it must be real;
    // unknown methods log + reject so gaps surface.
    function promisifyCb(fn) {
        return function () {
            var args = Array.prototype.slice.call(arguments);
            return new Promise(function (resolve, reject) {
                args.push(function (err, data) {
                    if (err) reject(err); else resolve(data);
                });
                try { fn.apply(null, args); } catch (e) { reject(e); }
            });
        };
    }
    function promisifySync(fn) {
        return function () {
            var args = Array.prototype.slice.call(arguments);
            return new Promise(function (resolve, reject) {
                setTimeout(function () {
                    try { resolve(fn.apply(null, args)); } catch (e) { reject(e); }
                }, 0);
            });
        };
    }
    var promises = new Proxy({
        readFile: promisifyCb(readFile),
        writeFile: promisifyCb(writeFile),
        readdir: promisifyCb(readdir),
        stat: promisifySync(statSync),
        lstat: promisifySync(statSync),
        mkdir: promisifySync(mkdirSync),
        unlink: promisifySync(unlinkSync),
        rename: promisifySync(renameSync),
        appendFile: promisifySync(appendFileSync),
        access: function (pth) {
            return new Promise(function (resolve, reject) {
                setTimeout(function () {
                    try {
                        if (existsSync(pth)) resolve();
                        else reject(new Error('ENOENT: access ' + pth));
                    } catch (e) { reject(e); }
                }, 0);
            });
        },
    }, {
        get: function (t, prop) {
            if (prop in t) return t[prop];
            return function () {
                var name = 'promises.' + String(prop);
                diagLog({ fs: name, args: Array.prototype.slice.call(arguments), note: 'NOT_IMPLEMENTED_V1' });
                return Promise.reject(new Error('NOT_IMPLEMENTED_V1: fs.' + name));
            };
        },
    });

    // unknown methods return a stub that logs, then throws NOT_IMPLEMENTED_V1.
    var fs = new Proxy(dispatch, {
        get: function (t, prop) {
            if (prop === 'promises') return promises;
            if (prop in t) return t[prop];
            return function () {
                var args = Array.prototype.slice.call(arguments);
                diagLog({ fs: String(prop), args: args });
                throw new Error('NOT_IMPLEMENTED_V1: fs.' + String(prop));
            };
        },
    });

    // ---------------- register onto require-dispatcher ----------------

    if (window.require && typeof window.require.register === 'function') {
        window.require.register('fs', fs);
    } else {
        // require-dispatcher.js didn't load -- unusual; expose fs globally as a last resort.
        try { console.warn(TAG + ': require-dispatcher missing, exposing window.fs'); } catch (e) {}
        window.fs = fs;
    }

    if (self.__gnShimVerbose) try { console.log('gamenative fs shim loaded'); } catch (e) {}

    // ---------------- RMMV/RMMZ save routing ----------------
    // Utils.isNwjs() is false (IndexHtmlRewriter keeps `process` a function so YEP_CoreEngine.initNwjs
    // doesn't crash on require('nw.gui')), so StorageManager.isLocalMode is false too and saves go to
    // IndexedDB instead of fs. override ONLY isLocalMode -- the one save-time branch choosing fs vs
    // forage -- so plugin-init code reading Utils.isNwjs directly keeps its guard.
    // poll: StorageManager's definition time varies across RMMV / RMMZ / plugin builds. give up
    // after the budget so engines without it don't leak an interval.
    function forceLocalModeOnStorageManager() {
        var attempts = 0;
        var maxAttempts = 40; // ~8s at 200ms -- generous for slow boots
        var timer = setInterval(function () {
            attempts++;
            var sm = window.StorageManager;
            if (sm && typeof sm.isLocalMode === 'function') {
                clearInterval(timer);
                if (sm.__gnIsLocalModeForced) return; // idempotent guard if game redefines StorageManager
                try {
                    sm.isLocalMode = function () { return true; };
                    sm.__gnIsLocalModeForced = true;
                    if (self.__gnShimVerbose) try { console.log('gamenative storage-route isLocalMode forced=true attempts=' + attempts); } catch (e) {}
                } catch (e) {
                    try { console.warn('gamenative storage-route failed to override isLocalMode: ' + e.message); } catch (_) {}
                }
                return;
            }
            if (attempts >= maxAttempts) {
                clearInterval(timer);
                // normal for non-RM engines.
                if (self.__gnShimVerbose) try { console.log('gamenative storage-route no StorageManager after ' + attempts + ' attempts — engine not RMMV/RMMZ, skipping'); } catch (e) {}
            }
        }, 200);
    }
    try { forceLocalModeOnStorageManager(); } catch (e) {}
})();
