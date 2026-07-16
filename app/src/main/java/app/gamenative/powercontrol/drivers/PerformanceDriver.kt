package app.gamenative.powercontrol.drivers

import app.gamenative.powercontrol.PowerProfile

/**
 * Abstract base class for device-specific performance management drivers.
 *
 * Implementations include:
 * - PServerDriver: For AYN Odin, Retroid Pocket devices
 * - Future: Samsung Performance SDK driver
 */
abstract class PerformanceDriver {

    /**
     * Display unit for frequency values
     */
    enum class DisplayUnit {
        HZ,      // Display as formatted Hz (e.g., 2.4 GHz, 1800 MHz)
        INTEGER  // Display as raw integer value (e.g., 2400000)
    }

    // ========================================
    // General / Driver Support
    // ========================================

    /**
     * Check if this driver is supported on the current device
     */
    abstract fun isDriverSupported(): Boolean

    /**
     * Check if CPU governor control is supported
     */
    abstract fun isGovernorSupported(): Boolean

    /**
     * Check if GPU control is supported
     */
    abstract fun isGpuSupported(): Boolean

    /**
     * Check if RAM bus control is supported
     */
    abstract fun isBusSupported(): Boolean

    /**
     * Check if fan control is supported
     */
    abstract fun isFanSupported(): Boolean

    /**
     * Get the display unit for frequency values
     */
    abstract fun getDisplayUnit(): DisplayUnit

    /**
     * Start the performance driver
     */
    abstract fun start()

    /**
     * Stop the performance driver
     */
    abstract fun stop()

    /**
     * Begin a batch update session.
     * For PServerDriver, this starts collecting commands to execute in a single call.
     * For SamsungDriver, this is a no-op as CustomParams already handles batching.
     */
    abstract fun beginUpdate()

    /**
     * Commit all pending updates from the batch session.
     * For PServerDriver, this executes all collected commands in a single root call.
     * For SamsungDriver, this is a no-op as each setter already calls start(params).
     */
    abstract fun commit(): Boolean

    // ========================================
    // CPU Control
    // ========================================

    /**
     * Get current minimum CPU Value in KHz / Integer
     */
    abstract fun getCurrentMinCpuValue(): Long

    /**
     * Get current maximum CPU Value in KHz / Integer
     */
    abstract fun getCurrentMaxCpuValue(): Long

    /**
     * Get current CPU governor
     */
    abstract fun getCurrentGovernor(): String

    /**
     * Get available CPU governors
     */
    abstract fun getAvailableGovernors(): List<String>

    /**
     * Get available CPU frequencies in KHz
     */
    abstract fun getAvailableCpuFrequencies(): List<Long>

    /**
     * Set CPU governor
     */
    abstract fun setGovernor(governor: String): Boolean

    /**
     * Set minimum CPU Value in KHz / Integer
     */
    abstract fun setMinCpuValue(value: Long): Boolean

    /**
     * Set maximum CPU Value in KHz / Integer
     */
    abstract fun setMaxCpuValue(value: Long): Boolean

    // ========================================
    // GPU Control
    // ========================================

    /**
     * Get current GPU Value in KHz / Integer
     */
    abstract fun getCurrentGpuValue(): Long

    /**
     * Get available GPU frequencies in KHz
     */
    abstract fun getAvailableGpuFrequencies(): List<Long>

    /**
     * Get current GPU minimum power level (0 = fastest)
     */
    abstract fun getCurrentMinGpuPowerLevel(): Int

    /**
     * Get current GPU maximum power level (0 = fastest)
     */
    abstract fun getCurrentMaxGpuPowerLevel(): Int

    /**
     * Get number of GPU power levels available
     */
    abstract fun getNumGpuPowerLevels(): Int

    /**
     * Set GPU minimum power level (0 = fastest, higher = slower)
     */
    abstract fun setMinGpuPowerLevel(level: Int): Boolean

    /**
     * Set GPU maximum power level (0 = fastest, higher = slower)
     */
    abstract fun setMaxGpuPowerLevel(level: Int): Boolean

    /**
     * Get Default Profile
     */
    abstract fun getDefaultProfile(): PowerProfile

    // ========================================
    // RAM Bus Control
    // ========================================

    /**
     * Get current minimum RAM bus performance level
     */
    abstract fun getCurrentMinBusLevel(): Int

    /**
     * Get current maximum RAM bus performance level
     */
    abstract fun getCurrentMaxBusLevel(): Int

    /**
     * Get number of RAM bus levels available
     */
    abstract fun getNumBusLevels(): Int

    /**
     * Set minimum RAM bus performance level
     */
    abstract fun setMinBusLevel(level: Int): Boolean

    /**
     * Set maximum RAM bus performance level
     */
    abstract fun setMaxBusLevel(level: Int): Boolean
}
