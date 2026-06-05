package app.gamenative.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.annotation.MainThread
import kotlin.math.roundToInt

class BrightnessManager(private val activity: Activity, private val targetBrightness: Float) {
    private var savedBrightness: Float = UNSET_BRIGHTNESS
    private var isDimmed = false

    @MainThread
    fun dim() {
        if (isDimmed) return
        val window = activity.window
        savedBrightness = window.attributes.screenBrightness
        val params = window.attributes
        params.screenBrightness = targetBrightness
        window.attributes = params
        isDimmed = true
    }

    @MainThread
    fun restore() {
        if (!isDimmed) return
        val window = activity.window
        val params = window.attributes
        params.screenBrightness = savedBrightness
        window.attributes = params
        savedBrightness = UNSET_BRIGHTNESS
        isDimmed = false
    }

    companion object {
        private const val UNSET_BRIGHTNESS = -1f
        const val DISPLAY_BRIGHTNESS_STEP = 0.05f
        const val DISPLAY_BRIGHTNESS_MIN = 0.05f
        const val DISPLAY_BRIGHTNESS_MAX = 1f

        tailrec fun findActivity(context: Context): Activity? {
            return when (context) {
                is Activity -> context
                is ContextWrapper -> findActivity(context.baseContext)
                else -> null
            }
        }

        fun snapDisplayBrightness(value: Float): Float {
            return (value / DISPLAY_BRIGHTNESS_STEP)
                .roundToInt()
                .times(DISPLAY_BRIGHTNESS_STEP)
                .coerceIn(DISPLAY_BRIGHTNESS_MIN, DISPLAY_BRIGHTNESS_MAX)
        }

        @MainThread
        fun readDisplayBrightness(activity: Activity): Float {
            val windowBrightness = activity.window.attributes.screenBrightness
            if (windowBrightness in DISPLAY_BRIGHTNESS_MIN..DISPLAY_BRIGHTNESS_MAX) {
                return snapDisplayBrightness(windowBrightness)
            }

            val systemBrightness = runCatching {
                Settings.System.getInt(
                    activity.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                ) / 255f
            }.getOrDefault(0.5f)

            return snapDisplayBrightness(systemBrightness)
        }

        @MainThread
        fun applyDisplayBrightness(activity: Activity, value: Float) {
            val params = activity.window.attributes
            params.screenBrightness = value.coerceIn(DISPLAY_BRIGHTNESS_MIN, DISPLAY_BRIGHTNESS_MAX)
            activity.window.attributes = params
        }
    }
}
