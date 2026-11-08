package app.gamenative.optimize

import androidx.annotation.StringRes
import app.gamenative.R

sealed class OptimizeStatus {
    data object Off : OptimizeStatus()
    data object NotStarted : OptimizeStatus()
    data object Measuring : OptimizeStatus()
    data class Trying(val visualCostId: String?, val visualCostEn: String?) : OptimizeStatus()
    data class Kept(val count: Int) : OptimizeStatus()

    companion object {
        fun of(state: OptimizeLocalState): OptimizeStatus {
            if (state.profile == OptimizeProfile.OFF) return Off
            val trying = state.pending?.takeIf { it.revert == null }
            return when {
                trying != null -> Trying(trying.rung.visualCostId, trying.rung.visualCostEn)
                state.kept.isNotEmpty() -> Kept(state.kept.size)
                !state.started -> NotStarted
                else -> Measuring
            }
        }

        @StringRes
        fun visualCostRes(id: String?): Int? = when (id) {
            "vc_shadows_simpler" -> R.string.optimize_vc_shadows_simpler
            "vc_less_grass_view_distance" -> R.string.optimize_vc_less_grass_view_distance
            "vc_textures_softer" -> R.string.optimize_vc_textures_softer
            "vc_effects_simpler" -> R.string.optimize_vc_effects_simpler
            "vc_fps_held" -> R.string.optimize_vc_fps_held
            else -> null
        }
    }
}
