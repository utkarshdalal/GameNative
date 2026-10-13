package app.gamenative.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.BrandGradient

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
    colors: List<Color> = BrandGradient,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
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
