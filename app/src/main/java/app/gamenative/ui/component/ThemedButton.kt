package app.gamenative.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.themeFill

/** True when the theme styles secondary buttons beyond a flat colour: fill gradient, focused fill or text, underline. */
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

/** Text and icons on a secondary button: the theme's focused button text colour while [focused], else [default]. */
@Composable
@ReadOnlyComposable
fun themedButtonContent(focused: Boolean, default: Color): Color =
    if (focused) PluviaTheme.onButtonFocusColor(default) else default
