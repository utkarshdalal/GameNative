package app.gamenative.optimize

import kotlin.math.roundToInt
import org.json.JSONObject

/** What a first card shows of the session behind its diagnosis, and the outcome it offers. */
object OptimizeEvidence {

    /** One line of measured evidence under the card's title. */
    sealed interface Line {
        /** The frame rate fell from [from] to [to] fps over the session. */
        data class Drop(val from: Int, val to: Int) : Line

        /** The session averaged [fps] with the GPU [gpuPct] percent busy. */
        data class Gpu(val fps: Int, val gpuPct: Int) : Line

        /** The session averaged [fps] with one processor core at its limit. */
        data class Core(val fps: Int) : Line

        /** [pct] percent of the session's frames stuttered. */
        data class Stutter(val pct: Int) : Line

        data class Fps(val fps: Int) : Line
    }

    /** The outcome a first card offers; [pct] is the gain the server expects, when it sent one. */
    sealed interface Offer {
        data object Heat : Offer
        data object Fps : Offer
        data object Stutter : Offer
        data class FpsGain(val pct: Int) : Offer
        data class StutterDrop(val pct: Int) : Offer
    }

    const val GPU_BUSY_MIN_PCT = 90
    const val CORE_LIMIT_MIN_PCT = 90
    const val STUTTER_MIN_PCT = 1

    private val HEAT_CLASSES = setOf("gpu", "thermal_gpu")
    private val STUTTER_CLASSES = setOf("memory", "present_locked")

    /** The five-minute windows that count: the first holds loading and menus once there are three. */
    private fun gameplayWindows(values: List<Int>): List<Int> = if (values.size >= 3) values.drop(1) else values

    /** The fall in frame rate over the session's gameplay windows, when it is a real one. */
    fun drop(m: Metrics): Line.Drop? {
        val windows = gameplayWindows(m.fpsBy5Min)
        if (windows.size < 2) return null
        val from = windows.first()
        val to = windows.last()
        return Line.Drop(from, to).takeIf { to > 0 && from - to >= OptimizeTarget.HOT_FPS_DECLINE }
    }

    /**
     * Minutes into the session before frames started dropping, for a title that says so: a GPU or heat diagnosis whose
     * session throttled. A GPU diagnosis also needs the fall to show in the frame rate; a heat one already claims it.
     */
    fun droppedAfterMin(cls: String?, m: Metrics?): Int? {
        if (m == null || cls !in HEAT_CLASSES) return null
        val onset = m.throttleOnsetSec ?: return null
        if (cls == "gpu" && drop(m) == null) return null
        return (onset / 60).coerceAtLeast(1)
    }

    /** The evidence line for a diagnosis, from what the session recorded; null when it recorded nothing worth showing. */
    fun line(cls: String?, m: Metrics?): Line? {
        if (m == null) return null
        val fps = m.steadyFps?.roundToInt()?.takeIf { it > 0 }
        val stutter = m.stutterShare?.let { (it * 100).roundToInt() }?.takeIf { it >= STUTTER_MIN_PCT }?.let { Line.Stutter(it) }
        val gpu = m.gpuBusyP95?.takeIf { it >= GPU_BUSY_MIN_PCT }?.let { busy -> fps?.let { Line.Gpu(it, busy) } }
        val plain = fps?.let { Line.Fps(it) }
        return when (cls) {
            "thermal_gpu" -> drop(m) ?: gpu ?: plain
            "gpu" -> drop(m).takeIf { m.throttleOnsetSec != null } ?: gpu ?: plain
            "cpu_single" -> m.hotThreadP95?.takeIf { it >= CORE_LIMIT_MIN_PCT }?.let { fps?.let { Line.Core(it) } } ?: plain
            "cpu_multi" -> plain
            "memory" -> stutter
            "present_locked" -> stutter ?: plain
            else -> null
        }
    }

    /**
     * The outcome offered for a diagnosis. A gain the server expects ([fpsGainPct], [stutterDropPct]) is named with its
     * number; otherwise the outcome is the one the diagnosis points at.
     */
    fun offer(cls: String?, m: Metrics?, fpsGainPct: Int?, stutterDropPct: Int?): Offer {
        val fps = fpsGainPct?.takeIf { it > 0 }?.let { Offer.FpsGain(it) }
        val stutter = stutterDropPct?.takeIf { it > 0 }?.let { Offer.StutterDrop(it) }
        return when {
            cls in STUTTER_CLASSES -> stutter ?: fps ?: Offer.Stutter
            fps != null -> fps
            stutter != null -> stutter
            cls == "thermal_gpu" || droppedAfterMin(cls, m) != null -> Offer.Heat
            else -> Offer.Fps
        }
    }

    /** The gain the server expects from tuning, from a next-rung answer's `expected` object; null fields when absent. */
    data class Expected(val fpsGainPct: Int?, val stutterDropPct: Int?) {
        companion object {
            const val MAX_PCT = 300

            fun parse(o: JSONObject?): Expected? {
                if (o == null) return null
                val fps = o.optIntOrNull("fps_gain_pct")?.takeIf { it in 1..MAX_PCT }
                val stutter = o.optIntOrNull("stutter_drop_pct")?.takeIf { it in 1..100 }
                return Expected(fps, stutter).takeIf { fps != null || stutter != null }
            }
        }
    }
}
