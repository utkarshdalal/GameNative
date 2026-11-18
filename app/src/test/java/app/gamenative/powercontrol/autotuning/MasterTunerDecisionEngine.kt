package app.gamenative.powercontrol.autotuning

// TunerDecisionEngine as on origin/master, verbatim apart from the class names, kept as the
// reference TunerMasterEquivalenceTest compares the current engine against.

/**
 * Which side of the pipeline holds the frame rate back while the target is out of reach.
 * GPU busy is trusted directly; CPU-boundness is inferred from an idle GPU, because a
 * single-thread-bound game never pushes the total CPU percent high.
 */
private enum class MasterBottleneck(val key: String) {
    GPU("gpu"),
    CPU("cpu"),
}

/**
 * Pure decision logic for [ClusterTuner]: a snapshot plus the current per-domain
 * capabilities in, one action out. Holds all tuning state and has no Android or
 * sysfs dependency, so it can be exercised on the JVM.
 */
class MasterTunerDecisionEngine(
    private val strategyTuning: () -> StrategyTuning = { StrategyTuning.BALANCED },
) {

    private val frozen = LinkedHashSet<TunerDomain>()

    var steadyCycles: Int = 0
        private set

    /**
     * True while the engine is harvesting: the target is out of reach, the side holding the
     * frame rate back has nothing left to give, and the steps being taken come off a domain
     * that is not the bottleneck. Owners use it to tell "we are saving power we do not need"
     * apart from "we are still holding performance back".
     */
    var harvesting: Boolean = false
        private set

    private var cycleCount = 0
    private var raiseCycles = 0
    private var trimQualifiedCycles = 0
    private var watchCyclesRemaining = 0
    private var watchedDomain: TunerDomain? = null

    private var lastBottleneck: MasterBottleneck? = null
    private var harvestBottleneck: MasterBottleneck? = null
    private var harvestQualifiedCycles = 0
    private var harvestWatchCyclesRemaining = 0
    private var harvestWatchedDomain: TunerDomain? = null
    private var harvestBaselineFps = 0f
    private var harvestBaselineP95Ms = 0f
    private var harvestBaselineSlowRatio = 0f
    private var harvestSettleCyclesRemaining = 0
    private var harvestDegradedCycles = 0

    private var averageFps: Float? = null
    private var averageP95Ms: Float? = null
    private var averageSlowRatio: Float? = null

    val frozenDomains: Set<TunerDomain>
        get() = frozen.toSet()

    fun decide(input: TunerInput): TunerDecision {
        cycleCount++
        harvesting = false
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
            clearRenderAverages()
            lastBottleneck = null
            return decision(TunerAction.HOLD, null, 0, "not-steady")
        }
        steadyCycles++
        updateRenderAverages(input, slowRatio)

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
        clearRenderAverages()
        trimQualifiedCycles = 0
        lastBottleneck = null
    }

    fun slowFrameRatio(input: TunerInput): Float {
        if (input.totalFrameCount <= 0) return 0f
        return input.slowFrameCount.toFloat() / input.totalFrameCount.toFloat()
    }

    private fun raiseTarget(input: TunerInput): TunerDomain? {
        // A missing GPU reading is not evidence of a GPU-bound render: raise the CPU first, the
        // same order a device without GPU control ends up with.
        val gpuUsage = input.gpuUsagePercent
        val gpuBound = gpuUsage != null && gpuUsage >= TunerThresholds.GPU_BOUND_USAGE_PERCENT
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
        bottleneck: MasterBottleneck,
        tuning: StrategyTuning,
    ): TunerDecision {
        harvesting = true
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
        harvestWatchCyclesRemaining = TunerThresholds.HARVEST_WATCH_CYCLES
        harvestSettleCyclesRemaining = TunerThresholds.HARVEST_SETTLE_CYCLES
        harvestDegradedCycles = 0
        harvestBaselineFps = averageFps ?: input.fps
        harvestBaselineP95Ms = averageP95Ms ?: input.frameTimeP95Ms
        harvestBaselineSlowRatio = averageSlowRatio ?: slowRatio
        return decision(TunerAction.TRIM, domain, 1, "harvest:${domain.key}")
    }

    /**
     * The frames measured on the cycle right after a trim were mostly rendered before it
     * landed, so the first cycles of a watch only settle. After that a single degraded
     * reading is treated as noise; [TunerThresholds.HARVEST_DEGRADED_CYCLES] consecutive
     * ones give the step back.
     */
    private fun harvestWatch(input: TunerInput, slowRatio: Float, watched: TunerDomain): TunerDecision {
        harvesting = true
        harvestWatchCyclesRemaining--

        if (harvestSettleCyclesRemaining > 0) {
            harvestSettleCyclesRemaining--
            if (harvestWatchCyclesRemaining <= 0) {
                clearHarvestWatch()
            }
            return decision(TunerAction.HOLD, watched, 0, "harvest-settle:${watched.key}")
        }

        if (isHarvestDegraded(input, slowRatio)) {
            harvestDegradedCycles++
            if (harvestDegradedCycles >= TunerThresholds.HARVEST_DEGRADED_CYCLES) {
                frozen.add(watched)
                clearHarvest()
                return decision(TunerAction.UNDO, watched, 1, "harvest-undo:${watched.key}")
            }
        } else {
            harvestDegradedCycles = 0
        }

        if (harvestWatchCyclesRemaining <= 0) {
            clearHarvestWatch()
        }
        return decision(TunerAction.HOLD, watched, 0, "harvest-watch:${watched.key}")
    }

    private fun updateRenderAverages(input: TunerInput, slowRatio: Float) {
        val alpha = TunerThresholds.HARVEST_BASELINE_ALPHA
        averageFps = averageFps?.let { it + alpha * (input.fps - it) } ?: input.fps
        averageP95Ms = averageP95Ms?.let { it + alpha * (input.frameTimeP95Ms - it) } ?: input.frameTimeP95Ms
        averageSlowRatio = averageSlowRatio?.let { it + alpha * (slowRatio - it) } ?: slowRatio
    }

    private fun clearRenderAverages() {
        averageFps = null
        averageP95Ms = null
        averageSlowRatio = null
    }

    /**
     * The target is unreachable during a harvest, so the absolute health test says nothing.
     * A harvested step is kept while the frame rate stays within
     * [TunerThresholds.HARVEST_FPS_DROP_RATIO] of the average rate the trim started from,
     * the p95 frame time stays within [TunerThresholds.HARVEST_P95_TRIM_FACTOR] of its
     * pre-trim average, and the slow-frame ratio gains no more than
     * [TunerThresholds.HARVEST_SLOW_FRAME_GAIN_RATIO] over its pre-trim average.
     */
    private fun isHarvestDegraded(input: TunerInput, slowRatio: Float): Boolean {
        return input.fps < harvestBaselineFps * TunerThresholds.HARVEST_FPS_DROP_RATIO ||
            input.frameTimeP95Ms > harvestBaselineP95Ms * TunerThresholds.HARVEST_P95_TRIM_FACTOR ||
            slowRatio > harvestBaselineSlowRatio + TunerThresholds.HARVEST_SLOW_FRAME_GAIN_RATIO
    }

    /**
     * GPU-boundness latches: entering needs [TunerThresholds.GPU_BOUND_USAGE_PERCENT], but
     * leaving needs a drop to [TunerThresholds.GPU_BOUND_RELEASE_PERCENT], so a busy figure
     * that dips for one cycle does not turn a harvest into a raise.
     */
    private fun classifyBottleneck(input: TunerInput): MasterBottleneck? {
        val gpu = input.gpuUsagePercent ?: return null
        val cpu = input.cpuUsagePercent ?: return null
        val gpuBoundThreshold = if (lastBottleneck == MasterBottleneck.GPU) {
            TunerThresholds.GPU_BOUND_RELEASE_PERCENT
        } else {
            TunerThresholds.GPU_BOUND_USAGE_PERCENT
        }
        val classified = when {
            gpu >= gpuBoundThreshold && cpu < TunerThresholds.HARVEST_CPU_HEADROOM_PERCENT -> MasterBottleneck.GPU
            gpu <= TunerThresholds.HARVEST_GPU_IDLE_PERCENT -> MasterBottleneck.CPU
            else -> null
        }
        lastBottleneck = classified
        return classified
    }

    private fun isBottleneckMaxed(input: TunerInput, bottleneck: MasterBottleneck): Boolean {
        val domains = when (bottleneck) {
            MasterBottleneck.GPU -> listOf(TunerDomain.GPU)
            MasterBottleneck.CPU -> listOf(TunerDomain.PRIME, TunerDomain.PERFORMANCE)
        }
        return domains.none { input.capabilities[it]?.canRaise == true }
    }

    private fun harvestTarget(input: TunerInput, bottleneck: MasterBottleneck): TunerDomain? {
        val order = when (bottleneck) {
            MasterBottleneck.GPU -> listOf(TunerDomain.PRIME, TunerDomain.PERFORMANCE)
            MasterBottleneck.CPU -> listOf(TunerDomain.GPU)
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
        harvestSettleCyclesRemaining = 0
        harvestDegradedCycles = 0
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
