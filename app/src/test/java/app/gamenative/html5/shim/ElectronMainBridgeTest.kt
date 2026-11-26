package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private class FakeJar : ElectronMainBridge.Jar {
        val asked = mutableListOf<String>()
        val set = mutableListOf<Pair<String, String>>()
        override fun get(url: String): String? {
            asked += url
            return "sid=1; HttpOnlyOne=2"
        }
        override fun set(url: String, cookie: String) {
            set += url to cookie
        }
        override fun flush() = Unit
    }

    private val served = setOf("example.test", "www.example.test")

    @Test
    fun hostsOutsideTheServedSetAreRefusedWithoutTouchingTheJar() {
        val jar = FakeJar()
        val bridge = ElectronMainBridge(allowsHost = { it in served }, jar = jar)
        assertEquals("[]", bridge.getCookies("https://example.com/"))
        assertEquals("[]", bridge.getCookies("https://store.steampowered.com/"))
        assertEquals("[]", bridge.getCookies("http://example.test/"))
        assertEquals("[]", bridge.getCookies("https://user@example.test/"))
        assertEquals("[]", bridge.getCookies("not a url"))
        bridge.setCookie("https://evil.test/", """{"name":"a","value":"b"}""")
        bridge.removeCookie("https://evil.test/", "a")
        assertTrue(jar.asked.isEmpty())
        assertTrue(jar.set.isEmpty())
    }

    @Test
    fun theJarIsAskedForTheParsedUrlOnly() {
        val jar = FakeJar()
        val bridge = ElectronMainBridge(allowsHost = { it in served }, jar = jar)
        val json = bridge.getCookies("https://WWW.Example.test/__game__/index.html?x=1#y")
        assertEquals(listOf("https://www.example.test/__game__/index.html"), jar.asked)
        assertTrue(json.contains("\"domain\":\"www.example.test\""))
        assertEquals("https://example.test/", ElectronMainBridge.targetUrl("https://example.test", { it in served }))
        assertNull(ElectronMainBridge.targetUrl("https://example.test.evil.com/", { it in served }))
    }
}
