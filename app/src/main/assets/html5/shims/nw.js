// gamenative html5 nw.js noop stub
// for C3-in-NW.js exports (and other NW.js titles) that reference the `nw` global.
// every access logs JSONL to __gnSteamworksBridge.log when available; unknown paths
// resolve through a deep Proxy so `nw.Window.get().setFullscreen()` etc. never throw.
(function () {
    'use strict';

    function nowIso() { return new Date().toISOString(); }

    function logCall(path, args, kind) {
        try {
            if (typeof window.__gnSteamworksBridge !== 'undefined' &&
                typeof window.__gnSteamworksBridge.log === 'function') {
                var safeArgs = (args || []).map(function (a) {
                    if (typeof a === 'function') return '[fn]';
                    return a;
                });
                window.__gnSteamworksBridge.log(JSON.stringify({
                    ts: nowIso(),
                    nw: path,
                    args: safeArgs,
                    kind: kind,
                }));
            }
        } catch (e) { /* swallow — stub MUST NOT crash the host game */ }
    }

    // chainable deep proxy: any property access returns another proxy; any invocation
    // logs the full dotted path and returns yet another proxy (so game code that does
    // `nw.App.quit()` or `nw.Window.get().on('closed', cb)` never throws).

    // invocation returns a callable proxy (not undefined) so chained `.foo().bar()`
    // patterns work -- same principle as the steamworks.js Proxy fallback 
    // paths that should return to the library when invoked (in-game quit/close).
    // matched via endsWith on the dotted path so variants like `nw.App.quit`,
    // `App.quit`, `nw.Window.get().close` all route through the host bridge.
    // other proxy invocations remain no-ops (logged and returning another proxy).
    var EXIT_PATHS = ['App.quit', 'App.closeAllWindows', 'Window.close', 'Window.closeAll'];
    function shouldExit(path) {
        for (var i = 0; i < EXIT_PATHS.length; i++) {
            if (path.indexOf(EXIT_PATHS[i]) !== -1) return EXIT_PATHS[i];
        }
        return null;
    }

    function makeDeepProxy(path) {
        var fn = function () {
            logCall(path, Array.prototype.slice.call(arguments), 'invoke');
            // route in-game exits to the host so the user lands back in the library.
            var matched = shouldExit(path);
            if (matched && typeof window.__gnRuntimeBridge !== 'undefined' &&
                typeof window.__gnRuntimeBridge.exit === 'function') {
                try { window.__gnRuntimeBridge.exit('nw:' + path); } catch (_e) {}
            }
            return makeDeepProxy(path + '()');
        };
        // record the path on the function itself so debugging tools can read it.
        fn.__gnNwPath = path;
        return new Proxy(fn, {
            get: function (target, prop) {
                // standard JS runtime probes -- return sane primitives, not proxies,
                // so Symbol.toPrimitive / toString / then / Promise interop don't loop.
                if (prop === 'then') return undefined; // NOT a thenable
                if (prop === Symbol.toPrimitive) return function () { return '[nw:' + path + ']'; };
                if (prop === Symbol.iterator) return undefined; // not iterable
                if (prop === 'toString') return function () { return '[nw:' + path + ']'; };
                if (prop === 'valueOf') return function () { return '[nw:' + path + ']'; };
                if (prop === '__gnNwPath') return target.__gnNwPath;
                if (prop === 'constructor') return Object;
                if (typeof prop === 'symbol') return undefined;
                // nw.{App,Window,Clipboard} concrete stubs -- see definitions below for rationale.
                if (path === 'nw' && prop === 'App') return appStub;
                if (path === 'nw' && prop === 'Window') return windowFactory;
                if (path === 'nw' && prop === 'Clipboard') return clipboardFactory;
                // returning primitive false/empty-string for common "is available?" checks
                // reduces the chance of games taking NW.js-only code paths that then
                // invoke APIs we silently absorb but that have observable side effects.
                logCall(path + '.' + String(prop), [], 'get');
                return makeDeepProxy(path + '.' + String(prop));
            },
            apply: function (target, _thisArg, args) {
                return target.apply(null, args);
            },
            has: function () { return true; }, // `'foo' in nw.App` → always true (Proxy stub)
        });
    }

    // Concrete pre-stubs for any nw.X path c3's NodeWebkit DOM handler reads and FORWARDS
    // via MessagePort.postMessage to the worker. Deep proxies are callable functions and
    // structured-clone rejects them. Each surface below returns ONLY primitives or no-ops.

    // Confirmed offenders (logged via Html5DebugClone wrapper
    // data.result.argv ← nw.App.argv
    // data.result.window-title/x/y/... ← nw.Window.get().{title,x,y,width,height}
    // 1Hz clipboard-change loop ← nw.Clipboard.get().get()
    var clipboardSurface = {
        get: function () { return ''; },
        set: function () {},
        clear: function () {},
    };
    var clipboardFactory = { get: function () { return clipboardSurface; } };

    // nw.App -- c3 NodeWebkit DOM handler reads .argv, .manifest. Bridge App.quit /
    // closeAllWindows to runtime-bridge exit so in-game Quit returns to library.
    function exitViaBridge(reason) {
        try {
            if (typeof window.__gnRuntimeBridge !== 'undefined' &&
                typeof window.__gnRuntimeBridge.exit === 'function') {
                window.__gnRuntimeBridge.exit(reason);
            }
        } catch (_) {}
    }
    // Impact-engine titles (CrossCode-class) read nw.App.dataPath to decide which storage
    // adapter to use: empty string → falsy → fall through to localStorage. real NW.js returns
    // %LOCALAPPDATA%\<package.json.name>. IndexHtmlRewriter emits __gnNwAppDataPath BEFORE
    // this shim runs (Windows-form path that fsBridge translates to <wine>/drive_c/...).
    // empty default preserves c3 behavior (c3 doesn't gate on dataPath truthiness).
    var __gnNwAppDataPath = (typeof window.__gnNwAppDataPath === 'string' && window.__gnNwAppDataPath) || '';
    var appStub = {
        argv: Array.isArray(window.__gnNwArgv) ? window.__gnNwArgv.slice() : [],
        // disable c3-steam-mode -- main.js opacity-flicker hack only matters on real Steam.
        manifest: {},
        // path-resolve helpers some c3 plugins read at boot. dataPath populated when
        // __gnNwAppDataPath is set by IndexHtmlRewriter (pack:nwjs containers) -- real NW.js
        // returns %LOCALAPPDATA%/<App>, what Impact-engine titles gate save adapter selection
        // on. startPath = "." (sandbox-relative): c2 titles compose asset paths via
        // `<startPath>/data/...` and the bridge resolves dot-relative paths under sandbox=
        // install dir directly. real NW.js returns the exec dir; we don't have a Windows-form
        // pointer to install dir, but "." preserves the "use startPath" branch in
        // `if (nw.App.startPath)` guards (truthy) and routes file ops to install dir via the
        // bridge's relative-path semantics.
        dataPath: __gnNwAppDataPath,
        startPath: '.',
        // exit-routing methods
        quit: function () { exitViaBridge('nw.App.quit'); },
        closeAllWindows: function () { exitViaBridge('nw.App.closeAllWindows'); },
        // common no-ops -- concrete primitive returns so structured-clone never hits a function.
        // c3 NodeWebkit init calls nw.App.clearCache(); add others as encountered.
        clearCache: function () {},
        clearAppCache: function () {},
        on: function () {},
        once: function () {},
        addListener: function () {},
        removeListener: function () {},
        addOriginAccessWhitelistEntry: function () {},
        removeOriginAccessWhitelistEntry: function () {},
        setCrashDumpDir: function () {},
        crashBrowser: function () {},
        getProxyForURL: function () { return ''; },
        setProxyConfig: function () {},
        registerGlobalHotKey: function () {},
        unregisterGlobalHotKey: function () {},
        getDataPath: function () { return __gnNwAppDataPath; },
    };
    // Proxy fallback so unknown App methods return a safe no-op instead of undefined
    // (avoids "nw.App.X is not a function" if c3 main.js reaches a method we didn't list).
    appStub = new Proxy(appStub, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            if (typeof prop === 'symbol') return undefined;
            try { logCall('nw.App.' + String(prop), [], 'autostub'); } catch (_) {}
            return function () {};
        },
    });
    // nw.Window -- c3 reads .title/.x/.y/.width/.height directly (no method call) AND
    // hooks .on('move',cb)/.on('resize',cb). Concrete primitives + on as no-op.
    var windowStub = {
        title: '',
        x: 0, y: 0,
        width: (typeof window !== 'undefined' && window.innerWidth) || 1920,
        height: (typeof window !== 'undefined' && window.innerHeight) || 1080,
        on: function () {},
        once: function () {},
        addListener: function () {},
        removeListener: function () {},
        emit: function () {},
        moveTo: function () {},
        resizeTo: function () {},
        setFullscreen: function () {},
        close: function () { exitViaBridge('nw.Window.close'); },
        closeAll: function () { exitViaBridge('nw.Window.closeAll'); },
    };
    // Proxy fallback for unknown Window methods (mirror of appStub's autostub).
    windowStub = new Proxy(windowStub, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            if (typeof prop === 'symbol') return undefined;
            try { logCall('nw.Window.' + String(prop), [], 'autostub'); } catch (_) {}
            return function () {};
        },
    });
    var windowFactory = {
        get: function () { return windowStub; },
        open: function () { return windowStub; },
    };

    // NW.js-specific early probes that some games guard on -- give them harmless values
    // rather than proxies so `if (nw.process.versions.nw)` etc. don't loop.
    var nwRoot = makeDeepProxy('nw');

    // expose on window. NW.js usually makes `nw` a global; we mirror that.
    window.nw = nwRoot;

    // C3 Browser plugin's "Close" action (used for in-game Quit menu items) routes through
    // the DOM `_OnClose` handler, which falls through to `window.close()` when no Cordova
    // bridge is present. Android WebView silently no-ops `window.close()`, so the Quit
    // button does nothing. Route window.close() through our runtime bridge so it returns
    // the user to the library -- same path App.quit / Window.close already take above.
    var __origWindowClose = window.close;
    window.close = function () {
        logCall('window.close', [], 'invoke');
        if (typeof window.__gnRuntimeBridge !== 'undefined' &&
            typeof window.__gnRuntimeBridge.exit === 'function') {
            try { window.__gnRuntimeBridge.exit('window.close'); return; } catch (_e) {}
        }
        try { return __origWindowClose.apply(window, arguments); } catch (_e) {}
    };

    // OMORI step-3: when WebViewScreen knows the Steam launch arg for this title, the
    // IndexHtmlRewriter emits `window.__gnNwArgv = ["--<key>"]` BEFORE this shim runs.
    // wrap nwRoot in an outer Proxy so `nw.App.argv` returns the real array; everything
    // else (nw.App.quit, nw.Window.get(), etc.) falls through to the deep proxy.
    if (Array.isArray(window.__gnNwArgv)) {
        var argvOverride = window.__gnNwArgv.slice();
        var appProxy = new Proxy(function () {}, {
            get: function (_t, prop) {
                if (prop === 'argv') return argvOverride;
                if (prop === 'then') return undefined;
                if (typeof prop === 'symbol') return undefined;
                return makeDeepProxy('nw.App.' + String(prop));
            },
            apply: function () { return makeDeepProxy('nw.App()'); },
        });
        window.nw = new Proxy(function () {}, {
            get: function (_t, prop) {
                // override branch: appProxy carries the argv override; everything else uses the
                // concrete stubs so structured-clone never trips on a deep-proxy chain.
                if (prop === 'App') return appProxy;
                if (prop === 'Window') return windowFactory;
                if (prop === 'Clipboard') return clipboardFactory;
                if (prop === 'then') return undefined;
                if (typeof prop === 'symbol') return undefined;
                return makeDeepProxy('nw.' + String(prop));
            },
            apply: function () { return makeDeepProxy('nw()'); },
        });
    }

    // some titles check `process.versions.nw` (NW.js manifest marker). stub a minimal
    // process object ONLY if the host environment has none -- don't clobber an existing
    // polyfill (e.g. from the steamworks.js require stub).
    if (typeof window.process === 'undefined') {
        // platform=win32 to match the Windows-NWjs posture invariant (saves resolve via the
        // bridge's wine-prefix translation, which assumes process.platform aligns with where
        // Steam UFS / GOG cloud expects the files).
        // mirror the worker-bootstrap process stub (cwd/execPath/arch/mainModule) so boot
        // diagnostics that probe several process fields don't trip on the next missing one.
        // cwd='/' matches the worker and fs.js relative-path normalization (root-relative).
        window.process = {
            versions: { nw: '0.0.0-gamenative-stub', node: '0.0.0-gamenative-stub' },
            platform: 'win32',
            arch: 'x64',
            execPath: '/nwjs',
            mainModule: { filename: '/index.html' },
            cwd: function () { return '/'; },
            env: {},
        };
    } else if (window.process && !window.process.versions) {
        window.process.versions = { nw: '0.0.0-gamenative-stub', node: '0.0.0-gamenative-stub' };
    } else if (window.process && window.process.versions && !window.process.versions.nw) {
        window.process.versions.nw = '0.0.0-gamenative-stub';
    }
    // backfill cwd on a preexisting process (e.g. steamworks.js require stub) -- the branches
    // above only touch .versions, so a polyfill without cwd would still throw.
    if (window.process && typeof window.process.cwd !== 'function') {
        window.process.cwd = function () { return '/'; };
    }

    // NW.js convention: `require("nw.gui")` returns the GUI module exposing Window, Tray,
    // Menu, etc. our deep proxy absorbs `.Window.get().on()` and similar chains, so we
    // return the (possibly argv-overridden) window.nw root for the require path. registered
    // AFTER the optional __gnNwArgv override above so the registered value matches what
    // titles see via direct `window.nw` access.
    if (window.require && typeof window.require.register === 'function') {
        window.require.register('nw.gui', window.nw);
        window.require.register('nw', window.nw);
        if (typeof window.require.register.pattern === 'function') {
            window.require.register.pattern(/^nw($|\.)/, window.nw);
        }
    }

    // signal marker for instrumentation tests (grepped by SMOKE-CHECKLIST)
    if (self.__gnShimVerbose) try { console.log('gamenative nw noop stub loaded'); } catch (e) {}
})();
