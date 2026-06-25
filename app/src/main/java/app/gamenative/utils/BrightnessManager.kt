package app.gamenative.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
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
        private const val SYSTEM_BRIGHTNESS_MAX = 255f
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

        fun canWriteSystemSettings(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
        }

        fun snapDisplayBrightness(value: Float): Float {
            return (value / DISPLAY_BRIGHTNESS_STEP)
                .roundToInt()
                .times(DISPLAY_BRIGHTNESS_STEP)
                .coerceIn(DISPLAY_BRIGHTNESS_MIN, DISPLAY_BRIGHTNESS_MAX)
        }

        @MainThread
        fun clearDisplayBrightnessOverride(activity: Activity) {
            val params = activity.window.attributes
            if (params.screenBrightness == UNSET_BRIGHTNESS) return
            params.screenBrightness = UNSET_BRIGHTNESS
            activity.window.attributes = params
        }

        @MainThread
        fun readDisplayBrightness(activity: Activity): Float {
            val systemBrightness = runCatching {
                Settings.System.getInt(
                    activity.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                ) / SYSTEM_BRIGHTNESS_MAX
            }.getOrDefault(0.5f)

            return snapDisplayBrightness(systemBrightness)
        }

        @MainThread
        fun applyDisplayBrightness(activity: Activity, value: Float): Boolean {
            val next = snapDisplayBrightness(value)
            val brightness = (next * SYSTEM_BRIGHTNESS_MAX).roundToInt()
            val contentResolver = activity.contentResolver
            val manualModeApplied = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val brightnessApplied = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness,
            )
            clearDisplayBrightnessOverride(activity)
            return manualModeApplied && brightnessApplied
        }
    }
}
