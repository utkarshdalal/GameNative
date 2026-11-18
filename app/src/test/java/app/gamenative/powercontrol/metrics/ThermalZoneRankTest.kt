package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalZoneRankTest {

    private fun cpu(type: String) = SystemMetricsSources.cpuTempRank(type)
    private fun gpu(type: String) = SystemMetricsSources.gpuTempRank(type)

    @Test
    fun excludesNonDieZones() {
        val types = listOf(
            "cpu-ocp", "vdroop1", "soft_ocp_cpu", "pm8550b-bcl-lvl0", "socd", "battery", "usb-therm",
            "skin-msm-therm", "quiet-therm", "neutral-therm", "disp-therm", "modem-0-usr", "charger-therm",
            "pa-therm0", "xo-ntc", "pm8550-tz", "pmic-tz", "gpu-ocp",
        )
        for (type in types) {
            assertNull(type, cpu(type))
            assertNull(type, gpu(type))
        }
    }

    @Test
    fun socMatchesOnlyAsItsOwnName() {
        assertEquals(8, cpu("soc"))
        assertEquals(8, cpu("soc_thermal"))
        assertEquals(8, cpu("soc-thermal"))
        assertNull(cpu("mtktssoc"))
        assertNull(cpu("socd"))
    }

    @Test
    fun cpuOrderKeepsCpuNamedAndClustersAboveSoc() {
        val ranks = listOf("cpu-silicon", "cpu-0-0-usr", "cpuss-0", "s5p-tmu", "tsens_tz_sensor1", "cluster0", "mid", "soc")
            .map { cpu(it)!! }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(7, cpu("big"))
        assertEquals(7, cpu("mid"))
        assertEquals(7, cpu("little"))
        assertNull(cpu("gpuss-0"))
        assertTrue(cpu("cpu-1-0")!! < cpu("soc")!!)
    }

    @Test
    fun gpuOrder() {
        assertEquals(0, gpu("gpu-silicon"))
        assertEquals(1, gpu("gpuss-0"))
        assertEquals(2, gpu("g3d"))
        assertEquals(3, gpu("kgsl"))
        assertEquals(4, gpu("mali"))
        assertNull(gpu("cpu-0-0"))
    }
}
