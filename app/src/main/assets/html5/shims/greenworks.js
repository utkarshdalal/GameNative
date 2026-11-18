// greenworks stub for NW.js Steam titles that require('./greenworks/greenworks'). registering with
// the require dispatcher keeps the game's own greenworks.js from running (it would load .node
// bindings absent on Android).
//
// DO NOT merge with steamworks.js despite the duplicated surface: the split is load-bearing.
// steamworks.js loads FIRST and registers a /greenworks/ PATTERN; this file loads SECOND and
// registers EXACT ids, which the dispatcher checks before patterns. so require('greenworks')
// resolves HERE while require('./greenworks-win64.node') hits steamworks.js. merging or
// reordering changes which impl already-validated titles get.

(function () {
    'use strict';

    // swallows callback exceptions: the stub MUST NOT crash the game.
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
        } catch (e) { /* best-effort */ }
    }

    var greenworks = {
        // real greenworks throws when Steam isn't running; always succeed.
        init: function () { logCall('init', [], 'invoke'); return true; },
        initAPI: function () { logCall('initAPI', [], 'invoke'); return true; },
        isSteamRunning: function () { return true; },
        getAppId: function () { return 0; },
        // Steam API language NAME ("german"), not a locale code.
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
        // no overlay in WebView.
        activateGameOverlayToStore: function () { logCall('activateGameOverlayToStore', [].slice.call(arguments), 'invoke'); },
        activateGameOverlay: function () { logCall('activateGameOverlay', [].slice.call(arguments), 'invoke'); },
        // real wiring, not the autostub: some titles read getAchievementNames().length at boot.
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
        // no native progress UI.
        indicateAchievementProgress: function (name, cur, max) {
            logCall('indicateAchievementProgress', [name, cur, max], true);
            return true;
        },
        // plain values only: c3 posts them through a MessagePort, which can't clone functions.
        isDLCInstalled: function (appId) {
            var rv = false;
            try { rv = !!__gnSteamworksBridge.isDlcInstalled(Number(appId) | 0); } catch (e) {}
            logCall('isDLCInstalled', [appId], rv);
            return rv;
        },
        getDLCCount: function () {
            var list = [];
            try { list = JSON.parse(__gnSteamworksBridge.getDlcListJson() || '[]'); } catch (e) {}
            logCall('getDLCCount', [], list.length);
            return list.length;
        },
        getDLCDataByIndex: function (index) {
            var list = [];
            try { list = JSON.parse(__gnSteamworksBridge.getDlcListJson() || '[]'); } catch (e) {}
            var d = list[index | 0];
            var rv = d ? { appId: d.appId, available: d.available, name: d.name } : { appId: 0, available: false, name: '' };
            logCall('getDLCDataByIndex', [index], rv.appId);
            return rv;
        },
        installDLC: function (appId) { logCall('installDLC', [appId], 'invoke'); },
        uninstallDLC: function (appId) { logCall('uninstallDLC', [appId], 'invoke'); },
        // real greenworks is an EventEmitter; return the receiver so .on().on() chains work.
        on: function () { return greenworks; },
        once: function () { return greenworks; },
        off: function () { return greenworks; },
        addListener: function () { return greenworks; },
        removeListener: function () { return greenworks; },
        removeAllListeners: function () { return greenworks; },
        emit: function () { return false; },
        _version: '0.0.0-gamenative-stub',
    };

    // unknown methods return a callable no-op.
    greenworks = new Proxy(greenworks, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            if (prop === 'then') return undefined;
            if (typeof prop === 'symbol') return undefined;
            try { logCall(String(prop), [], 'autostub'); } catch (_) {}
            return function () { return undefined; };
        },
    });

    // real greenworks.js sets this; some titles take a "greenworks missing" path without it.
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
        window.require.register('./greenworks/greenworks', greenworks);
        window.require.register('greenworks', greenworks);
        if (typeof window.require.register.pattern === 'function') {
            window.require.register.pattern(/(^|\/)greenworks(\/greenworks)?(\.js)?$/i, greenworks);
        }
    }

    if (typeof window.greenworks === 'undefined') {
        window.greenworks = greenworks;
    }

    if (self.__gnShimVerbose) try { console.log('gamenative greenworks noop stub loaded'); } catch (e) {}
})();
