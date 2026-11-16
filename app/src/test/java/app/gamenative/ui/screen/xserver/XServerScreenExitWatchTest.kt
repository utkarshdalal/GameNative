package app.gamenative.ui.screen.xserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerScreenExitWatchTest {
    @Test
    fun `real Steam web helpers do not keep the session open`() {
        assertTrue("steamwebhelper" in buildEssentialProcessAllowlist(realSteam = true))
    }

    @Test
    fun `Steam web helpers remain non-essential outside real Steam sessions`() {
        assertFalse("steamwebhelper" in buildEssentialProcessAllowlist(realSteam = false))
    }

    @Test
    fun `game processes remain non-essential`() {
        assertFalse("burn with me" in buildEssentialProcessAllowlist(realSteam = true))
    }
}
