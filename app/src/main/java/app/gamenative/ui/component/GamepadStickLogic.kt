package app.gamenative.ui.component

import androidx.compose.ui.focus.FocusDirection

/**
 * Pure, JVM-testable stick/hysteresis decision logic shared by both gamepad focus
 * navigators (spec 2026-08-10, §3.1 — root cause RC1).
 *
 * RC1: the old per-navigator logic re-armed only when the stick dropped below a 0.30
 * release zone. A stick resting in drift at 0.30–0.44 (below the 0.45 dead zone, above
 * the release zone) never re-armed, so the FIRST movement killed navigation until the
 * stick physically returned to center.
 *
 * Fix: re-arm whenever `magnitude < deadZone` (0.45); the [cooldownMs] still prevents
 * free-run while the stick is held. Immutable FP state: [decide] returns the successor
 * state alongside the move decision — no mutable state inside the logic.
 */
enum class GamepadStickDirection { Up, Down, Left, Right }

/** Immutable navigator state; [GamepadStickLogic.decide] returns the successor. */
data class GamepadStickState(
    val armed: Boolean = true,
    val lastMoveAt: Long = 0L,
)

/** Result of one decision: the next state plus an optional move. */
data class GamepadStickDecision(
    val state: GamepadStickState,
    val direction: GamepadStickDirection?,
)

object GamepadStickLogic {

    /**
     * Decides what one axis/hat sample means for focus navigation.
     *
     * - `direction == null` (neutral sample): never disarms; re-arms once the stick is
     *   (mostly) centered (`magnitude < deadZone`) — the RC1 fix.
     * - A real push while disarmed: consumed without moving (the stick never left the
     *   dead zone, so no repeat).
     * - A real push inside [cooldownMs] of the last move: consumed without moving, but
     *   the armed state is preserved (a fresh center + push within the cooldown still
     *   moves once the cooldown elapses).
     * - Otherwise: move, disarm, stamp [now].
     */
    fun decide(
        previous: GamepadStickState,
        now: Long,
        magnitude: Float,
        direction: GamepadStickDirection?,
        deadZone: Float = 0.45f,
        cooldownMs: Long = 180L,
    ): GamepadStickDecision {
        if (direction == null) {
            return GamepadStickDecision(
                state = previous.copy(armed = previous.armed || magnitude < deadZone),
                direction = null,
            )
        }
        if (!previous.armed) {
            return GamepadStickDecision(state = previous, direction = null)
        }
        if (now - previous.lastMoveAt < cooldownMs) {
            return GamepadStickDecision(state = previous, direction = null)
        }
        return GamepadStickDecision(
            state = GamepadStickState(armed = false, lastMoveAt = now),
            direction = direction,
        )
    }
}

/** Maps a stick direction to the Compose focus direction (spec §3.8 — extension). */
val GamepadStickDirection.focusDirection: FocusDirection
    get() = when (this) {
        GamepadStickDirection.Up -> FocusDirection.Up
        GamepadStickDirection.Down -> FocusDirection.Down
        GamepadStickDirection.Left -> FocusDirection.Left
        GamepadStickDirection.Right -> FocusDirection.Right
    }
