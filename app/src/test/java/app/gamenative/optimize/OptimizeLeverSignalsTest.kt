package app.gamenative.optimize

import app.gamenative.powercontrol.metrics.CpuClusterKind
import app.gamenative.utils.SessionSample
import app.gamenative.utils.SessionVerdict
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeLeverSignalsTest {

    @Test
    fun `gpu work scaling is the work drop over the pixel drop`() {
        // 1920x1080 -> 1280x720 takes 55.6 % of the pixels off; work 10 -> 5 is a 50 % drop.
        val px1080 = OptimizeJudge.screenPixels("1920x1080")!!
        val px720 = OptimizeJudge.screenPixels("1280x720")!!
        assertEquals(0.9, OptimizeJudge.gpuWorkScaling(10.0, 5.0, px1080, px720)!!, 1e-3)
        // Work that did not move reads 0; work that rose reads negative, clamped at -1.
        assertEquals(0.0, OptimizeJudge.gpuWorkScaling(10.0, 10.0, px1080, px720)!!, 1e-9)
        assertEquals(-1.0, OptimizeJudge.gpuWorkScaling(10.0, 30.0, px1080, px720)!!, 1e-9)
        // A tiny pixel change with a large work drop is clamped at 2.
        assertEquals(2.0, OptimizeJudge.gpuWorkScaling(10.0, 1.0, 1000L, 990L)!!, 1e-9)
        assertNull(OptimizeJudge.gpuWorkScaling(10.0, 5.0, px720, px720))
        assertNull(OptimizeJudge.gpuWorkScaling(null, 5.0, px1080, px720))
        assertNull(OptimizeJudge.screenPixels("native"))
    }

    private fun resolutionRung(size: String): Rung = Rung.parse(
        JSONObject()
            .put("rung_id", "res_1")
            .put(
                "levers",
                JSONArray().put(
                    JSONObject().put("lever_type", "container")
                        .put("payload", JSONObject().put("set", JSONArray().put(JSONObject().put("key", "screenSize").put("value", size)))),
                ),
            ),
    )!!

    private fun record(before: String): JSONObject = JSONObject()
        .put("type", "container")
        .put("index", 0)
        .put("before", JSONArray().put(JSONObject().put("key", "screenSize").put("value", before)))

    @Test
    fun `scaling is only computed for a resolution step`() {
        val before = Metrics(gameplaySec = 900, gpuWorkPerFrame = 10.0)
        val after = Metrics(gameplaySec = 900, gpuWorkPerFrame = 5.0)
        assertEquals("1920x1080" to "1280x720", OptimizeJudge.resolutionChange(resolutionRung("1280x720"), listOf(record("1920x1080"))))
        assertEquals(0.9, OptimizeJudge.gpuWorkScaling(resolutionRung("1280x720"), listOf(record("1920x1080")), before, after)!!, 1e-3)

        val other = Rung.parse(JSONObject().put("rung_id", "x").put("levers", JSONArray()))!!
        assertNull(OptimizeJudge.gpuWorkScaling(other, listOf(record("1920x1080")), before, after))
        assertNull(OptimizeJudge.gpuWorkScaling(resolutionRung("1280x720"), emptyList(), before, after))
    }

    @Test
    fun `gpu work scaling is kept per game`() {
        val state = OptimizeLocalState(gpuWorkScaling = 0.85)
        assertEquals(0.85, OptimizeLocalState.fromJson(state.toJson()).gpuWorkScaling!!, 1e-9)
        assertNull(OptimizeLocalState.fromJson(OptimizeLocalState().toJson()).gpuWorkScaling)
        assertEquals(2.0, OptimizeLocalState.fromJson(JSONObject(state.toJson().toString()).put("gpu_work_scaling", 9.0)).gpuWorkScaling!!, 1e-9)
    }

    private fun signals(): Metrics = Metrics(
        gameplaySec = 900,
        cls = "gpu",
        confidence = 0.8,
        hotThreadCoreShare = mapOf("little" to 0.1, "big" to 0.3, "prime" to 0.6),
        pipelineCompilesBy5Min = listOf(120, 4, 0),
        tessellationPipelines = true,
        majorFaultsBy5Min = listOf(300, 20, 10),
        memReclaimBy5Min = listOf(0, 5000, 0),
    )

    @Test
    fun `signals survive the stored metrics and the verdict block`() {
        val m = signals()
        assertEquals(m, Metrics.fromJson(JSONObject(m.toJson().toString())))
        val verdict = m.toVerdictJson()!!
        assertEquals(0.6, verdict.getJSONObject("hot_thread_core_share").getDouble("prime"), 1e-9)
        assertEquals(listOf(120, 4, 0), verdict.getJSONArray("pipeline_compiles_by_5min").let { a -> (0 until a.length()).map { a.getInt(it) } })
        assertTrue(verdict.getBoolean("tessellation_pipelines"))
        assertEquals(3, verdict.getJSONArray("major_faults_by_5min").length())
        assertEquals(5000, verdict.getJSONArray("mem_reclaim_by_5min").getInt(1))
        val report = m.toReportJson()
        assertEquals(true, report.getBoolean("tessellation_pipelines"))
        // Unmeasured signals are left out, not sent as zeros.
        val bare = Metrics(gameplaySec = 900, cls = "gpu").toVerdictJson()!!
        listOf("hot_thread_core_share", "pipeline_compiles_by_5min", "tessellation_pipelines", "major_faults_by_5min", "mem_reclaim_by_5min")
            .forEach { assertFalse(it, bare.has(it)) }
    }

    @Test
    fun `signals are read leniently`() {
        val o = JSONObject()
            .put("gameplay_s", 600)
            .put("hot_thread_core_share", JSONObject().put("little", 0.5).put("big", "0.5").put("prime", 7))
            .put("pipeline_compiles_by_5min", JSONArray().put(3).put(-1).put("x"))
            .put("tessellation_pipelines", "false")
            .put("major_faults_by_5min", "nope")
        val m = Metrics.fromJson(o)!!
        assertEquals(mapOf("little" to 0.5, "big" to 0.5), m.hotThreadCoreShare)
        assertEquals(listOf(3, 0, 0), m.pipelineCompilesBy5Min)
        assertEquals(false, m.tessellationPipelines)
        assertTrue(m.majorFaultsBy5Min.isEmpty())
    }

    @Test
    fun `game_exited perf_verdict carries the signals and the run record reads them back`() {
        val layout = listOf(CpuClusterKind(listOf(0, 1, 2, 3), "little"), CpuClusterKind(listOf(4, 5, 6), "big"), CpuClusterKind(listOf(7), "prime"))
        val samples = (SessionVerdict.WARMUP_SEC until SessionVerdict.WARMUP_SEC + 600).map { t ->
            SessionSample(t = t, fps = 50f, frames = 50, gpuBusy = 60, hotPct = 90, hotUserTicks = 80, hotSysTicks = 10, hotState = 'R', hotCore = if (t % 4 == 0) 4 else 7)
        }
        val props = SessionVerdict.compute(
            SessionVerdict.Input(
                samples = samples,
                clusterKinds = layout,
                majorFaultsBy5Min = listOf(10L, 20L, 5_000_000_000L),
                memReclaimBy5Min = listOf(0L, 0L, 0L),
                pipelineCompilesBy5Min = listOf(40, 2, 0),
                tessellationPipelines = false,
            ),
        ).properties()
        @Suppress("UNCHECKED_CAST")
        val verdict = props["perf_verdict"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val share = verdict["hot_thread_core_share"] as Map<String, Double>
        assertEquals(0.0, share.getValue("little"), 1e-9)
        assertEquals(0.25, share.getValue("big"), 1e-9)
        assertEquals(0.75, share.getValue("prime"), 1e-9)
        assertEquals(listOf(10, 20, Int.MAX_VALUE), verdict["major_faults_by_5min"])

        val run = JSONObject(props)
        val m = Metrics.fromRun(run)
        assertEquals(0.75, m.hotThreadCoreShare!!.getValue("prime"), 1e-9)
        assertEquals(listOf(40, 2, 0), m.pipelineCompilesBy5Min)
        assertEquals(false, m.tessellationPipelines)
        assertEquals(listOf(0, 0, 0), m.memReclaimBy5Min)

        val none = SessionVerdict.compute(SessionVerdict.Input(samples = samples)).properties()
        @Suppress("UNCHECKED_CAST")
        val v = none["perf_verdict"] as Map<String, Any>
        assertFalse(v.containsKey("hot_thread_core_share"))
        assertFalse(v.containsKey("pipeline_compiles_by_5min"))
        assertFalse(v.containsKey("tessellation_pipelines"))
    }
}
