package app.gamenative.runtime

import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRuntimeTest {

    @Test
    fun wine_runtime_id_matches_container_constant() {
        assertEquals(Container.RUNTIME_WINE, WineRuntime.id)
        assertEquals("wine", WineRuntime.id)
    }

    @Test
    fun webview_runtime_id_matches_container_constant() {
        assertEquals(Container.RUNTIME_WEBVIEW, WebViewRuntime.id)
        assertEquals("webview", WebViewRuntime.id)
    }

    @Test
    fun both_runtimes_are_game_runtime_variants() {
        val wine: GameRuntime = WineRuntime
        val web: GameRuntime = WebViewRuntime
        assertTrue(wine is WineRuntime)
        assertTrue(web is WebViewRuntime)
    }
}
