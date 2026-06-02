// NW.js / Electron expose __dirname / __filename to page scripts; a browser has neither, so games that
// compute resource paths from them throw ReferenceError. the values are best-effort placeholders.
(function () {
    'use strict';
    try {
        if (typeof window.__dirname === 'undefined') {
            window.__dirname = '/';
        }
        if (typeof window.__filename === 'undefined') {
            window.__filename = '/index.html';
        }
    } catch (_e) { /* best-effort */ }
})();
