// node `path` shim, pure string math. posix semantics throughout (sep='/'), which RMMV/RMMZ rely on.
(function () {
    'use strict';

    function normalize(p) {
        if (typeof p !== 'string' || p.length === 0) return '.';
        var isAbs = p.charAt(0) === '/';
        // node keeps the trailing slash. RMMZ's StorageManager string-concats
        // `fileDirectoryPath() + saveName`; without it saves land at "savefile3.rmmzsave" in the
        // install root instead of save/, where cloud sync doesn't look.
        var trailing = p.length > 1 && (p.charAt(p.length - 1) === '/' || p.charAt(p.length - 1) === '\\');
        // node-posix leaves '\\' alone, but games ship Windows-style literals.
        var src = p.replace(/\\/g, '/');
        var segs = src.split('/');
        var out = [];
        for (var i = 0; i < segs.length; i++) {
            var s = segs[i];
            if (s === '' || s === '.') continue;
            if (s === '..') {
                if (out.length && out[out.length - 1] !== '..') {
                    out.pop();
                } else if (!isAbs) {
                    out.push('..');
                }
                continue;
            }
            out.push(s);
        }
        var result = out.join('/');
        if (trailing && result.length > 0) result += '/';
        if (isAbs) return '/' + result;
        // node: normalize('./') === './'. plugins build a base with path.join(dirname(...), '/') and
        // string-concat onto it; without the slash '.' + 'media/x' FUSES into '.media/x'.
        if (result.length === 0) return trailing ? './' : '.';
        return result;
    }

    function join(/* ...parts */) {
        if (arguments.length === 0) return '.';
        var joined = '';
        for (var i = 0; i < arguments.length; i++) {
            var arg = arguments[i];
            if (typeof arg !== 'string') continue;
            if (arg.length === 0) continue;
            joined = joined.length === 0 ? arg : joined + '/' + arg;
        }
        return normalize(joined);
    }

    function resolve(/* ...parts */) {
        // no cwd in a WebView: resolution is root-less, the last absolute segment wins.
        var resolved = '';
        var isAbs = false;
        for (var i = arguments.length - 1; i >= 0 && !isAbs; i--) {
            var seg = arguments[i];
            if (typeof seg !== 'string' || seg.length === 0) continue;
            resolved = seg + '/' + resolved;
            isAbs = seg.charAt(0) === '/';
        }
        var normd = normalize(resolved);
        if (isAbs && normd.charAt(0) !== '/') normd = '/' + normd;
        return normd;
    }

    function dirname(p) {
        if (typeof p !== 'string' || p.length === 0) return '.';
        var src = p.replace(/\\/g, '/');
        var idx = src.lastIndexOf('/');
        if (idx === -1) return '.';
        if (idx === 0) return '/';
        return src.substring(0, idx);
    }

    function basename(p, ext) {
        if (typeof p !== 'string') return '';
        var src = p.replace(/\\/g, '/');
        var last = src.lastIndexOf('/');
        var name = last === -1 ? src : src.substring(last + 1);
        if (typeof ext === 'string' && ext.length > 0 && name.length >= ext.length) {
            if (name.lastIndexOf(ext) === name.length - ext.length) {
                name = name.substring(0, name.length - ext.length);
            }
        }
        return name;
    }

    function extname(p) {
        if (typeof p !== 'string') return '';
        var base = basename(p);
        var idx = base.lastIndexOf('.');
        if (idx <= 0) return ''; // leading dot (.rc) is NOT an extension per node
        return base.substring(idx);
    }

    // also accept a leading '\\', matching the Windows-input tolerance elsewhere.
    function isAbsolute(p) {
        if (typeof p !== 'string' || p.length === 0) return false;
        var c = p.charAt(0);
        return c === '/' || c === '\\';
    }

    // identity on posix.
    function toNamespacedPath(p) {
        return typeof p === 'string' ? p : '';
    }

    function relative(from, to) {
        if (typeof from !== 'string') from = '';
        if (typeof to !== 'string') to = '';
        if (from === to) return '';
        var fromAbs = resolve(from);
        var toAbs = resolve(to);
        if (fromAbs === toAbs) return '';
        var fromSegs = fromAbs.substring(1).split('/').filter(function (s) { return s.length > 0; });
        var toSegs = toAbs.substring(1).split('/').filter(function (s) { return s.length > 0; });
        var common = 0;
        var max = Math.min(fromSegs.length, toSegs.length);
        while (common < max && fromSegs[common] === toSegs[common]) common++;
        var out = [];
        for (var i = common; i < fromSegs.length; i++) out.push('..');
        for (var j = common; j < toSegs.length; j++) out.push(toSegs[j]);
        return out.join('/');
    }

    function parse(p) {
        if (typeof p !== 'string') p = '';
        var root = isAbsolute(p) ? '/' : '';
        var base = basename(p);
        var dir = dirname(p);
        // node: parse('file.txt').dir is '', but dirname returns '.', so re-derive.
        var src = p.replace(/\\/g, '/');
        var lastSep = src.lastIndexOf('/');
        if (lastSep === -1) {
            dir = '';
        } else if (lastSep === 0) {
            dir = '/';
        } else {
            dir = src.substring(0, lastSep);
        }
        var ext = extname(base);
        var name = ext.length > 0 ? base.substring(0, base.length - ext.length) : base;
        return { root: root, dir: dir, base: base, ext: ext, name: name };
    }

    // inverse of parse; when dir === root, skip the separator so '/file' stays '/file'.
    function format(obj) {
        if (!obj || typeof obj !== 'object') return '';
        var base = obj.base;
        if (typeof base !== 'string' || base.length === 0) {
            base = (typeof obj.name === 'string' ? obj.name : '') +
                   (typeof obj.ext === 'string' ? obj.ext : '');
        }
        var dir = typeof obj.dir === 'string' ? obj.dir : '';
        var root = typeof obj.root === 'string' ? obj.root : '';
        if (dir.length > 0) {
            if (dir === root) return dir + base;
            return dir + '/' + base;
        }
        return root + base;
    }

    var path = {
        sep: '/',
        delimiter: ':',
        join: join,
        resolve: resolve,
        normalize: normalize,
        dirname: dirname,
        basename: basename,
        extname: extname,
        isAbsolute: isAbsolute,
        relative: relative,
        parse: parse,
        format: format,
        toNamespacedPath: toNamespacedPath,
    };

    // call tracing (args, result, caller) gated on `self.__gnPathTrace`, read per call. set only
    // in the c3 module worker.
    (function instrumentPathTracing() {
        function describeArg(a) {
            if (typeof a === 'string') {
                try { return JSON.stringify(a); } catch (_e) { return '"<unstringifiable>"'; }
            }
            if (a == null) return String(a);
            if (typeof a === 'object') {
                try { return JSON.stringify(a); } catch (_e) { return '<obj>'; }
            }
            return String(a);
        }
        function describeResult(r) {
            if (typeof r === 'string') {
                try { return JSON.stringify(r); } catch (_e) { return '"<unstringifiable>"'; }
            }
            if (typeof r === 'object' && r !== null) {
                try { return JSON.stringify(r); } catch (_e) { return '<obj>'; }
            }
            return String(r);
        }
        function shortStack() {
            try {
                var stk = (new Error()).stack || '';
                return stk.split('\n').slice(3, 5).map(function (l) { return l.trim(); })
                    .join(' ← ').replace(/http:\/\/127\.0\.0\.1:[0-9]+/g, '');
            } catch (_e) { return ''; }
        }
        Object.keys(path).forEach(function (name) {
            var orig = path[name];
            if (typeof orig !== 'function') return;
            path[name] = function () {
                var r = orig.apply(this, arguments);
                if (!self.__gnPathTrace) return r;
                try {
                    var argDesc = Array.prototype.map.call(arguments, describeArg).join(', ');
                    console.log('Html5PathShim: ' + name + '(' + argDesc + ') → ' +
                        describeResult(r) + ' @ ' + shortStack());
                } catch (_e) {}
                return r;
            };
        });
    })();

    // libs reach for path.posix.*; posix only, so both namespaces alias `path`. win32 is a lie (its
    // sep is '\\'), but code relying on real win32 paths would break on our layout anyway.
    path.posix = path;
    path.win32 = path;

    if (window.require && typeof window.require.register === 'function') {
        window.require.register('path', path);
    } else {
        window.path = path;
    }

    if (self.__gnShimVerbose) try { console.log('gamenative path shim loaded'); } catch (e) {}
})();
