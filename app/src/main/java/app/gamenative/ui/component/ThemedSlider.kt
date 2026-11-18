package app.gamenative.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.drawSlidingRoundRect
import app.gamenative.ui.theme.rememberSlidePhase

/**
 * [Slider] whose filled part shows the theme's gradient toggle fill. Without a gradient fill it is the plain
 * [Slider] with these arguments.
 */
@Composable
@NonRestartableComposable
fun ThemedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val gradient = PluviaTheme.toggleFill?.takeIf { it !is SolidColor }
    if (gradient == null) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = colors,
            interactionSource = interactionSource,
        )
    } else {
        GradientTrackSlider(gradient, value, onValueChange, modifier, enabled, valueRange, steps, onValueChangeFinished, colors, interactionSource)
    }
}

@PublishedApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GradientTrackSlider(
    gradient: Brush,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)?,
    colors: SliderColors,
    interactionSource: MutableInteractionSource,
) {
    val inactive = PluviaTheme.sliderTrackColor(colors.inactiveTrackColor)
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        steps = steps,
        thumb = { SliderDefaults.Thumb(interactionSource = interactionSource, colors = colors, enabled = enabled) },
        track = { state -> ThemedSliderTrack(state, gradient, inactive, enabled) },
        valueRange = valueRange,
    )
}

/** Slider track: [active] brush up to the handle, [inactive] after it, with the Material gap around the handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemedSliderTrack(state: SliderState, active: Brush, inactive: Color, enabled: Boolean) {
    val range = state.valueRange
    val span = range.endInclusive - range.start
    val fraction = if (span == 0f) 0f else ((state.value - range.start) / span).coerceIn(0f, 1f)
    val alpha = if (enabled) 1f else DISABLED_TRACK_ALPHA
    val slide = if (active !is SolidColor) rememberSlidePhase(PluviaTheme.fillSlideSec) else null
    Box(
        Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .drawBehind {
                val h = size.height
                val gap = HANDLE_GAP.toPx()
                val radius = CornerRadius(h / 2f)
                val x = size.width * fraction
                val activeEnd = x - gap
                val inactiveStart = x + gap
                if (inactiveStart < size.width) {
                    drawRoundRect(inactive, Offset(inactiveStart, 0f), Size(size.width - inactiveStart, h), radius, alpha = alpha)
                }
                if (activeEnd > 0f) {
                    if (slide != null) {
                        drawSlidingRoundRect(active, slide.value, Offset.Zero, Size(activeEnd, h), radius, alpha)
                    } else {
                        drawRoundRect(active, Offset.Zero, Size(activeEnd, h), radius, alpha = alpha)
                    }
                }
            },
    )
}

private val TRACK_HEIGHT = 16.dp
private val HANDLE_GAP = 8.dp
private const val DISABLED_TRACK_ALPHA = 0.38f
