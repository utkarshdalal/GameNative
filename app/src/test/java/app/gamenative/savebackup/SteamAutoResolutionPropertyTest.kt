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
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith

/**
 * Property test for [SteamSaveSourceStrategy] automatic resolution — game-save-backup Task 4.4.
 *
 * Feature: game-save-backup, Property 4: Steam automatic resolution detects and persists non-empty
 * roots.
 *
 * For any generated container containing an arbitrary arrangement of files under Steam candidate
 * roots (UFS `saveFilePatterns`), automatic resolution returns [AutoResolveResult.Found] iff at
 * least one candidate root contains a **regular** (non-directory, non-symbolic-link) file;
 * otherwise it returns [AutoResolveResult.NoSavesFound].
 *
 * Persistence note: the strategy is discovery-only and never persists (persistence is the
 * resolver's job, covered by task 5.1's tests). The "persists nothing on NoSavesFound" clause of
 * Property 4 is therefore satisfied structurally — the strategy has no write path — so this test
 * asserts the strategy's Found-vs-NoSavesFound decision (Req 2.1, 2.2, 2.4). The Req 2.3 persist
 * step lives at the resolver layer.
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration) and does NOT depend on Robolectric. [Container] is mocked with MockK so only
 * [Container.getRootDir] is exercised (the same root `PathType.toAbsPath` reads), and the Android
 * [Environment] statics that `Container`'s initializer touches are stubbed statically to keep the
 * test off Robolectric. Every `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class SteamAutoResolutionPropertyTest {

    private val tempRoots = mutableListOf<File>()
    private val strategy = SteamSaveSourceStrategy()

    /**
     * The Windows candidate roots exercised here. All are in
     * [SaveLocation.SUPPORTED_PATH_TYPES], are Windows roots (so they survive the strategy's
     * `isWindows` filter), and are NOT `SteamUserData` (so no Steam account id is required — this
     * keeps the strategy off the `Unavailable` branch and off the userdata-account resolution
     * path). Using these roots means `toAbsPath` is a pure join of the container root with a
     * fixed suffix.
     */
    private val candidateRoots: List<PathType> = listOf(
        PathType.WinMyDocuments,
        PathType.WinSavedGames,
        PathType.WinAppDataRoaming,
        PathType.WinAppDataLocal,
    )

    @Before
    fun setUp() {
        // Referencing com.winlator.container.Container triggers its static initializer, which calls
        // Environment.getExternalStoragePublicDirectory. That is an unmocked Android stub in a
        // plain (Robolectric-free) JVM unit test, so stub it statically.
        mockkStatic(Environment::class)
        every { Environment.getExternalStoragePublicDirectory(any()) } returns File("/sdcard/Download")

        // The strategy probes the SteamUserData root unconditionally, which walks
        // SteamService.userSteamId and then PrefManager.steamUserAccountId. Stub both so no Steam
        // account id resolves: SteamUserData never becomes a candidate, keeping discovery to the
        // Win* patterns we control. (PrefManager's real getter hits an uninitialized DataStore in
        // a plain JVM unit test, so it must be stubbed.)
        mockkObject(SteamService.Companion)
        every { SteamService.userSteamId } returns null
        mockkObject(PrefManager)
        every { PrefManager.steamUserAccountId } returns 0
        // SaveFilePattern.substitutedPath eagerly calls SteamUtils.getSteamId64()/getSteam3AccountId(),
        // which fall through to these PrefManager properties when userSteamId is null. Stub them so
        // pattern-path substitution does not touch an uninitialized DataStore.
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

    // Feature: game-save-backup, Property 4: Steam automatic resolution detects and persists
    // non-empty roots — Found iff >=1 candidate root holds a regular (non-dir, non-symlink) file,
    // else NoSavesFound.
    @Property(trials = 200)
    fun foundIffAnyRootHoldsARegularFile(
        appId: Int,
        rootCountSelector: Int,
        arrangementSeed: Int,
        placeRegularSelector: Int,
        nonRegularKindSelector: Int,
    ) {
        val gameId = normalizedGameId(appId)

        // Pick 1..candidateRoots.size distinct Windows roots for this trial's saveFilePatterns.
        val rootCount = Math.floorMod(rootCountSelector, candidateRoots.size) + 1
        val chosenRoots = candidateRoots.take(rootCount)

        // Build the container's on-disk tree.
        val containerRootDir = newTempDir("steam-auto")
        val container = containerWithRoot(containerRootDir)

        // Stub the app info to expose exactly these roots as UFS save patterns. Each pattern uses
        // a plain path with a "*" glob and no {token} substitutions, so substitutedPath is inert.
        val patterns = chosenRoots.map { root ->
            SaveFilePattern(root = root, path = "", pattern = "*", recursive = 5)
        }
        stubAppInfo(gameId, patterns)

        // Decide, deterministically per trial, whether at least one root gets a REGULAR file.
        val placeRegularFile = Math.floorMod(placeRegularSelector, 2) == 0

        var expectedFound = false

        if (placeRegularFile) {
            // Put a regular file under one of the chosen roots -> must be Found.
            val targetRoot = chosenRoots[Math.floorMod(arrangementSeed, chosenRoots.size)]
            val rootDir = absRootDir(targetRoot, container, gameId)
            Files.createDirectories(rootDir)
            Files.write(rootDir.resolve("save_${Math.floorMod(arrangementSeed, 1000)}.dat"), byteArrayOf(1, 2, 3))
            expectedFound = true
        } else {
            // No regular files anywhere. Populate roots ONLY with non-regular entries (empty
            // dirs, nested dirs, or a symlink) to assert they do NOT count as save files.
            chosenRoots.forEachIndexed { index, root ->
                val rootDir = absRootDir(root, container, gameId)
                Files.createDirectories(rootDir)
                when (Math.floorMod(nonRegularKindSelector + index, 3)) {
                    0 -> {
                        // Only nested empty subdirectories — no regular files.
                        Files.createDirectories(rootDir.resolve("nested/deeper"))
                    }
                    1 -> {
                        // A directory that merely matches the "*" pattern by name, still a dir.
                        Files.createDirectories(rootDir.resolve("save.dat"))
                    }
                    else -> {
                        // A symlink pointing at a regular file OUTSIDE any candidate root. A
                        // symlink must not count as a regular save file. Guard against
                        // filesystems that disallow symlink creation.
                        val externalTarget = newTempDir("symlink-target").toPath().resolve("real.dat")
                        Files.write(externalTarget, byteArrayOf(9))
                        try {
                            Files.createSymbolicLink(rootDir.resolve("link.dat"), externalTarget)
                        } catch (e: Exception) {
                            // Symlinks unsupported here; fall back to an empty dir so this root
                            // still has no regular file.
                            Files.createDirectories(rootDir.resolve("nested"))
                        }
                    }
                }
            }
            expectedFound = false
        }

        val result = strategy.resolveAutomatic(mockk<Context>(relaxed = true), container, gameId)

        if (expectedFound) {
            assertTrue(
                "Expected Found because a regular file exists under a candidate root, got $result",
                result is AutoResolveResult.Found,
            )
            // The resolved SaveLocation must use a supported PathType.
            val found = result as AutoResolveResult.Found
            assertTrue(
                "Found SaveLocation must use a supported PathType, got ${found.location.pathType}",
                found.location.pathType in SaveLocation.SUPPORTED_PATH_TYPES,
            )
        } else {
            assertTrue(
                "Expected NoSavesFound because no candidate root holds a regular file, got $result",
                result is AutoResolveResult.NoSavesFound,
            )
        }
    }

    /**
     * A UFS pattern whose substituted path escapes its root via '..' must NOT crash resolution and
     * must NOT be returned as a candidate: SaveLocation's constructor rejects the escaping subpath,
     * and the strategy skips such a pattern. Even with a (regular) file physically present under
     * the escaping path, resolution reports NoSavesFound rather than throwing or resolving outside
     * the root.
     */
    @org.junit.Test
    fun unsafeParentTraversalPatternIsSkipped() {
        val gameId = 4242
        val containerRootDir = newTempDir("steam-auto-unsafe")
        val container = containerWithRoot(containerRootDir)

        // A single pattern whose path escapes the root. Its substitutedPath ("../escape") makes
        // SaveLocation(root, "../escape") throw; the strategy must catch that and skip the pattern.
        val patterns = listOf(
            SaveFilePattern(root = PathType.WinSavedGames, path = "../escape", pattern = "*", recursive = 5),
        )
        stubAppInfo(gameId, patterns)

        // Place a regular file where the (unsafe) pattern would look, so the only reason for
        // NoSavesFound is the skip — not the absence of files.
        val base = Paths.get(
            PathType.WinSavedGames.toAbsPath(container, gameId, 0L),
            "../escape",
        ).normalize()
        Files.createDirectories(base)
        Files.write(base.resolve("save.dat"), byteArrayOf(1, 2, 3))

        val result = strategy.resolveAutomatic(mockk<Context>(relaxed = true), container, gameId)

        assertTrue(
            "an escaping UFS pattern must be skipped, yielding NoSavesFound, got $result",
            result is AutoResolveResult.NoSavesFound,
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

    /** Absolute on-disk directory for a candidate root, mirroring the strategy's resolution. */
    private fun absRootDir(root: PathType, container: Container, gameId: Int): java.nio.file.Path {
        // The strategy joins toAbsPath(root) with the pattern's substitutedPath (empty here).
        return Paths.get(root.toAbsPath(container, gameId, 0L))
    }

    /** Stub SteamService.getAppInfoOf(gameId) to return a SteamApp with the given UFS patterns. */
    private fun stubAppInfo(gameId: Int, patterns: List<SaveFilePattern>) {
        // UFS is a simple data class; build a real one with the controlled patterns. SteamApp has
        // many required fields, so mock it and stub only the ufs accessor the strategy reads.
        val app = mockk<SteamApp>()
        every { app.ufs } returns UFS(saveFilePatterns = patterns)
        every { SteamService.getAppInfoOf(gameId) } returns app
    }

    /** Keep the game id small and non-negative so path segments stay well-formed. */
    private fun normalizedGameId(appId: Int): Int = Math.floorMod(appId, 1_000_000) + 1
}
