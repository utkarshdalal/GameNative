package app.gamenative.utils

import app.gamenative.powercontrol.metrics.CpuClusterKind
import app.gamenative.powercontrol.metrics.CpuClusterLayout
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

/** Session-end verdict computed from the light sampler's per-second records. Pure: no Android, no I/O. */
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
    const val HARD_MEMORY_AVAIL_MB = 1024
    private const val LOW_AVAIL_MB = 300
    private const val GPU_BUSY_SATURATED = 85
    private const val GPU_CPU_MAX = 60
    private const val GPU_THROTTLED_CPU_MAX = 70
    private const val GPU_FULL_CLOCK_RATIO = 0.9
    private const val THERMAL_GPU_BUSY = 70
    private const val HOT_BEFORE_ONSET_SHARE = 0.5
    private const val HOT_AT_LAUNCH_MAX_CONFIDENCE = 0.8
    private const val FIVE_MIN_DECLINE_RATIO = 0.9
    private const val LEGACY_MIN_SHARE = 0.4
    private const val LEGACY_THERMAL_SHARE = 0.5
    private const val LEGACY_MAX_CONFIDENCE = 0.6
    const val SOURCE_LEGACY = "legacy"

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
        /** The session's fps_by_5min, oldest first; when empty, five-minute means of [samples] stand in. */
        val fpsBy5Min: List<Int> = emptyList(),
        /** The older per-tick classifier's bottleneck_share (cpu, gpu, thermal, ...), the fallback verdict. */
        val legacyShare: Map<String, Double>? = null,
        /** The panel refresh rate in Hz; 0 when unknown. */
        val refreshHz: Double = 0.0,
        /** Which cores are little, big and prime; null when the device tells none apart. */
        val clusterKinds: List<CpuClusterKind>? = null,
        /** System-wide major faults and pages scanned for reclaim per five-minute window; null when /proc/vmstat was not read. */
        val majorFaultsBy5Min: List<Long>? = null,
        val memReclaimBy5Min: List<Long>? = null,
        /** New DXVK state cache entries per five-minute window; null when not on DXVK or no cache file was found. */
        val pipelineCompilesBy5Min: List<Int>? = null,
        /** Whether the state cache holds a pipeline with hull or domain shaders; null when not read to the end. */
        val tessellationPipelines: Boolean? = null,
    )

    /**
     * The per-lever diagnosis signals: where the hot thread ran, how many pipelines were compiled, whether the game
     * tessellates, and how hard the system paged. Each is null when it was not measured.
     */
    class LeverSignals(
        val hotThreadCoreShare: Map<String, Double>? = null,
        val pipelineCompilesBy5Min: List<Int>? = null,
        val tessellationPipelines: Boolean? = null,
        val majorFaultsBy5Min: List<Int>? = null,
        val memReclaimBy5Min: List<Int>? = null,
    ) {
        fun putInto(out: MutableMap<String, Any>) {
            hotThreadCoreShare?.let { out["hot_thread_core_share"] = it }
            pipelineCompilesBy5Min?.let { out["pipeline_compiles_by_5min"] = it }
            tessellationPipelines?.let { out["tessellation_pipelines"] = it }
            majorFaultsBy5Min?.let { out["major_faults_by_5min"] = it }
            memReclaimBy5Min?.let { out["mem_reclaim_by_5min"] = it }
        }

        companion object {
            fun of(input: Input, gameplay: List<SessionSample>): LeverSignals = LeverSignals(
                hotThreadCoreShare = CpuClusterLayout.coreShare(gameplay.map { it.hotPct to it.hotCore }, input.clusterKinds),
                pipelineCompilesBy5Min = input.pipelineCompilesBy5Min,
                tessellationPipelines = input.tessellationPipelines,
                majorFaultsBy5Min = input.majorFaultsBy5Min?.map(::clampInt),
                memReclaimBy5Min = input.memReclaimBy5Min?.map(::clampInt),
            )

            private fun clampInt(v: Long): Int = v.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
    }

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
        /** [SOURCE_LEGACY] when the class came from the older classifier; null when the sampler decided. */
        val source: String? = null,
        val load: Load? = null,
        val signals: LeverSignals? = null,
        val trimEvents: Int = 0,
        /** The lowest available RAM over the gameplay samples; null when none was sampled. */
        val availMinMb: Int? = null,
        val memoryHard: Boolean = false,
    ) {
        /** The game_exited properties of the verdict. */
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
                    put("trim_events", trimEvents)
                    availMinMb?.let { put("avail_min_mb", it) }
                    put("memory_hard", memoryHard)
                    hot?.let { put("hot", it) }
                    fpsBeforeOnset?.let { put("fps_before_onset", round1(it)) }
                    fpsAfterOnset?.let { put("fps_after_onset", round1(it)) }
                    put("card", "none")
                    source?.let { put("source", it) }
                    load?.let { l ->
                        l.gpuBusyP95?.let { put("gpu_busy_p95", it) }
                        l.hotThreadP95?.let { put("hot_thread_p95", it) }
                        put("fps_ceiling_reason", l.fpsCeilingReason)
                        l.fpsP10?.let { put("fps_p10", round1(it)) }
                        l.refreshHz?.let { put("refresh_hz", round1(it)) }
                        l.gpuWorkPerFrame?.let { put("gpu_work_per_frame", round2(it)) }
                        if (l.windows.isNotEmpty()) {
                            put("gpu_work_by_5min", l.windows.map { w -> w.gpuWorkPerFrame?.let { v -> round2(v) } ?: -1.0 })
                            put("gpu_clock_mhz_by_5min", l.windows.map { w -> w.gpuClockMhz ?: -1 })
                        }
                    }
                    signals?.putInto(this)
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

            // A GPU held below full clock by a power cap is not GPU-bound, but one held down by heat still is.
            val mhzRatio = if (s.gpuMhz != null && gpuTop != null && gpuTop > 0) s.gpuMhz.toDouble() / gpuTop else null
            val throttled = (s.thermalStatus ?: 0) >= THERMAL_MODERATE || clockThrottled(s, input, gpuTop)
            val gpuFed = if (throttled) {
                s.cpuTotal == null || s.cpuTotal < GPU_THROTTLED_CPU_MAX
            } else {
                (mhzRatio == null || mhzRatio >= GPU_FULL_CLOCK_RATIO) && (s.cpuTotal == null || s.cpuTotal < GPU_CPU_MAX)
            }
            // A busy, fed GPU limits the second whatever the hot thread shows: a thread that waits on the GPU reads as busy too.
            if (gpuBusy != null && gpuBusy >= GPU_BUSY_SATURATED && gpuFed) gpu++
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
        // Hot from the start: no cool stretch exists to compare against, so a busy GPU over the session decides.
        val hotBeforeOnset = onsetT?.let { t -> g.filter { it.t < t } }.orEmpty()
        val hotAtLaunch = onsetIndex != null && (
            onsetIndex * stride < WARMUP_SEC ||
                hotBeforeOnset.count { (it.thermalStatus ?: 0) >= THERMAL_MODERATE } >= HOT_BEFORE_ONSET_SHARE * hotBeforeOnset.size
            )
        val gpuMedian = median(g.mapNotNull { it.gpuBusy?.toDouble() })
        val thermalGpu = gameplaySec >= MIN_THERMAL_GAMEPLAY_SEC && if (hotAtLaunch) {
            gpuMedian != null && gpuMedian >= THERMAL_GPU_BUSY
        } else {
            onsetIndex != null && gpuBeforeOnset != null && gpuBeforeOnset >= THERMAL_GPU_BUSY && framesDropped
        }

        // Trim callbacks alone do not show a memory limit: available memory must also have run low.
        val availMinMb = g.mapNotNull { it.availMb }.minOrNull()
        val memoryHard = input.trimEvents >= HARD_MEMORY_TRIM_EVENTS && availMinMb != null && availMinMb < HARD_MEMORY_AVAIL_MB
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
        // Hot from launch has no cool stretch to compare against, so the thermal verdict is never fully certain.
        val confidence = (chosenShare * coverage).coerceIn(0.0, 1.0)
            .let { if (cls == THERMAL_GPU && hotAtLaunch) minOf(it, HOT_AT_LAUNCH_MAX_CONFIDENCE) else it }
        val legacy = if (gameplaySec >= MIN_GAMEPLAY_SEC && (cls == NONE || !confident)) legacyVerdict(input.legacyShare) else null

        val stutter = input.stutterShare
        val third = n / 3
        val firstThird = if (third > 0) median(g.take(third).map { it.fps.toDouble() }) else null
        val lastThird = if (third > 0) median(g.takeLast(third).map { it.fps.toDouble() }) else null
        val heatDrop = (fpsBefore != null && fpsAfter != null && fpsAfter < 0.9 * fpsBefore) ||
            (firstThird != null && lastThird != null && lastThird < 0.85 * firstThird)
        val symptom = when {
            legacy == null && cls == THERMAL_GPU && hotAtLaunch -> if (fpsDeclines(input.fpsBy5Min, g)) "heat_drop" else "low_fps"
            steadyFps != null && (steadyFps < 30 || (input.fpsCap > 0 && steadyFps < 0.9 * input.fpsCap)) -> "low_fps"
            stutter != null && stutter >= 0.01 -> "stutter"
            heatDrop -> "heat_drop"
            else -> "none"
        }

        return Result(
            cls = legacy?.first ?: cls,
            confident = if (legacy != null) legacy.second >= LEGACY_MIN_SHARE else confident,
            confidence = legacy?.let { minOf(LEGACY_MAX_CONFIDENCE, it.second) } ?: confidence,
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
            source = legacy?.let { SOURCE_LEGACY },
            load = load(input.samples.filter { it.fps > 0f }, g, steadyFps, input),
            signals = LeverSignals.of(input, g),
            trimEvents = input.trimEvents,
            availMinMb = availMinMb,
            memoryHard = memoryHard,
        )
    }

    /** One five-minute window of the sampler: mean GPU clock and GPU work per frame; null where not sampled. */
    class LoadWindow(val gpuClockMhz: Int?, val gpuWorkPerFrame: Double?)

    /**
     * The continuous load signals sent with the class: GPU busy p95 and the game-process hot thread's p95 over
     * gameplay, why fps stops where it does ([fpsCeilingReason]), and GPU work per frame for the session (median
     * of the gameplay windows) and per five-minute window.
     */
    class Load(
        val gpuBusyP95: Int?,
        val hotThreadP95: Int?,
        val fpsCeilingReason: String,
        val gpuWorkPerFrame: Double?,
        val windows: List<LoadWindow>,
        /** 10th percentile of per-second gameplay fps. */
        val fpsP10: Double? = null,
        /** The panel refresh rate the sampler saw; null when unknown. */
        val refreshHz: Double? = null,
    )

    const val CEILING_CAP = "cap"
    const val CEILING_MAIN_THREAD = "main_thread"
    const val CEILING_GPU = "gpu"
    const val CEILING_BOTH = "both"
    const val CEILING_NONE = "none"

    const val CEILING_CAP_SHARE = 0.97
    const val BOTH_GPU_P95 = 85
    const val BOTH_HOT_P95 = 75
    const val BOTH_FPS_SHARE = 0.9
    const val MAIN_THREAD_HOT_P95 = 75
    const val MAIN_THREAD_GPU_P95_BELOW = 85
    const val GPU_CEILING_P95 = 90
    private const val CEILING_FPS_PERCENTILE = 0.9
    private const val WINDOW_SEC = 300

    /**
     * GPU work per frame: GPU busy (0-1) x GPU clock in MHz / fps, from window means. Thermal clock swings move fps
     * and clock together, so this stays put across runs of the same workload and drops when work is removed.
     */
    fun gpuWorkPerFrame(gpuBusyMeanPct: Double?, gpuClockMhzMean: Double?, fpsMean: Double?): Double? {
        if (gpuBusyMeanPct == null || gpuClockMhzMean == null || fpsMean == null) return null
        if (gpuBusyMeanPct < 0 || gpuClockMhzMean <= 0 || fpsMean <= 0) return null
        return gpuBusyMeanPct / 100.0 * gpuClockMhzMean / fpsMean
    }

    /** Why fps stops where it does: cap, both, main_thread, gpu or none. */
    fun fpsCeilingReason(
        steadyFps: Double?,
        sessionCeilingFps: Double?,
        fpsCap: Int,
        refreshHz: Double,
        gpuBusyP95: Int?,
        hotThreadP95: Int?,
    ): String {
        val limit = listOfNotNull(fpsCap.takeIf { it > 0 }?.toDouble(), refreshHz.takeIf { it > 0 }).minOrNull()
        if (steadyFps != null && limit != null && steadyFps >= CEILING_CAP_SHARE * limit) return CEILING_CAP
        val gpu = gpuBusyP95
        val hot = hotThreadP95
        val nearCeiling = steadyFps != null && listOfNotNull(limit, sessionCeilingFps).any { steadyFps >= BOTH_FPS_SHARE * it }
        if (gpu != null && hot != null && gpu >= BOTH_GPU_P95 && hot >= BOTH_HOT_P95 && nearCeiling) return CEILING_BOTH
        if (hot != null && hot >= MAIN_THREAD_HOT_P95 && (gpu == null || gpu < MAIN_THREAD_GPU_P95_BELOW)) return CEILING_MAIN_THREAD
        if (gpu != null && gpu >= GPU_CEILING_P95) return CEILING_GPU
        return CEILING_NONE
    }

    /** [all] is every sample with frames (window means), [g] the gameplay ones (percentiles and the ceiling). */
    private fun load(all: List<SessionSample>, g: List<SessionSample>, steadyFps: Double?, input: Input): Load? {
        if (all.isEmpty()) return null
        val gpuP95 = p95(g.mapNotNull { it.gpuBusy })
        val hotP95 = p95(g.mapNotNull { it.hotPct })
        val ceiling = percentile(g.map { it.fps.toDouble() }, CEILING_FPS_PERCENTILE)
        val windows = all.groupBy { it.t / WINDOW_SEC }.toSortedMap().values.map { w ->
            val paired = w.filter { it.gpuBusy != null && it.gpuMhz != null && it.gpuMhz > 0 }
            val clocks = w.mapNotNull { it.gpuMhz?.takeIf { m -> m > 0 } }
            LoadWindow(
                gpuClockMhz = clocks.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                gpuWorkPerFrame = paired.takeIf { it.isNotEmpty() }?.let { p ->
                    gpuWorkPerFrame(p.map { it.gpuBusy!!.toDouble() }.average(), p.map { it.gpuMhz!!.toDouble() }.average(), p.map { it.fps.toDouble() }.average())
                },
            )
        }
        val gameplayWork = (if (windows.size >= 3) windows.drop(1) else windows).mapNotNull { it.gpuWorkPerFrame }
        return Load(
            gpuBusyP95 = gpuP95,
            hotThreadP95 = hotP95,
            fpsCeilingReason = fpsCeilingReason(steadyFps, ceiling, input.fpsCap, input.refreshHz, gpuP95, hotP95),
            gpuWorkPerFrame = median(gameplayWork),
            windows = windows.takeIf { ws -> ws.any { it.gpuWorkPerFrame != null || it.gpuClockMhz != null } }.orEmpty(),
            fpsP10 = percentile(g.map { it.fps.toDouble() }, 0.10),
            refreshHz = input.refreshHz.takeIf { it > 0 },
        )
    }

    private fun p95(values: List<Int>): Int? = percentile(values.map { it.toDouble() }, 0.95)?.roundToInt()

    /** Nearest-rank percentile. */
    private fun percentile(values: List<Double>, q: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[(kotlin.math.ceil(sorted.size * q).toInt() - 1).coerceIn(0, sorted.size - 1)]
    }

    /** The older classifier's verdict as (class, share): GPU-bound, or GPU-bound under heat; null when it names neither. */
    private fun legacyVerdict(share: Map<String, Double>?): Pair<String, Double>? {
        if (share == null) return null
        val gpu = share["gpu"] ?: return null
        val thermal = share["thermal"] ?: 0.0
        return when {
            gpu < LEGACY_MIN_SHARE -> null
            thermal >= LEGACY_THERMAL_SHARE -> THERMAL_GPU to gpu
            else -> GPU to gpu
        }
    }

    /** Whether fps fell by a tenth from the first to the last five minutes, the first (loading) bucket set aside. */
    private fun fpsDeclines(fpsBy5Min: List<Int>, g: List<SessionSample>): Boolean {
        val buckets = fpsBy5Min.map { it.toDouble() }.ifEmpty {
            g.groupBy { it.t / 300 }.toSortedMap().values.map { w -> w.sumOf { it.fps.toDouble() } / w.size }
        }
        val steady = if (fpsBy5Min.size >= 3) buckets.drop(1) else buckets
        return steady.size >= 2 && steady.last() < FIVE_MIN_DECLINE_RATIO * steady.first()
    }

    /** The big CPU cluster or the GPU held below [CLOCK_RATIO] of its top clock while hot, outside the user's cap. */
    private fun clockThrottled(s: SessionSample, input: Input, gpuTop: Int?): Boolean {
        val big = input.clusterCpuinfoMaxMhz.lastIndex
        val bigMax = input.clusterCpuinfoMaxMhz.getOrNull(big)
        val bigCap = s.clusterMaxMhz?.getOrNull(big)?.takeIf { it > 0 }
        val cpuClockDown = bigMax != null && bigCap != null && bigCap < CLOCK_RATIO * bigMax
        val gpuClockDown = gpuTop != null && s.gpuMaxMhz != null && s.gpuMaxMhz > 0 && s.gpuMaxMhz < CLOCK_RATIO * gpuTop
        val hot = (s.cpuTempC ?: 0) >= HOT_CPU_TEMP_C || (s.skinTempC ?: 0) >= HOT_SKIN_TEMP_C
        return !s.capActive && (cpuClockDown || gpuClockDown) && hot
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
        return sustainedStart(g, CLOCK_SUSTAINED_SEC) { clockThrottled(it, input, gpuTop) }?.let { "clock_temp" to it }
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
