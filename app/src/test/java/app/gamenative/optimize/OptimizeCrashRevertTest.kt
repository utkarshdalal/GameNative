package app.gamenative.optimize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertTrue(OptimizeJudge.judge(died, baseline, isFpsCap = false, shortSessions = 0) is OptimizeJudge.Decision.Final)
        }
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE),
            OptimizeJudge.judge(session(gameplay = 400, reason = "guest_terminated", kind = "device_lost"), baseline, isFpsCap = false, shortSessions = 0),
        )
    }

    @Test
    fun anEarlyExitOrNoGameWindowIsNoVerdict() {
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(session(gameplay = 30, reason = "guest_terminated"), baseline, isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Decision.Short(2), OptimizeJudge.judge(session(gameplay = 30, reason = "force_close"), baseline, isFpsCap = false, shortSessions = 1))
        val early = session(gameplay = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC - 1, kind = "exception", code = "c0000005")
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(early, baseline, isFpsCap = false, shortSessions = 0))
        assertEquals(
            OptimizeJudge.Decision.NoWindow(1),
            OptimizeJudge.judge(session(gameplay = 0, reason = "force_close", window = false), baseline, isFpsCap = false, shortSessions = 0),
        )
        // A fingerprint no longer turns a start without the window into anything else.
        assertEquals(
            OptimizeJudge.Decision.NoWindow(1),
            OptimizeJudge.judge(session(gameplay = 0, window = false, kind = "exception"), baseline, isFpsCap = false, shortSessions = 0),
        )
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
    fun aLostTrialSessionIsNeverACrash() {
        val longWithFingerprint = session(gameplay = 200, kind = "exception", code = "c0000005")
        assertEquals(OptimizeJudge.Lost.Again, OptimizeJudge.lostSession(longWithFingerprint, null))
        val shortWithFingerprint = session(gameplay = 30, kind = "exception", code = "c0000005")
        assertEquals(OptimizeJudge.Lost.Short(shortWithFingerprint), OptimizeJudge.lostSession(shortWithFingerprint, null))
    }

    @Test
    fun aLostTrialSessionThatEndedEarlyIsTooShortNotACrash() {
        val early = session(gameplay = 30, reason = "guest_terminated")
        assertEquals(OptimizeJudge.Lost.Short(early), OptimizeJudge.lostSession(early, null))
        val noWindow = session(gameplay = 0, reason = "force_close", window = false)
        assertEquals(OptimizeJudge.Lost.Short(noWindow), OptimizeJudge.lostSession(noWindow, null))
    }

    @Test
    fun aLostTrialSessionThatRanFineStaysOnTrial() {
        assertEquals(OptimizeJudge.Lost.Again, OptimizeJudge.lostSession(session(gameplay = 900), "LOW_MEMORY"))
    }

    @Test
    fun theAppDyingHardDuringTheTrialIsTooShortNotACrash() {
        val lost = OptimizeJudge.lostSession(null, "CRASH_NATIVE")
        assertTrue(lost is OptimizeJudge.Lost.Short)
        assertEquals(OptimizeJudge.EXIT_APP_KILLED, (lost as OptimizeJudge.Lost.Short).after.exitReason)
    }

    @Test
    fun anUnjudgedLaunchWithNoSignIsTooShortNotACrash() {
        val lost = OptimizeJudge.lostSession(null, null)
        assertTrue(lost is OptimizeJudge.Lost.Short)
        assertEquals(OptimizeJudge.EXIT_NO_EXIT, (lost as OptimizeJudge.Lost.Short).after.exitReason)
    }

    @Test
    fun theTrialMarkSurvivesTheStateFile() {
        val rung = Rung.parse(JSONObject().put("rung_id", "stack.fex.preset_step.performance"))!!
        val pending = PendingRung(rung, 1L, null, 1, emptyList(), trialStartedAt = 1234L)
        val back = PendingRung.fromJson(pending.toJson())!!
        assertEquals(1234L, back.trialStartedAt)
        assertEquals(1, back.shortSessions)
        val plain = PendingRung.fromJson(PendingRung(rung, 1L, null, 0, emptyList()).toJson())!!
        assertNull(plain.trialStartedAt)
        assertEquals(0, plain.shortSessions)
    }

    private val trialRung = Rung.parse(JSONObject().put("rung_id", "a").put("version", "v1"))!!

    @Test
    fun aShortSessionLeavesTheStepOnTrialUnjudged() {
        val pending = PendingRung(trialRung, 1L, baseline, 0, emptyList(), trialStartedAt = 5L)
        val ladder = ProfileLadder(state = LadderState.TRYING, pending = pending)
        val next = OptimizeLadders.unmeasured(ladder, pending, 1)
        assertEquals(1, next.pending!!.shortSessions)
        assertNull(next.pending!!.revert)
        assertNull(next.pending!!.trialStartedAt)
        assertTrue(next.tried.isEmpty())
        assertTrue(next.bad.isEmpty())
        assertTrue(next.kept.isEmpty())
        val state = OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED, profiles = mapOf(OptimizeProfile.BALANCED to next))
        assertFalse(OptimizeLadders.wouldAsk(state, next, isMember = true, nowMillis = 1L))
    }

    @Test
    fun twoSessionsInARowWithoutAResultAskTheQuestion() {
        assertFalse(OptimizeJudge.asksIfStopped(1))
        assertTrue(OptimizeJudge.asksIfStopped(OptimizeJudge.ASK_SHORT_SESSIONS))
        assertTrue(OptimizeJudge.asksIfStopped(3))
    }

    @Test
    fun keepTryingStartsTheCountOver() {
        val pending = PendingRung(trialRung, 1L, baseline, 2, emptyList())
        val ladder = ProfileLadder(state = LadderState.TRYING, pending = pending)
        val next = OptimizeLadders.keepTrying(ladder, "a")
        assertEquals(0, next.pending!!.shortSessions)
        assertNull(next.pending!!.revert)
        assertEquals(ladder, OptimizeLadders.keepTrying(ladder, "other"))
    }

    @Test
    fun undoToTheQuestionIsACrashThePlayerConfirmed() {
        val pending = PendingRung(trialRung, 1L, baseline, 2, emptyList())
        val ladder = ProfileLadder(state = LadderState.TRYING, pending = pending)
        val last = session(gameplay = 20, reason = "guest_terminated")
        val next = OptimizeLadders.stoppedUndone(ladder, "a", last)!!
        assertEquals(OptimizeJudge.CRASH, next.pending!!.revert)
        assertEquals(listOf("a"), next.bad)
        assertEquals(TriedRung("a", "v1", OptimizeJudge.CRASH, OptimizeJudge.REASON_PLAYER_CRASH), next.tried.last())
        assertTrue(next.kept.isEmpty())
        assertNull(OptimizeLadders.stoppedUndone(ladder, "other", last))
        assertNull(OptimizeLadders.stoppedUndone(next, "a", last))
    }

    private fun frozenSession(
        gameplay: Int = 90,
        stopped: Int? = 45,
        reason: String = "quick_menu",
        mapped: Boolean? = true,
        window: Boolean = true,
    ) = Metrics(gameplaySec = gameplay, steadyFps = 40.0, exitReason = reason, windowSeen = window, framesStoppedSec = stopped, mainWindowMapped = mapped)

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
    fun aFrozenLongSessionIsComparedOrNotMeasuredNeverACrash() {
        // Frames that stopped before the player left: still a normal comparison when the session has a frame rate.
        assertEquals(
            OptimizeJudge.Decision.Final(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE),
            OptimizeJudge.judge(frozenSession(), baseline, isFpsCap = false, shortSessions = 0),
        )
        // With no frame rate recorded there is nothing to compare: not measured, and it counts toward the question.
        val nothing = frozenSession().copy(steadyFps = null)
        assertEquals(
            OptimizeJudge.Decision.NotMeasured(OptimizeJudge.REASON_NO_DATA, emptyList(), 2),
            OptimizeJudge.judge(nothing, baseline, isFpsCap = false, shortSessions = 1),
        )
        assertTrue(OptimizeJudge.asksIfStopped(2))
        assertEquals(OptimizeJudge.Decision.NoBaseline, OptimizeJudge.judge(frozenSession(), null, isFpsCap = false, shortSessions = 2))
        val short = OptimizeJudge.COMPARABLE_GAMEPLAY_SEC - 1
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(frozenSession(gameplay = short), baseline, isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Lost.Again, OptimizeJudge.lostSession(frozenSession(), null))
    }
}
