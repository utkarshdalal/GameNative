package app.gamenative.ui.data

import app.gamenative.PrefManager

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
    val showBatteryTemperature: Boolean = false,
    val showClockTime: Boolean = false,
    val showCpuTemperature: Boolean = true,
    val showGpuTemperature: Boolean = true,
    val showFrameRateGraph: Boolean = false,
    val showCpuUsageGraph: Boolean = false,
    val showGpuUsageGraph: Boolean = false,
    // power-user metrics. off by default -- debug-oriented.
    val showFrameTime: Boolean = false,
    val showLow1Pct: Boolean = false,
    val showLow01Pct: Boolean = false,
    val showCpuCores: Boolean = false,
    val showThermalStatus: Boolean = false,
    val showGpuMemory: Boolean = false,
    // energy metrics. off by default.
    val showEnergySession: Boolean = false,
    val showMahUsed: Boolean = false,
    val showAvgPower: Boolean = false,
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
    val colorIntensity: Float = DEFAULT_COLOR_INTENSITY,
    val showTextOutline: Boolean = DEFAULT_SHOW_TEXT_OUTLINE,
    val size: PerformanceHudSize = PerformanceHudSize.MEDIUM,
) {
    // persist every field to PrefManager. shared by WebViewScreen + XServerScreen HUD config.
    fun saveToPrefs() {
        PrefManager.performanceHudShowFrameRate = showFrameRate
        PrefManager.performanceHudShowCpuUsage = showCpuUsage
        PrefManager.performanceHudShowGpuUsage = showGpuUsage
        PrefManager.performanceHudShowRamUsage = showRamUsage
        PrefManager.performanceHudShowBatteryLevel = showBatteryLevel
        PrefManager.performanceHudShowPowerDraw = showPowerDraw
        PrefManager.performanceHudShowBatteryRuntime = showBatteryRuntime
        PrefManager.performanceHudShowBatteryTemperature = showBatteryTemperature
        PrefManager.performanceHudShowClockTime = showClockTime
        PrefManager.performanceHudShowCpuTemperature = showCpuTemperature
        PrefManager.performanceHudShowGpuTemperature = showGpuTemperature
        PrefManager.performanceHudShowFrameRateGraph = showFrameRateGraph
        PrefManager.performanceHudShowCpuUsageGraph = showCpuUsageGraph
        PrefManager.performanceHudShowGpuUsageGraph = showGpuUsageGraph
        PrefManager.performanceHudShowFrameTime = showFrameTime
        PrefManager.performanceHudShowLow1Pct = showLow1Pct
        PrefManager.performanceHudShowLow01Pct = showLow01Pct
        PrefManager.performanceHudShowCpuCores = showCpuCores
        PrefManager.performanceHudShowThermalStatus = showThermalStatus
        PrefManager.performanceHudShowGpuMemory = showGpuMemory
        PrefManager.performanceHudShowEnergySession = showEnergySession
        PrefManager.performanceHudShowMahUsed = showMahUsed
        PrefManager.performanceHudShowAvgPower = showAvgPower
        PrefManager.performanceHudBackgroundOpacity = backgroundOpacity
        PrefManager.performanceHudColorIntensity = colorIntensity
        PrefManager.performanceHudShowTextOutline = showTextOutline
        PrefManager.performanceHudSize = size.prefValue
    }

    companion object {
        const val DEFAULT_BACKGROUND_OPACITY = 0.72f
        const val DEFAULT_COLOR_INTENSITY = 1f
        const val DEFAULT_SHOW_TEXT_OUTLINE = true

        // hydrate from PrefManager. inverse of saveToPrefs.
        fun fromPrefs(): PerformanceHudConfig = PerformanceHudConfig(
            showFrameRate = PrefManager.performanceHudShowFrameRate,
            showCpuUsage = PrefManager.performanceHudShowCpuUsage,
            showGpuUsage = PrefManager.performanceHudShowGpuUsage,
            showRamUsage = PrefManager.performanceHudShowRamUsage,
            showBatteryLevel = PrefManager.performanceHudShowBatteryLevel,
            showPowerDraw = PrefManager.performanceHudShowPowerDraw,
            showBatteryRuntime = PrefManager.performanceHudShowBatteryRuntime,
            showBatteryTemperature = PrefManager.performanceHudShowBatteryTemperature,
            showClockTime = PrefManager.performanceHudShowClockTime,
            showCpuTemperature = PrefManager.performanceHudShowCpuTemperature,
            showGpuTemperature = PrefManager.performanceHudShowGpuTemperature,
            showFrameRateGraph = PrefManager.performanceHudShowFrameRateGraph,
            showCpuUsageGraph = PrefManager.performanceHudShowCpuUsageGraph,
            showGpuUsageGraph = PrefManager.performanceHudShowGpuUsageGraph,
            showFrameTime = PrefManager.performanceHudShowFrameTime,
            showLow1Pct = PrefManager.performanceHudShowLow1Pct,
            showLow01Pct = PrefManager.performanceHudShowLow01Pct,
            showCpuCores = PrefManager.performanceHudShowCpuCores,
            showThermalStatus = PrefManager.performanceHudShowThermalStatus,
            showGpuMemory = PrefManager.performanceHudShowGpuMemory,
            showEnergySession = PrefManager.performanceHudShowEnergySession,
            showMahUsed = PrefManager.performanceHudShowMahUsed,
            showAvgPower = PrefManager.performanceHudShowAvgPower,
            backgroundOpacity = PrefManager.performanceHudBackgroundOpacity,
            colorIntensity = PrefManager.performanceHudColorIntensity,
            showTextOutline = PrefManager.performanceHudShowTextOutline,
            size = PerformanceHudSize.fromPrefValue(PrefManager.performanceHudSize),
        )
    }
}
