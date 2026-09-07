package app.gamenative.model

import app.gamenative.R
import kotlin.math.abs

enum class FrameGenPreset(
    val flowScale: Int,
    val labelRes: Int,
    val shortLabelRes: Int,
    val descriptionRes: Int,
) {
    ULTRA_PERFORMANCE(
        flowScale = 40,
        labelRes = R.string.frame_generation_preset_ultra_performance,
        shortLabelRes = R.string.frame_generation_preset_ultra_performance_short,
        descriptionRes = R.string.frame_generation_preset_ultra_performance_note,
    ),
    PERFORMANCE(
        flowScale = 50,
        labelRes = R.string.frame_generation_preset_performance,
        shortLabelRes = R.string.frame_generation_preset_performance_short,
        descriptionRes = R.string.frame_generation_preset_performance_note,
    ),
    BALANCED(
        flowScale = 70,
        labelRes = R.string.frame_generation_preset_balanced,
        shortLabelRes = R.string.frame_generation_preset_balanced_short,
        descriptionRes = R.string.frame_generation_preset_balanced_note,
    ),
    QUALITY(
        flowScale = 100,
        labelRes = R.string.frame_generation_preset_quality,
        shortLabelRes = R.string.frame_generation_preset_quality_short,
        descriptionRes = R.string.frame_generation_preset_quality_note,
    ),
    ;

    val flowScaleFloat: Float get() = flowScale / 100.0f

    companion object {
        val DEFAULT = BALANCED

        fun fromFlowScale(flowScale: Int): FrameGenPreset {
            var best = DEFAULT
            var bestDelta = Int.MAX_VALUE
            for (preset in values()) {
                val delta = abs(preset.flowScale - flowScale)
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = preset
                }
            }
            return best
        }

        fun atIndex(index: Int): FrameGenPreset =
            values()[index.coerceIn(0, values().size - 1)]
    }
}
