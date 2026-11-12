package app.gamenative.ui.theme

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.gamenative.theme.ThemeGradient
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
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

/** Size-aware brush for [gradient] with the same CSS angle semantics as the backdrop; null without colours. */
internal fun cssGradientBrush(gradient: ThemeGradient): Brush? {
    val colors = gradient.colors.map { Color(it) }
    if (colors.isEmpty()) return null
    if (colors.size == 1) return SolidColor(colors[0])
    val stops = gradient.stops.takeIf { it.size == colors.size }
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
