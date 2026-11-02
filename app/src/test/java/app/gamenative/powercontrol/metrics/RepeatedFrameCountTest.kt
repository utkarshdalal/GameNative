package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatedFrameCountTest {

    private val periodNs = 16_666_667L
    private val thresholdNs = (1.5 * periodNs).toLong()

    private fun timeline(vararg gapsInPeriods: Int): LongArray {
        val out = LongArray(gapsInPeriods.size + 1)
        for (i in gapsInPeriods.indices) out[i + 1] = out[i] + gapsInPeriods[i] * periodNs
        return out
    }

    @Test
    fun `steady intervals count no repeats`() {
        val ts = timeline(1, 1, 1, 1, 1)
        assertEquals(0, countSlowIntervals(ts, ts.size, thresholdNs))
    }

    @Test
    fun `each doubled interval is one repeat`() {
        val ts = timeline(1, 2, 1, 1, 2, 1, 3)
        assertEquals(3, countSlowIntervals(ts, ts.size, thresholdNs))
    }

    @Test
    fun `no threshold counts nothing`() {
        val ts = timeline(1, 5)
        assertEquals(0, countSlowIntervals(ts, ts.size, 0L))
    }
}
