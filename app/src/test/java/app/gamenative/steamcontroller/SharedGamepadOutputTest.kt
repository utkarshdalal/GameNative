package app.gamenative.steamcontroller

import app.gamenative.utils.SteamControllerProfileImporter
import com.winlator.inputcontrols.ExternalController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two inputs may be bound to the same virtual-pad button — the DOOM test config puts gamepad A on both the A
 * button and the left rear paddle. The level-output loop used to *assign* (`setPressed(idx, s.has(bit))`), so
 * whichever map entry came last won and an unpressed paddle cleared a held A: A and B were dead in game while
 * X and Y worked. Sentinel: revert the guard in ProfileInterpreter and the A/B cases here fail.
 */
@RunWith(RobolectricTestRunner::class)
class SharedGamepadOutputTest {

    private fun load(name: String): String =
        (javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
            .getResourceAsStream("sc/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing test resource sc/$name")

    private fun press(p: ScProfile, bit: Int): Int {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, p, haptics = null)
        interp.apply(TritonState())
        interp.apply(TritonState().apply { buttons = bit })
        interp.apply(TritonState())
        return sink.gamepadButtonsSeen
    }

    @Test
    fun `a button shared with a rear paddle still reaches the pad`() {
        val p = SteamControllerProfileImporter.importConfig(load("doom_sc_test.vdf")).defaultProfile()
        // Face buttons: A and B share their output with the rear paddles, X and Y do not.
        for ((name, bit, idx) in listOf(
            Triple("A", TritonProtocol.BTN_A, ExternalController.IDX_BUTTON_A),
            Triple("B", TritonProtocol.BTN_B, ExternalController.IDX_BUTTON_B),
            Triple("X", TritonProtocol.BTN_X, ExternalController.IDX_BUTTON_X),
            Triple("Y", TritonProtocol.BTN_Y, ExternalController.IDX_BUTTON_Y),
        )) {
            assertEquals("$name should press pad button $idx", 1 shl idx.toInt(), press(p, bit))
        }
        // The paddle sharing gamepad A must still work on its own.
        assertEquals("left rear paddle should press pad button A",
            1 shl ExternalController.IDX_BUTTON_A.toInt(), press(p, TritonProtocol.BTN_L4))
    }
}
