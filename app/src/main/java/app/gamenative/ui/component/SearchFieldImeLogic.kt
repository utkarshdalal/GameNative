package app.gamenative.ui.component

import android.view.KeyEvent

/**
 * Pure decision logic for the QuickMenu search-field IME (spec
 * 2026-08-10-search-field-ime-explicit-design).
 *
 * UX practice: a soft keyboard must only appear on explicit intent — X (A / DPAD_CENTER /
 * ENTER) opens it, B closes it, and merely navigating (stick/hat/walk-down) onto the field
 * never shows it. The Compose modifier in [ScreenEffectsPanel] is a thin wrapper over these
 * functions; everything here takes plain [KeyEvent] ints so it is unit-testable on the JVM.
 */
object SearchFieldImeLogic {

    /** What the search-field key handler decides for one key event. */
    enum class KeyAction {
        /** Show the IME and consume the event (X with the IME closed). */
        OpenIme,

        /** Hide the IME and consume the event (B with the IME open — the menu must NOT close). */
        CloseIme,

        /** Not our concern: the event flows to the parent (back, navigation, typing…). */
        Propagate,
    }

    /**
     * Decides what one key event should do on the focused search field.
     *
     * - X (A / DPAD_CENTER / ENTER, first down, field focused, IME closed) → [KeyAction.OpenIme]
     * - Same keys with the IME open → [KeyAction.Propagate] (ENTER must keep its Done action,
     *   and typing must never be disturbed).
     * - B with the IME open → [KeyAction.CloseIme] (consumed so the innermost surface wins and
     *   the menu does not close — the same hierarchy as `gamepadBackHandler`).
     * - B with the IME closed → [KeyAction.Propagate] (hierarchical back as usual).
     */
    fun onKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isFocused: Boolean,
        isImeVisible: Boolean,
    ): KeyAction {
        if (!isFocused) return KeyAction.Propagate
        if (action != KeyEvent.ACTION_DOWN) return KeyAction.Propagate
        return when {
            keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                if (isImeVisible) KeyAction.CloseIme else KeyAction.Propagate
            }
            !isImeVisible && repeatCount == 0 && (
                keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER
                ) -> KeyAction.OpenIme

            else -> KeyAction.Propagate
        }
    }
}
