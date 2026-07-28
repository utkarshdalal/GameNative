package app.gamenative.powercontrol

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.powercontrol.autotuning.PerformanceAutoTuner
import app.gamenative.powercontrol.drivers.NoOpPerformanceDriver
import app.gamenative.powercontrol.drivers.PServerDriver
import app.gamenative.powercontrol.drivers.PerformanceDriver
import app.gamenative.powercontrol.drivers.SamsungPerformanceDriver
import app.gamenative.powercontrol.profiles.CpuGovernor
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Manager for CPU and GPU performance control.
 * Provides a unified interface for CPU frequency, governor, and GPU power management.
 * Uses a PerformanceDriver implementation for device-specific operations.
 */
object PowerManager {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private var driver: PerformanceDriver? = null
    private var autoTuner: PerformanceAutoTuner? = null

    /**
     * The currently active power profile.
     * Updated when settings change, used for saving on stop.
     */
    var currentProfile: PowerProfile? = null
        private set

    var targetFps: Int = 0
        set(value) {
            // Enforce non-negative values and round/clamp if necessary
            field = value.coerceAtLeast(0)
        }

    var currentFps: Float = 0f
        set(value) {
            // Enforce non-negative values and round/clamp if necessary
            field = value.coerceAtLeast(0f)
        }

    var currentCpuUsage: Float = 0f
        set(value) {
            // Enforce 0-100% range
            field = value.coerceIn(0f, 100f)
        }

    var currentGpuUsage: Float = 0f
        set(value) {
            // Enforce 0-100% range
            field = value.coerceIn(0f, 100f)
        }

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

        // Pin PulseAudio to dedicated performance core if PServer is available
        pinPulseAudioToDedicatedCore()
    }

    /**
     * Stop the performance driver and save current profile
     */
    fun stop() {
        // Save the current profile if available, otherwise read from driver
        saveProfile()
        stopAutoTuning()
        getDriver().stop()
    }

    /**
     * Start automatic performance tuning.
     * Uses PID controller to adjust CPU/GPU/Bus frequencies based on targetFps and utilization.
     * Works with any driver that supports CPU frequency and GPU power level control.
     */
    fun startAutoTuning() {
        val driver = getDriver()

        if (autoTuner?.isRunning() == true) {
            Timber.tag("PowerManager").w("Auto-tuning already running")
            return
        }

        // Check if driver supports required features
        val availableCpuFreqs = driver.getAvailableCpuFrequencies()
        if (availableCpuFreqs.isEmpty()) {
            Timber.tag("PowerManager").w("Auto-tuning requires CPU frequency control")
            return
        }

        val numGpuLevels = if (driver.isGpuSupported()) driver.getNumGpuPowerLevels() else 0
        val numBusLevels = if (driver.isBusSupported()) driver.getNumBusLevels() else 0

        autoTuner = PerformanceAutoTuner(
            availableCpuFreqs = availableCpuFreqs,
            numGpuLevels = numGpuLevels,
            numBusLevels = numBusLevels,
            onCpuFrequencyChange = { freq ->
                update {
                    setMinCpuValue(freq)
                    setMaxCpuValue(freq)
                }
            },
            onGpuLevelChange = { level ->
                update {
                    setMinGpuPowerLevel(level)
                    setMaxGpuPowerLevel(level)
                }
            },
            onBusLevelChange = { level ->
                update {
                    setMinBusLevel(level)
                    setMaxBusLevel(level)
                }
            },
            getTuningStrategy = { currentProfile?.tuningStrategy ?: AutoTuningStrategy.BALANCED },
            enableLogging = BuildConfig.DEBUG
        )

        autoTuner?.start()
        Timber.tag("PowerManager").i("Auto-tuning started (CPU freqs: ${availableCpuFreqs.size}, GPU levels: $numGpuLevels, Bus levels: $numBusLevels)")
    }

    /**
     * Stop automatic performance tuning.
     */
    fun stopAutoTuning() {
        autoTuner?.let {
            if (!it.isRunning()) {
                Timber.tag("PowerManager").w("Auto-tuning not running")
                return
            }
            it.stop()
            autoTuner = null
        } ?: run {
            Timber.tag("PowerManager").w("Auto-tuning not initialized")
        }
    }

    /**
     * Update the current profile reference.
     * Should be called when the UI changes the active profile.
     */
    fun setCurrentProfile(profile: PowerProfile) {
        currentProfile = profile

        // Handle auto-tuning based on profile setting
        if (profile.enableAutoTuning) {
            startAutoTuning()
        } else {
            stopAutoTuning()
        }
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
            val jsonString = if (currentProfile != null) {
                json.encodeToString(currentProfile)
            } else ""
            PrefManager.powerControlProfile = jsonString
            Timber.tag("PowerManager").d("Saved power profile: $jsonString")
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to save power profile")
        }
    }

    // ========================================
    // CPU Affinity / Process Pinning
    // ========================================

    /**
     * Pin PulseAudio daemon to a dedicated core.
     * Strategy varies by cluster count:
     * - Dual-cluster (e.g., Odin 3): Pin to first efficiency/lower-frequency core
     * - Tri-cluster: Pin to first efficiency core
     * - Single-cluster: Pin to first available core
     */
    private fun pinPulseAudioToDedicatedCore() {
        val driver = getDriver()
        if (driver !is PServerDriver) return

        Thread {
            try {
                // Give PulseAudio time to start if it wasn't already running
                Thread.sleep(500)

                val audioPid = driver.getProcessId("libpulseaudio.so")
                if (audioPid != null) {
                    val clusterCount = driver.getCpuClusterCount()
                    val effCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.EFFICIENCY)
                    val perfCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.PERFORMANCE)

                    // Choose cores based on cluster configuration
                    val audioCores = when {
                        effCores.isNotEmpty() -> listOf(effCores.first())
                        perfCores.isNotEmpty() -> listOf(perfCores.first())
                        else -> emptyList()
                    }

                    if (audioCores.isNotEmpty()) {
                        val success = driver.setCpuAffinityByCores(audioPid, audioCores)
                        if (success) {
                            Timber.tag("PowerManager").i("Pinned PulseAudio (PID: $audioPid) to CPU ${audioCores.first()} ($clusterCount clusters)")
                        }
                    }
                } else {
                    Timber.tag("PowerManager").d("PulseAudio not found, skipping audio pinning")
                }
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin PulseAudio")
            }
        }.start()
    }

    /**
     * Pin Background processes for optimal game performance.
     * Strategy varies by cluster count:
     * - Dual-cluster (e.g., Odin 3): Pin to efficiency/lower-frequency cores to free prime cores for game
     * - Tri-cluster: Pin to efficiency + performance cores, leave prime for game
     * - Single-cluster: Pin to all available cores
     */
    fun pinBackgroundProcesses() {
        val driver = getDriver()
        if (driver !is PServerDriver) return

        Thread {
            try {
                // Wait for Wine to fully initialize
                Thread.sleep(2000)

                val clusterCount = driver.getCpuClusterCount()
                val effCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.EFFICIENCY)
                val perfCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.PERFORMANCE)

                // Determine Wine infrastructure cores based on cluster configuration
                val wineCores = when (clusterCount) {
                    1 -> perfCores // Single cluster: use all cores
                    2 -> effCores  // Dual cluster: use lower-frequency cores, save prime for game
                    else -> effCores + perfCores // Tri+ cluster: use eff + perf, save prime for game
                }

                if (wineCores.isEmpty()) {
                    Timber.tag("PowerManager").w("No cores available for Wine pinning")
                    return@Thread
                }

                // Pin wineserver to Wine infrastructure cores (critical for Wine IPC)
                driver.findRunningProcesses("wineserver")
                    .firstOrNull { it.second.endsWith("wineserver") }?.let {
                    val pid = it.first
                    val success = driver.setCpuAffinityByCores(pid, wineCores)
                    if (success) {
                        Timber.tag("PowerManager").i("Pinned wineserver (PID: $pid) to CPUs ${wineCores.joinToString()}")
                    }
                }

                // Pin winhandler to Wine infrastructure cores
                driver.findRunningProcesses("winhandler.exe")
                    .firstOrNull { it.second.endsWith("winhandler.exe") }?.let {
                    val pid = it.first
                    val success = driver.setCpuAffinityByCores(pid, wineCores)
                    if (success) {
                        Timber.tag("PowerManager").i("Pinned winhandler.exe (PID: $pid) to CPUs ${wineCores.joinToString()}")
                    }
                }

                // Pin services.exe to first two Wine infrastructure cores
                driver.findRunningProcesses("services.exe")
                    .firstOrNull { it.second.endsWith("services.exe") }?.let {
                    val pid = it.first
                    val serviceCores = wineCores.take(2)
                    if (serviceCores.isNotEmpty()) {
                        val success = driver.setCpuAffinityByCores(pid, serviceCores)
                        if (success) {
                            Timber.tag("PowerManager").i("Pinned services.exe (PID: $pid) to CPUs ${serviceCores.joinToString()}")
                        }
                    }
                }

                // Pin libsteambootstrap.so to first two Wine infrastructure cores
                driver.findRunningProcesses("libsteambootstrap.so")
                    .firstOrNull { it.second.contains("libsteambootstrap.so") }?.let {
                        val pid = it.first
                        val serviceCores = wineCores.take(2)
                        if (serviceCores.isNotEmpty()) {
                            val success = driver.setCpuAffinityByCores(pid, serviceCores)
                            if (success) {
                                Timber.tag("PowerManager").i("Pinned libsteambootstrap.so (PID: $pid) to CPUs ${serviceCores.joinToString()}")
                            }
                        }
                    }

            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin Wine infrastructure")
            }
        }.start()
    }

    /**
     * Pin a game process with retry logic.
     * Strategy varies by cluster count:
     * - Dual-cluster (e.g., Odin 3): Pin to prime cores only for maximum performance
     * - Tri-cluster: Pin to performance + prime cores
     * - Single-cluster: Pin to all available cores
     *
     * @param processName Process name or package name
     * @param maxRetries Maximum number of retry attempts (default: 10)
     * @param retryDelayMs Delay between retries in milliseconds (default: 1000)
     */
    fun pinGameWithRetry(
        processName: String,
        maxRetries: Int = 10,
        retryDelayMs: Long = 1000
    ) {
        val driver = getDriver()
        if (driver !is PServerDriver) return

        Thread {
            try {
                var retries = maxRetries
                val isWineExecutable = processName.endsWith(".exe", ignoreCase = true)

                while (retries > 0) {
                    // Use Wine-specific search for .exe files, regular pidof for others
                    val pid = if (isWineExecutable) {
                        driver.findRunningProcesses(processName).find {
                            it.second.endsWith(processName, ignoreCase = true) &&
                            !it.second.contains("winhandler.exe")
                        }?.first
                    } else {
                        driver.getProcessId(processName)
                    }

                    if (pid != null) {
                        val clusterCount = driver.getCpuClusterCount()
                        val perfCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.PERFORMANCE)
                        val primeCores = driver.getCpuCoresByCluster(PServerDriver.CpuCluster.PRIME)

                        // Determine game cores based on cluster configuration
                        val gameCores = when (clusterCount) {
                            1 -> perfCores // Single cluster: use all cores
                            2 -> primeCores.ifEmpty { perfCores } // Dual: prime only (or perf if no prime)
                            else -> perfCores + primeCores // Tri+: perf + prime, leave efficiency for background
                        }

                        if (gameCores.isNotEmpty()) {
                            val success = driver.setCpuAffinityByCores(pid, gameCores)
                            if (success) {
                                Timber.tag("PowerManager").i(
                                    "Pinned $processName (PID: $pid) to CPUs ${gameCores.joinToString()} ($clusterCount clusters) after ${maxRetries - retries + 1} attempts"
                                )
                            }
                        }
                        return@Thread
                    }
                    Thread.sleep(retryDelayMs)
                    retries--
                }
                Timber.tag("PowerManager").w("Failed to find process after $maxRetries attempts: $processName")
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin game with retry: $processName")
            }
        }.start()
    }

    /**
     * Restore the saved power profile from preferences
     */
    private fun restoreSavedProfile() {
        try {
            val jsonString = PrefManager.powerControlProfile
            if (jsonString.isEmpty()) {
                currentProfile = driver?.getDefaultProfile()
                Timber.tag("PowerManager").d("No saved profile to restore")
                return
            }

            currentProfile = json.decodeFromString<PowerProfile>(jsonString)
            Timber.tag("PowerManager").d("Restoring power profile: $jsonString")

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

            if (currentProfile?.enableAutoTuning == true) {
                startAutoTuning()
            }
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to restore power profile, falling back to default")
            currentProfile = getDriver().getDefaultProfile()
        }
    }
}
