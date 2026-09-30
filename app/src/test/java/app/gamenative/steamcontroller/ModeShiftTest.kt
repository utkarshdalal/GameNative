package app.gamenative.steamcontroller

import app.gamenative.utils.SteamControllerProfileImporter
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mode-shift (build step 3): a `mode_shift <source> <group>` binding momentarily overlays one source's mode
 * while its button is held ([ScOutput.ModeShift] + [ScConfig.shiftOverlays], merged via [mergeProfiles] with
 * `layerSources={source}`). Here holding right_bumper shifts the button_diamond source so A: Q -> M.
 */
@RunWith(RobolectricTestRunner::class)
class ModeShiftTest {

    private fun state(buttons: Int): TritonState = TritonState().apply { this.buttons = buttons }

    @Test
    fun `mode_shift overlays the source while held and restores on release`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("modeshift_v3.vdf"))
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, cfg.defaultProfile(), haptics = null).also { it.setConfig(cfg) }

        val a = TritonProtocol.BTN_A
        val bumper = TritonProtocol.BTN_RBUMPER

        interp.apply(state(0))
        interp.apply(state(a)); interp.apply(state(0))   // base: A -> Q
        interp.apply(state(bumper))                      // hold bumper -> shift button_diamond to group 1
        interp.apply(state(bumper or a))                 // shifted: A -> M
        interp.apply(state(bumper))
        interp.apply(state(0))                           // release bumper -> restore base
        interp.apply(state(a))                           // base again: A -> Q

        assertEquals(2, sink.keyPresses(XKeycode.KEY_Q))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_M))
    }
}
