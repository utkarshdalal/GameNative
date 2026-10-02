package app.gamenative.savebackup

import android.content.Context
import android.os.Environment
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import app.gamenative.utils.FakeDataStore
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith

/**
 * Property test for persist-only-if-resolvable — game-save-backup Task 17.2.
 *
 * Feature: game-save-backup, Property 16: Only resolvable roots are persisted (GameInstall safety).
 *
 * For any discovered root set mixing supported and unsupported [PathType]s, the
 * [SaveLocationResolutionService] persists only the roots that actually resolve to an absolute path;
 * a genuinely unsupported `PathType` is never persisted. A later resolve of the persisted value
 * therefore never reports `Unresolved` for a root that discovery accepted — closing the PR #1914
 * regression where an unresolvable (e.g. `GameInstall`-misclassified) root was persisted and then
 * rejected on every subsequent Export/Import.
 *
 * `GameInstall` itself is in the supported set (Req 14) and resolves, so this property uses a
 * genuinely unsupported `PathType` (e.g. `LinuxHome`) to represent the "must not persist" case, and
 * a supported Windows root for the "must persist" case.
 *
 * Runs under junit-quickcheck (Robolectric-free). Every `@Property` runs ≥100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class PersistOnlyResolvablePropertyTest {

    private val tempRoots = mutableListOf<File>()

    /** Supported Windows roots whose resolution is a pure container-root join (no account id). */
    private val supportedRoots: List<PathType> = listOf(
        PathType.WinMyDocuments,
        PathType.WinSavedGames,
        PathType.WinAppDataRoaming,
        PathType.WinAppDataLocal,
    )

    /** Unsupported path types (outside SaveRoot.SUPPORTED_PATH_TYPES) that must never persist. */
    private val unsupportedRoots: List<PathType> =
        PathType.values().filter { it !in SaveRoot.SUPPORTED_PATH_TYPES }

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

    // Feature: game-save-backup, Property 16: Only resolvable roots are persisted — a discovered
    // set mixing supported + unsupported roots persists only the resolvable (supported) ones, and
    // re-resolving the persisted value never reports Unresolved.
    @Property(trials = 200)
    fun onlyResolvableRootsArePersisted(
        appId: Int,
        supportedSelector: Int,
        unsupportedSelector: Int,
        subpathSeed: Int,
    ) = runBlocking {
        val gameId = Math.floorMod(appId, 1_000_000) + 1
        val storeAppId = "STEAM_$gameId"

        val supported = supportedRoots[Math.floorMod(supportedSelector, supportedRoots.size)]
        val unsupported = unsupportedRoots[Math.floorMod(unsupportedSelector, unsupportedRoots.size)]
        val subpath = "slot/${Math.floorMod(subpathSeed, 50)}"

        // A strategy that "discovers" one supported root (resolvable) and one unsupported root
        // (never resolvable). Discovery order is preserved; the supported root is second so we also
        // prove the service does not simply keep the first discovered root.
        val discovered = SaveLocation.of(
            listOf(
                SaveRoot(unsupported, subpath),
                SaveRoot(supported, subpath),
            ),
        )
        val strategy = object : SaveSourceStrategy {
            override fun resolveAutomatic(
                context: Context,
                container: Container,
                gameId: Int,
                intent: ResolveIntent,
            ): AutoResolveResult = AutoResolveResult.Found(discovered)
        }

        val store = DataStoreSaveLocationStore(FakeDataStore())
        val service = SaveLocationResolutionService(store) { strategy }

        val containerRootDir = newTempDir("persist-resolvable")
        val container = containerWithRoot(containerRootDir)

        val outcome = service.resolve(
            context = mockk<Context>(relaxed = true),
            container = container,
            gameSource = GameSource.STEAM,
            appId = storeAppId,
            gameId = gameId,
            intent = ResolveIntent.EXPORT,
        )

        // The outcome resolves (the supported root resolved).
        assertTrue("Expected Resolved, got $outcome", outcome is ResolveOutcome.Resolved)

        // The persisted value contains ONLY the resolvable (supported) root — never the unsupported
        // one.
        val persisted = store.get(storeAppId)
        assertTrue("something must be persisted", persisted != null)
        assertEquals(
            "only the resolvable supported root may be persisted",
            listOf(supported),
            persisted!!.roots.map { it.pathType },
        )

        // Re-resolving the persisted value never reports Unresolved for an accepted root.
        val reResolved = SaveLocationResolver.resolve(container, gameId, persisted)
        assertTrue(
            "re-resolving the persisted value must not report Unresolved, got $reResolved",
            reResolved is SaveLocationResult.Resolved,
        )
    }

    // Feature: game-save-backup, Property 16 (GameInstall is supported): a GameInstall root resolves
    // and is kept, not dropped. Uses a strategy that discovers only a GameInstall root.
    @Property(trials = 100)
    fun gameInstallRootResolvesAndIsKept(
        appId: Int,
        subpathSeed: Int,
    ) = runBlocking {
        val gameId = Math.floorMod(appId, 1_000_000) + 1
        val storeAppId = "STEAM_$gameId"
        val subpath = "profile/${Math.floorMod(subpathSeed, 50)}"

        // GameInstall.toAbsPath calls SteamService.getAppDirPath — stub it to a real temp dir so
        // resolution is deterministic and under the container.
        val containerRootDir = newTempDir("persist-gameinstall")
        val installDir = File(containerRootDir, "install").apply { mkdirs() }
        every { SteamService.getAppDirPath(gameId) } returns installDir.absolutePath

        val discovered = SaveLocation.of(listOf(SaveRoot(PathType.GameInstall, subpath)))
        val strategy = object : SaveSourceStrategy {
            override fun resolveAutomatic(
                context: Context,
                container: Container,
                gameId: Int,
                intent: ResolveIntent,
            ): AutoResolveResult = AutoResolveResult.Found(discovered)
        }

        val store = DataStoreSaveLocationStore(FakeDataStore())
        val service = SaveLocationResolutionService(store) { strategy }
        val container = containerWithRoot(containerRootDir)

        val outcome = service.resolve(
            context = mockk<Context>(relaxed = true),
            container = container,
            gameSource = GameSource.STEAM,
            appId = storeAppId,
            gameId = gameId,
            intent = ResolveIntent.EXPORT,
        )

        assertTrue("GameInstall must resolve, got $outcome", outcome is ResolveOutcome.Resolved)
        val persisted = store.get(storeAppId)
        assertEquals(
            "the GameInstall root must be persisted (not dropped as unsupported)",
            listOf(PathType.GameInstall),
            persisted?.roots?.map { it.pathType },
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
}
