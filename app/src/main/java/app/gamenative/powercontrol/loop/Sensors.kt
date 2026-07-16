package app.gamenative.powercontrol.loop

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class FrameStats(
    val fps: Double? = null,
    @SerialName("avg_frametime_ms") val avgFrametimeMs: Double? = null,
    @SerialName("p99_frametime_ms") val p99FrametimeMs: Double? = null,
    @SerialName("pacer_margin_ms") val pacerMarginMs: Double? = null,
    @SerialName("render_tid") val renderTid: Int? = null,
)

object Sensors {

    private const val GPU_BASE = "/sys/class/kgsl/kgsl-3d0"
    private const val GPU_BUSY_PATH = "$GPU_BASE/gpu_busy_percentage"
    private const val GPU_CLK_PATH = "$GPU_BASE/gpuclk"
    private const val PERF_STATS_FILE = "gn_perf_stats.json"

    private const val DEFAULT_BATT_ZONE = 94
    private const val DEFAULT_BOARD_ZONE = 90

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun readGpuBusyPercent(): Int? {
        val raw = readFile(GPU_BUSY_PATH) ?: return null
        // Adreno emits e.g. "72 %busy"; take the leading integer.
        val token = raw.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        return token.toIntOrNull()
    }

    fun readGpuClkHz(): Long? {
        val raw = readFile(GPU_CLK_PATH) ?: return null
        return raw.trim().toLongOrNull()
    }

    fun readFrameStats(context: Context): FrameStats? {
        val file = File(context.filesDir, PERF_STATS_FILE)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<FrameStats>(file.readText())
        } catch (e: Exception) {
            Timber.tag("Sensors").w(e, "Failed to parse $PERF_STATS_FILE")
            null
        }
    }

    fun readBatteryTempC(params: DeviceParams? = null): Double? {
        val zone = params?.thermal?.battZone ?: DEFAULT_BATT_ZONE
        return readThermalZoneC(zone)
    }

    fun readBoardTempC(params: DeviceParams? = null): Double? {
        val zone = params?.thermal?.boardZone ?: DEFAULT_BOARD_ZONE
        return readThermalZoneC(zone)
    }

    private fun readThermalZoneC(zone: Int): Double? {
        val raw = readFile("/sys/class/thermal/thermal_zone$zone/temp") ?: return null
        val milli = raw.trim().toLongOrNull() ?: return null
        return milli / 1000.0
    }

    private fun readFile(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists() && file.canRead()) file.readText() else null
        } catch (e: Exception) {
            Timber.tag("Sensors").w(e, "Failed to read $path")
            null
        }
    }
}
