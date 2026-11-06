package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuUsageParseTest {

    @Test
    fun parsesPlainPercent() {
        assertEquals(45, SystemMetricsSources.parseUsagePercent("45\n"))
        assertEquals(0, SystemMetricsSources.parseUsagePercent("0"))
        assertEquals(100, SystemMetricsSources.parseUsagePercent("100"))
    }

    @Test
    fun parsesSamsungGpuBusy() {
        assertEquals(37, SystemMetricsSources.parseUsagePercent("37 %"))
        assertEquals(37, SystemMetricsSources.parseUsagePercent("37%"))
        assertEquals(37, SystemMetricsSources.parseUsagePercent("37 63"))
    }

    @Test
    fun parsesMediaTekGedUtilization() {
        assertEquals(36, SystemMetricsSources.parseUsagePercent("36 10 54"))
    }

    @Test
    fun parsesDevfreqLoadWithFrequency() {
        assertEquals(18, SystemMetricsSources.parseUsagePercent("18@614400000Hz"))
        assertEquals(18, SystemMetricsSources.parseUsagePercent("18@614400"))
        assertEquals(0, SystemMetricsSources.parseUsagePercent("0@0Hz"))
    }

    @Test
    fun parsesDecimalByTruncating() {
        assertEquals(45, SystemMetricsSources.parseUsagePercent("45.7"))
    }

    @Test
    fun rejectsEmptyOrNonPercent() {
        assertNull(SystemMetricsSources.parseUsagePercent(""))
        assertNull(SystemMetricsSources.parseUsagePercent("   "))
        assertNull(SystemMetricsSources.parseUsagePercent("N/A"))
        assertNull(SystemMetricsSources.parseUsagePercent("614400000"))
        assertNull(SystemMetricsSources.parseUsagePercent("Mali-G57 3 cores r0p1 0x9093".substringAfter(" cores")))
    }

    @Test
    fun ignoresDigitsInsideWords() {
        assertNull(SystemMetricsSources.parseUsagePercent("Mali0 busy"))
        assertEquals(42, SystemMetricsSources.parseUsagePercent("gpu0=42"))
    }

    @Test
    fun tagsSourceWithParentDirectory() {
        assertEquals("hal/gpu_utilization", SystemMetricsSources.usageSourceTag("/sys/kernel/ged/hal/gpu_utilization"))
        assertEquals("kgsl-3d0/gpubusy", SystemMetricsSources.usageSourceTag("/sys/class/kgsl/kgsl-3d0/gpubusy"))
        assertEquals("13000000.mali/load", SystemMetricsSources.usageSourceTag("/sys/class/devfreq/13000000.mali/load"))
    }

    @Test
    fun busyDeltaNeedsTwoReads() {
        val delta = GpuBusyDelta()
        assertNull(delta.update(100L, 1000L))
        assertEquals(50, delta.update(600L, 2000L))
        assertNull(delta.update(0L, 0L))
    }

    @Test
    fun skipsBandwidthDevfreqNodes() {
        listOf(
            "soc:qcom,gpubw",
            "soc:qcom,kgsl-busmon",
            "soc:qcom,cpu-cpu-llcc-bw",
            "1d84000.qcom,llcc-ddr-bw",
            "soc:qcom,cpubw-bwmon",
            "aoss_qmp-bus",
        )
            .forEach { assertTrue(it, SystemMetricsSources.isBusDevfreqNode(it)) }
        listOf("3d00000.qcom,kgsl-3d0", "gpu", "13000000.mali", "1c500000.mali", "17500000.g3d")
            .forEach { assertFalse(it, SystemMetricsSources.isBusDevfreqNode(it)) }
    }
}
