package app.gamenative.html5.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IndexHtmlRewriterTest {

    private fun readFixture(path: String): String =
        javaClass.getResourceAsStream(path)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun rewrite(sourceText: String, shims: List<String>): String {
        val result = IndexHtmlRewriter.inject(sourceText.byteInputStream(Charsets.UTF_8), shims)
        return result.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun rewriteWithLocale(sourceText: String, shims: List<String>, locale: String?): String {
        val result = IndexHtmlRewriter.inject(sourceText.byteInputStream(Charsets.UTF_8), shims, locale)
        return result.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun termina_rmmv_fixture_injects_shim_before_first_game_script() {
        val source = readFixture("/html5/fixtures/termina/index.html")
        val rewritten = rewrite(source, listOf("/_shims/steamworks.js"))
        val shimTag = """<script src="/_shims/steamworks.js"></script>"""
        val shimIdx = rewritten.indexOf(shimTag)
        // stable landmark in rewritten output — canvasFixStyle block shifts absolute indices,
        // so compare against a substring uniquely present in the first game script.
        val firstGameScriptIdx = rewritten.indexOf("pixi.js")
        assertNotNull(rewritten)
        assertTrue("shim not found: $rewritten", shimIdx >= 0)
        assertTrue("first game script missing: $rewritten", firstGameScriptIdx >= 0)
        assertTrue("shim lands after first game script", shimIdx < firstGameScriptIdx)
    }

    @Test
    fun solcesto_c3_fixture_injects_shim_before_first_game_script() {
        val source = readFixture("/html5/fixtures/solcesto/index.html")
        val rewritten = rewrite(source, listOf("/_shims/steamworks.js"))
        val shimTag = """<script src="/_shims/steamworks.js"></script>"""
        val shimIdx = rewritten.indexOf(shimTag)
        val firstGameScriptIdx = rewritten.indexOf("c3runtime.js")
        assertTrue("shim not found: $rewritten", shimIdx >= 0)
        assertTrue("first game script missing: $rewritten", firstGameScriptIdx >= 0)
        assertTrue("shim lands after first game script", shimIdx < firstGameScriptIdx)
    }

    @Test
    fun uppercase_script_matches_case_insensitive() {
        val source = "<html><body><SCRIPT>foo</SCRIPT></body></html>"
        val rewritten = rewrite(source, listOf("/_shims/x.js"))
        val shimIdx = rewritten.indexOf("""<script src="/_shims/x.js"></script>""")
        val firstScriptIdx = rewritten.indexOf("<SCRIPT", ignoreCase = false)
        assertTrue("shim not injected: $rewritten", shimIdx >= 0)
        assertTrue("shim lands before uppercase <SCRIPT>", shimIdx < firstScriptIdx)
    }

    @Test
    fun multiple_shim_urls_injected_in_given_order() {
        val source = readFixture("/html5/fixtures/termina/index.html")
        val rewritten = rewrite(source, listOf("/_shims/a.js", "/_shims/b.js"))
        val aIdx = rewritten.indexOf("""<script src="/_shims/a.js"></script>""")
        val bIdx = rewritten.indexOf("""<script src="/_shims/b.js"></script>""")
        val firstGameScriptIdx = rewritten.indexOf("pixi.js")
        assertTrue("a missing", aIdx >= 0)
        assertTrue("b missing", bIdx >= 0)
        assertTrue("order: a before b", aIdx < bIdx)
        assertTrue("both before first game script", bIdx < firstGameScriptIdx)
    }

    @Test
    fun no_script_tag_is_fail_loud() {
        val source = "<html><body>Nothing</body></html>"
        try {
            rewrite(source, listOf("/_shims/x.js"))
            fail("expected exception on no-<script> input")
        } catch (e: IllegalStateException) {
            assertTrue("message: ${e.message}", e.message?.contains("no <script>") == true)
        }
    }

    @Test
    fun source_input_stream_is_not_mutated_on_disk() {
        // IndexHtmlRewriter.inject takes an InputStream (not File). Re-reading the
        // fixture after invocation must yield the original bytes.
        val before = readFixture("/html5/fixtures/termina/index.html")
        rewrite(before, listOf("/_shims/steamworks.js"))
        val after = readFixture("/html5/fixtures/termina/index.html")
        assertEquals(before, after)
    }

    @Test
    fun inline_first_script_also_receives_injection_before_it() {
        val source = "<html><head><script>inline</script></head><body>x</body></html>"
        val rewritten = rewrite(source, listOf("/_shims/x.js"))
        val shimIdx = rewritten.indexOf("""<script src="/_shims/x.js"></script>""")
        val inlineIdx = rewritten.indexOf("<script>inline")
        assertTrue("shim missing", shimIdx >= 0)
        assertTrue("inline still present", inlineIdx >= 0)
        assertTrue("shim before inline", shimIdx < inlineIdx)
    }

    //— locale injection coverage below.

    @Test
    fun locale_null_behaves_identically_to_existing_signature() {
        val source = readFixture("/html5/fixtures/termina/index.html")
        val withNull = rewriteWithLocale(source, listOf("/_shims/steamworks.js"), null)
        val withoutParam = rewrite(source, listOf("/_shims/steamworks.js"))
        assertEquals(withoutParam, withNull)
        // no locale script should appear.
        assertTrue("no navigator injection", !withNull.contains("navigator,'language'"))
    }

    @Test
    fun locale_en_us_injected_before_shims_and_game_scripts() {
        val source = "<html><head></head><body><script src=\"main.js\"></script></body></html>"
        val rewritten = rewriteWithLocale(source, listOf("/_shims/a.js"), "en-US")
        val localeIdx = rewritten.indexOf("navigator,'language'")
        val shimIdx = rewritten.indexOf("""<script src="/_shims/a.js">""")
        val gameIdx = rewritten.indexOf("""<script src="main.js">""")
        assertTrue("locale missing: $rewritten", localeIdx >= 0)
        assertTrue("locale tag contains en-US", rewritten.contains("\"en-US\""))
        assertTrue("order: locale < shim < game (l=$localeIdx s=$shimIdx g=$gameIdx)",
            localeIdx < shimIdx && shimIdx < gameIdx)
    }

    @Test
    fun locale_with_multiple_shims_keeps_ordering() {
        val source = "<html><body><script src=\"g.js\"></script></body></html>"
        val rewritten = rewriteWithLocale(
            source,
            listOf("/_shims/a.js", "/_shims/b.js", "/_shims/c.js"),
            "fr-FR",
        )
        val localeIdx = rewritten.indexOf("navigator,'language'")
        val aIdx = rewritten.indexOf("/_shims/a.js")
        val bIdx = rewritten.indexOf("/_shims/b.js")
        val cIdx = rewritten.indexOf("/_shims/c.js")
        val gIdx = rewritten.indexOf("g.js")
        assertTrue(localeIdx in 0 until aIdx)
        assertTrue(aIdx < bIdx && bIdx < cIdx && cIdx < gIdx)
    }

    @Test
    fun locale_injected_into_html_with_no_script_tag_when_shims_empty() {
        val source = "<html><body>hi</body></html>"
        val rewritten = rewriteWithLocale(source, emptyList(), "de-DE")
        assertTrue("locale tag present: $rewritten", rewritten.contains("navigator,'language'"))
        assertTrue("de-DE present", rewritten.contains("\"de-DE\""))
        // original body preserved.
        assertTrue("body preserved", rewritten.contains("hi"))
    }

    @Test
    fun locale_with_non_empty_shims_and_no_script_still_throws() {
        val source = "<html><body>hi</body></html>"
        try {
            rewriteWithLocale(source, listOf("/_shims/x.js"), "de-DE")
            fail("expected fail-loud: shims need a game <script> anchor")
        } catch (e: IllegalStateException) {
            assertTrue("message: ${e.message}", e.message?.contains("no <script>") == true)
        }
    }

    @Test
    fun locale_string_is_json_escaped() {
        // contrived hostile locale — JSONObject.quote must escape the quote so the
        // defineProperty literal doesn't break out.
        val source = "<html><body><script src=\"g.js\"></script></body></html>"
        val rewritten = rewriteWithLocale(source, emptyList(), "en\"XSS")
        // the raw unescaped 3-char sequence would be: en"XSS (hostile break-out attempt).
        // escaped form must be present — JSONObject.quote wraps + escapes quote as \".
        assertTrue("escaped sequence present: $rewritten",
            rewritten.contains("\"en\\\"XSS\""))
    }

    @Test
    fun locale_script_sets_process_mainModule_filename() {
        // RMMZ's StorageManager.fileDirectoryPath reads process.mainModule.filename
        // once fs.js's isLocalMode override routes saves through the native path. typeof
        // process stays 'function' (YEP guard preserved) but mainModule must exist or boot crashes.
        val source = "<html><body><script src=\"g.js\"></script></body></html>"
        val rewritten = rewriteWithLocale(source, emptyList(), "en-US")
        // production uses JSONObject.quote for XSS-safe escaping → emits double-quoted strings
        assertTrue("mainModule assignment present: $rewritten",
            rewritten.contains("window.process.mainModule={filename:\"\"}"))
    }
}
