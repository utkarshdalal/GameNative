package app.gamenative.optimize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntitlementLineTest {

    private fun line(entitlement: Entitlement?, exhausted: Boolean = false, signedIn: Boolean = true, member: Boolean = false) =
        OptimizeStatus.entitlementLine(entitlement, exhausted, signedIn, member)

    @Test
    fun membersNeverSeeTrialText() {
        assertEquals(EntitlementLine.Member, line(Entitlement(Entitlement.MEMBER, 0, 3), exhausted = true, signedIn = false))
        assertEquals(EntitlementLine.Member, line(Entitlement(Entitlement.NONE, 3, 3), exhausted = true, member = true))
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
    fun theGamesOwnEntitlementWinsOverTheAppWideOne() {
        val own = Entitlement(Entitlement.TRIAL, 1, 3)
        val appWide = Entitlement(Entitlement.TRIAL, 2, 3)
        val notAsked: Entitlement? = null
        assertEquals(own, own.orAppWide(appWide))
        assertEquals(appWide, notAsked.orAppWide(appWide))
        assertNull(notAsked.orAppWide(null))
        // A game that has not asked yet still shows the trial line from another game's answer.
        assertEquals(EntitlementLine.Trial(2, 3), line(notAsked.orAppWide(appWide)))
    }

    @Test
    fun theOutcomeAnswerCarriesTheEntitlementAfterTheInsert() {
        val ack = OutcomeAck.parse(org.json.JSONObject("{\"ok\":true,\"entitlement\":{\"state\":\"trial\",\"trial_games_used\":1,\"trial_games_limit\":3}}"))
        assertEquals(OutcomeAck(true, Entitlement(Entitlement.TRIAL, 1, 3)), ack)
        assertEquals(OutcomeAck(true, null), OutcomeAck.parse(org.json.JSONObject("{\"ok\":true}")))
        assertEquals(OutcomeAck(false, null), OutcomeAck.parse(null))
        assertEquals(EntitlementLine.Trial(1, 3), line(ack.entitlement))
    }
}
