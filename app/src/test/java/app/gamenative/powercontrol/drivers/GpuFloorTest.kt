package app.gamenative.powercontrol.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPU floor clamp on a kgsl table with nine levels (sysfs 0 = fastest, 8 = slowest) and a device
 * whose own min_pwrlevel is 2.
 */
class GpuFloorTest {

    private val numLevels = 9
    private val stockFloor = 2

    @Test
    fun `a floor slower than the device floor is raised to it`() {
        assertEquals(2, GpuFloor.sysfsMinLevel(requested = 4, stockFloor = stockFloor, maxInForce = 0))
        assertEquals(2, GpuFloor.sysfsMinLevel(requested = 8, stockFloor = stockFloor, maxInForce = 0))
    }

    @Test
    fun `a floor at or above the device floor is written as is`() {
        assertEquals(2, GpuFloor.sysfsMinLevel(requested = 2, stockFloor = stockFloor, maxInForce = 0))
        assertEquals(1, GpuFloor.sysfsMinLevel(requested = 1, stockFloor = stockFloor, maxInForce = 0))
    }

    @Test
    fun `a device whose own floor is the slowest level is unchanged`() {
        for (requested in 0 until numLevels) {
            assertEquals(requested, GpuFloor.sysfsMinLevel(requested, stockFloor = numLevels - 1, maxInForce = 0))
        }
    }

    @Test
    fun `without a stock value the request is written as is`() {
        assertEquals(4, GpuFloor.sysfsMinLevel(requested = 4, stockFloor = null, maxInForce = 0))
    }

    @Test
    fun `a ceiling slower than the device floor keeps the floor at the ceiling`() {
        assertEquals(6, GpuFloor.sysfsMinLevel(requested = 8, stockFloor = stockFloor, maxInForce = 6))
        assertEquals(4, GpuFloor.sysfsMinLevel(requested = 4, stockFloor = stockFloor, maxInForce = 6))
    }

    @Test
    fun `stock floor converts to a ui level`() {
        assertEquals(6, GpuFloor.uiLevel(stockFloor, numLevels))
        assertEquals(0, GpuFloor.uiLevel(numLevels - 1, numLevels))
        assertNull(GpuFloor.uiLevel(null, numLevels))
        assertNull(GpuFloor.uiLevel(stockFloor, 0))
    }

    @Test
    fun `tuner steps start at the device floor`() {
        assertEquals(listOf(6, 7, 8), GpuFloor.capSteps(numLevels, GpuFloor.uiLevel(stockFloor, numLevels)))
    }

    @Test
    fun `tuner steps cover every level without a floor or with the slowest floor`() {
        assertEquals((0 until numLevels).toList(), GpuFloor.capSteps(numLevels, null))
        assertEquals((0 until numLevels).toList(), GpuFloor.capSteps(numLevels, 0))
    }

    @Test
    fun `tuner has a single step when the device floor is the fastest level`() {
        assertEquals(listOf(8), GpuFloor.capSteps(numLevels, GpuFloor.uiLevel(0, numLevels)))
    }

    @Test
    fun `no gpu levels means no tuner steps`() {
        assertTrue(GpuFloor.capSteps(0, 3).isEmpty())
    }
}
