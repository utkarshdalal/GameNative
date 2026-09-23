package app.gamenative.texturepack

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TexturePackNetworkTest {

    @Test
    fun `unmetered network is allowed without the mobile data pref`() {
        assertTrue(TexturePackClient.transferAllowed(metered = false, allowMobileData = false))
    }

    @Test
    fun `metered network is refused without the mobile data pref`() {
        assertFalse(TexturePackClient.transferAllowed(metered = true, allowMobileData = false))
    }

    @Test
    fun `mobile data pref allows metered and unmetered networks`() {
        assertTrue(TexturePackClient.transferAllowed(metered = true, allowMobileData = true))
        assertTrue(TexturePackClient.transferAllowed(metered = false, allowMobileData = true))
    }

    @Test
    fun `worker network constraint follows the mobile data pref`() {
        assertEquals(NetworkType.UNMETERED, TexturePackSyncWorker.networkType(allowMobileData = false))
        assertEquals(NetworkType.CONNECTED, TexturePackSyncWorker.networkType(allowMobileData = true))
    }
}
