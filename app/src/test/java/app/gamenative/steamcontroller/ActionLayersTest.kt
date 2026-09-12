package app.gamenative.steamcontroller

import app.gamenative.utils.SteamControllerProfileImporter
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Action-layer overlays (build step 3): `add_layer`/`hold_layer`/`remove_layer` bindings push/pop a partial
 * overlay ([mergeProfiles]) over the active set. The synthetic config's Overlay layer rebinds only the face
 * buttons (A: Q -> M), so the test proves the per-source merge and the push/pop edge handling end to end.
 */
@RunWith(RobolectricTestRunner::class)
class ActionLayersTest {

    private fun state(buttons: Int): TritonState = TritonState().apply { this.buttons = buttons }

    private val a = TritonProtocol.BTN_A
    private val rb = TritonProtocol.BTN_RBUMPER   // hold_layer
    private val lb = TritonProtocol.BTN_LBUMPER   // add_layer
    private val menu = TritonProtocol.BTN_VIEW     // button_menu -> remove_layer

    private fun newInterp(sink: RecordingSink): ProfileInterpreter {
        val cfg = SteamControllerProfileImporter.importConfig(load("actionlayers_v3.vdf"))
        return ProfileInterpreter(sink, cfg.defaultProfile(), haptics = null).also { it.setConfig(cfg) }
    }

    @Test
    fun `hold_layer overlays the face buttons while held and pops on release`() {
        val sink = RecordingSink()
        val interp = newInterp(sink)

        interp.apply(state(0))
        interp.apply(state(a)); interp.apply(state(0))   // base set: A -> Q
        interp.apply(state(rb))                          // hold right bumper -> push Overlay
        interp.apply(state(rb or a))                     // overlay: A -> M
        interp.apply(state(rb))                          // release A
        interp.apply(state(0))                           // release bumper -> pop Overlay
        interp.apply(state(a))                           // base again: A -> Q

        assertEquals(2, sink.keyPresses(XKeycode.KEY_Q))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_M))
    }

    @Test
    fun `add_layer then remove_layer pushes and pops a persistent overlay`() {
        val sink = RecordingSink()
        val interp = newInterp(sink)

        interp.apply(state(0))
        interp.apply(state(lb)); interp.apply(state(0))   // add_layer -> Overlay stays up
        interp.apply(state(a)); interp.apply(state(0))    // overlay: A -> M
        interp.apply(state(menu)); interp.apply(state(0)) // remove_layer -> pop
        interp.apply(state(a))                            // base: A -> Q

        assertEquals(1, sink.keyPresses(XKeycode.KEY_M))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_Q))
    }
}
