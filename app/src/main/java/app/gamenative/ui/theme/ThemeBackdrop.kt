package app.gamenative.ui.theme

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.theme.ThemeFill
import app.gamenative.theme.ThemeGradient
import app.gamenative.theme.ThemeRadial
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val MAX_BACKDROP_EDGE_PX = 2048

private object BackdropImageCache {
    private var key: String? = null
    private var bitmap: ImageBitmap? = null

    @Synchronized
    fun peek(path: String): ImageBitmap? = if (key?.startsWith("$path:") == true) bitmap else null

    @Synchronized
    fun get(key: String): ImageBitmap? = if (this.key == key) bitmap else null

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap?) {
        this.key = key
        this.bitmap = bitmap
    }
}

private fun decodeBackdrop(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.isFile) return null
    val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
    BackdropImageCache.get(key)?.let { return it }
    val bitmap = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_BACKDROP_EDGE_PX) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (e: Throwable) {
        Timber.w(e, "Theme background unreadable: %s", path)
        null
    }
    BackdropImageCache.put(key, bitmap)
    return bitmap
}

private fun Modifier.themeGradient(gradient: ThemeGradient): Modifier = drawBehind {
    val colors = gradient.colors.map { Color(it) }
    when {
        colors.isEmpty() -> Unit
        colors.size == 1 -> drawRect(colors[0])
        else -> {
            // CSS angle: 0deg points up, clockwise; the line spans the box's projected extent.
            val rad = Math.toRadians(gradient.angleDeg.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
            val c = center
            val start = Offset(c.x - dx * half, c.y - dy * half)
            val end = Offset(c.x + dx * half, c.y + dy * half)
            val brush = if (gradient.stops.size == colors.size) {
                Brush.linearGradient(
                    colorStops = gradient.stops.zip(colors).toTypedArray(),
                    start = start,
                    end = end,
                )
            } else {
                Brush.linearGradient(colors = colors, start = start, end = end)
            }
            drawRect(brush)
        }
    }
}

/**
 * Size-aware brush for [gradient] with the same CSS angle semantics as the backdrop; null without colours.
 * A radial gradient follows CSS radial-gradient geometry; [density] converts its dp offsets and radii to pixels.
 */
internal fun cssGradientBrush(gradient: ThemeGradient, density: Float = 1f): Brush? {
    val colors = gradient.colors.map { Color(it) }
    if (colors.isEmpty()) return null
    if (colors.size == 1) return SolidColor(colors[0])
    val stops = gradient.stops.takeIf { it.size == colors.size }
    gradient.radial?.let { return radialBrush(it, gradient.colors.toIntArray(), stops?.toFloatArray(), density) }
    val rad = Math.toRadians(gradient.angleDeg.toDouble())
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            return LinearGradientShader(
                from = Offset(c.x - dx * half, c.y - dy * half),
                to = Offset(c.x + dx * half, c.y + dy * half),
                colors = colors,
                colorStops = stops,
            )
        }
    }
}

private fun radialBrush(r: ThemeRadial, colors: IntArray, stops: FloatArray?, density: Float): Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val cx = r.centerX * size.width + r.offsetXDp * density
        val cy = r.centerY * size.height + r.offsetYDp * density
        val dx = listOf(abs(cx), abs(size.width - cx))
        val dy = listOf(abs(cy), abs(size.height - cy))
        val explicit = r.radiusXDp?.times(density)
        val (rx, ry) = when {
            explicit != null -> explicit to (r.radiusYDp?.times(density) ?: explicit)
            r.extent == ThemeRadial.CLOSEST_SIDE -> dx.min() to dy.min()
            r.extent == ThemeRadial.FARTHEST_SIDE -> dx.max() to dy.max()
            r.extent == ThemeRadial.CLOSEST_CORNER -> dx.min() * SQRT2 to dy.min() * SQRT2
            else -> dx.max() * SQRT2 to dy.max() * SQRT2
        }
        val radius: Float
        val scaleY: Float
        if (r.circle) {
            radius = when {
                explicit != null -> rx
                r.extent == ThemeRadial.CLOSEST_SIDE -> minOf(dx.min(), dy.min())
                r.extent == ThemeRadial.FARTHEST_SIDE -> maxOf(dx.max(), dy.max())
                r.extent == ThemeRadial.CLOSEST_CORNER -> hypot(dx.min(), dy.min())
                else -> hypot(dx.max(), dy.max())
            }
            scaleY = 1f
        } else {
            radius = rx
            scaleY = if (rx > 0f) ry / rx else 1f
        }
        return android.graphics.RadialGradient(cx, cy, radius.coerceAtLeast(0.5f), colors, stops, android.graphics.Shader.TileMode.CLAMP).apply {
            if (scaleY != 1f && scaleY > 0f) setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, scaleY, cx, cy) })
        }
    }
}

private const val SQRT2 = 1.4142135f

/** One brush for [fill] when it is a flat colour or a single gradient layer; null when it needs [themeFill]. */
internal fun themeFillBrush(fill: ThemeFill, density: Float): Brush? = when {
    fill.layers.isEmpty() -> fill.color?.let { SolidColor(Color(it)) }
    fill.layers.size == 1 && fill.color == null -> cssGradientBrush(fill.layers[0], density)
    else -> null
}

/**
 * Paints a theme [fill] (its colour, then its gradient layers bottom to top, as CSS stacks them) inside [shape]
 * behind the content. Does nothing for a null fill.
 */
fun Modifier.themeFill(fill: ThemeFill?, shape: Shape = RectangleShape, alpha: Float = 1f, slideSec: Float? = null): Modifier {
    if (fill == null || fill.isEmpty) return this
    if (slideSec != null && slideSec > 0f && fill.layers.isNotEmpty()) {
        return this then SlideElement(slideSec, shape, alpha, fill.color?.let { Color(it) }, fill.layers.asReversed(), null, null)
    }
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val brushes = fill.layers.asReversed().mapNotNull { cssGradientBrush(it, density) }
        val base = fill.color?.let { Color(it) }
        onDrawBehind {
            base?.let { drawOutline(outline, it, alpha = alpha) }
            for (b in brushes) drawOutline(outline, b, alpha = alpha)
        }
    }
}

/** Width and height of a sliding gradient relative to its element, as CSS animates a 300 % background. */
private const val SLIDE_STRETCH = 3f

/**
 * Paints [brush] inside [shape] behind the content, panning back and forth across the element once per [seconds] when
 * [seconds] is set; otherwise a plain still fill.
 */
fun Modifier.slidingFill(brush: Brush, seconds: Float?, shape: Shape = RectangleShape, alpha: Float = 1f): Modifier {
    if (seconds == null || seconds <= 0f || brush is SolidColor) return background(brush, shape, alpha)
    return this then SlideElement(seconds, shape, alpha, null, emptyList(), brush, null)
}

/** A line of [height] along the bottom edge over the content, its [brush] sliding as in [slidingFill]. */
fun Modifier.slidingUnderline(brush: Brush, height: Dp, seconds: Float?): Modifier {
    if (seconds == null || seconds <= 0f || brush is SolidColor) {
        return drawWithContent {
            drawContent()
            val h = height.toPx()
            drawRect(brush, Offset(0f, size.height - h), Size(size.width, h))
        }
    }
    return this then SlideElement(seconds, RectangleShape, 1f, null, emptyList(), brush, height)
}

private data class SlideElement(
    val seconds: Float,
    val shape: Shape,
    val alpha: Float,
    val base: Color?,
    /** Bottom layer first. */
    val gradients: List<ThemeGradient>,
    val brush: Brush?,
    /** Set: a strip of this height along the bottom edge, drawn over the content. */
    val underline: Dp?,
) : ModifierNodeElement<SlideNode>() {
    override fun create() = SlideNode(this)

    override fun update(node: SlideNode) = node.update(this)
}

/**
 * Draws a [SlideElement]. While attached it advances its own phase every frame (one pass per [SlideElement.seconds],
 * eased, alternating) and redraws only itself; with the system animation scale at 0 it stays still.
 */
private class SlideNode(private var e: SlideElement) : Modifier.Node(), DrawModifierNode {
    private var phase = 0f
    private var job: Job? = null
    private var brushes: List<Brush> = emptyList()
    private var brushDensity = 0f
    private var outlineSize = Size.Unspecified
    private var outline: Outline? = null
    private var clip: Path? = null

    fun update(next: SlideElement) {
        val restart = next.seconds != e.seconds
        if (next.gradients != e.gradients) brushDensity = 0f
        if (next.shape != e.shape) outlineSize = Size.Unspecified
        e = next
        if (restart && isAttached) start()
        invalidateDraw()
    }

    override fun onAttach() = start()

    override fun onDetach() {
        job = null
    }

    private fun start() {
        job?.cancel()
        job = null
        phase = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val nanosPerPass = (e.seconds * 1_000_000_000L).toLong().coerceAtLeast(1L)
        job = coroutineScope.launch {
            val start = withFrameNanos { it }
            while (isActive) {
                withFrameNanos { t ->
                    val p = ((t - start) % (2 * nanosPerPass)).toFloat() / nanosPerPass
                    val tri = if (p <= 1f) p else 2f - p
                    phase = tri * tri * (3f - 2f * tri)
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val strip = e.underline
        if (strip != null) drawContent()
        if (brushDensity != density) {
            brushes = e.gradients.mapNotNull { cssGradientBrush(it, density) }
            brushDensity = density
        }
        if (outlineSize != size) {
            outline = e.shape.createOutline(size, layoutDirection, this)
            clip = Path().apply { addOutline(outline!!) }
            outlineSize = size
        }
        if (strip != null) {
            val h = strip.toPx()
            clipRect(top = size.height - h) { e.brush?.let { drawSliding(it, phase, Offset(0f, size.height - h), Size(size.width, h), e.alpha) } }
            return
        }
        e.base?.let { drawOutline(outline!!, it, alpha = e.alpha) }
        clipPath(clip!!) {
            for (b in brushes) drawSliding(b, phase, Offset.Zero, size, e.alpha)
            e.brush?.let { drawSliding(it, phase, Offset.Zero, size, e.alpha) }
        }
        drawContent()
    }
}

/** [brush] over an area [SLIDE_STRETCH] times [size], shifted by [phase] (0..1) so the window at [topLeft] pans across it. */
private fun DrawScope.drawSliding(brush: Brush, phase: Float, topLeft: Offset, size: Size, alpha: Float) {
    val big = Size(size.width * SLIDE_STRETCH, size.height * SLIDE_STRETCH)
    val dx = (big.width - size.width) * phase
    val dy = (big.height - size.height) * phase
    translate(topLeft.x - dx, topLeft.y - dy) { drawRect(brush, Offset.Zero, big, alpha) }
}

/**
 * The theme's background image and/or gradient, filling its parent. Draws nothing without one.
 * Place it first in a Box whose other children use [PluviaTheme.screenBackground].
 */
@Composable
fun ThemeBackdrop(modifier: Modifier = Modifier) {
    val spec = PluviaTheme.spec
    val gradient = spec.backgroundGradient
    val imagePath = spec.backgroundImage
    if (gradient == null && imagePath == null) return
    val base = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawRect(base) }
            .then(if (gradient != null) Modifier.themeGradient(gradient) else Modifier),
    ) {
        if (imagePath != null) {
            val bitmap by produceState(BackdropImageCache.peek(imagePath), imagePath) {
                value = withContext(Dispatchers.IO) { decodeBackdrop(imagePath) }
            }
            val blur = spec.blurDp?.takeIf { it > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blur != null) Modifier.blur(blur.dp) else Modifier),
                )
            }
        }
    }
}
