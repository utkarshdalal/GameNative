package app.gamenative.display

import app.gamenative.R

/**
 * A Look the QuickMenu can apply: a bundled Gamma-2.2 3D LUT, or [OFF] for a bare `set_look`
 * (clears the Gamma 2.2 look).
 */
enum class GamescopeLook(val id: String, val labelRes: Int) {
    OFF("", R.string.gamescope_look_off),
    GRAYSCALE("grayscale", R.string.gamescope_look_grayscale),
    INVERT_COLORS("invertcolors", R.string.gamescope_look_invertcolors),
    INVERT_BRIGHTNESS("invertbrightness", R.string.gamescope_look_invertbrightness),
    CB_DEUT("cb_deut", R.string.gamescope_look_cb_deut),
    CB_PROT("cb_prot", R.string.gamescope_look_cb_prot),
    CB_TRIT("cb_trit", R.string.gamescope_look_cb_trit);

    /** The LUT file gamescope ships for this look (`<id>_g22.cube`). */
    val lutFileName: String get() = "${id}_g22.cube"

    companion object {
        /** The look a stored id names, or null when absent/unknown (OFF maps to [OFF]). */
        fun fromId(id: String?): GamescopeLook? = when {
            id == null -> null
            id.isEmpty() -> OFF
            else -> entries.firstOrNull { it.id == id }
        }
    }
}
