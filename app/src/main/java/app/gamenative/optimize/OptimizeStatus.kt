package app.gamenative.optimize

import androidx.annotation.StringRes
import app.gamenative.R

sealed class OptimizeStatus {
    data object Off : OptimizeStatus()
    data object Never : OptimizeStatus()
    data object NotStarted : OptimizeStatus()
    /** A change is on and a short session left it unjudged; the next one is measured. */
    data object Measuring : OptimizeStatus()

    /** A profile is picked and no change is on trial; the next launch asks for one. */
    data object TuningNext : OptimizeStatus()

    /** The last change could not be written and nothing is on; the next launch asks for another. */
    data object RetryNext : OptimizeStatus()

    /** A change is on trial; [change] is the kind of change, null when the step has none the app knows. */
    data class Trying(val change: OptimizeChange?) : OptimizeStatus()
    data object Paused : OptimizeStatus()
    data object Done : OptimizeStatus()
    data object Exhausted : OptimizeStatus()

    /** The server has no safe change for the game yet; it is checked again later. */
    data object Waiting : OptimizeStatus()
    data object TrialUsed : OptimizeStatus()

    companion object {
        /** The account tiers whose plan optimizes every game. */
        private val UNLIMITED_ACCOUNT_PLANS = setOf("pro", "patron")

        /** [accountChangedAt] is when the account last changed: a plan answer from before it no longer holds the game. */
        fun of(state: OptimizeLocalState, accountChangedAt: Long = 0L): OptimizeStatus {
            val ladder = state.active ?: return if (state.never) Never else Off
            val trying = ladder.pending?.takeIf { it.revert == null }
            return when {
                trying != null && trying.shortSessions > 0 -> Measuring
                trying != null -> Trying(OptimizeChange.of(trying.rung))
                ladder.state == LadderState.TRYING && ladder.lastStatus == NextRungResponse.STATUS_ENTITLEMENT_REQUIRED &&
                    !predatesAccountChange(state.entitlementCheckedAt, accountChangedAt) -> TrialUsed
                ladder.state == LadderState.IDLE -> NotStarted
                ladder.state == LadderState.TRYING && ladder.tried.lastOrNull()?.result == OptimizeJudge.APPLY_FAILED -> RetryNext
                ladder.state == LadderState.TRYING -> TuningNext
                ladder.state == LadderState.PAUSED -> Paused
                ladder.state == LadderState.DONE -> Done
                ladder.state == LadderState.WAITING -> Waiting
                else -> Exhausted
            }
        }

        /**
         * The one plan line a surface shows. Signed out, it is always the offer, whatever is stored for the game.
         * Unlimited plans never see counts; [exhausted] is the game's own entitlement_required answer, which outranks a
         * stale stored count. [member] is the app's own flag and [accountPlan] the account's own tier
         * ([app.gamenative.billing.Membership.planKey], null while not loaded), used when the server has not said which
         * plan the account is on: only an unlimited tier reads as unlimited, any other shows no line until it does.
         */
        fun entitlementLine(entitlement: Entitlement?, exhausted: Boolean, signedIn: Boolean, member: Boolean, accountPlan: String?): EntitlementLine? {
            if (!signedIn) return EntitlementLine.Offer(entitlement.limitOrDefault())
            val plan = entitlement?.plan
            return when {
                plan == Entitlement.Plan.UNLIMITED -> EntitlementLine.Unlimited
                entitlement != null && plan == Entitlement.Plan.MONTHLY -> {
                    val quota = entitlement.quota
                    EntitlementLine.Monthly(if (exhausted) quota else entitlement.used.coerceAtMost(quota), quota, entitlement.periodResetsAt)
                }
                member && entitlement?.tier == null -> EntitlementLine.Unlimited.takeIf { accountPlan in UNLIMITED_ACCOUNT_PLANS }
                exhausted -> EntitlementLine.Exhausted(entitlement.limitOrDefault())
                entitlement == null -> null
                entitlement.state == Entitlement.NONE && entitlement.tier == null -> EntitlementLine.Exhausted(entitlement.quota)
                entitlement.used < entitlement.quota -> EntitlementLine.Trial(entitlement.used, entitlement.quota)
                else -> EntitlementLine.Exhausted(entitlement.quota)
            }
        }

        /** What the plan pill says for [line]. */
        fun entitlementPill(line: EntitlementLine): PlanText = when (line) {
            EntitlementLine.Unlimited -> PlanText(R.string.optimize_plan_unlimited, args = listOf(PlanName.PRO))
            is EntitlementLine.Offer -> PlanText(R.string.optimize_plan_signed_out)
            is EntitlementLine.Trial -> PlanText(R.plurals.optimize_trial_progress, line.limit, listOf(line.used, line.limit))
            is EntitlementLine.Exhausted -> PlanText(R.plurals.optimize_trial_progress, line.limit, listOf(line.limit, line.limit))
            is EntitlementLine.Monthly -> if (line.resetsAt != null) {
                PlanText(R.plurals.optimize_plan_monthly_resets, line.limit, listOf(line.used, line.limit), line.resetsAt)
            } else {
                PlanText(R.plurals.optimize_plan_monthly, line.limit, listOf(line.used, line.limit))
            }
        }

        /** The upsell shows when signed out or when the free or monthly games are all used; never for unlimited. */
        fun showsUpsell(line: EntitlementLine?): Boolean = when (line) {
            is EntitlementLine.Offer, is EntitlementLine.Exhausted -> true
            is EntitlementLine.Monthly -> line.usedUp
            else -> false
        }

        /** The upsell's subtitle: the quota that applies to [line]; null when the upsell does not show. */
        fun upsellSubtitle(line: EntitlementLine?): PlanText? = when (line) {
            is EntitlementLine.Offer -> PlanText(R.plurals.optimize_trial_offer, line.limit, listOf(line.limit))
            is EntitlementLine.Exhausted -> PlanText(R.plurals.optimize_upsell_free_used, line.limit, listOf(line.limit))
            is EntitlementLine.Monthly -> when {
                !line.usedUp -> null
                line.resetsAt != null -> PlanText(R.plurals.optimize_upsell_monthly_used_resets, line.limit, listOf(line.limit), line.resetsAt)
                else -> PlanText(R.plurals.optimize_upsell_monthly_used, line.limit, listOf(line.limit))
            }
            else -> null
        }

        /** The monthly games the plan line offers: the account's own monthly quota when on one, otherwise the default. */
        fun upsellMonthlyGames(line: EntitlementLine?): Int =
            (line as? EntitlementLine.Monthly)?.limit ?: Entitlement.DEFAULT_MONTHLY_GAMES

        /** The plan line of the upsell: Basic's monthly games and Pro's unlimited, by the plan names the paywall shows. */
        fun upsellPlanLine(line: EntitlementLine?): PlanText {
            val games = upsellMonthlyGames(line)
            return PlanText(R.plurals.optimize_upsell_plan_line, games, listOf(PlanName.BASIC, games, PlanName.PRO))
        }

        /**
         * Whether the dialog shows the status sentence under the header: not when its short form is the header's
         * status words already (Measuring, Tuning on the next launch, Paused), so the same state is said once.
         */
        fun showsSentence(status: OptimizeStatus): Boolean {
            val short = when (status) {
                Measuring -> R.string.optimize_pill_measuring
                TuningNext -> R.string.optimize_pill_tuning
                Paused -> R.string.optimize_status_paused
                else -> return true
            }
            return short != pillRes(status)
        }

        /**
         * The short status pill for [status], the words the cards use; null when there is nothing to show. [line] is the
         * game's plan line: games used up on a monthly plan say so, not that the trial is used.
         */
        @StringRes
        fun pillRes(status: OptimizeStatus, line: EntitlementLine? = null): Int? = when (status) {
            Measuring -> R.string.optimize_pill_measuring
            NotStarted, TuningNext, RetryNext, is Trying -> R.string.optimize_pill_tuning
            Paused -> R.string.optimize_status_paused
            Done, Exhausted -> R.string.optimize_pill_done
            Waiting -> R.string.optimize_pill_waiting
            TrialUsed -> if (line is EntitlementLine.Monthly) R.string.optimize_pill_month_used else R.string.optimize_pill_trial_used
            Off, Never -> null
        }

        /**
         * The goal the dialog marks Suggested: Power when heat held a done goal back, otherwise the game's newest
         * verdict, otherwise the profile a first card still waiting suggested. Off is never suggested.
         */
        fun suggestedProfile(state: OptimizeLocalState, verdict: OptimizeProfile?, hot: Boolean): OptimizeProfile? {
            val active = state.activeProfile
            if (active != null && active != OptimizeProfile.POWER && hot && of(state) == Done) return OptimizeProfile.POWER
            val stored = state.card?.takeIf { it.kind == OptimizeCard.FIRST }?.profile
            return (verdict ?: stored)?.takeIf { it != OptimizeProfile.OFF }
        }

        fun keptCount(state: OptimizeLocalState): Int = state.active?.kept?.count { it.applied } ?: 0

        /** The fps the active profile's kept cap holds the game at, or null when no cap is kept. */
        fun heldCapFps(state: OptimizeLocalState): Int? =
            state.active?.kept?.lastOrNull { it.applied && OptimizeCards.capFps(it.rung) != null }?.let { OptimizeCards.capFps(it.rung) }
    }
}

/** The kind of change a step makes, as far as the status says. */
enum class OptimizeChange {
    SETTINGS,
    CORES,
    COMPONENTS,
    FPS_HOLD,
    ;

    companion object {
        private val SETTINGS_KEYS = OptimizeApplier.EXTRA_KEYS + "screenSize"

        fun of(rung: Rung): OptimizeChange? {
            if (rung.isFpsCap) return FPS_HOLD
            val keys = rung.levers.filterIsInstance<Lever.ContainerSet>().flatMap { it.set }.map { it.first.removePrefix("extras.") }
            return when {
                rung.levers.any { it is Lever.EngineFile || it is Lever.Registry } || keys.any { it in SETTINGS_KEYS } -> SETTINGS
                keys.any { it.startsWith("cpuList") } -> CORES
                keys.isNotEmpty() || rung.levers.any { it is Lever.Drirc } -> COMPONENTS
                else -> null
            }
        }
    }
}

sealed class EntitlementLine {
    data object Unlimited : EntitlementLine()

    /** Signed out: free to try on [limit] games with an account. */
    data class Offer(val limit: Int) : EntitlementLine()

    /** Free tier with games left. */
    data class Trial(val used: Int, val limit: Int) : EntitlementLine()

    /** Free tier with every game used. */
    data class Exhausted(val limit: Int) : EntitlementLine()

    /** Monthly plan: [used] of [limit] games this period, which starts over at [resetsAt] when known. */
    data class Monthly(val used: Int, val limit: Int, val resetsAt: Long?) : EntitlementLine() {
        val usedUp: Boolean get() = used >= limit
    }
}

/**
 * A string or plural resource with its arguments: numbers, or a [PlanName] the UI turns into the plan's shown name.
 * [date], when set, is formatted and passed last.
 */
data class PlanText(val res: Int, val quantity: Int? = null, val args: List<Any> = emptyList(), val date: Long? = null)

/** A membership plan named in Optimize copy, shown with the support paywall's own plan names. */
enum class PlanName { BASIC, PRO }
