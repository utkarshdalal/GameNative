package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedBarStyle
import app.gamenative.ui.theme.themeFill
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

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

/** The backdrop blur source shared by a screen's themed bars; only provided while a bar on that screen blurs. */
val LocalBarBlur = staticCompositionLocalOf<HazeState?> { null }

/**
 * A blur source state for a screen whose themed bars blur what is behind them, or null when none does (no
 * state is created then). [footer] is false on screens without the button-hint bar.
 */
@Composable
fun rememberBarBlurState(footer: Boolean = true): HazeState? {
    val wanted = (themedHeaderOrNull()?.blur?.value ?: 0f) > 0f ||
        (footer && (themedFooterOrNull()?.blur?.value ?: 0f) > 0f)
    return if (wanted) remember { HazeState() } else null
}

/** Records this content as what themed bars blur behind them; returns this modifier unchanged without [state]. */
fun Modifier.barBlurSource(state: HazeState?, zIndex: Float = 0f): Modifier =
    if (state == null) this else hazeSource(state, zIndex)

/**
 * Paints a theme bar behind the content: a glow spilling past the edge that faces the page, the bar color and
 * gradient layers, and the border line on that edge. [contentBelow] is true for a top bar (page below it) and
 * false for a footer (page above it). With [blurState] and a style blur, the content recorded by
 * [barBlurSource] is drawn blurred under the bar.
 */
fun Modifier.themedBar(style: ThemedBarStyle, contentBelow: Boolean, blurState: HazeState? = null): Modifier {
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
    val withBlur = if (blurState != null && style.blur.value > 0f) {
        withGlow.hazeEffect(
            state = blurState,
            style = HazeStyle(
                backgroundColor = Color.Transparent,
                tint = style.color.raisedTo(BLUR_MIN_ALPHA)?.let { HazeTint(it) },
                blurRadius = style.blur,
                noiseFactor = 0f,
                fallbackTint = style.color.raisedTo(FALLBACK_MIN_ALPHA)?.let { HazeTint(it) } ?: HazeTint.Unspecified,
            ),
        )
    } else {
        withGlow
    }
    val border = style.border
    return withBlur
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

/**
 * The extra layer of this color that, drawn under the bar color itself, makes the pair reach [target] opacity;
 * null when the bar color alone already does.
 */
private fun Color.raisedTo(target: Float): Color? =
    if (alpha >= target) null else copy(alpha = 1f - (1f - target) / (1f - alpha))

private const val GLOW_EDGE_ALPHA = 0.6f
private const val BLUR_MIN_ALPHA = 0.35f
private const val FALLBACK_MIN_ALPHA = 0.5f
