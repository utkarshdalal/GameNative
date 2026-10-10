package app.gamenative.steamcontroller

import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TriggerModesTest {

    private fun trigState(rawLeft: Int): TritonState =
        TritonState().apply { triggerLeft = rawLeft }

    @Test
    fun `staged trigger fires soft then full at thresholds`() {
        val sink = RecordingSink()
        val profile = ScProfile(
            leftTrigger = TriggerMode.Staged(
                soft = ScOutput.Key(XKeycode.KEY_F1),
                full = ScOutput.Key(XKeycode.KEY_F2),
                softThreshold = 0.4f, fullThreshold = 0.9f,
            ),
        )
        val interp = ProfileInterpreter(sink, profile, haptics = null)

        interp.apply(trigState(0))      // released
        interp.apply(trigState(16000))  // ~0.49 -> soft (F1)
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
        assertEquals(0, sink.keyPresses(XKeycode.KEY_F2))

        interp.apply(trigState(31000))  // ~0.95 -> full (F2), soft still held
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F2))

        interp.apply(trigState(0))      // released -> both up
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_F1 && !it.pressed })
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_F2 && !it.pressed })
    }
}
