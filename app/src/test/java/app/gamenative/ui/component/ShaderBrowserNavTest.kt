package app.gamenative.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderBrowserNavTest {

    @Test
    fun `starts at home and reports root`() {
        val nav = ShaderBrowserNav()
        assertEquals(ShaderBrowserScreen.Home, nav.current)
        assertTrue(nav.atRoot)
    }

    @Test
    fun `push drills down and pop returns`() {
        val nav = ShaderBrowserNav()
        nav.push(ShaderBrowserScreen.Family("crt"))
        assertEquals(ShaderBrowserScreen.Family("crt"), nav.current)
        assertFalse(nav.atRoot)
        nav.push(ShaderBrowserScreen.Family("crt", "guest"))
        assertEquals(ShaderBrowserScreen.Family("crt", "guest"), nav.current)
        assertTrue(nav.pop())
        assertEquals(ShaderBrowserScreen.Family("crt"), nav.current)
        assertTrue(nav.pop())
        assertEquals(ShaderBrowserScreen.Home, nav.current)
        assertTrue(nav.atRoot)
    }

    @Test
    fun `pop at root returns false`() {
        val nav = ShaderBrowserNav()
        assertFalse(nav.pop())
        assertEquals(ShaderBrowserScreen.Home, nav.current)
    }

    @Test
    fun `pushing the same screen is a no-op`() {
        val nav = ShaderBrowserNav()
        nav.push(ShaderBrowserScreen.Family("crt"))
        nav.push(ShaderBrowserScreen.Family("crt"))
        assertEquals(2, nav.size)
    }

    @Test
    fun `screens expose stable keys`() {
        assertEquals("home", ShaderBrowserScreen.Home.key())
        assertEquals("family:crt", ShaderBrowserScreen.Family("crt").key())
        assertEquals("family:crt:guest", ShaderBrowserScreen.Family("crt", "guest").key())
    }
}
