package app.gamenative.powercontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class HelioThermalPolicyTest {
    @Test
    fun moderateAndSevereStatusesApplyImmediateCaps() {
        HelioThermalPolicy.reset()
        assertEquals(30, HelioThermalPolicy.capForStatus(2, 60))
        assertEquals(24, HelioThermalPolicy.capForStatus(3, 60))
        assertEquals(20, HelioThermalPolicy.capForStatus(4, 60))
    }

    @Test
    fun criticalStatusCapsAtTwentyFps() {
        HelioThermalPolicy.reset()
        assertEquals(20, HelioThermalPolicy.capForStatus(6, 60))
    }

    @Test
    fun absentTelemetryDoesNotRaiseAnExistingCap() {
        HelioThermalPolicy.reset()
        assertEquals(24, HelioThermalPolicy.capForStatus(3, 60))
        assertEquals(24, HelioThermalPolicy.capForStatus(null, 60))
    }

    @Test
    fun recoveryIsGradual() {
        HelioThermalPolicy.reset()
        assertEquals(30, HelioThermalPolicy.capForStatus(2, 60))
        repeat(9) { assertEquals(30, HelioThermalPolicy.capForStatus(1, 60)) }
        assertEquals(35, HelioThermalPolicy.capForStatus(1, 60))
    }

    @Test
    fun zeroFpsIsNotModified() {
        HelioThermalPolicy.reset()
        assertEquals(0, HelioThermalPolicy.capForStatus(4, 0))
    }
}
