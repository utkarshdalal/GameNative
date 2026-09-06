package app.gamenative.steamcontroller

import app.gamenative.utils.SteamControllerProfileImporter
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end test of config-driven action-set switching (build step 3): the importer turns a 2-set config into
 * an [ScConfig], and [ProfileInterpreter] swaps the live profile when a `CHANGE_PRESET`/[ScOutput.SwitchActionSet]
 * binding fires — reproducing the real "hold for menus" round trip (enter on press in set A, leave on release in
 * set B). Driven by synthetic button states, fully headless.
 */
@RunWith(RobolectricTestRunner::class)
class ActionSetsTest {

    private fun state(buttons: Int): TritonState = TritonState().apply { this.buttons = buttons }

    @Test
    fun `hold-for-menus round trip swaps the active set`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("actionsets_v3.vdf"))
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, cfg.defaultProfile(), haptics = null)
        interp.setConfig(cfg)

        val a = TritonProtocol.BTN_A
        val bumper = TritonProtocol.BTN_RBUMPER

        interp.apply(state(0))            // baseline
        interp.apply(state(a))            // Default set: A -> Q
        interp.apply(state(0))
        assertEquals("0", interp.activeSetId)

        interp.apply(state(bumper))       // hold bumper -> enter Menus set (id 1)
        assertEquals("1", interp.activeSetId)
        interp.apply(state(bumper or a))  // Menus set: A -> M (not Q)
        interp.apply(state(bumper))       // release A
        interp.apply(state(0))            // release bumper -> Menus' release binding returns to Default
        assertEquals("0", interp.activeSetId)
        interp.apply(state(a))            // Default again: A -> Q

        assertEquals("Q fired in the Default set, before and after the menu trip", 2, sink.keyPresses(XKeycode.KEY_Q))
        assertEquals("M fired only while the Menus set was active", 1, sink.keyPresses(XKeycode.KEY_M))
    }

    @Test
    fun `with no config installed SwitchActionSet bindings are inert`() {
        // The Default set alone (no ScConfig) must not crash or emit anything for the switch binding.
        val cfg = SteamControllerProfileImporter.importConfig(load("actionsets_v3.vdf"))
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, cfg.sets.getValue("0"), haptics = null)
        interp.apply(state(TritonProtocol.BTN_RBUMPER))
        interp.apply(state(0))
        assertEquals(0, sink.keys.size) // switch binding produced no key/mouse output
    }
}
