package app.gamenative.powercontrol.loop

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class DeviceParams(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val device: DeviceInfo? = null,
    val topology: Topology? = null,
    val gpu: GpuParams? = null,
    val cpu: CpuParams? = null,
    val ddr: DdrParams? = null,
    val thermal: ThermalParams? = null,
    val reaction: ReactionParams? = null,
    val presets: Presets? = null,
)

@Serializable
data class DeviceInfo(
    val model: String? = null,
    val soc: String? = null,
    @SerialName("fingerprint_keys") val fingerprintKeys: List<String> = emptyList(),
)

@Serializable
data class Topology(
    val clusters: List<Cluster> = emptyList(),
    val placement: Placement? = null,
)

@Serializable
data class Cluster(
    val name: String,
    val policy: Int? = null,
    val cpus: List<Int> = emptyList(),
)

@Serializable
data class Placement(
    val render: String? = null,
    val jit: String? = null,
    val misc: String? = null,
)

@Serializable
data class GpuParams(
    @SerialName("sysfs_reversed") val sysfsReversed: Boolean = true,
    val levels: List<GpuLevel> = emptyList(),
)

@Serializable
data class GpuLevel(
    val level: Int,
    val mhz: Int? = null,
    @SerialName("fps_scale") val fpsScale: Double? = null,
    val watts: Double? = null,
)

@Serializable
data class CpuParams(
    @SerialName("freq_steps_khz") val freqStepsKhz: List<Long> = emptyList(),
    @SerialName("floor_watts") val floorWatts: Map<String, Double> = emptyMap(),
)

@Serializable
data class DdrParams(
    @SerialName("steps_khz") val stepsKhz: List<Long> = emptyList(),
    @SerialName("default_pin") val defaultPin: Long? = null,
    @SerialName("coast_floor") val coastFloor: Long? = null,
)

@Serializable
data class ThermalParams(
    @SerialName("budget_watts") val budgetWatts: Double? = null,
    @SerialName("throttle_start_c") val throttleStartC: Double? = null,
    @SerialName("batt_zone") val battZone: Int? = null,
    @SerialName("board_zone") val boardZone: Int? = null,
)

@Serializable
data class ReactionParams(
    @SerialName("settle_ms") val settleMs: Int? = null,
    @SerialName("loop_interval_ms") val loopIntervalMs: Int? = null,
    @SerialName("step_hold_intervals") val stepHoldIntervals: Int? = null,
)

@Serializable
data class Presets(
    val battery: Preset? = null,
    val balanced: Preset? = null,
    val performance: Preset? = null,
)

@Serializable
data class Preset(
    @SerialName("fps_cap") val fpsCap: Int? = null,
    @SerialName("margin_target_ms") val marginTargetMs: Double? = null,
    val target: String? = null,
    @SerialName("watt_budget") val wattBudget: Double? = null,
)

object DeviceParamsLoader {

    const val FILE_NAME = "device_params.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun loadFromFile(file: File): DeviceParams? {
        if (!file.exists()) {
            Timber.tag("DeviceParams").d("No device params at ${file.absolutePath}")
            return null
        }
        return try {
            json.decodeFromString<DeviceParams>(file.readText())
        } catch (e: Exception) {
            Timber.tag("DeviceParams").e(e, "Failed to parse device params at ${file.absolutePath}")
            null
        }
    }

    fun load(context: Context): DeviceParams? {
        return loadFromFile(File(context.filesDir, FILE_NAME))
    }
}
