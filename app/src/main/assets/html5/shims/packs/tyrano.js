// gamenative pack:tyrano shim -- TyranoScript / TyranoBuilder visual novels (fixed-size stage scaled
// into a hardcoded layout div).
//
// viewport: Tyrano's viewport meta has no width, so WebView's layout viewport (~980 CSS px) isn't
// device width and Tyrano's fitBaseSize (min(innerW/scW, innerH/scH)) under-shrinks. forcing
// device-width gives pixel-correct scale + centering.
(function () {
    'use strict';
    if (window.__gnTyranoShimActive) return;
    window.__gnTyranoShimActive = true;

    var SCROLLABLE_SELECTOR = '.log_body, .area_save_list';

    function log(msg) {
        if (!self.__gnShimVerbose) return;
        try { console.log('gamenative pack:tyrano: ' + msg); } catch (_e) {}
    }
    function warn(msg) {
        try { console.warn('gamenative pack:tyrano: ' + msg); } catch (_e) {}
    }

    // Tyrano's checkUpdate -> kag.applyPatch extracts .tpatch files with adm-zip + fs-extra into the
    // install dir. TyranoTpatchOverlay already applies them host-side, so both are no-ops; without the
    // stubs, titles with checkUpdate crash at boot ("AdmZip is not a constructor").
    try {
        if (window.require && typeof window.require.register === 'function') {
            var AdmZipStub = function () {};
            AdmZipStub.prototype.getEntries = function () { return []; };
            AdmZipStub.prototype.getEntry = function () { return null; };
            AdmZipStub.prototype.extractAllTo = function () {};
            AdmZipStub.prototype.extractEntryTo = function () { return false; };
            AdmZipStub.prototype.readAsText = function () { return ''; };
            AdmZipStub.prototype.readFile = function () { return null; };
            window.require.register('adm-zip', AdmZipStub);

            var fsExtraStub = new Proxy({}, {
                get: function (_, prop) {
                    if (prop === 'then' || typeof prop === 'symbol') return undefined;
                    if (prop === 'existsSync') return function () { return false; };
                    if (prop === 'readFileSync') return function () { return ''; };
                    return function () {};
                },
            });
            window.require.register('fs-extra', fsExtraStub);
        }
    } catch (_e) { /* swallow */ }

    // kag.tag_audio.js creates `bgmaudio` as an implicit global on first [playbgm], but some titles read
    // bgmaudio.volume earlier (e.g. a volume slider handler). NW.js's older chromium happened to run
    // [playbgm] first; newer WebView doesn't -> ReferenceError. the stub is replaced on real bgm start.
    try {
        if (typeof window.bgmaudio === 'undefined') {
            window.bgmaudio = new Audio();
            log('bgmaudio stub predeclared (load-order race guard)');
        }
    } catch (_e) {}

    function applyViewportMeta() {
        try {
            var meta = document.querySelector('meta[name="viewport"]');
            if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                document.head.appendChild(meta);
            }
            meta.setAttribute(
                'content',
                'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no',
            );
            log('viewport set to device-width — Tyrano fitBaseSize will scale + center');
        } catch (e) {
            warn('viewport set failed: ' + e);
        }
    }

    // explicit horizontal centering of .tyrano_base, by measuring the rendered rect and nudging margin-left:
    //   ScreenCentering=true + ScreenRatio=fix: `margin:auto` should center, but when scWidth > viewport
    //     Chromium puts the whole over-constraint on margin-right, leaving it off-center.
    //   ScreenCentering=false + ScreenRatio=fit: non-uniform stretch already fills the viewport.
    //   ScreenCentering=false + ScreenRatio=fix: uniform scale from origin 0 0 renders flush-left
    //     (by design, but wasteful on a handheld).
    function applyHorizontalCenter() {
        try {
            var el = document.querySelector('.tyrano_base');
            if (!el) return false;
            var elemW = el.offsetWidth;
            if (!elemW) return false;
            var cs = getComputedStyle(el);
            // no transform yet = fitBaseSize hasn't run; measurements would be pre-fit.
            if (!cs.transform || cs.transform === 'none') return false;
            var rect = el.getBoundingClientRect();
            if (!rect.width) return false;
            var viewportW = window.innerWidth || document.documentElement.clientWidth;
            var target = (viewportW - rect.width) / 2;
            var delta = target - rect.x;
            // sub-pixel threshold keeps resize re-applies from jittering.
            if (Math.abs(delta) < 1) return true;
            var currentMarginLeft = parseFloat(cs.marginLeft) || 0;
            var newMargin = Math.round(currentMarginLeft + delta);
            el.style.setProperty('margin-left', newMargin + 'px', 'important');
            return true;
        } catch (_e) {
            return false;
        }
    }
    function installCenteringFix() {
        var attempts = 0;
        var iv = setInterval(function () {
            if (applyHorizontalCenter() || ++attempts >= 200) clearInterval(iv);
        }, 50);
        // Tyrano's fitBaseSize applies its transform after setTimeout(100); re-center after it.
        window.addEventListener('resize', function () {
            setTimeout(applyHorizontalCenter, 110);
        });
        window.addEventListener('orientationchange', function () {
            setTimeout(applyHorizontalCenter, 110);
        });
    }

    function installScrollFixes() {
        try {
            if (!document.body) return;

            // Tyrano's <body ontouchmove="event.preventDefault()"> blocks page-pan but also kills touch
            // scrolling in the backlog and save lists; keep its intent everywhere except those.
            document.body.removeAttribute('ontouchmove');
            document.body.addEventListener('touchmove', function (e) {
                var t = e.target;
                if (t && t.closest && t.closest(SCROLLABLE_SELECTOR)) {
                    return; // allow native scroll
                }
                try { e.preventDefault(); } catch (_e) {}
            }, { passive: false });

            // Tyrano binds no scroll keys for the backlog / save list (and .log_body has no tabindex).
            // capture phase + stopImmediatePropagation so Tyrano's keydown handler (skip/auto etc.)
            // doesn't also act on the press.
            document.addEventListener('keydown', function (e) {
                var scrollable = document.querySelector(SCROLLABLE_SELECTOR);
                if (!scrollable) return;
                var rect = scrollable.getBoundingClientRect();
                if (rect.width === 0 || rect.height === 0) return;

                var lineStep = 40;
                var pageStep = Math.max(160, rect.height * 0.85 | 0);
                switch (e.keyCode) {
                    case 38: scrollable.scrollTop -= lineStep; break;      // ArrowUp
                    case 40: scrollable.scrollTop += lineStep; break;      // ArrowDown
                    case 33: scrollable.scrollTop -= pageStep; break;      // PageUp
                    case 34: scrollable.scrollTop += pageStep; break;      // PageDown
                    case 36: scrollable.scrollTop = 0; break;              // Home
                    case 35: scrollable.scrollTop = scrollable.scrollHeight; break; // End
                    default: return;
                }
                try { e.preventDefault(); } catch (_e) {}
                try { e.stopImmediatePropagation(); } catch (_e) {}
            }, true);

            log('scroll fixes installed (body ontouchmove conditional + keyboard handler)');
        } catch (e) {
            warn('scroll-fixes install failed: ' + e);
        }
    }

    // Tyrano's [movie] tag fails silently (broken-media placeholder, no console output); this surfaces
    // the actual error code + message.
    function attachVideoErrorDiag(video) {
        var report = function (label) {
            try {
                var err = video.error;
                var rect = null;
                try { rect = video.getBoundingClientRect(); } catch (_e2) {}
                var cs = null;
                try { cs = window.getComputedStyle(video); } catch (_e3) {}
                var parentInfo = '<no-parent>';
                try {
                    if (video.parentElement) {
                        var pcs = window.getComputedStyle(video.parentElement);
                        parentInfo = video.parentElement.tagName +
                            '#' + (video.parentElement.id || '') +
                            '.' + (video.parentElement.className || '') +
                            ' display=' + pcs.display +
                            ' visibility=' + pcs.visibility;
                    }
                } catch (_e4) {}
                var msg = 'video[' + label + ']' +
                    ' src=' + (video.currentSrc || video.src || '<empty>') +
                    ' readyState=' + video.readyState +
                    ' networkState=' + video.networkState +
                    ' paused=' + video.paused +
                    ' vw=' + video.videoWidth + 'x' + video.videoHeight +
                    ' rect=' + (rect ? Math.round(rect.left) + ',' + Math.round(rect.top) + ' ' + Math.round(rect.width) + 'x' + Math.round(rect.height) : '<null>') +
                    (cs ? ' display=' + cs.display + ' visibility=' + cs.visibility + ' opacity=' + cs.opacity + ' z=' + cs.zIndex + ' pos=' + cs.position : '') +
                    ' parent=' + parentInfo +
                    (err ? ' ERROR code=' + err.code + ' msg=' + (err.message || '') : '');
                console.log('gamenative pack:tyrano: ' + msg);
            } catch (_e) {}
        };
        // lifecycle events are verbose-only: ~10 lines per playback on chatty VN titles.
        ['error', 'stalled', 'abort'].forEach(function (ev) {
            video.addEventListener(ev, function () { report(ev); });
        });
        if (self.__gnShimVerbose) {
            ['loadedmetadata', 'loadeddata', 'canplay', 'play', 'playing', 'pause', 'ended'].forEach(function (ev) {
                video.addEventListener(ev, function () { report(ev); });
            });
            setTimeout(function () { report('snapshot_1s'); }, 1000);
        }
        var origPlay = video.play.bind(video);
        video.play = function () {
            var p = origPlay();
            if (p && typeof p.then === 'function') {
                p.catch(function (err) {
                    try {
                        console.log('gamenative pack:tyrano: video[play_rejected] reason=' +
                            (err && err.name) + ' msg=' + (err && err.message));
                    } catch (_e) {}
                });
            }
            return p;
        };
    }

    // Sizzle-leniency selector fallback. Tyrano (and other jQuery-era code) passes jQuery-only selectors
    // to native selector APIs, which throw DOMException:
    //   - ':first' / ':eq(N)' pseudos
    //   - unquoted attribute values that aren't CSS identifiers ('.save_list_item[data-page=0]')
    // jQuery's own .filter() / .is() / .children() route through Element.matches for simple selectors,
    // so patching QSA alone isn't enough. valid CSS stays on the native fast path.
    function installQSASizzleFallback() {
        try {
            if (window.__gnTyranoQSAPatched) return;
            window.__gnTyranoQSAPatched = true;
            // jQuery may not be loaded yet; fine -- the fallback only runs on throw, after it has loaded.
            var origQSADoc = Document.prototype.querySelectorAll;
            var origQSAEl = Element.prototype.querySelectorAll;
            var origMatches = Element.prototype.matches;
            var origMatchesSelector = Element.prototype.matchesSelector;
            var origWebkitMatchesSelector = Element.prototype.webkitMatchesSelector;
            var origClosest = Element.prototype.closest;

            function withJQ(fn, fallbackThrow) {
                try { return fn(); } catch (_e) { throw fallbackThrow; }
            }

            function safeQSA(scope, selector, native) {
                try {
                    return native.call(scope, selector);
                } catch (e) {
                    if (!(e instanceof DOMException) || !window.jQuery) throw e;
                    return withJQ(function () {
                        var $els = window.jQuery(selector, scope);
                        var nodes = [];
                        for (var i = 0; i < $els.length; i++) nodes.push($els[i]);
                        nodes.item = function (idx) { return nodes[idx] || null; };
                        return nodes;
                    }, e);
                }
            }

            function safeMatches(scope, selector, native) {
                try {
                    return native.call(scope, selector);
                } catch (e) {
                    if (!(e instanceof DOMException) || !window.jQuery) throw e;
                    return withJQ(function () { return window.jQuery(scope).is(selector); }, e);
                }
            }

            function safeClosest(scope, selector, native) {
                try {
                    return native.call(scope, selector);
                } catch (e) {
                    if (!(e instanceof DOMException) || !window.jQuery) throw e;
                    return withJQ(function () {
                        var $c = window.jQuery(scope).closest(selector);
                        return $c.length ? $c[0] : null;
                    }, e);
                }
            }

            Document.prototype.querySelectorAll = function (s) { return safeQSA(this, s, origQSADoc); };
            Element.prototype.querySelectorAll = function (s) { return safeQSA(this, s, origQSAEl); };
            Element.prototype.matches = function (s) { return safeMatches(this, s, origMatches); };
            if (typeof origMatchesSelector === 'function') {
                Element.prototype.matchesSelector = function (s) { return safeMatches(this, s, origMatchesSelector); };
            }
            if (typeof origWebkitMatchesSelector === 'function') {
                Element.prototype.webkitMatchesSelector = function (s) { return safeMatches(this, s, origWebkitMatchesSelector); };
            }
            Element.prototype.closest = function (s) { return safeClosest(this, s, origClosest); };
            log('QSA Sizzle fallback installed (handles :first, :eq(N), unquoted attr values, etc. across QSA + matches + closest)');
        } catch (e) {
            warn('QSA fallback install failed: ' + e);
        }
    }

    // parseScenario splits .ks files by line and trims each into its own `text` tag, which the textwriter
    // joins with NO separator, so prose wrapped across raw newlines renders as "thata", "bus.I". this is
    // upstream Tyrano behavior (also on Windows). only Latin-Latin boundaries get a space; CJK is untouched.
    var LATIN_TAIL = /[A-Za-z0-9.,;:!?'")\]\u2019\u201d]$/;
    var LATIN_HEAD = /^[A-Za-z0-9'"(\[\u2018\u201c]/;
    function patchParser(parser) {
        var orig = parser.parseScenario;
        parser.parseScenario = function (text_str) {
            var result = orig.call(this, text_str);
            try {
                if (!result || !result.array_s) return result;
                var arr = result.array_s;
                for (var i = 1; i < arr.length; i++) {
                    var prev = arr[i - 1];
                    var cur = arr[i];
                    if (!prev || !cur) continue;
                    if (prev.name !== 'text' || cur.name !== 'text') continue;
                    var pv = prev.val || '';
                    var cv = cur.val || '';
                    if (!pv || !cv) continue;
                    if (LATIN_TAIL.test(pv) && LATIN_HEAD.test(cv)) {
                        cur.val = ' ' + cv;
                        if (cur.pm) cur.pm.val = ' ' + (cur.pm.val || '');
                    }
                }
            } catch (_e) {}
            return result;
        };
    }
    function installParserPatch() {
        var attempts = 0;
        var iv = setInterval(function () {
            attempts++;
            try {
                var parser = window.tyrano && window.tyrano.plugin &&
                    window.tyrano.plugin.kag && window.tyrano.plugin.kag.parser;
                if (parser && typeof parser.parseScenario === 'function' && !parser.__gnPatched) {
                    patchParser(parser);
                    parser.__gnPatched = true;
                    clearInterval(iv);
                    log('parseScenario patched — consecutive Latin text tags joined with a space');
                    return;
                }
            } catch (_e) {}
            if (attempts >= 400) {
                clearInterval(iv);
                warn('parseScenario patch timed out — Tyrano parser not found');
            }
        }, 50);
    }

    // intercept createElement so every <video> Tyrano builds gets the diagnostic.
    var origCreate = document.createElement.bind(document);
    document.createElement = function (tag) {
        var el = origCreate(tag);
        if (tag && tag.toLowerCase() === 'video') {
            try { attachVideoErrorDiag(el); } catch (_e) {}
        }
        return el;
    };

    // createElement misses videos built via innerHTML (jQuery's $('<video>'), Tyrano's [movie]
    // tag), so also watch the DOM for any added <video>.
    function installVideoMutationObserver() {
        try {
            if (!window.MutationObserver || !document.body) return;
            var observed = new WeakSet();
            function attachOnce(v) {
                if (observed.has(v)) return;
                observed.add(v);
                try { attachVideoErrorDiag(v); } catch (_e) {}
                // verbose-only: title pages often ship hidden <video> elements.
                if (self.__gnShimVerbose) {
                    try {
                        console.log('gamenative pack:tyrano: video[seen] src=' + (v.currentSrc || v.src || '<empty>'));
                    } catch (_e) {}
                }
            }
            var obs = new MutationObserver(function (mutations) {
                for (var i = 0; i < mutations.length; i++) {
                    var added = mutations[i].addedNodes;
                    for (var j = 0; j < added.length; j++) {
                        var n = added[j];
                        if (!n || n.nodeType !== 1) continue;
                        if (n.tagName === 'VIDEO') attachOnce(n);
                        // descendant videos when a wrapper is inserted as a chunk.
                        if (n.querySelectorAll) {
                            var vs = n.querySelectorAll('video');
                            for (var k = 0; k < vs.length; k++) attachOnce(vs[k]);
                        }
                    }
                }
            });
            obs.observe(document.body, { childList: true, subtree: true });
            // catch any videos already in the static document (Tyrano sometimes ships
            // a hidden <video> in index.html that the [movie] tag re-targets).
            var existing = document.body.querySelectorAll('video');
            for (var m = 0; m < existing.length; m++) attachOnce(existing[m]);
            log('video MutationObserver installed (catches innerHTML / template inserts)');
        } catch (e) {
            warn('video MutationObserver install failed: ' + e);
        }
    }

    // viewport applied inline so Tyrano's load handler sees the new metrics on first
    // fitBaseSize. scroll fixes deferred to DOMContentLoaded since they need document.body.
    applyViewportMeta();
    // QSA fallback installed early -- needs to wrap before any Tyrano/jQuery script runs so
    // first calls go through the wrapper. document.* prototypes are page-scoped; safe pre-DOM.
    installQSASizzleFallback();
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            installScrollFixes();
            installVideoMutationObserver();
            installParserPatch();
            installCenteringFix();
        }, { once: true });
    } else {
        installScrollFixes();
        installVideoMutationObserver();
        installParserPatch();
        installCenteringFix();
    }
})();
