package app.gamenative.powercontrol.drivers

import android.content.Context
import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.autotuning.DeviceGate
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import com.samsung.sdk.sperf.CustomParams
import com.samsung.sdk.sperf.PerformanceManager
import com.samsung.sdk.sperf.SPerf
import timber.log.Timber

class SamsungPerformanceDriver(private val context: Context) : PerformanceDriver() {

    companion object {
        private const val TAG = "SamsungPerformanceDriver"

        private const val DEFAULT_TIMEOUT_MS = 0

        private const val CPU_LEVEL_MIN = 0
        private const val CPU_LEVEL_MAX = 4
        private const val GPU_LEVEL_MIN = 0
        private const val GPU_LEVEL_MAX = 4

        private const val BUS_LEVEL_MIN = 0
        private const val BUS_LEVEL_MAX = 4

        /**
         * Check if device is a Samsung device
         * This is a quick check before attempting SDK initialization
         */
        fun isSamsungDevice(): Boolean {
            return android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        }
    }

    private val performanceManager: PerformanceManager?
    private var isSamsungSdkAvailable: Boolean = false

    private var currentCpuMinLevel: Int = CPU_LEVEL_MIN
    private var currentCpuMaxLevel: Int = CPU_LEVEL_MAX
    private var currentGpuMinLevel: Int = GPU_LEVEL_MIN
    private var currentGpuMaxLevel: Int = GPU_LEVEL_MAX
    private var currentBusMinLevel: Int = BUS_LEVEL_MIN
    private var currentBusMaxLevel: Int = BUS_LEVEL_MAX

    init {
        performanceManager = try {
            SPerf.setDebugModeEnabled(false)
            SPerf.initialize(context)
            val pm = PerformanceManager.getInstance()
            isSamsungSdkAvailable = true
            Timber.tag(TAG).i("Samsung Performance SDK initialized successfully")
            pm
        } catch (e: Exception) {
            Timber.tag(TAG).w("Samsung Performance SDK not available: ${e.message}")
            null
        }
    }

    override fun isDriverSupported(): Boolean {
        return isSamsungSdkAvailable
    }

    override fun isGpuSupported(): Boolean {
        return isSamsungSdkAvailable
    }

    override fun isBusSupported(): Boolean {
        return isSamsungSdkAvailable
    }

    override fun getDisplayUnit(): DisplayUnit {
        return DisplayUnit.INTEGER
    }

    override fun start() {
        // No-op for Samsung driver
        // Performance controls are started individually by setMinCpuValue, setMaxCpuValue, etc.
        // Each setter calls performanceManager.start(params) with specific CustomParams
        if (!isDriverSupported()) return
        Timber.tag(TAG).d("Samsung Performance Driver ready (controls started by individual setters)")
    }

    override fun stop() {
        if (!isDriverSupported()) return

        try {
            performanceManager?.stop()
            Timber.tag(TAG).d("Stopped Samsung Performance Manager")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to stop Samsung Performance Manager")
        }
    }

    override fun getCurrentMinCpuValue(): Long {
        return currentCpuMinLevel.toLong()
    }

    override fun getCurrentMaxCpuValue(): Long {
        return currentCpuMaxLevel.toLong()
    }

    override fun getAvailableCpuFrequencies(): List<Long> {
        return (CPU_LEVEL_MIN..CPU_LEVEL_MAX).map { it.toLong() }
    }

    override fun setMinCpuValue(value: Long): Boolean {
        if (!isDriverSupported()) return false

        return try {
            val level = value.toInt().coerceIn(CPU_LEVEL_MIN, CPU_LEVEL_MAX)

            val params = CustomParams()
            params.add(CustomParams.TYPE_CPU_MIN, level, DEFAULT_TIMEOUT_MS)

            performanceManager?.start(params)
            currentCpuMinLevel = level

            Timber.tag(TAG).d("Set CPU min level to $level")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set CPU min level")
            false
        }
    }

    override fun setMaxCpuValue(value: Long): Boolean {
        if (!isDriverSupported()) return false

        return try {
            val level = value.toInt().coerceIn(CPU_LEVEL_MIN, CPU_LEVEL_MAX)

            val params = CustomParams()
            params.add(CustomParams.TYPE_CPU_MAX, level, DEFAULT_TIMEOUT_MS)

            performanceManager?.start(params)
            currentCpuMaxLevel = level

            Timber.tag(TAG).d("Set CPU max level to $level")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set CPU max level")
            false
        }
    }

    override fun getCurrentGpuValue(): Long {
        return currentGpuMinLevel.toLong()
    }

    override fun getAvailableGpuFrequencies() = emptyList<Long>()

    override fun getCurrentMinGpuPowerLevel(): Int {
        return currentGpuMinLevel
    }

    override fun getCurrentMaxGpuPowerLevel(): Int {
        return currentGpuMaxLevel
    }

    override fun getNumGpuPowerLevels(): Int {
        return GPU_LEVEL_MAX - GPU_LEVEL_MIN + 1
    }

    override fun setMinGpuPowerLevel(level: Int): Boolean {
        if (!isDriverSupported()) return false

        return try {
            val gpuLevel = level.coerceIn(GPU_LEVEL_MIN, GPU_LEVEL_MAX)

            val params = CustomParams()
            params.add(CustomParams.TYPE_GPU_MIN, gpuLevel, DEFAULT_TIMEOUT_MS)

            performanceManager?.start(params)
            currentGpuMinLevel = gpuLevel

            Timber.tag(TAG).d("Set GPU min level to $gpuLevel")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set GPU min level")
            false
        }
    }

    override fun setMaxGpuPowerLevel(level: Int): Boolean {
        if (!isDriverSupported()) return false

        return try {
            val gpuLevel = level.coerceIn(GPU_LEVEL_MIN, GPU_LEVEL_MAX)

            val params = CustomParams()
            params.add(CustomParams.TYPE_GPU_MAX, gpuLevel, DEFAULT_TIMEOUT_MS)

            performanceManager?.start(params)
            currentGpuMaxLevel = gpuLevel

            Timber.tag(TAG).d("Set GPU max level to $gpuLevel")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set GPU max level")
            false
        }
    }

    override fun getCurrentMinBusLevel(): Int {
        return currentBusMinLevel
    }

    override fun getCurrentMaxBusLevel(): Int {
        return currentBusMaxLevel
    }

    override fun getNumBusLevels(): Int {
        return BUS_LEVEL_MAX - BUS_LEVEL_MIN + 1
    }

    override fun setMinBusLevel(level: Int): Boolean {
        if (!isBusSupported()) return false

        return try {
            val busLevel = level.coerceIn(BUS_LEVEL_MIN, BUS_LEVEL_MAX)

            val params = CustomParams()
            params.add(CustomParams.TYPE_BUS_MIN, busLevel, DEFAULT_TIMEOUT_MS)

            performanceManager?.start(params)
            currentBusMinLevel = busLevel

            Timber.tag(TAG).d("Set RAM bus min level to $busLevel")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set RAM bus min level")
            false
        }
    }

    override fun setMaxBusLevel(level: Int): Boolean {
        if (!isBusSupported()) return false

        return try {
            val busLevel = level.coerceIn(BUS_LEVEL_MIN, BUS_LEVEL_MAX)

            val params = CustomParams()
            params.add(CustomParams.TYPE_BUS_MAX, busLevel, DEFAULT_TIMEOUT_MS)

            performanceManager?.start(params)
            currentBusMaxLevel = busLevel

            Timber.tag(TAG).d("Set RAM bus max level to $busLevel")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set RAM bus max level")
            false
        }
    }

    override fun getDefaultProfile(): PowerProfile {
        // Samsung driver uses integer levels (0-4)
        // Default: Balanced profile (full range)

        return PowerProfile(
            enableAdaptiveFpsCap = true,
            enableAutoTuning = true,
            enablePerClusterTuning = false,
            enableGamePinning = false,
            name = PerformancePreset.BALANCED.displayName,
            governor = CpuGovernor.SCHEDUTIL, // Samsung doesn't use governors, but we need a value
            minCpuFreq = 0, // CPU level 0
            maxCpuFreq = 4, // CPU level 4
            minGpuPowerLevel = 0, // GPU level 0
            maxGpuPowerLevel = 4,  // GPU level 4
            minBusLevel = 0,
            maxBusLevel = 4
        )
    }
}
