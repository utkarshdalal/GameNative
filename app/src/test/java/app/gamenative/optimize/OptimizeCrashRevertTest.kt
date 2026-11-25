package app.gamenative.optimize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeCrashRevertTest {

    private fun session(
        gameplay: Int,
        reason: String = "processes_exited",
        window: Boolean = true,
        kind: String? = null,
        code: String? = null,
        hash: String? = null,
    ) = Metrics(gameplaySec = gameplay, steadyFps = 40.0, exitReason = reason, windowSeen = window, crashKind = kind, crashCode = code, crashHash = hash)

    private val baseline = Metrics(gameplaySec = 1200, steadyFps = 40.0, exitReason = "quick_menu")

    @Test
    fun aLongSessionIsComparedWhateverFingerprintItLeft() {
        for (kind in listOf("exception", "assert", "device_lost", "unimplemented")) {
            val died = session(gameplay = 200, kind = kind, code = "c0000005", hash = "h1")
            assertTrue(OptimizeJudge.judge(died, baseline, isFpsCap = false) is OptimizeJudge.Decision.Final)
        }
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE),
            OptimizeJudge.judge(session(gameplay = 400, reason = "guest_terminated", kind = "device_lost"), baseline, isFpsCap = false),
        )
    }

    @Test
    fun anEarlyExitOrNoGameWindowIsNoResult() {
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(session(gameplay = 30, reason = "guest_terminated"), baseline, isFpsCap = false))
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(session(gameplay = 30, reason = "force_close"), baseline, isFpsCap = false))
        val early = session(gameplay = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC - 1, kind = "exception", code = "c0000005")
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(early, baseline, isFpsCap = false))
        val noWindow = Metrics(gameplaySec = 0, exitReason = "force_close", windowSeen = false)
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(noWindow, baseline, isFpsCap = false))
    }

    @Test
    fun aLongSessionThatStoppedPresentingFramesIsComparedOrGivesNoResult() {
        val frozen = Metrics(gameplaySec = 90, steadyFps = 40.0, exitReason = "quick_menu", framesStoppedSec = 45, mainWindowMapped = true)
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE),
            OptimizeJudge.judge(frozen, baseline, isFpsCap = false),
        )
        // No frame rate recorded: nothing to compare, so no result, the same as a session too short.
        assertEquals(OptimizeJudge.Decision.Short, OptimizeJudge.judge(frozen.copy(steadyFps = null), baseline, isFpsCap = false))
        assertEquals(OptimizeJudge.Decision.NoBaseline, OptimizeJudge.judge(frozen, null, isFpsCap = false))
    }

    @Test
    fun theRunRecordCarriesTheCrashFingerprint() {
        val run = JSONObject()
            .put("session_length", 150)
            .put("exit_reason", "processes_exited")
            .put("main_window_class", "UnityWndClass")
            .put("crash_fingerprint", JSONObject().put("kind", "exception").put("code", "c0000005").put("hash", "abc"))
            .put("crash_fingerprint_hash", "abc")
        val m = Metrics.fromRun(run)
        assertEquals("exception", m.crashKind)
        assertEquals("c0000005", m.crashCode)
        assertEquals("abc", m.crashHash)
        val back = Metrics.fromJson(m.toJson())!!
        assertEquals(m.crashKind, back.crashKind)
        assertEquals(m.crashHash, back.crashHash)
    }

    @Test
    fun theRunRecordCarriesTheStoppedFrames() {
        val run = JSONObject()
            .put("session_length", 300)
            .put("exit_reason", "quick_menu")
            .put("main_window_class", "UnityWndClass")
            .put("main_window_mapped", true)
            .put("frames_stopped_s", 210)
            .put("perf_verdict", JSONObject().put("gameplay_s", 90))
        val m = Metrics.fromRun(run)
        assertEquals(210, m.framesStoppedSec)
        assertEquals(true, m.mainWindowMapped)
        val back = Metrics.fromJson(m.toJson())!!
        assertEquals(210, back.framesStoppedSec)
        assertEquals(true, back.mainWindowMapped)
        assertNull(Metrics.fromRun(JSONObject().put("exit_reason", "quick_menu")).framesStoppedSec)
    }

    @Test
    fun undoOnTheTooShortCardTakesTheStepOnTrialOff() {
        val rung = Rung.parse(JSONObject().put("rung_id", "a").put("version", "v1"))!!
        val ladder = ProfileLadder(state = LadderState.PAUSED, pending = PendingRung(rung, 1L, baseline, emptyList()), noChangeStreak = 1)
        val undone = OptimizeLadders.undoneOnTrial(ladder, "a")!!
        assertNull(undone.pending)
        assertEquals(TriedRung("a", "v1", OptimizeJudge.UNDO), undone.tried.single())
        assertTrue(undone.bad.isEmpty())
        assertEquals(LadderState.TRYING, undone.state)
        assertEquals(0, undone.noChangeStreak)
        assertEquals(baseline, undone.baselineOverride)
        assertNull(OptimizeLadders.undoneOnTrial(ladder, "other"))
        assertNull(OptimizeLadders.undoneOnTrial(ladder.copy(pending = ladder.pending!!.copy(revert = OptimizeJudge.WORSE)), "a"))
    }

    @Test
    fun oldTrialCountsInTheStateFileAreIgnored() {
        val rung = Rung.parse(JSONObject().put("rung_id", "stack.fex.preset_step.performance"))!!
        val stored = PendingRung(rung, 1L, null, emptyList()).toJson()
            .put("short_sessions", 2).put("trial_started_at", 1234L).put("unjudged", 1).put("no_window_starts", 1)
        val back = PendingRung.fromJson(stored)!!
        assertEquals("stack.fex.preset_step.performance", back.rung.rungId)
        assertNull(back.revert)
        assertEquals(PendingRung(rung, 1L, null, emptyList()).toJson().toString(), back.toJson().toString())
    }
}
