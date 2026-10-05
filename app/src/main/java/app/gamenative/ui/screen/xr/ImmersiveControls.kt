package app.gamenative.ui.screen.xr

/**
 * State + callbacks for the "Immersif" quick-menu tab (see QuickMenu.kt). Passed into
 * [app.gamenative.ui.screen.xserver.XServerScreen] only when running inside
 * [ImmersiveXrActivity] — its presence is what makes the tab show up at all.
 */
data class ImmersiveControls(
    val passthroughEnabled: Boolean,
    val onPassthroughToggle: (Boolean) -> Unit,
    val directRenderBlockedByEffects: Boolean? = null,
    val onResetScreenEffects: () -> Unit = {},
    val resizeModeEnabled: Boolean = false,
    val onResizeModeToggle: (Boolean) -> Unit = {},
) {
    companion object {
        const val MIN_DISTANCE = 1.0f
        const val MAX_DISTANCE = 5.0f
        const val DEFAULT_DISTANCE = 2.0f

        const val MIN_OFFSET = -2.0f
        const val MAX_OFFSET = 2.0f
        const val DEFAULT_OFFSET = 0.0f

        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 3.0f
        const val DEFAULT_SCALE = 1.0f

        const val BASE_WIDTH_METERS = 1.6f
        const val BASE_HEIGHT_METERS = 0.9f
    }
}
