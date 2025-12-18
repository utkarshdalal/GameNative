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
import app.gamenative.theme.runtime.SharedElementRenderers
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
    val fillColor = Color(binding.or(layer.color, 0x66000000.toInt()))
    val borderWidth = binding.or(layer.borderWidth, 0f)
    val borderColor = Color(binding.or(layer.borderColor, 0xFFFFFFFF.toInt()))
    val gradientStartInt = binding.or(layer.gradientStart, 0)
    val gradientEndInt = binding.or(layer.gradientEnd, 0)
    val gradientAngle = binding.or(layer.gradientAngle, 0f)
    
    SharedElementRenderers.RenderRect(
        modifier = Modifier.offset(p.x, p.y),
        width = p.width,
        height = p.height,
        color = fillColor,
        cornerRadius = layer.cornerRadius,
        borderWidth = borderWidth,
        borderColor = borderColor,
        gradientStart = if (gradientStartInt != 0) Color(gradientStartInt) else null,
        gradientEnd = if (gradientEndInt != 0) Color(gradientEndInt) else null,
        gradientAngle = gradientAngle,
        opacity = alpha,
    )
}

@Composable
private fun BoxScope.ShadowLayerView(layer: Layer.ShadowLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val radius = binding.or(layer.radius, 0f)
    val color = Color(binding.or(layer.color, 0x88000000.toInt()))
    val offsetX = ThemeUtils.dimToDp(layer.offset.x, parentSize.width, parentSize.height)
    val offsetY = ThemeUtils.dimToDp(layer.offset.y, parentSize.width, parentSize.height)
    
    SharedElementRenderers.RenderShadow(
        modifier = Modifier.offset(p.x + offsetX, p.y + offsetY),
        width = p.width,
        height = p.height,
        radius = radius,
        color = color,
        offsetX = 0f, // Already applied in modifier
        offsetY = 0f,
        cornerRadius = layer.cornerRadius,
        opacity = alpha,
    )
}

@Composable
private fun BoxScope.BorderLayerView(layer: Layer.BorderLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val strokeWidth = binding.or(layer.strokeWidth, 1f)
    val color = Color(binding.or(layer.color, 0xFFFFFFFF.toInt()))
    
    SharedElementRenderers.RenderBorder(
        modifier = Modifier.offset(p.x, p.y),
        width = p.width,
        height = p.height,
        strokeWidth = strokeWidth,
        color = color,
        cornerRadius = layer.cornerRadius,
        opacity = alpha,
    )
}

@Composable
private fun BoxScope.TextLayerView(layer: Layer.TextLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val color = Color(binding.or(layer.color, 0xFFFFFFFF.toInt()))
    val textSize = binding.or(layer.textSize, 16f)
    val text = when (val t = layer.text) {
        is StringOrBinding.Literal -> t.value
        is StringOrBinding.Ref -> binding.resolveString(t) ?: ""
    }
    val lineHeight = binding.or(layer.lineHeight, 0f).let { if (it > 0f) it else null }
    val letterSpacing = binding.or(layer.letterSpacing, 0f).let { if (it != 0f) it else null }
    
    SharedElementRenderers.RenderText(
        modifier = Modifier.offset(p.x, p.y),
        width = p.width,
        height = p.height,
        text = text,
        color = color,
        textSize = textSize,
        maxLines = layer.maxLines,
        textAlign = layer.textAlign,
        fontWeight = layer.fontWeight,
        fontStyle = layer.fontStyle,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
        textDecoration = layer.textDecoration,
        opacity = alpha,
    )
}

@Composable
private fun BoxScope.BackdropLayerView(layer: Layer.BackdropLayer, parentSize: DpSize, binding: BindingContext, anchor: Anchor) {
    val p = place(parentSize, layer.position, layer.size, defaultSize = DpSize(parentSize.width, parentSize.height), anchor)
    val alpha = binding.or(layer.opacity, 1f)
    val blurRadius = binding.or(layer.blurRadius, 0f)
    val tintInt = binding.or(layer.tintColor, 0)
    val tintColor = if (tintInt != 0) Color(tintInt) else null
    
    SharedElementRenderers.RenderBackdrop(
        modifier = Modifier.offset(p.x, p.y),
        width = p.width,
        height = p.height,
        blurRadius = blurRadius,
        tintColor = tintColor,
        cornerRadius = null, // BackdropLayer doesn't have cornerRadius in the model
        opacity = alpha,
    )
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
    
    val borderWidth = binding.or(layer.borderWidth, 0f)
    val borderColorInt = binding.or(layer.borderColor, 0xFFFFFFFF.toInt())
    val fontWeight = SharedElementRenderers.parseFontWeight(layer.fontWeight)
    
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
