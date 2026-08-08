// gamenative html5 require() dispatcher.
// loaded FIRST (index 0 of shimUrls per resolveShimUrls). installs window.require
// + a .register(id, impl) helper on it so later shims (fs.js, path.js, steamworks.js's
// greenworks chain) bind module ids onto the same dispatch table instead of each shim
// clobbering window.require in sequence.

// ordering contract: shims AFTER this one may call the register helper at parse
// time. shims before it (hypothetically) would see window.require undefined -- don't do that.
(function () {
    'use strict';

    var dispatchers = {};
    // pattern-based dispatchers (regex → impl). dispatched AFTER exact-name
    // match and BEFORE originalRequire. registered via myRequire.register.pattern(regex, impl).
    // first-match-wins on insertion order -- pack shims register early; games register never.
    var patternDispatchers = [];
    var originalRequire = (typeof window.require === 'function') ? window.require : null;

    function myRequire(id) {
        if (Object.prototype.hasOwnProperty.call(dispatchers, id)) {
            return dispatchers[id];
        }
        // pattern match pass -- O(n) with n ~= 2 for v1 (greenworks + future).
        for (var i = 0; i < patternDispatchers.length; i++) {
            try {
                if (patternDispatchers[i].regex.test(id)) {
                    return patternDispatchers[i].impl;
                }
            } catch (e) { /* bad regex — skip */ }
        }
        if (originalRequire) {
            try { return originalRequire(id); } catch (e) { /* fall through */ }
        }
        // file-path requires throw (matches Node.js semantics). Tyrano's index.html uses
        // `window.jQuery = require("./tyrano/libs/jquery.js")` in a try/catch as a CJS-
        // recovery pattern -- the catch preserves the working window.$ set by the prior
        // <script src>. undefined-on-miss wiped it.
        // bare-module misses (require('buffer'), 'fs', 'electron', etc.) preserve undefined-
        // on-miss -- many titles probe for optional Node built-ins and assume "not provided"
        // means undefined. some titles break with require('buffer') under throw-all.
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
    // pack-level shims (packs/electron.js) use this to route arch-suffixed
    // native-module require paths through the existing noop. arity-2: (regex, impl).
    myRequire.register.pattern = function (regex, impl) {
        patternDispatchers.push({ regex: regex, impl: impl });
    };

    // idempotent assign (previous shim may have set window.require). overwrite is intentional
    // per -- this shim OWNS window.require and later shims chain via register.
    window.require = myRequire;

    // NW.js exposes require.main.filename; many RPG Maker plugins read it on load. steamworks.js
    // used to set this; 1 moves ownership here now that steamworks.js chains.
    if (!window.require.main) {
        window.require.main = { filename: '' };
    }

    // INTENTIONALLY no process stub here. IndexHtmlRewriter.buildLocaleScript already installs
    // `window.process` as a FUNCTION (not object) so `Utils.isNwjs()` stays false -- that prevents
    // YEP_CoreEngine.initNwjs from crashing on `require('nw.gui').Window.get()`. flipping process
    // to an object would re-trigger that path. 3 instead intercepts StorageManager at
    // the save boundary (see fs.js forceLocalModeOnStorageManager) so RMMV/RMMZ saves route
    // through our fs bridge without touching plugin-init signals.

    // marker for device-smoke grep.
    if (self.__gnShimVerbose) try { console.log('gamenative require-dispatcher installed'); } catch (e) {}
})();
