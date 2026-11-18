package app.gamenative.display

/** gamescope `feature_support` bits the QuickMenu gates settings on. */
enum class GamescopeFeature(val mask: Int) {
    LOOK(0x40),
    SGSR_FILTER(0x200);

    companion object {
        /** Whether [feature] is advertised in a `features=0x<hex>` mask. */
        fun advertised(mask: Int, feature: GamescopeFeature): Boolean = mask and feature.mask != 0
    }
}
