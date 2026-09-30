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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import timber.log.Timber
import java.io.File

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

/**
 * Manager for CPU and GPU performance control.
 * Provides a unified interface for CPU frequency, governor, and GPU power management.
 * Uses a PerformanceDriver implementation for device-specific operations.
 */
object PowerManager {
    private const val AFFINITY_SETTLE_MS = 1500L

    /** Game watchdog: check interval, the slower one while the game doesn't show up, and the back-off cap on refused threads. */
    private const val PIN_WATCHDOG_INTERVAL_MS = 2000L
    private const val PIN_WATCHDOG_IDLE_INTERVAL_MS = 10_000L
    private const val PIN_WATCHDOG_IDLE_AFTER_MISSES = 30
    private const val PIN_WATCHDOG_MAX_BACKOFF_MS = 30_000L

    /** Background watchdog: check interval, and failed re-pins after which it leaves a thread alone. */
    private const val BACKGROUND_WATCHDOG_INTERVAL_MS = 5000L
    private const val BACKGROUND_REPIN_ATTEMPTS = 3

    /** How long the launch-time pins wait for Wine and PulseAudio to spawn; live changes don't wait. */
    private const val BACKGROUND_PIN_LAUNCH_DELAY_MS = 2000L
    private const val AUDIO_PIN_LAUNCH_DELAY_MS = 500L

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private lateinit var appContext: Context
    private var containerDir: File? = null
    private lateinit var driver: PerformanceDriver
    private var autoTuner: PerformanceAutoTuner? = null
    private var clusterTuner: ClusterTuner? = null

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
    lateinit var currentProfile: PowerProfile

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
     * True while a new game window must not get the container's CPU list re-applied over Power
     * Control's own pin: Auto pins only when it owns the affinity, Manual always overrides the list.
     * Requires a pin that was actually applied or a watchdog still bringing it about, so a skipped or
     * abandoned pin keeps the container's list.
     */
    val holdsGameAffinity: Boolean
        get() {
            val profile = currentProfile
            if (!profile.enablePowerControl || (!gamePinActive && pinnedGameCores.isEmpty())) return false
            return when (profile.gamePinningMode) {
                GamePinningMode.AUTO -> ownsGameAffinity
                GamePinningMode.MANUAL -> parseCpuList(profile.manualGamePinCores).isNotEmpty()
                GamePinningMode.OFF -> false
            }
        }

    /**
     * Bumped whenever a pin run starts or is called off, so a retry loop left over from an earlier
     * run stops instead of pinning the game again.
     */
    @Volatile
    private var gamePinGeneration: Int = 0

    /** Same as [gamePinGeneration], for the Wine background group and for PulseAudio respectively. */
    @Volatile
    private var backgroundPinGeneration: Int = 0

    @Volatile
    private var audioPinGeneration: Int = 0

    /** True while a game pin watchdog runs, so the container CPU list isn't re-applied over a pin still on its way. */
    @Volatile
    private var gamePinActive: Boolean = false

    /** Calls off every pending pin run (game, background, audio) without touching any affinity. */
    private fun cancelPendingPins() {
        synchronized(affinityLock) {
            gamePinGeneration++
            gamePinActive = false
            backgroundPinGeneration++
            audioPinGeneration++
        }
    }

    /**
     * Held while an affinity change is checked against its generation and applied, so a pin or an
     * unpin that got superseded meanwhile can never land after the newer one.
     */
    private val affinityLock = Any()

    /**
     * Autostart with contain dir and application context
     */
    fun autoStart(rootDir: File) {
        containerDir = rootDir
        resolveGameAffinityOwnership()
        loadCurrentProfile()
        if (isProfilePowerControlEnabled()) {
            startPowerControl()
        }

        if (currentProfile.adaptiveFpsCapEnabled) {
            AdaptiveFpsCapController.start(containerDir, tunerLogDirectory())
        }

        appContext.let { PerformanceMetricsCollector.start(it) }

        isGameStarted = true
    }

    /**
     * Initialize PowerManager with application context.
     * Should be called once during application startup.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        driver = when {
            SamsungPerformanceDriver.isSamsungDevice() -> {
                val samsungDriver = SamsungPerformanceDriver(appContext)
                if (samsungDriver.isDriverSupported()) {
                    Timber.tag("PowerManager").i("Using Samsung Performance Driver")
                    samsungDriver
                } else {
                    Timber.tag("PowerManager").w("Samsung device detected but Performance SDK not available")
                    NoOpPerformanceDriver()
                }
            }
            PServerDriver.checkPServerAvailability() -> {
                Timber.tag("PowerManager").i("Using PServer Driver")
                PServerDriver(appContext)
            }
            else -> {
                Timber.tag("PowerManager").w("No performance driver available")
                NoOpPerformanceDriver()
            }
        }
        currentProfile = driver.getDefaultProfile()
    }

    /**
     * Get Driver Default Profile
     */
    fun getDriverDefaultProfile(): PowerProfile = driver.getDefaultProfile()

    /**
     * True when the active profile has in-game power control enabled.
     */
    fun isProfilePowerControlEnabled(): Boolean = currentProfile.enablePowerControl

    /**
     * Enable or disable in-game power control at runtime. Disabling stops the
     * driver and hands clock control back to the OS; enabling re-initializes
     * and restarts it when a game is running. CPU pinning is released/re-applied along with it.
     */
    @Synchronized
    fun setPowerControlEnabled(enabled: Boolean) {
        // Always save the profile
        currentProfile = currentProfile.copy(enablePowerControl = enabled)

        if (enabled) {
            startPowerControl()
            if (isGameStarted) {
                val processName = pinnedGameProcessName
                if (processName != null) {
                    Timber.tag("PowerManager").i("Power control re-enabled, re-pinning $processName")
                    startGamePin(processName, "power control re-enabled")
                }
                pinBackgroundProcesses(initialDelayMs = 0L)
            }
        } else {
            cancelPendingPins()
            if (isGameStarted) {
                Timber.tag("PowerManager").i("Power control disabled, releasing CPU pinning")
                // Blocking, so the release finishes before stopPowerControl() shuts the root executor down.
                unpinGame(blocking = true)
                unpinBackgroundProcesses(blocking = true)
            }
            stopPowerControl()
        }
    }


    // ========================================
    // General Settings
    // ========================================

    /**
     * Start the performance driver and restore saved profile if available
     */
    @Synchronized
    private fun startPowerControl() {
        driver.start()
        applyCurrentProfile()

        // Pin PulseAudio to dedicated performance cores if PServer is available
        pinPulseAudioToDedicatedCores()

        // Reset the driver on initialize. For PServer this also restores the recorded
        // baseline of a session that died without restoring it.
        (driver as? PServerDriver)?.let {
            if (it.hasPendingBaseline()) {
                Timber.tag("PowerControl").i("Power baseline from a previous session found on disk, restoring it")
            }
        }
    }

    @Synchronized
    fun stopPowerControl() {
        stopAutoTuning()
        FanController.stop()
        if (::driver.isInitialized) {
            driver.stop()
        } else {
            Timber.tag("PowerManager").w("Driver not initialized, skipping stop")
        }
    }

    /**
     * Stop the performance driver and save current profile
     */
    @Synchronized
    fun stop() {
        // Save the current profile if available, otherwise read from driver
        saveProfile()
        AdaptiveFpsCapController.stop()
        PerformanceMetricsCollector.stop()
        stopPowerControl()
        isGameStarted = false
        fpsCapApplier = null
        frameSampleStride = 1
        containerDir = null
        // Under the lock, so a pin run past its generation check can't record into the next session.
        synchronized(affinityLock) {
            cancelPendingPins()
            pinnedGameProcessName = null
            pinnedGamePid = null
            pinnedGameCores = emptyList()
        }
        ownsGameAffinity = false
    }

    /**
     * Pause the performance driver and auto-tuning when app goes to background
     */
    @Synchronized
    fun pause() {
        if (!isGameStarted) return
        saveProfile()
        AdaptiveFpsCapController.pause()
        PerformanceMetricsCollector.pause()
        synchronized(affinityLock) {
            gamePinGeneration++
            gamePinActive = false
            backgroundPinGeneration++
        }
        stopPowerControl()
    }

    /**
     * Resume the performance driver and auto-tuning when app comes to foreground
     */
    fun resume() {
        if (!isGameStarted) return
        if (isProfilePowerControlEnabled()) {
            driver.start()
            applyCurrentProfile()
            pinnedGameProcessName?.let { startGamePin(it, "resume") }
            pinBackgroundProcesses(initialDelayMs = 0L)
        }
        if (currentProfile.adaptiveFpsCapEnabled) {
            AdaptiveFpsCapController.start(containerDir, tunerLogDirectory())
        }
        PerformanceMetricsCollector.resume()
    }

    /**
     * Start automatic performance tuning.
     * Uses PID controller to adjust CPU/GPU/Bus frequencies based on targetFps and utilization.
     * Works with any driver that supports CPU frequency and GPU power level control.
     */
    fun startAutoTuning() {
        val driver = driver

        if (autoTuner?.isRunning() == true || clusterTuner?.isRunning() == true) {
            Timber.tag("PowerManager").w("Auto-tuning already running")
            return
        }

        val perClusterTuning = currentProfile.enablePerClusterTuning
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
            onCpuFrequencyChange = { minFreq, maxFreq ->
                if (currentProfile.minCpuFreq != minFreq || currentProfile.maxCpuFreq != maxFreq) {
                    update {
                        setMinCpuValue(minFreq)
                        setMaxCpuValue(maxFreq)
                    }
                }
            },
            onGpuLevelChange = { minLevel, maxLevel ->
                if (currentProfile.minGpuPowerLevel != minLevel || currentProfile.maxGpuPowerLevel != maxLevel) {
                    update {
                        setMinGpuPowerLevel(minLevel)
                        setMaxGpuPowerLevel(maxLevel)
                    }
                }
            },
            onBusLevelChange = { minLevel, maxLevel ->
                if (currentProfile.minBusLevel != minLevel || currentProfile.maxBusLevel != maxLevel) {
                    update {
                        setMinBusLevel(minLevel)
                        setMaxBusLevel(maxLevel)
                    }
                }
            },
            getTuningStrategy = { currentProfile.tuningStrategy },
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
            strategyProvider = { currentProfile.tuningStrategy },
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

    private fun tunerLogDirectory(): File {
        return appContext.let { context ->
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
     * Applies [transform] to the current profile under the same lock as [setPowerProfile], so two
     * quick UI edits can't both start from the same stale profile and drop one another.
     */
    @Synchronized
    fun updateProfile(transform: (PowerProfile) -> PowerProfile) {
        setPowerProfile(transform(currentProfile))
    }

    /**
     * Update the current profile reference.
     * Should be called when the UI changes the active profile.
     */
    @Synchronized
    fun setPowerProfile(requested: PowerProfile) {
        val previousProfile = currentProfile
        val profile = withSeededManualCores(requested)
        currentProfile = profile

        // Handle auto-tuning based on profile setting
        if (profile.enablePowerControl) {
            val perClusterChanged = profile.autoTuningMode == AutoTuningMode.AUTO &&
                previousProfile.enablePerClusterTuning != profile.enablePerClusterTuning
            if (perClusterChanged) {
                Timber.tag("PowerManager").i(
                    "Per-cluster tuning changed to ${profile.enablePerClusterTuning}, restarting the tuner"
                )
            }
            if (profile.autoTuningMode != AutoTuningMode.AUTO || perClusterChanged) {
                stopAutoTuning()
            }
            // Stop the tuner before handing clocks to the OS, and take them back before it starts again.
            val switched = !isGameStarted || previousProfile.autoTuningMode == profile.autoTuningMode ||
                switchAutoTuningMode(previousProfile.autoTuningMode, profile.autoTuningMode)
            // A failed switch keeps the previous mode, so the profile never reports a state the clocks aren't in.
            if (!switched) currentProfile = currentProfile.copy(autoTuningMode = previousProfile.autoTuningMode)
            if (currentProfile.autoTuningMode == AutoTuningMode.AUTO) {
                startAutoTuning()
            }
        } else {
            stopAutoTuning()
        }

        if (profile.adaptiveFpsCapEnabled) {
            AdaptiveFpsCapController.start(containerDir, tunerLogDirectory())
        } else {
            AdaptiveFpsCapController.stop()
        }

        if (isGameStarted) {
            if (previousProfile.enableFanControl != profile.enableFanControl) {
                if (profile.enableFanControl) {
                    FanController.start(driver)
                } else {
                    FanController.stop()
                }
            }

            // Pins were released when power control got switched off.
            if (profile.enablePowerControl) {
                applyPinningChanges(previousProfile, profile)
            }
        }
    }

    /** Re-pins or releases the game and the background group live, when [profile] changes how they're pinned. */
    private fun applyPinningChanges(previousProfile: PowerProfile, profile: PowerProfile) {
        val mode = profile.gamePinningMode
        val modeChanged = previousProfile.gamePinningMode != mode
        val isManual = mode == GamePinningMode.MANUAL

        if (modeChanged || (isManual && previousProfile.manualGamePinCores != profile.manualGamePinCores)) {
            val processName = pinnedGameProcessName
            when {
                mode == GamePinningMode.OFF -> unpinGame()
                processName != null -> {
                    Timber.tag("PowerManager").i("Game pinning mode is now $mode, pinning $processName now")
                    startGamePin(processName, "live toggle")
                }
                else -> Timber.tag("PowerManager").i("Game pinning mode is now $mode, no game process recorded yet")
            }
        }

        // Background processes are otherwise pinned once at launch, so live changes need a re-pin too.
        if (modeChanged || (isManual && previousProfile.manualBackgroundPinCores != profile.manualBackgroundPinCores)) {
            if (mode == GamePinningMode.OFF) {
                unpinBackgroundProcesses()
            } else {
                Timber.tag("PowerManager").i("Background pinning mode is now $mode, re-pinning Wine infrastructure now")
                pinBackgroundProcesses(initialDelayMs = 0L)
                pinPulseAudioToDedicatedCores(initialDelayMs = 0L)
            }
        }
    }

    /**
     * Rebuild [uiState] from the current driver and profile state.
     * Performs blocking sysfs/driver reads, so call it from a background thread.
     */
    fun refreshUiState() {
        if (!isProfilePowerControlEnabled()) {
            _uiState.value = PowerControlUiState.Success(
                selectedProfile = currentProfile,
                availableProfiles = emptyList(),
                cpuInfo = null,
                gpuInfo = null,
                ramInfo = null,
            )
            return
        }

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
                profile.name == (currentProfile.name)
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
                // Preserve autoTuningMode and tuningStrategy from PowerManager's current profile
                enablePowerControl = currentProfile.enablePowerControl,
                autoTuningMode = currentProfile.autoTuningMode,
                enablePerClusterTuning = currentProfile.enablePerClusterTuning,
                adaptiveFpsCapEnabled = currentProfile.adaptiveFpsCapEnabled,
                enableFanControl = currentProfile.enableFanControl,
                gamePinningMode = currentProfile.gamePinningMode,
                manualGamePinCores = currentProfile.manualGamePinCores,
                manualBackgroundPinCores = currentProfile.manualBackgroundPinCores,
                tuningStrategy = currentProfile.tuningStrategy
            )

            _uiState.value = PowerControlUiState.Success(
                selectedProfile = selectedProfile,
                availableProfiles = profiles,
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
                ramInfo = ramDisplayInfo,
                cpuTopology = (driver as? PServerDriver)?.let { buildCpuTopologyDisplayInfo(it) },
            )
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to refresh power control UI state")
        }
    }

    /** The discovered cluster layout for the Manual pinning checkboxes, or null when discovery found none. */
    private fun buildCpuTopologyDisplayInfo(pserver: PServerDriver): CpuTopologyDisplayInfo? =
        pserver.getCpuClusters().takeIf { it.isNotEmpty() }?.let { CpuTopologyDisplayInfo(it) }

    /**
     * Caps currently applied by the cluster tuner, or null when it is not running.
     */
    fun latestTunerCaps(): ClusterTuner.Caps? = clusterTuner?.latestCaps()

    /**
     * True when driver support fan control and the FanController is usable
     */
    fun isFanControlAvailable(): Boolean {
        val driver = driver
        return driver.isFanSupported() && FanController.isAvailable(driver)
    }

    /**
     * True when this session can hold the game on the fast CPU cores.
     */
    fun isGamePinningAvailable(): Boolean = driver.isCpuPinningSupported()

    /**
     * True when auto-tuning caps each CPU cluster separately via [ClusterTuner].
     */
    fun isClusterTuningAvailable(): Boolean = driver.isPerClusterSupported()

    /**
     * Check if driver is supported
     */
    fun isDriverSupported(): Boolean = driver.isDriverSupported()

    fun isGovernorSupported(): Boolean = driver.isGovernorSupported()

    fun driverName(): String = driver::class.java.simpleName

    /**
     * Get display unit preference for frequency values
     */
    fun getDisplayUnit(): PerformanceDriver.DisplayUnit = driver.getDisplayUnit()

    /**
     * Begin a batch update session.
     * For PServerDriver, this starts collecting commands to execute in a single call.
     * For SamsungDriver, this is a no-op as CustomParams already handles batching.
     */
    fun beginUpdate() {
        driver.beginUpdate()
    }

    /**
     * Commit all pending updates from the batch session.
     * For PServerDriver, this executes all collected commands in a single root call.
     * For SamsungDriver, this is a no-op as each setter already calls start(params).
     */
    fun commit(): Boolean {
        return driver.commit()
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
                currentGovernor = driver.getCurrentGovernor(),
                currentMinValue = driver.getCurrentMinCpuValue(),
                currentMaxValue = driver.getCurrentMaxCpuValue()
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
        return driver.getAvailableGovernors()
    }

    /**
     * Get list of available CPU frequencies in KHz
     */
    fun getAvailableCpuFrequencies(): List<Long> {
        return driver.getAvailableCpuFrequencies()
    }

    fun setProfileName(name: String) {
        currentProfile.name = name
    }

    /**
     * Set CPU governor
     */
    fun setGovernor(governor: String): Boolean {
        val result = driver.setGovernor(governor)
        if (result) {
            val cpuGovernor = CpuGovernor.fromString(governor)
            if (cpuGovernor != null) {
                currentProfile.governor = cpuGovernor
            }
        }
        return result
    }

    /**
     * Set minimum CPU Value in KHz / Integer
     */
    fun setMinCpuValue(frequency: Long): Boolean {
        val result = driver.setMinCpuValue(frequency)
        if (result) {
            currentProfile.minCpuFreq = frequency
        }
        return result
    }

    /**
     * Set maximum CPU Value in KHz / Integer
     */
    fun setMaxCpuValue(frequency: Long): Boolean {
        val result = driver.setMaxCpuValue(frequency)
        if (result) {
            currentProfile.maxCpuFreq = frequency
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
        return driver.isGpuSupported()
    }

    /**
     * Get current GPU information (frequency, power levels)
     */
    fun getGpuInfo(): GpuInfo? {
        return try {
            if (!driver.isGpuSupported()) return null
            // Batched single-round-trip read when the driver supports it
            (driver as? PServerDriver)?.readGpuState()?.let { state ->
                return GpuInfo(
                    currentGpuValue = state.currentFreqKHz,
                    minGpuPowerLevel = state.minPowerLevel,
                    maxGpuPowerLevel = state.maxPowerLevel,
                    numGpuPowerLevels = state.numPowerLevels
                )
            }
            GpuInfo(
                currentGpuValue = driver.getCurrentGpuValue(),
                minGpuPowerLevel = driver.getCurrentMinGpuPowerLevel(),
                maxGpuPowerLevel = driver.getCurrentMaxGpuPowerLevel(),
                numGpuPowerLevels = driver.getNumGpuPowerLevels()
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
        return driver.getAvailableGpuFrequencies()
    }

    /**
     * Set minimum GPU power level (0 = fastest, higher = slower)
     */
    fun setMinGpuPowerLevel(level: Int): Boolean {
        val result = driver.setMinGpuPowerLevel(level)
        if (result) {
            currentProfile.minGpuPowerLevel = level
        }
        return result
    }

    /**
     * Set maximum GPU power level (0 = fastest, higher = slower)
     */
    fun setMaxGpuPowerLevel(level: Int): Boolean {
        val result = driver.setMaxGpuPowerLevel(level)
        if (result) {
            currentProfile.maxGpuPowerLevel = level
        }
        return result
    }

    // ========================================
    // RAM Bus Control
    // ========================================

    fun isBusSupported(): Boolean {
        return driver.isBusSupported()
    }

    fun getBusInfo(): BusInfo? {
        return try {
            if (!driver.isBusSupported()) return null

            BusInfo(
                minBusLevel = driver.getCurrentMinBusLevel(),
                maxBusLevel = driver.getCurrentMaxBusLevel(),
                numBusLevels = driver.getNumBusLevels()
            )
        } catch (e: Exception) {
            Timber.tag("PowerManager").e(e, "Failed to get RAM bus info")
            null
        }
    }

    fun setMinBusLevel(level: Int): Boolean {
        val result = driver.setMinBusLevel(level)

        if (result) {
            currentProfile.minBusLevel = level
        }

        return result
    }

    fun setMaxBusLevel(level: Int): Boolean {
        val result = driver.setMaxBusLevel(level)

        if (result) {
            currentProfile.maxBusLevel = level
        }

        return result
    }

    // ========================================
    // Profile Persistence
    // ========================================

    /**
     * Save a power profile to container-specific file using atomic write.
     * Uses AtomicFile to prevent corruption if process is killed during write.
     */
    fun saveProfile() {
        try {
            val jsonString = json.encodeToString(currentProfile)
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
    private fun allCpuCoresSorted(pserver: PServerDriver): List<Int> = pserver.getAllCpuCores()

    /**
     * Auto's cores for Wine, audio and the watchdogs: the whole efficiency cluster, so the game gets every faster
     * core. Without one, or when it holds more than half the cores (a 6+2 layout would leave the game 2), the 2
     * lowest-frequency cores instead.
     */
    private fun autoBackgroundCores(pserver: PServerDriver): List<Int> {
        val all = allCpuCoresSorted(pserver)
        val efficiency = pserver.getCpuClusters()[PServerDriver.CpuCluster.EFFICIENCY].orEmpty().sorted()
        return if (efficiency.isNotEmpty() && efficiency.size * 2 <= all.size) efficiency else all.take(2)
    }

    /** Auto's game cores: every core [autoBackgroundCores] leaves, or all of them on a device too small to split. */
    private fun gameCores(pserver: PServerDriver): List<Int> {
        val all = allCpuCoresSorted(pserver)
        val background = autoBackgroundCores(pserver).toSet()
        return all.filterNot { it in background }.ifEmpty { all }
    }

    /** Auto mode's current background/game core split, formatted for the Manual core-list profile fields. */
    private fun autoModeCoreSplit(): Pair<String, String>? {
        val pserver = driver as? PServerDriver ?: return null
        val background = autoBackgroundCores(pserver)
        val game = gameCores(pserver)
        if (background.isEmpty() && game.isEmpty()) return null
        return toCpuListString(background) to toCpuListString(game)
    }

    /** Fills a blank Manual core list from Auto's current split, so switching to Manual starts from what Auto did. */
    private fun withSeededManualCores(profile: PowerProfile): PowerProfile {
        if (profile.gamePinningMode != GamePinningMode.MANUAL) return profile
        val needsGame = profile.manualGamePinCores.isBlank()
        val needsBackground = profile.manualBackgroundPinCores.isBlank()
        if (!needsGame && !needsBackground) return profile

        val (background, game) = autoModeCoreSplit() ?: return profile
        Timber.tag("PowerManager").i(
            "Seeded empty Manual core list(s) from Auto's split (background=$background, game=$game)"
        )
        return profile.copy(
            manualBackgroundPinCores = if (needsBackground) background else profile.manualBackgroundPinCores,
            manualGamePinCores = if (needsGame) game else profile.manualGamePinCores,
        )
    }

    /**
     * Cores the game pins to, per [PowerProfile.gamePinningMode]; empty = don't pin. In Auto that is
     * every core but [autoBackgroundCores], which [backgroundPinCores] keeps for Wine and audio.
     */
    private fun gamePinCores(pserver: PServerDriver): List<Int> {
        return when (currentProfile.gamePinningMode) {
            GamePinningMode.AUTO -> gameCores(pserver)
            GamePinningMode.OFF -> emptyList()
            GamePinningMode.MANUAL -> manualCores(pserver, currentProfile.manualGamePinCores)
        }
    }

    /** Cores the Wine background group and PulseAudio pin to, per [PowerProfile.gamePinningMode]; empty = don't pin. */
    private fun backgroundPinCores(pserver: PServerDriver): List<Int> {
        return when (currentProfile.gamePinningMode) {
            GamePinningMode.AUTO -> autoBackgroundCores(pserver)
            GamePinningMode.OFF -> emptyList()
            GamePinningMode.MANUAL -> manualCores(pserver, currentProfile.manualBackgroundPinCores)
        }
    }

    /** A saved Manual core list cut down to the cores this device has, so a stale or edited entry never reaches a mask. */
    private fun manualCores(pserver: PServerDriver, cpuList: String): List<Int> {
        val known = allKnownCores(pserver).toSet()
        return parseCpuList(cpuList).filter { it in known }.sorted()
    }

    /**
     * Pins PulseAudio onto [backgroundPinCores], next to the Wine background group.
     * [initialDelayMs] gives the daemon time to start at launch; live changes pass 0.
     */
    private fun pinPulseAudioToDedicatedCores(initialDelayMs: Long = AUDIO_PIN_LAUNCH_DELAY_MS) {
        val driver = driver
        if (driver !is PServerDriver) return

        val generation = ++audioPinGeneration
        Thread {
            try {
                if (initialDelayMs > 0) Thread.sleep(initialDelayMs)

                synchronized(affinityLock) {
                    if (generation != audioPinGeneration) return@Thread
                    val audioCores = backgroundPinCores(driver)
                    if (audioCores.isEmpty()) {
                        Timber.tag("PowerManager").d(
                            "Background pinning mode is ${currentProfile.gamePinningMode}, skipping PulseAudio"
                        )
                        return@Thread
                    }
                    applyAudioAffinity(driver, audioCores, "Pinned")
                }
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin PulseAudio")
            }
        }.start()
    }

    /**
     * Pins the Wine background group onto [backgroundPinCores]: in Auto [autoBackgroundCores],
     * in Manual the user's own set, in Off nothing. [initialDelayMs] lets Wine spawn them at launch;
     * live changes pass 0. [watchBackgroundPin] then keeps the group there.
     */
    fun pinBackgroundProcesses(initialDelayMs: Long = BACKGROUND_PIN_LAUNCH_DELAY_MS) {
        if (!isProfilePowerControlEnabled()) return
        val driver = driver
        if (driver !is PServerDriver) return

        val generation = ++backgroundPinGeneration
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
                if (initialDelayMs > 0) Thread.sleep(initialDelayMs)
                watchBackgroundPin(driver, generation)
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin Wine infrastructure")
            }
        }, "BackgroundPinWatchdog").start()
    }

    /**
     * Pins the background group, then every [BACKGROUND_WATCHDOG_INTERVAL_MS] re-pins processes started since and
     * threads that left [backgroundPinCores], until [generation] is superseded. A thread the kernel keeps refusing
     * (e.g. its cpuset excludes the cores) is left alone after [BACKGROUND_REPIN_ATTEMPTS] tries.
     */
    private fun watchBackgroundPin(pserver: PServerDriver, generation: Int) {
        val refusals = HashMap<Int, Int>()
        var first = true
        var confinedTo: List<Int> = emptyList()
        while (generation == backgroundPinGeneration) {
            if (!first) {
                Thread.sleep(BACKGROUND_WATCHDOG_INTERVAL_MS)
                if (generation != backgroundPinGeneration) return
            }
            confinedTo = confineWatchdog(pserver, confinedTo)
            val cores = backgroundPinCores(pserver)
            if (cores.isEmpty()) {
                Timber.tag("PowerManager").i(
                    "Background pinning mode is ${currentProfile.gamePinningMode}, no cores to pin Wine infrastructure to"
                )
                return
            }
            val coreSet = cores.toSet()
            val strays = backgroundProcesses().filter { proc ->
                AffinityProbe.strayThreads(proc.pid, coreSet).orEmpty().any { (refusals[it.tid] ?: 0) < BACKGROUND_REPIN_ATTEMPTS }
            }
            if (strays.isNotEmpty()) {
                synchronized(affinityLock) {
                    if (generation != backgroundPinGeneration) return
                    applyBackgroundAffinity(pserver, strays, cores, if (first) "Pinned" else "Re-pinned")
                }
                refusals.keys.removeAll { !AffinityProbe.isAlive(it) }
                for (proc in strays) {
                    for (thread in AffinityProbe.strayThreads(proc.pid, coreSet).orEmpty()) {
                        val attempts = (refusals[thread.tid] ?: 0) + 1
                        refusals[thread.tid] = attempts
                        if (attempts == BACKGROUND_REPIN_ATTEMPTS) {
                            Timber.tag("PowerManager").w(
                                "Thread $thread of ${proc.exe} (PID: ${proc.pid}) stays off CPUs ${formatCores(cores)}, leaving it alone"
                            )
                        }
                    }
                }
            }
            first = false
        }
    }

    /** Running processes of the background group, see [AffinityProbe.isBackgroundProcess]. */
    private fun backgroundProcesses(): List<AffinityProbe.Proc> {
        val gameExe = pinnedGameProcessName
        val gamePid = pinnedGamePid
        return AffinityProbe.listOwnProcesses().filter { AffinityProbe.isBackgroundProcess(it, gameExe, gamePid) }
    }

    /**
     * Hands the Wine background group and PulseAudio every core back; mirrors [unpinGame] for them.
     * [blocking] runs it on the calling thread, for callers that stop the driver right after.
     */
    private fun unpinBackgroundProcesses(blocking: Boolean = false) {
        // Before any early return, so a pending pin run can't land after this release.
        val generation = ++backgroundPinGeneration
        val audioGeneration = ++audioPinGeneration
        val driver = driver
        if (driver !is PServerDriver) return

        // All cores, not the container CPU list: that list only ever reaches the game's windows, so this is their pre-pin state.
        val allCores = allKnownCores(driver)
        if (allCores.isEmpty()) {
            Timber.tag("PowerManager").w("No all-cores mask available, background process affinity left alone")
            return
        }

        runAffinityJob(blocking) {
            try {
                val procs = backgroundProcesses()
                synchronized(affinityLock) {
                    if (generation == backgroundPinGeneration) applyBackgroundAffinity(driver, procs, allCores, "Unpinned")
                    if (audioGeneration == audioPinGeneration) applyAudioAffinity(driver, allCores, "Unpinned")
                }
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to unpin Wine infrastructure")
            }
        }
    }

    /**
     * Applies [cores] to [procs]: to Wine's own mask over the winhandler, which new threads start on, and over
     * taskset to every thread, which also covers wineserver. [action] only words the log line.
     */
    private fun applyBackgroundAffinity(pserver: PServerDriver, procs: List<AffinityProbe.Proc>, cores: List<Int>, action: String) {
        val mask = ProcessHelper.getAffinityMask(cores.joinToString(","))
        val winHandler = WinHandler.getActiveInstance()?.takeIf { mask != 0 }
        for (proc in procs) {
            if (proc.exe.endsWith(".exe", ignoreCase = true)) winHandler?.setProcessAffinity(proc.exe, mask)
            if (pserver.setCpuAffinityByCores(proc.pid, cores, allThreads = true)) {
                Timber.tag("PowerManager").i("$action ${proc.exe} (PID: ${proc.pid}), CPUs ${cores.joinToString()}")
            }
        }
    }

    /** Applies [cores] to the PulseAudio daemon when it runs; [action] only words the log line. */
    private fun applyAudioAffinity(pserver: PServerDriver, cores: List<Int>, action: String) {
        val audioPid = pserver.getProcessId("libpulseaudio.so")
        if (audioPid == null) {
            Timber.tag("PowerManager").d("PulseAudio not found, skipping audio pinning")
            return
        }
        if (pserver.setCpuAffinityByCores(audioPid, cores, allThreads = true)) {
            Timber.tag("PowerManager").i("$action PulseAudio (PID: $audioPid), CPUs ${cores.joinToString()}")
        }
    }

    /** Runs [job] right here when [blocking], otherwise on a fresh background thread. */
    private fun runAffinityJob(blocking: Boolean, job: () -> Unit) {
        if (blocking) job() else Thread { job() }.start()
    }

    /**
     * Records the game process of this session and pins it onto the fast cores when the profile
     * asks for it. The name is recorded whatever the gates say, so a later toggle of
     * [PowerProfile.gamePinningMode] knows which process to move.
     *
     * @param processName Process name or package name
     */
    fun pinGameWithRetry(processName: String) {
        pinnedGameProcessName = processName
        if (!isProfilePowerControlEnabled()) return
        startGamePin(processName, "game start")
    }

    /** Lets core_ctl pause the game's cores again, see [PServerDriver.holdCoresActive]. */
    private fun releaseCoreHold(pserver: PServerDriver) {
        runCatching { pserver.holdCoresActive(emptyList()) }
            .onSuccess { result -> result?.let { Timber.tag("PowerManager").i(it) } }
            .onFailure { Timber.tag("PowerManager").w(it, "Failed to release the core_ctl hold") }
    }

    /**
     * Starts [watchGamePin], which keeps the game on [gamePinCores] until a newer run, an unpin, power control
     * going off or the session ending supersedes it.
     */
    private fun startGamePin(processName: String, reason: String) {
        val pserver = driver as? PServerDriver
        if (pserver == null) {
            Timber.tag("PowerManager").i(
                "Game pinning inactive (driver=${driver.let { it::class.simpleName }}), not pinning $processName"
            )
            return
        }

        when (currentProfile.gamePinningMode) {
            GamePinningMode.OFF -> {
                // A release that failed earlier is still recorded, and a running watchdog may have moved threads already.
                if (pinnedGameCores.isNotEmpty() || gamePinActive) unpinGame()
                Timber.tag("PowerManager").i("Game pinning mode is Off, not pinning $processName")
                return
            }
            GamePinningMode.AUTO -> {
                if (!ownsGameAffinity) {
                    if (pinnedGameCores.isNotEmpty() || gamePinActive) {
                        Timber.tag("PowerManager").i(
                            "Container CPU list owns the game affinity, releasing $processName's Manual pin before switching to Auto"
                        )
                        unpinGame()
                    } else {
                        Timber.tag("PowerManager").i(
                            "Container CPU list owns the game affinity, not pinning $processName"
                        )
                    }
                    return
                }
            }
            GamePinningMode.MANUAL -> {
                // Manual explicitly overrides the container CPU list; ownsGameAffinity only governs Auto.
            }
        }

        val generation = synchronized(affinityLock) {
            gamePinActive = true
            ++gamePinGeneration
        }
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
                watchGamePin(pserver, generation, processName, reason)
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to pin game: $processName")
            } finally {
                // Under the lock, so a run started meanwhile can't get its hold released; superseded runs leave both to it.
                synchronized(affinityLock) {
                    if (generation == gamePinGeneration) {
                        gamePinActive = false
                        releaseCoreHold(pserver)
                    }
                }
            }
        }, "GamePinWatchdog").start()
    }

    /**
     * Waits for the game, pins it and then re-checks its threads every [PIN_WATCHDOG_INTERVAL_MS], since games and
     * Wine reset affinities. A correction goes through the winhandler first, so Wine's own copy of the mask (which
     * new threads start on) follows, then over taskset to every thread the kernel still reports elsewhere.
     */
    private fun watchGamePin(pserver: PServerDriver, generation: Int, processName: String, reason: String) {
        var pid: Int? = null
        var recorded = false
        var misses = 0
        var corrections = 0
        var backoffMs = PIN_WATCHDOG_INTERVAL_MS
        var refused: List<String> = emptyList()
        var confinedTo: List<Int> = emptyList()
        var heldFor: List<Int>? = null
        while (generation == gamePinGeneration) {
            confinedTo = confineWatchdog(pserver, confinedTo)
            val cores = gamePinCores(pserver)
            if (cores.isEmpty()) {
                Timber.tag("PowerManager").w("No cores to pin $processName on, leaving its affinity alone")
                return
            }
            if (cores != heldFor) {
                // core_ctl moves a thread allowed only paused CPUs onto all of them, which would undo the pin.
                val hold = synchronized(affinityLock) {
                    if (generation != gamePinGeneration) return
                    pserver.holdCoresActive(cores)
                }
                hold?.let { Timber.tag("PowerManager").i(it) }
                heldFor = cores
            }
            val coreSet = cores.toSet()

            val known = pid
            val gamePid = known?.takeIf { AffinityProbe.isAlive(it) } ?: findGamePid(pserver, processName)
            if (gamePid == null) {
                // A game that never shows up under this name (started by a launcher) isn't polled hard forever.
                misses++
                Thread.sleep(if (misses > PIN_WATCHDOG_IDLE_AFTER_MISSES) PIN_WATCHDOG_IDLE_INTERVAL_MS else PIN_WATCHDOG_INTERVAL_MS)
                continue
            }
            if (gamePid != known) {
                if (known != null) Timber.tag("PowerManager").i("$processName restarted as PID $gamePid ($reason)")
                pid = gamePid
                recorded = false
                misses = 0
                backoffMs = PIN_WATCHDOG_INTERVAL_MS
                refused = emptyList()
            }

            val strays = AffinityProbe.strayThreads(gamePid, coreSet)
            if (strays.isNullOrEmpty()) {
                if (strays != null && !recorded) recorded = recordGamePin(generation, processName, gamePid, cores, reason) ?: return
                backoffMs = PIN_WATCHDOG_INTERVAL_MS
                refused = emptyList()
                Thread.sleep(PIN_WATCHDOG_INTERVAL_MS)
                continue
            }
            if (recorded) {
                // A game that keeps resetting its affinity would otherwise log every round.
                corrections++
                if (corrections <= 5 || corrections % 20 == 0) {
                    Timber.tag("PowerManager").i(
                        "${strays.size} thread(s) of $processName left CPUs ${formatCores(cores)} " +
                            "(${strays.take(6).joinToString()}), re-pinning, correction $corrections ($reason)"
                    )
                }
            }
            applyAffinityIfCurrent(generation, processName, gamePid, cores) ?: return
            Thread.sleep(AFFINITY_SETTLE_MS)
            if (AffinityProbe.strayThreads(gamePid, coreSet).isNullOrEmpty()) {
                Thread.sleep(PIN_WATCHDOG_INTERVAL_MS)
                continue
            }

            val moved = synchronized(affinityLock) {
                if (generation != gamePinGeneration) return
                pserver.setCpuAffinityByCores(gamePid, cores, allThreads = true)
            }
            val still = AffinityProbe.strayThreads(gamePid, coreSet).orEmpty()
            if (still.isEmpty()) {
                Thread.sleep(PIN_WATCHDOG_INTERVAL_MS)
                continue
            }
            // The kernel refuses these, e.g. cores outside their cpuset. The rest is pinned as far as it goes and counts
            // as the pin; back off, and only log a change.
            if (!recorded) recorded = recordGamePin(generation, processName, gamePid, cores, reason) ?: return
            backoffMs = (backoffMs * 2).coerceAtMost(PIN_WATCHDOG_MAX_BACKOFF_MS)
            val names = still.map { it.name }.distinct().sorted()
            if (names != refused) {
                Timber.tag("PowerManager").w(
                    "${still.size} thread(s) of $processName (${still.take(6).joinToString()}) stay off CPUs " +
                        "${formatCores(cores)} (taskset success=$moved), retrying up to every ${PIN_WATCHDOG_MAX_BACKOFF_MS}ms"
                )
                refused = names
            }
            Thread.sleep(backoffMs)
        }
    }

    /** Records the pin of [pid] onto [cores]; null when [generation] got superseded meanwhile. */
    private fun recordGamePin(generation: Int, processName: String, pid: Int, cores: List<Int>, reason: String): Boolean? {
        synchronized(affinityLock) {
            if (generation != gamePinGeneration) return null
            pinnedGameProcessName = processName
            pinnedGamePid = pid
            pinnedGameCores = cores
        }
        Timber.tag("PowerManager").i("Pinned $processName (PID: $pid) to CPUs ${cores.joinToString()} ($reason)")
        return true
    }

    /**
     * Moves the calling watchdog thread alone (no `-a`, which would take the app along) onto the background cores,
     * or the efficiency cluster without them, to keep it off the game's cores. Only when that list differs from
     * [current], the one it was last moved to; returns the new one.
     */
    private fun confineWatchdog(pserver: PServerDriver, current: List<Int>): List<Int> {
        val cores = backgroundPinCores(pserver)
            .ifEmpty { pserver.getCpuClusters()[PServerDriver.CpuCluster.EFFICIENCY].orEmpty() }
        if (cores.isEmpty() || cores == current) return current
        if (!pserver.setCpuAffinityByCores(android.os.Process.myTid(), cores)) {
            Timber.tag("PowerManager").w("Could not move ${Thread.currentThread().name} onto CPUs ${formatCores(cores)}")
        }
        return cores
    }

    /** Every core of the device: cluster discovery when it worked, else the container's fallback CPU list. */
    private fun allKnownCores(pserver: PServerDriver? = driver as? PServerDriver): List<Int> {
        pserver?.getAllCpuCores()?.takeIf { it.isNotEmpty() }?.let { return it }
        return parseCpuList(Container.getFallbackCPUList()).sorted()
    }

    /** Cores to release the game onto: the container's CPU list if it owns affinity, else all known cores. */
    private fun targetCoresToUnpin(): List<Int> {
        if (!ownsGameAffinity) {
            containerCpuList(containerDir)
                ?.let { parseCpuList(it).sorted() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return allKnownCores()
    }

    /**
     * Hands the recorded game process back its unpinned cores (see [targetCoresToUnpin]) and forgets
     * the pinned mask. [blocking] runs it on the calling thread, for callers that stop the driver right after.
     */
    private fun unpinGame(blocking: Boolean = false) {
        // Before any early return, so a pending pin run can't land after this release.
        val generation = synchronized(affinityLock) {
            gamePinActive = false
            ++gamePinGeneration
        }
        val processName = pinnedGameProcessName
        if (processName == null) {
            Timber.tag("PowerManager").i("No game process recorded, nothing to unpin")
            return
        }

        val coresToUnpin = targetCoresToUnpin()
        if (coresToUnpin.isEmpty()) {
            Timber.tag("PowerManager").w("No all-cores mask available, affinity of $processName left alone")
            return
        }

        runAffinityJob(blocking) {
            try {
                val pserver = driver as? PServerDriver
                pserver?.let { releaseCoreHold(it) }
                // A pin still on its way isn't recorded yet, but may have moved threads already.
                val pid = pinnedGamePid ?: pserver?.let { findGamePid(it, processName) }
                val success = synchronized(affinityLock) {
                    val applied = applyAffinityIfCurrent(generation, processName, pid, coresToUnpin) ?: return@runAffinityJob
                    // Over taskset too, for threads the winhandler doesn't reach.
                    if (pid != null) pserver?.setCpuAffinityByCores(pid, coresToUnpin, allThreads = true)
                    // Keep holding the affinity until the release actually left this side.
                    if (applied) pinnedGameCores = emptyList()
                    applied
                }
                Timber.tag("PowerManager").i(
                    "Game pinning switched off, gave $processName CPUs ${coresToUnpin.joinToString()} back (success=$success)"
                )
                // A blocking caller is about to stop the driver, so /proc can't be read back as root anymore.
                if (success && pid != null && !blocking) verifyGameAffinity(pid, coresToUnpin, "PowerManager")
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to unpin game: $processName")
            }
        }
    }

    /** [applyAffinity] under [affinityLock]; null, without applying anything, once [generation] got superseded. */
    private fun applyAffinityIfCurrent(generation: Int, processName: String, pid: Int?, cores: List<Int>): Boolean? =
        synchronized(affinityLock) {
            if (generation == gamePinGeneration) applyAffinity(processName, pid, cores) else null
        }

    /**
     * Linux PID of the game process, or null while it is not running. A Wine process is matched by
     * its executable, case-insensitively; the winhandler resolves it by name on its own side.
     */
    private fun findGamePid(pserver: PServerDriver, processName: String): Int? {
        if (!processName.endsWith(".exe", ignoreCase = true)) {
            return pserver.getProcessId(processName)
        }
        return AffinityProbe.listOwnProcesses().firstOrNull { it.exe.equals(processName, ignoreCase = true) }?.pid
    }

    /**
     * Moves a game process onto [cores] through the winhandler, which hands the mask to
     * SetProcessAffinityMask and so reaches every thread of the process. Root `taskset` over every
     * thread is kept as the fallback for a session without a live winhandler.
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
            Timber.tag("PowerManager").d(
                "Applied affinity ${cores.joinToString()} (mask 0x${Integer.toHexString(mask)}) to $processName over the winhandler"
            )
            return true
        }

        val pserver = driver as? PServerDriver
        if (pserver == null || pid == null) {
            Timber.tag("PowerManager").w("No winhandler and no PID for $processName, its affinity is left alone")
            return false
        }

        val success = pserver.setCpuAffinityByCores(pid, cores, allThreads = true)
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

    /** Formats cores the way the Manual core-list profile fields store them, e.g. `4,5,6`. */
    internal fun toCpuListString(cores: Collection<Int>): String = cores.sorted().joinToString(",")

    /**
     * [cores] with [core] added or removed. Removing the last remaining core is refused, so a Manual
     * core list never ends up empty.
     */
    internal fun toggleCoreInList(cores: String, core: Int, include: Boolean): String {
        val current = parseCpuList(cores)
        val updated = if (include) current + core else current - core
        return if (updated.isEmpty()) cores else toCpuListString(updated)
    }

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

    /**
     * Load Current Profile
     */
    private fun loadCurrentProfile() {
        val profileFile = getProfileFile()

        currentProfile = if (profileFile == null || !profileFile.exists()) {
            getDriverDefaultProfile()
        } else {
            val jsonString = profileFile.readText()
            Timber.tag("PowerManager").d("Restoring power profile from ${profileFile.absolutePath}: $jsonString")
            json.decodeFromJsonElement(PowerProfile.serializer(), migrateLegacyProfile(json.parseToJsonElement(jsonString).jsonObject))
        }
    }

    /**
     * Maps the boolean toggles of a profile saved before the Auto/Manual/Off modes onto the mode
     * fields, so an update keeps the old behavior instead of falling back to the Auto defaults.
     */
    private fun migrateLegacyProfile(saved: JsonObject): JsonObject {
        val migrated = saved.toMutableMap()
        if ("gamePinningMode" !in saved) {
            val pinning = saved["enableGamePinning"]?.jsonPrimitive?.booleanOrNull ?: false
            migrated["gamePinningMode"] = JsonPrimitive(if (pinning) GamePinningMode.AUTO.name else GamePinningMode.OFF.name)
        }
        if ("autoTuningMode" !in saved) {
            val tuning = saved["enableAutoTuning"]?.jsonPrimitive?.booleanOrNull ?: false
            migrated["autoTuningMode"] = JsonPrimitive(if (tuning) AutoTuningMode.AUTO.name else AutoTuningMode.MANUAL.name)
        }
        return JsonObject(migrated)
    }

    /**
     * Restore the saved power profile from container-specific file
     */
    private fun applyCurrentProfile() {
        if (currentProfile.enablePowerControl) {
            try {
                if (currentProfile.autoTuningMode != AutoTuningMode.OFF) {
                    val success = applyCpuGpuControl()

                    if (success) {
                        Timber.tag("PowerManager").i("Successfully restored power profile")
                    } else {
                        Timber.tag("PowerManager").w("Failed to restore power profile")
                    }
                } else {
                    val released = driver.releaseFrequencyControl()
                    Timber.tag("PowerManager").i(
                        "Auto-tuning mode is Off, released CPU/GPU frequency control back to the OS (success=$released)"
                    )
                }

                if (currentProfile.autoTuningMode == AutoTuningMode.AUTO) {
                    startAutoTuning()
                }

                if (currentProfile.enableFanControl) {
                    FanController.start(driver)
                }
            } catch (e: Exception) {
                Timber.tag("PowerManager").e(e, "Failed to apply current profile")
            }
        }
    }

    /** Applies the profile's CPU governor/min/max and GPU/Bus min/max power level to the driver. */
    private fun applyCpuGpuControl(): Boolean {
        return update {
            governor(currentProfile.governor.governorName)
            minCpuValue(currentProfile.minCpuFreq)
            maxCpuValue(currentProfile.maxCpuFreq)
            if (isGpuSupported()) {
                minGpuPowerLevel(currentProfile.minGpuPowerLevel)
                maxGpuPowerLevel(currentProfile.maxGpuPowerLevel)
            }
            if (isBusSupported()) {
                minBusLevel(currentProfile.minBusLevel)
                maxBusLevel(currentProfile.maxBusLevel)
            }
        }
    }

    /**
     * Releases/reclaims CPU-GPU frequency control from the OS on entering/leaving [AutoTuningMode.OFF] live.
     * @return false when that failed and [previous] still applies
     */
    private fun switchAutoTuningMode(previous: AutoTuningMode, next: AutoTuningMode): Boolean {
        if (next == AutoTuningMode.OFF) {
            val released = driver.releaseFrequencyControl()
            Timber.tag("PowerManager").i(
                "Auto-tuning mode is now Off, released CPU/GPU frequency control back to the OS (success=$released)"
            )
            return released
        } else if (previous == AutoTuningMode.OFF) {
            val reclaimed = applyCpuGpuControl()
            Timber.tag("PowerManager").i(
                "Auto-tuning mode is now $next, reclaimed CPU/GPU frequency control (success=$reclaimed)"
            )
            // The batch may have been applied in part; the mode stays Off, so hand everything back.
            if (!reclaimed) driver.releaseFrequencyControl()
            return reclaimed
        }
        return true
    }
}
