package app.gamenative.ui.component.dialog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsTextField
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.util.SnackbarManager
import com.winlator.inputcontrols.ControlElement
import com.winlator.widget.InputControlsView

internal data class ControlAppearance(
    val scale: Float,
    val color: Int,
    val activeColor: Int,
    val activeColorCustom: Boolean,
    val opacity: Float,
    val strokeScale: Float,
    val shooterLookThrough: Boolean
) {
    fun applyTo(element: ControlElement) {
        element.setScale(scale)
        element.setButtonColor(color)
        element.setButtonActiveColor(activeColor, activeColorCustom)
        element.setButtonOpacity(opacity)
        element.setButtonStrokeScale(strokeScale)
        element.setShooterLookThrough(shooterLookThrough)
    }

    companion object {
        fun capture(element: ControlElement) = ControlAppearance(
            scale = element.scale,
            color = element.buttonColor,
            activeColor = element.buttonActiveColor,
            activeColorCustom = element.hasCustomButtonActiveColor(),
            opacity = element.buttonOpacity,
            strokeScale = element.buttonStrokeScale,
            shooterLookThrough = element.isShooterLookThrough
        )
    }
}

internal val controlStrokeWidthScales = floatArrayOf(0.75f, 1.0f, 1.5f, 2.0f)

internal fun closestStrokeWidthIndex(strokeScale: Float): Int {
    return controlStrokeWidthScales.indices.minBy { index ->
        kotlin.math.abs(controlStrokeWidthScales[index] - strokeScale)
    }
}

private val controlColorPresets = listOf(
    0xffffff, 0xf44336, 0xff9800, 0xffeb3b,
    0x4caf50, 0x03a9f4, 0x3f51b5, 0x9c27b0
)

internal fun rgbToHex(color: Int): String = ControlElement.formatRgbColor(color)

internal fun parseRgbHex(value: String): Int? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6) return null
    return normalized.toIntOrNull(16)?.and(0x00ffffff)
}

private fun rgbToComposeColor(color: Int, opacity: Float = 1.0f): Color {
    val rgb = color and 0x00ffffff
    return Color(
        red = ((rgb shr 16) and 0xff) / 255f,
        green = ((rgb shr 8) and 0xff) / 255f,
        blue = (rgb and 0xff) / 255f,
        alpha = opacity.coerceIn(0f, 1f)
    )
}

@Composable
internal fun ControlAppearancePreview(
    type: ControlElement.Type,
    text: String,
    shape: ControlElement.Shape,
    orientation: Int,
    segmentCount: Int,
    color: Int,
    activeColor: Int,
    opacity: Float,
    strokeScale: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.preview),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(88.dp)
        )
        ControlPreviewPill(
            type = type,
            text = text,
            shape = shape,
            orientation = orientation,
            segmentCount = segmentCount,
            normalColor = color,
            activeColor = activeColor,
            opacity = opacity,
            strokeScale = strokeScale,
            active = false,
            modifier = Modifier.weight(1f)
        )
        ControlPreviewPill(
            type = type,
            text = text,
            shape = shape,
            orientation = orientation,
            segmentCount = segmentCount,
            normalColor = color,
            activeColor = activeColor,
            opacity = opacity,
            strokeScale = strokeScale,
            active = true,
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.active)
        )
    }
}

@Composable
private fun ControlPreviewPill(
    type: ControlElement.Type,
    text: String,
    shape: ControlElement.Shape,
    orientation: Int,
    segmentCount: Int,
    normalColor: Int,
    activeColor: Int,
    opacity: Float,
    strokeScale: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val normalDrawColor = rgbToComposeColor(normalColor, opacity)
    val activeDrawColor = rgbToComposeColor(activeColor, opacity)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label ?: " ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (type) {
            ControlElement.Type.D_PAD -> DPadPreview(normalDrawColor, activeDrawColor, active, strokeScale)
            ControlElement.Type.RANGE_BUTTON -> RangeButtonPreview(normalDrawColor, activeDrawColor, active, orientation, segmentCount, strokeScale)
            ControlElement.Type.STICK -> StickPreview(normalDrawColor, activeDrawColor, active, strokeScale)
            ControlElement.Type.TRACKPAD -> TrackpadPreview(normalDrawColor, activeDrawColor, active, strokeScale)
            ControlElement.Type.SHOOTER_MODE -> ShooterModePreview(normalDrawColor, activeDrawColor, active, strokeScale)
            else -> ButtonPreview(
                text = text,
                shape = shape,
                normalColor = normalDrawColor,
                activeColor = activeDrawColor,
                active = active,
                strokeScale = strokeScale
            )
        }
    }
}

@Composable
private fun ButtonPreview(
    text: String,
    shape: ControlElement.Shape,
    normalColor: Color,
    activeColor: Color,
    active: Boolean,
    strokeScale: Float
) {
    val composeShape = buttonPreviewShape(shape)
    val drawColor = if (active) activeColor else normalColor
    val strokeWidth = 2.dp * strokeScale
    Box(
        modifier = Modifier
            .then(if (shape == ControlElement.Shape.CIRCLE || shape == ControlElement.Shape.SQUARE) Modifier.size(56.dp) else Modifier.width(96.dp).height(48.dp))
            .clip(composeShape)
            .border(strokeWidth, drawColor, composeShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.take(8),
            color = drawColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DPadPreview(normalColor: Color, activeColor: Color, active: Boolean, strokeScale: Float) {
    Canvas(modifier = Modifier.size(72.dp)) {
        val unit = size.minDimension / 14f
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val start = unit
        val offsetX = unit * 2f
        val offsetY = unit * 3f
        val stroke = Stroke(width = 2.dp.toPx() * strokeScale)

        fun directionPath(direction: Int) = Path().apply {
            when (direction) {
                0 -> {
                    moveTo(cx, cy - start)
                    lineTo(cx - offsetX, cy - offsetY)
                    lineTo(cx - offsetX, 0f)
                    lineTo(cx + offsetX, 0f)
                    lineTo(cx + offsetX, cy - offsetY)
                }
                1 -> {
                    moveTo(cx + start, cy)
                    lineTo(cx + offsetY, cy - offsetX)
                    lineTo(size.width, cy - offsetX)
                    lineTo(size.width, cy + offsetX)
                    lineTo(cx + offsetY, cy + offsetX)
                }
                2 -> {
                    moveTo(cx, cy + start)
                    lineTo(cx - offsetX, cy + offsetY)
                    lineTo(cx - offsetX, size.height)
                    lineTo(cx + offsetX, size.height)
                    lineTo(cx + offsetX, cy + offsetY)
                }
                else -> {
                    moveTo(cx - start, cy)
                    lineTo(cx - offsetY, cy - offsetX)
                    lineTo(0f, cy - offsetX)
                    lineTo(0f, cy + offsetX)
                    lineTo(cx - offsetY, cy + offsetX)
                }
            }
            close()
        }

        repeat(4) { direction ->
            val directionActive = active && direction == 3
            val path = directionPath(direction)
            drawPath(path, if (directionActive) activeColor else normalColor, style = stroke)
        }
    }
}

@Composable
private fun RangeButtonPreview(
    normalColor: Color,
    activeColor: Color,
    active: Boolean,
    orientation: Int,
    segmentCount: Int,
    strokeScale: Float
) {
    val count = segmentCount.coerceIn(2, 5)
    val activeIndex = if (count > 1) 1 else 0
    val previewModifier = if (orientation == 0) Modifier.height(48.dp).fillMaxWidth() else Modifier.width(56.dp).height(104.dp)

    Box(modifier = previewModifier) {
        Canvas(Modifier.matchParentSize()) {
            val strokeWidth = (2.dp.toPx() * strokeScale).coerceAtLeast(1f)
            val activeStrokeWidth = strokeWidth * 1.5f
            val halfStroke = strokeWidth * 0.5f
            val radius = 12.dp.toPx()
            val activeRadius = radius + (activeStrokeWidth - strokeWidth) * 0.5f
            val outlinePath = Path()
            val activePath = Path()

            if (orientation == 0) {
                val left = halfStroke
                val right = size.width - halfStroke
                val top = halfStroke
                val bottom = size.height - halfStroke
                val segmentWidth = (size.width - strokeWidth) / count
                val activeLeft = activeIndex * segmentWidth + halfStroke
                val activeRight = (activeIndex + 1) * segmentWidth + halfStroke

                if (active) {
                    if (activeLeft > left) {
                        outlinePath.moveTo(left + radius, top)
                        outlinePath.lineTo(maxOf(left + radius, activeLeft), top)
                        outlinePath.moveTo(left + radius, bottom)
                        outlinePath.lineTo(maxOf(left + radius, activeLeft), bottom)
                        outlinePath.moveTo(left + radius, top)
                        outlinePath.quadraticTo(left, top, left, top + radius)
                        outlinePath.lineTo(left, bottom - radius)
                        outlinePath.quadraticTo(left, bottom, left + radius, bottom)
                    }
                    if (activeRight < right) {
                        outlinePath.moveTo(minOf(right - radius, activeRight), top)
                        outlinePath.lineTo(right - radius, top)
                        outlinePath.quadraticTo(right, top, right, top + radius)
                        outlinePath.lineTo(right, bottom - radius)
                        outlinePath.quadraticTo(right, bottom, right - radius, bottom)
                        outlinePath.moveTo(minOf(right - radius, activeRight), bottom)
                        outlinePath.lineTo(right - radius, bottom)
                    }
                    drawPath(outlinePath, normalColor, style = Stroke(width = strokeWidth))
                } else {
                    drawRoundRect(
                        color = normalColor,
                        topLeft = Offset(left, top),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = strokeWidth)
                    )
                }

                repeat(count - 1) { divider ->
                    val boundaryIndex = divider + 1
                    val x = boundaryIndex * segmentWidth + halfStroke
                    val activeBoundary = active && (boundaryIndex == activeIndex || boundaryIndex == activeIndex + 1)
                    if (!activeBoundary) drawLine(normalColor, Offset(x, top), Offset(x, bottom), strokeWidth = strokeWidth)
                }

                if (active) {
                    val leftRadius = if (activeIndex == 0) activeRadius.coerceAtMost(segmentWidth * 0.5f) else 0f
                    val rightRadius = if (activeIndex == count - 1) activeRadius.coerceAtMost(segmentWidth * 0.5f) else 0f
                    activePath.moveTo(activeLeft + leftRadius, top)
                    activePath.lineTo(activeRight - rightRadius, top)
                    if (rightRadius > 0f) {
                        activePath.quadraticTo(activeRight, top, activeRight, top + rightRadius)
                        activePath.lineTo(activeRight, bottom - rightRadius)
                        activePath.quadraticTo(activeRight, bottom, activeRight - rightRadius, bottom)
                    } else {
                        activePath.lineTo(activeRight, bottom)
                    }
                    activePath.lineTo(activeLeft + leftRadius, bottom)
                    if (leftRadius > 0f) {
                        activePath.quadraticTo(activeLeft, bottom, activeLeft, bottom - leftRadius)
                        activePath.lineTo(activeLeft, top + leftRadius)
                        activePath.quadraticTo(activeLeft, top, activeLeft + leftRadius, top)
                    } else {
                        activePath.lineTo(activeLeft, top)
                    }
                    activePath.close()
                    drawPath(activePath, activeColor, style = Stroke(width = activeStrokeWidth))
                }
            } else {
                val left = halfStroke
                val right = size.width - halfStroke
                val top = halfStroke
                val bottom = size.height - halfStroke
                val segmentHeight = (size.height - strokeWidth) / count
                val activeTop = activeIndex * segmentHeight + halfStroke
                val activeBottom = (activeIndex + 1) * segmentHeight + halfStroke

                if (active) {
                    if (activeTop > top) {
                        outlinePath.moveTo(left + radius, top)
                        outlinePath.lineTo(right - radius, top)
                        outlinePath.quadraticTo(right, top, right, top + radius)
                        outlinePath.moveTo(left + radius, top)
                        outlinePath.quadraticTo(left, top, left, top + radius)
                        outlinePath.lineTo(left, maxOf(top + radius, activeTop))
                        outlinePath.moveTo(right, top + radius)
                        outlinePath.lineTo(right, maxOf(top + radius, activeTop))
                    }
                    if (activeBottom < bottom) {
                        outlinePath.moveTo(left, minOf(bottom - radius, activeBottom))
                        outlinePath.lineTo(left, bottom - radius)
                        outlinePath.quadraticTo(left, bottom, left + radius, bottom)
                        outlinePath.lineTo(right - radius, bottom)
                        outlinePath.quadraticTo(right, bottom, right, bottom - radius)
                        outlinePath.moveTo(right, minOf(bottom - radius, activeBottom))
                        outlinePath.lineTo(right, bottom - radius)
                    }
                    drawPath(outlinePath, normalColor, style = Stroke(width = strokeWidth))
                } else {
                    drawRoundRect(
                        color = normalColor,
                        topLeft = Offset(left, top),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = strokeWidth)
                    )
                }

                repeat(count - 1) { divider ->
                    val boundaryIndex = divider + 1
                    val y = boundaryIndex * segmentHeight + halfStroke
                    val activeBoundary = active && (boundaryIndex == activeIndex || boundaryIndex == activeIndex + 1)
                    if (!activeBoundary) drawLine(normalColor, Offset(left, y), Offset(right, y), strokeWidth = strokeWidth)
                }

                if (active) {
                    val topRadius = if (activeIndex == 0) activeRadius.coerceAtMost(segmentHeight * 0.5f) else 0f
                    val bottomRadius = if (activeIndex == count - 1) activeRadius.coerceAtMost(segmentHeight * 0.5f) else 0f
                    activePath.moveTo(left + topRadius, activeTop)
                    activePath.lineTo(right - topRadius, activeTop)
                    if (topRadius > 0f) {
                        activePath.quadraticTo(right, activeTop, right, activeTop + topRadius)
                    } else {
                        activePath.lineTo(right, activeTop)
                    }
                    activePath.lineTo(right, activeBottom - bottomRadius)
                    if (bottomRadius > 0f) {
                        activePath.quadraticTo(right, activeBottom, right - bottomRadius, activeBottom)
                        activePath.lineTo(left + bottomRadius, activeBottom)
                        activePath.quadraticTo(left, activeBottom, left, activeBottom - bottomRadius)
                    } else {
                        activePath.lineTo(right, activeBottom)
                        activePath.lineTo(left, activeBottom)
                    }
                    activePath.lineTo(left, activeTop + topRadius)
                    if (topRadius > 0f) {
                        activePath.quadraticTo(left, activeTop, left + topRadius, activeTop)
                    } else {
                        activePath.lineTo(left, activeTop)
                    }
                    activePath.close()
                    drawPath(activePath, activeColor, style = Stroke(width = activeStrokeWidth))
                }
            }
        }

        if (orientation == 0) {
            Row(Modifier.fillMaxSize()) {
                repeat(count) { index ->
                    RangeSegment(index, normalColor, activeColor, active && index == activeIndex, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                repeat(count) { index ->
                    RangeSegment(index, normalColor, activeColor, active && index == activeIndex, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun RangeSegment(index: Int, normalColor: Color, activeColor: Color, active: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${index + 1}",
            color = if (active) activeColor else normalColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StickPreview(normalColor: Color, activeColor: Color, active: Boolean, strokeScale: Float) {
    Canvas(modifier = Modifier.size(72.dp)) {
        val stroke = Stroke(width = 2.dp.toPx() * strokeScale)
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val radius = size.minDimension * 0.42f
        val thumbCenter = if (active) Offset(center.x + radius * 0.28f, center.y - radius * 0.28f) else center
        val thumbRadius = radius * 0.42f
        drawCircle(if (active) activeColor else normalColor, radius, center, style = stroke)
        drawCircle(activeFillColor(if (active) activeColor else normalColor), thumbRadius, thumbCenter)
        drawCircle(if (active) activeColor else normalColor, thumbRadius, thumbCenter, style = stroke)
    }
}

@Composable
private fun TrackpadPreview(normalColor: Color, activeColor: Color, active: Boolean, strokeScale: Float) {
    Canvas(modifier = Modifier.size(72.dp)) {
        val drawColor = if (active) activeColor else normalColor
        val strokeWidth = 2.dp.toPx() * strokeScale
        val stroke = Stroke(width = strokeWidth)
        val halfStroke = strokeWidth * 0.5f
        val corner = CornerRadius(size.height * 0.15f, size.height * 0.15f)
        drawRoundRect(
            color = drawColor,
            topLeft = Offset(halfStroke, halfStroke),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = corner,
            style = stroke
        )
        val inset = strokeWidth * 4f
        drawRoundRect(
            color = drawColor,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            cornerRadius = CornerRadius(size.height * 0.08f, size.height * 0.08f),
            style = Stroke(width = strokeWidth * 1.5f)
        )
    }
}

@Composable
private fun ShooterModePreview(normalColor: Color, activeColor: Color, active: Boolean, strokeScale: Float) {
    val drawColor = if (active) activeColor else normalColor
    val strokeWidth = 2.dp * strokeScale
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (active) activeFillColor(activeColor) else Color.Transparent)
            .border(strokeWidth, drawColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "DJ",
            color = drawColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun activeFillColor(color: Color): Color = color.copy(alpha = minOf(color.alpha, 0.28f))

private fun buttonPreviewShape(shape: ControlElement.Shape): Shape = when (shape) {
    ControlElement.Shape.CIRCLE -> CircleShape
    ControlElement.Shape.RECT -> RoundedCornerShape(0.dp)
    ControlElement.Shape.ROUND_RECT -> RoundedCornerShape(24.dp)
    ControlElement.Shape.SQUARE -> RoundedCornerShape(8.dp)
}

@Composable
internal fun ControlColorField(
    title: String,
    subtitle: String,
    value: String,
    selectedColor: Int,
    onValueChange: (String) -> Unit,
    onPresetSelected: (Int) -> Unit
) {
    SettingsTextField(
        colors = settingsTileColors(),
        title = { Text(title) },
        subtitle = { Text(subtitle) },
        value = value,
        onValueChange = onValueChange,
        action = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(rgbToComposeColor(selectedColor), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        controlColorPresets.forEach { preset ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(rgbToComposeColor(preset), CircleShape)
                    .border(
                        width = if ((preset and 0x00ffffff) == (selectedColor and 0x00ffffff)) 3.dp else 1.dp,
                        color = if ((preset and 0x00ffffff) == (selectedColor and 0x00ffffff)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onPresetSelected(preset) }
            )
        }
    }
}

internal fun showCopyAppearanceDialog(
    context: android.content.Context,
    currentElement: ControlElement,
    view: InputControlsView,
    onAppearanceCopied: (ControlAppearance) -> Unit
) {
    val controls = view.profile?.elements
        ?.filter { it != currentElement }
        .orEmpty()

    if (controls.isEmpty()) {
        SnackbarManager.show(context.getString(R.string.toast_no_buttons_to_copy_appearance))
        return
    }

    val names = controls.map { element ->
        val scale = String.format(java.util.Locale.US, "%.2fx", element.scale)
        val label = if (!element.text.isNullOrEmpty()) {
            element.text
        } else {
            val binding = element.getBindingAt(0)
            if (binding != null && binding != com.winlator.inputcontrols.Binding.NONE) binding.toString().take(15) else context.getString(R.string.binding_none)
        }
        "${element.type.name.replace("_", " ")} - $scale - ${rgbToHex(element.buttonColor)} - $label"
    }.toTypedArray()

    android.app.AlertDialog.Builder(context)
        .setTitle(context.getString(R.string.copy_appearance_from_button))
        .setItems(names) { _, which ->
            onAppearanceCopied(ControlAppearance.capture(controls[which]))
            SnackbarManager.show(context.getString(R.string.toast_copied_button_appearance))
        }
        .setNegativeButton(context.getString(R.string.cancel), null)
        .show()
}
