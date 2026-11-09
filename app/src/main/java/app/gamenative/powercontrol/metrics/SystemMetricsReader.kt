package app.gamenative.powercontrol.metrics

import android.os.SystemClock
import java.io.File
import java.util.Locale
import timber.log.Timber

enum class CpuUsageSource {
    PROC_STAT,
    CPU_FREQUENCY,
    UNAVAILABLE,
}

class CpuUsageReading(
    val percent: Int,
    val source: CpuUsageSource,
    val coreMaxPercent: Int? = null,
    val coreBusyTicks: LongArray? = null,
)

data class GpuUsageReading(val percent: Int, val source: String)

data class TemperatureReading(val celsius: Int, val path: String)

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

    @Volatile
    private var skinTempPathsCache: List<String>? = null

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
            if (File(path).canRead()) {
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
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/module/ged/parameters/gpu_loading",
            "/sys/class/misc/pvrsrvkm/device/utilisation",
            "/sys/class/devfreq/gpu/load",
        ).forEach(::add)

        // Mali platform devices (Tensor 1c500000.mali, MediaTek 13000000.mali, ...).
        File("/sys/devices/platform").listFiles { file ->
            file.isDirectory && file.name.lowercase(Locale.US).contains("mali")
        }?.forEach { dir ->
            add(File(dir, "utilization").path)
            add(File(dir, "utilisation").path)
        }

        listOf(
            File("/sys/class/devfreq"),
            File("/sys/devices/virtual/devfreq"),
        ).forEach { devfreqRoot ->
            if (!devfreqRoot.isDirectory) return@forEach
            val nodeDirs = devfreqRoot.listFiles { file -> file.isDirectory } ?: emptyArray<File>()
            for (nodeDir in nodeDirs) {
                if (isBusDevfreqNode(nodeDir.name)) continue
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
                    if (!file.canRead()) continue
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

    private val busDevfreqTokens = listOf("gpubw", "busmon", "bwmon", "llcc", "ddr", "bus")

    /**
     * Devfreq nodes that scale memory or interconnect bandwidth rather than the GPU,
     * e.g. Adreno's `soc:qcom,gpubw`, whose `load` is bus traffic, not GPU busy time.
     */
    internal fun isBusDevfreqNode(dirName: String): Boolean {
        val name = dirName.lowercase(Locale.US)
        return busDevfreqTokens.any { name.contains(it) }
    }

    /** Thermal zones ranked for CPU representativeness (lower rank wins), see [cpuTempRank]. */
    @Synchronized
    fun cpuTempPaths(): List<String> {
        cpuTempPathsCache?.let { return it }

        val paths = prioritizePaths(allThermalZones(), ::cpuTempRank)

        cpuTempPathsCache = paths
        return paths
    }

    /** Vendor GPU temperature nodes first, then thermal zones ranked by [gpuTempRank]. */
    @Synchronized
    fun gpuTempPaths(): List<String> {
        gpuTempPathsCache?.let { return it }

        val zonePaths = prioritizePaths(allThermalZones(), ::gpuTempRank)

        val paths = (fixedGpuTempPaths + zonePaths).distinct()
        gpuTempPathsCache = paths
        return paths
    }

    /**
     * Zone types that are not die sensors: current/voltage limiters, battery, charger,
     * board NTCs, PMICs, skin and display sensors.
     */
    private val nonDieZoneTokens = listOf(
        "ocp", "vdroop", "soft_", "bcl", "socd", "batt", "battery", "usb", "skin", "quiet",
        "neutral", "disp", "modem", "charger", "pa-", "ntc", "pm8", "pmic",
    )

    internal fun isNonDieZone(type: String): Boolean = nonDieZoneTokens.any { type.contains(it) }

    private fun isSocZone(type: String): Boolean =
        type == "soc" || type.startsWith("soc_") || type.startsWith("soc-")

    /**
     * CPU rank of a lowercase zone type, null when it is not a CPU candidate:
     * cpu-silicon, cpu-0, generic cpu, s5p-tmu, cputop, tsens, cluster, big/mid/little, soc.
     */
    internal fun cpuTempRank(type: String): Int? = when {
        isNonDieZone(type) -> null
        type.contains("cpu-silicon") -> 0
        type.contains("cpu-0") -> 1
        type.contains("cpu") && !type.contains("gpu") -> 2
        type.contains("s5p-tmu") -> 3
        type.contains("cputop") -> 4
        type.contains("tsens") -> 5
        type.contains("cluster") -> 6
        type.contains("big") || type.contains("mid") || type.contains("little") -> 7
        isSocZone(type) -> 8
        else -> null
    }

    /** GPU rank of a lowercase zone type: gpu-silicon, generic gpu, g3d, kgsl, mali. */
    internal fun gpuTempRank(type: String): Int? = when {
        isNonDieZone(type) -> null
        type.contains("gpu-silicon") -> 0
        type.contains("gpu") -> 1
        type.contains("g3d") -> 2
        type.contains("kgsl") -> 3
        type.contains("mali") -> 4
        else -> null
    }

    /** Thermal zones whose type names a skin sensor; empty on devices without one. */
    @Synchronized
    fun skinTempPaths(): List<String> {
        skinTempPathsCache?.let { return it }
        val paths = skinPathsOf(allThermalZones())
        skinTempPathsCache = paths
        return paths
    }

    internal fun skinPathsOf(zones: List<Pair<String, String>>): List<String> =
        zones.filter { (type, _) -> type.contains("skin") }.map { it.second }.distinct()

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

    fun readTemperatureC(paths: List<String>): Int? = readTemperature(paths)?.celsius

    /** First path in [paths] holding a plausible temperature, with the path that produced it. */
    fun readTemperature(paths: List<String>): TemperatureReading? {
        for (path in paths.distinct()) {
            val raw = readFirstLine(path)?.trim()?.toIntOrNull() ?: continue
            val celsius = scaleTemperatureC(raw) ?: continue
            return TemperatureReading(celsius, path)
        }
        return null
    }

    /**
     * Whole degrees from a raw sensor value: plain degrees (1..150), deci-degrees
     * (151..1500, e.g. 425 = 42.5 C) or milli-degrees (above 1500). Zero, negative
     * and out-of-range results are rejected.
     */
    fun scaleTemperatureC(raw: Int): Int? {
        val celsius = when {
            raw <= 0 -> return null
            raw <= MAX_PLAIN_CELSIUS -> raw
            raw <= MAX_DECI_CELSIUS_RAW -> (raw + 5) / 10
            else -> (raw + 500) / 1000
        }
        return celsius.takeIf { it in 1..MAX_PLAIN_CELSIUS }
    }

    private const val MAX_PLAIN_CELSIUS = 150
    private const val MAX_DECI_CELSIUS_RAW = 1500

    fun readFirstLine(path: String): String? {
        return try {
            File(path).bufferedReader().use { it.readLine() }
        } catch (_: Exception) {
            null
        }
    }

    fun readNthLine(path: String, lineIndex: Int): String? {
        return try {
            File(path).bufferedReader().useLines { lines ->
                lines.drop(lineIndex).firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readPercentFromLine(path: String): Int? = readFirstLine(path)?.let(::parseUsagePercent)

    private val leadingNumber = Regex("(?<![A-Za-z0-9_.])(\\d+)(?![A-Za-z0-9_])")

    /**
     * First standalone integer on a usage line, e.g. `45`, `45 %`, `45@600000000Hz`
     * (devfreq load), `45 12 43` (MediaTek ged loading/block/idle). Values outside
     * 0..100 are not percentages and yield null rather than a clamped number.
     */
    fun parseUsagePercent(raw: String): Int? {
        val value = leadingNumber.find(raw.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return value.takeIf { it in 0..100 }
    }

    /** Short tag for a usage node: its parent directory and file name, e.g. `hal/gpu_utilization`. */
    fun usageSourceTag(path: String): String {
        val file = File(path)
        val parent = file.parentFile?.name
        return if (parent.isNullOrEmpty()) file.name else "$parent/${file.name}"
    }

    /**
     * Short tag for a temperature node: the thermal zone's `type` (e.g. `cpu-0-0-0`)
     * for `thermal_zoneN/temp`, otherwise its parent directory and file name
     * (e.g. `kgsl-3d0/temp`).
     */
    fun temperatureSourceTag(path: String): String {
        val file = File(path)
        val zone = file.parentFile
        if (zone != null && zone.name.startsWith("thermal_zone")) {
            readFirstLine(File(zone, "type").path)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return usageSourceTag(path)
    }

    /**
     * Tag of the node behind the latest valid GPU usage reading, null until one is read.
     * Written only by the session's publishing sampler (the metrics collector's).
     */
    @Volatile
    var lastGpuUsageSource: String? = null

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
    private var lastCoreTotal = LongArray(0)
    private var lastCoreIdle = LongArray(0)

    fun reset() {
        lastTotal = null
        lastIdle = null
        lastCoreTotal = LongArray(0)
        lastCoreIdle = LongArray(0)
    }

    fun sample(): CpuUsageReading? {
        val lines = readCpuLines()
        val parts = lines?.firstOrNull()
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
                        val cores = lines.drop(1).let(::sampleCores)
                        return CpuUsageReading(percent, CpuUsageSource.PROC_STAT, cores?.first, cores?.second)
                    }
                }
            }
        }

        val fallback = readFromFrequency() ?: return null
        return CpuUsageReading(fallback, CpuUsageSource.CPU_FREQUENCY)
    }

    private fun readCpuLines(): List<String>? = try {
        File("/proc/stat").bufferedReader().useLines { seq -> seq.takeWhile { it.startsWith("cpu") }.toList() }
    } catch (_: Exception) {
        null
    }

    private fun sampleCores(lines: List<String>): Pair<Int?, LongArray>? {
        val parsed = lines.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val index = parts.firstOrNull()?.removePrefix("cpu")?.toIntOrNull() ?: return@mapNotNull null
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return@mapNotNull null
            Triple(index, values.sum(), values[3] + values.getOrElse(4) { 0L })
        }
        if (parsed.isEmpty()) return null
        val size = parsed.maxOf { it.first } + 1
        val total = LongArray(size) { -1L }
        val idle = LongArray(size) { -1L }
        parsed.forEach { (index, t, i) ->
            total[index] = t
            idle[index] = i
        }
        val prevTotal = lastCoreTotal
        val prevIdle = lastCoreIdle
        lastCoreTotal = total
        lastCoreIdle = idle
        if (prevTotal.isEmpty()) return null
        val busy = LongArray(size)
        var maxPercent: Int? = null
        for (index in 0 until minOf(size, prevTotal.size)) {
            if (total[index] < 0L || prevTotal[index] < 0L) continue
            val totalDiff = total[index] - prevTotal[index]
            if (totalDiff <= 0L) continue
            val busyDiff = (totalDiff - (idle[index] - prevIdle[index])).coerceIn(0L, totalDiff)
            busy[index] = busyDiff
            val pct = (busyDiff * 100L / totalDiff).toInt()
            if (maxPercent == null || pct > maxPercent) maxPercent = pct
        }
        return maxPercent to busy
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
 *
 * Counter nodes such as kgsl `gpubusy` can reset on read, so only one sampler should
 * read them while a session runs: the metrics collector's, created with [publishSource]
 * set so it alone records [SystemMetricsSources.lastGpuUsageSource].
 */
class GpuUsageSampler(private val publishSource: Boolean = false) {
    private val busyDelta = GpuBusyDelta()
    private var lastGpuInfoMs: Long? = null
    private var lastGpuInfoWallMs: Long = 0L

    fun reset() {
        busyDelta.reset()
        lastGpuInfoMs = null
        lastGpuInfoWallMs = 0L
    }

    fun sample(): GpuUsageReading? {
        val reading = SystemMetricsSources.gpuUsagePaths()
            .asSequence()
            .mapNotNull { readSample(it) }
            .firstOrNull()
            ?: MaliGpuCounters.sample()
        if (publishSource && reading != null) {
            SystemMetricsSources.lastGpuUsageSource = SystemMetricsSources.usageSourceTag(reading.source)
        }
        return reading
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
