package app.gamenative.ui

import app.gamenative.runtime.GameRuntime
import app.gamenative.runtime.WebViewRuntime
import app.gamenative.runtime.WineRuntime
import app.gamenative.runtime.dispatchLaunchByRuntime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// phase-2 webview branch activation. dispatchLaunchByRuntime(WebViewRuntime, ...)
// must call navigateToWebView and NOT navigateToWine. the phase-1 test verified
// WineRuntime -> navigateToWine; this adds the complementary check + catches
// regression if someone ever reverts to the snackbar path.
class PluviaMainWebViewDispatchTest {

    @Test
    fun webview_runtime_triggers_navigate_to_webview() {
        var wentWine = false
        var wentWebView = false
        dispatchLaunchByRuntime(
            runtime = WebViewRuntime,
            appId = "CUSTOM_GAME_42",
            navigateToWine = { wentWine = true },
            navigateToWebView = { wentWebView = true },
        )
        assertFalse("must not call navigateToWine for WebViewRuntime", wentWine)
        assertTrue("must call navigateToWebView for WebViewRuntime", wentWebView)
    }

    @Test
    fun wine_runtime_still_triggers_navigate_to_wine() {
        var wentWine = false
        var wentWebView = false
        dispatchLaunchByRuntime(
            runtime = WineRuntime,
            appId = "123456",
            navigateToWine = { wentWine = true },
            navigateToWebView = { wentWebView = true },
        )
        assertTrue(wentWine)
        assertFalse(wentWebView)
    }

    @Test
    fun from_id_routes_webview_string_to_webview_branch() {
        var wentWebView = false
        dispatchLaunchByRuntime(
            runtime = GameRuntime.fromId("webview"),
            appId = "x",
            navigateToWine = { },
            navigateToWebView = { wentWebView = true },
        )
        assertTrue(wentWebView)
    }
}
