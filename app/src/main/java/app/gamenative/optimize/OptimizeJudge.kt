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

    const val CRASH_GAMEPLAY_SEC = 120
    const val COMPARABLE_GAMEPLAY_SEC = 600
    const val MAX_SHORT_SESSIONS = 3

    private const val FPS_HELPED_RATIO = 1.08
    private const val FPS_REGRESSION_RATIO = 0.95
    private const val STUTTER_RELATIVE = 0.30
    private const val STUTTER_ABSOLUTE = 0.005
    private const val THROTTLE_LATER_SEC = 180
    private const val EPS = 1e-9

    private val CRASH_REASONS = setOf("guest_terminated", "force_close")

    sealed class Decision {
        data object Crash : Decision()
        data class Short(val shortSessions: Int) : Decision()
        data class Final(val result: String) : Decision()
    }

    /** The baseline to judge against: the newest run, of [runsNewestFirst], long enough to compare; short runs are skipped. */
    fun baselineOf(runsNewestFirst: Sequence<JSONObject>): Metrics? =
        runsNewestFirst.map { Metrics.fromRun(it) }.firstOrNull { it.gameplaySec >= COMPARABLE_GAMEPLAY_SEC }

    fun isCrash(m: Metrics): Boolean =
        m.gameplaySec < CRASH_GAMEPLAY_SEC && (m.exitReason in CRASH_REASONS || !m.windowSeen)

    fun judge(after: Metrics, baseline: Metrics?, isFpsCap: Boolean, shortSessions: Int): Decision {
        if (isCrash(after)) {
            val sameAsBefore = baseline != null && isCrash(baseline) && baseline.exitReason == after.exitReason
            if (!sameAsBefore) return Decision.Crash
        }
        if (after.gameplaySec < COMPARABLE_GAMEPLAY_SEC || baseline == null || baseline.gameplaySec < COMPARABLE_GAMEPLAY_SEC) {
            val count = shortSessions + 1
            return if (count >= MAX_SHORT_SESSIONS) Decision.Final(NOT_COMPARABLE) else Decision.Short(count)
        }
        return Decision.Final(compare(baseline, after, isFpsCap))
    }

    fun compare(before: Metrics, after: Metrics, isFpsCap: Boolean): String {
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

        if ((fpsDown && !isFpsCap) || stutterUp) return WORSE
        if (fpsUp || stutterDown || throttleLater) return HELPED
        return NO_CHANGE
    }
}
