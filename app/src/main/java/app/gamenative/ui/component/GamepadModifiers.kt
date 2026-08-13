package app.gamenative.ui.component

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Pure, JVM-testable decision logic behind the gamepad modifier framework
 * (spec 2026-08-09, §3.1 — "testável em JVM").
 *
 * Every function takes plain [KeyEvent] ints (compile-time constants, safe in unit tests) and
 * answers one question: should this key press be consumed, and what should happen?
 * The Compose modifiers below are thin wrappers that translate [KeyEvent]s into these
 * decisions and apply the effects.
 */
object GamepadKeyLogic {

    /** What a row-level handler decides for one key event. */
    enum class AdjustAction { Ignore, ToggleLock, Unlock, AdjustLeft, AdjustRight }

    /**
     * `gamepadSelectable`: consume A / DPAD_CENTER / ENTER only on ACTION_DOWN with
     * repeatCount == 0 and only when the node is focused. Anything else propagates to the
     * parent (navigation, back, repeats).
     */
    fun selectableActivation(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isFocused: Boolean,
    ): Boolean {
        if (!isFocused || action != KeyEvent.ACTION_DOWN || repeatCount != 0) return false
        return keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
    }

    /**
     * `gamepadAdjustableRow`: A/DPAD_CENTER toggles the lock (down, repeat 0); while locked,
     * raw B unlocks and DPAD_LEFT/RIGHT adjust (repeats allowed — holding the D-pad keeps
     * adjusting); when unlocked, B and DPAD_L/R propagate (hierarchical back / navigation).
     */
    fun adjustableAction(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isFocused: Boolean,
        isLocked: Boolean,
    ): AdjustAction {
        if (action != KeyEvent.ACTION_DOWN || !isFocused) return AdjustAction.Ignore
        return when {
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) &&
                repeatCount == 0 -> AdjustAction.ToggleLock

            isLocked && keyCode == KeyEvent.KEYCODE_BUTTON_B -> AdjustAction.Unlock
            isLocked && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> AdjustAction.AdjustLeft
            isLocked && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> AdjustAction.AdjustRight
            else -> AdjustAction.Ignore
        }
    }

    /**
     * `gamepadSelectable` optional Y handler (spec 2026-08-12, M3 — favoritos no browser):
     * raw Y on ACTION_DOWN with repeatCount == 0 and only when the node is focused. Y is
     * not consumed by any other surface today, so rows that opt in never fight navigation.
     */
    fun selectableYKey(keyCode: Int, action: Int, repeatCount: Int, isFocused: Boolean): Boolean =
        isFocused && action == KeyEvent.ACTION_DOWN && repeatCount == 0 &&
            keyCode == KeyEvent.KEYCODE_BUTTON_Y

    /**
     * `gamepadBackHandler`: consumes only the RAW gamepad B (KEYCODE_BUTTON_B) on ACTION_DOWN.
     * Physical BACK is deliberately NOT handled here — it goes through the OnBackPressedDispatcher
     * (BackHandler) so the two paths can never double-fire (spec §3.1, "caminhos disjuntos").
     */
    fun back(keyCode: Int, action: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BUTTON_B && action == KeyEvent.ACTION_DOWN
}

/**
 * Single activation mechanism for every gamepad-activatable row
 * (spec 2026-08-09, §3.1 — replaces `gamepadActivate`, inline `onPreviewKeyEvent` activation
 * and the `selectable(selected, onClick = {})` combos that swallowed DPAD_CENTER).
 *
 * - A / DPAD_CENTER / ENTER (ACTION_DOWN, repeat 0) while focused → [onClick], consumed.
 *   Works with the bridge (A arrives as synthetic DPAD_CENTER) and without it (raw A).
 * - Implicit focus (via [clickable]'s own focusable node, same [interactionSource]) and the
 *   D7 visual: focused → animated ring, selected → persistent accent border.
 * - Touch taps still activate; semantics keep the a11y "selected" state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.gamepadSelectable(
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape,
    interactionSource: MutableInteractionSource,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onYKey: (() -> Unit)? = null,
): Modifier {
    val isFocused by interactionSource.collectIsFocusedAsState()
    return this
        .gamepadFocus(
            state = when {
                isFocused -> GamepadFocusState.Focused
                selected -> GamepadFocusState.Selected
                else -> null
            },
            shape = shape,
            interactionSource = interactionSource,
            accentColor = accentColor,
        )
        .onPreviewKeyEvent { keyEvent ->
            val native = keyEvent.nativeKeyEvent
            if (GamepadKeyLogic.selectableActivation(
                    keyCode = native.keyCode,
                    action = native.action,
                    repeatCount = native.repeatCount,
                    isFocused = isFocused,
                )
            ) {
                onClick()
                true
            } else if (onYKey != null && GamepadKeyLogic.selectableYKey(
                    keyCode = native.keyCode,
                    action = native.action,
                    repeatCount = native.repeatCount,
                    isFocused = isFocused,
                )
            ) {
                onYKey()
                true
            } else {
                false
            }
        }
        .then(
            if (onLongClick != null) {
                // Long-press affordance (shader browser cancel, spec 2026-08-12 UX fix 1):
                // this Compose version's clickable() has no onLongClick overload, so the
                // long-press-capable variant is used ONLY when a handler exists (regular
                // rows keep the plain clickable path unchanged).
                Modifier.combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
            } else {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
            },
        )
        .semantics { this.selected = selected }
}

/**
 * Standard adjustment-row behavior (spec 2026-08-09, §3.1 — replaces the duplicated
 * QuickMenuAdjustmentRow / ScreenEffectAdjustmentRow key handling):
 *
 * A / DPAD_CENTER toggles [locked]; while locked, raw B unlocks and DPAD_LEFT/RIGHT call
 * [onAdjust](-1/+1) (repeats adjust continuously). The lock resets when the row loses focus.
 * DPAD_L/R and B propagate when unlocked (navigation / hierarchical back).
 *
 * The caller owns the [locked] state and renders the `●` indicator; this modifier provides the
 * focus visual (Focused when focused, Locked ring when locked) and focusability.
 */
@Composable
fun Modifier.gamepadAdjustableRow(
    locked: Boolean,
    onLockChange: (Boolean) -> Unit,
    onAdjust: (Int) -> Unit,
    shape: Shape,
    interactionSource: MutableInteractionSource,
    accentColor: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    val isFocused by interactionSource.collectIsFocusedAsState()
    return this
        .gamepadFocus(
            state = when {
                locked -> GamepadFocusState.Locked
                isFocused -> GamepadFocusState.Focused
                else -> null
            },
            shape = shape,
            interactionSource = interactionSource,
            accentColor = accentColor,
        )
        .onFocusChanged { if (!it.isFocused && locked) onLockChange(false) }
        .onPreviewKeyEvent { keyEvent ->
            val native = keyEvent.nativeKeyEvent
            when (GamepadKeyLogic.adjustableAction(
                keyCode = native.keyCode,
                action = native.action,
                repeatCount = native.repeatCount,
                isFocused = isFocused,
                isLocked = locked,
            )) {
                GamepadKeyLogic.AdjustAction.ToggleLock -> {
                    onLockChange(!locked)
                    true
                }
                GamepadKeyLogic.AdjustAction.Unlock -> {
                    onLockChange(false)
                    true
                }
                GamepadKeyLogic.AdjustAction.AdjustLeft -> {
                    onAdjust(-1)
                    true
                }
                GamepadKeyLogic.AdjustAction.AdjustRight -> {
                    onAdjust(1)
                    true
                }
                GamepadKeyLogic.AdjustAction.Ignore -> false
            }
        }
        .focusable(interactionSource = interactionSource)
}

/**
 * Hierarchical "B" for a surface: reacts to the RAW gamepad B and runs [onBack].
 *
 * Implemented in the MAIN phase (`onKeyEvent`, bottom-up from the focused node), so the
 * innermost surface consuming B always wins — a focused adjustment row unlocks before a
 * parent surface backs out. Register the same lambda with the physical `BackHandler` (see
 * [GamepadFocusScope]) for touch/BACK parity: raw B never reaches the dispatcher and physical
 * BACK never reaches Compose, so the paths are disjoint by construction.
 */
fun Modifier.gamepadBackHandler(onBack: () -> Unit): Modifier = onKeyEvent { keyEvent ->
    val native = keyEvent.nativeKeyEvent
    if (GamepadKeyLogic.back(native.keyCode, native.action)) {
        onBack()
        true
    } else {
        false
    }
}

/**
 * Window/overlay bootstrap for gamepad input (spec 2026-08-09, §3.1 — replaces the manual
 * `JoystickFocusNavigator` + `GamepadKeyBridge` + `BackHandler` + focus-request dance
 * repeated in every Dialog/overlay).
 *
 * Installs inside a Box:
 * - [JoystickFocusNavigator] (stick/hat → focus) and [GamepadKeyBridge] (A → DPAD_CENTER);
 * - a physical [BackHandler] wired to [backAction] (touch/back-button parity — apply
 *   `Modifier.gamepadBackHandler(backAction)` to the surface content for the raw-B path);
 * - initial focus via [initialFocusRequester] once [enabled] (retries while the window's
 *   composition settles, mirroring the old bootstrap loops).
 */
@Composable
fun GamepadFocusScope(
    enabled: Boolean = true,
    backAction: (() -> Unit)? = null,
    initialFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        JoystickFocusNavigator(enabled = enabled)
        GamepadKeyBridge(enabled = enabled)
        if (backAction != null) {
            BackHandler(enabled = enabled, onBack = backAction)
        }
        if (initialFocusRequester != null) {
            LaunchedEffect(enabled) {
                if (enabled) {
                    repeat(3) {
                        try {
                            initialFocusRequester.requestFocus()
                            return@LaunchedEffect
                        } catch (_: Exception) {
                            delay(80)
                        }
                    }
                }
            }
        }
        content()
    }
}

/**
 * Reports the index of the row that currently has focus (G9 remember-selection,
 * spec 2026-08-09 §3.3). Attach to every focusable row in a tab; the tab hoists the last
 * index into `rememberSaveable` so reopening the QuickMenu restores the position.
 */
fun Modifier.gamepadFocusIndex(index: Int, onFocusIndexChanged: (Int) -> Unit): Modifier =
    onFocusChanged {
        if (it.isFocused) {
            Timber.d("QMFocus: row %d focused", index)
            onFocusIndexChanged(index)
        }
    }
