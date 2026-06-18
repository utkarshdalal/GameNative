// greenworks / steamworks stub. real steam is never contacted; calls route to (or log via)
// __gnSteamworksBridge (SteamworksJsBridge.kt). unknown exports resolve via a Proxy fallback.

(function () {
    'use strict';

    // inbound cloud restore. it must happen HERE, at parse time inside the game's origin: inbound
    // sync runs before loadUrl, when evaluateJavascript would hit about:blank's localStorage.
    // shims are prepended ahead of the game's <script> tags, so this runs before any game JS.
    try {
        if (typeof __gnSteamworksBridge !== 'undefined' &&
            typeof __gnSteamworksBridge.getInboundCloudJson === 'function') {
            var __gnInboundJson = __gnSteamworksBridge.getInboundCloudJson();
            if (__gnInboundJson && __gnInboundJson !== '{}') {
                var __gnInboundFiles = JSON.parse(__gnInboundJson);
                Object.keys(__gnInboundFiles).forEach(function (name) {
                    try {
                        // cloud bytes are UTF-8 (outbound capture encodes them that way); atob alone
                        // yields one char per byte, which garbles non-ASCII and compounds every round
                        // trip. non-UTF-8 bytes keep the raw byte string rather than dropping the file.
                        var bytes = atob(__gnInboundFiles[name]);
                        var text;
                        try { text = decodeURIComponent(escape(bytes)); } catch (_d) { text = bytes; }
                        window.localStorage.setItem('gn:gw:' + name, text);
                    } catch (_e) { /* skip this file */ }
                });
            }
        }
    } catch (_e) { /* best-effort */ }

    function logCall(exportName, args, returnedDefault) {
        try {
            if (typeof window.__gnSteamworksBridge !== 'undefined' &&
                typeof window.__gnSteamworksBridge.log === 'function') {
                var safeArgs = args.map(function (a) {
                    if (typeof a === 'function') return '[fn]';
                    return a;
                });
                window.__gnSteamworksBridge.log(JSON.stringify({
                    ts: new Date().toISOString(),
                    export: exportName,
                    args: safeArgs,
                    returnedDefault: returnedDefault,
                }));
            }
        } catch (e) { /* best-effort */ }
    }

    // namespaced so getFileCount/getFileNameAndSize enumerate only greenworks files, not the
    // game's other keys in the same localStorage.
    var GN_GW_PREFIX = 'gn:gw:';

    // swallows callback exceptions: the stub MUST NOT crash the game when a title's cb throws.
    // a lone undefined arg calls cb() so the callee sees arguments.length 0.
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

    // real logged-in steam identity, read once (stable for the session). placeholders when the
    // bridge is missing or the user isn't logged in.
    var realAccountId = 0;
    var realSteamId64 = '0';
    var realPersonaName = 'Player';
    try {
        if (typeof __gnSteamworksBridge !== 'undefined') {
            if (typeof __gnSteamworksBridge.getUserAccountId === 'function') {
                realAccountId = __gnSteamworksBridge.getUserAccountId() | 0;
            }
            if (typeof __gnSteamworksBridge.getUserSteamId64 === 'function') {
                var sid64 = __gnSteamworksBridge.getUserSteamId64();
                if (sid64) realSteamId64 = String(sid64);
            }
            if (typeof __gnSteamworksBridge.getUserPersonaName === 'function') {
                var nm = __gnSteamworksBridge.getUserPersonaName();
                if (nm && nm.length) realPersonaName = nm;
            }
        }
    } catch (e) { /* keep placeholders */ }

    // ALL values primitive, NO methods (getRawSteamID etc.): c3's greenworks DOM handler posts
    // this through a MessagePort, and structured clone throws DataCloneError on any function.
    // both casings on purpose: native greenworks ships `accountId`, the c3 plugin reads `accountID`.
    var steamIdStub = {
        rawSteamID: realSteamId64,
        steamId: realSteamId64,
        accountId: realAccountId,
        accountID: realAccountId,
        personaName: realPersonaName,
        screenName: realPersonaName,
        staticAccountId: String(realAccountId),
        valid: true,
        level: 0,
        steamLevel: 0,
    };

    // one factory per op so the aliases (saveTextToFile / writeTextToFile / fileWrite, and the
    // read pair) can't drift on namespace or the observation hook.
    function gwWrite(logName) {
        return function (name, content, cb) {
            var len = (typeof content === 'string') ? content.length : -1;
            try {
                window.localStorage.setItem(GN_GW_PREFIX + String(name), String(content));
                try { __gnSteamworksBridge.markGreenworksCloudObserved(); } catch (_e) {}
                logCall(logName, [name, len], 'success');
                syncCb(cb);
            } catch (e) {
                logCall(logName, [name, len], 'err:' + e.name);
                syncCb(cb, e);
            }
        };
    }
    // greenworks keys cloud files by BASENAME; saveFilesToCloud is handed full paths.
    function gwBaseName(p) {
        var s = String(p).replace(/\\/g, '/');
        var i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    // BASE64 OF RAW BYTES, deliberately not a gn:gw:* string: that namespace is text and uploads
    // as utf8, which corrupts binary saves (c3 saves are binary) so a desktop client can't read
    // them back. the bytes go to the host verbatim via stageCloudFile. throws when unreadable.
    function gwReadPathBase64(p) {
        var fs = null;
        try { if (typeof window.require === 'function') fs = window.require('fs'); } catch (_e) {}
        if (!fs || typeof fs.readFileSync !== 'function') {
            throw new Error('saveFilesToCloud: no fs module to read ' + p);
        }
        var buf = fs.readFileSync(p);
        if (buf && typeof buf.toString === 'function' && buf.__isGnBuffer) return buf.toString('base64');
        if (typeof buf === 'string') return btoa(unescape(encodeURIComponent(buf)));
        throw new Error('saveFilesToCloud: unreadable buffer for ' + p);
    }

    function gwRead(logName) {
        return function (name, cb) {
            var v = window.localStorage.getItem(GN_GW_PREFIX + String(name));
            try { __gnSteamworksBridge.markGreenworksCloudObserved(); } catch (_e) {}
            var hit = v != null;
            logCall(logName, [name], hit ? 'len=' + v.length : 'miss');
            syncCb(cb, hit ? v : '');
        };
    }

    var dispatch = {
        init: function () { logCall('init', [], true); return true; },
        initAPI: function () { logCall('initAPI', [], true); return true; },
        restartAppIfNecessary: function (appId) { logCall('restartAppIfNecessary', [appId], false); return false; },
        isSteamRunning: function () { logCall('isSteamRunning', [], true); return true; },
        isSteamRunningOnSteamDeck: function () { logCall('isSteamRunningOnSteamDeck', [], false); return false; },
        getAppId: function () { logCall('getAppId', [], 0); return 0; },

        getSteamId: function () { logCall('getSteamId', [], 'steamIdStub'); return steamIdStub; },
        getPersonaName: function () { logCall('getPersonaName', [], realPersonaName); return realPersonaName; },
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
        // no native progress UI.
        indicateAchievementProgress: function (name, cur, max) {
            logCall('indicateAchievementProgress', [name, cur, max], true);
            return true;
        },

        getStatInt: function (name) {
            var n = 0;
            try { n = __gnSteamworksBridge.getStatInt(name) | 0; } catch (e) {}
            logCall('getStatInt', [name], n);
            return n;
        },
        getStatFloat: function (name) {
            var n = 0.0;
            try { n = Number(__gnSteamworksBridge.getStatFloat(name)) || 0.0; } catch (e) {}
            logCall('getStatFloat', [name], n);
            return n;
        },
        setStat: function (name, value) {
            var rv = false;
            try { rv = !!__gnSteamworksBridge.setStat(name, Number(value)); } catch (e) {}
            logCall('setStat', [name, value], rv);
            return rv;
        },
        storeStats: function (cb) {
            var rv = false;
            try { rv = !!__gnSteamworksBridge.storeStats(); } catch (e) {}
            logCall('storeStats', [], rv);
            syncCb(cb);
            return rv;
        },
        // plain bool: c3's greenworks DOM handler resolves its promise with this value and posts it
        // through a MessagePort, which can't clone the Proxy fallback's function.
        resetAllStats: function (achievementsToo) {
            var rv = false;
            try { rv = !!__gnSteamworksBridge.resetAllStats(!!achievementsToo); } catch (e) {}
            logCall('resetAllStats', [achievementsToo], rv);
            return rv;
        },
        // sync: the bridge's stats cache is already seeded.
        requestStats: function (cb) {
            var rv = false;
            try { rv = !!__gnSteamworksBridge.requestStats(); } catch (e) {}
            logCall('requestStats', [], rv);
            syncCb(cb, rv);
            return rv;
        },

        // cloud files live in localStorage and round-trip to store cloud with the rest of LS.
        // a fake-success no-op here makes saves silently vanish.
        saveTextToFile: gwWrite('saveTextToFile'),
        writeTextToFile: gwWrite('writeTextToFile'),
        readTextFromFile: gwRead('readTextFromFile'),
        // c3's steam plugin AWAITS this with (path, resolve, reject); the permissive proxy ignores
        // callbacks, so the promise would never settle and the event sheet stalls. files go up
        // under their basename. accepts a single path too (what c3 passes).
        saveFilesToCloud: function (files, cb, errCb) {
            var list = (files == null) ? [] :
                (Object.prototype.toString.call(files) === '[object Array]' ? files : [files]);
            var staged = 0;
            try {
                for (var i = 0; i < list.length; i++) {
                    var name = gwBaseName(list[i]);
                    // already in the text namespace via saveTextToFile: the exit snapshot carries it.
                    if (window.localStorage.getItem(GN_GW_PREFIX + name) == null) {
                        __gnSteamworksBridge.stageCloudFile(name, gwReadPathBase64(list[i]));
                    }
                    staged++;
                }
                try { __gnSteamworksBridge.markGreenworksCloudObserved(); } catch (_e) {}
                logCall('saveFilesToCloud', [staged], 'success');
                syncCb(cb);
            } catch (e) {
                // settle either way -- a rejection the game can branch on beats a hung promise.
                logCall('saveFilesToCloud', [list.length], 'err:' + (e && e.name ? e.name : 'Error'));
                syncCb(errCb, (e instanceof Error) ? e : new Error(String(e)));
            }
        },

        // greenworks aliases some Electron titles use.
        fileWrite: gwWrite('fileWrite'),
        fileRead: gwRead('fileRead'),
        deleteFile: function (name, cb) {
            window.localStorage.removeItem(GN_GW_PREFIX + String(name));
            // tombstone server-side too, or inbound sync re-downloads it. the bridge call blocks;
            // tolerable for this rarely-fired path.
            try {
                if (typeof __gnSteamworksBridge !== 'undefined' &&
                    typeof __gnSteamworksBridge.deleteFromCloud === 'function') {
                    __gnSteamworksBridge.deleteFromCloud(String(name));
                }
            } catch (_e) { /* local copy is gone regardless */ }
            logCall('deleteFile', [name], 'success');
            syncCb(cb);
        },
        fileExists: function (name) {
            var hit = window.localStorage.getItem(GN_GW_PREFIX + String(name)) != null;
            logCall('fileExists', [name], hit);
            return hit;
        },
        getFileCount: function () {
            var n = 0;
            for (var i = 0; i < window.localStorage.length; i++) {
                if ((window.localStorage.key(i) || '').indexOf(GN_GW_PREFIX) === 0) n++;
            }
            logCall('getFileCount', [], n);
            return n;
        },
        getFileNameAndSize: function (idx) {
            var n = 0;
            for (var i = 0; i < window.localStorage.length; i++) {
                var k = window.localStorage.key(i);
                if (k && k.indexOf(GN_GW_PREFIX) === 0) {
                    if (n === idx) {
                        var name = k.substring(GN_GW_PREFIX.length);
                        var v = window.localStorage.getItem(k) || '';
                        logCall('getFileNameAndSize', [idx], name + ':' + v.length);
                        return { name: name, size: v.length };
                    }
                    n++;
                }
            }
            logCall('getFileNameAndSize', [idx], 'oob');
            return { name: '', size: 0 };
        },
        // greenworks callback shape is cb(err, totalBytes, availableBytes); some games gate
        // backup UI on the real values.
        getCloudQuota: function (cb) {
            try {
                var raw = __gnSteamworksBridge.getCloudQuota();
                var parsed = JSON.parse(raw);
                var totalBytes = parsed.total | 0;
                var availableBytes = parsed.available | 0;
                logCall('getCloudQuota', [], 'total=' + totalBytes + ' avail=' + availableBytes);
                syncCb(cb, null, totalBytes, availableBytes);
            } catch (e) {
                logCall('getCloudQuota', [], 'err:' + e.name);
                syncCb(cb, e);
            }
        },
        // many titles gate save logic on this; true is honest since the file API persists.
        isCloudEnabled: function () { logCall('isCloudEnabled', [], true); return true; },
        isCloudEnabledForUser: function () { logCall('isCloudEnabledForUser', [], true); return true; },

        activateGameOverlay: function (option) { logCall('activateGameOverlay', [option], 'undefined'); },
        isGameOverlayEnabled: function () { logCall('isGameOverlayEnabled', [], false); return false; },
        on: function (event, handler) {
            // never fires.
            logCall('on', [event], 'registered-no-fire');
        },
        isSubscribedApp: function (appId) { logCall('isSubscribedApp', [appId], true); return true; },

        // DLC answered from the launching store's records. return plain values only: c3 posts
        // them through a MessagePort, which can't clone functions.
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
        // no store to install through.
        installDLC: function (appId) { logCall('installDLC', [appId], 'undefined'); },
        uninstallDLC: function (appId) { logCall('uninstallDLC', [appId], 'undefined'); },
    };

    // fallback for unknown exports: callable, descendable, and primitive-coercible, so
    // `greenworks.getFriends(greenworks.FriendFlags.Immediate).length` and similar chains get
    // 0 / '' / empty-iterable instead of a TypeError on undefined.
    function makePermissive(name) {
        var stub = function () {};
        return new Proxy(stub, {
            get: function (target, prop) {
                if (prop === 'then' || prop === 'catch' || prop === 'finally') return undefined;
                if (prop === Symbol.toPrimitive) return function (hint) {
                    if (hint === 'string') return '';
                    return 0;
                };
                if (prop === 'valueOf') return function () { return 0; };
                if (prop === 'toString') return function () { return ''; };
                if (prop === 'length') return 0;
                if (prop === Symbol.iterator) {
                    return function () {
                        return { next: function () { return { value: undefined, done: true }; } };
                    };
                }
                if (typeof prop === 'symbol') return undefined;
                if (prop in target) return target[prop];
                return makePermissive();
            },
            apply: function (_target, _this, args) {
                if (name) {
                    try { logCall(name, args, 'permissive'); } catch (_e) {}
                }
                return makePermissive();
            },
        });
    }

    // logs only when a permissive value is CALLED; bare reads like FriendFlags.Immediate would be noise.
    var proxy = new Proxy(dispatch, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            return makePermissive(String(prop));
        },
    });

    window.greenworks = proxy;
    window.steamworks = proxy;

    // register with require-dispatcher.js (loaded earlier) rather than replacing window.require.
    if (window.require && typeof window.require.register === 'function') {
        window.require.register('greenworks', proxy);
        window.require.register('./greenworks', proxy);
        window.require.register('steamworks.js', proxy);
        // games require it by many paths ('./js/libs/greenworks', './greenworks-win64.node', ...).
        if (typeof window.require.register.pattern === 'function') {
            window.require.register.pattern(/greenworks/, proxy);
        }
    } else {
        // dispatcher missing: install require wholesale rather than silently break it.
        try { console.warn('gamenative steamworks: require-dispatcher missing; falling back to wholesale require install'); } catch (e) {}
        var originalRequire = (typeof window.require === 'function') ? window.require : null;
        window.require = function (modulePath) {
            if (modulePath === 'greenworks' ||
                modulePath === './greenworks' ||
                modulePath === 'steamworks.js') {
                return proxy;
            }
            if (originalRequire) return originalRequire(modulePath);
            return proxy;
        };
        if (!window.require.main) {
            window.require.main = { filename: '' };
        }
    }

    if (self.__gnShimVerbose) try { console.log('gamenative steamworks noop stub loaded'); } catch (e) {}
})();
