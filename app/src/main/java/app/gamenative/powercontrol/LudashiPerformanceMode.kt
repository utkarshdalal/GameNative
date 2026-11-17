package app.gamenative.powercontrol

/**
 * App-level tuning strategy for the MediaTek G99/G100 target profile.
 *
 * This changes only the decision strategy used by the existing auto-tuner. It does not
 * request CPU/GPU clocks, modify kernel nodes, or disable Android thermal management.
 */
enum class LudashiPerformanceMode(
    val title: String,
    val description: String,
    val tuningStrategy: AutoTuningStrategy,
    val suggestedFps: Int,
    val suggestedResolution: String,
) {
    POWER_SAVE(
        title = "Hemat",
        description = "Utamakan efisiensi dan kurangi penyesuaian performa yang tidak diperlukan.",
        tuningStrategy = AutoTuningStrategy.POWER_EFFICIENT,
        suggestedFps = 30,
        suggestedResolution = "960 × 540",
    ),
    BALANCED(
        title = "Seimbang",
        description = "Pilihan awal untuk menjaga responsivitas dan konsumsi daya.",
        tuningStrategy = AutoTuningStrategy.BALANCED,
        suggestedFps = 30,
        suggestedResolution = "960 × 540",
    ),
    PERFORMANCE(
        title = "Performa",
        description = "Auto-tuner lebih cepat merespons kekurangan FPS bila driver kompatibel tersedia.",
        tuningStrategy = AutoTuningStrategy.AGGRESSIVE,
        suggestedFps = 30,
        suggestedResolution = "960 × 540",
    );

    companion object {
        fun fromStoredValue(value: String?): LudashiPerformanceMode =
            entries.firstOrNull { it.name == value } ?: BALANCED
    }
}
