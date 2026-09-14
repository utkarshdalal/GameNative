package app.gamenative.savebackup

import android.content.Context
import android.os.Environment
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Example test for [SteamSaveSourceStrategy] automatic resolution — game-save-backup Task 4.5.
 *
 * Covers the [AutoResolveResult.Unavailable] path (Requirement 2.5): when the game's UFS
 * `saveFilePatterns` cannot be retrieved AND the `SteamUserData` root cannot be resolved,
 * `resolveAutomatic` returns [AutoResolveResult.Unavailable] so the engine prompts the container
 * browser and reports that automatic resolution was unavailable.
 *
 * Plain JUnit 4 example test (no Robolectric). It reuses the exact stubbing approach from
 * [SteamAutoResolutionPropertyTest]: [Environment] statics are stubbed so referencing [Container]
 * stays off Robolectric, and [SteamService] / [PrefManager] are stubbed so no Steam account id can
 * be resolved. [SteamService.getAppInfoOf] returns null so there are no UFS Windows patterns, and
 * the container's root points at a temp directory with no `userdata` directory, so the
 * `SteamUserData` root cannot be resolved either.
 */
class SteamAutoResolutionUnavailableTest {

    private val tempRoots = mutableListOf<File>()
    private val strategy = SteamSaveSourceStrategy()

    @Before
    fun setUp() {
        // Referencing com.winlator.container.Container triggers its static initializer, which calls
        // Environment.getExternalStoragePublicDirectory. That is an unmocked Android stub in a
        // plain (Robolectric-free) JVM unit test, so stub it statically.
        mockkStatic(Environment::class)
        every { Environment.getExternalStoragePublicDirectory(any()) } returns File("/sdcard/Download")

        // No Steam account id can resolve: SteamService.userSteamId is null and the PrefManager
        // fallbacks are 0, so the SteamUserData root cannot be resolved.
        mockkObject(SteamService.Companion)
        every { SteamService.userSteamId } returns null
        mockkObject(PrefManager)
        every { PrefManager.steamUserAccountId } returns 0
        every { PrefManager.steamUserSteamId64 } returns 0L
    }

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
        unmockkObject(SteamService.Companion)
        unmockkObject(PrefManager)
        unmockkStatic(Environment::class)
    }

    /**
     * Requirement 2.5: UFS `saveFilePatterns` and the SteamUserData root both unavailable →
     * [AutoResolveResult.Unavailable].
     */
    @Test
    fun returnsUnavailableWhenNoUfsPatternsAndNoSteamUserDataRoot() {
        val gameId = 440

        // Container root is a fresh temp dir with NO userdata directory, so the SteamUserData
        // account-id probe finds nothing and resolveSteamAccountId returns null.
        val containerRootDir = newTempDir("steam-unavailable")
        val container = containerWithRoot(containerRootDir)

        // No app info => no UFS Windows saveFilePatterns.
        every { SteamService.getAppInfoOf(gameId) } returns null

        val result = strategy.resolveAutomatic(mockk<Context>(relaxed = true), container, gameId)

        assertTrue(
            "Expected Unavailable when UFS patterns and SteamUserData root cannot be retrieved, got $result",
            result is AutoResolveResult.Unavailable,
        )
    }

    // ---- helpers -----------------------------------------------------------

    /** A mocked container whose only exercised behavior is [Container.getRootDir]. */
    private fun containerWithRoot(root: File): Container {
        val container = mockk<Container>()
        every { container.rootDir } returns root
        return container
    }

    private fun newTempDir(prefix: String): File {
        val dir = Files.createTempDirectory(prefix).toFile()
        tempRoots += dir
        return dir
    }
}
