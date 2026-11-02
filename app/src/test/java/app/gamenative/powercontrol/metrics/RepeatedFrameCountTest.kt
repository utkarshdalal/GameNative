package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedFrameCountTest {

    private fun ms(nanos: Long) = nanos / 1_000_000.0

    /** Timestamps from intervals given in display periods of a [refreshHz] panel, plus jitter in ms. */
    private fun timeline(refreshHz: Int, vararg intervals: Pair<Int, Double>): LongArray {
        val periodNs = 1_000_000_000.0 / refreshHz
        val out = LongArray(intervals.size + 1)
        out[0] = 1_000_000_000L
        for (i in intervals.indices) {
            val (vblanks, jitterMs) = intervals[i]
            out[i + 1] = out[i] + (vblanks * periodNs + jitterMs * 1_000_000.0).toLong()
        }
        return out
    }

    private fun steady(vblanks: Int, n: Int, jitterMs: Double = 0.0) =
        Array(n) { vblanks to if (it % 2 == 0) jitterMs else -jitterMs }

    private fun count(ts: LongArray, cap: Int, refresh: Int) =
        countSlowIntervals(ts, ts.size, repeatThresholdNs(cap, refresh))

    @Test
    fun `thresholds sit half a display period above the expected interval`() {
        assertEquals(25.0, ms(repeatThresholdNs(60, 60)), 0.01)
        assertEquals(20.83, ms(repeatThresholdNs(60, 120)), 0.01)
        assertEquals(41.67, ms(repeatThresholdNs(30, 60)), 0.01)
        assertEquals(25.0, ms(repeatThresholdNs(45, 60)), 0.01)
        assertEquals(29.17, ms(repeatThresholdNs(40, 120)), 0.01)
        // Cap above the refresh rate, and no cap: one display period expected.
        assertEquals(25.0, ms(repeatThresholdNs(90, 60)), 0.01)
        assertEquals(12.5, ms(repeatThresholdNs(0, 120)), 0.01)
        assertEquals(0L, repeatThresholdNs(60, 0))
    }

    @Test
    fun `cap 60 at 60 Hz counts each extra vblank`() {
        val ts = timeline(60, *steady(1, 20, 2.0), 2 to 0.0, *steady(1, 20, 2.0))
        assertEquals(1, count(ts, 60, 60))
    }

    @Test
    fun `cap 60 at 120 Hz counts a 3-vblank interval and not jitter`() {
        assertEquals(0, count(timeline(120, *steady(2, 40, 2.0)), 60, 120))
        assertEquals(1, count(timeline(120, *steady(2, 20, 2.0), 3 to 0.0, *steady(2, 20)), 60, 120))
    }

    @Test
    fun `cap 30 at 60 Hz counts a 3-vblank interval and not jitter`() {
        assertEquals(0, count(timeline(60, *steady(2, 40, 2.0)), 30, 60))
        assertEquals(1, count(timeline(60, *steady(2, 20), 3 to 0.0, *steady(2, 20)), 30, 60))
    }

    @Test
    fun `cap 45 at 60 Hz runs at one vblank and a doubled one is a repeat`() {
        assertEquals(0, count(timeline(60, *steady(1, 40, 2.0)), 45, 60))
        assertEquals(1, count(timeline(60, *steady(1, 20), 2 to 0.0, *steady(1, 20)), 45, 60))
    }

    @Test
    fun `cap 40 at 120 Hz counts a 4-vblank interval and not jitter`() {
        assertEquals(0, count(timeline(120, *steady(3, 40, 2.0)), 40, 120))
        assertEquals(1, count(timeline(120, *steady(3, 20), 4 to 0.0, *steady(3, 20)), 40, 120))
    }

    @Test
    fun `cap above refresh and no cap expect one display period`() {
        assertEquals(1, count(timeline(60, *steady(1, 20), 2 to 0.0), 120, 60))
        assertEquals(1, count(timeline(120, *steady(1, 20, 1.0), 2 to 0.0), 0, 120))
    }

    @Test
    fun `repeats are counted only in gamescope sessions without frame generation`() {
        assertTrue(repeatCountingActive(gamescopeSession = true, sampleStride = 1))
        assertFalse("X11 sessions keep the tuner as on master", repeatCountingActive(gamescopeSession = false, sampleStride = 1))
        assertFalse("LSFG x2 publishes generated frames", repeatCountingActive(gamescopeSession = true, sampleStride = 2))
        assertFalse(repeatCountingActive(gamescopeSession = false, sampleStride = 3))
    }

    @Test
    fun `no threshold counts nothing`() {
        val ts = timeline(60, 1 to 0.0, 5 to 0.0)
        assertEquals(0, countSlowIntervals(ts, ts.size, 0L))
    }
}
