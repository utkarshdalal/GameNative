package app.gamenative.html5.shim

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// EXECUTES fs.js under Rhino against a recording __gnFsBridge mock. fs path routing is the single
// most regression-prone shim per project history -- every save round-trip rides it. these lock
// the bridgeRel transforms (leading-/ strip, backslash normalize, embedded file:// strip), the
// base64 binary round-trip, the Buffer-like read shape, and the user-data-vs-asset XHR decision.
// previously only source-string locked.
class ShimFsExecTest {
    private lateinit var js: ShimJsRuntime

    @Before
    fun setUp() {
        js = ShimJsRuntime().installProxyShim().installBase64().installXhr()
        // recording bridge: store writes by (already-transformed) path so reads round-trip;
        // record every call so path transforms are observable.
        js.eval(
            """
            window.__fs = { calls: [], store: {} };
            window.__gnFsBridge = {
                writeFile: function (p, data, enc) {
                    window.__fs.calls.push('writeFile:' + p + ':' + enc);
                    window.__fs.store[p] = { data: data, enc: enc }; return true;
                },
                readFile: function (p, enc) {
                    var e = window.__fs.store[p]; if (!e) return null;
                    return e.enc === enc ? e.data : null;
                },
                exists: function (p) { window.__fs.calls.push('exists:' + p); return !!window.__fs.store[p]; },
                unlink: function (p) { window.__fs.calls.push('unlink:' + p); var h = !!window.__fs.store[p]; delete window.__fs.store[p]; return h; },
                stat: function (p) { window.__fs.calls.push('stat:' + p); return JSON.stringify({ size: 5, mtimeMs: 42, isFile: true, isDirectory: false }); },
                mkdir: function (p, rec) { window.__fs.calls.push('mkdir:' + p + ':' + rec); return true; },
                readdir: function (p) { window.__fs.calls.push('readdir:' + p); return JSON.stringify(['a.sav', 'b.sav']); },
                rename: function (o, n) { window.__fs.calls.push('rename:' + o + '->' + n); return true; },
                appendFile: function (p, data, enc) { window.__fs.calls.push('appendFile:' + p + ':' + enc); return true; },
            };
            """.trimIndent(),
        )
        js.load("require-dispatcher.js").load("fs.js")
        js.eval("var fs = window.require('fs');")
    }

    @After
    fun tearDown() {
        js.close()
    }

    private fun lastCall(): String =
        js.evalString("window.__fs.calls[window.__fs.calls.length - 1]")

    @Test
    fun write_userdata_absolute_strips_leading_slash() {
        // NW.js-style /.local/<vendor>/... save path must land inside the bridge sandbox.
        js.eval("fs.writeFileSync('/.local/Vendor/save.json', '{}');")
        assertEquals("writeFile:.local/Vendor/save.json:utf8", lastCall())
    }

    @Test
    fun write_windows_path_passes_through_unchanged() {
        js.eval("fs.writeFileSync('C:/users/x/save', 'd');")
        assertEquals("writeFile:C:/users/x/save:utf8", lastCall())
    }

    @Test
    fun write_backslashes_normalized_to_forward() {
        // build the path with literal backslashes via fromCharCode to avoid Kotlin/JS escaping.
        js.eval("fs.writeFileSync('data' + String.fromCharCode(92) + 'os' + String.fromCharCode(92) + 'x', 'd');")
        assertEquals("writeFile:data/os/x:utf8", lastCall())
    }

    @Test
    fun write_strips_embedded_file_scheme() {
        js.eval("fs.writeFileSync('foo/file://data/x', 'd');")
        assertEquals("writeFile:/data/x:utf8", lastCall())
    }

    @Test
    fun binary_write_uses_base64_and_round_trips() {
        js.eval("fs.writeFileSync('/.local/b.bin', new Uint8Array([1, 2, 3, 255]));")
        assertEquals("writeFile:.local/b.bin:base64", lastCall())
        // read back without encoding -> Buffer-like; bytes must survive the base64 round-trip.
        // (assert via toString/slice/length -- numeric buf[i] is a get-trap, not on the target.)
        js.eval("var buf = fs.readFileSync('/.local/b.bin');")
        assertEquals("4", js.evalString("String(buf.length)"))
        assertEquals("010203ff", js.evalString("buf.toString('hex')"))
        assertEquals("0203", js.evalString("buf.slice(1, 3).toString('hex')"))
    }

    @Test
    fun utf8_write_read_round_trips() {
        js.eval("fs.writeFileSync('/.local/s.txt', 'hello world');")
        assertEquals("hello world", js.evalString("fs.readFileSync('/.local/s.txt', 'utf8')"))
    }

    @Test
    fun stat_sync_exposes_node_stats_shape() {
        val r = js.evalString(
            "(function () { var st = fs.statSync('/.local/x'); return st.size + ',' + st.isFile() + ',' + st.isDirectory(); })()",
        )
        assertEquals("5,true,false", r)
    }

    @Test
    fun readdir_userdata_absolute_goes_to_bridge_not_xhr() {
        js.eval("var d = fs.readdirSync('/.local/conf');")
        assertEquals("2", js.evalString("String(d.length)"))
        assertEquals("a.sav", js.evalString("d[0]"))
        assertEquals("readdir:.local/conf", lastCall())
        // user-data absolutes are bridge-authoritative -- the asar listdir XHR must NOT fire.
        assertFalse(js.evalBoolean("window.__xhr.opens.join('|').indexOf('_asar_listdir') >= 0"))
    }

    @Test
    fun read_asset_absolute_falls_through_to_xhr() {
        // bridge miss on a non-user-data absolute -> sync XHR against the asset interceptor.
        js.eval("window.__xhr.responder = function (m, u) { return { status: 200, body: 'ASSET' }; };")
        assertEquals("ASSET", js.evalString("fs.readFileSync('/img/x.json', 'utf8')"))
        assertTrue(js.evalBoolean("window.__xhr.opens.join('|').indexOf('GET /img/x.json') >= 0"))
    }

    @Test
    fun read_userdata_absolute_miss_throws_without_xhr() {
        val threw = js.evalBoolean(
            "(function () { try { fs.readFileSync('/.local/missing', 'utf8'); return false; } catch (e) { return true; } })()",
        )
        assertTrue(threw)
        // the bridge is authoritative for /.local/... -- no console-noisy asset XHR on miss.
        assertFalse(js.evalBoolean("window.__xhr.opens.join('|').indexOf('/.local/missing') >= 0"))
    }

    @Test
    fun mkdir_and_rename_route_through_bridge() {
        js.eval("fs.mkdirSync('/.local/d', true);")
        assertEquals("mkdir:.local/d:true", lastCall())
        js.eval("fs.renameSync('/.local/a', '/.local/b');")
        assertEquals("rename:.local/a->.local/b", lastCall())
    }
}
