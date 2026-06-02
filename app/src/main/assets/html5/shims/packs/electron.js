// pack:electron shim. registers 'electron' via require-dispatcher with real implementations for:
// (a) app.getPath(name) -- derives from process.env (APPDATA/USERPROFILE/TEMP, populated by
//     IndexHtmlRewriter as the Windows-NWjs posture) + __gnElectronCtx.productName
// (b) app.on('ready') + app.whenReady() + app.once('ready') lifecycle stubs
// (c) app.getName / app.getVersion (reads __gnElectronCtx.productName / .version)
// everything ELSE returns a NOT_IMPLEMENTED_V1 Proxy that logs + throws on access.
// enumerated namespaces: BrowserWindow, ipcRenderer, shell, dialog, Menu, Tray,
// powerSaveBlocker, screen, globalShortcut, webContents.

// registers pattern dispatcher for greenworks .node paths, routing to the existing
// greenworks noop. non-greenworks .node returns a structured NOT_IMPLEMENTED_V1
// error whose __proto__ is a Proxy that THROWS on any property access -- no silent
// success possible. the template is stored verbatim in register.pattern; dispatcher returns
// it as-is (path stays '<dynamic>' -- cares about __notImplemented marker + proxy
// throw-on-access, not per-call path accuracy).
(function () {
    'use strict';

    // __dirname / __filename live in always-injected node-globals.js -- not duplicated here.

    // amd.js / UMD-pollution unblocker (Wayward + similar Electron titles that ship a custom
    // AMD module loader). IndexHtmlRewriter sets window.module = {} so Steam4C2-style c3
    // titles don't ReferenceError on `module.exports = X`. that leaves typeof module ===
    // 'object'. lz-string's UMD epilogue does:
    //     else if (typeof module !== 'undefined' && module != null) module.exports = LZString;
    // (no .exports check, no define.amd check). after lz-string runs, window.module.exports
    // = LZString. then subsequent UMD modules (Wayward's out/js/hosts/shared/globals.js):
    //     if (typeof module === 'object' && typeof module.exports === 'object') { CJS path }
    //     else if (typeof define === 'function' && define.amd) { AMD path }
    // -- both objects now → CJS branch fires → bare `exports` reference (unscoped in
    // renderer) → ReferenceError → AMD chain collapses ("module 'globals' failed to load").
    //
    // amd.js exposes itself via `self.define = define` (with define.amd = true set first).
    // hook the assignment via a getter/setter so we know exactly when AMD takes over;
    // reset window.module.exports = undefined so subsequent UMD checks see only `typeof
    // module === 'object'` and fall through to the AMD branch where amd.js wires
    // require/exports as factory args.
    try {
        var _gnDefine;
        Object.defineProperty(window, 'define', {
            get: function () { return _gnDefine; },
            set: function (v) {
                _gnDefine = v;
                if (v && v.amd) {
                    try { if (window.module && typeof window.module === 'object') window.module.exports = undefined; } catch (_e) {}
                }
            },
            configurable: true,
        });
    } catch (_e) { /* swallow — older WebViews without defineProperty on window */ }

    function diagLog(obj) {
        try { console.warn('gamenative pack:electron: ' + JSON.stringify(obj)); } catch (e) { /* swallow */ }
    }

    // shipping default: log loudly, return descendable empty object. throwing kills boot for
    // every electron title that polls a missing API (Sunshine Heavy Industries 1542810 was
    // the trigger -- game called remote.getGlobal('trackEvent') and we threw, killing boot).
    // strict-throw variant kept for the genuinely fatal handful (no-recovery situations).
    function logNotImplemented(api, extra) {
        var entry = { ts: new Date().toISOString(), api: api };
        if (extra) entry.extra = extra;
        diagLog({ marker: 'NOT_IMPLEMENTED_V1', detail: entry });
    }
    function logNotImplementedFatal(api, extra) {
        logNotImplemented(api, extra);
        throw new Error('NOT_IMPLEMENTED_V1: ' + api);
    }

    // returns a "permissive empty" -- a function so callers can either invoke it or chain
    // property access on its return. mirrors the appProxied pattern further below.
    function makeSafeNoop() {
        var stub = function () { return makeSafeNoop(); };
        return new Proxy(stub, {
            get: function (_, prop) {
                if (prop === 'then' || prop === 'catch' || prop === 'finally') return undefined;
                if (typeof prop === 'symbol') return undefined;
                return makeSafeNoop();
            },
        });
    }

    // proxy factory. ns = namespace name (used for logging); trapConstruct = true
    // for classes that games do `new NS(...)` on (BrowserWindow, Menu, Tray).
    // SHIPPING DEFAULT: noop-returns-empty rather than throw, so unknown API calls degrade
    // silently instead of killing boot. dev who wants a hard signal can read logcat for
    // NOT_IMPLEMENTED_V1 markers.
    function makeNotImplementedProxy(ns, trapConstruct) {
        var handler = {
            get: function (_, prop) {
                if (prop === 'toString' || prop === Symbol.toPrimitive) {
                    return function () { return '[NOT_IMPLEMENTED_V1 electron.' + ns + ']'; };
                }
                if (prop === 'then' || prop === 'catch' || prop === 'finally') return undefined;
                return function () {
                    logNotImplemented('electron.' + ns + '.' + String(prop));
                    return makeSafeNoop();
                };
            },
        };
        if (trapConstruct) {
            handler.construct = function (_, args) {
                logNotImplemented('electron.' + ns + ' (construct)', { argCount: args.length });
                return {};
            };
        }
        return new Proxy(function () {}, handler);
    }

    // minimum lifecycle stubs. setTimeout(0) ready-delivery matches electron's async app.on('ready').
    var app = {
        on: function (event, cb) {
            if (event === 'ready') {
                setTimeout(function () {
                    try { cb({}); } catch (_e) { /* swallow — electron's dispatcher does too */ }
                }, 0);
            } else {
                logNotImplemented("app.on('" + event + "')");
            }
        },
        once: function (event, cb) { this.on(event, cb); },
        whenReady: function () { return Promise.resolve(); },
        getPath: function (name) {
            // derived from process.env (Windows-NWjs posture in IndexHtmlRewriter) +
            // productName. mirrors what Electron native returns on Windows: userData =
            // %APPDATA%/<productName>, appData = %APPDATA%, temp = %TEMP%, etc. single source
            // of truth -- wine layout changes propagate from the env populator. always returns
            // a string (never undefined) so callers' path.join() doesn't throw.
            var env = (window.process && window.process.env) || {};
            var product = (window.__gnElectronCtx && window.__gnElectronCtx.productName) || '';
            var roaming = env.APPDATA || '';
            var profile = env.USERPROFILE || '';
            var temp = env.TEMP || env.TMP || '';
            switch (name) {
                case 'userData':   return (roaming && product) ? roaming + '/' + product : '';
                case 'appData':    return roaming;
                case 'documents':  return profile ? profile + '/Documents' : '';
                case 'desktop':    return profile ? profile + '/Desktop' : '';
                case 'downloads':  return profile ? profile + '/Downloads' : '';
                case 'music':      return profile ? profile + '/Music' : '';
                case 'pictures':   return profile ? profile + '/Pictures' : '';
                case 'videos':     return profile ? profile + '/Videos' : '';
                case 'temp':       return temp;
                case 'home':       return profile;
                case 'logs':       return (roaming && product) ? roaming + '/' + product + '/logs' : '';
                case 'crashDumps': return (roaming && product) ? roaming + '/' + product + '/Crashpad' : '';
            }
            logNotImplemented("app.getPath('" + name + "')");
            return (roaming && product) ? roaming + '/' + product : '';
        },
        getName: function () {
            return (window.__gnElectronCtx && window.__gnElectronCtx.productName) || '';
        },
        getVersion: function () {
            return (window.__gnElectronCtx && window.__gnElectronCtx.version) || '0.0.0';
        },
        // Tyrano-on-Electron's getExePath() strips `\resources\app` from this to derive its
        // save dir. dot-relative + windows separators yields "." after strip → sandbox-relative
        // writes land at install dir (where Steam UFS *.sav pattern picks them up).
        getAppPath: function () { return (window.__gnElectronCtx && window.__gnElectronCtx.appPath) || ''; },
        // quit / exit route through gamenative runtime bridge (same plumbing RMMV uses).
        quit: function () {
            try {
                if (window.__gnRuntimeBridge && typeof window.__gnRuntimeBridge.exit === 'function') {
                    window.__gnRuntimeBridge.exit('electron.app.quit');
                    return;
                }
            } catch (_e) {}
            logNotImplemented('app.quit');
        },
        exit: function (code) {
            try {
                if (window.__gnRuntimeBridge && typeof window.__gnRuntimeBridge.exit === 'function') {
                    window.__gnRuntimeBridge.exit('electron.app.exit(' + (code || 0) + ')');
                    return;
                }
            } catch (_e) {}
            logNotImplemented('app.exit');
        },
    };

    // Electron exposes dozens of app.* methods (getGPUFeatureStatus, commandLine,
    // setAsDefaultProtocolClient, isReady, ...). fully enumerating them is a losing game.
    // wrap `app` in a Proxy so defined methods pass through; unknown access returns a
    // logging noop that returns {} -- truthy, descendable, doesn't crash callers that do
    // `.foo.bar` on the result. drops "silent undefined" landmines that kill boot.
    var appProxied = new Proxy(app, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            // log once per call site; return a benign noop that can be .-accessed further
            return function () {
                diagLog({
                    marker: 'NOT_IMPLEMENTED_V1',
                    detail: { api: 'electron.app.' + String(prop), stubReturn: 'empty-object' },
                });
                return {};
            };
        },
    });

    // contextBridge.exposeInMainWorld is the v12+ way preload scripts publish APIs to the
    // renderer when contextIsolation:true. real electron sets the prop on a separate context;
    // we approximate by writing directly to window. enables Cookie Clicker (1454400) preload
    // to publish window.api so steam.js's window.api.receive call doesn't throw, which is
    // what was leaving App=undefined → game thinking it was the web version.
    var contextBridge = {
        exposeInMainWorld: function (name, value) {
            try { window[name] = value; } catch (e) { logNotImplemented('contextBridge.exposeInMainWorld(' + String(name) + ')'); }
        },
        exposeInIsolatedWorld: function (_world, name, value) {
            try { window[name] = value; } catch (e) { logNotImplemented('contextBridge.exposeInIsolatedWorld'); }
        },
    };

    // ipcRenderer must NOT throw on .on / .send / .invoke -- preload scripts capture it and
    // store handlers that fire on game events (Cookie Clicker's window.api.receive →
    // ipcRenderer.on). throwing kills the game. silent noop is the right shape: there is no
    // main process to receive messages, so dispatched callbacks would never fire anyway.
    function makeIpcRendererNoop() {
        var listeners = {};
        // Cookie Clicker pattern (and similar request/reply IPC over ipcRenderer):
        // renderer: api.send('toMain', {id:'X', callback:N})
        // main: api.send('fromMain', {callback:N, data:{...}})
        // renderer: receives reply, dispatches sendCallbacks[N](data)
        // without a real main process the renderer's pending-callback table grows forever and
        // promises that await a reply hang past their timeouts. when a sent payload carries a
        // .callback field, schedule a microtask reply with empty data so the awaiter resolves
        // and the game advances. games that don't use this pattern register no reply listeners
        // -- the dispatch is a no-op for them.
        // shape: read-style channels (load / cloud read) MUST reply with '' so the game's
        // localStorage fallback fires (Cookie Clicker steam.js: `if (!local) local =
        // localStorageGet(Game.SaveTo)`). [] would be truthy → fallback skipped → save lost.
        // everything else gets [] which tolerates both .filter() (mods) and .X access
        // (returns undefined). list of "looks like a read" IDs is conservative -- add more
        // here as new electron titles surface.
        var READ_LIKE_IDS = { 'load': 1, 'cloud read': 1 };
        // Cookie Clicker (1454400) main: req=='quit' → app.quit() + win.close(). without
        // a real main process the IPC dies silently. route to __gnRuntimeBridge.exit so
        // in-game quit menus actually exit. add new electron titles' IDs here as they surface.
        var QUIT_LIKE_IDS = { 'quit': 1, 'exit': 1 };
        // -- Electron titles' renderers send `{id:'cloud save', data:saveStr, callback:N}`
        // and `{id:'cloud read', callback:N}` IPC to start.js, which on real desktop calls
        // greenworks.saveTextToFile / readTextFromFile. on Android-via-WebView there's no
        // start.js process, so without this routing the bytes were dropped on the floor:
        // cloud save → autoReply replied [] (game thought "saved" but nothing reached cloud);
        // cloud read → autoReply replied '' (game fell through to localStorage every time).
        // diagnosed Cookie Clicker (1454400) end-of-day by reading start.js +
        // steam.js + this stub side-by-side. routing via window.greenworks (steamworks.js
        // shim, loaded earlier in shimUrls order) flips the markGreenworksCloudObserved flag
        // AND populates the gn:gw: localStorage namespace that 's snapshot capture
        // scrapes at exit.

        // filename hardcoded to 'save.txt' -- matches Cookie Clicker's start.js
        // `saveFileCloud=saveFile.replace('.cki','.txt')` so desktop CC reads the same name.
        // future Electron+greenworks titles will need a per-appId override map; for v1
        // we lean on the Cookie-Clicker shape since CC is the only known witness.
        var CLOUD_SAVE_FILENAME = 'save.txt';
        // CC's send() helper passes a bare string when no callback ('quit', 'reload') and an
        // object when a callback is needed ({id, callback, data}). start.js normalizes both
        // (`if (typeof args==='string') args={id:args}`) -- mirror that here so quit routing
        // catches both shapes.
        function extractId(data) {
            if (typeof data === 'string') return data;
            if (data && typeof data === 'object') return String(data.id || '');
            return '';
        }
        function pickReplyData(data) {
            if (READ_LIKE_IDS[extractId(data)]) return '';
            return [];
        }
        function maybeRouteQuit(data) {
            var id = extractId(data);
            if (!QUIT_LIKE_IDS[id]) return false;
            try {
                if (window.__gnRuntimeBridge && typeof window.__gnRuntimeBridge.exit === 'function') {
                    window.__gnRuntimeBridge.exit('electron.ipc.' + id);
                    return true;
                }
            } catch (e) { /* fall through to autoReply */ }
            return false;
        }
        function dispatchCallbackReply(callbackId, replyData) {
            // mirrors start.js `send(id, data, callback)` → renderer's
            // sendCallbacks[callbackId](mes.data) lookup. id stays empty since the
            // renderer dispatches by callback number alone for these reply shapes.
            var reply = { callback: callbackId, data: replyData };
            queueMicrotask(function () {
                Object.keys(listeners).forEach(function (ch) {
                    (listeners[ch] || []).slice().forEach(function (fn) {
                        try { fn({}, reply); } catch (e) { /* swallow */ }
                    });
                });
            });
        }
        function maybeRouteCloud(data) {
            if (!data || typeof data !== 'object') return false;
            var id = extractId(data);
            var gw = window.greenworks;
            if (!gw) return false;
            if (id === 'cloud save' && typeof gw.saveTextToFile === 'function' && data.data != null) {
                try {
                    gw.saveTextToFile(CLOUD_SAVE_FILENAME, String(data.data), function () {
                        // CC's main: send('cloud saved', 0, callback) -- data=0 for the success path.
                        if (data.callback != null) dispatchCallbackReply(data.callback, 0);
                    });
                    return true;
                } catch (e) { /* fall through to autoReply */ }
            }
            if (id === 'cloud read' && typeof gw.readTextFromFile === 'function') {
                // localStorage[gn:gw:save.txt] is populated by Html5SaveSyncService.syncInbound
                // BEFORE webView.loadUrl runs (WebViewScreen gates loadUrl on the
                // saveSyncInboundComplete state). so by the time the renderer fires this IPC,
                // the cache is current -- gw.readTextFromFile reads what Steam Cloud just sent.
                try {
                    gw.readTextFromFile(CLOUD_SAVE_FILENAME, function (text) {
                        // start.js: send('cloud read', data||0, callback) -- passes either the
                        // string content or 0 if missing. mirror that here so steam.js's
                        // Steam.getMostRecentSave receives a falsy when cloud is empty and
                        // falls through to localStorage as designed.
                        if (data.callback != null) dispatchCallbackReply(data.callback, text || 0);
                    });
                    return true;
                } catch (e) { /* fall through to autoReply */ }
            }
            // 'load' IPC normally reads the on-disk save file via start.js's fs.readFileSync.
            // on Android there's no main-process file system; without routing, autoReply gives
            // '' and CC's Steam.getMostRecentSave falls back to localStorage[CookieClickerGame]
            // (web-fallback path). that stale local always has a newer lastDate than the
            // cloud save (Android writes overwrite it on every autosave), so CC's heuristic
            // ALWAYS picks local -- desktop saves never reach the game even when cloud has them.
            // route 'load' through the same greenworks read so local == cloud, the lastDate
            // tiebreaker becomes a no-op, and CC effectively loads cloud bytes. backup-load
            // (data.backup === true) stays autoReply since we have no OLDsave equivalent.
            if (id === 'load' && !data.backup && typeof gw.readTextFromFile === 'function') {
                try {
                    gw.readTextFromFile(CLOUD_SAVE_FILENAME, function (text) {
                        if (data.callback != null) dispatchCallbackReply(data.callback, text || 0);
                    });
                    return true;
                } catch (e) { /* fall through to autoReply */ }
            }
            return false;
        }
        function autoReply(data) {
            if (!data || typeof data !== 'object' || data.callback == null) return;
            var reply = { callback: data.callback, data: pickReplyData(data) };
            queueMicrotask(function () {
                Object.keys(listeners).forEach(function (ch) {
                    (listeners[ch] || []).slice().forEach(function (fn) {
                        try { fn({}, reply); } catch (e) { /* swallow — a faulty handler shouldn't poison the next */ }
                    });
                });
            });
        }
        var stub = {
            send: function (_channel, data) {
                if (maybeRouteQuit(data)) return;
                if (maybeRouteCloud(data)) return;
                autoReply(data);
            },
            sendSync: function (_channel, data) {
                if (maybeRouteQuit(data)) return undefined;
                // electron-store-style storage reads `R.error && console.error(R.error), R.data`
                // on result. {} → both accesses return undefined → no crash; caller treats as
                // "no saved file".
                return {};
            },
            sendTo: function () {},
            sendToHost: function () {},
            invoke: function () { return Promise.resolve(undefined); },
            postMessage: function () {},
            on: function (channel, fn) {
                (listeners[channel] = listeners[channel] || []).push(fn);
                return stub;
            },
            once: function (channel, fn) { return stub.on(channel, fn); },
            addListener: function (channel, fn) { return stub.on(channel, fn); },
            off: function () { return stub; },
            removeListener: function () { return stub; },
            removeAllListeners: function () { return stub; },
            eventNames: function () { return Object.keys(listeners); },
            listenerCount: function (channel) { return (listeners[channel] || []).length; },
            setMaxListeners: function () {},
            getMaxListeners: function () { return 0; },
        };
        return new Proxy(stub, {
            get: function (t, prop) {
                if (prop in t) return t[prop];
                if (typeof prop === 'symbol') return undefined;
                return function () {
                    diagLog({ marker: 'NOT_IMPLEMENTED_V1', detail: { api: 'electron.ipcRenderer.' + String(prop), stubReturn: 'noop' } });
                };
            },
        });
    }

    // webFrame is the renderer-side window-level API; games call setZoomFactor /
    // setVisualZoomLevelLimits at boot to apply user zoom prefs. real implementations need
    // chromium internals we can't reach from a WebView. silent noops keep boot moving;
    // visual zoom isn't required for gameplay (Antimatter Dimensions 1399720 is the live
    // example -- Vue calls electron.webFrame.setZoomFactor on first frame).
    var webFrame = {
        setZoomFactor: function () {},
        getZoomFactor: function () { return 1; },
        setZoomLevel: function () {},
        getZoomLevel: function () { return 0; },
        setVisualZoomLevelLimits: function () {},
        setLayoutZoomLevelLimits: function () {},
        setSpellCheckProvider: function () {},
        clearCache: function () {},
        executeJavaScript: function () { return Promise.resolve(undefined); },
    };

    var electronModule = {
        app: appProxied,
        contextBridge: contextBridge,
        webFrame: webFrame,
        // ipcRenderer is the one namespace that MUST not throw -- preload scripts attach
        // listeners during boot. all other namespaces stay as throwing proxies 
        ipcRenderer:      makeIpcRendererNoop(),
        // enumerated namespaces -- one Proxy per namespace so logcat NOT_IMPLEMENTED_V1 tags are
        // per-namespace greppable during SMOKE.
        BrowserWindow:    makeNotImplementedProxy('BrowserWindow', true),
        shell:            makeNotImplementedProxy('shell', false),
        dialog:           makeNotImplementedProxy('dialog', false),
        Menu:             makeNotImplementedProxy('Menu', true),
        Tray:             makeNotImplementedProxy('Tray', true),
        powerSaveBlocker: makeNotImplementedProxy('powerSaveBlocker', false),
        screen:           makeNotImplementedProxy('screen', false),
        globalShortcut:   makeNotImplementedProxy('globalShortcut', false),
        webContents:      makeNotImplementedProxy('webContents', false),
    };

    // pre-v14 `remote` API -- lets renderer reach main-process objects as if local. many
    // older Electron titles (Curious Expedition, anything built before ~2020) rely on
    // `require('electron').remote.app.getPath(...)` etc. mirror electronModule so the
    // app/namespace accessors work the same; add remote-only helpers (getCurrentWindow,
    // getGlobal, require) as NOT_IMPLEMENTED proxies.
    // refinement: remote.getCurrentWindow() is hit at boot by CE's pixel-scale path
    // (handleScale → getBounds). makeNotImplementedProxy threw on every access -- kills init.
    // return a BrowserWindow-lookalike that answers geometry from the live viewport and
    // no-ops mutators. unknown methods fall through to a logging noop that returns {}.
    function makeBrowserWindowStub() {
        var ctx = window.__gnElectronCtx || {};
        var self;
        var stub = {
            id: 1,
            webContents: electronModule.webContents,
            // geometry queries -- real viewport so scale math produces sane values
            getBounds: function () {
                return { x: 0, y: 0, width: window.innerWidth || 0, height: window.innerHeight || 0 };
            },
            getContentBounds: function () { return self.getBounds(); },
            getNormalBounds: function () { return self.getBounds(); },
            getSize: function () { return [window.innerWidth || 0, window.innerHeight || 0]; },
            getContentSize: function () { return self.getSize(); },
            getMinimumSize: function () { return [0, 0]; },
            getMaximumSize: function () { return [0, 0]; },
            getPosition: function () { return [0, 0]; },
            getTitle: function () { return ctx.productName || ''; },
            getBackgroundColor: function () { return '#000000'; },
            // mutators -- WebView has no real window to resize. noop.
            setBounds: function () {}, setContentBounds: function () {}, setSize: function () {},
            setContentSize: function () {}, setMinimumSize: function () {}, setMaximumSize: function () {},
            setPosition: function () {}, setTitle: function () {}, setFullScreen: function () {},
            setSimpleFullScreen: function () {}, setAspectRatio: function () {},
            setBackgroundColor: function () {}, setAlwaysOnTop: function () {}, setResizable: function () {},
            setMovable: function () {}, setMinimizable: function () {}, setMaximizable: function () {},
            setFullScreenable: function () {}, setClosable: function () {}, setMenu: function () {},
            setMenuBarVisibility: function () {}, setAutoHideMenuBar: function () {},
            setVisibleOnAllWorkspaces: function () {}, setIcon: function () {},
            setOverlayIcon: function () {}, setSkipTaskbar: function () {},
            // state queries -- safe defaults. WebView is always "focused + visible + not fullscreen" from app POV.
            isFullScreen: function () { return false; }, isSimpleFullScreen: function () { return false; },
            isMaximized: function () { return false; }, isMinimized: function () { return false; },
            isVisible: function () { return true; }, isFocused: function () { return true; },
            isResizable: function () { return true; }, isMovable: function () { return true; },
            isClosable: function () { return true; }, isAlwaysOnTop: function () { return false; },
            isFullScreenable: function () { return true; }, isDestroyed: function () { return false; },
            isDocumentEdited: function () { return false; }, isModal: function () { return false; },
            // actions -- noop. exit goes through app.quit.
            maximize: function () {}, unmaximize: function () {}, minimize: function () {},
            restore: function () {}, show: function () {}, showInactive: function () {},
            hide: function () {}, close: function () {}, focus: function () {},
            blur: function () {}, reload: function () {}, center: function () {},
            flashFrame: function () {}, openDevTools: function () {}, closeDevTools: function () {},
            // event wiring -- chainable, never fires
            on: function () { return self; }, once: function () { return self; },
            off: function () { return self; }, removeListener: function () { return self; },
            addListener: function () { return self; }, removeAllListeners: function () { return self; },
            emit: function () { return false; }, listenerCount: function () { return 0; },
        };
        self = stub;
        return new Proxy(stub, {
            get: function (t, prop) {
                if (prop in t) return t[prop];
                if (typeof prop === 'symbol') return undefined;
                return function () {
                    diagLog({
                        marker: 'NOT_IMPLEMENTED_V1',
                        detail: { api: 'electron.remote.getCurrentWindow().' + String(prop), stubReturn: 'empty-object' },
                    });
                    return {};
                };
            },
        });
    }

    electronModule.remote = {
        app: appProxied,
        BrowserWindow:    electronModule.BrowserWindow,
        ipcRenderer:      electronModule.ipcRenderer,
        shell:            electronModule.shell,
        dialog:           electronModule.dialog,
        Menu:             electronModule.Menu,
        Tray:             electronModule.Tray,
        powerSaveBlocker: electronModule.powerSaveBlocker,
        screen:           electronModule.screen,
        globalShortcut:   electronModule.globalShortcut,
        webContents:      electronModule.webContents,
        getCurrentWindow: makeBrowserWindowStub,
        getCurrentWebContents: function () {
            return electronModule.webContents;
        },
        getGlobal: function (name) {
            logNotImplemented("remote.getGlobal('" + String(name) + "')");
            // SHI (1542810) calls remote.getGlobal('trackEvent') at boot -- returning undefined
            // crashed game on the next `.foo()` access. safe-noop is descendable + invokable.
            return makeSafeNoop();
        },
        require: function (mod) {
            if (window.require && typeof window.require === 'function') return window.require(mod);
            logNotImplemented("remote.require('" + String(mod) + "')");
            return makeSafeNoop();
        },
        process: (typeof window.process !== 'undefined') ? window.process : {},
    };

    // register bare-name 'electron' so require('electron') resolves.
    if (window.require && typeof window.require.register === 'function') {
        window.require.register('electron', electronModule);
    } else {
        try { console.warn('gamenative pack:electron: require-dispatcher missing — electron module NOT registered'); } catch (_e) {}
    }

    // greenworks native-module pattern. regex anchors at start-or-separator;
    // matches greenworks.node, greenworks-linux64.node, greenworks-win64.node,
    // greenworks-darwin-x64.node, greenworks-darwin-arm64.node, path/to/greenworks-*.node.
    // does NOT match my-greenworks-*.node or greenworks/linux64.node.
    var GREENWORKS_NODE_REGEX = /(?:^|[/\\])greenworks(?:-[^./\\]+)?\.node$/;

    // non-greenworks .node returns structured error object with __proto__ Proxy
    // that throws on any method access. NOT thrown at require time (titles wrapping
    // require() in try/catch survive); titles that call methods fail loudly via proxy
    // throws. `path` is a static placeholder -- dispatcher stores this template verbatim
    // and returns it as-is; only requires the __notImplemented marker + proxy to
    // throw on access (not per-call path accuracy).
    var NODE_MODULE_REGEX = /\.node$/;
    var NOT_IMPLEMENTED_NODE_TEMPLATE = {
        __notImplemented: 'NOT_IMPLEMENTED_V1',
        moduleType: 'native-.node',
        path: '<dynamic — template shared across all non-greenworks .node requires>',
        __proto__: new Proxy({}, {
            get: function (_, prop) {
                return function () {
                    throw new Error('NOT_IMPLEMENTED_V1: native-.node access on ' + String(prop));
                };
            },
        }),
    };

    if (window.require && typeof window.require.register === 'function' &&
        typeof window.require.register.pattern === 'function') {
        // greenworks .node → existing greenworks noop (registered as 'greenworks' by steamworks.js).
        // require throws Cannot-find-module when steamworks.js hasn't loaded yet (pack:tyrano
        // titles via the Tyrano-on-Electron path don't carry the steamworks bundle). fall back
        // to the same NOT_IMPLEMENTED template used for non-greenworks .node paths so the
        // pattern still resolves to SOMETHING and games that probe `require('greenworks.node')`
        // get a structured error instead of an unhandled exception during shim load.
        var greenworksImpl = NOT_IMPLEMENTED_NODE_TEMPLATE;
        try { greenworksImpl = window.require('greenworks'); } catch (_e) { /* fall through */ }
        window.require.register.pattern(
            GREENWORKS_NODE_REGEX,
            greenworksImpl,
        );
        // non-greenworks .node → structured error template. pattern dispatch order is
        // insertion-order first-match-wins -- greenworks regex registered first wins when
        // both match.
        window.require.register.pattern(
            NODE_MODULE_REGEX,
            NOT_IMPLEMENTED_NODE_TEMPLATE,
        );
    } else {
        try { console.warn('gamenative pack:electron: register.pattern missing — .node paths will NOT be caught'); } catch (_e) {}
    }

    // window.close routing: Electron renderer's window.close exits the app. plain WebView
    // window.close is a no-op for the page that opened itself (security restriction), so
    // games like Sunshine Heavy Industries (1542810) whose Quit button calls window.close()
    // appear to do nothing. route through __gnRuntimeBridge.exit, same plumbing app.quit
    // and nw.js shim use. preserve original close as fallback so titles that call it from
    // a popup window (rare in pack:electron) still work.
    try {
        var origClose = window.close ? window.close.bind(window) : null;
        window.close = function () {
            try {
                if (window.__gnRuntimeBridge && typeof window.__gnRuntimeBridge.exit === 'function') {
                    window.__gnRuntimeBridge.exit('electron.window.close');
                    return;
                }
            } catch (_e) {}
            if (origClose) {
                try { return origClose(); } catch (_e) {}
            }
        };
    } catch (_e) { /* swallow */ }

    // Cookie Clicker (1454400): #notes is `position:absolute; bottom:0` -- anchored to
    // layout viewport bottom, which on desktop-ported titles with fixed viewport meta sits
    // BELOW the visual viewport (CC has `width=900` viewport, layout 507 vs visual 469 on
    // AYN Thor). bias up by --gn-bottom-inset (computed by viewport-inset.js, always-on).
    // selector is CC-specific by inertia; future electron titles needing the same fix add
    // their own selector + var(--gn-bottom-inset, 0px) one-liner here.
    try {
        var injectCss = function () {
            try {
                var s = document.createElement('style');
                s.textContent = '#notes { bottom: var(--gn-bottom-inset, 0px) !important; }';
                (document.head || document.documentElement).appendChild(s);
            } catch (_e) {}
        };
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', injectCss, { once: true });
        } else {
            injectCss();
        }
    } catch (_e) {}

    if (self.__gnShimVerbose) try { console.log('gamenative pack:electron shim loaded'); } catch (_e) {}

    // viewport-fit. desktop-ported electron titles ship a fixed-width meta viewport like
    // `<meta name="viewport" content="width=900, initial-scale=1">`. on a handheld smaller
    // than width=N, that produces a layout viewport (899×507 CSS for Cookie Clicker on Odin 3)
    // bigger than the visual viewport (833×469). the browser shows only the top-left chunk;
    // the bottom + right of the page is cut off, regardless of how the page positions things.
    //
    // fix: rewrite initial-scale so the visual viewport zooms out to encompass the full layout.
    // pick the more-constrained dimension's scale, apply uniformly (browser preserves aspect).
    // also force min/max-scale to lock zoom (prevents the user pinch-zooming back to broken).
    function fitViewport() {
        try {
            var meta = document.querySelector('meta[name="viewport"]');
            if (!meta) return;
            var content = meta.getAttribute('content') || '';
            var parsed = {};
            content.split(',').forEach(function (p) {
                var kv = p.split('=');
                if (kv.length === 2) parsed[kv[0].trim()] = kv[1].trim();
            });
            var declaredWidth = parseInt(parsed.width, 10);
            // only act on FIXED-width pages -- width=device-width pages are already device-fit.
            if (!declaredWidth || isNaN(declaredWidth)) return;

            // compute the scale that would make the visual viewport encompass the layout viewport.
            // layout viewport == window.innerWidth/Height; visual viewport == screen.availWidth/
            // Height (or visualViewport.width/height -- same value on Android WebView when scale=1).
            var availW = window.screen.availWidth | 0;
            var availH = window.screen.availHeight | 0;
            var layoutW = window.innerWidth | 0;
            var layoutH = window.innerHeight | 0;
            if (!availW || !availH || !layoutW || !layoutH) return;
            if (layoutW <= availW && layoutH <= availH) return; // already fits, nothing to do.

            var fitScale = Math.min(availW / layoutW, availH / layoutH);
            // clamp to a sane range. browsers may refuse very small initial-scale (<0.1).
            fitScale = Math.max(0.1, Math.min(1, fitScale));
            var s = fitScale.toFixed(4);

            parsed['initial-scale'] = s;
            parsed['minimum-scale'] = s;
            parsed['maximum-scale'] = s;
            parsed['user-scalable'] = 'no';

            var rebuilt = Object.keys(parsed).map(function (k) { return k + '=' + parsed[k]; }).join(', ');
            meta.setAttribute('content', rebuilt);
            if (self.__gnShimVerbose) try {
                console.log('gamenative pack:electron viewport-fit: ' +
                    layoutW + 'x' + layoutH + ' layout → scale ' + s +
                    ' (avail ' + availW + 'x' + availH + ')');
            } catch (_e) {}
        } catch (e) {
            try { console.warn('gamenative pack:electron viewport-fit failed: ' + e); } catch (_e) {}
        }
    }
    // run at DOMContentLoaded so the meta tag is parsed, but BEFORE the game's layout JS
    // settles into the current viewport metrics. some game code reads innerWidth/Height
    // once at boot and caches.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', fitViewport, { once: true });
    } else {
        fitViewport();
    }
})();
