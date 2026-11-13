package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.themeFill

/** True when the theme gives a focused row its own fill or underline. */
val hasThemedRowFocus: Boolean
    @Composable
    @ReadOnlyComposable
    get() {
        val f = PluviaTheme.spec.focusStyle ?: return false
        return (f.fill != null && !f.fill.isEmpty) || f.underline != null
    }

/** True when the theme paints a fill under a focused row. */
val hasThemedRowFocusFill: Boolean
    @Composable
    @ReadOnlyComposable
    get() = PluviaTheme.spec.focusStyle?.fill?.isEmpty == false

/**
 * The theme's focused-row look while [focused]: [base] and the theme focus fill inside [shape] behind the row, and the
 * theme focus underline along its bottom edge. Returns this modifier unchanged otherwise.
 */
@Composable
fun Modifier.themedRowFocus(
    focused: Boolean,
    shape: Shape = RectangleShape,
    base: Color = Color.Transparent,
): Modifier {
    if (!focused || !hasThemedRowFocus) return this
    val style = PluviaTheme.focusStyle
    var m = this
    if (style.fill != null && !style.fill.isEmpty) {
        if (base.alpha > 0f) m = m.background(base, shape)
        m = m.themeFill(style.fill, shape)
    }
    val line = style.underline
    if (line != null) {
        val height = style.underlineHeight
        m = m.drawWithContent {
            drawContent()
            val px = height.toPx()
            drawRect(line, Offset(0f, size.height - px), Size(size.width, px))
        }
    }
    return m
}

/** Text and icons on a focused row: the theme's colour on its focus fill while [focused], else [default]. */
@Composable
@ReadOnlyComposable
fun themedRowFocusContent(focused: Boolean, default: Color): Color {
    if (!focused || !hasThemedRowFocusFill) return default
    return PluviaTheme.spec.focusStyle?.onFill?.let { Color(it) } ?: default
}
