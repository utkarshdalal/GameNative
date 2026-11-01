package app.gamenative.ui.widget

import app.gamenative.powercontrol.metrics.Bottleneck
import app.gamenative.powercontrol.metrics.BottleneckClassifier
import java.util.Locale
import kotlin.math.roundToInt

/** Row texts of the performance HUD that depend on more than one reading. */
internal object PerformanceHudText {
    private const val MISSING = "--"

    /**
     * GPU row. The percent is always the active usage source's reading (sysfs, Mali counters
     * or the shim); a measured GPU frame time is appended, never substituted:
     * "GPU 8% (0.5 ms)" with both, "GPU 8%" with only a percent, "GPU 0.5 ms" with only a
     * frame time, "GPU --" with neither. The compact form is "GPU 8% 0.5ms".
     */
    fun gpu(percent: Int?, gpuFrameMs: Float?, compact: Boolean = false): String {
        val ms = gpuFrameMs?.takeIf { it.isFinite() && it >= 0f }
        return when {
            percent != null && ms != null ->
                if (compact) String.format(Locale.US, "GPU %d%% %.1fms", percent, ms)
                else String.format(Locale.US, "GPU %d%% (%.1f ms)", percent, ms)
            percent != null -> "GPU $percent%"
            ms != null -> if (compact) String.format(Locale.US, "GPU %.1fms", ms) else String.format(Locale.US, "GPU %.1f ms", ms)
            else -> "GPU $MISSING"
        }
    }

    /**
     * Thermal load when no temperature zone is readable: the raw headroom as a percent
     * (100% = the platform throttles at THERMAL_STATUS_SEVERE), plus " throttling" from
     * THERMAL_STATUS_MODERATE up in the full form. Null without a headroom.
     */
    fun heat(headroom: Float?, thermalStatus: Int?, compact: Boolean): String? {
        val h = headroom?.takeIf { it.isFinite() } ?: return null
        val text = "HEAT ${(h * 100).roundToInt().coerceAtLeast(0)}%"
        val throttling = thermalStatus != null && thermalStatus >= BottleneckClassifier.THERMAL_STATUS_MODERATE
        return if (throttling && !compact) "$text throttling" else text
    }

    fun bottleneck(verdict: Bottleneck, compact: Boolean): String =
        if (compact) "BN: ${verdict.shortLabel()}" else "BOTTLENECK: ${verdict.label()}"
}
