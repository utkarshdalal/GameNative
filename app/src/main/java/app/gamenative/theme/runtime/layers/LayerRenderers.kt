package app.gamenative.theme.runtime.layers

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.gamenative.theme.model.*
import app.gamenative.theme.runtime.BindingContext
import app.gamenative.theme.runtime.ThemeUtils
import app.gamenative.theme.runtime.parseCornerRadius

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

/** Alias for ThemeUtils.calculatePlacement for cleaner code */
private fun place(parent: DpSize, pos: DimOffset, size: DimSize?, defaultSize: DpSize, anchor: Anchor): ThemeUtils.Placement =
    ThemeUtils.calculatePlacement(parent, pos, size, defaultSize, anchor)

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

@Composable
private fun BindingContext.or(value: StringOrBinding?, default: String): String = when (value) {
    null -> default
    is StringOrBinding.Literal -> value.value
    is StringOrBinding.Ref -> resolveString(value) ?: default
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
                .size(p.width, p.height)
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
            .size(p.width, p.height)
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

@OptIn(UnstableApi::class)
@Composable
private fun BoxScope.VideoLayerView(layer: Layer.VideoLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val shape = parseCornerRadius(layer.cornerRadius)
    val context = LocalContext.current

    // Resolve video source from binding
    val mediaManager = remember { app.gamenative.theme.media.MediaSourceManager() }
    val videoSrc = binding.or(layer.source.src, "")
    val posterSrc = layer.source.poster?.let { binding.or(it, "") }

    // If no video source, show placeholder with poster if available
    if (videoSrc.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(p.x, p.y)
                .size(p.width, p.height)
                .clip(shape)
                .graphicsLayer(alpha = alpha)
                .background(Color(0xFF303030))
        ) {
            if (!posterSrc.isNullOrEmpty()) {
                com.skydoves.landscapist.coil.CoilImage(
                    modifier = Modifier.fillMaxSize(),
                    imageModel = { posterSrc },
                    imageOptions = com.skydoves.landscapist.ImageOptions(
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        contentDescription = "Video poster",
                    ),
                )
            }
            Text("▶", color = Color.White, fontSize = 24.sp)
        }
        return
    }

    // Create and remember ExoPlayer instance
    val exoPlayer = remember(videoSrc) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(videoSrc)
            setMediaItem(mediaItem)
            repeatMode = if (layer.source.loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            volume = if (layer.source.muted) 0f else 1f
            playWhenReady = layer.source.autoplay
            prepare()
        }
    }

    // Clean up player when composable leaves composition
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.width, p.height)
            .clip(shape)
            .graphicsLayer(alpha = alpha)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // Hide playback controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
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
    
    // Gradient support
    val gradientStartInt = binding.or(layer.gradientStart, 0)
    val gradientEndInt = binding.or(layer.gradientEnd, 0)
    val gradientAngle = binding.or(layer.gradientAngle, 0f)
    val hasGradient = gradientStartInt != 0 && gradientEndInt != 0
    
    // Calculate gradient direction based on angle
    val gradientBrush = if (hasGradient) {
        val angleRad = Math.toRadians(gradientAngle.toDouble())
        val cos = kotlin.math.cos(angleRad).toFloat()
        val sin = kotlin.math.sin(angleRad).toFloat()
        // Normalize to 0-1 range for Offset
        val startX = 0.5f - cos * 0.5f
        val startY = 0.5f + sin * 0.5f
        val endX = 0.5f + cos * 0.5f
        val endY = 0.5f - sin * 0.5f
        androidx.compose.ui.graphics.Brush.linearGradient(
            colors = listOf(Color(gradientStartInt), Color(gradientEndInt)),
            start = androidx.compose.ui.geometry.Offset(startX * p.width.value, startY * p.height.value),
            end = androidx.compose.ui.geometry.Offset(endX * p.width.value, endY * p.height.value),
        )
    } else null
    
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.width, p.height)
            .clip(shape)
            .graphicsLayer(alpha = alpha)
            .then(
                if (gradientBrush != null) {
                    Modifier.background(gradientBrush)
                } else {
                    Modifier.background(fillColor)
                }
            )
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
    val shape = layer.cornerRadius?.let { parseCornerRadius(it) } ?: RectangleShape
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.width, p.height)
            .shadow(elevation = if (radius > 0.dp) radius / 2 else 0.dp, shape = shape, ambientColor = color, spotColor = color)
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
            .size(p.width, p.height)
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
    
    // New text styling attributes
    val lineHeightSp = binding.or(layer.lineHeight, 0f).let { if (it > 0f) (it * px / d).sp else androidx.compose.ui.unit.TextUnit.Unspecified }
    val letterSpacingSp = binding.or(layer.letterSpacing, 0f).let { if (it != 0f) it.sp else androidx.compose.ui.unit.TextUnit.Unspecified }
    val textDecoration = when (layer.textDecoration.lowercase()) {
        "underline" -> androidx.compose.ui.text.style.TextDecoration.Underline
        "linethrough", "line-through", "strikethrough" -> androidx.compose.ui.text.style.TextDecoration.LineThrough
        else -> androidx.compose.ui.text.style.TextDecoration.None
    }
    
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.width, p.height)
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
            lineHeight = lineHeightSp,
            letterSpacing = letterSpacingSp,
            textDecoration = textDecoration,
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
            .size(p.width, p.height)
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
    
    // New border attributes
    val borderWidth = binding.or(layer.borderWidth, 0f)
    val borderColorInt = binding.or(layer.borderColor, 0xFFFFFFFF.toInt())
    val fontWeight = when (layer.fontWeight.lowercase()) {
        "bold" -> androidx.compose.ui.text.font.FontWeight.Bold
        "semibold" -> androidx.compose.ui.text.font.FontWeight.SemiBold
        "medium" -> androidx.compose.ui.text.font.FontWeight.Medium
        "light" -> androidx.compose.ui.text.font.FontWeight.Light
        "thin" -> androidx.compose.ui.text.font.FontWeight.Thin
        "extrabold", "black" -> androidx.compose.ui.text.font.FontWeight.ExtraBold
        else -> androidx.compose.ui.text.font.FontWeight.Normal
    }
    
    Box(
        modifier = Modifier
            .offset(p.x, p.y)
            .size(p.width, p.height)
            .graphicsLayer(alpha = alpha)
            .clip(shape)
            .background(Color(bgColorInt))
            .then(
                if (borderWidth > 0f) {
                    Modifier.border(borderWidth.dp, Color(borderColorInt), shape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(textColorInt),
            fontSize = textSizeSp,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
