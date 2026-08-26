package app.gamenative.data

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DownloadInfoTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = tempFolder.newFolder()
    }

    @After
    fun cleanup() {
        testDir.deleteRecursively()
    }
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

    @Test
    fun `rapid persistence calls do not trigger multiple immediate writes`() = runBlocking {
        val info = DownloadInfo(
            jobCount = 1,
            gameId = 123,
            downloadingAppIds = CopyOnWriteArrayList(),
        )

        info.setTotalExpectedBytes(1000L)
        info.updateBytesDownloaded(100L)

        // First call should write immediately
        info.persistBytesDownloaded(testDir.absolutePath)
        delay(100)

        val firstValue = info.loadPersistedBytesDownloaded(testDir.absolutePath)
        assertEquals(100L, firstValue)

        // Rapid subsequent calls within debounce window
        info.updateBytesDownloaded(50L)
        info.persistBytesDownloaded(testDir.absolutePath)
        info.updateBytesDownloaded(50L)
        info.persistBytesDownloaded(testDir.absolutePath)
        info.updateBytesDownloaded(50L)
        info.persistBytesDownloaded(testDir.absolutePath)

        // Should still show first write value immediately
        val secondValue = info.loadPersistedBytesDownloaded(testDir.absolutePath)
        assertEquals(100L, secondValue)

        // Wait for debounced write to complete
        delay(11_000)

        // Now should show updated value
        val finalValue = info.loadPersistedBytesDownloaded(testDir.absolutePath)
        assertEquals(250L, finalValue)
    }

    @Test
    fun `clearPersistedBytesDownloaded cancels pending writes`() = runBlocking {
        val info = DownloadInfo(
            jobCount = 1,
            gameId = 123,
            downloadingAppIds = CopyOnWriteArrayList(),
        )

        info.setTotalExpectedBytes(1000L)
        info.updateBytesDownloaded(100L)

        // Write initial value
        info.persistBytesDownloaded(testDir.absolutePath)
        delay(100)

        val firstValue = info.loadPersistedBytesDownloaded(testDir.absolutePath)
        assertEquals(100L, firstValue)

        // Update and schedule a delayed write
        info.updateBytesDownloaded(200L)
        info.persistBytesDownloaded(testDir.absolutePath)

        // Clear the file before the delayed write executes
        info.clearPersistedBytesDownloaded(testDir.absolutePath)

        // File should be deleted
        val clearedValue = info.loadPersistedBytesDownloaded(testDir.absolutePath)
        assertEquals(0L, clearedValue)

        // Wait for what would have been the delayed write
        delay(11_000)

        // File should still be deleted (pending write was invalidated)
        val finalValue = info.loadPersistedBytesDownloaded(testDir.absolutePath)
        assertEquals(0L, finalValue)
    }

    @Test
    fun `completion during debounce interval prevents file recreation`() = runBlocking {
        val info = DownloadInfo(
            jobCount = 1,
            gameId = 123,
            downloadingAppIds = CopyOnWriteArrayList(),
        )

        info.setTotalExpectedBytes(1000L)
        info.updateBytesDownloaded(500L)

        // Write initial progress
        info.persistBytesDownloaded(testDir.absolutePath)
        delay(100)

        assertEquals(500L, info.loadPersistedBytesDownloaded(testDir.absolutePath))

        // Schedule another write within debounce window
        info.updateBytesDownloaded(200L)
        info.persistBytesDownloaded(testDir.absolutePath)

        // Download completes and clears the file
        info.clearPersistedBytesDownloaded(testDir.absolutePath)
        assertEquals(0L, info.loadPersistedBytesDownloaded(testDir.absolutePath))

        // Wait for the scheduled write delay
        delay(11_000)

        // File should remain deleted
        assertEquals(0L, info.loadPersistedBytesDownloaded(testDir.absolutePath))
    }
}
