package app.gamenative.steamcontroller

import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Per-binding delays + toggle + macro playback, driven by a virtual clock. */
@RunWith(RobolectricTestRunner::class)
class MacroDelayTest {

    private val A = TritonProtocol.BTN_A
    private fun press() = TritonState().apply { buttons = A }
    private fun release() = TritonState()
    private val F = XKeycode.KEY_F

    private class H(binding: Binding) {
        var t = 0L
        val sink = RecordingSink()
        private val interp = ProfileInterpreter(
            sink, ScProfile(buttons = mapOf(TritonProtocol.BTN_A to binding)), haptics = null, clock = { t },
        )
        fun at(time: Long, s: TritonState) { t = time; interp.apply(s) }
    }

    private fun H.downs(k: XKeycode) = sink.keyPresses(k)
    private fun H.ups(k: XKeycode) = sink.keys.count { it.key == k && !it.pressed }

    @Test
    fun `fire start delay defers the press`() {
        val h = H(Binding(ScOutput.Key(F), delayStartMs = 100))
        h.at(0, press());  assertEquals(0, h.downs(F))   // not yet
        h.at(50, press()); assertEquals(0, h.downs(F))   // still waiting
        h.at(100, press()); assertEquals(1, h.downs(F))  // fires at +100
    }

    @Test
    fun `fire end delay defers the release`() {
        val h = H(Binding(ScOutput.Key(F), delayEndMs = 100))
        h.at(0, press());   assertEquals(1, h.downs(F))
        h.at(10, release()); assertEquals(0, h.ups(F))   // release deferred
        h.at(110, release()); assertEquals(1, h.ups(F))  // released at +110
    }

    @Test
    fun `fire-anyway - a tap shorter than the start delay still pulses`() {
        val h = H(Binding(ScOutput.Key(F), delayStartMs = 100))
        h.at(0, press())
        h.at(10, release())            // let go before the 100ms delay elapses
        h.at(100, release())           // scheduled press fires here
        h.at(140, release())           // and the clamped release
        assertEquals("press still fired", 1, h.downs(F))
        assertEquals("and released", 1, h.ups(F))
    }

    @Test
    fun `toggle latches on then off across presses`() {
        val h = H(Binding(ScOutput.Key(F), toggle = true))
        h.at(0, press()); h.at(10, release())
        assertEquals(1, h.downs(F)); assertEquals(0, h.ups(F))   // latched on, still held
        h.at(100, press()); h.at(110, release())
        assertEquals(1, h.ups(F))                                // second press latches off
    }

    @Test
    fun `macro plays its commands in order over time`() {
        val m = ScOutput.Macro(listOf(
            MacroCommand(listOf(ScOutput.Key(XKeycode.KEY_1))),
            MacroCommand(listOf(ScOutput.Key(XKeycode.KEY_2))),
        ))
        val h = H(Binding(m))
        h.at(0, press())
        for (t in listOf(40L, 80, 120, 160, 200)) h.at(t, release())  // advance frames so scheduled steps fire
        assertEquals(1, h.downs(XKeycode.KEY_1))
        assertEquals(1, h.downs(XKeycode.KEY_2))
        val i1 = h.sink.keys.indexOfFirst { it.key == XKeycode.KEY_1 && it.pressed }
        val i2 = h.sink.keys.indexOfFirst { it.key == XKeycode.KEY_2 && it.pressed }
        assertTrue("command 1 fires before command 2", i1 in 0 until i2)
    }

    @Test
    fun `macro one-shot - holding does not replay`() {
        val m = ScOutput.Macro(listOf(MacroCommand(listOf(ScOutput.Key(XKeycode.KEY_1)))))
        val h = H(Binding(m))
        h.at(0, press())
        for (t in listOf(40L, 80, 120, 160, 200, 240)) h.at(t, press())  // keep holding
        assertEquals("played exactly once", 1, h.downs(XKeycode.KEY_1))
    }
}
