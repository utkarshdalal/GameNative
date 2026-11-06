package app.gamenative.powercontrol.metrics

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import app.gamenative.powercontrol.PowerBaselineScripts
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.autotuning.TunerThresholds
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Headless collector for game performance metrics.
 *
 * Runs for the whole game session independently of the on-screen HUD: frame pacing
 * comes from [FrameTimeRing] (fed by the render/present path), CPU/GPU/thermal
 * readings from [SystemMetricsSources]. Every cycle publishes a [MetricsSnapshot]
 * on [PowerManager] and appends a JSONL record for offline analysis.
 */
object PerformanceMetricsCollector {
    private const val TAG = "PowerMetrics"
    private const val SAMPLE_INTERVAL_MS = 500L
    private const val FRAME_WINDOW_MS = 2_000L
    private const val SLOW_FRAME_FACTOR = 1.5
    private const val LOG_EVERY_N_SAMPLES = 10
    private const val MAX_LOG_BYTES = 20L * 1024L * 1024L
    private const val MAX_SESSION_FILES = 5
    private const val DEFAULT_REFRESH_RATE = 60f
    private const val THERMAL_EVERY_N_SAMPLES = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var samplingJob: Job? = null

    private val cpuSampler = CpuUsageSampler()
    private val gpuSampler = GpuUsageSampler(publishSource = true)
    private val coreSampler = CoreLoadSampler()

    private val frameScratch = LongArray(FrameTimeRing.capacity())
    private val deltaScratch = LongArray(FrameTimeRing.capacity())
    private val repeatScratch = LongArray(FrameTimeRing.capacity())
    private var repeatWindowKey: List<Int>? = null
    private var repeatWindowStartNs = 0L

    private val sessionLog = JsonlSessionLog(TAG, "metrics-", MAX_LOG_BYTES, MAX_SESSION_FILES)
    private var sampleCount = 0L
    private var displayRefreshRate = DEFAULT_REFRESH_RATE
    private var appContext: Context? = null

    @Volatile
    private var paused = false

    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Latest reading of the session's single GPU usage sampler; null while the device
     * gives no valid value. Other consumers read this instead of sampling the node.
     */
    @Volatile
    var latestGpuUsage: GpuUsageReading? = null
        private set

    /** Load of the busiest core in the last cycle; null while /proc/stat gives no per-core value. */
    @Volatile
    var latestCoreMaxPercent: Int? = null
        private set

    /** Refresh rate of the default display, read at session start. */
    val refreshRateHz: Float
        get() = displayRefreshRate

    fun start(context: Context, sessionStartMillis: Long = System.currentTimeMillis()) {
        if (isRunning) return

        val appContext = context.applicationContext
        this.appContext = appContext
        ThermalHeadroom.reset()
        coreSampler.reset()
        latestCoreMaxPercent = null
        displayRefreshRate = readDisplayRefreshRate(appContext)
        PowerTelemetry.attachBattery(appContext)
        cpuSampler.reset()
        gpuSampler.reset()
        latestGpuUsage = null
        MaliGpuCounters.attach(appContext)
        sampleCount = 0L
        paused = false
        FrameTimeRing.start()
        PowerTelemetry.start()
        SessionProcessTelemetry.start(appContext)
        openLog(appContext, sessionStartMillis)

        val gpuPaths = SystemMetricsSources.gpuUsagePaths()
        Timber.tag(TAG).i(
            "Collector started: frameSource=frame-hook interval=%dms window=%dms refresh=%.1fHz gpu=[%s] cpuTemp=%s gpuTemp=%s log=%s",
            SAMPLE_INTERVAL_MS,
            FRAME_WINDOW_MS,
            displayRefreshRate,
            gpuPaths.joinToString(),
            SystemMetricsSources.cpuTempPaths().firstOrNull() ?: "none",
            SystemMetricsSources.gpuTempPaths().firstOrNull() ?: "none",
            sessionLog.path ?: "none",
        )

        isRunning = true
        samplingJob = scope.launch {
            while (isActive) {
                if (!paused) {
                    runCatching { sampleOnce() }
                        .onFailure { Timber.tag(TAG).e(it, "Sampling cycle failed") }
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        if (!isRunning) return

        isRunning = false
        paused = false
        samplingJob?.cancel()
        samplingJob = null
        FrameTimeRing.stop()
        SessionProcessTelemetry.stop()
        closeLog()
        latestGpuUsage = null
        PowerManager.latestMetrics = null
        Timber.tag(TAG).i("Collector stopped after %d samples", sampleCount)
        MaliGpuCounters.close()
    }

    fun pause() {
        if (!isRunning || paused) return
        paused = true
        Timber.tag(TAG).i("Collector paused")
    }

    fun resume() {
        if (!isRunning || !paused) return
        cpuSampler.reset()
        gpuSampler.reset()
        coreSampler.reset()
        paused = false
        Timber.tag(TAG).i("Collector resumed")
    }

    private fun sampleOnce() {
        val now = System.nanoTime()
        val frameCount = FrameTimeRing.copySince(now - FRAME_WINDOW_MS * 1_000_000L, frameScratch)
        val frameStats = computeFrameWindowStats(
            frameScratch,
            frameCount,
            slowFrameThresholdNs(),
            deltaScratch,
            PowerManager.frameSampleStride,
        )
        val repeatedFrames = countRepeatedFrames(now)

        val cpu = cpuSampler.sample()
        val gpu = gpuSampler.sample()
        latestGpuUsage = gpu
        val cpuTemp = SystemMetricsSources.readTemperature(SystemMetricsSources.cpuTempPaths())
        val gpuTemp = SystemMetricsSources.readTemperature(SystemMetricsSources.gpuTempPaths())
        PowerTelemetry.noteTemperatureSources(cpuTemp?.path, gpuTemp?.path)
        // Every 10 s: the headroom API rate-limits to one call a second, and status is
        // sampled on the same cadence.
        if (sampleCount % THERMAL_EVERY_N_SAMPLES == 0L) {
            appContext?.let { PowerTelemetry.recordThermal(ThermalHeadroom.read(it), ThermalHeadroom.status(it)) }
        }
        val coreMax = coreSampler.sample()
        latestCoreMaxPercent = coreMax
        PowerTelemetry.noteBottleneck(
            BottleneckClassifier.classify(
                BottleneckInput(
                    fps = frameStats.fps,
                    frameMs = if (frameStats.fps > 0f) 1000f / frameStats.fps else 0f,
                    fpsCap = PowerManager.targetFps,
                    vsyncHz = displayRefreshRate,
                    cpuTotalPct = cpu?.percent?.toFloat(),
                    coreMaxPct = coreMax?.toFloat(),
                    gpuPct = gpu?.percent?.toFloat(),
                    gpuFrameMs = GpuFrameTime.current(),
                    headroom = ThermalHeadroom.latest,
                    thermalStatus = ThermalHeadroom.latestStatus,
                ),
            ),
        )

        val snapshot = MetricsSnapshot(
            timestampMs = System.currentTimeMillis(),
            fps = frameStats.fps,
            frameTimeP50Ms = frameStats.p50Ms,
            frameTimeP95Ms = frameStats.p95Ms,
            frameTimeMaxMs = frameStats.maxMs,
            slowFrameCount = frameStats.slowFrameCount,
            totalFrameCount = frameStats.totalFrameCount,
            cpuUsagePercent = cpu?.percent?.toFloat(),
            cpuUsageSource = cpu?.source ?: CpuUsageSource.UNAVAILABLE,
            gpuUsagePercent = gpu?.percent?.toFloat(),
            cpuTempC = cpuTemp?.celsius,
            gpuTempC = gpuTemp?.celsius,
            thermalHeadroom = ThermalHeadroom.latest,
            repeatedFrameCount = repeatedFrames,
        )

        publish(snapshot)
        runCatching { PowerTelemetry.record(snapshot, cpu) }
        runCatching { SessionProcessTelemetry.tick(PowerTelemetry.currentBucket()) }
        appendLog(snapshot)

        sampleCount++
        if (sampleCount % LOG_EVERY_N_SAMPLES == 0L) {
            Timber.tag(TAG).i(
                "fps=%.1f p95=%.1fms slow=%d/%d repeated=%s in 10s cpu=%s%%(%s) gpu=%s%% cpuTemp=%s gpuTemp=%s",
                snapshot.fps,
                snapshot.frameTimeP95Ms,
                snapshot.slowFrameCount,
                snapshot.totalFrameCount,
                snapshot.repeatedFrameCount?.toString() ?: "-",
                snapshot.cpuUsagePercent?.toInt()?.toString() ?: "-",
                snapshot.cpuUsageSource.name,
                snapshot.gpuUsagePercent?.toInt()?.toString() ?: "-",
                snapshot.cpuTempC?.toString() ?: "-",
                snapshot.gpuTempC?.toString() ?: "-",
            )
        }
    }

    private fun publish(snapshot: MetricsSnapshot) {
        PowerManager.latestMetrics = snapshot
        PowerManager.currentFps = snapshot.fps
        PowerManager.currentCpuUsage = snapshot.cpuUsagePercent ?: 0f
        PowerManager.currentGpuUsage = snapshot.gpuUsagePercent ?: 0f
        PowerManager.gpuUsageKnown = snapshot.gpuUsagePercent != null
    }

    /**
     * Repeated frames over the tuner's repeat window, or null when they are not counted: only
     * gamescope sessions publish one ring entry per frame gamescope flipped on its vblank grid,
     * and with frame generation on the ring carries generated frames, so neither X11 sessions nor
     * generation count repeats. A change of cap or display rate re-scores the window, so it
     * restarts empty then.
     */
    private fun countRepeatedFrames(now: Long): Int? {
        if (!repeatCountingActive(PowerManager.gamescopeSession, PowerManager.frameSampleStride)) {
            repeatWindowKey = null
            return null
        }
        val refreshHz = PowerManager.panelRefreshHz.takeIf { it > 0 } ?: Math.round(displayRefreshRate)
        val capFps = PowerManager.targetFps
        val key = listOf(capFps, refreshHz)
        if (key != repeatWindowKey) {
            repeatWindowKey = key
            repeatWindowStartNs = now
        }
        val since = maxOf(now - TunerThresholds.REPEAT_WINDOW_MS * 1_000_000L, repeatWindowStartNs)
        val count = FrameTimeRing.copySince(since, repeatScratch)
        return countSlowIntervals(repeatScratch, count, repeatThresholdNs(capFps, refreshHz))
    }

    private fun slowFrameThresholdNs(): Long {
        val targetFps = PowerManager.targetFps
        val referenceFps = if (targetFps > 0) targetFps.toFloat() else displayRefreshRate
        if (referenceFps <= 0f) return 0L
        return (SLOW_FRAME_FACTOR * 1_000_000_000.0 / referenceFps).toLong()
    }

    private fun readDisplayRefreshRate(context: Context): Float {
        return try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val rate = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
                ?: DEFAULT_REFRESH_RATE
            if (rate.isFinite() && rate > 1f) rate else DEFAULT_REFRESH_RATE
        } catch (_: Exception) {
            DEFAULT_REFRESH_RATE
        }
    }

    private fun metricsDirectory(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val parent = external ?: context.filesDir
        return File(parent, PowerBaselineScripts.DIRECTORY_NAME)
    }

    private fun openLog(context: Context, sessionStartMillis: Long) {
        sessionLog.open(metricsDirectory(context), sessionStartMillis)
    }

    private fun appendLog(snapshot: MetricsSnapshot) {
        sessionLog.append(toJsonLine(snapshot))
    }

    private fun toJsonLine(snapshot: MetricsSnapshot): String {
        return String.format(
            Locale.US,
            "{\"timestampMs\":%d,\"fps\":%.2f,\"frameTimeP50Ms\":%.2f,\"frameTimeP95Ms\":%.2f," +
                "\"frameTimeMaxMs\":%.2f,\"slowFrameCount\":%d,\"totalFrameCount\":%d," +
                "\"cpuUsagePercent\":%s,\"cpuUsageSource\":\"%s\",\"gpuUsagePercent\":%s," +
                "\"cpuTempC\":%s,\"gpuTempC\":%s}",
            snapshot.timestampMs,
            snapshot.fps,
            snapshot.frameTimeP50Ms,
            snapshot.frameTimeP95Ms,
            snapshot.frameTimeMaxMs,
            snapshot.slowFrameCount,
            snapshot.totalFrameCount,
            snapshot.cpuUsagePercent?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            snapshot.cpuUsageSource.name,
            snapshot.gpuUsagePercent?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            snapshot.cpuTempC?.toString() ?: "null",
            snapshot.gpuTempC?.toString() ?: "null",
        )
    }

    private fun closeLog() {
        sessionLog.close()
    }
}

/**
 * GPU usage for consumers other than the collector (HUD, debug-report sampler).
 * While the collector runs its reading is reused so the usage node has a single
 * reader; otherwise this falls back to a private, non-publishing sampler.
 */
class SharedGpuUsage {
    private val fallback = GpuUsageSampler()
    private var usedCollector = true

    fun read(): GpuUsageReading? {
        if (PerformanceMetricsCollector.isRunning) {
            usedCollector = true
            return PerformanceMetricsCollector.latestGpuUsage
        }
        if (usedCollector) {
            fallback.reset()
            usedCollector = false
        }
        return fallback.sample()
    }
}
