package app.gamenative.powercontrol

import com.winlator.box86_64.Box86_64Preset
import com.winlator.fexcore.FEXCorePreset

/**
 * LUDASHI presets for MediaTek Helio G99/G100 devices.
 *
 * These values are recommendations and per-container runtime settings, not promises of a
 * specific frame rate. Hardware clock tuning remains controlled by the existing driver gate.
 */
enum class LudashiPerformanceMode(
    val title: String,
    val description: String,
    val tuningStrategy: AutoTuningStrategy,
    val suggestedFps: Int,
    val suggestedResolution: String,
    val runtimeResolution: String,
    val box64Preset: String,
    val fexcorePreset: String,
) {
    POWER_SAVE(
        title = "Hemat",
        description = "Kurangi beban render dan utamakan kompatibilitas serta efisiensi.",
        tuningStrategy = AutoTuningStrategy.POWER_EFFICIENT,
        suggestedFps = 30,
        suggestedResolution = "854 × 480",
        runtimeResolution = "854x480",
        box64Preset = Box86_64Preset.STABILITY,
        fexcorePreset = FEXCorePreset.STABILITY,
    ),
    BALANCED(
        title = "Seimbang",
        description = "Profil awal untuk keseimbangan responsivitas, kompatibilitas, dan beban grafis.",
        tuningStrategy = AutoTuningStrategy.BALANCED,
        suggestedFps = 30,
        suggestedResolution = "960 × 540",
        runtimeResolution = "960x540",
        box64Preset = Box86_64Preset.INTERMEDIATE,
        fexcorePreset = FEXCorePreset.INTERMEDIATE,
    ),
    PERFORMANCE(
        title = "Performa",
        description = "Gunakan preset dynarec lebih agresif; hasil dan suhu tetap bergantung pada game.",
        tuningStrategy = AutoTuningStrategy.AGGRESSIVE,
        suggestedFps = 30,
        suggestedResolution = "960 × 540",
        runtimeResolution = "960x540",
        box64Preset = Box86_64Preset.PERFORMANCE,
        fexcorePreset = FEXCorePreset.PERFORMANCE,
    );

    companion object {
        fun fromStoredValue(value: String?): LudashiPerformanceMode =
            entries.firstOrNull { it.name == value } ?: BALANCED
    }
}
