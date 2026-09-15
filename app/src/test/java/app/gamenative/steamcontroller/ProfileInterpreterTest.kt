package app.gamenative.steamcontroller

import com.winlator.inputcontrols.ExternalController
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Replays the captured golden trace (real controller, every control exercised) through the default profile
 * and asserts the interpreter emits the expected outputs. This is the regression guard for the engine: any
 * change that breaks the default mapping fails here, headlessly, on the PC. See docs/AUTOMATION-PLAN.md.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileInterpreterTest {

    private val trace by lazy { TraceReader.loadStates("sc/golden_ble_001.bin") }

    private fun replay(profile: ScProfile): RecordingSink {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, profile, haptics = null)
        for (s in trace) interp.apply(s)
        return sink
    }

    @Test
    fun `golden trace decodes to a rich sample`() {
        assertTrue("expected many frames, got ${trace.size}", trace.size > 1500)
    }

    @Test
    fun `default profile maps all face buttons and dpad to the virtual pad`() {
        val sink = replay(ScProfile.default())
        fun bit(idx: Byte) = (sink.gamepadButtonsSeen and (1 shl idx.toInt())) != 0
        for (idx in listOf(
            ExternalController.IDX_BUTTON_A, ExternalController.IDX_BUTTON_B,
            ExternalController.IDX_BUTTON_X, ExternalController.IDX_BUTTON_Y,
            ExternalController.IDX_BUTTON_L1, ExternalController.IDX_BUTTON_R1,
            ExternalController.IDX_BUTTON_L3, ExternalController.IDX_BUTTON_R3,
            ExternalController.IDX_BUTTON_START, ExternalController.IDX_BUTTON_SELECT,
        )) {
            assertTrue("gamepad button idx=$idx never pressed", bit(idx))
        }
        assertTrue("not all dpad directions seen", sink.dpadSeen.all { it })
    }

    @Test
    fun `default profile drives sticks full range and triggers full pull`() {
        val sink = replay(ScProfile.default())
        assertTrue("left stick X did not reach right", sink.maxThumbLX > 0.9f)
        assertTrue("left stick X did not reach left", sink.minThumbLX < -0.9f)
        assertTrue("right stick X did not reach right", sink.maxThumbRX > 0.9f)
        assertTrue("left trigger not fully pulled", sink.maxTriggerL > 0.9f)
        assertTrue("right trigger not fully pulled", sink.maxTriggerR > 0.9f)
    }

    @Test
    fun `default profile maps right pad to mouse motion and pad clicks to mouse buttons`() {
        val sink = replay(ScProfile.default())
        assertTrue("right pad produced no mouse motion", sink.mouseMoves > 0)
        assertTrue("right-pad click did not press left mouse", sink.mouseButtonPresses(Pointer.Button.BUTTON_LEFT) > 0)
        assertTrue("left-pad click did not press right mouse", sink.mouseButtonPresses(Pointer.Button.BUTTON_RIGHT) > 0)
    }

    @Test
    fun `default profile maps the four rear paddles to F1-F4`() {
        val sink = replay(ScProfile.default())
        for (key in listOf(XKeycode.KEY_F1, XKeycode.KEY_F2, XKeycode.KEY_F3, XKeycode.KEY_F4)) {
            assertTrue("paddle key $key never fired", sink.keyPresses(key) > 0)
        }
    }
}
