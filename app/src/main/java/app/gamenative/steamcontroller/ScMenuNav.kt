package app.gamenative.steamcontroller

/**
 * The **fixed, user-un-editable** control scheme for navigating GameNative's own menus / SC editors (the QuickMenu
 * and every in-game Steam-Controller editor). This is the single source of truth: [ProfileInterpreter.handleMenuNav]
 * drives nav from [controls], and the editor tooltips / help dialog render their labels from the same list — so a
 * tooltip can never drift from what the button actually does, and every controller navigates our menus identically.
 *
 * Deliberately NOT part of the editable [ScProfile] / `.vdf` config: these controls are reserved for menu nav and are
 * the same regardless of the game's bindings. (Directional focus movement is structural — d-pad OR left stick — so it
 * isn't a single edge button; it's described by [DIRECTIONS_HINT]/[DIRECTIONS_DESC] rather than a [Control].)
 */
object ScMenuNav {
    /** One fixed edge-triggered menu-nav control: a physical button, the nav intent it fires, and its tooltip text. */
    data class Control(val key: ScNavKey, val buttonBit: Int, val hint: String, val desc: String)

    const val DIRECTIONS_HINT = "D-pad / Left stick"
    const val DIRECTIONS_DESC = "Move focus (hold to repeat)"

    /** Edge-triggered controls, in tooltip order. */
    val controls: List<Control> = listOf(
        Control(ScNavKey.SELECT, TritonProtocol.BTN_A, "A", "Select"),
        Control(ScNavKey.BACK, TritonProtocol.BTN_B, "B", "Back (one level)"),
        Control(ScNavKey.BACK, TritonProtocol.BTN_STEAM, "Steam", "Close menu"),
        Control(ScNavKey.TAB_PREV, TritonProtocol.BTN_LBUMPER, "LB", "Previous tab / action set"),
        Control(ScNavKey.TAB_NEXT, TritonProtocol.BTN_RBUMPER, "RB", "Next tab / action set"),
        Control(ScNavKey.HELP, TritonProtocol.BTN_Y, "Y", "Help"),
        Control(ScNavKey.CLOSE, TritonProtocol.BTN_VIEW, "Start", "Close editor (back to game)"),
    )

    /** Tooltip label for the control that fires [key] (e.g. HELP -> "Y"), or empty if none. */
    fun hintFor(key: ScNavKey): String = controls.firstOrNull { it.key == key }?.hint ?: ""

    /** Multi-line "how to navigate" text for the help dialog — derived, so it's always accurate. */
    fun helpLines(): List<String> =
        listOf("$DIRECTIONS_HINT — $DIRECTIONS_DESC") + controls.map { "${it.hint} — ${it.desc}" }
}
