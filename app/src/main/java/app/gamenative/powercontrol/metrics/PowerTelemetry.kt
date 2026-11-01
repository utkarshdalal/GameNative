package app.gamenative.powercontrol.metrics

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
            val ticks = buckets.sumOf { it.n }
            val gpuTicks = buckets.sumOf { it.gpuN }
            put("gpu_usage_source", SystemMetricsSources.lastGpuUsageSource?.takeIf { gpuTicks > 0 } ?: "none")
            if (ticks > 0) put("gpu_usage_valid_share", (gpuTicks * 100.0 / ticks).roundToInt() / 100.0)
            put(
                "perf_by_5min",
                buckets.map { b ->
                    buildMap<String, Any> {
                        put("samples", b.n)
                        putAll(bucketExtras(b.skinTempMax))
                        if (b.cpuN > 0) {
                            put("cpu_avg", (b.cpuSum / b.cpuN).toInt())
                            put("cpu_p95", percentile(b.cpuHist, b.cpuN))
                        }
                        if (b.gpuN > 0) {
                            put("gpu_avg", (b.gpuSum / b.gpuN).toInt())
                            put("gpu_p95", percentile(b.gpuHist, b.gpuN))
                        }
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
        synchronized(lock) {
            if (startMs == 0L) return
            val index = ((SystemClock.elapsedRealtime() - startMs) / BUCKET_MS).toInt()
            if (index < 0 || index >= MAX_BUCKETS) return
            while (buckets.size <= index) buckets.add(Bucket(clusters.size))
            val b = buckets[index]
            b.n++
            skinC?.let { if (it > b.skinTempMax) b.skinTempMax = it }
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

    private const val SKIN_EVERY_N_TICKS = 10
    private var skinTick = 0

    private fun readSkinTempC(): Int? =
        SystemMetricsSources.skinTempPaths().takeIf { it.isNotEmpty() }?.let { SystemMetricsSources.readTemperatureC(it) }

    /** Per-bucket keys that are left out when the device has no reading. */
    internal fun bucketExtras(skinTempMax: Int): Map<String, Any> = buildMap {
        if (skinTempMax > 0) put("skin_temp_max", skinTempMax)
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
