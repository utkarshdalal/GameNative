package app.gamenative.powercontrol.drivers

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import app.gamenative.powercontrol.PowerManager
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

    // CPU policy information for optimized control
    private data class CpuPolicy(
        val policyId: Int,
        val governorPath: String,
        val minFreqPath: String,
        val maxFreqPath: String,
        val cpuCores: List<Int>,
        val maxFrequency: Long = 0L
    )

    // CPU cluster types based on frequency
    enum class CpuCluster {
        EFFICIENCY,    // Lowest frequency cores
        PERFORMANCE,   // Mid-high frequency cores
        PRIME          // Highest frequency core(s)
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

    // CPU policies discovered at initialization (reduces redundant IPC calls)
    private var cpuPolicies: List<CpuPolicy> = emptyList()

    // CPU cluster mapping for affinity control
    private var cpuClusters: Map<CpuCluster, List<Int>> = emptyMap()

    // Taskset mask format (cached after first detection)
    private var tasksetMaskFormat: TasksetMaskFormat? = null

    enum class TasksetMaskFormat {
        PLAIN_HEX,  // e.g., "f8"
        HEX_PREFIX  // e.g., "0xf8"
    }

    // Track current CPU settings (what was requested, not what policy0 has)
    private var currentMinCpuFreq: Long = 0L
    private var currentMaxCpuFreq: Long = 0L
    private var currentGovernor: String = ""

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
                // When using auto-tuning, this log can spam around, suppress it
                if (PowerManager.currentProfile?.enableAutoTuning == false) {
                    Timber.tag(TAG).d("Successfully executed ${batchCommands.size} batched commands")
                }
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
     * Start the performance driver.
     * Validates CPU frequency scaling support and discovers CPU policies.
     */
    override fun start() {
        // Discover CPU policies if not already done
        if (cpuPolicies.isEmpty()) {
            validateCpuFreqSupport()
            cpuPolicies = discoverCpuPolicies()
            cpuClusters = identifyCpuClusters()
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

                // Reset app process CPU affinity to all cores
                resetAppCpuAffinity()

                // Clear CPU policies and clusters to force re-discovery on next start()
                cpuPolicies = emptyList()
                cpuClusters = emptyMap()
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
     * Returns the last requested value, not policy0's value
     */
    override fun getCurrentMinCpuValue(): Long {
        // If we haven't set anything yet, read from policy0
        if (currentMinCpuFreq == 0L) {
            currentMinCpuFreq = readSysfsFile("$POLICY0_PATH/scaling_min_freq")?.toLongOrNull() ?: 0L
        }
        return currentMinCpuFreq
    }

    /**
     * Get current maximum CPU frequency in KHz
     * Returns the last requested value, not policy0's value
     */
    override fun getCurrentMaxCpuValue(): Long {
        // If we haven't set anything yet, read from policy0
        if (currentMaxCpuFreq == 0L) {
            currentMaxCpuFreq = readSysfsFile("$POLICY0_PATH/scaling_max_freq")?.toLongOrNull() ?: 0L
        }
        return currentMaxCpuFreq
    }

    /**
     * Get current CPU governor name
     * Returns the last set governor, not policy0's governor
     */
    override fun getCurrentGovernor(): String {
        // If we haven't set anything yet, read from policy0
        if (currentGovernor.isEmpty()) {
            currentGovernor = readSysfsFile("$POLICY0_PATH/scaling_governor")?.trim() ?: ""
        }
        return currentGovernor
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
     * Collects frequencies from all CPU policies to include all clusters
     */
    override fun getAvailableCpuFrequencies(): List<Long> {
        return try {
            val allFrequencies = mutableSetOf<Long>()

            // If policies are discovered, read from each policy
            if (cpuPolicies.isNotEmpty()) {
                for (policy in cpuPolicies) {
                    val policyDir = policy.governorPath.substringBeforeLast("/")
                    val freqs = readSysfsFile("$policyDir/scaling_available_frequencies")
                    freqs?.split("\\s+".toRegex())
                        ?.mapNotNull { it.toLongOrNull() }
                        ?.let { allFrequencies.addAll(it) }
                }
            } else {
                // Fallback: read from policy0 only
                val freqs = readSysfsFile("$POLICY0_PATH/scaling_available_frequencies")
                freqs?.split("\\s+".toRegex())
                    ?.mapNotNull { it.toLongOrNull() }
                    ?.let { allFrequencies.addAll(it) }
            }

            allFrequencies.sorted()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get available frequencies")
            emptyList()
        }
    }

    // ========================================
    // CPU Control - Setters
    // ========================================

    /**
     * Set CPU governor for all CPU cores.
     * Uses policy-based approach to reduce IPC calls by 50-75%.
     */
    override fun setGovernor(governor: String): Boolean {
        return try {
            // Use policy-based approach if policies are discovered
            if (cpuPolicies.isNotEmpty()) {
                if (isBatchMode) {
                    for (policy in cpuPolicies) {
                        batchFilePaths.add(policy.governorPath)
                        batchCommands.add("echo '$governor' > '${policy.governorPath}'")
                        // Set governor file to read-only (444) to prevent system from changing it
                        batchCommands.add("chmod 444 '${policy.governorPath}'")
                        modifiedSysfsFiles.add(policy.governorPath)
                    }
                    currentGovernor = governor
                    return true
                }

                var success = true
                for (policy in cpuPolicies) {
                    if (!writeSysfsFile(policy.governorPath, governor)) {
                        success = false
                        Timber.tag(TAG).e(
                            "Failed to set governor for policy ${policy.policyId} " +
                            "(CPUs: ${policy.cpuCores.joinToString()})"
                        )
                    } else {
                        Timber.tag(TAG).d(
                            "Set governor to '$governor' for policy ${policy.policyId} " +
                            "(CPUs: ${policy.cpuCores.joinToString()})"
                        )
                    }
                }
                if (success) {
                    currentGovernor = governor
                }
                return success
            }

            // Fallback: per-CPU approach (legacy behavior)
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
     * Set minimum CPU frequency in KHz.
     * Uses policy-based approach to reduce IPC calls by 50-75%.
     */
    override fun setMinCpuValue(value: Long): Boolean {
        return try {
            // Use policy-based approach if policies are discovered
            if (cpuPolicies.isNotEmpty()) {
                if (isBatchMode) {
                    for (policy in cpuPolicies) {
                        // Cap at policy's max frequency
                        val cappedValue = if (policy.maxFrequency > 0) {
                            minOf(value, policy.maxFrequency)
                        } else {
                            value
                        }
                        batchFilePaths.add(policy.minFreqPath)
                        batchCommands.add("echo '$cappedValue' > '${policy.minFreqPath}'")
                        modifiedSysfsFiles.add(policy.minFreqPath)
                    }
                    currentMinCpuFreq = value
                    return true
                }

                var success = true
                for (policy in cpuPolicies) {
                    // Cap at policy's max frequency
                    val cappedValue = if (policy.maxFrequency > 0) {
                        minOf(value, policy.maxFrequency)
                    } else {
                        value
                    }

                    if (!writeSysfsFile(policy.minFreqPath, cappedValue.toString())) {
                        success = false
                        Timber.tag(TAG).e("Failed to set min freq for policy ${policy.policyId}")
                    } else {
                        if (cappedValue != value) {
                            Timber.tag(TAG).d(
                                "Set min freq to $cappedValue (capped from $value) for policy ${policy.policyId} " +
                                "(CPUs: ${policy.cpuCores.joinToString()}, max: ${policy.maxFrequency})"
                            )
                        } else {
                            Timber.tag(TAG).d(
                                "Set min freq to $value for policy ${policy.policyId} " +
                                "(CPUs: ${policy.cpuCores.joinToString()})"
                            )
                        }
                    }
                }
                if (success) {
                    currentMinCpuFreq = value
                }
                return success
            }

            // Fallback: per-CPU approach (legacy behavior)
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
     * Set maximum CPU frequency in KHz.
     * Uses policy-based approach and respects each policy's maximum frequency.
     */
    override fun setMaxCpuValue(value: Long): Boolean {
        return try {
            // Use policy-based approach if policies are discovered
            if (cpuPolicies.isNotEmpty()) {
                if (isBatchMode) {
                    for (policy in cpuPolicies) {
                        // Cap at policy's max frequency
                        val cappedValue = if (policy.maxFrequency > 0) {
                            minOf(value, policy.maxFrequency)
                        } else {
                            value
                        }
                        batchFilePaths.add(policy.maxFreqPath)
                        batchCommands.add("echo '$cappedValue' > '${policy.maxFreqPath}'")
                        modifiedSysfsFiles.add(policy.maxFreqPath)
                    }
                    currentMaxCpuFreq = value
                    return true
                }

                var success = true
                for (policy in cpuPolicies) {
                    // Cap at policy's max frequency
                    val cappedValue = if (policy.maxFrequency > 0) {
                        minOf(value, policy.maxFrequency)
                    } else {
                        value
                    }

                    if (!writeSysfsFile(policy.maxFreqPath, cappedValue.toString())) {
                        success = false
                        Timber.tag(TAG).e("Failed to set max freq for policy ${policy.policyId}")
                    } else {
                        if (cappedValue != value) {
                            Timber.tag(TAG).d(
                                "Set max freq to $cappedValue (capped from $value) for policy ${policy.policyId} " +
                                "(CPUs: ${policy.cpuCores.joinToString()}, max: ${policy.maxFrequency})"
                            )
                        } else {
                            Timber.tag(TAG).d(
                                "Set max freq to $value for policy ${policy.policyId} " +
                                "(CPUs: ${policy.cpuCores.joinToString()})"
                            )
                        }
                    }
                }
                if (success) {
                    currentMaxCpuFreq = value
                }
                return success
            }

            // Fallback: per-CPU approach (legacy behavior)
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
                minCpuFreq = getCurrentMinCpuValue(),
                maxCpuFreq = getCurrentMaxCpuValue(),
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

    // ========================================
    // Policy-Based CPU Control (GameMode-inspired)
    // ========================================

    /**
     * Discover CPU policies by resolving symlinks.
     * Inspired by GameMode's realpath() approach to eliminate redundant writes.
     *
     * Benefits:
     * - Reduces IPC calls by 50-75% on devices with shared policies
     * - Eliminates redundant writes to CPUs sharing the same policy
     * - More robust against race conditions
     */
    private fun discoverCpuPolicies(): List<CpuPolicy> {
        val policies = mutableMapOf<String, MutableList<Int>>()
        val numCpus = getNumCpus()

        Timber.tag(TAG).d("Discovering CPU policies for $numCpus cores")

        for (cpu in 0 until numCpus) {
            val governorSymlink = "$CPU_BASE_PATH/cpu$cpu/cpufreq/scaling_governor"

            try {
                // Resolve symlink to find the actual policy directory
                val governorRealPath = File(governorSymlink).canonicalPath

                // Extract policy directory from real path
                val policyDir = File(governorRealPath).parent ?: continue

                // Group CPUs by their policy directory
                if (!policies.containsKey(policyDir)) {
                    policies[policyDir] = mutableListOf()
                }
                policies[policyDir]?.add(cpu)

            } catch (e: Exception) {
                // Fallback: treat as individual policy
                val policyDir = "$CPU_BASE_PATH/cpu$cpu/cpufreq"
                if (!policies.containsKey(policyDir)) {
                    policies[policyDir] = mutableListOf()
                }
                policies[policyDir]?.add(cpu)
            }
        }

        // Convert to CpuPolicy objects and read max frequency for each
        val policyList = policies.entries.mapIndexed { index, (policyDir, cpuList) ->
            val maxFreq = try {
                readSysfsFile("$policyDir/cpuinfo_max_freq")?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            }

            CpuPolicy(
                policyId = index,
                governorPath = "$policyDir/scaling_governor",
                minFreqPath = "$policyDir/scaling_min_freq",
                maxFreqPath = "$policyDir/scaling_max_freq",
                cpuCores = cpuList.sorted(),
                maxFrequency = maxFreq
            )
        }

        if (policyList.isNotEmpty()) {
            Timber.tag(TAG).i("Discovered ${policyList.size} CPU policies:")
            policyList.forEach { policy ->
                Timber.tag(TAG).i("  Policy ${policy.policyId}: CPUs ${policy.cpuCores.joinToString()} (max: ${policy.maxFrequency / 1000} MHz)")
            }
        }

        return policyList
    }

    /**
     * Validate CPU frequency scaling support.
     * Helps diagnose issues like disabled cpufreq in BIOS/kernel.
     */
    private fun validateCpuFreqSupport(): Boolean {
        val checks = mapOf(
            "CPU base directory" to CPU_BASE_PATH,
            "CPUFreq directory" to CPUFREQ_PATH,
            "Policy0 directory" to POLICY0_PATH,
            "Policy0 governor" to "$POLICY0_PATH/scaling_governor"
        )

        var allValid = true
        val results = mutableListOf<String>()

        for ((name, path) in checks) {
            val valid = File(path).exists()
            val status = if (valid) "✓" else "✗"
            results.add("  $status $name")

            if (!valid) {
                allValid = false
            }
        }

        if (!allValid) {
            Timber.tag(TAG).w("CPU frequency scaling validation:")
            results.forEach { Timber.tag(TAG).w(it) }
            Timber.tag(TAG).w(
                "CPU frequency scaling may be disabled. " +
                "Check kernel config or device settings."
            )
        } else {
            Timber.tag(TAG).d("CPU frequency scaling validation: All checks passed")
        }

        return allValid
    }

    /**
     * Identify CPU clusters based on max frequencies.
     * Categorizes CPUs into EFFICIENCY, PERFORMANCE, and PRIME clusters.
     */
    private fun identifyCpuClusters(): Map<CpuCluster, List<Int>> {
        if (cpuPolicies.isEmpty()) {
            Timber.tag(TAG).w("Cannot identify clusters: no policies discovered")
            return emptyMap()
        }

        // Read max frequencies for each policy
        val policiesWithFreq = cpuPolicies.map { policy ->
            val maxFreq = try {
                readSysfsFile("${policy.governorPath.substringBeforeLast("/")}/cpuinfo_max_freq")
                    ?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            }
            policy.copy(maxFrequency = maxFreq)
        }.sortedBy { it.maxFrequency }

        val clusters = mutableMapOf<CpuCluster, MutableList<Int>>()

        when (policiesWithFreq.size) {
            1 -> {
                // Single cluster - all cores are same type
                clusters[CpuCluster.PERFORMANCE] = policiesWithFreq[0].cpuCores.toMutableList()
            }
            2 -> {
                // Dual cluster (big.LITTLE)
                clusters[CpuCluster.EFFICIENCY] = policiesWithFreq[0].cpuCores.toMutableList()
                clusters[CpuCluster.PERFORMANCE] = policiesWithFreq[1].cpuCores.toMutableList()
            }
            3 -> {
                // Tri-cluster (efficiency + performance + prime)
                clusters[CpuCluster.EFFICIENCY] = policiesWithFreq[0].cpuCores.toMutableList()
                clusters[CpuCluster.PERFORMANCE] = policiesWithFreq[1].cpuCores.toMutableList()
                clusters[CpuCluster.PRIME] = policiesWithFreq[2].cpuCores.toMutableList()
            }
            else -> {
                // 4+ clusters - group by frequency ranges
                clusters[CpuCluster.EFFICIENCY] = policiesWithFreq[0].cpuCores.toMutableList()
                clusters[CpuCluster.PRIME] = policiesWithFreq.last().cpuCores.toMutableList()

                val perfCores = mutableListOf<Int>()
                for (i in 1 until policiesWithFreq.size - 1) {
                    perfCores.addAll(policiesWithFreq[i].cpuCores)
                }
                clusters[CpuCluster.PERFORMANCE] = perfCores
            }
        }

        Timber.tag(TAG).i("Identified CPU clusters:")
        clusters.forEach { (cluster, cores) ->
            val freq = policiesWithFreq.find { cores.intersect(it.cpuCores.toSet()).isNotEmpty() }?.maxFrequency ?: 0
            Timber.tag(TAG).i("  $cluster: CPUs ${cores.joinToString()} @ ${freq / 1000} MHz")
        }

        return clusters
    }

    // ========================================
    // CPU Affinity / Process Pinning
    // ========================================

    /**
     * Get the list of CPU core numbers for a specific cluster.
     *
     * @param cluster The CPU cluster type
     * @return List of CPU core numbers, or empty list if cluster not found
     */
    fun getCpuCoresByCluster(cluster: CpuCluster): List<Int> {
        return cpuClusters[cluster] ?: emptyList()
    }

    /**
     * Get the number of CPU clusters identified.
     * Used to determine optimal pinning strategy.
     *
     * @return Number of clusters (1, 2, or 3+)
     */
    fun getCpuClusterCount(): Int {
        return cpuClusters.size
    }

    /**
     * Reset the current app process to use all available CPU cores.
     *
     * @return true if successful
     */
    fun resetAppCpuAffinity(): Boolean {
        val appPid = android.os.Process.myPid()

        // Get all available cores from all clusters
        val effCores = getCpuCoresByCluster(CpuCluster.EFFICIENCY)
        val perfCores = getCpuCoresByCluster(CpuCluster.PERFORMANCE)
        val primeCores = getCpuCoresByCluster(CpuCluster.PRIME)
        val allCores = (effCores + perfCores + primeCores).sorted()

        if (allCores.isEmpty()) {
            Timber.tag(TAG).w("No CPU cores found for reset")
            return false
        }

        val success = setCpuAffinityByCores(appPid, allCores)
        if (success) {
            Timber.tag(TAG).i("Reset app process (PID: $appPid) to all CPUs ${allCores.joinToString()}")
        }
        return success
    }

    /**
     * Pin a process to specific CPU cores using taskset.
     *
     * @param pid Process ID to pin
     * @param cpuMask CPU affinity mask (e.g., "0xff" for CPUs 0-7, "0x80" for CPU 7 only)
     * @return true if successful
     */
    fun setCpuAffinity(pid: Int, cpuMask: String): Boolean {
        if (!isPServerAvailable) {
            Timber.tag(TAG).w("PServer not available for CPU affinity")
            return false
        }

        return try {
            val command = "taskset -p $cpuMask $pid"
            val result = executeAsRoot(command)

            if (result.isSuccess) {
                Timber.tag(TAG).i("Set CPU affinity for PID $pid to mask $cpuMask")
                true
            } else {
                Timber.tag(TAG).e("Failed to set CPU affinity: ${result.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set CPU affinity for PID $pid")
            false
        }
    }

    /**
     * Pin a process to specific CPU cores by core list.
     *
     * @param pid Process ID to pin
     * @param cpuList List of CPU core numbers (e.g., listOf(3, 4, 5, 6, 7))
     * @return true if successful
     */
    fun setCpuAffinityByCores(pid: Int, cpuList: List<Int>): Boolean {
        if (cpuList.isEmpty()) {
            Timber.tag(TAG).w("Empty CPU list provided")
            return false
        }

        // Convert CPU list to bitmask
        // e.g., [3,4,5,6,7] -> 0xf8 (binary: 11111000)
        val mask = cpuList.fold(0) { acc, cpu -> acc or (1 shl cpu) }
        val hexMask = getTasksetMask(mask)

        return setCpuAffinity(pid, hexMask)
    }

    /**
     * Get the correct taskset mask format for this system.
     * Detects once and caches the result.
     *
     * @param mask Bitmask value (e.g., 0xf8 = 248)
     * @return Formatted mask string (e.g., "f8" or "0xf8")
     */
    fun getTasksetMask(mask: Int): String {
        // Return cached format if already detected
        if (tasksetMaskFormat != null) {
            return when (tasksetMaskFormat) {
                TasksetMaskFormat.PLAIN_HEX -> mask.toString(16)
                TasksetMaskFormat.HEX_PREFIX -> "0x${mask.toString(16)}"
                else -> mask.toString(16)
            }
        }

        // Detect format by testing with a simple command
        try {
            val testMask = "0x1"  // Test with 0x prefix
            val command = arrayOf("sh", "-c", "taskset $testMask echo test 2>&1")
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()

            tasksetMaskFormat = if (process.exitValue() != 0) {
                // 0x prefix failed, use plain hex
                Timber.tag(TAG).d("Detected taskset format: plain hex (no 0x prefix)")
                TasksetMaskFormat.PLAIN_HEX
            } else {
                // 0x prefix works
                Timber.tag(TAG).d("Detected taskset format: hex with 0x prefix")
                TasksetMaskFormat.HEX_PREFIX
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to detect taskset format, defaulting to plain hex")
            tasksetMaskFormat = TasksetMaskFormat.PLAIN_HEX
        }

        // Return formatted mask
        return when (tasksetMaskFormat) {
            TasksetMaskFormat.PLAIN_HEX -> mask.toString(16)
            TasksetMaskFormat.HEX_PREFIX -> "0x${mask.toString(16)}"
            else -> mask.toString(16)
        }
    }

    /**
     * Get the process ID for a given package name or process name.
     *
     * @param packageName Package name (e.g., "app.gamenative") or process name
     * @return Process ID or null if not found
     */
    fun getProcessId(packageName: String): Int? {
        return try {
            val result = executeAsRoot("pidof $packageName")
            result.getOrNull()?.trim()?.toIntOrNull()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get PID for $packageName")
            null
        }
    }

    /**
     * Find Running processes searching command line.
     * This is more reliable for Wine processes than pidof.
     *
     * @return List of pairs containing process ID and command line
     */
    private fun findRunningProcesses(): List<Pair<Int, String>> {
        return try {
            val command = arrayOf("sh", "-c", "ps -eo pid=,args= | awk '{ pid=\$1; \$1=\"\"; sub(/^ /, \"\"); print pid \"|\" \$0 }'")
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()

            if (output.isNullOrEmpty()) {
                return emptyList()
            }

            val processes = output.lines().mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) {
                    return@mapNotNull null
                }

                val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
                val cmdline = parts[1]

                if (cmdline.contains("ps -eo", ignoreCase = true)) {
                    return@mapNotNull null
                }

                Pair(pid, cmdline)
            }

            processes
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to find Running processe")
            emptyList()
        }
    }

    /**
     * Find Running processes by searching for executable name.
     * This is more reliable for Wine processes than pidof.
     *
     * @param executableName x name (e.g., "YookaLaylee64.exe")
     * @return List of pairs containing process ID and command line
     */
    fun findRunningProcesses(executableName: String): List<Pair<Int, String>> {
        return try {
            val allProcesses = findRunningProcesses()
            val matchingProcesses = allProcesses.filter { (_, cmdline) ->
                cmdline.contains(executableName, ignoreCase = false)
            }

            if (matchingProcesses.isNotEmpty()) {
                Timber.tag(TAG).d("Found ${matchingProcesses.size} Wine process(es) for $executableName")
            }

            matchingProcesses
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to find Wine process for $executableName")
            emptyList()
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

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
