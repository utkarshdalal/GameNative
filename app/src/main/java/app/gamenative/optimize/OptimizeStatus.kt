package app.gamenative.optimize

import androidx.annotation.StringRes
import app.gamenative.R

sealed class OptimizeStatus {
    data object Off : OptimizeStatus()
    data object Never : OptimizeStatus()
    data object NotStarted : OptimizeStatus()
    data object Measuring : OptimizeStatus()
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
                trying != null -> Trying(trying.rung.visualCostId, trying.rung.visualCostEn)
                ladder.state == LadderState.TRYING && ladder.lastStatus == NextRungResponse.STATUS_ENTITLEMENT_REQUIRED -> TrialUsed
                ladder.state == LadderState.IDLE -> NotStarted
                ladder.state == LadderState.TRYING -> Measuring
                ladder.state == LadderState.PAUSED -> Paused
                ladder.state == LadderState.DONE -> Done
                else -> Exhausted
            }
        }

        fun keptCount(state: OptimizeLocalState): Int = state.active?.kept?.count { it.applied } ?: 0

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
