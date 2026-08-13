package app.gamenative.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Single source of truth for "who consumes gamepad input right now" (D3,
 * docs/superpowers/specs/2026-08-08-gamepad-input-refactoring-design.md).
 *
 * The XServerScreen computes this from its overlay states in ONE place; the key and
 * motion handlers only consult it, so a new overlay cannot silently leak gamepad
 * input to the game (P2-8).
 */
enum class OverlayInputContext {
    /** No overlay is open — gamepad input goes to the game (PhysicalControllerHandler/WinHandler). */
    NONE,

    /** An overlay (menu, dialog, editor) is open — gamepad input goes to the Compose UI. */
    OVERLAY,
}

/**
 * Mutable holder for the routing context (spec 2026-08-12, M1 — C1: stale routing state).
 *
 * C1: the bus handlers in XServerScreen are registered ONCE in `DisposableEffect(Unit)`, so
 * any closure that captured the derived `val overlayInputContext` from the FIRST composition
 * would read NONE forever — the game would keep receiving gamepad input behind an open menu
 * (the exact "overlay surdo, jogo respondendo" scenario) and, worse, keep re-scanning SDL
 * controller mappings on every button press (C2).
 *
 * Fix: the XServerScreen WRITES this holder during composition (same single computation as
 * before) and the bus handlers READ it at event time. The handlers capture the HOLDER
 * (stable `remember` instance), never the value — no stale routing, no re-registration.
 */
class OverlayInputState {
    var context by mutableStateOf(OverlayInputContext.NONE)
        internal set
}
