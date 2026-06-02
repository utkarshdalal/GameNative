// gamenative base background -- sets html/body background to #000.
// canvases scaled via transform: scale(N) leave sub-pixel rounding gaps where the
// scaled canvas and any letterbox boundary round differently. with the page background
// at the browser default (white), those 1-px gaps flash white during fades / on scaled
// canvases. setting the page background to black hides the artifact -- the gap blends
// with the letterbox / fade-to-black.
//
// always-injected for html5 containers. games whose <body style="background:..."> sets
// something else still wins; this only affects the unset case.
(function () {
    'use strict';
    if (window.__gnBaseBackgroundApplied) return;
    window.__gnBaseBackgroundApplied = true;
    try {
        var style = document.createElement('style');
        style.id = '__gnBaseBackground';
        style.textContent = 'html,body{background-color:#000;}';
        (document.head || document.documentElement).appendChild(style);
    } catch (_e) { /* swallow */ }
})();
