package app.gamenative.shaders

/**
 * Double-click gesture logic (spec 2026-08-12, Missão 5): the SECOND press on the SAME
 * preset row within [WINDOW_MS] "applies AND closes" the QuickMenu — a fast experiment
 * loop (PS → pick shader → A A → see the game → PS → repeat). Pure and JVM-testable.
 *
 * Rules (spec §5.1):
 *  - The first press has NO delay — it activates immediately (Activate).
 *  - The gesture only arms when the preset was actually applied ([armedPath] is set only
 *    by a successful apply; cloud rows and failed applies never arm).
 *  - A second press on the SAME path inside the window ⇒ ConfirmAndClose.
 *  - Outside the window (or on a different path) ⇒ Activate again (re-arming the gesture
 *    for the new row — two quick presses on different rows just switch shaders).
 *  - The toggle-off of an already-active preset (commit d73a83cc) is naturally preserved:
 *    inside the window the second press confirms-and-closes; outside it falls through to
 *    the normal activation path (which toggles off).
 */
object ShaderDoubleClickLogic {

    // 400 ms (raised from 300): two comfortable gamepad A presses land ~350-450 ms apart.
    const val WINDOW_MS = 400L

    enum class Action { Activate, ConfirmAndClose }

    /**
     * [armedPath]/[armedAtMs] = last preset that was really applied (null when none).
     * [nowMs] is a monotonic clock (SystemClock.uptimeMillis).
     */
    fun decide(
        armedPath: String?,
        armedAtMs: Long,
        path: String,
        nowMs: Long,
    ): Action =
        if (armedPath == path && nowMs - armedAtMs in 0..WINDOW_MS) Action.ConfirmAndClose
        else Action.Activate
}
