package app.gamenative.display

import app.gamenative.R

/**
 * The scaler a gamescope session runs with (gamescope's `GamescopeUpscaleScaler`, also its
 * `--scaler` argument).
 */
enum class GamescopeScalingMode(val id: String, val value: Int, val labelRes: Int) {
    AUTO("auto", 0, R.string.gamescope_scaler_auto),
    INTEGER("integer", 1, R.string.gamescope_scaler_integer),
    FIT("fit", 2, R.string.gamescope_scaler_fit),
    // STRETCH=4 / FILL=3 are gamescope's enum order, not this list's order.
    STRETCH("stretch", 4, R.string.gamescope_scaler_stretch),
    FILL("fill", 3, R.string.gamescope_scaler_fill);

    companion object {
        /** gamescope's own default (`g_wantedUpscaleScaler`), passed as no argument. */
        val DEFAULT = AUTO

        /** The mode a stored id names, or null when it is unknown/absent. */
        fun fromId(id: String?): GamescopeScalingMode? =
            id?.takeIf { it.isNotEmpty() }?.let { v -> entries.firstOrNull { it.id == v } }
    }
}
