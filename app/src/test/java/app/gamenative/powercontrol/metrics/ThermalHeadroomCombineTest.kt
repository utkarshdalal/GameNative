package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalHeadroomCombineTest {

    private fun combine(platform: Int?, kernel: Int?, platformReports: Boolean) =
        ThermalHeadroom.combine(platform, kernel, platformReports)

    @Test
    fun reportingPlatformWinsEvenWhenTheKernelSaysMore() {
        assertEquals(1 to ThermalHeadroom.SOURCE_PLATFORM, combine(platform = 1, kernel = 3, platformReports = true))
        assertEquals(0 to ThermalHeadroom.SOURCE_PLATFORM, combine(platform = 0, kernel = null, platformReports = true))
    }

    @Test
    fun silentPlatformFallsBackToTheKernel() {
        // Retroid Pocket 6 / AYN Thor: thermal HAL not ready, PowerManager says NONE, headroom is NaN.
        assertEquals(3 to ThermalHeadroom.SOURCE_KERNEL, combine(platform = 0, kernel = 3, platformReports = false))
        assertEquals(0 to ThermalHeadroom.SOURCE_KERNEL, combine(platform = 0, kernel = 0, platformReports = false))
    }

    @Test
    fun kernelWorksWithoutAnyPlatformStatus() {
        // Below Android 10 there is no PowerManager thermal status at all.
        assertEquals(2 to ThermalHeadroom.SOURCE_KERNEL, combine(platform = null, kernel = 2, platformReports = false))
    }

    @Test
    fun noKernelDevicesKeepsThePlatformReading() {
        assertEquals(0 to ThermalHeadroom.SOURCE_PLATFORM, combine(platform = 0, kernel = null, platformReports = false))
        assertEquals(null to null, combine(platform = null, kernel = null, platformReports = false))
    }
}
