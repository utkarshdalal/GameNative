package app.gamenative.optimize

import app.gamenative.utils.SessionVerdict
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

    /**
     * In the tried list: a settings rung that wrote nothing because its files are not where its paths lead. The outcome
     * is reported as [NO_OP] with apply.missing_files.
     */
    const val FILE_MISSING = "file_missing"

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

    /** Why an offered step was skipped: it repeats one this ladder already kept, tried or skipped. */
    const val REASON_REPEAT = "repeat"

    /** Why an offered cap was skipped: the player said no to its question. */
    const val REASON_DECLINED = "declined"

    /** Gameplay a session needs to be judged or to serve as a baseline: the gameplay from which the app asks the server. */
    const val COMPARABLE_GAMEPLAY_SEC = OptimizeServer.ASK_GAMEPLAY_SEC

    /**
     * Whole minutes of play, from the game's first frame, a session needs to be judged: the warm-up that does not count
     * as gameplay plus [COMPARABLE_GAMEPLAY_SEC], rounded up. What the player is told to play.
     */
    const val PLAY_MINUTES = (SessionVerdict.WARMUP_SEC + COMPARABLE_GAMEPLAY_SEC + 59) / 60

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

    private val GPU_CLASSES = setOf("gpu", "thermal_gpu")

    sealed class Decision {
        /** Too short to judge, or no frame rate recorded: no result, and the step stays on trial. */
        data object Short : Decision()

        /** Long enough, but the step has no session before it to compare with: it can never be judged. */
        data object NoBaseline : Decision()

        data class Final(val result: String, val reason: String? = null) : Decision()
    }

    data class Verdict(val result: String, val reason: String)

    /**
     * The fallback baseline when the ladder holds none (ProfileLadder.baselineOverride): the newest run of
     * [runsNewestFirst] long enough to compare; short runs and runs with a step on trial are skipped.
     */
    fun baselineOf(runsNewestFirst: Sequence<JSONObject>): Metrics? =
        runsNewestFirst.filter { it.optJSONObject("optimize_state")?.optStringOrNull("template_id") == null }
            .map { Metrics.fromRun(it) }.firstOrNull { it.gameplaySec >= COMPARABLE_GAMEPLAY_SEC }

    /**
     * [fpsCap] is the frame rate the game is held to; null judges without it. A session long enough to judge, with a
     * frame rate recorded, is compared whole with [baseline]; any other gives no result.
     */
    fun judge(after: Metrics, baseline: Metrics?, isFpsCap: Boolean, fpsCap: Double? = null): Decision {
        if (after.gameplaySec < COMPARABLE_GAMEPLAY_SEC || after.steadyFps == null) return Decision.Short
        if (baseline == null || baseline.gameplaySec < COMPARABLE_GAMEPLAY_SEC) return Decision.NoBaseline
        val v = verdict(baseline, after, isFpsCap, fpsCap)
        return Decision.Final(v.result, v.reason)
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
