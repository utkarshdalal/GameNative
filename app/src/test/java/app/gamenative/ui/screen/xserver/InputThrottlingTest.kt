package app.gamenative.ui.screen.xserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputThrottlingTest {
    private val frame60Hz = 1_000_000_000L / 60

    /** Frames that send over [frames] display frames of [frameNanos], timestamps rounded down to ms like nowNanos(). */
    private fun sendingFrames(throttling: InputThrottling, frames: Int, frameNanos: Long): List<Int> {
        val pacer = throttling.Pacer()
        val sent = ArrayList<Int>()
        for (i in 0 until frames) {
            val now = (i * frameNanos) / 1_000_000L * 1_000_000L + 1_000_000_000L
            if (pacer.isDue(now)) {
                pacer.onSent(now)
                sent += i
            }
        }
        return sent
    }

    private fun throttling(hz: Int) = InputThrottling().apply {
        enabled = true
        setRateHz(hz)
    }

    @Test
    fun `the display rate sends on every frame despite millisecond rounding`() {
        assertEquals(600, sendingFrames(throttling(60), 600, frame60Hz).size)
    }

    @Test
    fun `a divisor of the display rate is evenly spaced`() {
        val sent = sendingFrames(throttling(30), 600, frame60Hz)
        assertEquals(300, sent.size)
        assertTrue(sent.zipWithNext().all { (a, b) -> b - a == 2 })
    }

    @Test
    fun `a rate between divisors averages out instead of falling to the lower one`() {
        // 45 Hz on 60 Hz frames: 3 of every 4.
        assertEquals(450, sendingFrames(throttling(45), 600, frame60Hz).size.toDouble(), 1.0)
        // 50 Hz on 120 Hz frames: no longer every 3rd frame (40 Hz).
        assertEquals(500, sendingFrames(throttling(50), 1200, 1_000_000_000L / 120).size.toDouble(), 1.0)
    }

    @Test
    fun `after idle the next send goes out right away, without a catch-up burst`() {
        val pacer = throttling(30).Pacer()
        pacer.onSent(0L)
        val afterIdle = 1_000_000_000L
        assertTrue(pacer.isDue(afterIdle))
        pacer.onSent(afterIdle)
        assertFalse(pacer.isDue(afterIdle + frame60Hz))
        assertTrue(pacer.isDue(afterIdle + 2 * frame60Hz))
    }

    @Test
    fun `a forced send ahead of schedule doesn't move it`() {
        val pacer = throttling(30).Pacer()
        pacer.onSent(0L)
        pacer.onSent(frame60Hz) // e.g. a button between motion sends
        assertTrue(pacer.isDue(2 * frame60Hz))
    }

    @Test
    fun `a new rate or turning throttling off and on restarts the schedule`() {
        val throttling = throttling(5)
        val pacer = throttling.Pacer()
        pacer.onSent(0L)
        assertFalse(pacer.isDue(frame60Hz))

        throttling.setRateHz(60)
        assertTrue(pacer.isDue(frame60Hz))

        pacer.onSent(frame60Hz)
        throttling.enabled = false
        assertTrue(pacer.isDue(frame60Hz))
        throttling.enabled = true
        assertTrue(pacer.isDue(frame60Hz))
    }
}
