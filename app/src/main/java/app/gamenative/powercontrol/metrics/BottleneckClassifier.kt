package app.gamenative.powercontrol.metrics

import kotlin.math.abs

enum class BottleneckPart(val key: String, val label: String, val shortLabel: String) {
    CPU("cpu", "CPU", "CPU"),
    GPU("gpu", "GPU", "GPU"),
    THERMAL("thermal", "THERMAL", "HOT"),
    CAPPED("capped", "CAPPED", "CAP"),
    NONE("none", "NONE", "NONE"),
}

/** Verdict of [BottleneckClassifier]: one part, or CPU/GPU combined with THERMAL. */
enum class Bottleneck(val parts: List<BottleneckPart>) {
    CPU(listOf(BottleneckPart.CPU)),
    GPU(listOf(BottleneckPart.GPU)),
    THERMAL(listOf(BottleneckPart.THERMAL)),
    CAPPED(listOf(BottleneckPart.CAPPED)),
    NONE(listOf(BottleneckPart.NONE)),
    CPU_THERMAL(listOf(BottleneckPart.CPU, BottleneckPart.THERMAL)),
    GPU_THERMAL(listOf(BottleneckPart.GPU, BottleneckPart.THERMAL)),
    ;

    /** HUD text, e.g. "GPU+THERMAL". */
    fun label(): String = parts.joinToString("+") { it.label }

    /** Compact HUD text, e.g. "GPU+HOT". */
    fun shortLabel(): String = parts.joinToString("+") { it.shortLabel }

    /** Telemetry value, e.g. "gpu+thermal". */
    fun key(): String = parts.joinToString("+") { it.key }
}

data class BottleneckInput(
    val fps: Float,
    /** Current average frame time; 0 or less when unknown. */
    val frameMs: Float,
    /** Configured FPS limit; 0 = none. */
    val fpsCap: Int,
    /** Display refresh rate; 0 or less when unknown. */
    val vsyncHz: Float,
    val cpuTotalPct: Float?,
    /** Load of the busiest core. */
    val coreMaxPct: Float?,
    val gpuPct: Float?,
    val gpuFrameMs: Float?,
    /** Raw thermal headroom: 1.0 = throttling at THERMAL_STATUS_SEVERE. */
    val headroom: Float?,
    /** PowerManager.THERMAL_STATUS_*; null when unknown. */
    val thermalStatus: Int?,
)

object BottleneckClassifier {
    const val THERMAL_STATUS_MODERATE = 2
    const val THERMAL_STATUS_SEVERE = 3

    private const val CAP_TOLERANCE_FPS = 1.0f
    private const val CAPPED_LOAD_MAX = 70f
    private const val CAPPED_GPU_FRAME_SHARE = 0.7f
    private const val GPU_FRAME_SHARE = 0.8f
    private const val GPU_BUSY_PCT = 85f
    private const val CORE_BUSY_PCT = 85f
    private const val CPU_TOTAL_BUSY_PCT = 90f
    private const val HEADROOM_HOT = 0.95f
    private val DESIGN_CAPS = listOf(30f, 60f)

    fun classify(input: BottleneckInput): Bottleneck = with(input) {
        val frameKnown = frameMs > 0f && frameMs.isFinite()
        val gpuMs = gpuFrameMs?.takeIf { it.isFinite() && it >= 0f }

        if (isCapped(input, frameKnown, gpuMs)) return Bottleneck.CAPPED

        val gpuByTime = gpuMs != null && frameKnown && gpuMs >= GPU_FRAME_SHARE * frameMs
        val gpuBound = gpuByTime || (gpuPct ?: 0f) >= GPU_BUSY_PCT
        val cpuBound = if (coreMaxPct != null) coreMaxPct >= CORE_BUSY_PCT else (cpuTotalPct ?: 0f) >= CPU_TOTAL_BUSY_PCT
        val thermal = (headroom != null && headroom >= HEADROOM_HOT) ||
            (thermalStatus != null && thermalStatus >= THERMAL_STATUS_SEVERE)

        // Both loaded: a measured GPU frame time decides, otherwise the higher load wins.
        val gpuWins = gpuBound && (!cpuBound || gpuByTime || (gpuPct ?: 0f) >= (coreMaxPct ?: cpuTotalPct ?: 0f))
        return when {
            gpuWins && thermal -> Bottleneck.GPU_THERMAL
            gpuWins -> Bottleneck.GPU
            cpuBound && thermal -> Bottleneck.CPU_THERMAL
            cpuBound -> Bottleneck.CPU
            thermal -> Bottleneck.THERMAL
            else -> Bottleneck.NONE
        }
    }

    private fun isCapped(input: BottleneckInput, frameKnown: Boolean, gpuMs: Float?): Boolean = with(input) {
        if (fps <= 0f || !fps.isFinite()) return false
        val targets = buildList {
            if (fpsCap > 0) add(fpsCap.toFloat())
            if (vsyncHz > 0f && vsyncHz.isFinite()) add(vsyncHz)
            if (isEmpty()) addAll(DESIGN_CAPS)
        }
        if (targets.none { abs(fps - it) <= CAP_TOLERANCE_FPS }) return false
        val cpuLoad = coreMaxPct ?: cpuTotalPct ?: 0f
        if (cpuLoad >= CAPPED_LOAD_MAX) return false
        if ((gpuPct ?: 0f) >= CAPPED_LOAD_MAX) return false
        if (gpuMs != null && (!frameKnown || gpuMs >= CAPPED_GPU_FRAME_SHARE * frameMs)) return false
        return true
    }
}
