package app.gamenative.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReportDesignCapTest {

    @Test
    fun steadyThirtyAtLowLoadIsDesignCap() {
        assertTrue(SessionReport.designCapDetected(listOf(30, 29, 31), listOf(40 to 30, 50 to null, 45 to 20)))
    }

    @Test
    fun saturatedCoreIsNotDesignCap() {
        assertFalse(SessionReport.designCapDetected(listOf(30, 30, 30), listOf(95 to 30, 92 to 30, 40 to 30)))
    }

    @Test
    fun offTargetFpsIsNotDesignCap() {
        assertFalse(SessionReport.designCapDetected(listOf(45, 44), listOf(30 to 30, 30 to 30)))
    }

    @Test
    fun missingCoreLoadIsNotDesignCap() {
        assertFalse(SessionReport.designCapDetected(listOf(60, 60), listOf(null to 20, null to 20)))
        assertFalse(SessionReport.designCapDetected(emptyList(), emptyList()))
    }
}
