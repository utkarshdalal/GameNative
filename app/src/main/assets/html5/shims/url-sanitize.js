// gamenative html5 url sanitizer.
// some titles compose asset URLs with literal `%`, `$`, `(`, `)` characters (filenames like
// `$DW_OMORI_RUN%(8).png`). chrome's URL parser rejects `%X` where X isn't a hex digit, so
// XHR/Image.src calls bail with ERR_NAME_NOT_RESOLVED before our AssetInterceptor sees the
// request. the standard library answer is to call encodeURI/encodeURIComponent at the
// engine boundary; we can't easily patch every engine call site, so wrap the few APIs that
// actually fetch (XMLHttpRequest.open, Image.src, fetch) and sanitize defensively.

// strategy: replace `%` followed by anything that isn't two hex digits with `%25`. preserves
// already-correct percent-encoded sequences. doesn't touch the rest of the URL -- leaves `$`,
// `(`, `)` etc. as-is since chrome accepts those in path segments.
(function () {
    'use strict';

    var STRAY_PCT = /%(?![0-9A-Fa-f]{2})/g;

    // NW.js convention: assets loaded via `file://` URLs (Audio.src, Image.src, <source>).
    // chromium blocks `file://` from our `https://gamenative` origin BEFORE our interceptor
    // sees the request ("Not allowed to load local resource"). rewrite to a same-origin path
    // so the AssetLoader pipeline can serve it. some c2 titles construct
    // `file://./data/audio/music/...` (literal `'file://' + relativePath` concat); typical
    // NW.js form is `file:///absolute/path/...`. both collapse to a leading-slash path against
    // our origin.

    // also handles EMBEDDED `file://` -- c2 code can concatenate a relative dir with audio
    // entries whose `.url` field is itself a `file://...` string, producing
    // `./data/audio/file://data/audio/foo.ogg`. NW.js's URL parser apparently coalesces these
    // (cwd context + lenient handling); WebView treats the result as a relative URL,
    // resolves it against the doc, and DNS-fails. when `file://` appears anywhere, we treat
    // everything before it as garbage and keep only the real path tail.
    function rewriteFileUrl(url) {
        if (typeof url !== 'string') return url;
        // BACKSLASH FIRST: c2's audio plugin receives src composed from game-side
        // `FilePrefix(file:\\) + AppData(.\data\) + relpath` -- the URL arrives at xhr.open
        // with backslashes intact. native XHR canonicalizes `\\` → `//` AFTER our hook runs,
        // so without normalizing first, indexOf('file://') below misses the scheme entirely
        // and we pass an unmodified `file:\\...` URL to origOpen -- which Chrome then
        // canonicalizes and rejects from our https origin.
        if (url.indexOf('\\') !== -1) url = url.replace(/\\/g, '/');
        var idx = url.indexOf('file://');
        if (idx === -1) return url;
        var rest = url.substring(idx + 7);
        // strip leading `/` (file:///abs → /abs) or `./` (file://./rel → /rel) so we land on
        // a single canonical leading slash. anything else is treated as an opaque tail.
        if (rest.charAt(0) === '/') rest = rest.substring(1);
        else if (rest.charAt(0) === '.' && rest.charAt(1) === '/') rest = rest.substring(2);
        return '/' + rest;
    }

    function sanitize(url) {
        url = rewriteFileUrl(url);
        if (typeof url !== 'string') return url;
        // only touch URLs that have a stray % -- otherwise no-op for perf and to avoid
        // double-encoding edge cases on already-encoded paths.
        if (!STRAY_PCT.test(url)) return url;
        STRAY_PCT.lastIndex = 0;
        return url.replace(STRAY_PCT, '%25');
    }

    // ---- XMLHttpRequest.open ----
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
        var args = Array.prototype.slice.call(arguments);
        if (args.length >= 2) args[1] = sanitize(args[1]);
        return origOpen.apply(this, args);
    };

    // ---- fetch ----
    if (typeof window.fetch === 'function') {
        var origFetch = window.fetch.bind(window);
        window.fetch = function (input, init) {
            if (typeof input === 'string') input = sanitize(input);
            return origFetch(input, init);
        };
    }

    // ---- HTMLImageElement.src / HTMLAudioElement.src / HTMLVideoElement.src ----
    // setting `.src` triggers Chrome's URL parser. patch via Object.defineProperty over the
    // prototype so all element instances inherit. preserves the original setter for getter
    // delegation (otherwise reading .src returns undefined).
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
    // HTMLMediaElement is the OWNER of `src` for audio/video -- the property is inherited
    // by HTMLAudioElement / HTMLVideoElement, so getOwnPropertyDescriptor on those subclass
    // prototypes returns undefined and wrapMediaSrc no-ops. install on HTMLMediaElement
    // directly so both audio and video src assignments route through sanitize.
    if (typeof HTMLMediaElement !== 'undefined') wrapMediaSrc(HTMLMediaElement.prototype);
    // <source src> inside <audio>/<video>. c2 builds these for streaming audio playlists
    // and they bypass HTMLMediaElement.src.
    if (typeof HTMLSourceElement !== 'undefined') wrapMediaSrc(HTMLSourceElement.prototype);

    // ---- Element.setAttribute('src'/'href', ...) ----
    // some titles set sources via setAttribute rather than the property. cheap pre-check on
    // attribute name keeps cost negligible for the 99% of setAttribute calls that aren't src.
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

    // ---- innerHTML / outerHTML / insertAdjacentHTML / document.write ----
    // c2 in NW.js mode (window.c2nwjs=true) uses HTML string injection paths for some image
    // elements, which bypass the property setters and setAttribute hooks above. parse the
    // string and rewrite any embedded `file://` URLs before the HTML parser sees them.
    // catches src/href/data/poster attributes plus CSS background-image/url() in style attrs.
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

    // ---- HTMLMediaElement.play() retry queue ----
    // chromium's autoplay policy resets per element on src change. WebView setting
    // mediaPlaybackRequiresUserGesture=false handles the FIRST autoplay, but a SECOND
    // play() on a re-src'd element (game switching media in response to a click) can
    // still reject with NotAllowedError if the play call isn't synchronously inside the
    // event handler. queue rejected elements and flush on the next genuine input event --
    // the user has just clicked, so the queued element gets its play satisfied.
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
