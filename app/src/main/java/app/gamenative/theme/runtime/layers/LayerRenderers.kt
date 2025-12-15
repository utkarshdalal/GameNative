package app.gamenative.theme.runtime.layers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import app.gamenative.theme.model.*
import app.gamenative.theme.runtime.BindingContext

/** Dispatcher for rendering a single template layer. */
@Composable
fun BoxScope.RenderLayer(layer: Layer, parentSize: DpSize, binding: BindingContext, anchor: Anchor = Anchor.TOP_LEFT) {
    when (layer) {
        is Layer.ImageLayer -> ImageLayerView(layer, parentSize, binding, anchor)
        is Layer.VideoLayer -> VideoLayerView(layer, parentSize, binding, anchor)
        is Layer.RectLayer -> RectLayerView(layer, parentSize, binding, anchor)
        is Layer.ShadowLayer -> ShadowLayerView(layer, parentSize, binding, anchor)
        is Layer.BorderLayer -> BorderLayerView(layer, parentSize, binding, anchor)
        is Layer.TextLayer -> TextLayerView(layer, parentSize, binding, anchor)
        is Layer.BackdropLayer -> BackdropLayerView(layer, parentSize, binding, anchor)
        is Layer.ButtonLayer -> ButtonLayerView(layer, parentSize, binding, anchor)
    }
}

// --- Helpers ---

@Composable
private fun dimToDp(d: Dimension, maxW: Dp, maxH: Dp): Dp = when (d) {
    is Dimension.Px -> d.value.dp
    is Dimension.RelW -> maxW * d.fraction
    is Dimension.RelH -> maxH * d.fraction
}

/**
 * Parse CSS-like corner radius string.
 * - "8" = all corners 8dp
 * - "8 4" = top-left/bottom-right 8dp, top-right/bottom-left 4dp
 * - "8 4 2" = top-left 8dp, top-right/bottom-left 4dp, bottom-right 2dp
 * - "8 4 2 1" = top-left 8dp, top-right 4dp, bottom-right 2dp, bottom-left 1dp
 */
private fun parseCornerRadius(value: String?): RoundedCornerShape {
    if (value.isNullOrBlank()) return RoundedCornerShape(0.dp)
    val parts = value.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
    return when (parts.size) {
        0 -> RoundedCornerShape(0.dp)
        1 -> RoundedCornerShape(parts[0].dp)
        2 -> RoundedCornerShape(topStart = parts[0].dp, topEnd = parts[1].dp, bottomEnd = parts[0].dp, bottomStart = parts[1].dp)
        3 -> RoundedCornerShape(topStart = parts[0].dp, topEnd = parts[1].dp, bottomEnd = parts[2].dp, bottomStart = parts[1].dp)
        else -> RoundedCornerShape(topStart = parts[0].dp, topEnd = parts[1].dp, bottomEnd = parts[2].dp, bottomStart = parts[3].dp)
    }
}

private data class Placement(val x: Dp, val y: Dp, val w: Dp, val h: Dp)

@Composable
private fun place(parent: DpSize, pos: DimOffset, size: DimSize?, defaultSize: DpSize, anchor: Anchor): Placement {
    val w = size?.let { dimToDp(it.width, parent.width, parent.height) } ?: defaultSize.width
    val h = size?.let { dimToDp(it.height, parent.width, parent.height) } ?: defaultSize.height
    val x = dimToDp(pos.x, parent.width, parent.height)
    val y = dimToDp(pos.y, parent.width, parent.height)
    val px = when (anchor) {
        Anchor.TOP_LEFT, Anchor.CENTER_LEFT, Anchor.BOTTOM_LEFT -> x
        Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> (parent.width - w) / 2 + x
        Anchor.TOP_RIGHT, Anchor.CENTER_RIGHT, Anchor.BOTTOM_RIGHT -> parent.width - x - w
    }
    val py = when (anchor) {
        Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT -> y
        Anchor.CENTER_LEFT, Anchor.CENTER, Anchor.CENTER_RIGHT -> (parent.height - h) / 2 + y
        Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT -> parent.height - y - h
    }
    return Placement(px, py, w, h)
}

@Composable
private fun BindingContext.or(value: FloatOrBinding?, default: Float): Float = when (value) {
    null -> default
    is FloatOrBinding.Literal -> value.value
    is FloatOrBinding.Ref -> resolveFloat(value) ?: default
}

@Composable
private fun BindingContext.or(value: IntOrBinding?, default: Int): Int = when (value) {
    null -> default
    is IntOrBinding.Literal -> value.value
    is IntOrBinding.Ref -> resolveInt(value) ?: default
}

// --- Layer views ---

@Composable
private fun BoxScope.ImageLayerView(layer: Layer.ImageLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val shape = parseCornerRadius(layer.cornerRadius)
    val tintInt = binding.or(layer.tintColor, 0)
    val tint = if (tintInt != 0) Color(tintInt) else null

    // Resolve the image source using the media pipeline, mapping bindings like "game.capsule".
    val mediaManager = remember { app.gamenative.theme.media.MediaSourceManager() }
    val resolved = mediaManager.resolve(
        media = layer.source,
        allowVideo = false,
        bindingResolver = { b ->
            // Bridge our simple BindingContext to theme Binding resolver
            binding.resolveString(app.gamenative.theme.model.StringOrBinding.Ref(b))
        },
        themeRoot = null,
    ) as app.gamenative.theme.media.ResolvedMedia.Image

    // When no image could be resolved, show a tinted placeholder to match previous behavior.
    if (resolved.uri.isNullOrEmpty()) {
        Box(
            modifier = Modifier
                .offset(p.x, p.y)
                .size(p.w, p.h)
                .clip(shape)
                .graphicsLayer(alpha = alpha)
                .background(tint ?: Color(0xFF555555))
        ) {}
        return
    }

    // Determine content scale based on scaleType attribute
    val contentScale = when (layer.scaleType.lowercase()) {
        "contain", "fit" -> androidx.compose.ui.layout.ContentScale.Fit
        "stretch", "fill" -> androidx.compose.ui.layout.ContentScale.FillBounds
        else -> androidx.compose.ui.layout.ContentScale.Crop // "cover" is default
    }

    // Render the resolved image with Coil (Landscapist), keeping clipping, alpha and optional tint overlay.
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.w, p.h)
            .clip(shape)
            .graphicsLayer(alpha = alpha)
    ) {
        com.skydoves.landscapist.coil.CoilImage(
            modifier = Modifier.fillMaxSize(),
            imageModel = { resolved.uri },
            imageOptions = com.skydoves.landscapist.ImageOptions(
                contentScale = contentScale,
                contentDescription = null,
            ),
        )
        if (tint != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(tint)
            ) {}
        }
    }
}

@Composable
private fun BoxScope.VideoLayerView(layer: Layer.VideoLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val corner = binding.or(layer.cornerRadius, 0f).dp
    val shape = if (corner > 0.dp) RoundedCornerShape(corner) else RectangleShape
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.w, p.h)
            .clip(shape)
            .graphicsLayer(alpha = alpha)
            .background(Color(0xFF303030))
    ) {
        Text("VIDEO", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun BoxScope.RectLayerView(layer: Layer.RectLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val shape = parseCornerRadius(layer.cornerRadius)
    val fillColor = Color(binding.or(layer.color, 0x66000000.toInt()))
    val borderWidth = binding.or(layer.borderWidth, 0f)
    val borderColor = Color(binding.or(layer.borderColor, 0xFFFFFFFF.toInt()))
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.w, p.h)
            .clip(shape)
            .graphicsLayer(alpha = alpha)
            .background(fillColor)
            .then(
                if (borderWidth > 0f) {
                    Modifier.border(borderWidth.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
    ) {}
}

@Composable
private fun BoxScope.ShadowLayerView(layer: Layer.ShadowLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val radius = binding.or(layer.radius, 0f).dp
    val color = Color(binding.or(layer.color, 0x88000000.toInt()))
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.w, p.h)
            .shadow(elevation = if (radius > 0.dp) radius / 2 else 0.dp, shape = RectangleShape, ambientColor = color, spotColor = color)
            .graphicsLayer(alpha = alpha)
            .background(Color.Transparent)
    ) {}
}

@Composable
private fun BoxScope.BorderLayerView(layer: Layer.BorderLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val width = binding.or(layer.strokeWidth, 1f).dp
    val color = Color(binding.or(layer.color, 0xFFFFFFFF.toInt()))
    val shape = parseCornerRadius(layer.cornerRadius)
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.w, p.h)
            .clip(shape)
            .graphicsLayer(alpha = alpha)
            .border(width = width, color = color, shape = shape)
    ) {}
}

@Composable
private fun BoxScope.TextLayerView(layer: Layer.TextLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val color = Color(binding.or(layer.color, 0xFFFFFFFF.toInt()))
    val px = binding.or(layer.textSize, 16f)
    val density = LocalDensity.current
    val d = density.density
    val spSize = with(density) { (px / d).sp }
    val text = when (val t = layer.text) {
        is StringOrBinding.Literal -> t.value
        is StringOrBinding.Ref -> binding.resolveString(t) ?: ""
    }
    val fontWeight = when (layer.fontWeight.lowercase()) {
        "bold" -> androidx.compose.ui.text.font.FontWeight.Bold
        "semibold" -> androidx.compose.ui.text.font.FontWeight.SemiBold
        "medium" -> androidx.compose.ui.text.font.FontWeight.Medium
        "light" -> androidx.compose.ui.text.font.FontWeight.Light
        "thin" -> androidx.compose.ui.text.font.FontWeight.Thin
        "extrabold", "black" -> androidx.compose.ui.text.font.FontWeight.ExtraBold
        else -> androidx.compose.ui.text.font.FontWeight.Normal
    }
    val fontStyle = when (layer.fontStyle.lowercase()) {
        "italic" -> androidx.compose.ui.text.font.FontStyle.Italic
        else -> androidx.compose.ui.text.font.FontStyle.Normal
    }
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.w, p.h)
            .graphicsLayer(alpha = alpha)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = spSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            maxLines = layer.maxLines ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Composable
private fun BoxScope.BackdropLayerView(layer: Layer.BackdropLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val blurRadius = binding.or(layer.blurRadius, 0f).dp
    val tintInt = binding.or(layer.tintColor, 0)
    val tint = if (tintInt != 0) Color(tintInt) else Color.Transparent
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.w, p.h)
            .graphicsLayer(alpha = alpha)
            .blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .background(tint)
    ) {}
}

@Composable
private fun BoxScope.ButtonLayerView(layer: Layer.ButtonLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(80.dp, 40.dp), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val shape = parseCornerRadius(layer.cornerRadius)
    val bgColorInt = binding.or(layer.backgroundColor, 0xFFE91E63.toInt())
    val textColorInt = binding.or(layer.textColor, 0xFFFFFFFF.toInt())
    val textSizeSp = binding.or(layer.textSize, 14f).sp
    val text = when (val t = layer.text) {
        is StringOrBinding.Literal -> t.value
        is StringOrBinding.Ref -> binding.resolveString(t) ?: ""
    }
    
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.w, p.h)
            .graphicsLayer(alpha = alpha)
            .clip(shape)
            .background(Color(bgColorInt)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(textColorInt),
            fontSize = textSizeSp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
