package app.gamenative.powercontrol.drivers

import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import timber.log.Timber

class NoOpPerformanceDriver : PerformanceDriver() {

    companion object {
        private const val TAG = "NoOpPerformanceDriver"
    }

    init {
        Timber.tag(TAG).w("No performance driver available on this device")
    }

    override fun isDriverSupported(): Boolean = false

    override fun isGovernorSupported(): Boolean = false

    override fun isGpuSupported(): Boolean = false

    override fun isFanSupported(): Boolean = false

    override fun getDisplayUnit(): DisplayUnit = DisplayUnit.INTEGER

    override fun start() {}

    override fun stop() {}

    override fun beginUpdate() {}

    override fun commit(): Boolean = false

    override fun getCurrentMinCpuValue(): Long = 0L

    override fun getCurrentMaxCpuValue(): Long = 0L

    override fun getCurrentGovernor(): String = "none"

    override fun getAvailableGovernors(): List<String> = emptyList()

    override fun getAvailableCpuFrequencies(): List<Long> = emptyList()

    override fun setGovernor(governor: String): Boolean = false

    override fun setMinCpuValue(value: Long): Boolean = false

    override fun setMaxCpuValue(value: Long): Boolean = false

    override fun getCurrentGpuValue(): Long = 0L

    override fun getAvailableGpuFrequencies(): List<Long> = emptyList()

    override fun getCurrentMinGpuPowerLevel(): Int = 0

    override fun getCurrentMaxGpuPowerLevel(): Int = 0

    override fun getNumGpuPowerLevels(): Int = 0

    override fun setMinGpuPowerLevel(level: Int): Boolean = false

    override fun setMaxGpuPowerLevel(level: Int): Boolean = false

    override fun isBusSupported(): Boolean = false

    override fun getCurrentMinBusLevel(): Int = 0

    override fun getCurrentMaxBusLevel(): Int = 0

    override fun getNumBusLevels(): Int = 0

    override fun setMinBusLevel(level: Int): Boolean = false

    override fun setMaxBusLevel(level: Int): Boolean = false

    override fun getDefaultProfile(): PowerProfile {
        // Return a dummy Balanced profile for devices without driver support
        return PowerProfile(
            name = PerformancePreset.BALANCED.displayName,
            governor = CpuGovernor.SCHEDUTIL,
            minCpuFreq = 0,
            maxCpuFreq = 0,
            minGpuPowerLevel = 0,
            maxGpuPowerLevel = 0
        )
    }
}
