package app.gamenative.ui.screen.xr

import androidx.compose.ui.focus.FocusManager

/**
 * Everything ImmersiveXrActivity threads into [app.gamenative.ui.screen.xserver.XServerScreen],
 * bundled into a single parameter: XServerScreen sits at the dex verifier's register limit, and
 * extra parameters/locals there have tripped runtime VerifyErrors (compiles clean, crashes at
 * class load on entering a game) before. Null on every non-immersive launch.
 *
 * See QuickMenu's register* parameter kdocs for what each hook is for and why the immersive
 * activity needs these bypasses at all.
 */
class ImmersiveSessionHooks(
    // Presence of controls is what makes the QuickMenu's Immersive tab show up.
    val controls: ImmersiveControls,
    // ImmersiveXrActivity switches its capture source while any overlay is up — see its
    // quickMenuVisible kdoc.
    val onQuickMenuVisibilityChanged: (Boolean) -> Unit = {},
    val registerFocusManager: ((FocusManager) -> Unit)? = null,
    val registerCycleTab: (((Boolean) -> Unit) -> Unit)? = null,
    val registerAdjustmentControl: ((() -> Pair<() -> Unit, () -> Unit>?) -> Unit)? = null,
    val registerFocusTabRail: ((() -> Unit) -> Unit)? = null,
    val registerFocusedActivate: ((() -> (() -> Unit)?) -> Unit)? = null,
    // Open/close only, no IME/controller-rescan/touchpad side effects — used by the
    // long-press-Start gesture instead of the shared registerBackAction handler.
    val registerToggle: ((() -> Unit) -> Unit)? = null,
    val registerStartHeld: (((Boolean) -> Unit) -> Unit)? = null,
)
