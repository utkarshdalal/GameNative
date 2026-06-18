// gamenative html5 greenworks noop stub
// for NW.js Steam titles that require('./greenworks/greenworks') for achievements,
// UFS, and overlay activation. v1 covers Alabaster Dawn (3110760): only init() and
// activateGameOverlayToStore() are reached. unknown methods absorb via Proxy fallback so
// chained calls (e.g. greenworks.on('cloud-ready', cb)) don't throw. registers itself
// against the require dispatcher so the original greenworks.js never executes (which
// would otherwise try to require platform-specific .node bindings absent on Android).
//
// DO NOT merge this with steamworks.js despite the duplicated surface (syncCb, logCall, the 6
// achievement methods). the two are kept separate ON PURPOSE because they resolve via DIFFERENT
// require paths and that split is load-bearing: steamworks.js loads FIRST and registers its big
// dispatch under window.greenworks/window.steamworks + a /greenworks/ PATTERN; greenworks.js loads
// SECOND and registers this smaller concrete stub under the EXACT ids 'greenworks' +
// './greenworks/greenworks'. require-dispatcher checks exact names before patterns, and the later
// exact registration wins -- so require('greenworks') resolves to THIS file while
// require('./greenworks-win64.node') falls through to the pattern in steamworks.js. merging (or
// reordering the loads) would change which impl a given -- already validated -- title resolves.

(function () {
    'use strict';

    // synchronous invoke-callback helper. swallows exceptions -- stub MUST NOT crash host game.
    function syncCb(cb /*, ...rest */) {
        if (typeof cb === 'function') {
            var rest = Array.prototype.slice.call(arguments, 1);
            try {
                if (rest.length === 0) { cb(); }
                else if (rest.length === 1 && rest[0] === undefined) { cb(); }
                else { cb.apply(null, rest); }
            } catch (e) { /* swallow */ }
        }
    }

    function logCall(path, args, kind) {
        try {
            if (typeof window.__gnSteamworksBridge !== 'undefined' &&
                typeof window.__gnSteamworksBridge.log === 'function') {
                var safeArgs = (args || []).map(function (a) {
                    if (typeof a === 'function') return '[fn]';
                    return a;
                });
                window.__gnSteamworksBridge.log(JSON.stringify({
                    ts: new Date().toISOString(),
                    greenworks: path,
                    args: safeArgs,
                    kind: kind,
                }));
            }
        } catch (e) { /* swallow — stub MUST NOT crash the host game */ }
    }

    // concrete surface for the calls the bundle.js sniff confirmed in v1.
    var greenworks = {
        // init: real greenworks returns true on success, throws on Steam-not-running.
        // we always succeed -- host already knows it owns the appid.
        init: function () { logCall('init', [], 'invoke'); return true; },
        initAPI: function () { logCall('initAPI', [], 'invoke'); return true; },
        isSteamRunning: function () { return true; },
        getAppId: function () { return 0; },
        // language -- bridge-routed (same __gnSteamworksBridge). real greenworks returns the
        // Steam API NAME ("german"). absent here previously → Proxy-undefined fallback, so
        // localized titles fell back to english regardless of the container language setting.
        getCurrentGameLanguage: function () {
            var v = 'english';
            try { v = __gnSteamworksBridge.getGameLanguage() || 'english'; } catch (e) {}
            logCall('getCurrentGameLanguage', [], v);
            return v;
        },
        getCurrentUILanguage: function () {
            var v = 'english';
            try { v = __gnSteamworksBridge.getGameLanguage() || 'english'; } catch (e) {}
            logCall('getCurrentUILanguage', [], v);
            return v;
        },
        // overlay-to-store opens the Steam store page in chromium UI overlay; under WebView
        // there's no overlay → silently absorb. games typically show a fallback "View on Steam"
        // button anyway.
        activateGameOverlayToStore: function () { logCall('activateGameOverlayToStore', [].slice.call(arguments), 'invoke'); },
        activateGameOverlay: function () { logCall('activateGameOverlay', [].slice.call(arguments), 'invoke'); },
        // achievements -- bridge-routed via __gnSteamworksBridge (same JNI bridge that
        // steamworks.js uses). titles that require('./greenworks') and call achievement
        // methods (Fix Me Fix You: getAchievementNames().length at scenario boot) need real
        // wiring, not Proxy-undefined fallback.
        getAchievementNames: function () {
            var arr = [];
            try {
                var raw = __gnSteamworksBridge.getAchievementNames();
                arr = JSON.parse(raw || '[]');
            } catch (e) {}
            logCall('getAchievementNames', [], arr.length);
            return arr;
        },
        getNumberOfAchievements: function () {
            var n = 0;
            try { n = __gnSteamworksBridge.getNumberOfAchievements() | 0; } catch (e) {}
            logCall('getNumberOfAchievements', [], n);
            return n;
        },
        activateAchievement: function (name, cb) {
            var rv = false;
            try { rv = !!__gnSteamworksBridge.activateAchievement(name); } catch (e) {}
            logCall('activateAchievement', [name], rv);
            syncCb(cb);
            return rv;
        },
        clearAchievement: function (name, cb) {
            var rv = false;
            try { rv = !!__gnSteamworksBridge.clearAchievement(name); } catch (e) {}
            logCall('clearAchievement', [name], rv);
            syncCb(cb);
            return rv;
        },
        getAchievement: function (name, cb) {
            var rv = false;
            try { rv = !!__gnSteamworksBridge.getAchievement(name); } catch (e) {}
            logCall('getAchievement', [name], rv);
            syncCb(cb, rv);
            return rv;
        },
        // no native progress UI in v1 -- noop returns true.
        indicateAchievementProgress: function (name, cur, max) {
            logCall('indicateAchievementProgress', [name, cur, max], true);
            return true;
        },
        // EventEmitter shape -- greenworks chains ._steam_events on EventEmitter prototype. games
        // call greenworks.on('steam-status', cb) etc. return the receiver to keep .on().on() chains.
        on: function () { return greenworks; },
        once: function () { return greenworks; },
        off: function () { return greenworks; },
        addListener: function () { return greenworks; },
        removeListener: function () { return greenworks; },
        removeAllListeners: function () { return greenworks; },
        emit: function () { return false; },
        // process.versions.greenworks is set by the real greenworks.js; mirror so titles that
        // gate on it don't take the "greenworks missing" code path.
        _version: '0.0.0-gamenative-stub',
    };

    // Proxy fallback: any unknown method returns a callable no-op so chained calls don't throw.
    // matches the steamworks.js / nw.js shim posture.
    greenworks = new Proxy(greenworks, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            if (prop === 'then') return undefined; // not thenable
            if (typeof prop === 'symbol') return undefined;
            try { logCall(String(prop), [], 'autostub'); } catch (_) {}
            return function () { return undefined; };
        },
    });

    // mirror process.versions.greenworks so titles probing the marker see a value.
    try {
        if (typeof window.process === 'undefined') {
            window.process = { versions: { greenworks: '0.0.0-gamenative-stub' }, platform: 'win32', env: {} };
        } else if (window.process && !window.process.versions) {
            window.process.versions = { greenworks: '0.0.0-gamenative-stub' };
        } else if (window.process && window.process.versions && !window.process.versions.greenworks) {
            window.process.versions.greenworks = '0.0.0-gamenative-stub';
        }
    } catch (_e) { /* swallow */ }

    if (window.require && typeof window.require.register === 'function') {
        // Alabaster Dawn (3110760): require('./greenworks/greenworks').
        window.require.register('./greenworks/greenworks', greenworks);
        window.require.register('greenworks', greenworks);
        // pattern for variants: greenworks, ./greenworks/greenworks.js, ../greenworks/greenworks, etc.
        if (typeof window.require.register.pattern === 'function') {
            window.require.register.pattern(/(^|\/)greenworks(\/greenworks)?(\.js)?$/i, greenworks);
        }
    }

    // expose on window for titles that read it directly (rare, but cheap insurance).
    if (typeof window.greenworks === 'undefined') {
        window.greenworks = greenworks;
    }

    if (self.__gnShimVerbose) try { console.log('gamenative greenworks noop stub loaded'); } catch (e) {}
})();
