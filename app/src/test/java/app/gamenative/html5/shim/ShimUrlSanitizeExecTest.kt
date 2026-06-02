package app.gamenative.html5.shim

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// EXECUTES url-sanitize.js under Rhino via a recording XMLHttpRequest. the shim wraps
// XHR.open and rewrites the URL before it reaches the network: stray-% escaping (chrome rejects
// %X non-hex) + file:// -> same-origin path. the backslash-first ordering and embedded-file://
// cases are exactly what a refactor breaks; locked here. OMORI/c2 asset loads depend on it.
class ShimUrlSanitizeExecTest {
    private lateinit var js: ShimJsRuntime

    @Before
    fun setUp() {
        // installXhr defines XMLHttpRequest + records the final (post-sanitize) url in lastUrl;
        // url-sanitize wraps that open() so san(u) returns whatever the shim forwarded.
        js = ShimJsRuntime().installXhr().load("url-sanitize.js")
        js.eval("function san(u) { new XMLHttpRequest().open('GET', u); return window.__xhr.lastUrl; }")
    }

    @After
    fun tearDown() {
        js.close()
    }

    @Test
    fun escapes_stray_percent() {
        assertEquals("%25(8).png", js.evalString("san('%(8).png')"))
    }

    @Test
    fun preserves_valid_percent_encoding() {
        assertEquals("%2F", js.evalString("san('%2F')"))
    }

    @Test
    fun escapes_percent_but_keeps_dollar_and_parens() {
        // OMORI's $DW_OMORI_RUN%(8).png -- only the bad % is touched, $() stay.
        assertEquals("\$DW_OMORI_RUN%25(8).png", js.evalString("san('\$DW_OMORI_RUN%(8).png')"))
    }

    @Test
    fun rewrites_triple_slash_file_url() {
        assertEquals("/abs/path/x.ogg", js.evalString("san('file:///abs/path/x.ogg')"))
    }

    @Test
    fun rewrites_dot_slash_file_url() {
        assertEquals("/data/audio/x.ogg", js.evalString("san('file://./data/audio/x.ogg')"))
    }

    @Test
    fun normalizes_backslashes_before_detecting_scheme() {
        // file:\\data\x -- canonicalized to / FIRST so indexOf('file://') matches. the documented
        // ordering bug: detect-before-normalize would leak an unrewritten file:\\ url to chrome.
        assertEquals(
            "/data/x.ogg",
            js.evalString(
                "san('file:' + String.fromCharCode(92) + String.fromCharCode(92) + 'data' + String.fromCharCode(92) + 'x.ogg')",
            ),
        )
    }

    @Test
    fun strips_garbage_before_embedded_file_scheme() {
        // c2 concatenation artifact: ./data/file://data/x.ogg -> keep only the real tail.
        assertEquals("/data/x.ogg", js.evalString("san('./data/file://data/x.ogg')"))
    }

    @Test
    fun passes_through_plain_url_untouched() {
        assertEquals("https://gamenative/img/y.png", js.evalString("san('https://gamenative/img/y.png')"))
    }
}
