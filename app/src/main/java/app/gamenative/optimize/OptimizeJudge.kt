package app.gamenative.optimize

import org.json.JSONObject

object OptimizeJudge {
    const val HELPED = "helped"
    const val NO_CHANGE = "no_change"
    const val WORSE = "worse"
    const val CRASH = "crash"
    const val UNDO = "undo"
    const val DID_NOT_TAKE = "did_not_take"
    const val NOT_COMPARABLE = "not_comparable"
    const val APPLY_FAILED = "apply_failed"

    /** A relative rung found every key already at or below its target and wrote nothing. */
    const val NO_OP = "no_op"

    /** Why a session was judged the way it was; recorded as judge_reason in the outcome and the tried list. */
    const val REASON_FPS = "fps"
    const val REASON_MIN_WINDOW = "min_window"
    const val REASON_THROTTLE = "throttle"
    const val REASON_STUTTER = "stutter"
    const val REASON_GPU_LOAD = "gpu_load"
    const val REASON_CLOCK = "clock"

    /** GPU work per frame went down. */
    const val REASON_GPU_WORK = "gpu_work"
    const val REASON_TARGET_MET = "target_met"

    /** The device ran cooler. */
    const val REASON_TEMP = "temp"
    const val REASON_NONE = "none"

    /** Too few comparable windows were left once the menu-like ones of a GPU-bound game were taken out. */
    const val REASON_MENU_WINDOWS = "menu_windows"

    const val CRASH_GAMEPLAY_SEC = 120

    /** Gameplay a session needs to be judged or to serve as a baseline; the same minimum a card needs. */
    const val COMPARABLE_GAMEPLAY_SEC = OptimizeCards.MIN_GAMEPLAY_SEC

    /** A self-exit on a fatal fingerprint before this much gameplay counts as a crash. */
    const val FATAL_END_GAMEPLAY_SEC = 600
    const val MAX_SHORT_SESSIONS = 3

    /** Seconds without a frame before the user's exit, with the game on screen and not suspended, that mean it froze. */
    const val FROZEN_TAIL_SEC = 30

    private const val FPS_HELPED_RATIO = 1.08
    private const val FPS_REGRESSION_RATIO = 0.95
    private const val STUTTER_RELATIVE = 0.30
    private const val STUTTER_ABSOLUTE = 0.005
    private const val THROTTLE_LATER_SEC = 180
    private const val CAP_TEMP_DROP_C = 3
    private const val EPS = 1e-9

    /** Offline fallback tuning; the server's judgement takes precedence. */
    private const val NEAR_CAP_SHARE = 0.9
    private const val MIN_WINDOW_FPS = 3
    private const val MIN_WINDOW_CAP_SHARE = 0.95
    private const val GPU_LOAD_POINTS = 10

    /** Offline fallback tuning. */
    private const val NEAR_CAP_FPS_HOLD = 0.98
    private const val GPU_CLOCK_UP_SHARE = 0.10

    /** Offline fallback tuning. */
    const val WORK_DROP_SHARE = 0.08
    const val WORK_FPS_HOLD = 0.98
    const val WORK_FPS_UP_SHARE = 0.05
    const val WORK_FPS_DOWN_SHARE = 0.05
    const val WORK_EQUAL_SHARE = 0.03

    private val CRASH_REASONS = setOf("guest_terminated", "force_close")

    /** Exits the game made on its own (every guest process gone), as opposed to the user leaving through the app. */
    private val SELF_EXITS = setOf("guest_terminated", "processes_exited")

    /** Crash fingerprint kinds that end a process: an unhandled exception, a failed assertion, a lost GPU device. */
    private val FATAL_KINDS = setOf("exception", "assert", "device_lost")

    /** Exit reasons for a trial session the app never judged: the app process died hard, or two launches went unjudged. */
    const val EXIT_APP_KILLED = "app_killed"
    const val EXIT_NO_EXIT = "no_exit"

    /** Unjudged trial launches after which the step counts as a crash. */
    const val MAX_UNJUDGED_LAUNCHES = 2

    /** Offline fallback tuning. */
    private const val GPU_BOUND_MEDIAN = 60.0
    private val GPU_CLASSES = setOf("gpu", "thermal_gpu")

    /** Offline fallback tuning. */
    private const val MENU_GPU_SHARE = 0.4
    private const val MENU_CAP_SHARE = 0.95
    const val MIN_COMPARABLE_WINDOWS = 2

    sealed class Decision {
        data object Crash : Decision()
        data class Short(val shortSessions: Int) : Decision()

        /** [judged] is the session as compared once menu-like windows were taken out, null when none were; [excludedWindows] their fps_by_5min indices. */
        data class Final(
            val result: String,
            val reason: String? = null,
            val judged: Metrics? = null,
            val excludedWindows: List<Int> = emptyList(),
        ) : Decision()
    }

    /** [after] with its menu-like windows taken out ([excluded], fps_by_5min indices) and how many gameplay windows are left. */
    data class Windows(val after: Metrics, val excluded: List<Int>, val left: Int)

    /** True when [baseline] was held back by the GPU, so a session that ran at the cap on an idle GPU was not playing. */
    fun gpuBound(baseline: Metrics): Boolean =
        baseline.cls in GPU_CLASSES || (median(gameplayWindows(baseline.gpuBy5Min)) ?: 0.0) >= GPU_BOUND_MEDIAN - EPS

    /** The windows of [after] that compare with [baseline]; null when the filter does not apply. */
    fun comparableWindows(baseline: Metrics, after: Metrics, fpsCap: Double?): Windows? {
        if (fpsCap == null || fpsCap <= 0 || !gpuBound(baseline)) return null
        val baseGpu = median(gameplayWindows(baseline.gpuBy5Min))?.takeIf { it > 0 } ?: return null
        val fps = after.fpsBy5Min
        val gpu = after.gpuBy5Min
        if (fps.isEmpty() || gpu.size != fps.size) return null
        val first = if (fps.size >= 3) 1 else 0
        val gameplay = first until fps.size
        val excluded = gameplay.filter { i -> gpu[i] < MENU_GPU_SHARE * baseGpu - EPS && fps[i] >= MENU_CAP_SHARE * fpsCap - EPS }
        if (excluded.isEmpty()) return Windows(after, emptyList(), gameplay.count())
        val keep = (0 until fps.size).filter { it !in excluded }
        val keptFps = gameplay.filter { it !in excluded }.map { fps[it] }
        val clock = after.gpuClockBy5Min
        val judged = after.copy(
            steadyFps = if (keptFps.isEmpty()) after.steadyFps else keptFps.average(),
            fpsBy5Min = keep.map { fps[it] },
            gpuBy5Min = keep.map { gpu[it] },
            gpuClockBy5Min = if (clock.size == fps.size) keep.map { clock[it] } else clock,
            gpuWorkBy5Min = if (after.gpuWorkBy5Min.size == fps.size) keep.map { after.gpuWorkBy5Min[it] } else after.gpuWorkBy5Min,
        )
        return Windows(judged, excluded, keptFps.size)
    }

    data class Verdict(val result: String, val reason: String)

    /**
     * The fallback baseline when the ladder holds none (ProfileLadder.baselineOverride): the newest run of
     * [runsNewestFirst] long enough to compare; short runs are skipped.
     */
    fun baselineOf(runsNewestFirst: Sequence<JSONObject>): Metrics? =
        runsNewestFirst.map { Metrics.fromRun(it) }.firstOrNull { it.gameplaySec >= COMPARABLE_GAMEPLAY_SEC }

    /** Whether the session counts as a crash. */
    fun isCrash(m: Metrics): Boolean =
        (m.gameplaySec < CRASH_GAMEPLAY_SEC && (m.exitReason in CRASH_REASONS || !m.windowSeen)) || fatalEnd(m) || frozen(m)

    /**
     * The game reached gameplay and then stopped presenting frames until the session ended: its last
     * [FROZEN_TAIL_SEC] seconds had no frame and an idle GPU while the game was not suspended, its main window was
     * still mapped, and the session did not end by the game exiting on its own.
     */
    fun frozen(m: Metrics): Boolean {
        val stopped = m.framesStoppedSec ?: return false
        return stopped >= FROZEN_TAIL_SEC && m.gameplaySec > 0 && m.windowSeen && m.mainWindowMapped != false &&
            m.exitReason != null && m.exitReason !in SELF_EXITS
    }

    /** The session ended by itself on a fatal fingerprint before it was long enough to judge. */
    fun fatalEnd(m: Metrics): Boolean =
        m.gameplaySec < FATAL_END_GAMEPLAY_SEC && m.exitReason in SELF_EXITS && fatalFingerprint(m.crashKind, m.crashCode)

    /** A fingerprint that ends the process; an unimplemented instruction or function does, an unimplemented syscall does not. */
    fun fatalFingerprint(kind: String?, code: String?): Boolean =
        kind in FATAL_KINDS || (kind == "unimplemented" && code != null && !code.startsWith("syscall_"))

    /** What the next launch makes of a trial session that was started but never judged (the app did not run afterExit). */
    sealed class Lost {
        /** The step is judged a crash; [after] stands in for the session. */
        data class Crash(val after: Metrics) : Lost()

        /** Not enough to blame the step: launch it once more, counting [unjudged] launches. */
        data class Again(val unjudged: Int) : Lost()
    }

    /**
     * [run] is the session's run record if it was written (exit ran, judging did not); [hardExitReason] the Android exit
     * reason of the app process if it died hard since the session started. A run that crashed is a crash; a run that
     * did not leaves the step on trial; with no run a hard app exit is a crash, and so is the second unjudged launch.
     */
    fun lostSession(unjudgedBefore: Int, run: Metrics?, hardExitReason: String?): Lost = when {
        run != null && isCrash(run) -> Lost.Crash(run)
        run != null -> Lost.Again(0)
        hardExitReason != null -> Lost.Crash(Metrics(gameplaySec = 0, exitReason = EXIT_APP_KILLED, windowSeen = false))
        unjudgedBefore + 1 >= MAX_UNJUDGED_LAUNCHES -> Lost.Crash(Metrics(gameplaySec = 0, exitReason = EXIT_NO_EXIT, windowSeen = false))
        else -> Lost.Again(unjudgedBefore + 1)
    }

    /** [fpsCap] is the frame rate the game is held to; null judges without it. */
    fun judge(after: Metrics, baseline: Metrics?, isFpsCap: Boolean, shortSessions: Int, fpsCap: Double? = null): Decision {
        if (isCrash(after)) {
            val sameAsBefore = baseline != null && isCrash(baseline) && baseline.exitReason == after.exitReason &&
                baseline.crashHash == after.crashHash
            if (!sameAsBefore) return Decision.Crash
        }
        if (after.gameplaySec < COMPARABLE_GAMEPLAY_SEC || baseline == null || baseline.gameplaySec < COMPARABLE_GAMEPLAY_SEC) {
            val count = shortSessions + 1
            return if (count >= MAX_SHORT_SESSIONS) Decision.Final(NOT_COMPARABLE) else Decision.Short(count)
        }
        val windows = comparableWindows(baseline, after, fpsCap)
        val excluded = windows?.excluded.orEmpty()
        if (windows != null && excluded.isNotEmpty() && windows.left < MIN_COMPARABLE_WINDOWS) {
            return Decision.Final(NOT_COMPARABLE, REASON_MENU_WINDOWS, windows.after, excluded)
        }
        val judged = windows?.after?.takeIf { excluded.isNotEmpty() }
        val v = verdict(baseline, judged ?: after, isFpsCap, fpsCap)
        return Decision.Final(v.result, v.reason, judged, excluded)
    }

    fun compare(before: Metrics, after: Metrics, isFpsCap: Boolean, fpsCap: Double? = null): String =
        verdict(before, after, isFpsCap, fpsCap).result

    /** The five-minute windows that count. */
    private fun gameplayWindows(values: List<Int>): List<Int> = if (values.size >= 3) values.drop(1) else values

    private fun median(values: List<Int>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    /** Offline fallback judge on GPU work per frame; null when it does not apply. */
    private fun workVerdict(
        before: Metrics,
        after: Metrics,
        fpsRatio: Double?,
        stutterUp: Boolean,
        stutterDown: Boolean,
        throttleLater: Boolean,
    ): Verdict? {
        if (before.cls !in GPU_CLASSES || fpsRatio == null) return null
        val wb = before.workPerFrame()?.takeIf { it > 0 } ?: return null
        val wa = after.workPerFrame()?.takeIf { it > 0 } ?: return null
        val workRatio = wa / wb
        val equalOrLower = workRatio <= 1 + WORK_EQUAL_SHARE + EPS
        val equalOrHigher = workRatio >= 1 - WORK_EQUAL_SHARE - EPS
        if (stutterUp) return Verdict(WORSE, REASON_STUTTER)
        if (workRatio <= 1 - WORK_DROP_SHARE + EPS && fpsRatio >= WORK_FPS_HOLD - EPS) return Verdict(HELPED, REASON_GPU_WORK)
        if (fpsRatio >= 1 + WORK_FPS_UP_SHARE - EPS && equalOrLower) return Verdict(HELPED, REASON_FPS)
        if (fpsRatio < 1 - WORK_FPS_DOWN_SHARE - EPS && equalOrHigher) return Verdict(WORSE, REASON_FPS)
        if (stutterDown) return Verdict(HELPED, REASON_STUTTER)
        if (throttleLater) return Verdict(HELPED, REASON_THROTTLE)
        return Verdict(NO_CHANGE, REASON_NONE)
    }

    /** Whether the device ran cooler. */
    fun cooler(before: Metrics, after: Metrics): Boolean {
        fun dropped(b: Int?, a: Int?) = b != null && a != null && b - a >= CAP_TEMP_DROP_C
        val statusLower = before.thermalPeak != null && after.thermalPeak != null && after.thermalPeak < before.thermalPeak
        return statusLower && (dropped(before.cpuTempMax, after.cpuTempMax) || dropped(before.gpuTempMax, after.gpuTempMax))
    }

    /** Offline fallback judge for a step; cap steps are judged on their own signals. */
    fun verdict(before: Metrics, after: Metrics, isFpsCap: Boolean, fpsCap: Double? = null): Verdict {
        val fpsBefore = before.steadyFps
        val fpsAfter = after.steadyFps
        val fpsRatio = if (fpsBefore != null && fpsAfter != null && fpsBefore > 0 && fpsAfter > 0) fpsAfter / fpsBefore else null
        val fpsUp = fpsRatio != null && fpsRatio >= FPS_HELPED_RATIO - EPS
        val fpsDown = fpsRatio != null && fpsRatio <= FPS_REGRESSION_RATIO + EPS

        val sb = before.stutterShare
        val sa = after.stutterShare
        val stutterDown = sb != null && sa != null && sb > 0 &&
            sb - sa >= STUTTER_ABSOLUTE - EPS && (sb - sa) / sb >= STUTTER_RELATIVE - EPS
        val stutterUp = sb != null && sa != null &&
            sa - sb >= STUTTER_ABSOLUTE - EPS && (sb <= 0 || (sa - sb) / sb >= STUTTER_RELATIVE - EPS)

        val tb = before.throttleOnsetSec
        val ta = after.throttleOnsetSec
        val throttleLater = when {
            tb != null && ta != null -> ta - tb >= THROTTLE_LATER_SEC
            tb != null -> after.gameplaySec >= tb + THROTTLE_LATER_SEC
            else -> false
        }

        val throttleEarlier = tb != null && ta != null && tb - ta >= THROTTLE_LATER_SEC

        // A cap step has its own judge.
        if (isFpsCap) {
            if (throttleLater) return Verdict(HELPED, REASON_THROTTLE)
            if (stutterDown) return Verdict(HELPED, REASON_STUTTER)
            if (cooler(before, after)) return Verdict(HELPED, REASON_TEMP)
            return Verdict(NO_CHANGE, REASON_NONE)
        }

        workVerdict(before, after, fpsRatio, stutterUp, stutterDown, throttleLater)?.let { return it }

        val nearCap = !isFpsCap && fpsCap != null && fpsCap > 0 && fpsBefore != null && fpsBefore >= NEAR_CAP_SHARE * fpsCap - EPS
        if (!nearCap) {
            if (fpsDown && !isFpsCap) return Verdict(WORSE, REASON_FPS)
            if (stutterUp) return Verdict(WORSE, REASON_STUTTER)
            if (fpsUp) return Verdict(HELPED, REASON_FPS)
            if (stutterDown) return Verdict(HELPED, REASON_STUTTER)
            if (throttleLater) return Verdict(HELPED, REASON_THROTTLE)
            return Verdict(NO_CHANGE, REASON_NONE)
        }

        val cap = fpsCap!!
        val minBefore = gameplayWindows(before.fpsBy5Min).minOrNull()
        val minAfter = gameplayWindows(after.fpsBy5Min).minOrNull()
        val minUp = minBefore != null && minAfter != null &&
            (minAfter - minBefore >= MIN_WINDOW_FPS || (minBefore < MIN_WINDOW_CAP_SHARE * cap && minAfter >= MIN_WINDOW_CAP_SHARE * cap - EPS))
        val minDown = minBefore != null && minAfter != null && minBefore - minAfter >= MIN_WINDOW_FPS
        val gpuBefore = median(gameplayWindows(before.gpuBy5Min))
        val gpuAfter = median(gameplayWindows(after.gpuBy5Min))
        val fpsHeld = fpsAfter != null && fpsAfter >= NEAR_CAP_FPS_HOLD * fpsBefore!! - EPS
        val gpuDown = gpuBefore != null && gpuAfter != null && fpsHeld && gpuBefore - gpuAfter >= GPU_LOAD_POINTS - EPS
        val clockBefore = median(gameplayWindows(before.gpuClockBy5Min))
        val clockAfter = median(gameplayWindows(after.gpuClockBy5Min))
        val clockUp = clockBefore != null && clockAfter != null && clockBefore > 0 && fpsHeld &&
            clockAfter >= (1 + GPU_CLOCK_UP_SHARE) * clockBefore - EPS

        if (fpsDown) return Verdict(WORSE, REASON_FPS)
        if (minDown) return Verdict(WORSE, REASON_MIN_WINDOW)
        if (stutterUp) return Verdict(WORSE, REASON_STUTTER)
        if (throttleEarlier) return Verdict(WORSE, REASON_THROTTLE)
        if (fpsUp) return Verdict(HELPED, REASON_FPS)
        if (minUp) return Verdict(HELPED, REASON_MIN_WINDOW)
        if (throttleLater) return Verdict(HELPED, REASON_THROTTLE)
        if (stutterDown) return Verdict(HELPED, REASON_STUTTER)
        if (gpuDown) return Verdict(HELPED, REASON_GPU_LOAD)
        if (clockUp) return Verdict(HELPED, REASON_CLOCK)
        return Verdict(NO_CHANGE, REASON_NONE)
    }

    private val SCREEN_SIZE = Regex("^\\s*([0-9]{2,5})\\s*x\\s*([0-9]{2,5})\\s*$", RegexOption.IGNORE_CASE)
    private const val SCREEN_SIZE_KEY = "screenSize"
    const val GPU_WORK_SCALING_MIN = -1.0
    const val GPU_WORK_SCALING_MAX = 2.0

    /** Pixels of a "WxH" screen size; null when it is not one. */
    fun screenPixels(size: String?): Long? {
        val m = size?.let { SCREEN_SIZE.matchEntire(it) } ?: return null
        val pixels = m.groupValues[1].toLong() * m.groupValues[2].toLong()
        return pixels.takeIf { it > 0 }
    }

    /**
     * The screen size a resolution step changed, as (before, after): the value its apply record kept and the value
     * the rung set. Null when the step does not set screenSize.
     */
    fun resolutionChange(rung: Rung, applyRecords: List<JSONObject>): Pair<String, String>? {
        val after = rung.levers.filterIsInstance<Lever.ContainerSet>()
            .flatMap { it.set }.lastOrNull { it.first == SCREEN_SIZE_KEY }?.second ?: return null
        val before = applyRecords.filter { it.optString("type") == Lever.TYPE_CONTAINER }
            .flatMap { it.optJSONArray("before").objects() }
            .lastOrNull { it.optString("key") == SCREEN_SIZE_KEY }
            ?.optStringOrNull("value") ?: return null
        return before to after
    }

    /** How GPU work per frame followed the pixels of a resolution step; null when unknown. */
    fun gpuWorkScaling(workBefore: Double?, workAfter: Double?, pixelsBefore: Long?, pixelsAfter: Long?): Double? {
        if (workBefore == null || workAfter == null || pixelsBefore == null || pixelsAfter == null) return null
        if (workBefore <= 0 || workAfter <= 0 || pixelsBefore <= 0 || pixelsAfter <= 0 || pixelsBefore == pixelsAfter) return null
        val work = (workBefore - workAfter) / workBefore
        val pixels = (pixelsBefore - pixelsAfter).toDouble() / pixelsBefore
        val ratio = work / pixels
        if (ratio.isNaN() || ratio.isInfinite()) return null
        return Math.round(ratio.coerceIn(GPU_WORK_SCALING_MIN, GPU_WORK_SCALING_MAX) * 1000) / 1000.0
    }

    /** gpu_work_scaling for a judged resolution step; null for any other step or without GPU work on both sides. */
    fun gpuWorkScaling(rung: Rung, applyRecords: List<JSONObject>, before: Metrics?, after: Metrics): Double? {
        val (sizeBefore, sizeAfter) = resolutionChange(rung, applyRecords) ?: return null
        return gpuWorkScaling(before?.workPerFrame(), after.workPerFrame(), screenPixels(sizeBefore), screenPixels(sizeAfter))
    }
}
