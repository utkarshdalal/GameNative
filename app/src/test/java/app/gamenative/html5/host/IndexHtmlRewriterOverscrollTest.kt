package app.gamenative.html5.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// covers overscrollFixStyle + gestureConfig parse-time
// snippet robolectric needed because TouchGestureConfig.toJson uses
// org.json.JSONObject. existing electronCtx test stays pure-jvm — IndexHtmlRewriter
// itself doesn't pull android in this code path.
@RunWith(RobolectricTestRunner::class)
class IndexHtmlRewriterOverscrollTest {

    private fun rewrite(
        html: String,
        shims: List<String> = listOf("/_shims/touch.js"),
        gestureConfigJson: String? = null,
    ): String {
        return IndexHtmlRewriter.inject(
            source = html.byteInputStream(Charsets.UTF_8),
            shimScriptUrls = shims,
            gestureConfigJson = gestureConfigJson,
        ).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test fun overscroll_style_is_emitted_unconditionally() {
        val out = rewrite("<html><body><script>game();</script></body></html>")
        assertTrue("overscroll style id missing: $out", out.contains("""<style id="__gnOverscrollFix">"""))
    }

    @Test fun overscroll_style_contains_pan_x_pan_y_and_overflow_hidden() {
        val out = rewrite("<html><body><script>game();</script></body></html>")
        assertTrue("touch-action: $out", out.contains("touch-action: pan-x pan-y !important"))
        assertTrue("overflow: $out", out.contains("overflow: hidden !important"))
        assertTrue("overscroll-behavior: $out", out.contains("overscroll-behavior: none !important"))
    }

    @Test fun gestureConfigJson_null_does_not_emit_window_assignment() {
        val out = rewrite("<html><body><script>game();</script></body></html>", gestureConfigJson = null)
        assertFalse(
            "__gnGestureConfig assignment should be absent when param null: $out",
            out.contains("__gnGestureConfig"),
        )
    }

    @Test fun gestureConfigJson_nonNull_emits_script_before_shim_scripts() {
        val cfgJson = """{"tapEnabled":true,"cursorMode":"absolute"}"""
        val out = rewrite(
            html = "<html><body><script>game();</script></body></html>",
            shims = listOf("/_shims/touch.js"),
            gestureConfigJson = cfgJson,
        )
        assertTrue("__gnGestureConfig snippet missing: $out", out.contains("window.__gnGestureConfig = $cfgJson"))
        // ordering: gestureConfig snippet must come BEFORE shim script tag.
        val cfgIdx = out.indexOf("__gnGestureConfig")
        val shimIdx = out.indexOf("/_shims/touch.js")
        assertTrue("gestureConfig must precede shim (cfgIdx=$cfgIdx shimIdx=$shimIdx)", cfgIdx in 0 until shimIdx)
    }

    @Test fun gestureConfigJson_real_data_class_json_round_trips_unchanged() {
        // mirrors WebViewScreen call shape — TouchGestureConfig().toJson() is the
        // exact serialized form. assert it appears verbatim in output (no escaping breakage).
        val cfg = app.gamenative.data.TouchGestureConfig()
        val cfgJson = cfg.toJson()
        val out = rewrite(
            html = "<html><body><script>game();</script></body></html>",
            gestureConfigJson = cfgJson,
        )
        assertTrue("toJson output not embedded verbatim: $out", out.contains("window.__gnGestureConfig = $cfgJson"))
    }
}
