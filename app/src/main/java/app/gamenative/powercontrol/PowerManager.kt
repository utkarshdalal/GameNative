package app.gamenative.powercontrol

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.powercontrol.autotuning.ClusterTuner
import app.gamenative.powercontrol.autotuning.PerformanceAutoTuner
import app.gamenative.powercontrol.drivers.NoOpPerformanceDriver
import app.gamenative.powercontrol.drivers.PServerDriver
import app.gamenative.powercontrol.drivers.PerformanceDriver
import app.gamenative.powercontrol.drivers.SamsungPerformanceDriver
import app.gamenative.powercontrol.fan.FanController
import app.gamenative.powercontrol.metrics.MetricsSnapshot
import app.gamenative.powercontrol.metrics.PerformanceMetricsCollector
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import com.winlator.container.Container
import com.winlator.core.ProcessHelper
import com.winlator.winhandler.WinHandler
import com.winlator.xserver.extensions.PresentExtension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Manager for CPU and GPU performance control.
 * Provides a unified interface for CPU frequency, governor, and GPU power management.
 * Uses a PerformanceDriver implementation for device-specific operations.
 */
object PowerManager {
    private const val AFFINITY_SETTLE_MS = 1500L
    private const val GAME_PIN_MAX_RETRIES = 10
    private const val GAME_PIN_RETRY_DELAY_MS = 1000L

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private var driver: PerformanceDriver? = null
    private var autoTuner: PerformanceAutoTuner? = null
    private var clusterTuner: ClusterTuner? = null
    private var containerDir: File? = null
    private var appContext: Context? = null

    /**
     * Flag to track if a game has been started.
     * Used to guard pause/resume operations.
     */
    @Volatile
    var isGameStarted: Boolean = false

    /**
     * The currently active power profile.
     * Updated when settings change, used for saving on stop.
     */
    var currentProfile: PowerProfile? = null
        private set

    /**
     * Observable UI state for the power control quick menu.
     * Rebuilt by [refreshUiState] after any setting changes.
     */
    private val _uiState = MutableStateFlow<PowerControlUiState>(PowerControlUiState.Loading)
    val uiState: StateFlow<PowerControlUiState> = _uiState.asStateFlow()

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
     * Full metrics snapshot of the most recent collector cycle, or null when no
     * game session is active.
     */
    @Volatile
    var latestMetrics: MetricsSnapshot? = null

    /**
     * Process name of the game [pinGameWithRetry] pinned, or null when nothing is pinned.
     */
    @Volatile
    var pinnedGameProcessName: String? = null
        private set

    /**
     * PID of the pinned game process, or null when nothing is pinned.
     */
    @Volatile
    var pinnedGamePid: Int? = null
        private set

    /**
     * Exact core list last applied to the pinned game, empty when nothing is pinned.
     */
    @Volatile
    var pinnedGameCores: List<Int> = emptyList()
        private set

    /**
     * True while power control is allowed to move the game between cores. False when the
     * container carries an explicit CPU list, because that list is the user's own choice and the
     * winhandler applies it to every thread of the game anyway.
     */
    @Volatile
    var ownsGameAffinity: Boolean = false
        private set

    /**
     * Bumped whenever a pin run starts or is called off, so a retry loop left over from an earlier
     * run stops instead of pinning the game again.
     */
    @Volatile
    private var gamePinGeneration: Int = 0

    /**
     * Initialize PowerManager with application context.
     * Should be called once during application startup.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
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

        // Reset the driver on initialize. For PServer this also restores the recorded
        // baseline of a session that died without restoring it.
        (driver as? PServerDriver)?.let {
            if (it.hasPendingBaseline()) {
                Timber.tag("PowerControl").i("Power baseline from a previous session found on disk, restoring it")
            }
        }
        driver?.reset()
    }

    private fun getDriver(): PerformanceDriver {
        return driver ?: NoOpPerformanceDriver().also {
            Timber.tag("PowerManager").w("PowerManager not initialized, using NoOpPerformanceDriver as fallback")
            driver = it
        }
    }

    /**
     * Get Driver Default Profile
     */
    fun getDriverDefaultProfile(): PowerProfile = getDriver().getDefaultProfile()

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
     * @param containerDir The container directory for saving/restoring per-container profiles
     */
    fun start(containerDir: File? = null) {
        this.containerDir = containerDir
        resolveGameAffinityOwnership()
        getDriver().start()
        restoreSavedProfile()

        // Pin PulseAudio to dedicated performance cores if PServer is available
        pinPulseAudioToDedicatedCores()
        isGameStarted = true

        if (currentProfile?.enableAdaptiveFpsCap != false) {
            AdaptiveFpsCapController.start(containerDir, tunerLogDirectory())
        }

        appContext?.let { PerformanceMetricsCollector.start(it) }
            ?: Timber.tag("PowerManager").w("No application context, metrics collector not started")

        if (currentProfile?.enableFanControl != false) {
            FanController.start(getDriver())
        }
    }

    /**
     * Stop the performance driver and save current profile
     */
    fun stop() {
        // Save the current profile if available, otherwise read from driver
        saveProfile()
        stopAutoTuning()
        AdaptiveFpsCapController.stop()
        PerformanceMetricsCollector.stop()
        FanController.stop()
        getDriver().stop()
        isGameStarted = false
        fpsCapApplier = null
        frameSampleStride = 1
        containerDir = null
        pinnedGameProcessName = null
        pinnedGamePid = null
        pinnedGameCores = emptyList()
        ownsGameAffinity = false
    }

    /**
     * Pause the performance driver and auto-tuning when app goes to background
     */
    fun pause() {
        if (!isGameStarted) return
        saveProfile()
        stopAutoTuning()
        AdaptiveFpsCapController.pause()
        PerformanceMetricsCollector.pause()
        FanController.pause()
        getDriver().stop()
    }

    /**
     * Resume the performance driver and auto-tuning when app comes to foreground
     */
    fun resume() {
        if (!isGameStarted) return
        getDriver().start()
        restoreSavedProfile()
        if (currentProfile?.enableAdaptiveFpsCap != false) {
            AdaptiveFpsCapController.start(containerDir, tunerLogDirectory())
        }
        PerformanceMetricsCollector.resume()
        if (currentProfile?.enableFanControl != false) {
            FanController.start(getDriver())
        }
    }

    /**
     * Start automatic performance tuning.
     * Uses PID controller to adjust CPU/GPU/Bus frequencies based on targetFps and utilization.
     * Works with any driver that supports CPU frequency and GPU power level control.
     */
    fun startAutoTuning() {
        val driver = getDriver()

        if (autoTuner?.isRunning() == true || clusterTuner?.isRunning() == true) {
            Timber.tag("PowerManager").w("Auto-tuning already running")
            return
        }

        val perClusterTuning = currentProfile?.enablePerClusterTuning ?: true
        val clusterTuningSupported = driver.isPerClusterSupported()
        val pserver = driver as? PServerDriver
        if (perClusterTuning && clusterTuningSupported && pserver != null) {
            Timber.tag("PowerManager").i("Selected ClusterTuner (per-cluster tuning on, PServer driver, model: ${Build.MODEL})")
            if (startClusterTuning(pserver)) return
            Timber.tag("PowerManager").w("Cluster tuner unavailable, falling back to PerformanceAutoTuner")
        } else {
            Timber.tag("PowerManager").i(
                "Selected PerformanceAutoTuner (per-cluster tuning: $perClusterTuning, cluster tuning supported: $clusterTuningSupported, model: ${Build.MODEL}, driver: ${driver::class.simpleName})"
            )
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
                if (currentProfile?.minCpuFreq != freq || currentProfile?.maxCpuFreq != freq) {
                    update {
                        setMinCpuValue(freq)
                        setMaxCpuValue(freq)
                    }
                }
            },
            onGpuLevelChange = { level ->
                if (currentProfile?.minGpuPowerLevel != level || currentProfile?.maxGpuPowerLevel != level) {
                    update {
                        setMinGpuPowerLevel(level)
                        setMaxGpuPowerLevel(level)
                    }
                }
            },
            onBusLevelChange = { level ->
                if (currentProfile?.minBusLevel != level || currentProfile?.maxBusLevel != level) {
                    update {
                        setMinBusLevel(level)
                        setMaxBusLevel(level)
                    }
                }
            },
            getTuningStrategy = { currentProfile?.tuningStrategy ?: AutoTuningStrategy.BALANCED },
            enableLogging = BuildConfig.DEBUG,
            skipWarmupCycles = isGameStarted
        )

        autoTuner?.start()
        Timber.tag("PowerManager").i("Auto-tuning started (CPU freqs: ${availableCpuFreqs.size}, GPU levels: $numGpuLevels, Bus levels: $numBusLevels)")
    }

    /**
     * Start the per-cluster, frame-pacing aware tuner.
     * Unlike [PerformanceAutoTuner] it caps each cluster separately, never touches
     * scaling_min_freq and never writes its values back into [currentProfile].
     * @return true when the tuner took over tuning for this session
     */
    private fun startClusterTuning(pserver: PServerDriver): Boolean {
        val primeSteps = pserver.getAvailableCpuFrequenciesForCluster(PServerDriver.CpuCluster.PRIME)
        val performanceSteps = pserver.getAvailableCpuFrequenciesForCluster(PServerDriver.CpuCluster.PERFORMANCE)
        val gpuSteps = if (pserver.isGpuSupported()) {
            val numLevels = pserver.getNumGpuPowerLevels()
            if (numLevels > 0) (0 until numLevels).toList() else emptyList()
        } else {
            emptyList()
        }

        if (primeSteps.size < 2 && performanceSteps.size < 2 && gpuSteps.size < 2) {
            Timber.tag("PowerManager").w("Cluster tuner has no controllable domain")
            return false
        }

        val tuner = ClusterTuner(
            primeSteps = primeSteps,
            performanceSteps = performanceSteps,
            gpuSteps = gpuSteps,
            applyPrimeCapKhz = { freq ->
                pserver.setMaxCpuValueForCluster(PServerDriver.CpuCluster.PRIME, freq)
            },
            applyPerformanceCapKhz = { freq ->
                pserver.setMaxCpuValueForCluster(PServerDriver.CpuCluster.PERFORMANCE, freq)
            },
            applyGpuMaxLevel = { level -> pserver.setMaxGpuPowerLevel(level) },
            metricsProvider = { latestMetrics },
            targetFpsProvider = { targetFps },
            fanSampleProvider = { FanController.latestSample },
            strategyProvider = { currentProfile?.tuningStrategy ?: AutoTuningStrategy.BALANCED },
            fpsCapProvider = { AdaptiveFpsCapController.snapshot() },
            stateFile = ClusterTuner.stateFileFor(containerDir),
            logDirectory = tunerLogDirectory(),
        )

        clusterTuner = tuner
        tuner.start()
        Timber.tag("PowerManager").i(
            "Cluster tuning started (prime: ${primeSteps.size} steps, performance: ${performanceSteps.size} steps, GPU: ${gpuSteps.size} levels)"
        )
        return true
    }

    private fun tunerLogDirectory(): File? {
        return appContext?.let { context ->
            File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                PowerBaselineScripts.DIRECTORY_NAME
            )
        }
    }

    /**
     * Clock steps the cluster tuner currently holds back, or null when it is not running.
     */
    internal fun tunerTrimmedSteps(): Int? = clusterTuner?.trimmedSteps()

    /**
     * True while the cluster tuner's trimmed steps come from a harvest, so they are taken
     * off a domain that is not the bottleneck. Null when it is not running.
     */
    internal fun tunerIsHarvesting(): Boolean? = clusterTuner?.isHarvesting()

    /**
     * Asks the cluster tuner to reopen every domain for an FPS cap probe.
     * @return true when a running tuner took the request
     */
    internal fun openTunerClocksForProbe(): Boolean {
        val tuner = clusterTuner ?: return false
        if (!tuner.isRunning()) return false
        tuner.requestOpenClocks()
        return true
    }

    /**
     * Pushes an FPS cap into the live frame-limiting engines, the same ones the quick menu
     * limiter drives. The container's stored limiter value is left untouched, so the user's
     * setting stays the ceiling.
     * @return true when the cap reached a running X server view
     */
    /** Reroutes cap changes while frame generation owns pacing; declining
     *  falls through to the engine path. Installed by XServerScreen. */
    @Volatile
    var fpsCapApplier: ((Int) -> Boolean)? = null

    /** Frame-ring timestamps per base frame (= LSFG multiplier while frame
     *  generation runs, 1 otherwise) so frame stats stay in base units. */
    @Volatile
    var frameSampleStride: Int = 1

    internal fun applyFpsCapToEngines(limitFps: Int): Boolean {
        fpsCapApplier?.let { if (it(limitFps)) return true }
        val xServerView = PluviaApp.xServerView ?: return false
        val presentExtension = xServerView.getxServer()
            ?.getExtension<PresentExtension>(PresentExtension.MAJOR_OPCODE.toInt())

        val apply = Runnable {
            xServerView.setFrameRateLimit(limitFps)
            presentExtension?.setFrameRateLimit(limitFps)
            com.winlator.xserver.ShmFramePacer.setFrameRateLimit(limitFps)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run()
        } else {
            Handler(Looper.getMainLooper()).post(apply)
        }

        targetFps = limitFps
        return true
    }

    /**
     * Stop automatic performance tuning. Both tuners are stopped so a tuner left over from
     * an earlier selection can never keep running.
     */
    fun stopAutoTuning() {
        if (clusterTuner == null && autoTuner == null) {
            Timber.tag("PowerManager").w("Auto-tuning not initialized")
            return
        }

        clusterTuner?.let {
            it.stop()
            clusterTuner = null
        }

        autoTuner?.let {
            if (it.isRunning()) {
                it.stop()
            } else {
                Timber.tag("PowerManager").w("Auto-tuning not running")
            }
            autoTuner = null
        }
    }

    /**
     * Update the current profile reference.
     * Should be called when the UI changes the active profile.
     */
    fun setCurrentProfile(profile: PowerProfile) {
        val previousProfile = currentProfile
        currentProfile = profile

        // Handle auto-tuning based on profile setting
        if (profile.enableAutoTuning) {
            if (previousProfile != null && previousProfile.enablePerClusterTuning != profile.enablePerClusterTuning) {
                Timber.tag("PowerManager").i(
                    "Per-cluster tuning changed to ${profile.enablePerClusterTuning}, restarting the tuner"
                )
                stopAutoTuning()
            }
            startAutoTuning()
        } else {
            stopAutoTuning()
        }

        if (isGameStarted) {
            if (profile.enableAdaptiveFpsCap) {
                AdaptiveFpsCapController.start(containerDir, tunerLogDirectory())
            } else {
                AdaptiveFpsCapController.stop()
            }

            if (profile.enableFanControl) {
                FanController.start(getDriver())
            } else {
                FanController.stop()
            }

            if (previousProfile != null && previousProfile.enableGamePinning != profile.enableGamePinning) {
                val processName = pinnedGameProcessName
                if (profile.enableGamePinning) {
                    if (processName != null) {
                        Timber.tag("PowerManager").i("Game pinning switched on, pinning $processName now")
                        startGamePin(processName, "live toggle")
                    } else {
                        Timber.tag("PowerManager").i("Game pinning switched on, no game process recorded yet")
                    }
                } else {
                    unpinGame()
                }
            }
        }
    }

    /**
     * Rebuild [uiState] from the current driver and profile state.
     * Performs blocking sysfs/driver reads, so call it from a background thread.
     */
    fun refreshUiState() {
        try {
            val cpuInfo = getCpuInfo() ?: return

            val availableGovernors = getAvailableGovernors()
            val availableFrequencies = getAvailableCpuFrequencies()

            val gpuDisplayInfo = if (isGpuSupported()) {
                val gpuInfo = getGpuInfo()
                val availableGpuFrequencies = getAvailableGpuFrequencies()
                if (gpuInfo != null) {
                    val maxGpuPowerLevel = if (gpuInfo.numGpuPowerLevels > 0) {
                        gpuInfo.numGpuPowerLevels - 1
                    } else 0
                    val currentFreqIndex = if (availableGpuFrequencies.isNotEmpty()) {
                        availableGpuFrequencies.indexOfFirst {
                            it >= gpuInfo.currentGpuValue
                        }.coerceAtLeast(0)
                    } else {
                        0
                    }
                    GpuDisplayInfo(
                        availableFrequencies = availableGpuFrequencies,
                        currentFreqIndex = currentFreqIndex,
                        minPowerLevel = gpuInfo.minGpuPowerLevel,
                        maxPowerLevel = gpuInfo.maxGpuPowerLevel,
                        maxAvailablePowerLevel = maxGpuPowerLevel
                    )
                } else null
            } else null

            val ramDisplayInfo = if (isBusSupported()) {
                val busInfo = getBusInfo()
                if (busInfo != null && busInfo.numBusLevels > 0) {
                    RamDisplayInfo(
                        minBusLevel = busInfo.minBusLevel,
                        maxBusLevel = busInfo.maxBusLevel,
                        maxAvailableBusLevel = busInfo.numBusLevels - 1
                    )
                } else {
                    null
                }
            } else {
                null
            }

            val selectedMinFreqIndex = availableFrequencies.indexOfFirst {
                it >= cpuInfo.currentMinValue
            }.coerceAtLeast(0)
            val selectedMaxFreqIndex = availableFrequencies.indexOfFirst {
                it >= cpuInfo.currentMaxValue
            }.coerceAtLeast(0)

            val maxGpuPowerLevel = gpuDisplayInfo?.maxAvailablePowerLevel ?: 0
            val profiles = PowerProfiles.getDefaultProfiles(availableGovernors, availableFrequencies, maxGpuPowerLevel)

            val currentGovernor = CpuGovernor.fromString(cpuInfo.currentGovernor)

            // Try to match current settings against available profiles
            // Match by PowerManager's current profile name
            val matchingProfile = profiles.find { profile ->
                profile.name == (currentProfile?.name ?: PerformancePreset.CUSTOM.displayName)
            }

            val selectedProfile = (matchingProfile ?: PowerProfile(
                name = PerformancePreset.CUSTOM.displayName,
                governor = currentGovernor ?: CpuGovernor.SCHEDUTIL,
                minCpuFreq = cpuInfo.currentMinValue,
                maxCpuFreq = cpuInfo.currentMaxValue,
                minGpuPowerLevel = gpuDisplayInfo?.minPowerLevel ?: 0,
                maxGpuPowerLevel = gpuDisplayInfo?.maxPowerLevel ?: 0,
                minBusLevel = ramDisplayInfo?.minBusLevel ?: 0,
                maxBusLevel = ramDisplayInfo?.maxBusLevel ?: 0
            )).copy(
                // Preserve enableAutoTuning and tuningStrategy from PowerManager's current profile
                enableAutoTuning = currentProfile?.enableAutoTuning ?: true,
                enablePerClusterTuning = currentProfile?.enablePerClusterTuning ?: true,
                enableAdaptiveFpsCap = currentProfile?.enableAdaptiveFpsCap ?: true,
                enableFanControl = currentProfile?.enableFanControl ?: true,
                enableGamePinning = currentProfile?.enableGamePinning ?: false,
                tuningStrategy = currentProfile?.tuningStrategy ?: AutoTuningStrategy.POWER_EFFICIENT
            )

            _uiState.value = PowerControlUiState.Success(
                cpuInfo = CpuDisplayInfo(
                    currentGovernor = cpuInfo.currentGovernor,
                    availableGovernors = availableGovernors,
                    availableFrequencies = availableFrequencies,
                    currentMinValue = cpuInfo.currentMinValue,
                    currentMaxValue = cpuInfo.currentMaxValue,
                    selectedMinFreqIndex = selectedMinFreqIndex,
                    selectedMaxFreqIndex = selectedMaxFreqIndex
                ),
                gpuInfo = gpuDisplayInfo,
                selectedProfile = selectedProfile,
                availableProfiles = profiles,
                ramInfo = ramDisplayInfo,
            )
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to refresh power control UI state")
        }
    }

    /**
     * Caps currently applied by the cluster tuner, or null when it is not running.
     */
    fun latestTunerCaps(): ClusterTuner.Caps? = clusterTuner?.latestCaps()

    /**
     * True when driver support fan control and the FanController is usable
     */
    fun isFanControlAvailable(): Boolean {
        val driver = getDriver()
        return getDriver().isFanSupported() && FanController.isAvailable(driver)
    }

    /**
     * True when this session can hold the game on the fast CPU cores.
     */
    fun isGamePinningAvailable(): Boolean = getDriver().isCpuPinningSupported()

    /**
     * True when auto-tuning caps each CPU cluster separately via [ClusterTuner].
     */
    fun isClusterTuningAvailable(): Boolean = getDriver().isPerClusterSupported()

    /**
     * Check if driver is supported
     */
    fun isDriverSupported(): Boolean = getDriver().isDriverSupported()

    /**
     * Get display unit preference for frequency values
     */
    fun getDisplayUnit(): PerformanceDriver.DisplayUnit = getDriver().getDisplayUnit()

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
    // File I/O
    // ========================================

    /**
     * Read a file using the driver's file reading capabilities.
     * For PServerDriver, this uses root access with fallback to direct file read.
     * For other drivers, returns null.
     * @param path The absolute path to the file to read
     * @return The file contents as a trimmed string, or null if the file cannot be read
     */
    fun readFile(path: String): String? = getDriver().readFile(path)

    // ========================================
    // Profile Persistence
    // ========================================

    /**
     * Save a power profile to container-specific file using atomic write.
     * Uses AtomicFile to prevent corruption if process is killed during write.
     */
    fun saveProfile() {
        try {
            val jsonString = if (currentProfile != null) {
                json.encodeToString(currentProfile)
            } else ""

            val profileFile = getProfileFile()
            if (profileFile != null) {
                profileFile.parentFile?.mkdirs()

                val atomicFile = AtomicFile(profileFile)
                val stream = atomicFile.startWrite()
                try {
                    stream.write(jsonString.toByteArray(Charsets.UTF_8))
                    atomicFile.finishWrite(stream)
                    Timber.tag("PowerManager").d("Saved power profile to ${profileFile.absolutePath}: $jsonString")
                } catch (e: Exception) {
                    atomicFile.failWrite(stream)
                    throw e
                }
            } else {
                Timber.tag("PowerManager").w("No container directory set, skipping profile save")
            }
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to save power profile")
        }
    }

    // ========================================
    // CPU Affinity / Process Pinning
    // ========================================

    /**
     * All CPU cores sorted from lowest to highest frequency.
     * Order is efficiency, performance, then prime. For dual-cluster devices,
     * PERFORMANCE is the lower-frequency cluster and PRIME is the higher one.
     */
    private fun allCpuCoresSorted(pserver: PServerDriver): List<Int> {
        return pserver.getCpuCoresByCluster(PServerDriver.CpuCluster.EFFICIENCY) +
            pserver.getCpuCoresByCluster(PServerDriver.CpuCluster.PERFORMANCE) +
            pserver.getCpuCoresByCluster(PServerDriver.CpuCluster.PRIME)
    }

    /**
     * The N lowest-frequency cores, reserved for non-game processes
     * (PulseAudio, Wine infrastructure, etc.).
     */
    private fun lowestCores(pserver: PServerDriver, count: Int = 2): List<Int> =
        allCpuCoresSorted(pserver).take(count)

    /**
     * Cores the game should use: all cores except the 2 lowest-frequency ones
     * reserved for audio/background.
     */
    private fun gameCores(pserver: PServerDriver): List<Int> {
        val all = allCpuCoresSorted(pserver)
        return if (all.size <= 2) all else all.drop(2)
    }

    /**
     * Pin PulseAudio daemon to the 2 lowest-frequency cores.
     * The game is pinned to all remaining cores, so PulseAudio never shares
     * a CPU with the game.
     */
    private fun pinPulseAudioToDedicatedCores() {
        val driver = getDriver()
        if (driver !is PServerDriver) return

        Thread {
            try {
                // Give PulseAudio time to start if it wasn't already running
                Thread.sleep(500)

                val audioPid = driver.getProcessId("libpulseaudio.so")
                if (audioPid != null) {
                    val audioCores = lowestCores(driver, 2)

                    if (audioCores.isNotEmpty()) {
                        val success = driver.setCpuAffinityByCores(audioPid, audioCores)
                        if (success) {
                            Timber.tag("PowerManager").i("Pinned PulseAudio (PID: $audioPid) to CPU $audioCores")
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
     * Pin Wine/background processes to the 2 lowest-frequency cores.
     * The game is pinned to all remaining cores, so these processes never
     * share a CPU with the game.
     */
    fun pinBackgroundProcesses() {
        val driver = getDriver()
        if (driver !is PServerDriver) return

        Thread {
            try {
                // Wait for Wine to fully initialize
                Thread.sleep(2000)

                val backgroundCores = lowestCores(driver, 2)

                if (backgroundCores.isEmpty()) {
                    Timber.tag("PowerManager").w("No cores available for background pinning")
                    return@Thread
                }

                // Pin wineserver to the background cores (critical for Wine IPC)
                driver.findRunningProcesses("wineserver")
                    .firstOrNull { it.second.endsWith("wineserver") }?.let {
                        val pid = it.first
                        val success = driver.setCpuAffinityByCores(pid, backgroundCores)
                        if (success) {
                            Timber.tag("PowerManager").i("Pinned wineserver (PID: $pid) to CPUs ${backgroundCores.joinToString()}")
                        }
                    }

                // Pin winhandler to the background cores
                driver.findRunningProcesses("winhandler.exe")
                    .firstOrNull { it.second.endsWith("winhandler.exe") }?.let {
                        val pid = it.first
                        val success = driver.setCpuAffinityByCores(pid, backgroundCores)
                        if (success) {
                            Timber.tag("PowerManager").i("Pinned winhandler.exe (PID: $pid) to CPUs ${backgroundCores.joinToString()}")
                        }
                    }

                // Pin services.exe to the background cores
                driver.findRunningProcesses("services.exe")
                    .firstOrNull { it.second.endsWith("services.exe") }?.let {
                        val pid = it.first
                        val success = driver.setCpuAffinityByCores(pid, backgroundCores)
                        if (success) {
                            Timber.tag("PowerManager").i("Pinned services.exe (PID: $pid) to CPUs ${backgroundCores.joinToString()}")
                        }
                    }

                // Pin libsteambootstrap.so to the background cores
                driver.findRunningProcesses("libsteambootstrap.so")
                    .firstOrNull { it.second.contains("libsteambootstrap.so") }?.let {
                        val pid = it.first
                        val success = driver.setCpuAffinityByCores(pid, backgroundCores)
                        if (success) {
                            Timber.tag("PowerManager").i("Pinned libsteambootstrap.so (PID: $pid) to CPUs ${backgroundCores.joinToString()}")
                        }
                    }
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin Wine infrastructure")
            }
        }.start()
    }

    /**
     * Records the game process of this session and pins it onto the fast cores when the profile
     * asks for it. The name is recorded whatever the gates say, so a later toggle of
     * [PowerProfile.enableGamePinning] knows which process to move.
     *
     * @param processName Process name or package name
     * @param maxRetries Maximum number of retry attempts
     * @param retryDelayMs Delay between retries in milliseconds
     */
    fun pinGameWithRetry(
        processName: String,
        maxRetries: Int = GAME_PIN_MAX_RETRIES,
        retryDelayMs: Long = GAME_PIN_RETRY_DELAY_MS
    ) {
        pinnedGameProcessName = processName
        startGamePin(processName, "game start", maxRetries, retryDelayMs)
    }

    /**
     * Hands the game process to the winhandler by name, which resolves it against the Windows
     * process list and applies the mask to every thread. The request is repeated until the kernel
     * reports the mask on the game, because the winhandler drops a request for a process that has
     * not started yet.
     */
    private fun startGamePin(
        processName: String,
        reason: String,
        maxRetries: Int = GAME_PIN_MAX_RETRIES,
        retryDelayMs: Long = GAME_PIN_RETRY_DELAY_MS
    ) {
        val pserver = driver as? PServerDriver
        if (pserver == null) {
            Timber.tag("PowerManager").i(
                "Game pinning inactive (driver=${driver?.let { it::class.simpleName }}), not pinning $processName"
            )
            return
        }

        if (currentProfile?.enableGamePinning != true) {
            Timber.tag("PowerManager").i("Game pinning switched off in the profile, not pinning $processName")
            return
        }

        if (!ownsGameAffinity) {
            Timber.tag("PowerManager").i(
                "Container CPU list owns the game affinity, not pinning $processName"
            )
            return
        }

        val generation = ++gamePinGeneration
        Thread {
            try {
                val gameCores = gamePinCores(pserver)
                if (gameCores.isEmpty()) {
                    Timber.tag("PowerManager").w("No cores to pin $processName on, leaving its affinity alone")
                    return@Thread
                }

                var pid: Int? = null
                var attempt = 1
                while (attempt <= maxRetries) {
                    if (generation != gamePinGeneration) {
                        Timber.tag("PowerManager").i("Pin run of $processName called off ($reason)")
                        return@Thread
                    }

                    val applied = applyAffinity(processName, pid, gameCores)
                    pid = findGamePid(pserver, processName)

                    if (pid == null) {
                        Timber.tag("PowerManager").d(
                            "$processName has not started yet, pin attempt $attempt of $maxRetries ($reason)"
                        )
                    } else if (applied && verifyGameAffinity(pid, gameCores, "PowerManager")) {
                        pinnedGameProcessName = processName
                        pinnedGamePid = pid
                        pinnedGameCores = gameCores
                        Timber.tag("PowerManager").i(
                            "Pinned $processName (PID: $pid) to CPUs ${gameCores.joinToString()} after $attempt attempts ($reason)"
                        )
                        return@Thread
                    }

                    if (attempt < maxRetries) Thread.sleep(retryDelayMs)
                    attempt++
                }
                Timber.tag("PowerManager").w(
                    "Pin of $processName onto CPUs ${gameCores.joinToString()} did not take after $maxRetries attempts ($reason)"
                )
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin game: $processName")
            }
        }.start()
    }

    /**
     * Hands the recorded game process back every core and forgets the pinned mask.
     */
    private fun unpinGame() {
        val processName = pinnedGameProcessName
        if (processName == null) {
            Timber.tag("PowerManager").i("No game process recorded, nothing to unpin")
            return
        }

        val allCores = parseCpuList(Container.getFallbackCPUList()).sorted()
        if (allCores.isEmpty()) {
            Timber.tag("PowerManager").w("No all-cores mask available, affinity of $processName left alone")
            return
        }

        val pid = pinnedGamePid
        gamePinGeneration++
        Thread {
            try {
                val success = applyAffinity(processName, pid, allCores)
                pinnedGameCores = emptyList()
                Timber.tag("PowerManager").i(
                    "Game pinning switched off, gave $processName CPUs ${allCores.joinToString()} back (success=$success)"
                )
                if (success && pid != null) verifyGameAffinity(pid, allCores, "PowerManager")
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to unpin game: $processName")
            }
        }.start()
    }

    /**
     * Cores a pinned game runs on: all cores except the 2 lowest-frequency ones,
     * which are reserved for PulseAudio and other background/Wine processes.
     * This applies uniformly to single, dual, and tri-cluster devices.
     */
    private fun gamePinCores(pserver: PServerDriver): List<Int> {
        return gameCores(pserver)
    }

    /**
     * Linux PID of the game process, or null while it is not running. Only read to verify a pin
     * against /proc, the winhandler resolves the process by name on its own side.
     */
    private fun findGamePid(pserver: PServerDriver, processName: String): Int? {
        if (!processName.endsWith(".exe", ignoreCase = true)) {
            return pserver.getProcessId(processName)
        }
        return pserver.findRunningProcesses(processName).find {
            !it.second.contains("winhandler.exe") &&
                (
                    it.second.endsWith(processName, ignoreCase = true) ||
                        it.second.startsWith("A:\\$processName", ignoreCase = true)
                    )
        }?.first
    }

    /**
     * Moves a game process onto [cores] through the winhandler, which hands the mask to
     * SetProcessAffinityMask and so reaches every thread of the process. `taskset` only moves the
     * thread it is given and is kept as the fallback for a session without a live winhandler.
     *
     * The request carries the process name, not [pid]: the winhandler resolves the name against
     * the Windows process list, while [pid] is the Linux pid this side found and means nothing to
     * OpenProcess on the other side.
     * @return true when the request left this side
     */
    private fun applyAffinity(processName: String, pid: Int?, cores: List<Int>): Boolean {
        val mask = ProcessHelper.getAffinityMask(cores.joinToString(","))
        if (mask == 0) return false

        val winHandler = WinHandler.getActiveInstance()
            ?.takeIf { processName.endsWith(".exe", ignoreCase = true) }
        if (winHandler != null) {
            winHandler.setProcessAffinity(processName, mask)
            Timber.tag("PowerManager").i(
                "Applied affinity ${cores.joinToString()} (mask 0x${Integer.toHexString(mask)}) to $processName over the winhandler"
            )
            return true
        }

        val pserver = driver as? PServerDriver
        if (pserver == null || pid == null) {
            Timber.tag("PowerManager").w("No winhandler and no PID for $processName, its affinity is left alone")
            return false
        }

        val success = pserver.setCpuAffinityByCores(pid, cores)
        Timber.tag("PowerManager").w(
            "No winhandler available, applied affinity ${cores.joinToString()} to PID $pid over taskset (success=$success)"
        )
        return success
    }

    /**
     * Waits for the winhandler round trip and compares what the kernel reports for [pid] with the
     * core list this app applied.
     * @return true when the mask took effect
     */
    internal fun verifyGameAffinity(pid: Int, cores: List<Int>, tag: String): Boolean {
        try {
            Thread.sleep(AFFINITY_SETTLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }

        val kernelCores = kernelCpuList(pid)
        if (kernelCores == null) {
            Timber.tag(tag).w("Could not read the affinity of PID $pid, treating the pin as unverified")
            return false
        }

        if (kernelCores == cores.toSet()) {
            Timber.tag(tag).i("Affinity of PID $pid settled on ${formatCores(kernelCores)}")
            return true
        }

        Timber.tag(tag).w(
            "Affinity of PID $pid is ${formatCores(kernelCores)}, applied ${cores.joinToString()}"
        )
        return false
    }

    /**
     * Cores the kernel currently allows for [pid], or null when the read failed.
     */
    internal fun kernelCpuList(pid: Int): Set<Int>? {
        val pserver = driver as? PServerDriver ?: return null
        val value = pserver.executeRootCommand("cat /proc/$pid/status | grep Cpus_allowed_list")
            .getOrNull()
            ?.substringAfter(':', "")
            ?.trim()
        if (value.isNullOrEmpty()) return null
        return parseCpuList(value).takeIf { it.isNotEmpty() }
    }

    /**
     * Expands a core list such as `4-6,7` or `0,1,2` into the core numbers it names.
     */
    internal fun parseCpuList(value: String): Set<Int> {
        val cores = mutableSetOf<Int>()
        value.split(',').forEach { part ->
            val range = part.trim().split('-')
            when (range.size) {
                1 -> range[0].toIntOrNull()?.let { cores.add(it) }
                2 -> {
                    val from = range[0].toIntOrNull()
                    val to = range[1].toIntOrNull()
                    if (from != null && to != null && to >= from) cores.addAll(from..to)
                }
            }
        }
        return cores
    }

    internal fun formatCores(cores: Collection<Int>): String = cores.sorted().joinToString(", ")

    /**
     * Decides once per session whether power control may move the game between cores. A container
     * with an explicit CPU list keeps its own pin, anything else (no list, or the all-cores
     * default) leaves the game affinity to power control.
     */
    private fun resolveGameAffinityOwnership() {
        val cpuList = containerCpuList(containerDir)
        val allCores = parseCpuList(Container.getFallbackCPUList())
        val explicit = cpuList != null && parseCpuList(cpuList) != allCores
        ownsGameAffinity = !explicit

        if (explicit) {
            Timber.tag("PowerManager").i(
                "Container CPU list is $cpuList, power control does not manage the game affinity this session"
            )
        } else {
            Timber.tag("PowerManager").i(
                "Container CPU list is ${cpuList ?: "unset"}, power control manages the game affinity this session"
            )
        }
    }

    /**
     * The CPU list the container stores, or null when it has none.
     */
    private fun containerCpuList(dir: File?): String? {
        if (dir == null) return null
        return runCatching {
            val container = Container(dir.name.substringAfterLast('-'))
            container.rootDir = dir
            container.loadData(JSONObject(container.containerJson))
            container.getCPUList(false)
        }.onFailure {
            Timber.tag("PowerManager").w(it, "Failed to read the CPU list of ${dir.absolutePath}")
        }.getOrNull()
    }

    /**
     * Get the profile file path for the current container
     */
    private fun getProfileFile(): File? {
        return containerDir?.let { File(it, ".config/.power-profile") }
    }

    private fun applyDefaultProfile(logMessage: String?) {
        currentProfile = getDriverDefaultProfile()
        logMessage?.let { Timber.tag("PowerManager").d(it) }
        if (currentProfile?.enableAutoTuning == true) {
            startAutoTuning()
        }
    }

    /**
     * Restore the saved power profile from container-specific file
     */
    private fun restoreSavedProfile() {
        try {
            val profileFile = getProfileFile()
            if (profileFile == null) {
                applyDefaultProfile("No container directory set, using default profile")
                return
            }

            if (!profileFile.exists()) {
                applyDefaultProfile("No saved profile found at ${profileFile.absolutePath}, using default profile")
                return
            }

            val jsonString = profileFile.readText()
            if (jsonString.isEmpty()) {
                applyDefaultProfile("Empty profile file, using default profile")
                return
            }

            currentProfile = json.decodeFromString<PowerProfile>(jsonString)
            Timber.tag("PowerManager").d("Restoring power profile from ${profileFile.absolutePath}: $jsonString")

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
            applyDefaultProfile(null)
        }
    }
}
