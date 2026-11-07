package app.gamenative.service

import app.gamenative.data.AppInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamInstallIdentityTest {
    @Test
    fun `install record only belongs to its matching app id`() {
        val installed = AppInfo(id = 2131120, isDownloaded = true)

        assertTrue(isOwnedSteamInstallRecord(2131120, installed))
        assertFalse(isOwnedSteamInstallRecord(108200, installed))
    }

    @Test
    fun `incomplete app info does not prove installation`() {
        val incomplete = AppInfo(id = 2131120, isDownloaded = false)

        assertFalse(isOwnedSteamInstallRecord(2131120, incomplete))
    }
}
