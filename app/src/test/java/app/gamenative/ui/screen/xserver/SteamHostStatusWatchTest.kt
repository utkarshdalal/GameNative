package app.gamenative.ui.screen.xserver

import app.gamenative.ui.screen.xserver.SteamHostStatusWatch.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamHostStatusWatchTest {

    @Test
    fun failureReason_connectionForLogonAndSync() {
        assertEquals(FailureReason.CONNECTION, SteamHostStatusWatch.failureReason("failed logon"))
        assertEquals(FailureReason.CONNECTION, SteamHostStatusWatch.failureReason("failed logon eresult=0"))
        assertEquals(FailureReason.CONNECTION, SteamHostStatusWatch.failureReason("failed sync"))
        assertEquals(FailureReason.CONNECTION, SteamHostStatusWatch.failureReason("failed sync status=9"))
    }

    @Test
    fun failureReason_refusedOnlyWithLaunchStatus() {
        assertEquals(FailureReason.REFUSED, SteamHostStatusWatch.failureReason("failed launch status=9"))
        assertEquals(FailureReason.OTHER, SteamHostStatusWatch.failureReason("failed launch"))
    }

    @Test
    fun failureReason_otherForSetupStartAndUnknown() {
        assertEquals(FailureReason.OTHER, SteamHostStatusWatch.failureReason("failed setup"))
        assertEquals(FailureReason.OTHER, SteamHostStatusWatch.failureReason("failed start"))
        assertEquals(FailureReason.OTHER, SteamHostStatusWatch.failureReason("failed xyz"))
        assertEquals(FailureReason.OTHER, SteamHostStatusWatch.failureReason("failed"))
    }

    @Test
    fun launchStatusCode_parsesStatusWord() {
        assertEquals(9, SteamHostStatusWatch.launchStatusCode("failed launch status=9"))
        assertEquals(9, SteamHostStatusWatch.launchStatusCode(" failed sync status=9\n"))
        assertNull(SteamHostStatusWatch.launchStatusCode("failed launch"))
        assertNull(SteamHostStatusWatch.launchStatusCode("failed launch status=x"))
    }
}
