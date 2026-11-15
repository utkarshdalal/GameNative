package app.gamenative.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * The per-second records of the session that just ended and the inputs its verdict was computed with, kept until the
 * next session starts so the exit requests can send them for the server's own verdict. [buffer] stays bounded by its
 * capacity; the records are only read back when [putInto] serializes them.
 */
internal class SessionSeries(
    private val buffer: SessionSampleBuffer,
    /** perf_verdict.gameplay_s of the session, which tells this session's metrics apart from an older run's. */
    val gameplaySec: Int,
    val clusterCpuinfoMaxMhz: IntArray,
    val gpuTopMhz: Int?,
    val trimEvents: Int,
    val designCapDetected: Boolean,
    val legacyShare: Map<String, Double>?,
) {
    /** metrics.samples and the verdict inputs that go next to it, added to a next-rung metrics block. */
    fun putInto(metrics: JSONObject) {
        metrics.put("samples", toJson(buffer.toList(), buffer.keepEvery))
        if (clusterCpuinfoMaxMhz.isNotEmpty()) metrics.put("cluster_cpuinfo_max_mhz", JSONArray().apply { clusterCpuinfoMaxMhz.forEach { put(it) } })
        gpuTopMhz?.let { metrics.put("gpu_top_mhz", it) }
        metrics.put("trim_events", trimEvents)
        metrics.put("design_cap_detected", designCapDetected)
        legacyShare?.takeIf { it.isNotEmpty() }?.let { share ->
            metrics.put("legacy_share", JSONObject().apply { share.forEach { (k, v) -> if (v.isFinite()) put(k, Math.round(v * 100) / 100.0) } })
        }
    }

    companion object {
        /** The server reads at most this many records. */
        const val MAX_SAMPLES = 4000

        /**
         * The records as metrics.samples: one array per field, index i is record i. Longer than [max], one record in
         * every N is kept and interval_sec grows by N, so gameplay seconds still add up. A field no record has is left
         * out; a record without it is null.
         */
        fun toJson(all: List<SessionSample>, intervalSec: Int, max: Int = MAX_SAMPLES): JSONObject {
            val every = if (all.size > max) (all.size + max - 1) / max else 1
            val s = if (every == 1) all else all.filterIndexed { i, _ -> i % every == 0 }
            return JSONObject().apply {
                put("interval_sec", intervalSec.coerceAtLeast(1) * every)
                put("t", JSONArray().apply { s.forEach { put(it.t) } })
                put("fps", JSONArray().apply { s.forEach { put(Math.round(it.fps * 10.0) / 10.0) } })
                fun counts(key: String, value: (SessionSample) -> Int) = put(key, JSONArray().apply { s.forEach { put(value(it)) } })
                fun column(key: String, value: (SessionSample) -> Int?) {
                    if (s.none { value(it) != null }) return
                    put(key, JSONArray().apply { s.forEach { put(value(it) ?: JSONObject.NULL) } })
                }
                counts("frames") { it.frames }
                counts("stutter_frames") { it.stutterFrames }
                counts("refresh_multiple_frames") { it.refreshMultipleFrames }
                column("gpu_busy") { it.gpuBusy }
                column("gpu_mhz") { it.gpuMhz }
                column("gpu_max_mhz") { it.gpuMaxMhz }
                column("cpu_total") { it.cpuTotal }
                column("core_max") { it.coreMax }
                if (s.any { it.clusterMaxMhz != null }) {
                    put(
                        "cluster_max_mhz",
                        JSONArray().apply {
                            s.forEach { r -> put(r.clusterMaxMhz?.let { m -> JSONArray().apply { m.forEach { put(it) } } } ?: JSONObject.NULL) }
                        },
                    )
                }
                column("thermal_status") { it.thermalStatus }
                column("cpu_temp_c") { it.cpuTempC }
                column("skin_temp_c") { it.skinTempC }
                column("hot_pct") { it.hotPct }
                column("hot_user_ticks") { it.hotUserTicks }
                column("hot_sys_ticks") { it.hotSysTicks }
                if (s.any { it.hotState != null }) put("hot_state", String(CharArray(s.size) { s[it].hotState ?: '-' }))
                column("hot_core") { it.hotCore }
                column("hot_vol_ps") { it.hotVolPerSec }
                column("hot_invol_ps") { it.hotInvolPerSec }
                column("wineserver_pct") { it.wineserverPct }
                column("avail_mb") { it.availMb }
                counts("flags") { (if (it.lowMemory) 1 else 0) or (if (it.trimEvent) 2 else 0) or (if (it.capActive) 4 else 0) }
            }
        }
    }
}
