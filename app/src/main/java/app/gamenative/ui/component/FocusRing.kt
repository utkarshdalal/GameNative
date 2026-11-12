package app.gamenative.ui.component

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.NavigateSoundOnFocusGain

/**
 * Colored focus ring: a sweep gradient border whose colors rotate around the element while
 * [interactionSource] is focused (D-pad / controller). Shared by LibraryGridCard, InfoCard, etc.
 *
 * Only the colors move; the shape stays put. The static stroke acts as a mask that the rotating
 * gradient is painted through, clipped to [shape] so the outer edge stays flush with the element.
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
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    NavigateSoundOnFocusGain(focused)

    // The Animatable and its driver are created unconditionally (stable remember slots), so the slot
    // count doesn't change between focused/unfocused and the ring can't flicker on recompose (the
    // earlier bug on frequently-recomposing elements like the tab-bar buttons). The spin runs only
    // while focused; losing focus cancels the effect and snaps back to 0, so an unfocused ring
    // schedules no animation frames (an always-on InfiniteTransition would keep ticking per frame).
    val angle = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) {
            angle.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        } else {
            angle.snapTo(0f)
        }
    }

    if (!focused) return this

    // First == last so the sweep loops seamlessly. By default purple (primary) and blue (tertiary).
    val focusColors = PluviaTheme.colors
    val colors = listOf(focusColors.focus, focusColors.focusAlt, focusColors.focus)
    val strokePx = with(LocalDensity.current) { width.toPx() }
    val glowPx = PluviaTheme.focusGlow?.let { with(LocalDensity.current) { it.toPx() } }
    val glowColor = focusColors.focus

    return drawWithCache {
        // Rebuilt only when the size changes.
        val outline = shape.createOutline(size, layoutDirection, this)
        val bounds = Rect(Offset.Zero, size)
        val center = bounds.center
        val sweep = Brush.sweepGradient(colors, center)
        // Allocate the layer Paint once per cache build (size change), not once per frame.
        val layerPaint = Paint()
        val clipPath = Path().apply {
            when (val o = outline) {
                is Outline.Rectangle -> addRect(o.rect)
                is Outline.Rounded -> addRoundRect(o.roundRect)
                is Outline.Generic -> addPath(o.path)
            }
        }

        val glowPaint = glowPx?.let { focusGlowPaint(glowColor, it) }

        onDrawWithContent {
            glowPaint?.let { paint -> drawIntoCanvas { it.drawOutline(outline, paint) } }
            drawContent()
            // Reading angle here keeps the animation in the draw phase, off recomposition.
            val canvas = drawContext.canvas
            canvas.saveLayer(bounds, layerPaint)

            // Keep the ring's outer edge flush with the element.
            canvas.clipPath(clipPath)

            // Stroke at 2x width; the clipped-off outer half leaves an inward border of `width`.
            drawOutline(outline, color = Color.Black, style = Stroke(strokePx * 2f))

            // Paint the gradient only over the stroke. Oversized circle covers any rotation.
            rotate(angle.value, pivot = center) {
                drawCircle(
                    brush = sweep,
                    radius = size.maxDimension,
                    blendMode = BlendMode.SrcIn,
                )
            }

            canvas.restore()
        }
    }
}

/** Paint for an outer glow of [radiusPx] around a shape, drawn outside its edge only. */
internal fun focusGlowPaint(color: Color, radiusPx: Float): Paint = Paint().apply {
    this.color = color
    asFrameworkPaint().maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.OUTER)
}
