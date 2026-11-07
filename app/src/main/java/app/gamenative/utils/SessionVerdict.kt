package app.gamenative.utils

import kotlin.math.roundToInt

/** One second of the light per-session sampler. [t] is seconds since the session's first frame. */
internal class SessionSample(
    val t: Int,
    val fps: Float,
    val frames: Int = 0,
    val stutterFrames: Int = 0,
    val refreshMultipleFrames: Int = 0,
    val gpuBusy: Int? = null,
    val gpuMhz: Int? = null,
    val gpuMaxMhz: Int? = null,
    val cpuTotal: Int? = null,
    val coreMax: Int? = null,
    val clusterCurMhz: IntArray? = null,
    val clusterMaxMhz: IntArray? = null,
    val thermalStatus: Int? = null,
    val thermalHeadroom: Float? = null,
    val cpuTempC: Int? = null,
    val skinTempC: Int? = null,
    val hotPct: Int? = null,
    val hotUserTicks: Int? = null,
    val hotSysTicks: Int? = null,
    val hotState: Char? = null,
    val hotCore: Int? = null,
    val hotVolPerSec: Int? = null,
    val hotInvolPerSec: Int? = null,
    val wineserverPct: Int? = null,
    val availMb: Int? = null,
    val lowMemory: Boolean = false,
    val trimEvent: Boolean = false,
    val capActive: Boolean = false,
)

/**
 * Fixed-size store for [SessionSample]s: one short per field, about 60 bytes a second. When full it keeps every
 * second record and from then on stores one in [keepEvery], so a session of any length stays within [capacity].
 */
internal class SessionSampleBuffer(private val clusterCount: Int, private val capacity: Int = 3600) {
    private val width = FIXED + 2 * clusterCount
    private val data = ShortArray(capacity * width)
    private val times = IntArray(capacity)
    private val fps = FloatArray(capacity)
    private var index = 0L

    var size = 0
        private set

    var keepEvery = 1
        private set

    fun add(s: SessionSample) {
        index++
        if (index % keepEvery != 0L) return
        write(size++, s)
        if (size >= capacity) compact()
    }

    fun toList(): List<SessionSample> = (0 until size).map(::read)

    private fun compact() {
        var kept = 0
        for (i in 0 until size step 2) {
            System.arraycopy(data, i * width, data, kept * width, width)
            times[kept] = times[i]
            fps[kept] = fps[i]
            kept++
        }
        size = kept
        keepEvery *= 2
    }

    private fun write(row: Int, s: SessionSample) {
        val o = row * width
        times[row] = s.t
        fps[row] = s.fps
        data[o + FRAMES] = enc(s.frames)
        data[o + STUTTER] = enc(s.stutterFrames)
        data[o + REFRESH_MULTIPLE] = enc(s.refreshMultipleFrames)
        data[o + GPU_BUSY] = enc(s.gpuBusy)
        data[o + GPU_MHZ] = enc(s.gpuMhz)
        data[o + GPU_MAX_MHZ] = enc(s.gpuMaxMhz)
        data[o + CPU_TOTAL] = enc(s.cpuTotal)
        data[o + CORE_MAX] = enc(s.coreMax)
        data[o + THERMAL_STATUS] = enc(s.thermalStatus)
        data[o + HEADROOM] = enc(s.thermalHeadroom?.let { (it * 100f).roundToInt() })
        data[o + CPU_TEMP] = enc(s.cpuTempC)
        data[o + SKIN_TEMP] = enc(s.skinTempC)
        data[o + HOT_PCT] = enc(s.hotPct)
        data[o + HOT_USER] = enc(s.hotUserTicks)
        data[o + HOT_SYS] = enc(s.hotSysTicks)
        data[o + HOT_STATE] = enc(s.hotState?.code)
        data[o + HOT_CORE] = enc(s.hotCore)
        data[o + HOT_VOL] = enc(s.hotVolPerSec)
        data[o + HOT_INVOL] = enc(s.hotInvolPerSec)
        data[o + WINESERVER] = enc(s.wineserverPct)
        data[o + AVAIL_MB] = enc(s.availMb)
        data[o + FLAGS] = ((if (s.lowMemory) 1 else 0) or (if (s.trimEvent) 2 else 0) or (if (s.capActive) 4 else 0)).toShort()
        for (c in 0 until clusterCount) {
            data[o + FIXED + c] = enc(s.clusterCurMhz?.getOrNull(c)?.takeIf { it > 0 })
            data[o + FIXED + clusterCount + c] = enc(s.clusterMaxMhz?.getOrNull(c)?.takeIf { it > 0 })
        }
    }

    private fun read(row: Int): SessionSample {
        val o = row * width
        fun f(field: Int): Int? = data[o + field].takeIf { it != NONE }?.toInt()
        val flags = data[o + FLAGS].toInt()
        val clusters = clusterCount > 0
        return SessionSample(
            t = times[row],
            fps = fps[row],
            frames = f(FRAMES) ?: 0,
            stutterFrames = f(STUTTER) ?: 0,
            refreshMultipleFrames = f(REFRESH_MULTIPLE) ?: 0,
            gpuBusy = f(GPU_BUSY),
            gpuMhz = f(GPU_MHZ),
            gpuMaxMhz = f(GPU_MAX_MHZ),
            cpuTotal = f(CPU_TOTAL),
            coreMax = f(CORE_MAX),
            clusterCurMhz = if (clusters) IntArray(clusterCount) { f(FIXED + it) ?: -1 } else null,
            clusterMaxMhz = if (clusters) IntArray(clusterCount) { f(FIXED + clusterCount + it) ?: -1 } else null,
            thermalStatus = f(THERMAL_STATUS),
            thermalHeadroom = f(HEADROOM)?.let { it / 100f },
            cpuTempC = f(CPU_TEMP),
            skinTempC = f(SKIN_TEMP),
            hotPct = f(HOT_PCT),
            hotUserTicks = f(HOT_USER),
            hotSysTicks = f(HOT_SYS),
            hotState = f(HOT_STATE)?.toChar(),
            hotCore = f(HOT_CORE),
            hotVolPerSec = f(HOT_VOL),
            hotInvolPerSec = f(HOT_INVOL),
            wineserverPct = f(WINESERVER),
            availMb = f(AVAIL_MB),
            lowMemory = flags and 1 != 0,
            trimEvent = flags and 2 != 0,
            capActive = flags and 4 != 0,
        )
    }

    private fun enc(value: Int?): Short = value?.coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt())?.toShort() ?: NONE

    private companion object {
        const val NONE = Short.MIN_VALUE
        const val FRAMES = 0
        const val STUTTER = 1
        const val REFRESH_MULTIPLE = 2
        const val GPU_BUSY = 3
        const val GPU_MHZ = 4
        const val GPU_MAX_MHZ = 5
        const val CPU_TOTAL = 6
        const val CORE_MAX = 7
        const val THERMAL_STATUS = 8
        const val HEADROOM = 9
        const val CPU_TEMP = 10
        const val SKIN_TEMP = 11
        const val HOT_PCT = 12
        const val HOT_USER = 13
        const val HOT_SYS = 14
        const val HOT_STATE = 15
        const val HOT_CORE = 16
        const val HOT_VOL = 17
        const val HOT_INVOL = 18
        const val WINESERVER = 19
        const val AVAIL_MB = 20
        const val FLAGS = 21
        const val FIXED = 22
    }
}

/**
 * Session-end verdict of the Optimize loop (optimize-loop-spec sections 5.2-5.6), computed from the light
 * sampler's per-second records. Pure: no Android, no I/O.
 */
internal object SessionVerdict {

    const val WARMUP_SEC = 120
    const val MIN_GAMEPLAY_SEC = 300
    const val MIN_THERMAL_GAMEPLAY_SEC = 600

    const val SYNC_SPIN = "sync_spin"
    const val MEMORY = "memory"
    const val THERMAL_GPU = "thermal_gpu"
    const val GPU = "gpu"
    const val CPU_SINGLE = "cpu_single"
    const val CPU_MULTI = "cpu_multi"
    const val PRESENT_LOCKED = "present_locked"
    const val NOT_SATURATED = "not_saturated"
    const val NONE = "none"

    private const val HOLD_SHARE = 0.3
    private const val CONFIDENT_SHARE = 0.6
    private const val CONFIDENT_LEAD = 0.25
    private const val CONFIDENT_COVERAGE = 0.8
    private const val HOT_SATURATED = 85
    private const val KSHARE_WINDOW_SEC = 5
    private const val THERMAL_MODERATE = 2
    private const val STATUS_SUSTAINED_SEC = 20
    private const val CLOCK_SUSTAINED_SEC = 30
    private const val CLOCK_RATIO = 0.85
    private const val HOT_CPU_TEMP_C = 80
    private const val HOT_SKIN_TEMP_C = 45
    private const val ONSET_WINDOW_SEC = 300
    private const val GPU_BEFORE_ONSET_SEC = 120
    private const val HARD_MEMORY_TRIM_EVENTS = 3
    private const val LOW_AVAIL_MB = 300

    class Input(
        val samples: List<SessionSample>,
        val intervalSec: Int = 1,
        val clusterCpuinfoMaxMhz: IntArray = IntArray(0),
        val clusterCores: List<List<Int>> = emptyList(),
        val gpuTopMhz: Int? = null,
        val hotThreadName: String? = null,
        val trimEvents: Int = 0,
        val designCapDetected: Boolean = false,
        val fpsCap: Int = 0,
        val stutterShare: Double? = null,
    )

    class Result(
        val cls: String,
        val confident: Boolean,
        val confidence: Double,
        val symptom: String,
        val gameplaySec: Int,
        val shares: Map<String, Double>,
        val thermalGpu: Boolean,
        val procStatReal: Boolean,
        val thermalSignal: String,
        val throttleOnsetSec: Int?,
        val fpsBeforeOnset: Double?,
        val fpsAfterOnset: Double?,
        val hot: Map<String, Any>?,
        val stutterShare: Double?,
    ) {
        /** The four game_exited properties of spec section 10. */
        fun properties(): Map<String, Any> = buildMap {
            put(
                "perf_verdict",
                buildMap<String, Any> {
                    put("v", 1)
                    put("class", cls)
                    put("confident", confident)
                    put("confidence", round2(confidence))
                    put("symptom", symptom)
                    put("gameplay_s", gameplaySec)
                    put("shares", shares.mapValues { round2(it.value) })
                    put("thermal_gpu", thermalGpu)
                    put("proc_stat", if (procStatReal) "real" else "proxy")
                    put("thermal_signal", thermalSignal)
                    hot?.let { put("hot", it) }
                    fpsBeforeOnset?.let { put("fps_before_onset", round1(it)) }
                    fpsAfterOnset?.let { put("fps_after_onset", round1(it)) }
                    put("card", "none")
                },
            )
            stutterShare?.let { put("stutter_share", Math.round(it * 10_000) / 10_000.0) }
            throttleOnsetSec?.let { put("throttle_onset_s", it) }
            put("throttle_signal", thermalSignal)
        }
    }

    fun compute(input: Input): Result {
        val stride = input.intervalSec.coerceAtLeast(1)
        val g = input.samples.filter { it.t >= WARMUP_SEC && it.fps > 0f }
        val n = g.size
        val gameplaySec = n * stride
        val procStatReal = n > 0 && g.count { it.cpuTotal != null } * 2 > n

        val kshare = kernelShares(g)
        val hotStates = g.mapNotNull { it.hotState }
        val sleepFraction = if (hotStates.isEmpty()) 0.0 else hotStates.count { it == 'S' }.toDouble() / hotStates.size
        val gpuTop = input.gpuTopMhz ?: g.mapNotNull { it.gpuMaxMhz }.maxOrNull()

        var sync = 0
        var gpu = 0
        var cpu1 = 0
        var cpuN = 0
        var present = 0
        var memory = 0
        g.forEachIndexed { i, s ->
            val hot = s.hotPct
            val ks = kshare[i]
            val gpuBusy = s.gpuBusy
            val spin = hot != null && hot >= HOT_SATURATED && ks != null && ks >= 0.5
            val sleepSync = sleepFraction > 0.5 && (s.hotVolPerSec ?: 0) > 5000 && hot != null && hot < 50
            val wineserverSync = (s.wineserverPct ?: 0) >= 80 && hot != null && hot < 50
            if ((spin || sleepSync || wineserverSync) && (gpuBusy == null || gpuBusy < 60)) sync++

            val mhzRatio = if (s.gpuMhz != null && gpuTop != null && gpuTop > 0) s.gpuMhz.toDouble() / gpuTop else null
            if (gpuBusy != null && gpuBusy >= 85 && (mhzRatio == null || mhzRatio >= 0.9) &&
                (hot == null || hot < HOT_SATURATED) && (s.cpuTotal == null || s.cpuTotal < 60)
            ) {
                gpu++
            }
            if (hot != null && hot >= HOT_SATURATED && ks != null && ks < 0.4 && (gpuBusy == null || gpuBusy < 75)) cpu1++
            if (s.cpuTotal != null && s.cpuTotal >= 80 && (s.coreMax ?: 0) < 90 && (gpuBusy == null || gpuBusy < 75)) cpuN++
            if (s.frames > 0 && s.refreshMultipleFrames * 10 > s.frames * 7 &&
                (gpuBusy == null || gpuBusy < 70) && (hot == null || hot < HOT_SATURATED)
            ) {
                present++
            }
            if ((s.availMb != null && s.availMb < LOW_AVAIL_MB) || s.lowMemory || s.trimEvent) memory++
        }
        fun share(count: Int) = if (n == 0) 0.0 else count.toDouble() / n
        val shares = linkedMapOf(
            "sync" to share(sync),
            "gpu" to share(gpu),
            "cpu1" to share(cpu1),
            "cpuN" to share(cpuN),
            "present" to share(present),
            "memory" to share(memory),
        )

        val thermal = thermalOnset(g, input, gpuTop)
        val onsetIndex = thermal?.second
        val onsetT = onsetIndex?.let { g[it].t }
        val before = onsetT?.let { t -> g.filter { it.t in (t - ONSET_WINDOW_SEC) until t } }.orEmpty()
        val after = onsetT?.let { t -> g.filter { it.t >= t && it.t < t + ONSET_WINDOW_SEC } }.orEmpty()
        val fpsBefore = minuteMedianFps(before)
        val fpsAfter = minuteMedianFps(after)
        val stutterBefore = stutterShareOf(before)
        val stutterAfter = stutterShareOf(after)
        val framesDropped = (fpsBefore != null && fpsAfter != null && fpsAfter < 0.9 * fpsBefore) ||
            (stutterAfter != null && stutterAfter > 0.0 && stutterAfter >= 2 * (stutterBefore ?: 0.0))
        val gpuBeforeOnset = onsetT?.let { t ->
            median(g.filter { it.t in (t - GPU_BEFORE_ONSET_SEC) until t }.mapNotNull { it.gpuBusy?.toDouble() })
        }
        val thermalGpu = onsetIndex != null && gameplaySec >= MIN_THERMAL_GAMEPLAY_SEC &&
            onsetIndex * stride >= WARMUP_SEC && gpuBeforeOnset != null && gpuBeforeOnset >= 70 && framesDropped

        val memoryHard = input.trimEvents >= HARD_MEMORY_TRIM_EVENTS
        val steadyFps = minuteMedianFps(g)
        val held = listOf(
            SYNC_SPIN to (shares.getValue("sync") >= HOLD_SHARE),
            MEMORY to memoryHard,
            THERMAL_GPU to thermalGpu,
            GPU to (shares.getValue("gpu") >= HOLD_SHARE),
            CPU_SINGLE to (shares.getValue("cpu1") >= HOLD_SHARE),
            CPU_MULTI to (shares.getValue("cpuN") >= HOLD_SHARE),
            PRESENT_LOCKED to (shares.getValue("present") >= HOLD_SHARE),
            MEMORY to (shares.getValue("memory") >= HOLD_SHARE),
        ).firstOrNull { it.second }?.first
        val cls = when {
            gameplaySec < MIN_GAMEPLAY_SEC -> NONE
            held != null -> held
            steadyFps != null && steadyFps < 30 && !input.designCapDetected -> NOT_SATURATED
            else -> NONE
        }

        val shareKey = mapOf(
            SYNC_SPIN to "sync",
            GPU to "gpu",
            CPU_SINGLE to "cpu1",
            CPU_MULTI to "cpuN",
            PRESENT_LOCKED to "present",
            MEMORY to "memory",
        )
        fun coverage(predicate: (SessionSample) -> Boolean) = if (n == 0) 0.0 else g.count(predicate).toDouble() / n
        val coverage = when (cls) {
            GPU -> coverage { it.gpuBusy != null }
            CPU_SINGLE, SYNC_SPIN -> coverage { it.hotPct != null }
            CPU_MULTI -> coverage { it.cpuTotal != null }
            THERMAL_GPU -> coverage { it.thermalStatus != null || it.cpuTempC != null || it.skinTempC != null }
            MEMORY -> coverage { it.availMb != null }
            PRESENT_LOCKED -> 1.0
            else -> 0.0
        }
        val shareBased = cls in shareKey && !(cls == MEMORY && memoryHard)
        val chosenShare = when {
            shareBased -> shares.getValue(shareKey.getValue(cls))
            cls == THERMAL_GPU || cls == MEMORY -> 1.0
            else -> 0.0
        }
        val runnerUp = if (shareBased) {
            shares.filterKeys { it != shareKey.getValue(cls) }.values.maxOrNull() ?: 0.0
        } else {
            0.0
        }
        val needsProcStat = cls == CPU_SINGLE || cls == CPU_MULTI || cls == SYNC_SPIN
        val confident = when {
            cls == NONE || cls == NOT_SATURATED -> false
            needsProcStat && !procStatReal -> false
            !shareBased -> coverage >= CONFIDENT_COVERAGE
            else -> chosenShare >= CONFIDENT_SHARE && chosenShare - runnerUp >= CONFIDENT_LEAD && coverage >= CONFIDENT_COVERAGE
        }
        val confidence = (chosenShare * coverage).coerceIn(0.0, 1.0)

        val stutter = input.stutterShare
        val third = n / 3
        val firstThird = if (third > 0) median(g.take(third).map { it.fps.toDouble() }) else null
        val lastThird = if (third > 0) median(g.takeLast(third).map { it.fps.toDouble() }) else null
        val heatDrop = (fpsBefore != null && fpsAfter != null && fpsAfter < 0.9 * fpsBefore) ||
            (firstThird != null && lastThird != null && lastThird < 0.85 * firstThird)
        val symptom = when {
            steadyFps != null && (steadyFps < 30 || (input.fpsCap > 0 && steadyFps < 0.9 * input.fpsCap)) -> "low_fps"
            stutter != null && stutter >= 0.01 -> "stutter"
            heatDrop -> "heat_drop"
            else -> "none"
        }

        return Result(
            cls = cls,
            confident = confident,
            confidence = confidence,
            symptom = symptom,
            gameplaySec = gameplaySec,
            shares = shares,
            thermalGpu = thermalGpu,
            procStatReal = procStatReal,
            thermalSignal = thermal?.first ?: "none",
            throttleOnsetSec = onsetIndex?.let { it * stride },
            fpsBeforeOnset = fpsBefore,
            fpsAfterOnset = fpsAfter,
            hot = hotSummary(g, input),
            stutterShare = stutter,
        )
    }

    /** stime / (utime + stime) of the hot thread over the last [KSHARE_WINDOW_SEC] seconds, per sample. */
    private fun kernelShares(g: List<SessionSample>): Array<Double?> {
        val out = arrayOfNulls<Double>(g.size)
        var start = 0
        var user = 0L
        var sys = 0L
        for (i in g.indices) {
            user += g[i].hotUserTicks ?: 0
            sys += g[i].hotSysTicks ?: 0
            while (g[start].t <= g[i].t - KSHARE_WINDOW_SEC) {
                user -= g[start].hotUserTicks ?: 0
                sys -= g[start].hotSysTicks ?: 0
                start++
            }
            if (user + sys > 0) out[i] = sys.toDouble() / (user + sys)
        }
        return out
    }

    /** (signal, gameplay index of the onset) or null when there was no throttle. */
    private fun thermalOnset(g: List<SessionSample>, input: Input, gpuTop: Int?): Pair<String, Int>? {
        sustainedStart(g, STATUS_SUSTAINED_SEC) { (it.thermalStatus ?: 0) >= THERMAL_MODERATE }?.let { return "status" to it }
        if (g.any { (it.thermalStatus ?: 0) >= 1 }) return null
        val big = input.clusterCpuinfoMaxMhz.lastIndex
        val bigMax = input.clusterCpuinfoMaxMhz.getOrNull(big)
        return sustainedStart(g, CLOCK_SUSTAINED_SEC) { s ->
            val bigCap = s.clusterMaxMhz?.getOrNull(big)?.takeIf { it > 0 }
            val cpuClockDown = bigMax != null && bigCap != null && bigCap < CLOCK_RATIO * bigMax
            val gpuClockDown = gpuTop != null && s.gpuMaxMhz != null && s.gpuMaxMhz > 0 && s.gpuMaxMhz < CLOCK_RATIO * gpuTop
            val hot = (s.cpuTempC ?: 0) >= HOT_CPU_TEMP_C || (s.skinTempC ?: 0) >= HOT_SKIN_TEMP_C
            !s.capActive && (cpuClockDown || gpuClockDown) && hot
        }?.let { "clock_temp" to it }
    }

    private fun sustainedStart(g: List<SessionSample>, minSec: Int, condition: (SessionSample) -> Boolean): Int? {
        var runStart = -1
        for ((i, s) in g.withIndex()) {
            if (condition(s)) {
                if (runStart < 0) runStart = i
                if (s.t - g[runStart].t >= minSec) return runStart
            } else {
                runStart = -1
            }
        }
        return null
    }

    /** Median of 1-minute mean fps windows. */
    private fun minuteMedianFps(samples: List<SessionSample>): Double? {
        if (samples.isEmpty()) return null
        val windows = samples.groupBy { it.t / 60 }.values.map { w -> w.sumOf { it.fps.toDouble() } / w.size }
        return median(windows)
    }

    private fun stutterShareOf(samples: List<SessionSample>): Double? {
        val frames = samples.sumOf { it.frames.toLong() }
        if (frames <= 0L) return null
        return samples.sumOf { it.stutterFrames.toLong() }.toDouble() / frames
    }

    private fun hotSummary(g: List<SessionSample>, input: Input): Map<String, Any>? {
        val hot = g.filter { it.hotPct != null }
        if (hot.isEmpty()) return null
        return buildMap {
            input.hotThreadName?.let { put("name", it) }
            median(hot.map { it.hotPct!!.toDouble() })?.let { put("pct_p50", it.roundToInt()) }
            val user = hot.sumOf { (it.hotUserTicks ?: 0).toLong() }
            val sys = hot.sumOf { (it.hotSysTicks ?: 0).toLong() }
            if (user + sys > 0) put("user_share", round2(user.toDouble() / (user + sys)))
            median(hot.mapNotNull { it.hotVolPerSec?.toDouble() })?.let { put("vol_ps", it.roundToInt()) }
            median(hot.mapNotNull { it.hotInvolPerSec?.toDouble() })?.let { put("invol_ps", it.roundToInt()) }
            hot.mapNotNull { it.hotCore }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?.let { put("cluster", clusterLabel(input.clusterCores, it)) }
        }
    }

    private fun clusterLabel(clusters: List<List<Int>>, core: Int): String {
        val index = clusters.indexOfFirst { core in it }
        return when {
            index < 0 || clusters.size <= 1 -> "cpu"
            index == 0 -> "little"
            index == clusters.lastIndex -> "big"
            else -> "mid"
        }
    }

    /** Frames longer than three times the median frame time, as a share of all frames; null without frames. */
    fun stutterShare(histogramMs: LongArray): Double? {
        val total = histogramMs.sum()
        if (total <= 0L) return null
        val median = histogramMedianMs(histogramMs)
        var slow = 0L
        for (ms in (3 * median + 1) until histogramMs.size) slow += histogramMs[ms]
        return slow.toDouble() / total
    }

    fun histogramMedianMs(histogramMs: LongArray): Int {
        val total = histogramMs.sum()
        if (total <= 0L) return 0
        var seen = 0L
        for (ms in histogramMs.indices) {
            seen += histogramMs[ms]
            if (seen * 2 >= total) return ms
        }
        return histogramMs.lastIndex
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun round1(value: Double): Double = (value * 10).roundToInt() / 10.0

    private fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0
}
