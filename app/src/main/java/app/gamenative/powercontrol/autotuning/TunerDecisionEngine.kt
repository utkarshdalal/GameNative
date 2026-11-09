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
    /**
     * Frame intervals longer than 1.5 target periods over the last
     * [TunerThresholds.REPEAT_WINDOW_MS], from the app's own frame timestamps. At a cap each
     * one is a frame the display showed twice.
     */
    val repeatedFrameCount: Int = 0,
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

    /**
     * GPU busy has to fall this far before a workload already judged GPU-bound is let go.
     * Without the gap a render that swings either side of [GPU_BOUND_USAGE_PERCENT] lands in
     * the unclassified band every few seconds, and an unclassified miss raises clocks - which
     * hands back every step the harvest took.
     */
    const val GPU_BOUND_RELEASE_PERCENT = 75f

    const val HARVEST_CPU_HEADROOM_PERCENT = 70f
    const val HARVEST_GPU_IDLE_PERCENT = 60f

    /**
     * A harvest runs while the target is out of reach, so its guard compares against the
     * render before the trim rather than against the target. That reference is noisy - a
     * benchmark or a scene change moves the frame rate by far more than a clock step does -
     * so the guard is deliberately slack, is not read until the metrics window has turned
     * over, and needs the same verdict twice before it gives a step back.
     */
    const val HARVEST_FPS_DROP_RATIO = 0.90f
    const val HARVEST_P95_TRIM_FACTOR = 1.6f
    const val HARVEST_SLOW_FRAME_GAIN_RATIO = 0.10f
    const val HARVEST_SETTLE_CYCLES = 1
    const val HARVEST_DEGRADED_CYCLES = 2
    const val HARVEST_WATCH_CYCLES = 5

    /**
     * Weight of the newest sample in the render averages the harvest baseline is taken from.
     */
    const val HARVEST_BASELINE_ALPHA = 0.3f

    const val RAISE_STEP_COUNT = 3
    const val RAISE_CYCLES_BEFORE_UNCAP = 2
    const val TRIM_QUALIFY_CYCLES = 3
    const val TRIM_WATCH_CYCLES = 3
    const val REPROBE_INTERVAL_CYCLES = 60

    const val STEPS_UNCAPPED = Int.MAX_VALUE

    /**
     * Window the repeated-frame count covers. The fps and p95 of a capped game read as steady
     * while it repeats a frame every few seconds (59.9 fps, p95 one period), so repeats are
     * counted over a window long enough to see them.
     */
    const val REPEAT_WINDOW_MS = 10_000L
    const val REPEAT_WINDOW_LABEL = "10s"

    /**
     * Any repeat in the window blocks the next trim. Repeats at or above this count step the
     * last trim back. A capped game with power control off repeats about once in 90 s
     * (RP6, SteamWorld Dig 2: 1 in 90 s), so two in one 10 s window almost never happens
     * on its own, while the trimmed run repeated 25 times in 70 s.
     */
    const val REPEAT_UNDO_COUNT = 2

    /**
     * Cycles after a repeat step-back before the next one: the repeats that caused it stay in
     * the window until it has turned over, and must not step back a second domain.
     */
    const val REPEAT_UNDO_COOLDOWN_CYCLES = (REPEAT_WINDOW_MS / CYCLE_INTERVAL_MS).toInt()

    /**
     * Repeat step-backs that did not cut the repeats in the window after them before repeats
     * stop being a step-back signal for the session. A game that hitches on its own (shader
     * compiles, GC pauses, streaming) repeats whatever the clocks are; repeats still block trims.
     */
    const val REPEAT_UNDO_INEFFECTIVE_LIMIT = 3
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

/** One trimmed step: made by this session, or carried in by a warm start. */
private data class TrimEntry(val domain: TunerDomain, val warmStart: Boolean)

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

    /** Domains trimmed this session, newest last, so a repeat step-back undoes the latest. */
    private val trimHistory = ArrayList<TrimEntry>()
    private var repeatUndoCooldownCycles = 0
    private var repeatUndoBaseline = -1
    private var repeatUndoWarm = false
    private var ineffectiveRepeatUndos = 0
    private var repeatStepBackOff = false
    private var lastTargetFps = 0

    private var lastBottleneck: Bottleneck? = null
    private var harvestBottleneck: Bottleneck? = null
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
        if (input.targetFps != lastTargetFps) {
            // A new cap re-scores the window: a step-back in flight can no longer be judged.
            if (lastTargetFps != 0) {
                repeatUndoCooldownCycles = 0
                repeatUndoBaseline = -1
            }
            lastTargetFps = input.targetFps
        }
        if (repeatUndoCooldownCycles > 0) {
            repeatUndoCooldownCycles--
            if (repeatUndoCooldownCycles == 0) judgeRepeatUndo(input.repeatedFrameCount)
        }

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
                forgetTrim(watched)
                val cause = if (input.repeatedFrameCount > 0) " ${repeatLabel(input)}" else ""
                return decision(TunerAction.UNDO, watched, 1, "undo:${watched.key}$cause")
            }
            watchCyclesRemaining--
            return decision(TunerAction.HOLD, watched, 0, "watch:${watched.key}")
        }

        val repeated = input.repeatedFrameCount
        if (repeated > 0) {
            trimQualifiedCycles = 0
            if (repeated >= TunerThresholds.REPEAT_UNDO_COUNT && repeatUndoCooldownCycles == 0) {
                val entry = repeatUndoTarget(input)
                if (entry != null) {
                    val domain = entry.domain
                    frozen.add(domain)
                    trimHistory.remove(entry)
                    repeatUndoCooldownCycles = TunerThresholds.REPEAT_UNDO_COOLDOWN_CYCLES
                    repeatUndoBaseline = repeated
                    repeatUndoWarm = entry.warmStart
                    return decision(TunerAction.UNDO, domain, 1, "repeat-undo:${domain.key} ${repeatLabel(input)}")
                }
            }
            val note = if (repeatStepBackOff) " step-back off" else ""
            return decision(TunerAction.HOLD, null, 0, "repeat-hold ${repeatLabel(input)}$note")
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
                trimHistory.add(TrimEntry(domain, warmStart = false))
                return decision(TunerAction.TRIM, domain, 1, "trim:${domain.key}")
            }
            return decision(TunerAction.HOLD, null, 0, "trim:no-domain")
        }

        return decision(TunerAction.HOLD, null, 0, if (steadyEnough) "hold" else "warmup")
    }

    /**
     * Records warm-start caps as trims made this session, [stepsByDomain] steps below the top per
     * domain, so repeats can step them back like any other trim. A state file written by an
     * earlier session can hold a domain at its floor; without this the floor would only ever be
     * held. Entries interleave, the most-trimmed domain newest, so step-backs start there.
     */
    fun noteWarmStart(stepsByDomain: Map<TunerDomain, Int>) {
        val most = stepsByDomain.values.maxOrNull() ?: return
        for (step in 0 until most) {
            stepsByDomain.entries
                .filter { it.value > step }
                .sortedBy { it.value }
                .forEach { trimHistory.add(TrimEntry(it.key, warmStart = true)) }
        }
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
        trimHistory.clear()
        repeatUndoCooldownCycles = 0
        repeatUndoBaseline = -1
    }

    fun slowFrameRatio(input: TunerInput): Float {
        if (input.totalFrameCount <= 0) return 0f
        return input.slowFrameCount.toFloat() / input.totalFrameCount.toFloat()
    }

    /**
     * The domain a repeat step-back raises: the latest trim made this session that can still go
     * up. Without one the repeats are not the tuner's doing, and nothing is raised.
     */
    private fun repeatUndoTarget(input: TunerInput): TrimEntry? {
        return trimHistory.lastOrNull { entry ->
            (entry.warmStart || !repeatStepBackOff) && input.capabilities[entry.domain]?.canRaise == true
        }
    }

    /**
     * Judges a repeat step-back once its window has turned over: one that left at least as many
     * repeats as it started from did not help, and after
     * [TunerThresholds.REPEAT_UNDO_INEFFECTIVE_LIMIT] of those repeats stop stepping back.
     */
    private fun judgeRepeatUndo(repeated: Int) {
        val baseline = repeatUndoBaseline
        repeatUndoBaseline = -1
        // A warm-start floor is the known-bad state: stepping it back is never held against
        // the repeat signal, it goes on until the floor is gone or the repeats stop.
        if (repeatUndoWarm || baseline < 0 || repeated < baseline) return
        ineffectiveRepeatUndos++
        if (ineffectiveRepeatUndos >= TunerThresholds.REPEAT_UNDO_INEFFECTIVE_LIMIT) {
            repeatStepBackOff = true
        }
    }

    private fun forgetTrim(domain: TunerDomain) {
        val index = trimHistory.indexOfLast { it.domain == domain && !it.warmStart }
            .takeIf { it >= 0 } ?: trimHistory.indexOfLast { it.domain == domain }
        if (index >= 0) trimHistory.removeAt(index)
    }

    private fun repeatLabel(input: TunerInput): String =
        "repeated=${input.repeatedFrameCount} in ${TunerThresholds.REPEAT_WINDOW_LABEL}"

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
        bottleneck: Bottleneck,
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
        trimHistory.add(TrimEntry(domain, warmStart = false))
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
                forgetTrim(watched)
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
    private fun classifyBottleneck(input: TunerInput): Bottleneck? {
        val gpu = input.gpuUsagePercent ?: return null
        val cpu = input.cpuUsagePercent ?: return null
        val gpuBoundThreshold = if (lastBottleneck == Bottleneck.GPU) {
            TunerThresholds.GPU_BOUND_RELEASE_PERCENT
        } else {
            TunerThresholds.GPU_BOUND_USAGE_PERCENT
        }
        val classified = when {
            gpu >= gpuBoundThreshold && cpu < TunerThresholds.HARVEST_CPU_HEADROOM_PERCENT -> Bottleneck.GPU
            gpu <= TunerThresholds.HARVEST_GPU_IDLE_PERCENT -> Bottleneck.CPU
            else -> null
        }
        lastBottleneck = classified
        return classified
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
        return input.repeatedFrameCount == 0 &&
            input.fps >= target - tuning.fpsTrimToleranceFps &&
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
