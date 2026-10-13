// black page background: scaled canvases leave 1-px rounding gaps at letterbox edges that flash white on the
// default background. a game's own body background still wins.
//
// black <video> poster: until a video's first frame composites, WebView paints a white placeholder with a
// play-circle. `display:none` on that media-controls pseudo-element does NOT hide it on chrome-124 WebView, but
// chromium paints a poster instead of the placeholder. the poster must be set BEFORE the engine plays the element,
// hence the createElement wrap. a poster the game ships is never replaced.
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
    } catch (_e) { /* best-effort */ }

    // 1x1 black gif
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
    } catch (_e) { /* best-effort */ }
    // videos built via innerHTML / jQuery bypass createElement. best-effort only: the observer is async, so a
    // video played immediately can still flash.
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
        } catch (_e) { /* best-effort */ }
    }
    if (document.body) observe();
    else document.addEventListener('DOMContentLoaded', observe, { once: true });
})();
