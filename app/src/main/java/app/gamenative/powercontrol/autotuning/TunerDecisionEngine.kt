package app.gamenative.powercontrol.autotuning

/**
 * Control domains the tuner may cap, in trim-priority order.
 */
enum class TunerDomain(val key: String) {
    PRIME("prime"),
    PERFORMANCE("perf"),
    GPU("gpu"),
}

enum class TunerAction {
    HOLD,
    RAISE,
    TRIM,
    UNDO,
}

/**
 * What a domain can currently do, derived from its step list and current index.
 */
data class DomainCapability(
    val available: Boolean,
    val canRaise: Boolean,
    val canTrim: Boolean,
)

data class TunerInput(
    val fps: Float,
    val targetFps: Int,
    val frameTimeP50Ms: Float,
    val frameTimeP95Ms: Float,
    val slowFrameCount: Int,
    val totalFrameCount: Int,
    val cpuUsagePercent: Float?,
    val gpuUsagePercent: Float?,
    val cpuTempC: Int?,
    val gpuTempC: Int?,
    val capabilities: Map<TunerDomain, DomainCapability>,
)

data class TunerDecision(
    val action: TunerAction,
    val domain: TunerDomain?,
    val steps: Int,
    val reason: String,
    val steadyCycles: Int,
    val frozenDomains: Set<TunerDomain>,
)

object TunerThresholds {
    const val CYCLE_INTERVAL_MS = 1000L
    const val METRICS_STALE_MS = 2000L

    const val STEADY_RENDER_CYCLES = 5

    const val FPS_RAISE_DEFICIT_FPS = 3f
    const val FPS_TRIM_TOLERANCE_FPS = 1f
    const val SLOW_FRAME_RAISE_RATIO = 0.10f
    const val SLOW_FRAME_TRIM_RATIO = 0.02f
    const val FRAME_TIME_P95_TRIM_FACTOR = 1.25f
    const val GPU_BOUND_USAGE_PERCENT = 85f
    const val HARVEST_CPU_HEADROOM_PERCENT = 70f
    const val HARVEST_GPU_IDLE_PERCENT = 60f
    const val HARVEST_FPS_DROP_RATIO = 0.97f

    const val RAISE_STEP_COUNT = 3
    const val RAISE_CYCLES_BEFORE_UNCAP = 2
    const val TRIM_QUALIFY_CYCLES = 3
    const val TRIM_WATCH_CYCLES = 3
    const val REPROBE_INTERVAL_CYCLES = 60

    const val STEPS_UNCAPPED = Int.MAX_VALUE
}

data class StrategyTuning(
    val fpsRaiseDeficitFps: Float,
    val fpsTrimToleranceFps: Float,
    val trimQualifyCycles: Int,
    val trimWatchCycles: Int,
    val raiseCyclesBeforeUncap: Int,
) {
    companion object {
        val BALANCED = StrategyTuning(
            fpsRaiseDeficitFps = TunerThresholds.FPS_RAISE_DEFICIT_FPS,
            fpsTrimToleranceFps = TunerThresholds.FPS_TRIM_TOLERANCE_FPS,
            trimQualifyCycles = TunerThresholds.TRIM_QUALIFY_CYCLES,
            trimWatchCycles = TunerThresholds.TRIM_WATCH_CYCLES,
            raiseCyclesBeforeUncap = TunerThresholds.RAISE_CYCLES_BEFORE_UNCAP,
        )
        val POWER_EFFICIENT = BALANCED.copy(
            fpsTrimToleranceFps = 2f,
            trimQualifyCycles = 2,
        )
        val AGGRESSIVE = BALANCED.copy(
            fpsRaiseDeficitFps = 2f,
            trimQualifyCycles = 5,
            raiseCyclesBeforeUncap = 1,
        )
        val CONSERVATIVE = BALANCED.copy(
            trimQualifyCycles = 6,
            trimWatchCycles = 4,
        )
    }
}

/**
 * Which side of the pipeline holds the frame rate back while the target is out of reach.
 * GPU busy is trusted directly; CPU-boundness is inferred from an idle GPU, because a
 * single-thread-bound game never pushes the total CPU percent high.
 */
private enum class Bottleneck(val key: String) {
    GPU("gpu"),
    CPU("cpu"),
}

/**
 * Pure decision logic for [ClusterTuner]: a snapshot plus the current per-domain
 * capabilities in, one action out. Holds all tuning state and has no Android or
 * sysfs dependency, so it can be exercised on the JVM.
 */
class TunerDecisionEngine(
    private val strategyTuning: () -> StrategyTuning = { StrategyTuning.BALANCED },
) {

    private val frozen = LinkedHashSet<TunerDomain>()

    var steadyCycles: Int = 0
        private set

    private var cycleCount = 0
    private var raiseCycles = 0
    private var trimQualifiedCycles = 0
    private var watchCyclesRemaining = 0
    private var watchedDomain: TunerDomain? = null

    private var harvestBottleneck: Bottleneck? = null
    private var harvestQualifiedCycles = 0
    private var harvestWatchCyclesRemaining = 0
    private var harvestWatchedDomain: TunerDomain? = null
    private var harvestBaselineFps = 0f
    private var harvestBaselineP95Ms = 0f
    private var harvestBaselineSlowRatio = 0f

    val frozenDomains: Set<TunerDomain>
        get() = frozen.toSet()

    fun decide(input: TunerInput): TunerDecision {
        cycleCount++
        if (cycleCount % TunerThresholds.REPROBE_INTERVAL_CYCLES == 0) {
            frozen.clear()
        }

        val slowRatio = slowFrameRatio(input)

        if (input.targetFps <= 0) {
            return decision(TunerAction.HOLD, null, 0, "no-target")
        }

        if (input.fps <= 0f || input.totalFrameCount <= 0) {
            steadyCycles = 0
            raiseCycles = 0
            trimQualifiedCycles = 0
            clearHarvest()
            return decision(TunerAction.HOLD, null, 0, "not-steady")
        }
        steadyCycles++

        val target = input.targetFps.toFloat()
        val targetPeriodMs = 1000f / target
        val tuning = strategyTuning()

        val missingTarget = input.fps < target - tuning.fpsRaiseDeficitFps
        val slowFrameSpike = slowRatio > TunerThresholds.SLOW_FRAME_RAISE_RATIO

        if (missingTarget || slowFrameSpike) {
            val bottleneck = classifyBottleneck(input)
            if (bottleneck != null && isBottleneckMaxed(input, bottleneck)) {
                return harvest(input, slowRatio, bottleneck, tuning)
            }

            raiseCycles++
            trimQualifiedCycles = 0
            clearWatch()
            clearHarvest()

            val domain = raiseTarget(input)
            if (domain == null) {
                return decision(TunerAction.HOLD, null, 0, "raise:uncapped")
            }

            val steps = if (raiseCycles >= tuning.raiseCyclesBeforeUncap) {
                TunerThresholds.STEPS_UNCAPPED
            } else {
                TunerThresholds.RAISE_STEP_COUNT
            }
            return decision(TunerAction.RAISE, domain, steps, "raise:${domain.key}")
        }
        raiseCycles = 0
        harvestBottleneck = null
        harvestQualifiedCycles = 0

        val harvestWatched = harvestWatchedDomain
        if (harvestWatchCyclesRemaining > 0 && harvestWatched != null) {
            return harvestWatch(input, slowRatio, harvestWatched)
        }

        val watched = watchedDomain
        if (watchCyclesRemaining > 0 && watched != null) {
            if (isDegraded(input, slowRatio, targetPeriodMs, tuning)) {
                frozen.add(watched)
                clearWatch()
                trimQualifiedCycles = 0
                return decision(TunerAction.UNDO, watched, 1, "undo:${watched.key}")
            }
            watchCyclesRemaining--
            return decision(TunerAction.HOLD, watched, 0, "watch:${watched.key}")
        }

        if (isHealthy(input, slowRatio, targetPeriodMs, tuning)) {
            trimQualifiedCycles++
        } else {
            trimQualifiedCycles = 0
        }

        val steadyEnough = steadyCycles >= TunerThresholds.STEADY_RENDER_CYCLES
        if (steadyEnough && trimQualifiedCycles >= tuning.trimQualifyCycles) {
            val domain = trimTarget(input)
            if (domain != null) {
                trimQualifiedCycles = 0
                watchedDomain = domain
                watchCyclesRemaining = tuning.trimWatchCycles
                return decision(TunerAction.TRIM, domain, 1, "trim:${domain.key}")
            }
            return decision(TunerAction.HOLD, null, 0, "trim:no-domain")
        }

        return decision(TunerAction.HOLD, null, 0, if (steadyEnough) "hold" else "warmup")
    }

    /**
     * Drops the frozen set and any in-flight trim watch, the same trim state a raise clears.
     * For owners that open every domain outside the decision path.
     */
    fun releaseHolds() {
        frozen.clear()
        clearWatch()
        clearHarvest()
        trimQualifiedCycles = 0
    }

    fun slowFrameRatio(input: TunerInput): Float {
        if (input.totalFrameCount <= 0) return 0f
        return input.slowFrameCount.toFloat() / input.totalFrameCount.toFloat()
    }

    private fun raiseTarget(input: TunerInput): TunerDomain? {
        val gpuBound = (input.gpuUsagePercent ?: 0f) >= TunerThresholds.GPU_BOUND_USAGE_PERCENT
        val order = if (gpuBound) {
            listOf(TunerDomain.GPU, TunerDomain.PRIME, TunerDomain.PERFORMANCE)
        } else {
            listOf(TunerDomain.PRIME, TunerDomain.PERFORMANCE, TunerDomain.GPU)
        }
        return order.firstOrNull { input.capabilities[it]?.canRaise == true }
    }

    /**
     * Runs while the target is out of reach and the bottleneck side has no headroom left:
     * qualifies over consecutive cycles that agree on the bottleneck, then trims one step
     * off a domain that is not the bottleneck and watches the result against the frame
     * rate that step started from.
     */
    private fun harvest(
        input: TunerInput,
        slowRatio: Float,
        bottleneck: Bottleneck,
        tuning: StrategyTuning,
    ): TunerDecision {
        raiseCycles = 0
        trimQualifiedCycles = 0
        clearWatch()

        val watched = harvestWatchedDomain
        if (harvestWatchCyclesRemaining > 0 && watched != null) {
            return harvestWatch(input, slowRatio, watched)
        }

        if (bottleneck != harvestBottleneck) {
            harvestBottleneck = bottleneck
            harvestQualifiedCycles = 1
        } else {
            harvestQualifiedCycles++
        }

        val steadyEnough = steadyCycles >= TunerThresholds.STEADY_RENDER_CYCLES
        if (!steadyEnough || harvestQualifiedCycles < tuning.trimQualifyCycles) {
            return decision(TunerAction.HOLD, null, 0, "harvest-qualify:${bottleneck.key}")
        }

        val domain = harvestTarget(input, bottleneck)
            ?: return decision(TunerAction.HOLD, null, 0, "harvest-none:${bottleneck.key}")

        harvestQualifiedCycles = 0
        harvestWatchedDomain = domain
        harvestWatchCyclesRemaining = tuning.trimWatchCycles
        harvestBaselineFps = input.fps
        harvestBaselineP95Ms = input.frameTimeP95Ms
        harvestBaselineSlowRatio = slowRatio
        return decision(TunerAction.TRIM, domain, 1, "harvest:${domain.key}")
    }

    private fun harvestWatch(input: TunerInput, slowRatio: Float, watched: TunerDomain): TunerDecision {
        if (isHarvestDegraded(input, slowRatio)) {
            frozen.add(watched)
            clearHarvest()
            return decision(TunerAction.UNDO, watched, 1, "harvest-undo:${watched.key}")
        }
        harvestWatchCyclesRemaining--
        if (harvestWatchCyclesRemaining <= 0) {
            clearHarvestWatch()
        }
        return decision(TunerAction.HOLD, watched, 0, "harvest-watch:${watched.key}")
    }

    /**
     * The target is unreachable during a harvest, so the absolute health test says nothing.
     * A harvested step is kept only while the frame rate stays within
     * [TunerThresholds.HARVEST_FPS_DROP_RATIO] of the rate measured just before the trim,
     * the p95 frame time stays within the normal trim factor of its pre-trim value, and the
     * slow-frame ratio gains no more than the normal trim ratio over its pre-trim value.
     */
    private fun isHarvestDegraded(input: TunerInput, slowRatio: Float): Boolean {
        return input.fps < harvestBaselineFps * TunerThresholds.HARVEST_FPS_DROP_RATIO ||
            input.frameTimeP95Ms > harvestBaselineP95Ms * TunerThresholds.FRAME_TIME_P95_TRIM_FACTOR ||
            slowRatio > harvestBaselineSlowRatio + TunerThresholds.SLOW_FRAME_TRIM_RATIO
    }

    private fun classifyBottleneck(input: TunerInput): Bottleneck? {
        val gpu = input.gpuUsagePercent ?: return null
        val cpu = input.cpuUsagePercent ?: return null
        return when {
            gpu >= TunerThresholds.GPU_BOUND_USAGE_PERCENT &&
                cpu < TunerThresholds.HARVEST_CPU_HEADROOM_PERCENT -> Bottleneck.GPU
            gpu <= TunerThresholds.HARVEST_GPU_IDLE_PERCENT -> Bottleneck.CPU
            else -> null
        }
    }

    private fun isBottleneckMaxed(input: TunerInput, bottleneck: Bottleneck): Boolean {
        val domains = when (bottleneck) {
            Bottleneck.GPU -> listOf(TunerDomain.GPU)
            Bottleneck.CPU -> listOf(TunerDomain.PRIME, TunerDomain.PERFORMANCE)
        }
        return domains.none { input.capabilities[it]?.canRaise == true }
    }

    private fun harvestTarget(input: TunerInput, bottleneck: Bottleneck): TunerDomain? {
        val order = when (bottleneck) {
            Bottleneck.GPU -> listOf(TunerDomain.PRIME, TunerDomain.PERFORMANCE)
            Bottleneck.CPU -> listOf(TunerDomain.GPU)
        }
        return order.firstOrNull { domain ->
            domain !in frozen && input.capabilities[domain]?.canTrim == true
        }
    }

    private fun trimTarget(input: TunerInput): TunerDomain? {
        return TunerDomain.entries.firstOrNull { domain ->
            domain !in frozen && input.capabilities[domain]?.canTrim == true
        }
    }

    private fun isHealthy(input: TunerInput, slowRatio: Float, targetPeriodMs: Float, tuning: StrategyTuning): Boolean {
        val target = input.targetFps.toFloat()
        return input.fps >= target - tuning.fpsTrimToleranceFps &&
            input.frameTimeP95Ms <= targetPeriodMs * TunerThresholds.FRAME_TIME_P95_TRIM_FACTOR &&
            slowRatio < TunerThresholds.SLOW_FRAME_TRIM_RATIO
    }

    private fun isDegraded(input: TunerInput, slowRatio: Float, targetPeriodMs: Float, tuning: StrategyTuning): Boolean {
        return !isHealthy(input, slowRatio, targetPeriodMs, tuning)
    }

    private fun clearWatch() {
        watchCyclesRemaining = 0
        watchedDomain = null
    }

    private fun clearHarvest() {
        harvestBottleneck = null
        harvestQualifiedCycles = 0
        clearHarvestWatch()
    }

    private fun clearHarvestWatch() {
        harvestWatchCyclesRemaining = 0
        harvestWatchedDomain = null
        harvestBaselineFps = 0f
        harvestBaselineP95Ms = 0f
        harvestBaselineSlowRatio = 0f
    }

    private fun decision(
        action: TunerAction,
        domain: TunerDomain?,
        steps: Int,
        reason: String,
    ): TunerDecision {
        return TunerDecision(
            action = action,
            domain = domain,
            steps = steps,
            reason = reason,
            steadyCycles = steadyCycles,
            frozenDomains = frozen.toSet(),
        )
    }
}
