package app.gamenative.theme.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared rendering functions for visual elements used in both card layers and fixed elements.
 * This consolidates duplicate rendering code to ensure consistent behavior.
 */
object SharedElementRenderers {

    /**
     * Render a rectangle with optional fill, gradient, and border.
     */
    @Composable
    fun RenderRect(
        modifier: Modifier,
        width: Dp,
        height: Dp,
        color: Color,
        cornerRadius: String?,
        borderWidth: Float,
        borderColor: Color,
        gradientStart: Color?,
        gradientEnd: Color?,
        gradientAngle: Float,
        opacity: Float,
    ) {
        val shape = parseCornerRadius(cornerRadius)
        val hasGradient = gradientStart != null && gradientEnd != null
        
        val gradientBrush = if (hasGradient && gradientStart != null && gradientEnd != null) {
            createGradientBrush(gradientStart, gradientEnd, gradientAngle, width, height)
        } else null
        
        Box(
            modifier = modifier
                .size(width, height)
                .clip(shape)
                .graphicsLayer(alpha = opacity)
                .then(
                    if (gradientBrush != null) {
                        Modifier.background(gradientBrush)
                    } else {
                        Modifier.background(color, shape)
                    }
                )
                .then(
                    if (borderWidth > 0f) {
                        Modifier.border(borderWidth.dp, borderColor, shape)
                    } else {
                        Modifier
                    }
                )
        )
    }

    /**
     * Render static text with styling.
     * 
     * @param overflow Text overflow behavior: "ellipsis" (add ...), "clip" (hard cut), or "visible" (show all)
     */
    @Composable
    fun RenderText(
        modifier: Modifier,
        width: Dp?,
        height: Dp?,
        text: String,
        color: Color,
        textSize: Float,
        maxLines: Int?,
        textAlign: String,
        fontWeight: String,
        fontStyle: String,
        lineHeight: Float? = null,
        letterSpacing: Float? = null,
        textDecoration: String? = null,
        overflow: String = "ellipsis",
        opacity: Float,
    ) {
        val fontWeightValue = parseFontWeight(fontWeight)
        val fontStyleValue = parseFontStyle(fontStyle)
        val textAlignValue = parseTextAlign(textAlign)
        val textDecorationValue = parseTextDecoration(textDecoration)
        val textOverflow = parseTextOverflow(overflow)
        
        val lineHeightSp = lineHeight?.let { if (it > 0f) (it * textSize).sp else TextUnit.Unspecified } ?: TextUnit.Unspecified
        val letterSpacingSp = letterSpacing?.let { if (it != 0f) it.sp else TextUnit.Unspecified } ?: TextUnit.Unspecified

        val sizeModifier = if (width != null && height != null) {
            Modifier.size(width, height)
        } else if (width != null) {
            Modifier.width(width)
        } else {
            Modifier
        }

        Box(
            modifier = modifier
                .then(sizeModifier)
                .graphicsLayer(alpha = opacity)
        ) {
            Text(
                text = text,
                color = color,
                fontSize = textSize.sp,
                fontWeight = fontWeightValue,
                fontStyle = fontStyleValue,
                textAlign = textAlignValue,
                maxLines = maxLines ?: Int.MAX_VALUE,
                overflow = textOverflow,
                lineHeight = lineHeightSp,
                letterSpacing = letterSpacingSp,
                textDecoration = textDecorationValue,
                modifier = if (width != null) Modifier.fillMaxWidth() else Modifier,
            )
        }
    }
    
    /**
     * Parse text overflow mode from string.
     */
    fun parseTextOverflow(overflow: String?): TextOverflow = when (overflow?.lowercase()) {
        "clip" -> TextOverflow.Clip
        "visible" -> TextOverflow.Visible
        else -> TextOverflow.Ellipsis  // Default to ellipsis
    }

    /**
     * Render a shadow effect.
     */
    @Composable
    fun RenderShadow(
        modifier: Modifier,
        width: Dp,
        height: Dp,
        radius: Float,
        color: Color,
        offsetX: Float,
        offsetY: Float,
        cornerRadius: String?,
        opacity: Float,
    ) {
        val shape = cornerRadius?.let { parseCornerRadius(it) } ?: RectangleShape
        val elevationDp = if (radius > 0f) (radius / 2).dp else 0.dp
        
        Box(
            modifier = modifier
                .size(width, height)
                .shadow(
                    elevation = elevationDp,
                    shape = shape,
                    ambientColor = color,
                    spotColor = color
                )
                .graphicsLayer(alpha = opacity)
                .background(Color.Transparent)
        )
    }

    /**
     * Render a border/stroke around a rectangular area.
     */
    @Composable
    fun RenderBorder(
        modifier: Modifier,
        width: Dp,
        height: Dp,
        strokeWidth: Float,
        color: Color,
        cornerRadius: String?,
        opacity: Float,
    ) {
        val shape = parseCornerRadius(cornerRadius)
        
        Box(
            modifier = modifier
                .size(width, height)
                .clip(shape)
                .graphicsLayer(alpha = opacity)
                .border(width = strokeWidth.dp, color = color, shape = shape)
        )
    }

    /**
     * Render a backdrop blur effect.
     * Uses native blur on API 31+, otherwise applies tint only.
     */
    @Composable
    fun RenderBackdrop(
        modifier: Modifier,
        width: Dp,
        height: Dp,
        blurRadius: Float,
        tintColor: Color?,
        cornerRadius: String?,
        opacity: Float,
    ) {
        val shape = parseCornerRadius(cornerRadius)
        val tint = tintColor ?: Color.Transparent
        
        Box(
            modifier = modifier
                .size(width, height)
                .clip(shape)
                .graphicsLayer(alpha = opacity)
                // Use native blur API when available (API 31+)
                .then(
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && blurRadius > 0f) {
                        Modifier.graphicsLayer {
                            renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                blurRadius,
                                blurRadius,
                                android.graphics.Shader.TileMode.CLAMP
                            ).asComposeRenderEffect()
                        }
                    } else if (blurRadius > 0f) {
                        // Fallback to Compose blur for older devices
                        Modifier.blur(radius = blurRadius.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    } else {
                        Modifier
                    }
                )
                .background(tint, shape)
        )
    }

    // --- Helper functions ---

    fun parseFontWeight(value: String): FontWeight = when (value.lowercase()) {
        "bold" -> FontWeight.Bold
        "semibold" -> FontWeight.SemiBold
        "medium" -> FontWeight.Medium
        "light" -> FontWeight.Light
        "thin" -> FontWeight.Thin
        "extrabold", "black" -> FontWeight.ExtraBold
        else -> FontWeight.Normal
    }

    fun parseFontStyle(value: String): FontStyle = when (value.lowercase()) {
        "italic" -> FontStyle.Italic
        else -> FontStyle.Normal
    }

    fun parseTextAlign(value: String): TextAlign = when (value.lowercase()) {
        "center" -> TextAlign.Center
        "right", "end" -> TextAlign.End
        else -> TextAlign.Start
    }

    fun parseTextDecoration(value: String?): TextDecoration = when (value?.lowercase()) {
        "underline" -> TextDecoration.Underline
        "linethrough", "line-through", "strikethrough" -> TextDecoration.LineThrough
        else -> TextDecoration.None
    }

    private fun createGradientBrush(
        startColor: Color,
        endColor: Color,
        angleDegrees: Float,
        width: Dp,
        height: Dp,
    ): Brush {
        val angleRad = Math.toRadians(angleDegrees.toDouble())
        val cos = cos(angleRad).toFloat()
        val sin = sin(angleRad).toFloat()
        // Normalize to 0-1 range for Offset
        val startX = 0.5f - cos * 0.5f
        val startY = 0.5f + sin * 0.5f
        val endX = 0.5f + cos * 0.5f
        val endY = 0.5f - sin * 0.5f
        return Brush.linearGradient(
            colors = listOf(startColor, endColor),
            start = Offset(startX * width.value, startY * height.value),
            end = Offset(endX * width.value, endY * height.value),
        )
    }
}
