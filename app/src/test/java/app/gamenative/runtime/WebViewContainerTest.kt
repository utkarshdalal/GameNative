package app.gamenative.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewContainerTest {

    @Test
    fun instantiates_with_all_required_fields() {
        val c = WebViewContainer(
            id = "test-id",
            installPath = "/sdcard/games/test",
            entryPoint = "index.html",
            engineProfile = "pack:rmmv",
            inputMap = "native-controller",
        )
        assertEquals("test-id", c.id)
        assertEquals("/sdcard/games/test", c.installPath)
        assertEquals("index.html", c.entryPoint)
        assertEquals("pack:rmmv", c.engineProfile)
        assertEquals("native-controller", c.inputMap)
        assertEquals(WebViewRuntime.id, c.runtime)
    }
}
