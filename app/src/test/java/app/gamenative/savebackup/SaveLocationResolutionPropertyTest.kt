package app.gamenative.savebackup

import android.os.Environment
import app.gamenative.PrefManager
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import com.pholser.junit.quickcheck.From
import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.generator.Size
import com.pholser.junit.quickcheck.generator.java.lang.Encoded
import com.pholser.junit.quickcheck.generator.java.lang.Encoded.InCharset
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
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith

/**
 * Property tests for [SaveLocationResolver] resolution — game-save-backup Task 2.3.
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration) and does NOT depend on Robolectric. [Container] is mocked with MockK so only
 * [Container.getRootDir] is exercised — the same root that `PathType.toAbsPath` reads — which keeps
 * these tests pure and free of the Android runtime bits in `Container`'s static initializers.
 *
 * Every `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class SaveLocationResolutionPropertyTest {

    private val tempRoots = mutableListOf<File>()

    @Before
    fun setUp() {
        // Referencing com.winlator.container.Container triggers its static initializer, which calls
        // Environment.getExternalStoragePublicDirectory (Container.DEFAULT_DRIVES). That method is
        // an unmocked Android stub in a plain (Robolectric-free) JVM unit test, so we stub it
        // statically to keep these property tests off Robolectric while still loading Container.
        mockkStatic(Environment::class)
        every { Environment.getExternalStoragePublicDirectory(any()) } returns File("/sdcard/Download")
    }

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
        unmockkStatic(Environment::class)
        unmockkObject(SteamService.Companion)
        unmockkObject(PrefManager)
    }

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

    /**
     * The supported roots whose resolution is a pure, deterministic join of `toAbsPath(root)` with
     * the relative subpath — i.e. every supported root except `SteamUserData`, which additionally
     * requires a resolvable Steam account id and is covered by the Property 3 tests below.
     */
    private val pureJoinRoots: List<PathType> =
        (SaveLocation.SUPPORTED_PATH_TYPES - PathType.SteamUserData).toList()

    private fun pickPureJoinRoot(selector: Int): PathType {
        val idx = Math.floorMod(selector, pureJoinRoots.size)
        return pureJoinRoots[idx]
    }

    // Feature: game-save-backup, Property 2: SaveLocation resolution is root-relative and
    // container-independent — for any SaveLocation and container root, resolving equals
    // PathType.toAbsPath(container, gameId, accountId) joined with the relative subpath; and the
    // stored value is never an absolute path.
    @Property(trials = 200)
    fun resolutionEqualsRootJoinedWithSubpath(
        @From(Encoded::class) @InCharset("UTF-8") rawSubpath: String,
        rootTypeSelector: Int,
        appId: Int,
    ) {
        val pathType = pickPureJoinRoot(rootTypeSelector)
        val saveLocation = SaveLocation(pathType, safeSubpath(rawSubpath))

        // (1.4) The stored value is never absolute: no leading separator, no drive prefix.
        assertSubpathIsRelative(saveLocation.relativeSubpath)

        val root = newTempDir("resolve-join")
        val container = containerWithRoot(root)

        val result = SaveLocationResolver.resolve(container, appId, saveLocation)
        assertTrue(
            "Expected a Resolved result for supported root $pathType, got $result",
            result is SaveLocationResult.Resolved,
        )
        val resolved = result as SaveLocationResult.Resolved

        // (1.3, 1.8) Resolution equals toAbsPath(root) joined with the (normalized) subpath;
        // an empty subpath yields the unmodified root.
        val rootAbs = pathType.toAbsPath(container, appId, 0L)
        val expected: Path =
            if (saveLocation.relativeSubpath.isEmpty()) {
                Paths.get(rootAbs)
            } else {
                Paths.get(rootAbs).resolve(saveLocation.relativeSubpath)
            }
        assertEquals(expected, resolved.absolutePath)

        // The result must not smuggle an absolute subpath back into the SaveLocation.
        assertSubpathIsRelative(resolved.saveLocation.relativeSubpath)
    }

    // Feature: game-save-backup, Property 2: SaveLocation resolution is root-relative and
    // container-independent — for any two container roots that differ only in their base directory,
    // resolution produces paths that differ only by that base.
    @Property(trials = 200)
    fun twoRootsDifferingOnlyInBaseDifferOnlyByBase(
        @From(Encoded::class) @InCharset("UTF-8") rawSubpath: String,
        rootTypeSelector: Int,
        appId: Int,
    ) {
        val pathType = pickPureJoinRoot(rootTypeSelector)
        val saveLocation = SaveLocation(pathType, safeSubpath(rawSubpath))

        // Two distinct base directories; each container root is <base>/container-shared so the two
        // roots differ ONLY in their base directory.
        val baseA = newTempDir("baseA")
        val baseB = newTempDir("baseB")
        val rootA = File(baseA, "container-shared").apply { mkdirs() }
        val rootB = File(baseB, "container-shared").apply { mkdirs() }

        val resultA = SaveLocationResolver.resolve(containerWithRoot(rootA), appId, saveLocation)
        val resultB = SaveLocationResolver.resolve(containerWithRoot(rootB), appId, saveLocation)

        assertTrue(resultA is SaveLocationResult.Resolved)
        assertTrue(resultB is SaveLocationResult.Resolved)
        val pathA = (resultA as SaveLocationResult.Resolved).absolutePath
        val pathB = (resultB as SaveLocationResult.Resolved).absolutePath

        // The resolved paths differ only by the base: stripping each container root prefix leaves
        // the identical remainder (Req 1.5 — a changed container root resolves correctly).
        val remainderA = pathA.toString().removePrefix(rootA.absolutePath)
        val remainderB = pathB.toString().removePrefix(rootB.absolutePath)
        assertEquals(remainderA, remainderB)

        // And rebasing A's path onto B's base reproduces B's path exactly.
        val rebased = baseB.absolutePath + pathA.toString().removePrefix(baseA.absolutePath)
        assertEquals(pathB.toString(), rebased)
    }

    // Feature: game-save-backup, Property 2 (stored value never absolute): for arbitrary input the
    // normalized relativeSubpath carries no leading separator and no drive-letter prefix.
    @Property(trials = 200)
    fun storedSubpathIsNeverAbsolute(
        @From(Encoded::class) @InCharset("UTF-8") @Size(min = 0, max = 64) rawSubpath: String,
        rootTypeSelector: Int,
    ) {
        val pathType = pickPureJoinRoot(rootTypeSelector)
        val saveLocation = SaveLocation(pathType, rawSubpath)
        assertSubpathIsRelative(saveLocation.relativeSubpath)
    }

    // Feature: game-save-backup, Property 3: Unresolvable Save_Location preserves stored state —
    // for any SaveLocation whose PathType is outside the supported set, resolution returns no
    // absolute path, reports Unresolved, and leaves the persisted value byte-for-byte unchanged.
    @Property(trials = 200)
    fun unsupportedPathTypeIsUnresolvedAndUnchanged(
        @From(Encoded::class) @InCharset("UTF-8") rawSubpath: String,
        unsupportedSelector: Int,
        appId: Int,
    ) {
        val unsupported = pickUnsupportedPathType(unsupportedSelector)
        val saveLocation = SaveLocation(unsupported, rawSubpath)

        // Snapshot the persisted value before resolution.
        val beforeType = saveLocation.pathType
        val beforeSubpath = saveLocation.relativeSubpath

        val root = newTempDir("unsupported")
        val result = SaveLocationResolver.resolve(containerWithRoot(root), appId, saveLocation)

        // No absolute path, reports Unresolved.
        assertTrue(
            "Expected Unresolved for unsupported PathType $unsupported, got $result",
            result is SaveLocationResult.Unresolved,
        )

        // Persisted value left byte-for-byte unchanged.
        assertEquals(beforeType, saveLocation.pathType)
        assertEquals(beforeSubpath, saveLocation.relativeSubpath)
    }

    // Feature: game-save-backup, Property 3: Unresolvable Save_Location preserves stored state —
    // for a SteamUserData root with no resolvable Steam account id (no logged-in id, no persisted
    // id, and no userdata/<id>/<appId> directory), resolution returns no absolute path, reports
    // Unresolved, and leaves the persisted value byte-for-byte unchanged.
    @Property(trials = 100)
    fun steamUserDataWithNoAccountIdIsUnresolvedAndUnchanged(
        @From(Encoded::class) @InCharset("UTF-8") rawSubpath: String,
        appId: Int,
    ) {
        // Stub the statics so no Steam account id can be resolved.
        mockkObject(SteamService.Companion)
        mockkObject(PrefManager)
        every { SteamService.userSteamId } returns null
        every { PrefManager.steamUserAccountId } returns 0

        // A container root with NO userdata directory guarantees no userdata/<id>/<appId> match.
        val root = newTempDir("steam-nouserdata")

        val saveLocation = SaveLocation(PathType.SteamUserData, rawSubpath)
        val beforeType = saveLocation.pathType
        val beforeSubpath = saveLocation.relativeSubpath

        val result = SaveLocationResolver.resolve(containerWithRoot(root), appId, saveLocation)

        assertTrue(
            "Expected Unresolved for SteamUserData with no account id, got $result",
            result is SaveLocationResult.Unresolved,
        )
        assertEquals(beforeType, saveLocation.pathType)
        assertEquals(beforeSubpath, saveLocation.relativeSubpath)
    }

    /**
     * Strip characters that are illegal in a filesystem path from a generated raw subpath. The
     * `@From(Encoded)` UTF-8 generator can emit control characters, NUL, and unpaired surrogates
     * that make `Paths.get(...)`/`File` throw `InvalidPathException` on some filesystems. These
     * properties test path *arithmetic* (root joined with a relative subpath), so restricting the
     * generated input to filesystem-legal characters is faithful to the property's intent while
     * keeping it deterministic across seeds.
     */
    private fun safeSubpath(raw: String): String =
        raw.filter { it.code >= 0x20 && it != '\u007F' && it !in "\u0000<>:\"|?*\\" && !it.isSurrogate() }

    private fun assertSubpathIsRelative(subpath: String) {
        assertFalse("subpath must not start with '/': '$subpath'", subpath.startsWith("/"))
        assertFalse("subpath must not start with '\\': '$subpath'", subpath.startsWith("\\"))
        // No Windows drive-letter prefix like "C:".
        val hasDrivePrefix = subpath.length >= 2 && subpath[1] == ':' && subpath[0].isLetter()
        assertFalse("subpath must not carry a drive prefix: '$subpath'", hasDrivePrefix)
    }

    private val unsupportedPathTypes: List<PathType> =
        PathType.values().filter { it !in SaveLocation.SUPPORTED_PATH_TYPES }

    private fun pickUnsupportedPathType(selector: Int): PathType {
        val idx = Math.floorMod(selector, unsupportedPathTypes.size)
        return unsupportedPathTypes[idx]
    }
}
