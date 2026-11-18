// pack:electron shim. registers 'electron' via require-dispatcher: app lifecycle / getPath / name,
// ipcRenderer, contextBridge, webFrame and `remote` get working stubs; every other namespace is a
// logging NOT_IMPLEMENTED_V1 proxy. also routes greenworks .node requires to the greenworks shim.
(function () {
    'use strict';

    // UMD pollution vs custom AMD loaders: IndexHtmlRewriter sets window.module = {}, so a UMD library
    // like lz-string sets window.module.exports. later UMD modules then see module + module.exports as
    // objects, take the CJS branch, and hit a bare `exports` ReferenceError, collapsing the AMD chain.
    // when an AMD `define` is installed, clear module.exports so UMD checks fall through to AMD.
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
    } catch (_e) { /* swallow -- older WebViews without defineProperty on window */ }

    function diagLog(obj) {
        try { console.warn('gamenative pack:electron: ' + JSON.stringify(obj)); } catch (e) { /* swallow */ }
    }

    // log loudly but never throw: throwing kills boot for any title that probes a missing API.
    function logNotImplemented(api, extra) {
        var entry = { ts: new Date().toISOString(), api: api };
        if (extra) entry.extra = extra;
        diagLog({ marker: 'NOT_IMPLEMENTED_V1', detail: entry });
    }

    // a function so callers can either invoke it or chain property access on it.
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

    // trapConstruct for classes games `new` (BrowserWindow, Menu, Tray). no-op rather than throw so unknown
    // calls don't kill boot; grep logcat for NOT_IMPLEMENTED_V1 for a hard signal.
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

    // setTimeout(0) matches electron's async 'ready' delivery.
    var app = {
        on: function (event, cb) {
            if (event === 'ready') {
                setTimeout(function () {
                    try { cb({}); } catch (_e) { /* swallow -- electron's dispatcher does too */ }
                }, 0);
            } else {
                logNotImplemented("app.on('" + event + "')");
            }
        },
        once: function (event, cb) { this.on(event, cb); },
        whenReady: function () { return Promise.resolve(); },
        getPath: function (name) {
            // mirrors Electron on Windows, from the Windows-style process.env IndexHtmlRewriter populates.
            // always a string (never undefined) so callers' path.join() doesn't throw.
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
        // Tyrano-on-Electron strips `\resources\app` from this to derive its save dir; a dot-relative
        // value yields "." so saves land in the install dir, where Steam cloud's *.sav pattern finds them.
        getAppPath: function () { return (window.__gnElectronCtx && window.__gnElectronCtx.appPath) || ''; },
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

    // Electron's app surface is too large to enumerate; unknown methods log and return {} (truthy,
    // descendable) instead of undefined, which would kill boot.
    var appProxied = new Proxy(app, {
        get: function (target, prop) {
            if (prop in target) return target[prop];
            return function () {
                diagLog({
                    marker: 'NOT_IMPLEMENTED_V1',
                    detail: { api: 'electron.app.' + String(prop), stubReturn: 'empty-object' },
                });
                return {};
            };
        },
    });

    // preload scripts publish renderer APIs via contextBridge (contextIsolation:true). there is no
    // separate context here, so write straight to window; without it games like Cookie Clicker think
    // they're the web version.
    var contextBridge = {
        exposeInMainWorld: function (name, value) {
            try { window[name] = value; } catch (e) { logNotImplemented('contextBridge.exposeInMainWorld(' + String(name) + ')'); }
        },
        exposeInIsolatedWorld: function (_world, name, value) {
            try { window[name] = value; } catch (e) { logNotImplemented('contextBridge.exposeInIsolatedWorld'); }
        },
    };

    // ipcRenderer must NOT throw on .on / .send / .invoke -- preload scripts wire it up during boot.
    // there is no main process, so messages go nowhere unless routed below.
    function makeIpcRendererNoop() {
        var listeners = {};
        // request/reply IPC (e.g. Cookie Clicker): renderer sends {id, callback:N}, main replies
        // {callback:N, data}. with no main process, awaiters hang forever, so any payload carrying
        // .callback gets an empty microtask reply.
        // read-style ids MUST reply '' so the game's localStorage fallback fires; a truthy [] would
        // skip it and lose the save. everything else gets [] (tolerates .filter() and .X access).
        var READ_LIKE_IDS = { 'load': 1, 'cloud read': 1 };
        // quit handled by the main process on desktop; route to __gnRuntimeBridge.exit instead.
        var QUIT_LIKE_IDS = { 'quit': 1, 'exit': 1 };
        // 'cloud save' / 'cloud read' IPC goes to the main process, which calls greenworks on desktop.
        // without routing, saves never reach the cloud and reads always fall back to localStorage.
        // routing through window.greenworks (steamworks.js shim, loaded earlier) marks greenworks cloud
        // as observed AND fills the gn:gw: localStorage namespace the exit save-sync scrapes.

        // Cookie Clicker's cloud filename -- the only known title using this IPC shape. other titles
        // would need a per-appId override.
        var CLOUD_SAVE_FILENAME = 'save.txt';
        // payloads are a bare id string when there's no callback, else {id, callback, data}.
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
            // no id: the renderer dispatches replies by callback number alone.
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
                        // desktop main replies data=0 on success.
                        if (data.callback != null) dispatchCallbackReply(data.callback, 0);
                    });
                    return true;
                } catch (e) { /* fall through to autoReply */ }
            }
            if (id === 'cloud read' && typeof gw.readTextFromFile === 'function') {
                // current by now: inbound save sync completes before the page is loaded.
                try {
                    gw.readTextFromFile(CLOUD_SAVE_FILENAME, function (text) {
                        // 0 when missing, like desktop, so an empty cloud falls through to localStorage.
                        if (data.callback != null) dispatchCallbackReply(data.callback, text || 0);
                    });
                    return true;
                } catch (e) { /* fall through to autoReply */ }
            }
            // 'load' reads the on-disk save in the desktop main process. unrouted, the game falls back to
            // its localStorage copy, which always has a newer lastDate (rewritten every autosave), so the
            // newest-wins pick ALWAYS chooses local and desktop saves never load. reading the cloud file
            // here makes local == cloud. backup loads stay autoReply -- there is no backup equivalent.
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
                        try { fn({}, reply); } catch (e) { /* swallow -- a faulty handler shouldn't poison the next */ }
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
                // electron-store-style callers read R.error / R.data; {} reads as "no saved file".
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

    // games set zoom prefs at boot; WebView can't reach chromium's zoom internals and gameplay doesn't
    // need them.
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
        ipcRenderer:      makeIpcRendererNoop(),
        // one proxy per namespace so NOT_IMPLEMENTED_V1 logs are greppable per namespace.
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

    // pre-v14 `remote` API, used by older (~pre-2020) Electron titles. getCurrentWindow() is hit at boot
    // for pixel-scale math, so it answers geometry from the live viewport and no-ops mutators.
    function makeBrowserWindowStub() {
        var ctx = window.__gnElectronCtx || {};
        var self;
        var stub = {
            id: 1,
            webContents: electronModule.webContents,
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
            isFullScreen: function () { return false; }, isSimpleFullScreen: function () { return false; },
            isMaximized: function () { return false; }, isMinimized: function () { return false; },
            isVisible: function () { return true; }, isFocused: function () { return true; },
            isResizable: function () { return true; }, isMovable: function () { return true; },
            isClosable: function () { return true; }, isAlwaysOnTop: function () { return false; },
            isFullScreenable: function () { return true; }, isDestroyed: function () { return false; },
            isDocumentEdited: function () { return false; }, isModal: function () { return false; },
            // exit goes through app.quit.
            maximize: function () {}, unmaximize: function () {}, minimize: function () {},
            restore: function () {}, show: function () {}, showInactive: function () {},
            hide: function () {}, close: function () {}, focus: function () {},
            blur: function () {}, reload: function () {}, center: function () {},
            flashFrame: function () {}, openDevTools: function () {}, closeDevTools: function () {},
            // chainable, never fires
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
            // undefined would crash the caller's next `.foo()`.
            return makeSafeNoop();
        },
        require: function (mod) {
            if (window.require && typeof window.require === 'function') return window.require(mod);
            logNotImplemented("remote.require('" + String(mod) + "')");
            return makeSafeNoop();
        },
        process: (typeof window.process !== 'undefined') ? window.process : {},
    };

    if (window.require && typeof window.require.register === 'function') {
        window.require.register('electron', electronModule);
    } else {
        try { console.warn('gamenative pack:electron: require-dispatcher missing — electron module NOT registered'); } catch (_e) {}
    }

    // matches greenworks.node, greenworks-<platform>.node, path/to/greenworks-*.node;
    // NOT my-greenworks-*.node or greenworks/linux64.node.
    var GREENWORKS_NODE_REGEX = /(?:^|[/\\])greenworks(?:-[^./\\]+)?\.node$/;

    // other native .node modules: NOT thrown at require time (so feature probes survive), but any method
    // access throws, so real use fails loudly. one shared template, hence the static `path`.
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
        // 'greenworks' is registered by steamworks.js, which isn't loaded on every path (e.g.
        // Tyrano-on-Electron); fall back to the not-implemented template.
        var greenworksImpl = NOT_IMPLEMENTED_NODE_TEMPLATE;
        try { greenworksImpl = window.require('greenworks'); } catch (_e) { /* fall through */ }
        window.require.register.pattern(
            GREENWORKS_NODE_REGEX,
            greenworksImpl,
        );
        // first match wins, so this MUST be registered after the greenworks pattern.
        window.require.register.pattern(
            NODE_MODULE_REGEX,
            NOT_IMPLEMENTED_NODE_TEMPLATE,
        );
    } else {
        try { console.warn('gamenative pack:electron: register.pattern missing — .node paths will NOT be caught'); } catch (_e) {}
    }

    // in Electron window.close exits the app; in WebView it's a no-op for a self-opened page, so Quit
    // buttons would do nothing. original close kept as fallback for popup windows.
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

    // Cookie Clicker's #notes is `bottom:0` of the layout viewport, which with a fixed-width viewport meta
    // sits BELOW the visual viewport. --gn-bottom-inset comes from viewport-inset.js.
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

    // desktop-ported titles ship a fixed-width viewport meta (`width=900`). on a smaller handheld the layout
    // viewport exceeds the visual one and the bottom/right is cut off. zoom out to fit, and lock min/max
    // scale so pinch-zoom can't undo it.
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
            if (!declaredWidth || isNaN(declaredWidth)) return;

            // layout viewport == innerWidth/Height; visual == screen.availWidth/Height (at scale=1).
            var availW = window.screen.availWidth | 0;
            var availH = window.screen.availHeight | 0;
            var layoutW = window.innerWidth | 0;
            var layoutH = window.innerHeight | 0;
            if (!availW || !availH || !layoutW || !layoutH) return;
            if (layoutW <= availW && layoutH <= availH) return;

            var fitScale = Math.min(availW / layoutW, availH / layoutH);
            // browsers may refuse initial-scale < 0.1.
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
    // after the meta is parsed but BEFORE game code caches innerWidth/Height at boot.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', fitViewport, { once: true });
    } else {
        fitViewport();
    }
})();
