package app.gamenative.runtime

import com.winlator.container.Container
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

// dispatch — locks wine vs webview routing at the play-tap seam.
// exercises the extracted testable helper so tests don't need navhost / compose.
// dispatch takes sealed GameRuntime — string resolution lives in GameRuntime.fromId.
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
}
