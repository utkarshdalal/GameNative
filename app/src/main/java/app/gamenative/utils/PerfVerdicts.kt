package app.gamenative.utils

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

internal object PerfVerdicts {

    private const val THERMAL_MODERATE = 2
    private const val SUSTAINED_SEC = 20
    private const val CEILING_RATIO = 0.85
    private val thermalNames = arrayOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")

    private class Sustained(val maxSec: Int, val firstStartT: Int?)

    fun compute(run: PerfRun): JSONObject {
        val samples = run.samples
        val notes = mutableListOf<String>()
        val signals = mutableListOf<String>()

        fun medianOf(selector: (PerfSample) -> Number?): Double? =
            median(samples.mapNotNull { selector(it)?.toDouble() })

        val fpsMed = medianOf { it.fps }
        val cpuMed = medianOf { it.cpuTotal }
        val gpuMed = medianOf { it.gpuBusy }
        val iowMed = medianOf { it.iowait }
        val wsMed = medianOf { it.wineserverCpu }

        val gamePid = modal(samples.mapNotNull { it.game?.pid })
        val gameSamples = samples.mapNotNull { s -> s.game?.takeIf { it.pid == gamePid } }
        val gameName = gameSamples.firstOrNull()?.name
        val topThreads = gameSamples.mapNotNull { it.top.firstOrNull() }
        val topThreadMed = median(topThreads.map { it.cpu.toDouble() })
        val topThreadName = modal(topThreads.map { it.name })
        val topProcessor = modal(topThreads.mapNotNull { it.processor })
        val topSleepFraction = fraction(topThreads) { it.state == 'S' }
        val topVolMed = median(topThreads.mapNotNull { it.voluntaryPerSec?.toDouble() })
        val dStateFraction = fraction(gameSamples) { it.dStateThreads > 0 }

        val coreCount = samples.firstNotNullOfOrNull { it.cores }?.size ?: 0
        val coreMeds = (0 until coreCount).map { i ->
            median(samples.mapNotNull { s -> s.cores?.getOrNull(i)?.takeIf { it >= 0 }?.toDouble() })
        }

        val littleCores = if (run.clusters.size > 1) run.clusters.first().cores.toSet() else emptySet()
        val bigIndex = run.clusters.lastIndex
        val bigMaxMhz = run.clusters.lastOrNull()?.maxKhz?.div(1000L)?.toInt()

        val gpuBound = gpuMed != null && cpuMed != null && gpuMed > 85 && cpuMed < 60
        val hotCore = coreMeds.withIndex().firstOrNull { (i, med) ->
            med != null && med > 90 && (median(coreMeds.filterIndexed { j, _ -> j != i }.filterNotNull()) ?: 100.0) < 40
        }
        val cpuMultiThread = cpuMed != null && cpuMed > 80
        val littleCoreTrap = topThreadMed != null && topThreadMed > 85 && topProcessor != null && topProcessor in littleCores
        val wineserverBound = wsMed != null && wsMed > 80 && (topThreadMed ?: 0.0) < 50
        val syncBound = topSleepFraction > 0.5 && topVolMed != null && topVolMed > 5000 && (topThreadMed ?: 0.0) < 50
        val ioBound = (iowMed != null && iowMed > 10) || dStateFraction > 0.3
        val vsyncFraction = if (run.totalFrames > 0) run.vsyncMultipleFrames.toDouble() / run.totalFrames else 0.0
        val vsyncLocked = run.totalFrames > 0 && vsyncFraction > 0.7
        val frameP50 = percentile(run.histogramMs, 0.50)
        val frameP99 = percentile(run.histogramMs, 0.99)
        val hitching = frameP50 != null && frameP99 != null && frameP50 > 0 &&
            frameP99 > 3 * frameP50 && fpsMed != null && fpsMed > 25

        if (littleCoreTrap) {
            signals += "littleCoreTrap"
            notes += "${topThreadName ?: "game top thread"} on cpu$topProcessor (little cluster) at ${fmt(topThreadMed)}%"
        }
        if (wineserverBound) {
            signals += "wineserverBound"
            notes += "wineserver ${fmt(wsMed)}% of a core, game top thread ${fmt(topThreadMed)}%"
        }
        if (gpuBound) {
            signals += "gpu"
            notes += "GPU busy ${fmt(gpuMed)}% with total CPU ${fmt(cpuMed)}%"
        }
        if (hotCore != null) {
            signals += "cpuSingleThread"
            val others = median(coreMeds.filterIndexed { j, _ -> j != hotCore.index }.filterNotNull())
            notes += "cpu${hotCore.index} at ${fmt(hotCore.value)}% (${clusterLabel(run, hotCore.index)} cluster) " +
                "while the other cores median ${fmt(others)}%"
        }
        if (cpuMultiThread) {
            signals += "cpuMultiThread"
            notes += "total CPU ${fmt(cpuMed)}% across $coreCount cores"
        }
        if (ioBound) {
            signals += "ioBound"
            val install = run.installPath?.let { " installed at $it (${run.installLocation ?: "unknown"})" } ?: ""
            notes += "iowait ${fmt(iowMed)}%, D-state game threads in ${pct(dStateFraction)}% of samples;$install"
        }
        if (syncBound) {
            signals += "syncBound"
            notes += "${topThreadName ?: "game top thread"} sleeping in ${pct(topSleepFraction)}% of samples, " +
                "${fmt(topVolMed)}/s voluntary switches, ${fmt(topThreadMed)}% CPU"
        }
        if (vsyncLocked) {
            signals += "vsyncLocked"
            notes += "${pct(vsyncFraction)}% of frames at a multiple of the ${fmt(run.refreshRateHz.toDouble())} Hz interval " +
                "(${fmt(1000.0 / run.refreshRateHz)} ms)"
        }
        if (hitching) {
            signals += "hitching"
            notes += "p99 frame time $frameP99 ms vs median $frameP50 ms at ${fmt(fpsMed)} fps"
        }
        val notSaturated = signals.isEmpty() && fpsMed != null && fpsMed < 30
        if (notSaturated) {
            signals += "notSaturated"
            notes += "median ${fmt(fpsMed)} fps with CPU ${fmt(cpuMed)}% and GPU ${fmt(gpuMed)}%; no saturated resource"
        }
        val bottleneck = signals.firstOrNull() ?: "unknown"

        val thermal = sustained(samples, SUSTAINED_SEC) { (it.thermalStatus ?: 0) >= THERMAL_MODERATE }
        val ceiling = if (bigIndex >= 0 && bigMaxMhz != null) {
            sustained(samples, SUSTAINED_SEC) { s ->
                val mhz = s.clusterMaxMhz?.getOrNull(bigIndex) ?: -1
                mhz > 0 && mhz < CEILING_RATIO * bigMaxMhz
            }
        } else {
            Sustained(0, null)
        }
        val userCapped = run.powerControl?.capActive == true
        val third = samples.size / 3
        val fpsFirst = if (third > 0) median(samples.take(third).mapNotNull { it.fps?.toDouble() }) else null
        val fpsLast = if (third > 0) median(samples.takeLast(third).mapNotNull { it.fps?.toDouble() }) else null
        val fpsDropped = fpsFirst != null && fpsLast != null && fpsFirst > 0 && fpsLast < 0.75 * fpsFirst
        val maxThermal = samples.maxOfOrNull { it.thermalStatus ?: 0 } ?: 0
        val ceilingMinMhz = if (bigIndex >= 0) {
            samples.mapNotNull { it.clusterMaxMhz?.getOrNull(bigIndex)?.takeIf { v -> v > 0 } }.minOrNull()
        } else {
            null
        }
        val throttlingStatus = when {
            thermal.firstStartT != null -> "thermal"
            ceiling.firstStartT != null && userCapped -> "userCapped"
            ceiling.firstStartT != null -> "clockCeiling"
            else -> "none"
        }
        if (throttlingStatus == "thermal" || throttlingStatus == "clockCeiling") {
            val parts = mutableListOf<String>()
            if (thermal.firstStartT != null) {
                val at = run.thermalTransitions.firstOrNull { it.second >= THERMAL_MODERATE }?.first ?: thermal.firstStartT
                parts += "thermal ${thermalName(maxThermal)} at ${clock(at)}"
            }
            if (ceiling.firstStartT != null && bigMaxMhz != null && ceilingMinMhz != null) {
                parts += "big cluster ceiling ${ghz(bigMaxMhz)}→${ghz(ceilingMinMhz)} GHz"
            }
            if (fpsFirst != null && fpsLast != null) {
                parts += "fps ${fmt(fpsFirst)}→${fmt(fpsLast)}"
            }
            notes += parts.joinToString("; ")
        }
        if (userCapped) {
            val cap = run.powerControl?.maxCpuKhz?.let { " (max ${ghz((it / 1000L).toInt())} GHz)" } ?: ""
            notes += "power control cap active$cap; clock ceiling not counted as thermal throttling"
        }

        val minAvail = samples.mapNotNull { it.availMb }.minOrNull()
        val lowSeen = samples.any { it.lowMemory == true }
        val peakPss = samples.mapNotNull { it.pssMb }.maxOrNull()
        val peakGameRss = samples.mapNotNull { it.gameRssMb }.maxOrNull()
        val memoryPressure = (minAvail != null && minAvail < 300) || lowSeen
        if (memoryPressure) {
            notes += "memory pressure: min available ${minAvail ?: "?"} MB" +
                (if (lowSeen) ", low-memory flag seen" else "") +
                "; peak PSS ${peakPss ?: "?"} MB, game RSS ${peakGameRss ?: "?"} MB"
        }

        val threadProfile = JSONObject().apply {
            putOpt("game", gameName)
            putOpt("pid", gamePid)
            putOpt("threadCount", median(gameSamples.map { it.threadCount.toDouble() })?.roundToInt())
            putOpt("wineserverCpu", wsMed?.roundToInt())
            put(
                "topThreads",
                JSONArray().apply {
                    gameSamples.flatMap { it.top }
                        .groupBy { it.name }
                        .map { (name, entries) ->
                            val cpu = median(entries.map { it.cpu.toDouble() }) ?: 0.0
                            val processor = modal(entries.mapNotNull { it.processor })
                            Triple(name, cpu, processor)
                        }
                        .sortedByDescending { it.second }
                        .take(5)
                        .forEach { (name, cpu, processor) ->
                            put(
                                JSONObject().apply {
                                    put("name", name)
                                    put("cpu", cpu.roundToInt())
                                    putOpt("cpuIndex", processor)
                                    putOpt("cluster", processor?.let { clusterLabel(run, it) })
                                },
                            )
                        }
                },
            )
        }

        return JSONObject().apply {
            put("runLengthSec", run.runLengthSec)
            put("bottleneck", bottleneck)
            put("signals", JSONArray(signals))
            put(
                "throttling",
                JSONObject().apply {
                    put("status", throttlingStatus)
                    put("detected", throttlingStatus == "thermal" || throttlingStatus == "clockCeiling")
                    put("maxThermalStatus", thermalName(maxThermal))
                    put("thermalAboveModerateSec", thermal.maxSec)
                    putOpt("bigClusterMaxMhz", bigMaxMhz)
                    putOpt("bigClusterCeilingMinMhz", ceilingMinMhz)
                    putOpt("fpsFirstThird", fpsFirst?.let { round1(it) })
                    putOpt("fpsLastThird", fpsLast?.let { round1(it) })
                    put("fpsDropped", fpsDropped)
                },
            )
            put(
                "memory",
                JSONObject().apply {
                    put("pressure", memoryPressure)
                    putOpt("minAvailMb", minAvail)
                    put("lowMemorySeen", lowSeen)
                    putOpt("peakPssMb", peakPss)
                    putOpt("peakGameRssMb", peakGameRss)
                    putOpt("totalMb", run.totalMemMb)
                },
            )
            put("threadProfile", threadProfile)
            put(
                "medians",
                JSONObject().apply {
                    putOpt("fps", fpsMed?.let { round1(it) })
                    putOpt("cpu", cpuMed?.roundToInt())
                    putOpt("gpu", gpuMed?.roundToInt())
                    putOpt("iowait", iowMed?.roundToInt())
                    putOpt("wineserver", wsMed?.roundToInt())
                    putOpt("gameTopThread", topThreadMed?.roundToInt())
                },
            )
            run.powerControl?.let { put("powerControl", it.toJson()) }
            if (run.installPath != null) {
                put("install", JSONObject().put("path", run.installPath).putOpt("location", run.installLocation))
            }
            put("notes", JSONArray(notes))
        }
    }

    private fun sustained(samples: List<PerfSample>, minSec: Int, condition: (PerfSample) -> Boolean): Sustained {
        var runStart = -1
        var maxSec = 0
        var firstStart: Int? = null
        for (sample in samples) {
            if (condition(sample)) {
                if (runStart < 0) runStart = sample.t
                val duration = sample.t - runStart
                if (duration > maxSec) maxSec = duration
                if (duration > minSec && firstStart == null) firstStart = runStart
            } else {
                runStart = -1
            }
        }
        return Sustained(maxSec, firstStart)
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun <T> modal(values: List<T>): T? =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun <T> fraction(values: List<T>, predicate: (T) -> Boolean): Double =
        if (values.isEmpty()) 0.0 else values.count(predicate).toDouble() / values.size

    private fun percentile(histogram: Map<Int, Int>, q: Double): Int? {
        val total = histogram.values.sumOf { it.toLong() }
        if (total <= 0L) return null
        val target = Math.ceil(q * total).toLong()
        var cumulative = 0L
        for ((ms, count) in histogram.toSortedMap()) {
            cumulative += count
            if (cumulative >= target) return ms
        }
        return histogram.keys.maxOrNull()
    }

    private fun clusterLabel(run: PerfRun, core: Int): String {
        val index = run.clusters.indexOfFirst { core in it.cores }
        return when {
            index < 0 || run.clusters.size <= 1 -> "cpu"
            index == 0 -> "little"
            index == run.clusters.lastIndex -> "big"
            else -> "mid"
        }
    }

    private fun thermalName(status: Int): String = thermalNames.getOrElse(status) { status.toString() }

    private fun clock(seconds: Int): String = "${seconds / 60}m${seconds % 60}s"

    private fun ghz(mhz: Int): String = String.format(Locale.US, "%.1f", mhz / 1000.0)

    private fun fmt(value: Double?): String = value?.let { String.format(Locale.US, "%.0f", it) } ?: "?"

    private fun pct(fraction: Double): Int = (fraction * 100).roundToInt()

    private fun round1(value: Double): Double = (value * 10).roundToInt() / 10.0
}
