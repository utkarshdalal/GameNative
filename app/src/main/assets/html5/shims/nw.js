// `nw` global stub for NW.js titles. unknown paths resolve through a deep Proxy so
// `nw.Window.get().setFullscreen()` etc. never throw.
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
        } catch (e) { /* stub MUST NOT crash the game */ }
    }

    // deep-proxy invocations matching these return the user to the library (in-game quit/close);
    // substring match so `nw.Window.get().close` etc. all count.
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
            var matched = shouldExit(path);
            if (matched && typeof window.__gnRuntimeBridge !== 'undefined' &&
                typeof window.__gnRuntimeBridge.exit === 'function') {
                try { window.__gnRuntimeBridge.exit('nw:' + path); } catch (_e) {}
            }
            return makeDeepProxy(path + '()');
        };
        fn.__gnNwPath = path;
        return new Proxy(fn, {
            get: function (target, prop) {
                // runtime probes get primitives, not proxies, so coercion and Promise interop don't loop.
                if (prop === 'then') return undefined; // NOT a thenable
                if (prop === Symbol.toPrimitive) return function () { return '[nw:' + path + ']'; };
                if (prop === Symbol.iterator) return undefined; // not iterable
                if (prop === 'toString') return function () { return '[nw:' + path + ']'; };
                if (prop === 'valueOf') return function () { return '[nw:' + path + ']'; };
                if (prop === '__gnNwPath') return target.__gnNwPath;
                if (prop === 'constructor') return Object;
                if (typeof prop === 'symbol') return undefined;
                // concrete stubs, see definitions below.
                if (path === 'nw' && prop === 'App') return appStub;
                if (path === 'nw' && prop === 'Window') return windowFactory;
                if (path === 'nw' && prop === 'Clipboard') return clipboardFactory;
                logCall(path + '.' + String(prop), [], 'get');
                return makeDeepProxy(path + '.' + String(prop));
            },
            apply: function (target, _thisArg, args) {
                return target.apply(null, args);
            },
            has: function () { return true; },
        });
    }

    // concrete stubs for nw.X paths c3's NodeWebkit DOM handler reads and FORWARDS through a
    // MessagePort: deep proxies are functions, which structured clone rejects. primitives only.
    // known readers: nw.App.argv, nw.Window.get().{title,x,y,width,height}, nw.Clipboard.get().get().
    var clipboardSurface = {
        get: function () { return ''; },
        set: function () {},
        clear: function () {},
    };
    var clipboardFactory = { get: function () { return clipboardSurface; } };

    function exitViaBridge(reason) {
        try {
            if (typeof window.__gnRuntimeBridge !== 'undefined' &&
                typeof window.__gnRuntimeBridge.exit === 'function') {
                window.__gnRuntimeBridge.exit(reason);
            }
        } catch (_) {}
    }
    // Impact-engine titles pick their save adapter on nw.App.dataPath: empty falls back to
    // localStorage. real NW.js returns %LOCALAPPDATA%\<package.json.name>; the host emits a
    // Windows-form path (fs bridge maps it into the wine prefix) before this shim runs.
    var __gnNwAppDataPath = (typeof window.__gnNwAppDataPath === 'string' && window.__gnNwAppDataPath) || '';
    var appStub = {
        argv: Array.isArray(window.__gnNwArgv) ? window.__gnNwArgv.slice() : [],
        // empty: keeps c3-steam-mode (an opacity-flicker hack for real Steam) off.
        manifest: {},
        // startPath: real NW.js returns the exec dir. "." is truthy (keeps `if (nw.App.startPath)`
        // branches) and the bridge resolves dot-relative paths under the install dir, so
        // `<startPath>/data/...` asset paths work.
        dataPath: __gnNwAppDataPath,
        startPath: '.',
        quit: function () { exitViaBridge('nw.App.quit'); },
        closeAllWindows: function () { exitViaBridge('nw.App.closeAllWindows'); },
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
    // unknown methods get a no-op rather than "nw.App.X is not a function".
    appStub = new Proxy(appStub, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            if (typeof prop === 'symbol') return undefined;
            try { logCall('nw.App.' + String(prop), [], 'autostub'); } catch (_) {}
            return function () {};
        },
    });
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

    var nwRoot = makeDeepProxy('nw');

    window.nw = nwRoot;

    // Android WebView silently no-ops window.close(), which is what in-game Quit falls through to
    // (e.g. C3's Browser plugin "Close" action). route it back to the library instead.
    var __origWindowClose = window.close;
    window.close = function () {
        logCall('window.close', [], 'invoke');
        if (typeof window.__gnRuntimeBridge !== 'undefined' &&
            typeof window.__gnRuntimeBridge.exit === 'function') {
            try { window.__gnRuntimeBridge.exit('window.close'); return; } catch (_e) {}
        }
        try { return __origWindowClose.apply(window, arguments); } catch (_e) {}
    };

    // the host emits __gnNwArgv (a known launch arg, e.g. OMORI's) before this shim runs.
    // everything but argv MUST delegate to appStub: deep-proxying App here would hand
    // Impact-engine titles a marker string as dataPath and they'd write saves to a garbage path.
    if (Array.isArray(window.__gnNwArgv)) {
        var argvOverride = window.__gnNwArgv.slice();
        var appProxy = new Proxy(function () {}, {
            get: function (_t, prop) {
                if (prop === 'argv') return argvOverride;
                if (prop === 'then') return undefined;
                if (typeof prop === 'symbol') return undefined;
                return appStub[prop];
            },
            apply: function () { return makeDeepProxy('nw.App()'); },
        });
        window.nw = new Proxy(function () {}, {
            get: function (_t, prop) {
                // concrete stubs so structured clone never trips on a deep-proxy chain.
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

    // some titles check `process.versions.nw`. don't clobber an existing process polyfill.
    if (typeof window.process === 'undefined') {
        // win32: saves resolve through the wine-prefix translation, where store cloud expects
        // Windows-layout files. shape mirrors the worker-bootstrap stub; cwd '/' matches fs.js
        // relative-path normalization.
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
    // a preexisting polyfill may lack cwd; the branches above only touch .versions.
    if (window.process && typeof window.process.cwd !== 'function') {
        window.process.cwd = function () { return '/'; };
    }

    // registered AFTER the __gnNwArgv override so require('nw.gui') matches window.nw.
    if (window.require && typeof window.require.register === 'function') {
        window.require.register('nw.gui', window.nw);
        window.require.register('nw', window.nw);
        if (typeof window.require.register.pattern === 'function') {
            window.require.register.pattern(/^nw($|\.)/, window.nw);
        }
    }

    if (self.__gnShimVerbose) try { console.log('gamenative nw noop stub loaded'); } catch (e) {}
})();
