package app.gamenative.html5.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// pin: synthesizeDesktopChromeUa rewrites a mobile WebView UA to a Windows desktop Chrome UA
// preserving the actual Chromium milestone so the spoof tracks WebView updates automatically.
class SynthesizeDesktopChromeUaTest {

    private val mobileUa = "Mozilla/5.0 (Linux; Android 15; Odin3 Build/AQ3A.250728.001; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.219 Mobile Safari/537.36"

    @Test fun preserves_chrome_token_from_mobile_webview_ua() {
        val out = synthesizeDesktopChromeUa(mobileUa)
        assertTrue("must keep Chrome/124.0.6367.219: $out", out.contains("Chrome/124.0.6367.219"))
    }

    @Test fun reports_windows_desktop_platform_segment() {
        val out = synthesizeDesktopChromeUa(mobileUa)
        assertTrue("must contain Windows NT 10.0 segment: $out", out.contains("(Windows NT 10.0; Win64; x64)"))
    }

    @Test fun strips_mobile_marker() {
        val out = synthesizeDesktopChromeUa(mobileUa)
        assertTrue(
            "must NOT contain 'Mobile' marker (desktop UA does not): $out",
            !out.contains("Mobile"),
        )
    }

    @Test fun strips_android_segment() {
        val out = synthesizeDesktopChromeUa(mobileUa)
        assertTrue("must NOT contain Linux/Android segment: $out", !out.contains("Android"))
        assertTrue("must NOT contain Linux/Android segment: $out", !out.contains("Linux"))
    }

    @Test fun strips_webview_version_marker() {
        // "Version/4.0" is the WebView app-version marker. Desktop Chrome doesn't include it.
        val out = synthesizeDesktopChromeUa(mobileUa)
        assertTrue("must NOT contain 'Version/4.0' marker: $out", !out.contains("Version/4.0"))
    }

    @Test fun fallback_chrome_token_when_input_lacks_one() {
        val out = synthesizeDesktopChromeUa("garbage UA with no chrome token")
        assertTrue("fallback must include Chrome/N.N.N.N: $out", out.contains("Chrome/124.0.0.0"))
    }

    @Test fun output_shape_matches_canonical_desktop_chrome_ua() {
        // shape regression: the helper must emit a single, contiguous, well-formed UA string
        // that matches the canonical Chrome desktop format. consumers (server-side UA parsers)
        // are sensitive to the exact ordering of segments.
        val out = synthesizeDesktopChromeUa(mobileUa)
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.6367.219 Safari/537.36",
            out,
        )
    }
}
