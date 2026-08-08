package app.gamenative.ui.widget

internal enum class MetricId {
    FPS,
    CPU,
    GPU,
    RAM,
    BATTERY,
    POWER,
    RUNTIME,
    BATTERY_TEMP,
    CLOCK,
    CPU_TEMP,
    GPU_TEMP,

    // power-user
    FRAMETIME,
    LOW_1PCT,
    LOW_01PCT,
    CPU_CORES,
    THERMAL,
    GPU_MEM,

    // energy
    ENERGY_SESSION,
    MAH_USED,
    AVG_POWER,
}

internal enum class GraphScaleMode {
    FPS_DYNAMIC,
    PERCENT_100,
}

internal data class BatterySnapshot(
    val percent: Int? = null,
    val powerWatts: Double? = null,
    val currentMilliAmps: Double? = null,
    val runtimeText: String? = null,
    val temperatureC: Int? = null,
)

internal data class HudSnapshot(
    val fpsValue: Float,
    val cpuValue: Float?,
    val gpuValue: Float?,
    val fps: String,
    val cpu: String?,
    val gpu: String?,
    val ram: String,
    val battery: String?,
    val power: String?,
    val runtime: String?,
    val batteryTemp: String?,
    val clock: String,
    val cpuTemp: String?,
    val gpuTemp: String?,
    val frameTime: String? = null,
    val low1Pct: String? = null,
    val low01Pct: String? = null,
    val cpuCores: String? = null,
    val thermal: String? = null,
    val gpuMem: String? = null,
    val energySession: String? = null,
    val mahUsed: String? = null,
    val avgPower: String? = null,
)

internal data class HudAppearance(
    val textSizeSp: Float,
    val containerHorizontalPaddingDp: Int,
    val containerVerticalPaddingDp: Int,
    val rowVerticalPaddingDp: Int,
    val rowSpacingDp: Int,
    val columnSpacingDp: Int,
    val cornerRadiusDp: Int,
    val strokeWidthDp: Int,
    val stackedGraphWidthDp: Int,
    val stackedGraphHeightDp: Int,
    val compactGraphWidthDp: Int,
    val compactGraphHeightDp: Int,
    val stackedGraphTopMarginDp: Int,
    val stackedGraphBottomMarginDp: Int,
    val compactGraphStartMarginDp: Int,
)

internal data class MetricSignature(
    val id: MetricId,
    val showText: Boolean,
    val showGraph: Boolean,
)
