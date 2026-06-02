// owns window.require; other shims bind module ids via require.register(id, impl) instead of each clobbering
// window.require. must load FIRST: shims register at parse time.
(function () {
    'use strict';

    var dispatchers = {};
    // checked after exact names, before originalRequire; first match wins.
    var patternDispatchers = [];
    var originalRequire = (typeof window.require === 'function') ? window.require : null;

    function myRequire(id) {
        if (Object.prototype.hasOwnProperty.call(dispatchers, id)) {
            return dispatchers[id];
        }
        for (var i = 0; i < patternDispatchers.length; i++) {
            try {
                if (patternDispatchers[i].regex.test(id)) {
                    return patternDispatchers[i].impl;
                }
            } catch (e) { /* bad regex: skip */ }
        }
        if (originalRequire) {
            try { return originalRequire(id); } catch (e) { /* fall through */ }
        }
        // file-path misses throw like Node: Tyrano does `window.jQuery = require("./tyrano/libs/jquery.js")` in a
        // try/catch, and returning undefined would wipe the jQuery its <script src> already set.
        // bare-module misses return undefined: games probe optional built-ins (e.g. 'buffer') and break on a throw.
        if (id && (id.charAt(0) === '.' || id.indexOf('/') >= 0)) {
            var err = new Error("Cannot find module '" + id + "'");
            err.code = "MODULE_NOT_FOUND";
            throw err;
        }
        return undefined;
    }

    myRequire.register = function (id, impl) {
        dispatchers[id] = impl;
    };
    myRequire.register.pattern = function (regex, impl) {
        patternDispatchers.push({ regex: regex, impl: impl });
    };

    window.require = myRequire;

    // NW.js exposes require.main.filename; many RPG Maker plugins read it on load.
    if (!window.require.main) {
        window.require.main = { filename: '' };
    }

    // INTENTIONALLY no process stub here. the host-injected locale script makes `window.process` a FUNCTION so
    // RMMV's `Utils.isNwjs()` stays false; an object would send YEP_CoreEngine.initNwjs into
    // `require('nw.gui').Window.get()` and crash. RMMV saves reach the fs bridge via fs.js's StorageManager hook instead.

    if (self.__gnShimVerbose) try { console.log('gamenative require-dispatcher installed'); } catch (e) {}
})();
