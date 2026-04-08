package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.service.gog.GOGDownloadManager
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class GOGDependencyFixTest {
    private lateinit var context: Context
    private lateinit var container: Container

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
        mockkObject(GOGService.Companion)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun apply_returnsTrue_withoutDownloading_whenDependencyAlreadyInstalled() {
        val installDir = createTempDirectory(prefix = "gog-dep-fix-satisfied").toFile()
        val marker = File(installDir, "_CommonRedist/ISI/scriptinterpreter.exe")
        marker.parentFile?.mkdirs()
        marker.writeText("ok")
        val fix = GOGDependencyFix(GameSource.GOG, "123", listOf("ISI"))

        val result = fix.apply(context, "123", installDir.absolutePath, "A:\\", container)

        assertTrue(result)
        verify(exactly = 0) { GOGService.getInstance() }
    }

    @Test
    fun apply_returnsFalse_whenUnsatisfied_andServiceUnavailable() {
        val installDir = createTempDirectory(prefix = "gog-dep-fix-no-service").toFile()
        val fix = GOGDependencyFix(GameSource.GOG, "123", listOf("ISI"))
        every { GOGService.getInstance() } returns null

        val result = fix.apply(context, "123", installDir.absolutePath, "A:\\", container)

        assertFalse(result)
    }

    @Test
    fun apply_downloadsDependencies_whenUnsatisfied_andServiceAvailable() {
        val installDir = createTempDirectory(prefix = "gog-dep-fix-download").toFile()
        val fix = GOGDependencyFix(GameSource.GOG, "123", listOf("ISI"))
        val service = mockk<GOGService>()
        val downloadManager = mockk<GOGDownloadManager>()
        every { GOGService.getInstance() } returns service
        every { service.gogDownloadManager } returns downloadManager
        coEvery {
            downloadManager.downloadDependenciesWithProgress(
                gameId = "123",
                dependencies = listOf("ISI"),
                gameDir = installDir,
                supportDir = File(installDir, "_CommonRedist"),
                onProgress = null,
            )
        } returns Result.success(Unit)

        val result = fix.apply(context, "123", installDir.absolutePath, "A:\\", container)

        assertTrue(result)
        coVerify(exactly = 1) {
            downloadManager.downloadDependenciesWithProgress(
                gameId = "123",
                dependencies = listOf("ISI"),
                gameDir = installDir,
                supportDir = File(installDir, "_CommonRedist"),
                onProgress = null,
            )
        }
    }
}
