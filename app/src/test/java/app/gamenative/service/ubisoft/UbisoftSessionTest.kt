package app.gamenative.service.ubisoft

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbisoftSessionTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    private fun creds(expiresIn: Long, rememberMe: String = "rm") = UbisoftCredentials(
        ticket = "t", sessionId = "s", expiresAt = now + expiresIn, userId = "u", profileId = "p",
        nameOnPlatform = "name", email = "e@example.com", country = "US", rememberMeTicket = rememberMe,
    )

    @Test fun lifetimeComesFromTheServerClock() {
        // the server's clock is a day off from ours; only the difference counts
        val json = JSONObject()
            .put("ticket", "abc").put("sessionId", "sid").put("userId", "uid").put("profileId", "pid")
            .put("nameOnPlatform", "Player").put("rememberMeTicket", "rm2")
            .put("serverTime", "2026-11-16T09:00:00.0000000Z")
            .put("expiration", "2026-11-16T12:00:00.0000000Z")
        val c = UbisoftCredentials.fromSessionResponse(json, now)
        assertEquals(now + 3 * hour, c.expiresAt)
        assertEquals("abc", c.ticket)
        assertEquals("rm2", c.rememberMeTicket)
    }

    @Test fun aRenewalKeepsWhatItDoesNotRepeat() {
        val previous = creds(hour)
        val c = UbisoftCredentials.fromSessionResponse(JSONObject().put("ticket", "new"), now, previous)
        assertEquals("new", c.ticket)
        assertEquals("rm", c.rememberMeTicket)
        assertEquals("e@example.com", c.email)
        assertEquals(now + UbisoftCredentials.FALLBACK_LIFETIME_MS, c.expiresAt)
    }

    @Test fun credentialsRoundTrip() {
        val c = creds(hour)
        assertEquals(c, UbisoftCredentials.fromJson(JSONObject(c.toJson().toString())))
    }

    @Test fun webLoginIsReadFromPageStorage() {
        val storage = JSONArray()
            .put(JSONObject().put("theme", "dark"))
            .put(JSONObject().put("rememberMeTicket", "rm"))
            .put(JSONObject().put("ticket", "t").put("sessionId", "s").put("profileId", "p").put("nameOnPlatform", "n"))
        val login = UbisoftWebLogin.fromStorage(storage.toString())!!
        assertEquals("t", login.ticket)
        assertEquals("s", login.sessionId)
        assertEquals("rm", login.rememberMeTicket)
        assertEquals("p", login.userId)
        assertNull(UbisoftWebLogin.fromStorage(JSONArray().put(JSONObject().put("ticket", "t")).toString()))
        assertNull(UbisoftWebLogin.fromStorage("not json"))
    }

    @Test fun aFreshTicketNeedsNothing() {
        assertTrue(UbisoftSessionPolicy.isFresh(now, creds(31 * 60 * 1000L)))
        assertEquals(emptyList<UbisoftSessionPolicy.Step>(), UbisoftSessionPolicy.steps(now, creds(hour)))
    }

    @Test fun aShortTicketIsExtendedThenRenewed() {
        val c = creds(10 * 60 * 1000L)
        assertFalse(UbisoftSessionPolicy.isFresh(now, c))
        assertEquals(listOf(UbisoftSessionPolicy.Step.EXTEND, UbisoftSessionPolicy.Step.RENEW), UbisoftSessionPolicy.steps(now, c))
        assertEquals(listOf(UbisoftSessionPolicy.Step.EXTEND), UbisoftSessionPolicy.steps(now, creds(10 * 60 * 1000L, rememberMe = "")))
    }

    @Test fun anExpiredTicketCanOnlyBeRenewed() {
        assertEquals(listOf(UbisoftSessionPolicy.Step.RENEW), UbisoftSessionPolicy.steps(now, creds(-hour)))
        assertEquals(emptyList<UbisoftSessionPolicy.Step>(), UbisoftSessionPolicy.steps(now, creds(-hour, rememberMe = "")))
    }

    @Test fun aNetworkFailureNeverBlocksAValidTicket() {
        assertEquals(
            UbisoftSessionPolicy.Verdict.USE_EXISTING,
            UbisoftSessionPolicy.afterFailedRefresh(now, creds(10 * 60 * 1000L), extendRejected = false, renewRejected = false),
        )
    }

    @Test fun onlyAnExplicitRejectionSignsOut() {
        val p = UbisoftSessionPolicy
        assertEquals(UbisoftSessionPolicy.Verdict.SIGN_IN_AGAIN, p.afterFailedRefresh(now, creds(hour / 6), extendRejected = true, renewRejected = true))
        assertEquals(UbisoftSessionPolicy.Verdict.SIGN_IN_AGAIN, p.afterFailedRefresh(now, creds(-hour), extendRejected = false, renewRejected = true))
        assertEquals(UbisoftSessionPolicy.Verdict.SIGN_IN_AGAIN, p.afterFailedRefresh(now, creds(hour / 6, ""), extendRejected = true, renewRejected = false))
        // the ticket was refused but remember-me could not be reached: do not sign out, do not launch
        assertEquals(UbisoftSessionPolicy.Verdict.UNAVAILABLE, p.afterFailedRefresh(now, creds(hour / 6), extendRejected = true, renewRejected = false))
        // expired and offline
        assertEquals(UbisoftSessionPolicy.Verdict.UNAVAILABLE, p.afterFailedRefresh(now, creds(-hour), extendRejected = false, renewRejected = false))
        // expired with nothing to renew it with
        assertEquals(UbisoftSessionPolicy.Verdict.SIGN_IN_AGAIN, p.afterFailedRefresh(now, creds(-hour, ""), extendRejected = false, renewRejected = false))
    }

    @Test fun clientErrorsAreRejectionsButTimeoutsAreNot() {
        assertTrue(UbisoftAuthManager.isRejection(401))
        assertTrue(UbisoftAuthManager.isRejection(400))
        assertFalse(UbisoftAuthManager.isRejection(429))
        assertFalse(UbisoftAuthManager.isRejection(408))
        assertFalse(UbisoftAuthManager.isRejection(503))
    }
}
