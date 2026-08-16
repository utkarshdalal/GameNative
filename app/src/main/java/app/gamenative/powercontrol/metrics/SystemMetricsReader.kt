package app.gamenative.powercontrol.metrics

import android.os.SystemClock
import app.gamenative.powercontrol.PowerManager
import java.io.File
import java.util.Locale
import timber.log.Timber

enum class CpuUsageSource {
    PROC_STAT,
    CPU_FREQUENCY,
    UNAVAILABLE,
}

data class CpuUsageReading(val percent: Int, val source: CpuUsageSource)

data class GpuUsageReading(val percent: Int, val source: String)

/**
 * Device sysfs discovery shared by the metrics collector and the on-screen HUD.
 * Discovery results are cached process-wide so both consumers scan the tree once.
 */
object SystemMetricsSources {
    private const val TAG = "PowerMetrics"

    @Volatile
    private var gpuUsagePathsCache: List<String>? = null

    @Volatile
    private var thermalZonesCache: List<Pair<String, String>>? = null

    @Volatile
    private var cpuTempPathsCache: List<String>? = null

    @Volatile
    private var gpuTempPathsCache: List<String>? = null

    private val fixedGpuTempPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/temp",
        "/sys/class/kgsl/kgsl-3d0/devfreq/temp",
        "/sys/class/misc/mali0/device/temp",
        "/sys/kernel/gpu/temp",
    )

    @Synchronized
    fun gpuUsagePaths(): List<String> {
        gpuUsagePathsCache?.let { return it }

        val candidates = linkedSetOf<String>()
        fun add(path: String) {
            if (File(path).exists()) {
                candidates += path
            }
        }

        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/gpuinfo",
            "/sys/devices/platform/mali/utilization",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/pvrsrvkm/device/utilisation",
            "/sys/class/devfreq/gpu/load",
        ).forEach(::add)

        listOf(
            File("/sys/class/devfreq"),
            File("/sys/devices/virtual/devfreq"),
        ).forEach { devfreqRoot ->
            if (!devfreqRoot.isDirectory) return@forEach
            val nodeDirs = devfreqRoot.listFiles { file -> file.isDirectory } ?: emptyArray<File>()
            for (nodeDir in nodeDirs) {
                val nodePath = nodeDir.path.lowercase(Locale.US)
                val looksLikeGpuNode = listOf("gpu", "mali", "g3d", "kgsl").any { token ->
                    nodePath.contains(token)
                }
                val usageFiles = listOf(
                    "gpu_busy_percentage",
                    "gpu_load",
                    "utilisation",
                    "utilization",
                    "load",
                    "gpuinfo",
                )
                for (fileName in usageFiles) {
                    val file = File(nodeDir, fileName)
                    if (!file.exists()) continue
                    if (looksLikeGpuNode || fileName == "gpu_busy_percentage" || fileName == "gpuinfo") {
                        candidates += file.path
                    }
                }
            }
        }

        val paths = candidates.toList()
        gpuUsagePathsCache = paths
        Timber.tag(TAG).v("Discovered GPU usage paths: %s", paths.joinToString())
        return paths
    }

    /**
     * Thermal zones ranked for CPU representativeness (lower rank wins):
     * cpu-silicon, cpu-0, generic cpu, soc, s5p-tmu, cputop, tsens, cluster, big/little.
     */
    @Synchronized
    fun cpuTempPaths(): List<String> {
        cpuTempPathsCache?.let { return it }

        val paths = prioritizePaths(allThermalZones()) { type ->
            when {
                type.contains("cpu-silicon") -> 0
                type.contains("cpu-0") -> 1
                type.contains("cpu") && !type.contains("gpu") -> 2
                type.contains("soc") -> 3
                type.contains("s5p-tmu") -> 4
                type.contains("cputop") -> 5
                type.contains("tsens") -> 6
                type.contains("cluster") -> 7
                type.contains("big") || type.contains("little") -> 8
                else -> null
            }
        }

        cpuTempPathsCache = paths
        return paths
    }

    /**
     * Vendor GPU temperature nodes first, then thermal zones ranked by
     * gpu-silicon, generic gpu, g3d, kgsl, mali.
     */
    @Synchronized
    fun gpuTempPaths(): List<String> {
        gpuTempPathsCache?.let { return it }

        val zonePaths = prioritizePaths(allThermalZones()) { type ->
            when {
                type.contains("gpu-silicon") -> 0
                type.contains("gpu") -> 1
                type.contains("g3d") -> 2
                type.contains("kgsl") -> 3
                type.contains("mali") -> 4
                else -> null
            }
        }

        val paths = (fixedGpuTempPaths + zonePaths).distinct()
        gpuTempPathsCache = paths
        return paths
    }

    @Synchronized
    private fun allThermalZones(): List<Pair<String, String>> {
        thermalZonesCache?.let { return it }

        val zones = listOf(
            File("/sys/class/thermal"),
            File("/sys/devices/virtual/thermal"),
        ).flatMap { thermalDir ->
            val zoneDirs = thermalDir.listFiles { file ->
                file.isDirectory && file.name.startsWith("thermal_zone")
            } ?: return@flatMap emptyList<Pair<String, String>>()

            zoneDirs.mapNotNull { zone ->
                val type = readFirstLine(File(zone, "type").path)
                    ?.trim()
                    ?.lowercase(Locale.US)
                    ?: return@mapNotNull null
                Pair(type, File(zone, "temp").path)
            }
        }.distinctBy { it.second }

        thermalZonesCache = zones
        return zones
    }

    private fun prioritizePaths(
        zones: List<Pair<String, String>>,
        ranker: (String) -> Int?,
    ): List<String> {
        return zones
            .mapNotNull { (type, path) ->
                ranker(type)?.let { rank -> Triple(type, path, rank) }
            }
            .sortedWith(compareBy({ it.third }, { it.second }))
            .map { it.second }
    }

    fun readTemperatureC(paths: List<String>): Int? {
        for (path in paths.distinct()) {
            val raw = readFirstLine(path)?.trim()?.toIntOrNull() ?: continue
            val celsius = if (raw > 1000) (raw + 500) / 1000 else raw
            if (celsius in 1..150) {
                return celsius
            }
        }
        return null
    }

    fun readFirstLine(path: String): String? {
        return try {
            PowerManager.readFile(path)?.lines()?.firstOrNull()
                ?: File(path).bufferedReader().use { it.readLine() }
        } catch (_: Exception) {
            null
        }
    }

    fun readNthLine(path: String, lineIndex: Int): String? {
        return try {
            PowerManager.readFile(path)?.lines()?.drop(lineIndex)?.firstOrNull()
                ?: File(path).bufferedReader().useLines { lines ->
                    lines.drop(lineIndex).firstOrNull()
                }
        } catch (_: Exception) {
            null
        }
    }

    fun readPercentFromLine(path: String): Int? {
        val raw = readFirstLine(path)?.trim() ?: return null
        val token = raw.split(Regex("\\s+"))
            .map { it.replace(Regex("[^0-9]"), "") }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return token.toIntOrNull()?.coerceIn(0, 100)
    }

    fun readLongFromLine(path: String): Long? {
        return readFirstLine(path)?.trim()?.toLongOrNull()
    }
}

/**
 * Delta-based `/proc/stat` reader. Each instance owns its own previous sample,
 * so several consumers can sample at independent cadences.
 */
class CpuUsageSampler {
    private var lastTotal: Long? = null
    private var lastIdle: Long? = null

    fun reset() {
        lastTotal = null
        lastIdle = null
    }

    fun sample(): CpuUsageReading? {
        val parts = SystemMetricsSources.readFirstLine("/proc/stat")
            ?.trim()
            ?.split(Regex("\\s+"))

        if (parts != null && parts.size >= 5 && parts.firstOrNull() == "cpu") {
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size >= 4) {
                val idle = values.getOrElse(3) { 0L }
                val iowait = values.getOrElse(4) { 0L }
                val total = values.sum()
                val idleTotal = idle + iowait

                val previousTotal = lastTotal
                val previousIdle = lastIdle
                lastTotal = total
                lastIdle = idleTotal

                if (previousTotal != null && previousIdle != null) {
                    val totalDiff = total - previousTotal
                    val idleDiff = idleTotal - previousIdle
                    if (totalDiff > 0) {
                        val percent = (((totalDiff - idleDiff).coerceAtLeast(0L)) * 100L / totalDiff)
                            .toInt()
                            .coerceIn(0, 100)
                        return CpuUsageReading(percent, CpuUsageSource.PROC_STAT)
                    }
                }
            }
        }

        val fallback = readFromFrequency() ?: return null
        return CpuUsageReading(fallback, CpuUsageSource.CPU_FREQUENCY)
    }

    /**
     * Biased proxy: reports the aggregate clock ratio, not occupancy. Only used
     * when /proc/stat is unreadable, and always tagged as such on the sample.
     */
    private fun readFromFrequency(): Int? {
        var currentTotal = 0L
        var maxTotal = 0L

        repeat(Runtime.getRuntime().availableProcessors()) { cpuIndex ->
            val current = SystemMetricsSources
                .readLongFromLine("/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/scaling_cur_freq")
            val max = SystemMetricsSources
                .readLongFromLine("/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/cpuinfo_max_freq")
            if (current != null && max != null && max > 0L) {
                currentTotal += current.coerceIn(0L, max)
                maxTotal += max
            }
        }

        if (maxTotal <= 0L) {
            return null
        }

        return ((currentTotal * 100L) / maxTotal).toInt().coerceIn(0, 100)
    }
}

/**
 * Turns the cumulative `busy total` pair of kgsl `gpubusy` into a load percentage
 * for the interval between two reads.
 *
 * Some kernels clear the counters on read instead of accumulating; a repeated
 * decrease switches this sampler to reading the raw pair as an interval value.
 */
class GpuBusyDelta {
    private var lastBusy = -1L
    private var lastTotal = -1L
    private var decreaseStreak = 0

    var perReadCounters: Boolean = false
        private set

    fun reset() {
        lastBusy = -1L
        lastTotal = -1L
        decreaseStreak = 0
    }

    fun update(busy: Long, total: Long): Int? {
        if (busy < 0L || total <= 0L) return null

        val previousBusy = lastBusy
        val previousTotal = lastTotal
        lastBusy = busy
        lastTotal = total

        if (perReadCounters) {
            return percent(busy, total)
        }
        if (previousBusy < 0L || previousTotal < 0L) return null

        val busyDelta = busy - previousBusy
        val totalDelta = total - previousTotal
        if (busyDelta < 0L || totalDelta <= 0L) {
            decreaseStreak++
            if (decreaseStreak >= PER_READ_DETECTION_STREAK) {
                perReadCounters = true
            }
            return null
        }

        decreaseStreak = 0
        return percent(busyDelta, totalDelta)
    }

    private fun percent(busy: Long, total: Long): Int? {
        if (total <= 0L) return null
        return ((busy * 100L) / total).toInt().coerceIn(0, 100)
    }

    private companion object {
        const val PER_READ_DETECTION_STREAK = 2
    }
}

/**
 * Reads the first GPU usage node that yields a value, keeping the per-node delta
 * state needed by counter-style nodes.
 */
class GpuUsageSampler {
    private val busyDelta = GpuBusyDelta()
    private var lastGpuInfoMs: Long? = null
    private var lastGpuInfoWallMs: Long = 0L

    fun reset() {
        busyDelta.reset()
        lastGpuInfoMs = null
        lastGpuInfoWallMs = 0L
    }

    fun sample(): GpuUsageReading? {
        return SystemMetricsSources.gpuUsagePaths()
            .asSequence()
            .mapNotNull { readSample(it) }
            .firstOrNull()
    }

    private fun readSample(path: String): GpuUsageReading? {
        return when (path.substringAfterLast("/")) {
            "gpubusy" -> {
                val raw = SystemMetricsSources.readFirstLine(path)?.trim() ?: return null
                val parts = raw.split(Regex("\\s+"))
                if (parts.size < 2) return null
                val busy = parts[0].toLongOrNull() ?: return null
                val total = parts[1].toLongOrNull() ?: return null
                busyDelta.update(busy, total)?.let { GpuUsageReading(it, path) }
            }
            "gpuinfo" -> {
                val line = SystemMetricsSources.readNthLine(path, 1)?.trim() ?: return null
                val gpuMs = line.split(Regex("\\s+")).lastOrNull()?.toLongOrNull() ?: return null
                val now = SystemClock.elapsedRealtime()
                val previousMs = lastGpuInfoMs
                val previousWall = lastGpuInfoWallMs
                lastGpuInfoMs = gpuMs
                lastGpuInfoWallMs = now
                if (previousMs == null || previousWall <= 0L) return null
                val wallDelta = now - previousWall
                if (wallDelta <= 0L) return null
                val gpuDelta = (gpuMs - previousMs).coerceAtLeast(0L)
                GpuUsageReading(((gpuDelta * 100L) / wallDelta).toInt().coerceIn(0, 100), path)
            }
            else -> SystemMetricsSources.readPercentFromLine(path)?.let { percent ->
                GpuUsageReading(percent, path)
            }
        }
    }
}
