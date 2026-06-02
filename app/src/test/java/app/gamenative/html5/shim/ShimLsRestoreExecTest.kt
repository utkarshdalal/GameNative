package app.gamenative.html5.shim

import app.gamenative.html5.savesync.LocalStorageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

// EXECUTES ls-restore.js under Rhino: the launch-time localStorage restore the page applies
// instead of the host writing chromium's live LS leveldb.
class ShimLsRestoreExecTest {
    private fun restoreJson(vararg kv: Pair<String, String>): String = LocalStorageSnapshot.toRestoreJson(
        kv.map { (k, v) -> LocalStorageSnapshot.encode(k.toCharArray()) to LocalStorageSnapshot.encode(v.toCharArray()) },
    )

    // bridge hands the JSON out once, like the host (second take = "").
    private fun runtime(json: String): ShimJsRuntime {
        val js = ShimJsRuntime().installBase64()
        js.eval(
            """
            window.localStorage = {
                _m: {},
                setItem: function (k, v) { this._m[k] = String(v); },
                getItem: function (k) { return Object.prototype.hasOwnProperty.call(this._m, k) ? this._m[k] : null; },
                removeItem: function (k) { delete this._m[k]; },
                clear: function () { this._m = {}; },
            };
            var __gnLsRestoreBridge = {
                taken: 0, appliedCalls: 0,
                take: function () { this.taken++; return this.taken === 1 ? '$json' : ''; },
                applied: function () { this.appliedCalls++; },
            };
            """.trimIndent(),
        )
        return js
    }

    @Test
    fun applies_restore_replacing_existing_keys_and_reports_applied() {
        runtime(restoreJson("k€" to "välue", "opts" to "{\"a\":1}")).use { js ->
            js.eval("window.localStorage.setItem('stale', 'x');")
            js.load("ls-restore.js")

            assertEquals("null", js.evalString("String(window.localStorage.getItem('stale'))"))
            assertEquals("välue", js.evalString("window.localStorage.getItem('k€')"))
            assertEquals("{\"a\":1}", js.evalString("window.localStorage.getItem('opts')"))
            assertEquals("1", js.evalString("String(__gnLsRestoreBridge.appliedCalls)"))
        }
    }

    @Test
    fun nothing_staged_leaves_storage_alone() {
        runtime("").use { js ->
            js.eval("__gnLsRestoreBridge.taken = 1; window.localStorage.setItem('keep', 'y');")
            js.load("ls-restore.js")

            assertEquals("y", js.evalString("window.localStorage.getItem('keep')"))
            assertEquals("0", js.evalString("String(__gnLsRestoreBridge.appliedCalls)"))
        }
    }

    // an in-game reload re-runs the shim; it must not put the launch-time values back over what the game saved.
    @Test
    fun reload_does_not_reapply() {
        runtime(restoreJson("slot" to "old")).use { js ->
            js.load("ls-restore.js")
            js.eval("window.localStorage.setItem('slot', 'new');")
            js.load("ls-restore.js")

            assertEquals("new", js.evalString("window.localStorage.getItem('slot')"))
            assertEquals("1", js.evalString("String(__gnLsRestoreBridge.appliedCalls)"))
        }
    }
}
