// gamenative html5 fs shim.
// routes node's fs.*Sync surface to the host-side Html5FsBridge via __gnFsBridge.
// v1 ships 9 sync methods; ALL OTHER fs surface (including fs.promises + callback-style
// writeFile/readFile) returns a logging stub that THROWS NOT_IMPLEMENTED_V1 so
// device-smoke catalogues every real-world fs method each title actually calls.

// binary data crosses the bridge as base64 strings -- shim detects Buffer/ArrayBuffer/
// Uint8Array input and encodes before the bridge call. readFileSync WITHOUT encoding returns
// a Buffer-like (minimal polyfill, see bottom); WITH encoding='utf8' returns a string.
(function () {
    'use strict';

    var BRIDGE_NAME = '__gnFsBridge';
    var TAG = 'gamenative fs';

    function bridge() {
        return window[BRIDGE_NAME];
    }

    // diagnostic sink -- same pattern as steamworks.js logCall. unknown fs.* goes here
    // before throwing, so logcat shows exactly what each title needed beyond v1.
    function diagLog(obj) {
        try { console.warn(TAG + ': ' + JSON.stringify(obj)); } catch (e) { /* swallow */ }
    }

    // ---------------- minimal base64 polyfill ----------------
    // RMMV's StorageManager JSON.stringify(save) then saves a string -- most fs writes are utf8.
    // base64 round-trip is preserved for readFileSync(path) without encoding so callers that
    // iterate bytes still work.

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
        // we sanitize first so a malformed bridge return (or pre-base64 garbage from a non-
        // existent file's edge case) surfaces as empty bytes instead of an InvalidCharacterError.
        // upstream callers that check `b64 === null` for ENOENT still take that path; this
        // only catches the case where bridge returns a non-null but unparseable string.
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

    // ---------------- Buffer -- discipline extended ----------------
    // node global `Buffer`. installed here because fs.*Sync callers expect it on binary paths.
    // v1 surface: Buffer.from(data, encoding) + Buffer.isBuffer(v) + buf.toString(encoding) +
    // buf.length + indexed access buf[i]. everything else logs via diagnostic bridge + throws
    // NOT_IMPLEMENTED_V1:Buffer.<name> catalogues each title's real surface.

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
            // node Buffer.slice semantics: shallow slice over the same backing memory. returning a
            // fresh wrapped sub-Buffer matches caller expectations (length, indexed access,
            // toString, further slicing). negative indices and missing end mirror Buffer.
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
                // a Buffer must NOT look thenable. the Promise resolution algorithm reads `.then`
                // on any resolved value; the callable stub below would make it treat the Buffer as
                // a thenable, call then(), throw, and REJECT. terra reads saves via
                // `await fs.promises.readFile(...)` -> without this, every async save-load rejects
                // with "buf.then" even though the bytes are on disk. real node Buffers have no .then.
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

    // Buffer.concat([buf, buf, ...], totalLengthMaybe). totalLength optional; node computes
    // when omitted. accepts our wrapBytes proxies, raw Uint8Array, ArrayBuffer.
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

    // Buffer.alloc(size, fill, encoding) -- node guarantees zero-filled. fill+encoding rare;
    // Buffer.allocUnsafe(size) / .allocUnsafeSlow(size) -- uninitialized in node, but zero-init
    // is a safe superset. all three trigger via electron-store's JSON file reader (Desktop
    // Heroes 3734200).
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

    // install the Buffer global if the host WebView didn't already provide one. typeof-guard
    // avoids clobbering a hypothetical future polyfill injected earlier by the host page.
    if (typeof window['Buffer'] === 'undefined') {
        window.Buffer = BufferShim;
    }
    // extend isByteArrayLike to recognize our wrapper so writeFileSync routes base64 path
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
    // Curious Expedition and likely other older Electron titles treat '/' as the game root
    // and call fs.readFileSync('/img/foo.json') etc. Html5FsBridge is save-sandbox only; it
    // rejects absolutes. route those reads through sync XHR against the same origin that
    // AsarAssetInterceptor already serves for <img>/fetch/XHR requests. interceptor answers
    // whether the game is asar-packed or loose-extracted -- no per-title knowledge here.

    // sync XHR is deprecated off-main-thread but fs.readFileSync callers are sync anyway,
    // so the blocking semantics match. same-origin so no CORS concern.

    function assetTryReadSync(pth, wantBinary) {
        try {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', pth, false);
            if (wantBinary) {
                // force raw bytes through responseText -- responseType is forbidden on sync XHR.
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
            // 405 = method-not-allowed: some interceptors only handle GET. 404 means the
            // resource genuinely doesn't exist -- no need to retry with GET (would just log
            // a second 404 to the console). 404 is now reliably emitted by our interceptors
            // (no more DNS fallthrough), so trust it.
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

    // c2 audio paths sometimes carry an embedded `file://` substring -- see
    // url-sanitize.rewriteFileUrl for context. strip here too so bridge.* sees the real
    // path tail instead of garbage that withinSandbox can't normalize.
    function stripEmbeddedFileScheme(pth) {
        if (typeof pth !== 'string') return pth;
        var idx = pth.indexOf('file://');
        if (idx === -1) return pth;
        var rest = pth.substring(idx + 7);
        if (rest.charAt(0) === '/') rest = rest.substring(1);
        else if (rest.charAt(0) === '.' && rest.charAt(1) === '/') rest = rest.substring(2);
        return '/' + rest;
    }

    // Hypnospace's c2 game data uses Windows-style backslash separators (`hs\error.hsp`,
    // `data\os\themes\default`). Linux File handling treats `\` as a literal filename char,
    // so the lookup fails. normalize universally -- node `fs` accepts both on Windows and
    // games written for Windows freely mix them.
    function normalizeSeparators(pth) {
        if (typeof pth !== 'string') return pth;
        return pth.indexOf('\\') === -1 ? pth : pth.replace(/\\/g, '/');
    }

    // narrow strip -- only NW.js dotfile-style user-data absolutes (`/.local/<vendor>/...`,
    // `/.config/...`) get the leading '/' chopped before going to the bridge. these come
    // from `path.join(os.homedir(), '.local/...')` patterns in Construct 2 NW.js titles.
    // bare absolute paths reach the bridge as-is and are rejected (falling through to asset
    // XHR for read/exists). previous broad strip would silently re-root real abs paths
    // (Android namespace, asset URLs) under sandbox -- the failure mode that masked the
    // buildElectronCtx Android-abs-path bug. now possible because our shim posture pins
    // process.execPath / nw.App.startPath / app.getPath / process.env paths to either
    // C:/... (Windows-form, bridge translates) or "." (sandbox-relative) -- composed paths
    // no longer produce a leading `/` from any of our convention pointers.
    function bridgeRel(pth) {
        if (typeof pth !== 'string') return pth;
        pth = normalizeSeparators(stripEmbeddedFileScheme(pth));
        if (pth.length >= 2 && pth.charAt(0) === '/' && pth.charAt(1) === '.') {
            return pth.substring(1);
        }
        return pth;
    }

    // NW.js/Electron convention: absolute paths whose first segment is a hidden dir
    // (`/.local/`, `/.config/`, `/.cache/`, etc.) are user-data, never game assets.
    // when bridge says no for these paths, skip the asset XHR fallback -- the XHR would
    // hit the AssetInterceptor's null fallthrough and DNS-fail with console-noisy
    // ERR_NAME_NOT_RESOLVED. bridge result is authoritative for user-data absolutes.
    //
    // EXCLUDE bare `/./` paths -- those come from `'/' + normalizeSeparators(pth)` for
    // relative inputs like `.\data\...` (Hypnospace's c2 ListFiles passes paths in this
    // form). They are ASSET paths, not user-data, and need the XHR/listdir route.
    function looksLikeUserDataAbsolute(pth) {
        return typeof pth === 'string' && pth.length >= 3 &&
            pth.charAt(0) === '/' && pth.charAt(1) === '.' && pth.charAt(2) !== '/';
    }

    // OMORI's plugins compose paths like `path.dirname(process.mainModule.filename) + '/data/X.KEL'`.
    // process.mainModule.filename is "" (set by IndexHtmlRewriter) → path.dirname("") === "."
    // → result is "./data/X.KEL". route those through the asset interceptor too -- same XHR
    // contract as absolute paths, just stripping the leading "./".
    function isRelativeAssetPath(pth) {
        return typeof pth === 'string' && (pth.indexOf('./') === 0 || pth.indexOf('../') === 0);
    }

    // pack-level posture: when the engine pack uses fs ONLY for saves (RMMV's stock
    // pipeline -- assets go through XHR / PIXI URL loading / <script> tags, never fs),
    // bridge miss = ENOENT directly; no asset XHR fallback. WebViewScreen / IndexHtmlRewriter
    // injects window.__gnFsBridgeOnly = true at parse time for these packs. without this,
    // RMMV save plugins that probe file1..fileN sequentially (e.g. TERMINA's
    // MrTS_SimpleSaveLoadMenu) issue one sync HEAD XHR per empty slot -- hundreds of 404s
    // block the renderer thread. read on every call (not cached) so live-updates take effect.
    function fsBridgeOnly() {
        return typeof self !== 'undefined' && self.__gnFsBridgeOnly === true;
    }

    function normalizeRelativeAssetPath(pth) {
        // collapse leading ./ segments. anything beyond simple `./...` (e.g. `../escape`) is
        // rejected -- game asset paths should never need to climb above the root.
        var p = pth;
        while (p.indexOf('./') === 0) p = p.substring(2);
        if (p.indexOf('../') === 0) return null;
        return '/' + p;
    }

    // ---------------- v1 sync methods ----------------

    function writeFileSync(pth, data, options) {
        var b = bridge();
        if (!b) throw new Error('__gnFsBridge missing');
        // options may be a string (encoding) or object ({encoding}) or undefined. parsed for
        // completeness; encoding choice below is driven by data type (string vs bytes).
        if (options) { /* accepted, not inspected in v1 */ }
        // absolute path → strip leading '/' so NW.js-style "/.local/<vendor>/..." save
        // paths land inside the bridge sandbox. writes are NEVER asset writes.
        var rp = bridgeRel(pth);

        if (typeof data === 'string') {
            // utf8 string path; default encoding when unspecified is utf8 for string data.
            if (!b.writeFile(rp, data, 'utf8')) throw new Error('EIO: writeFileSync failed ' + pth);
            return;
        }
        if (isByteArrayLike(data)) {
            var b64 = toBase64(data);
            if (!b.writeFile(rp, b64, 'base64')) throw new Error('EIO: writeFileSync failed ' + pth);
            return;
        }
        // last resort -- toString + utf8. matches node fallback behavior for number, boolean.
        if (!b.writeFile(rp, String(data), 'utf8')) throw new Error('EIO: writeFileSync failed ' + pth);
    }

    function readFileSync(pth, options) {
        var enc = null;
        if (typeof options === 'string') enc = options;
        else if (options && typeof options.encoding === 'string') enc = options.encoding;
        var wantUtf8 = (enc === 'utf8' || enc === 'utf-8');

        // bridge first (fast in-process JNI) -- save sandbox is separate from install dir,
        // so genuine asset paths miss cleanly here without disk I/O collision. C2 NW.js
        // titles compose absolute save paths (`/.local/<vendor>/...`) that need this route;
        // older Electron titles (Curious Expedition) compose absolute asset paths (`/img/...`)
        // that miss the bridge and fall through to the asset interceptor below.
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

        // asset fallback -- absolute paths route to the asset interceptor; NW.js-style
        // relative `./data/...` follow the same route after normalization. user-data
        // absolutes (`/.local/...`) are bridge-only -- see looksLikeUserDataAbsolute.
        // bare-relative paths (`hs/home.hsp` -- Hypnospace's c2 archive system uses these)
        // are treated as install-dir-relative on bridge miss: prepend `/` and asset-XHR.
        // pack:rmmv (and other bridge-only packs) skip the XHR fallback entirely.
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
        // bridge first -- see readFileSync rationale. covers C2 NW.js absolute save paths
        // (`/.local/<vendor>/...`) without forcing every check through a sync XHR.
        var b = bridge();
        if (b) {
            try {
                if (b.exists(bridgeRel(pth))) return true;
            } catch (_e) { /* fall through */ }
        }
        // asset fallback -- absolute / nw-relative paths hit the asset interceptor.
        // user-data absolutes (`/.local/...`) skip the XHR -- bridge already authoritative.
        // bare-relative paths (`hs/home.hsp`) treated as install-relative -- same install-dir
        // semantics readFileSync uses for c2 archive lookups.
        // pack:rmmv (and other bridge-only packs) skip the XHR fallback entirely -- no sync
        // HEAD per empty slot. RMMV save menus that probe file1..fileN no longer wedge.
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
        // node fs.Stats-like shape -- only methods games check are isFile/isDirectory.
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
        // synthetic asar listing endpoint served by Zip/Asar AssetInterceptor merges zip
        // entries + loose disk children. older Electron titles (Curious Expedition) scan
        // `/conf`, `/langs`; c2 NW.js (Hypnospace) scans `./data/audio/soundscapes/<name>/`
        // to enumerate music files. NW.js-relative `./...` is normalized to absolute so the
        // listdir endpoint sees the same path the asset interceptor serves. user-data
        // absolutes skip the XHR -- bridge owns those.
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
                    // empty listing might be a real empty dir OR an unrecognized endpoint.
                    // fall through to bridge as a second opinion (covers user-data absolutes
                    // that slipped past the prefix check, plus historic save dirs).
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
        if (options) { /* accepted, not inspected in v1 */ }
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

    // pack:electron reality: many Electron apps open log files via fs.createWriteStream at
    // boot. throwing NOT_IMPLEMENTED_V1 kills game init. return a Writable-lookalike noop sink --
    // saves don't use streams so there's no silent data loss risk.
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

    // async fs.readFile(path, options?, cb) -- CE's resource loader uses this variant (node
    // callback style). signature variants: (pth, cb) | (pth, encoding, cb) | (pth, optionsObj, cb).
    // delivery is setTimeout(0) so callers that chain .catch or expect async semantics don't
    // get stack-reentrancy surprises.
    function writeFile(pth, data, options, cb) {
        // node signature: writeFile(file, data[, options], callback). options can be string
        // (encoding), object ({encoding}), or absent. callback always last.
        if (typeof options === 'function') { cb = options; options = null; }
        if (typeof cb !== 'function') cb = function () {};
        var rp = bridgeRel(pth);
        setTimeout(function () {
            try {
                var b = bridge();
                if (!b) return cb(new Error('__gnFsBridge missing'));
                // mirror writeFileSync data-type dispatch.
                if (typeof data === 'string') {
                    if (!b.writeFile(rp, data, 'utf8')) return cb(new Error('EIO: writeFile failed ' + pth));
                    return cb(null);
                }
                if (isByteArrayLike(data)) {
                    var b64 = toBase64(data);
                    if (!b.writeFile(rp, b64, 'base64')) return cb(new Error('EIO: writeFile failed ' + pth));
                    return cb(null);
                }
                // last resort -- toString + utf8. matches node fallback for number/boolean/etc.
                if (!b.writeFile(rp, String(data), 'utf8')) return cb(new Error('EIO: writeFile failed ' + pth));
                cb(null);
            } catch (e) {
                cb(e);
            }
        }, 0);
    }

    // async fs.readdir -- Alabaster Dawn's terra engine asks for `terra/data/locale` with
    // {withFileTypes:true}. wraps readdirSync (which already handles _asar_listdir + bridge
    // fallback). withFileTypes returns Dirent-like objects with name + isFile/isDirectory;
    // heuristic on file-extension presence covers leaf-file dirs. when a title needs accurate
    // type info we extend _asar_listdir to return [{name,t}] objects.
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

    // async wrappers around the sync bridge methods. node's fs.rename / fs.unlink / fs.exists /
    // fs.stat / fs.mkdir are all callback-style (Impact's _saveToFile pipeline uses this form).
    // Impact calls fs.rename(old, new, null) -- fire-and-forget with null callback -- so the
    // (cb || noop) guard is load-bearing.
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

    // node's fs.exists callback signature is `(exists) =>` -- NO error arg, just a bool. it's
    // deprecated in modern node but still in widespread engine code. Impact's _loadFromList
    // probes save paths via this.
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
                // bridge first (mirror readFileSync). C2 NW.js absolute save paths
                // (`/.local/...`) need this route; older Electron asset paths fall through
                // to the asset interceptor below.
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
                // pack:rmmv (and other bridge-only packs) skip XHR fallback.
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
        // async (callback) variants -- Impact engine uses these for save backups / loads
        rename: rename,
        unlink: unlink,
        exists: exists,
        stat: stat,
        lstat: stat,
        mkdir: mkdir,
    };

    // fs.promises -- real implementation wrapping the dispatch table. unknown methods still
    // log + reject so we surface gaps as titles hit them. Alabaster Dawn (3110760) and other
    // pack:nwjs titles use the promises API for save reads (tries Default/Backups/Backups2),
    // engine init readdir, and similar -- rejecting everything broke pack:nwjs boot.
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
            // unknown promises method -- log + reject so the gap surfaces.
            return function () {
                var name = 'promises.' + String(prop);
                diagLog({ fs: name, args: Array.prototype.slice.call(arguments), note: 'NOT_IMPLEMENTED_V1' });
                return Promise.reject(new Error('NOT_IMPLEMENTED_V1: fs.' + name));
            };
        },
    });

    // known methods dispatch directly; unknown access returns a function that logs
    // via diagLog + throws NOT_IMPLEMENTED_V1. fs.promises also goes here since it's a
    // property access, not a method call.
    var fs = new Proxy(dispatch, {
        get: function (t, prop) {
            if (prop === 'promises') return promises;
            if (prop in t) return t[prop];
            // unknown method -- log the eventual call then throw.
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

    // ---------------- RMMV/RMMZ save routing 3 ----------------
    // `Utils.isNwjs()` returns false (IndexHtmlRewriter keeps process as a function to avoid
    // YEP_CoreEngine.initNwjs crashing on require('nw.gui')). that means StorageManager.isLocalMode
    // -- which delegates to Utils.isNwjs -- also returns false, routing saves to IndexedDB/localStorage
    // instead of `fs.writeFileSync`. our fs bridge never fires.

    // surgical fix: once StorageManager exists, redefine its isLocalMode to `() => true`. this is
    // the single save-time branch that picks between saveZipToFile (uses require('fs')) and
    // saveToForage (IndexedDB). plugin-init code paths that read Utils.isNwjs directly are untouched,
    // so YEP_CoreEngine's guard still works.

    // poll rather than rely on a specific timer because StorageManager's definition time varies
    // across RMMV / RMMZ / plugin-modified builds. give up after the budget so C3 and other engines
    // without StorageManager don't leak intervals.
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
                // not an error for non-RM engines (C3, Construct, Electron games without StorageManager).
                if (self.__gnShimVerbose) try { console.log('gamenative storage-route no StorageManager after ' + attempts + ' attempts — engine not RMMV/RMMZ, skipping'); } catch (e) {}
            }
        }, 200);
    }
    try { forceLocalModeOnStorageManager(); } catch (e) {}
})();
