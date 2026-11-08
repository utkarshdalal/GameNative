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
}
