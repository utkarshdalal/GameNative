// runs an electron app's own main script in the page as its main process, so the app's ipcMain handlers
// answer its renderer. the host injects this shim and then the main script, wrapped as
// __gnElectronMain.run(function (require, module, exports, __filename, __dirname, process, Buffer) {...}),
// between the shims and the preload. only for apps whose main serves the page itself (ProtocolServedPage).
// main gets a private require: a main-side `electron`, the steamworks.js client from steamworks.js, and the
// page's node shims. a main that throws (while loading or in its startup callbacks) or quits before it opens its
// window leaves the page on the plain ipcRenderer stub, as without this shim.
(function () {
    'use strict';
    if (window.__gnElectronMain) return;

    var TAG = 'gamenative electron-main';
    function mark(obj) {
        try { console.warn(TAG + ': ' + JSON.stringify(obj)); } catch (e) { /* swallow */ }
    }

    var pageRequire = window.require;
    var renderer = null;
    try { renderer = pageRequire && pageRequire('electron'); } catch (e) { /* below */ }
    var EventEmitter = null;
    try { EventEmitter = pageRequire('events').EventEmitter; } catch (e) { /* below */ }
    if (!renderer || !renderer.ipcRenderer || typeof EventEmitter !== 'function') {
        mark({ marker: 'MAIN_STANDIN_FAILED', stage: 'setup' });
        return;
    }

    var ctx = window.__gnElectronCtx || {};
    var slice = Array.prototype.slice;
    // true once main loaded without throwing; false again if it fails before opening its window.
    var active = false;
    var ran = false;
    var booted = false;
    var notImplemented = {};

    function noteNotImplemented(api) {
        if (notImplemented[api]) return;
        notImplemented[api] = 1;
        mark({ marker: 'NOT_IMPLEMENTED_V1', api: api });
    }

    // electron structured-clones ipc payloads both ways.
    function clone(v) {
        if (v === undefined || v === null || typeof v !== 'object') return v;
        try { return structuredClone(v); } catch (e) { return v; }
    }

    function emitter() {
        var e = new EventEmitter();
        if (typeof e.listenerCount !== 'function') {
            e.listenerCount = function (ev) { return ((this._events || {})[ev] || []).length; };
        }
        return e;
    }

    // a member the stand-in doesn't have: callable (returns undefined) and descendable, logged once when called.
    function unknownMember(name) {
        return new Proxy(function () { noteNotImplemented(name); }, {
            get: function (t, prop) {
                if (typeof prop === 'symbol' || prop === 'then') return undefined;
                return unknownMember(name + '.' + String(prop));
            },
        });
    }

    function tolerant(name, target) {
        return new Proxy(target, {
            get: function (t, prop) {
                if (prop in t) return t[prop];
                if (typeof prop === 'symbol' || prop === 'then') return undefined;
                return unknownMember(name + '.' + String(prop));
            },
        });
    }

    function runtimeExit(source) {
        try {
            if (window.__gnRuntimeBridge && typeof window.__gnRuntimeBridge.exit === 'function') {
                window.__gnRuntimeBridge.exit(source);
                return;
            }
        } catch (e) { /* swallow */ }
        mark({ marker: 'NOT_IMPLEMENTED_V1', api: source });
    }

    function deactivate(reason, err) {
        if (!active && ran) return;
        active = false;
        var m = { marker: 'MAIN_STANDIN_FAILED', stage: reason };
        if (err !== undefined) m.error = String(err && err.stack || err).slice(0, 600);
        mark(m);
    }

    // main failed before it opened its window (a throwing ready callback, a rejected startup chain): the page
    // keeps the plain stub, as when main throws while loading. after the window, an error is the app's own. a
    // rejection anywhere else in main only warns, as in electron.
    function failStart(stage, err) {
        if (booted || !active) return;
        deactivate(stage, err);
    }

    function guardStart(fn) {
        return function () {
            var r;
            try {
                r = fn.apply(this, arguments);
            } catch (e) {
                failStart('ready', e);
                throw e;
            }
            if (r && typeof r.then === 'function') {
                r.then(null, function (e) { failStart('ready', e); });
            }
            return r;
        };
    }

    // ---------- renderer side: preload's ipcRenderer reaches main's handlers ----------

    var base = renderer.ipcRenderer;
    var handlers = {};
    var ipcMain = emitter();
    var rendererListeners = {};
    var rendererEvent = { sender: null, senderId: 0, ports: [] };

    function deliverToRenderer(channel, args) {
        var fns = (rendererListeners[channel] || []).slice();
        if (!fns.length) return;
        var payload = clone(args);
        setTimeout(function () {
            for (var i = 0; i < fns.length; i++) {
                try { fns[i].apply(null, [rendererEvent].concat(payload)); } catch (e) { /* swallow */ }
            }
        }, 0);
    }

    var mainWebContents = emitter();
    var senderFrame = {
        get url() { return String(location.href); },
        get origin() { return String(location.origin); },
        name: '',
        routingId: 1,
        processId: 1,
        top: null,
        parent: null,
        send: function (channel) { deliverToRenderer(channel, slice.call(arguments, 1)); },
    };
    senderFrame.top = senderFrame;

    function mainEvent() {
        return {
            sender: mainWebContents,
            senderFrame: senderFrame,
            frameId: 1,
            processId: 1,
            ports: [],
            returnValue: undefined,
            defaultPrevented: false,
            preventDefault: function () { this.defaultPrevented = true; },
            reply: function (channel) { deliverToRenderer(channel, slice.call(arguments, 1)); },
        };
    }

    function remoteError(channel, err) {
        return new Error("Error invoking remote method '" + channel + "': " + String(err));
    }

    function hasMainListener(channel) {
        return active && ipcMain.listenerCount(channel) > 0;
    }

    var wrapped = {
        invoke: function (channel) {
            var handler = active ? handlers[channel] : null;
            if (!handler) return base.invoke.apply(base, arguments);
            var args = clone(slice.call(arguments, 1));
            return new Promise(function (resolve, reject) {
                try {
                    Promise.resolve(handler.apply(null, [mainEvent()].concat(args))).then(
                        function (v) { resolve(clone(v)); },
                        function (e) { reject(remoteError(channel, e)); });
                } catch (e) {
                    reject(remoteError(channel, e));
                }
            });
        },
        send: function (channel) {
            if (!hasMainListener(channel)) return base.send.apply(base, arguments);
            var args = clone(slice.call(arguments, 1));
            setTimeout(function () {
                try { ipcMain.emit.apply(ipcMain, [channel, mainEvent()].concat(args)); } catch (e) { /* swallow */ }
            }, 0);
        },
        sendSync: function (channel) {
            if (!hasMainListener(channel)) return base.sendSync.apply(base, arguments);
            var ev = mainEvent();
            try { ipcMain.emit.apply(ipcMain, [channel, ev].concat(clone(slice.call(arguments, 1)))); } catch (e) { /* swallow */ }
            return clone(ev.returnValue);
        },
        on: function (channel, fn) {
            (rendererListeners[channel] = rendererListeners[channel] || []).push(fn);
            base.on(channel, fn);
            return wrapped;
        },
        once: function (channel, fn) {
            function once() {
                wrapped.removeListener(channel, once);
                return fn.apply(this, arguments);
            }
            return wrapped.on(channel, once);
        },
        addListener: function (channel, fn) { return wrapped.on(channel, fn); },
        removeListener: function (channel, fn) {
            var fns = rendererListeners[channel] || [];
            var i = fns.indexOf(fn);
            if (i >= 0) fns.splice(i, 1);
            try { base.removeListener(channel, fn); } catch (e) { /* swallow */ }
            return wrapped;
        },
        off: function (channel, fn) { return wrapped.removeListener(channel, fn); },
        removeAllListeners: function (channel) {
            if (channel == null) rendererListeners = {}; else delete rendererListeners[channel];
            try { base.removeAllListeners(channel); } catch (e) { /* swallow */ }
            return wrapped;
        },
        listenerCount: function (channel) { return (rendererListeners[channel] || []).length; },
    };
    var wrappedProxy = new Proxy(wrapped, {
        get: function (t, prop) { return prop in t ? t[prop] : base[prop]; },
    });
    rendererEvent.sender = wrappedProxy;
    try {
        renderer.ipcRenderer = wrappedProxy;
        if (renderer.remote) renderer.remote.ipcRenderer = wrappedProxy;
    } catch (e) {
        mark({ marker: 'MAIN_STANDIN_FAILED', stage: 'hook' });
        return;
    }

    ipcMain.handle = function (channel, fn) {
        if (handlers[channel]) throw new Error("Attempted to register a second handler for '" + channel + "'");
        handlers[channel] = fn;
    };
    ipcMain.handleOnce = function (channel, fn) {
        ipcMain.handle(channel, function () {
            delete handlers[channel];
            return fn.apply(this, arguments);
        });
    };
    ipcMain.removeHandler = function (channel) { delete handlers[channel]; };

    // ---------- main side: electron ----------

    var appName = ctx.productName || '';
    var paths = {};
    var readyResolve;
    var readyPromise = new Promise(function (r) { readyResolve = r; });
    var isReady = false;
    var quitting = false;
    var app = emitter();
    // the events shim swallows a throwing listener, so startup listeners are guarded where they are added.
    ['on', 'once', 'addListener', 'prependListener', 'prependOnceListener'].forEach(function (m) {
        var add = app[m];
        if (typeof add !== 'function') return;
        app[m] = function (ev, fn) {
            if ((ev === 'ready' || ev === 'will-finish-launching') && typeof fn === 'function') fn = guardStart(fn);
            return add.call(this, ev, fn);
        };
    });
    var userAgentFallback = navigator.userAgent;

    function preventable() {
        return { defaultPrevented: false, preventDefault: function () { this.defaultPrevented = true; } };
    }

    function quit(source) {
        if (quitting) return;
        // a main that quits before it opens its window failed to start here; the page keeps running.
        if (!booted) {
            deactivate('quit-before-window');
            return;
        }
        var before = preventable();
        app.emit('before-quit', before);
        if (before.defaultPrevented) return;
        var will = preventable();
        app.emit('will-quit', will);
        if (will.defaultPrevented) return;
        quitting = true;
        app.emit('quit', preventable(), 0);
        runtimeExit(source);
    }

    app.getName = function () { return appName; };
    app.setName = function (n) { appName = String(n); };
    app.getVersion = function () { return ctx.version || '0.0.0'; };
    app.getAppPath = function () { return renderer.app.getAppPath(); };
    app.getPath = function (name) { return paths[name] || renderer.app.getPath(name); };
    app.setPath = function (name, p) { paths[name] = String(p); };
    app.getLocale = function () { return navigator.language || 'en-US'; };
    app.getSystemLocale = app.getLocale;
    app.getPreferredSystemLanguages = function () { return slice.call(navigator.languages || [app.getLocale()]); };
    app.getLocaleCountryCode = function () { return (app.getLocale().split('-')[1] || '').toUpperCase(); };
    app.isPackaged = true;
    app.isReady = function () { return isReady; };
    // callbacks run guarded, so a main that throws in them before its window exists falls back to the stub.
    app.whenReady = function () {
        var p = readyPromise.then(function () {});
        var then = p.then;
        p.then = function (ok, bad) { return then.call(p, typeof ok === 'function' ? guardStart(ok) : ok, bad); };
        return p;
    };
    app.requestSingleInstanceLock = function () { return true; };
    app.hasSingleInstanceLock = function () { return true; };
    app.releaseSingleInstanceLock = function () {};
    app.setAppUserModelId = function () {};
    app.disableHardwareAcceleration = function () {};
    app.setAsDefaultProtocolClient = function () { return false; };
    app.isDefaultProtocolClient = function () { return false; };
    app.removeAsDefaultProtocolClient = function () { return false; };
    app.getGPUInfo = function () { return Promise.resolve({}); };
    app.getGPUFeatureStatus = function () { return {}; };
    app.getAppMetrics = function () { return []; };
    app.focus = function () {};
    app.setBadgeCount = function () { return false; };
    app.commandLine = {
        appendSwitch: function () {},
        appendArgument: function () {},
        removeSwitch: function () {},
        hasSwitch: function () { return false; },
        getSwitchValue: function () { return ''; },
    };
    Object.defineProperty(app, 'userAgentFallback', {
        get: function () { return userAgentFallback; },
        // the host already appends main's suffix to the WebView UA.
        set: function (v) { userAgentFallback = String(v); },
    });
    app.quit = function () { quit('electron.main.app.quit'); };
    app.exit = function (code) {
        if (!booted) {
            deactivate('exit-before-window');
            return;
        }
        quitting = true;
        runtimeExit('electron.main.app.exit(' + (code | 0) + ')');
    };
    app.relaunch = function () {};

    var lastPointer = { x: 0, y: 0 };
    try {
        window.addEventListener('pointermove', function (e) { lastPointer = { x: e.clientX, y: e.clientY }; }, true);
        window.addEventListener('pointerdown', function (e) { lastPointer = { x: e.clientX, y: e.clientY }; }, true);
    } catch (e) { /* swallow */ }

    function viewportBounds() {
        return { x: 0, y: 0, width: window.innerWidth | 0, height: window.innerHeight | 0 };
    }

    function display() {
        var b = viewportBounds();
        return {
            id: 1,
            label: '',
            bounds: b,
            workArea: b,
            size: { width: b.width, height: b.height },
            workAreaSize: { width: b.width, height: b.height },
            scaleFactor: window.devicePixelRatio || 1,
            rotation: 0,
            internal: true,
            monochrome: false,
            displayFrequency: 60,
            touchSupport: 'available',
        };
    }

    var screen = emitter();
    screen.getPrimaryDisplay = display;
    screen.getAllDisplays = function () { return [display()]; };
    screen.getDisplayMatching = display;
    screen.getDisplayNearestPoint = display;
    screen.getCursorScreenPoint = function () { return { x: lastPointer.x, y: lastPointer.y }; };
    screen.screenToDipRect = function (_w, r) { return r; };
    screen.dipToScreenRect = function (_w, r) { return r; };
    screen.screenToDipPoint = function (p) { return p; };
    screen.dipToScreenPoint = function (p) { return p; };

    mainWebContents.id = 1;
    mainWebContents.send = function (channel) { deliverToRenderer(channel, slice.call(arguments, 1)); };
    mainWebContents.getURL = function () { return String(location.href); };
    mainWebContents.getTitle = function () { return document.title; };
    mainWebContents.isDestroyed = function () { return false; };
    mainWebContents.isLoading = function () { return false; };
    mainWebContents.isFocused = function () { return true; };
    mainWebContents.setBackgroundThrottling = function () {};
    mainWebContents.setWindowOpenHandler = function () {};
    mainWebContents.setUserAgent = function () {};
    mainWebContents.getUserAgent = function () { return navigator.userAgent; };
    mainWebContents.setZoomFactor = function () {};
    mainWebContents.setVisualZoomLevelLimits = function () { return Promise.resolve(); };
    mainWebContents.setAudioMuted = function () {};
    mainWebContents.isDevToolsOpened = function () { return false; };
    mainWebContents.openDevTools = function () {};
    mainWebContents.closeDevTools = function () {};
    mainWebContents.toggleDevTools = function () {};
    mainWebContents.sendInputEvent = function () {};
    mainWebContents.focus = function () {};
    mainWebContents.getOSProcessId = function () { return 1; };
    mainWebContents.mainFrame = senderFrame;
    // electron runs this in the page, which is where we already are.
    mainWebContents.executeJavaScript = function (code) {
        try { return Promise.resolve((0, eval)(String(code))); } catch (e) { return Promise.reject(e); }
    };
    var mainWebContentsProxy = tolerant('webContents', mainWebContents);

    var windows = [];

    function BrowserWindow(opts) {
        EventEmitter.call(this);
        opts = opts || {};
        this.id = windows.length + 1;
        this.webContents = mainWebContentsProxy;
        this._fullScreen = !!opts.fullscreen;
        this._destroyed = false;
        windows.push(this);
        booted = true;
        var self = this;
        setTimeout(function () {
            app.emit('browser-window-created', preventable(), self);
            app.emit('web-contents-created', preventable(), self.webContents);
            self.emit('ready-to-show');
            mainWebContents.emit('dom-ready');
            mainWebContents.emit('did-finish-load');
        }, 0);
    }
    BrowserWindow.prototype = Object.create(EventEmitter.prototype);
    BrowserWindow.prototype.constructor = BrowserWindow;
    var bw = BrowserWindow.prototype;
    bw.loadURL = function () { return Promise.resolve(); };
    bw.loadFile = function () { return Promise.resolve(); };
    bw.isDestroyed = function () { return this._destroyed; };
    bw.isFullScreen = function () { return this._fullScreen; };
    bw.setFullScreen = function (on) {
        on = !!on;
        if (on === this._fullScreen) return;
        this._fullScreen = on;
        this.emit(on ? 'enter-full-screen' : 'leave-full-screen');
    };
    bw.isMinimized = function () { return false; };
    bw.isMaximized = function () { return !this._fullScreen; };
    bw.isVisible = function () { return true; };
    bw.isFocused = function () { return true; };
    bw.isResizable = function () { return false; };
    bw.getBounds = viewportBounds;
    bw.getContentBounds = viewportBounds;
    bw.getNormalBounds = viewportBounds;
    bw.getSize = function () { var b = viewportBounds(); return [b.width, b.height]; };
    bw.getContentSize = bw.getSize;
    bw.getPosition = function () { return [0, 0]; };
    bw.getTitle = function () { return document.title; };
    bw.close = function () {
        if (this._destroyed) return;
        var ev = preventable();
        this.emit('close', ev);
        if (ev.defaultPrevented) return;
        this.destroy();
    };
    bw.destroy = function () {
        if (this._destroyed) return;
        this._destroyed = true;
        this.emit('closed');
        if (windows.every(function (w) { return w._destroyed; })) app.emit('window-all-closed');
    };
    ['show', 'showInactive', 'hide', 'focus', 'blur', 'maximize', 'unmaximize', 'minimize', 'restore', 'center',
        'setBounds', 'setContentBounds', 'setSize', 'setContentSize', 'setMinimumSize', 'setMaximumSize',
        'setPosition', 'setTitle', 'setIcon', 'setMenu', 'removeMenu', 'setMenuBarVisibility',
        'setAutoHideMenuBar', 'setAspectRatio', 'setBackgroundColor', 'setAlwaysOnTop', 'setResizable',
        'setFullScreenable', 'setSkipTaskbar', 'flashFrame', 'setProgressBar', 'setOverlayIcon',
        'setIgnoreMouseEvents', 'setHasShadow', 'setOpacity'].forEach(function (m) {
        bw[m] = function () {};
    });
    BrowserWindow.getAllWindows = function () { return windows.filter(function (w) { return !w._destroyed; }); };
    BrowserWindow.getFocusedWindow = function () { return BrowserWindow.getAllWindows()[0] || null; };
    BrowserWindow.fromWebContents = BrowserWindow.getFocusedWindow;
    BrowserWindow.fromId = function (id) { return windows[id - 1] || null; };

    var protocolHandlers = {};
    var protocol = tolerant('protocol', {
        // the host serves the page; main's handler is only recorded.
        handle: function (scheme, fn) { protocolHandlers[scheme] = fn; },
        unhandle: function (scheme) { delete protocolHandlers[scheme]; },
        isProtocolHandled: function (scheme) { return !!protocolHandlers[scheme]; },
        registerSchemesAsPrivileged: function () {},
        registerFileProtocol: function () { return true; },
        registerBufferProtocol: function () { return true; },
        registerStringProtocol: function () { return true; },
        registerHttpProtocol: function () { return true; },
        interceptFileProtocol: function () { return true; },
        interceptBufferProtocol: function () { return true; },
        interceptStringProtocol: function () { return true; },
        interceptHttpProtocol: function () { return true; },
    });

    // main's requests come from the page, so the server's cookies land in the jar the page uses.
    function netFetch(input, init) {
        var opts = {};
        if (init) for (var k in init) if (k !== 'bypassCustomProtocolHandlers') opts[k] = init[k];
        return window.fetch(input, opts);
    }

    var CB = (typeof __gnElectronMainBridge !== 'undefined') ? __gnElectronMainBridge : null;
    function cookieUrl(filter) {
        return String((filter && filter.url) || location.origin);
    }
    var cookies = emitter();
    cookies.get = function (filter) {
        var list = [];
        try {
            list = JSON.parse((CB && CB.getCookies(cookieUrl(filter))) || '[]');
        } catch (e) { list = []; }
        if (filter && filter.name != null) list = list.filter(function (c) { return c.name === filter.name; });
        return Promise.resolve(list);
    };
    cookies.set = function (details) {
        try { if (CB) CB.setCookie(cookieUrl(details), JSON.stringify(details || {})); } catch (e) { /* swallow */ }
        return Promise.resolve();
    };
    cookies.remove = function (url, name) {
        try { if (CB) CB.removeCookie(String(url), String(name)); } catch (e) { /* swallow */ }
        return Promise.resolve();
    };
    cookies.flushStore = function () {
        try { if (CB) CB.flushCookies(); } catch (e) { /* swallow */ }
        return Promise.resolve();
    };

    var defaultSession = tolerant('session', {
        fetch: netFetch,
        cookies: cookies,
        protocol: protocol,
        webRequest: tolerant('session.webRequest', {}),
        setPermissionRequestHandler: function () {},
        setPermissionCheckHandler: function () {},
        setDevicePermissionHandler: function () {},
        setDisplayMediaRequestHandler: function () {},
        setSpellCheckerEnabled: function () {},
        setUserAgent: function () {},
        getUserAgent: function () { return navigator.userAgent; },
        setProxy: function () { return Promise.resolve(); },
        // the page's storage is the game's save; nothing in main may wipe it here.
        clearCache: function () { return Promise.resolve(); },
        clearStorageData: function () { return Promise.resolve(); },
        clearHostResolverCache: function () { return Promise.resolve(); },
        flushStorageData: function () {},
        on: function () {},
        once: function () {},
    });

    var dialog = tolerant('dialog', {
        showMessageBox: function (_w, o) {
            mark({ marker: 'MAIN_DIALOG', message: String(((o || _w) || {}).message || '') });
            return Promise.resolve({ response: 0, checkboxChecked: false });
        },
        showMessageBoxSync: function () { return 0; },
        showErrorBox: function (title, content) { mark({ marker: 'MAIN_DIALOG', message: String(title) + ': ' + String(content) }); },
        showOpenDialog: function () { return Promise.resolve({ canceled: true, filePaths: [] }); },
        showOpenDialogSync: function () { return undefined; },
        showSaveDialog: function () { return Promise.resolve({ canceled: true }); },
        showSaveDialogSync: function () { return undefined; },
    });

    var nativeTheme = emitter();
    nativeTheme.shouldUseDarkColors = false;
    nativeTheme.shouldUseHighContrastColors = false;
    nativeTheme.shouldUseInvertedColorScheme = false;
    nativeTheme.themeSource = 'system';

    function emptyImage() {
        return tolerant('nativeImage', {
            isEmpty: function () { return true; },
            getSize: function () { return { width: 0, height: 0 }; },
            toPNG: function () { return new Uint8Array(0); },
            toDataURL: function () { return ''; },
            resize: function () { return emptyImage(); },
        });
    }

    function Ctor(name) {
        return function () { noteNotImplemented(name + ' (construct)'); };
    }
    var Menu = Ctor('Menu');
    Menu.setApplicationMenu = function () {};
    Menu.getApplicationMenu = function () { return null; };
    Menu.buildFromTemplate = function () { return tolerant('Menu', { items: [], popup: function () {} }); };

    var mainElectron = tolerant('electron', {
        app: tolerant('app', app),
        ipcMain: ipcMain,
        BrowserWindow: BrowserWindow,
        webContents: tolerant('webContents', {
            getAllWebContents: function () { return [mainWebContentsProxy]; },
            getFocusedWebContents: function () { return mainWebContentsProxy; },
            fromId: function () { return mainWebContentsProxy; },
        }),
        session: tolerant('session', { defaultSession: defaultSession, fromPartition: function () { return defaultSession; } }),
        protocol: protocol,
        net: tolerant('net', {
            fetch: netFetch,
            isOnline: function () { return navigator.onLine !== false; },
            get online() { return navigator.onLine !== false; },
        }),
        screen: screen,
        shell: tolerant('shell', {
            openExternal: function (url) { mark({ marker: 'MAIN_OPEN_EXTERNAL', url: String(url) }); return Promise.resolve(); },
            openPath: function () { return Promise.resolve(''); },
            showItemInFolder: function () {},
            beep: function () {},
        }),
        dialog: dialog,
        Menu: Menu,
        MenuItem: Ctor('MenuItem'),
        Tray: Ctor('Tray'),
        Notification: Ctor('Notification'),
        nativeTheme: nativeTheme,
        nativeImage: tolerant('nativeImage', {
            createEmpty: emptyImage,
            createFromPath: emptyImage,
            createFromBuffer: emptyImage,
            createFromDataURL: emptyImage,
        }),
        globalShortcut: tolerant('globalShortcut', {
            register: function () { return false; },
            isRegistered: function () { return false; },
            unregister: function () {},
            unregisterAll: function () {},
        }),
        powerSaveBlocker: tolerant('powerSaveBlocker', {
            start: function () { return 1; },
            stop: function () {},
            isStarted: function () { return false; },
        }),
        powerMonitor: emitter(),
        crashReporter: tolerant('crashReporter', { start: function () {} }),
        clipboard: tolerant('clipboard', {
            writeText: function (t) { try { navigator.clipboard.writeText(String(t)); } catch (e) { /* swallow */ } },
            readText: function () { return ''; },
        }),
    });

    // ---------- main side: node ----------

    function socketFailing(path) {
        var s = emitter();
        s.write = function () { return false; };
        s.end = function () {};
        s.destroy = function () {};
        s.setEncoding = function () {};
        s.setNoDelay = function () {};
        s.setKeepAlive = function () {};
        s.setTimeout = function () {};
        s.ref = function () { return s; };
        s.unref = function () { return s; };
        // nothing to connect to here; node reports that asynchronously.
        setTimeout(function () {
            var err = new Error('connect ENOENT ' + String(path));
            err.code = 'ENOENT';
            s.emit('error', err);
            s.emit('close', true);
        }, 0);
        return s;
    }

    var netModule = {
        connect: function (path) { return socketFailing(path && path.path ? path.path : path); },
        createConnection: function (path) { return socketFailing(path && path.path ? path.path : path); },
        createServer: function () {
            var srv = emitter();
            srv.listen = function () {
                setTimeout(function () {
                    var err = new Error('listen EACCES');
                    err.code = 'EACCES';
                    srv.emit('error', err);
                }, 0);
                return srv;
            };
            srv.close = function (cb) { if (typeof cb === 'function') setTimeout(cb, 0); return srv; };
            return srv;
        },
        isIP: function () { return 0; },
    };

    var urlModule = {
        URL: window.URL,
        URLSearchParams: window.URLSearchParams,
        pathToFileURL: function (p) { return new URL('file:///' + String(p).replace(/\\/g, '/').replace(/^\/+/, '')); },
        fileURLToPath: function (u) { return decodeURIComponent(new URL(String(u)).pathname).replace(/^\/([A-Za-z]:)/, '$1'); },
        parse: function (u) { return new URL(String(u), location.href); },
        format: function (u) { return String(u); },
    };

    function extend(baseModule, extra) {
        var out = Object.create(baseModule || null);
        for (var k in extra) out[k] = extra[k];
        return out;
    }

    function cryptoModule() {
        var c = null;
        try { c = pageRequire('crypto'); } catch (e) { /* none */ }
        return extend(c, {
            randomUUID: function () { return crypto.randomUUID(); },
            randomBytes: function (n) {
                var b = new Uint8Array(n | 0);
                crypto.getRandomValues(b);
                return (window.Buffer && window.Buffer.from) ? window.Buffer.from(b) : b;
            },
            getRandomValues: function (a) { return crypto.getRandomValues(a); },
        });
    }

    function osModule() {
        var o = null;
        try { o = pageRequire('os'); } catch (e) { /* none */ }
        var mem = (navigator.deviceMemory || 4) * 1024 * 1024 * 1024;
        return extend(o, {
            totalmem: function () { return mem; },
            freemem: function () { return mem / 2; },
            uptime: function () { return performance.now() / 1000; },
            loadavg: function () { return [0, 0, 0]; },
            networkInterfaces: function () { return {}; },
            userInfo: function () { return { username: 'xuser', homedir: (o && o.homedir) ? o.homedir() : '' }; },
        });
    }

    var builtins = null;
    function mainRequire(id) {
        var name = String(id).replace(/^node:/, '');
        if (!builtins) {
            builtins = {
                electron: mainElectron,
                net: netModule,
                url: urlModule,
                crypto: cryptoModule(),
                os: osModule(),
            };
            if (window.__gnSteamworksJs) builtins['steamworks.js'] = tolerant('steamworks.js', window.__gnSteamworksJs);
        }
        if (Object.prototype.hasOwnProperty.call(builtins, name)) return builtins[name];
        var m = pageRequire(name);
        if (m === undefined) {
            var err = new Error("Cannot find module '" + id + "'");
            err.code = 'MODULE_NOT_FOUND';
            throw err;
        }
        return m;
    }
    mainRequire.resolve = function (id) { return String(id); };
    mainRequire.cache = {};

    var started = Date.now();
    var mainProcess = emitter();
    var pageProcess = window.process || {};
    // the host's real platform: main must not pick a desktop window path it would drive natively.
    mainProcess.platform = 'android';
    mainProcess.arch = 'arm64';
    mainProcess.type = 'browser';
    mainProcess.env = (function () {
        var env = {};
        var src = pageProcess.env || {};
        for (var k in src) env[k] = src[k];
        return env;
    })();
    mainProcess.resourcesPath = 'resources';
    mainProcess.execPath = (appName || 'electron') + '.exe';
    mainProcess.argv = [mainProcess.execPath];
    mainProcess.execArgv = [];
    mainProcess.pid = 1;
    mainProcess.ppid = 0;
    mainProcess.version = '';
    mainProcess.versions = (function () {
        var m = /Chrome\/([\d.]+)/.exec(navigator.userAgent);
        return { node: '', electron: '', chrome: m ? m[1] : '', v8: '' };
    })();
    mainProcess.hrtime = function (prev) {
        var t = performance.now();
        var s = Math.floor(t / 1000);
        var ns = Math.floor((t % 1000) * 1e6);
        if (prev) {
            s -= prev[0];
            ns -= prev[1];
            if (ns < 0) { s--; ns += 1e9; }
        }
        return [s, ns];
    };
    mainProcess.hrtime.bigint = function () { return BigInt(Math.floor(performance.now() * 1e6)); };
    mainProcess.uptime = function () { return (Date.now() - started) / 1000; };
    mainProcess.cwd = function () { return '/'; };
    mainProcess.chdir = function () {};
    mainProcess.umask = function () { return 0; };
    mainProcess.getSystemVersion = function () { return ''; };
    mainProcess.memoryUsage = function () { return { rss: 0, heapTotal: 0, heapUsed: 0, external: 0 }; };
    mainProcess.getProcessMemoryInfo = function () { return Promise.resolve({}); };
    mainProcess.nextTick = function (fn) {
        var args = slice.call(arguments, 1);
        queueMicrotask(function () { fn.apply(null, args); });
    };
    mainProcess.emitWarning = function () {};
    mainProcess.kill = function () { return false; };
    mainProcess.exit = function (code) { app.exit(code); };
    mainProcess.stdout = { write: function () { return true; }, isTTY: false };
    mainProcess.stderr = { write: function () { return true; }, isTTY: false };

    // ---------- run ----------

    window.__gnElectronMain = {
        run: function (fn, filename) {
            if (ran) return;
            ran = true;
            var file = String(filename || 'main.js');
            var module = { exports: {}, id: '.', filename: file, loaded: false, children: [], paths: [] };
            mainRequire.main = module;
            var dir = mainProcess.resourcesPath + '/app.asar/' + file.replace(/\/?[^/]*$/, '');
            active = true;
            try {
                fn.call(module.exports, mainRequire, module, module.exports,
                    mainProcess.resourcesPath + '/app.asar/' + file, dir, mainProcess, window.Buffer);
                module.loaded = true;
            } catch (e) {
                active = false;
                mark({ marker: 'MAIN_STANDIN_FAILED', stage: 'load', error: String(e && e.stack || e).slice(0, 600) });
                return;
            }
            queueMicrotask(function () {
                if (!active) return;
                isReady = true;
                try {
                    app.emit('will-finish-launching');
                    app.emit('ready', preventable(), {});
                } catch (e) {
                    failStart('ready', e);
                    if (!active) return;
                }
                readyResolve();
                setTimeout(function () {
                    if (active && !booted) mark({ marker: 'MAIN_STANDIN_NO_WINDOW' });
                }, 0);
            });
            mark({ marker: 'MAIN_STANDIN_READY', handlers: Object.keys(handlers).length });
        },
        // devtools/diagnostics.
        state: function () {
            return { active: active, ran: ran, booted: booted, handlers: Object.keys(handlers) };
        },
    };

    document.addEventListener('DOMContentLoaded', function () {
        if (!ran) mark({ marker: 'MAIN_STANDIN_FAILED', stage: 'not-run' });
    }, { once: true });
})();
