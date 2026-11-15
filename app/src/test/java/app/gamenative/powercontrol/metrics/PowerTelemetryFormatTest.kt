package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertFalse(PowerTelemetry.bucketExtras(0, null).containsKey("skin_temp_max"))
        assertEquals(41, PowerTelemetry.bucketExtras(41, null)["skin_temp_max"])
    }

    @Test
    fun thermalKeysOmittedWithoutReading() {
        assertEquals(emptyMap<String, Any>(), PowerTelemetry.thermalExtras(null, null))
        val extras = PowerTelemetry.thermalExtras(0.4649f, 2)
        assertEquals(0.46, extras["headroom_min"])
        assertEquals(2, extras["thermal_status_max"])
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

    @Test
    fun chargeCounterOmittedWhenUnsupported() {
        assertNull(PowerTelemetry.validChargeCounter(Long.MIN_VALUE))
        assertNull(PowerTelemetry.validChargeCounter(0L))
        assertEquals(3_900_000L, PowerTelemetry.validChargeCounter(3_900_000L))
        assertFalse(PowerTelemetry.bucketExtras(0, null).containsKey("batt_uah"))
        assertEquals(3_900_000L, PowerTelemetry.bucketExtras(0, 3_900_000L)["batt_uah"])
    }
}
