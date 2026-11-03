package app.gamenative.ui.widget

import app.gamenative.powercontrol.metrics.Bottleneck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceHudTextTest {

    @Test
    fun fpsRowShowsDisplayedThenGameWhenTheGameOutrunsThePanel() {
        // Hades II uncapped: 287 frames produced, a 60 Hz panel shows at most 60.
        assertEquals("FPS 60 (287)", PerformanceHudText.fps(287f, 60f))
        assertEquals("FPS 120 (142)", PerformanceHudText.fps(142.4f, 120f))
    }

    @Test
    fun fpsRowIsOneNumberAtOrBelowThePanelRate() {
        assertEquals("FPS 59.8", PerformanceHudText.fps(59.8f, 60f))
        assertEquals("FPS 60.3", PerformanceHudText.fps(60.3f, 60f))
        assertEquals("FPS 42.0", PerformanceHudText.fps(42f, 60f))
        // No refresh rate known: the game's fps alone.
        assertEquals("FPS 287.0", PerformanceHudText.fps(287f, 0f))
        assertEquals("FPS 0.0", PerformanceHudText.fps(Float.NaN, 60f))
    }

    @Test
    fun gpuRow() {
        assertEquals("GPU 8%", PerformanceHudText.gpu(8, null))
        assertEquals("GPU 8% (0.5 ms)", PerformanceHudText.gpu(8, 0.5f))
        assertEquals("GPU 0.5 ms", PerformanceHudText.gpu(null, 0.5f))
        assertEquals("GPU --", PerformanceHudText.gpu(null, null))
        assertEquals("GPU --", PerformanceHudText.gpu(null, Float.NaN))
    }

    @Test
    fun gpuRowKeepsTheSourcePercentWhenAFrameTimeExists() {
        // Pixel 9: counters read 8% while the shim measured 0.5 ms per frame at 60 fps;
        // the row used to show the frame-time share (3%) instead of the source percent.
        assertEquals("GPU 8% (0.5 ms)", PerformanceHudText.gpu(8, 0.5f))
        assertEquals("GPU 91% (12.3 ms)", PerformanceHudText.gpu(91, 12.3f))
    }

    @Test
    fun gpuRowCompact() {
        assertEquals("GPU 8% 0.5ms", PerformanceHudText.gpu(8, 0.5f, compact = true))
        assertEquals("GPU 8%", PerformanceHudText.gpu(8, null, compact = true))
        assertEquals("GPU 0.5ms", PerformanceHudText.gpu(null, 0.5f, compact = true))
        assertEquals("GPU --", PerformanceHudText.gpu(null, null, compact = true))
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
        assertEquals("LIMITED BY: GPU+THERMAL", PerformanceHudText.bottleneck(Bottleneck.GPU_THERMAL, compact = false))
        assertEquals("LIMIT CPU", PerformanceHudText.bottleneck(Bottleneck.CPU, compact = true))
        assertEquals("LIMITED BY: FPS CAP", PerformanceHudText.bottleneck(Bottleneck.CAPPED, compact = false))
        assertEquals("LIMIT --", PerformanceHudText.bottleneck(Bottleneck.NONE, compact = true))
    }
}
