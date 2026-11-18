package app.gamenative.optimize

import kotlin.math.roundToInt
import org.json.JSONObject

/** What a first card shows of the session behind its diagnosis, and the outcome it offers. */
object OptimizeEvidence {

    /** One line under the card's title: what the player saw, in the session's own numbers. */
    sealed interface Line {
        /** The frame rate fell from [from] to [to] fps over the session. */
        data class Drop(val from: Int, val to: Int) : Line

        /** [pct] percent of the session's frames stuttered. */
        data class Stutter(val pct: Int) : Line

        /** The session averaged [fps]; [dipsTo] is where its slow moments fell to, when well below that. */
        data class Fps(val fps: Int, val dipsTo: Int? = null) : Line
    }

    /** The outcome a first card offers. */
    sealed interface Offer {
        data object Heat : Offer
        data object Smoother : Offer
        data object Stutter : Offer

        /** A frame rate to hold, [lessHeat] when heat is what broke it. */
        data class Steady(val fps: Int, val lessHeat: Boolean) : Offer
    }

    const val STUTTER_MIN_PCT = 1
    const val DIP_SHARE = 0.8
    const val REACHED_SHARE = 0.95

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

    /** The line for a diagnosis, from what the session recorded; null when it recorded nothing worth showing. */
    fun line(cls: String?, m: Metrics?): Line? {
        if (m == null) return null
        val fps = m.steadyFps?.roundToInt()?.takeIf { it > 0 }
        val stutter = m.stutterShare?.let { (it * 100).roundToInt() }?.takeIf { it >= STUTTER_MIN_PCT }?.let { Line.Stutter(it) }
        val plain = fps?.let { avg -> Line.Fps(avg, m.fpsP10?.roundToInt()?.takeIf { it > 0 && it <= DIP_SHARE * avg }) }
        return when (cls) {
            "thermal_gpu" -> drop(m) ?: plain
            "gpu" -> drop(m).takeIf { m.throttleOnsetSec != null } ?: plain
            "cpu_single", "cpu_multi" -> plain
            "memory" -> stutter
            "present_locked" -> stutter ?: plain
            else -> null
        }
    }

    /** The frame rate the session held before it fell, when that was the one the screen and limiter allow. */
    fun reachedFps(m: Metrics?): Int? {
        if (m == null) return null
        val from = drop(m)?.from ?: return null
        val target = OptimizeTarget.fpsTarget(m.refreshHz, 0)
        return target.roundToInt().takeIf { from >= REACHED_SHARE * target }
    }

    /**
     * The outcome offered for a diagnosis. A frame rate is named only when there is one to stand behind: the one the
     * server expects ([expectedFps]), else the one the session itself held before it fell.
     */
    fun offer(cls: String?, m: Metrics?, expectedFps: Int?): Offer {
        if (cls in STUTTER_CLASSES) return Offer.Stutter
        val heat = cls == "thermal_gpu" || droppedAfterMin(cls, m) != null
        val fps = expectedFps?.takeIf { it > 0 } ?: reachedFps(m)
        return when {
            fps != null -> Offer.Steady(fps, heat)
            heat -> Offer.Heat
            else -> Offer.Smoother
        }
    }

    /** The frame rate the server expects the game to hold once tuned, from a next-rung answer's `expected` object. */
    data class Expected(val steadyFps: Int) {
        companion object {
            private val FPS = 20..240

            fun parse(o: JSONObject?): Expected? = o?.optIntOrNull("steady_fps")?.takeIf { it in FPS }?.let { Expected(it) }
        }
    }
}
