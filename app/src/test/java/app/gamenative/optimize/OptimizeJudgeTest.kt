package app.gamenative.optimize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun shortCrashIsACrashUnlessTheBaselineEndedTheSameWay() {
        val crash = session(gameplay = 30, reason = "guest_terminated")
        assertEquals(OptimizeJudge.Decision.Crash, OptimizeJudge.judge(crash, session(), isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Decision.Crash, OptimizeJudge.judge(crash, null, isFpsCap = false, shortSessions = 0))
        assertEquals(
            OptimizeJudge.Decision.Short(1),
            OptimizeJudge.judge(crash, session(gameplay = 40, reason = "guest_terminated"), isFpsCap = false, shortSessions = 0),
        )
        val noWindow = session(gameplay = 60, reason = "quick_menu", window = false)
        assertEquals(OptimizeJudge.Decision.Crash, OptimizeJudge.judge(noWindow, session(), isFpsCap = false, shortSessions = 0))
    }

    @Test
    fun shortSessionsBecomeNotComparableOnTheThird() {
        val short = session(gameplay = 300)
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(short, session(), isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Decision.Short(2), OptimizeJudge.judge(short, session(), isFpsCap = false, shortSessions = 1))
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.NOT_COMPARABLE),
            OptimizeJudge.judge(short, session(), isFpsCap = false, shortSessions = 2),
        )
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(session(), null, isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(session(), session(gameplay = 200), isFpsCap = false, shortSessions = 0))
    }

    @Test
    fun longSessionsAreCompared() {
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.HELPED, OptimizeJudge.REASON_FPS),
            OptimizeJudge.judge(session(fps = 50.0), session(fps = 40.0), isFpsCap = false, shortSessions = 0),
        )
    }

    @Test
    fun baselineIsTheNewestLongEnoughRun() {
        fun record(length: Int, fps: Double, verdictGameplay: Int? = null) = JSONObject().put("session_length", length).put("avg_fps", fps).apply {
            verdictGameplay?.let { put("perf_verdict", JSONObject().put("gameplay_s", it)) }
        }
        val aborted = record(length = 95, fps = 20.0)
        val menuOnly = record(length = 900, fps = 60.0, verdictGameplay = 300)
        val played = record(length = 1000, fps = 51.0, verdictGameplay = 874)
        val older = record(length = 1500, fps = 40.0)

        val baseline = OptimizeJudge.baselineOf(sequenceOf(aborted, menuOnly, played, older))!!
        assertEquals(874, baseline.gameplaySec)
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
            OptimizeJudge.judge(forestAfter, forestBefore, isFpsCap = false, shortSessions = 0, fpsCap = 60.0),
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
        // Lower load at lower fps is not a gain.
        assertEquals(OptimizeJudge.NO_CHANGE, OptimizeJudge.compare(before, after.copy(steadyFps = 56.0), false, 60.0))
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
}
