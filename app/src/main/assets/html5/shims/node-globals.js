// gamenative Node-style globals -- sets window.__dirname + window.__filename.
// NW.js / Electron auto-wrap each <script> tag in a CommonJS module function that
// injects __dirname + __filename as locals. browser has neither. games that compute
// resource paths with these throw ReferenceError on first access.
//
// matches the Windows-NWjs posture we apply to every html5 container
// (project_html5_windows_nwjs_posture): process.platform=win32, process.env populated
// from IndexHtmlRewriter, paths translate via fs.js bridge. setting these as globals
// completes the Node-style boot environment so any title's bare-name lookups resolve.
// path VALUES are best-effort; games that do real filesystem I/O hit Html5FsBridge
// sandbox rules the same as any other fs path.
(function () {
    'use strict';
    try {
        if (typeof window.__dirname === 'undefined') {
            window.__dirname = '/';
        }
        if (typeof window.__filename === 'undefined') {
            window.__filename = '/index.html';
        }
    } catch (_e) { /* swallow */ }
})();
