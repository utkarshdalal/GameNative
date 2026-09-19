package app.gamenative.runtime

import com.winlator.container.Container
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// locks wine vs webview routing at the play-tap seam via the extracted helper, so no navhost /
// compose. string resolution lives in GameRuntime.fromId.
class PluviaMainDispatchTest {

    @Test
    fun wine_runtime_routes_to_xserver_and_skips_webview() {
        val navigate = mockk<() -> Unit>(relaxed = true)
        val webview = mockk<() -> Unit>(relaxed = true)

        dispatchLaunchByRuntime(
            runtime = WineRuntime,
            appId = "42",
            navigateToWine = navigate,
            navigateToWebView = webview,
        )

        verify(exactly = 1) { navigate() }
        verify(exactly = 0) { webview() }
    }

    @Test
    fun webview_runtime_navigates_to_webview_and_skips_wine() {
        val navigate = mockk<() -> Unit>(relaxed = true)
        val webview = mockk<() -> Unit>(relaxed = true)

        dispatchLaunchByRuntime(
            runtime = WebViewRuntime,
            appId = "42",
            navigateToWine = navigate,
            navigateToWebView = webview,
        )

        verify(exactly = 0) { navigate() }
        verify(exactly = 1) { webview() }
    }

    @Test
    fun fromId_unknown_string_falls_back_to_wine_then_routes_to_xserver() {
        val navigate = mockk<() -> Unit>(relaxed = true)
        val webview = mockk<() -> Unit>(relaxed = true)

        // unknown-id handling lives in GameRuntime.fromId (single source of truth)
        val resolved = GameRuntime.fromId("unknown-value")
        assertEquals(WineRuntime, resolved)

        dispatchLaunchByRuntime(
            runtime = resolved,
            appId = "42",
            navigateToWine = navigate,
            navigateToWebView = webview,
        )

        verify(exactly = 1) { navigate() }
        verify(exactly = 0) { webview() }
    }

    @Test
    fun fromId_maps_container_constants_to_sealed_variants() {
        assertEquals(WineRuntime, GameRuntime.fromId(Container.RUNTIME_WINE))
        assertEquals(WebViewRuntime, GameRuntime.fromId(Container.RUNTIME_WEBVIEW))
    }

    // the save export hangs off the WebView's onDispose, so an in-session exit must pop -- a
    // finish() here would kill exitSteamApp's scope before the cloud upload.
    @Test
    fun webview_session_exit_pops_and_never_finishes() {
        val pop = mockk<() -> Unit>(relaxed = true)

        dispatchWebViewSessionExit(expectedRoute = "webview", currentRoute = "webview", popBackStack = pop)

        verify(exactly = 1) { pop() }
    }

    @Test
    fun webview_session_exit_ignores_a_stale_route() {
        val pop = mockk<() -> Unit>(relaxed = true)

        dispatchWebViewSessionExit(expectedRoute = "webview", currentRoute = "home", popBackStack = pop)

        verify(exactly = 0) { pop() }
    }

    @Test
    fun webview_exit_completion_is_null_for_an_in_app_launch() {
        val finish = mockk<() -> Unit>(relaxed = true)
        val clear = mockk<() -> Unit>(relaxed = true)

        assertNull(webViewExitCompletion(wasLaunchedViaExternalIntent = false, finishActivity = finish, clearExternalIntentFlag = clear))
        verify(exactly = 0) { finish() }
        verify(exactly = 0) { clear() }
    }

    @Test
    fun webview_exit_completion_finishes_only_when_invoked_for_an_intent_launch() {
        val finish = mockk<() -> Unit>(relaxed = true)
        val clear = mockk<() -> Unit>(relaxed = true)

        val completion = webViewExitCompletion(
            wasLaunchedViaExternalIntent = true,
            finishActivity = finish,
            clearExternalIntentFlag = clear,
        )
        // building it must not finish -- exitSteamApp runs the cloud sync first, then invokes it.
        verify(exactly = 0) { finish() }

        completion!!.invoke()

        verify(exactly = 1) { clear() }
        verify(exactly = 1) { finish() }
    }
}
