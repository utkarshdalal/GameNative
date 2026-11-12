package app.gamenative.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme

/**
 * Rounded progress bar drawn as one continuous track with the fill over it, so the unfilled part
 * reads as the same bar. The gradient spans the whole bar, so its colors hold still as the fill
 * grows. A single entry in [colors] gives a flat fill.
 */
@Composable
fun GradientProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    colors: List<Color> = PluviaTheme.colors.brandGradient,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
    fill: Brush? = PluviaTheme.toggleFill,
) {
    val fraction = progress.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        if (fraction > 0f) {
            // Keep a barely-started fill from collapsing into a lens shape.
            val fillWidth = (size.width * fraction).coerceAtLeast(size.height)
            if (fill != null) {
                val fillPath = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, fillWidth, size.height, radius))
                }
                clipPath(fillPath) { drawRect(fill) }
            } else {
                drawRoundRect(
                    brush = if (colors.size > 1) {
                        Brush.horizontalGradient(colors = colors, startX = 0f, endX = size.width)
                    } else {
                        SolidColor(colors.firstOrNull() ?: trackColor)
                    },
                    size = Size(fillWidth, size.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}
