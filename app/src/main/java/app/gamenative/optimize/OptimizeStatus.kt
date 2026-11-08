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
    data class Trying(val visualCostId: String?, val visualCostEn: String?) : OptimizeStatus()
    data object Paused : OptimizeStatus()
    data object Done : OptimizeStatus()
    data object Exhausted : OptimizeStatus()
    data object TrialUsed : OptimizeStatus()

    companion object {
        fun of(state: OptimizeLocalState): OptimizeStatus {
            val ladder = state.active ?: return if (state.never) Never else Off
            val trying = ladder.pending?.takeIf { it.revert == null }
            return when {
                trying != null && trying.shortSessions > 0 -> Measuring
                trying != null -> Trying(trying.rung.visualCostId, trying.rung.visualCostEn)
                ladder.state == LadderState.TRYING && ladder.lastStatus == NextRungResponse.STATUS_ENTITLEMENT_REQUIRED -> TrialUsed
                ladder.state == LadderState.IDLE -> NotStarted
                ladder.state == LadderState.TRYING -> TuningNext
                ladder.state == LadderState.PAUSED -> Paused
                ladder.state == LadderState.DONE -> Done
                else -> Exhausted
            }
        }

        /**
         * The one trial line a surface shows. Members never see trial text; [exhausted] is the game's
         * own entitlement_required answer, which outranks a stale stored count.
         */
        fun entitlementLine(entitlement: Entitlement?, exhausted: Boolean, signedIn: Boolean, member: Boolean): EntitlementLine? = when {
            member || entitlement?.isMember == true -> EntitlementLine.Member
            exhausted -> EntitlementLine.Exhausted(entitlement.limitOrDefault())
            !signedIn -> EntitlementLine.Offer(entitlement.limitOrDefault())
            entitlement == null -> null
            entitlement.state == Entitlement.TRIAL && entitlement.trialGamesLeft > 0 ->
                EntitlementLine.Trial(entitlement.trialGamesUsed, entitlement.limitOrDefault())
            else -> EntitlementLine.Exhausted(entitlement.limitOrDefault())
        }

        fun keptCount(state: OptimizeLocalState): Int = state.active?.kept?.count { it.applied } ?: 0

        /** The fps the active profile's kept cap holds the game at, or null when no cap is kept. */
        fun heldCapFps(state: OptimizeLocalState): Int? =
            state.active?.kept?.lastOrNull { it.applied && OptimizeCards.capFps(it.rung) != null }?.let { OptimizeCards.capFps(it.rung) }

        @StringRes
        fun visualCostRes(id: String?): Int? = when (id) {
            "vc_shadows_simpler" -> R.string.optimize_vc_shadows_simpler
            "vc_less_grass_view_distance" -> R.string.optimize_vc_less_grass_view_distance
            "vc_textures_softer" -> R.string.optimize_vc_textures_softer
            "vc_effects_simpler" -> R.string.optimize_vc_effects_simpler
            "vc_fps_held" -> R.string.optimize_vc_fps_held
            "vc_dx11_version" -> R.string.optimize_vc_dx11_version
            else -> null
        }
    }
}

sealed class EntitlementLine {
    data object Member : EntitlementLine()
    data class Offer(val limit: Int) : EntitlementLine()
    data class Trial(val used: Int, val limit: Int) : EntitlementLine()
    data class Exhausted(val limit: Int) : EntitlementLine()
}
