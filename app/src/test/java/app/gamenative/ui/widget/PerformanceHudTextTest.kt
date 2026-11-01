package app.gamenative.ui.widget

import app.gamenative.powercontrol.metrics.Bottleneck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceHudTextTest {

    @Test
    fun gpuRow() {
        assertEquals("GPU 38%", PerformanceHudText.gpu(38, null, 60f))
        assertEquals("GPU 12.3 ms (74%)", PerformanceHudText.gpu(38, 12.3f, 60f))
        assertEquals("GPU 12.3 ms", PerformanceHudText.gpu(null, 12.3f, 0f))
        assertEquals("GPU 40.0 ms (100%)", PerformanceHudText.gpu(null, 40f, 60f))
        assertEquals("GPU --", PerformanceHudText.gpu(null, null, 60f))
    }

    @Test
    fun heatRow() {
        assertEquals("HEAT 54%", PerformanceHudText.heat(0.54f, 1, compact = false))
        assertEquals("HEAT 97% throttling", PerformanceHudText.heat(0.97f, 2, compact = false))
        assertEquals("HEAT 97%", PerformanceHudText.heat(0.97f, 3, compact = true))
        assertNull(PerformanceHudText.heat(null, 3, compact = false))
        assertNull(PerformanceHudText.heat(Float.NaN, null, compact = false))
    }

    @Test
    fun bottleneckRow() {
        assertEquals("BOTTLENECK: GPU+THERMAL", PerformanceHudText.bottleneck(Bottleneck.GPU_THERMAL, compact = false))
        assertEquals("BN: CPU", PerformanceHudText.bottleneck(Bottleneck.CPU, compact = true))
    }
}
