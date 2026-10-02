package app.gamenative.savebackup

import android.content.Context
import android.os.Environment
import app.gamenative.PrefManager
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith

/**
 * Property test for [SteamSaveSourceStrategy] IMPORT-intent resolution — game-save-backup Task 16.6.
 *
 * Feature: game-save-backup, Property 15: Steam import resolves restore targets without files
 * present.
 *
 * For any generated container whose Steam candidate roots are **empty** (no save files),
 * [ResolveIntent.IMPORT] resolution still returns [AutoResolveResult.Found] with the destination
 * roots derived from the game's Windows UFS `saveFilePatterns`, so a restore onto a freshly
 * installed game does not fall back to the browser (Req 2.6); and every resolved root uses a
 * supported [PathType] (Req 2.8).
 *
 * This is the IMPORT counterpart to Property 4 (which covers EXPORT, where a root must actually
 * hold a file). The contrast is the point: with the exact same empty container, EXPORT yields
 * [AutoResolveResult.NoSavesFound] while IMPORT yields [AutoResolveResult.Found].
 *
 * Runs under junit-quickcheck (no JUnit Platform migration), Robolectric-free; [Container] is
 * mocked so only [Container.getRootDir] is exercised. Every `@Property` runs ≥100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class SteamImportResolutionPropertyTest {

    private val tempRoots = mutableListOf<File>()
    private val strategy = SteamSaveSourceStrategy()

    private val candidateRoots: List<PathType> = listOf(
        PathType.WinMyDocuments,
        PathType.WinSavedGames,
        PathType.WinAppDataRoaming,
        PathType.WinAppDataLocal,
    )

    @Before
    fun setUp() {
        mockkStatic(Environment::class)
        every { Environment.getExternalStoragePublicDirectory(any()) } returns File("/sdcard/Download")
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

    // Feature: game-save-backup, Property 15: Steam import resolves restore targets without files
    // present — for an EMPTY container, IMPORT returns Found with the UFS-derived supported roots,
    // while EXPORT over the same empty container returns NoSavesFound.
    @Property(trials = 200)
    fun importResolvesWithoutFilesWhileExportDoesNot(
        appId: Int,
        rootCountSelector: Int,
    ) {
        val gameId = Math.floorMod(appId, 1_000_000) + 1
        val rootCount = Math.floorMod(rootCountSelector, candidateRoots.size) + 1
        val chosenRoots = candidateRoots.take(rootCount)

        val patterns = chosenRoots.map { root ->
            SaveFilePattern(root = root, path = "", pattern = "*", recursive = 5)
        }
        stubAppInfo(gameId, patterns)

        // A fresh, EMPTY container root — no save files materialized under any candidate root.
        val containerRootDir = newTempDir("steam-import-empty")
        val container = containerWithRoot(containerRootDir)

        // IMPORT: resolves restore targets from UFS metadata regardless of file presence (Req 2.6).
        val importResult = strategy.resolveAutomatic(
            mockk<Context>(relaxed = true),
            container,
            gameId,
            ResolveIntent.IMPORT,
        )
        assertTrue(
            "IMPORT must resolve restore targets even with no files present, got $importResult",
            importResult is AutoResolveResult.Found,
        )
        val found = importResult as AutoResolveResult.Found
        assertEquals(
            "IMPORT must resolve every chosen UFS root as a restore target",
            chosenRoots.toSet(),
            found.location.roots.map { it.pathType }.toSet(),
        )
        assertTrue(
            "every resolved root must use a supported PathType (Req 2.8)",
            found.location.roots.all { it.pathType in SaveRoot.SUPPORTED_PATH_TYPES },
        )

        // EXPORT over the SAME empty container finds nothing — the contrast that makes 2.6 matter.
        val exportResult = strategy.resolveAutomatic(
            mockk<Context>(relaxed = true),
            container,
            gameId,
            ResolveIntent.EXPORT,
        )
        assertTrue(
            "EXPORT over an empty container must find no saves, got $exportResult",
            exportResult is AutoResolveResult.NoSavesFound,
        )
    }

    // ---- helpers -----------------------------------------------------------

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

    private fun stubAppInfo(gameId: Int, patterns: List<SaveFilePattern>) {
        val app = mockk<SteamApp>()
        every { app.ufs } returns UFS(saveFilePatterns = patterns)
        every { SteamService.getAppInfoOf(gameId) } returns app
    }
}
