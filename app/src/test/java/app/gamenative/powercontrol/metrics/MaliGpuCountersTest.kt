package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaliGpuCountersTest {
    private val nan = Float.NaN

    @Test
    fun gateNeedsMaliRendererAndDeviceNode() {
        assertTrue(MaliGpuCounters.gateAllows("Mali-G710", "gs201", true))
        assertTrue(MaliGpuCounters.gateAllows("Immortalis-G720", "mt6989", true))
        assertFalse(MaliGpuCounters.gateAllows("Mali-G615", "mt6878", false))
    }

    @Test
    fun gateRejectsOtherVendors() {
        assertFalse(MaliGpuCounters.gateAllows("Adreno (TM) 740", "qcom", true))
        assertFalse(MaliGpuCounters.gateAllows("PowerVR D-Series DXT-48-1536", "mali", true))
        assertFalse(MaliGpuCounters.gateAllows("Samsung Xclipse 940", "s5e9945", true))
    }

    @Test
    fun gateFallsBackToHardwareOnlyWithoutRenderer() {
        assertTrue(MaliGpuCounters.gateAllows(null, "mali-g68", true))
        assertTrue(MaliGpuCounters.gateAllows("", "Mali", true))
        assertFalse(MaliGpuCounters.gateAllows(null, "gs201", true))
        assertFalse(MaliGpuCounters.gateAllows("llvmpipe", "mali", true))
    }

    @Test
    fun nativeUtilizationUsedWhenClockKnown() {
        // Pixel 7 vinstr under load: 993.6 M active cycles over 932 M GPU cycles.
        val e = MaliUtilEstimator()
        assertEquals(99, e.update(floatArrayOf(99.4f, 932f, 993.6e6f, 1.0f)))
        assertEquals(100, e.update(floatArrayOf(140f, 932f, 900e6f, 1.0f)))
        assertEquals(0, e.update(floatArrayOf(-3f, 932f, 0f, 1.0f)))
    }

    @Test
    fun peakEstimateWithoutClock() {
        // Redmi kinstr_prfcnt: no cycle count, utilization relative to the peak active rate.
        val e = MaliUtilEstimator()
        // Light first sample: the floor keeps it from reading 100 %.
        assertEquals(2, e.update(floatArrayOf(nan, nan, 10.6e6f, 1.0f)))
        assertEquals(100, e.update(floatArrayOf(nan, nan, 1040e6f, 1.0f)))
        assertEquals(50, e.update(floatArrayOf(nan, nan, 520e6f, 0.5f)))
        assertEquals(1040f, e.peakActiveMhz, 0.5f)
        e.reset()
        assertEquals(60, e.update(floatArrayOf(nan, nan, 300e6f, 1.0f)))
    }

    @Test
    fun implausibleSamplesDropped() {
        val e = MaliUtilEstimator()
        assertNull(e.update(floatArrayOf(nan, nan, 500e6f, 5.0f)))
        assertNull(e.update(floatArrayOf(nan, nan, 500e6f, 0.05f)))
        assertNull(e.update(floatArrayOf(nan, nan, nan, 1.0f)))
        assertNull(e.update(floatArrayOf(nan, nan)))
        // An absurd active rate does not raise the peak.
        assertEquals(100, e.update(floatArrayOf(nan, nan, 9000e6f, 1.0f)))
        assertEquals(0f, e.peakActiveMhz, 0f)
    }

    @Test
    fun counterBackendOnlyForCounterSource() {
        assertEquals("kinstr_prfcnt", PowerTelemetry.counterBackend("mali/counters", "kinstr_prfcnt"))
        assertNull(PowerTelemetry.counterBackend("kgsl-3d0/gpubusy", "vinstr"))
        assertNull(PowerTelemetry.counterBackend("mali/counters", null))
    }

    @Test
    fun exynosClockInKhzOrMhz() {
        assertEquals(351, GpuClock.parseExynosClockMhz("351000"))
        assertEquals(702, GpuClock.parseExynosClockMhz("702\n"))
        assertNull(GpuClock.parseExynosClockMhz("off"))
        assertNull(GpuClock.parseExynosClockMhz("0"))
    }
}
