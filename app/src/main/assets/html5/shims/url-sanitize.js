// some titles compose asset URLs with a literal `%` (filenames like `$DW_OMORI_RUN%(8).png`).
// chromium rejects `%X` where X isn't hex, so the request fails before the asset interceptor sees
// it. rather than patch every engine call site, wrap the APIs that fetch and escape stray `%` to
// `%25`; valid percent-escapes and other characters are left alone.
(function () {
    'use strict';

    var STRAY_PCT = /%(?![0-9A-Fa-f]{2})/g;

    // NW.js games load assets via `file://` URLs, which chromium blocks from our https origin
    // BEFORE the interceptor sees them. rewrite to a same-origin path; `file:///abs/...` and
    // `file://./rel/...` both collapse to a leading-slash path.
    // `file://` can also appear EMBEDDED (Construct 2 concatenates a dir with a `file://` url:
    // `./data/audio/file://data/audio/foo.ogg`). NW.js tolerates that; WebView resolves it
    // relative and fails. everything before `file://` is treated as garbage.
    function rewriteFileUrl(url) {
        if (typeof url !== 'string') return url;
        // BACKSLASH FIRST: URLs can arrive as `file:\\.\data\...`, which native XHR only
        // canonicalizes to `file://` AFTER this hook runs, so the scheme check would miss it.
        if (url.indexOf('\\') !== -1) url = url.replace(/\\/g, '/');
        var idx = url.indexOf('file://');
        if (idx === -1) return url;
        var rest = url.substring(idx + 7);
        if (rest.charAt(0) === '/') rest = rest.substring(1);
        else if (rest.charAt(0) === '.' && rest.charAt(1) === '/') rest = rest.substring(2);
        return '/' + rest;
    }

    function sanitize(url) {
        url = rewriteFileUrl(url);
        if (typeof url !== 'string') return url;
        if (!STRAY_PCT.test(url)) return url;
        STRAY_PCT.lastIndex = 0;
        return url.replace(STRAY_PCT, '%25');
    }

    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
        var args = Array.prototype.slice.call(arguments);
        if (args.length >= 2) args[1] = sanitize(args[1]);
        return origOpen.apply(this, args);
    };

    if (typeof window.fetch === 'function') {
        var origFetch = window.fetch.bind(window);
        window.fetch = function (input, init) {
            if (typeof input === 'string') input = sanitize(input);
            return origFetch(input, init);
        };
    }

    // keeps the original getter, or reading .src returns undefined.
    function wrapMediaSrc(proto) {
        if (!proto) return;
        var d = Object.getOwnPropertyDescriptor(proto, 'src');
        if (!d || !d.set) return;
        Object.defineProperty(proto, 'src', {
            configurable: true,
            enumerable: d.enumerable,
            get: d.get,
            set: function (v) { d.set.call(this, sanitize(v)); },
        });
    }
    if (typeof HTMLImageElement !== 'undefined') wrapMediaSrc(HTMLImageElement.prototype);
    // audio/video inherit `src` from HTMLMediaElement; their own prototypes have no descriptor.
    if (typeof HTMLMediaElement !== 'undefined') wrapMediaSrc(HTMLMediaElement.prototype);
    // <source src> bypasses HTMLMediaElement.src.
    if (typeof HTMLSourceElement !== 'undefined') wrapMediaSrc(HTMLSourceElement.prototype);

    if (typeof Element !== 'undefined' && Element.prototype && Element.prototype.setAttribute) {
        var origSetAttribute = Element.prototype.setAttribute;
        Element.prototype.setAttribute = function (name, value) {
            if (typeof name === 'string' && typeof value === 'string') {
                var n = name.toLowerCase();
                if (n === 'src' || n === 'href' || n === 'data' || n === 'poster') {
                    value = sanitize(value);
                }
            }
            return origSetAttribute.call(this, name, value);
        };
    }

    // HTML-string injection (Construct 2 in NW.js mode does this for some images) bypasses the
    // setter hooks above, so rewrite embedded `file://` URLs before the parser sees them.
    var FILE_URL_IN_HTML = /(file:\/\/[^"'\s>)]+)/g;
    function rewriteHtmlFileUrls(html) {
        if (typeof html !== 'string') return html;
        if (html.indexOf('file://') === -1) return html;
        return html.replace(FILE_URL_IN_HTML, function (m) { return rewriteFileUrl(m); });
    }
    function wrapHtmlSetter(proto, propName) {
        if (!proto) return;
        var d = Object.getOwnPropertyDescriptor(proto, propName);
        if (!d || !d.set) return;
        Object.defineProperty(proto, propName, {
            configurable: true,
            enumerable: d.enumerable,
            get: d.get,
            set: function (v) { d.set.call(this, rewriteHtmlFileUrls(v)); },
        });
    }
    if (typeof Element !== 'undefined') {
        wrapHtmlSetter(Element.prototype, 'innerHTML');
        wrapHtmlSetter(Element.prototype, 'outerHTML');
        if (Element.prototype.insertAdjacentHTML) {
            var origInsertAdj = Element.prototype.insertAdjacentHTML;
            Element.prototype.insertAdjacentHTML = function (where, html) {
                return origInsertAdj.call(this, where, rewriteHtmlFileUrls(html));
            };
        }
    }
    if (typeof document !== 'undefined') {
        if (typeof document.write === 'function') {
            var origDocWrite = document.write.bind(document);
            document.write = function (html) { return origDocWrite(rewriteHtmlFileUrls(html)); };
        }
        if (typeof document.writeln === 'function') {
            var origDocWriteln = document.writeln.bind(document);
            document.writeln = function (html) { return origDocWriteln(rewriteHtmlFileUrls(html)); };
        }
    }

    // chromium's autoplay policy resets per element on src change: mediaPlaybackRequiresUserGesture=false
    // covers the first autoplay, but play() on a re-src'd element outside the input handler can still
    // reject with NotAllowedError. queue rejected elements and retry on the next input event.
    if (typeof HTMLMediaElement !== 'undefined' && HTMLMediaElement.prototype.play) {
        var origPlay = HTMLMediaElement.prototype.play;
        var pendingMedia = [];
        HTMLMediaElement.prototype.play = function () {
            var elem = this;
            var p;
            try { p = origPlay.apply(elem, arguments); } catch (e) {
                pendingMedia.push(elem);
                return Promise.reject(e);
            }
            if (p && typeof p.then === 'function') {
                p.catch(function (err) {
                    if (err && err.name === 'NotAllowedError' && pendingMedia.indexOf(elem) === -1) {
                        pendingMedia.push(elem);
                    }
                });
            }
            return p;
        };
        var flush = function () {
            if (pendingMedia.length === 0) return;
            var batch = pendingMedia.slice();
            pendingMedia.length = 0;
            for (var i = 0; i < batch.length; i++) {
                try {
                    var pp = origPlay.call(batch[i]);
                    if (pp && pp.catch) pp.catch(function () {});
                } catch (_e) {}
            }
        };
        // capture-phase listeners so we flush BEFORE the game's own handlers consume the
        // gesture. all input modalities -- touch, mouse, key, pointer -- feed the same queue.
        var opts = { capture: true, passive: true };
        ['touchstart', 'mousedown', 'click', 'keydown', 'pointerdown'].forEach(function (t) {
            try { document.addEventListener(t, flush, opts); } catch (_e) {}
        });
    }

    if (self.__gnShimVerbose) try { console.log('gamenative url-sanitize installed'); } catch (e) {}
})();
