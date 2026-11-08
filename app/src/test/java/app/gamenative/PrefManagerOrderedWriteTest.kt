package app.gamenative

import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.awaitUntil
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `setDownloadSpeedOrdered` exists because the plain setter launches on Dispatchers.IO, where two
 * rapid slider gestures can complete out of order. It queues on the DataStore's single thread, so
 * a burst of selections must settle on the LAST value.
 */
class PrefManagerOrderedWriteTest {

    @Test
    fun rapid_download_speed_writes_settle_on_the_last_selection() {
        installFakePrefManager(FakeDataStore()).use {
            listOf(8, 32, 16, 100).forEach { PrefManager.setDownloadSpeedOrdered(it) }
            awaitUntil { PrefManager.downloadSpeed == 100 }

            // A second burst must also leave the burst's last value, not an earlier one.
            listOf(16, 8, 32).forEach { PrefManager.setDownloadSpeedOrdered(it) }
            awaitUntil { PrefManager.downloadSpeed == 32 }
            assertEquals(32, PrefManager.downloadSpeed)
        }
    }
}
