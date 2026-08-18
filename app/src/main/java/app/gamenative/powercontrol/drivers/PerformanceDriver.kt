package app.gamenative.powercontrol.drivers

import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.autotuning.DeviceGate
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset

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
    open fun isGovernorSupported(): Boolean = false

    /**
     * Check if GPU control is supported
     */
    open fun isGpuSupported(): Boolean = false

    /**
     * Check if RAM bus control is supported
     */
    open fun isBusSupported(): Boolean = false

    /**
     * Check if fan control is supported
     */
    open fun isFanSupported(): Boolean = false

    /**
     * Check if this driver can cap each CPU cluster separately, as the cluster tuner needs
     */
    open fun isPerClusterSupported(): Boolean = false

    /**
     * Check if this driver can pin process on specific cpu
     */
    open fun isCpuPinningSupported(): Boolean = false

    /**
     * Get the display unit for frequency values
     */
    abstract fun getDisplayUnit(): DisplayUnit

    /**
     * Start the performance driver
     */
    open fun start() {}

    /**
     * Stop the performance driver
     */
    open fun stop() {}

    /**
     * Reset the performance driver
     */
    open fun reset() {}

    /**
     * Begin a batch update session.
     * For PServerDriver, this starts collecting commands to execute in a single call.
     * For SamsungDriver, this is a no-op as CustomParams already handles batching.
     */
    open fun beginUpdate() {}

    /**
     * Commit all pending updates from the batch session.
     * For PServerDriver, this executes all collected commands in a single root call.
     * For SamsungDriver, this is a no-op as each setter already calls start(params).
     */
    open fun commit(): Boolean = true

    // ========================================
    // CPU Control
    // ========================================

    /**
     * Get current minimum CPU Value in KHz / Integer
     */
    open fun getCurrentMinCpuValue(): Long = 0L

    /**
     * Get current maximum CPU Value in KHz / Integer
     */
    open fun getCurrentMaxCpuValue(): Long = 0L

    /**
     * Get current CPU governor
     */
    open fun getCurrentGovernor(): String = "none"

    /**
     * Get available CPU governors
     */
    open fun getAvailableGovernors(): List<String> = emptyList()

    /**
     * Get available CPU frequencies in KHz
     */
    open fun getAvailableCpuFrequencies(): List<Long> = emptyList()

    /**
     * Set CPU governor
     */
    open fun setGovernor(governor: String): Boolean = false

    /**
     * Set minimum CPU Value in KHz / Integer
     */
    open fun setMinCpuValue(value: Long): Boolean = false

    /**
     * Set maximum CPU Value in KHz / Integer
     */
    open fun setMaxCpuValue(value: Long): Boolean = false

    // ========================================
    // GPU Control
    // ========================================

    /**
     * Get current GPU Value in KHz / Integer
     */
    open fun getCurrentGpuValue(): Long = 0L

    /**
     * Get available GPU frequencies in KHz
     */
    open fun getAvailableGpuFrequencies(): List<Long> = emptyList()

    /**
     * Get current GPU minimum power level (0 = fastest)
     */
    open fun getCurrentMinGpuPowerLevel(): Int = 0

    /**
     * Get current GPU maximum power level (0 = fastest)
     */
    open fun getCurrentMaxGpuPowerLevel(): Int = 0

    /**
     * Get number of GPU power levels available
     */
    open fun getNumGpuPowerLevels(): Int = 0

    /**
     * Set GPU minimum power level (0 = fastest, higher = slower)
     */
    open fun setMinGpuPowerLevel(level: Int): Boolean = false

    /**
     * Set GPU maximum power level (0 = fastest, higher = slower)
     */
    open fun setMaxGpuPowerLevel(level: Int): Boolean = false

    /**
     * Get Default Profile
     */
    open fun getDefaultProfile(): PowerProfile {
        // Return a dummy Balanced profile for devices without driver support
        return PowerProfile(
            enableAutoTuning = false,
            enableAdaptiveFpsCap = false,
            enableGamePinning = false,
            name = PerformancePreset.BALANCED.displayName,
            governor = CpuGovernor.SCHEDUTIL,
            minCpuFreq = 0,
            maxCpuFreq = 0,
            minGpuPowerLevel = 0,
            maxGpuPowerLevel = 0
        )
    }

    // ========================================
    // RAM Bus Control
    // ========================================

    /**
     * Get current minimum RAM bus performance level
     */
    open fun getCurrentMinBusLevel(): Int = 0

    /**
     * Get current maximum RAM bus performance level
     */
    open fun getCurrentMaxBusLevel(): Int = 0

    /**
     * Get number of RAM bus levels available
     */
    open fun getNumBusLevels(): Int = 0

    /**
     * Set minimum RAM bus performance level
     */
    open fun setMinBusLevel(level: Int): Boolean = false

    /**
     * Set maximum RAM bus performance level
     */
    open fun setMaxBusLevel(level: Int): Boolean = false

    // ========================================
    // Utility functions
    // ========================================

    /**
     * Read File using driver
     */
    open fun readFile(path: String): String? = null
}
