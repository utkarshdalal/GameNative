package app.gamenative.ui.component

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Colored focus ring (sweep gradient border rotating while focused).
 *
 * Kept as a thin wrapper over the gamepad focus language ([Modifier.gamepadFocus] in
 * [GamepadFocusState.Focused]) for surfaces OUTSIDE the QuickMenu (LibraryGridCard, InfoCard…),
 * so the animated-ring behavior is byte-for-byte the same as before (spec 2026-08-09, §4).
 *
 * Pass the element's clickable [interactionSource] so focus is tracked, and apply this after the
 * clip/background so the border draws on top. [durationMillis] is one full rotation.
 */
@Composable
fun Modifier.focusRing(
    interactionSource: InteractionSource,
    shape: Shape,
    width: Dp = 4.dp,
    durationMillis: Int = 5000,
): Modifier = gamepadFocus(
    state = GamepadFocusState.Focused,
    shape = shape,
    interactionSource = interactionSource,
    width = width,
    durationMillis = durationMillis,
)
