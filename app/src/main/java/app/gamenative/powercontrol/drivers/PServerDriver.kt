package app.gamenative.powercontrol.drivers

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import timber.log.Timber
import java.io.File
import java.nio.charset.Charset

/**
 * Performance driver implementation for devices with PServer support
 * (AYN Odin, Retroid Pocket, etc.)
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class PServerDriver(private val context: Context? = null) : PerformanceDriver() {

    companion object {
        private const val TAG = "PServerDriver"

        // CPU sysfs paths
        private const val CPU_BASE_PATH = "/sys/devices/system/cpu"
        private const val CPUFREQ_PATH = "/sys/devices/system/cpu/cpufreq"
        private const val POLICY0_PATH = "$CPUFREQ_PATH/policy0"

        // GPU sysfs paths (Adreno)
        private const val GPU_BASE_PATH = "/sys/class/kgsl/kgsl-3d0"
        private const val GPU_DEVFREQ_PATH = "$GPU_BASE_PATH/devfreq"
    }

    // PServer binder interface
    private val binder: IBinder?
    private var isPServerAvailable: Boolean = false
    private val isGpuAvailable: Boolean

    // Track modified sysfs files for permission restoration
    private val modifiedSysfsFiles = mutableSetOf<String>()

    // Batch update support
    private var batchCommands = mutableListOf<String>()
    private var batchFilePaths = mutableSetOf<String>()
    private var isBatchMode = false

    init {
        binder = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val rawBinder = getService.invoke(serviceManager, "PServerBinder") as IBinder
            isPServerAvailable = true
            Timber.tag(TAG).i("PServer service found and available")
            rawBinder
        }.getOrElse {
            Timber.tag(TAG).w("Root service not available: ${it.message}")
            null
        }

        // Check GPU support once during initialization
        isGpuAvailable = try {
            val maxPwrLevelFile = File("$GPU_BASE_PATH/max_pwrlevel")
            val availableFreqsFile = File("$GPU_DEVFREQ_PATH/available_frequencies")
            maxPwrLevelFile.exists() && availableFreqsFile.exists()
        } catch (e: Exception) {
            false
        }
    }

    // ========================================
    // General / Driver Support
    // ========================================

    override fun isBusSupported(): Boolean = false

    override fun getCurrentMinBusLevel(): Int = 0

    override fun getCurrentMaxBusLevel(): Int = 0

    override fun getNumBusLevels(): Int = 0

    override fun setMinBusLevel(level: Int): Boolean = false

    override fun setMaxBusLevel(level: Int): Boolean = false

    /**
     * Check if PServer driver is available on this device
     */
    override fun isDriverSupported(): Boolean {
        return isPServerAvailable
    }

    /**
     * Check if CPU governor control is supported
     */
    override fun isGovernorSupported(): Boolean {
        return isDriverSupported()
    }

    /**
     * Check if GPU control is supported (Adreno GPUs)
     */
    override fun isGpuSupported(): Boolean {
        return isGpuAvailable
    }

    /**
     * Check if fan control is supported
     * Currently not implemented for PServer devices
     */
    override fun isFanSupported(): Boolean {
        return false
    }

    /**
     * Get display unit for frequency values
     * Returns HZ for formatted display (e.g., 2.4 GHz)
     */
    override fun getDisplayUnit(): DisplayUnit {
        return DisplayUnit.HZ
    }

    /**
     * Start the performance driver
     * Does nothing for PServerDriver
     */
    override fun start() {
        // No-op for PServerDriver
    }

    /**
     * Begin a batch update session.
     * Collects commands to execute in a single root call for better performance.
     */
    override fun beginUpdate() {
        batchCommands.clear()
        batchFilePaths.clear()
        isBatchMode = true
    }

    /**
     * Commit all pending updates from the batch session.
     * Writes commands to a temporary shell script and executes it to avoid Binder size limits.
     */
    override fun commit(): Boolean {
        if (!isBatchMode || batchCommands.isEmpty()) {
            isBatchMode = false
            return true
        }

        var scriptFile: File? = null
        return try {
            // Create temporary shell script in app cache directory (or fallback to /data/local/tmp)
            scriptFile = if (context != null) {
                File(context.cacheDir, "pserver_batch_${System.currentTimeMillis()}.sh")
            } else {
                File("/data/local/tmp/pserver_batch_${System.currentTimeMillis()}.sh")
            }

            // Write script content directly to file
            val scriptContent = buildString {
                appendLine("#!/system/bin/sh")

                // First, make all files writable in a single chmod command
                if (batchFilePaths.isNotEmpty()) {
                    val paths = batchFilePaths.joinToString(" ") { "'$it'" }
                    appendLine("chmod 644 $paths")
                }

                // Execute all the actual commands (echo operations)
                for (cmd in batchCommands) {
                    appendLine(cmd)
                }

                // Finally, make all files read-only in a single chmod command
                if (batchFilePaths.isNotEmpty()) {
                    val paths = batchFilePaths.joinToString(" ") { "'$it'" }
                    appendLine("chmod 444 $paths")
                }
            }

            try {
                scriptFile.writeText(scriptContent)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to write batch script to ${scriptFile.absolutePath}")
                batchCommands.clear()
                isBatchMode = false
                return false
            }

            // Make script executable and run it
            val chmodResult = executeAsRoot("chmod 755 '${scriptFile.absolutePath}'")
            if (chmodResult.isFailure) {
                Timber.tag(TAG).e("Failed to chmod batch script: ${chmodResult.exceptionOrNull()?.message}")
                batchCommands.clear()
                isBatchMode = false
                return false
            }

            val execResult = executeAsRoot("/system/bin/sh '${scriptFile.absolutePath}'")
            val success = execResult.isSuccess

            if (execResult.isFailure) {
                Timber.tag(TAG).e("Failed to execute batch script: ${execResult.exceptionOrNull()?.message}")
            } else {
                Timber.tag(TAG).d("Successfully executed ${batchCommands.size} batched commands")
            }

            batchCommands.clear()
            isBatchMode = false
            success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to commit batch update")
            batchCommands.clear()
            isBatchMode = false
            false
        } finally {
            // Clean up script file
            try {
                scriptFile?.delete()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to delete batch script")
            }
        }
    }

    /**
     * Stop the performance driver
     * Restores CPU governor to first available governor and all modified sysfs files to 644 permissions
     * Runs asynchronously on a background thread
     */
    override fun stop() {
        if (!isPServerAvailable) {
            Timber.tag(TAG).w("PServer not available to restore settings")
            return
        }

        // Run restoration on background thread to avoid blocking
        Thread {
            try {
                // Reset CPU frequencies to maximum before changing governor
                // This prevents device from staying slow if it was in Power Save mode
                try {
                    val availableFrequencies = getAvailableCpuFrequencies()
                    if (availableFrequencies.isNotEmpty()) {
                        val minFreq = availableFrequencies.first()
                        val maxFreq = availableFrequencies.last()
                        Timber.tag(TAG).d("Resetting CPU frequencies to full range: $minFreq - $maxFreq")
                        setMinCpuValue(minFreq)
                        setMaxCpuValue(maxFreq)
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to reset CPU frequencies")
                }

                // Reset GPU power levels to maximum if supported
                // This prevents GPU from staying throttled
                if (isGpuSupported()) {
                    try {
                        val maxGpuLevel = getNumGpuPowerLevels() - 1
                        Timber.tag(TAG).d("Resetting GPU power levels to full range: 0 - $maxGpuLevel")
                        setMinGpuPowerLevel(0)
                        setMaxGpuPowerLevel(maxGpuLevel)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to reset GPU power levels")
                    }
                }

                // Restore governor to first available (typically the default/recommended one)
                try {
                    val availableGovernors = getAvailableGovernors()
                    if (availableGovernors.isNotEmpty()) {
                        val defaultGovernor = availableGovernors.first()
                        Timber.tag(TAG).d("Restoring governor to $defaultGovernor")
                        setGovernor(defaultGovernor)

                        // Restore governor file permissions to 644 (setGovernor sets them to 444)
                        val numCpus = getNumCpus()
                        for (cpu in 0 until numCpus) {
                            modifiedSysfsFiles.add("$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_governor")
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to restore governor")
                }

                // Restore file permissions - concatenate all chmod commands for faster execution
                if (modifiedSysfsFiles.isNotEmpty()) {
                    try {
                        val chmodCommands = modifiedSysfsFiles.joinToString("; ") { path ->
                            "chmod 644 '$path'"
                        }
                        val result = executeAsRoot(chmodCommands)
                        if (result.isSuccess) {
                            Timber.tag(TAG).d("Restored permissions for ${modifiedSysfsFiles.size} files")
                        } else {
                            Timber.tag(TAG).e("Failed to restore permissions: ${result.exceptionOrNull()?.message}")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to restore permissions")
                    }
                }

                modifiedSysfsFiles.clear()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to stop PServerDriver")
            }
        }.start()
    }

    // ========================================
    // CPU Control - Getters
    // ========================================

    /**
     * Get current minimum CPU frequency in KHz
     */
    override fun getCurrentMinCpuValue(): Long {
        return readSysfsFile("$POLICY0_PATH/scaling_min_freq")?.toLongOrNull() ?: 0L
    }

    /**
     * Get current maximum CPU frequency in KHz
     */
    override fun getCurrentMaxCpuValue(): Long {
        return readSysfsFile("$POLICY0_PATH/scaling_max_freq")?.toLongOrNull() ?: 0L
    }

    /**
     * Get current CPU governor name
     */
    override fun getCurrentGovernor(): String {
        return readSysfsFile("$POLICY0_PATH/scaling_governor")?.trim() ?: ""
    }

    /**
     * Get list of available CPU governors
     */
    override fun getAvailableGovernors(): List<String> {
        return try {
            val governors = readSysfsFile("$POLICY0_PATH/scaling_available_governors")
            governors?.split("\\s+".toRegex())?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get available governors")
            emptyList()
        }
    }

    /**
     * Get list of available CPU frequencies in KHz (sorted)
     */
    override fun getAvailableCpuFrequencies(): List<Long> {
        return try {
            val freqs = readSysfsFile("$POLICY0_PATH/scaling_available_frequencies")
            freqs?.split("\\s+".toRegex())
                ?.mapNotNull { it.toLongOrNull() }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get available frequencies")
            emptyList()
        }
    }

    // ========================================
    // CPU Control - Setters
    // ========================================

    /**
     * Set CPU governor for all CPU cores
     */
    override fun setGovernor(governor: String): Boolean {
        return try {
            val numCpus = getNumCpus()

            if (isBatchMode) {
                for (cpu in 0 until numCpus) {
                    val path = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_governor"
                    batchFilePaths.add(path)
                    batchCommands.add("echo '$governor' > '$path'")
                    modifiedSysfsFiles.add(path)
                }
                return true
            }

            var success = true
            for (cpu in 0 until numCpus) {
                val path = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_governor"
                if (!writeSysfsFile(path, governor)) {
                    success = false
                    Timber.tag(TAG).e("Failed to set governor for CPU $cpu")
                }
            }

            success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set governor")
            false
        }
    }

    /**
     * Set minimum CPU frequency in KHz
     */
    override fun setMinCpuValue(value: Long): Boolean {
        return try {
            val numCpus = getNumCpus()

            if (isBatchMode) {
                for (cpu in 0 until numCpus) {
                    val path = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_min_freq"
                    batchFilePaths.add(path)
                    batchCommands.add("echo '$value' > '$path'")
                    modifiedSysfsFiles.add(path)
                }
                return true
            }

            var success = true
            for (cpu in 0 until numCpus) {
                val path = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_min_freq"
                if (!writeSysfsFile(path, value.toString())) {
                    success = false
                    Timber.tag(TAG).e("Failed to set min frequency for CPU $cpu")
                }
            }

            success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set min frequency")
            false
        }
    }

    /**
     * Set maximum CPU frequency in KHz
     */
    override fun setMaxCpuValue(value: Long): Boolean {
        return try {
            val numCpus = getNumCpus()

            if (isBatchMode) {
                for (cpu in 0 until numCpus) {
                    val path = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_max_freq"
                    batchFilePaths.add(path)
                    batchCommands.add("echo '$value' > '$path'")
                    modifiedSysfsFiles.add(path)
                }
                return true
            }

            var success = true
            for (cpu in 0 until numCpus) {
                val path = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_max_freq"
                if (!writeSysfsFile(path, value.toString())) {
                    success = false
                    Timber.tag(TAG).e("Failed to set max frequency for CPU $cpu")
                }
            }

            success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set max frequency")
            false
        }
    }

    // ========================================
    // GPU Control - Getters
    // ========================================

    /**
     * Get list of available GPU frequencies in KHz (sorted)
     */
    override fun getAvailableGpuFrequencies(): List<Long> {
        return try {
            val freqs = readSysfsFile("$GPU_DEVFREQ_PATH/available_frequencies")
            freqs?.split("\\s+".toRegex())
                ?.mapNotNull { it.toLongOrNull() }
                ?.map { it / 1000 }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get available GPU frequencies")
            emptyList()
        }
    }

    /**
     * Get current GPU frequency in KHz
     */
    override fun getCurrentGpuValue(): Long {
        return try {
            val freqHz = readSysfsFile("$GPU_DEVFREQ_PATH/cur_freq")?.toLongOrNull() ?: 0L
            freqHz / 1000
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current GPU frequency")
            0L
        }
    }

    /**
     * Get current GPU minimum power level
     * Returns UI-friendly value where higher = better performance
     * (Internally converts from Adreno's reversed sysfs semantics)
     */
    override fun getCurrentMinGpuPowerLevel(): Int {
        return try {
            val sysfsLevel = readSysfsFile("$GPU_BASE_PATH/min_pwrlevel")?.toIntOrNull() ?: 0
            val numLevels = getNumGpuPowerLevels()
            // Convert: sysfs min_pwrlevel (high index = low perf) to UI (high value = high perf)
            if (numLevels > 0) numLevels - 1 - sysfsLevel else 0
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current GPU min power level")
            0
        }
    }

    /**
     * Get current GPU maximum power level
     * Returns UI-friendly value where higher = better performance
     * (Internally converts from Adreno's reversed sysfs semantics)
     */
    override fun getCurrentMaxGpuPowerLevel(): Int {
        return try {
            val sysfsLevel = readSysfsFile("$GPU_BASE_PATH/max_pwrlevel")?.toIntOrNull() ?: 0
            val numLevels = getNumGpuPowerLevels()
            // Convert: sysfs max_pwrlevel (low index = high perf) to UI (high value = high perf)
            if (numLevels > 0) numLevels - 1 - sysfsLevel else 0
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current GPU max power level")
            0
        }
    }

    /**
     * Get total number of GPU power levels available
     */
    override fun getNumGpuPowerLevels(): Int {
        return try {
            readSysfsFile("$GPU_BASE_PATH/num_pwrlevels")?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get number of GPU power levels")
            0
        }
    }

    // ========================================
    // GPU Control - Setters
    // ========================================

    /**
     * Set GPU minimum power level
     * Accepts UI-friendly value where higher = better performance
     * (Internally converts to Adreno's reversed sysfs semantics)
     */
    override fun setMinGpuPowerLevel(level: Int): Boolean {
        if (!isGpuSupported()) {
            Timber.tag(TAG).w("GPU control not supported")
            return false
        }

        val numLevels = getNumGpuPowerLevels()
        // Convert: UI level (high = high perf) to sysfs min_pwrlevel (high index = low perf)
        val sysfsLevel = if (numLevels > 0) numLevels - 1 - level else level

        val minPath = "$GPU_BASE_PATH/min_pwrlevel"
        return writeGpuPowerLevel(minPath, sysfsLevel)
    }

    /**
     * Set GPU maximum power level
     * Accepts UI-friendly value where higher = better performance
     * (Internally converts to Adreno's reversed sysfs semantics)
     */
    override fun setMaxGpuPowerLevel(level: Int): Boolean {
        if (!isGpuSupported()) {
            Timber.tag(TAG).w("GPU control not supported")
            return false
        }

        val numLevels = getNumGpuPowerLevels()
        // Convert: UI level (high = high perf) to sysfs max_pwrlevel (low index = high perf)
        val sysfsLevel = if (numLevels > 0) numLevels - 1 - level else level

        val maxPath = "$GPU_BASE_PATH/max_pwrlevel"
        return writeGpuPowerLevel(maxPath, sysfsLevel)
    }

    override fun getDefaultProfile(): PowerProfile {
        val availableFrequencies = getAvailableCpuFrequencies()
        val availableGovernors = getAvailableGovernors()

        if (availableFrequencies.isEmpty()) {
            // Fallback to a safe default
            return PowerProfile(
                name = PerformancePreset.BALANCED.displayName,
                governor = CpuGovernor.SCHEDUTIL,
                minCpuFreq = 0,
                maxCpuFreq = 0,
                minGpuPowerLevel = 0,
                maxGpuPowerLevel = 0
            )
        }

        val midFreq = availableFrequencies[availableFrequencies.size / 2]
        val maxFreq = availableFrequencies.last()

        // GPU power levels
        val maxGpuPowerLevel = if (isGpuSupported()) {
            getNumGpuPowerLevels() - 1
        } else {
            0
        }
        val midGpuLevel = maxGpuPowerLevel / 2

        // Return Balanced profile (middle performance)
        val governor = when {
            availableGovernors.contains(CpuGovernor.SCHEDUTIL.governorName) -> CpuGovernor.SCHEDUTIL
            availableGovernors.contains(CpuGovernor.CONSERVATIVE.governorName) -> CpuGovernor.CONSERVATIVE
            availableGovernors.contains(CpuGovernor.INTERACTIVE.governorName) -> CpuGovernor.INTERACTIVE
            else -> CpuGovernor.SCHEDUTIL
        }

        return PowerProfile(
            name = PerformancePreset.BALANCED.displayName,
            governor = governor,
            minCpuFreq = midFreq,
            maxCpuFreq = maxFreq,
            minGpuPowerLevel = midGpuLevel,
            maxGpuPowerLevel = maxGpuPowerLevel
        )
    }

    /**
     * Write GPU power level to sysfs using PServer root access
     * @param path Sysfs path to write to
     * @param level Power level value in sysfs semantics (0 = fastest for Adreno)
     */
    private fun writeGpuPowerLevel(path: String, level: Int): Boolean {
        if (!isPServerAvailable) {
            Timber.tag(TAG).w("PServer not available to write GPU power level")
            return false
        }

        return try {
            // Concatenate chmod -> echo -> chmod into a single command
            val command = "chmod 644 '$path'; echo $level > $path; chmod 444 '$path'"
            val result = executeAsRoot(command)

            if (result.isFailure) {
                Timber.tag(TAG).e("Failed to write GPU power level to $path: ${result.exceptionOrNull()?.message}")
                return false
            }

            // Track modified file for restoration
            modifiedSysfsFiles.add(path)

            result.isSuccess
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to write GPU power level to $path")
            false
        }
    }

    private fun getNumCpus(): Int {
        return try {
            val content = readSysfsFile("$CPU_BASE_PATH/present")
            if (content != null) {
                val parts = content.split("-")
                if (parts.size == 2) {
                    parts[1].toInt() + 1
                } else {
                    Runtime.getRuntime().availableProcessors()
                }
            } else {
                Runtime.getRuntime().availableProcessors()
            }
        } catch (e: Exception) {
            Runtime.getRuntime().availableProcessors()
        }
    }

    private fun executeAsRoot(cmd: String): Result<String?> {
        if (binder == null) {
            return Result.failure(IllegalStateException("PServer not available"))
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "1"))
            binder.transact(0, data, reply, 0)
            Result.success(decodeReply(reply))
        } catch (throwable: Throwable) {
            Timber.tag(TAG).e(throwable, "Failed to execute command via PServer: $cmd")
            Result.failure(throwable)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun decodeReply(reply: Parcel): String? {
        return reply.createByteArray()
            ?.toString(Charset.defaultCharset())
            ?.trim()
            ?.let { value -> if (value == "null") null else value }
    }

    private fun readSysfsFile(path: String): String? {
        // Try using PServer cat command first (works with root permissions)
        if (isPServerAvailable) {
            return try {
                val result = executeAsRoot("cat '$path'")
                if (result.isSuccess) {
                    result.getOrNull()?.trim()
                } else {
                    Timber.tag(TAG).e("Failed to read $path via PServer: ${result.exceptionOrNull()?.message}")
                    // Fallback: try direct file read
                    tryDirectFileRead(path)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to read $path via PServer")
                // Fallback: try direct file read
                tryDirectFileRead(path)
            }
        }

        // Fallback: try direct file read if PServer not available
        return tryDirectFileRead(path)
    }

    private fun tryDirectFileRead(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                file.readText().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read $path directly")
            null
        }
    }

    private fun writeSysfsFile(path: String, value: String): Boolean {
        if (!isPServerAvailable) {
            Timber.tag(TAG).w("PServer not available to write to $path")
            return false
        }

        return try {
            // Concatenate chmod -> echo -> chmod into a single command
            val command = "chmod 644 '$path'; echo '$value' > '$path'; chmod 444 '$path'"
            val result = executeAsRoot(command)

            if (result.isFailure) {
                Timber.tag(TAG).e("Failed to write to $path: ${result.exceptionOrNull()?.message}")
                return false
            }

            // Track modified file for restoration
            modifiedSysfsFiles.add(path)

            result.isSuccess
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to write to $path")
            false
        }
    }
}
