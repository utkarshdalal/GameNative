package app.gamenative.ui.widget

import app.gamenative.powercontrol.metrics.Bottleneck
import app.gamenative.powercontrol.metrics.BottleneckClassifier
import java.util.Locale
import kotlin.math.roundToInt

/** Row texts of the performance HUD that depend on more than one reading. */
internal object PerformanceHudText {
    private const val MISSING = "--"

    /** GPU busy share of the current frame derived from a measured GPU frame time; null without both. */
    fun gpuFrameSharePercent(gpuFrameMs: Float?, fps: Float): Int? {
        if (gpuFrameMs == null || fps <= 0f || !fps.isFinite()) return null
        return (gpuFrameMs * fps / 10f).roundToInt().coerceIn(0, 100)
    }

    /** "GPU 12.3 ms (91%)" with a GPU frame time, "GPU 38%" with only a busy percent, "GPU --" with neither. */
    fun gpu(percent: Int?, gpuFrameMs: Float?, fps: Float): String {
        if (gpuFrameMs != null) {
            val ms = String.format(Locale.US, "GPU %.1f ms", gpuFrameMs)
            return gpuFrameSharePercent(gpuFrameMs, fps)?.let { "$ms ($it%)" } ?: ms
        }
        return percent?.let { "GPU $it%" } ?: "GPU $MISSING"
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
