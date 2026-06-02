package app.gamenative.html5.host

import java.io.ByteArrayInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pure-JVM — no Android deps. tests optional __gnElectronCtx injection.
class IndexHtmlRewriterElectronCtxTest {

    private fun inject(
        html: String,
        shims: List<String> = emptyList(),
        locale: String? = null,
        ctx: Map<String, String>? = null,
    ): String = IndexHtmlRewriter.inject(
        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        shims,
        locale,
        ctx,
    ).readBytes().toString(Charsets.UTF_8)

    @Test
    fun electronCtx_null_producesByteIdenticalWithPrePhase61Output() {
        // regression guard: absent ctx must not change ANY other byte of output.
        val html = "<html><head></head><body><script>var g=1;</script></body></html>"
        val out = inject(html, shims = listOf("/_shims/a.js"), locale = null, ctx = null)
        assertFalse("must not inject __gnElectronCtx when ctx=null", out.contains("__gnElectronCtx"))
    }

    @Test
    fun electronCtx_nonNull_injectsBeforeShimScripts() {
        val ctx = linkedMapOf(
            "productName" to "Wayward",
            "userData" to "/tmp/wayward",
        )
        val html = "<html><script>var g=1;</script></html>"
        val out = inject(html, shims = listOf("/_shims/packs/electron.js"), ctx = ctx)
        val ctxIdx = out.indexOf("__gnElectronCtx")
        val shimIdx = out.indexOf("/_shims/packs/electron.js")
        val gameIdx = out.indexOf("var g=1;")
        assertTrue("ctx before shim: ctxIdx=$ctxIdx shimIdx=$shimIdx", ctxIdx in 0 until shimIdx)
        assertTrue("shim before game: shimIdx=$shimIdx gameIdx=$gameIdx", shimIdx in 0 until gameIdx)
    }

    @Test
    fun electronCtx_escapesValuesSafely() {
        // JSONObject.quote guards against DOM escape (same pattern as buildLocaleScript).
        val ctx = mapOf(
            "productName" to "Evil\"</script><script>alert(1)//",
            "userData" to "/ok",
        )
        val html = "<html><script>var g=1;</script></html>"
        val out = inject(html, shims = emptyList(), ctx = ctx)
        // attacker's </script> must be JSON-escaped; the literal `\"` sequence keeps brace structure intact.
        assertFalse(
            "unescaped </script> leaks out — XSS possible",
            out.contains("productName\":\"Evil\"</script>"),
        )
    }

    @Test
    fun electronCtx_emitsAllSixKeysWhenProvided() {
        val ctx = mapOf(
            "productName" to "A",
            "userData" to "/a/u",
            "appData" to "/a/a",
            "documents" to "/a/d",
            "temp" to "/a/t",
            "home" to "/a/h",
        )
        val html = "<html><script>var g=1;</script></html>"
        val out = inject(html, shims = emptyList(), ctx = ctx)
        assertTrue(out.contains("\"productName\":\"A\""))
        assertTrue(out.contains("\"userData\":\"\\/a\\/u\"") || out.contains("\"userData\":\"/a/u\""))
        assertTrue(out.contains("\"appData\":\"\\/a\\/a\"") || out.contains("\"appData\":\"/a/a\""))
        assertTrue(out.contains("\"documents\":\"\\/a\\/d\"") || out.contains("\"documents\":\"/a/d\""))
        assertTrue(out.contains("\"temp\":\"\\/a\\/t\"") || out.contains("\"temp\":\"/a/t\""))
        assertTrue(out.contains("\"home\":\"\\/a\\/h\"") || out.contains("\"home\":\"/a/h\""))
    }

    @Test
    fun electronCtx_withLocaleAndNoShims_stillInjectsInHead() {
        // <head> synthesis branch (no <script> anchor + locale set): ctx must appear in head too.
        val ctx = mapOf("productName" to "A")
        val html = "<html></html>"  // no <script>
        val out = inject(html, shims = emptyList(), locale = "en-US", ctx = ctx)
        assertTrue("ctx in head section: $out", out.contains("__gnElectronCtx"))
    }

    @Test
    fun electronCtx_orderingLock_localeBeforeCtxBeforeShim() {
        val ctx = mapOf("productName" to "X")
        val html = "<html><script>var g=1;</script></html>"
        val out = inject(html, shims = listOf("/_shims/packs/electron.js"), locale = "en-US", ctx = ctx)
        val localeIdx = out.indexOf("navigator,'language'")
        val ctxIdx = out.indexOf("__gnElectronCtx")
        val shimIdx = out.indexOf("/_shims/packs/electron.js")
        assertTrue("locale < ctx < shim — got $localeIdx, $ctxIdx, $shimIdx", localeIdx in 0 until ctxIdx && ctxIdx < shimIdx)
    }
}
