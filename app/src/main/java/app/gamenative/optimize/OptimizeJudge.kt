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
    const val REASON_TARGET_MET = "target_met"
    const val REASON_NONE = "none"

    const val CRASH_GAMEPLAY_SEC = 120
    const val COMPARABLE_GAMEPLAY_SEC = 600
    const val MAX_SHORT_SESSIONS = 3

    private const val FPS_HELPED_RATIO = 1.08
    private const val FPS_REGRESSION_RATIO = 0.95
    private const val STUTTER_RELATIVE = 0.30
    private const val STUTTER_ABSOLUTE = 0.005
    private const val THROTTLE_LATER_SEC = 180
    private const val EPS = 1e-9

    /** At or above this share of the fps cap the average cannot show a gain; the judge reads the other signals. */
    private const val NEAR_CAP_SHARE = 0.9
    private const val MIN_WINDOW_FPS = 3
    private const val MIN_WINDOW_CAP_SHARE = 0.95
    private const val GPU_LOAD_POINTS = 10

    private val CRASH_REASONS = setOf("guest_terminated", "force_close")

    sealed class Decision {
        data object Crash : Decision()
        data class Short(val shortSessions: Int) : Decision()
        data class Final(val result: String, val reason: String? = null) : Decision()
    }

    data class Verdict(val result: String, val reason: String)

    /** The baseline to judge against: the newest run, of [runsNewestFirst], long enough to compare; short runs are skipped. */
    fun baselineOf(runsNewestFirst: Sequence<JSONObject>): Metrics? =
        runsNewestFirst.map { Metrics.fromRun(it) }.firstOrNull { it.gameplaySec >= COMPARABLE_GAMEPLAY_SEC }

    fun isCrash(m: Metrics): Boolean =
        m.gameplaySec < CRASH_GAMEPLAY_SEC && (m.exitReason in CRASH_REASONS || !m.windowSeen)

    /** [fpsCap] is the frame rate the game is held to (the limiter, or min(refresh, 60)); null judges without it. */
    fun judge(after: Metrics, baseline: Metrics?, isFpsCap: Boolean, shortSessions: Int, fpsCap: Double? = null): Decision {
        if (isCrash(after)) {
            val sameAsBefore = baseline != null && isCrash(baseline) && baseline.exitReason == after.exitReason
            if (!sameAsBefore) return Decision.Crash
        }
        if (after.gameplaySec < COMPARABLE_GAMEPLAY_SEC || baseline == null || baseline.gameplaySec < COMPARABLE_GAMEPLAY_SEC) {
            val count = shortSessions + 1
            return if (count >= MAX_SHORT_SESSIONS) Decision.Final(NOT_COMPARABLE) else Decision.Short(count)
        }
        val v = verdict(baseline, after, isFpsCap, fpsCap)
        return Decision.Final(v.result, v.reason)
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
        val gpuDown = gpuBefore != null && gpuAfter != null && fpsAfter != null && fpsAfter >= fpsBefore!! - EPS &&
            gpuBefore - gpuAfter >= GPU_LOAD_POINTS - EPS

        if (fpsDown) return Verdict(WORSE, REASON_FPS)
        if (minDown) return Verdict(WORSE, REASON_MIN_WINDOW)
        if (stutterUp) return Verdict(WORSE, REASON_STUTTER)
        if (throttleEarlier) return Verdict(WORSE, REASON_THROTTLE)
        if (fpsUp) return Verdict(HELPED, REASON_FPS)
        if (minUp) return Verdict(HELPED, REASON_MIN_WINDOW)
        if (throttleLater) return Verdict(HELPED, REASON_THROTTLE)
        if (stutterDown) return Verdict(HELPED, REASON_STUTTER)
        if (gpuDown) return Verdict(HELPED, REASON_GPU_LOAD)
        return Verdict(NO_CHANGE, REASON_NONE)
    }
}
