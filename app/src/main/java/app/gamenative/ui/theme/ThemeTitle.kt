package app.gamenative.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A game title that follows the theme title alignment and outline. Without either it is a plain [Text].
 * The outline is a stroked copy laid out under the fill with the same constraints.
 */
@Composable
fun ThemedTitleText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val layout = PluviaTheme.layout
    val align = if (PluviaTheme.centerTitles) TextAlign.Center else textAlign
    val strokeDp = layout.titleStrokeDp?.takeIf { it > 0f }
    if (strokeDp == null) {
        Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = align,
        )
        return
    }
    val strokeColor = layout.titleStrokeColor?.let { Color(it) } ?: Color.Black
    val strokePx = with(LocalDensity.current) { strokeDp.dp.toPx() }
    Box(modifier = modifier, propagateMinConstraints = true) {
        Text(
            text = text,
            style = style.copy(shadow = null, drawStyle = Stroke(width = strokePx * 2f, join = StrokeJoin.Round)),
            color = strokeColor,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = align,
        )
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = align,
        )
    }
}
