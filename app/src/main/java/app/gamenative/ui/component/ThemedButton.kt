package app.gamenative.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.themeFill

/** True when the theme styles secondary buttons beyond a flat color: fill gradient, focused fill or text, underline. */
val hasThemedButtonLook: Boolean
    @Composable
    @ReadOnlyComposable
    get() {
        val k = PluviaTheme.spec.controls ?: return false
        return k.buttonFill?.isEmpty == false || k.buttonFocusFill?.isEmpty == false || k.onButtonFocus != null ||
            (k.buttonUnderlineDp ?: 0f) > 0f
    }

/** True when the theme gives secondary buttons a fill of their own, so an outline would sit on top of it. */
val hasThemedButtonFill: Boolean
    @Composable
    @ReadOnlyComposable
    get() {
        val k = PluviaTheme.spec.controls ?: return false
        return k.buttonFill?.isEmpty == false || k.buttonFocusFill?.isEmpty == false
    }

/**
 * A secondary button's theme fill inside [shape] (its focused fill while [focused]) and the theme button underline
 * along the bottom edge. Returns this modifier unchanged without them.
 */
@Composable
fun Modifier.themedButtonFill(focused: Boolean, shape: Shape): Modifier {
    val k = PluviaTheme.spec.controls ?: return this
    val idle = k.buttonFill?.takeIf { !it.isEmpty }
    val fill = if (focused) k.buttonFocusFill?.takeIf { !it.isEmpty } ?: idle else idle
    val line = PluviaTheme.buttonUnderline
    var m = this
    if (fill != null) m = m.themeFill(fill, shape)
    if (line != null) {
        m = m.drawWithContent {
            drawContent()
            val px = line.width.toPx()
            drawRect(line.brush, Offset(0f, size.height - px), Size(size.width, px))
        }
    }
    return m
}

/** Text and icons on a secondary button: the theme's focused button text color while [focused], else [default]. */
@Composable
@ReadOnlyComposable
fun themedButtonContent(focused: Boolean, default: Color): Color =
    if (focused) PluviaTheme.onButtonFocusColor(default) else default

/**
 * [OutlinedButton] in the theme's secondary button look (fill, focused fill and text, underline) when it has one;
 * otherwise the plain [OutlinedButton] with these arguments.
 */
@Composable
@NonRestartableComposable
fun ThemedOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (!hasThemedButtonLook) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            contentPadding = contentPadding,
            interactionSource = interactionSource,
            content = content,
        )
    } else {
        ThemedLookOutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
    }
}

@PublishedApi
@Composable
internal fun ThemedLookOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    colors: ButtonColors,
    elevation: ButtonElevation?,
    border: BorderStroke?,
    contentPadding: PaddingValues,
    interactionSource: MutableInteractionSource?,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val filled = hasThemedButtonFill
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.themedButtonFill(focused, shape),
        enabled = enabled,
        shape = shape,
        colors = colors.copy(
            containerColor = if (filled) Color.Transparent else colors.containerColor,
            contentColor = themedButtonContent(focused, colors.contentColor),
        ),
        elevation = elevation,
        border = if (filled) null else border,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}
