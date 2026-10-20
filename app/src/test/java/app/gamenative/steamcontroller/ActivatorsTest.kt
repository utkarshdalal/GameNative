package app.gamenative.steamcontroller

import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Activator timing state machines, tested deterministically with a virtual clock. */
@RunWith(RobolectricTestRunner::class)
class ActivatorsTest {

    private val A = TritonProtocol.BTN_A
    private fun press() = TritonState().apply { buttons = A }
    private fun release() = TritonState()

    private class Harness(activator: Activator, val key: XKeycode = XKeycode.KEY_F1) {
        var t = 0L
        val sink = RecordingSink()
        private val interp = ProfileInterpreter(
            sink,
            ScProfile(buttons = mapOf(TritonProtocol.BTN_A to Binding(ScOutput.Key(key), activator))),
            haptics = null,
            clock = { t },
        )
        fun at(time: Long, s: TritonState) { t = time; interp.apply(s) }
        fun downs() = sink.keyPresses(key)
        fun ups() = sink.keys.count { it.key == key && !it.pressed }
    }

    @Test
    fun `double press fires only on the second press within the window`() {
        val h = Harness(Activator.DoublePress(windowMs = 300))
        h.at(0, press()); h.at(10, release())
        assertEquals("single press should not fire", 0, h.downs())
        h.at(100, press())                      // second press within 300ms -> fire
        assertEquals(1, h.downs())
        h.at(110, release())
    }

    @Test
    fun `double press does not fire when presses are too far apart`() {
        val h = Harness(Activator.DoublePress(windowMs = 300))
        h.at(0, press()); h.at(10, release())
        h.at(1000, press())                     // > window since first -> treated as a new first press
        assertEquals(0, h.downs())
        h.at(1010, release())
    }

    @Test
    fun `long press fires after the hold threshold and releases on lift`() {
        val h = Harness(Activator.LongPress(holdMs = 500), key = XKeycode.KEY_F2)
        h.at(0, press())
        h.at(200, press())                      // still held, below threshold
        assertEquals(0, h.downs())
        h.at(500, press())                      // threshold reached -> press
        assertEquals(1, h.downs())
        h.at(600, release())                    // physical release -> output up
        assertEquals(1, h.ups())
    }

    @Test
    fun `turbo pulses on press and every interval while held`() {
        val h = Harness(Activator.Turbo(intervalMs = 80), key = XKeycode.KEY_F3)
        h.at(0, press())     // pulse 1 (on press)
        h.at(80, press())    // pulse 2
        h.at(160, press())   // pulse 3
        h.at(200, release())
        assertEquals(3, h.downs())
        assertEquals(3, h.ups()) // each pulse is press+release
    }
}
