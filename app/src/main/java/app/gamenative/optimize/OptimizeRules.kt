package app.gamenative.optimize

import kotlin.math.roundToInt

/** What has to come off and what has to go back on so that only [target]'s kept changes are on the game. */
data class SwitchPlan(
    val revertPending: List<OptimizeProfile>,
    val revertKept: List<Pair<OptimizeProfile, String>>,
    val reapply: List<String>,
) {
    val isEmpty: Boolean get() = revertPending.isEmpty() && revertKept.isEmpty() && reapply.isEmpty()

    companion object {
        /** [target] null means Off: everything comes off. The target's own pending change stays. */
        fun of(state: OptimizeLocalState, target: OptimizeProfile?): SwitchPlan {
            val others = state.profiles.filterKeys { it != target }
            return SwitchPlan(
                revertPending = others.filterValues { it.pending != null }.keys.sortedBy { it.ordinal },
                revertKept = others.entries.sortedBy { it.key.ordinal }.flatMap { (profile, ladder) ->
                    ladder.kept.filter { it.applied }.asReversed().map { profile to it.rungId }
                },
                reapply = target?.let { state.profiles[it] }?.kept.orEmpty().filter { !it.applied }.map { it.rungId },
            )
        }
    }
}

object OptimizeTarget {
    const val BALANCED_MIN_FPS = 30.0
    const val BALANCED_MAX_STUTTER = 0.02
    const val STABILITY_MAX_STUTTER = 0.01
    const val POWER_MIN_GAMEPLAY_SEC = 1200
    const val PERFORMANCE_FPS_SHARE = 0.9
    const val PERFORMANCE_FPS_CEILING = 60.0

    private val LIMITING_CLASSES = setOf("gpu", "cpu_single", "cpu_multi", "thermal_gpu")

    /** True when the session after a step already reached what the profile asks for; inconclusive sessions are false. */
    fun met(profile: OptimizeProfile, after: Metrics, refreshHz: Double?, fpsCap: Int): Boolean {
        val fps = after.steadyFps
        val stutter = after.stutterShare
        return when (profile) {
            OptimizeProfile.PERFORMANCE -> {
                if (after.confident == true && after.cls in LIMITING_CLASSES) return false
                if (fps == null) return false
                val ceiling = minOf(refreshHz?.takeIf { it > 0 } ?: PERFORMANCE_FPS_CEILING, PERFORMANCE_FPS_CEILING)
                fps >= PERFORMANCE_FPS_SHARE * ceiling || (fpsCap > 0 && fps >= PERFORMANCE_FPS_SHARE * fpsCap)
            }
            OptimizeProfile.BALANCED ->
                fps != null && stutter != null && fps >= BALANCED_MIN_FPS && stutter < BALANCED_MAX_STUTTER && after.throttleOnsetSec == null
            OptimizeProfile.STABILITY -> stutter != null && stutter < STABILITY_MAX_STUTTER
            OptimizeProfile.POWER -> after.gameplaySec >= POWER_MIN_GAMEPLAY_SEC && after.throttleOnsetSec == null
            OptimizeProfile.OFF -> false
        }
    }
}

object OptimizeCards {
    const val MIN_GAMEPLAY_SEC = 300
    const val SUPPRESS_MS = 7L * 24 * 60 * 60 * 1000

    const val SYNC_SPIN = "sync_spin"
    val CLASSES = setOf("gpu", "thermal_gpu", "cpu_single", "cpu_multi", "memory", "present_locked", SYNC_SPIN)

    const val ACTION_OPTIMIZE = "optimize"
    const val ACTION_NOT_NOW = "not_now"
    const val ACTION_NEVER = "never"
    const val ACTION_DISMISSED = "dismissed"
    const val ACTION_KEEP_GOING = "keep_going"
    const val ACTION_HAPPY = "happy"
    const val ACTION_UNDO = "undo"
    const val ACTION_STOP = "stop"
    const val ACTION_OK = "ok"
    const val ACTION_KEEP_CHANGES = "keep_changes"
    const val ACTION_UNDO_ALL = "undo_all"
    const val ACTION_MEMBERSHIP = "membership"

    /** Whether the session that just ended should leave the first card for a game nobody set up yet. */
    fun offerFirst(state: OptimizeLocalState, session: Metrics, nowMillis: Long): Boolean {
        if (state.never || state.activeProfile != null || state.profiles.isNotEmpty() || state.card != null) return false
        if (session.confident != true || session.gameplaySec < MIN_GAMEPLAY_SEC || session.cls !in CLASSES) return false
        val suppression = state.suppression ?: return true
        if (nowMillis >= suppression.untilMillis) return true
        val reference = suppression.metrics ?: return false
        return OptimizeJudge.compare(reference, session, isFpsCap = false) == OptimizeJudge.WORSE
    }

    const val ENTITLEMENT_RECHECK_MS = 24L * 60 * 60 * 1000

    /** After the trial ran out, ask again only once the user is a member or a day has passed. */
    fun shouldAskAfterTrial(ladder: ProfileLadder, entitlement: Entitlement?, checkedAtMillis: Long, isMember: Boolean, nowMillis: Long): Boolean {
        if (ladder.lastStatus != NextRungResponse.STATUS_ENTITLEMENT_REQUIRED) return true
        if (entitlement?.state != Entitlement.NONE || isMember) return true
        return nowMillis - checkedAtMillis >= ENTITLEMENT_RECHECK_MS
    }

    fun notNow(card: OptimizeCard, nowMillis: Long): CardSuppression = CardSuppression(nowMillis + SUPPRESS_MS, card.metrics)

    fun fpsGainPct(before: Metrics?, after: Metrics): Int? {
        val b = before?.steadyFps ?: return null
        val a = after.steadyFps ?: return null
        if (b <= 0 || a <= b) return null
        return ((a / b - 1) * 100).roundToInt().takeIf { it >= 1 }
    }

    fun stutterDropPct(before: Metrics?, after: Metrics): Int? {
        val b = before?.stutterShare ?: return null
        val a = after.stutterShare ?: return null
        if (b <= 0 || a >= b) return null
        return ((b - a) / b * 100).roundToInt().takeIf { it >= 1 }
    }

    /** Which outcome a helped card names: the rung's own outcome when it says, otherwise what the profile is for. */
    fun helpedOutcome(rungOutcome: String?, profile: OptimizeProfile): String = when (rungOutcome) {
        OptimizeCard.OUTCOME_FPS -> OptimizeCard.OUTCOME_FPS
        OptimizeCard.OUTCOME_STUTTER -> OptimizeCard.OUTCOME_STUTTER
        OptimizeCard.OUTCOME_STABLE -> OptimizeCard.OUTCOME_STABLE
        else -> when (profile) {
            OptimizeProfile.PERFORMANCE -> OptimizeCard.OUTCOME_FPS
            OptimizeProfile.BALANCED, OptimizeProfile.STABILITY -> OptimizeCard.OUTCOME_STUTTER
            OptimizeProfile.POWER, OptimizeProfile.OFF -> OptimizeCard.OUTCOME_STABLE
        }
    }
}
