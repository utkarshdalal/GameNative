package app.gamenative.optimize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeRulesTest {

    private fun kept(id: String, applied: Boolean = true) =
        KeptRung(id, "1", 1, emptyList(), 0L, emptyList(), null, rung = JSONObject().put("rung_id", id), applied = applied)

    private fun pending(id: String) = PendingRung(Rung.parse(JSONObject().put("rung_id", id))!!, 0L, null, 0, emptyList())

    private val session = Metrics(gameplaySec = 900, steadyFps = 40.0, stutterShare = 0.03, cls = "gpu", confidence = 0.8, confident = true)

    @Test
    fun switchingRevertsTheOldProfileAndReappliesTheNewOne() {
        val state = OptimizeLocalState(
            activeProfile = OptimizeProfile.BALANCED,
            profiles = mapOf(
                OptimizeProfile.BALANCED to ProfileLadder(kept = listOf(kept("b1"), kept("b2")), pending = pending("b3")),
                OptimizeProfile.PERFORMANCE to ProfileLadder(kept = listOf(kept("p1", applied = false), kept("p2", applied = false))),
            ),
        )
        val plan = SwitchPlan.of(state, OptimizeProfile.PERFORMANCE)
        assertEquals(listOf(OptimizeProfile.BALANCED), plan.revertPending)
        assertEquals(listOf(OptimizeProfile.BALANCED to "b2", OptimizeProfile.BALANCED to "b1"), plan.revertKept)
        assertEquals(listOf("p1", "p2"), plan.reapply)
    }

    @Test
    fun offRevertsEverythingAndReappliesNothing() {
        val state = OptimizeLocalState(
            activeProfile = OptimizeProfile.QUALITY,
            profiles = mapOf(OptimizeProfile.QUALITY to ProfileLadder(kept = listOf(kept("s1")), pending = pending("s2"))),
        )
        val plan = SwitchPlan.of(state, null)
        assertEquals(listOf(OptimizeProfile.QUALITY), plan.revertPending)
        assertEquals(listOf(OptimizeProfile.QUALITY to "s1"), plan.revertKept)
        assertTrue(plan.reapply.isEmpty())
    }

    @Test
    fun theActiveProfileKeepsItsPendingAndOnlyFixesWhatIsOff() {
        val state = OptimizeLocalState(
            activeProfile = OptimizeProfile.POWER,
            profiles = mapOf(
                OptimizeProfile.POWER to ProfileLadder(kept = listOf(kept("w1"), kept("w2", applied = false)), pending = pending("w3")),
                OptimizeProfile.BALANCED to ProfileLadder(kept = listOf(kept("b1", applied = false))),
            ),
        )
        val plan = SwitchPlan.of(state, OptimizeProfile.POWER)
        assertTrue(plan.revertPending.isEmpty())
        assertTrue(plan.revertKept.isEmpty())
        assertEquals(listOf("w2"), plan.reapply)
        assertFalse(plan.isEmpty)
        assertTrue(SwitchPlan.of(OptimizeLocalState(), null).isEmpty)
    }

    @Test
    fun performanceHoldsTheTargetInEveryWindowWithoutHeat() {
        val good = Metrics(gameplaySec = 900, steadyFps = 59.0, cls = "none", confident = true, fpsBy5Min = listOf(40, 59, 58, 60))
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good, 120.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(steadyFps = 58.0), 120.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(fpsBy5Min = listOf(40, 59, 56, 60)), 120.0, 0))
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(steadyFps = 44.0, fpsBy5Min = listOf(44, 43)), 60.0, 45))
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(steadyFps = 29.5, fpsBy5Min = emptyList()), 30.0, 0))
        // An onset that cost nothing does not count; one under which the GPU clock fell 10 % does.
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(throttleOnsetSec = 300), 60.0, 0))
        val clockFell = good.copy(throttleOnsetSec = 300, gpuClockBy5Min = listOf(980, 950, 900, 850))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, clockFell, 60.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(cls = "thermal_gpu"), 60.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(steadyFps = null), 60.0, 0))
    }

    /** The Odin 3 The Forest baseline: 56.7 fps at a 60 cap, windows 54-58, thermal status 5 throughout. */
    private val forest = Metrics(
        gameplaySec = 1056, steadyFps = 56.7, stutterShare = 0.0008, throttleOnsetSec = 0,
        cls = "thermal_gpu", confidence = 0.8, confident = true, symptom = "low_fps", fpsBy5Min = listOf(54, 58, 57, 56),
    )

    @Test
    fun forestBaselineMeetsNoTargetSoEveryProfileKeepsTrying() {
        val trying = ProfileLadder(state = LadderState.TRYING)
        for (profile in listOf(OptimizeProfile.PERFORMANCE, OptimizeProfile.POWER, OptimizeProfile.BALANCED)) {
            assertFalse(profile.name, OptimizeTarget.metAtBaseline(profile, trying, forest, 60.0, 60))
        }
        assertTrue(OptimizeTarget.metAtBaseline(OptimizeProfile.QUALITY, trying, forest, 60.0, 60))
    }

    @Test
    fun baselineCheckOnlyBeforeTheFirstStep() {
        val cool = forest.copy(steadyFps = 59.5, throttleOnsetSec = null, cls = "gpu", confident = false, fpsBy5Min = listOf(59, 60, 59))
        val trying = ProfileLadder(state = LadderState.TRYING)
        assertTrue(OptimizeTarget.metAtBaseline(OptimizeProfile.PERFORMANCE, trying, cool, 60.0, 60))
        assertFalse(OptimizeTarget.metAtBaseline(OptimizeProfile.PERFORMANCE, trying.copy(kept = listOf(kept("a"))), cool, 60.0, 60))
        assertFalse(OptimizeTarget.metAtBaseline(OptimizeProfile.PERFORMANCE, trying.copy(pending = pending("a")), cool, 60.0, 60))
        assertFalse(OptimizeTarget.metAtBaseline(OptimizeProfile.PERFORMANCE, trying.copy(tried = listOf(TriedRung("a", "1", "worse"))), cool, 60.0, 60))
        assertFalse(OptimizeTarget.metAtBaseline(OptimizeProfile.PERFORMANCE, ProfileLadder(state = LadderState.DONE), cool, 60.0, 60))
        assertFalse(OptimizeTarget.metAtBaseline(OptimizeProfile.PERFORMANCE, trying, null, 60.0, 60))
    }

    @Test
    fun balancedWithFewStuttersIsMetOnlyWithoutHeat() {
        val calm = Metrics(gameplaySec = 1056, steadyFps = 59.0, stutterShare = 0.0008)
        assertTrue(OptimizeTarget.met(OptimizeProfile.BALANCED, calm, 60.0, 60))
        assertFalse(OptimizeTarget.met(OptimizeProfile.BALANCED, calm.copy(throttleOnsetSec = 0), 60.0, 60))
        assertFalse(OptimizeTarget.met(OptimizeProfile.BALANCED, calm.copy(cls = "thermal_gpu", confident = true), 60.0, 60))
    }

    @Test
    fun aGameThatRunsFineGetsNoCard() {
        val busy = session.copy(steadyFps = 59.6, stutterShare = 0.002, fpsBy5Min = listOf(52, 60, 60, 59), refreshHz = 60.0, gpuBusyP95 = 96)
        assertTrue(OptimizeCards.ranFine(busy, 0))
        assertEquals(OptimizeCards.SUPPRESSED_RAN_FINE, OptimizeCards.firstCardBlock(OptimizeLocalState(), busy, 0L))
        // Holding the user's own 30 fps limiter is fine too; missing it is not.
        val capped = busy.copy(steadyFps = 30.0, fpsBy5Min = listOf(30, 30, 30))
        assertTrue(OptimizeCards.ranFine(capped, 30))
        assertFalse(OptimizeCards.ranFine(capped, 0))
        // Short of the frame rate, dipping, stuttering or hot: there is something to offer.
        assertFalse(OptimizeCards.ranFine(busy.copy(steadyFps = 50.0), 0))
        assertFalse(OptimizeCards.ranFine(busy.copy(fpsBy5Min = listOf(52, 60, 60, 51)), 0))
        assertFalse(OptimizeCards.ranFine(busy.copy(stutterShare = 0.03), 0))
        assertFalse(OptimizeCards.ranFine(busy.copy(cls = "thermal_gpu"), 0))
        assertFalse(OptimizeCards.ranFine(busy.copy(throttleOnsetSec = 400, fpsBy5Min = listOf(52, 60, 60, 57)), 0))
        assertNull(OptimizeCards.firstCardBlock(OptimizeLocalState(), busy.copy(steadyFps = 50.0), 0L))
    }

    @Test
    fun aKeptStepWithNothingToSeeIsNotCalledAHelp() {
        val before = Metrics(gameplaySec = 900, steadyFps = 47.6, stutterShare = 0.0)
        val after = Metrics(gameplaySec = 900, steadyFps = 47.9, stutterShare = 0.0)
        val card = OptimizeCard(OptimizeCard.HELPED, 1L, "Game", OptimizeProfile.BALANCED, rungId = "a", metrics = after, before = before)
        assertFalse(OptimizeCards.showsGain(card))
        assertTrue(OptimizeCards.showsGain(card.copy(fpsGainPct = 8)))
        assertTrue(OptimizeCards.showsGain(card.copy(stutterDropPct = 40)))
        // Holding longer before slowing down is something the player sees.
        val hot = card.copy(before = before.copy(throttleOnsetSec = 300))
        assertTrue(OptimizeCards.showsGain(hot))
        assertTrue(OptimizeCards.showsGain(hot.copy(metrics = after.copy(throttleOnsetSec = 420))))
        assertFalse(OptimizeCards.showsGain(hot.copy(metrics = after.copy(throttleOnsetSec = 320))))
    }

    @Test
    fun balancedIsTheSuggestedGoal() {
        assertEquals(OptimizeProfile.BALANCED, OptimizeCards.SUGGESTED_PROFILE)
    }

    @Test
    fun windowsSurviveTheRunRecordAndState() {
        val run = JSONObject(mapOf("avg_fps" to 56.7, "session_length" to 1100, "fps_by_5min" to listOf(54, 58, 57, 56)))
        val m = Metrics.fromRun(run)
        assertEquals(listOf(54, 58, 57, 56), m.fpsBy5Min)
        assertEquals(m, Metrics.fromJson(m.toJson()))
        val card = OptimizeCard(OptimizeCard.FIRST, 1L, profile = OptimizeProfile.PERFORMANCE, metrics = m)
        assertEquals(card, OptimizeCard.fromJson(card.toJson()))
    }

    @Test
    fun balancedIsMetWhenTheGameRunsFine() {
        val m = Metrics(gameplaySec = 900, steadyFps = 59.6, stutterShare = 0.002, fpsBy5Min = listOf(52, 60, 60, 59))
        assertTrue(OptimizeTarget.met(OptimizeProfile.BALANCED, m, 60.0, 0))
        // The session a first card is about is never one Balanced already calls done.
        assertFalse(OptimizeTarget.met(OptimizeProfile.BALANCED, m.copy(steadyFps = 44.0), 60.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.BALANCED, m.copy(stutterShare = 0.02), 60.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.BALANCED, m.copy(throttleOnsetSec = 400, fpsBy5Min = listOf(52, 60, 60, 57)), 60.0, 0))
        assertTrue(OptimizeTarget.met(OptimizeProfile.BALANCED, m.copy(stutterShare = null), 60.0, 0))
        assertTrue(OptimizeTarget.met(OptimizeProfile.BALANCED, m.copy(steadyFps = 30.0, fpsBy5Min = listOf(30, 30, 30)), 60.0, 30))
        assertEquals(OptimizeCards.ranFine(m, 0), OptimizeTarget.met(OptimizeProfile.BALANCED, m, m.refreshHz, 0))
    }

    @Test
    fun qualityNeedsUnderOnePercentStutter() {
        assertTrue(OptimizeTarget.met(OptimizeProfile.QUALITY, Metrics(gameplaySec = 900, stutterShare = 0.009), null, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.QUALITY, Metrics(gameplaySec = 900, stutterShare = 0.01), null, 0))
    }

    @Test
    fun powerNeedsTwentyMinutesWithoutThrottle() {
        assertTrue(OptimizeTarget.met(OptimizeProfile.POWER, Metrics(gameplaySec = 1200), null, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.POWER, Metrics(gameplaySec = 1199), null, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.POWER, Metrics(gameplaySec = 2000, throttleOnsetSec = 900), null, 0))
    }

    @Test
    fun firstCardNeedsAConfidentLongSessionOnAnUntouchedGame() {
        assertTrue(OptimizeCards.offerFirst(OptimizeLocalState(), session, 0L))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(), session.copy(gameplaySec = 299), 0L))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(), session.copy(confident = false), 0L))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(), session.copy(cls = "not_saturated"), 0L))
        // A slowdown tuning cannot change leaves no card.
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(), session.copy(cls = OptimizeCards.SYNC_SPIN), 0L))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(never = true), session, 0L))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED), session, 0L))
        assertTrue(OptimizeCards.offerFirst(OptimizeLocalState(profiles = mapOf(OptimizeProfile.POWER to ProfileLadder())), session, 0L))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(card = OptimizeCard(OptimizeCard.FIRST, 0L)), session, 0L))
    }

    @Test
    fun notNowHoldsForSevenDaysUnlessASessionRunsWorse() {
        val card = OptimizeCard(OptimizeCard.FIRST, 0L, cls = "gpu", metrics = session)
        val suppression = OptimizeCards.notNow(card, 1_000L)
        assertEquals(1_000L + OptimizeCards.SUPPRESS_MS, suppression.untilMillis)
        val state = OptimizeLocalState(suppression = suppression)
        assertFalse(OptimizeCards.offerFirst(state, session, 2_000L))
        assertFalse(OptimizeCards.offerFirst(state, session.copy(steadyFps = 39.0), 2_000L))
        assertTrue(OptimizeCards.offerFirst(state, session.copy(steadyFps = 35.0), 2_000L))
        assertTrue(OptimizeCards.offerFirst(state, session.copy(stutterShare = 0.05), 2_000L))
        assertTrue(OptimizeCards.offerFirst(state, session, suppression.untilMillis))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(suppression = CardSuppression(10_000L, null)), session, 2_000L))
    }

    @Test
    fun measuredLinesComeOnlyFromOurOwnBeforeAndAfter() {
        val before = Metrics(gameplaySec = 900, steadyFps = 40.0, stutterShare = 0.05)
        assertEquals(12, OptimizeCards.fpsGainPct(before, before.copy(steadyFps = 44.8)))
        assertNull(OptimizeCards.fpsGainPct(before, before.copy(steadyFps = 39.0)))
        assertNull(OptimizeCards.fpsGainPct(null, before))
        assertEquals(40, OptimizeCards.stutterDropPct(before, before.copy(stutterShare = 0.03)))
        assertNull(OptimizeCards.stutterDropPct(before, before.copy(stutterShare = 0.06)))
        assertEquals(OptimizeCard.OUTCOME_FPS, OptimizeCards.helpedOutcome("fps", OptimizeProfile.QUALITY, 12, null))
        assertEquals(OptimizeCard.OUTCOME_STUTTER, OptimizeCards.helpedOutcome(null, OptimizeProfile.BALANCED, null, 40))
        assertEquals(OptimizeCard.OUTCOME_STABLE, OptimizeCards.helpedOutcome(null, OptimizeProfile.POWER, 12, 40))
        assertEquals(OptimizeCard.OUTCOME_STABLE, OptimizeCards.helpedOutcome("stable", OptimizeProfile.PERFORMANCE, 12, null))
        assertEquals(OptimizeCard.OUTCOME_FPS, OptimizeCards.helpedOutcome("thermal", OptimizeProfile.PERFORMANCE, 12, null))
    }

    @Test
    fun aGainUnderFivePercentIsNotNamed() {
        val before = Metrics(gameplaySec = 900, steadyFps = 44.1, stutterShare = 0.05)
        val small = OptimizeCards.fpsGainPct(before, before.copy(steadyFps = 44.7))
        assertNull(small)
        assertEquals(OptimizeCard.OUTCOME_STABLE, OptimizeCards.helpedOutcome("fps", OptimizeProfile.PERFORMANCE, small, null))
        val five = Metrics(gameplaySec = 900, steadyFps = 40.0)
        val gain = OptimizeCards.fpsGainPct(five, five.copy(steadyFps = 42.0))
        assertEquals(5, gain)
        assertEquals(OptimizeCard.OUTCOME_FPS, OptimizeCards.helpedOutcome("fps", OptimizeProfile.PERFORMANCE, gain, null))
        assertNull(OptimizeCards.stutterDropPct(before, before.copy(stutterShare = 0.048)))
        assertEquals(OptimizeCard.OUTCOME_STABLE, OptimizeCards.helpedOutcome(null, OptimizeProfile.BALANCED, null, null))
        val drop = OptimizeCards.stutterDropPct(before, before.copy(stutterShare = 0.0475))
        assertEquals(5, drop)
        assertEquals(OptimizeCard.OUTCOME_STUTTER, OptimizeCards.helpedOutcome(null, OptimizeProfile.BALANCED, null, drop))
    }

    @Test
    fun aUsedUpTrialIsAskedAgainOnlyForMembersOrAfterADay() {
        val used = ProfileLadder(state = LadderState.TRYING, lastStatus = NextRungResponse.STATUS_ENTITLEMENT_REQUIRED)
        val none = Entitlement(Entitlement.NONE, 3, 3)
        val day = OptimizeCards.ENTITLEMENT_RECHECK_MS
        assertFalse(OptimizeCards.shouldAskAfterTrial(used, none, 1_000L, isMember = false, nowMillis = 2_000L))
        assertTrue(OptimizeCards.shouldAskAfterTrial(used, none, 1_000L, isMember = true, nowMillis = 2_000L))
        assertTrue(OptimizeCards.shouldAskAfterTrial(used, none, 1_000L, isMember = false, nowMillis = 1_000L + day))
        assertTrue(OptimizeCards.shouldAskAfterTrial(used, Entitlement(Entitlement.MEMBER, 0, 0), 1_000L, isMember = false, nowMillis = 2_000L))
        assertTrue(OptimizeCards.shouldAskAfterTrial(ProfileLadder(state = LadderState.TRYING), none, 1_000L, isMember = false, nowMillis = 2_000L))
    }

    @Test
    fun anAccountChangeLiftsTheDayHoldOnAUsedUpTrial() {
        val used = ProfileLadder(state = LadderState.TRYING, lastStatus = NextRungResponse.STATUS_ENTITLEMENT_REQUIRED)
        val none = Entitlement(Entitlement.NONE, 3, 3)
        assertFalse(OptimizeCards.shouldAskAfterTrial(used, none, 1_000L, isMember = false, nowMillis = 3_000L, accountChangedAt = 500L))
        assertTrue(OptimizeCards.shouldAskAfterTrial(used, none, 1_000L, isMember = false, nowMillis = 3_000L, accountChangedAt = 2_000L))
        val state = OptimizeLocalState(entitlement = none, entitlementCheckedAt = 1_000L).withLadder(OptimizeProfile.BALANCED, used)
        assertFalse(OptimizeLadders.wouldAsk(state, used, isMember = false, nowMillis = 3_000L))
        assertTrue(OptimizeLadders.wouldAsk(state, used, isMember = false, nowMillis = 3_000L, accountChangedAt = 2_000L))
    }
    private val cascade = listOf("5", "5", "4", "4", "3", "3", "2", "2", "1", "1", "0", "0")

    @Test
    fun relativeApplyOnTheForestNumbers() {
        assertTrue(OptimizeRelative.shouldApply("0", "4", cascade, OptimizeRelative.MORE))
        val bloom = listOf("0", "0", "0", "0", "0", "0", "1", "1", "1", "1", "1", "1")
        assertFalse(OptimizeRelative.shouldApply("0", "1", bloom, OptimizeRelative.LESS))
        val grass = listOf("0.1", "0.15", "0.2", "0.25", "0.3", "0.35", "0.4", "0.45", "0.5", "0.6", "0.8", "1")
        assertTrue(OptimizeRelative.shouldApply("0.45", "0.30", grass, OptimizeRelative.LESS))
        val draw = listOf("0", "0", "1", "1", "1", "2", "2", "2", "3", "3", "3", "3")
        assertTrue(OptimizeRelative.shouldApply("3", "2", draw, OptimizeRelative.LESS))
        assertFalse(OptimizeRelative.shouldApply("2", "2", draw, OptimizeRelative.LESS))
        assertFalse(OptimizeRelative.shouldApply("1.000000", "1", draw, OptimizeRelative.LESS))
    }

    @Test
    fun relativeApplyOnEnumKeys() {
        val quality = listOf("Low", "Low", "Low", "Medium", "Medium", "Medium", "High", "High", "High", "Ultra", "Ultra", "Ultra")
        assertFalse(OptimizeRelative.shouldApply("Low", "Medium", quality, "enum"))
        assertFalse(OptimizeRelative.shouldApply(" low ", "Low", quality, "enum"))
        assertTrue(OptimizeRelative.shouldApply("High", "Medium", quality, "enum"))
        assertTrue(OptimizeRelative.shouldApply("Custom", "Medium", quality, "enum"))
        assertEquals(11, OptimizeRelative.stepOf(quality, "ultra"))
        assertEquals(5, OptimizeRelative.stepOf(listOf("0", "0", "0", "0", "0", "1.000000", null, null, null, null, null, null), "1"))
    }

    @Test
    fun relativeApplyWritesMissingKeysAndKeysWithoutALadder() {
        assertTrue(OptimizeRelative.shouldApply(null, "4", cascade, OptimizeRelative.MORE))
        assertTrue(OptimizeRelative.shouldApply("0", "1", null, null))
        assertTrue(OptimizeRelative.shouldApply("0", "1", cascade, null))
    }

    @Test
    fun relativeRungsCarryLaddersOnEveryOpAndValue() {
        val rung = Rung.parse(
            JSONObject(
                """{"rung_id":"r","relative":true,"levers":[
                  {"lever_type":"engine_file","payload":{"path":"p","format":"ini","ops":[
                    {"op":"set","section":"s","key":"CascadeCount","value":"4","ladder":[5,5,4,4,3,3,2,2,1,1,0,null],"lower_is":"more"}]}},
                  {"lever_type":"registry","payload":{"key":"Software\\X","values":[
                    {"name":"q","type":"dword","data":"1","ladder":["0","1"],"lower_is":"less"}]}}]}""",
            ),
        )!!
        assertTrue(rung.relative)
        val op = (rung.levers[0] as Lever.EngineFile).ops.single()
        assertEquals("more", op.lowerIs)
        assertEquals(listOf("5", "5", "4", "4", "3", "3", "2", "2", "1", "1", "0", null), op.ladder)
        val value = (rung.levers[1] as Lever.Registry).values.single()
        assertEquals(listOf("0", "1"), value.ladder)
        assertEquals("less", value.lowerIs)
        assertFalse(Rung.parse(JSONObject().put("rung_id", "c"))!!.relative)
    }

    @Test
    fun registryValuesReadBackAsLeverData() {
        listOf("dword" to "4", "qword" to "17179869184", "sz" to "High", "binary_f64le" to "0.3").forEach { (type, data) ->
            val raw = OptimizeFiles.encodeRegistryValue(type, data)
            val back = OptimizeFiles.decodeRegistryValue(type, raw)!!
            assertTrue("$type $back", OptimizeRelative.same(back, data))
        }
        assertNull(OptimizeFiles.decodeRegistryValue("dword", "\"text\""))
    }

    @Test
    fun cardActionsGoOutAsTheServerNamesThem() {
        assertEquals("accepted", OptimizeCards.wireAction(OptimizeCards.ACTION_OPTIMIZE, OptimizeCard.FIRST))
        assertEquals("optimize", OptimizeCards.wireAction(OptimizeCards.ACTION_OPTIMIZE, OptimizeCard.DONE))
        assertEquals("dismissed", OptimizeCards.wireAction(OptimizeCards.ACTION_NOT_NOW, OptimizeCard.FIRST))
        assertEquals("dismissed", OptimizeCards.wireAction(OptimizeCards.ACTION_OK, OptimizeCard.WORSE))
        assertEquals("never", OptimizeCards.wireAction(OptimizeCards.ACTION_NEVER, OptimizeCard.FIRST))
        assertEquals("happy", OptimizeCards.wireAction(OptimizeCards.ACTION_KEEP_CHANGES, OptimizeCard.EXHAUSTED))
        assertEquals("undo", OptimizeCards.wireAction(OptimizeCards.ACTION_UNDO_ALL, OptimizeCard.EXHAUSTED))
        assertEquals("keep_going", OptimizeCards.wireAction(OptimizeCards.ACTION_KEEP_GOING, OptimizeCard.HELPED))
        assertEquals("stop", OptimizeCards.wireAction(OptimizeCards.ACTION_STOP, OptimizeCard.NO_CHANGE))
        assertEquals("continue", OptimizeCards.wireAction(OptimizeCards.WIRE_CONTINUE, null))
        val allowed = setOf("accepted", "dismissed", "never", "happy", "undo", "continue", "keep_going", "stop", "optimize")
        listOf(
            OptimizeCards.ACTION_OPTIMIZE, OptimizeCards.ACTION_NOT_NOW, OptimizeCards.ACTION_NEVER, OptimizeCards.ACTION_DISMISSED,
            OptimizeCards.ACTION_KEEP_GOING, OptimizeCards.ACTION_HAPPY, OptimizeCards.ACTION_UNDO, OptimizeCards.ACTION_STOP,
            OptimizeCards.ACTION_OK, OptimizeCards.ACTION_KEEP_CHANGES, OptimizeCards.ACTION_UNDO_ALL, OptimizeCards.ACTION_MEMBERSHIP,
        ).forEach { assertTrue(it, OptimizeCards.wireAction(it, OptimizeCard.HELPED) in allowed) }
    }

    @Test
    fun perfVerdictCardSaysShownOrWhyNot() {
        assertEquals("suppressed:never", OptimizeCards.cardField(false, OptimizeCards.firstCardBlock(OptimizeLocalState(never = true), session, 0L)))
        assertEquals("suppressed:short", OptimizeCards.cardField(false, OptimizeCards.firstCardBlock(OptimizeLocalState(), session.copy(gameplaySec = 10), 0L)))
        assertEquals("shown", OptimizeCards.cardField(true, null))
        assertEquals("shown", OptimizeCards.cardBeforeJudging(OptimizeLocalState(), session, 0L))
        assertEquals("suppressed:card_waiting", OptimizeCards.cardBeforeJudging(OptimizeLocalState(card = OptimizeCard(OptimizeCard.HELPED, 0L)), session, 0L))
        assertEquals("pending", OptimizeCards.cardBeforeJudging(OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED), session, 0L))
        val props = mapOf("avg_fps" to 40.0, "perf_verdict" to mapOf("class" to "gpu", "card" to "none"))
        val out = OptimizeCards.withCard(props, "shown")
        assertEquals(mapOf("class" to "gpu", "card" to "shown"), out["perf_verdict"])
        assertEquals(40.0, out["avg_fps"])
        assertTrue(OptimizeCards.withCard(props, null) === props)
        assertTrue(OptimizeCards.withCard(mapOf("avg_fps" to 1), "shown")["perf_verdict"] == null)
    }

    @Test
    fun pickingAProfileResumesIt() {
        assertEquals(LadderState.TRYING, OptimizeLadders.picked(ProfileLadder(state = LadderState.IDLE)).state)
        val paused = OptimizeLadders.picked(ProfileLadder(state = LadderState.PAUSED, noChangeStreak = 2))
        assertEquals(LadderState.TRYING, paused.state)
        assertEquals(0, paused.noChangeStreak)
        val done = OptimizeLadders.picked(ProfileLadder(state = LadderState.DONE, noChangeStreak = 3))
        assertEquals(LadderState.TRYING, done.state)
        assertEquals(0, done.noChangeStreak)
        assertEquals(LadderState.EXHAUSTED, OptimizeLadders.picked(ProfileLadder(state = LadderState.EXHAUSTED)).state)
        val trying = ProfileLadder(state = LadderState.TRYING, noChangeStreak = 1)
        assertTrue(OptimizeLadders.picked(trying) === trying)
    }

    @Test
    fun tappingTheActiveProfileContinuesAPausedOrDoneLadder() {
        val balanced = OptimizeProfile.BALANCED
        assertEquals(OptimizeLadders.Tap.CONTINUE, OptimizeLadders.tap(balanced, balanced, OptimizeStatus.Paused))
        assertEquals(OptimizeLadders.Tap.CONTINUE, OptimizeLadders.tap(balanced, balanced, OptimizeStatus.Done))
        assertEquals(OptimizeLadders.Tap.NONE, OptimizeLadders.tap(balanced, balanced, OptimizeStatus.Trying(null)))
        assertEquals(OptimizeLadders.Tap.NONE, OptimizeLadders.tap(balanced, balanced, OptimizeStatus.TuningNext))
        assertEquals(OptimizeLadders.Tap.NONE, OptimizeLadders.tap(OptimizeProfile.OFF, OptimizeProfile.OFF, OptimizeStatus.Off))
        assertEquals(OptimizeLadders.Tap.SWITCH, OptimizeLadders.tap(balanced, OptimizeProfile.POWER, OptimizeStatus.Done))
        assertEquals(OptimizeLadders.Tap.SWITCH, OptimizeLadders.tap(balanced, OptimizeProfile.OFF, OptimizeStatus.Trying(null)))
    }

    @Test
    fun continueIsOfferedForPausedAndDoneOnly() {
        assertTrue(OptimizeLadders.canContinue(OptimizeStatus.Paused))
        assertTrue(OptimizeLadders.canContinue(OptimizeStatus.Done))
        listOf(null, OptimizeStatus.Off, OptimizeStatus.TuningNext, OptimizeStatus.Exhausted, OptimizeStatus.TrialUsed, OptimizeStatus.Measuring)
            .forEach { assertFalse(OptimizeLadders.canContinue(it)) }
    }

    @Test
    fun keepTuningIsSentAsContinue() {
        assertEquals("continue", OptimizeCards.wireAction(OptimizeCards.WIRE_CONTINUE, OptimizeCard.DONE))
    }

    // The Forest on an Odin 3, same loop: the game's own settings, then the s3 step, then the s0 step on top.
    private val forestDefaults = Metrics(
        gameplaySec = 1200, steadyFps = 54.9, stutterShare = 0.004, throttleOnsetSec = 87, cls = "gpu", confident = true,
        fpsBy5Min = listOf(56, 55, 54, 53), gpuBy5Min = listOf(33, 86, 90, 83), gpuClockBy5Min = listOf(980, 930, 850, 772),
    )
    private val forestAfter = Metrics(
        gameplaySec = 1200, steadyFps = 59.37, stutterShare = 0.004, throttleOnsetSec = 105, cls = "none", confident = false,
        fpsBy5Min = listOf(56, 59, 60, 59), gpuBy5Min = listOf(20, 72, 74, 80), gpuClockBy5Min = listOf(982, 930, 860, 798),
    )
    private val forestS0 = Metrics(
        gameplaySec = 1200, steadyFps = 59.22, stutterShare = 0.004, throttleOnsetSec = 70, cls = "none", confident = false,
        fpsBy5Min = listOf(56, 59, 60, 59), gpuBy5Min = listOf(31, 64, 61, 61), gpuClockBy5Min = listOf(960, 935, 915, 901),
    )

    @Test
    fun aThrottleOnsetIsHotOnlyWhenItCostsSomething() {
        // Defaults: windows below 0.95 x 60 and the clock falling 930 -> 772.
        assertTrue(OptimizeTarget.hot(forestDefaults, 120.0, 0))
        // s3: windows hold 59-60 but the clock falls 930 -> 798.
        assertTrue(OptimizeTarget.hot(forestAfter, 120.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, forestAfter, 120.0, 0))
        // s0: windows hold 59-60 and the clock stays flat: the onset is only the status flag.
        assertFalse(OptimizeTarget.hot(forestS0, 120.0, 0))
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, forestS0, 120.0, 0))
        // Fps falling 3 from the second window to the last counts, and so does an onset with no windows to clear it.
        assertTrue(OptimizeTarget.hot(forestS0.copy(fpsBy5Min = listOf(56, 62, 60, 59)), 120.0, 0))
        assertTrue(OptimizeTarget.hot(forestS0.copy(fpsBy5Min = emptyList()), 120.0, 0))
        assertFalse(OptimizeTarget.hot(forestS0.copy(throttleOnsetSec = null, fpsBy5Min = listOf(40, 40)), 120.0, 0))
        assertTrue(OptimizeTarget.hot(forestS0.copy(throttleOnsetSec = null, cls = "thermal_gpu", confident = true), 120.0, 0))
    }

    @Test
    fun forestS3IsKeptAndTheLadderKeepsTryingThenS0MeetsTheTarget() {
        val s3 = OptimizeJudge.judge(forestAfter, forestDefaults, isFpsCap = false, shortSessions = 0, fpsCap = 60.0)
        assertEquals(OptimizeJudge.Decision.Final(OptimizeJudge.HELPED, OptimizeJudge.REASON_FPS), s3)
        assertEquals(
            OptimizeTarget.Settled(OptimizeJudge.HELPED, OptimizeJudge.REASON_FPS, done = false),
            OptimizeTarget.settle(OptimizeProfile.PERFORMANCE, OptimizeJudge.HELPED, OptimizeJudge.REASON_FPS, forestAfter, 120.0, 0),
        )
        val s0 = OptimizeJudge.judge(forestS0, forestAfter, isFpsCap = false, shortSessions = 0, fpsCap = 60.0)
        assertEquals(OptimizeJudge.Decision.Final(OptimizeJudge.HELPED, OptimizeJudge.REASON_GPU_LOAD), s0)
        assertEquals(
            OptimizeTarget.Settled(OptimizeJudge.HELPED, OptimizeJudge.REASON_TARGET_MET, done = true),
            OptimizeTarget.settle(OptimizeProfile.PERFORMANCE, OptimizeJudge.HELPED, OptimizeJudge.REASON_GPU_LOAD, forestS0, 120.0, 0),
        )
        // The ladder is done and the game holds 60: no cap question.
        val done = ProfileLadder(state = LadderState.DONE)
        assertNull(OptimizeCards.capDecision(forestS0.cls, forestS0, done, OptimizeProfile.PERFORMANCE, 0, 120.0))
        assertEquals(40, OptimizeCards.capDecision(forestAfter.cls, forestAfter, done, OptimizeProfile.PERFORMANCE, 0, 120.0))
    }

    @Test
    fun aMetTargetKeepsTheStepAndEndsTheLadder() {
        val cool = forestAfter.copy(throttleOnsetSec = null)
        for (judged in listOf(OptimizeJudge.NO_CHANGE, OptimizeJudge.WORSE, OptimizeJudge.HELPED)) {
            assertEquals(
                judged,
                OptimizeTarget.Settled(OptimizeJudge.HELPED, OptimizeJudge.REASON_TARGET_MET, done = true),
                OptimizeTarget.settle(OptimizeProfile.PERFORMANCE, judged, OptimizeJudge.REASON_NONE, cool, 120.0, 0),
            )
        }
    }

    @Test
    fun anUnmetTargetLeavesTheJudgeStanding() {
        // The Forest s3 session: throttled from 105 s with the GPU clock falling, so Performance is not met and no_change still reverts.
        assertEquals(
            OptimizeTarget.Settled(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE, done = false),
            OptimizeTarget.settle(OptimizeProfile.PERFORMANCE, OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE, forestAfter, 120.0, 0),
        )
        // With the cap-aware judge it is helped by min_window: kept, but the ladder keeps trying.
        assertEquals(
            OptimizeTarget.Settled(OptimizeJudge.HELPED, OptimizeJudge.REASON_MIN_WINDOW, done = false),
            OptimizeTarget.settle(OptimizeProfile.PERFORMANCE, OptimizeJudge.HELPED, OptimizeJudge.REASON_MIN_WINDOW, forestAfter, 120.0, 0),
        )
        assertEquals(
            OptimizeTarget.Settled(OptimizeJudge.HELPED, OptimizeJudge.REASON_MIN_WINDOW, done = false),
            OptimizeTarget.settle(
                OptimizeProfile.PERFORMANCE, OptimizeJudge.HELPED, OptimizeJudge.REASON_MIN_WINDOW,
                forestAfter.copy(cls = "thermal_gpu", confident = true, throttleOnsetSec = null), 120.0, 0,
            ),
        )
    }

    @Test
    fun crashesAndShortRunsNeverEndTheLadder() {
        val cool = forestAfter.copy(throttleOnsetSec = null)
        for (judged in listOf(OptimizeJudge.CRASH, OptimizeJudge.NOT_COMPARABLE)) {
            assertFalse(OptimizeTarget.settle(OptimizeProfile.PERFORMANCE, judged, null, cool, 120.0, 0).done)
        }
    }

    private val capRungJson = JSONObject(
        """{"rung_id":"cap.balanced.30","version":"c1","is_fps_cap":true,"outcome":"stable","visual_cost_id":"vc_fps_held",
           "levers":[{"lever_type":"container","payload":{"set":[{"key":"fpsLimit","value":"30"}]}}]}""",
    )

    private val hot = Metrics(gameplaySec = 900, steadyFps = 40.0, stutterShare = 0.03, cls = "thermal_gpu", confidence = 0.8, confident = true)

    @Test
    fun capValueMatchesWhatTheServerServesOnPreferCap() {
        assertEquals(30, OptimizeCards.capValue(null))
        assertEquals(45, OptimizeCards.capValue(80.0))
        assertEquals(45, OptimizeCards.capValue(60.0))
        assertEquals(40, OptimizeCards.capValue(56.0))
        assertEquals(30, OptimizeCards.capValue(50.0))
        assertEquals(30, OptimizeCards.capValue(44.0))
        assertEquals(30, OptimizeCards.capValue(35.0))
        assertNull(OptimizeCards.capValue(30.0))
    }

    @Test
    fun capIsProposedOnlyWhenHeatIsTheLimit() {
        assertNull(OptimizeCards.capDecision("gpu", hot.copy(cls = "gpu"), null, null))
        assertEquals(30, OptimizeCards.capDecision("thermal_gpu", hot, null, null))
        assertEquals(30, OptimizeCards.capDecision("gpu", hot.copy(cls = "gpu", throttleOnsetSec = 300), null, null))
    }

    @Test
    fun capIsProposedBelow45FpsOrOnceSettingsRanOutOrForPower() {
        val fast = hot.copy(steadyFps = 58.0)
        val trying = ProfileLadder(state = LadderState.TRYING)
        // Settings may still hold the frame rate: no question yet.
        assertNull(OptimizeCards.capDecision("thermal_gpu", fast, trying, OptimizeProfile.PERFORMANCE))
        assertEquals(40, OptimizeCards.capDecision("thermal_gpu", fast, trying.copy(state = LadderState.EXHAUSTED), OptimizeProfile.PERFORMANCE))
        assertEquals(40, OptimizeCards.capDecision("thermal_gpu", fast, trying.copy(state = LadderState.DONE), OptimizeProfile.BALANCED))
        assertEquals(40, OptimizeCards.capDecision("thermal_gpu", fast, trying.copy(noChangeStreak = 2), OptimizeProfile.PERFORMANCE))
        assertNull(OptimizeCards.capDecision("thermal_gpu", fast, trying.copy(noChangeStreak = 1), OptimizeProfile.PERFORMANCE))
        assertEquals(40, OptimizeCards.capDecision("thermal_gpu", fast, trying, OptimizeProfile.POWER))
        assertEquals(30, OptimizeCards.capDecision("thermal_gpu", hot, trying, OptimizeProfile.PERFORMANCE))
    }

    @Test
    fun capIsNotProposedWhenACapIsAlreadyOnOrTheGameRunsAt30() {
        val capKept = KeptRung("cap.balanced.30", "c1", 1, listOf("framecap"), 0L, emptyList(), null, rung = capRungJson)
        assertNull(OptimizeCards.capDecision("thermal_gpu", hot, ProfileLadder(kept = listOf(capKept)), OptimizeProfile.BALANCED))
        val onTrial = PendingRung(Rung.parse(capRungJson)!!, 0L, null, 0, emptyList())
        assertNull(OptimizeCards.capDecision("thermal_gpu", hot, ProfileLadder(pending = onTrial), OptimizeProfile.BALANCED))
        assertNull(OptimizeCards.capDecision("thermal_gpu", hot, null, null, fpsCap = 30))
        assertEquals(30, OptimizeCards.capDecision("thermal_gpu", hot, null, null, fpsCap = 60))
        assertNull(OptimizeCards.capDecision("thermal_gpu", hot.copy(steadyFps = 29.0), null, null))
    }

    @Test
    fun capQuestionWaitsOutNotNowAndAYes() {
        val state = OptimizeLocalState()
        assertEquals(30, OptimizeCards.capQuestion(state, hot, 0, 1000L))
        assertNull(OptimizeCards.capQuestion(state.copy(capSnoozedUntil = 2000L), hot, 0, 1000L))
        assertEquals(30, OptimizeCards.capQuestion(state.copy(capSnoozedUntil = 2000L), hot, 0, 2000L))
        assertNull(OptimizeCards.capQuestion(state.copy(capPreferred = true), hot, 0, 1000L))
        assertNull(OptimizeCards.capQuestion(state.copy(never = true), hot, 0, 1000L))
    }

    @Test
    fun capFpsReadsTheKeptCap() {
        assertEquals(30, OptimizeCards.capFps(capRungJson))
        assertNull(OptimizeCards.capFps(JSONObject(capRungJson.toString()).put("is_fps_cap", false)))
        assertNull(OptimizeCards.capFps(null))
        val capKept = KeptRung("cap.balanced.30", "c1", 1, listOf("framecap"), 0L, emptyList(), null, rung = capRungJson)
        val held = OptimizeLocalState(
            activeProfile = OptimizeProfile.BALANCED,
            profiles = mapOf(OptimizeProfile.BALANCED to ProfileLadder(kept = listOf(kept("b1"), capKept))),
        )
        assertEquals(30, OptimizeStatus.heldCapFps(held))
        assertNull(OptimizeStatus.heldCapFps(held.withLadder(OptimizeProfile.BALANCED, ProfileLadder(kept = listOf(capKept.copy(applied = false))))))
        assertEquals(OptimizeCards.ACTION_CAP, OptimizeCards.wireAction(OptimizeCards.ACTION_CAP, OptimizeCard.CAP))
    }

    // The Odin 3 Forest cap trial: kept s3 at 59.4 fps throttling from 105 s, then the 45 fps cap, still at thermal status 5.
    private val forestKept = Metrics(
        gameplaySec = 1800, steadyFps = 59.4, stutterShare = 0.012, throttleOnsetSec = 105, cls = "gpu", confident = true,
        fpsBy5Min = listOf(58, 60, 59, 60, 59, 60), cpuTempMax = 100, gpuTempMax = 90, thermalPeak = 5,
    )
    private val forestCap = forestKept.copy(
        steadyFps = 44.6, stutterShare = 0.011, throttleOnsetSec = 119,
        fpsBy5Min = listOf(44, 45, 45, 45, 44, 45), cpuTempMax = 101, gpuTempMax = 91,
    )

    private fun capRecord(enabled: String?, target: String?) = JSONObject()
        .put("type", Lever.TYPE_CONTAINER).put("index", 0)
        .put("before", org.json.JSONArray().put(JSONObject().put("key", "fpsLimit").put("enabled", enabled).put("target", target)))

    @Test
    fun theTargetIgnoresTheCapOptimizePutOn() {
        val cap = PendingRung(Rung.parse(capRungJson)!!, 0L, forestKept, 0, listOf(capRecord("false", null)))
        val ladder = ProfileLadder(state = LadderState.TRYING, pending = cap)
        assertEquals(0, OptimizeTarget.userFpsCap(ladder, 45, 60))
        assertEquals(60.0, OptimizeTarget.fpsTarget(120.0, OptimizeTarget.userFpsCap(ladder, 45, 60)), 1e-9)
        assertEquals(40, OptimizeTarget.userFpsCap(ladder.copy(pending = cap.copy(levers = listOf(capRecord("true", "40")))), 30, 60))
        assertEquals(60, OptimizeTarget.userFpsCap(ladder.copy(pending = cap.copy(levers = listOf(capRecord(null, null)))), 30, 60))
        val kept = KeptRung("cap.balanced.30", "c1", 1, listOf("framecap"), 0L, listOf(capRecord("true", "50")), null, rung = capRungJson)
        assertEquals(50, OptimizeTarget.userFpsCap(ProfileLadder(kept = listOf(kept)), 30, 60))
        // A cap whose profile is not on leaves the container as the user set it.
        assertEquals(30, OptimizeTarget.userFpsCap(ProfileLadder(kept = listOf(kept.copy(applied = false))), 30, 60))
        assertEquals(45, OptimizeTarget.userFpsCap(ProfileLadder(), 45, 60))
        assertEquals(45, OptimizeTarget.userFpsCap(null, 45, 60))
        // Against our own 45 the cap session held the target; against the user's own it does not.
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, forestCap, 120.0, 45))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, forestCap, 120.0, OptimizeTarget.userFpsCap(ladder, 45, 60)))
    }

    @Test
    fun theForestCapDidNotHelpAndComesOff() {
        val decision = OptimizeJudge.judge(forestCap, forestKept, isFpsCap = true, shortSessions = 0, fpsCap = 60.0)
        assertEquals(OptimizeJudge.Decision.Final(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE), decision)
        // A cap never settles as target_met, even where the session would meet the profile's target.
        assertEquals(
            OptimizeTarget.Settled(OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE, done = false),
            OptimizeTarget.settle(
                OptimizeProfile.PERFORMANCE, OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE, forestCap, 120.0, 45, isFpsCap = true,
            ),
        )
        assertEquals(
            OptimizeTarget.Settled(OptimizeJudge.HELPED, OptimizeJudge.REASON_TARGET_MET, done = true),
            OptimizeTarget.settle(OptimizeProfile.PERFORMANCE, OptimizeJudge.NO_CHANGE, OptimizeJudge.REASON_NONE, forestCap, 120.0, 45),
        )
    }

    @Test
    fun aCapIsHelpedOnlyByLaterThrottlingFewerStuttersOrACoolerDevice() {
        fun verdict(after: Metrics) = OptimizeJudge.verdict(forestKept, after, isFpsCap = true, fpsCap = 60.0)
        val throttle = OptimizeJudge.Verdict(OptimizeJudge.HELPED, OptimizeJudge.REASON_THROTTLE)
        assertEquals(throttle, verdict(forestCap.copy(throttleOnsetSec = null)))
        assertEquals(throttle, verdict(forestCap.copy(throttleOnsetSec = 285)))
        assertEquals(OptimizeJudge.REASON_STUTTER, verdict(forestCap.copy(stutterShare = 0.006)).reason)
        assertEquals(
            OptimizeJudge.Verdict(OptimizeJudge.HELPED, OptimizeJudge.REASON_TEMP),
            verdict(forestCap.copy(gpuTempMax = 87, thermalPeak = 4)),
        )
        // Cooler without a lower thermal status, or a lower status without 3 °C, is no change.
        assertEquals(OptimizeJudge.NO_CHANGE, verdict(forestCap.copy(gpuTempMax = 85)).result)
        assertEquals(OptimizeJudge.NO_CHANGE, verdict(forestCap.copy(cpuTempMax = 98, thermalPeak = 4)).result)
        // Lower fps and more stutter are what a cap may cost; neither makes it worse.
        assertEquals(OptimizeJudge.NO_CHANGE, verdict(forestCap.copy(stutterShare = 0.03)).result)
    }

    @Test
    fun peakTemperaturesComeFromTheRunRecord() {
        val run = JSONObject()
            .put("avg_fps", 44.6).put("session_length", 1800).put("thermal_peak", 5)
            .put(
                "perf_by_5min",
                org.json.JSONArray()
                    .put(JSONObject().put("cpu_temp_max", 99).put("gpu_temp_max", 88).put("thermal_status_max", 4))
                    .put(JSONObject().put("cpu_temp_max", 101).put("gpu_temp_max", 91).put("thermal_status_max", 5)),
            )
        val m = Metrics.fromRun(run)
        assertEquals(101, m.cpuTempMax)
        assertEquals(91, m.gpuTempMax)
        assertEquals(5, m.thermalPeak)
        assertEquals(m, Metrics.fromJson(JSONObject(m.toJson().toString())))
        val noPeak = JSONObject(run.toString()).apply { remove("thermal_peak") }
        assertEquals(5, Metrics.fromRun(noPeak).thermalPeak)
        assertNull(Metrics.fromRun(JSONObject().put("session_length", 600)).cpuTempMax)
    }

    @Test
    fun restoreStartsEveryGoalOverButKeepsCrashedSteps() {
        val crashed = TriedRung("c1", "7", OptimizeJudge.CRASH)
        val state = OptimizeLocalState(
            activeProfile = OptimizeProfile.BALANCED,
            profiles = mapOf(
                OptimizeProfile.BALANCED to ProfileLadder(
                    state = LadderState.TRYING,
                    tried = listOf(TriedRung("b1", "1", OptimizeJudge.NO_CHANGE), crashed, TriedRung("b2", "2", OptimizeJudge.UNDO)),
                    bad = listOf("c1"),
                    noChangeStreak = 2,
                    baselineOverride = session.copy(steadyFps = 50.0),
                ),
                OptimizeProfile.PERFORMANCE to ProfileLadder(
                    state = LadderState.WAITING,
                    tried = listOf(TriedRung("p1", "3", OptimizeJudge.WORSE)),
                    noChangeStreak = 1,
                    baselineOverride = session,
                ),
            ),
            noRule = NoRuleHold(Long.MAX_VALUE, "r1", NextRungResponse.STATUS_NO_RULE),
        )
        val next = OptimizeLadders.restarted(state, OptimizeProfile.BALANCED, LadderState.PAUSED, session)
        val balanced = next.ladder(OptimizeProfile.BALANCED)
        assertEquals(LadderState.PAUSED, balanced.state)
        assertEquals(listOf(crashed), balanced.tried)
        assertEquals(listOf("c1"), balanced.bad)
        assertEquals(0, balanced.noChangeStreak)
        assertEquals(session, balanced.baselineOverride)
        assertTrue(balanced.kept.isEmpty())
        val performance = next.ladder(OptimizeProfile.PERFORMANCE)
        assertEquals(LadderState.IDLE, performance.state)
        assertTrue(performance.tried.isEmpty())
        assertEquals(0, performance.noChangeStreak)
        assertNull(performance.baselineOverride)
        assertNull(next.noRule)
        assertTrue(balanced.repeats(Rung.parse(JSONObject().put("rung_id", "c1").put("version", "7"))!!))
        assertFalse(balanced.repeats(Rung.parse(JSONObject().put("rung_id", "b1").put("version", "1"))!!))
    }
}
