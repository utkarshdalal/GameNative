package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PowerTelemetryFormatTest {

    @Test
    fun contiguousCoresCollapseToRange() {
        assertEquals("4-7", PowerTelemetry.cpuRanges(listOf(7, 4, 6, 5)))
    }

    @Test
    fun mixedCoresKeepSinglesAndRanges() {
        assertEquals("0,2-4,7", PowerTelemetry.cpuRanges(listOf(0, 2, 3, 4, 7)))
    }

    @Test
    fun singleAndEmpty() {
        assertEquals("3", PowerTelemetry.cpuRanges(setOf(3)))
        assertEquals("", PowerTelemetry.cpuRanges(emptyList()))
    }

    @Test
    fun duplicatesIgnored() {
        assertEquals("1-2", PowerTelemetry.cpuRanges(listOf(2, 1, 2)))
    }

    @Test
    fun skinTempOmittedWithoutReading() {
        assertFalse(PowerTelemetry.bucketExtras(0).containsKey("skin_temp_max"))
        assertEquals(41, PowerTelemetry.bucketExtras(41)["skin_temp_max"])
    }

    @Test
    fun skinPathsKeepOnlySkinZones() {
        val zones = listOf(
            "cpu-0-0" to "/sys/class/thermal/thermal_zone1/temp",
            "skin-therm" to "/sys/class/thermal/thermal_zone7/temp",
            "xo-therm-skin" to "/sys/class/thermal/thermal_zone9/temp",
            "skin-therm" to "/sys/class/thermal/thermal_zone7/temp",
        )
        assertEquals(
            listOf("/sys/class/thermal/thermal_zone7/temp", "/sys/class/thermal/thermal_zone9/temp"),
            SystemMetricsSources.skinPathsOf(zones),
        )
        assertEquals(emptyList<String>(), SystemMetricsSources.skinPathsOf(zones.take(1)))
    }
}
