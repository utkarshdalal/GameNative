package app.gamenative.ui.data

/**
 * Size presets for the floating performance HUD.
 */
enum class PerformanceHudSize(val prefValue: String) {
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large"),
    ;

    companion object {
        fun fromPrefValue(value: String?): PerformanceHudSize {
            return values().firstOrNull { it.prefValue == value } ?: MEDIUM
        }
    }
}

/**
 * Controls which metrics are rendered inside the floating performance HUD.
 */
data class PerformanceHudConfig(
    val showFrameRate: Boolean = true,
    val showCpuUsage: Boolean = true,
    val showGpuUsage: Boolean = true,
    val showRamUsage: Boolean = true,
    val showBatteryLevel: Boolean = true,
    val showPowerDraw: Boolean = true,
    val showBatteryRuntime: Boolean = false,
    val showClockTime: Boolean = false,
    val showCpuTemperature: Boolean = true,
    val showGpuTemperature: Boolean = true,
    val showFrameRateGraph: Boolean = false,
    val showCpuUsageGraph: Boolean = false,
    val showGpuUsageGraph: Boolean = false,
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
    val colorIntensity: Float = DEFAULT_COLOR_INTENSITY,
    val showTextOutline: Boolean = DEFAULT_SHOW_TEXT_OUTLINE,
    val size: PerformanceHudSize = PerformanceHudSize.MEDIUM,
) {
    companion object {
        const val DEFAULT_BACKGROUND_OPACITY = 0.72f
        const val DEFAULT_COLOR_INTENSITY = 1f
        const val DEFAULT_SHOW_TEXT_OUTLINE = true
    }
}
