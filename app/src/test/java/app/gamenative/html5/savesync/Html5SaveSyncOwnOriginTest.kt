package app.gamenative.html5.savesync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// a page at its own origin keeps its storage there; the loopback-origin rewrite must leave it alone.
class Html5SaveSyncOwnOriginTest {

    @Test
    fun theRewriteIsSkippedOnlyForThePageRunningAtItsOwnOrigin() {
        val rewrite = SaveSyncStrategy.LevelDbOriginRewrite
        assertTrue(Html5SaveSyncService.skipsOwnOriginRewrite(rewrite, "STEAM_1", "STEAM_1"))
        assertFalse(Html5SaveSyncService.skipsOwnOriginRewrite(rewrite, "STEAM_2", "STEAM_1"))
        assertFalse(Html5SaveSyncService.skipsOwnOriginRewrite(rewrite, "STEAM_1", null))
        assertFalse(Html5SaveSyncService.skipsOwnOriginRewrite(SaveSyncStrategy.FsBridge, "STEAM_1", "STEAM_1"))
    }
}
