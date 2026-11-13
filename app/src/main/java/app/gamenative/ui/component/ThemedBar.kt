package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedBarStyle
import app.gamenative.ui.theme.themeFill

/** The theme top bar style, or null when the theme does not style it (no style is built then). */
@Composable
@ReadOnlyComposable
fun themedHeaderOrNull(): ThemedBarStyle? =
    if (PluviaTheme.spec.header == null) null else PluviaTheme.headerStyle.takeIf { it.themed }

/** The theme button-hint bar style, or null when the theme does not style it. */
@Composable
@ReadOnlyComposable
fun themedFooterOrNull(): ThemedBarStyle? =
    if (PluviaTheme.spec.footer == null) null else PluviaTheme.footerStyle.takeIf { it.themed }

/**
 * Paints a theme bar behind the content: a glow spilling past the edge that faces the page, the bar colour and
 * gradient layers, and the border line on that edge. [contentBelow] is true for a top bar (page below it) and
 * false for a footer (page above it).
 */
fun Modifier.themedBar(style: ThemedBarStyle, contentBelow: Boolean): Modifier {
    val glowColor = style.glowColor
    val withGlow = if (glowColor != null && style.glow.value > 0f) {
        drawBehind {
            val reach = style.glow.toPx()
            val edge = glowColor.copy(alpha = glowColor.alpha * GLOW_EDGE_ALPHA)
            if (contentBelow) {
                drawRect(
                    brush = Brush.verticalGradient(listOf(edge, Color.Transparent), startY = size.height, endY = size.height + reach),
                    topLeft = Offset(0f, size.height),
                    size = Size(size.width, reach),
                )
            } else {
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Transparent, edge), startY = -reach, endY = 0f),
                    topLeft = Offset(0f, -reach),
                    size = Size(size.width, reach),
                )
            }
        }
    } else {
        this
    }
    val border = style.border
    return withGlow
        .background(style.color)
        .themeFill(style.fill)
        .then(
            if (border != null) {
                Modifier.drawBehind {
                    val w = border.width.toPx().coerceAtLeast(1f)
                    drawRect(
                        brush = border.brush,
                        topLeft = Offset(0f, if (contentBelow) size.height - w else 0f),
                        size = Size(size.width, w),
                    )
                }
            } else {
                Modifier
            },
        )
}

private const val GLOW_EDGE_ALPHA = 0.6f
