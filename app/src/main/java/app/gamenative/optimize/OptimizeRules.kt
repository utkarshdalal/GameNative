package app.gamenative.optimize

import app.gamenative.ui.component.parseBooleanExtra
import app.gamenative.ui.component.parsePositiveFpsLimit
import kotlin.math.roundToInt
import org.json.JSONObject

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

object OptimizeLadders {
    private val RESUMABLE = setOf(LadderState.IDLE, LadderState.PAUSED, LadderState.DONE)

    /** A profile picked from the dialog, the options row or a card starts trying again when it was idle, paused or done. */
    fun picked(ladder: ProfileLadder): ProfileLadder =
        if (ladder.state in RESUMABLE) ladder.copy(state = LadderState.TRYING, noChangeStreak = 0) else ladder

    /** Continue is offered while the active ladder is paused or done. */
    fun canContinue(status: OptimizeStatus?): Boolean = status == OptimizeStatus.Paused || status == OptimizeStatus.Done

    enum class Tap { SWITCH, CONTINUE, NONE }

    /** Tapping another profile switches to it; tapping the active one continues its ladder when that is offered. */
    fun tap(active: OptimizeProfile, tapped: OptimizeProfile, status: OptimizeStatus?): Tap = when {
        tapped != active -> Tap.SWITCH
        canContinue(status) -> Tap.CONTINUE
        else -> Tap.NONE
    }
}

object OptimizeTarget {
    const val BALANCED_MIN_FPS = 30.0
    const val BALANCED_MAX_STUTTER = 0.02
    const val STABILITY_MAX_STUTTER = 0.01
    const val POWER_MIN_GAMEPLAY_SEC = 1200
    const val PERFORMANCE_FPS_SHARE = 0.97
    const val PERFORMANCE_WINDOW_SHARE = 0.95
    const val PERFORMANCE_FPS_CEILING = 60.0

    const val HOT_FPS_DECLINE = 3
    const val HOT_CLOCK_DROP_SHARE = 0.10

    /**
     * Heat held the session back: a confident thermal_gpu verdict, or a throttle onset that cost something. The
     * onset costs when fps falls 3 or more from the second window to the last, the GPU clock falls 10 % or more
     * from the first gameplay window to the last, or a gameplay window falls below 0.95 x the target. An onset
     * with no fps windows to clear it counts.
     */
    fun hot(m: Metrics, refreshHz: Double?, fpsCap: Int): Boolean {
        if (m.cls == "thermal_gpu" && m.confident == true) return true
        if (m.throttleOnsetSec == null) return false
        if (m.fpsBy5Min.isEmpty()) return true
        val fpsDeclines = m.fpsBy5Min.size >= 2 && m.fpsBy5Min[1] - m.fpsBy5Min.last() >= HOT_FPS_DECLINE
        val clocks = gameplayWindows(m.gpuClockBy5Min)
        val clockFalls = clocks.size >= 2 && clocks.first() > 0 && clocks.last() <= (1 - HOT_CLOCK_DROP_SHARE) * clocks.first()
        return fpsDeclines || clockFalls || dips(m, refreshHz, fpsCap)
    }

    /**
     * The frame rate Performance holds: min(refresh, 60), or the user's own limiter when it is lower. [fpsCap] must be
     * the user's limiter ([userFpsCap]), never a cap Optimize put on: holding that would meet the target trivially.
     */
    fun fpsTarget(refreshHz: Double?, fpsCap: Int): Double {
        val ceiling = minOf(refreshHz?.takeIf { it > 0 } ?: PERFORMANCE_FPS_CEILING, PERFORMANCE_FPS_CEILING)
        return if (fpsCap > 0) minOf(ceiling, fpsCap.toDouble()) else ceiling
    }

    /**
     * The fps limiter the user set: when a kept (applied) or trial step of [ladder] set the container fpsLimit, the value
     * the first of them found before it, from its lever record; otherwise [containerFps]. 0 is no limiter.
     */
    fun userFpsCap(ladder: ProfileLadder?, containerFps: Int, defaultFps: Int): Int {
        val records = ladder?.let { l -> l.kept.filter { it.applied }.flatMap { it.levers } + l.pending?.levers.orEmpty() }.orEmpty()
        val before = records.asSequence()
            .filter { it.optString("type") == Lever.TYPE_CONTAINER }
            .flatMap { it.optJSONArray("before").objects().asSequence() }
            .firstOrNull { it.optString("key") == "fpsLimit" } ?: return containerFps
        if (parseBooleanExtra(before.optString("enabled")) == false) return 0
        return parsePositiveFpsLimit(before.optString("target").trim()) ?: defaultFps
    }

    /** The five-minute windows that count; the first holds the loading screens once there are three or more. */
    private fun gameplayWindows(values: List<Int>): List<Int> = if (values.size >= 3) values.drop(1) else values

    /** A five-minute window fell below 0.95 x the target. */
    fun dips(m: Metrics, refreshHz: Double?, fpsCap: Int): Boolean {
        val target = fpsTarget(refreshHz, fpsCap)
        return gameplayWindows(m.fpsBy5Min).any { it < PERFORMANCE_WINDOW_SHARE * target }
    }

    /** Steady fps at 0.97 x the target and no five-minute window below 0.95 x it, heat aside. */
    fun holdsTarget(m: Metrics, refreshHz: Double?, fpsCap: Int): Boolean {
        val fps = m.steadyFps ?: return false
        return fps >= PERFORMANCE_FPS_SHARE * fpsTarget(refreshHz, fpsCap) && !dips(m, refreshHz, fpsCap)
    }

    /** True when the session after a step already reached what the profile asks for; inconclusive sessions are false. */
    fun met(profile: OptimizeProfile, after: Metrics, refreshHz: Double?, fpsCap: Int): Boolean {
        val fps = after.steadyFps
        val stutter = after.stutterShare
        val heat = hot(after, refreshHz, fpsCap)
        return when (profile) {
            OptimizeProfile.PERFORMANCE -> holdsTarget(after, refreshHz, fpsCap) && !heat
            OptimizeProfile.BALANCED ->
                fps != null && stutter != null && fps >= BALANCED_MIN_FPS && stutter < BALANCED_MAX_STUTTER && !heat
            OptimizeProfile.STABILITY -> stutter != null && stutter < STABILITY_MAX_STUTTER
            OptimizeProfile.POWER -> after.gameplaySec >= POWER_MIN_GAMEPLAY_SEC && !heat
            OptimizeProfile.OFF -> false
        }
    }

    /** What a judged session settles: the result recorded and kept or reverted by, why, and whether the ladder is done. */
    data class Settled(val result: String, val reason: String?, val done: Boolean)

    /**
     * Done implies keep: a compared session that meets the target keeps the step as helped (target_met) whatever the
     * judge said, so the ladder never ends while the step is still flagged to come off. Otherwise the judge stands.
     * A cap step ([isFpsCap]) never meets the target: the judge's cap rules alone keep or revert it.
     */
    fun settle(
        profile: OptimizeProfile,
        result: String,
        reason: String?,
        after: Metrics,
        refreshHz: Double?,
        fpsCap: Int,
        isFpsCap: Boolean = false,
    ): Settled {
        if (isFpsCap) return Settled(result, reason, done = false)
        val compared = result == OptimizeJudge.HELPED || result == OptimizeJudge.NO_CHANGE || result == OptimizeJudge.WORSE
        if (compared && met(profile, after, refreshHz, fpsCap)) {
            return Settled(OptimizeJudge.HELPED, OptimizeJudge.REASON_TARGET_MET, done = true)
        }
        return Settled(result, reason, done = false)
    }

    /** Before the first step of [ladder]: true when the game already meets the profile's target on its own settings. */
    fun metAtBaseline(profile: OptimizeProfile, ladder: ProfileLadder, baseline: Metrics?, refreshHz: Double?, fpsCap: Int): Boolean =
        ladder.state == LadderState.TRYING && ladder.kept.isEmpty() && ladder.pending == null && ladder.tried.isEmpty() &&
            baseline != null && met(profile, baseline, refreshHz, fpsCap)
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
    const val ACTION_CAP = "cap"

    const val SUPPRESSED_NEVER = "never"
    const val SUPPRESSED_PROFILE = "profile_active"
    const val SUPPRESSED_CARD_WAITING = "card_waiting"
    const val SUPPRESSED_NOT_CONFIDENT = "not_confident"
    const val SUPPRESSED_SHORT = "short"
    const val SUPPRESSED_CLASS = "class"
    const val SUPPRESSED_NOT_NOW = "not_now"

    /**
     * Why the session that just ended leaves no first card, or null when it should. A game with no active profile
     * gets it again after Off or Restore, ladders and all.
     */
    fun firstCardBlock(state: OptimizeLocalState, session: Metrics, nowMillis: Long): String? {
        if (state.never) return SUPPRESSED_NEVER
        if (state.activeProfile != null) return SUPPRESSED_PROFILE
        if (state.card != null) return SUPPRESSED_CARD_WAITING
        if (session.confident != true) return SUPPRESSED_NOT_CONFIDENT
        if (session.gameplaySec < MIN_GAMEPLAY_SEC) return SUPPRESSED_SHORT
        if (session.cls !in CLASSES) return SUPPRESSED_CLASS
        val suppression = state.suppression ?: return null
        if (nowMillis >= suppression.untilMillis) return null
        val reference = suppression.metrics ?: return SUPPRESSED_NOT_NOW
        return if (OptimizeJudge.compare(reference, session, isFpsCap = false) == OptimizeJudge.WORSE) null else SUPPRESSED_NOT_NOW
    }

    fun offerFirst(state: OptimizeLocalState, session: Metrics, nowMillis: Long): Boolean = firstCardBlock(state, session, nowMillis) == null

    /** perf_verdict.card for a session: "shown" when it left a card, otherwise why not. */
    fun cardField(shown: Boolean, block: String?): String = if (shown) "shown" else "suppressed:${block ?: "none"}"

    const val CARD_PENDING = "pending"

    /** perf_verdict.card before judging: a profile's ladder may still leave a card, so it is "pending" then. */
    fun cardBeforeJudging(state: OptimizeLocalState, session: Metrics, nowMillis: Long): String {
        if (state.activeProfile != null) return CARD_PENDING
        val block = firstCardBlock(state, session, nowMillis)
        return cardField(block == null, block)
    }

    /** game_exited properties with perf_verdict.card set to [card]; unchanged when either is missing. */
    fun withCard(props: Map<String, Any>, card: String?): Map<String, Any> = withVerdictField(props, "card", card)

    /** game_exited properties with perf_verdict.[key] set to [value]; unchanged when either is missing. */
    fun withVerdictField(props: Map<String, Any>, key: String, value: Any?): Map<String, Any> {
        val verdict = props["perf_verdict"] as? Map<*, *> ?: return props
        if (value == null) return props
        val updated = buildMap<String, Any> {
            verdict.forEach { (k, v) -> if (k is String && v != null) put(k, v) }
            put(key, value)
        }
        return props + ("perf_verdict" to updated)
    }

    const val WIRE_ACCEPTED = "accepted"
    const val WIRE_CONTINUE = "continue"

    /** The card_action the server is sent for an answer: accepted, dismissed, never, happy, undo, continue, keep_going, stop or optimize. */
    fun wireAction(action: String, cardKind: String?): String = when (action) {
        ACTION_OPTIMIZE -> if (cardKind == OptimizeCard.FIRST) WIRE_ACCEPTED else ACTION_OPTIMIZE
        ACTION_MEMBERSHIP -> WIRE_ACCEPTED
        ACTION_NEVER, ACTION_HAPPY, ACTION_UNDO, ACTION_KEEP_GOING, ACTION_STOP, ACTION_CAP -> action
        ACTION_KEEP_CHANGES -> ACTION_HAPPY
        ACTION_UNDO_ALL -> ACTION_UNDO
        WIRE_CONTINUE -> WIRE_CONTINUE
        else -> ACTION_DISMISSED
    }

    const val ENTITLEMENT_RECHECK_MS = 24L * 60 * 60 * 1000

    /** After the trial ran out, ask again only once the user is a member or a day has passed. */
    fun shouldAskAfterTrial(ladder: ProfileLadder, entitlement: Entitlement?, checkedAtMillis: Long, isMember: Boolean, nowMillis: Long): Boolean {
        if (ladder.lastStatus != NextRungResponse.STATUS_ENTITLEMENT_REQUIRED) return true
        if (entitlement?.state != Entitlement.NONE || isMember) return true
        return nowMillis - checkedAtMillis >= ENTITLEMENT_RECHECK_MS
    }

    private val FPS_CLASSES = setOf("gpu", "cpu_single", "cpu_multi")
    const val SUGGEST_STUTTER_SHARE = 0.02

    const val REASON_HOT = "hot"
    const val REASON_HEAT_DIPS = "heat_dips"
    const val REASON_LOW_FPS = "low_fps"
    const val REASON_STUTTER = "stutter"
    const val REASON_NONE = "none"

    data class Suggestion(val profile: OptimizeProfile, val reason: String)

    /**
     * The profile a game's verdict points at. Heat with dips wants the frame rate held with less load (Performance);
     * heat while the target holds wants Power and heat; then a frame rate short of the target, then stutter.
     */
    fun suggestedProfile(verdict: String?, metrics: Metrics?, capFps: Int, refreshHz: Double?): Suggestion {
        val holds = metrics != null && OptimizeTarget.holdsTarget(metrics, refreshHz, capFps)
        if (verdict == "thermal_gpu" || metrics?.throttleOnsetSec != null) {
            return if (holds) Suggestion(OptimizeProfile.POWER, REASON_HOT) else Suggestion(OptimizeProfile.PERFORMANCE, REASON_HEAT_DIPS)
        }
        if (verdict in FPS_CLASSES && metrics?.steadyFps != null && !holds) return Suggestion(OptimizeProfile.PERFORMANCE, REASON_LOW_FPS)
        if ((metrics?.stutterShare ?: 0.0) >= SUGGEST_STUTTER_SHARE || metrics?.symptom == "stutter") {
            return Suggestion(OptimizeProfile.STABILITY, REASON_STUTTER)
        }
        return Suggestion(OptimizeProfile.BALANCED, REASON_NONE)
    }

    /** Below this steady fps under heat, settings alone will not hold a cap, so the cap is the lever. */
    const val CAP_HEAT_FPS = 45.0
    const val CAP_NO_CHANGE_STREAK = 2
    const val CAP_SNOOZE_MS = SUPPRESS_MS
    private val CAP_VALUES = listOf(45, 40, 30)
    private const val CAP_SHARE = 0.75
    private val ENDED = setOf(LadderState.EXHAUSTED, LadderState.DONE)

    /** The fps a cap rung holds the game at, from its container fpsLimit lever; null for any other rung. */
    fun capFps(rung: JSONObject?): Int? {
        val parsed = rung?.let { Rung.parse(it) }?.takeIf { it.isFpsCap } ?: return null
        return parsed.levers.filterIsInstance<Lever.ContainerSet>().flatMap { it.set }
            .firstOrNull { it.first == "fpsLimit" }?.second?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * The cap the server serves on ladder.prefer "cap": the highest of 45, 40, 30 at most 0.75 x steady fps, else
     * 30; 30 with no steady fps; none when the game already runs at 30 or less.
     */
    fun capValue(steadyFps: Double?): Int? {
        if (steadyFps == null || steadyFps <= 0) return CAP_VALUES.last()
        if (steadyFps <= CAP_VALUES.last()) return null
        return CAP_VALUES.firstOrNull { it <= CAP_SHARE * steadyFps } ?: CAP_VALUES.last()
    }

    /**
     * The fps to propose holding the game at, or null when the cap is not the best lever. Heat has to be the limit
     * ([OptimizeTarget.hot] for [metrics]; with no metrics, a thermal_gpu verdict) and one of: steady fps under 45,
     * so settings alone will not hold a cap; the profile's settings ladder ended (exhausted or done) or ran two
     * no-change steps in a row; or Power is the active profile. Never while a cap is already on or on trial, or the container already holds the game at
     * or below it ([fpsCap] 0 is no cap).
     */
    fun capDecision(
        verdict: String?,
        metrics: Metrics?,
        ladder: ProfileLadder?,
        profile: OptimizeProfile?,
        fpsCap: Int = 0,
        refreshHz: Double? = null,
    ): Int? {
        val heat = metrics?.let { OptimizeTarget.hot(it, refreshHz, fpsCap) } ?: (verdict == "thermal_gpu")
        if (!heat) return null
        if (ladder != null && (ladder.pending?.rung?.isFpsCap == true || ladder.kept.any { it.applied && capFps(it.rung) != null })) return null
        val steady = metrics?.steadyFps
        val cap = capValue(steady) ?: return null
        if (fpsCap in 1..cap) return null
        val fires = (steady != null && steady < CAP_HEAT_FPS) ||
            (ladder != null && (ladder.state in ENDED || ladder.noChangeStreak >= CAP_NO_CHANGE_STREAK)) ||
            profile == OptimizeProfile.POWER
        return cap.takeIf { fires }
    }

    /** [capDecision] for the session that just ended, unless the question is snoozed or already answered yes. */
    fun capQuestion(state: OptimizeLocalState, session: Metrics, fpsCap: Int, nowMillis: Long, refreshHz: Double? = null): Int? {
        if (state.never || state.capPreferred || nowMillis < state.capSnoozedUntil) return null
        val profile = state.activeProfile
        val ladder = profile?.let { state.ladder(it) }
        if (ladder?.lastStatus == NextRungResponse.STATUS_ENTITLEMENT_REQUIRED) return null
        return capDecision(session.cls, session, ladder, profile, fpsCap, refreshHz)
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

/** A relative rung only lowers quality: each value is written only when it is below what the game has now. */
object OptimizeRelative {
    const val LESS = "less"
    const val MORE = "more"

    private fun number(s: String): Double? = s.trim().toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** Same value: trimmed, case-insensitive, and "1.000000" equals "1". */
    fun same(a: String, b: String): Boolean {
        if (a.trim().equals(b.trim(), ignoreCase = true)) return true
        val x = number(a) ?: return false
        val y = number(b) ?: return false
        return x == y
    }

    /** The highest step whose value is [value], or null when the ladder does not hold it. */
    fun stepOf(ladder: List<String?>, value: String): Int? = ladder.indices.lastOrNull { i -> ladder[i]?.let { same(it, value) } == true }

    /**
     * Whether a set to [target] lowers quality from [current]. A key the game does not have yet is written; numeric
     * keys move strictly in [lowerIs]'s direction; other keys need a lower step than the current value's, and a
     * current value outside the ladder is written.
     */
    fun shouldApply(current: String?, target: String, ladder: List<String?>?, lowerIs: String?): Boolean {
        if (current == null || ladder == null || lowerIs == null) return true
        val c = number(current)
        val t = number(target)
        if (c != null && t != null && (lowerIs == LESS || lowerIs == MORE)) return if (lowerIs == LESS) t < c else t > c
        if (same(current, target)) return false
        val currentStep = stepOf(ladder, current) ?: return true
        val targetStep = stepOf(ladder, target) ?: return true
        return targetStep < currentStep
    }
}
