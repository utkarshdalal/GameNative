package app.gamenative.ui.screen.xr

/**
 * State + callbacks for the "Immersif" quick-menu tab (see QuickMenu.kt). Passed into
 * [app.gamenative.ui.screen.xserver.XServerScreen] only when running inside
 * [ImmersiveXrActivity] — its presence is what makes the tab show up at all.
 */
data class ImmersiveControls(
    val passthroughEnabled: Boolean,
    val onPassthroughToggle: (Boolean) -> Unit,
    // No slider-based position/size controls here anymore, by explicit request — the
    // trigger-driven grab handles (ImmersiveXrActivity's own quadDistance/quadHorizontal/
    // quadVertical/quadScale fields, moved via tryStartPointerGrab/updateActiveGrab) are the ONLY
    // way to move/resize the quad now, not an alternative alongside a slider-based one.
    // Vulkan's zero-copy direct-render path (better performance) only engages when no screen
    // effect/filter/brightness/contrast/gamma adjustment is active — null when the renderer isn't
    // VulkanRenderer at all (nothing to reset), so the button only shows when it's actually
    // relevant and possible.
    val directRenderBlockedByEffects: Boolean? = null,
    val onResetScreenEffects: () -> Unit = {},
    // Shows/enables the resize+move handles (drawn just inside the game content's own edges,
    // not in an outside margin — see ImmersiveXrActivity's resizeHandlesEnabled kdoc for why:
    // an always-present margin band cost a real chunk of the direct-render path's fixed
    // swapchain pixel budget, visible as pixelation).
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
