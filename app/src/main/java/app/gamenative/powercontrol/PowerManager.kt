package app.gamenative.powercontrol

import android.content.Context
import app.gamenative.powercontrol.drivers.NoOpPerformanceDriver
import app.gamenative.powercontrol.drivers.PServerDriver
import app.gamenative.powercontrol.drivers.PerformanceDriver
import app.gamenative.powercontrol.drivers.SamsungPerformanceDriver
import timber.log.Timber

/**
 * Manager for CPU and GPU performance control.
 * Provides a unified interface for CPU frequency, governor, and GPU power management.
 * Uses a PerformanceDriver implementation for device-specific operations.
 */
object PowerManager {
    private var driver: PerformanceDriver? = null

    /**
     * Initialize PowerManager with application context.
     * Should be called once during application startup.
     */
    fun initialize(context: Context) {
        if (driver != null) return

        driver = when {
            SamsungPerformanceDriver.isSamsungDevice() -> {
                val samsungDriver = SamsungPerformanceDriver(context.applicationContext)
                if (samsungDriver.isDriverSupported()) {
                    Timber.tag("PowerManager").i("Using Samsung Performance Driver")
                    samsungDriver
                } else {
                    Timber.tag("PowerManager").w("Samsung device detected but Performance SDK not available")
                    NoOpPerformanceDriver()
                }
            }
            PServerDriver().isDriverSupported() -> {
                Timber.tag("PowerManager").i("Using PServer Driver")
                PServerDriver()
            }
            else -> {
                Timber.tag("PowerManager").w("No performance driver available")
                NoOpPerformanceDriver()
            }
        }
    }

    private fun getDriver(): PerformanceDriver {
        return driver ?: NoOpPerformanceDriver().also {
            Timber.tag("PowerManager").w("PowerManager not initialized, using NoOpPerformanceDriver as fallback")
            driver = it
        }
    }

    data class CpuInfo(
        val currentGovernor: String,
        val currentMinValue: Long,
        val currentMaxValue: Long
    )

    data class GpuInfo(
        val currentGpuValue: Long,
        val minGpuPowerLevel: Int,
        val maxGpuPowerLevel: Int,
        val numGpuPowerLevels: Int
    )

    // ========================================
    // General Settings
    // ========================================

    /**
     * Start the performance driver
     */
    fun start() {
        getDriver().start()
    }

    /**
     * Stop the performance driver
     */
    fun stop() {
        getDriver().stop()
    }

    /**
     * Check if PServer driver is available
     */
    fun isPServerAvailable(): Boolean {
        return getDriver().isDriverSupported()
    }

    /**
     * Get display unit preference for frequency values
     */
    fun getDisplayUnit(): PerformanceDriver.DisplayUnit {
        return getDriver().getDisplayUnit()
    }

    // ========================================
    // CPU Control
    // ========================================

    /**
     * Get current CPU information (governor, min/max frequencies)
     */
    fun getCpuInfo(): CpuInfo? {
        return try {
            CpuInfo(
                currentGovernor = getDriver().getCurrentGovernor(),
                currentMinValue = getDriver().getCurrentMinCpuValue(),
                currentMaxValue = getDriver().getCurrentMaxCpuValue()
            )
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to get CPU info")
            null
        }
    }

    /**
     * Get list of available CPU governors
     */
    fun getAvailableGovernors(): List<String> {
        return getDriver().getAvailableGovernors()
    }

    /**
     * Get list of available CPU frequencies in KHz
     */
    fun getAvailableCpuFrequencies(): List<Long> {
        return getDriver().getAvailableCpuFrequencies()
    }

    /**
     * Set CPU governor
     */
    fun setGovernor(governor: String): Boolean {
        return getDriver().setGovernor(governor)
    }

    /**
     * Set minimum CPU Value in KHz / Integer
     */
    fun setMinCpuValue(frequency: Long): Boolean {
        return getDriver().setMinCpuValue(frequency)
    }

    /**
     * Set maximum CPU Value in KHz / Integer
     */
    fun setMaxCpuValue(frequency: Long): Boolean {
        return getDriver().setMaxCpuValue(frequency)
    }

    // ========================================
    // GPU Control
    // ========================================

    /**
     * Check if GPU control is supported
     */
    fun isGpuSupported(): Boolean {
        return getDriver().isGpuSupported()
    }

    /**
     * Get current GPU information (frequency, power levels)
     */
    fun getGpuInfo(): GpuInfo? {
        return try {
            if (!getDriver().isGpuSupported()) return null
            GpuInfo(
                currentGpuValue = getDriver().getCurrentGpuValue(),
                minGpuPowerLevel = getDriver().getCurrentMinGpuPowerLevel(),
                maxGpuPowerLevel = getDriver().getCurrentMaxGpuPowerLevel(),
                numGpuPowerLevels = getDriver().getNumGpuPowerLevels()
            )
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to get GPU info")
            null
        }
    }

    /**
     * Get list of available GPU frequencies in KHz
     */
    fun getAvailableGpuFrequencies(): List<Long> {
        return getDriver().getAvailableGpuFrequencies()
    }

    /**
     * Set minimum GPU power level (0 = fastest, higher = slower)
     */
    fun setMinGpuPowerLevel(level: Int): Boolean {
        return getDriver().setMinGpuPowerLevel(level)
    }

    /**
     * Set maximum GPU power level (0 = fastest, higher = slower)
     */
    fun setMaxGpuPowerLevel(level: Int): Boolean {
        return getDriver().setMaxGpuPowerLevel(level)
    }
}
