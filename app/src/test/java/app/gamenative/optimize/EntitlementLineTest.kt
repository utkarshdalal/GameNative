package app.gamenative.optimize

import app.gamenative.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementLineTest {

    private fun line(entitlement: Entitlement?, exhausted: Boolean = false, signedIn: Boolean = true, member: Boolean = false, plan: String? = null) =
        OptimizeStatus.entitlementLine(entitlement, exhausted, signedIn, member, plan)

    @Test
    fun membersNeverSeeTrialText() {
        assertEquals(EntitlementLine.Unlimited, line(Entitlement(Entitlement.MEMBER, 0, 3), exhausted = true))
        assertEquals(EntitlementLine.Unlimited, line(Entitlement(Entitlement.NONE, 3, 3), exhausted = true, member = true, plan = "pro"))
    }

    @Test
    fun signedOutNeverShowsAStoredEntitlement() {
        val pro = parse("{\"state\":\"member\",\"tier\":\"pro\",\"games_used\":9,\"games_quota\":null}")
        val monthly = parse("{\"tier\":\"monthly\",\"games_used\":1,\"games_quota\":3}")
        assertEquals(EntitlementLine.Offer(3), line(pro, signedIn = false, member = true, plan = "none"))
        assertEquals(EntitlementLine.Offer(3), line(monthly, signedIn = false))
        assertEquals(EntitlementLine.Offer(3), line(Entitlement(Entitlement.NONE, 3, 3), exhausted = true, signedIn = false))
    }

    @Test
    fun aMemberWithNoEntitlementYetReadsTheAccountsOwnPlan() {
        assertEquals(EntitlementLine.Unlimited, line(null, member = true, plan = "pro"))
        assertEquals(EntitlementLine.Unlimited, line(null, member = true, plan = "patron"))
        assertNull(line(null, member = true, plan = "basic"))
        assertNull(line(null, member = true, plan = null))
        assertNull(line(null, member = true, plan = "none"))
        // An answer from before the account changed is dropped, so a basic member waits for the first server answer.
        assertNull(line(Entitlement(Entitlement.TRIAL, 1, 3).orAppWide(1_000L, null, 0L, 2_000L), member = true, plan = "basic"))
    }

    @Test
    fun signedOutOffersTheServerLimitOrThree() {
        assertEquals(EntitlementLine.Offer(3), line(null, signedIn = false))
        assertEquals(EntitlementLine.Offer(5), line(Entitlement(Entitlement.TRIAL, 1, 5), signedIn = false))
    }

    @Test
    fun trialShowsUsedOfLimitFromTheServer() {
        assertEquals(EntitlementLine.Trial(1, 3), line(Entitlement(Entitlement.TRIAL, 1, 3)))
        assertNull(line(null))
    }

    @Test
    fun exhaustedWhenTheServerSaysSoOrNoGamesAreLeft() {
        assertEquals(EntitlementLine.Exhausted(3), line(Entitlement(Entitlement.NONE, 3, 3)))
        assertEquals(EntitlementLine.Exhausted(4), line(Entitlement(Entitlement.TRIAL, 4, 4)))
        assertEquals(EntitlementLine.Exhausted(3), line(Entitlement(Entitlement.TRIAL, 1, 3), exhausted = true))
    }

    @Test
    fun theNewerOfTheGamesOwnAndTheAppWideEntitlementWins() {
        val own = Entitlement(Entitlement.TRIAL, 1, 3)
        val appWide = Entitlement(Entitlement.TRIAL, 2, 3)
        val notAsked: Entitlement? = null
        assertEquals(own, own.orAppWide(2_000L, appWide, 1_000L, 0L))
        assertEquals(appWide, own.orAppWide(1_000L, appWide, 2_000L, 0L))
        assertEquals(own, own.orAppWide(1_000L, appWide, 1_000L, 0L))
        // A missing time counts as the oldest.
        assertEquals(own, own.orAppWide(1_000L, appWide, 0L, 0L))
        assertEquals(appWide, own.orAppWide(0L, appWide, 1_000L, 0L))
        assertEquals(appWide, notAsked.orAppWide(0L, appWide, 1_000L, 0L))
        assertNull(notAsked.orAppWide(0L, null, 0L, 0L))
        // A game that has not asked yet still shows the trial line from another game's answer.
        assertEquals(EntitlementLine.Trial(2, 3), line(notAsked.orAppWide(0L, appWide, 1_000L, 0L)))
    }

    @Test
    fun anEntitlementFromBeforeTheAccountChangedIsIgnored() {
        val own = Entitlement(Entitlement.NONE, 3, 3)
        val appWide = Entitlement(Entitlement.TRIAL, 0, 3)
        assertEquals(appWide, own.orAppWide(1_000L, appWide, 3_000L, 2_000L))
        assertNull(own.orAppWide(1_000L, null, 0L, 2_000L))
        assertNull(own.orAppWide(1_000L, appWide, 1_500L, 2_000L))
        assertNull(own.orAppWide(0L, null, 0L, 2_000L))
        assertEquals(own, own.orAppWide(2_000L, appWide, 1_500L, 2_000L))
        // With nothing from this account, a signed-in user sees no count and a signed-out one the offer.
        assertNull(line(own.orAppWide(1_000L, null, 0L, 2_000L)))
        assertEquals(EntitlementLine.Offer(3), line(own.orAppWide(1_000L, null, 0L, 2_000L), signedIn = false))
    }

    @Test
    fun aTrialUsedAnswerFromBeforeTheAccountChangedNoLongerHoldsTheStatus() {
        val ladder = ProfileLadder(state = LadderState.TRYING, lastStatus = NextRungResponse.STATUS_ENTITLEMENT_REQUIRED)
        val state = OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED, profiles = mapOf(OptimizeProfile.BALANCED to ladder), entitlementCheckedAt = 1_000L)
        assertEquals(OptimizeStatus.TrialUsed, OptimizeStatus.of(state))
        assertEquals(OptimizeStatus.TrialUsed, OptimizeStatus.of(state, accountChangedAt = 500L))
        assertEquals(OptimizeStatus.TuningNext, OptimizeStatus.of(state, accountChangedAt = 2_000L))
    }

    @Test
    fun theOutcomeAnswerCarriesTheEntitlementAfterTheInsert() {
        val ack = OutcomeAck.parse(org.json.JSONObject("{\"ok\":true,\"entitlement\":{\"state\":\"trial\",\"trial_games_used\":1,\"trial_games_limit\":3}}"))
        assertEquals(OutcomeAck(true, Entitlement(Entitlement.TRIAL, 1, 3)), ack)
        assertEquals(OutcomeAck(true, null), OutcomeAck.parse(org.json.JSONObject("{\"ok\":true}")))
        assertEquals(OutcomeAck(false, null), OutcomeAck.parse(null))
        assertEquals(EntitlementLine.Trial(1, 3), line(ack.entitlement))
    }

    private fun parse(json: String) = Entitlement.fromJson(JSONObject(json))!!

    @Test
    fun newPlanFieldsParseLenientlyAndRoundTrip() {
        val monthly = parse("{\"tier\":\"monthly\",\"games_used\":2,\"games_quota\":3,\"period_resets_at\":\"2026-12-01T00:00:00Z\"}")
        assertEquals(Entitlement.Plan.MONTHLY, monthly.plan)
        assertEquals(2, monthly.used)
        assertEquals(3, monthly.quota)
        assertEquals(1_796_083_200_000L, monthly.periodResetsAt)
        assertEquals(monthly, Entitlement.fromJson(JSONObject(monthly.toJson().toString())))

        val unlimited = parse("{\"state\":\"member\",\"tier\":\"pro\",\"games_used\":9,\"games_quota\":null}")
        assertTrue(unlimited.quotaUnlimited)
        assertEquals(Entitlement.Plan.UNLIMITED, unlimited.plan)
        assertEquals(unlimited, Entitlement.fromJson(JSONObject(unlimited.toJson().toString())))

        // Seconds, milliseconds and a bare date are all read; garbage is dropped, not fatal.
        assertEquals(1_796_083_200_000L, parse("{\"tier\":\"monthly\",\"period_resets_at\":1796083200}").periodResetsAt)
        assertEquals(1_796_083_200_000L, parse("{\"tier\":\"monthly\",\"period_resets_at\":1796083200000}").periodResetsAt)
        assertEquals(1_796_083_200_000L, parse("{\"tier\":\"monthly\",\"period_resets_at\":\"2026-12-01\"}").periodResetsAt)
        assertNull(parse("{\"tier\":\"monthly\",\"period_resets_at\":\"soon\"}").periodResetsAt)
        assertEquals(3, parse("{\"tier\":\"monthly\",\"games_quota\":\"x\"}").quota)

        // Today's answer, with no new fields, is the free trial it always was.
        val old = parse("{\"state\":\"trial\",\"trial_games_used\":1,\"trial_games_limit\":3}")
        assertEquals(Entitlement.Plan.FREE, old.plan)
        assertEquals(1, old.used)
        assertEquals(3, old.quota)
        assertEquals(Entitlement.Plan.UNLIMITED, Entitlement(Entitlement.MEMBER, 0, 0).plan)
        assertEquals(Entitlement.Plan.MONTHLY, Entitlement(Entitlement.MEMBER, 0, 0, gamesQuota = 3).plan)
    }

    @Test
    fun linesFollowTheTier() {
        val reset = 1_796_083_200_000L
        assertEquals(EntitlementLine.Trial(1, 3), line(parse("{\"tier\":\"free\",\"games_used\":1,\"games_quota\":3}")))
        assertEquals(EntitlementLine.Exhausted(3), line(parse("{\"tier\":\"free\",\"games_used\":3,\"games_quota\":3}")))
        assertEquals(EntitlementLine.Monthly(1, 3, reset), line(parse("{\"tier\":\"monthly\",\"games_used\":1,\"games_quota\":3,\"period_resets_at\":$reset}")))
        assertEquals(EntitlementLine.Monthly(3, 3, null), line(parse("{\"tier\":\"monthly\",\"games_used\":1,\"games_quota\":3}"), exhausted = true))
        assertEquals(EntitlementLine.Unlimited, line(parse("{\"tier\":\"unlimited\",\"games_quota\":null}"), exhausted = true))
        // The app's member flag only stands in when the server has not named a tier.
        assertEquals(EntitlementLine.Monthly(2, 3, null), line(parse("{\"tier\":\"monthly\",\"games_used\":2,\"games_quota\":3}"), member = true))
        assertEquals(EntitlementLine.Unlimited, line(null, member = true, plan = "pro"))
    }

    @Test
    fun proAccountsReadUnlimitedFromTheTierOrTheOlderFields() {
        val unlimitedPill = PlanText(R.string.optimize_plan_unlimited, args = listOf(PlanName.PRO))
        val live = parse(
            "{\"state\":\"member\",\"trial_games_used\":4,\"trial_games_limit\":0,\"tier\":\"optimize_unlimited\"," +
                "\"games_used\":4,\"games_quota\":null,\"period_resets_at\":null}",
        )
        assertEquals(Entitlement.Plan.UNLIMITED, live.plan)
        assertEquals(EntitlementLine.Unlimited, line(live))
        assertEquals(unlimitedPill, OptimizeStatus.entitlementPill(line(live)!!))
        // The tier alone is enough, whatever the quota field says.
        assertEquals(EntitlementLine.Unlimited, line(parse("{\"tier\":\"optimize_unlimited\"}"), member = false))
        // Before tiers: a member with no trial limit.
        val older = parse("{\"state\":\"member\",\"trial_games_used\":0,\"trial_games_limit\":0}")
        assertEquals(EntitlementLine.Unlimited, line(older))
        assertEquals(unlimitedPill, OptimizeStatus.entitlementPill(line(older, signedIn = true)!!))
        // A monthly plan with its games used stays a monthly line.
        val basicUsed = parse("{\"state\":\"none\",\"tier\":\"optimize_basic\",\"games_used\":3,\"games_quota\":3}")
        assertEquals(EntitlementLine.Monthly(3, 3, null), line(basicUsed))
    }

    @Test
    fun pillTextForEachPlan() {
        assertEquals(PlanText(R.string.optimize_plan_unlimited, args = listOf(PlanName.PRO)), OptimizeStatus.entitlementPill(EntitlementLine.Unlimited))
        assertEquals(PlanText(R.string.optimize_plan_signed_out), OptimizeStatus.entitlementPill(EntitlementLine.Offer(3)))
        assertEquals(PlanText(R.plurals.optimize_trial_progress, 3, listOf(1, 3)), OptimizeStatus.entitlementPill(EntitlementLine.Trial(1, 3)))
        assertEquals(PlanText(R.plurals.optimize_trial_progress, 3, listOf(3, 3)), OptimizeStatus.entitlementPill(EntitlementLine.Exhausted(3)))
        assertEquals(
            PlanText(R.plurals.optimize_plan_monthly_resets, 3, listOf(2, 3), 42L),
            OptimizeStatus.entitlementPill(EntitlementLine.Monthly(2, 3, 42L)),
        )
        assertEquals(PlanText(R.plurals.optimize_plan_monthly, 3, listOf(2, 3)), OptimizeStatus.entitlementPill(EntitlementLine.Monthly(2, 3, null)))
    }

    @Test
    fun upsellShowsOnlyWhenSignedOutOrTheQuotaIsUsed() {
        assertTrue(OptimizeStatus.showsUpsell(EntitlementLine.Offer(3)))
        assertTrue(OptimizeStatus.showsUpsell(EntitlementLine.Exhausted(3)))
        assertTrue(OptimizeStatus.showsUpsell(EntitlementLine.Monthly(3, 3, null)))
        assertFalse(OptimizeStatus.showsUpsell(EntitlementLine.Monthly(2, 3, null)))
        assertFalse(OptimizeStatus.showsUpsell(EntitlementLine.Trial(2, 3)))
        assertFalse(OptimizeStatus.showsUpsell(EntitlementLine.Unlimited))
        assertFalse(OptimizeStatus.showsUpsell(null))

        assertEquals(PlanText(R.plurals.optimize_trial_offer, 3, listOf(3)), OptimizeStatus.upsellSubtitle(EntitlementLine.Offer(3)))
        assertEquals(PlanText(R.plurals.optimize_upsell_free_used, 3, listOf(3)), OptimizeStatus.upsellSubtitle(EntitlementLine.Exhausted(3)))
        assertEquals(
            PlanText(R.plurals.optimize_upsell_monthly_used_resets, 5, listOf(5), 42L),
            OptimizeStatus.upsellSubtitle(EntitlementLine.Monthly(5, 5, 42L)),
        )
        assertNull(OptimizeStatus.upsellSubtitle(EntitlementLine.Monthly(1, 5, 42L)))
        assertEquals(5, OptimizeStatus.upsellMonthlyGames(EntitlementLine.Monthly(5, 5, null)))
        assertEquals(Entitlement.DEFAULT_MONTHLY_GAMES, OptimizeStatus.upsellMonthlyGames(EntitlementLine.Exhausted(3)))
        assertEquals(
            PlanText(R.plurals.optimize_upsell_plan_line, 3, listOf(PlanName.BASIC, 3, PlanName.PRO)),
            OptimizeStatus.upsellPlanLine(EntitlementLine.Offer(3)),
        )
        assertEquals(EntitlementLine.Unlimited, OptimizeStatus.entitlementLine(Entitlement(Entitlement.MEMBER, 0, 0, tier = "patron", gamesQuota = 3), false, true, false, null))
    }

    @Test
    fun statusPillsUseTheShortWords() {
        assertEquals(R.string.optimize_pill_measuring, OptimizeStatus.pillRes(OptimizeStatus.Measuring))
        assertEquals(R.string.optimize_pill_tuning, OptimizeStatus.pillRes(OptimizeStatus.Trying(null)))
        assertEquals(R.string.optimize_pill_tuning, OptimizeStatus.pillRes(OptimizeStatus.TuningNext))
        assertEquals(R.string.optimize_status_paused, OptimizeStatus.pillRes(OptimizeStatus.Paused))
        assertEquals(R.string.optimize_pill_done, OptimizeStatus.pillRes(OptimizeStatus.Done))
        assertEquals(R.string.optimize_pill_waiting, OptimizeStatus.pillRes(OptimizeStatus.Waiting))
        assertEquals(R.string.optimize_pill_trial_used, OptimizeStatus.pillRes(OptimizeStatus.TrialUsed))
        assertNull(OptimizeStatus.pillRes(OptimizeStatus.Off))
    }

    @Test
    fun usedUpGamesSayTrialOnlyOnTheFreePlan() {
        val free = line(Entitlement(Entitlement.NONE, 3, 3, tier = "free"), exhausted = true)
        val basic = line(Entitlement(Entitlement.MEMBER, 0, 0, tier = "basic", gamesUsed = 5, gamesQuota = 5), exhausted = true)
        val untiered = line(Entitlement(Entitlement.MEMBER, 0, 0, gamesUsed = 5, gamesQuota = 5), exhausted = true)
        assertEquals(R.string.optimize_pill_trial_used, OptimizeStatus.pillRes(OptimizeStatus.TrialUsed, free))
        assertEquals(R.string.optimize_pill_month_used, OptimizeStatus.pillRes(OptimizeStatus.TrialUsed, basic))
        assertEquals(R.string.optimize_pill_month_used, OptimizeStatus.pillRes(OptimizeStatus.TrialUsed, untiered))
        assertEquals(R.string.optimize_pill_trial_used, OptimizeStatus.pillRes(OptimizeStatus.TrialUsed, null))
        assertEquals(R.string.optimize_pill_tuning, OptimizeStatus.pillRes(OptimizeStatus.TuningNext, basic))
    }

    @Test
    fun statusSentenceIsShownOnlyWhenItSaysMoreThanTheHeader() {
        assertFalse(OptimizeStatus.showsSentence(OptimizeStatus.TuningNext))
        assertFalse(OptimizeStatus.showsSentence(OptimizeStatus.Paused))
        assertFalse(OptimizeStatus.showsSentence(OptimizeStatus.Measuring))
        assertTrue(OptimizeStatus.showsSentence(OptimizeStatus.Trying(null)))
        assertTrue(OptimizeStatus.showsSentence(OptimizeStatus.RetryNext))
        assertTrue(OptimizeStatus.showsSentence(OptimizeStatus.NotStarted))
        assertTrue(OptimizeStatus.showsSentence(OptimizeStatus.Done))
        assertTrue(OptimizeStatus.showsSentence(OptimizeStatus.Waiting))
        assertTrue(OptimizeStatus.showsSentence(OptimizeStatus.Never))
    }
}
