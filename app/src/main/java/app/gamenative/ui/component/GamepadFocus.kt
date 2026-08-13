package app.gamenative.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
 * Single visual language for gamepad focus across every QuickMenu surface
 * (spec 2026-08-09, §3.2 — replaces the heterogeneous focusRing + cyan borders + gradients).
 *
 * Three semantically distinct states, theme-aware (Pluvia palette, light/dark):
 *
 * - [GamepadFocusState.Focused]: animated ring (primary/tertiary sweep, inherited from the
 *   old FocusRing without the multi-color gradient) — the node has focus.
 * - [GamepadFocusState.Selected]: persistent solid accent border, no animation — the node is
 *   the current *selection* of the surface (chosen tab, active preset, enabled toggle).
 *   Never confused with focus.
 * - [GamepadFocusState.Locked]: solid ring — an adjustment row with an active A-lock. The
 *   `●` indicator is drawn by the row itself (see `quick_menu_locked_indicator`).
 *
 * Pass `null` for no decoration. Apply AFTER the clip/background so the border draws on top.
 */
enum class GamepadFocusState { Focused, Selected, Locked }

@Composable
fun Modifier.gamepadFocus(
    state: GamepadFocusState?,
    shape: Shape,
    interactionSource: InteractionSource,
    width: Dp = 2.dp,
    durationMillis: Int = 5000,
    accentColor: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    if (state == null) return this
    return when (state) {
        GamepadFocusState.Focused -> animatedFocusRing(interactionSource, shape, width, durationMillis)
        // Selected / Locked keep the item's Pluvia accent (persistent, never animated).
        GamepadFocusState.Selected -> border(width, accentColor, shape)
        GamepadFocusState.Locked -> border((width * 1.5f), accentColor, shape)
    }
}

/**
 * Focus + visual + accessibility semantics in one helper: applies [gamepadFocus] and makes the
 * node focusable with the given [interactionSource] (the source the caller already observes).
 */
@Composable
fun Modifier.gamepadFocusable(
    state: GamepadFocusState?,
    shape: Shape,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier = this
    .gamepadFocus(state, shape, interactionSource)
    .focusable(enabled = enabled, interactionSource = interactionSource)

/**
 * Animated focus ring: a sweep-gradient border whose colors rotate around the element while
 * [interactionSource] is focused. Only the colors move; the shape stays put. The static stroke
 * acts as a mask that the rotating gradient is painted through, clipped to [shape].
 *
 * Extracted from the old FocusRing (kept as a thin wrapper for out-of-QuickMenu users) and
 * promoted to the "Focused" state of the gamepad focus language.
 */
@Composable
private fun Modifier.animatedFocusRing(
    interactionSource: InteractionSource,
    shape: Shape,
    width: Dp,
    durationMillis: Int,
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()

    // The Animatable and its driver are created unconditionally (stable remember slots), so the
    // slot count doesn't change between focused/unfocused and the ring can't flicker on
    // recompose. The spin runs only while focused; losing focus cancels the effect and snaps
    // back to 0, so an unfocused ring schedules no animation frames.
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

    // First == last so the sweep loops seamlessly. Only primary and tertiary; secondary is a
    // near-black gray and would show as a dark band in the ring.
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.primary,
    )
    val strokePx = with(LocalDensity.current) { width.toPx() }

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

        onDrawWithContent {
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
