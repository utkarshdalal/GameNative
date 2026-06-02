package app.gamenative.runtime

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Test

// webview session-exit routing, without a navhost / compose.
class PluviaMainDispatchTest {

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
