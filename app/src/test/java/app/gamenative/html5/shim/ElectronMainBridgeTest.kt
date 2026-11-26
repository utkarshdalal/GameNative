package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Test

class ElectronMainBridgeTest {

    @Test
    fun cookieHeaderSplitsIntoNameValuePairs() {
        assertEquals(
            listOf("sid" to "a=b", "theme" to "dark", "empty" to ""),
            ElectronMainBridge.parseCookieHeader("sid=a=b; theme=dark;empty=; ;noequals"),
        )
    }

    @Test
    fun noCookiesIsAnEmptyList() {
        assertEquals(emptyList<Pair<String, String>>(), ElectronMainBridge.parseCookieHeader(null))
        assertEquals(emptyList<Pair<String, String>>(), ElectronMainBridge.parseCookieHeader(""))
    }
}
