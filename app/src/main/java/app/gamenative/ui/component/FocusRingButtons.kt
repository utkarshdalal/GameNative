@file:OptIn(ExperimentalFoundationApi::class)

package app.gamenative.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme

/*
 * Material controls with the shared focus ring (see focusRing), so the theme focus style applies. Same parameters
 * as the Material component, so a call site switches by name only; a passed interactionSource is shared with the
 * ring. Outlined buttons use ThemedOutlinedButton, which rings itself.
 */

private val RingWidth = 2.dp

@Composable
fun RingTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.focusRing(source, shape, width = RingWidth),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun RingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.focusRing(source, shape, width = RingWidth),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun RingFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = PluviaTheme.tonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.focusRing(source, shape, width = RingWidth),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun RingIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    // the ring sits on the 48dp touch target, outside the 40dp focused node, so scrolling to the focused node alone
    // can cut it off at the edge of a scroll area
    val ringInView = remember { BringIntoViewRequester() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) ringInView.bringIntoView() }
    IconButton(
        onClick = onClick,
        modifier = modifier.bringIntoViewRequester(ringInView).focusRing(source, CircleShape, width = RingWidth),
        enabled = enabled,
        colors = colors,
        interactionSource = source,
        content = content,
    )
}

@Composable
fun RingCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.focusRing(source, CircleShape, width = RingWidth),
        enabled = enabled,
        colors = colors,
        interactionSource = source,
    )
}

@Composable
fun RingRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    RadioButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier.focusRing(source, CircleShape, width = RingWidth),
        enabled = enabled,
        colors = colors,
        interactionSource = source,
    )
}

/** [clickable] plus the focus ring in [shape]. Put it where the element's clip/background ends so the ring fits. */
@Composable
fun Modifier.ringClickable(
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return clickable(
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    ).focusRing(source, shape, width = RingWidth)
}

/** [selectable] plus the focus ring in [shape]. */
@Composable
fun Modifier.ringSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    shape: Shape = RectangleShape,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return selectable(
        selected = selected,
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onClick = onClick,
    ).focusRing(source, shape, width = RingWidth)
}

/** [toggleable] plus the focus ring in [shape]. */
@Composable
fun Modifier.ringToggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    shape: Shape = RectangleShape,
    interactionSource: MutableInteractionSource? = null,
    onValueChange: (Boolean) -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return toggleable(
        value = value,
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onValueChange = onValueChange,
    ).focusRing(source, shape, width = RingWidth)
}
