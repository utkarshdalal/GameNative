package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
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
}
