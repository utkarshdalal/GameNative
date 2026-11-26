package app.gamenative.html5.shim

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// EXECUTES electron-main.js under Rhino with a synthetic main script: the renderer's ipcRenderer reaches main's
// ipcMain handlers, main's webContents.send reaches renderer listeners, app.quit runs will-quit before the runtime
// exit, and a main that fails to start leaves the plain ipcRenderer stub in charge.
class ShimElectronMainExecTest {

    private val js = ShimJsRuntime().installProxyShim()

    @After
    fun close() = js.close()

    private fun boot(mainBody: String) {
        js.eval(
            """
            var __q = [];
            var setTimeout = function (fn) { __q.push(fn); return __q.length; };
            var queueMicrotask = function (fn) { __q.push(fn); };
            var __drain = function () { var n = 0; while (__q.length && n++ < 1000) __q.shift()(); };
            var navigator = { userAgent: 'Mozilla/5.0 Chrome/124.0.0.0 Test', language: 'en-US', languages: ['en-US'], onLine: true };
            var location = { href: 'https://example.test/__game__/index.html', origin: 'https://example.test' };
            var document = { title: 't', addEventListener: function () {} };
            var performance = { now: function () { return 0; } };
            window.__listeners = {};
            window.addEventListener = function (t, fn) { (window.__listeners[t] = window.__listeners[t] || []).push(fn); };
            window.__gnElectronCtx = { productName: 'game', version: '1.2.3' };
            window.__exits = [];
            window.__gnRuntimeBridge = { exit: function (s) { window.__exits.push(s); } };
            """.trimIndent(),
        )
        js.load("require-dispatcher.js").load("events.js")
        js.eval(
            """
            window.__base = [];
            window.require.register('electron', {
                app: { getPath: function () { return 'C:/users/xuser/AppData/Roaming'; }, getAppPath: function () { return '.'; } },
                ipcRenderer: {
                    invoke: function (ch) { window.__base.push('invoke:' + ch); return Promise.resolve('base'); },
                    send: function (ch) { window.__base.push('send:' + ch); },
                    sendSync: function (ch) { window.__base.push('sendSync:' + ch); return 'base'; },
                    on: function () {},
                    removeListener: function () {},
                    removeAllListeners: function () {},
                },
            });
            """.trimIndent(),
        )
        js.load("electron-main.js")
        js.eval(
            """
            window.__heard = [];
            window.require('electron').ipcRenderer.on('game:hello', function (e, v) { window.__heard.push(v); });
            window.__gnElectronMain.run(function (require, module, exports, __filename, __dirname, process) {
            $mainBody
            }, 'out/main.js');
            __drain();
            """.trimIndent(),
        )
        // promise jobs (whenReady) run between evaluations.
        js.eval("__drain();")
        js.eval("__drain();")
    }

    private val gameMain = """
        var e = require('electron');
        var path = require('node:events');
        e.ipcMain.handle('game:add', function (ev, a, b) {
            if (ev.senderFrame.url.indexOf('https://example.test/__game__/') !== 0) throw new Error('untrusted sender');
            return a + b;
        });
        e.ipcMain.handle('game:fail', function () { throw new Error('nope'); });
        e.ipcMain.on('game:platform', function (ev) { ev.returnValue = process.platform; });
        e.ipcMain.on('game:exit', function () { e.app.quit(); });
        e.app.on('will-quit', function () { window.__willQuit = true; });
        e.app.whenReady().then(function () {
            var w = new e.BrowserWindow({ fullscreen: false });
            w.webContents.send('game:hello', 42);
        });
    """.trimIndent()

    private fun settle(expr: String): String {
        js.eval("window.__out = 'pending'; Promise.resolve($expr).then(function (v) { window.__out = 'ok:' + v; }, function (e) { window.__out = 'err:' + e.message; });")
        js.eval("__drain();")
        return js.evalString("window.__out")
    }

    @Test
    fun invokeReachesMainsHandlerWithTheRealPageAsSender() {
        boot(gameMain)
        assertEquals("ok:5", settle("window.require('electron').ipcRenderer.invoke('game:add', 2, 3)"))
    }

    @Test
    fun aThrowingHandlerRejectsLikeElectron() {
        boot(gameMain)
        assertEquals(
            "err:Error invoking remote method 'game:fail': Error: nope",
            settle("window.require('electron').ipcRenderer.invoke('game:fail')"),
        )
    }

    @Test
    fun channelsMainDoesNotHandleKeepThePlainStub() {
        boot(gameMain)
        assertEquals("ok:base", settle("window.require('electron').ipcRenderer.invoke('other:thing')"))
        assertEquals("invoke:other:thing", js.evalString("window.__base.join(',')"))
    }

    @Test
    fun sendSyncReturnsWhatMainSetsAndMainSeesTheHostPlatform() {
        boot(gameMain)
        assertEquals("android", js.evalString("window.require('electron').ipcRenderer.sendSync('game:platform')"))
    }

    @Test
    fun webContentsSendReachesRendererListeners() {
        boot(gameMain)
        assertEquals("42", js.evalString("window.__heard.join(',')"))
    }

    @Test
    fun quitRunsWillQuitThenExitsTheSession() {
        boot(gameMain)
        js.eval("window.require('electron').ipcRenderer.send('game:exit'); __drain();")
        assertTrue(js.evalBoolean("window.__willQuit === true"))
        assertEquals("electron.main.app.quit", js.evalString("window.__exits.join(',')"))
    }

    @Test
    fun aMainThatThrowsWhileLoadingLeavesThePlainStub() {
        boot(
            """
            require('electron').ipcMain.handle('game:add', function () { return 1; });
            throw new Error('boom');
            """.trimIndent(),
        )
        assertEquals("ok:base", settle("window.require('electron').ipcRenderer.invoke('game:add')"))
        assertFalse(js.evalBoolean("window.__gnElectronMain.state().active"))
    }

    @Test
    fun aQuitBeforeTheWindowOpensIsNotASessionExit() {
        boot(
            """
            var e = require('electron');
            e.ipcMain.handle('game:add', function () { return 1; });
            e.app.whenReady().then(function () { e.app.quit(); });
            """.trimIndent(),
        )
        assertEquals("", js.evalString("window.__exits.join(',')"))
        assertEquals("ok:base", settle("window.require('electron').ipcRenderer.invoke('game:add')"))
    }

    private fun assertFellBackToTheStub() {
        assertFalse(js.evalBoolean("window.__gnElectronMain.state().active"))
        assertEquals("ok:base", settle("window.require('electron').ipcRenderer.invoke('game:add')"))
    }

    @Test
    fun aMainThatThrowsInItsWhenReadyCallbackLeavesThePlainStub() {
        boot(
            """
            var e = require('electron');
            e.ipcMain.handle('game:add', function () { return 1; });
            e.app.whenReady().then(function () { throw new Error('boom'); });
            """.trimIndent(),
        )
        assertFellBackToTheStub()
    }

    @Test
    fun aThrowingReadyListenerLeavesThePlainStub() {
        boot(
            """
            var e = require('electron');
            e.ipcMain.handle('game:add', function () { return 1; });
            e.app.on('ready', function () { throw new Error('boom'); });
            """.trimIndent(),
        )
        assertFellBackToTheStub()
    }

    @Test
    fun aStartupChainThatRejectsBeforeTheWindowLeavesThePlainStub() {
        boot(
            """
            var e = require('electron');
            e.ipcMain.handle('game:add', function () { return 1; });
            e.app.whenReady().then(function () { return Promise.resolve().then(function () { throw new Error('later'); }); });
            """.trimIndent(),
        )
        js.eval("__drain();")
        assertFellBackToTheStub()
    }

    @Test
    fun aStrayRejectionInMainBeforeTheWindowKeepsMainInCharge() {
        boot(
            """
            var e = require('electron');
            e.ipcMain.handle('game:add', function () { return 7; });
            """.trimIndent(),
        )
        js.eval(
            """
            var err = new Error('stray');
            err.stack = 'Error: stray\n    at https://example.test/_gn_electron_main.js:3:9';
            (window.__listeners.unhandledrejection || []).forEach(function (fn) { fn({ reason: err }); });
            (window.__listeners.error || []).forEach(function (fn) { fn({ error: err, filename: 'https://example.test/_gn_electron_main.js' }); });
            """.trimIndent(),
        )
        assertTrue(js.evalBoolean("window.__gnElectronMain.state().active"))
        assertEquals("ok:7", settle("window.require('electron').ipcRenderer.invoke('game:add')"))
    }

    @Test
    fun anErrorAfterTheWindowOpensKeepsMainInCharge() {
        boot(
            """
            var e = require('electron');
            e.ipcMain.handle('game:add', function () { return 7; });
            e.app.whenReady().then(function () { new e.BrowserWindow({}); throw new Error('late'); });
            """.trimIndent(),
        )
        assertTrue(js.evalBoolean("window.__gnElectronMain.state().active"))
        assertEquals("ok:7", settle("window.require('electron').ipcRenderer.invoke('game:add')"))
    }
}
