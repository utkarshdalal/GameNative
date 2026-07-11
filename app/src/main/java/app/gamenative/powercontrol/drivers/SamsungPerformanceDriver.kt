package app.gamenative.powercontrol.drivers

import android.content.Context
import com.samsung.sdk.sperf.CustomParams
import com.samsung.sdk.sperf.PerformanceManager
import com.samsung.sdk.sperf.SPerf
import timber.log.Timber

class SamsungPerformanceDriver(private val context: Context) : PerformanceDriver() {

    companion object {
        private const val TAG = "SamsungPerformanceDriver"

        private const val DEFAULT_TIMEOUT_MS = 0

        private const val CPU_LEVEL_MIN = 1
        private const val CPU_LEVEL_MAX = 4
        private const val GPU_LEVEL_MIN = 1
        private const val GPU_LEVEL_MAX = 4

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

    override fun isGovernorSupported(): Boolean {
        return false
    }

    override fun isGpuSupported(): Boolean {
        return isSamsungSdkAvailable
    }

    override fun isFanSupported(): Boolean {
        return false
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

    override fun getCurrentGovernor(): String {
        return "samsung_performance"
    }

    override fun getAvailableGovernors(): List<String> {
        return listOf("samsung_performance")
    }

    override fun getAvailableCpuFrequencies(): List<Long> {
        return (CPU_LEVEL_MIN..CPU_LEVEL_MAX).map { it.toLong() }
    }

    override fun setGovernor(governor: String): Boolean {
        return false
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
}
