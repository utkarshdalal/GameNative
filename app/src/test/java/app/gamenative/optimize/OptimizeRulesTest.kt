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
            activeProfile = OptimizeProfile.STABILITY,
            profiles = mapOf(OptimizeProfile.STABILITY to ProfileLadder(kept = listOf(kept("s1")), pending = pending("s2"))),
        )
        val plan = SwitchPlan.of(state, null)
        assertEquals(listOf(OptimizeProfile.STABILITY), plan.revertPending)
        assertEquals(listOf(OptimizeProfile.STABILITY to "s1"), plan.revertKept)
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
    fun performanceIsMetNearTheRefreshOrTheCap() {
        val good = Metrics(gameplaySec = 900, steadyFps = 55.0, cls = "none", confident = true)
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good, 120.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(steadyFps = 50.0), 120.0, 0))
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(steadyFps = 41.0), 60.0, 45))
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(steadyFps = 28.0), 30.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(cls = "gpu"), 60.0, 0))
        assertTrue(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(cls = "gpu", confident = false), 60.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.PERFORMANCE, good.copy(steadyFps = null), 60.0, 0))
    }

    @Test
    fun balancedNeedsThirtyFpsFewStuttersAndNoThrottle() {
        val m = Metrics(gameplaySec = 900, steadyFps = 31.0, stutterShare = 0.01)
        assertTrue(OptimizeTarget.met(OptimizeProfile.BALANCED, m, 60.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.BALANCED, m.copy(steadyFps = 29.0), 60.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.BALANCED, m.copy(stutterShare = 0.02), 60.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.BALANCED, m.copy(throttleOnsetSec = 400), 60.0, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.BALANCED, m.copy(stutterShare = null), 60.0, 0))
    }

    @Test
    fun stabilityNeedsUnderOnePercentStutter() {
        assertTrue(OptimizeTarget.met(OptimizeProfile.STABILITY, Metrics(gameplaySec = 900, stutterShare = 0.009), null, 0))
        assertFalse(OptimizeTarget.met(OptimizeProfile.STABILITY, Metrics(gameplaySec = 900, stutterShare = 0.01), null, 0))
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
        assertTrue(OptimizeCards.offerFirst(OptimizeLocalState(), session.copy(cls = OptimizeCards.SYNC_SPIN), 0L))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(never = true), session, 0L))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED), session, 0L))
        assertFalse(OptimizeCards.offerFirst(OptimizeLocalState(profiles = mapOf(OptimizeProfile.POWER to ProfileLadder())), session, 0L))
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
        assertEquals(OptimizeCard.OUTCOME_FPS, OptimizeCards.helpedOutcome("fps", OptimizeProfile.STABILITY))
        assertEquals(OptimizeCard.OUTCOME_STUTTER, OptimizeCards.helpedOutcome(null, OptimizeProfile.BALANCED))
        assertEquals(OptimizeCard.OUTCOME_STABLE, OptimizeCards.helpedOutcome(null, OptimizeProfile.POWER))
    }
}
