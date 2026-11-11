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
    fun aFatalFingerprintBeforeTheSessionCanBeJudgedIsACrash() {
        val died = session(gameplay = 200, kind = "exception", code = "c0000005", hash = "h1")
        assertTrue(OptimizeJudge.isCrash(died))
        assertEquals(OptimizeJudge.Decision.Crash, OptimizeJudge.judge(died, baseline, isFpsCap = false, shortSessions = 0))
        assertTrue(OptimizeJudge.isCrash(session(gameplay = 400, reason = "guest_terminated", kind = "device_lost")))
        assertTrue(OptimizeJudge.isCrash(session(gameplay = 400, kind = "unimplemented", code = "0f 0b")))
    }

    @Test
    fun aSessionEndingOnItsOwnWithoutAFatalSignIsShortNotACrash() {
        assertFalse(OptimizeJudge.isCrash(session(gameplay = 200)))
        assertFalse(OptimizeJudge.isCrash(session(gameplay = 200, kind = "unimplemented", code = "syscall_435")))
        assertFalse(OptimizeJudge.isCrash(session(gameplay = 200, kind = "other", code = "wineserver_lost")))
        assertFalse(OptimizeJudge.isCrash(session(gameplay = 200, kind = "load_failure")))
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(session(gameplay = 200), baseline, isFpsCap = false, shortSessions = 0))
    }

    @Test
    fun theUserLeavingOrALongSessionIsNotAFingerprintCrash() {
        assertFalse(OptimizeJudge.isCrash(session(gameplay = 200, reason = "quick_menu", kind = "exception")))
        assertFalse(OptimizeJudge.isCrash(session(gameplay = 900, kind = "exception")))
    }

    @Test
    fun noGameWindowOrAGuestCrashInTheFirstTwoMinutesStaysACrash() {
        assertTrue(OptimizeJudge.isCrash(session(gameplay = 60, reason = "quick_menu", window = false)))
        assertTrue(OptimizeJudge.isCrash(session(gameplay = 60, reason = "guest_terminated")))
        assertFalse(OptimizeJudge.isCrash(session(gameplay = 60, reason = "quick_menu")))
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
        assertTrue(OptimizeJudge.isCrash(m))
        val back = Metrics.fromJson(m.toJson())!!
        assertEquals(m.crashKind, back.crashKind)
        assertEquals(m.crashHash, back.crashHash)
    }

    @Test
    fun aLostTrialSessionThatCrashedIsACrash() {
        val crashed = session(gameplay = 30, reason = "guest_terminated")
        val lost = OptimizeJudge.lostSession(0, crashed, null)
        assertTrue(lost is OptimizeJudge.Lost.Crash)
        assertEquals("guest_terminated", (lost as OptimizeJudge.Lost.Crash).after.exitReason)
    }

    @Test
    fun aLostTrialSessionThatRanFineStaysOnTrial() {
        assertEquals(OptimizeJudge.Lost.Again(0), OptimizeJudge.lostSession(1, session(gameplay = 900), "LOW_MEMORY"))
    }

    @Test
    fun theAppDyingHardDuringTheTrialIsACrash() {
        val lost = OptimizeJudge.lostSession(0, null, "CRASH_NATIVE")
        assertTrue(lost is OptimizeJudge.Lost.Crash)
        assertEquals(OptimizeJudge.EXIT_APP_KILLED, (lost as OptimizeJudge.Lost.Crash).after.exitReason)
        assertTrue(OptimizeJudge.isCrash(lost.after))
    }

    @Test
    fun theSecondUnjudgedLaunchWithNoSignIsACrash() {
        assertEquals(OptimizeJudge.Lost.Again(1), OptimizeJudge.lostSession(0, null, null))
        val second = OptimizeJudge.lostSession(1, null, null)
        assertTrue(second is OptimizeJudge.Lost.Crash)
        assertEquals(OptimizeJudge.EXIT_NO_EXIT, (second as OptimizeJudge.Lost.Crash).after.exitReason)
    }

    @Test
    fun theTrialMarkSurvivesTheStateFile() {
        val rung = Rung.parse(JSONObject().put("rung_id", "stack.fex.preset_step.performance"))!!
        val pending = PendingRung(rung, 1L, null, 0, emptyList(), trialStartedAt = 1234L, unjudged = 1)
        val back = PendingRung.fromJson(pending.toJson())!!
        assertEquals(1234L, back.trialStartedAt)
        assertEquals(1, back.unjudged)
        val plain = PendingRung.fromJson(PendingRung(rung, 1L, null, 0, emptyList()).toJson())!!
        assertNull(plain.trialStartedAt)
        assertEquals(0, plain.unjudged)
    }

    private fun frozenSession(
        gameplay: Int = 90,
        stopped: Int? = 45,
        reason: String = "quick_menu",
        mapped: Boolean? = true,
        window: Boolean = true,
    ) = Metrics(gameplaySec = gameplay, steadyFps = 40.0, exitReason = reason, windowSeen = window, framesStoppedSec = stopped, mainWindowMapped = mapped)

    @Test
    fun framesStoppingUntilTheUserLeavesIsAFreeze() {
        assertTrue(OptimizeJudge.frozen(frozenSession()))
        assertTrue(OptimizeJudge.frozen(frozenSession(stopped = OptimizeJudge.FROZEN_TAIL_SEC)))
        assertTrue(OptimizeJudge.frozen(frozenSession(mapped = null)))
        assertTrue(OptimizeJudge.frozen(frozenSession(gameplay = 1200, reason = "activity_destroyed")))
        assertTrue(OptimizeJudge.isCrash(frozenSession()))
    }

    @Test
    fun aShortStopAnUnmeasuredTailOrNoGameplayIsNotAFreeze() {
        assertFalse(OptimizeJudge.frozen(frozenSession(stopped = OptimizeJudge.FROZEN_TAIL_SEC - 1)))
        assertFalse(OptimizeJudge.frozen(frozenSession(stopped = 0)))
        assertFalse(OptimizeJudge.frozen(frozenSession(stopped = null)))
        assertFalse(OptimizeJudge.frozen(frozenSession(gameplay = 0)))
        assertFalse(OptimizeJudge.isCrash(frozenSession(stopped = 10)))
    }

    @Test
    fun aClosedWindowOrTheGameExitingOnItsOwnIsNotAFreeze() {
        assertFalse(OptimizeJudge.frozen(frozenSession(mapped = false)))
        assertFalse(OptimizeJudge.frozen(frozenSession(window = false)))
        assertFalse(OptimizeJudge.frozen(frozenSession(reason = "processes_exited")))
        assertFalse(OptimizeJudge.frozen(frozenSession(reason = "guest_terminated")))
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
        assertTrue(OptimizeJudge.frozen(m))
        val back = Metrics.fromJson(m.toJson())!!
        assertEquals(210, back.framesStoppedSec)
        assertEquals(true, back.mainWindowMapped)
        assertNull(Metrics.fromRun(JSONObject().put("exit_reason", "quick_menu")).framesStoppedSec)
    }

    @Test
    fun aFrozenTrialSessionIsJudgedACrashUnlessTheBaselineFrozeTheSameWay() {
        val frozen = frozenSession()
        assertEquals(OptimizeJudge.Decision.Crash, OptimizeJudge.judge(frozen, baseline, isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Decision.Crash, OptimizeJudge.judge(frozen, null, isFpsCap = false, shortSessions = 2))
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(frozen, frozenSession(gameplay = 1200), isFpsCap = false, shortSessions = 0))
        assertEquals(OptimizeJudge.Decision.Short(1), OptimizeJudge.judge(frozenSession(stopped = 5), baseline, isFpsCap = false, shortSessions = 0))
        val lost = OptimizeJudge.lostSession(0, frozen, null)
        assertTrue(lost is OptimizeJudge.Lost.Crash)
    }

    @Test
    fun aFrozenTrialStepComesOffAndIsMarkedBad() {
        val rung = Rung.parse(JSONObject().put("rung_id", "a"))!!
        val pending = PendingRung(rung, 1L, baseline, 1, emptyList())
        val ladder = ProfileLadder(state = LadderState.TRYING, pending = pending)
        val frozen = frozenSession()
        val settled = OptimizeTarget.settle(OptimizeProfile.BALANCED, OptimizeJudge.CRASH, null, frozen, 60.0, 60)
        assertEquals(OptimizeJudge.CRASH, settled.result)
        assertFalse(settled.done)
        val next = OptimizeLadders.afterJudged(ladder, pending, settled.result, settled.reason, frozen)
        assertEquals(OptimizeJudge.CRASH, next.pending?.revert)
        assertEquals(listOf("a"), next.bad)
        assertTrue(next.kept.isEmpty())
        assertEquals(OptimizeJudge.CRASH, next.tried.last().result)
    }

    @Test
    fun aCrashCardSaysTheChangeWasUndone() {
        val card = OptimizeCard(OptimizeCard.WORSE, 1L, "Game", OptimizeProfile.BALANCED, rungId = "a", outcome = OptimizeCard.OUTCOME_CRASH)
        assertEquals(OptimizeCard.OUTCOME_CRASH, OptimizeCard.fromJson(card.toJson())!!.outcome)
    }
}
