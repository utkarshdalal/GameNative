package app.gamenative.utils.launchdependencies

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GameSource
import app.gamenative.service.gog.GOGDownloadManager
import app.gamenative.service.gog.GOGManifestUtils
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GogScriptInterpreterDependencyTest {
    private lateinit var context: Context
    private lateinit var container: Container
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
        mockkObject(GOGService.Companion)
        mockkObject(GOGManifestUtils)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun appliesTo_returnsFalse_whenSourceIsNotGog() {
        val result = GogScriptInterpreterDependency.appliesTo(container, GameSource.STEAM, 10)

        assertFalse(result)
    }

    @Test
    fun appliesTo_returnsFalse_whenInstallPathMissing() {
        every { GOGService.getInstallPath("11") } returns null

        val result = GogScriptInterpreterDependency.appliesTo(container, GameSource.GOG, 11)

        assertFalse(result)
    }

    @Test
    fun appliesTo_usesManifestFlag_whenGogInstallPathExists() {
        val installDir = tempFolder.newFolder("gog-dep-apply")
        every { GOGService.getInstallPath("12") } returns installDir.absolutePath
        every { GOGManifestUtils.needsScriptInterpreter(any()) } returns true

        val result = GogScriptInterpreterDependency.appliesTo(container, GameSource.GOG, 12)

        assertTrue(result)
    }

    @Test
    fun isSatisfied_returnsTrue_whenScriptInterpreterExists() {
        val installDir = tempFolder.newFolder("gog-dep-satisfied")
        val interpreter = File(installDir, "_CommonRedist/ISI/scriptinterpreter.exe")
        interpreter.parentFile?.mkdirs()
        interpreter.writeText("ok")
        every { GOGService.getInstallPath("13") } returns installDir.absolutePath

        val result = GogScriptInterpreterDependency.isSatisfied(context, container, GameSource.GOG, 13)

        assertTrue(result)
    }

    @Test
    fun getLoadingMessage_returnsExpectedMessage() {
        val message = GogScriptInterpreterDependency.getLoadingMessage(context, container, GameSource.GOG, 99)

        assertEquals("Downloading GOG script interpreter", message)
    }

    @Test
    fun install_downloadsIsi_andForwardsProgress() = runBlocking {
        val installDir = tempFolder.newFolder("gog-dep-install")
        every { GOGService.getInstallPath("14") } returns installDir.absolutePath

        val service = mockk<GOGService>()
        val downloadManager = mockk<GOGDownloadManager>()
        every { GOGService.getInstance() } returns service
        every { service.gogDownloadManager } returns downloadManager

        val onProgressSlot = slot<(Float) -> Unit>()
        coEvery {
            downloadManager.downloadDependenciesWithProgress(
                gameId = "redist",
                dependencies = listOf("ISI"),
                gameDir = any(),
                supportDir = any(),
                onProgress = capture(onProgressSlot),
            )
        } returns Result.success(Unit)

        val progressValues = mutableListOf<Float>()
        GogScriptInterpreterDependency.install(
            context = context,
            container = container,
            callbacks = LaunchDependencyCallbacks(
                setLoadingMessage = {},
                setLoadingProgress = { progressValues += it },
            ),
            gameSource = GameSource.GOG,
            gameId = 14,
        )

        onProgressSlot.captured(0.42f)

        coVerify(exactly = 1) {
            downloadManager.downloadDependenciesWithProgress(
                gameId = "redist",
                dependencies = listOf("ISI"),
                gameDir = File(installDir, "_CommonRedist"),
                supportDir = File(installDir, "_CommonRedist"),
                onProgress = any(),
            )
        }
        assertEquals(listOf(0.42f), progressValues)
    }

    @Test
    fun install_skips_whenServiceUnavailable() = runBlocking {
        val installDir = tempFolder.newFolder("gog-dep-no-service")
        every { GOGService.getInstallPath("15") } returns installDir.absolutePath
        every { GOGService.getInstance() } returns null

        try {
            GogScriptInterpreterDependency.install(
                context = context,
                container = container,
                callbacks = LaunchDependencyCallbacks({}, {}),
                gameSource = GameSource.GOG,
                gameId = 15,
            )
        } catch (e: Exception) {
            fail("Expected install to exit gracefully when service is unavailable, but threw: ${e.message}")
        }
    }
}
