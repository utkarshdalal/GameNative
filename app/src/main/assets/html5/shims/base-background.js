// gamenative base background -- sets html/body background to #000 + suppresses the UA <video>
// placeholder.
// canvases scaled via transform: scale(N) leave sub-pixel rounding gaps where the
// scaled canvas and any letterbox boundary round differently. with the page background
// at the browser default (white), those 1-px gaps flash white during fades / on scaled
// canvases. setting the page background to black hides the artifact -- the gap blends
// with the letterbox / fade-to-black.
//
// same family of artifact for movies: while a <video> is visible but its first frame hasn't
// composited, WebView paints its built-in placeholder -- a white field with a black play-circle.
// every engine that plays a movie (RMMV/MZ Video, CGMZ splash, Tyrano [movie], NW.js/Electron
// <video>) flashes it for a frame or two before auto-play, regardless of pack. the play-circle is
// a media-controls shadow-DOM pseudo-element. on chrome-124 WebView `display:none` on that
// pseudo-element does NOT suppress it (verified on Elderfield over CDP), so the real fix is to give
// every <video> a black poster: chromium paints the poster -- not the placeholder -- until the
// first frame composites over it. the css below stays as belt-and-braces (black bg + the
// pseudo-element rule for WebView builds where it does work).
//
// the poster must be set BEFORE the engine builds + plays the element, so we wrap createElement
// here (document-start) rather than chase elements after the fact.
//
// always-injected for html5 containers. games whose <body style="background:..."> sets
// something else still wins; this only affects the unset case. we never clobber a poster the
// title shipped itself.
(function () {
    'use strict';
    if (window.__gnBaseBackgroundApplied) return;
    window.__gnBaseBackgroundApplied = true;
    try {
        var style = document.createElement('style');
        style.id = '__gnBaseBackground';
        style.textContent =
            'html,body{background-color:#000;}' +
            'video{background-color:#000;}' +
            'video::-webkit-media-controls-start-playback-button,' +
            'video::-webkit-media-controls-overlay-play-button{display:none!important;}';
        (document.head || document.documentElement).appendChild(style);
    } catch (_e) { /* swallow */ }

    // 1x1 black gif -- fills the pre-first-frame gap, suppressing the UA play-circle placeholder.
    var BLANK_POSTER = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAAAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==';
    function setPoster(v) {
        try { if (v && !v.getAttribute('poster')) v.setAttribute('poster', BLANK_POSTER); } catch (_e) {}
    }
    try {
        var origCreate = document.createElement.bind(document);
        document.createElement = function (tag) {
            var el = origCreate(tag);
            if (tag && String(tag).toLowerCase() === 'video') setPoster(el);
            return el;
        };
    } catch (_e) { /* swallow */ }
    // belt: videos built via innerHTML / jQuery bypass createElement. observe the subtree so any
    // <video> reaching the DOM still gets a poster (best-effort -- async, so the createElement
    // hook above is what covers the play-immediately splash case).
    function observe() {
        try {
            if (!window.MutationObserver || !document.body) return;
            new MutationObserver(function (muts) {
                for (var i = 0; i < muts.length; i++) {
                    var a = muts[i].addedNodes;
                    for (var j = 0; j < a.length; j++) {
                        var n = a[j];
                        if (!n || n.nodeType !== 1) continue;
                        if (n.tagName === 'VIDEO') setPoster(n);
                        if (n.querySelectorAll) {
                            var vs = n.querySelectorAll('video');
                            for (var k = 0; k < vs.length; k++) setPoster(vs[k]);
                        }
                    }
                }
            }).observe(document.body, { childList: true, subtree: true });
            var ex = document.body.querySelectorAll('video');
            for (var m = 0; m < ex.length; m++) setPoster(ex[m]);
        } catch (_e) { /* swallow */ }
    }
    if (document.body) observe();
    else document.addEventListener('DOMContentLoaded', observe, { once: true });
})();
