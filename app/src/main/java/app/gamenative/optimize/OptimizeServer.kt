package app.gamenative.optimize

import org.json.JSONObject

/**
 * Where the server's verdict and judgement take the place of the local ones. The server is authoritative when it
 * answers with them; the local rules decide when it does not (an older server, or no answer).
 */
object OptimizeServer {

    /** The first-card blocks that come from the session's verdict, which the server's verdict may overrule. */
    val VERDICT_BLOCKS = setOf(OptimizeCards.SUPPRESSED_NOT_CONFIDENT, OptimizeCards.SUPPRESSED_SHORT, OptimizeCards.SUPPRESSED_CLASS)

    const val BLOCK_SERVER = "server"

    /** The verdict blocks the server is still asked about at exit; a short session never is. */
    val ASKED_BLOCKS = setOf(OptimizeCards.SUPPRESSED_NOT_CONFIDENT, OptimizeCards.SUPPRESSED_CLASS)

    /** Marks a game asked at exit while no rules build was known yet. */
    const val UNKNOWN_RULES = "unknown"

    /**
     * Whether the exit dry-run is sent for a session the local rules would [localBlock]. Always when nothing blocks
     * it. For an unsure or unlisted verdict only after [gameplaySec] of real play, with no [userHold], and once per
     * rules build: [askedRulesVersion] is the build this game was last asked under, [rulesVersion] the current one.
     */
    fun asksAtExit(localBlock: String?, gameplaySec: Int, userHold: String?, askedRulesVersion: String?, rulesVersion: String?): Boolean = when {
        localBlock == null -> true
        localBlock !in ASKED_BLOCKS || userHold != null -> false
        gameplaySec < OptimizeCards.MIN_GAMEPLAY_SEC -> false
        else -> askedRulesVersion != (rulesVersion ?: UNKNOWN_RULES)
    }

    /** [card] when one is left, else null and why not. */
    data class FirstDecision(val card: OptimizeCard?, val block: String?)

    /**
     * The first card a session leaves. With a [server] verdict that says show_card, it decides: no card when false
     * (its block_reason), otherwise [card] with the server's class, suggested profile and card kind ([capFps] is the
     * cap a cap card proposes). Without one, or when the server had no samples to read (no_samples), the local
     * [localBlock] stands, exactly as before.
     */
    fun firstDecision(localBlock: String?, card: OptimizeCard, server: ServerVerdict?, capFps: Int?): FirstDecision {
        val local = if (localBlock != null) FirstDecision(null, localBlock) else FirstDecision(card, null)
        if (server?.blockReason == ServerVerdict.BLOCK_NO_SAMPLES) return local
        val show = server?.showCard ?: return local
        if (!show) return FirstDecision(null, server.blockReason ?: BLOCK_SERVER)
        if ((server.cls ?: card.cls) !in OptimizeCards.CARD_CLASSES) return FirstDecision(null, OptimizeCards.SUPPRESSED_CLASS)
        val profile = server.suggestedProfile ?: card.profile
        val cap = capFps ?: card.capFps
        val kind = when (server.cardKind) {
            OptimizeCard.CAP -> if (cap != null) OptimizeCard.CAP else OptimizeCard.FIRST
            OptimizeCard.FIRST -> OptimizeCard.FIRST
            else -> card.kind
        }
        return FirstDecision(
            card.copy(
                kind = kind,
                cls = server.cls ?: card.cls,
                profile = profile,
                capFps = if (kind == OptimizeCard.CAP) cap else card.capFps,
            ),
            null,
        )
    }

    private val KEEP = setOf(OptimizeJudge.HELPED, OptimizeJudge.NOT_COMPARABLE)
    private val REVERT = setOf(OptimizeJudge.WORSE, OptimizeJudge.NO_CHANGE)
    private val AFTER_CARDS = setOf(OptimizeCard.HELPED, OptimizeCard.NO_CHANGE, OptimizeCard.WORSE, OptimizeCard.DONE)

    /** The after-card kind for a judgement: the server's own when it is one, else from its result. */
    fun afterCardKind(judgement: ServerJudgement): String = judgement.afterCardKind?.takeIf { it in AFTER_CARDS } ?: when (judgement.result) {
        // A step that stays on shows the kept card; with no gain to name it says there is nothing to see yet.
        OptimizeJudge.HELPED, OptimizeJudge.NOT_COMPARABLE -> OptimizeCard.HELPED
        OptimizeJudge.NO_CHANGE -> OptimizeCard.NO_CHANGE
        else -> OptimizeCard.WORSE
    }

    /**
     * [state] with the server's [judgement] of [rungId] on [profile] in place of the local one: the tried entry,
     * the after-card (kind and deltas) and whether the step stays on. A step the local judge called a crash stays
     * as it was: it is already off and that decision never waits for the network. Keep and revert are only changed
     * while the step is still where the local judgement left it (pending to come off, or the newest kept step).
     */
    fun applyJudgement(state: OptimizeLocalState, profile: OptimizeProfile, rungId: String, judgement: ServerJudgement, after: Metrics?): OptimizeLocalState {
        if (judgement.result == OptimizeJudge.CRASH) return state
        var ladder = state.ladder(profile)
        val triedAt = ladder.tried.indexOfLast { it.rungId == rungId }
        if (triedAt < 0 || ladder.tried[triedAt].result == OptimizeJudge.CRASH) return state
        val local = ladder.tried[triedAt]
        ladder = ladder.copy(tried = ladder.tried.toMutableList().also { it[triedAt] = local.copy(result = judgement.result, reason = judgement.reason) })

        val pending = ladder.pending
        val lastKept = ladder.kept.lastOrNull()
        if (pending != null && pending.rung.rungId == rungId && pending.revert != null && pending.revert != OptimizeJudge.CRASH) {
            if (judgement.result in KEEP) {
                ladder = ladder.copy(kept = ladder.kept + pending.toKept(after ?: pending.baseline), pending = null, baselineOverride = null)
            } else if (judgement.result in REVERT) {
                ladder = ladder.copy(pending = pending.copy(revert = judgement.result))
            }
        } else if (pending == null && lastKept?.rungId == rungId && judgement.result in REVERT) {
            val rung = Rung.parse(lastKept.rung)
            if (rung != null) {
                ladder = ladder.copy(
                    kept = ladder.kept.dropLast(1),
                    pending = PendingRung(rung, lastKept.appliedAt, lastKept.before, 0, lastKept.levers, revert = judgement.result),
                    state = if (ladder.state == LadderState.DONE) LadderState.TRYING else ladder.state,
                )
            }
        }

        var next = state.withLadder(profile, ladder)
        val card = state.card
        if (card != null && card.rungId == rungId && card.kind in AFTER_CARDS && card.outcome != OptimizeCard.OUTCOME_CRASH) {
            val kind = afterCardKind(judgement)
            next = next.copy(
                card = card.copy(
                    kind = kind,
                    outcome = if (kind == OptimizeCard.HELPED) OptimizeCards.helpedOutcome(card.outcome, profile, card.fpsGainPct, card.stutterDropPct) else card.outcome,
                    fpsGainPct = if (kind == OptimizeCard.HELPED) card.fpsGainPct else null,
                    stutterDropPct = if (kind == OptimizeCard.HELPED) card.stutterDropPct else null,
                    serverDeltas = judgement.deltas ?: card.serverDeltas,
                ),
            )
        }
        return next
    }

    /**
     * An outcome's fps_limit (the limiter the after session ran under, an Optimize cap included) and user_fps_cap (the
     * user's own), 0 for none; the server judge reads both. Left out when not known.
     */
    fun putLimits(outcome: JSONObject, fpsLimit: Int?, userFpsCap: Int?): JSONObject = outcome.apply {
        fpsLimit?.let { put("fps_limit", it.coerceAtLeast(0)) }
        userFpsCap?.let { put("user_fps_cap", it.coerceAtLeast(0)) }
    }

    /** The outcome report's after session, for a step the server keeps. */
    fun afterOf(report: JSONObject): Metrics? = Metrics.fromJson(report.optJSONObject("after"))
}
