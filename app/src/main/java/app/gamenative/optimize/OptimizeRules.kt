package app.gamenative.optimize

import app.gamenative.ui.component.parseBooleanExtra
import app.gamenative.ui.component.parsePositiveFpsLimit
import kotlin.math.abs
import kotlin.math.ceil
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

    /**
     * A profile accepted from a card: the session that produced the card is the first step's baseline, when the
     * ladder has nothing on yet and the session was long enough to judge against.
     */
    fun accepted(ladder: ProfileLadder, cardSession: Metrics?): ProfileLadder =
        if (cardSession != null && cardSession.gameplaySec >= OptimizeJudge.COMPARABLE_GAMEPLAY_SEC && ladder.pending == null && ladder.kept.isEmpty()) {
            ladder.copy(baselineOverride = cardSession)
        } else {
            ladder
        }

    /**
     * The baseline once a step is judged and stays on: a step that helped makes its own session the baseline, as it
     * is the newest judged session under the kept steps; one kept without a comparison leaves the step's baseline.
     */
    fun baselineAfterKeep(result: String, pending: PendingRung, judged: Metrics, current: Metrics?): Metrics? =
        if (result == OptimizeJudge.HELPED) judged else pending.baseline ?: current

    /**
     * [ladder] once [pending] was judged [result] for [reason]: the step is recorded as tried, kept at [judged] when it
     * helped or could not be compared, otherwise flagged to come off; a crash also marks it bad.
     */
    fun afterJudged(ladder: ProfileLadder, pending: PendingRung, result: String, reason: String?, judged: Metrics): ProfileLadder {
        val rung = pending.rung
        val keep = result == OptimizeJudge.HELPED || result == OptimizeJudge.NOT_COMPARABLE
        val tried = ladder.copy(tried = (ladder.tried + TriedRung(rung.rungId, rung.version, result, reason)).takeLast(OptimizeLocalState.MAX_TRIED))
        val next = if (keep) {
            tried.copy(
                kept = tried.kept + pending.toKept(judged),
                pending = null,
                baselineOverride = baselineAfterKeep(result, pending, judged, ladder.baselineOverride),
            )
        } else {
            tried.copy(
                pending = pending.copy(revert = result),
                bad = if (result == OptimizeJudge.CRASH) (tried.bad + rung.rungId).distinct() else tried.bad,
            )
        }
        return next.copy(noChangeStreak = if (result == OptimizeJudge.NO_CHANGE) next.noChangeStreak + 1 else 0)
    }

    /** The baseline once every step is taken off: the session before the first of them. */
    fun baselineAfterRestore(ladder: ProfileLadder): Metrics? =
        ladder.kept.firstOrNull()?.before ?: ladder.pending?.baseline ?: ladder.baselineOverride

    /**
     * The game once Restore took every change off: every goal starts over, with no tried history, no-change streak,
     * kept baseline or no-rule hold, and goals other than [profile] go idle. Steps that crashed or froze the game stay
     * tried and bad, so they are never offered again. [profile] goes to [nextState] with [baseline], the session on the
     * original settings.
     */
    fun restarted(state: OptimizeLocalState, profile: OptimizeProfile, nextState: LadderState, baseline: Metrics?): OptimizeLocalState {
        val profiles = (state.profiles + (profile to state.ladder(profile))).mapValues { (p, ladder) ->
            ladder.copy(
                state = if (p == profile) nextState else LadderState.IDLE,
                tried = ladder.tried.filter { it.result == OptimizeJudge.CRASH },
                noChangeStreak = 0,
                baselineOverride = baseline.takeIf { p == profile },
            )
        }
        return state.copy(profiles = profiles, noRule = null, prefetched = null)
    }

    /** Keep tuning or Continue: the ladder tries again with a fresh no-change streak, recorded as card_action continue. */
    fun resumed(state: OptimizeLocalState): OptimizeLocalState {
        val profile = state.activeProfile ?: return state
        return state.copy(cardAction = OptimizeCards.WIRE_CONTINUE)
            .withLadder(profile, state.ladder(profile).copy(state = LadderState.TRYING, noChangeStreak = 0))
    }

    /**
     * True when the next launch asks for a rung: trying, nothing on trial, and not held back by the plan.
     * [accountChangedAt] is when the account last changed, which lifts a plan hold from before it.
     */
    fun wouldAsk(state: OptimizeLocalState, ladder: ProfileLadder, isMember: Boolean, nowMillis: Long, accountChangedAt: Long = 0L): Boolean =
        ladder.state == LadderState.TRYING &&
            ladder.pending.let { it == null || it.revert != null } &&
            OptimizeCards.shouldAskAfterTrial(ladder, state.entitlement, state.entitlementCheckedAt, isMember, nowMillis, accountChangedAt)

    /**
     * Holds [profile]'s ladder while the server has nothing for the game: a trying ladder with nothing on trial waits,
     * and a waiting ladder tries again once [OptimizeLocalState.noRule] lets go, which is then cleared.
     * [rulesVersion] is the newest rules build any answer carried.
     */
    fun held(state: OptimizeLocalState, profile: OptimizeProfile, nowMillis: Long, rulesVersion: String?): OptimizeLocalState {
        val ladder = state.ladder(profile)
        val holds = state.noRule?.holds(nowMillis, rulesVersion) == true
        return when {
            holds && ladder.state == LadderState.TRYING && ladder.pending.let { it == null || it.revert != null } ->
                state.withLadder(profile, ladder.copy(state = LadderState.WAITING))
            !holds && ladder.state == LadderState.WAITING ->
                state.copy(noRule = null).withLadder(profile, ladder.copy(state = LadderState.TRYING, noChangeStreak = 0))
            !holds && state.noRule != null -> state.copy(noRule = null)
            else -> state
        }
    }

    /** Restore has something to take off: a step on trial or a kept step. */
    fun hasRestorable(ladder: ProfileLadder?): Boolean = ladder != null && (ladder.pending != null || ladder.kept.isNotEmpty())

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
    const val BALANCED_MAX_STUTTER = 0.02
    const val QUALITY_MAX_STUTTER = 0.01
    const val POWER_MIN_GAMEPLAY_SEC = 1200
    const val POTATO_FPS_TARGET = 30.0
    const val PERFORMANCE_FPS_SHARE = 0.97
    const val PERFORMANCE_WINDOW_SHARE = 0.95
    const val PERFORMANCE_FPS_CEILING = 60.0

    const val HOT_FPS_DECLINE = 3
    const val HOT_CLOCK_DROP_SHARE = 0.10

    /** Whether heat held the session back (offline fallback). */
    fun hot(m: Metrics, refreshHz: Double?, fpsCap: Int): Boolean {
        if (m.cls == "thermal_gpu" && m.confident == true) return true
        if (m.throttleOnsetSec == null) return false
        if (m.fpsBy5Min.isEmpty()) return true
        val fpsDeclines = m.fpsBy5Min.size >= 2 && m.fpsBy5Min[1] - m.fpsBy5Min.last() >= HOT_FPS_DECLINE
        val clocks = gameplayWindows(m.gpuClockBy5Min)
        val clockFalls = clocks.size >= 2 && clocks.first() > 0 && clocks.last() <= (1 - HOT_CLOCK_DROP_SHARE) * clocks.first()
        return fpsDeclines || clockFalls || dips(m, refreshHz, fpsCap)
    }

    /** The frame rate Performance holds; [fpsCap] must be the user's own limiter. */
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

    /** The five-minute windows that count. */
    private fun gameplayWindows(values: List<Int>): List<Int> = if (values.size >= 3) values.drop(1) else values

    /** Whether a five-minute window fell short of the target (offline fallback). */
    fun dips(m: Metrics, refreshHz: Double?, fpsCap: Int): Boolean {
        val target = fpsTarget(refreshHz, fpsCap)
        return gameplayWindows(m.fpsBy5Min).any { it < PERFORMANCE_WINDOW_SHARE * target }
    }

    /** Whether the session holds the target (offline fallback). */
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
            // Balanced is done when the game would no longer be offered tuning: it holds its frame rate, cool and smooth.
            OptimizeProfile.BALANCED -> holdsTarget(after, refreshHz, fpsCap) && !heat && (stutter ?: 0.0) < BALANCED_MAX_STUTTER
            OptimizeProfile.QUALITY -> stutter != null && stutter < QUALITY_MAX_STUTTER
            OptimizeProfile.POWER -> after.gameplaySec >= POWER_MIN_GAMEPLAY_SEC && !heat
            OptimizeProfile.POTATO -> (fps ?: 0.0) >= PERFORMANCE_FPS_SHARE * POTATO_FPS_TARGET
            OptimizeProfile.OFF -> false
        }
    }

    /** What a judged session settles: the result recorded and kept or reverted by, why, and whether the ladder is done. */
    data class Settled(val result: String, val reason: String?, val done: Boolean)

    /** What a judged session settles to. */
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
    /** [cardAction] continue (Keep tuning) asks for a step even when the target is already met. */
    fun metAtBaseline(
        profile: OptimizeProfile,
        ladder: ProfileLadder,
        baseline: Metrics?,
        refreshHz: Double?,
        fpsCap: Int,
        cardAction: String? = null,
    ): Boolean =
        cardAction != OptimizeCards.WIRE_CONTINUE &&
            ladder.state == LadderState.TRYING && ladder.kept.isEmpty() && ladder.pending == null && ladder.tried.isEmpty() &&
            baseline != null && met(profile, baseline, refreshHz, fpsCap)
}

object OptimizeCards {
    const val MIN_GAMEPLAY_SEC = 300

    /** The smallest frame-rate gain or stutter drop, in percent, a card names; a smaller one reads as more stable. */
    const val MIN_NAMED_GAIN_PCT = 5

    /** How much longer, in seconds, a session has to hold before slowing down for a card to call it more stable. */
    const val MIN_STABLE_GAIN_SEC = 60

    /**
     * Whether a kept step changed something the player can see: a named frame-rate gain or stutter drop, or a session
     * that held its performance longer before it slowed down. A step kept for what it saves under the surface shows
     * none, and its card says so.
     */
    fun showsGain(card: OptimizeCard): Boolean {
        if (card.fpsGainPct != null || card.stutterDropPct != null) return true
        val before = card.before?.throttleOnsetSec ?: return false
        val after = card.metrics?.throttleOnsetSec ?: return card.metrics != null
        return after >= before + MIN_STABLE_GAIN_SEC
    }
    const val SUPPRESS_MS = 7L * 24 * 60 * 60 * 1000

    const val SYNC_SPIN = "sync_spin"
    val CLASSES = setOf("gpu", "thermal_gpu", "cpu_single", "cpu_multi", "memory", "present_locked", SYNC_SPIN)

    /** The diagnoses a card is shown for: the ones tuning can do something about. */
    val CARD_CLASSES = CLASSES - SYNC_SPIN

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
    const val SUPPRESSED_RAN_FINE = "ran_fine"
    const val SUPPRESSED_NOT_NOW = "not_now"
    const val SUPPRESSED_NO_RULE = "no_rule"
    const val SUPPRESSED_CAP_SNOOZED = "cap_snoozed"

    /**
     * The user's own reasons for no first card, checked before anything the session or the server says: Never, a
     * profile on, a card waiting, the no-rule hold ([rulesVersion] is the newest rules build any answer carried),
     * a declined cap question, or "Not now" ([session] lifts it when it ran worse than the session it was said after).
     */
    fun userHold(state: OptimizeLocalState, session: Metrics?, nowMillis: Long, rulesVersion: String? = null): String? {
        if (state.never) return SUPPRESSED_NEVER
        if (state.activeProfile != null) return SUPPRESSED_PROFILE
        if (state.card != null) return SUPPRESSED_CARD_WAITING
        if (state.noRule?.holds(nowMillis, rulesVersion) == true) return SUPPRESSED_NO_RULE
        if (nowMillis < state.capSnoozedUntil) return SUPPRESSED_CAP_SNOOZED
        val suppression = state.suppression ?: return null
        if (nowMillis >= suppression.untilMillis) return null
        val reference = suppression.metrics ?: return SUPPRESSED_NOT_NOW
        if (session == null) return SUPPRESSED_NOT_NOW
        return if (OptimizeJudge.compare(reference, session, isFpsCap = false) == OptimizeJudge.WORSE) null else SUPPRESSED_NOT_NOW
    }

    /**
     * Why the session that just ended leaves no first card, or null when it should: the user's own holds first,
     * then the session's verdict. A game with no active profile gets it again after Off or Restore, ladders and all.
     */
    fun firstCardBlock(
        state: OptimizeLocalState,
        session: Metrics,
        nowMillis: Long,
        rulesVersion: String? = null,
        fpsCap: Int = 0,
    ): String? {
        userHold(state, session, nowMillis, rulesVersion)?.let { return it }
        if (session.confident != true) return SUPPRESSED_NOT_CONFIDENT
        if (session.gameplaySec < MIN_GAMEPLAY_SEC) return SUPPRESSED_SHORT
        if (session.cls !in CARD_CLASSES) return SUPPRESSED_CLASS
        if (ranFine(session, fpsCap)) return SUPPRESSED_RAN_FINE
        return null
    }

    /**
     * A session with nothing to fix: it held its frame rate ([fpsCap] is the user's own limiter, 0 for none), heat did
     * not hold it back and it barely stuttered. A busy GPU or processor alone is no reason to offer anything.
     */
    fun ranFine(session: Metrics, fpsCap: Int): Boolean =
        OptimizeTarget.holdsTarget(session, session.refreshHz, fpsCap) &&
            !OptimizeTarget.hot(session, session.refreshHz, fpsCap) &&
            (session.stutterShare ?: 0.0) < OptimizeTarget.BALANCED_MAX_STUTTER

    fun offerFirst(state: OptimizeLocalState, session: Metrics, nowMillis: Long): Boolean = firstCardBlock(state, session, nowMillis) == null

    /** What the dry-run answer at exit lets the first card be. */
    enum class Offer {
        /** The server has a settings change: the first card (or the cap question the session already asks). */
        CARD,

        /** The server's only change is a frame cap it wants asked first: the cap question. */
        CAP,

        /** The server has nothing for the game: no card, and the game is held. */
        HOLD,

        /** No answer: the card is shown as before, marked unverified. */
        UNVERIFIED,
    }

    /** [response] is the dry-run answer, null when there was none in time, the request failed or the user is signed out. */
    fun offerOf(response: NextRungResponse?): Offer = when {
        response == null -> Offer.UNVERIFIED
        response.hasNothing || response.status == NextRungResponse.STATUS_EXHAUSTED -> Offer.HOLD
        response.status == NextRungResponse.STATUS_RUNG && response.rung == null -> Offer.UNVERIFIED
        response.status == NextRungResponse.STATUS_RUNG && response.rung?.isFpsCap == true && response.rung.askFirst != false -> Offer.CAP
        response.status == NextRungResponse.STATUS_RUNG || response.status == NextRungResponse.STATUS_ENTITLEMENT_REQUIRED -> Offer.CARD
        else -> Offer.UNVERIFIED
    }

    /**
     * What a launch does with a cap rung it did not ask for. [ASK]: raise the cap question and wait for the answer,
     * the first time. [END]: the question was declined or cannot be asked, so the ladder ends here. [APPLY]: the
     * server says the cap needs no question (ask_first false), so it is tried like any other step.
     */
    enum class UnaskedCap { ASK, END, APPLY }

    /** No to the cap question: it is not asked again for a while. */
    fun capDeclined(state: OptimizeLocalState, nowMillis: Long): OptimizeLocalState = state.copy(capSnoozedUntil = nowMillis + CAP_SNOOZE_MS)

    fun unaskedCap(state: OptimizeLocalState, rung: Rung, nowMillis: Long): UnaskedCap = when {
        rung.askFirst == false -> UnaskedCap.APPLY
        state.never || nowMillis < state.capSnoozedUntil || capFps(rung.raw) == null -> UnaskedCap.END
        else -> UnaskedCap.ASK
    }

    /**
     * The card a ladder the server has no more for ends with: exhausted (keep or undo) when one of its changes is on,
     * otherwise no_rule, since there is nothing to keep or undo.
     */
    fun endCard(ladder: ProfileLadder): String = if (ladder.kept.any { it.applied }) OptimizeCard.EXHAUSTED else OptimizeCard.NO_RULE

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

    /** When to ask again after the trial ran out: at once when the account changed since, otherwise after a day. */
    fun shouldAskAfterTrial(
        ladder: ProfileLadder,
        entitlement: Entitlement?,
        checkedAtMillis: Long,
        isMember: Boolean,
        nowMillis: Long,
        accountChangedAt: Long = 0L,
    ): Boolean {
        if (ladder.lastStatus != NextRungResponse.STATUS_ENTITLEMENT_REQUIRED) return true
        if (predatesAccountChange(checkedAtMillis, accountChangedAt)) return true
        if (entitlement?.state != Entitlement.NONE || isMember) return true
        return nowMillis - checkedAtMillis >= ENTITLEMENT_RECHECK_MS
    }

    /** The goal a first card suggests, whatever the diagnosis; the other goals are the player's own choice. */
    val SUGGESTED_PROFILE = OptimizeProfile.BALANCED

    /** Offline fallback tuning. */
    const val CAP_HEAT_FPS = 45.0
    const val CAP_NO_CHANGE_STREAK = 2
    const val CAP_SNOOZE_MS = SUPPRESS_MS
    private val CAP_VALUES = listOf(45, 40, 30)
    private const val CAP_SHARE = 0.75
    private val ENDED = setOf(LadderState.EXHAUSTED, LadderState.DONE, LadderState.WAITING)

    /** The fps a cap rung holds the game at, from its container fpsLimit lever; null for any other rung. */
    fun capFps(rung: JSONObject?): Int? {
        val parsed = rung?.let { Rung.parse(it) }?.takeIf { it.isFpsCap } ?: return null
        return parsed.levers.filterIsInstance<Lever.ContainerSet>().flatMap { it.set }
            .firstOrNull { it.first == "fpsLimit" }?.second?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** The cap to propose (offline fallback). */
    fun capValue(steadyFps: Double?): Int? {
        if (steadyFps == null || steadyFps <= 0) return CAP_VALUES.last()
        if (steadyFps <= CAP_VALUES.last()) return null
        return CAP_VALUES.firstOrNull { it <= CAP_SHARE * steadyFps } ?: CAP_VALUES.last()
    }

    /** The fps to propose holding the game at, or null (offline fallback). */
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
        return ((a / b - 1) * 100).roundToInt().takeIf { it >= MIN_NAMED_GAIN_PCT }
    }

    fun stutterDropPct(before: Metrics?, after: Metrics): Int? {
        val b = before?.stutterShare ?: return null
        val a = after.stutterShare ?: return null
        if (b <= 0 || a >= b) return null
        return ((b - a) / b * 100).roundToInt().takeIf { it >= MIN_NAMED_GAIN_PCT }
    }

    /**
     * Which outcome a helped card names: the rung's own outcome when it says, otherwise what the profile is for. A higher
     * frame rate or fewer stutters is only named with its measured [fpsGainPct] or [stutterDropPct]; otherwise the card
     * reads as more stable.
     */
    fun helpedOutcome(rungOutcome: String?, profile: OptimizeProfile, fpsGainPct: Int?, stutterDropPct: Int?): String {
        val outcome = when (rungOutcome) {
            OptimizeCard.OUTCOME_FPS -> OptimizeCard.OUTCOME_FPS
            OptimizeCard.OUTCOME_STUTTER -> OptimizeCard.OUTCOME_STUTTER
            OptimizeCard.OUTCOME_STABLE -> OptimizeCard.OUTCOME_STABLE
            else -> when (profile) {
                OptimizeProfile.PERFORMANCE, OptimizeProfile.POTATO -> OptimizeCard.OUTCOME_FPS
                OptimizeProfile.BALANCED, OptimizeProfile.QUALITY -> OptimizeCard.OUTCOME_STUTTER
                OptimizeProfile.POWER, OptimizeProfile.OFF -> OptimizeCard.OUTCOME_STABLE
            }
        }
        return when {
            outcome == OptimizeCard.OUTCOME_FPS && fpsGainPct == null -> OptimizeCard.OUTCOME_STABLE
            outcome == OptimizeCard.OUTCOME_STUTTER && stutterDropPct == null -> OptimizeCard.OUTCOME_STABLE
            else -> outcome
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

    /** Whether [candidate] is lower quality than [current] ([lower] true) or higher (false), where both are numbers. */
    private fun moves(current: String, candidate: String, lowerIs: String?, lower: Boolean): Boolean {
        val c = number(current) ?: return true
        val v = number(candidate) ?: return true
        if (lowerIs != LESS && lowerIs != MORE) return true
        return if ((lowerIs == LESS) == lower) v < c else v > c
    }

    /**
     * The steps of [ladder] that hold the level [current] sits at: the ones with its value, else for a number between
     * two steps the ones of the nearest value at or below its quality. Null when the ladder does not place it.
     */
    fun levelOf(current: String, ladder: List<String?>, lowerIs: String?): IntRange? {
        val exact = ladder.indices.filter { i -> ladder[i]?.let { same(it, current) } == true }
        if (exact.isNotEmpty()) return exact.first()..exact.last()
        val c = number(current) ?: return null
        if (lowerIs != LESS && lowerIs != MORE) return null
        val under = ladder.indices.lastOrNull { i ->
            val v = ladder[i]?.let { number(it) } ?: return@lastOrNull false
            if (lowerIs == LESS) v <= c else v >= c
        } ?: return null
        val value = ladder[under]!!
        return ladder.indices.first { i -> ladder[i]?.let { same(it, value) } == true }..under
    }

    /**
     * The value a step writes for a key that holds [current]: [down] levels of [ladder] below it, or above it when
     * [down] is negative. [down] is the distance for a key at the top of its ladder: a key that sits lower moves
     * in proportion to its level, rounded up, so a game on medium settings is not sent to the bottom by one large step.
     * The count starts at the highest level that holds the current value, so a key whose value spans many levels
     * moves less, never more, than asked. Null when there is nothing to write: no move asked, or
     * the level reached holds the value the key already has. A level without a value gives way to the nearest one
     * toward the current value. A key the game does not have yet, or whose value the ladder cannot place, falls back
     * to [target], under the lower-only rule.
     */
    fun retarget(current: String?, target: String, ladder: List<String?>, lowerIs: String?, down: Int): String? {
        if (down == 0) return null
        if (current == null) return target.takeIf { down > 0 }
        val at = levelOf(current, ladder, lowerIs)
            ?: return target.takeIf { down > 0 && shouldApply(current, target, ladder, lowerIs) }
        val top = ladder.lastIndex.coerceAtLeast(1)
        val levels = ceil(abs(down) * at.last / top.toDouble()).toInt().coerceAtLeast(1)
        val candidates = if (down > 0) {
            (at.last - levels).coerceAtLeast(0) until at.first
        } else {
            (at.last + levels).coerceAtMost(ladder.lastIndex) downTo at.last + 1
        }
        return candidates.firstNotNullOfOrNull { i ->
            ladder[i]?.takeIf { !same(it, current) && moves(current, it, lowerIs, lower = down > 0) }
        }
    }
}
