package app.gamenative.optimize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeOfferTest {

    private val session = Metrics(gameplaySec = 900, steadyFps = 40.0, stutterShare = 0.03, cls = "gpu", confidence = 0.8, confident = true)

    private val capRung = JSONObject(
        """{"rung_id":"cap.balanced.30","version":"c1","is_fps_cap":true,
           "levers":[{"lever_type":"container","payload":{"set":[{"key":"fpsLimit","value":"30"}]}}]}""",
    )

    private fun response(json: String) = NextRungResponse.parse(JSONObject(json))

    @Test
    fun newResponseFieldsParseAndDefaultWhenAbsent() {
        val none = response("""{"status":"no_rule","retry_after_s":86400,"rules_version":"r7"}""")
        assertTrue(none.hasNothing)
        assertEquals(86400, none.retryAfterSec)
        assertEquals("r7", none.effectiveRulesVersion)
        assertTrue(response("""{"status":"not_settings"}""").hasNothing)
        assertNull(response("""{"status":"not_settings"}""").retryAfterSec)
        assertFalse(response("""{"status":"exhausted"}""").hasNothing)

        assertNull(Rung.parse(capRung)!!.askFirst)
        assertEquals(true, Rung.parse(JSONObject(capRung.toString()).put("ask_first", true))!!.askFirst)
        assertEquals(false, Rung.parse(JSONObject(capRung.toString()).put("ask_first", false))!!.askFirst)
    }

    @Test
    fun theDryRunAnswerDecidesTheFirstCard() {
        assertEquals(OptimizeCards.Offer.UNVERIFIED, OptimizeCards.offerOf(null))
        assertEquals(OptimizeCards.Offer.CARD, OptimizeCards.offerOf(response("""{"status":"rung","rung":{"rung_id":"cfg.x.balanced.a.s3"}}""")))
        assertEquals(OptimizeCards.Offer.CARD, OptimizeCards.offerOf(response("""{"status":"entitlement_required"}""")))
        assertEquals(OptimizeCards.Offer.HOLD, OptimizeCards.offerOf(response("""{"status":"no_rule","retry_after_s":60}""")))
        assertEquals(OptimizeCards.Offer.HOLD, OptimizeCards.offerOf(response("""{"status":"not_settings"}""")))
        assertEquals(OptimizeCards.Offer.HOLD, OptimizeCards.offerOf(response("""{"status":"exhausted"}""")))
        assertEquals(OptimizeCards.Offer.UNVERIFIED, OptimizeCards.offerOf(response("""{"status":"rung"}""")))
        val cap = JSONObject().put("status", "rung").put("rung", capRung)
        assertEquals(OptimizeCards.Offer.CAP, OptimizeCards.offerOf(NextRungResponse.parse(cap)))
        val capNoQuestion = JSONObject().put("status", "rung").put("rung", JSONObject(capRung.toString()).put("ask_first", false))
        assertEquals(OptimizeCards.Offer.CARD, OptimizeCards.offerOf(NextRungResponse.parse(capNoQuestion)))
    }

    @Test
    fun aHeldGameGetsNoFirstCardUntilTheWindowPassesOrTheRulesChange() {
        val hold = NoRuleHold.of(response("""{"status":"no_rule","retry_after_s":100,"rules_version":"r1"}"""), 1_000L)
        assertEquals(101_000L, hold.untilMillis)
        val state = OptimizeLocalState(noRule = hold)
        assertEquals(OptimizeCards.SUPPRESSED_NO_RULE, OptimizeCards.firstCardBlock(state, session, 2_000L, "r1"))
        assertEquals(OptimizeCards.SUPPRESSED_NO_RULE, OptimizeCards.firstCardBlock(state, session, 2_000L, null))
        assertNull(OptimizeCards.firstCardBlock(state, session, 2_000L, "r2"))
        assertNull(OptimizeCards.firstCardBlock(state, session, 101_000L, "r1"))
        val noRetry = NoRuleHold.of(response("""{"status":"not_settings"}"""), 0L)
        assertEquals(NoRuleHold.DEFAULT_RETRY_SEC * 1000L, noRetry.untilMillis)
    }

    @Test
    fun aWaitingLadderStopsAskingAndTriesAgainOnceTheHoldLetsGo() {
        val hold = NoRuleHold(10_000L, "r1", NextRungResponse.STATUS_NO_RULE)
        val trying = OptimizeLocalState(
            activeProfile = OptimizeProfile.BALANCED,
            profiles = mapOf(OptimizeProfile.BALANCED to ProfileLadder(state = LadderState.TRYING)),
            noRule = hold,
        )
        val waiting = OptimizeLadders.held(trying, OptimizeProfile.BALANCED, 5_000L, "r1")
        assertEquals(LadderState.WAITING, waiting.ladder(OptimizeProfile.BALANCED).state)
        assertEquals(OptimizeStatus.Waiting, OptimizeStatus.of(waiting))
        assertEquals(waiting, OptimizeLadders.held(waiting, OptimizeProfile.BALANCED, 5_000L, "r1"))

        val later = OptimizeLadders.held(waiting, OptimizeProfile.BALANCED, 10_000L, "r1")
        assertEquals(LadderState.TRYING, later.ladder(OptimizeProfile.BALANCED).state)
        assertNull(later.noRule)
        val newRules = OptimizeLadders.held(waiting, OptimizeProfile.BALANCED, 5_000L, "r2")
        assertEquals(LadderState.TRYING, newRules.ladder(OptimizeProfile.BALANCED).state)

        // A step on trial is judged first; a paused ladder stays paused.
        val onTrial = trying.withLadder(
            OptimizeProfile.BALANCED,
            ProfileLadder(state = LadderState.TRYING, pending = PendingRung(Rung.parse(JSONObject().put("rung_id", "a"))!!, 0L, null, 0, emptyList())),
        )
        assertEquals(LadderState.TRYING, OptimizeLadders.held(onTrial, OptimizeProfile.BALANCED, 5_000L, "r1").ladder(OptimizeProfile.BALANCED).state)
        val paused = trying.withLadder(OptimizeProfile.BALANCED, ProfileLadder(state = LadderState.PAUSED))
        assertEquals(LadderState.PAUSED, OptimizeLadders.held(paused, OptimizeProfile.BALANCED, 5_000L, "r1").ladder(OptimizeProfile.BALANCED).state)
    }

    @Test
    fun anUnaskedCapIsAskedFirstThenEndsTheLadderOnlyOnceDeclined() {
        val rung = Rung.parse(capRung)!!
        assertEquals(OptimizeCards.UnaskedCap.ASK, OptimizeCards.unaskedCap(OptimizeLocalState(), rung, 0L))
        assertEquals(OptimizeCards.UnaskedCap.END, OptimizeCards.unaskedCap(OptimizeLocalState(capSnoozedUntil = 10L), rung, 0L))
        assertEquals(OptimizeCards.UnaskedCap.END, OptimizeCards.unaskedCap(OptimizeLocalState(never = true), rung, 0L))
        assertEquals(OptimizeCards.UnaskedCap.ASK, OptimizeCards.unaskedCap(OptimizeLocalState(capSnoozedUntil = 10L), rung, 10L))
        val noQuestion = Rung.parse(JSONObject(capRung.toString()).put("ask_first", false))!!
        assertEquals(OptimizeCards.UnaskedCap.APPLY, OptimizeCards.unaskedCap(OptimizeLocalState(capSnoozedUntil = 10L), noQuestion, 0L))
    }

    @Test
    fun aLadderWithNothingOnNeverEndsOnTheExhaustedCard() {
        assertEquals(OptimizeCard.NO_RULE, OptimizeCards.endCard(ProfileLadder()))
        val reverted = KeptRung("a", "1", 1, emptyList(), 0L, emptyList(), null, applied = false)
        assertEquals(OptimizeCard.NO_RULE, OptimizeCards.endCard(ProfileLadder(kept = listOf(reverted), tried = listOf(TriedRung("b", "1", OptimizeJudge.WORSE)))))
        assertEquals(OptimizeCard.EXHAUSTED, OptimizeCards.endCard(ProfileLadder(kept = listOf(reverted.copy(applied = true)))))
    }

    @Test
    fun aWaitingLadderCountsAsEndedForTheCapQuestion() {
        val fast = Metrics(gameplaySec = 900, steadyFps = 58.0, cls = "thermal_gpu", confidence = 0.8, confident = true)
        assertEquals(40, OptimizeCards.capDecision("thermal_gpu", fast, ProfileLadder(state = LadderState.WAITING), OptimizeProfile.PERFORMANCE))
    }

    @Test
    fun aPrefetchedCapIsUsableUnaskedOnlyWhenItNeedsNoQuestion() {
        val key = PrefetchKey("game.exe", "dxvk", 60)
        val asked = PrefetchedRung(Rung.parse(capRung)!!, 0L, OptimizeProfile.BALANCED, key, emptyList(), "r1")
        assertFalse(asked.isUsable(1L, OptimizeProfile.BALANCED, key, ProfileLadder(), "r1"))
        val free = asked.copy(rung = Rung.parse(JSONObject(capRung.toString()).put("ask_first", false))!!)
        assertTrue(free.isUsable(1L, OptimizeProfile.BALANCED, key, ProfileLadder(), "r1"))
    }

    @Test
    fun cardsKeepBothSessionsAndTheUnverifiedMarkAcrossARestart() {
        val before = Metrics(gameplaySec = 900, steadyFps = 40.0, stutterShare = 0.05, throttleOnsetSec = 600)
        val after = Metrics(gameplaySec = 900, steadyFps = 50.0, stutterShare = 0.01)
        val card = OptimizeCard(OptimizeCard.HELPED, 5L, "Game", OptimizeProfile.BALANCED, rungId = "a", metrics = after, before = before, unverified = true)
        val back = OptimizeCard.fromJson(JSONObject(card.toJson().toString()))!!
        assertEquals(before, back.before)
        assertEquals(after, back.metrics)
        assertEquals(600, back.before?.throttleOnsetSec)
        assertTrue(back.unverified)
        val old = OptimizeCard.fromJson(JSONObject("""{"kind":"helped","at":1}"""))!!
        assertNull(old.before)
        assertFalse(old.unverified)
    }

    @Test
    fun theHoldSurvivesARestart() {
        val state = OptimizeLocalState(
            activeProfile = OptimizeProfile.POWER,
            profiles = mapOf(OptimizeProfile.POWER to ProfileLadder(state = LadderState.WAITING, lastStatus = NextRungResponse.STATUS_NOT_SETTINGS)),
            noRule = NoRuleHold(99L, null, NextRungResponse.STATUS_NOT_SETTINGS),
        )
        val back = OptimizeLocalState.fromJson(JSONObject(state.toJson().toString()))
        assertEquals(state.noRule, back.noRule)
        assertEquals(LadderState.WAITING, back.ladder(OptimizeProfile.POWER).state)
        assertNull(OptimizeLocalState.fromJson(JSONObject(OptimizeLocalState().toJson().toString())).noRule)
    }

    @Test
    fun funnelEventsCarryTheCardAndGame() {
        val game = JSONObject().put("store", "epic").put("store_id", "Quail").put("app_id", "12").put("name", "Game")
        val e = OptimizeEvents.event(OptimizeEvents.CARD_SHOWN, game, OptimizeProfile.BALANCED, "gpu", OptimizeCard.FIRST, null, "r1", 0L, unverified = true)
        assertEquals("card_shown", e.getString("event"))
        assertEquals("Quail", e.getJSONObject("game").getString("store_id"))
        assertEquals("balanced", e.getString("profile"))
        assertEquals("first", e.getString("card_kind"))
        assertTrue(e.isNull("rung_id"))
        assertEquals("1970-01-01T00:00:00Z", e.getString("client_ts"))
        assertTrue(e.getBoolean("unverified"))
        assertEquals(OptimizeEvents.JUDGED_WORSE, OptimizeEvents.judged(OptimizeJudge.CRASH))
        assertNull(OptimizeEvents.judged(OptimizeJudge.NOT_COMPARABLE))
    }

    @Test
    fun gogBuildComesFromTheInfoFile() {
        assertEquals("5612", OptimizeIdentity.gogBuild(JSONObject("""{"buildId":"5612","versionName":"1.2"}""")))
        assertEquals("1.2", OptimizeIdentity.gogBuild(JSONObject("""{"versionName":"1.2"}""")))
        assertNull(OptimizeIdentity.gogBuild(JSONObject("""{"gameId":"1"}""")))
    }

    private val cardSession = Metrics(
        gameplaySec = 511, steadyFps = 41.0, stutterShare = 0.012, cls = "gpu", confidence = 0.82, confident = true,
        fpsCeilingReason = "gpu", gpuBusyP95 = 97, hotThreadP95 = 64, hotThreadCoreShare = mapOf("prime" to 0.7, "big" to 0.3),
        pipelineCompilesBy5Min = listOf(40, 3),
    )
    private val olderRun = Metrics(gameplaySec = 914, steadyFps = 52.0, cls = "none", confidence = 0.0)

    private fun pendingWith(baseline: Metrics?) = PendingRung(
        Rung.parse(JSONObject("""{"rung_id":"cfg.x.balanced.a.s1","version":"v1","levers":[]}"""))!!, 0L, baseline, 0, emptyList(),
    )

    @Test
    fun theSessionBehindTheCardIsTheFirstBaseline() {
        val accepted = OptimizeLadders.accepted(OptimizeLadders.picked(ProfileLadder()), cardSession)
        assertEquals(cardSession, accepted.baselineOverride)
        // It survives the state file, so the launch that asks for the first step still has it.
        val stored = OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED).withLadder(OptimizeProfile.BALANCED, accepted)
        val back = OptimizeLocalState.fromJson(JSONObject(stored.toJson().toString())).ladder(OptimizeProfile.BALANCED)
        assertEquals(cardSession, back.baselineOverride)

        // The next-rung request carries its verdict and signals, not an older run's.
        val verdict = back.baselineOverride!!.toVerdictJson()!!
        assertEquals("gpu", verdict.getString("class"))
        assertEquals(0.82, verdict.getDouble("confidence"), 1e-9)
        assertEquals("gpu", verdict.getString("fps_ceiling_reason"))
        assertEquals(511, verdict.getInt("gameplay_s"))
        assertEquals(0.7, verdict.getJSONObject("hot_thread_core_share").getDouble("prime"), 1e-9)
        assertEquals(40, verdict.getJSONArray("pipeline_compiles_by_5min").getInt(0))
        assertEquals("gpu", back.baselineOverride!!.toReportJson().getString("fps_ceiling_reason"))

        // A ladder that already has steps keeps its own baseline; a session too short to judge against is not used.
        val withKept = ProfileLadder(kept = listOf(pendingWith(olderRun).toKept(olderRun)), baselineOverride = olderRun)
        assertEquals(olderRun, OptimizeLadders.accepted(withKept, cardSession).baselineOverride)
        assertNull(OptimizeLadders.accepted(ProfileLadder(), cardSession.copy(gameplaySec = 299)).baselineOverride)
        assertNull(OptimizeLadders.accepted(ProfileLadder(), null).baselineOverride)
    }

    @Test
    fun laterStepsAreJudgedAgainstTheNewestJudgedSessionUnderTheKeptSteps() {
        val after = cardSession.copy(gameplaySec = 415, steadyFps = 47.0)
        val pending = pendingWith(cardSession)
        assertEquals(after, OptimizeLadders.baselineAfterKeep(OptimizeJudge.HELPED, pending, after, cardSession))
        // Kept without a comparison: the step's own baseline stays.
        assertEquals(cardSession, OptimizeLadders.baselineAfterKeep(OptimizeJudge.NOT_COMPARABLE, pending, after, olderRun))
        assertEquals(olderRun, OptimizeLadders.baselineAfterKeep(OptimizeJudge.NOT_COMPARABLE, pendingWith(null), after, olderRun))

        // Restoring goes back to the session before the first step.
        val second = cardSession.copy(steadyFps = 50.0)
        val kept = listOf(pendingWith(cardSession).toKept(after), pendingWith(after).toKept(second))
        assertEquals(cardSession, OptimizeLadders.baselineAfterRestore(ProfileLadder(kept = kept, pending = pendingWith(second))))
        assertEquals(cardSession, OptimizeLadders.baselineAfterRestore(ProfileLadder(pending = pendingWith(cardSession))))
        assertEquals(olderRun, OptimizeLadders.baselineAfterRestore(ProfileLadder(baselineOverride = olderRun)))
    }

    @Test
    fun keepTuningAsksForTheNextStepOnTheNextLaunch() {
        val kept = pendingWith(cardSession).toKept(cardSession)
        val done = OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED).withLadder(
            OptimizeProfile.BALANCED,
            ProfileLadder(state = LadderState.DONE, kept = listOf(kept), tried = listOf(TriedRung(kept.rungId, kept.version, OptimizeJudge.HELPED))),
        )
        assertFalse(OptimizeLadders.wouldAsk(done, done.ladder(OptimizeProfile.BALANCED), isMember = true, nowMillis = 0L))
        val resumed = OptimizeLadders.resumed(done)
        val ladder = resumed.ladder(OptimizeProfile.BALANCED)
        assertEquals(LadderState.TRYING, ladder.state)
        assertEquals(OptimizeCards.WIRE_CONTINUE, resumed.cardAction)
        assertTrue(OptimizeLadders.wouldAsk(resumed, ladder, isMember = true, nowMillis = 0L))
        assertTrue(OptimizeLadders.wouldAsk(resumed, ladder, isMember = false, nowMillis = 0L))

        // Done at the baseline (no step yet): Keep tuning still asks instead of finding the target met again.
        val good = Metrics(gameplaySec = 1200, steadyFps = 60.0, stutterShare = 0.005)
        val fresh = OptimizeLadders.resumed(
            OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED).withLadder(OptimizeProfile.BALANCED, ProfileLadder(state = LadderState.DONE)),
        )
        val freshLadder = fresh.ladder(OptimizeProfile.BALANCED)
        assertTrue(OptimizeTarget.metAtBaseline(OptimizeProfile.BALANCED, freshLadder, good, 60.0, 60))
        assertFalse(OptimizeTarget.metAtBaseline(OptimizeProfile.BALANCED, freshLadder, good, 60.0, 60, fresh.cardAction))
        assertTrue(OptimizeLadders.wouldAsk(fresh, freshLadder, isMember = true, nowMillis = 0L))
    }
}
