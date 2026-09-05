package app.gamenative.powercontrol.autotuning

import app.gamenative.powercontrol.AutoTuningStrategy
import app.gamenative.powercontrol.fan.FanSample
import app.gamenative.powercontrol.metrics.JsonlSessionLog
import app.gamenative.powercontrol.metrics.MetricsSnapshot
import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Per-cluster, frame-pacing aware tuner.
 *
 * Each control domain owns an index into its own list of steps: max-frequency caps for
 * the PRIME and PERFORMANCE cpufreq policies and the GPU max power level. Efficiency
 * cores and every scaling_min_freq are left alone so the kernel governor keeps its full
 * range below the cap. Decisions come from [TunerDecisionEngine]; this class only maps
 * them onto sysfs writes, warm-start state and logs.
 */
class ClusterTuner(
    private val primeSteps: List<Long>,
    private val performanceSteps: List<Long>,
    private val gpuSteps: List<Int>,
    private val applyPrimeCapKhz: (Long) -> Boolean,
    private val applyPerformanceCapKhz: (Long) -> Boolean,
    private val applyGpuMaxLevel: (Int) -> Boolean,
    private val metricsProvider: () -> MetricsSnapshot?,
    private val targetFpsProvider: () -> Int,
    private val fanSampleProvider: () -> FanSample? = { null },
    private val strategyProvider: () -> AutoTuningStrategy = { AutoTuningStrategy.BALANCED },
    private val fpsCapProvider: () -> FpsCapSnapshot? = { null },
    private val stateFile: File?,
    private val logDirectory: File?,
    private val sessionStartMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        private const val TAG = "PowerTuner"
        private const val STATE_FILE_NAME = ".power-tuner-state.json"
        private const val HOLD_ACTION = "hold"
        private const val CAP_STATE_OFF = "off"

        fun stateFileFor(containerDir: File?): File? {
            return containerDir?.let { File(it, ".config/$STATE_FILE_NAME") }
        }

        fun tuningForStrategy(strategy: AutoTuningStrategy): StrategyTuning {
            return when (strategy) {
                AutoTuningStrategy.POWER_EFFICIENT -> StrategyTuning.POWER_EFFICIENT
                AutoTuningStrategy.BALANCED -> StrategyTuning.BALANCED
                AutoTuningStrategy.AGGRESSIVE -> StrategyTuning.AGGRESSIVE
                AutoTuningStrategy.CONSERVATIVE -> StrategyTuning.CONSERVATIVE
            }
        }
    }

    data class Caps(
        val primeKhz: Long?,
        val performanceKhz: Long?,
        val gpuLevel: Int?,
    )

    @Serializable
    data class WarmState(
        val primeIndex: Int = -1,
        val primeStepCount: Int = 0,
        val performanceIndex: Int = -1,
        val performanceStepCount: Int = 0,
        val gpuIndex: Int = -1,
        val gpuStepCount: Int = 0,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val engine = TunerDecisionEngine({ tuningForStrategy(strategyProvider()) })
    private val sessionLog = JsonlSessionLog(TAG, "tuner-")
    private val stepIndex = mutableMapOf<TunerDomain, Int>()

    private var pendingWarmState: WarmState? = null
    private var tuningThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var caps: Caps? = null

    @Volatile
    private var trimmedStepCount = 0

    @Volatile
    private var openClocksRequested = false

    @Volatile
    private var harvesting = false

    fun isRunning(): Boolean = running

    /**
     * Currently applied caps, or null while the tuner is not running.
     * Reads a single volatile reference, safe to call from any thread.
     */
    fun latestCaps(): Caps? = if (running) caps else null

    /**
     * Total number of steps the clock domains sit below their maximum, or null while the
     * tuner is not running. Reads a single volatile, safe to call from any thread.
     */
    fun trimmedSteps(): Int? = if (running) trimmedStepCount else null

    /**
     * True while the trimmed steps come from a harvest, so they are not holding the frame
     * rate back. Null while the tuner is not running.
     */
    fun isHarvesting(): Boolean? = if (running) harvesting else null

    /**
     * Asks for every domain to be reopened and every trim hold to be dropped. The request is
     * carried out at the top of the next tuning cycle, so all sysfs writes stay on one thread.
     */
    fun requestOpenClocks() {
        openClocksRequested = true
    }

    fun start() {
        if (running) {
            Timber.tag(TAG).w("Cluster tuner already running")
            return
        }

        TunerDomain.entries.forEach { domain -> stepIndex[domain] = lastIndexOf(domain) }
        pendingWarmState = readWarmState()
        logDirectory?.let { sessionLog.open(it, sessionStartMillis) }

        Timber.tag(TAG).i(
            "Cluster tuner started (prime=%d steps, perf=%d steps, gpu=%d levels, warmState=%s, log=%s)",
            primeSteps.size,
            performanceSteps.size,
            gpuSteps.size,
            pendingWarmState != null,
            sessionLog.path ?: "none",
        )

        applyUncapped()

        running = true
        tuningThread = Thread {
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    runCatching { runCycle() }
                        .onFailure { Timber.tag(TAG).e(it, "Tuning cycle failed") }
                    Thread.sleep(TunerThresholds.CYCLE_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                Timber.tag(TAG).d("Cluster tuner thread interrupted")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Cluster tuner error")
            }
        }.apply {
            name = "ClusterTuner"
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    /**
     * Stops the control thread and flushes the decision log. Nothing is restored here:
     * every cap written by this tuner is covered by the driver's session baseline.
     */
    fun stop() {
        if (!running) return

        running = false
        tuningThread?.interrupt()
        tuningThread?.join(1000)
        tuningThread = null
        caps = null
        sessionLog.close()
        Timber.tag(TAG).i("Cluster tuner stopped")
    }

    private fun runCycle() {
        if (openClocksRequested) {
            openClocksRequested = false
            applyUncapped()
            engine.releaseHolds()
            Timber.tag(TAG).i("Clocks reopened for an FPS cap probe")
        }

        val snapshot = metricsProvider()
        val now = System.currentTimeMillis()
        val targetFps = targetFpsProvider()

        if (snapshot == null) {
            logCycle(null, 0, HOLD_ACTION, "no-metrics", engine.steadyCycles, engine.frozenDomains, 0f)
            return
        }
        if (now - snapshot.timestampMs > TunerThresholds.METRICS_STALE_MS) {
            logCycle(snapshot, 0, HOLD_ACTION, "stale-metrics", engine.steadyCycles, engine.frozenDomains, 0f)
            return
        }

        val input = TunerInput(
            fps = snapshot.fps,
            targetFps = targetFps,
            frameTimeP50Ms = snapshot.frameTimeP50Ms,
            frameTimeP95Ms = snapshot.frameTimeP95Ms,
            slowFrameCount = snapshot.slowFrameCount,
            totalFrameCount = snapshot.totalFrameCount,
            cpuUsagePercent = snapshot.cpuUsagePercent,
            gpuUsagePercent = snapshot.gpuUsagePercent,
            cpuTempC = snapshot.cpuTempC,
            gpuTempC = snapshot.gpuTempC,
            capabilities = capabilities(),
        )

        val slowRatio = engine.slowFrameRatio(input)
        val decision = engine.decide(input)
        harvesting = engine.harvesting

        var applied = true
        var reason = decision.reason
        var action = decision.action.name.lowercase(Locale.US)

        when (decision.action) {
            TunerAction.RAISE -> {
                val domain = decision.domain
                applied = domain != null && moveTo(domain, raiseIndex(domain, decision.steps))
            }
            TunerAction.TRIM -> {
                val domain = decision.domain
                applied = domain != null && moveTo(domain, trimIndex(domain, decision.steps))
            }
            TunerAction.UNDO -> {
                val domain = decision.domain
                applied = domain != null && moveTo(domain, raiseIndex(domain, 1))
            }
            TunerAction.HOLD -> {
                applied = applyWarmStateIfDue()
                if (applied) {
                    action = "warmstart"
                    reason = "warmstart"
                }
            }
        }

        if (!applied && decision.action != TunerAction.HOLD) {
            action = HOLD_ACTION
            reason = "${decision.reason}:noop"
        }

        if (applied) {
            persistWarmState()
            Timber.tag(TAG).i(
                "%s %s: fps=%.1f/%d p95=%.1fms slow=%.1f%% cpu=%s%% gpu=%s%% caps[prime=%s perf=%s gpu=%s] frozen=%s",
                action,
                reason,
                snapshot.fps,
                targetFps,
                snapshot.frameTimeP95Ms,
                slowRatio * 100f,
                snapshot.cpuUsagePercent?.toInt()?.toString() ?: "-",
                snapshot.gpuUsagePercent?.toInt()?.toString() ?: "-",
                capLabel(TunerDomain.PRIME),
                capLabel(TunerDomain.PERFORMANCE),
                capLabel(TunerDomain.GPU),
                decision.frozenDomains.joinToString(",") { it.key }.ifEmpty { "-" },
            )
        } else {
            Timber.tag(TAG).d(
                "hold %s: fps=%.1f/%d p95=%.1fms steady=%d",
                reason,
                snapshot.fps,
                targetFps,
                snapshot.frameTimeP95Ms,
                decision.steadyCycles,
            )
        }

        logCycle(snapshot, targetFps, action, reason, decision.steadyCycles, decision.frozenDomains, slowRatio)
    }

    // ========================================
    // Domain state
    // ========================================

    private fun stepsOf(domain: TunerDomain): Int = when (domain) {
        TunerDomain.PRIME -> primeSteps.size
        TunerDomain.PERFORMANCE -> performanceSteps.size
        TunerDomain.GPU -> gpuSteps.size
    }

    private fun lastIndexOf(domain: TunerDomain): Int = stepsOf(domain) - 1

    private fun capabilities(): Map<TunerDomain, DomainCapability> {
        return TunerDomain.entries.associateWith { domain ->
            val last = lastIndexOf(domain)
            val current = stepIndex[domain] ?: last
            DomainCapability(
                available = last > 0,
                canRaise = last > 0 && current < last,
                canTrim = last > 0 && current > 0,
            )
        }
    }

    private fun raiseIndex(domain: TunerDomain, steps: Int): Int {
        val last = lastIndexOf(domain)
        val current = stepIndex[domain] ?: last
        if (steps >= last - current) return last
        return (current + steps).coerceIn(0, last)
    }

    private fun trimIndex(domain: TunerDomain, steps: Int): Int {
        val last = lastIndexOf(domain)
        val current = stepIndex[domain] ?: last
        return (current - steps).coerceIn(0, last)
    }

    private fun moveTo(domain: TunerDomain, newIndex: Int): Boolean {
        val last = lastIndexOf(domain)
        if (last <= 0) return false

        val current = stepIndex[domain] ?: last
        if (newIndex == current) return false

        val applied = when (domain) {
            TunerDomain.PRIME -> applyPrimeCapKhz(primeSteps[newIndex])
            TunerDomain.PERFORMANCE -> applyPerformanceCapKhz(performanceSteps[newIndex])
            TunerDomain.GPU -> applyGpuMaxLevel(gpuSteps[newIndex])
        }

        if (applied) {
            stepIndex[domain] = newIndex
            refreshCaps()
        } else {
            Timber.tag(TAG).w("Failed to apply %s step %d", domain.key, newIndex)
        }
        return applied
    }

    private fun applyUncapped() {
        TunerDomain.entries.forEach { domain ->
            val last = lastIndexOf(domain)
            if (last <= 0) return@forEach
            val applied = when (domain) {
                TunerDomain.PRIME -> applyPrimeCapKhz(primeSteps[last])
                TunerDomain.PERFORMANCE -> applyPerformanceCapKhz(performanceSteps[last])
                TunerDomain.GPU -> applyGpuMaxLevel(gpuSteps[last])
            }
            stepIndex[domain] = last
            if (!applied) {
                Timber.tag(TAG).w("Failed to release %s cap at session start", domain.key)
            }
        }
        refreshCaps()
    }

    private fun refreshCaps() {
        caps = Caps(
            primeKhz = capValue(TunerDomain.PRIME),
            performanceKhz = capValue(TunerDomain.PERFORMANCE),
            gpuLevel = capValue(TunerDomain.GPU)?.toInt(),
        )
        trimmedStepCount = TunerDomain.entries.sumOf { domain ->
            val last = lastIndexOf(domain)
            if (last <= 0) 0 else (last - (stepIndex[domain] ?: last)).coerceAtLeast(0)
        }
    }

    private fun capValue(domain: TunerDomain): Long? {
        val last = lastIndexOf(domain)
        if (last < 0) return null
        val current = stepIndex[domain] ?: return null
        return when (domain) {
            TunerDomain.PRIME -> primeSteps.getOrNull(current)
            TunerDomain.PERFORMANCE -> performanceSteps.getOrNull(current)
            TunerDomain.GPU -> gpuSteps.getOrNull(current)?.toLong()
        }
    }

    private fun capLabel(domain: TunerDomain): String = capValue(domain)?.toString() ?: "-"

    // ========================================
    // Warm start
    // ========================================

    /**
     * Warm-start caps are only applied once the engine has seen a steady render window,
     * so a cold boot always runs uncapped through loading screens.
     */
    private fun applyWarmStateIfDue(): Boolean {
        val state = pendingWarmState ?: return false
        if (engine.steadyCycles < TunerThresholds.STEADY_RENDER_CYCLES) return false

        pendingWarmState = null

        var applied = false
        applied = applyWarmDomain(TunerDomain.PRIME, state.primeIndex, state.primeStepCount) || applied
        applied = applyWarmDomain(TunerDomain.PERFORMANCE, state.performanceIndex, state.performanceStepCount) || applied
        applied = applyWarmDomain(TunerDomain.GPU, state.gpuIndex, state.gpuStepCount) || applied

        if (applied) {
            Timber.tag(TAG).i(
                "Warm-start caps applied [prime=%s perf=%s gpu=%s]",
                capLabel(TunerDomain.PRIME),
                capLabel(TunerDomain.PERFORMANCE),
                capLabel(TunerDomain.GPU),
            )
        }
        return applied
    }

    private fun applyWarmDomain(domain: TunerDomain, savedIndex: Int, savedStepCount: Int): Boolean {
        val last = lastIndexOf(domain)
        if (last <= 0) return false
        if (savedStepCount != stepsOf(domain)) {
            Timber.tag(TAG).w("Discarding warm-start %s: step list changed", domain.key)
            return false
        }
        if (savedIndex !in 0..last || savedIndex >= last) return false
        return moveTo(domain, savedIndex)
    }

    private fun readWarmState(): WarmState? {
        val file = stateFile ?: return null
        return try {
            if (!file.exists() || file.length() == 0L) return null
            json.decodeFromString(WarmState.serializer(), file.readText())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read tuner state, ignoring it")
            null
        }
    }

    private fun persistWarmState() {
        val file = stateFile ?: return
        try {
            val state = WarmState(
                primeIndex = stepIndex[TunerDomain.PRIME] ?: -1,
                primeStepCount = primeSteps.size,
                performanceIndex = stepIndex[TunerDomain.PERFORMANCE] ?: -1,
                performanceStepCount = performanceSteps.size,
                gpuIndex = stepIndex[TunerDomain.GPU] ?: -1,
                gpuStepCount = gpuSteps.size,
            )
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(WarmState.serializer(), state))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to persist tuner state")
        }
    }

    // ========================================
    // Decision log
    // ========================================

    private fun logCycle(
        snapshot: MetricsSnapshot?,
        targetFps: Int,
        action: String,
        reason: String,
        steadyCycles: Int,
        frozen: Set<TunerDomain>,
        slowRatio: Float,
    ) {
        val fanSample = fanSampleProvider()
        val fpsCap = fpsCapProvider()
        val line = String.format(
            Locale.US,
            "{\"t\":%d,\"fps\":%s,\"target\":%d,\"p50\":%s,\"p95\":%s,\"slowRatio\":%.4f," +
                "\"cpu\":%s,\"cpuSrc\":\"%s\",\"gpu\":%s,\"cpuTemp\":%s,\"gpuTemp\":%s," +
                "\"capPrime\":%s,\"capPerf\":%s,\"capGpu\":%s,\"frozen\":[%s]," +
                "\"action\":\"%s\",\"reason\":\"%s\",\"steadyCount\":%d," +
                "\"strategy\":\"%s\",\"fanPercent\":%s,\"fanTempC\":%s," +
                "\"userCap\":%d,\"effectiveCap\":%d,\"capState\":\"%s\"}",
            System.currentTimeMillis(),
            snapshot?.let { String.format(Locale.US, "%.2f", it.fps) } ?: "null",
            targetFps,
            snapshot?.let { String.format(Locale.US, "%.2f", it.frameTimeP50Ms) } ?: "null",
            snapshot?.let { String.format(Locale.US, "%.2f", it.frameTimeP95Ms) } ?: "null",
            slowRatio,
            snapshot?.cpuUsagePercent?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            snapshot?.cpuUsageSource?.name ?: "UNAVAILABLE",
            snapshot?.gpuUsagePercent?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            snapshot?.cpuTempC?.toString() ?: "null",
            snapshot?.gpuTempC?.toString() ?: "null",
            capValue(TunerDomain.PRIME)?.toString() ?: "null",
            capValue(TunerDomain.PERFORMANCE)?.toString() ?: "null",
            capValue(TunerDomain.GPU)?.toString() ?: "null",
            frozen.joinToString(",") { "\"${it.key}\"" },
            action,
            reason,
            steadyCycles,
            strategyProvider().name,
            fanSample?.appliedPercent?.toString() ?: "null",
            fanSample?.tempC?.toString() ?: "null",
            fpsCap?.userCapFps ?: 0,
            fpsCap?.effectiveCapFps ?: 0,
            fpsCap?.capState ?: CAP_STATE_OFF,
        )
        sessionLog.append(line)
    }
}
