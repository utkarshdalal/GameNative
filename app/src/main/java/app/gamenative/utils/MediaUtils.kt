package app.gamenative.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.service.SteamService
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.io.File
import java.io.FileOutputStream
import timber.log.Timber

/**
 * Media/image utilities shared across the app.
 *
 * Coding style aligns with other files under app.gamenative.utils (top-level helpers + object for stateful ops).
 */
object MediaUtils {
    // Observable media version to trigger UI refresh when custom images change
    private val _mediaVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val mediaVersionFlow: kotlinx.coroutines.flow.StateFlow<Int> = _mediaVersion
    fun notifyMediaChanged() { _mediaVersion.value = _mediaVersion.value + 1 }

    // --- Custom media (hero/logo/capsule/header) helpers ---
    private fun mediaDirFor(appId: Int): File {
        val base = File(SteamService.getAppDirPath(appId))
        val dir = File(base, "media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCustomHeroFile(appId: Int): File = File(mediaDirFor(appId), "custom_hero.jpg")
    fun getCustomLogoFile(appId: Int): File = File(mediaDirFor(appId), "custom_logo.png")
    fun getCustomCapsuleFile(appId: Int): File = File(mediaDirFor(appId), "custom_capsule.jpg")
    fun getCustomHeaderFile(appId: Int): File = File(mediaDirFor(appId), "custom_header.jpg")
    fun getCustomIconFile(appId: Int): File = File(mediaDirFor(appId), "custom_icon.png")

    fun hasCustomHero(appId: Int): Boolean = getCustomHeroFile(appId).exists()
    fun hasCustomLogo(appId: Int): Boolean = getCustomLogoFile(appId).exists()
    fun hasCustomCapsule(appId: Int): Boolean = getCustomCapsuleFile(appId).exists()
    fun hasCustomHeader(appId: Int): Boolean = getCustomHeaderFile(appId).exists()
    fun hasCustomIcon(appId: Int): Boolean = getCustomIconFile(appId).exists()

    fun resetCustomHero(appId: Int) {
        runCatching { getCustomHeroFile(appId).delete() }
        notifyMediaChanged()
    }
    fun resetCustomLogo(appId: Int) {
        runCatching { getCustomLogoFile(appId).delete() }
        notifyMediaChanged()
    }
    fun resetCustomCapsule(appId: Int) {
        runCatching { getCustomCapsuleFile(appId).delete() }
        notifyMediaChanged()
    }
    fun resetCustomHeader(appId: Int) {
        runCatching { getCustomHeaderFile(appId).delete() }
        notifyMediaChanged()
    }
    fun resetCustomIcon(appId: Int) {
        runCatching { getCustomIconFile(appId).delete() }
        notifyMediaChanged()
    }

    /**
     * Save a custom hero image. The image will be center-cropped to 920x430 and saved as JPEG.
     */
    fun saveCustomHero(context: Context, appId: Int, sourceUri: Uri): Boolean =
        try {
            val bmp = decodeBitmap(context, sourceUri) ?: return false
            val out = centerCropResize(bmp, 920, 430)
            saveJpeg(out, getCustomHeroFile(appId))
            notifyMediaChanged()
            true
        } catch (t: Throwable) { Timber.w(t, "saveCustomHero failed"); false }

    /**
     * Save a custom logo image. It will be fitted inside 600x200 canvas preserving aspect, with transparent background.
     */
    fun saveCustomLogo(context: Context, appId: Int, sourceUri: Uri): Boolean =
        try {
            val bmp = decodeBitmap(context, sourceUri) ?: return false
            val out = fitIntoCanvas(bmp, 600, 200)
            savePng(out, getCustomLogoFile(appId))
            notifyMediaChanged()
            true
        } catch (t: Throwable) { Timber.w(t, "saveCustomLogo failed"); false }

    /**
     * Save a custom capsule image for grid capsule view. Center-crop to 600x900 (portrait) JPEG.
     */
    fun saveCustomCapsule(context: Context, appId: Int, sourceUri: Uri): Boolean =
        try {
            val bmp = decodeBitmap(context, sourceUri) ?: return false
            val out = centerCropResize(bmp, 600, 900)
            saveJpeg(out, getCustomCapsuleFile(appId))
            notifyMediaChanged()
            true
        } catch (t: Throwable) { Timber.w(t, "saveCustomCapsule failed"); false }

    /**
     * Save a custom header image for list view. Center-crop to 460x215 JPEG.
     */
    fun saveCustomHeader(context: Context, appId: Int, sourceUri: Uri): Boolean =
        try {
            val bmp = decodeBitmap(context, sourceUri) ?: return false
            val out = centerCropResize(bmp, 460, 215)
            saveJpeg(out, getCustomHeaderFile(appId))
            notifyMediaChanged()
            true
        } catch (t: Throwable) { Timber.w(t, "saveCustomHeader failed"); false }

    /**
     * Save a custom icon image for list view. The image will be center-cropped to 512x512 and saved as PNG.
     */
    fun saveCustomIcon(context: Context, appId: Int, sourceUri: Uri): Boolean =
        try {
            val bmp = decodeBitmap(context, sourceUri) ?: return false
            val out = centerCropResize(bmp, 512, 512)
            savePng(out, getCustomIconFile(appId))
            notifyMediaChanged()
            true
        } catch (t: Throwable) { Timber.w(t, "saveCustomIcon failed"); false }

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri).use { ins ->
                if (ins == null) null else BitmapFactory.decodeStream(ins)
            }
        } catch (t: Throwable) { Timber.w(t, "decodeBitmap failed"); null }
    }

    private fun centerCropResize(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val scale = maxOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = (scaledW - targetW) / 2
        val y = (scaledH - targetH) / 2
        return Bitmap.createBitmap(
            scaled,
            x.coerceAtLeast(0),
            y.coerceAtLeast(0),
            targetW.coerceAtMost(scaled.width),
            targetH.coerceAtMost(scaled.height)
        )
    }

    private fun fitIntoCanvas(src: Bitmap, canvasW: Int, canvasH: Int): Bitmap {
        val out = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val scale = minOf(canvasW.toFloat() / src.width, canvasH.toFloat() / src.height)
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val left = (canvasW - w) / 2f
        val top = (canvasH - h) / 2f
        val dst = RectF(left, top, left + w, top + h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, null, dst, paint)
        return out
    }

    private fun saveJpeg(bmp: Bitmap, file: File) {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        FileOutputStream(file).use { fos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }
    }

    private fun savePng(bmp: Bitmap, file: File) {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        FileOutputStream(file).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
    }

    fun getCustomHeroUri(appId: Int): Uri? = getCustomHeroFile(appId).takeIf { it.exists() }?.let { Uri.fromFile(it) }
    fun getCustomLogoUri(appId: Int): Uri? = getCustomLogoFile(appId).takeIf { it.exists() }?.let { Uri.fromFile(it) }
    fun getCustomCapsuleUri(appId: Int): Uri? = getCustomCapsuleFile(appId).takeIf { it.exists() }?.let { Uri.fromFile(it) }
    fun getCustomHeaderUri(appId: Int): Uri? = getCustomHeaderFile(appId).takeIf { it.exists() }?.let { Uri.fromFile(it) }
    fun getCustomIconUri(appId: Int): Uri? = getCustomIconFile(appId).takeIf { it.exists() }?.let { Uri.fromFile(it) }
}

/**
 * Cache-busting helper: appends a version query to supported models so Coil invalidates its cache.
 */
fun bustCache(model: Any?, version: Int): Any? {
    if (model == null) return null
    return when (model) {
        is String -> {
            val s = model
            val lower = s.lowercase()
            if (lower.startsWith("http") || lower.startsWith("file:") || lower.startsWith("content:")) {
                val sep = if (s.contains("?")) "&" else "?"
                s + sep + "v=" + version
            } else s
        }
        is Uri -> {
            val scheme = model.scheme?.lowercase()
            if (scheme == "http" || scheme == "https" || scheme == "file" || scheme == "content") {
                val s = model.toString()
                val sep = if (s.contains("?")) "&" else "?"
                Uri.parse(s + sep + "v=" + version)
            } else model
        }
        else -> model // For File or other models we leave as-is.
    }
}

// ---------------------- UI helpers (reused across screens) ----------------------
@Composable
internal fun ListItemImage(
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier.clip(CircleShape),
    contentDescription: String? = null,
    size: Dp = 40.dp,
    image: () -> Any?,
    onFailure: () -> Unit = {},
) {
    CoilImage(
        modifier = modifier
            .size(size)
            .then(imageModifier),
        imageModel = image,
        imageOptions = ImageOptions(
            contentScale = ContentScale.Fit,
            contentDescription = contentDescription,
        ),
        loading = { CircularProgressIndicator() },
        failure = {
            onFailure()
            Icon(Icons.Filled.QuestionMark, null)
        },
        previewPlaceholder = painterResource(R.drawable.ic_logo_color),
    )
}

@Composable
internal fun SteamIconImage(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 40.dp,
    image: () -> Any?,
) {
    CoilImage(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp)),
        imageModel = image,
        imageOptions = ImageOptions(
            contentScale = ContentScale.Crop,
            contentDescription = contentDescription,
        ),
        loading = { CircularProgressIndicator() },
        failure = { Icon(Icons.Default.AccountCircle, null) },
        previewPlaceholder = painterResource(R.drawable.ic_logo_color),
    )
}

@Composable
fun EmoticonImage(
    size: Dp = 54.dp,
    image: () -> Any?,
) {
    CoilImage(
        modifier = Modifier.size(size),
        imageModel = image,
        loading = { CircularProgressIndicator() },
        failure = { Icon(Icons.Filled.QuestionMark, null) },
        previewPlaceholder = painterResource(R.drawable.ic_logo_color),
    )
}

@Composable
fun StickerImage(
    size: Dp = 150.dp,
    image: () -> Any?,
) {
    EmoticonImage(size, image)
}

@Preview
@Composable
private fun Preview_EmoticonImage() {
    PluviaTheme {
        EmoticonImage { "https://steamcommunity-a.akamaihd.net/economy/emoticonlarge/roar" }
    }
}

@Preview
@Composable
private fun Preview_StickerImage() {
    PluviaTheme {
        StickerImage { "https://steamcommunity-a.akamaihd.net/economy/sticker/Delivery%20Cat%20in%20a%20Blanket" }
    }
}
