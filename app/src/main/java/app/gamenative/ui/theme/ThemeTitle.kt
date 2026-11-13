package app.gamenative.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * A game title that follows the theme title alignment and outline. Without either it is a plain [Text].
 * The outline is drawn by copies laid out under the fill with the same constraints.
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
    val align = if (PluviaTheme.centerTitles) TextAlign.Center else textAlign
    val outline = PluviaTheme.titleOutline
    @Suppress("NAME_SHADOWING")
    val style = PluviaTheme.titleTextStyle(style)
    if (outline == null) {
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
    OutlinedText(text, style, color, outline, modifier, maxLines, overflow, align)
}

/** The line under a game title (developer, play time); follows the theme subtitle outline. */
@Composable
fun ThemedSubtitleText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val outline = PluviaTheme.subtitleOutline
    if (outline == null) {
        Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign,
        )
        return
    }
    OutlinedText(text, style, color, outline, modifier, maxLines, overflow, textAlign)
}

/**
 * [outline] with no blur is a stroked copy under the fill; with blur it is a soft shadow on each diagonal, as
 * stacked CSS text-shadows draw it. Without an outline this is a plain [Text].
 */
@Composable
private fun OutlinedText(
    text: String,
    style: TextStyle,
    color: Color,
    outline: ThemedTextOutline?,
    modifier: Modifier,
    maxLines: Int,
    overflow: TextOverflow,
    textAlign: TextAlign?,
) {
    if (outline == null) {
        Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign,
        )
        return
    }
    val density = LocalDensity.current
    val widthPx = with(density) { outline.width.toPx() }
    val blurPx = with(density) { outline.blur.toPx() }
    val under = remember(style, outline, widthPx, blurPx) {
        if (blurPx > 0f) {
            SOFT_OUTLINE_DIRECTIONS.map { (dx, dy) ->
                style.copy(shadow = Shadow(outline.color, Offset(dx * widthPx, dy * widthPx), blurPx))
            }
        } else {
            listOf(style.copy(shadow = null, drawStyle = Stroke(width = widthPx * 2f, join = StrokeJoin.Round)))
        }
    }
    val underColor = if (blurPx > 0f) color else outline.color
    Box(modifier = modifier, propagateMinConstraints = true) {
        for (s in under) {
            Text(
                text = text,
                style = s,
                color = underColor,
                maxLines = maxLines,
                overflow = overflow,
                textAlign = textAlign,
            )
        }
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign,
        )
    }
}

private val SOFT_OUTLINE_DIRECTIONS = listOf(-1f to 1f, 1f to 1f, 1f to -1f, -1f to -1f)
