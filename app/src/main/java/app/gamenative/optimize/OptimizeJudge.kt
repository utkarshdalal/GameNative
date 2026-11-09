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

    /** A GPU-bound step took GPU work off each frame at held fps (see [workVerdict]). */
    const val REASON_GPU_WORK = "gpu_work"
    const val REASON_TARGET_MET = "target_met"

    /** A cap ran the device cooler: the hottest CPU or GPU reading down 3 °C or more with a lower peak thermal status. */
    const val REASON_TEMP = "temp"
    const val REASON_NONE = "none"

    /** Too few comparable windows were left once the menu-like ones of a GPU-bound game were taken out. */
    const val REASON_MENU_WINDOWS = "menu_windows"

    const val CRASH_GAMEPLAY_SEC = 120
    const val COMPARABLE_GAMEPLAY_SEC = 600
    const val MAX_SHORT_SESSIONS = 3

    private const val FPS_HELPED_RATIO = 1.08
    private const val FPS_REGRESSION_RATIO = 0.95
    private const val STUTTER_RELATIVE = 0.30
    private const val STUTTER_ABSOLUTE = 0.005
    private const val THROTTLE_LATER_SEC = 180
    private const val CAP_TEMP_DROP_C = 3
    private const val EPS = 1e-9

    /** At or above this share of the fps cap the average cannot show a gain; the judge reads the other signals. */
    private const val NEAR_CAP_SHARE = 0.9
    private const val MIN_WINDOW_FPS = 3
    private const val MIN_WINDOW_CAP_SHARE = 0.95
    private const val GPU_LOAD_POINTS = 10

    /** Near the cap, lower GPU load or a higher GPU clock counts while fps stays within this share of before. */
    private const val NEAR_CAP_FPS_HOLD = 0.98
    private const val GPU_CLOCK_UP_SHARE = 0.10

    /**
     * GPU work per frame rules for a gpu or thermal_gpu baseline (see [workVerdict]). Equal work is within
     * [WORK_EQUAL_SHARE] of before, the run-to-run spread of the same workload.
     */
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

    /** Unjudged trial launches, with no hard app exit recorded, after which the step counts as a crash. */
    const val MAX_UNJUDGED_LAUNCHES = 2

    /** A GPU-bound baseline: a gpu or thermal_gpu verdict, or a gameplay GPU median at or above this. */
    private const val GPU_BOUND_MEDIAN = 60.0
    private val GPU_CLASSES = setOf("gpu", "thermal_gpu")

    /** A window under this share of the baseline's GPU median while at 0.95 x the cap is a menu or map, not gameplay. */
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

    /**
     * The windows of [after] that compare with a GPU-bound [baseline]: a gameplay window with GPU busy under 0.4 x the
     * baseline's gameplay GPU median and fps at 0.95 x [fpsCap] or more (a menu or the world map) is taken out of the
     * steady fps, the min window and the GPU and clock medians. Null when the filter does not apply: the baseline is
     * not GPU-bound, there is no cap or GPU median, or the session's fps and GPU windows do not line up.
     */
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

    /** The baseline to judge against: the newest run, of [runsNewestFirst], long enough to compare; short runs are skipped. */
    fun baselineOf(runsNewestFirst: Sequence<JSONObject>): Metrics? =
        runsNewestFirst.map { Metrics.fromRun(it) }.firstOrNull { it.gameplaySec >= COMPARABLE_GAMEPLAY_SEC }

    /**
     * A crash: under [CRASH_GAMEPLAY_SEC] of gameplay with a crash exit or no game window ever seen, or under
     * [COMPARABLE_GAMEPLAY_SEC] ending on its own with a fatal crash fingerprint (a session that died before it could
     * be judged, which would otherwise count as short and in the end be kept as not comparable).
     */
    fun isCrash(m: Metrics): Boolean =
        (m.gameplaySec < CRASH_GAMEPLAY_SEC && (m.exitReason in CRASH_REASONS || !m.windowSeen)) || fatalEnd(m)

    /** The session ended by itself on a fatal fingerprint before it was long enough to judge. */
    fun fatalEnd(m: Metrics): Boolean =
        m.gameplaySec < COMPARABLE_GAMEPLAY_SEC && m.exitReason in SELF_EXITS && fatalFingerprint(m.crashKind, m.crashCode)

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

    /** [fpsCap] is the frame rate the game is held to (the limiter, or min(refresh, 60)); null judges without it. */
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

    /** The five-minute windows that count; the first holds the loading screens once there are three or more. */
    private fun gameplayWindows(values: List<Int>): List<Int> = if (values.size >= 3) values.drop(1) else values

    private fun median(values: List<Int>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    /**
     * The judge for a GPU-bound baseline (class gpu or thermal_gpu) when both sessions carry GPU work per frame
     * (GPU busy 0-1 x GPU clock MHz / fps, the median of the gameplay windows left after menu-window dropping).
     * Thermal clock swings move fps by about 15 % between runs of the same workload, while work per frame stays
     * within a few percent, so fps alone cannot tell a step's effect from the device's temperature.
     *
     *   worse      stutter up (30 % and 0.005, as everywhere)
     *   helped     work per frame down 8 % or more while fps holds at 0.98 x before or better (gpu_work)
     *   helped     fps up 5 % or more at equal or lower work per frame (fps)
     *   worse      fps down more than 5 % at equal or higher work per frame (fps)
     *   helped     stutter down, or the throttle onset 180 s or more later
     *   no_change  anything else, including fps down at lower work (the device ran hotter, not the step)
     *
     * Equal work is within 3 % of before. Null when the rule does not apply; the fps-only rules judge then. Cap steps
     * never come here: they are judged by the cap rules only.
     */
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

    /** The hottest CPU or GPU reading fell [CAP_TEMP_DROP_C] °C or more and the peak thermal status is lower. */
    fun cooler(before: Metrics, after: Metrics): Boolean {
        fun dropped(b: Int?, a: Int?) = b != null && a != null && b - a >= CAP_TEMP_DROP_C
        val statusLower = before.thermalPeak != null && after.thermalPeak != null && after.thermalPeak < before.thermalPeak
        return statusLower && (dropped(before.cpuTempMax, after.cpuTempMax) || dropped(before.gpuTempMax, after.gpuTempMax))
    }

    /**
     * A cap rung ([isFpsCap]) is helped only when the throttle onset came 180 s or more later (or not at all, past the
     * baseline's onset + 180 s), stutter share fell 30 % and 0.005, or [cooler]; otherwise it is no change.
     */
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

        // A cap lowers fps by design and holds the target trivially; it is judged only by what a cap is for.
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
}
