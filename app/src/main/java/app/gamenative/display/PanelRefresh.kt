package app.gamenative.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** The panel's active refresh rate, read one way for every consumer. */
object PanelRefresh {
    /** The rate reported when the display can't be read. */
    const val FALLBACK_HZ = 60

    /**
     * The panel's refresh rate in Hz: the mode the display is in now, not the fastest it supports,
     * rounded to an integer. The daemon's advertised mode and latch-grid period
     * ([GameNativeDrm.nativeSetPanelRefreshHz], which gamescope takes its refresh from) and the FPS
     * limiter's ceiling both come from here, so they agree; a panel a user holds at 60 Hz counts as
     * 60 even when it can do 120.
     */
    @JvmStatic
    fun activeHz(context: Context, attachedView: View? = null): Int {
        val display = attachedView?.display
            ?: runCatching { context.display }.getOrNull()
            ?: ContextCompat.getSystemService(context, DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.refreshRate
            ?.takeIf { it.isFinite() && it > 0f }
            ?.roundToInt()
            ?.coerceAtLeast(5)
            ?: FALLBACK_HZ
    }
}
