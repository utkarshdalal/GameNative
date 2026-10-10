package app.gamenative.ui.component.dialog

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The QuickMenu's rotating gradient focus ring (primary↔tertiary sweep), but driven **synchronously** by a [selected]
 * boolean instead of an [androidx.compose.foundation.interaction.InteractionSource].
 *
 * We can't reuse `app.gamenative.ui.component.focusRing` directly for our (line,col) nav model: adapting our boolean
 * to a focus interaction requires an async `emit`, which races under fast d-pad auto-repeat — a cancelled `Unfocus`
 * left the ring stuck lit on items we'd already scrolled past. Reading [selected] in the draw phase (via [Animatable])
 * is race-free: the ring appears/clears exactly with the selection. Mirrors focusRing's masked-sweep technique: a
 * static stroke clipped to [shape] acts as a mask that the rotating sweep is painted through.
 */
@Composable
fun Modifier.scSelectionRing(
    selected: Boolean,
    shape: Shape,
    width: Dp = 2.dp,
    durationMillis: Int = 5000,
): Modifier {
    // Created unconditionally (stable slot) so the ring can't flicker on recompose; spins only while selected.
    val angle = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) {
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

    if (!selected) return this

    // first == last so the sweep loops seamlessly; only primary + tertiary (secondary is near-black).
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.primary,
    )
    val strokePx = with(LocalDensity.current) { width.toPx() }

    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val bounds = Rect(Offset.Zero, size)
        val center = bounds.center
        val sweep = Brush.sweepGradient(colors, center)
        val layerPaint = Paint()
        val clipPath = Path().apply {
            when (val o = outline) {
                is Outline.Rectangle -> addRect(o.rect)
                is Outline.Rounded -> addRoundRect(o.roundRect)
                is Outline.Generic -> addPath(o.path)
            }
        }
        onDrawWithContent {
            drawContent()
            val canvas = drawContext.canvas
            canvas.saveLayer(bounds, layerPaint)
            canvas.clipPath(clipPath)
            // Stroke at 2× width; the clipped-off outer half leaves an inward border of `width`.
            drawOutline(outline, color = Color.Black, style = Stroke(strokePx * 2f))
            rotate(angle.value, pivot = center) {
                drawCircle(brush = sweep, radius = size.maxDimension, blendMode = BlendMode.SrcIn)
            }
            canvas.restore()
        }
    }
}
