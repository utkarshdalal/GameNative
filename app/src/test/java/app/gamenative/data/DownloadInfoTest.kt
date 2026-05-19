package app.gamenative.data

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadInfoTest {
    @Test
    fun `post install sync state is tracked independently`() {
        val info = DownloadInfo(
            jobCount = 1,
            gameId = 123,
            downloadingAppIds = CopyOnWriteArrayList(),
        )

        assertFalse(info.isPostInstallSyncing())

        info.setPostInstallSyncing(true)

        assertTrue(info.isPostInstallSyncing())
    }

    @Test
    fun `cancel clears post install sync state`() {
        val info = DownloadInfo(
            jobCount = 1,
            gameId = 123,
            downloadingAppIds = CopyOnWriteArrayList(),
        )

        info.setPostInstallSyncing(true)
        info.cancel()

        assertFalse(info.isPostInstallSyncing())
        assertFalse(info.isActive())
    }
}
