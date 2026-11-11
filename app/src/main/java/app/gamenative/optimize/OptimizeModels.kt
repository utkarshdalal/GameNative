package app.gamenative.optimize

import app.gamenative.utils.SessionVerdict
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class Metrics(
    val gameplaySec: Int,
    val steadyFps: Double? = null,
    val stutterShare: Double? = null,
    val throttleOnsetSec: Int? = null,
    val cls: String? = null,
    val confidence: Double? = null,
    val confident: Boolean? = null,
    val symptom: String? = null,
    val exitReason: String? = null,
    val windowSeen: Boolean = true,
    val procStatReal: Boolean? = null,
    val thermalReporting: Boolean? = null,
    /** Mean fps of each five-minute window, oldest first. */
    val fpsBy5Min: List<Int> = emptyList(),
    /** Mean GPU busy percent of each five-minute window that sampled it (perf_by_5min gpu_avg), oldest first. */
    val gpuBy5Min: List<Int> = emptyList(),
    /** Mean GPU clock in MHz of each five-minute window that sampled it (perf_by_5min gpu_clk_mhz_avg), oldest first. */
    val gpuClockBy5Min: List<Int> = emptyList(),
    /** Hottest CPU and GPU reading of the session in °C (perf_by_5min cpu_temp_max, gpu_temp_max); null when not read. */
    val cpuTempMax: Int? = null,
    val gpuTempMax: Int? = null,
    /** Highest Android thermal status of the session (thermal_peak, else perf_by_5min thermal_status_max). */
    val thermalPeak: Int? = null,
    /** GPU busy percent p95 over gameplay (perf_verdict.gpu_busy_p95). */
    val gpuBusyP95: Int? = null,
    /** CPU percent p95 of the game process's hot thread over gameplay (perf_verdict.hot_thread_p95). */
    val hotThreadP95: Int? = null,
    /** Why fps stops where it does: cap, main_thread, gpu, both or none (perf_verdict.fps_ceiling_reason). */
    val fpsCeilingReason: String? = null,
    /** GPU busy (0-1) x GPU clock MHz / fps for the session: the median of its gameplay windows. */
    val gpuWorkPerFrame: Double? = null,
    /** GPU work per frame of each five-minute window, oldest first; null where the window has no GPU busy or clock. */
    val gpuWorkBy5Min: List<Double?> = emptyList(),
    /** 10th percentile of per-second gameplay fps (perf_verdict.fps_p10). */
    val fpsP10: Double? = null,
    /** Session frame time median and p99 in ms (game_exited frame_p50_ms, frame_p99_ms). */
    val frameTimeP50Ms: Double? = null,
    val frameTimeP99Ms: Double? = null,
    /** Panel refresh rate in Hz (perf_verdict.refresh_hz, else display_refresh_hz). */
    val refreshHz: Double? = null,
    /** The crash fingerprint of the guest output at exit (game_exited crash_fingerprint kind, code, hash); null when none. */
    val crashKind: String? = null,
    val crashCode: String? = null,
    val crashHash: String? = null,
    /** Share of the hot thread's busy seconds on little, big and prime cores (perf_verdict.hot_thread_core_share). */
    val hotThreadCoreShare: Map<String, Double>? = null,
    /** New DXVK state cache entries per five-minute window; empty when not on DXVK or not measured. */
    val pipelineCompilesBy5Min: List<Int> = emptyList(),
    /** Whether the game's state cache holds a hull or domain shader pipeline; null when unknown. */
    val tessellationPipelines: Boolean? = null,
    /** System-wide major page faults and pages scanned for reclaim per five-minute window (/proc/vmstat deltas). */
    val majorFaultsBy5Min: List<Int> = emptyList(),
    val memReclaimBy5Min: List<Int> = emptyList(),
    /** Seconds before exit the game presented no frame while it was not suspended (frames_stopped_s); null when not measured. */
    val framesStoppedSec: Int? = null,
    /** Whether the game's main window was still mapped at exit (main_window_mapped); null when not recorded. */
    val mainWindowMapped: Boolean? = null,
    /** The facts the session's class was decided from; null when the run recorded none. */
    val basis: VerdictBasis? = null,
) {
    /** Session GPU work per frame: the median of the gameplay windows, else [gpuWorkPerFrame]. */
    fun workPerFrame(): Double? {
        val windows = (if (gpuWorkBy5Min.size >= 3) gpuWorkBy5Min.drop(1) else gpuWorkBy5Min).filterNotNull()
        return medianOf(windows) ?: gpuWorkPerFrame
    }

    private fun JSONObject.putLoad(report: Boolean) {
        gpuBusyP95?.let { put("gpu_busy_p95", it) }
        hotThreadP95?.let { put("hot_thread_p95", it) }
        fpsCeilingReason?.let { put("fps_ceiling_reason", it) }
        fpsP10?.let { put("fps_p10", it) }
        frameTimeP50Ms?.let { put("frame_time_p50_ms", it) }
        frameTimeP99Ms?.let { put("frame_time_p99_ms", it) }
        refreshHz?.let { put("refresh_hz", it) }
        workPerFrame()?.let { put("gpu_work_per_frame", round2(it)) }
        if (gpuWorkBy5Min.isNotEmpty()) {
            val windows = if (report) gpuWorkBy5Min.take(MAX_REPORT_WINDOWS) else gpuWorkBy5Min
            put("gpu_work_by_5min", JSONArray().apply { windows.forEach { put(it?.let { v -> round2(v) } ?: JSONObject.NULL) } })
        }
        putSignals(report)
    }

    private fun JSONObject.putSignals(report: Boolean) {
        hotThreadCoreShare?.let { share -> put("hot_thread_core_share", JSONObject().apply { share.forEach { (k, v) -> put(k, v) } }) }
        fun series(key: String, values: List<Int>) {
            if (values.isNotEmpty()) put(key, JSONArray(if (report) values.take(MAX_REPORT_WINDOWS) else values))
        }
        series("pipeline_compiles_by_5min", pipelineCompilesBy5Min)
        tessellationPipelines?.let { put("tessellation_pipelines", it) }
        series("major_faults_by_5min", majorFaultsBy5Min)
        series("mem_reclaim_by_5min", memReclaimBy5Min)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("gameplay_s", gameplaySec)
        steadyFps?.let { put("steady_fps", it) }
        stutterShare?.let { put("stutter_share", it) }
        throttleOnsetSec?.let { put("throttle_onset_s", it) }
        cls?.let { put("class", it) }
        confidence?.let { put("confidence", it) }
        confident?.let { put("confident", it) }
        symptom?.let { put("symptom", it) }
        exitReason?.let { put("exit_reason", it) }
        put("window_seen", windowSeen)
        procStatReal?.let { put("proc_stat_real", it) }
        thermalReporting?.let { put("thermal_reporting", it) }
        if (fpsBy5Min.isNotEmpty()) put("fps_by_5min", JSONArray(fpsBy5Min))
        if (gpuBy5Min.isNotEmpty()) put("gpu_by_5min", JSONArray(gpuBy5Min))
        if (gpuClockBy5Min.isNotEmpty()) put("gpu_clock_by_5min", JSONArray(gpuClockBy5Min))
        cpuTempMax?.let { put("cpu_temp_max", it) }
        gpuTempMax?.let { put("gpu_temp_max", it) }
        thermalPeak?.let { put("thermal_peak", it) }
        crashKind?.let { put("crash_kind", it) }
        crashCode?.let { put("crash_code", it) }
        crashHash?.let { put("crash_hash", it) }
        framesStoppedSec?.let { put("frames_stopped_s", it) }
        mainWindowMapped?.let { put("main_window_mapped", it) }
        basis?.let { put("basis", it.toJson()) }
        putLoad(report = false)
    }

    /** The session as the outcome and next-rung requests send it, with the series cut to [MAX_REPORT_WINDOWS]. */
    fun toReportJson(): JSONObject = JSONObject().apply {
        put("gameplay_s", gameplaySec)
        put("steady_fps", steadyFps ?: JSONObject.NULL)
        put("stutter_share", stutterShare ?: JSONObject.NULL)
        put("throttle_onset_s", throttleOnsetSec ?: JSONObject.NULL)
        put("class", cls ?: JSONObject.NULL)
        put("confidence", confidence ?: JSONObject.NULL)
        confident?.let { put("confident", it) }
        symptom?.let { put("symptom", it) }
        exitReason?.let { put("exit_reason", it) }
        put("window_seen", windowSeen)
        cpuTempMax?.let { put("cpu_temp_max", it) }
        gpuTempMax?.let { put("gpu_temp_max", it) }
        thermalPeak?.let { put("thermal_peak", it) }
        crashKind?.let { put("crash_kind", it) }
        crashCode?.let { put("crash_code", it) }
        crashHash?.let { put("crash_hash", it) }
        if (fpsBy5Min.isNotEmpty()) put("fps_by_5min", JSONArray(fpsBy5Min.take(MAX_REPORT_WINDOWS)))
        if (gpuBy5Min.isNotEmpty()) put("gpu_by_5min", JSONArray(gpuBy5Min.take(MAX_REPORT_WINDOWS)))
        if (gpuClockBy5Min.isNotEmpty()) put("gpu_clock_by_5min", JSONArray(gpuClockBy5Min.take(MAX_REPORT_WINDOWS)))
        basis?.let { put("basis", it.toJson()) }
        putLoad(report = true)
    }

    fun toVerdictJson(): JSONObject? {
        val c = cls ?: return null
        return JSONObject().apply {
            put("class", c)
            put("confidence", confidence ?: 0.0)
            put("symptom", symptom ?: JSONObject.NULL)
            put("steady_fps", steadyFps ?: JSONObject.NULL)
            put("stutter_share", stutterShare ?: JSONObject.NULL)
            put("throttle_onset_s", throttleOnsetSec ?: JSONObject.NULL)
            put("gameplay_s", gameplaySec)
            putLoad(report = true)
            if (gpuClockBy5Min.isNotEmpty()) put("gpu_clock_by_5min", JSONArray(gpuClockBy5Min.take(MAX_REPORT_WINDOWS)))
            if (fpsBy5Min.isNotEmpty()) put("fps_by_5min", JSONArray(fpsBy5Min.take(MAX_REPORT_WINDOWS)))
            basis?.let { put("basis", it.toJson()) }
        }
    }

    companion object {
        /** The server accepts at most this many windows per series in an outcome. */
        const val MAX_REPORT_WINDOWS = 48

        fun fromJson(o: JSONObject?): Metrics? {
            if (o == null || !o.has("gameplay_s")) return null
            return Metrics(
                gameplaySec = o.optInt("gameplay_s", 0),
                steadyFps = o.optDoubleOrNull("steady_fps"),
                stutterShare = o.optDoubleOrNull("stutter_share"),
                throttleOnsetSec = o.optIntOrNull("throttle_onset_s"),
                cls = o.optStringOrNull("class"),
                confidence = o.optDoubleOrNull("confidence"),
                confident = if (o.has("confident") && !o.isNull("confident")) o.optBoolean("confident") else null,
                symptom = o.optStringOrNull("symptom"),
                exitReason = o.optStringOrNull("exit_reason"),
                windowSeen = o.optBoolean("window_seen", true),
                procStatReal = if (o.has("proc_stat_real")) o.optBoolean("proc_stat_real") else null,
                thermalReporting = if (o.has("thermal_reporting")) o.optBoolean("thermal_reporting") else null,
                fpsBy5Min = o.optJSONArray("fps_by_5min").ints(),
                gpuBy5Min = o.optJSONArray("gpu_by_5min").ints(),
                gpuClockBy5Min = o.optJSONArray("gpu_clock_by_5min").ints(),
                cpuTempMax = o.optIntOrNull("cpu_temp_max"),
                gpuTempMax = o.optIntOrNull("gpu_temp_max"),
                thermalPeak = o.optIntOrNull("thermal_peak"),
                gpuBusyP95 = o.optIntOrNull("gpu_busy_p95"),
                hotThreadP95 = o.optIntOrNull("hot_thread_p95"),
                fpsCeilingReason = o.optStringOrNull("fps_ceiling_reason"),
                gpuWorkPerFrame = o.optDoubleOrNull("gpu_work_per_frame")?.takeIf { it > 0 },
                gpuWorkBy5Min = o.optJSONArray("gpu_work_by_5min").works(),
                fpsP10 = o.optDoubleOrNull("fps_p10"),
                frameTimeP50Ms = o.optDoubleOrNull("frame_time_p50_ms"),
                frameTimeP99Ms = o.optDoubleOrNull("frame_time_p99_ms"),
                refreshHz = o.optDoubleOrNull("refresh_hz")?.takeIf { it > 0 },
                crashKind = o.optStringOrNull("crash_kind"),
                crashCode = o.optStringOrNull("crash_code"),
                crashHash = o.optStringOrNull("crash_hash"),
                hotThreadCoreShare = coreShareOf(o.optJSONObject("hot_thread_core_share")),
                pipelineCompilesBy5Min = o.optJSONArray("pipeline_compiles_by_5min").counts(),
                tessellationPipelines = o.optBooleanOrNull("tessellation_pipelines"),
                majorFaultsBy5Min = o.optJSONArray("major_faults_by_5min").counts(),
                memReclaimBy5Min = o.optJSONArray("mem_reclaim_by_5min").counts(),
                framesStoppedSec = o.optIntOrNull("frames_stopped_s"),
                mainWindowMapped = o.optBooleanOrNull("main_window_mapped"),
                basis = VerdictBasis.fromJson(o.optJSONObject("basis")),
            )
        }

        /** Reads a SessionReport run record (the game_exited properties). */
        fun fromRun(run: JSONObject): Metrics {
            val verdict = run.optJSONObject("perf_verdict")
            val gameplay = verdict?.optIntOrNull("gameplay_s") ?: run.optInt("session_length", 0)
            val perf = run.optJSONArray("perf_by_5min").objects()
            val fps = run.optJSONArray("fps_by_5min").ints()
            val work = verdict?.optJSONArray("gpu_work_by_5min")?.works()?.takeIf { it.isNotEmpty() } ?: workFromPerf(perf, fps)
            val fingerprint = run.optJSONObject("crash_fingerprint")
            return Metrics(
                gameplaySec = gameplay,
                steadyFps = run.optDoubleOrNull("avg_fps")?.takeIf { it > 0.0 },
                stutterShare = run.optDoubleOrNull("stutter_share"),
                throttleOnsetSec = run.optIntOrNull("throttle_onset_s"),
                cls = verdict?.optStringOrNull("class"),
                confidence = verdict?.optDoubleOrNull("confidence"),
                confident = verdict?.takeIf { it.has("confident") && !it.isNull("confident") }?.optBoolean("confident"),
                symptom = verdict?.optStringOrNull("symptom"),
                exitReason = run.optStringOrNull("exit_reason"),
                windowSeen = run.has("main_window_class"),
                procStatReal = verdict?.optStringOrNull("proc_stat")?.let { it == "real" },
                thermalReporting = run.has("thermal_headroom") || run.optInt("thermal_peak", 0) > 0,
                fpsBy5Min = run.optJSONArray("fps_by_5min").ints(),
                gpuBy5Min = perf.mapNotNull { it.optIntOrNull("gpu_avg") },
                gpuClockBy5Min = perf.mapNotNull { it.optIntOrNull("gpu_clk_mhz_avg") },
                cpuTempMax = perf.mapNotNull { it.optIntOrNull("cpu_temp_max")?.takeIf { t -> t > 0 } }.maxOrNull(),
                gpuTempMax = perf.mapNotNull { it.optIntOrNull("gpu_temp_max")?.takeIf { t -> t > 0 } }.maxOrNull(),
                thermalPeak = run.optIntOrNull("thermal_peak") ?: perf.mapNotNull { it.optIntOrNull("thermal_status_max") }.maxOrNull(),
                gpuBusyP95 = verdict?.optIntOrNull("gpu_busy_p95"),
                hotThreadP95 = verdict?.optIntOrNull("hot_thread_p95"),
                fpsCeilingReason = verdict?.optStringOrNull("fps_ceiling_reason"),
                gpuWorkPerFrame = verdict?.optDoubleOrNull("gpu_work_per_frame")?.takeIf { it > 0 },
                gpuWorkBy5Min = work,
                fpsP10 = verdict?.optDoubleOrNull("fps_p10"),
                frameTimeP50Ms = run.optDoubleOrNull("frame_p50_ms")?.takeIf { it > 0 },
                frameTimeP99Ms = run.optDoubleOrNull("frame_p99_ms")?.takeIf { it > 0 },
                refreshHz = (verdict?.optDoubleOrNull("refresh_hz") ?: run.optDoubleOrNull("display_refresh_hz"))?.takeIf { it > 0 },
                crashKind = fingerprint?.optStringOrNull("kind"),
                crashCode = fingerprint?.optStringOrNull("code"),
                crashHash = run.optStringOrNull("crash_fingerprint_hash") ?: fingerprint?.optStringOrNull("hash"),
                hotThreadCoreShare = coreShareOf(verdict?.optJSONObject("hot_thread_core_share")),
                pipelineCompilesBy5Min = verdict?.optJSONArray("pipeline_compiles_by_5min").counts(),
                tessellationPipelines = verdict?.optBooleanOrNull("tessellation_pipelines"),
                majorFaultsBy5Min = verdict?.optJSONArray("major_faults_by_5min").counts(),
                memReclaimBy5Min = verdict?.optJSONArray("mem_reclaim_by_5min").counts(),
                framesStoppedSec = run.optIntOrNull("frames_stopped_s"),
                mainWindowMapped = run.optBooleanOrNull("main_window_mapped"),
                basis = VerdictBasis.fromRun(run),
            )
        }

        /** hot_thread_core_share as sent: little, big and prime, each 0-1; null when absent or empty. */
        internal fun coreShareOf(o: JSONObject?): Map<String, Double>? {
            if (o == null) return null
            val share = listOf("little", "big", "prime").mapNotNull { k -> o.optDoubleOrNull(k)?.takeIf { it in 0.0..1.0 }?.let { k to it } }.toMap()
            return share.takeIf { it.isNotEmpty() }
        }

        /**
         * GPU work per frame of each window for runs from before the sampler sent it: perf_by_5min gpu_avg x
         * gpu_clk_mhz_avg / fps_by_5min, only when the two series line up.
         */
        fun workFromPerf(perf: List<JSONObject>, fps: List<Int>): List<Double?> {
            if (perf.isEmpty() || perf.size != fps.size) return emptyList()
            val work = perf.indices.map { i ->
                SessionVerdict.gpuWorkPerFrame(perf[i].optDoubleOrNull("gpu_avg"), perf[i].optDoubleOrNull("gpu_clk_mhz_avg"), fps[i].toDouble())
            }
            return if (work.all { it == null }) emptyList() else work
        }

        private fun round2(v: Double): Double = Math.round(v * 100) / 100.0

        private fun medianOf(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }
    }
}

/**
 * What the session's class was decided from: the per-class shares of gameplay seconds, the low-memory trim events,
 * the lowest available RAM over gameplay, whether those two made it a memory verdict on their own, the thermal signal,
 * whether CPU load came from /proc/stat, the GPU busy and hot-thread p95s, and the hot thread's name and cluster.
 */
data class VerdictBasis(
    val shares: Map<String, Double>? = null,
    val trimEvents: Int? = null,
    val availMinMb: Int? = null,
    val memoryHard: Boolean? = null,
    val thermalSignal: String? = null,
    val procStat: String? = null,
    val gpuBusyP95: Int? = null,
    val hotThreadP95: Int? = null,
    val hotThreadName: String? = null,
    val hotThreadCluster: String? = null,
) {
    fun isEmpty(): Boolean = this == VerdictBasis()

    fun toJson(): JSONObject = JSONObject().apply {
        shares?.let { m -> put("shares", JSONObject().apply { m.forEach { (k, v) -> put(k, Math.round(v * 100) / 100.0) } }) }
        trimEvents?.let { put("trim_events", it) }
        availMinMb?.let { put("avail_min_mb", it) }
        memoryHard?.let { put("memory_hard", it) }
        thermalSignal?.let { put("thermal_signal", it) }
        procStat?.let { put("proc_stat", it) }
        gpuBusyP95?.let { put("gpu_busy_p95", it) }
        hotThreadP95?.let { put("hot_thread_p95", it) }
        hotThreadName?.let { put("hot_thread_name", it) }
        hotThreadCluster?.let { put("hot_thread_cluster", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?): VerdictBasis? {
            if (o == null) return null
            return VerdictBasis(
                shares = sharesOf(o.optJSONObject("shares")),
                trimEvents = o.optIntOrNull("trim_events"),
                availMinMb = o.optIntOrNull("avail_min_mb"),
                memoryHard = o.optBooleanOrNull("memory_hard"),
                thermalSignal = o.optStringOrNull("thermal_signal"),
                procStat = o.optStringOrNull("proc_stat"),
                gpuBusyP95 = o.optIntOrNull("gpu_busy_p95"),
                hotThreadP95 = o.optIntOrNull("hot_thread_p95"),
                hotThreadName = o.optStringOrNull("hot_thread_name"),
                hotThreadCluster = o.optStringOrNull("hot_thread_cluster"),
            ).takeIf { !it.isEmpty() }
        }

        /** From a game_exited run record: perf_verdict, with the run's low_memory_events for records without trim_events. */
        fun fromRun(run: JSONObject): VerdictBasis? {
            val verdict = run.optJSONObject("perf_verdict")
            val hot = verdict?.optJSONObject("hot")
            return VerdictBasis(
                shares = sharesOf(verdict?.optJSONObject("shares")),
                trimEvents = verdict?.optIntOrNull("trim_events") ?: run.optIntOrNull("low_memory_events"),
                availMinMb = verdict?.optIntOrNull("avail_min_mb"),
                memoryHard = verdict?.optBooleanOrNull("memory_hard"),
                thermalSignal = verdict?.optStringOrNull("thermal_signal"),
                procStat = verdict?.optStringOrNull("proc_stat"),
                gpuBusyP95 = verdict?.optIntOrNull("gpu_busy_p95"),
                hotThreadP95 = verdict?.optIntOrNull("hot_thread_p95"),
                hotThreadName = hot?.optStringOrNull("name"),
                hotThreadCluster = hot?.optStringOrNull("cluster"),
            ).takeIf { !it.isEmpty() }
        }

        private fun sharesOf(o: JSONObject?): Map<String, Double>? {
            if (o == null) return null
            val shares = linkedMapOf<String, Double>()
            o.keys().forEach { k -> o.optDoubleOrNull(k)?.let { shares[k] = it } }
            return shares.takeIf { it.isNotEmpty() }
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toInt() ?: optString(key).toIntOrNull()

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toLong() ?: optString(key).toLongOrNull()

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) {
        null
    } else {
        ((opt(key) as? Number)?.toDouble() ?: optString(key).toDoubleOrNull())?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.optBooleanOrNull(key: String): Boolean? = when (val v = if (has(key)) opt(key) else null) {
    is Boolean -> v
    is String -> v.toBooleanStrictOrNull()
    else -> null
}

/** A count per window: numbers kept, negative or non-numeric entries read as 0 so the windows stay aligned. */
internal fun JSONArray?.counts(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map { i -> (opt(i) as? Number)?.toInt()?.coerceAtLeast(0) ?: 0 }

internal fun JSONArray?.ints(): List<Int> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> (opt(i) as? Number)?.toInt() }

/** A per-window series where null or a negative entry is a window that was not measured. */
internal fun JSONArray?.works(): List<Double?> =
    if (this == null) {
        emptyList()
    } else {
        (0 until length()).map { i -> (opt(i) as? Number)?.toDouble()?.takeIf { it >= 0 && !it.isNaN() && !it.isInfinite() } }
    }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotEmpty() } }

/**
 * [ladder] is the key's values from lowest to highest quality and [lowerIs] which way lower quality goes; [onlyExisting]
 * ops are skipped when the file does not have the key. [down] is how many levels of [ladder] below the key's current
 * value the op goes (negative: back up; 0: nowhere); null from servers that send only [value].
 */
data class FileOp(
    val op: String,
    val section: String,
    val key: String,
    val value: String?,
    val ladder: List<String?>? = null,
    val lowerIs: String? = null,
    val onlyExisting: Boolean = false,
    val down: Int? = null,
) {
    val isDelete: Boolean get() = op == "delete"

    fun toJson(): JSONObject = JSONObject().apply {
        put("op", op)
        put("section", section)
        put("key", key)
        put("value", value ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): FileOp? {
            val op = o.optString("op")
            val key = o.optStringOrNull("key") ?: return null
            if (op != "set" && op != "delete") return null
            val value = o.optStringOrNull("value") ?: if (o.has("value") && !o.isNull("value")) "" else null
            if (op == "set" && value == null) return null
            return FileOp(
                op, o.optStringOrNull("section").orEmpty(), key, if (op == "set") value else null,
                ladderOf(o), o.optStringOrNull("lower_is"), o.optBoolean("only_existing", false), o.optIntOrNull("down"),
            )
        }
    }
}

/** A key's quality ladder as the server sends it: step 0 is the lowest quality, null where a step has no value. */
internal fun ladderOf(o: JSONObject): List<String?>? {
    val a = o.optJSONArray("ladder") ?: return null
    return (0 until a.length()).map { i -> if (a.isNull(i)) null else a.opt(i).toString() }
}

data class RegistryValue(
    val name: String,
    val type: String?,
    val data: String?,
    val delete: Boolean,
    val ladder: List<String?>? = null,
    val lowerIs: String? = null,
    /** As [FileOp.down]. */
    val down: Int? = null,
)

sealed class Lever {
    abstract val type: String

    data class EngineFile(
        val path: String,
        val format: String,
        val encoding: String,
        val createIfMissing: Boolean,
        val ops: List<FileOp>,
        /** The file's write bits are cleared after the write, so the game cannot save over the changed keys. */
        val readOnly: Boolean = false,
    ) : Lever() {
        override val type: String get() = TYPE_ENGINE_FILE
    }

    data class Registry(val hive: String, val key: String, val values: List<RegistryValue>) : Lever() {
        override val type: String get() = TYPE_REGISTRY
    }

    data class ContainerSet(val set: List<Pair<String, String>>) : Lever() {
        override val type: String get() = TYPE_CONTAINER
    }

    /** Mesa driconf options; [executable] is an exe file name, or null for every process. */
    data class Drirc(val options: List<Pair<String, String>>, val executable: String?) : Lever() {
        override val type: String get() = TYPE_DRIRC
    }

    data class Unknown(override val type: String) : Lever()

    companion object {
        const val TYPE_ENGINE_FILE = "engine_file"
        const val TYPE_REGISTRY = "registry"
        const val TYPE_CONTAINER = "container"
        const val TYPE_DRIRC = "drirc"

        private val DRIRC_OPTION = Regex("^[A-Za-z0-9_]{1,64}$")
        private val CONTROL = Regex("[\\x00-\\x1f\\x7f]")

        private fun plainText(s: String, max: Int): Boolean = s.length <= max && !CONTROL.containsMatchIn(s)

        fun parse(o: JSONObject): Lever {
            val type = o.optString("lever_type")
            val payload = o.optJSONObject("payload") ?: return Unknown(type)
            return when (type) {
                TYPE_ENGINE_FILE -> {
                    val path = payload.optStringOrNull("path") ?: return Unknown(type)
                    val opsArray = payload.optJSONArray("ops").objects()
                    val ops = opsArray.mapNotNull { FileOp.fromJson(it) }
                    if (ops.isEmpty() || ops.size != opsArray.size) return Unknown(type)
                    EngineFile(
                        path = path,
                        format = payload.optString("format"),
                        encoding = payload.optStringOrNull("encoding") ?: "default",
                        createIfMissing = payload.optBoolean("create_if_missing", false),
                        ops = ops,
                        readOnly = payload.optBoolean("read_only", false),
                    )
                }
                TYPE_REGISTRY -> {
                    val key = payload.optStringOrNull("key") ?: return Unknown(type)
                    val raw = payload.optJSONArray("values").objects()
                    val values = raw.mapNotNull { v ->
                        val name = if (v.has("name") && !v.isNull("name")) v.optString("name") else return@mapNotNull null
                        val delete = v.optBoolean("delete", false)
                        val valueType = v.optStringOrNull("type")
                        val data = if (v.has("data") && !v.isNull("data")) v.optString("data") else null
                        if (!delete && (valueType == null || data == null)) return@mapNotNull null
                        RegistryValue(name, valueType, data, delete, ladderOf(v), v.optStringOrNull("lower_is"), v.optIntOrNull("down"))
                    }
                    if (values.isEmpty() || values.size != raw.size) return Unknown(type)
                    Registry(payload.optString("hive", "HKCU"), key, values)
                }
                TYPE_CONTAINER -> {
                    val raw = payload.optJSONArray("set").objects()
                    val set = raw.mapNotNull { s ->
                        val key = s.optStringOrNull("key") ?: return@mapNotNull null
                        if (!s.has("value") || s.isNull("value")) return@mapNotNull null
                        key to s.optString("value")
                    }
                    if (set.isEmpty() || set.size != raw.size) return Unknown(type)
                    ContainerSet(set)
                }
                TYPE_DRIRC -> {
                    val raw = payload.optJSONObject("options") ?: return Unknown(type)
                    val options = raw.keys().asSequence().toList().map { name ->
                        if (!DRIRC_OPTION.matches(name) || raw.isNull(name)) return Unknown(type)
                        val value = raw.optString(name)
                        if (!plainText(value, 256)) return Unknown(type)
                        name to value
                    }
                    if (options.isEmpty()) return Unknown(type)
                    val executable = payload.optStringOrNull("executable")
                    if (executable != null && (!plainText(executable, 128) || executable.contains('/') || executable.contains('\\'))) {
                        return Unknown(type)
                    }
                    Drirc(options, executable)
                }
                else -> Unknown(type)
            }
        }
    }
}

class Rung(
    val raw: JSONObject,
    val rungId: String,
    val version: String,
    val profile: String,
    val cls: String?,
    val leverClasses: List<String>,
    val order: Int,
    val remaining: Int,
    val isFpsCap: Boolean,
    val outcome: String?,
    val visualCostId: String?,
    val visualCostEn: String?,
    val levers: List<Lever>,
    val expiresAtMillis: Long?,
    /** Each file and registry value only moves toward lower quality from what the game has now; never on a [recovery] rung. */
    val relative: Boolean = false,
    /** The server rules build the rung came from. */
    val rulesVersion: String? = null,
    /** A cap rung the user has to say yes to first; null when the server does not say. */
    val askFirst: Boolean? = null,
    /** A step that raises quality: its values are written as sent. It is judged, kept and taken off like any other step. */
    val recovery: Boolean = false,
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis != null && nowMillis > expiresAtMillis

    companion object {
        fun parse(o: JSONObject?): Rung? {
            if (o == null) return null
            val id = o.optStringOrNull("rung_id") ?: return null
            return Rung(
                raw = o,
                rungId = id,
                version = o.optStringOrNull("version").orEmpty(),
                profile = o.optString("profile"),
                cls = o.optStringOrNull("class"),
                leverClasses = o.optJSONArray("lever_classes").strings(),
                order = o.optInt("order", 0),
                remaining = o.optInt("remaining", 0),
                isFpsCap = o.optBoolean("is_fps_cap", false),
                outcome = o.optStringOrNull("outcome"),
                visualCostId = o.optStringOrNull("visual_cost_id"),
                visualCostEn = o.optStringOrNull("visual_cost_en"),
                levers = o.optJSONArray("levers").objects().map { Lever.parse(it) },
                expiresAtMillis = o.optStringOrNull("expires_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                relative = o.optBoolean("relative", false) && !o.optBoolean("recovery", false),
                rulesVersion = o.optStringOrNull("rules_version"),
                askFirst = if (o.has("ask_first") && !o.isNull("ask_first")) o.optBoolean("ask_first") else null,
                recovery = o.optBoolean("recovery", false),
            )
        }
    }
}

/**
 * What the account may optimize. [tier], [gamesUsed], [gamesQuota] and [periodResetsAt] come from newer servers;
 * [quotaUnlimited] is a games_quota sent as null. Older answers only carry the trial fields.
 */
data class Entitlement(
    val state: String,
    val trialGamesUsed: Int,
    val trialGamesLimit: Int,
    val tier: String? = null,
    val gamesUsed: Int? = null,
    val gamesQuota: Int? = null,
    val quotaUnlimited: Boolean = false,
    val periodResetsAt: Long? = null,
) {
    val isMember: Boolean get() = state == MEMBER
    val trialGamesLeft: Int get() = (trialGamesLimit - trialGamesUsed).coerceAtLeast(0)

    /** The plan the counts belong to, from the tier when sent, otherwise from the quota and the member flag. */
    val plan: Plan get() {
        val t = tier?.lowercase()
        return when {
            quotaUnlimited || t in UNLIMITED_TIERS -> Plan.UNLIMITED
            t in MONTHLY_TIERS -> Plan.MONTHLY
            t in FREE_TIERS -> Plan.FREE
            isMember -> if (gamesQuota != null) Plan.MONTHLY else Plan.UNLIMITED
            else -> Plan.FREE
        }
    }

    /** Games counted against the plan's quota, the new count when sent. */
    val used: Int get() = (gamesUsed ?: trialGamesUsed).coerceAtLeast(0)

    /** The plan's quota: the new field when sent, otherwise the trial limit or the default. */
    val quota: Int get() = gamesQuota?.takeIf { it > 0 } ?: limitOrDefault()

    enum class Plan { FREE, MONTHLY, UNLIMITED }

    fun toJson(): JSONObject = JSONObject().apply {
        put("state", state)
        put("trial_games_used", trialGamesUsed)
        put("trial_games_limit", trialGamesLimit)
        tier?.let { put("tier", it) }
        gamesUsed?.let { put("games_used", it) }
        when {
            quotaUnlimited -> put("games_quota", JSONObject.NULL)
            gamesQuota != null -> put("games_quota", gamesQuota)
        }
        periodResetsAt?.let { put("period_resets_at", it) }
    }

    companion object {
        const val MEMBER = "member"
        const val TRIAL = "trial"
        const val NONE = "none"
        const val DEFAULT_TRIAL_GAMES_LIMIT = 3
        const val DEFAULT_MONTHLY_GAMES = 3

        private val UNLIMITED_TIERS = setOf("unlimited", "optimize_unlimited", "pro", "patron")
        private val MONTHLY_TIERS = setOf("monthly", "optimize_basic", "basic")
        private val FREE_TIERS = setOf("free", "trial", "none")

        fun fromJson(o: JSONObject?): Entitlement? {
            if (o == null) return null
            val tier = o.optStringOrNull("tier")
            val state = o.optStringOrNull("state")
                ?: tier?.let { if (it.lowercase() in FREE_TIERS) TRIAL else MEMBER }
                ?: return null
            return Entitlement(
                state = state,
                trialGamesUsed = o.optIntOrNull("trial_games_used") ?: 0,
                trialGamesLimit = o.optIntOrNull("trial_games_limit") ?: 0,
                tier = tier,
                gamesUsed = o.optIntOrNull("games_used"),
                gamesQuota = o.optIntOrNull("games_quota"),
                quotaUnlimited = o.has("games_quota") && o.isNull("games_quota"),
                periodResetsAt = resetsAt(o),
            )
        }

        /** period_resets_at as epoch milliseconds: an ISO-8601 time, or a number in seconds or milliseconds. */
        private fun resetsAt(o: JSONObject): Long? {
            if (!o.has("period_resets_at") || o.isNull("period_resets_at")) return null
            val raw = o.opt("period_resets_at")
            val number = (raw as? Number)?.toLong() ?: raw.toString().toLongOrNull()
            if (number != null) return if (number < 100_000_000_000L) number * 1000L else number
            return try {
                Instant.parse(raw.toString()).toEpochMilli()
            } catch (e: Exception) {
                try {
                    java.time.OffsetDateTime.parse(raw.toString()).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    try {
                        java.time.LocalDate.parse(raw.toString()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }
}

/**
 * The entitlement a surface shows for a game: the newer of its own, received at [ownAt], and the app-wide one, received
 * at [appWideAt]; its own on a tie. Either is ignored when received before [accountChangedAt]. A time of 0 is oldest.
 */
fun Entitlement?.orAppWide(ownAt: Long, appWide: Entitlement?, appWideAt: Long, accountChangedAt: Long): Entitlement? {
    val own = this?.takeIf { !predatesAccountChange(ownAt, accountChangedAt) }
    val wide = appWide?.takeIf { !predatesAccountChange(appWideAt, accountChangedAt) }
    return when {
        own == null -> wide
        wide == null -> own
        appWideAt > ownAt -> wide
        else -> own
    }
}

/** Whether something learned at [atMillis] belongs to the account as it was before the change at [accountChangedAt]. */
fun predatesAccountChange(atMillis: Long, accountChangedAt: Long): Boolean = accountChangedAt > 0L && atMillis < accountChangedAt

fun Entitlement?.limitOrDefault(): Int = this?.trialGamesLimit?.takeIf { it > 0 } ?: Entitlement.DEFAULT_TRIAL_GAMES_LIMIT

/**
 * The server's own reading of a session (next-rung verdict_server), computed from the raw metrics block. When an
 * answer carries one it decides the first card; the app's local verdict is the offline fallback.
 */
data class ServerVerdict(
    val cls: String?,
    val confidence: Double?,
    val fpsCeilingReason: String?,
    /** Whether the session should leave a first card; null when the server did not say, which leaves it to the app. */
    val showCard: Boolean?,
    val cardKind: String?,
    val suggestedProfile: OptimizeProfile?,
    val blockReason: String?,
    /** samples: read from the per-second records the request sent; client: the metrics block's own class. */
    val source: String? = null,
) {
    companion object {
        /** The server left the card to the app. */
        const val BLOCK_NO_SAMPLES = "no_samples"

        fun parse(o: JSONObject?): ServerVerdict? {
            if (o == null) return null
            return ServerVerdict(
                cls = o.optStringOrNull("class"),
                confidence = o.optDoubleOrNull("confidence")?.coerceIn(0.0, 1.0),
                fpsCeilingReason = o.optStringOrNull("fps_ceiling_reason"),
                showCard = o.optBooleanOrNull("show_card"),
                cardKind = o.optStringOrNull("card_kind"),
                suggestedProfile = OptimizeProfile.fromWire(o.optStringOrNull("suggested_profile")).takeIf { it != OptimizeProfile.OFF },
                blockReason = o.optStringOrNull("block_reason"),
                source = o.optStringOrNull("source"),
            )
        }
    }
}

/** The server's judgement of a step's session (outcome judgement_server); the local judge is the fallback. */
data class ServerJudgement(val result: String, val reason: String?, val afterCardKind: String?, val deltas: JSONObject?) {
    companion object {
        private val RESULTS = setOf(
            OptimizeJudge.HELPED, OptimizeJudge.NO_CHANGE, OptimizeJudge.WORSE, OptimizeJudge.CRASH, OptimizeJudge.NOT_COMPARABLE,
        )

        fun parse(o: JSONObject?): ServerJudgement? {
            if (o == null) return null
            val result = o.optStringOrNull("result")?.takeIf { it in RESULTS } ?: return null
            return ServerJudgement(result, o.optStringOrNull("reason"), o.optStringOrNull("after_card_kind"), o.optJSONObject("deltas"))
        }
    }
}

/** The outcome endpoint's answer: acknowledged, the entitlement after the outcome was stored, and the server's judgement. */
data class OutcomeAck(val ok: Boolean, val entitlement: Entitlement?, val judgement: ServerJudgement? = null) {
    companion object {
        fun parse(o: JSONObject?): OutcomeAck = OutcomeAck(
            o?.optBoolean("ok", false) == true,
            Entitlement.fromJson(o?.optJSONObject("entitlement")),
            runCatching { ServerJudgement.parse(o?.optJSONObject("judgement_server")) }.getOrNull(),
        )
    }
}

data class NextRungResponse(
    val status: String,
    val messageId: String?,
    val rung: Rung?,
    val entitlement: Entitlement? = null,
    val rulesVersion: String? = null,
    /** Seconds before the game is worth asking about again. */
    val retryAfterSec: Int? = null,
    /** The server's verdict of the metrics block; null from older servers. */
    val verdictServer: ServerVerdict? = null,
    /** The frame rate the server expects this game to hold on this device once tuned; null when it has none. */
    val expected: OptimizeEvidence.Expected? = null,
) {
    /** The rules build that answered: the top-level value, else the rung's. */
    val effectiveRulesVersion: String? get() = rulesVersion ?: rung?.rulesVersion

    /** The server has nothing for the game yet. */
    val hasNothing: Boolean get() = status == STATUS_NO_RULE || status == STATUS_NOT_SETTINGS

    companion object {
        const val STATUS_RUNG = "rung"
        const val STATUS_EXHAUSTED = "exhausted"
        const val STATUS_ENTITLEMENT_REQUIRED = "entitlement_required"
        const val STATUS_NO_RULE = "no_rule"
        const val STATUS_NOT_SETTINGS = "not_settings"

        /** Client-side only: an unasked cap rung raised the cap question, so the ladder waits for the answer. */
        const val STATUS_CAP_QUESTION = "cap_question"

        fun parse(o: JSONObject): NextRungResponse = NextRungResponse(
            o.optString("status"),
            o.optStringOrNull("message_id"),
            Rung.parse(o.optJSONObject("rung")),
            Entitlement.fromJson(o.optJSONObject("entitlement")),
            o.optStringOrNull("rules_version"),
            o.optIntOrNull("retry_after_s")?.takeIf { it >= 0 },
            runCatching { ServerVerdict.parse(o.optJSONObject("verdict_server")) }.getOrNull(),
            OptimizeEvidence.Expected.parse(o.optJSONObject("expected")),
        )
    }
}
