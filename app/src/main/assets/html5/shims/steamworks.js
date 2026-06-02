// gamenative html5 steamworks noop stub
// real steam is never contacted. every call logs JSONL to the host-side bridge
// at __gnSteamworksBridge.log(jsonString) (added by SteamworksJsBridge.kt).
// unknown exports resolve via Proxy fallback.

// 30 exports: init(6) + user(4) + achievements(6) + stats(4) + cloud(6) + overlay(4).

(function () {
    'use strict';

    // parse-time inbound cloud restore. Html5SaveSyncService.syncInbound
    // fetches Steam Cloud bytes BEFORE webView.loadUrl (gated on saveSyncInboundComplete);
    // its evaluateJavascript path writes to about:blank's localStorage which the game never
    // sees after navigation. so we cache the bytes on the bridge and read them HERE -- this
    // shim runs at parse time inside the game's actual origin (https://game-steam_<appid>),
    // so localStorage.setItem hits the right scope. fires before any game JS runs because
    // shim scripts are prepended ahead of the game's <script> tags by IndexHtmlRewriter.
    try {
        if (typeof __gnSteamworksBridge !== 'undefined' &&
            typeof __gnSteamworksBridge.getInboundCloudJson === 'function') {
            var __gnInboundJson = __gnSteamworksBridge.getInboundCloudJson();
            if (__gnInboundJson && __gnInboundJson !== '{}') {
                var __gnInboundFiles = JSON.parse(__gnInboundJson);
                Object.keys(__gnInboundFiles).forEach(function (name) {
                    try {
                        // atob decodes base64 → original utf-8 string the renderer wrote
                        var bytes = atob(__gnInboundFiles[name]);
                        window.localStorage.setItem('gn:gw:' + name, bytes);
                    } catch (_e) { /* per-file fail; continue with others */ }
                });
            }
        }
    } catch (_e) { /* bridge missing or JSON parse fail; restore is best-effort */ }

    function logCall(exportName, args, returnedDefault) {
        try {
            if (typeof window.__gnSteamworksBridge !== 'undefined' &&
                typeof window.__gnSteamworksBridge.log === 'function') {
                var safeArgs = args.map(function (a) {
                    // don't serialize functions (callbacks) -- log their kind only.
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
        } catch (e) { /* bridge missing or serialization fail — no-op */ }
    }

    // localStorage key prefix for greenworks Steam-Cloud-style file API. namespaced so
    // enumeration (getFileCount/getFileNameAndSize) only sees greenworks files, not other
    // game keys that share this origin's chromium LS leveldb.
    var GN_GW_PREFIX = 'gn:gw:';

    // synchronous invoke-callback helper. swallows exceptions inside the callback --
    // the stub MUST NOT crash the host game when a title's cb throws.
    // variadic -- getCloudQuota fires cb(err, totalBytes, availableBytes) (3 args).
    // existing 1-arg call sites (saveTextToFile etc.) preserved verbatim -- apply with [val] is
    // identical to cb(val) when val !== undefined. zero-extra-args case mirrors the original cb().
    function syncCb(cb /*, ...rest */) {
        if (typeof cb === 'function') {
            var rest = Array.prototype.slice.call(arguments, 1);
            try {
                if (rest.length === 0) { cb(); }
                else if (rest.length === 1 && rest[0] === undefined) { cb(); }
                else { cb.apply(null, rest); }
            } catch (e) { /* swallow — noop stub */ }
        }
    }

    // pull REAL logged-in steam user identity from the host bridge (PrefManager-cached at
    // login). values are stable for the session -- read once at shim load. falls back to the
    // safe placeholders ('0' / 'Player') when the bridge is missing (tests, non-html5 surfaces)
    // or when the user isn't logged in. structured-clone safe -- all primitives.
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
    } catch (e) { /* swallow — fall through to placeholder values */ }

    // SteamID stub. ALL values primitive -- c3's Greenworks DOM handler ships this
    // through MessagePort.postMessage to the worker, and structured-clone errors out
    // on any function value. Method-style accessors (getRawSteamID etc.) were dropped
    // after Moonstone GOG (pack:c3 + workerShim + nwjs exportType) tripped
    // a 1Hz `Uncaught DataCloneError: Failed to execute 'postMessage' on 'MessagePort':
    // function () {` on every Greenworks poll. callers that need method-style access
    // can read the equivalent primitive field -- c3 plugin reads `staticAccountId`,
    // `screenName` etc. directly per SolCesto main.js precedent.

    // accountId / accountID + personaName / screenName: BOTH casing variants present.
    // greenworks's native ToObject ships `accountId` (lowercase d) -- Sunshine Heavy
    // Industries (1542810) reads it that way. structured-clone-marshaled c3 side reads
    // `accountID` (uppercase D, c3 plugin convention). emit both so neither breaks.
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

    // greenworks Steam-Cloud-style file API, LS-backed under GN_GW_PREFIX. saveTextToFile /
    // writeTextToFile / fileWrite are the same write; readTextFromFile / fileRead the same read --
    // build each from one factory so the LS namespace + first-call observation hook can't drift.
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
    function gwRead(logName) {
        return function (name, cb) {
            var v = window.localStorage.getItem(GN_GW_PREFIX + String(name));
            try { __gnSteamworksBridge.markGreenworksCloudObserved(); } catch (_e) {}
            var hit = v != null;
            logCall(logName, [name], hit ? 'len=' + v.length : 'miss');
            syncCb(cb, hit ? v : '');
        };
    }

    // dispatch table -- all 30 exports perExports.
    var dispatch = {
        // init / app identity (6)
        init: function () { logCall('init', [], true); return true; },
        initAPI: function () { logCall('initAPI', [], true); return true; },
        restartAppIfNecessary: function (appId) { logCall('restartAppIfNecessary', [appId], false); return false; },
        isSteamRunning: function () { logCall('isSteamRunning', [], true); return true; },
        isSteamRunningOnSteamDeck: function () { logCall('isSteamRunningOnSteamDeck', [], false); return false; },
        getAppId: function () { logCall('getAppId', [], 0); return 0; },

        // user identity (4)
        getSteamId: function () { logCall('getSteamId', [], 'steamIdStub'); return steamIdStub; },
        getPersonaName: function () { logCall('getPersonaName', [], realPersonaName); return realPersonaName; },
        getCurrentGameLanguage: function () { logCall('getCurrentGameLanguage', [], 'english'); return 'english'; },
        getCurrentUILanguage: function () { logCall('getCurrentUILanguage', [], 'english'); return 'english'; },

        // achievements (6) -- bridge-routed per logCall preserved for diagnostic continuity.
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
        // STAYS NOOP returning true. no native progress UI in v1.
        indicateAchievementProgress: function (name, cur, max) {
            logCall('indicateAchievementProgress', [name, cur, max], true);
            return true;
        },

        // stats (4) -- bridge-routed 
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
        // requestStats -- sync return; bridge cache populated at seed.
        requestStats: function (cb) {
            var rv = false;
            try { rv = !!__gnSteamworksBridge.requestStats(); } catch (e) {}
            logCall('requestStats', [], rv);
            syncCb(cb, rv);
            return rv;
        },

        // cloud / file -- back greenworks' Steam-Cloud-style file API with localStorage so
        // c3-steam-mode titles (Moonstone Island, etc.) can persist across sessions. without
        // this, saveTextToFile was a fake-success no-op: callback fired but no bytes touched
        // disk → save UI lied "success", save list stayed empty. keys are namespaced under
        // GN_GW_PREFIX so we can enumerate without colliding with other LS users.
        // round-trips to GOG/Steam cloud via the existing LS leveldb origin-rewrite path
        // (Html5SaveSyncService).
        // markGreenworksCloudObserved (inside gwWrite/gwRead) flips the persisted flag on the
        // first observed greenworks call this session; the bridge debounces the JSON write.
        saveTextToFile: gwWrite('saveTextToFile'),
        writeTextToFile: gwWrite('writeTextToFile'),
        readTextFromFile: gwRead('readTextFromFile'),
        // TODO: wire saveFilesToCloud([names], cb) -- greenworks bulk-upload variant.
        // map names -> values from gn:gw:* LS, then call __gnSteamworksBridge.upload.

        // fileWrite/fileRead -- greenworks aliases some Electron titles use; same LS namespace.
        fileWrite: gwWrite('fileWrite'),
        fileRead: gwRead('fileRead'),
        deleteFile: function (name, cb) {
            window.localStorage.removeItem(GN_GW_PREFIX + String(name));
            // also tombstone server-side so INBOUND stops re-downloading.
            // bridge call is sync via runBlocking; tolerable for this rarely-fired path
            // (Cookie Clicker's Steam.purgeCloud and per-title cleanup utilities).
            try {
                if (typeof __gnSteamworksBridge !== 'undefined' &&
                    typeof __gnSteamworksBridge.deleteFromCloud === 'function') {
                    __gnSteamworksBridge.deleteFromCloud(String(name));
                }
            } catch (_e) { /* swallow — local LS removed regardless */ }
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
        // honest cloud quota via JavaSteam SteamCloud (was previously a
        // permissive-proxy fake-success that returned undefined-coerces-to-0). bridge
        // returns JSON {total: N, available: M}; greenworks NodeAPI callback shape
        // is cb(err, totalBytes, availableBytes) -- match exactly so Cookie Clicker's
        // "do you want to back up?" UI gates correctly on real values.
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
        // c3-steam-mode + many other titles gate save logic on isCloudEnabled. now that
        // saveTextToFile actually persists, advertising true is honest.
        isCloudEnabled: function () { logCall('isCloudEnabled', [], true); return true; },
        isCloudEnabledForUser: function () { logCall('isCloudEnabledForUser', [], true); return true; },

        // overlay / utils (4)
        activateGameOverlay: function (option) { logCall('activateGameOverlay', [option], 'undefined'); },
        isGameOverlayEnabled: function () { logCall('isGameOverlayEnabled', [], false); return false; },
        on: function (event, handler) {
            // EventEmitter register -- never fires in stub.
            logCall('on', [event], 'registered-no-fire');
        },
        isSubscribedApp: function (appId) { logCall('isSubscribedApp', [appId], true); return true; },
    };

    // permissive value used as fallback for unknown greenworks/steamworks exports. callable,
    // descendable, and primitive-coercible so games can use `greenworks.foo()` (treats result
    // as array → length=0 / iter=empty), `greenworks.SomeEnum.Value` (descends + coerces to
    // 0 in numeric contexts), and `greenworks.unknownMethod(...)` chains without crashing.
    // Sunshine Heavy Industries (1542810) trigger: `greenworks.getFriends(greenworks.FriendFlags.Immediate)`
    // returned a function (the old fallback); `func.length` was 0 (function arity), but
    // `r = func()` returned undefined → `r.length` TypeError. permissive value coerces in all
    // these contexts cleanly.
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

    // Proxy fallback: any access not in `dispatch` returns a permissive value. logging fires
    // when the resulting value is CALLED (preserves existing diagnostic semantics) -- bare
    // property access doesn't log to avoid noise from chained reads like FriendFlags.Immediate.
    var proxy = new Proxy(dispatch, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            return makePermissive(String(prop));
        },
    });

    // expose under every call convention RMMV / C3 / Electron games might use.
    window.greenworks = proxy;
    window.steamworks = proxy;

    // chain onto require-dispatcher.js (loaded earlier in shimUrl order) instead
    // of replacing window.require wholesale. fs.js + path.js register their modules the same way.
    // require.main.filename ownership moved to require-dispatcher.js.
    if (window.require && typeof window.require.register === 'function') {
        window.require.register('greenworks', proxy);
        window.require.register('./greenworks', proxy);
        window.require.register('steamworks.js', proxy);
        // pattern fallback: OMORI does `require('./js/libs/greenworks')`; other titles use
        // `require('./greenworks-win64.node')` etc. matching the bare token catches every
        // form so we don't have to enumerate.
        if (typeof window.require.register.pattern === 'function') {
            window.require.register.pattern(/greenworks/, proxy);
        }
    } else {
        // legacy fallback -- if require-dispatcher didn't load, keep wholesale-require behavior
        // so smoke doesn't regress silently. should not happen in production bundles.
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

    // signal marker for instrumentation tests (grepped by SMOKE-CHECKLIST)
    if (self.__gnShimVerbose) try { console.log('gamenative steamworks noop stub loaded'); } catch (e) {}
})();
