package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import app.gamenative.MainActivity
import app.gamenative.R
import app.gamenative.data.GameSource
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun createAdaptiveIconBitmap(context: Context, src: Bitmap): Bitmap {
    val density = context.resources.displayMetrics.density
    val targetSize = (108f * density).toInt().coerceAtLeast(108)
    val outBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(outBmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // --- Compute a background color from the icon's edge-center pixels (top/bottom/left/right) ---
    fun medianChannel(a: Int, b: Int, c: Int, d: Int): Int {
        // Median-of-four by sorting 4 values; small fixed-size so simple sort is fine
        val arr = intArrayOf(a, b, c, d)
        Arrays.sort(arr)
        // For even count, take average of the two middle values to avoid bias
        return ((arr[1] + arr[2]) / 2f).toInt()
    }
    fun sampleEdgeColor(bmp: Bitmap): Int {
        if (bmp.width <= 1 || bmp.height <= 1) return 0 // transparent fallback
        val midX = bmp.width / 2
        val midY = bmp.height / 2
        val top = bmp.getPixel(midX.coerceIn(0, bmp.width - 1), 0)
        val bottom = bmp.getPixel(midX.coerceIn(0, bmp.width - 1), bmp.height - 1)
        val left = bmp.getPixel(0, midY.coerceIn(0, bmp.height - 1))
        val right = bmp.getPixel(bmp.width - 1, midY.coerceIn(0, bmp.height - 1))

        // If three or more of the four edge-center pixels are exactly the same color, use that color
        val counts = hashMapOf<Int, Int>()
        listOf(top, bottom, left, right).forEach { c -> counts[c] = (counts[c] ?: 0) + 1 }
        val majority = counts.entries.firstOrNull { it.value >= 3 }?.key
        if (majority != null) return majority

        // Otherwise, fall back to median per channel to get a robust blended background
        val r = medianChannel(Color.red(top), Color.red(bottom), Color.red(left), Color.red(right))
        val g = medianChannel(Color.green(top), Color.green(bottom), Color.green(left), Color.green(right))
        val b = medianChannel(Color.blue(top), Color.blue(bottom), Color.blue(left), Color.blue(right))
        val a = medianChannel(Color.alpha(top), Color.alpha(bottom), Color.alpha(left), Color.alpha(right))
        return Color.argb(a, r, g, b)
    }

    val bgColor = sampleEdgeColor(src)

    // Fill background first so transparent icons still have a pleasant backdrop
    canvas.drawColor(bgColor)

    // Add uniform inset so icons are not cropped too tightly
    val insetFraction = 0.18f // 18% padding around
    val availSize = targetSize * (1f - insetFraction * 2f)

    // Center-fit scale to keep entire icon visible inside the padded area
    val scale = minOf(
        availSize.toFloat() / src.width.coerceAtLeast(1),
        availSize.toFloat() / src.height.coerceAtLeast(1),
    )
    val drawW = src.width * scale
    val drawH = src.height * scale
    val left = (targetSize - drawW) / 2f
    val top = (targetSize - drawH) / 2f
    val dest = RectF(left, top, left + drawW, top + drawH)

    canvas.drawBitmap(src, null, dest, paint)

    return outBmp
}

internal suspend fun createPinnedShortcut(context: Context, gameId: Int, label: String, gameSource: GameSource, iconUrl: String?) {
    val appContext = context.applicationContext
    val shortcutManager = appContext.getSystemService(ShortcutManager::class.java)

    val intent = Intent("app.gamenative.LAUNCH_GAME").apply {
        setClass(appContext, MainActivity::class.java)
        putExtra("app_id", gameId)
        putExtra("game_source", gameSource.name)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    // Try to load game's icon bitmap; fallback to built-in adaptive icon
    val bitmapIcon: Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (!iconUrl.isNullOrBlank()) {
                val loader = ImageLoader(appContext)
                val request = ImageRequest.Builder(appContext)
                    .data(iconUrl)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                val drawable = (result as? SuccessResult)?.drawable
                val rawBitmap = when (drawable) {
                    is BitmapDrawable -> drawable.bitmap

                    else -> {
                        if (drawable != null) {
                            val bmp = Bitmap.createBitmap(
                                drawable.intrinsicWidth.coerceAtLeast(1),
                                drawable.intrinsicHeight.coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            val canvas = Canvas(bmp)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                            bmp
                        } else {
                            null
                        }
                    }
                }
                rawBitmap
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    val finalIcon: Icon = if (bitmapIcon != null) {
        val adaptiveBmp = createAdaptiveIconBitmap(appContext, bitmapIcon)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Icon.createWithAdaptiveBitmap(adaptiveBmp)
        } else {
            Icon.createWithBitmap(adaptiveBmp)
        }
    } else {
        Icon.createWithResource(appContext, R.mipmap.ic_shortcut_filter)
    }

    val shortcut = ShortcutInfo.Builder(appContext, "game_$gameId")
        .setShortLabel(label)
        .setLongLabel(label)
        .setIcon(finalIcon)
        .setIntent(intent)
        .build()

    withContext(Dispatchers.Main) {
        if (shortcutManager?.isRequestPinShortcutSupported == true) {
            shortcutManager.requestPinShortcut(shortcut, null)
        } else {
            val existing = shortcutManager?.dynamicShortcuts ?: emptyList()
            shortcutManager?.dynamicShortcuts = (existing + shortcut).take(4)
        }
    }
}
