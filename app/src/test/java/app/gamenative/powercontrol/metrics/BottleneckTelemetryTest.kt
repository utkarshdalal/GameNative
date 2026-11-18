package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottleneckTelemetryTest {

    @Test
    fun bucketModeIsMostFrequentVerdict() {
        assertEquals("gpu+thermal", PowerTelemetry.bottleneckMode(mapOf("gpu" to 3, "gpu+thermal" to 5, "none" to 1)))
        assertNull(PowerTelemetry.bottleneckMode(emptyMap()))
    }

    @Test
    fun summaryWeighsBucketsBySamplesAndSplitsCombinedVerdicts() {
        val summary = PowerTelemetry.bottleneckSummary(
            listOf(
                mapOf("cpu" to 10, "capped" to 2),
                mapOf("gpu+thermal" to 6, "cpu" to 2),
            ),
        )
        assertEquals("cpu", summary["bottleneck_primary"])
        assertEquals(
            mapOf("cpu" to 0.6, "gpu" to 0.3, "thermal" to 0.3, "capped" to 0.1, "none" to 0.0),
            summary["bottleneck_share"],
        )
    }

    @Test
    fun summaryEmptyWithoutTicks() {
        assertTrue(PowerTelemetry.bottleneckSummary(listOf(emptyMap())).isEmpty())
    }

    @Test
    fun coreSamplerReportsBusiestCore() {
        val sampler = CoreLoadSampler()
        assertNull(sampler.sample(listOf("cpu0 100 0 0 900 0 0 0", "cpu1 100 0 0 900 0 0 0")))
        // cpu0: 100 busy of 200; cpu1: 180 busy of 200.
        assertEquals(90, sampler.sample(listOf("cpu0 200 0 0 1000 0 0 0", "cpu1 280 0 0 920 0 0 0")))
    }

    @Test
    fun coreSamplerSkipsOfflineCores() {
        val sampler = CoreLoadSampler()
        sampler.sample(listOf("cpu0 100 0 0 900 0 0 0", "cpu2 100 0 0 900 0 0 0"))
        assertEquals(50, sampler.sample(listOf("cpu0 200 0 0 1000 0 0 0", "cpu1 500 0 0 500 0 0 0", "cpu2 150 0 0 950 0 0 0")))
    }
}
