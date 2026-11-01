package app.gamenative.powercontrol.metrics

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import app.gamenative.powercontrol.PowerManager
import java.io.File
import java.util.TreeMap
import kotlin.math.roundToInt

object PowerTelemetry {
    private const val BUCKET_MS = 5 * 60 * 1000L
    private const val MAX_BUCKETS = 48

    private class Cluster(val cores: List<Int>, val maxKhz: Long) {
        val dir = "/sys/devices/system/cpu/cpu${cores.first()}/cpufreq"
    }

    private class ClusterBucket {
        var n = 0
        var curSum = 0L
        var capSum = 0L
        var atCap = 0
    }

    private class Bucket(clusterCount: Int) {
        var n = 0
        var skinTempMax = 0
        var battUah: Long? = null
        var headroomMin: Float? = null
        var thermalStatusMax: Int? = null
        var gpuClkN = 0
        var gpuClkSum = 0L
        var cpuN = 0
        var cpuSum = 0L
        val cpuHist = IntArray(101)
        var gpuN = 0
        var gpuSum = 0L
        val gpuHist = IntArray(101)
        var cpuTempMax = 0
        var gpuTempMax = 0
        var p95N = 0
        var p95Sum = 0.0
        var gpuCap: Int? = null
        val clusters = Array(clusterCount) { ClusterBucket() }
    }

    private val lock = Any()
    private val clusters: List<Cluster> = runCatching { discoverClusters() }.getOrDefault(emptyList())
    private var startMs = 0L
    private val buckets = ArrayList<Bucket>()

    fun capabilityProperties(): Map<String, Any> = buildMap {
        put("power_driver", PowerManager.driverName())
        put("power_driver_supported", PowerManager.isDriverSupported())
        put("power_governor_supported", PowerManager.isGovernorSupported())
        put("power_gpu_supported", PowerManager.isGpuSupported())
        put("power_bus_supported", PowerManager.isBusSupported())
        put("power_fan_supported", PowerManager.isFanControlAvailable())
        put("power_cluster_tuning_supported", PowerManager.isClusterTuningAvailable())
        put("power_pinning_supported", PowerManager.isGamePinningAvailable())
        PowerManager.getAvailableGovernors().takeIf { it.isNotEmpty() }?.let { put("cpu_governors", it) }
        PowerManager.getAvailableCpuFrequencies().takeIf { it.isNotEmpty() }?.let { put("cpu_freqs_khz", it) }
        PowerManager.getGpuInfo()?.numGpuPowerLevels?.takeIf { it > 0 }?.let { put("gpu_power_levels", it) }
        PowerManager.getAvailableGpuFrequencies().takeIf { it.isNotEmpty() }?.let { put("gpu_freqs_khz", it) }
        PowerManager.getBusInfo()?.numBusLevels?.takeIf { it > 0 }?.let { put("bus_levels", it) }
        clusters.takeIf { it.isNotEmpty() }?.let { list ->
            put(
                "cpu_clusters",
                list.map { c ->
                    buildMap<String, Any> {
                        put("cores", c.cores)
                        put("max_khz", c.maxKhz)
                        SystemMetricsSources.readFirstLine("${c.dir}/scaling_available_frequencies")
                            ?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }
                            ?.takeIf { it.isNotEmpty() }?.let { put("freqs_khz", it) }
                    }
                },
            )
        }
    }

    fun sessionProperties(): Map<String, Any> = buildMap {
        val active = PowerManager.isGameStarted && PowerManager.isDriverSupported() && PowerManager.isProfilePowerControlEnabled()
        put("power_enabled", active)
        put("target_fps", PowerManager.targetFps)
        val p = PowerManager.currentProfile
        put(
            "power_profile",
            mapOf(
                "name" to p.name,
                "governor" to p.governor.governorName,
                "min_cpu_khz" to p.minCpuFreq,
                "max_cpu_khz" to p.maxCpuFreq,
                "min_gpu_level" to p.minGpuPowerLevel,
                "max_gpu_level" to p.maxGpuPowerLevel,
                "min_bus_level" to p.minBusLevel,
                "max_bus_level" to p.maxBusLevel,
                "auto_tuning" to p.enableAutoTuning,
                "per_cluster_tuning" to p.enablePerClusterTuning,
                "strategy" to p.tuningStrategy.name,
                "fan_control" to p.enableFanControl,
                "game_pinning" to p.enableGamePinning,
                "adaptive_fps_cap" to p.adaptiveFpsCapEnabled,
            ) + pinningOutcome(),
        )
        synchronized(lock) {
            if (buckets.isEmpty()) return@buildMap
            buckets.last().let { if (it.battUah == null) it.battUah = readChargeCounter() }
            val ticks = buckets.sumOf { it.n }
            val gpuTicks = buckets.sumOf { it.gpuN }
            put("gpu_usage_source", SystemMetricsSources.lastGpuUsageSource?.takeIf { gpuTicks > 0 } ?: "none")
            counterBackend(SystemMetricsSources.lastGpuUsageSource, MaliGpuCounters.backend)?.let { put("gpu_counter_backend", it) }
            put("cpu_temp_source", cpuTempSource ?: "none")
            thermalZonesReadable?.let { put("thermal_zones_readable", it) }
            put("gpu_temp_source", gpuTempSource ?: "none")
            if (ticks > 0) put("gpu_usage_valid_share", (gpuTicks * 100.0 / ticks).roundToInt() / 100.0)
            put(
                "perf_by_5min",
                buckets.map { b ->
                    buildMap<String, Any> {
                        put("samples", b.n)
                        putAll(bucketExtras(b.skinTempMax, b.battUah))
                        putAll(thermalExtras(b.headroomMin, b.thermalStatusMax))
                        if (b.cpuN > 0) {
                            put("cpu_avg", (b.cpuSum / b.cpuN).toInt())
                            put("cpu_p95", percentile(b.cpuHist, b.cpuN))
                        }
                        if (b.gpuN > 0) {
                            put("gpu_avg", (b.gpuSum / b.gpuN).toInt())
                            put("gpu_p95", percentile(b.gpuHist, b.gpuN))
                        }
                        if (b.gpuClkN > 0) put("gpu_clk_mhz_avg", (b.gpuClkSum / b.gpuClkN).toInt())
                        if (b.cpuTempMax > 0) put("cpu_temp_max", b.cpuTempMax)
                        if (b.gpuTempMax > 0) put("gpu_temp_max", b.gpuTempMax)
                        if (b.p95N > 0) put("frame_p95_ms", (b.p95Sum / b.p95N * 10).roundToInt() / 10.0)
                        b.gpuCap?.let { put("gpu_cap", it) }
                        b.clusters.takeIf { c -> c.any { it.n > 0 } }?.let { c ->
                            put(
                                "clusters",
                                c.map {
                                    if (it.n == 0) {
                                        emptyMap<String, Any>()
                                    } else {
                                        mapOf(
                                            "mhz_avg" to (it.curSum / it.n / 1000).toInt(),
                                            "cap_mhz" to (it.capSum / it.n / 1000).toInt(),
                                            "at_cap_pct" to it.atCap * 100 / it.n,
                                        )
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    fun start() {
        synchronized(lock) {
            startMs = SystemClock.elapsedRealtime()
            buckets.clear()
            SystemMetricsSources.lastGpuUsageSource = null
            cpuTempSource = null
            gpuTempSource = null
            thermalZonesReadable = runCatching { File("/sys/class/thermal").list() != null }.getOrNull()
        }
    }

    fun record(snapshot: MetricsSnapshot) {
        val active = PowerManager.isDriverSupported() && PowerManager.isProfilePowerControlEnabled()
        val gpuCap = if (active) PowerManager.latestTunerCaps()?.gpuLevel ?: PowerManager.currentProfile.maxGpuPowerLevel else null
        val freqs = clusters.map { c ->
            SystemMetricsSources.readLongFromLine("${c.dir}/scaling_cur_freq") to
                SystemMetricsSources.readLongFromLine("${c.dir}/scaling_max_freq")
        }
        val skinC = if (skinTick++ % SKIN_EVERY_N_TICKS == 0) readSkinTempC() else null
        val gpuClkMhz = runCatching { GpuClock.currentMhz() }.getOrNull()
        synchronized(lock) {
            if (startMs == 0L) return
            val index = ((SystemClock.elapsedRealtime() - startMs) / BUCKET_MS).toInt()
            if (index < 0 || index >= MAX_BUCKETS) return
            if (index >= buckets.size && buckets.isNotEmpty()) buckets.last().battUah = readChargeCounter()
            while (buckets.size <= index) buckets.add(Bucket(clusters.size))
            val b = buckets[index]
            b.n++
            skinC?.let { if (it > b.skinTempMax) b.skinTempMax = it }
            gpuClkMhz?.let {
                b.gpuClkN++
                b.gpuClkSum += it
            }
            snapshot.cpuUsagePercent?.let { v ->
                val pct = v.roundToInt().coerceIn(0, 100)
                b.cpuN++
                b.cpuSum += pct
                b.cpuHist[pct]++
            }
            snapshot.gpuUsagePercent?.let { v ->
                val pct = v.roundToInt().coerceIn(0, 100)
                b.gpuN++
                b.gpuSum += pct
                b.gpuHist[pct]++
            }
            snapshot.cpuTempC?.let { if (it > b.cpuTempMax) b.cpuTempMax = it }
            snapshot.gpuTempC?.let { if (it > b.gpuTempMax) b.gpuTempMax = it }
            if (snapshot.totalFrameCount > 0) {
                b.p95N++
                b.p95Sum += snapshot.frameTimeP95Ms
            }
            gpuCap?.let { b.gpuCap = it }
            pendingHeadroom?.let { h -> if (b.headroomMin?.let { h < it } != false) b.headroomMin = h }
            pendingThermalStatus?.let { s -> if (b.thermalStatusMax?.let { s > it } != false) b.thermalStatusMax = s }
            pendingHeadroom = null
            pendingThermalStatus = null
            freqs.forEachIndexed { i, (cur, cap) ->
                if (cur == null || cap == null) return@forEachIndexed
                val cb = b.clusters[i]
                cb.n++
                cb.curSum += cur
                cb.capSum += cap
                if (cur >= cap) cb.atCap++
            }
        }
    }

    private var pendingHeadroom: Float? = null
    private var pendingThermalStatus: Int? = null

    /** Fresh thermal reads of this tick; folded into the current bucket by the next [record]. */
    fun recordThermal(headroom: Float?, thermalStatus: Int?) {
        synchronized(lock) {
            pendingHeadroom = headroom?.takeIf { it.isFinite() }
            pendingThermalStatus = thermalStatus
        }
    }

    private const val SKIN_EVERY_N_TICKS = 10
    private var skinTick = 0

    private fun readSkinTempC(): Int? =
        SystemMetricsSources.skinTempPaths().takeIf { it.isNotEmpty() }?.let { SystemMetricsSources.readTemperatureC(it) }

    @Volatile
    private var cpuTempSource: String? = null

    /** Whether the app may list /sys/class/thermal (SELinux denies it on many devices); read once per session. */
    @Volatile
    private var thermalZonesReadable: Boolean? = null

    @Volatile
    private var gpuTempSource: String? = null

    /** Records the sensor behind the first valid CPU and GPU temperature of the session. */
    fun noteTemperatureSources(cpuPath: String?, gpuPath: String?) {
        if (cpuTempSource == null && cpuPath != null) cpuTempSource = SystemMetricsSources.temperatureSourceTag(cpuPath)
        if (gpuTempSource == null && gpuPath != null) gpuTempSource = SystemMetricsSources.temperatureSourceTag(gpuPath)
    }

    @Volatile
    private var batteryManager: BatteryManager? = null

    fun attachBattery(context: Context) {
        batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    }

    /** Battery charge counter in uAh, read at the end of each bucket; null where unsupported. */
    private fun readChargeCounter(): Long? =
        runCatching { batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }
            .getOrNull()?.let(::validChargeCounter)

    internal fun validChargeCounter(raw: Long): Long? = raw.takeIf { it != Long.MIN_VALUE && it > 0L }

    /** Per-bucket keys that are left out when the device has no reading. */
    internal fun bucketExtras(skinTempMax: Int, battUah: Long?): Map<String, Any> = buildMap {
        if (skinTempMax > 0) put("skin_temp_max", skinTempMax)
        battUah?.let { put("batt_uah", it) }
    }

    /** `gpu_counter_backend` is reported only when the session's GPU usage came from the Mali counters. */
    internal fun counterBackend(gpuUsageSource: String?, backend: String?): String? =
        backend?.takeIf { gpuUsageSource == "mali/counters" && it.isNotEmpty() }

    /** Per-bucket thermal keys, left out when the bucket took no reading. */
    internal fun thermalExtras(headroomMin: Float?, thermalStatusMax: Int?): Map<String, Any> = buildMap {
        headroomMin?.let { put("headroom_min", (it * 100).roundToInt() / 100.0) }
        thermalStatusMax?.let { put("thermal_status_max", it) }
    }

    private fun pinningOutcome(): Map<String, Any> {
        val cores = PowerManager.pinnedGameCores
        return buildMap {
            put("pinning_applied", PowerManager.gamePinVerified == true && cores.isNotEmpty())
            if (cores.isNotEmpty()) put("pinned_cpus", cpuRanges(cores))
        }
    }

    /** Compact core list, e.g. [4, 5, 6, 7] -> "4-7", [0, 2, 3, 4] -> "0,2-4". */
    internal fun cpuRanges(cores: Collection<Int>): String {
        val sorted = cores.toSortedSet().toList()
        val parts = ArrayList<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            parts += if (j > i) "${sorted[i]}-${sorted[j]}" else "${sorted[i]}"
            i = j + 1
        }
        return parts.joinToString(",")
    }

    private fun percentile(hist: IntArray, n: Int): Int {
        val target = (n * 0.95).toInt().coerceAtLeast(1)
        var seen = 0
        for (i in hist.indices) {
            seen += hist[i]
            if (seen >= target) return i
        }
        return 100
    }

    private fun discoverClusters(): List<Cluster> {
        val dirs = File("/sys/devices/system/cpu").listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
            ?: return emptyList()
        val byMax = TreeMap<Long, MutableList<Int>>()
        for (dir in dirs) {
            val index = dir.name.removePrefix("cpu").toIntOrNull() ?: continue
            val max = SystemMetricsSources.readLongFromLine("${dir.path}/cpufreq/cpuinfo_max_freq") ?: continue
            byMax.getOrPut(max) { mutableListOf() } += index
        }
        return byMax.map { (max, cores) -> Cluster(cores.sorted(), max) }
    }
}
