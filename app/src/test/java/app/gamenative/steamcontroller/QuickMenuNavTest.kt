package app.gamenative.steamcontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * QuickMenu controller access (the [ScUiBridge] seam): the Steam button opens the menu, and while a menu/editor is
 * captured the controller drives Android focus-nav keys instead of the game.
 */
@RunWith(RobolectricTestRunner::class)
class QuickMenuNavTest {

    private class FakeBridge(var capturing: Boolean = false) : ScUiBridge {
        var opens = 0
        var hides = 0
        val navs = ArrayList<ScNavKey>()
        override fun isMenuCapturing() = capturing
        override fun openQuickMenu() { opens++ }
        override fun nav(key: ScNavKey) { navs.add(key) }
        override fun hideCursor() { hides++ }
    }

    private fun interp(bridge: FakeBridge, profile: ScProfile = ScProfile.default(), sink: RecordingSink = RecordingSink()) =
        ProfileInterpreter(sink, profile, haptics = null, uiBridge = bridge)

    @Test
    fun `default profile opens the QuickMenu on the Steam-button press edge`() {
        val bridge = FakeBridge()
        val i = interp(bridge)
        i.apply(TritonState())                                           // baseline, no buttons
        i.apply(TritonState().apply { buttons = TritonProtocol.BTN_STEAM }) // rising edge
        assertEquals(1, bridge.opens)
    }

    @Test
    fun `Y sends the HELP nav intent while a menu is captured`() {
        val bridge = FakeBridge(capturing = true)
        val i = interp(bridge)
        i.apply(TritonState())
        i.apply(TritonState().apply { buttons = TritonProtocol.BTN_Y }) // rising edge
        assertTrue("Y -> HELP nav", bridge.navs.contains(ScNavKey.HELP))
    }

    @Test
    fun `nav cursor is hidden when menu capture ends`() {
        val bridge = FakeBridge(capturing = true)
        val i = interp(bridge)
        i.apply(TritonState())          // capturing -> drives nav, no hide
        assertEquals(0, bridge.hides)
        bridge.capturing = false
        i.apply(TritonState())          // falling edge -> cursor detached exactly once
        assertEquals(1, bridge.hides)
        i.apply(TritonState())          // still closed -> not repeatedly hidden
        assertEquals(1, bridge.hides)
    }

    @Test
    fun `Steam button does not re-open while the menu is already captured`() {
        val bridge = FakeBridge(capturing = true)
        val i = interp(bridge)
        i.apply(TritonState())
        i.apply(TritonState().apply { buttons = TritonProtocol.BTN_STEAM })
        assertEquals("no open while captured (Steam routes to BACK instead)", 0, bridge.opens)
        assertEquals(listOf(ScNavKey.BACK), bridge.navs)
    }

    @Test
    fun `d-pad, A and B map to focus nav while captured`() {
        val bridge = FakeBridge(capturing = true)
        val i = interp(bridge)
        i.apply(TritonState())                                                   // baseline
        i.apply(TritonState().apply { buttons = TritonProtocol.BTN_DPAD_UP })
        i.apply(TritonState())                                                   // release
        i.apply(TritonState().apply { buttons = TritonProtocol.BTN_A })
        i.apply(TritonState())
        i.apply(TritonState().apply { buttons = TritonProtocol.BTN_B })
        assertEquals(listOf(ScNavKey.UP, ScNavKey.SELECT, ScNavKey.BACK), bridge.navs)
    }

    @Test
    fun `bumpers emit tab prev-next while captured`() {
        val bridge = FakeBridge(capturing = true)
        val i = interp(bridge)
        i.apply(TritonState())                                                     // baseline
        i.apply(TritonState().apply { buttons = TritonProtocol.BTN_LBUMPER })      // LB -> TAB_PREV
        i.apply(TritonState())                                                     // release
        i.apply(TritonState().apply { buttons = TritonProtocol.BTN_RBUMPER })      // RB -> TAB_NEXT
        assertEquals(listOf(ScNavKey.TAB_PREV, ScNavKey.TAB_NEXT), bridge.navs)
    }

    @Test
    fun `left stick acts as a d-pad with edge-triggered direction changes`() {
        val bridge = FakeBridge(capturing = true)
        val i = interp(bridge)
        i.apply(TritonState())                                       // dir none
        i.apply(TritonState().apply { leftStickY = 20000 })          // into up -> one UP
        i.apply(TritonState().apply { leftStickY = 22000 })          // still up -> no repeat
        i.apply(TritonState().apply { leftStickX = -20000 })         // into left -> one LEFT
        assertEquals(listOf(ScNavKey.UP, ScNavKey.LEFT), bridge.navs)
    }

    @Test
    fun `every fixed ScMenuNav control fires its declared nav key`() {
        // Locks the source-of-truth table to interpreter behavior: if a Control's button is remapped, the tooltip
        // (which reads the same table) and this test move together, so they can't drift.
        for (c in ScMenuNav.controls) {
            val bridge = FakeBridge(capturing = true)
            val i = interp(bridge)
            i.apply(TritonState())                                            // baseline
            i.apply(TritonState().apply { buttons = c.buttonBit })            // rising edge
            assertTrue("${c.hint} (${c.desc}) -> ${c.key}", bridge.navs.contains(c.key))
        }
    }

    @Test
    fun `no game output is emitted while a menu is captured`() {
        val bridge = FakeBridge(capturing = true)
        val sink = RecordingSink()
        val i = interp(bridge, sink = sink)
        i.apply(TritonState())
        // BTN_A would normally drive a virtual-pad button; while captured it must not reach the game.
        i.apply(TritonState().apply { buttons = TritonProtocol.BTN_A })
        assertEquals(0, sink.gamepadFrames)
        assertTrue(sink.keys.isEmpty())
        assertTrue(sink.mouseButtons.isEmpty())
    }
}
