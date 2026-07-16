package app.gamenative.powercontrol

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.powercontrol.drivers.NoOpPerformanceDriver
import app.gamenative.powercontrol.drivers.PServerDriver
import app.gamenative.powercontrol.drivers.PerformanceDriver
import app.gamenative.powercontrol.drivers.SamsungPerformanceDriver
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Manager for CPU and GPU performance control.
 * Provides a unified interface for CPU frequency, governor, and GPU power management.
 * Uses a PerformanceDriver implementation for device-specific operations.
 */
object PowerManager {
    private var driver: PerformanceDriver? = null

    /**
     * The currently active power profile.
     * Updated when settings change, used for saving on stop.
     */
    var currentProfile: PowerProfile? = null
        private set

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
            PServerDriver(context.applicationContext).isDriverSupported() -> {
                Timber.tag("PowerManager").i("Using PServer Driver")
                PServerDriver(context.applicationContext)
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

    data class BusInfo(
        val minBusLevel: Int,
        val maxBusLevel: Int,
        val numBusLevels: Int
    )

    // ========================================
    // General Settings
    // ========================================

    /**
     * Start the performance driver and restore saved profile if available
     */
    fun start() {
        getDriver().start()
        restoreSavedProfile()
    }

    /**
     * Stop the performance driver and save current profile
     */
    fun stop() {
        // Save the current profile if available, otherwise read from driver
        saveProfile()
        getDriver().stop()
    }

    /**
     * Update the current profile reference.
     * Should be called when the UI changes the active profile.
     */
    fun setCurrentProfile(profile: PowerProfile) {
        currentProfile = profile
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

    /**
     * Begin a batch update session.
     * For PServerDriver, this starts collecting commands to execute in a single call.
     * For SamsungDriver, this is a no-op as CustomParams already handles batching.
     */
    fun beginUpdate() {
        getDriver().beginUpdate()
    }

    /**
     * Commit all pending updates from the batch session.
     * For PServerDriver, this executes all collected commands in a single root call.
     * For SamsungDriver, this is a no-op as each setter already calls start(params).
     */
    fun commit(): Boolean {
        return getDriver().commit()
    }

    /**
     * Builder for batch updates. Provides a fluent API for setting multiple values.
     * Usage:
     * ```
     * PowerManager.update {
     *     governor(profile.governor.governorName)
     *     minCpuValue(profile.minFreq)
     *     maxCpuValue(profile.maxFreq)
     * }
     * ```
     */
    class UpdateBuilder {
        fun name(name: String): UpdateBuilder {
            setProfileName(name)
            return this
        }
        fun governor(governor: String): UpdateBuilder {
            setGovernor(governor)
            return this
        }

        fun minCpuValue(value: Long): UpdateBuilder {
            setMinCpuValue(value)
            return this
        }

        fun maxCpuValue(value: Long): UpdateBuilder {
            setMaxCpuValue(value)
            return this
        }

        fun minGpuPowerLevel(level: Int): UpdateBuilder {
            setMinGpuPowerLevel(level)
            return this
        }

        fun maxGpuPowerLevel(level: Int): UpdateBuilder {
            setMaxGpuPowerLevel(level)
            return this
        }

        fun minBusLevel(level: Int): UpdateBuilder {
            setMinBusLevel(level)
            return this
        }

        fun maxBusLevel(level: Int): UpdateBuilder {
            setMaxBusLevel(level)
            return this
        }

        fun build(): Boolean {
            return commit()
        }
    }

    /**
     * Execute a batch update using a builder pattern.
     * All updates are collected and executed in a single call for PServerDriver.
     */
    inline fun update(block: UpdateBuilder.() -> Unit): Boolean {
        beginUpdate()
        val builder = UpdateBuilder()
        builder.block()
        return builder.build()
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

    fun setProfileName(name: String) {
        currentProfile?.name = name
    }

    /**
     * Set CPU governor
     */
    fun setGovernor(governor: String): Boolean {
        val result = getDriver().setGovernor(governor)
        if (result) {
            val cpuGovernor = CpuGovernor.fromString(governor)
            if (cpuGovernor != null) {
                currentProfile?.governor = cpuGovernor
            }
        }
        return result
    }

    /**
     * Set minimum CPU Value in KHz / Integer
     */
    fun setMinCpuValue(frequency: Long): Boolean {
        val result = getDriver().setMinCpuValue(frequency)
        if (result) {
            currentProfile?.minCpuFreq = frequency
        }
        return result
    }

    /**
     * Set maximum CPU Value in KHz / Integer
     */
    fun setMaxCpuValue(frequency: Long): Boolean {
        val result = getDriver().setMaxCpuValue(frequency)
        if (result) {
            currentProfile?.maxCpuFreq = frequency
        }
        return result
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
        val result = getDriver().setMinGpuPowerLevel(level)
        if (result) {
            currentProfile?.minGpuPowerLevel = level
        }
        return result
    }

    /**
     * Set maximum GPU power level (0 = fastest, higher = slower)
     */
    fun setMaxGpuPowerLevel(level: Int): Boolean {
        val result = getDriver().setMaxGpuPowerLevel(level)
        if (result) {
            currentProfile?.maxGpuPowerLevel = level
        }
        return result
    }

    // ========================================
    // RAM Bus Control
    // ========================================

    fun isBusSupported(): Boolean {
        return getDriver().isBusSupported()
    }

    fun getBusInfo(): BusInfo? {
        return try {
            if (!getDriver().isBusSupported()) return null

            BusInfo(
                minBusLevel = getDriver().getCurrentMinBusLevel(),
                maxBusLevel = getDriver().getCurrentMaxBusLevel(),
                numBusLevels = getDriver().getNumBusLevels()
            )
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to get RAM bus info")
            null
        }
    }

    fun setMinBusLevel(level: Int): Boolean {
        val result = getDriver().setMinBusLevel(level)

        if (result) {
            currentProfile?.minBusLevel = level
        }

        return result
    }

    fun setMaxBusLevel(level: Int): Boolean {
        val result = getDriver().setMaxBusLevel(level)

        if (result) {
            currentProfile?.maxBusLevel = level
        }

        return result
    }

    // ========================================
    // Profile Persistence
    // ========================================

    /**
     * Save a power profile to preferences
     */
    fun saveProfile() {
        try {
            val json = if (currentProfile != null) {
                Json.encodeToString(currentProfile)
            } else ""
            PrefManager.powerControlProfile = json
            Timber.tag("PowerManager").d("Saved power profile: $json")
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to save power profile")
        }
    }

    /**
     * Restore the saved power profile from preferences
     */
    private fun restoreSavedProfile() {
        try {
            val json = PrefManager.powerControlProfile
            if (json.isEmpty()) {
                currentProfile = driver?.getDefaultProfile()
                Timber.tag("PowerManager").d("No saved profile to restore")
                return
            }

            currentProfile = Json.decodeFromString<PowerProfile>(json)
            Timber.tag("PowerManager").d("Restoring power profile: $json")

            val success = update {
                governor(currentProfile!!.governor.governorName)
                minCpuValue(currentProfile!!.minCpuFreq)
                maxCpuValue(currentProfile!!.maxCpuFreq)
                if (isGpuSupported()) {
                    minGpuPowerLevel(currentProfile!!.minGpuPowerLevel)
                    maxGpuPowerLevel(currentProfile!!.maxGpuPowerLevel)
                }
                if (isBusSupported()) {
                    minBusLevel(currentProfile!!.minBusLevel)
                    maxBusLevel(currentProfile!!.maxBusLevel)
                }
            }

            if (success) {
                Timber.tag("PowerManager").i("Successfully restored power profile")
            } else {
                Timber.tag("PowerManager").w("Failed to restore power profile")
            }
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to restore power profile")
        }
    }
}
