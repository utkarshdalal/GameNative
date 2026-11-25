package app.gamenative.optimize

import app.gamenative.utils.SessionVerdict
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeJudgeTest {

    private fun session(
        gameplay: Int = 1200,
        fps: Double? = 40.0,
        stutter: Double? = 0.02,
        throttle: Int? = null,
        reason: String = "quick_menu",
        window: Boolean = true,
    ) = Metrics(gameplaySec = gameplay, steadyFps = fps, stutterShare = stutter, throttleOnsetSec = throttle, exitReason = reason, windowSeen = window)

    @Test
    fun fpsGainOfEightPercentHelps() {
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(session(fps = 40.0), session(fps = 43.2), isFpsCap = false))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(fps = 40.0), session(fps = 42.0), isFpsCap = false))
    }

    @Test
    fun stutterDropNeedsRelativeAndAbsoluteChange() {
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(session(stutter = 0.020), session(stutter = 0.014), isFpsCap = false))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(stutter = 0.010), session(stutter = 0.006), isFpsCap = false))
    }

    @Test
    fun laterOrNoThrottlingHelps() {
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(session(throttle = 300), session(throttle = 480), isFpsCap = false))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(throttle = 300), session(throttle = 400), isFpsCap = false))
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(session(throttle = 300), session(gameplay = 1200, throttle = null), isFpsCap = false))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(throttle = 1100), session(gameplay = 1200, throttle = null), isFpsCap = false))
    }

    @Test
    fun regressionsMakeItWorse() {
        assertEquals(OptimizeJudge.WORSE, OptimizeJudge.compare(session(fps = 40.0), session(fps = 37.9), isFpsCap = false))
        assertEquals(OptimizeJudge.WORSE, OptimizeJudge.compare(session(stutter = 0.02), session(stutter = 0.03), isFpsCap = false))
        assertEquals(
            OptimizeJudge.WORSE,
            OptimizeJudge.compare(session(fps = 40.0, stutter = 0.05), session(fps = 37.0, stutter = 0.01), isFpsCap = false),
        )
    }

    @Test
    fun fpsCapRungsAreJudgedOnStutterAndThrottling() {
        assertEquals(
            OptimizeJudge.HELPED,
            OptimizeJudge.compare(session(fps = 55.0, throttle = 300), session(fps = 45.0, throttle = 900), isFpsCap = true),
        )
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(session(fps = 55.0), session(fps = 45.0), isFpsCap = true))
    }

    @Test
    fun anEarlyExitOrAStartWithoutTheWindowGivesNoResult() {
        val early = session(gameplay = 30, reason = "guest_terminated")
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(early, session(), isFpsCap = false))
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(early, null, isFpsCap = false))
        val failed = session(gameplay = 0, reason = "force_close", window = false, fps = null)
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(failed, session(), isFpsCap = false))
    }

    @Test
    fun theBaselineSkipsSessionsWithAStepOnTrial() {
        fun run(step: String?, gameplay: Int) = JSONObject().put("session_length", gameplay).put("avg_fps", 40.0).apply {
            put("optimize_state", JSONObject().apply { step?.let { put("template_id", it) } })
        }
        val baseline = OptimizeJudge.baselineOf(sequenceOf(run("cfg.g.performance.c.s6", 900), run(null, 600)))!!
        assertEquals(600, baseline.gameplaySec)
        assertNull(OptimizeJudge.baselineOf(sequenceOf(run("cfg.g.performance.c.s6", 900))))
    }

    @Test
    fun aShortSessionOrOneWithoutAFrameRateGivesNoResult() {
        val short = session(gameplay = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC - 1)
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(short, session(), isFpsCap = false))
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(session(fps = null), session(), isFpsCap = false))
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(session(fps = null), session(), isFpsCap = true))
    }

    @Test
    fun aLongSessionWithoutASessionBeforeItHasNoBaseline() {
        assertEquals(OptimizeJudge.Decision.NoBaseline, OptimizeJudge.judge(session(), null, isFpsCap = false))
        assertEquals(
            OptimizeJudge.Decision.NoBaseline,
            OptimizeJudge.judge(session(), session(gameplay = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC - 1), isFpsCap = false),
        )
        assertFalse(OptimizeLadders.hasBaseline(null))
        assertFalse(OptimizeLadders.hasBaseline(session(gameplay = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC - 1)))
        assertTrue(OptimizeLadders.hasBaseline(session(gameplay = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC)))
    }

    @Test
    fun thePlayTimeToldIsTheWarmUpPlusTheGameplayJudged() {
        val sec = SessionVerdict.WARMUP_SEC + OptimizeJudge.COMPARABLE_GAMEPLAY_SEC
        assertTrue(OptimizeJudge.PLAY_MINUTES * 60 >= sec)
        assertTrue((OptimizeJudge.PLAY_MINUTES - 1) * 60 < sec)
    }

    @Test
    fun sessionsFromTheAskPointAreJudged() {
        // The two sessions behind a card on device: 511 s and 415 s of gameplay, judged like any longer one.
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.HELPED, OptimizeJudge.REASON_FPS),
            OptimizeJudge.judge(session(gameplay = 415, fps = 50.0), session(gameplay = 511, fps = 40.0), isFpsCap = false),
        )
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE),
            OptimizeJudge.judge(session(gameplay = 300), session(gameplay = 300), isFpsCap = false),
        )
        val ask = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE),
            OptimizeJudge.judge(session(gameplay = ask), session(gameplay = ask), isFpsCap = false),
        )
        assertEquals(OptimizeServer.ASK_GAMEPLAY_SEC, ask)
        assertEquals(OptimizeServer.ASK_PLAY_SEC, ask + SessionVerdict.WARMUP_SEC)
    }

    @Test
    fun longSessionsAreCompared() {
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.HELPED, OptimizeJudge.REASON_FPS),
            OptimizeJudge.judge(session(fps = 50.0), session(fps = 40.0), isFpsCap = false),
        )
    }

    @Test
    fun baselineIsTheNewestLongEnoughRun() {
        fun record(length: Int, fps: Double, verdictGameplay: Int? = null) = JSONObject().put("session_length", length).put("avg_fps", fps).apply {
            verdictGameplay?.let { put("perf_verdict", JSONObject().put("gameplay_s", it)) }
        }
        val aborted = record(length = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC - 1, fps = 20.0)
        val menuOnly = record(length = 900, fps = 60.0, verdictGameplay = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC - 1)
        val played = record(length = 600, fps = 51.0, verdictGameplay = 415)
        val older = record(length = 1500, fps = 40.0)

        val baseline = OptimizeJudge.baselineOf(sequenceOf(aborted, menuOnly, played, older))!!
        assertEquals(415, baseline.gameplaySec)
        assertEquals(51.0, baseline.steadyFps!!, 1e-9)
        assertEquals(1500, OptimizeJudge.baselineOf(sequenceOf(aborted, older))!!.gameplaySec)
        assertNull(OptimizeJudge.baselineOf(sequenceOf(aborted, menuOnly)))
        assertNull(OptimizeJudge.baselineOf(emptySequence()))
    }

    // The Forest on an Odin 3: baseline 56.7 fps, the s3 session 59.3 fps, both against a 60 fps target.
    private val forestBefore = Metrics(
        gameplaySec = 1200, steadyFps = 56.7, stutterShare = 0.004, cls = "thermal_gpu",
        fpsBy5Min = listOf(56, 54, 57, 58), gpuBy5Min = listOf(44, 73, 81, 80),
    )
    private val forestAfter = Metrics(
        gameplaySec = 1200, steadyFps = 59.3, stutterShare = 0.004, throttleOnsetSec = 12, cls = "none", confident = false,
        fpsBy5Min = listOf(57, 59, 59, 59), gpuBy5Min = listOf(49, 58, 58, 55),
    )

    @Test
    fun nearTheCapTheLowestWindowDecides() {
        // Without the cap the average needs +8 % (61.3 fps), above the 60 cap: no_change.
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(forestBefore, forestAfter, isFpsCap = false))
        // Near the cap the lowest gameplay window (first window skipped) went 54 -> 59: helped. The cave run lands here.
        assertEquals(
            OptimizeJudge.Verdict(OptimizeJudge.HELPED, OptimizeJudge.REASON_MIN_WINDOW),
            OptimizeJudge.verdict(forestBefore, forestAfter, isFpsCap = false, fpsCap = 60.0),
        )
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.HELPED, OptimizeJudge.REASON_MIN_WINDOW),
            OptimizeJudge.judge(forestAfter, forestBefore, isFpsCap = false, fpsCap = 60.0),
        )
    }

    @Test
    fun nearTheCapAWindowReachingTheCapHelps() {
        val before = forestBefore.copy(fpsBy5Min = listOf(50, 55, 56, 58))
        val after = forestAfter.copy(fpsBy5Min = listOf(50, 57, 58, 58))
        assertEquals(OptimizeJudge.REASON_MIN_WINDOW, OptimizeJudge.verdict(before, after, false, 60.0).reason)
    }

    @Test
    fun nearTheCapLowerGpuLoadAtTheSameFpsHelps() {
        val after = forestAfter.copy(fpsBy5Min = listOf(57, 55, 56, 57), throttleOnsetSec = null)
        val before = forestBefore.copy(fpsBy5Min = listOf(56, 55, 56, 57))
        // GPU median of the gameplay windows 80 -> 58 at higher fps.
        assertEquals(
            OptimizeJudge.Verdict(OptimizeJudge.HELPED, OptimizeJudge.REASON_GPU_LOAD),
            OptimizeJudge.verdict(before, after, false, 60.0),
        )
        // Within 0.98 x the fps before still counts (56.7 -> 55.6); lower load at lower fps is not a gain.
        assertEquals(OptimizeJudge.REASON_GPU_LOAD, OptimizeJudge.verdict(before, after.copy(steadyFps = 55.6), false, 60.0).reason)
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(before, after.copy(steadyFps = 55.5), false, 60.0))
    }

    @Test
    fun nearTheCapAHigherGpuClockAtTheSameFpsHelps() {
        val before = forestBefore.copy(fpsBy5Min = listOf(56, 55, 56, 57), gpuBy5Min = emptyList(), gpuClockBy5Min = listOf(980, 800, 780, 760))
        val after = forestAfter.copy(
            steadyFps = 56.0, throttleOnsetSec = null, fpsBy5Min = listOf(56, 55, 56, 57), gpuBy5Min = emptyList(),
            gpuClockBy5Min = listOf(980, 900, 870, 860),
        )
        // Gameplay clock median 780 -> 870 (+11.5 %) at 56.0 vs 56.7 fps.
        assertEquals(
            OptimizeJudge.Verdict(OptimizeJudge.HELPED, OptimizeJudge.REASON_CLOCK),
            OptimizeJudge.verdict(before, after, false, 60.0),
        )
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(before, after.copy(gpuClockBy5Min = listOf(980, 850, 840, 830)), false, 60.0))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(before, after.copy(steadyFps = 55.0), false, 60.0))
    }

    @Test
    fun nearTheCapThrottleAndStutterCount() {
        val before = forestBefore.copy(throttleOnsetSec = 300, fpsBy5Min = emptyList(), gpuBy5Min = emptyList())
        val after = forestAfter.copy(steadyFps = 57.0, throttleOnsetSec = 600, fpsBy5Min = emptyList(), gpuBy5Min = emptyList())
        assertEquals(OptimizeJudge.REASON_THROTTLE, OptimizeJudge.verdict(before, after, false, 60.0).reason)
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(before, after.copy(throttleOnsetSec = null), false, 60.0))
        val stutterBefore = before.copy(throttleOnsetSec = null, stutterShare = 0.02)
        val stutterAfter = after.copy(throttleOnsetSec = null, stutterShare = 0.01)
        assertEquals(OptimizeJudge.Verdict(OptimizeJudge.HELPED, OptimizeJudge.REASON_STUTTER), OptimizeJudge.verdict(stutterBefore, stutterAfter, false, 60.0))
    }

    @Test
    fun nearTheCapRegressionsAreWorse() {
        val lowerWindow = forestAfter.copy(fpsBy5Min = listOf(57, 59, 51, 59))
        assertEquals(
            OptimizeJudge.Verdict(OptimizeJudge.WORSE, OptimizeJudge.REASON_MIN_WINDOW),
            OptimizeJudge.verdict(forestBefore, lowerWindow, false, 60.0),
        )
        val hotterBefore = forestBefore.copy(throttleOnsetSec = 600)
        val hotterAfter = forestAfter.copy(throttleOnsetSec = 300)
        assertEquals(OptimizeJudge.Verdict(OptimizeJudge.WORSE, OptimizeJudge.REASON_THROTTLE), OptimizeJudge.verdict(hotterBefore, hotterAfter, false, 60.0))
        assertEquals(OptimizeJudge.WORSE, OptimizeJudge.compare(forestBefore, forestAfter.copy(stutterShare = 0.02), false, 60.0))
    }

    @Test
    fun farBelowTheCapTheAverageStillDecides() {
        val before = forestBefore.copy(steadyFps = 40.0, fpsBy5Min = listOf(40, 36, 40, 41))
        val after = forestAfter.copy(steadyFps = 41.0, fpsBy5Min = listOf(40, 41, 41, 41))
        assertEquals(OptimizeJudge.Verdict(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE), OptimizeJudge.verdict(before, after, false, 60.0))
    }

    @Test
    fun gpuLoadIsReadFromTheRunRecordAndKeptInTheBaseline() {
        val run = JSONObject().put("session_length", 1200).put("avg_fps", 56.7)
            .put("perf_by_5min", org.json.JSONArray().put(JSONObject().put("gpu_avg", 44)).put(JSONObject().put("samples", 3)).put(JSONObject().put("gpu_avg", 81)))
        val m = Metrics.fromRun(run)
        assertEquals(listOf(44, 81), m.gpuBy5Min)
        assertEquals(listOf(44, 81), Metrics.fromJson(m.toJson())!!.gpuBy5Min)
    }

    @Test
    fun gpuClockIsReadFromTheRunRecordAndKeptInTheBaseline() {
        val run = JSONObject().put("session_length", 1200).put("avg_fps", 56.7)
            .put(
                "perf_by_5min",
                org.json.JSONArray().put(JSONObject().put("gpu_clk_mhz_avg", 980)).put(JSONObject().put("samples", 3))
                    .put(JSONObject().put("gpu_clk_mhz_avg", 772)),
            )
        val m = Metrics.fromRun(run)
        assertEquals(listOf(980, 772), m.gpuClockBy5Min)
        assertEquals(m, Metrics.fromJson(m.toJson()))
    }

    @Test
    fun theOutcomeReportCarriesTheWindows() {
        val json = forestAfter.copy(gpuClockBy5Min = listOf(982, 930, 860, 798)).toReportJson()
        assertEquals("[57,59,59,59]", json.getJSONArray("fps_by_5min").toString())
        assertEquals("[49,58,58,55]", json.getJSONArray("gpu_by_5min").toString())
        assertEquals("[982,930,860,798]", json.getJSONArray("gpu_clock_by_5min").toString())
        assertEquals(59.3, json.getDouble("steady_fps"), 1e-9)
        val bare = Metrics(gameplaySec = 900).toReportJson()
        assertFalse(bare.has("fps_by_5min") || bare.has("gpu_by_5min") || bare.has("gpu_clock_by_5min"))
        val long = Metrics(gameplaySec = 900, fpsBy5Min = List(60) { 30 }).toReportJson()
        assertEquals(Metrics.MAX_REPORT_WINDOWS, long.getJSONArray("fps_by_5min").length())
    }

    private fun gpuSession(fps: Double, work: Double?, cls: String = "gpu", stutter: Double = 0.01) =
        Metrics(gameplaySec = 1200, steadyFps = fps, stutterShare = stutter, cls = cls, gpuWorkPerFrame = work)

    @Test
    fun lessGpuWorkAtHeldFpsHelpsAGpuBoundGame() {
        val v = OptimizeJudge.verdict(gpuSession(57.0, 12.6), gpuSession(56.0, 11.5), isFpsCap = false, fpsCap = 60.0)
        assertEquals(OptimizeJudge.HELPED, v.result)
        assertEquals(OptimizeJudge.REASON_GPU_WORK, v.reason)
        // Work down but fps fell past 0.98 x: not a gain on work alone.
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(gpuSession(57.0, 12.6), gpuSession(55.0, 11.5), isFpsCap = false, fpsCap = 60.0))
    }

    @Test
    fun fpsUpAtEqualWorkHelps() {
        val v = OptimizeJudge.verdict(gpuSession(40.0, 12.0, cls = "thermal_gpu"), gpuSession(42.0, 12.2, cls = "thermal_gpu"), isFpsCap = false)
        assertEquals(OptimizeJudge.HELPED, v.result)
        assertEquals(OptimizeJudge.REASON_FPS, v.reason)
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(gpuSession(40.0, 12.0), gpuSession(42.0, 13.0), isFpsCap = false))
    }

    @Test
    fun fpsDownAtEqualWorkIsWorseButHeatAloneIsNoChange() {
        assertEquals(OptimizeJudge.WORSE, OptimizeJudge.compare(gpuSession(50.0, 12.0), gpuSession(47.0, 12.0), isFpsCap = false))
        // A hotter run: clock and fps fell together, work per frame fell with them.
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(gpuSession(50.0, 12.0), gpuSession(43.0, 11.0), isFpsCap = false))
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(gpuSession(50.0, 12.0), gpuSession(48.0, 12.0), isFpsCap = false))
    }

    @Test
    fun stutterRulesStillApplyUnderTheWorkRule() {
        assertEquals(OptimizeJudge.WORSE, OptimizeJudge.compare(gpuSession(57.0, 12.6), gpuSession(57.0, 11.0, stutter = 0.03), isFpsCap = false))
        assertEquals(OptimizeJudge.HELPED, OptimizeJudge.compare(gpuSession(57.0, 12.6, stutter = 0.02), gpuSession(57.0, 12.6, stutter = 0.01), isFpsCap = false))
    }

    @Test
    fun workRuleNeedsAGpuClassAndWorkOnBothSides() {
        // cpu_single keeps the fps rules: 47 / 50 is no change there.
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(gpuSession(50.0, 12.0, cls = "cpu_single"), gpuSession(48.0, 10.0, cls = "cpu_single"), isFpsCap = false))
        // No work after (Mali without a clock): the existing near-cap rules judge.
        val noWork = OptimizeJudge.compare(gpuSession(57.0, 12.6), gpuSession(56.0, null), isFpsCap = false, fpsCap = 60.0)
        assertEquals(OptimizeJudge.NO_CHANGE, noWork)
    }

    @Test
    fun capStepsKeepTheCapRules() {
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(gpuSession(57.0, 12.6), gpuSession(45.0, 8.0), isFpsCap = true))
    }

    @Test
    fun workWindowsComeFromTheRunAndOlderRunsDeriveThem() {
        val run = JSONObject(
            """{"session_length":1500,"avg_fps":57,"fps_by_5min":[30,57,57,57],"frame_p50_ms":17,"frame_p99_ms":33,"display_refresh_hz":120,
            "perf_verdict":{"class":"gpu","gameplay_s":1500,"gpu_work_per_frame":11.67,"gpu_busy_p95":96,"hot_thread_p95":82,
            "fps_ceiling_reason":"both","gpu_work_by_5min":[-1,11.6,11.7,11.7],"fps_p10":52.5,"refresh_hz":60}}""",
        )
        val m = Metrics.fromRun(run)
        assertEquals(listOf(null, 11.6, 11.7, 11.7), m.gpuWorkBy5Min)
        assertEquals(11.7, m.workPerFrame()!!, 1e-9)
        assertEquals("both", m.fpsCeilingReason)
        assertEquals(96, m.gpuBusyP95)
        assertEquals(82, m.hotThreadP95)
        val old = JSONObject(
            """{"session_length":1500,"fps_by_5min":[50,50],"perf_by_5min":[{"gpu_avg":90,"gpu_clk_mhz_avg":700},{"gpu_avg":80}]}""",
        )
        val derived = Metrics.fromRun(old).gpuWorkBy5Min
        assertEquals(0.9 * 700 / 50, derived[0]!!, 1e-9)
        assertNull(derived[1])
        val roundTrip = Metrics.fromJson(m.toJson())!!
        assertEquals(m.gpuWorkBy5Min, roundTrip.gpuWorkBy5Min)
        assertEquals("both", roundTrip.fpsCeilingReason)
        val verdict = m.toVerdictJson()!!
        assertEquals("both", verdict.getString("fps_ceiling_reason"))
        assertEquals(11.7, verdict.getDouble("gpu_work_per_frame"), 1e-9)
        assertEquals(4, verdict.getJSONArray("gpu_work_by_5min").length())
        assertEquals(4, verdict.getJSONArray("fps_by_5min").length())
        assertEquals(52.5, verdict.getDouble("fps_p10"), 1e-9)
        assertEquals(17.0, verdict.getDouble("frame_time_p50_ms"), 1e-9)
        assertEquals(33.0, verdict.getDouble("frame_time_p99_ms"), 1e-9)
        assertEquals(60.0, verdict.getDouble("refresh_hz"), 1e-9)
        assertEquals(60.0, m.toReportJson().getDouble("refresh_hz"), 1e-9)
        assertEquals(52.5, roundTrip.fpsP10!!, 1e-9)
        assertEquals(120.0, Metrics.fromRun(JSONObject("""{"session_length":900,"display_refresh_hz":120}""")).refreshHz!!, 1e-9)
        // Older stored metrics without the fields still parse.
        val legacy = Metrics.fromJson(JSONObject("""{"gameplay_s":900,"steady_fps":40}"""))!!
        assertNull(legacy.workPerFrame())
        assertNull(legacy.fpsCeilingReason)
    }
}
