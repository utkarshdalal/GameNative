// gamenative pack:tyrano shim
//
// TyranoScript / TyranoBuilder visual-novel engine. wraps a fixed-size canvas (scWidth ×
// scHeight from data/system/Config.tjs -- typically 1024×768) inside a hardcoded layout div.
//
// fix #1 -- viewport: Tyrano ships `<meta viewport content=" user-scalable=no">` with no
// width. WebView's default layout viewport for that shape (~980 CSS px on the chrome desktop
// fallback) doesn't equal device width, so Tyrano's own fitBaseSize math (scale_f =
// min(innerW/scW, innerH/scH)) under-shrinks slightly. force layout = device-width so
// Tyrano's fit + ScreenCentering math sees the visual viewport directly and produces
// pixel-correct scale + center offset.
//
// fix #2 -- backlog touch scroll: Tyrano's <body> ships `ontouchmove="event.preventDefault()"`
// to prevent page-pan on mobile WebKit. unfortunate side effect: ALL touch-drag-scroll inside
// the page is killed, including the `.log_body` backlog (overflow-y:scroll). swap the inline
// attribute for a conditional listener that only preventDefaults when the touch target is
// OUTSIDE a known scrollable container.
//
// fix #3 -- backlog keyboard scroll: `.log_body` has no tabindex and Tyrano's keydown map
// (kag.key_mouse.js) doesn't bind arrow keys / PageUp/Down to scroll the visible
// scrollable. capture-phase keydown handler scrolls `.log_body` / `.area_save_list` when
// visible and consumes the event before Tyrano's handler sees it.
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

    // adm-zip stub -- Tyrano's kag.applyPatch (called from checkUpdate) does
    //   `new (require('adm-zip'))(tpatchPath)` then iterates getEntries() to extract patch
    // files via writeFileSync into the install dir. host-side TyranoTpatchOverlay already
    // pre-applies .tpatch files via the asset interceptor, so the JS path is redundant --
    // register a noop class so the constructor + getEntries() / extractAllTo() / getEntry()
    // calls don't throw. without this, Glare1more (Tyrano-on-NW.js) and any future Tyrano
    // title with checkUpdate logic crashes at boot with "AdmZip is not a constructor".
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

            // fs-extra stub -- Tyrano's applyPatch chains adm-zip with fs-extra's copySync /
            // ensureDirSync / removeSync to copy patch contents into the install dir.
            // host-side TyranoTpatchOverlay already pre-applies, so any method called here
            // is a noop. Proxy returns a callable noop for any method access so we don't
            // need to enumerate the full fs-extra surface.
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

    // fix -- bgmaudio implicit-global race. tyrano/plugins/kag/kag.tag_audio.js does
    // `bgmaudio = audio_obj;` (no var) on first [playbgm], leaking to window. titles
    // (Fix Me Fix You: index.html slider handler + scenario reads bgmaudio.volume) read it
    // before any [playbgm] fires. NW.js's older chromium happens to schedule [playbgm] first;
    // newer WebView fires the slidechange listener first → ReferenceError. predeclare a stub
    // Audio so .volume setter no-ops harmlessly until tyrano's implicit-global assign
    // replaces it on real bgm start.
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

    // fix -- explicit horizontal centering of .tyrano_base. covers three known Tyrano paths:
    //
    //   (1) ScreenCentering=true + ScreenRatio=fix (FMFY): tyrano.css `margin:auto` is
    //       supposed to center via auto-margin. Chromium WebView refuses to split the
    //       over-constraint when scWidth > viewport (LTR) -- dumps full excess on
    //       margin-right, leaves margin-left:0 → canvas flush-right.
    //   (2) ScreenCentering=false + ScreenRatio=fit (Runeous): Tyrano sets
    //       transform-origin=0 0 + margin:0 inline, uses scaleX/scaleY non-uniform stretch
    //       -- naturally fills the viewport edge-to-edge.
    //   (3) ScreenCentering=false + ScreenRatio=fix (japanese-named title): Tyrano sets
    //       transform-origin=0 0 + margin:0 inline, uniform scale -- renders flush-left
    //       with empty space on right (Tyrano-by-design but undesirable on handheld).
    //
    // strategy: measure Tyrano's actual rendered rect, compute delta to center it, nudge
    // margin-left by that delta. idempotent -- re-applies on resize without jitter
    // (sub-pixel deltas are ignored).
    function applyHorizontalCenter() {
        try {
            var el = document.querySelector('.tyrano_base');
            if (!el) return false;
            var elemW = el.offsetWidth;
            if (!elemW) return false;
            var cs = getComputedStyle(el);
            // wait until Tyrano's fitBaseSize has assigned a transform. before that,
            // measurements reflect Tyrano's pre-fit layout.
            if (!cs.transform || cs.transform === 'none') return false;
            var rect = el.getBoundingClientRect();
            if (!rect.width) return false;
            var viewportW = window.innerWidth || document.documentElement.clientWidth;
            var target = (viewportW - rect.width) / 2;
            var delta = target - rect.x;
            // already centered (case 2 -- Runeous) -- sub-pixel slop is fine.
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
        // re-apply post-resize. Tyrano's fitBaseSize uses setTimeout(100) for its transform;
        // wait 110 so the new viewport metrics + transform have settled before we re-center.
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

            // strip Tyrano's blanket preventDefault. replace with conditional handler that
            // allows native touch-drag-scroll on known scrollable containers (backlog log,
            // save/load lists) while preserving Tyrano's anti-page-pan intent elsewhere.
            document.body.removeAttribute('ontouchmove');
            document.body.addEventListener('touchmove', function (e) {
                var t = e.target;
                if (t && t.closest && t.closest(SCROLLABLE_SELECTOR)) {
                    return; // allow native scroll
                }
                try { e.preventDefault(); } catch (_e) {}
            }, { passive: false });

            // keyboard scroll: arrow keys / PageUp/Down / Home/End scroll the visible
            // scrollable container. capture phase + stopImmediatePropagation so Tyrano's
            // own keydown handler (which maps keys to game actions like skip/auto) doesn't
            // re-handle the same press.
            document.addEventListener('keydown', function (e) {
                var scrollable = document.querySelector(SCROLLABLE_SELECTOR);
                if (!scrollable) return;
                var rect = scrollable.getBoundingClientRect();
                if (rect.width === 0 || rect.height === 0) return;

                var lineStep = 40;
                var pageStep = Math.max(160, rect.height * 0.85 | 0);
                var key = e.key || '';
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

    // error-only video diagnostic. Tyrano's [movie] tag fails silently when the video can't
    // play (broken-media placeholder appears with no console output). this catches load /
    // decode / network errors so future Tyrano titles surface the actual code + message. only
    // attaches when an error event fires -- playback-path is otherwise silent.
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
        // log only genuine failures by default -- lifecycle events (play/pause/loadeddata/
        // canplay/playing/ended/loadedmetadata + snapshot_1s) generate ~10 lines per video
        // playback on chatty VN titles. opt-in via window.__gnShimVerbose for diagnostics.
        ['error', 'stalled', 'abort'].forEach(function (ev) {
            video.addEventListener(ev, function () { report(ev); });
        });
        if (self.__gnShimVerbose) {
            ['loadedmetadata', 'loadeddata', 'canplay', 'play', 'playing', 'pause', 'ended'].forEach(function (ev) {
                video.addEventListener(ev, function () { report(ev); });
            });
            setTimeout(function () { report('snapshot_1s'); }, 1000);
        }
        // trap unhandled promise rejection from video.play() (autoplay blocked, etc.)
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

    // fix #5 -- Sizzle-leniency selector fallback. Tyrano (and many old jQuery-era engines)
    // pass jQuery-only selectors through native DOM selector APIs in spots:
    //   - ':first' pseudo (Maison Chichigami textwriter: 'img.onshitsu:first')
    //   - ':eq(N)' pseudo (Fujiki kag.tag.js uses 'span:eq(0)' through jQuery.children().filter)
    //   - unquoted attribute values not valid CSS identifiers ('.save_list_item[data-page=0]')
    // native APIs (querySelectorAll, matches, closest) throw DOMException. jQuery's INTERNAL
    // .filter() / .is() / .children() routes through Element.matches when the selector is
    // simple enough -- so QSA-only patching misses Sizzle pseudos that jQuery itself dispatches
    // via matches. wrap QSA + matches + closest (+ legacy alias matchesSelector/webkitMatchesSelector)
    // with try-native + catch-Sizzle fallback. valid CSS continues fast-path via native.
    function installQSASizzleFallback() {
        try {
            if (window.__gnTyranoQSAPatched) return;
            window.__gnTyranoQSAPatched = true;
            if (!window.jQuery && !window.$) {
                // jQuery loads via Tyrano's index.html <script src>; patching prototypes before
                // it loads is fine -- the fallback only fires on throw, by which time jQuery
                // will exist (jQuery itself uses native APIs after init).
            }
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
                    // jQuery's .is() runs Sizzle on a single-element collection -- matches result.
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

    // fix #7 -- scenario line-join space insertion. Tyrano's kag.parser.parseScenario
    // splits .ks files by \n and trims each line, producing a separate `text` tag
    // per line. when the textwriter renders consecutive text tags, char-spans
    // accumulate with NO separator -- so authors who break prose across raw .ks
    // newlines (without [r] or trailing spaces) get word-joins like "thata", "bus.I",
    // "prefecture,following". upstream Tyrano behavior, reproduces on Windows native.
    // patch parseScenario to walk array_s and prepend a space to text[N] when
    // text[N-1] ends with Latin-ish and text[N] starts with Latin-ish. CJK boundaries
    // are left alone (no space) so Japanese flow is unaffected.
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

    // belt-and-braces: createElement only catches video elements built via
    // document.createElement('video'). jQuery's $('<video src=...>') and Tyrano's [movie] tag
    // sometimes build via innerHTML (parsed → element insertion, bypasses createElement).
    // MutationObserver on body subtree catches EVERY video element added to the DOM,
    // regardless of construction path. attach diag if not already attached.
    function installVideoMutationObserver() {
        try {
            if (!window.MutationObserver || !document.body) return;
            var observed = new WeakSet();
            function attachOnce(v) {
                if (observed.has(v)) return;
                observed.add(v);
                try { attachVideoErrorDiag(v); } catch (_e) {}
                // verbose-only -- log the src as soon as we attach. routine; gated to
                // avoid spamming on title pages that ship hidden <video> elements.
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
    // (base-background.js + audio-registry.js -- extracted to always-injected base shims.)
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
