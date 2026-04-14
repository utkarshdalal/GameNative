package app.gamenative.utils

import android.app.Activity
import androidx.annotation.MainThread

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
    }
}
