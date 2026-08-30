package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTimeRingTest {
    @Test
    fun computeFrameWindowStats_rejectsSubMillisecondEventStormAsImplausibleFps() {
        // Mirrors the debug-log failure: one ~37us interval produced ~27k FPS
        // and caused PerformanceAutoTuner to downclock CPU/GPU/bus.
        val timestamps = longArrayOf(1_000_000_000L, 1_000_037_070L)
        val scratch = LongArray(timestamps.size)

        val stats = computeFrameWindowStats(
            timestampsNs = timestamps,
            count = timestamps.size,
            slowFrameThresholdNs = 25_000_000L,
            deltaScratch = scratch,
        )

        assertEquals(0f, stats.fps)
        assertEquals(0, stats.totalFrameCount)
    }

    @Test
    fun computeFrameWindowStats_keepsNormalHighRefreshFrameData() {
        val timestamps = longArrayOf(
            1_000_000_000L,
            1_004_166_667L,
            1_008_333_334L,
            1_012_500_001L,
        )
        val scratch = LongArray(timestamps.size)

        val stats = computeFrameWindowStats(
            timestampsNs = timestamps,
            count = timestamps.size,
            slowFrameThresholdNs = 10_000_000L,
            deltaScratch = scratch,
        )

        assertTrue(stats.fps in 239f..241f)
        assertEquals(3, stats.totalFrameCount)
    }
}
