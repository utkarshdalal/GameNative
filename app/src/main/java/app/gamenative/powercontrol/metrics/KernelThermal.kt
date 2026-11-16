package app.gamenative.powercontrol.metrics

import java.io.File

/**
 * Thermal throttling as the kernel applies it, read from `/sys/class/thermal/cooling_device*`.
 *
 * Some handhelds (Retroid Pocket 6, AYN Thor) ship without a working Android thermal HAL:
 * `dumpsys thermalservice` reports `HAL Ready: false`, so [android.os.PowerManager] always says
 * THERMAL_STATUS_NONE and the headroom API returns NaN, while the kernel still throttles the CPU
 * and GPU through its cooling devices. Those devices are world-readable, and their state is the
 * governor's own decision, so they stand in for the platform status there.
 *
 * Only CPU and GPU frequency limits and thermal core offlining count. Backlight, battery, display
 * refresh, storage and memory-bus cooling devices are left out: the backlight one sits at its
 * maximum whenever the screen is at full brightness, and none of them limit the game's clocks.
 */
object KernelThermal {
    /** Mirrors android.os.PowerManager.THERMAL_STATUS_* so callers can mix both sources. */
    const val STATUS_NONE = 0
    const val STATUS_LIGHT = 1
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3

    /** Share of a frequency cooling device's range at and above which throttling counts as moderate. */
    const val MODERATE_FRACTION = 0.2f

    /** Share at and above which it counts as severe. */
    const val SEVERE_FRACTION = 0.5f

    enum class Kind {
        /** Caps a CPU cluster's or the GPU's clock; the state is a step down the frequency table. */
        FREQUENCY,

        /** Takes CPU cores away (pause, hotplug, isolate); any active state is severe throttling. */
        CORE_OFFLINE,
    }

    data class CoolingDevice(val type: String, val curState: Int, val maxState: Int)

    /** Which kind of throttling a cooling device of [type] applies; null when it does not limit the game's clocks. */
    fun kindOf(type: String): Kind? {
        val t = type.trim().lowercase()
        return when {
            t.startsWith("thermal-pause") || t.startsWith("pause-cpu") ||
                t.startsWith("cpu-hotplug") || t.startsWith("cpu-isolate") -> Kind.CORE_OFFLINE
            t.startsWith("cpufreq") || t.startsWith("cpu-cluster") || t.startsWith("thermal-cluster") ||
                t == "gpu" || t.contains("kgsl") || (t.startsWith("devfreq") && t.contains("gpu")) -> Kind.FREQUENCY
            else -> null
        }
    }

    /**
     * Android-style thermal status from cooling device states: severe while a core is taken offline
     * or any clock is held at least [SEVERE_FRACTION] down its range, moderate from [MODERATE_FRACTION],
     * light for any smaller cap, none when nothing is throttled. Null when no CPU or GPU cooling device
     * was readable, so a device without them is not reported as cool.
     */
    fun statusOf(devices: List<CoolingDevice>): Int? {
        var relevant = false
        var maxFraction = 0f
        for (d in devices) {
            when (kindOf(d.type)) {
                Kind.CORE_OFFLINE -> {
                    relevant = true
                    if (d.curState > 0) return STATUS_SEVERE
                }
                Kind.FREQUENCY -> {
                    if (d.maxState <= 0) continue
                    relevant = true
                    val fraction = d.curState.coerceIn(0, d.maxState).toFloat() / d.maxState
                    if (fraction > maxFraction) maxFraction = fraction
                }
                null -> Unit
            }
        }
        if (!relevant) return null
        return when {
            maxFraction >= SEVERE_FRACTION -> STATUS_SEVERE
            maxFraction >= MODERATE_FRACTION -> STATUS_MODERATE
            maxFraction > 0f -> STATUS_LIGHT
            else -> STATUS_NONE
        }
    }

    private const val DEFAULT_ROOT = "/sys/class/thermal"

    private val lock = Any()
    private var scannedRoot: String? = null

    /** Relevant devices found by the last scan: their type and max state never change while the device is up. */
    private var cached: List<Pair<File, CoolingDevice>> = emptyList()

    /** Reads the current states of the CPU and GPU cooling devices under [root]; empty when none are readable. */
    fun readDevices(root: File = File(DEFAULT_ROOT)): List<CoolingDevice> {
        val devices = synchronized(lock) {
            if (scannedRoot != root.path) {
                cached = scan(root)
                scannedRoot = root.path
            }
            cached
        }
        return devices.mapNotNull { (dir, d) ->
            val cur = readInt(File(dir, "cur_state")) ?: return@mapNotNull null
            d.copy(curState = cur)
        }
    }

    /** Current kernel-side thermal status, or null when the device exposes no CPU or GPU cooling devices. */
    fun read(root: File = File(DEFAULT_ROOT)): Int? = statusOf(readDevices(root))

    /** Forgets the scanned device list, for tests. */
    internal fun resetCache() {
        synchronized(lock) {
            scannedRoot = null
            cached = emptyList()
        }
    }

    private fun scan(root: File): List<Pair<File, CoolingDevice>> {
        val dirs = root.listFiles { f -> f.name.startsWith("cooling_device") } ?: return emptyList()
        return dirs.sortedBy { it.name }.mapNotNull { dir ->
            val type = runCatching { File(dir, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            if (kindOf(type) == null) return@mapNotNull null
            val max = readInt(File(dir, "max_state")) ?: return@mapNotNull null
            dir to CoolingDevice(type, curState = 0, maxState = max)
        }
    }

    private fun readInt(file: File): Int? = runCatching { file.readText().trim().toInt() }.getOrNull()
}
