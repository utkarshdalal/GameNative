package app.gamenative.steamcontroller

/**
 * Step-6 overlay HUD seam. [ProfileInterpreter] (pure logic, background thread) calls this when a Radial/Touch
 * menu is active so a UI layer can draw the ring/grid + highlight the selected slot. Kept as a tiny interface
 * with no Android types so the interpreter stays unit-testable; the real Android renderer is `ScMenuOverlayView`.
 * Calls are best-effort — the interpreter wraps them so a UI failure can never break input.
 */
interface ScMenuOverlay {
    /** Show/update the menu with the currently highlighted slot (or -1 = none). Called while the pad is touched. */
    fun showMenu(spec: ScMenuSpec)
    /** Hide the menu (pad released / committed / mode changed). */
    fun hideMenu()
    /** Briefly show a centered status toast (e.g. the new action-set name on a set switch); auto-fades. */
    fun toast(text: String) {}
}

/** A snapshot of the active menu for the overlay to draw. */
data class ScMenuSpec(
    val kind: Kind,
    /** Per-slot display labels (may be blank), in slot order. */
    val labels: List<String>,
    /** Grid dimensions for [Kind.GRID]; ignored for [Kind.RADIAL]. */
    val cols: Int = 0,
    val rows: Int = 0,
    /** Index of the highlighted slot, or -1 when nothing is selected (finger in the dead-zone). */
    val highlighted: Int = -1,
    /** Radial center button label (Steam `touch_menu_button_0`); null = no center. Drawn in the ring's middle. */
    val centerLabel: String? = null,
    /** Live source position for a cursor dot (radial only): normalized −1..1, x right+, y up+; NaN = no cursor.
     *  Lets the HUD show exactly where the thumb points (incl. resting over the center "Wait" hub). */
    val cursorX: Float = Float.NaN,
    val cursorY: Float = Float.NaN,
    /** Which surface hosts this menu ("LEFT_PAD"/"RIGHT_PAD"/"LEFT_STICK"/"RIGHT_STICK", = [ScMenuLocation.name]);
     *  the overlay resolves per-menu placement by this id so each menu can sit in its own spot. "" = unscoped. */
    val menuId: String = "",
) {
    enum class Kind { RADIAL, GRID }
}

/** Default sink that draws nothing (used in tests / when no UI is attached). */
object NoOpScMenuOverlay : ScMenuOverlay {
    override fun showMenu(spec: ScMenuSpec) {}
    override fun hideMenu() {}
}
