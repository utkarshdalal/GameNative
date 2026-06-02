// applies the game's localStorage from its Wine copy (cloud) in the page, before any game script runs. the host can't
// write the WebView's LS leveldb itself: chromium keeps that store open for the whole app process once any WebView used
// it, so a second writer would race chromium's own log. the bridge hands the entries out once per launch, so an
// in-game reload doesn't roll the game back.
(function () {
    'use strict';
    var json;
    try {
        if (typeof __gnLsRestoreBridge === 'undefined' || typeof __gnLsRestoreBridge.take !== 'function') return;
        json = __gnLsRestoreBridge.take();
    } catch (e) { return; }
    if (!json) return;

    // key/value = base64 of UTF-16LE code units, same as the exit capture, so lone surrogates survive.
    function d(b64) {
        var b = atob(b64), s = '';
        for (var i = 0; i + 1 < b.length; i += 2) s += String.fromCharCode(b.charCodeAt(i) | (b.charCodeAt(i + 1) << 8));
        return s;
    }

    try {
        var pairs = JSON.parse(json);
        // mirror: the restored copy replaces this origin's keys (localStorage.clear is origin-scoped).
        window.localStorage.clear();
        for (var j = 0; j < pairs.length; j++) window.localStorage.setItem(d(pairs[j][0]), d(pairs[j][1]));
        __gnLsRestoreBridge.applied();
        if (self.__gnShimVerbose) try { console.log('gamenative ls-restore: applied ' + pairs.length + ' keys'); } catch (e) {}
    } catch (e) {
        // not reported as applied: the host then keeps the Wine copy out of this session's exit sync.
        try { console.error('gamenative ls-restore failed: ' + e); } catch (_e) {}
    }
})();
