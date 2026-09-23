package app.gamenative.powercontrol.metrics

/**
 * Immutable view of one sampling cycle. Published by [PerformanceMetricsCollector]
 * and safe to read from any thread.
 */
data class MetricsSnapshot(
    val timestampMs: Long,
    val fps: Float,
    val frameTimeP50Ms: Float,
    val frameTimeP95Ms: Float,
    val frameTimeMaxMs: Float,
    val slowFrameCount: Int,
    val totalFrameCount: Int,
    val cpuUsagePercent: Float?,
    val cpuUsageSource: CpuUsageSource,
    val gpuUsagePercent: Float?,
    val cpuTempC: Int?,
    val gpuTempC: Int?,
)
