package app.gamenative.powercontrol.loop

data class Snapshot(
    val gpuBusyPercent: Int? = null,
    val gpuClkHz: Long? = null,
    val frameStats: FrameStats? = null,
    val frameStatsAgeMs: Long? = null,
    val battTempC: Double? = null,
    val boardTempC: Double? = null,
    val gpuNumLevels: Int = 0,
)

data class Decision(
    val gpuMinLevel: Int? = null,
    val gpuMaxLevel: Int? = null,
    val cpuMinKhz: Long? = null,
    val cpuMaxKhz: Long? = null,
    val clusterMax: Map<Int, Long>? = null,
    val clusterMin: Map<Int, Long>? = null,
    val sysfs: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = gpuMinLevel == null && gpuMaxLevel == null &&
            cpuMinKhz == null && cpuMaxKhz == null &&
            clusterMax.isNullOrEmpty() && clusterMin.isNullOrEmpty() && sysfs.isEmpty()
}

enum class PerfPreset {
    BATTERY,
    BALANCED,
    PERFORMANCE;

    fun config(params: DeviceParams?): Preset? {
        val presets = params?.presets ?: return null
        return when (this) {
            BATTERY -> presets.battery
            BALANCED -> presets.balanced
            PERFORMANCE -> presets.performance
        }
    }

    companion object {
        fun fromString(name: String?): PerfPreset =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: BALANCED
    }
}

interface Policy {
    fun decide(snapshot: Snapshot, params: DeviceParams?): Decision
}

class HoldTopPolicy : Policy {
    override fun decide(snapshot: Snapshot, params: DeviceParams?): Decision {
        val top = when {
            snapshot.gpuNumLevels > 0 -> snapshot.gpuNumLevels - 1
            else -> return Decision()
        }
        return Decision(gpuMinLevel = top, gpuMaxLevel = top)
    }
}
