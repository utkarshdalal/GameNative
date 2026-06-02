package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// EXECUTES steamworks.js under Rhino. c3's steam plugin wraps this export in a promise --
// `_OnSaveFileToCloud(a){return new Promise((b,c)=>{this._greenworks.saveFilesToCloud(a.file_path,b,c)})}`
// -- so an export that never calls either callback leaves the event sheet waiting forever. every
// case here asserts that exactly one callback fired.
class SteamworksSaveFilesToCloudExecTest {

    private fun runtime(files: Map<String, String>): ShimJsRuntime {
        val fsEntries = files.entries.joinToString(",") { (path, body) ->
            "'${path.replace("'", "\\'")}': '${body.replace("'", "\\'")}'"
        }
        val js = ShimJsRuntime().installBase64().installProxyShim()
        js.eval(
            """
            window.localStorage = {
                _m: {},
                setItem: function (k, v) { this._m[k] = String(v); },
                getItem: function (k) { return Object.prototype.hasOwnProperty.call(this._m, k) ? this._m[k] : null; },
                removeItem: function (k) { delete this._m[k]; },
                key: function (i) { return Object.keys(this._m)[i] || null; },
                get length() { return Object.keys(this._m).length; },
            };
            var __gnObserved = 0;
            var __gnStaged = {};
            var __gnSteamworksBridge = {
                getInboundCloudJson: function () { return '{}'; },
                markGreenworksCloudObserved: function () { __gnObserved++; },
                // host side keeps raw bytes; record the base64 the shim hands over.
                stageCloudFile: function (name, b64) { __gnStaged[name] = b64; },
            };
            // stand-in for shims/fs.js: no encoding -> its Buffer wrapper (__isGnBuffer/_bytes),
            // which is what saveFilesToCloud asks for so bytes survive.
            var __gnDisk = { $fsEntries };
            var __gnFsModule = {
                readFileSync: function (p, enc) {
                    if (!Object.prototype.hasOwnProperty.call(__gnDisk, p)) {
                        throw new Error('ENOENT: readFileSync ' + p);
                    }
                    var str = __gnDisk[p];
                    var bytes = [];
                    for (var i = 0; i < str.length; i++) bytes.push(str.charCodeAt(i) & 0xff);
                    return {
                        __isGnBuffer: true,
                        length: bytes.length,
                        _bytes: bytes,
                        // mirrors fs.js wrapBytes: toString('base64') over the raw bytes.
                        toString: function (enc) {
                            if (enc !== 'base64') throw new Error('fake buffer: only base64');
                            var s = '';
                            for (var j = 0; j < bytes.length; j++) s += String.fromCharCode(bytes[j]);
                            return btoa(s);
                        },
                    };
                },
            };
            var __gnOk = 0, __gnErr = [];
            var __gnDone = function () { __gnOk++; };
            var __gnFail = function (e) { __gnErr.push(String(e && e.message ? e.message : e)); };
            """.trimIndent(),
        )
        js.load("require-dispatcher.js").load("steamworks.js")
        // register after the dispatcher exists so `require('fs')` inside the shim resolves.
        js.eval("window.require.register('fs', __gnFsModule);")
        return js
    }

    @Test
    fun readsEachPathOffDiskAndStagesItUnderItsBasename() {
        runtime(mapOf("/save/dir/slot1.json" to "{\"hp\":10}")).use { js ->
            js.eval("window.greenworks.saveFilesToCloud(['/save/dir/slot1.json'], __gnDone, __gnFail);")

            assertEquals("success callback fired exactly once", "1", js.evalString("String(__gnOk)"))
            assertEquals("no error callback", "0", js.evalString("String(__gnErr.length)"))
            assertEquals(
                "handed to the host as base64 of the file's bytes, keyed by basename",
                java.util.Base64.getEncoder().encodeToString("{\"hp\":10}".toByteArray()),
                js.evalString("__gnStaged['slot1.json']"),
            )
            assertEquals(
                "file bytes never enter the text namespace",
                "null",
                js.evalString("String(window.localStorage.getItem('gn:gw:slot1.json'))"),
            )
        }
    }

    @Test
    fun acceptsABarePathBecauseThatIsWhatC3Passes() {
        // c3 hands `a.file_path`, a single string -- not the array real greenworks documents.
        runtime(mapOf("slot0.dat" to "payload")).use { js ->
            js.eval("window.greenworks.saveFilesToCloud('slot0.dat', __gnDone, __gnFail);")

            assertEquals("1", js.evalString("String(__gnOk)"))
            assertEquals(
                java.util.Base64.getEncoder().encodeToString("payload".toByteArray()),
                js.evalString("__gnStaged['slot0.dat']"),
            )
        }
    }

    @Test
    fun keepsWhatSaveTextToFileAlreadyWroteWithoutTouchingDisk() {
        // the name is already in the cloud namespace, so there is nothing on disk to read and
        // the existing bytes must survive.
        runtime(emptyMap()).use { js ->
            js.eval("window.greenworks.saveTextToFile('notes.txt', 'from-the-game', __gnDone, __gnFail);")
            js.eval("window.greenworks.saveFilesToCloud(['notes.txt'], __gnDone, __gnFail);")

            assertEquals("both calls succeeded", "2", js.evalString("String(__gnOk)"))
            assertEquals("0", js.evalString("String(__gnErr.length)"))
            assertEquals("from-the-game", js.evalString("window.localStorage.getItem('gn:gw:notes.txt')"))
        }
    }

    @Test
    fun rejectsInsteadOfHangingWhenTheFileIsNotReadable() {
        runtime(emptyMap()).use { js ->
            js.eval("window.greenworks.saveFilesToCloud(['/gone/missing.sav'], __gnDone, __gnFail);")

            assertEquals("success must NOT fire", "0", js.evalString("String(__gnOk)"))
            assertEquals("error callback fired once", "1", js.evalString("String(__gnErr.length)"))
            assertTrue(
                "error names the unreadable path; was " + js.evalString("__gnErr[0]"),
                js.evalString("__gnErr[0]").contains("missing.sav"),
            )
        }
    }

    @Test
    fun keepsEveryByteOfABinarySave() {
        // binary saves (this prefix is a real one) read as utf8 replace every invalid sequence
        // with U+FFFD, unrecoverably, so the staged value must be one char per byte instead.
        val bytes = byteArrayOf(0x00, 0xac.toByte(), 0x10, 0xd8.toByte(), 0xff.toByte(), 0x41)
        val onDisk = bytes.joinToString("") { ((it.toInt() and 0xff).toChar()).toString() }
        runtime(mapOf("/game/save0.dat" to onDisk)).use { js ->
            js.eval("window.greenworks.saveFilesToCloud('/game/save0.dat', __gnDone, __gnFail);")

            assertEquals("1", js.evalString("String(__gnOk)"))
            assertEquals(
                "cloud blob is the file's bytes, not a utf8 re-encoding of them",
                java.util.Base64.getEncoder().encodeToString(bytes),
                js.evalString("__gnStaged['save0.dat']"),
            )
            assertEquals(
                "nothing binary leaks into the text namespace",
                "null",
                js.evalString("String(window.localStorage.getItem('gn:gw:save0.dat'))"),
            )
        }
    }

    @Test
    fun emptyListStillSettles() {
        runtime(emptyMap()).use { js ->
            js.eval("window.greenworks.saveFilesToCloud([], __gnDone, __gnFail);")
            assertEquals("1", js.evalString("String(__gnOk)"))
            assertEquals("0", js.evalString("String(__gnErr.length)"))
        }
    }
}
