package app.gamenative.runtime

import com.winlator.container.Container
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// robolectric needed — Container's static init reads Environment.getExternalStoragePublicDirectory
// at class load (L60 DEFAULT_DRIVES). pure-jvm junit fails in clinit before our tests run.
@RunWith(RobolectricTestRunner::class)
class ContainerRuntimeJsonTest {

    @Test
    fun missing_runtime_key_defaults_to_wine() {
        // mimics pre-html5 container json — no runtime field
        val container = Container("test-id")
        val json = JSONObject().apply {
            put("name", "legacy-container")
        }
        container.loadData(json)
        assertEquals(Container.RUNTIME_WINE, container.runtime)
    }

    @Test
    fun explicit_runtime_loads_from_json() {
        val container = Container("test-id")
        val json = JSONObject().apply {
            put("name", "html5-container")
            put("runtime", Container.RUNTIME_WEBVIEW)
        }
        container.loadData(json)
        assertEquals(Container.RUNTIME_WEBVIEW, container.runtime)
    }

    @Test
    fun setter_round_trips_value_via_getter() {
        val container = Container("test-id")
        container.setRuntime(Container.RUNTIME_WEBVIEW)
        assertEquals(Container.RUNTIME_WEBVIEW, container.runtime)
    }

    @Test
    fun setter_normalizes_null_or_empty_to_wine() {
        val container = Container("test-id")
        // empty normalizes to wine
        container.setRuntime("")
        assertEquals(Container.RUNTIME_WINE, container.runtime)
        // null also normalizes to wine
        container.setRuntime(null)
        assertEquals(Container.RUNTIME_WINE, container.runtime)
    }

    @Test
    fun setter_normalizes_mixed_case_and_whitespace() {
        val container = Container("test-id")
        // hand-edited JSON could contain uppercase or padded values — must canonicalize
        container.setRuntime("WEBVIEW")
        assertEquals(Container.RUNTIME_WEBVIEW, container.runtime)
        container.setRuntime(" Wine ")
        assertEquals(Container.RUNTIME_WINE, container.runtime)
        // unknown values fall back to wine (defense in depth for dispatch seam)
        container.setRuntime("bogus")
        assertEquals(Container.RUNTIME_WINE, container.runtime)
    }

    @Test
    fun loadData_normalizes_uppercase_runtime_from_json() {
        val container = Container("test-id")
        val json = JSONObject().apply {
            put("runtime", "WEBVIEW")
        }
        container.loadData(json)
        // loadData path must route hand-edited uppercase JSON through normalization too
        assertEquals(Container.RUNTIME_WEBVIEW, container.runtime)
    }
}
